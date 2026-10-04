package com.gamblingdex.gui;

import com.gamblingdex.economy.TokenManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import java.util.Map;

public class BuyTokenMenu {
    private final TokenManager tokenManager;

    public BuyTokenMenu(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(new BuyTokenMenuHolder(), 27,
                "§e§lElige una ficha");

        // Bordes decorativos: cristal negro
        for (int i = 0; i < inv.getSize(); i++) {
            if (i < 9 || i > 17 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, MenuUtils.createButton("", Material.BLACK_STAINED_GLASS_PANE));
            }
        }

        // Fichas centradas y coloridas
        Material[] colores = {
                Material.YELLOW_DYE, Material.PINK_DYE, Material.BLUE_DYE, Material.LIME_DYE,
                Material.PURPLE_DYE, Material.ORANGE_DYE, Material.MAGENTA_DYE, Material.BLACK_DYE
        };
        int[] fichaSlots = { 10, 11, 12, 13, 14, 15, 16, 17 };
        int idx = 0;
        for (Map.Entry<Material, Integer> entry : TokenManager.getDenoms().entrySet()) {
            if (idx >= fichaSlots.length)
                break;
            Material mat = entry.getKey();
            ItemStack token = tokenManager.createToken(mat, 1);
            org.bukkit.inventory.meta.ItemMeta meta = token.getItemMeta();
            if (meta != null) {
                java.util.List<String> lore = new java.util.ArrayList<>();
                lore.add("§7Haz clic para comprar esta ficha");
                lore.add("§fValor: §e" + entry.getValue() + " ⛃");
                meta.setLore(lore);
                meta.setDisplayName("§f" + mat.name().replace("_DYE", "").toLowerCase().replace('_', ' '));
                token.setItemMeta(meta);
            }
            inv.setItem(fichaSlots[idx], token);
            idx++;
        }

        // Rellenar espacios vacíos con cristal gris
        for (int i : fichaSlots) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, MenuUtils.createButton("", Material.LIGHT_GRAY_STAINED_GLASS_PANE));
            }
        }

        player.openInventory(inv);
    }

    public static boolean isBuyTokenMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof BuyTokenMenuHolder;
    }
}
