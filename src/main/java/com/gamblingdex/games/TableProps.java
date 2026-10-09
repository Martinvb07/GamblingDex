package com.gamblingdex.games;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.games.blackjack.Card;
import com.gamblingdex.pack.CasinoPack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Cartas y fichas en 3D sobre las mesas (póker, blackjack...): ItemDisplay acostados con
 * los modelos del resource pack, que pueden llegar deslizándose y darse vuelta.
 */
public final class TableProps {

    /** Marca de los ItemDisplay de las mesas (se borran solos al arrancar). */
    public static final String TAG = "gdx_poker_visual";
    /** custom_model_data de las cartas en el pack: CARD + palo*13 + valor; +52 dorso; +53 botón. */
    public static final int CARD_CMD = 7800, BACK = CARD_CMD + 52, DEALER = CARD_CMD + 53;
    private static final int SLIDE_TICKS = 6, FLIP_TICKS = 5;
    /**
     * Con la vista GROUND el modelo queda centrado en la entidad: la carta (y 0..0.3 del
     * modelo, ground: subir 1, escala 0.5) queda 0.1875 * tamaño por debajo y la ficha
     * (ground: subir 1, escala 0.45) 0.1625 * tamaño. Se sube eso para que se apoyen en el paño.
     */
    private static final float CARD_BOTTOM = 0.1875f, CARD_THICK = 0.3f / 16 * 0.5f;
    public static final float CHIP_BOTTOM = 0.1625f;
    /** Grosor de una ficha (aro + marcas: 1.75 del modelo, escala 0.45) por tamaño: así se apilan pegadas. */
    public static final float CHIP_STEP = 1.75f / 16 * 0.45f;

    private final GamblingDexPlugin plugin;

