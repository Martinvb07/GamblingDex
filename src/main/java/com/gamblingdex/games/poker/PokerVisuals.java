package com.gamblingdex.games.poker;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.games.blackjack.Card;
import com.gamblingdex.models.StationModels;
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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Cartas, fichas y botón del dealer en 3D sobre las mesas de póker (resource pack:
 * resource_pack.custom_cards). Mira el estado de cada mesa cada 4 ticks y pone o quita
 * lo que haga falta, con animación:
 * <ul>
 * <li>Cada carta sale de la bandeja del dealer y se desliza a su sitio.</li>
 * <li>Las de la mesa (flop, turn, river) llegan boca abajo y se dan vuelta.</li>
 * <li>Las 2 de cada jugador quedan delante de su asiento: él las ve boca arriba y los
 * demás el dorso; en el showdown se voltean para todos.</li>
 * <li>Las apuestas se deslizan desde el asiento; el bote queda junto a las cartas.</li>
 * <li>El botón del dealer delante del asiento que lo tiene.</li>
 * </ul>
 * Funciona sobre la mesa 3D (ModelEngine) o sobre la mesa de bloques de siempre.
 */
public class PokerVisuals {

    private static final String TAG = "gdx_poker_visual";
    /** custom_model_data de las cartas en el pack: CARD + palo*13 + valor; +52 dorso; +53 botón. */
    public static final int CARD_CMD = 7800, BACK = CARD_CMD + 52, DEALER = CARD_CMD + 53;
    private static final int SLIDE_TICKS = 6, FLIP_TICKS = 5;

    private final GamblingDexPlugin plugin;
    private final Map<String, TableView> views = new HashMap<>();
    private BukkitTask task;

    /** Lo que está puesto en una mesa: cada cosa con su clave ("board:0", "hole:3:1", "bet:2"...). */
    private static final class TableView {
        final Map<String, Placed> placed = new HashMap<>();
        int boardCount, holeCount;
        long betTotal;
        /** Ya se mandó el bote al ganador en esta mano. */
        boolean potSent;
    }

    /** Displays de una cosa puesta y lo que muestran (para no rehacerlos si no cambió). */
    private static final class Placed {
        final List<UUID> ids = new ArrayList<>();
        String signature;
    }

    /** Cómo aparece algo: desde dónde se desliza (offset en el mundo) y si llega boca abajo y se da vuelta. */
    private record Motion(Vector slideFrom, boolean flip) {
        static final Motion NONE = new Motion(null, false);
    }

    public PokerVisuals(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("resource_pack.custom_cards", false)
                && plugin.getConfig().getBoolean("poker.visuals.enabled", true);
    }

