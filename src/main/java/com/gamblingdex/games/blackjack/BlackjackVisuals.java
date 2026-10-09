package com.gamblingdex.games.blackjack;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.TableProps;
import com.gamblingdex.models.StationModels;
import com.gamblingdex.pack.CasinoPack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Cartas y fichas en 3D sobre las mesas de blackjack (resource pack:
 * resource_pack.custom_cards), con o sin la mesa 3D de ModelEngine:
 * <ul>
 * <li>Las cartas salen del zapato y se deslizan; las de cada jugador quedan en escalera
 * en el borde de la mesa delante de su asiento (las manos divididas una al lado de la otra).</li>
 * <li>La segunda carta del dealer queda boca abajo y se da vuelta cuando le toca.</li>
 * <li>La apuesta en el círculo (y las laterales al lado). Al resolver: si gana, el pago sale
 * de la bandeja del dealer y todo se desliza hacia el jugador; si pierde, el dealer se lleva
 * las fichas a su bandeja.</li>
 * </ul>
 */
public class BlackjackVisuals {

    private final GamblingDexPlugin plugin;
    private final TableProps props;
    private final Map<String, TableView> views = new HashMap<>();
    private BukkitTask task;

    private static final class TableView {
        final Map<String, TableProps.Placed> placed = new HashMap<>();
        /** Manos ya resueltas en la mesa (sus fichas ya se fueron). */
        final Set<String> settled = new HashSet<>();
        int cardCount;
        long betTotal;
    }

