package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.items.GameItemType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class GameItemInteractListener implements Listener {

    private final GamblingDexPlugin plugin;

    public GameItemInteractListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
            return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        GameItemType type = plugin.getGameItemManager().getType(item);
        if (type == null)
            return;

        event.setCancelled(true);

        switch (type) {
            case ROULETTE -> {
                player.sendMessage(plugin.getMessages().getString("items.roulette.header", "&6&lRULETA"));
                player.sendMessage(plugin.getMessages().getString("items.roulette.line1",
                        "&7Esta ruleta se juega en una mesa física."));
                if (player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(plugin.getMessages().getString("items.roulette.admin_hint",
                            "&8- &f/gdx roulette build [radius]&7 para construir una."));
                }
                player.sendMessage(plugin.getMessages().getString("items.roulette.line2",
                        "&8- &7Luego haz &fclick&7 al centro/números con tokens para apostar."));
            }
            case SLOTS -> plugin.getSlotsController().open(player);
            case EXCHANGE -> plugin.getExchangeMenu().open(player);
        }
    }
}