    public void start() {
        stop();
        removeOrphans();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 4L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TableView v : views.values())
            for (Placed p : v.placed.values())
                remove(p);
        views.clear();
    }

    private void removeOrphans() {
        for (World w : Bukkit.getWorlds())
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class))
                if (d.getScoreboardTags().contains(TAG))
                    d.remove();
    }

    // ------------------------------------------------------------------

    private void tick() {
        PokerManager manager = plugin.getPokerManager();
        if (manager == null)
            return;
        boolean on = enabled();
        Set<String> alive = new HashSet<>();
        for (PokerTable table : manager.getTables()) {
            Location c = table.getCenter();
            if (!on || c == null || c.getWorld() == null
                    || !c.getWorld().isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4))
                continue;
            alive.add(table.getTableKey());
            update(table, views.computeIfAbsent(table.getTableKey(), k -> new TableView()));
        }
        for (Iterator<Map.Entry<String, TableView>> it = views.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, TableView> e = it.next();
            if (!alive.contains(e.getKey())) {
                for (Placed p : e.getValue().placed.values())
                    remove(p);
                it.remove();
            }
        }
    }

    /**
     * Medidas de la mesa: centro, altura del paño, medio largo/ancho del paño (bloques),
     * ejes (along = fila de cartas, across = hacia la bandeja del dealer al revés) y escala.
     */
    private record Layout(Location center, double y, double halfX, double halfZ, Vector along, Vector across,
            double scale) {

        /** Distancia desde el centro hasta el borde del paño en esa dirección (horizontal, unitaria). */
        double edge(Vector dir) {
            double lx = Math.abs(dir.dot(along)), lz = Math.abs(dir.dot(across));
            return Math.min(lx < 1e-6 ? 1e9 : halfX / lx, lz < 1e-6 ? 1e9 : halfZ / lz);
        }

        /** Donde está la bandeja del dealer. */
        Vector dealer() {
            return across.clone().multiply(-halfZ * 0.8);
        }
    }

    private Layout layout(PokerTable table, PokerTable.VisualState st) {
        Location c = table.getCenter().clone().add(0.5, 0, 0.5);
        StationModels models = plugin.getStationModels();
        StationModels.TableSurface surf = models == null ? null : models.pokerSurface(table.getCenter());
        if (surf != null) {
            double yaw = Math.toRadians(surf.base().getYaw());
            // Ejes del modelo en el mundo: +X a la derecha del frente, +Z hacia atrás
            Vector along = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
            Vector across = new Vector(Math.sin(yaw), 0, -Math.cos(yaw));
            return new Layout(c, surf.feltY(), surf.halfX(), surf.halfZ(), along, across, surf.scale());
        }
        // Mesa de bloques: encima del bloque del centro, cuadrada hasta los asientos
        double avg = 0;
        int n = 0;
        for (Location s : st.seatLocations())
            if (s != null && s.getWorld() == c.getWorld()) {
                avg += Math.hypot(s.getX() + 0.5 - c.getX(), s.getZ() + 0.5 - c.getZ());
                n++;
            }
        double half = Math.max(1.2, (n == 0 ? 2.5 : avg / n) - 0.9);
        double top = table.getCenter().getBlock().getBoundingBox().getMaxY();
        if (top <= table.getCenter().getY())
            top = table.getCenter().getY() + 1;
        return new Layout(c, top + 0.01, half, half, new Vector(1, 0, 0), new Vector(0, 0, 1), 1);
    }

    private void update(PokerTable table, TableView view) {
        PokerTable.VisualState st = table.visualState();
        Layout lay = layout(table, st);
        Map<String, String> want = new LinkedHashMap<>(); // clave -> firma
        Map<String, Runnable> spawners = new HashMap<>();
        float s = (float) lay.scale();

        // Cartas de la mesa: salen del dealer boca abajo y se dan vuelta en su hueco
        List<Card> board = st.board();
        float boardYaw = yawFacing(lay.across()) + 180;
        for (int i = 0; i < board.size(); i++) {
            Vector off = lay.along().clone().multiply((i - 2) * 0.525 * s);
            Location at = point(lay, off, 0);
            int face = cmd(board.get(i));
            String key = "board:" + i;
            want.put(key, "c" + face);
            Motion mo = new Motion(lay.dealer().subtract(off), true);
            spawners.put(key, () -> view.placed.put(key, single(at, boardYaw, cardItem(face), s, null, false, mo)));
        }

        // Cartas de cada asiento, apuestas y botón
        int holeCount = 0;
        long betTotal = 0;
        for (int seat = 0; seat < st.seatLocations().size(); seat++) {
            Location sl = st.seatLocations().get(seat);
            if (sl == null || sl.getWorld() != lay.center().getWorld())
                continue;
            Vector toSeat = new Vector(sl.getX() + 0.5 - lay.center().getX(), 0, sl.getZ() + 0.5 - lay.center().getZ());
            if (toSeat.lengthSquared() < 1e-4)
                continue;
            toSeat.normalize();
            Vector side = new Vector(-toSeat.getZ(), 0, toSeat.getX());
            float yaw = yawFacing(toSeat);
            double edge = lay.edge(toSeat);
            UUID owner = st.owners().get(seat);

            List<Card> hole = st.hole().get(seat);
            if (hole != null) {
                boolean shown = st.revealed().contains(seat);
                for (int k = 0; k < hole.size(); k++) {
                    float cardYaw = yaw + (k == 0 ? -6 : 6);
                    holeCount++;
                    Vector off = toSeat.clone().multiply(edge * 0.72).add(side.clone().multiply((k == 0 ? -0.17 : 0.17) * s));
                    Location at = point(lay, off, k * 0.01);
                    String key = "hole:" + seat + ":" + k;
                    int face = cmd(hole.get(k));
                    want.put(key, (shown ? "up" : "own" + owner) + face);
                    // Repartida: se desliza desde el dealer. Mostrada en el showdown: se da vuelta ahí.
                    Motion mo = shown && view.placed.containsKey(key) ? new Motion(null, true)
                            : new Motion(lay.dealer().subtract(off), false);
                    spawners.put(key, () -> view.placed.put(key, holeCard(at, cardYaw, face, shown, owner, s, mo)));
                }
            }

            long bet = st.streetBets().getOrDefault(seat, 0L);
            if (bet > 0) {
                betTotal += bet;
                Vector off = toSeat.clone().multiply(edge * 0.45);
                Location at = point(lay, off, 0);
                String key = "bet:" + seat;
                want.put(key, "b" + bet);
                Motion mo = new Motion(toSeat.clone().multiply(edge * 0.4), false); // desde el jugador
                spawners.put(key, () -> view.placed.put(key, chips(at, bet, s, mo)));
            }

            if (seat == st.button()) {
                Vector off = toSeat.clone().multiply(edge * 0.6).add(side.clone().multiply(0.42 * s));
                Location at = point(lay, off, 0);
                want.put("button", "s" + seat);
                spawners.put("button", () -> view.placed.put("button",
                        single(at, yaw, cardItem(DEALER), 0.42f * s, null, true, Motion.NONE)));
            }
        }

        // Mano resuelta: las fichas del bote se deslizan hasta el (o los) ganadores
        if (st.winners().isEmpty())
            view.potSent = false;
        else if (!view.potSent) {
            view.potSent = true;
            Placed pot = view.placed.remove("pot");
            if (pot != null)
                sendPotToWinners(pot, lay, st);
            CasinoPack.soundNear(lay.center().clone().add(0, 1, 0), null, "poker.win", Sound.ENTITY_PLAYER_LEVELUP,
                    0.8f, 1.4f, 12);
        }

        // Bote (al lado de las cartas de la mesa, hacia el dealer)
        if (st.pot() > 0 && !view.potSent) {
            Location at = point(lay, lay.across().clone().multiply(-0.55 * s), 0);
            want.put("pot", "p" + st.pot());
            spawners.put("pot", () -> view.placed.put("pot", chips(at, st.pot(), s, Motion.NONE)));
        }

        // Quitar lo que ya no está o cambió, poner lo nuevo
        for (Iterator<Map.Entry<String, Placed>> it = view.placed.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Placed> e = it.next();
            if (!Objects.equals(want.get(e.getKey()), e.getValue().signature)) {
                // Las propias que se muestran en el showdown: el spawner ve que ya estaba (para darla vuelta)
                if (want.containsKey(e.getKey()) && e.getKey().startsWith("hole:"))
                    spawners.put(e.getKey(), showdownFlip(spawners.get(e.getKey()), e.getValue()));
                else
                    remove(e.getValue());
                it.remove();
            }
        }
        for (Map.Entry<String, String> e : want.entrySet())
            if (!view.placed.containsKey(e.getKey())) {
                spawners.get(e.getKey()).run();
                Placed p = view.placed.get(e.getKey());
                if (p != null)
                    p.signature = e.getValue();
            }

        // Sonidos para los que están cerca: carta nueva / fichas a la mesa
        Location snd = lay.center().clone().add(0, 1, 0);
        if (board.size() > view.boardCount || holeCount > view.holeCount)
            CasinoPack.soundNear(snd, null, "poker.card", Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f, 12);
        if (betTotal > view.betTotal)
            CasinoPack.soundNear(snd, null, "poker.chips", Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.6f, 1.6f, 12);
        view.boardCount = board.size();
        view.holeCount = holeCount;
        view.betTotal = betTotal;
    }

    /** Desliza cada ficha del bote hacia un ganador (si son varios, se reparten) y luego las quita. */
    private void sendPotToWinners(Placed pot, Layout lay, PokerTable.VisualState st) {
        List<Vector> targets = new ArrayList<>();
        for (int seat : st.winners().keySet()) {
            Location sl = seat < st.seatLocations().size() ? st.seatLocations().get(seat) : null;
            if (sl == null || sl.getWorld() != lay.center().getWorld())
                continue;
            Vector toSeat = new Vector(sl.getX() + 0.5 - lay.center().getX(), 0, sl.getZ() + 0.5 - lay.center().getZ());
            if (toSeat.lengthSquared() < 1e-4)
                continue;
            toSeat.normalize();
            targets.add(toSeat.multiply(lay.edge(toSeat) * 0.75));
        }
        Vector potPos = lay.across().clone().multiply(-0.55 * lay.scale());
        int i = 0;
        for (UUID id : pot.ids) {
            if (!(Bukkit.getEntity(id) instanceof ItemDisplay d) || targets.isEmpty())
                continue;
            Vector move = targets.get(i++ % targets.size()).clone().subtract(potPos);
            Transformation t = d.getTransformation();
            Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d,
                    new Vector3f((float) move.getX(), 0, (float) move.getZ()), t.getLeftRotation(), t.getScale(), 12), 2L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> remove(pot), 40L);
    }

    /** Showdown: se quita la carta de antes justo cuando aparece la nueva (que se da vuelta). */
    private static Runnable showdownFlip(Runnable spawn, Placed old) {
        return () -> {
            spawn.run();
            remove(old);
        };
    }

    // ------------------------------------------------------------------

    private static Location point(Layout lay, Vector offset, double lift) {
        Location at = lay.center().clone().add(offset);
        at.setY(lay.y() + 0.005 + lift);
        return at;
    }

    /** Yaw de Minecraft que mira en esa dirección. */
    private static float yawFacing(Vector dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
    }

    private static int cmd(Card c) {
        return CARD_CMD + c.suit().ordinal() * 13 + c.rank().ordinal();
    }

    private static ItemStack cardItem(int cmd) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.setCustomModelData(cmd);
        it.setItemMeta(meta);
        return it;
    }

    /** Carta propia: boca arriba solo para el dueño (o para todos en el showdown), dorso para el resto. */
    private Placed holeCard(Location at, float yaw, int face, boolean shown, UUID owner, float scale, Motion mo) {
        if (shown || owner == null)
            return single(at, yaw, cardItem(face), scale, null, false, mo);
        Placed back = single(at, yaw, cardItem(BACK), scale, null, false, mo);
        Player o = Bukkit.getPlayer(owner);
        if (o != null)
            for (UUID id : back.ids)
                if (Bukkit.getEntity(id) instanceof Entity e)
                    o.hideEntity(plugin, e);
        back.ids.addAll(single(at, yaw, cardItem(face), scale, owner, false, mo).ids);
        return back;
    }

    /**
     * Un ItemDisplay acostado sobre la mesa. onlyFor != null: solo lo ve ese jugador.
     * El giro va en la transformación (no en el yaw de la entidad) para que el
     * deslizamiento sea en coordenadas del mundo.
     */
    private Placed single(Location at, float yaw, ItemStack item, float scale, UUID onlyFor, boolean chip, Motion mo) {
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
        Vector3f lifted = new Vector3f(0, 0.06f * scale, 0); // boca abajo un poco más alta: no se mete en el paño
        Vector3f start = new Vector3f(startMove).add(mo.flip() ? lifted : new Vector3f());
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
            Vector3f mid = mo.flip() ? lifted : new Vector3f();
            Quaternionf rot = mo.flip() ? flipped : rest;
            Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d, mid, rot, size, SLIDE_TICKS), at1);
            at1 += SLIDE_TICKS + 1;
        }
        if (mo.flip())
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                animate(d, new Vector3f(), rest, size, FLIP_TICKS);
                if (d.isValid())
                    CasinoPack.soundNear(d.getLocation(), null, "poker.flip", Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.7f, 12);
            }, at1);
        p.ids.add(d.getUniqueId());
        return p;
    }

    private static void animate(ItemDisplay d, Vector3f move, Quaternionf rot, Vector3f size, int ticks) {
        if (!d.isValid())
            return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(move, rot, size, new Quaternionf()));
    }

    /** Montón de fichas: de la ficha más grande que quepa, más alto cuanto más vale (hasta 6). */
    private Placed chips(Location at, long amount, float scale, Motion mo) {
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
        for (int i = 0; i < count; i++) {
            Location l = at.clone().add(0, i * 0.075 * scale, 0);
            all.ids.addAll(single(l, i * 23f, item, 0.32f * scale, null, true, mo).ids);
        }
        return all;
    }

    private static void remove(Placed p) {
        for (UUID id : p.ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null)
                e.remove();
        }
        p.ids.clear();
    }
}
