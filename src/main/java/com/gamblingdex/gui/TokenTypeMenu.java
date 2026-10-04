package com.gamblingdex.gui;

import com.gamblingdex.economy.TokenManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.Map;

public class TokenTypeMenu {
    private final TokenManager tokenManager;

    public TokenTypeMenu(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(new TokenTypeMenuHolder(), 36, ChatColor.GOLD + "Elige una ficha");

        int[] tokenSlots = new int[] { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21 };
        int i = 0;
        for (Map.Entry<Material, Integer> entry : TokenManager.getDenoms().entrySet()) {
            if (i >= tokenSlots.length)
                break;
            ItemStack token = tokenManager.createToken(entry.getKey(), 1);
            inv.setItem(tokenSlots[i], token);
            i++;
        }
        player.openInventory(inv);
    }

    public static boolean isTokenTypeMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof TokenTypeMenuHolder;
    }
}
