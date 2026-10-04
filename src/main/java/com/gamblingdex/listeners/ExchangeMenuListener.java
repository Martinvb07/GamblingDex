package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.gui.ExchangeMenu;
import com.gamblingdex.gui.ExchangeMenuHolder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ExchangeMenuListener implements Listener {

    private final GamblingDexPlugin plugin;

    // Anti double-click / spam protection
    private final Map<UUID, Long> lastActionMs = new HashMap<>();
    private final Set<UUID> processing = new HashSet<>();
    private static final long DEBOUNCE_MS = 400L;

    public ExchangeMenuListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (!(event.getInventory().getHolder() instanceof ExchangeMenuHolder))
            return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null)
            return;

        int slot = event.getRawSlot();
        int buySlot = plugin.getConfig().getInt("gui.exchange.buttons.buy.slot", 10);
        int sellHandSlot = plugin.getConfig().getInt("gui.exchange.buttons.sell_hand.slot", 12);
        int sellAllSlot = plugin.getConfig().getInt("gui.exchange.buttons.sell_all.slot", 14);

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        // only debounce actions that actually do something
        boolean isActionSlot = (slot == buySlot || slot == sellHandSlot || slot == sellAllSlot);
        if (isActionSlot) {
            Long last = lastActionMs.get(id);
            if (last != null && (now - last) < DEBOUNCE_MS) {
                return;
            }
            if (processing.contains(id)) {
                return;
            }
            lastActionMs.put(id, now);
        }

        if (slot == buySlot) {
            // NUEVO: abrir menú de selección de ficha
            plugin.getTokenTypeMenu().open(player);
            return;
        }

        if (slot == sellHandSlot) {
            // NUEVO: abrir menú de selección de ficha para vender
            plugin.getTokenSellMenu().open(player);
            return;
        }

        if (slot == sellAllSlot) {
            processing.add(id);
            try {
                long totalUnits = plugin.exchangeTakeAllTokens(player);
                if (totalUnits <= 0) {
                    player.sendMessage(plugin.getMessages().getString(
                            "messages.exchange.sell_all.no_tokens",
                            "&cNo tienes tokens para vender."));
                    return;
                }

                double money = totalUnits * plugin.getConfig().getDouble("exchange.money_per_unit", 1.0);
                boolean ok = plugin.exchangeGiveMoney(player, money);
                if (!ok) {
                    // Refund tokens if economy is not available / deposit failed.
                    plugin.exchangeGiveTokens(player, totalUnits);
                    player.sendMessage(plugin.getMessages().getString(
                            "messages.exchange.sell_all.deposit_failed",
                            "&cNo se pudo darte dinero (¿Vault/Economía?). No se vendieron tus tokens."));
                    return;
                }

                player.sendMessage(plugin.getMessages().format(
                        "messages.exchange.sell_all.success",
                        "&aVendiste TODO por &e{money}&a de dinero del server.",
                        Map.of("money", String.valueOf(money))));
            } finally {
                processing.remove(id);
            }
        }
    }
}
