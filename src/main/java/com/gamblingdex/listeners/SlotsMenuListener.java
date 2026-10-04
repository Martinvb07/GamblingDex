package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.slots.SlotsController;
import com.gamblingdex.games.slots.SlotsHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class SlotsMenuListener implements Listener {

    private final GamblingDexPlugin plugin;

    public SlotsMenuListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        SlotsController controller = plugin.getSlotsController();
        if (!controller.isSlotsInventory(event.getInventory(), player))
            return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory())
            return;
        boolean shift = event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;
        controller.handleClick(player, event.getInventory(), event.getRawSlot(), shift);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SlotsHolder)
            event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory().getHolder() instanceof SlotsHolder)
            plugin.getSlotsController().handleClose(player);
    }
}
