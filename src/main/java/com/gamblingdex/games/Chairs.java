package com.gamblingdex.games;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.blackjack.BlackjackTables;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Sillas (taburetes de casino) en los asientos de las mesas de póker y blackjack
 * (resource pack: resource_pack.custom_chairs). Click derecho = sentarse, shift = pararse.
 * Estar sentado en la silla de un asiento cuenta como estar en ese asiento.
 */
public class Chairs implements Listener {

    private static final String TAG = "gdx_chair", SEAT_TAG = "gdx_chair_seat";
    private static NamespacedKey seatKey;

    private final GamblingDexPlugin plugin;
    /** Asiento (clave del bloque) -> taburete + zona clickeable. */
    private final Map<String, UUID[]> chairs = new HashMap<>();
    private BukkitTask task;

    public Chairs(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        seatKey = new NamespacedKey(plugin, "chair_seat");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("resource_pack.custom_chairs", false)
                && plugin.getConfig().getBoolean("chairs.enabled", true);
    }

    public void start() {
        stop();
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(SEAT_TAG))
                    e.remove();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID[] ids : chairs.values())
            removeIds(ids);
        chairs.clear();
    }

    /** Clave del asiento (bloque) donde está sentado el jugador, o null. */
    public static String sittingKey(Player p) {
        if (p == null || seatKey == null)
            return null;
        Entity v = p.getVehicle();
        if (v == null || !v.getScoreboardTags().contains(SEAT_TAG))
            return null;
        return v.getPersistentDataContainer().get(seatKey, PersistentDataType.STRING);
    }

    /** ¿El jugador está sentado en la silla del asiento {@code seatBlock}? (lo usan las mesas) */
    public static boolean isSittingOn(Player p, Block seatBlock) {
        if (p == null || seatBlock == null || seatKey == null)
            return false;
        Entity v = p.getVehicle();
        if (v == null || !v.getScoreboardTags().contains(SEAT_TAG))
            return false;
        String k = v.getPersistentDataContainer().get(seatKey, PersistentDataType.STRING);
        return BlackjackTables.key(seatBlock.getLocation()).equals(k);
    }

    // ------------------------------------------------------------------

    /** Asientos de todas las mesas, con el centro de su mesa (para mirar hacia ella). */
    private Map<String, Location> seats() {
        Map<String, Location> out = new HashMap<>();
        if (plugin.getPokerManager() != null)
            for (var t : plugin.getPokerManager().getTables())
                for (String k : t.getSeatKeys())
                    out.put(k, t.getCenter());
        if (plugin.getBlackjackManager() != null)
            for (var t : plugin.getBlackjackManager().getTables())
                for (String k : t.getSeatKeys())
                    out.put(k, t.getCenter());
        return out;
    }

    private void tick() {
        Map<String, Location> seats = enabled() ? seats() : Map.of();
        for (Iterator<Map.Entry<String, UUID[]>> it = chairs.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, UUID[]> e = it.next();
            Location l = BlackjackTables.parseKey(e.getKey());
            boolean loaded = l != null && l.getWorld() != null
                    && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4);
            if (!seats.containsKey(e.getKey()) || !loaded || Bukkit.getEntity(e.getValue()[0]) == null) {
                removeIds(e.getValue());
                it.remove();
            }
        }
        for (Map.Entry<String, Location> e : seats.entrySet()) {
            if (chairs.containsKey(e.getKey()))
                continue;
            Location l = BlackjackTables.parseKey(e.getKey());
            if (l == null || l.getWorld() == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4))
                continue;
            chairs.put(e.getKey(), spawnChair(l, e.getValue()));
        }
        // Asientos vacíos (alguien se paró): se quitan
        for (World w : Bukkit.getWorlds())
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class))
                if (d.getScoreboardTags().contains(SEAT_TAG) && d.getPassengers().isEmpty())
                    d.remove();
    }

    /** Altura (desde el bloque registrado) de la superficie donde se para el jugador. */
    private static double surface(Block seat) {
        Block above = seat.getRelative(BlockFace.UP);
        if (!above.isPassable() && above.getBoundingBox().getHeight() > 0)
            return 1.0 + (above.getBoundingBox().getMaxY() - above.getY());
        double h = seat.getBoundingBox().getMaxY() - seat.getY();
        return h > 0 ? h : 1.0;
    }

    private UUID[] spawnChair(Location seat, Location tableCenter) {
        World w = seat.getWorld();
        Location at = seat.clone().add(0.5, surface(seat.getBlock()), 0.5);
        float yaw = tableCenter == null ? 0f : TableProps.yawFacing(tableCenter.clone().add(0.5, 0, 0.5)
                .toVector().subtract(at.toVector()).setY(0));
        float scale = (float) plugin.getConfig().getDouble("chairs.scale", 1.0);
        Location dl = at.clone().add(0, 0.5 * scale, 0);
        dl.setYaw(0);
        dl.setPitch(0);
        java.util.function.Consumer<ItemDisplay> setup = d -> {
            d.setItemStack(TableProps.cardItem(TableProps.CARD_CMD + 54));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY((float) -Math.toRadians(yaw)),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
        };
        ItemDisplay stool = w.spawn(dl, ItemDisplay.class, setup);
        Interaction box = w.spawn(at, Interaction.class);
        box.setInteractionWidth(0.9f * scale);
        box.setInteractionHeight(0.8f * scale);
        box.setResponsive(true);
        box.setPersistent(false);
        box.addScoreboardTag(TAG);
        return new UUID[] { stool.getUniqueId(), box.getUniqueId() };
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Interaction box)
                || !box.getScoreboardTags().contains(TAG))
            return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (p.isInsideVehicle() || p.isSneaking())
            return;
        for (Map.Entry<String, UUID[]> e : chairs.entrySet()) {
            if (!box.getUniqueId().equals(e.getValue()[1]))
                continue;
            sit(p, e.getKey(), box.getLocation());
            return;
        }
    }

    private void sit(Player p, String key, Location chairBase) {
        // ¿Ya hay alguien?
        for (Entity n : chairBase.getWorld().getNearbyEntities(chairBase, 0.4, 1.2, 0.4))
            if (n.getScoreboardTags().contains(SEAT_TAG) && !n.getPassengers().isEmpty()) {
                p.sendMessage(plugin.color("&7Esa silla está ocupada."));
                return;
            }
        UUID[] ids = chairs.get(key);
        Entity stool = ids == null ? null : Bukkit.getEntity(ids[0]);
        float yaw = stool instanceof ItemDisplay d ? yawOf(d) : p.getLocation().getYaw();
        double h = plugin.getConfig().getDouble("chairs.sit_height", 0.45) * plugin.getConfig().getDouble("chairs.scale", 1.0);
        Location at = chairBase.clone().add(0, h, 0);
        at.setYaw(yaw);
        at.setPitch(0);
        java.util.function.Consumer<ItemDisplay> setup = d -> {
            d.setPersistent(false);
            d.addScoreboardTag(SEAT_TAG);
            d.getPersistentDataContainer().set(seatKey, PersistentDataType.STRING, key);
        };
        ItemDisplay seat = chairBase.getWorld().spawn(at, ItemDisplay.class, setup);
        if (!seat.addPassenger(p))
            seat.remove();
        else
            p.setRotation(yaw, p.getLocation().getPitch());
    }

    private static float yawOf(ItemDisplay d) {
        Quaternionf q = d.getTransformation().getLeftRotation();
        // ángulo en Y del cuaternión (solo gira en Y)
        double ang = 2 * Math.atan2(q.y, q.w);
        return (float) -Math.toDegrees(ang);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Entity v = event.getPlayer().getVehicle();
        if (v != null && v.getScoreboardTags().contains(SEAT_TAG)) {
            v.eject();
            v.remove();
        }
    }

    private static void removeIds(UUID[] ids) {
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null)
                e.remove();
        }
    }
}
