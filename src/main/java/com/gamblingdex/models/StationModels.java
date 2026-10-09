package com.gamblingdex.models;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.items.GameItemType;
import com.gamblingdex.stations.StationManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Máquinas en 3D con ModelEngine sobre las estaciones: la tragamonedas
 * (models/slot_machine.bbmodel) y el cajero de cambio (models/exchange_machine.bbmodel).
 * Para cada estación pone el modelo, recibe los clicks, esconde el bloque y el
 * holograma a quien ve el modelo y lo anima cuando alguien juega desde ahí.
 * Las animaciones van en el modelo compartido: las ven todos los que estén cerca.
 *
 * <p>
 * Es opcional: sin ModelEngine (o con {@code model.enabled: false}) las estaciones
 * siguen funcionando como siempre. Para una máquina nueva basta con añadirla a {@link Kind}.
 */
public class StationModels implements Listener {

    /** Marca de las entidades Interaction que reciben los clicks sobre el modelo. */
    public static final String TAG = "gdx_slot_model";

    /** Cada tipo de estación con máquina 3D: dónde está su config y su modelo por defecto. */
    public enum Kind {
        SLOTS(GameItemType.SLOTS, "games.slots.model", "slot_machine", 0.75),
        EXCHANGE(GameItemType.EXCHANGE, "exchange.model", "exchange_machine", 1.0);

        final GameItemType type;
        final String path, defaultId;
        /** Altura del bloque de la estación (la máquina va encima si no se esconde). */
        final double blockHeight;

        Kind(GameItemType type, String path, String defaultId, double blockHeight) {
            this.type = type;
            this.path = path;
            this.defaultId = defaultId;
            this.blockHeight = blockHeight;
        }

        static Kind of(GameItemType type) {
            for (Kind k : values())
                if (k.type == type)
                    return k;
            return null;
        }
    }

    /**
     * Símbolos de la tragamonedas: cara K del rodillo R (0 = izquierda) muestra
     * ORDER[(K + 3R) % 8]. Tiene que coincidir con models/tools/slot_machine.js.
     */
    private static final Material[] ORDER = { Material.DIAMOND, Material.GOLD_INGOT, Material.IRON_INGOT,
            Material.EMERALD, Material.NETHER_STAR, Material.GOLD_INGOT, Material.AMETHYST_SHARD, Material.IRON_INGOT };
    /** Tick (desde que se tira de la palanca) en que para el último rodillo: 2.2 s. */
    private static final long LAST_REEL_TICKS = 44;

    private final GamblingDexPlugin plugin;
    private final ModelEngineBridge bridge;
    private final Map<String, Machine> machines = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    /** Jugadores que cargaron el resource pack que manda el server. */
    private final Set<UUID> packLoaded = new HashSet<>();
    /** Estaciones a las que cada jugador no les ve el bloque ni el holograma (los tapa el modelo). */
    private final Map<UUID, Set<String>> hiddenFor = new HashMap<>();
    private final Map<Kind, Integer> failedSpawns = new EnumMap<>(Kind.class);
    private BukkitTask syncTask;
    private boolean meListener;

    private static final class Machine {
        final Location station;
        final Kind kind;
        ModelEngineBridge.Handle handle;
        UUID interaction;
        long spinStartTick = -1;
        BukkitTask pending;

        Machine(Location station, Kind kind) {
            this.station = station;
            this.kind = kind;
        }
    }

    public StationModels(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.bridge = new ModelEngineBridge(plugin.getLogger());
    }

    // ------------------------------------------------------------------
    // Config (slots.yml → model / config.yml → exchange.model)
    // ------------------------------------------------------------------

    private boolean configEnabled(Kind k) {
        return plugin.getConfig().getBoolean(k.path + ".enabled", true);
    }

    private String modelId(Kind k) {
        return plugin.getConfig().getString(k.path + ".id", k.defaultId);
    }

    private double cfg(Kind k, String key, double def) {
        return plugin.getConfig().getDouble(k.path + "." + key, def);
    }

