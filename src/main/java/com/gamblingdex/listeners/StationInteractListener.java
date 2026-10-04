package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.items.GameItemType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event.Result;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class StationInteractListener implements Listener {

    private final GamblingDexPlugin plugin;

    public StationInteractListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onStationClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK)
            return;

        Block clicked = event.getClickedBlock();
        if (clicked == null)
            return;

        GameItemType type = plugin.getStationManager().getStationType(clicked);
        if (type == null)
            return;

        // Roulette is handled as a physical table (world roulette), not a GUI.
        if (type == GameItemType.ROULETTE)
            return;

        // Only respond if the block is still the expected material.
        if (clicked.getType() != type.getMaterial()) {
            // Cleanup stale station entry
            plugin.getStationManager().removeStation(clicked.getLocation());
            return;
        }

        Player player = event.getPlayer();
        event.setCancelled(true);
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);

        switch (type) {
            case SLOTS -> {
                if (plugin.getMaintenance() == null || plugin.getMaintenance().allow(player, "slots"))
                    plugin.getSlotsController().open(player);
            }
            case EXCHANGE -> plugin.getExchangeMenu().open(player);
            default -> {
            }
        }
    }

    @EventHandler
    public void onStationBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        GameItemType type = plugin.getStationManager().getStationType(block);
        if (type == null)
            return;

        if (!event.getPlayer().hasPermission("gamblingdex.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getMessages().getString(
                    "stations.break_denied",
                    "&cNo puedes romper una mesa de GamblingDex."));
            return;
        }

        // Allow break but remove station registration.
        plugin.getStationManager().removeStation(block.getLocation());
        event.getPlayer().sendMessage(plugin.getMessages().format(
                "stations.removed",
                "&aMesa eliminada: &f{type}",
                java.util.Map.of("type", type.getId())));
    }
}
