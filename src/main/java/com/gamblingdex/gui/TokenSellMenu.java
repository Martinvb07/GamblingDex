package com.gamblingdex.gui;

import com.gamblingdex.economy.TokenManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.Map;

public class TokenSellMenu {
    private final TokenManager tokenManager;

    public TokenSellMenu(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(new TokenSellMenuHolder(), 27, ChatColor.GOLD + "Vender fichas");
        int slot = 10;
        Map<Material, Integer> denoms = TokenManager.getDenoms();
        for (Map.Entry<Material, Integer> entry : denoms.entrySet()) {
            int count = countTokens(player, entry.getKey());
            if (count > 0) {
                ItemStack token = tokenManager.createToken(entry.getKey(), 1);
                token.setAmount(Math.min(count, 64));
                inv.setItem(slot, token);
                slot++;
            }
        }
        player.openInventory(inv);
    }

    private int countTokens(Player player, Material mat) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == mat && tokenManager.isToken(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public static boolean isTokenSellMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof TokenSellMenuHolder;
    }
}
