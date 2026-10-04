package com.gamblingdex.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public class VaultEconomyHook {

    private Economy economy;

    public void setup() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        } else {
            economy = null;
        }
    }

    public String getProviderName() {
        if (economy == null) {
            setup();
        }
        return economy == null ? "" : economy.getName();
    }

    public String getProviderClassName() {
        if (economy == null) {
            setup();
        }
        return economy == null ? "" : economy.getClass().getName();
    }

    public boolean isAvailable() {
        if (economy == null) {
            setup();
        }
        return economy != null;
    }

    public boolean withdraw(Player player, double amount) {
        if (player == null)
            return false;
        if (amount <= 0)
            return true;
        if (economy == null) {
            setup();
        }
        if (economy == null)
            return false;
        EconomyResponse resp = economy.withdrawPlayer(player, amount);
        return resp.transactionSuccess();
    }

    public boolean deposit(Player player, double amount) {
        if (player == null)
            return false;
        if (amount <= 0)
            return true;
        if (economy == null) {
            setup();
        }
        if (economy == null)
            return false;
        EconomyResponse resp = economy.depositPlayer(player, amount);
        return resp.transactionSuccess();
    }

    public double getBalance(Player player) {
        if (player == null)
            return 0.0;
        if (economy == null) {
            setup();
        }
        if (economy == null)
            return 0.0;
        return economy.getBalance(player);
    }
}
