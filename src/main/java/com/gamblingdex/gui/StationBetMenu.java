package com.gamblingdex.gui;

import com.gamblingdex.economy.TokenManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import java.util.Map;

public class StationBetMenu {
    private final TokenManager tokenManager;
    private final String stationName;

    public StationBetMenu(TokenManager tokenManager, String stationName) {
        this.tokenManager = tokenManager;
        this.stationName = stationName;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(new StationBetMenuHolder(), 27,
                "§6§lApostar en " + stationName);
        // Slots rojos para fichas, resto cristales
        int[] fichaSlots = { 10, 11, 12, 13, 14, 15, 16 };
        int idx = 0;
        for (Map.Entry<Material, Integer> entry : TokenManager.getDenoms().entrySet()) {
            if (idx >= fichaSlots.length)
                break;
            Material mat = entry.getKey();
            int count = countTokens(player, mat);
            if (count > 0) {
                ItemStack token = tokenManager.createToken(mat, 1);
                token.setAmount(count);
                org.bukkit.inventory.meta.ItemMeta meta = token.getItemMeta();
                if (meta != null) {
                    java.util.List<String> lore = new java.util.ArrayList<>();
                    lore.add("§7Haz clic para apostar");
                    lore.add("§fValor: §e" + entry.getValue() + " ⛃");
                    meta.setLore(lore);
                    token.setItemMeta(meta);
                }
                inv.setItem(fichaSlots[idx], token);
            }
            idx++;
        }
        // Bordes de cristal blanco
        for (int i = 0; i < inv.getSize(); i++) {
            if (i < 9 || i > 17 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, MenuUtils.createButton("", Material.WHITE_STAINED_GLASS_PANE));
            }
        }
        // Resto de slots: cristales
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, MenuUtils.createButton("", Material.GRAY_STAINED_GLASS_PANE));
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

    public static boolean isStationBetMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof StationBetMenuHolder;
    }
}

// ...existing code...
