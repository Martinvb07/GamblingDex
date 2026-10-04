package com.gamblingdex.games.slots;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SlotsStatsManager {

    public static final class Stats {
        private long spins;
        private long wins;
        private long totalWagerUnits;
        private long totalPayoutUnits;
        private long biggestWinUnits;

        public long getSpins() {
            return spins;
        }

        public long getWins() {
            return wins;
        }

        public long getTotalWagerUnits() {
            return totalWagerUnits;
        }

        public long getTotalPayoutUnits() {
            return totalPayoutUnits;
        }

        public long getBiggestWinUnits() {
            return biggestWinUnits;
        }
    }

    private final GamblingDexPlugin plugin;
    private final File file;
    private FileConfiguration cfg;
    private final Map<UUID, Stats> cache = new HashMap<>();

    public SlotsStatsManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "slots_stats.yml");
        load();
    }

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta de datos del plugin.");
        }
        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    plugin.getLogger().warning("No se pudo crear slots_stats.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Error creando slots_stats.yml: " + e.getMessage());
            }
        }

        this.cfg = YamlConfiguration.loadConfiguration(file);
        this.cache.clear();

        if (!cfg.isConfigurationSection("players")) {
            return;
        }

        for (String uuidStr : cfg.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID id = UUID.fromString(uuidStr);
                Stats s = new Stats();
                String base = "players." + uuidStr + ".";
                s.spins = cfg.getLong(base + "spins", 0L);
                s.wins = cfg.getLong(base + "wins", 0L);
                s.totalWagerUnits = cfg.getLong(base + "total_wager_units", 0L);
                s.totalPayoutUnits = cfg.getLong(base + "total_payout_units", 0L);
                s.biggestWinUnits = cfg.getLong(base + "biggest_win_units", 0L);
                cache.put(id, s);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        if (cfg == null) {
            cfg = new YamlConfiguration();
        }

        // rewrite from cache to keep it simple
        cfg.set("players", null);
        for (Map.Entry<UUID, Stats> e : cache.entrySet()) {
            String uuidStr = e.getKey().toString();
            Stats s = e.getValue();
            String base = "players." + uuidStr + ".";
            cfg.set(base + "spins", s.spins);
            cfg.set(base + "wins", s.wins);
            cfg.set(base + "total_wager_units", s.totalWagerUnits);
            cfg.set(base + "total_payout_units", s.totalPayoutUnits);
            cfg.set(base + "biggest_win_units", s.biggestWinUnits);
        }

        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar slots_stats.yml: " + e.getMessage());
        }
    }

    public Stats get(UUID playerId) {
        return cache.computeIfAbsent(playerId, k -> new Stats());
    }

    /**
     * Returns a snapshot of all cached player stats.
     * Intended for read-only operations (e.g., leaderboards).
     */
    public Map<UUID, Stats> snapshot() {
        return new HashMap<>(cache);
    }

    public void recordSpin(UUID playerId, long wagerUnits) {
        if (playerId == null)
            return;
        Stats s = get(playerId);
        s.spins++;
        if (wagerUnits > 0)
            s.totalWagerUnits += wagerUnits;
    }

    public void recordPayout(UUID playerId, long payoutUnits) {
        if (playerId == null)
            return;
        Stats s = get(playerId);
        if (payoutUnits > 0) {
            s.wins++;
            s.totalPayoutUnits += payoutUnits;
            if (payoutUnits > s.biggestWinUnits)
                s.biggestWinUnits = payoutUnits;
        }
    }

    public List<Map.Entry<UUID, Stats>> topByBiggestWin(int limit) {
        int safe = Math.max(1, Math.min(50, limit));
        List<Map.Entry<UUID, Stats>> list = new ArrayList<>(cache.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue().biggestWinUnits, a.getValue().biggestWinUnits));
        if (list.size() > safe) {
            return list.subList(0, safe);
        }
        return list;
    }
}
