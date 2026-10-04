package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.gui.BuyTokenMenu;
import com.gamblingdex.gui.BuyTokenMenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

public class BuyTokenMenuListener implements Listener {
    private final GamblingDexPlugin plugin;

    public BuyTokenMenuListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        if (BuyTokenMenu.isBuyTokenMenu(inv)) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player))
                return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null)
                return;
            if (plugin.getTokenManager().isToken(clicked)) {
                // Aquí iría la lógica para comprar la ficha seleccionada
                String token = clicked.getType().name().toLowerCase();
                player.sendMessage(plugin.getMessages().format(
                        "menus.buy_token.bought",
                        "&aHas comprado una ficha {token}",
                        java.util.Map.of("token", token)));
                // Implementar lógica de compra real aquí
            }
        }
    }
}
