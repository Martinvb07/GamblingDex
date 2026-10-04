package com.gamblingdex.games.rouletteworld;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RouletteStatsManager {

    public static final class Stats {
        private long rounds;
        private long wins;
        private long totalWagerUnits;
        private long totalPayoutUnits;
        private long biggestWinUnits;

        public long getRounds() {
            return rounds;
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

    public RouletteStatsManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "roulette_stats.yml");
        load();
    }

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta de datos del plugin.");
        }
        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    plugin.getLogger().warning("No se pudo crear roulette_stats.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Error creando roulette_stats.yml: " + e.getMessage());
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
                s.rounds = cfg.getLong(base + "rounds", 0L);
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

        cfg.set("players", null);
        for (Map.Entry<UUID, Stats> e : cache.entrySet()) {
            String uuidStr = e.getKey().toString();
            Stats s = e.getValue();
            String base = "players." + uuidStr + ".";
            cfg.set(base + "rounds", s.rounds);
            cfg.set(base + "wins", s.wins);
            cfg.set(base + "total_wager_units", s.totalWagerUnits);
            cfg.set(base + "total_payout_units", s.totalPayoutUnits);
            cfg.set(base + "biggest_win_units", s.biggestWinUnits);
        }

        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar roulette_stats.yml: " + e.getMessage());
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

    public void recordRound(UUID playerId) {
        if (playerId == null)
            return;
        Stats s = get(playerId);
        s.rounds++;
    }

    public void recordWager(UUID playerId, long wagerUnits) {
        if (playerId == null)
            return;
        if (wagerUnits <= 0)
            return;
        Stats s = get(playerId);
        s.totalWagerUnits += wagerUnits;
    }

    public void recordPayout(UUID playerId, long payoutUnits) {
        if (playerId == null)
            return;
        if (payoutUnits <= 0)
            return;
        Stats s = get(playerId);
        s.wins++;
        s.totalPayoutUnits += payoutUnits;
        if (payoutUnits > s.biggestWinUnits)
            s.biggestWinUnits = payoutUnits;
    }
}
