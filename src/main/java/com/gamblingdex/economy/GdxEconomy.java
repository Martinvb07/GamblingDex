package com.gamblingdex.economy;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class GdxEconomy {

    private final GamblingDexPlugin plugin;
    private final File balancesFile;
    private FileConfiguration balances;

    public GdxEconomy(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.balancesFile = new File(plugin.getDataFolder(), "balances.yml");
        reload();
    }

    public void reload() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta de datos del plugin.");
        }
        if (!balancesFile.exists()) {
            try {
                if (!balancesFile.createNewFile()) {
                    plugin.getLogger().warning("No se pudo crear balances.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Error creando balances.yml: " + e.getMessage());
            }
        }
        this.balances = YamlConfiguration.loadConfiguration(balancesFile);
    }

    public long getBalance(UUID playerId) {
        return balances.getLong(path(playerId), 0L);
    }

    public void setBalance(UUID playerId, long amount) {
        long safeAmount = Math.max(0L, amount);
        balances.set(path(playerId), safeAmount);
        save();
    }

    public void deposit(UUID playerId, long amount) {
        if (amount <= 0)
            return;
        long current = getBalance(playerId);
        long updated = safeAdd(current, amount);
        balances.set(path(playerId), updated);
        save();
    }

    public boolean withdraw(UUID playerId, long amount) {
        if (amount <= 0)
            return true;
        long current = getBalance(playerId);
        if (current < amount)
            return false;
        balances.set(path(playerId), current - amount);
        save();
        return true;
    }

    private void save() {
        try {
            balances.save(balancesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Error guardando balances.yml: " + e.getMessage());
        }
    }

    private static String path(UUID playerId) {
        return "players." + playerId + ".balance";
    }

    private static long safeAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }
}
