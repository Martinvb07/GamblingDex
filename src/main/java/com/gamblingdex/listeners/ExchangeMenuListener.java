package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.gui.ExchangeMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Clicks del menú de cambio (la lógica está en {@link ExchangeMenu}). */
public class ExchangeMenuListener implements Listener {

    private final GamblingDexPlugin plugin;

    public ExchangeMenuListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (!(event.getInventory().getHolder() instanceof ExchangeMenu.Session s))
            return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory())
            return;
        boolean shift = event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;
        plugin.getExchangeMenu().handleClick(player, s, event.getRawSlot(), shift);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ExchangeMenu.Session)
            event.setCancelled(true);
    }
}
