package com.gamblingdex.stats;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Estadísticas de TODOS los juegos (apostado, cobrado, ganancia neta, mejor
 * premio), históricas y de la semana actual. Cada juego llama a
 * {@link #record} cuando se resuelve una apuesta. Además guarda:
 * <ul>
 * <li>los últimos premios del casino y el récord histórico,</li>
 * <li>el historial de las últimas apuestas de cada jugador (/gdx history),</li>
 * <li>las estadísticas del póker (que es entre jugadores, va aparte).</li>
 * </ul>
 * Todo se guarda en {@code stats.yml}. La semana se reinicia el lunes (stats.timezone).
 */
public class GameStats {

    public static final class Entry {
        long wagered;
        long paid;
        long biggestWin;
        long rounds;

        public long wagered() {
            return wagered;
        }

        public long paid() {
            return paid;
        }

        /** Ganancia neta: lo cobrado menos lo apostado. */
        public long profit() {
            return paid - wagered;
        }

        public long biggestWin() {
            return biggestWin;
        }

        public long rounds() {
            return rounds;
        }
    }

    /** Póker: manos, manos ganadas, ganancia (cobrado al levantarse - comprado), bote más grande, torneos. */
    public static final class PokerEntry {
        long hands;
        long handsWon;
        long profit;
        long biggestPot;
        long tournaments;

        public long hands() {
            return hands;
        }

        public long handsWon() {
            return handsWon;
        }

        public long profit() {
            return profit;
        }

        public long biggestPot() {
            return biggestPot;
        }

        public long tournaments() {
            return tournaments;
        }
    }

    /** Una apuesta resuelta (para el historial y los últimos premios). */
    public record Play(long time, String name, String game, long wager, long payout) {
        public long net() {
            return payout - wager;
        }
    }

    public enum Metric {
        PROFIT, WAGERED, PAID, BIGGEST_WIN;

        long of(Entry e) {
            return switch (this) {
                case PROFIT -> e.profit();
                case WAGERED -> e.wagered;
                case PAID -> e.paid;
                case BIGGEST_WIN -> e.biggestWin;
            };
        }

        public static Metric parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "profit", "ganancia", "ganancias", "neto" -> PROFIT;
                case "wagered", "apostado" -> WAGERED;
                case "paid", "won", "cobrado", "ganado" -> PAID;
                case "biggest", "biggest_win", "biggestwin", "mejor", "mejor_premio" -> BIGGEST_WIN;
                default -> null;
            };
        }
    }

    public enum PokerMetric {
        PROFIT, HANDS, WON, POT, TOURNAMENTS;

        long of(PokerEntry e) {
            return switch (this) {
                case PROFIT -> e.profit;
                case HANDS -> e.hands;
                case WON -> e.handsWon;
                case POT -> e.biggestPot;
                case TOURNAMENTS -> e.tournaments;
            };
        }

        public static PokerMetric parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "profit" -> PROFIT;
                case "hands" -> HANDS;
                case "won", "hands_won" -> WON;
                case "pot", "pots", "biggest_pot" -> POT;
                case "tournaments", "torneos" -> TOURNAMENTS;
                default -> null;
            };
        }
    }

    /** Nombre bonito de cada juego (el id es el que usan los juegos al registrar). */
    public static String gameName(String id) {
        return switch (id == null ? "" : id) {
            case "slots" -> "Tragamonedas";
            case "ruleta" -> "Ruleta";
            case "blackjack" -> "Blackjack";
            case "baccarat" -> "Baccarat";
            case "crash" -> "Crash";
            case "rueda" -> "Rueda";
            case "carrera" -> "Carrera";
            case "coinflip" -> "Coinflip";
            case "rasca" -> "Rasca y Gana";
            case "bingo" -> "Bingo";
            case "loteria" -> "Lotería";
            case "mines" -> "Mines";
            case "plinko" -> "Plinko";
            case "poker" -> "Póker";
            default -> id;
        };
    }

    private static final int RECENT = 10;

    private final GamblingDexPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> allTime = new HashMap<>();
    private final Map<UUID, Entry> weekly = new HashMap<>();
    // Por juego (id del juego → jugador → stats), para los tops de cada juego
    private final Map<String, Map<UUID, Entry>> gameAll = new HashMap<>();
    private final Map<String, Map<UUID, Entry>> gameWeekly = new HashMap<>();
    private final Map<UUID, PokerEntry> pokerAll = new HashMap<>();
    private final Map<UUID, PokerEntry> pokerWeekly = new HashMap<>();
    private final Map<UUID, Deque<Play>> history = new HashMap<>();
    private final Deque<Play> recentWins = new ArrayDeque<>();
    private final Map<UUID, Long> lastPlay = new HashMap<>();
    private Play record;
    private String weekKey;
    private boolean dirty;
    private BukkitTask saveTask;

    // Rankings en caché (los placeholders se piden muchas veces por segundo)
    private final Map<String, List<Map.Entry<UUID, Long>>> topCache = new HashMap<>();
    private long topCacheAt;

    public GameStats(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            checkWeek();
            if (dirty)
                save();
        }, 20L * 60, 20L * 60);
    }

    /**
     * Registra una apuesta ya resuelta: lo que apostó y lo que se le pagó en
     * total (incluida la apuesta devuelta si ganó). {@code game} es el id del juego.
     */
    public void record(UUID player, String game, long wager, long payout) {
        if (player == null || (wager <= 0 && payout <= 0))
            return;
        checkWeek();
        Entry all = allTime.computeIfAbsent(player, k -> new Entry());
        apply(all, wager, payout);
        apply(weekly.computeIfAbsent(player, k -> new Entry()), wager, payout);
        if (game != null && !game.isBlank()) {
            apply(gameAll.computeIfAbsent(game, k -> new HashMap<>()).computeIfAbsent(player, k -> new Entry()), wager, payout);
            apply(gameWeekly.computeIfAbsent(game, k -> new HashMap<>()).computeIfAbsent(player, k -> new Entry()), wager, payout);
        }
        lastPlay.put(player, System.currentTimeMillis());

        String name = nameOf(player);
        Play play = new Play(System.currentTimeMillis(), name, game, wager, payout);
        Deque<Play> h = history.computeIfAbsent(player, k -> new ArrayDeque<>());
        h.addFirst(play);
        while (h.size() > RECENT)
            h.removeLast();
        if (play.net() > 0) {
            recentWins.addFirst(play);
            while (recentWins.size() > RECENT)
                recentWins.removeLast();
            if (record == null || play.net() > record.net())
                record = play;
        }
        dirty = true;

        var lock = plugin.getBonusLock();
        if (lock != null)
            lock.onWager(player, wager, payout);

        var ach = plugin.getAchievements();
        if (ach != null)
            ach.onPlay(player, game, wager, payout, all.rounds);
    }

    private static void apply(Entry e, long wager, long payout) {
        e.wagered += Math.max(0, wager);
        e.paid += Math.max(0, payout);
        long win = payout - wager;
        if (win > e.biggestWin)
            e.biggestWin = win;
        e.rounds++;
    }

    public Entry get(UUID player, boolean week) {
        checkWeek();
        Entry e = (week ? weekly : allTime).get(player);
        return e == null ? new Entry() : e;
    }

    // ------------------------------------------------------------------
    // Póker
    // ------------------------------------------------------------------

    /** Fin de una mano: la jugó y cobró {@code won} del bote (0 si no ganó). */
    public void pokerHand(UUID player, long won) {
        if (player == null)
            return;
        checkWeek();
        for (PokerEntry e : List.of(pokerAll.computeIfAbsent(player, k -> new PokerEntry()),
                pokerWeekly.computeIfAbsent(player, k -> new PokerEntry()))) {
            e.hands++;
            if (won > 0) {
                e.handsWon++;
                if (won > e.biggestPot)
                    e.biggestPot = won;
            }
        }
        lastPlay.put(player, System.currentTimeMillis());
        dirty = true;
    }

    /** Se levantó de una mesa (no torneo): ganancia = lo que se llevó - lo que compró. */
    public void pokerSession(UUID player, long net) {
        if (player == null || net == 0)
            return;
        checkWeek();
        pokerAll.computeIfAbsent(player, k -> new PokerEntry()).profit += net;
        pokerWeekly.computeIfAbsent(player, k -> new PokerEntry()).profit += net;
        if (net > 0) {
            Play play = new Play(System.currentTimeMillis(), nameOf(player), "poker", 0, net);
            recentWins.addFirst(play);
            while (recentWins.size() > RECENT)
                recentWins.removeLast();
        }
        dirty = true;
    }

    public void pokerTournamentWin(UUID player) {
        if (player == null)
            return;
        checkWeek();
        pokerAll.computeIfAbsent(player, k -> new PokerEntry()).tournaments++;
        pokerWeekly.computeIfAbsent(player, k -> new PokerEntry()).tournaments++;
        dirty = true;
    }

    public PokerEntry poker(UUID player, boolean week) {
        checkWeek();
        PokerEntry e = (week ? pokerWeekly : pokerAll).get(player);
        return e == null ? new PokerEntry() : e;
    }

    public List<Map.Entry<UUID, Long>> pokerTop(PokerMetric metric, boolean week) {
        checkWeek();
        refreshCache();
        return topCache.computeIfAbsent("P_" + metric.name() + (week ? "_w" : ""), k -> {
            List<Map.Entry<UUID, Long>> list = new ArrayList<>();
            for (Map.Entry<UUID, PokerEntry> e : (week ? pokerWeekly : pokerAll).entrySet()) {
                long v = metric.of(e.getValue());
                if (metric == PokerMetric.PROFIT ? e.getValue().hands > 0 || v != 0 : v > 0)
                    list.add(Map.entry(e.getKey(), v));
            }
            list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            return list;
        });
    }

    // ------------------------------------------------------------------
    // Premios, récord, historial, actividad
    // ------------------------------------------------------------------

    /** Últimos premios del casino (el más reciente primero). */
    public List<Play> recentWins() {
        return new ArrayList<>(recentWins);
    }

    /** El premio más grande de la historia (null si aún no hay). */
    public Play record() {
        return record;
    }

    /** Últimas apuestas de un jugador (la más reciente primero). */
    public List<Play> history(UUID player) {
        return new ArrayList<>(history.getOrDefault(player, new ArrayDeque<>()));
    }

    /** Jugadores conectados que jugaron algo en los últimos {@code minutes} minutos. */
    public int activePlayers(int minutes) {
        long since = System.currentTimeMillis() - minutes * 60_000L;
        int n = 0;
        for (Map.Entry<UUID, Long> e : lastPlay.entrySet())
            if (e.getValue() >= since && Bukkit.getPlayer(e.getKey()) != null)
                n++;
        return n;
    }

    // ------------------------------------------------------------------
    // Rankings
    // ------------------------------------------------------------------

    private void refreshCache() {
        long now = System.currentTimeMillis();
        if (now - topCacheAt > 30_000L) {
            topCache.clear();
            topCacheAt = now;
        }
    }

    /** Top ordenado de mayor a menor (se recalcula como mucho cada 30 s). */
    /** Stats de un jugador en un juego (blackjack, ruleta, crash...). */
    public Entry get(UUID player, String game, boolean week) {
        checkWeek();
        Map<UUID, Entry> m = (week ? gameWeekly : gameAll).get(game);
        Entry e = m == null ? null : m.get(player);
        return e == null ? new Entry() : e;
    }

    /** Top de un juego concreto. */
    public List<Map.Entry<UUID, Long>> top(String game, Metric metric, boolean week) {
        checkWeek();
        refreshCache();
        return topCache.computeIfAbsent("G_" + game + "_" + metric.name() + (week ? "_w" : ""), k -> {
            List<Map.Entry<UUID, Long>> list = new ArrayList<>();
            Map<UUID, Entry> m = (week ? gameWeekly : gameAll).get(game);
            if (m != null)
                for (Map.Entry<UUID, Entry> e : m.entrySet()) {
                    long v = metric.of(e.getValue());
                    if (metric == Metric.PROFIT ? e.getValue().rounds > 0 : v > 0)
                        list.add(Map.entry(e.getKey(), v));
                }
            list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            return list;
        });
    }

    public List<Map.Entry<UUID, Long>> top(Metric metric, boolean week) {
        checkWeek();
        refreshCache();
        return topCache.computeIfAbsent(metric.name() + (week ? "_w" : ""), k -> {
            List<Map.Entry<UUID, Long>> list = new ArrayList<>();
            for (Map.Entry<UUID, Entry> e : (week ? weekly : allTime).entrySet()) {
                long v = metric.of(e.getValue());
                if (metric == Metric.PROFIT ? e.getValue().rounds > 0 : v > 0)
                    list.add(Map.entry(e.getKey(), v));
            }
            list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            return list;
        });
    }

    /** Puesto del jugador en el top (1 = primero), o 0 si no aparece. */
    public int rank(UUID player, Metric metric, boolean week) {
        List<Map.Entry<UUID, Long>> list = top(metric, week);
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).getKey().equals(player))
                return i + 1;
        return 0;
    }

    public int rank(UUID player, String game, Metric metric, boolean week) {
        List<Map.Entry<UUID, Long>> list = top(game, metric, week);
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).getKey().equals(player))
                return i + 1;
        return 0;
    }

    /** Id del juego para los placeholders (acepta los nombres en inglés), o null. */
    public static String gameId(String s) {
        return switch (s == null ? "" : s) {
            case "slots" -> "slots";
            case "ruleta", "roulette" -> "ruleta";
            case "blackjack", "bj" -> "blackjack";
            case "baccarat" -> "baccarat";
            case "crash" -> "crash";
            case "rueda", "wheel" -> "rueda";
            case "carrera", "race" -> "carrera";
            case "coinflip" -> "coinflip";
            case "rasca", "scratch" -> "rasca";
            case "bingo" -> "bingo";
            case "loteria", "lottery" -> "loteria";
            case "mines" -> "mines";
            case "plinko" -> "plinko";
            default -> null;
        };
    }

    private String nameOf(UUID id) {
        String n = null;
        try {
            if (plugin.getPlayerIndex() != null)
                n = plugin.getPlayerIndex().getLastKnownName(id);
        } catch (Throwable ignored) {
        }
        if (n == null || n.isBlank())
            n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    // ------------------------------------------------------------------
    // Semana
    // ------------------------------------------------------------------

    private String currentWeekKey() {
        ZoneId zone;
        String tz = plugin.getConfig().getString("stats.timezone", "");
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        return now.get(IsoFields.WEEK_BASED_YEAR) + "-W" + now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private void checkWeek() {
        String k = currentWeekKey();
        if (!k.equals(weekKey)) {
            if (weekKey != null && (!weekly.isEmpty() || !pokerWeekly.isEmpty()))
                plugin.getLogger().info("[Stats] Nueva semana (" + k + "): se reinicia el ranking semanal.");
            weekKey = k;
            weekly.clear();
            gameWeekly.clear();
            pokerWeekly.clear();
            topCache.clear();
            dirty = true;
        }
    }

    // ------------------------------------------------------------------
    // Archivo
    // ------------------------------------------------------------------

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        readSection(y.getConfigurationSection("all_time"), allTime);
        weekKey = y.getString("week", null);
        readSection(y.getConfigurationSection("weekly"), weekly);
        for (String[] part : new String[][] { { "games_all", "a" }, { "games_weekly", "w" } }) {
            ConfigurationSection gs = y.getConfigurationSection(part[0]);
            if (gs == null)
                continue;
            for (String game : gs.getKeys(false)) {
                Map<UUID, Entry> m = new HashMap<>();
                readSection(gs.getConfigurationSection(game), m);
                (part[1].equals("a") ? gameAll : gameWeekly).put(game, m);
            }
        }
        readPoker(y.getConfigurationSection("poker_all"), pokerAll);
        readPoker(y.getConfigurationSection("poker_weekly"), pokerWeekly);
        for (String s : y.getStringList("recent_wins")) {
            Play p = parsePlay(s);
            if (p != null)
                recentWins.addLast(p);
        }
        record = parsePlay(y.getString("record"));
        ConfigurationSection hs = y.getConfigurationSection("history");
        if (hs != null) {
            for (String k : hs.getKeys(false)) {
                try {
                    Deque<Play> d = new ArrayDeque<>();
                    for (String s : hs.getStringList(k)) {
                        Play p = parsePlay(s);
                        if (p != null)
                            d.addLast(p);
                    }
                    history.put(UUID.fromString(k), d);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        checkWeek();
    }

    private static String formatPlay(Play p) {
        return p.time() + "|" + p.name() + "|" + p.game() + "|" + p.wager() + "|" + p.payout();
    }

    private static Play parsePlay(String s) {
        if (s == null)
            return null;
        String[] a = s.split("\\|", -1);
        if (a.length != 5)
            return null;
        try {
            return new Play(Long.parseLong(a[0]), a[1], a[2], Long.parseLong(a[3]), Long.parseLong(a[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void readSection(ConfigurationSection sec, Map<UUID, Entry> into) {
        if (sec == null)
            return;
        for (String k : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                Entry e = new Entry();
                e.wagered = sec.getLong(k + ".wagered");
                e.paid = sec.getLong(k + ".paid");
                e.biggestWin = sec.getLong(k + ".biggest_win");
                e.rounds = sec.getLong(k + ".rounds");
                into.put(id, e);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void readPoker(ConfigurationSection sec, Map<UUID, PokerEntry> into) {
        if (sec == null)
            return;
        for (String k : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                PokerEntry e = new PokerEntry();
                e.hands = sec.getLong(k + ".hands");
                e.handsWon = sec.getLong(k + ".hands_won");
                e.profit = sec.getLong(k + ".profit");
                e.biggestPot = sec.getLong(k + ".biggest_pot");
                e.tournaments = sec.getLong(k + ".tournaments");
                into.put(id, e);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void writeSection(YamlConfiguration y, String path, Map<UUID, Entry> from) {
        for (Map.Entry<UUID, Entry> e : from.entrySet()) {
            String b = path + "." + e.getKey() + ".";
            y.set(b + "wagered", e.getValue().wagered);
            y.set(b + "paid", e.getValue().paid);
            y.set(b + "biggest_win", e.getValue().biggestWin);
            y.set(b + "rounds", e.getValue().rounds);
        }
    }

    private static void writePoker(YamlConfiguration y, String path, Map<UUID, PokerEntry> from) {
        for (Map.Entry<UUID, PokerEntry> e : from.entrySet()) {
            String b = path + "." + e.getKey() + ".";
            y.set(b + "hands", e.getValue().hands);
            y.set(b + "hands_won", e.getValue().handsWon);
            y.set(b + "profit", e.getValue().profit);
            y.set(b + "biggest_pot", e.getValue().biggestPot);
            y.set(b + "tournaments", e.getValue().tournaments);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("week", weekKey);
        writeSection(y, "all_time", allTime);
        writeSection(y, "weekly", weekly);
        for (Map.Entry<String, Map<UUID, Entry>> e : gameAll.entrySet())
            writeSection(y, "games_all." + e.getKey(), e.getValue());
        for (Map.Entry<String, Map<UUID, Entry>> e : gameWeekly.entrySet())
            writeSection(y, "games_weekly." + e.getKey(), e.getValue());
        writePoker(y, "poker_all", pokerAll);
        writePoker(y, "poker_weekly", pokerWeekly);
        List<String> recent = new ArrayList<>();
        for (Play p : recentWins)
            recent.add(formatPlay(p));
        y.set("recent_wins", recent);
        if (record != null)
            y.set("record", formatPlay(record));
        for (Map.Entry<UUID, Deque<Play>> e : history.entrySet()) {
            List<String> l = new ArrayList<>();
            for (Play p : e.getValue())
                l.add(formatPlay(p));
            y.set("history." + e.getKey(), l);
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar stats.yml: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (saveTask != null)
            saveTask.cancel();
        save();
    }
}