    public TableProps(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    /** Displays de una cosa puesta y lo que muestran (para no rehacerlos si no cambió). */
    public static final class Placed {
        public final List<UUID> ids = new ArrayList<>();
        public String signature;
    }

    /** Cómo aparece algo: desde dónde se desliza (offset en el mundo) y si llega boca abajo y se da vuelta. */
    public record Motion(Vector slideFrom, boolean flip) {
        public static final Motion NONE = new Motion(null, false);
    }

    public static void removeOrphans() {
        for (World w : Bukkit.getWorlds())
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class))
                if (d.getScoreboardTags().contains(TAG))
                    d.remove();
    }

    public static int cmd(Card c) {
        return CARD_CMD + c.suit().ordinal() * 13 + c.rank().ordinal();
    }

    public static ItemStack cardItem(int cmd) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.setCustomModelData(cmd);
        it.setItemMeta(meta);
        return it;
    }

    /** Yaw de Minecraft que mira en esa dirección. */
    public static float yawFacing(Vector dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
    }

    /** Carta propia: boca arriba solo para el dueño (o para todos si shown), dorso para el resto. */
    public Placed privateCard(Location at, float yaw, int face, boolean shown, UUID owner, float scale, Motion mo) {
        if (shown || owner == null)
            return single(at, yaw, cardItem(face), scale, null, false, mo);
        Placed back = single(at, yaw, cardItem(BACK), scale, null, false, mo);
        Player o = Bukkit.getPlayer(owner);
        if (o != null)
            for (UUID id : back.ids) {
                Entity e = Bukkit.getEntity(id);
                if (e != null)
                    o.hideEntity(plugin, e);
            }
        back.ids.addAll(single(at, yaw, cardItem(face), scale, owner, false, mo).ids);
        return back;
    }

    /**
     * Un ItemDisplay acostado sobre la mesa. onlyFor != null: solo lo ve ese jugador.
     * El giro va en la transformación (no en el yaw de la entidad) para que el
     * deslizamiento sea en coordenadas del mundo.
     */
    public Placed single(Location at, float yaw, ItemStack item, float scale, UUID onlyFor, boolean chip, Motion mo) {
        Placed p = new Placed();
        World w = at.getWorld();
        if (w == null)
            return p;
        Location loc = at.clone();
        loc.setYaw(0);
        loc.setPitch(0);
        float sz = chip ? scale : 0.62f * scale;
        Vector3f size = new Vector3f(sz, sz, sz);
        Quaternionf rest = new Quaternionf().rotateY((float) -Math.toRadians(yaw));
        Quaternionf flipped = new Quaternionf(rest).rotateZ((float) Math.PI);
        Vector3f startMove = mo.slideFrom() == null ? new Vector3f()
                : new Vector3f((float) mo.slideFrom().getX(), 0.05f, (float) mo.slideFrom().getZ());
        // Apoyada en el paño: boca arriba se sube lo que la vista GROUND la baja; boca abajo
        // (girada 180° alrededor de la entidad) queda por encima y se baja.
        float bottom = chip ? CHIP_BOTTOM : CARD_BOTTOM;
        Vector3f restLift = new Vector3f(0, bottom * sz, 0);
        Vector3f lifted = new Vector3f(0, (CARD_THICK - bottom) * sz + 0.004f, 0);
        Vector3f start = new Vector3f(startMove).add(mo.flip() ? lifted : restLift);
        java.util.function.Consumer<ItemDisplay> setup = e -> {
            e.setItemStack(item);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            e.setTransformation(new Transformation(start, mo.flip() ? flipped : rest, size, new Quaternionf()));
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            if (onlyFor != null)
                e.setVisibleByDefault(false); // antes de aparecer: nadie más la ve ni un instante
        };
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, setup);
        if (onlyFor != null) {
            Player o = Bukkit.getPlayer(onlyFor);
            if (o != null)
                o.showEntity(plugin, d);
        }
        // Animación: primero se desliza (si viene de otro lado), después se da vuelta
        int at1 = 2;
        if (mo.slideFrom() != null) {
            Vector3f mid = mo.flip() ? lifted : restLift;
            Quaternionf rot = mo.flip() ? flipped : rest;
            Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d, mid, rot, size, SLIDE_TICKS), at1);
            at1 += SLIDE_TICKS + 1;
        }
        if (mo.flip())
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                animate(d, restLift, rest, size, FLIP_TICKS);
                if (d.isValid())
                    CasinoPack.soundNear(d.getLocation(), null, "poker.flip", Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.7f, 12);
            }, at1);
        p.ids.add(d.getUniqueId());
        return p;
    }

    public static void animate(ItemDisplay d, Vector3f move, Quaternionf rot, Vector3f size, int ticks) {
        if (!d.isValid())
            return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(move, rot, size, new Quaternionf()));
    }

    /** Montón de fichas: de la ficha más grande que quepa, más alto cuanto más vale (hasta 6). */
    public Placed chips(Location at, long amount, float scale, Motion mo) {
        Material mat = Material.YELLOW_DYE;
        int best = 0;
        for (Map.Entry<Material, Integer> d : TokenManager.getDenoms().entrySet())
            if (d.getValue() <= amount && d.getValue() > best) {
                best = d.getValue();
                mat = d.getKey();
            }
        int count = (int) Math.max(1, Math.min(6, amount / Math.max(1, best)));
        ItemStack item = plugin.getTokenManager().createToken(mat, 1);
        Placed all = new Placed();
        float size = 0.32f * scale;
        for (int i = 0; i < count; i++) {
            Location l = at.clone().add(0, i * CHIP_STEP * size, 0);
            all.ids.addAll(single(l, i * 23f, item, size, null, true, mo).ids);
        }
        return all;
    }

    /** Desliza lo que ya está puesto (offset en el mundo desde donde está) y lo quita al llegar. */
    public void slideAway(Placed p, Vector move, int ticks, long removeAfter) {
        for (UUID id : p.ids) {
            if (!(Bukkit.getEntity(id) instanceof ItemDisplay d))
                continue;
            Transformation t = d.getTransformation();
            Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d,
                    new Vector3f((float) move.getX(), t.getTranslation().y(), (float) move.getZ()),
                    t.getLeftRotation(), t.getScale(), ticks), 2L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> remove(p), removeAfter);
    }

    public static void remove(Placed p) {
        for (UUID id : p.ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null)
                e.remove();
        }
        p.ids.clear();
    }
}
