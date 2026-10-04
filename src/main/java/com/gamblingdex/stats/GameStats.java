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
 * {@link #record} cuando se resuelve una apuesta. Se guardan en
 * {@code stats.yml}. La semana se reinicia sola el lunes (stats.timezone).
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

    private final GamblingDexPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> allTime = new HashMap<>();
    private final Map<UUID, Entry> weekly = new HashMap<>();
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
     * total (incluida la apuesta devuelta si ganó). {@code game} es informativo.
     */
    public void record(UUID player, String game, long wager, long payout) {
        if (player == null || (wager <= 0 && payout <= 0))
            return;
        checkWeek();
        apply(allTime.computeIfAbsent(player, k -> new Entry()), wager, payout);
        apply(weekly.computeIfAbsent(player, k -> new Entry()), wager, payout);
        dirty = true;
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

    /** Top ordenado de mayor a menor (se recalcula como mucho cada 30 s). */
    public List<Map.Entry<UUID, Long>> top(Metric metric, boolean week) {
        checkWeek();
        long now = System.currentTimeMillis();
        if (now - topCacheAt > 30_000L) {
            topCache.clear();
            topCacheAt = now;
        }
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
            if (weekKey != null && !weekly.isEmpty())
                plugin.getLogger().info("[Stats] Nueva semana (" + k + "): se reinicia el ranking semanal.");
            weekKey = k;
            weekly.clear();
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
        checkWeek();
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

    private static void writeSection(YamlConfiguration y, String path, Map<UUID, Entry> from) {
        for (Map.Entry<UUID, Entry> e : from.entrySet()) {
            String b = path + "." + e.getKey() + ".";
            y.set(b + "wagered", e.getValue().wagered);
            y.set(b + "paid", e.getValue().paid);
            y.set(b + "biggest_win", e.getValue().biggestWin);
            y.set(b + "rounds", e.getValue().rounds);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("week", weekKey);
        writeSection(y, "all_time", allTime);
        writeSection(y, "weekly", weekly);
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
