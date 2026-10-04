package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.slots.SlotsController;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class SlotsMenuListener implements Listener {

    private final GamblingDexPlugin plugin;

    public SlotsMenuListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        Inventory inv = event.getInventory();
        SlotsController controller = plugin.getSlotsController();
        if (!controller.isSlotsInventory(inv, player))
            return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        int slot = event.getRawSlot();

        if (controller.isBetSlot(slot)) {
            boolean clickedIsToken = clicked != null && clicked.getType() != Material.AIR
                    && plugin.getTokenManager().isToken(clicked);
            if (clickedIsToken) {
                int removed = controller.removeBetTokens(player, clicked.getType());
                if (removed > 0) {
                    int left = removed;
                    while (left > 0) {
                        int give = Math.min(64, left);
                        ItemStack stack = plugin.getTokenManager().createToken(clicked.getType(), give);
                        var leftover = player.getInventory().addItem(stack);
                        if (!leftover.isEmpty()) {
                            leftover.values()
                                    .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                        }
                        left -= give;
                    }
                    controller.refresh(player, inv);
                }
                return;
            }
        }

        if (slot >= inv.getSize()) {
            if (clicked != null && clicked.getType() != Material.AIR && plugin.getTokenManager().isToken(clicked)) {
                int toAdd = event.isRightClick() ? 1 : clicked.getAmount();
                int added = controller.addBetTokens(player, clicked.getType(), toAdd);
                if (added > 0) {
                    int remaining = clicked.getAmount() - added;
                    if (remaining <= 0) {
                        event.setCurrentItem(null);
                    } else {
                        clicked.setAmount(remaining);
                        event.setCurrentItem(clicked);
                    }
                    controller.refresh(player, inv);
                }
            }
            return;
        }

        if (clicked == null)
            return;
        controller.handleClick(player, inv, clicked);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.getSlotsController().handleClose(player);
        }
    }
}
