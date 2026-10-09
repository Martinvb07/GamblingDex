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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Máquinas de slots en 3D con ModelEngine (models/slot_machine.bbmodel): pone el
 * modelo encima de cada estación de slots y lo anima cuando alguien gira desde
 * esa estación. Los rodillos paran en los símbolos que tocaron de verdad.
 *
 * <p>
 * Es opcional: sin ModelEngine (o con {@code model.enabled: false} en slots.yml)
 * las estaciones siguen funcionando como siempre.
 */
public class SlotsMachineModels implements Listener {

    /** Marca de las entidades Interaction que reciben los clicks sobre el modelo. */
    public static final String TAG = "gdx_slot_model";

    /**
     * Símbolos del modelo: cara K del rodillo R (0 = izquierda) muestra
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
    private BukkitTask syncTask;
    private boolean meListener;
    private int failedSpawns;

    private static final class Machine {
        final Location station;
        ModelEngineBridge.Handle handle;
        UUID interaction;
        long spinStartTick = -1;
        BukkitTask pending;

        Machine(Location station) {
            this.station = station;
        }
    }

    public SlotsMachineModels(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.bridge = new ModelEngineBridge(plugin.getLogger());
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    private boolean configEnabled() {
        return plugin.getConfig().getBoolean("games.slots.model.enabled", true);
    }

    private String modelId() {
        return plugin.getConfig().getString("games.slots.model.id", "slot_machine");
    }

    /** ¿Se están usando los modelos 3D? (ModelEngine instalado y activado en slots.yml) */
    public boolean active() {
        return configEnabled() && bridge.present();
    }

    /** Altura extra del holograma de la estación cuando el modelo está encima. */
    public double holoExtraHeight() {
        return active() ? plugin.getConfig().getDouble("games.slots.model.holo_extra_height", 2.1) : 0;
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    public void start() {
        stop();
        removeOrphanInteractions();
        if (!active()) {
            if (configEnabled() && Bukkit.getPluginManager().getPlugin("ModelEngine") == null)
                plugin.getLogger().info("[Modelos] ModelEngine no está instalado: las slots se ven como bloques.");
            return;
        }
        installBlueprint();
        registerModelEngineClicks();
        // ModelEngine carga los modelos un poco después de arrancar: se reintenta cada 2 s.
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sync, 40L, 40L);
        plugin.getLogger().info("[Modelos] ModelEngine detectado: máquinas de slots en 3D ('" + modelId() + "').");
    }

    /**
     * Copia slot_machine.bbmodel (va dentro del jar) a plugins/ModelEngine/blueprints
     * si aún no está, para que el admin solo tenga que hacer /meg reload.
     */
    private void installBlueprint() {
        if (!"slot_machine".equals(modelId()))
            return; // modelo propio del admin: no se toca
        java.io.File dir = new java.io.File(plugin.getDataFolder().getParentFile(), "ModelEngine/blueprints/gamblingdex");
        java.io.File out = new java.io.File(dir, "slot_machine.bbmodel");
        java.io.File old = new java.io.File(dir.getParentFile(), "slot_machine.bbmodel");
        if (out.exists() || old.exists())
            return;
        try (java.io.InputStream in = plugin.getResource("models/slot_machine.bbmodel")) {
            if (in == null || (!dir.isDirectory() && !dir.mkdirs()))
                return;
            java.nio.file.Files.copy(in, out.toPath());
            plugin.getLogger().info("[Modelos] Se copió slot_machine.bbmodel a plugins/ModelEngine/blueprints/gamblingdex."
                    + " Usa /meg reload para cargarlo (y /gdx pack para el resource pack).");
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[Modelos] No se pudo copiar slot_machine.bbmodel: " + e.getMessage());
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
        failedSpawns = 0;
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
        for (Location loc : sm.locationsOf(GameItemType.SLOTS)) {
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
            if (m != null && m.handle != null && !bridge.isRemoved(m.handle))
                continue;
            if (m == null) {
                m = new Machine(loc.clone());
                machines.put(key, m);
            }
            spawn(m);
        }
        // Estaciones que ya no existen
        for (Iterator<Map.Entry<String, Machine>> it = machines.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Machine> e = it.next();
            if (!alive.contains(e.getKey())) {
                despawn(e.getValue());
                it.remove();
            }
        }
    }

    private void spawn(Machine m) {
        Location at = modelLocation(m.station);
        if (m.handle == null || bridge.isRemoved(m.handle)) {
            m.handle = bridge.spawn(modelId(), at);
            if (m.handle == null) {
                if (++failedSpawns == 15) // ~30 s reintentando
                    plugin.getLogger().warning("[Modelos] ModelEngine no tiene el modelo '" + modelId()
                            + "'. Copia slot_machine.bbmodel a plugins/ModelEngine/blueprints y usa /meg reload.");
                return;
            }
            failedSpawns = 0;
            bridge.play(m.handle, "idle", 0, 0, false);
        }
        if (m.interaction == null || Bukkit.getEntity(m.interaction) == null) {
            World w = at.getWorld();
            if (w == null)
                return;
            Interaction box = w.spawn(at, Interaction.class);
            box.setInteractionWidth((float) plugin.getConfig().getDouble("games.slots.model.hitbox_width", 1.0));
            box.setInteractionHeight((float) plugin.getConfig().getDouble("games.slots.model.hitbox_height", 2.0));
            box.setResponsive(true);
            box.setPersistent(false);
            box.addScoreboardTag(TAG);
            m.interaction = box.getUniqueId();
        }
    }

    private void despawn(Machine m) {
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

    private Location modelLocation(Location station) {
        Location at = station.clone().add(0.5, plugin.getConfig().getDouble("games.slots.model.y_offset", 0.75), 0.5);
        at.setYaw(plugin.getStationManager().getModelYaw(station));
        at.setPitch(0);
        return at;
    }

    private void removeOrphanInteractions() {
        for (World w : Bukkit.getWorlds())
            for (Interaction i : w.getEntitiesByClass(Interaction.class))
                if (i.getScoreboardTags().contains(TAG))
                    i.remove();
    }

    // ------------------------------------------------------------------
    // Animaciones
    // ------------------------------------------------------------------

    /** Alguien tiró de la palanca en esta estación: palanca, luces y rodillos hasta el resultado. */
    public void spin(Location station, Material[] result) {
        Machine m = station == null ? null : machines.get(StationManager.key(station));
        if (m == null || m.handle == null || result == null || result.length < 3)
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
        Machine m = station == null ? null : machines.get(StationManager.key(station));
        if (m == null || m.handle == null || !win)
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
                open(event.getPlayer(), m.station);
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
                                    open(p, m.station);
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

    private void open(Player p, Location station) {
        long now = System.currentTimeMillis();
        Long last = lastClick.put(p.getUniqueId(), now);
        if (last != null && now - last < 300)
            return; // el mismo click llega por la Interaction y por ModelEngine
        if (plugin.getStationManager().getStationType(station) != GameItemType.SLOTS)
            return;
        if (plugin.getMaintenance() == null || plugin.getMaintenance().allow(p, "slots"))
            plugin.getSlotsController().open(p, plugin.getStationManager().getTheme(station), station);
    }

    /** Al apagar el plugin. */
    public void shutdown() {
        stop();
        HandlerList.unregisterAll(this);
    }
}
