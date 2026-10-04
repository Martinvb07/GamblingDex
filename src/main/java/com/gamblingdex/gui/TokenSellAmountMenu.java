package com.gamblingdex.gui;

import com.gamblingdex.economy.TokenManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

public class TokenSellAmountMenu {
    private final TokenManager tokenManager;
    private final Material material;
    private final int maxAmount;
    private int amount;

    public TokenSellAmountMenu(TokenManager tokenManager, Material material, int maxAmount, int initialAmount) {
        this.tokenManager = tokenManager;
        this.material = material;
        this.maxAmount = maxAmount;
        this.amount = initialAmount;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(new TokenSellAmountMenuHolder(), 27,
                ChatColor.RED + "Cantidad a vender");
        // Ficha seleccionada
        ItemStack token = tokenManager.createToken(material, 1);
        inv.setItem(13, token);
        // Opciones de cantidad (solo si el jugador tiene suficiente)
        if (maxAmount >= 1)
            inv.setItem(10, MenuUtils.createButton("§aVender 1", Material.LIME_WOOL));
        if (maxAmount >= 10)
            inv.setItem(11, MenuUtils.createButton("§aVender 10", Material.LIME_WOOL));
        if (maxAmount >= 50)
            inv.setItem(12, MenuUtils.createButton("§aVender 50", Material.LIME_WOOL));
        if (maxAmount >= 100)
            inv.setItem(14, MenuUtils.createButton("§aVender 100", Material.LIME_WOOL));
        // Confirmar personalizado
        inv.setItem(22, MenuUtils.createButton("§eVender x" + amount, Material.GOLD_INGOT));
        player.openInventory(inv);
    }

    public static boolean isTokenSellAmountMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof TokenSellAmountMenuHolder;
    }
}