    public BlackjackVisuals(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.props = new TableProps(plugin);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("resource_pack.custom_cards", false)
                && plugin.getConfig().getBoolean("blackjack.visuals.enabled", true);
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 4L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TableView v : views.values())
            for (TableProps.Placed p : v.placed.values())
                TableProps.remove(p);
        views.clear();
    }

    private void tick() {
        BlackjackManager manager = plugin.getBlackjackManager();
        if (manager == null)
            return;
        boolean on = enabled();
        Set<String> alive = new HashSet<>();
        for (BlackjackTable table : manager.getTables()) {
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
                for (TableProps.Placed p : e.getValue().placed.values())
                    TableProps.remove(p);
                it.remove();
            }
        }
    }

    /**
     * Medidas de la mesa (como models/tools/blackjack_table.js): el dealer en el origen, la
     * mesa hacia delante (front), +X del modelo = right. k = bloques por unidad del modelo.
     */
    private record Layout(Location base, double y, Vector front, Vector right, double k) {
        /** Punto del modelo (x, z en unidades; -z = hacia los jugadores) en el mundo, sobre el paño. */
        Location at(double x, double z, double lift) {
            Location l = base.clone().add(right.clone().multiply(x * k)).add(front.clone().multiply(-z * k));
            l.setY(y + 0.005 + lift);
            return l;
        }

        /** Punto del mundo en unidades del modelo: {x, z}. */
        double[] local(Location l) {
            Vector d = l.toVector().subtract(base.toVector()).setY(0);
            return new double[] { d.dot(right) / k, -d.dot(front) / k };
        }
    }

    private Layout layout(BlackjackTable table, BlackjackTable.VisualState st) {
        Location base = table.getCenter().clone().add(0.5, 1, 0.5);
        StationModels models = plugin.getStationModels();
        StationModels.TableSurface surf = models == null ? null : models.blackjackSurface(table.getCenter());
        double yaw;
        double scale, y;
        if (surf != null) {
            yaw = surf.base().getYaw();
            scale = surf.scale();
            y = surf.feltY();
        } else {
            yaw = facing(table, st, base);
            // Sin mesa 3D: el paño "virtual" escala con lo lejos que están los asientos
            double avg = avgSeatDistance(st, base);
            scale = Math.max(0.5, Math.min(1.2, (avg - 0.55) * 16.0 / 60.0));
            y = table.getCenter().getY() + 1
                    + plugin.getConfig().getDouble("blackjack.visuals.table_height", 1.0);
        }
        double r = Math.toRadians(yaw);
        Vector front = new Vector(-Math.sin(r), 0, Math.cos(r));
        Vector right = new Vector(-Math.cos(r), 0, -Math.sin(r));
        return new Layout(base, y, front, right, scale / 16.0);
    }

    /** Hacia dónde mira el dealer: su yaw guardado o, si no tiene, hacia los asientos. */
    public static float facing(BlackjackTable table, BlackjackTable.VisualState st, Location base) {
        if (table.getDealerYaw() != null)
            return table.getDealerYaw();
        double x = 0, z = 0;
        for (String key : table.getSeatKeys()) {
            Location s = BlackjackTables.parseKey(key);
            if (s != null && s.getWorld() == base.getWorld()) {
                x += s.getX() + 0.5 - base.getX();
                z += s.getZ() + 0.5 - base.getZ();
            }
        }
        return x == 0 && z == 0 ? 0f : TableProps.yawFacing(new Vector(x, 0, z));
    }

    private static double avgSeatDistance(BlackjackTable.VisualState st, Location base) {
        double sum = 0;
        int n = 0;
        for (BlackjackTable.PlayerView pv : st.players())
            if (pv.seat() != null && pv.seat().getWorld() == base.getWorld()) {
                sum += Math.hypot(pv.seat().getX() + 0.5 - base.getX(), pv.seat().getZ() + 0.5 - base.getZ());
                n++;
            }
        return n == 0 ? 3.0 : sum / n;
    }

    private void update(BlackjackTable table, TableView view) {
        BlackjackTable.VisualState st = table.visualState();
        Layout lay = layout(table, st);
        float s = (float) (lay.k() * 16);
        Map<String, String> want = new LinkedHashMap<>();
        Map<String, Runnable> spawners = new HashMap<>();
        Location shoe = lay.at(27, -12, 0.1), tray = lay.at(0, -9.7, 0);
        float dealerFacing = TableProps.yawFacing(lay.front()) + 180;

        if (st.players().isEmpty() && st.dealer().isEmpty())
            view.settled.clear();

        // Cartas del dealer: en fila delante de él; la segunda boca abajo hasta su turno
        List<Card> dealer = st.dealer();
        int cards = dealer.size();
        for (int i = 0; i < dealer.size(); i++) {
            Location at = lay.at((i - (dealer.size() - 1) / 2.0) * 7.5, -19, i * 0.004);
            boolean back = st.dealerHidden() && i == 1;
            int face = TableProps.cmd(dealer.get(i));
            String key = "dealer:" + i;
            want.put(key, back ? "back" : "c" + face);
            TableProps.Motion mo = !back && view.placed.containsKey(key) ? new TableProps.Motion(null, true)
                    : new TableProps.Motion(slide(shoe, at), false);
            int shown = back ? TableProps.BACK : face;
            spawners.put(key, () -> view.placed.put(key,
                    props.single(at, dealerFacing, TableProps.cardItem(shown), s, null, false, mo)));
        }

        // Jugadores: todo va en el borde del paño más cercano a su asiento (mesa rectangular:
        // paño x -48..48, z -54..-6; la franja de z > -22 es del dealer)
        long betTotal = 0;
        for (BlackjackTable.PlayerView pv : st.players()) {
            if (pv.seat() == null || pv.seat().getWorld() != lay.base().getWorld())
                continue;
            Location seatAt = pv.seat().clone().add(0.5, 0, 0.5);
            double[] sl = lay.local(seatAt);
            double qx = Math.max(-44, Math.min(44, sl[0])), qz = Math.max(-50, Math.min(-22, sl[1]));
            Location edge = lay.at(qx, qz, 0);
            // u: del asiento hacia la mesa
            Vector u = edge.toVector().subtract(seatAt.toVector()).setY(0);
            if (u.lengthSquared() < 1e-4)
                u = lay.at(0, -30, 0).toVector().subtract(seatAt.toVector()).setY(0);
            if (u.lengthSquared() < 1e-4)
                continue;
            u.normalize();
            Vector toPlayer = u.clone().multiply(-1);
            Vector side = new Vector(-u.getZ(), 0, u.getX());
            float yaw = TableProps.yawFacing(toPlayer);
            List<BlackjackTable.HandView> hands = pv.hands();
            int nh = Math.max(1, hands.size());
            for (int h = 0; h < nh; h++) {
                Vector shift = side.clone().multiply((h - (nh - 1) / 2.0) * 0.48 * s);
                BlackjackTable.HandView hv = h < hands.size() ? hands.get(h) : null;
                String handKey = pv.id() + ":" + h;

                // Cartas en escalera
                if (hv != null)
                    for (int k = 0; k < hv.cards().size(); k++) {
                        Vector off = u.clone().multiply(18 * lay.k() - k * 0.09 * s).add(shift)
                                .add(side.clone().multiply(k * 0.07 * s));
                        Location at = point(edge, off, lay.y(), k * 0.004);
                        int face = TableProps.cmd(hv.cards().get(k));
                        String key = "card:" + handKey + ":" + k;
                        want.put(key, "c" + face);
                        cards++;
                        TableProps.Motion mo = new TableProps.Motion(slide(shoe, at), false);
                        spawners.put(key, () -> view.placed.put(key,
                                props.single(at, yaw, TableProps.cardItem(face), s, null, false, mo)));
                    }

                // Apuesta en el círculo (en la ronda, la de cada mano; antes de repartir, la principal)
                long bet = hv != null ? hv.bet() : pv.mainBet();
                if (bet <= 0 || view.settled.contains(handKey))
                    continue;
                betTotal += bet;
                Location betAt = point(edge, u.clone().multiply(4 * lay.k()).add(shift), lay.y(), 0);
                String key = "bet:" + handKey;
                String result = hv == null ? null : hv.result();
                if (result != null && !result.contains("EMPATE")) {
                    // Mano resuelta: las fichas se van (y si ganó, llega el pago y todo va al jugador)
                    view.settled.add(handKey);
                    TableProps.Placed chips = view.placed.remove(key);
                    boolean won = result.contains("+");
                    settle(chips, betAt, tray, bet, toPlayer, won, s);
                    if (won)
                        CasinoPack.soundNear(betAt, null, "poker.win", Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f, 12);
                    continue;
                }
                want.put(key, "b" + bet);
                TableProps.Motion mo = new TableProps.Motion(toPlayer.clone().multiply(0.5 * s), false); // desde el jugador
                spawners.put(key, () -> view.placed.put(key, props.chips(betAt, bet, s, mo)));
            }

            // Laterales: parejas a un lado del círculo, 21+3 al otro
            long[] sideBets = { pv.pairsBet(), pv.plus3Bet() };
            for (int b = 0; b < 2; b++) {
                if (sideBets[b] <= 0 || (!hands.isEmpty() && st.resultsShown()))
                    continue;
                long amount = sideBets[b];
                Location at = point(edge, u.clone().multiply(4 * lay.k()).add(side.clone().multiply((b == 0 ? -0.36 : 0.36) * s)),
                        lay.y(), 0);
                String key = "side:" + pv.id() + ":" + b;
                want.put(key, "b" + amount);
                spawners.put(key, () -> view.placed.put(key, props.chips(at, amount, s * 0.8f, TableProps.Motion.NONE)));
            }
        }

        // Quitar lo que ya no está o cambió (la del dealer que se da vuelta: al aparecer la nueva)
        for (Iterator<Map.Entry<String, TableProps.Placed>> it = view.placed.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, TableProps.Placed> e = it.next();
            if (!Objects.equals(want.get(e.getKey()), e.getValue().signature)) {
                TableProps.Placed old = e.getValue();
                if (want.containsKey(e.getKey()) && e.getKey().startsWith("dealer:")) {
                    Runnable spawn = spawners.get(e.getKey());
                    spawners.put(e.getKey(), () -> {
                        spawn.run();
                        TableProps.remove(old);
                    });
                } else
                    TableProps.remove(old);
                it.remove();
            }
        }
        for (Map.Entry<String, String> e : want.entrySet())
            if (!view.placed.containsKey(e.getKey())) {
                spawners.get(e.getKey()).run();
                TableProps.Placed p = view.placed.get(e.getKey());
                if (p != null)
                    p.signature = e.getValue();
            }

        Location snd = lay.base().clone().add(0, 0.5, 0);
        if (cards > view.cardCount)
            CasinoPack.soundNear(snd, null, "poker.card", Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f, 12);
        if (betTotal > view.betTotal)
            CasinoPack.soundNear(snd, null, "poker.chips", Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.6f, 1.6f, 12);
        view.cardCount = cards;
        view.betTotal = betTotal;
    }

    /**
     * Fichas de una mano resuelta. Pierde: van a la bandeja del dealer. Gana: el pago sale
     * de la bandeja hasta el círculo y después todo se desliza hacia el jugador.
     */
    private void settle(TableProps.Placed chips, Location betAt, Location tray, long bet, Vector toPlayer, boolean won,
            float s) {
        if (!won) {
            if (chips != null)
                props.slideAway(chips, tray.toVector().subtract(betAt.toVector()), 10, 30L);
            return;
        }
        Vector side = new Vector(-toPlayer.getZ(), 0, toPlayer.getX()).multiply(0.2 * s);
        Location payAt = betAt.clone().add(side);
        TableProps.Placed pay = props.chips(payAt, bet, s, new TableProps.Motion(slide(tray, payAt), false));
        Vector away = toPlayer.clone().multiply(0.9 * s);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            props.slideAway(pay, away, 10, 30L);
            if (chips != null)
                props.slideAway(chips, away, 10, 30L);
        }, 20L);
    }

    private static Location point(Location from, Vector off, double y, double lift) {
        Location at = from.clone().add(off);
        at.setY(y + 0.005 + lift);
        return at;
    }

    /** Offset desde "from" hasta "to" (para que algo llegue deslizándose desde from). */
    private static Vector slide(Location from, Location to) {
        return from.toVector().subtract(to.toVector()).setY(0);
    }
}
