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

        // El dealer (villager) es decorativo: NO se puede interactuar.
        BlackjackTable table = plugin.getBlackjackManager().getByDealer(event.getRightClicked().getUniqueId());
        if (table == null)
            return;

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
