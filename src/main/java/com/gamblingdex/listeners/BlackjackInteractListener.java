package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.blackjack.BlackjackTable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

public class BlackjackInteractListener implements Listener {

    private final GamblingDexPlugin plugin;

    public BlackjackInteractListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;

        if (plugin.getBlackjackManager() == null)
            return;

        // El dealer (villager) no se puede usar como aldeano; click derecho = reabrir tu menú.
        BlackjackTable table = plugin.getBlackjackManager().getByDealer(event.getRightClicked().getUniqueId());
        if (table == null)
            return;

        event.setCancelled(true);
        table.reopenMenu(event.getPlayer());
    }

    /** Click derecho a la mesa (bloque central): también reabre el menú. */
    @EventHandler
    public void onInteractTable(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || plugin.getBlackjackManager() == null)
            return;
        BlackjackTable table = plugin.getBlackjackManager().getByBlock(event.getClickedBlock());
        if (table == null || !table.isSeated(event.getPlayer().getUniqueId()))
            return;
        if (table.reopenMenu(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (plugin.getBlackjackManager() == null)
            return;
        BlackjackTable table = plugin.getBlackjackManager().getByBlock(event.getBlock());
        if (table == null)
            return;

        if (!event.getPlayer().hasPermission("gamblingdex.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getMessages().getString(
                    "blackjack.cannot_break",
                    "&cNo puedes romper una mesa de Blackjack."));
            return;
        }

        // Admin breaks center -> remove table (displays) and unregister.
        if (table.isCenter(event.getBlock())) {
            plugin.getBlackjackManager().removeTable(event.getBlock(), true);
            event.getPlayer().sendMessage(plugin.getMessages().getString(
                    "blackjack.removed",
                    "&aMesa de Blackjack eliminada."));
        }
    }
}
