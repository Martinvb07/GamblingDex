package com.gamblingdex.gui;

import com.gamblingdex.economy.TokenManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import java.util.Map;

public class SlotsBetMenu {
    private final TokenManager tokenManager;

    public SlotsBetMenu(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(new SlotsBetMenuHolder(), 27,
                "§6§lApostar en Slots");
        int[] fichaSlots = { 10, 11, 12, 13, 14, 15, 16 };

        // Fondo: paneles púrpuras en todo
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, MenuUtils.createButton("", Material.PURPLE_STAINED_GLASS_PANE));
        }
        // Puntos rojos donde van las fichas
        for (int slot : fichaSlots) {
            inv.setItem(slot, MenuUtils.createButton("", Material.RED_STAINED_GLASS_PANE));
        }
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

    public static boolean isSlotsBetMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof SlotsBetMenuHolder;
    }
}