    /** ¿Hay máquinas 3D de este tipo? (ModelEngine instalado y activado en la config) */
    public boolean active(GameItemType type) {
        Kind k = Kind.of(type);
        return k != null && configEnabled(k) && bridge.present();
    }

    /** Altura extra del holograma de la estación cuando el modelo está encima. */
    public double holoExtraHeight(GameItemType type) {
        Kind k = Kind.of(type);
        // Si el bloque y el holograma se esconden a quien ve el modelo, el holograma se queda
        // donde siempre para los demás.
        return k != null && active(type) && hideMode(k).equals("never") ? cfg(k, "holo_extra_height", 2.1) : 0;
    }

    /** auto | always | never (model.hide_station). */
    private String hideMode(Kind k) {
        String m = plugin.getConfig().getString(k.path + ".hide_station", "auto");
        m = m == null ? "auto" : m.toLowerCase(Locale.ROOT);
        return m.equals("always") || m.equals("never") ? m : "auto";
    }

    /**
     * ¿Este jugador ve el modelo 3D (tiene el pack)? Entonces no se le muestran el bloque de
     * la estación ni el holograma. auto: si el server no manda el pack (lo reparte otro
     * plugin o cada uno lo pone a mano) o lo manda obligatorio, a todos; si es opcional,
     * solo a los que lo aceptaron.
     */
    private boolean hidesFor(Player p, Kind k) {
        return switch (hideMode(k)) {
            case "always" -> true;
            case "never" -> false;
            default -> !plugin.getConfig().getBoolean("resource_pack.send.enabled", false)
                    || plugin.getConfig().getBoolean("resource_pack.send.required", false)
                    || packLoaded.contains(p.getUniqueId());
        };
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    public void start() {
        stop();
        removeOrphanInteractions();
        boolean any = false;
        for (Kind k : Kind.values())
            any |= configEnabled(k);
        if (!any)
            return;
        if (!bridge.present()) {
            if (Bukkit.getPluginManager().getPlugin("ModelEngine") == null)
                plugin.getLogger().info("[Modelos] ModelEngine no está instalado: las estaciones se ven como bloques.");
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Kind k : Kind.values())
            if (configEnabled(k)) {
                installBlueprint(k);
                ids.add(modelId(k));
            }
        registerModelEngineClicks();
        // ModelEngine carga los modelos un poco después de arrancar: se reintenta cada 2 s.
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sync, 40L, 40L);
        plugin.getLogger().info("[Modelos] ModelEngine detectado: máquinas en 3D " + ids + ".");
    }

