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
        ROULETTE(GameItemType.ROULETTE, "roulette_world.model", "roulette_table", 1.0, 3.75, 0.95),
        /** Las mesas de póker no son estaciones: sus centros salen de PokerManager. */
        POKER(null, "poker.model", "poker_table", 1.0, 3.75, 1.0),
        /** La mesa de blackjack va delante del dealer (sus centros salen de BlackjackManager). */
        BLACKJACK(null, "blackjack.model", "blackjack_table", 1.0, 3.5, 1.0);

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
        /** Shulkers invisibles que le dan colisión al modelo (un bloque cada uno). */
        final List<UUID> colliders = new ArrayList<>();
        /** Bloques que cubren (si cambian los asientos con los comandos, se vuelven a poner). */
        String colliderCells = "";
        final Map<String, List<UUID>> chips = new HashMap<>();
        final Map<String, Long> chipAmounts = new HashMap<>();
        /** Ruleta: casillas ganadoras iluminadas sobre el paño. */
        final List<UUID> lit = new ArrayList<>();
        BukkitTask litTask;

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

    /** Centros de las estaciones de este tipo. */
    private List<Location> locationsFor(Kind kind) {
        if (kind.type != null)
            return plugin.getStationManager().locationsOf(kind.type);
        List<Location> out = new ArrayList<>();
        if (kind == Kind.POKER && plugin.getPokerManager() != null)
            for (var t : plugin.getPokerManager().getTables())
                if (t.getCenter() != null)
                    out.add(t.getCenter());
        if (kind == Kind.BLACKJACK && plugin.getBlackjackManager() != null)
            for (var t : plugin.getBlackjackManager().getTables())
                if (t.getCenter() != null)
                    out.add(t.getCenter());
        return out;
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
        // En el blackjack el dealer está parado sobre el bloque del centro: no se esconde
        String m = plugin.getConfig().getString(k.path + ".hide_station", k == Kind.BLACKJACK ? "never" : "auto");
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
     * Copia el .bbmodel de la máquina (va dentro del jar) a plugins/ModelEngine/blueprints/gamblingdex
     * (o lo actualiza si el plugin trae uno nuevo), para que el admin solo tenga que hacer /meg reload.
     */
    private void installBlueprint(Kind k) {
        if (!k.defaultId.equals(modelId(k)))
            return; // modelo propio del admin: no se toca
        String file = k.defaultId + ".bbmodel";
        java.io.File dir = new java.io.File(plugin.getDataFolder().getParentFile(), "ModelEngine/blueprints/gamblingdex");
        java.io.File out = new java.io.File(dir, file);
        java.io.File old = new java.io.File(dir.getParentFile(), file);
        if (old.exists())
            return; // el admin lo puso a mano fuera de la carpeta gamblingdex: no se toca
        try (java.io.InputStream in = plugin.getResource("models/" + file)) {
            if (in == null || (!dir.isDirectory() && !dir.mkdirs()))
                return;
            byte[] bundled = in.readAllBytes();
            // Los de la carpeta gamblingdex son del plugin: se actualizan si la versión nueva cambió
            if (out.exists() && java.util.Arrays.equals(bundled, java.nio.file.Files.readAllBytes(out.toPath())))
                return;
            boolean update = out.exists();
            java.nio.file.Files.write(out.toPath(), bundled);
            plugin.getLogger().info("[Modelos] Se " + (update ? "actualizó " : "copió ") + file
                    + " en plugins/ModelEngine/blueprints/gamblingdex."
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
            for (Location loc : locationsFor(kind)) {
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
                if (m != null && m.kind == kind && m.handle != null && !bridge.isRemoved(m.handle)) {
                    spawnColliders(m, modelLocation(m)); // por si cambiaron los asientos o se perdió alguno
                    continue;
                }
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
            else if (m.kind == Kind.POKER)
                m.scale = pokerScale(m);
            else if (m.kind == Kind.BLACKJACK)
                m.scale = blackjackScale(m);
            if (m.scale != 1 && !bridge.setScale(m.handle, m.scale))
                m.scale = 1;
        }
        spawnColliders(m, at);
        if (m.kind == Kind.ROULETTE) {
            spawnTableBoxes(m, at, TABLE_X1, TABLE_X2, TABLE_HALF_Z, FELT_Y + 2);
            return;
        }
        if (m.kind == Kind.POKER) {
            spawnTableBoxes(m, at, -54, 54, 30, 16.2);
            return;
        }
        if (m.kind == Kind.BLACKJACK) {
            // la mesa está delante del dealer: cajas a lo ancho, corridas hacia -Z
            Location front = tableToWorld(m, at, 0, 0, -32);
            front.setYaw(at.getYaw());
            spawnTableBoxes(m, front, -54, 54, 28, 16.2);
            return;
        }
        if (m.interaction == null || Bukkit.getEntity(m.interaction) == null) {
            World w = at.getWorld();
            if (w == null)
                return;
            Interaction box = w.spawn(at, Interaction.class);
            box.setInteractionWidth((float) cfg(m.kind, "hitbox_width", m.kind.hitWidth * m.scale));
            box.setInteractionHeight((float) cfg(m.kind, "hitbox_height", m.kind.hitHeight * m.scale));
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
        removeAll(m.colliders);
        clearLit(m);
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
        at.setYaw(m.kind == Kind.BLACKJACK ? blackjackYaw(m) : plugin.getStationManager().getModelYaw(m.station));
        at.setPitch(0);
        return at;
    }

    private void removeOrphanInteractions() {
        for (World w : Bukkit.getWorlds()) {
            for (Interaction i : w.getEntitiesByClass(Interaction.class))
                if (i.getScoreboardTags().contains(TAG))
                    i.remove();
            for (org.bukkit.entity.Display d : w.getEntitiesByClass(org.bukkit.entity.Display.class))
                if (d.getScoreboardTags().contains(TAG))
                    d.remove();
            for (org.bukkit.entity.Shulker sh : w.getEntitiesByClass(org.bukkit.entity.Shulker.class))
                if (sh.getScoreboardTags().contains(TAG))
                    sh.remove();
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
                var ring = ring(m);
                if (ring != null) { // ruleta: el anillo de bloques y sus números no se ven, solo la mesa
                    for (Map.Entry<Location, org.bukkit.block.data.BlockData> b : ring.entrySet())
                        p.sendBlockChange(b.getKey(), b.getValue());
                    for (UUID id : rouletteOf(m).getNumberDisplayIds()) {
                        Entity n = Bukkit.getEntity(id);
                        if (n != null)
                            p.hideEntity(plugin, n);
                    }
                }
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
        var ring = ring(m);
        if (ring != null) {
            for (Location l : ring.keySet())
                if (l.getWorld() == p.getWorld())
                    p.sendBlockChange(l, l.getBlock().getBlockData());
            for (UUID id : rouletteOf(m).getNumberDisplayIds()) {
                Entity n = Bukkit.getEntity(id);
                if (n != null)
                    p.showEntity(plugin, n);
            }
        }
    }

    /** ¿Se esconde el anillo de bloques de la ruleta? (roulette_world.model.hide_ring) */
    private boolean hideRing() {
        return plugin.getConfig().getBoolean(Kind.ROULETTE.path + ".hide_ring", true);
    }

    /** ¿Esta ruleta tiene la mesa 3D y su anillo de bloques escondido? (entonces no se apuesta en el anillo) */
    public boolean ringHidden(Location center) {
        return hideRing() && machine(center, Kind.ROULETTE) != null;
    }

    private com.gamblingdex.games.rouletteworld.WorldRouletteTable rouletteOf(Machine m) {
        var manager = plugin.getWorldRouletteManager();
        return manager == null ? null : manager.getByCenter(m.station.getBlock());
    }

    /** Bloques del anillo de una ruleta con lo que había antes de construirlo (el suelo). */
    private Map<Location, org.bukkit.block.data.BlockData> ring(Machine m) {
        if (m.kind != Kind.ROULETTE || !hideRing())
            return null;
        var table = rouletteOf(m);
        if (table == null)
            return null;
        Map<Location, org.bukkit.block.data.BlockData> out = new HashMap<>();
        for (Map.Entry<String, com.gamblingdex.games.rouletteworld.WorldRouletteTable.OriginalBlock> e : table
                .getOriginals().entrySet()) {
            if (e.getKey().equals(table.getTableKey()))
                continue; // el centro ya se esconde con la estación
            Location l = com.gamblingdex.games.rouletteworld.WorldRouletteTable.parseKey(e.getKey());
            if (l == null || table.getNumberForBlock(l.getBlock()) == null)
                continue;
            org.bukkit.block.data.BlockData data;
            try {
                data = Bukkit.createBlockData(e.getValue().getBlockData());
            } catch (Exception ex) {
                data = e.getValue().getType().createBlockData();
            }
            out.put(l, data);
        }
        return out;
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
        if (hideRing())
            return 1.0; // sin el anillo de bloques, la mesa va a su tamaño
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

    /** Zonas clickeables a lo largo de una mesa (X de x1 a x2, ancho 2*halfZ, en unidades del modelo). */
    private void spawnTableBoxes(Machine m, Location base, double x1, double x2, double halfZ, double height) {
        boolean ok = !m.extraBoxes.isEmpty();
        for (UUID id : m.extraBoxes)
            ok &= Bukkit.getEntity(id) != null;
        if (ok)
            return;
        removeAll(m.extraBoxes);
        World w = base.getWorld();
        if (w == null)
            return;
        double width = 2 * halfZ, step = width;
        for (double x = x1 + width / 2; x - width / 2 < x2; x += step) {
            Location at = tableToWorld(m, base, Math.min(x, x2 - width / 2), 0, 0);
            at.setYaw(0);
            Interaction box = w.spawn(at, Interaction.class);
            box.setInteractionWidth((float) (width * m.scale / 16.0));
            box.setInteractionHeight((float) (height * m.scale / 16.0));
            box.setResponsive(true);
            box.setPersistent(false);
            box.addScoreboardTag(TAG);
            m.extraBoxes.add(box.getUniqueId());
        }
    }

    // ------------------------------------------------------------------
    // Colisión: shulkers invisibles (cajas de 1 bloque) en la huella del modelo, para que no
    // se pueda atravesar. Al clickearlos pasa lo mismo que al clickear la máquina.
    // ------------------------------------------------------------------

    private boolean collision(Kind k) {
        return plugin.getConfig().getBoolean(k.path + ".collision", true);
    }

    private void spawnColliders(Machine m, Location at) {
        World w = at.getWorld();
        if (w == null || !collision(m.kind) || w.getDifficulty() == org.bukkit.Difficulty.PEACEFUL)
            return;
        List<org.bukkit.block.Block> cells = colliderCells(m, at);
        StringBuilder sig = new StringBuilder();
        for (org.bukkit.block.Block c : cells)
            sig.append(c.getX()).append(',').append(c.getY()).append(',').append(c.getZ()).append(';');
        boolean ok = !m.colliders.isEmpty() && sig.toString().equals(m.colliderCells);
        for (UUID id : m.colliders)
            ok &= Bukkit.getEntity(id) != null;
        if (ok)
            return;
        removeAll(m.colliders);
        m.colliderCells = sig.toString();
        for (org.bukkit.block.Block cell : cells) {
            org.bukkit.entity.Shulker sh = spawnCollider(cell.getLocation().add(0.5, 0, 0.5), TAG);
            if (sh != null)
                m.colliders.add(sh.getUniqueId());
        }
    }

    /** Un shulker invisible, quieto e invulnerable: una caja sólida de 1 bloque. */
    public static org.bukkit.entity.Shulker spawnCollider(Location at, String tag) {
        World w = at.getWorld();
        if (w == null)
            return null;
        java.util.function.Consumer<org.bukkit.entity.Shulker> setup = sh -> {
            sh.setAI(false);
            sh.setInvulnerable(true);
            sh.setSilent(true);
            sh.setGravity(false);
            sh.setInvisible(true);
            sh.setCollidable(false); // no empuja a nadie; igual es sólido
            sh.setPersistent(false);
            sh.setRemoveWhenFarAway(false);
            sh.setPeek(0);
            sh.addScoreboardTag(tag);
        };
        try {
            org.bukkit.entity.Shulker sh = w.spawn(at, org.bukkit.entity.Shulker.class, setup);
            return sh.isValid() ? sh : null;
        } catch (Throwable t) {
            return null; // otro plugin no deja aparecer mobs ahí
        }
    }

    /** Bloques que ocupa el modelo (al nivel del suelo de la máquina). */
    private List<org.bukkit.block.Block> colliderCells(Machine m, Location at) {
        List<org.bukkit.block.Block> out = new ArrayList<>();
        org.bukkit.block.Block foot = at.getBlock();
        if (m.kind == Kind.SLOTS || m.kind == Kind.EXCHANGE) {
            out.add(foot);
            out.add(foot.getRelative(org.bukkit.block.BlockFace.UP)); // la máquina mide 2 bloques
            return out;
        }
        // Mesas: el rectángulo del borde (unidades del modelo)
        double x1, x2, z1, z2;
        switch (m.kind) {
            case ROULETTE -> { x1 = TABLE_X1; x2 = TABLE_X2; z1 = -TABLE_HALF_Z; z2 = TABLE_HALF_Z; }
            case POKER -> { x1 = -54; x2 = 54; z1 = -30; z2 = 30; }
            default -> { x1 = -54; x2 = 54; z1 = -60; z2 = -3; } // blackjack: delante del dealer
        }
        Set<String> skip = new HashSet<>(); // asientos (y el dealer): ahí se para la gente
        if (m.kind == Kind.POKER) {
            var poker = plugin.getPokerManager() == null ? null : plugin.getPokerManager().getByBlock(m.station.getBlock());
            if (poker != null)
                for (String k : poker.getSeatKeys())
                    skip.add(column(com.gamblingdex.games.blackjack.BlackjackTables.parseKey(k)));
        }
        if (m.kind == Kind.BLACKJACK) {
            var bj = blackjack(m);
            if (bj != null)
                for (String k : bj.getSeatKeys())
                    skip.add(column(com.gamblingdex.games.blackjack.BlackjackTables.parseKey(k)));
            skip.add(column(m.station));
        }
        double yaw = Math.toRadians(at.getYaw()), k = m.scale / 16.0;
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw), fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double reach = Math.max(Math.max(Math.abs(x1), Math.abs(x2)), Math.max(Math.abs(z1), Math.abs(z2))) * k + 1;
        double margin = 2; // unidades: la caja no sobresale del borde
        for (int bx = (int) Math.floor(at.getX() - reach); bx <= (int) Math.floor(at.getX() + reach); bx++)
            for (int bz = (int) Math.floor(at.getZ() - reach); bz <= (int) Math.floor(at.getZ() + reach); bz++) {
                double dx = bx + 0.5 - at.getX(), dz = bz + 0.5 - at.getZ();
                double lx = (dx * rx + dz * rz) / k, lz = -(dx * fx + dz * fz) / k; // inverso de tableToWorld
                if (lx < x1 + margin || lx > x2 - margin || lz < z1 + margin || lz > z2 - margin)
                    continue;
                org.bukkit.block.Block b = foot.getWorld().getBlockAt(bx, foot.getY(), bz);
                if (skip.contains(column(b.getLocation())))
                    continue;
                boolean station = b.getX() == m.station.getBlockX() && b.getZ() == m.station.getBlockZ();
                if (!b.isPassable() && !station)
                    continue; // ya es sólido
                out.add(b);
            }
        return out;
    }

    private static String column(Location l) {
        return l == null ? "" : l.getBlockX() + "," + l.getBlockZ();
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
        // Apiladas pegadas sobre el paño (la vista GROUND baja la ficha: se sube con la traslación)
        double step = com.gamblingdex.games.TableProps.CHIP_STEP * s * 16.0 / m.scale;
        for (int i = 0; i < count; i++) {
            Location at = tableToWorld(m, base, x, FELT_Y + 0.05 + i * step, z);
            at.setYaw(base.getYaw() + i * 23);
            at.setPitch(0);
            org.bukkit.entity.ItemDisplay d = w.spawn(at, org.bukkit.entity.ItemDisplay.class);
            d.setItemStack(item);
            d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.GROUND);
            d.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(0, com.gamblingdex.games.TableProps.CHIP_BOTTOM * s, 0),
                    new org.joml.AxisAngle4f(), new org.joml.Vector3f(s, s, s), new org.joml.AxisAngle4f()));
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            ids.add(d.getUniqueId());
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Mesa de póker (models/tools/poker_table.js): rectangular, paño de 96 x 48 (x ±48,
    // z ±24) a 14 de alto y borde acolchado hasta x ±54 / z ±30. Las cartas y fichas las
    // pone PokerVisuals encima.
    // ------------------------------------------------------------------

    /** Dónde está el paño de una mesa de póker con modelo 3D (para poner cartas y fichas). */
    public record TableSurface(Location base, double feltY, double scale, double halfX, double halfZ) {
    }

    /**
     * La mesa crece o se achica (igual en todo) para que el borde quede medio bloque
     * delante del asiento más cercano.
     */
    private double pokerScale(Machine m) {
        String cfgScale = plugin.getConfig().getString(Kind.POKER.path + ".scale", "auto");
        try {
            if (cfgScale != null && !cfgScale.equalsIgnoreCase("auto"))
                return Math.max(0.3, Math.min(2.0, Double.parseDouble(cfgScale)));
        } catch (NumberFormatException ignored) {
        }
        var poker = plugin.getPokerManager() == null ? null : plugin.getPokerManager().getByBlock(m.station.getBlock());
        double yaw = Math.toRadians(plugin.getStationManager().getModelYaw(m.station));
        double ax = -Math.cos(yaw), az = -Math.sin(yaw), px = Math.sin(yaw), pz = -Math.cos(yaw); // ejes X y Z del modelo
        double best = 1.0; // más grande sería más alta: no se vería la mesa desde la silla
        boolean any = false;
        if (poker != null)
            for (Location seat : poker.visualState().seatLocations()) {
                if (seat == null || seat.getWorld() != m.station.getWorld())
                    continue;
                double dx = seat.getX() - m.station.getX(), dz = seat.getZ() - m.station.getZ(), dist = Math.hypot(dx, dz);
                if (dist < 0.5)
                    continue;
                double lx = Math.abs((dx * ax + dz * az) / dist), lz = Math.abs((dx * px + dz * pz) / dist);
                double edge = Math.min(lx < 1e-6 ? 1e9 : 54 / lx, lz < 1e-6 ? 1e9 : 30 / lz); // unidades hasta el borde
                best = Math.min(best, (dist - 0.5) * 16.0 / edge);
                any = true;
            }
        return any ? Math.max(0.4, best) : 1.0;
    }

    private com.gamblingdex.games.blackjack.BlackjackTable blackjack(Machine m) {
        return plugin.getBlackjackManager() == null ? null : plugin.getBlackjackManager().getByBlock(m.station.getBlock());
    }

    /** La mesa de blackjack mira hacia donde mira el dealer (o hacia los asientos). */
    private float blackjackYaw(Machine m) {
        var bj = blackjack(m);
        return bj == null ? 0f
                : com.gamblingdex.games.blackjack.BlackjackVisuals.facing(bj, null, m.station.clone().add(0.5, 1, 0.5));
    }

    /**
     * Mesa de blackjack rectangular (models/tools/blackjack_table.js): delante del dealer, con
     * el borde hasta x ±54 y z -60 (unidades). Crece o se achica para que el borde quede medio
     * bloque delante del asiento más cercano.
     */
    private double blackjackScale(Machine m) {
        String cfgScale = plugin.getConfig().getString(Kind.BLACKJACK.path + ".scale", "auto");
        try {
            if (cfgScale != null && !cfgScale.equalsIgnoreCase("auto"))
                return Math.max(0.3, Math.min(2.0, Double.parseDouble(cfgScale)));
        } catch (NumberFormatException ignored) {
        }
        var bj = blackjack(m);
        double yaw = Math.toRadians(blackjackYaw(m));
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw), fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double best = 1.0; // más grande sería más alta: no se vería la mesa desde la silla
        boolean any = false;
        if (bj != null)
            for (String key : bj.getSeatKeys()) {
                Location seat = com.gamblingdex.games.blackjack.BlackjackTables.parseKey(key);
                if (seat == null || seat.getWorld() != m.station.getWorld())
                    continue;
                double dx = seat.getX() - m.station.getX(), dz = seat.getZ() - m.station.getZ();
                double lx = Math.abs(dx * rx + dz * rz), front = dx * fx + dz * fz; // en bloques
                if (front < 0.5)
                    continue; // detrás o al lado del dealer
                // el asiento queda fuera del rectángulo (x ±54k, hasta 60k delante) con medio bloque libre
                double k = Math.max((lx - 0.5) / 54.0, (front - 0.5) / 60.0) * 16.0;
                best = Math.min(best, k);
                any = true;
            }
        return any ? Math.max(0.5, best) : 1.0;
    }

    public TableSurface blackjackSurface(Location center) {
        Machine m = machine(center, Kind.BLACKJACK);
        if (m == null)
            return null;
        Location base = modelLocation(m);
        return new TableSurface(base, base.getY() + 14.05 * m.scale / 16.0, m.scale, 48 * m.scale / 16.0,
                24 * m.scale / 16.0);
    }

    public TableSurface pokerSurface(Location center) {
        Machine m = machine(center, Kind.POKER);
        if (m == null)
            return null;
        Location base = modelLocation(m);
        return new TableSurface(base, base.getY() + 14.05 * m.scale / 16.0, m.scale, 48 * m.scale / 16.0,
                24 * m.scale / 16.0);
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
        clearLit(m);
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

    /**
     * La bola cayó: se iluminan en el paño todas las casillas que ganan con ese número
     * (el número, su color, par/impar, 1-18/19-36, la docena y la columna). Parpadean
     * unos segundos y se quedan encendidas hasta el siguiente giro.
     */
    public void rouletteResult(Location center, int number) {
        Machine m = machine(center, Kind.ROULETTE);
        if (m == null)
            return;
        clearLit(m);
        World w = m.station.getWorld();
        if (w == null)
            return;
        List<String> cells = new ArrayList<>();
        cells.add("N" + number);
        boolean red = com.gamblingdex.games.rouletteworld.WorldRouletteTables.isRed(number);
        for (var type : com.gamblingdex.games.rouletteworld.WorldRouletteBetType.values())
            if (type != com.gamblingdex.games.rouletteworld.WorldRouletteBetType.NUMBER && type.wins(number, null, red))
                cells.add(type.name());
        Location base = modelLocation(m);
        org.bukkit.block.data.BlockData glass = Material.YELLOW_STAINED_GLASS.createBlockData();
        for (String cell : cells) {
            double[] r = cellRect(cell);
            if (r == null)
                continue;
            Location a = tableToWorld(m, base, r[0] + 0.3, FELT_Y + 0.08, r[1] + 0.3);
            Location b = tableToWorld(m, base, r[2] - 0.3, FELT_Y + 0.08, r[3] - 0.3);
            Location at = new Location(w, Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()));
            org.bukkit.entity.BlockDisplay d = w.spawn(at, org.bukkit.entity.BlockDisplay.class);
            d.setBlock(glass);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f((float) Math.abs(a.getX() - b.getX()), 0.015f, (float) Math.abs(a.getZ() - b.getZ())),
                    new org.joml.AxisAngle4f()));
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(org.bukkit.Color.fromRGB(255, 210, 60));
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            m.lit.add(d.getUniqueId());
        }
        // Parpadeo los primeros 3 s
        m.litTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                boolean on = t >= 12 || t % 2 == 0;
                for (UUID id : m.lit)
                    if (Bukkit.getEntity(id) instanceof org.bukkit.entity.BlockDisplay d)
                        d.setBlock(on ? glass : Material.AIR.createBlockData());
                if (t >= 12 && m.litTask != null) {
                    m.litTask.cancel();
                    m.litTask = null;
                }
            }
        }, 5L, 5L);
    }

    private static void clearLit(Machine m) {
        if (m.litTask != null) {
            m.litTask.cancel();
            m.litTask = null;
        }
        removeAll(m.lit);
    }

    /** Rectángulo de una casilla en el modelo: {x1, z1, x2, z2} (como el dibujo del paño). */
    private static double[] cellRect(String cell) {
        if (cell.equals("N0"))
            return new double[] { 28, 0, 36, 12 };
        if (cell.equals("N37"))
            return new double[] { 28, -12, 36, 0 };
        if (cell.startsWith("N")) {
            int n = Integer.parseInt(cell.substring(1)), col = (n - 1) / 3, row = (n - 1) % 3;
            double x1 = 36 + col * 6, z1 = 4 - row * 8;
            return new double[] { x1, z1, x1 + 6, z1 + 8 };
        }
        if (cell.startsWith("COLUMN_")) {
            double z1 = 4 - (Integer.parseInt(cell.substring(7)) - 1) * 8;
            return new double[] { 108, z1, 116, z1 + 8 };
        }
        if (cell.startsWith("DOZEN_")) {
            double x1 = 36 + (Integer.parseInt(cell.substring(6)) - 1) * 24;
            return new double[] { x1, 12, x1 + 24, 18 };
        }
        int i = List.of("LOW", "EVEN", "RED", "BLACK", "ODD", "HIGH").indexOf(cell);
        return i < 0 ? null : new double[] { 36 + i * 12, 18, 48 + i * 12, 24 };
    }

    private static String ballAnim(int number) {
        return "ball_" + (number == 37 ? "00" : String.valueOf(number));
    }

    // ------------------------------------------------------------------
    // Clicks sobre el modelo
    // ------------------------------------------------------------------

    /** Click izquierdo sobre el modelo (a la entidad Interaction le llega como golpe). */
    @EventHandler(priority = EventPriority.HIGH) // aunque WorldGuard o la protección del spawn lo cancelen
    public void onHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        Entity i = event.getEntity();
        if (!(i instanceof Interaction || i instanceof org.bukkit.entity.Shulker) || !i.getScoreboardTags().contains(TAG))
            return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player p))
            return;
        Machine m = machineOf(i.getUniqueId());
        if (m != null)
            open(p, m);
    }

    /** La máquina a la que pertenece una zona clickeable o un bloque de colisión. */
    private Machine machineOf(UUID id) {
        for (Machine m : machines.values())
            if (id.equals(m.interaction) || m.extraBoxes.contains(id) || m.colliders.contains(id))
                return m;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH) // aunque WorldGuard o la protección del spawn lo cancelen
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity i = event.getRightClicked();
        if (!(i instanceof Interaction || i instanceof org.bukkit.entity.Shulker) || !i.getScoreboardTags().contains(TAG))
            return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND)
            return;
        Machine m = machineOf(i.getUniqueId());
        if (m != null)
            open(event.getPlayer(), m);
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
                    }, plugin, false); // también si otro plugin lo canceló
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
        if (m.kind.type != null && plugin.getStationManager().getStationType(m.station) != m.kind.type)
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
            case BLACKJACK -> {
                var bj = blackjack(m);
                if (bj != null && !bj.reopenMenu(p))
                    p.sendMessage(plugin.color("&7Párate en un asiento de la mesa para jugar."));
            }
            case POKER -> {
                var poker = plugin.getPokerManager() == null ? null
                        : plugin.getPokerManager().getByBlock(m.station.getBlock());
                if (poker != null)
                    poker.openMenu(p);
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
