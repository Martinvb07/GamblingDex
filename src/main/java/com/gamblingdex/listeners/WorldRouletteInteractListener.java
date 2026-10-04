package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.rouletteworld.WorldRouletteTable;
import com.gamblingdex.gui.RouletteBetMenu;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class WorldRouletteInteractListener implements Listener {

    private final GamblingDexPlugin plugin;

    public WorldRouletteInteractListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK)
            return;

        Block clicked = event.getClickedBlock();
        if (clicked == null)
            return;

        WorldRouletteTable table = plugin.getWorldRouletteManager().getByBlock(clicked);
        if (table == null)
            return;

        // Ignore if the block was rebuilt into something else.
        if (table.isCenter(clicked) && clicked.getType() != org.bukkit.Material.LODESTONE) {
            // stale entry, remove
            plugin.getWorldRouletteManager().removeTable(clicked, true);
            return;
        }

        event.setCancelled(true);
        if (plugin.getMaintenance() != null && !plugin.getMaintenance().allow(event.getPlayer(), "ruleta"))
            return;

        boolean isRight = action == Action.RIGHT_CLICK_BLOCK;
        boolean isLeft = action == Action.LEFT_CLICK_BLOCK;

        if (table.isCenter(clicked)) {
            if (isLeft) {
                new RouletteBetMenu(plugin).open(event.getPlayer(), table);
                return;
            }

            // right click center -> place bet with tokens in hand
            ItemStack hand = event.getItem();
            Integer tokenValue = plugin.getTokenManager().getTokenValue(hand);
            if (tokenValue == null) {
                new RouletteBetMenu(plugin).open(event.getPlayer(), table);
                return;
            }

            int take = event.getPlayer().isSneaking() ? hand.getAmount() : 1;
            long units = (long) tokenValue * (long) take;
            boolean ok = table.placeBet(event.getPlayer(), units);
            if (ok) {
                int newAmount = hand.getAmount() - take;
                if (newAmount <= 0) {
                    event.getPlayer().getInventory().setItemInMainHand(null);
                } else {
                    hand.setAmount(newAmount);
                }
            }
            return;
        }

        // segment
        Integer number = table.getNumberForBlock(clicked);
        if (number == null)
            return;

        if (isLeft) {
            table.selectNumber(event.getPlayer(), number);
            return;
        }

        // right click segment: select number and optionally bet
        table.selectNumber(event.getPlayer(), number);

        ItemStack hand = event.getItem();
        Integer tokenValue = plugin.getTokenManager().getTokenValue(hand);
        if (tokenValue == null) {
            event.getPlayer().sendMessage(plugin.getMessages().getString(
                    "roulette_world.now_bet_with_tokens",
                    "&7Ahora apuesta con tokens (click derecho al centro o al número)."));
            return;
        }

        int take = event.getPlayer().isSneaking() ? hand.getAmount() : 1;
        long units = (long) tokenValue * (long) take;
        boolean ok = table.placeBet(event.getPlayer(), units);
        if (ok) {
            int newAmount = hand.getAmount() - take;
            if (newAmount <= 0) {
                event.getPlayer().getInventory().setItemInMainHand(null);
            } else {
                hand.setAmount(newAmount);
            }
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        WorldRouletteTable table = plugin.getWorldRouletteManager().getByBlock(block);
        if (table == null)
            return;

        if (!event.getPlayer().hasPermission("gamblingdex.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getMessages().getString(
                    "roulette_world.cannot_break",
                    "&cNo puedes romper una ruleta de GamblingDex."));
            return;
        }

        // If they break center, unregister table (do not touch blocks; just remove
        // displays)
        if (table.isCenter(block)) {
            plugin.getWorldRouletteManager().removeTable(block, true);
            event.getPlayer().sendMessage(plugin.getMessages().getString(
                    "roulette_world.removed",
                    "&aRuleta eliminada."));
        }
    }
}
