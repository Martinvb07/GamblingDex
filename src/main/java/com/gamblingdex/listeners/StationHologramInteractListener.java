package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.items.GameItemType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

public class StationHologramInteractListener implements Listener {

    private final GamblingDexPlugin plugin;

    public StationHologramInteractListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;
        Location stationLoc = plugin.getStationManager()
                .getStationLocationByHologram(event.getRightClicked().getUniqueId());
        if (stationLoc == null)
            return;

        GameItemType type = plugin.getStationManager().getStationType(stationLoc);
        if (type == null) {
            String debug = plugin.getStationManager().debugLocation(stationLoc);
            plugin.getLogger().warning("[StationHolo] Tipo de estación no encontrado: " + debug);
            return;
        }
        if (type == GameItemType.ROULETTE)
            return; // roulette is physical via /gdx roulette build

        event.setCancelled(true);

        Player player = event.getPlayer();
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
}
