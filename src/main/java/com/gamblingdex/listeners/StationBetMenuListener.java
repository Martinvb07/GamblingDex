package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.gui.StationBetMenu;
import com.gamblingdex.gui.StationBetMenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

public class StationBetMenuListener implements Listener {
    private final GamblingDexPlugin plugin;

    public StationBetMenuListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inv = event.getInventory();
        if (StationBetMenu.isStationBetMenu(inv)) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player))
                return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null)
                return;
            if (plugin.getTokenManager().isToken(clicked)) {
                // Aquí iría la lógica para apostar con la ficha seleccionada
                String token = clicked.getType().name().toLowerCase();
                player.sendMessage(plugin.getMessages().format(
                        "menus.bet.token_selected",
                        "&aApostaste con una ficha {token}",
                        java.util.Map.of("token", token)));
                // Implementar lógica de apuesta real aquí
            }
        }
    }
}
