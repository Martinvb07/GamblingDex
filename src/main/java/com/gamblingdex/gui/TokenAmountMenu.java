package com.gamblingdex.gui;

import com.gamblingdex.economy.TokenManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

public class TokenAmountMenu {
    private final TokenManager tokenManager;
    private final Material material;
    private int amount;

    public TokenAmountMenu(TokenManager tokenManager, Material material, int initialAmount) {
        this.tokenManager = tokenManager;
        this.material = material;
        this.amount = initialAmount;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(new TokenAmountMenuHolder(), 27, ChatColor.GREEN + "Cantidad de fichas");
        // Ficha seleccionada
        ItemStack token = tokenManager.createToken(material, 1);
        inv.setItem(13, token);
        // Opciones de cantidad
        inv.setItem(10, MenuUtils.createButton("§a+1", Material.LIME_WOOL));
        inv.setItem(11, MenuUtils.createButton("§a+10", Material.LIME_WOOL));
        inv.setItem(12, MenuUtils.createButton("§a+50", Material.LIME_WOOL));
        inv.setItem(14, MenuUtils.createButton("§c-1", Material.RED_WOOL));
        inv.setItem(15, MenuUtils.createButton("§c-10", Material.RED_WOOL));
        inv.setItem(16, MenuUtils.createButton("§c-50", Material.RED_WOOL));
        // Confirmar
        inv.setItem(22, MenuUtils.createButton("§eComprar x" + amount, Material.EMERALD));
        player.openInventory(inv);
    }

    public static boolean isTokenAmountMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof TokenAmountMenuHolder;
    }
}