    /**
     * Copia el .bbmodel de la máquina (va dentro del jar) a plugins/ModelEngine/blueprints
     * si aún no está, para que el admin solo tenga que hacer /meg reload.
     */
    private void installBlueprint(Kind k) {
        if (!k.defaultId.equals(modelId(k)))
            return; // modelo propio del admin: no se toca
        String file = k.defaultId + ".bbmodel";
        java.io.File dir = new java.io.File(plugin.getDataFolder().getParentFile(), "ModelEngine/blueprints/gamblingdex");
        java.io.File out = new java.io.File(dir, file);
        java.io.File old = new java.io.File(dir.getParentFile(), file);
        if (out.exists() || old.exists())
            return;
        try (java.io.InputStream in = plugin.getResource("models/" + file)) {
            if (in == null || (!dir.isDirectory() && !dir.mkdirs()))
                return;
            java.nio.file.Files.copy(in, out.toPath());
            plugin.getLogger().info("[Modelos] Se copió " + file + " a plugins/ModelEngine/blueprints/gamblingdex."
                    + " Usa /meg reload para cargarlo (y /gdx pack para el resource pack).");
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[Modelos] No se pudo copiar " + file + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        for (Machine m : machines.values())
            despawn(m);
        machines.clear();
        failedSpawns.clear();
    }

    /** Vuelve a crear el modelo de una estación (p. ej. después de girarla). */
    public void respawn(Location station) {
        Machine m = machines.remove(StationManager.key(station));
        if (m != null)
            despawn(m);
        sync();
    }

    private void sync() {
        StationManager sm = plugin.getStationManager();
        if (sm == null)
            return;
        Set<String> alive = new HashSet<>();
        for (Kind kind : Kind.values()) {
            if (!configEnabled(kind))
                continue;
            for (Location loc : sm.locationsOf(kind.type)) {
                World w = loc.getWorld();
                if (w == null)
                    continue;
                String key = StationManager.key(loc);
                boolean loaded = w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
                Machine m = machines.get(key);
                if (!loaded) {
                    if (m != null) {
                        despawn(m);
                        machines.remove(key);
                    }
                    continue;
                }
                alive.add(key);
                if (m != null && m.kind == kind && m.handle != null && !bridge.isRemoved(m.handle))
                    continue;
                if (m != null && m.kind != kind) { // la estación cambió de tipo
                    despawn(m);
                    m = null;
                }
                if (m == null) {
                    m = new Machine(loc.clone(), kind);
                    machines.put(key, m);
                }
                spawn(m);
            }
        }
        // Estaciones que ya no existen
        for (Iterator<Map.Entry<String, Machine>> it = machines.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Machine> e = it.next();
            if (!alive.contains(e.getKey())) {
                despawn(e.getValue());
                it.remove();
            }
        }
        updateHidden();
    }

    private void spawn(Machine m) {
        Location at = modelLocation(m);
        String id = modelId(m.kind);
        if (m.handle == null || bridge.isRemoved(m.handle)) {
            m.handle = bridge.spawn(id, at);
            if (m.handle == null) {
                if (failedSpawns.merge(m.kind, 1, Integer::sum) == 15) // ~30 s reintentando
                    plugin.getLogger().warning("[Modelos] ModelEngine no tiene el modelo '" + id + "'. Copia " + id
                            + ".bbmodel a plugins/ModelEngine/blueprints y usa /meg reload.");
                return;
            }
            failedSpawns.remove(m.kind);
            bridge.play(m.handle, "idle", 0, 0, false);
        }
        if (m.interaction == null || Bukkit.getEntity(m.interaction) == null) {
            World w = at.getWorld();
            if (w == null)
                return;
            Interaction box = w.spawn(at, Interaction.class);
            box.setInteractionWidth((float) cfg(m.kind, "hitbox_width", 1.0));
            box.setInteractionHeight((float) cfg(m.kind, "hitbox_height", 2.0));
            box.setResponsive(true);
            box.setPersistent(false);
            box.addScoreboardTag(TAG);
            m.interaction = box.getUniqueId();
        }
    }

    private void despawn(Machine m) {
        showToAll(m);
        if (m.pending != null) {
            m.pending.cancel();
            m.pending = null;
        }
        bridge.remove(m.handle);
        m.handle = null;
        if (m.interaction != null) {
            Entity e = Bukkit.getEntity(m.interaction);
            if (e != null)
                e.remove();
            m.interaction = null;
        }
    }

    private Location modelLocation(Machine m) {
        // Si el bloque se esconde, la máquina va en el suelo (ocupa su hueco); si no, encima de él.
        double y = hideMode(m.kind).equals("never") ? cfg(m.kind, "y_offset", m.kind.blockHeight)
                : cfg(m.kind, "y_offset_hidden", 0.0);
        Location at = m.station.clone().add(0.5, y, 0.5);
        at.setYaw(plugin.getStationManager().getModelYaw(m.station));
        at.setPitch(0);
        return at;
    }

    private void removeOrphanInteractions() {
        for (World w : Bukkit.getWorlds())
            for (Interaction i : w.getEntitiesByClass(Interaction.class))
                if (i.getScoreboardTags().contains(TAG))
                    i.remove();
    }

    private Machine machine(Location station, Kind kind) {
        Machine m = station == null ? null : machines.get(StationManager.key(station));
        return m == null || m.kind != kind || m.handle == null ? null : m;
    }

    // ------------------------------------------------------------------
    // Esconder el bloque de la estación y el holograma a quien ve el modelo
    // (solo en su pantalla: el bloque sigue en el mundo y la estación funciona igual).
    // Se repite cada 2 s porque el cliente vuelve a ver el bloque si se recarga el chunk.
    // ------------------------------------------------------------------

    private void updateHidden() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<String> was = hiddenFor.getOrDefault(p.getUniqueId(), Set.of());
            Set<String> now = new HashSet<>();
            for (Map.Entry<String, Machine> e : machines.entrySet()) {
                Machine m = e.getValue();
                if (m.handle == null || m.station.getWorld() != p.getWorld()
                        || m.station.distanceSquared(p.getLocation()) > 64 * 64 || !hidesFor(p, m.kind))
                    continue;
                now.add(e.getKey());
                p.sendBlockChange(m.station, Material.AIR.createBlockData());
                Entity holo = hologram(m);
                if (holo != null)
                    p.hideEntity(plugin, holo);
            }
            for (String key : was)
                if (!now.contains(key) && machines.get(key) != null)
                    show(p, machines.get(key));
            if (now.isEmpty())
                hiddenFor.remove(p.getUniqueId());
            else
                hiddenFor.put(p.getUniqueId(), now);
        }
    }

    private void show(Player p, Machine m) {
        if (m.station.getWorld() == p.getWorld())
            p.sendBlockChange(m.station, m.station.getBlock().getBlockData());
        Entity holo = hologram(m);
        if (holo != null)
            p.showEntity(plugin, holo);
    }

    /** Vuelve a mostrar el bloque y el holograma de esta máquina a todos los que no los veían. */
    private void showToAll(Machine m) {
        String key = StationManager.key(m.station);
        for (Map.Entry<UUID, Set<String>> e : hiddenFor.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && e.getValue().remove(key))
                show(p, m);
        }
    }

    private Entity hologram(Machine m) {
        UUID id = plugin.getStationManager().getHologramId(m.station);
        return id == null ? null : Bukkit.getEntity(id);
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> packLoaded.add(event.getPlayer().getUniqueId());
            case DECLINED, FAILED_DOWNLOAD -> packLoaded.remove(event.getPlayer().getUniqueId());
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        packLoaded.remove(event.getPlayer().getUniqueId());
        hiddenFor.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Animaciones: tragamonedas
    // ------------------------------------------------------------------

    /** Alguien tiró de la palanca en esta estación: palanca, luces y rodillos hasta el resultado. */
    public void spin(Location station, Material[] result) {
        Machine m = machine(station, Kind.SLOTS);
        if (m == null || result == null || result.length < 3)
            return;
        if (m.pending != null) {
            m.pending.cancel();
            m.pending = null;
        }
        m.spinStartTick = Bukkit.getCurrentTick();
        bridge.stop(m.handle, "win");
        bridge.play(m.handle, "spin", 0, 0.1, true);
        Material[] shown = mapToModel(result);
        for (int r = 0; r < 3; r++) {
            for (int k = 0; k < 8; k++)
                bridge.stop(m.handle, "reel" + (r + 1) + "_" + k);
            bridge.play(m.handle, "reel" + (r + 1) + "_" + face(r, shown[r]), 0, 0, true);
        }
    }

    /** Terminó el giro: si hubo premio, la máquina lo celebra cuando ya pararon los rodillos. */
    public void result(Location station, boolean win) {
        Machine m = machine(station, Kind.SLOTS);
        if (m == null || !win)
            return;
        long elapsed = m.spinStartTick < 0 ? LAST_REEL_TICKS : Bukkit.getCurrentTick() - m.spinStartTick;
        long delay = Math.max(1, LAST_REEL_TICKS + 2 - elapsed);
        m.pending = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            m.pending = null;
            if (m.handle != null)
                bridge.play(m.handle, "win", 0.05, 0.1, true);
        }, delay);
    }

    /**
     * Pasa los símbolos del resultado a símbolos que existen en el modelo (los temas
     * pueden usar otros ítems). Se mantienen las parejas y tríos: lo que se ve en la
     * máquina siempre "dice" lo mismo que el menú.
     */
    private static Material[] mapToModel(Material[] result) {
        Set<Material> inModel = new LinkedHashSet<>(Arrays.asList(ORDER));
        Map<Material, Material> sub = new HashMap<>();
        List<Material> free = new ArrayList<>(inModel);
        free.removeAll(Arrays.asList(result));
        Material[] out = new Material[3];
        for (int i = 0; i < 3; i++) {
            Material m = result[i];
            if (inModel.contains(m)) {
                out[i] = m;
                continue;
            }
            Material s = sub.get(m);
            if (s == null) {
                s = free.isEmpty() ? ORDER[i] : free.remove(0);
                sub.put(m, s);
            }
            out[i] = s;
        }
        return out;
    }

    /** Cara del rodillo r que muestra ese símbolo (si hay dos, una al azar). */
    private static int face(int r, Material symbol) {
        List<Integer> faces = new ArrayList<>(2);
        for (int k = 0; k < 8; k++)
            if (ORDER[(k + 3 * r) % 8] == symbol)
                faces.add(k);
        if (faces.isEmpty())
            return ThreadLocalRandom.current().nextInt(8);
        return faces.get(ThreadLocalRandom.current().nextInt(faces.size()));
    }

    // ------------------------------------------------------------------
    // Animaciones: cajero de cambio
    // ------------------------------------------------------------------

    /** Compra (caen fichas a la bandeja) o venta (entra un billete) en este cajero. */
    public void exchange(Location station, boolean buy) {
        Machine m = machine(station, Kind.EXCHANGE);
        if (m == null)
            return;
        bridge.play(m.handle, buy ? "buy" : "sell", 0, 0.1, true);
    }

    // ------------------------------------------------------------------
    // Clicks sobre el modelo
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Interaction i)
                || !i.getScoreboardTags().contains(TAG))
            return;
        event.setCancelled(true);
        for (Machine m : machines.values())
            if (i.getUniqueId().equals(m.interaction)) {
                open(event.getPlayer(), m);
                return;
            }
    }

    /** Clicks en el hitbox propio de ModelEngine (BaseEntityInteractEvent), por reflexión. */
    @SuppressWarnings("unchecked")
    private void registerModelEngineClicks() {
        if (meListener)
            return;
        try {
            var me = Bukkit.getPluginManager().getPlugin("ModelEngine");
            Class<?> cls = Class.forName("com.ticxo.modelengine.api.events.BaseEntityInteractEvent", true,
                    me.getClass().getClassLoader());
            Method getPlayer = cls.getMethod("getPlayer");
            Method getBase = cls.getMethod("getBaseEntity");
            Method getSlot = findMethod(cls, "getSlot");
            Method getAction = findMethod(cls, "getAction");
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) cls, this, EventPriority.NORMAL,
                    (listener, event) -> {
                        if (!cls.isInstance(event))
                            return;
                        try {
                            if (getSlot != null && getSlot.invoke(event) instanceof EquipmentSlot s
                                    && s != EquipmentSlot.HAND)
                                return;
                            if (getAction != null && String.valueOf(getAction.invoke(event)).contains("ATTACK"))
                                return;
                            Object base = getBase.invoke(event);
                            for (Machine m : machines.values())
                                if (m.handle != null && m.handle.base() == base
                                        && getPlayer.invoke(event) instanceof Player p) {
                                    open(p, m);
                                    return;
                                }
                        } catch (Throwable ignored) {
                        }
                    }, plugin, true);
            meListener = true;
        } catch (Throwable t) {
            // Versión de ModelEngine sin ese evento: quedan los clicks de la entidad Interaction.
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            return cls.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private void open(Player p, Machine m) {
        long now = System.currentTimeMillis();
        Long last = lastClick.put(p.getUniqueId(), now);
        if (last != null && now - last < 300)
            return; // el mismo click llega por la Interaction y por ModelEngine
        if (plugin.getStationManager().getStationType(m.station) != m.kind.type)
            return;
        switch (m.kind) {
            case SLOTS -> {
                if (plugin.getMaintenance() == null || plugin.getMaintenance().allow(p, "slots"))
                    plugin.getSlotsController().open(p, plugin.getStationManager().getTheme(m.station), m.station);
            }
            case EXCHANGE -> plugin.getExchangeMenu().open(p, m.station);
        }
    }

    /** Al apagar el plugin. */
    public void shutdown() {
        stop();
        HandlerList.unregisterAll(this);
    }
}
