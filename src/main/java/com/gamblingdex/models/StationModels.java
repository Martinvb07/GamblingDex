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
 * (models/slot_machine.bbmodel), el cajero de cambio (models/exchange_machine.bbmodel)
 * y la rueda de la ruleta (models/roulette_wheel.bbmodel).
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
        SLOTS(GameItemType.SLOTS, "games.slots.model", "slot_machine", 0.75, 1.0, 2.0),
        EXCHANGE(GameItemType.EXCHANGE, "exchange.model", "exchange_machine", 1.0, 1.0, 2.0),
        ROULETTE(GameItemType.ROULETTE, "roulette_world.model", "roulette_table", 1.0, 3.75, 0.95);

        final GameItemType type;
        final String path, defaultId;
        /** Altura del bloque de la estación (la máquina va encima si no se esconde). */
        final double blockHeight;
        /** Tamaño por defecto de la zona clickeable. */
        final double hitWidth, hitHeight;

        Kind(GameItemType type, String path, String defaultId, double blockHeight, double hitWidth, double hitHeight) {
            this.type = type;
            this.path = path;
            this.defaultId = defaultId;
            this.blockHeight = blockHeight;
            this.hitWidth = hitWidth;
            this.hitHeight = hitHeight;
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
    private BukkitTask syncTask, chipTask;
    private boolean meListener;

    private static final class Machine {
        final Location station;
        final Kind kind;
        ModelEngineBridge.Handle handle;
        UUID interaction;
        long spinStartTick = -1;
        BukkitTask pending;
        /** Ruleta: tamaño del modelo (la mesa se achica para caber dentro del anillo). */
        double scale = 1;
        /** Ruleta: más zonas clickeables (la mesa es larga) y las fichas apostadas sobre el paño. */
        final List<UUID> extraBoxes = new ArrayList<>();
        final Map<String, List<UUID>> chips = new HashMap<>();
        final Map<String, Long> chipAmounts = new HashMap<>();

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
        chipTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateChips, 60L, 10L);
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
        if (chipTask != null) {
            chipTask.cancel();
            chipTask = null;
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
            if (m.kind == Kind.ROULETTE)
                m.scale = rouletteScale(m);
            if (m.scale != 1 && !bridge.setScale(m.handle, m.scale))
                m.scale = 1;
        }
        if (m.kind == Kind.ROULETTE) {
            spawnTableBoxes(m, at);
            return;
        }
        if (m.interaction == null || Bukkit.getEntity(m.interaction) == null) {
            World w = at.getWorld();
            if (w == null)
                return;
            Interaction box = w.spawn(at, Interaction.class);
            box.setInteractionWidth((float) cfg(m.kind, "hitbox_width", m.kind.hitWidth));
            box.setInteractionHeight((float) cfg(m.kind, "hitbox_height", m.kind.hitHeight));
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
        removeAll(m.extraBoxes);
        for (List<UUID> ids : m.chips.values())
            removeAll(ids);
        m.chips.clear();
        m.chipAmounts.clear();
    }

    private static void removeAll(List<UUID> ids) {
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null)
                e.remove();
        }
        ids.clear();
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
        for (World w : Bukkit.getWorlds()) {
            for (Interaction i : w.getEntitiesByClass(Interaction.class))
                if (i.getScoreboardTags().contains(TAG))
                    i.remove();
            for (org.bukkit.entity.ItemDisplay d : w.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class))
                if (d.getScoreboardTags().contains(TAG))
                    d.remove();
        }
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
    // Mesa de ruleta: tamaño, zonas clickeables, casillas del tablero y fichas
    // Medidas del modelo (models/tools/roulette_table.js), en unidades (16 = 1 bloque):
    // la rueda en el origen y la mesa hacia +X de -30 a 122, Z de -30 a 30, paño a 12.4.
    // ------------------------------------------------------------------

    private static final double TABLE_X1 = -30, TABLE_X2 = 122, TABLE_HALF_Z = 30, FELT_Y = 12.4;

    /** La mesa se achica para quedar dentro del anillo de la ruleta (que sigue sirviendo para apostar). */
    private double rouletteScale(Machine m) {
        String cfgScale = plugin.getConfig().getString(Kind.ROULETTE.path + ".scale", "auto");
        try {
            if (cfgScale != null && !cfgScale.equalsIgnoreCase("auto"))
                return Math.max(0.2, Math.min(2.0, Double.parseDouble(cfgScale)));
        } catch (NumberFormatException ignored) {
        }
        var manager = plugin.getWorldRouletteManager();
        var table = manager == null ? null : manager.getByCenter(m.station.getBlock());
        int radius = table == null ? 6 : table.getRadius();
        double room = radius - 0.75; // bloques libres desde el centro hasta el anillo
        return Math.max(0.3, Math.min(1.0, room / (TABLE_X2 / 16.0)));
    }

    /** Pasa un punto del modelo (unidades, sin escalar) a coordenadas del mundo. */
    private Location tableToWorld(Machine m, Location base, double x, double y, double z) {
        double yaw = Math.toRadians(base.getYaw()), k = m.scale / 16.0;
        // El frente del modelo (-Z) mira hacia donde mira la entidad; +X queda a su derecha.
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw), fx = -Math.sin(yaw), fz = Math.cos(yaw);
        return base.clone().add((x * rx - z * fx) * k, y * k, (x * rz - z * fz) * k);
    }

    private void spawnTableBoxes(Machine m, Location base) {
        boolean ok = !m.extraBoxes.isEmpty();
        for (UUID id : m.extraBoxes)
            ok &= Bukkit.getEntity(id) != null;
        if (ok)
            return;
        removeAll(m.extraBoxes);
        World w = base.getWorld();
        if (w == null)
            return;
        double width = 2 * TABLE_HALF_Z, step = width;
        for (double x = TABLE_X1 + width / 2; x - width / 2 < TABLE_X2; x += step) {
            Location at = tableToWorld(m, base, Math.min(x, TABLE_X2 - width / 2), 0, 0);
            at.setYaw(0);
            Interaction box = w.spawn(at, Interaction.class);
            box.setInteractionWidth((float) (width * m.scale / 16.0));
            box.setInteractionHeight((float) ((FELT_Y + 2) * m.scale / 16.0));
            box.setResponsive(true);
            box.setPersistent(false);
            box.addScoreboardTag(TAG);
            m.extraBoxes.add(box.getUniqueId());
        }
    }

    /** Casilla del tablero en (x, z) del modelo: "N17", "RED", "DOZEN_1"... o null. */
    private static String cellAt(double x, double z) {
        if (z >= -12 && z < 12) {
            int row = z >= 4 ? 0 : z >= -4 ? 1 : 2;
            if (x >= 28 && x < 36)
                return z < 0 ? "N37" : "N0";
            if (x >= 36 && x < 108)
                return "N" + ((int) ((x - 36) / 6) * 3 + row + 1);
            if (x >= 108 && x < 116)
                return "COLUMN_" + (row + 1);
        }
        if (x >= 36 && x < 108 && z >= 12 && z < 18)
            return "DOZEN_" + ((int) ((x - 36) / 24) + 1);
        if (x >= 36 && x < 108 && z >= 18 && z < 24)
            return new String[] { "LOW", "EVEN", "RED", "BLACK", "ODD", "HIGH" }[(int) ((x - 36) / 12)];
        return null;
    }

    /** Centro de una casilla en el modelo (para poner las fichas). */
    private static double[] cellCenter(String cell) {
        if (cell.startsWith("N")) {
            int n = Integer.parseInt(cell.substring(1));
            if (n == 0)
                return new double[] { 32, 6 };
            if (n == 37)
                return new double[] { 32, -6 };
            int col = (n - 1) / 3, row = (n - 1) % 3;
            return new double[] { 39 + col * 6, 8 - row * 8 };
        }
        if (cell.startsWith("COLUMN_"))
            return new double[] { 112, 8 - (Integer.parseInt(cell.substring(7)) - 1) * 8 };
        if (cell.startsWith("DOZEN_"))
            return new double[] { 48 + (Integer.parseInt(cell.substring(6)) - 1) * 24, 15 };
        int i = List.of("LOW", "EVEN", "RED", "BLACK", "ODD", "HIGH").indexOf(cell);
        return i < 0 ? null : new double[] { 42 + i * 12, 21 };
    }

    /**
     * Click sobre la mesa: se mira dónde corta la vista del jugador el paño y se apuesta a
     * esa casilla (como el anillo: elige la casilla y, con fichas en la mano, apuesta).
     * Devuelve false si no apuntaba al tablero (entonces hace lo del centro de la mesa).
     */
    private boolean tableClick(Player p, Machine m) {
        var manager = plugin.getWorldRouletteManager();
        var table = manager == null ? null : manager.getByCenter(m.station.getBlock());
        if (table == null)
            return false;
        Location base = modelLocation(m);
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection();
        double planeY = base.getY() + FELT_Y * m.scale / 16.0;
        if (dir.getY() > -1e-3)
            return false;
        double t = (planeY - eye.getY()) / dir.getY();
        if (t < 0 || t > 8)
            return false;
        double dx = eye.getX() + dir.getX() * t - base.getX(), dz = eye.getZ() + dir.getZ() * t - base.getZ();
        double yaw = Math.toRadians(base.getYaw()), k = 16.0 / m.scale;
        double lx = (-dx * Math.cos(yaw) - dz * Math.sin(yaw)) * k;
        double lz = (dx * Math.sin(yaw) - dz * Math.cos(yaw)) * k;
        String cell = cellAt(lx, lz);
        if (cell == null)
            return false;
        if (plugin.getMaintenance() != null && !plugin.getMaintenance().allowTable(p, "ruleta", table.getTableKey()))
            return true;
        if (cell.startsWith("N")) {
            table.selectNumber(p, Integer.parseInt(cell.substring(1)));
        } else {
            var type = com.gamblingdex.games.rouletteworld.WorldRouletteBetType.valueOf(cell);
            table.setSelectionType(p, type);
            p.sendMessage(plugin.color("&eApuesta seleccionada: &f" + type.label()));
        }
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        Integer value = plugin.getTokenManager().getTokenValue(hand);
        if (value == null) {
            p.sendMessage(plugin.getMessages().getString("roulette_world.now_bet_with_tokens",
                    "&7Ahora apuesta con tokens (click derecho al centro o al número)."));
            return true;
        }
        int take = p.isSneaking() ? hand.getAmount() : 1;
        if (table.placeBet(p, (long) value * take)) {
            int left = hand.getAmount() - take;
            if (left <= 0)
                p.getInventory().setItemInMainHand(null);
            else
                hand.setAmount(left);
        }
        return true;
    }

    /** Fichas sobre el paño: un montón en cada casilla con apuestas (más alto cuanto más se apuesta). */
    private void updateChips() {
        var manager = plugin.getWorldRouletteManager();
        if (manager == null)
            return;
        for (Machine m : machines.values()) {
            if (m.kind != Kind.ROULETTE || m.handle == null)
                continue;
            var table = manager.getByCenter(m.station.getBlock());
            Map<String, Long> totals = table == null ? Map.of() : table.betTotalsByCell();
            for (Iterator<Map.Entry<String, List<UUID>>> it = m.chips.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, List<UUID>> e = it.next();
                Long now = totals.get(e.getKey());
                if (now == null || !now.equals(m.chipAmounts.get(e.getKey()))) {
                    removeAll(e.getValue());
                    it.remove();
                    m.chipAmounts.remove(e.getKey());
                }
            }
            Location base = modelLocation(m);
            for (Map.Entry<String, Long> e : totals.entrySet()) {
                if (m.chips.containsKey(e.getKey()))
                    continue;
                double[] c = cellCenter(e.getKey());
                if (c == null)
                    continue;
                m.chips.put(e.getKey(), spawnChips(m, base, c[0], c[1], e.getValue()));
                m.chipAmounts.put(e.getKey(), e.getValue());
            }
        }
    }

    private List<UUID> spawnChips(Machine m, Location base, double x, double z, long amount) {
        List<UUID> ids = new ArrayList<>();
        World w = base.getWorld();
        if (w == null)
            return ids;
        // De la ficha más grande que quepa en la apuesta, y más alto el montón cuanto más vale
        Material mat = Material.YELLOW_DYE;
        int best = 0;
        for (Map.Entry<Material, Integer> d : com.gamblingdex.economy.TokenManager.getDenoms().entrySet())
            if (d.getValue() <= amount && d.getValue() > best) {
                best = d.getValue();
                mat = d.getKey();
            }
        int count = (int) Math.max(1, Math.min(5, amount / Math.max(1, best)));
        org.bukkit.inventory.ItemStack item = plugin.getTokenManager().createToken(mat, 1);
        float s = (float) (0.42 * m.scale);
        for (int i = 0; i < count; i++) {
            Location at = tableToWorld(m, base, x, FELT_Y + 0.1 + i * 1.7, z);
            at.setYaw(base.getYaw() + i * 23);
            at.setPitch(0);
            org.bukkit.entity.ItemDisplay d = w.spawn(at, org.bukkit.entity.ItemDisplay.class);
            d.setItemStack(item);
            d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.GROUND);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                    new org.joml.AxisAngle4f(), new org.joml.Vector3f(s, s, s), new org.joml.AxisAngle4f()));
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            ids.add(d.getUniqueId());
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Animaciones: ruleta
    // ------------------------------------------------------------------

    /** Lo que dura la bola en caer en su casilla (la animación ball_N del modelo: 7 s). */
    public static final long ROULETTE_SPIN_TICKS = 140;

    /**
     * Lanza la bola de la rueda 3D de esta mesa para que caiga en {@code number}
     * (0..36; 37 = "00"), con su sonido para todos los que estén cerca. Devuelve false
     * si la mesa no tiene rueda 3D (entonces la ruleta usa la luz de siempre).
     */
    public boolean rouletteSpin(Location center, int number) {
        Machine m = machine(center, Kind.ROULETTE);
        if (m == null)
            return false;
        if (m.pending != null) {
            m.pending.cancel();
            m.pending = null;
        }
        for (int n = 0; n <= 37; n++)
            bridge.stop(m.handle, ballAnim(n));
        bridge.play(m.handle, ballAnim(number), 0, 0, true);

        Location at = m.station.clone().add(0.5, 1, 0.5);
        double radius = cfg(Kind.ROULETTE, "sound_radius", 16);
        if (plugin.getResourcePackManager() != null && plugin.getResourcePackManager().customSounds()) {
            com.gamblingdex.pack.CasinoPack.soundNear(at, null, "roulette.spin", org.bukkit.Sound.UI_BUTTON_CLICK,
                    1f, 1f, radius);
            return true;
        }
        // Sin el pack: tic-tic de la bola que va frenando y los golpes al caer
        m.pending = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int t = 0, next = 0;

            @Override
            public void run() {
                if (t >= next && t < 92) {
                    com.gamblingdex.pack.CasinoPack.soundNear(at, null, "", org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f,
                            1.9f - t / 120f, radius);
                    next = t + 2 + t / 12;
                }
                if (t == 106 || t == 115 || t == 126)
                    com.gamblingdex.pack.CasinoPack.soundNear(at, null, "", org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT,
                            0.8f, 1.6f, radius);
                if (++t > 130 && m.pending != null) {
                    m.pending.cancel();
                    m.pending = null;
                }
            }
        }, 1L, 1L);
        return true;
    }

    private static String ballAnim(int number) {
        return "ball_" + (number == 37 ? "00" : String.valueOf(number));
    }

    // ------------------------------------------------------------------
    // Clicks sobre el modelo
    // ------------------------------------------------------------------

    /** Click izquierdo sobre el modelo (a la entidad Interaction le llega como golpe). */
    @EventHandler(ignoreCancelled = true)
    public void onHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Interaction i) || !i.getScoreboardTags().contains(TAG)
                || !(event.getDamager() instanceof Player p))
            return;
        event.setCancelled(true);
        for (Machine m : machines.values())
            if (i.getUniqueId().equals(m.interaction) || m.extraBoxes.contains(i.getUniqueId())) {
                open(p, m);
                return;
            }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Interaction i)
                || !i.getScoreboardTags().contains(TAG))
            return;
        event.setCancelled(true);
        for (Machine m : machines.values())
            if (i.getUniqueId().equals(m.interaction) || m.extraBoxes.contains(i.getUniqueId())) {
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
            case ROULETTE -> {
                if (!tableClick(p, m))
                    openRoulette(p, m.station);
            }
        }
    }

    /** Igual que clickear el centro de la mesa: con fichas en la mano apuesta, si no abre el menú. */
    private void openRoulette(Player p, Location center) {
        var manager = plugin.getWorldRouletteManager();
        var table = manager == null ? null : manager.getByCenter(center.getBlock());
        if (table == null)
            return;
        if (plugin.getMaintenance() != null && !plugin.getMaintenance().allowTable(p, "ruleta", table.getTableKey()))
            return;
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        Integer value = plugin.getTokenManager().getTokenValue(hand);
        if (value == null) {
            new com.gamblingdex.gui.RouletteBetMenu(plugin).open(p, table);
            return;
        }
        int take = p.isSneaking() ? hand.getAmount() : 1;
        if (table.placeBet(p, (long) value * take)) {
            int left = hand.getAmount() - take;
            if (left <= 0)
                p.getInventory().setItemInMainHand(null);
            else
                hand.setAmount(left);
        }
    }

    /** Al apagar el plugin. */
    public void shutdown() {
        stop();
        HandlerList.unregisterAll(this);
    }
}
