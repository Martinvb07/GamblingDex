package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.gui.TokenTypeMenu;
import com.gamblingdex.gui.TokenAmountMenu;
import com.gamblingdex.gui.TokenSellMenu;
import com.gamblingdex.gui.TokenSellAmountMenu;
import com.gamblingdex.gui.MenuUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class TokenMenuListener implements Listener {
    private final GamblingDexPlugin plugin;
    private final TokenManager tokenManager;
    private final Map<UUID, Integer> tokenAmounts = new HashMap<>();

    // Anti double-click / spam protection (buy/sell confirmations)
    private final Map<UUID, Long> lastTxnMs = new HashMap<>();
    private final Set<UUID> processing = new HashSet<>();
    private static final long DEBOUNCE_MS = 400L;

    public TokenMenuListener(GamblingDexPlugin plugin, TokenManager tokenManager) {
        this.plugin = plugin;
        this.tokenManager = tokenManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        Inventory inv = event.getInventory();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null)
            return;

        // Menú de selección de tipo de ficha
        if (TokenTypeMenu.isTokenTypeMenu(inv)) {
            event.setCancelled(true);
            Material mat = clicked.getType();
            if (TokenManager.getDenoms().containsKey(mat)) {
                tokenAmounts.put(player.getUniqueId(), 1);
                new TokenAmountMenu(tokenManager, mat, 1).open(player);
            }
        }
        // Menú de cantidad
        else if (TokenAmountMenu.isTokenAmountMenu(inv)) {
            event.setCancelled(true);
            String name = clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "";
            int currentAmount = tokenAmounts.getOrDefault(player.getUniqueId(), 1);
            if (name.equals("§a+1")) {
                currentAmount = Math.min(64, currentAmount + 1);
            } else if (name.equals("§a+10")) {
                currentAmount = Math.min(64, currentAmount + 10);
            } else if (name.equals("§a+50")) {
                currentAmount = Math.min(64, currentAmount + 50);
            } else if (name.equals("§c-1")) {
                currentAmount = Math.max(1, currentAmount - 1);
            } else if (name.equals("§c-10")) {
                currentAmount = Math.max(1, currentAmount - 10);
            } else if (name.equals("§c-50")) {
                currentAmount = Math.max(1, currentAmount - 50);
            }

            if (!name.startsWith("§eComprar x")) {
                tokenAmounts.put(player.getUniqueId(), currentAmount);
                inv.setItem(22, MenuUtils.createButton("§eComprar x" + currentAmount, Material.EMERALD));
                return;
            }

            if (name.startsWith("§eComprar x")) {
                UUID id = player.getUniqueId();
                long now = System.currentTimeMillis();
                Long last = lastTxnMs.get(id);
                if (last != null && (now - last) < DEBOUNCE_MS)
                    return;
                if (processing.contains(id))
                    return;
                lastTxnMs.put(id, now);
                processing.add(id);

                int amount = tokenAmounts.getOrDefault(player.getUniqueId(), 1);
                Material mat = inv.getItem(13).getType();
                Integer denom = TokenManager.getDenoms().get(mat);
                if (denom == null) {
                    player.sendMessage(plugin.getMessages().getString(
                            "messages.exchange.buy.token_value_unknown",
                            "&cNo se pudo determinar el valor de esa ficha."));
                    player.closeInventory();
                    processing.remove(id);
                    return;
                }

                double unitPrice = plugin.getConfig().getDouble("exchange.money_per_unit", 1.0);
                double cost = amount * unitPrice * denom;
                if (!plugin.exchangeTakeMoney(player, cost)) {
                    player.sendMessage(plugin.getMessages().getString(
                            "messages.exchange.buy.not_enough_money",
                            "&cNo tienes dinero suficiente o no hay economía disponible."));
                    player.closeInventory();
                    processing.remove(id);
                    return;
                }

                ItemStack tokens = tokenManager.createToken(mat, amount);
                var leftover = player.getInventory().addItem(tokens);
                if (!leftover.isEmpty()) {
                    for (ItemStack lf : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), lf);
                    }
                }
                player.sendMessage(plugin.getMessages().format(
                        "messages.exchange.buy.success",
                        "&a¡Has comprado &e{amount}&a ficha(s) por &e{money}&a!",
                        Map.of(
                                "amount", String.valueOf(amount),
                                "money", String.valueOf(cost))));
                tokenAmounts.remove(player.getUniqueId());
                player.closeInventory();
                processing.remove(id);
            }
        }
        // Menú de selección de ficha para vender
        else if (TokenSellMenu.isTokenSellMenu(inv)) {
            event.setCancelled(true);
            Material mat = clicked.getType();
            int maxAmount = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == mat && tokenManager.isToken(item)) {
                    maxAmount += item.getAmount();
                }
            }
            if (maxAmount > 0) {
                new TokenSellAmountMenu(tokenManager, mat, maxAmount, 1).open(player);
            }
        }
        // Menú de cantidad para vender
        else if (TokenSellAmountMenu.isTokenSellAmountMenu(inv)) {
            event.setCancelled(true);
            String name = clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "";

            // La cantidad sale del botón (slot) clickeado. Antes se usaba
            // name.contains("1"), que hacía que "10", "100" y el relleno vendieran 1.
            int amount;
            switch (event.getRawSlot()) {
                case 10 -> amount = 1;
                case 11 -> amount = 10;
                case 12 -> amount = 50;
                case 14 -> amount = 100;
                case 22 -> {
                    try {
                        amount = Integer.parseInt(name.replace("§eVender x", "").trim());
                    } catch (Exception ignored) {
                        return;
                    }
                }
                default -> {
                    return; // relleno, la ficha del centro o tu propio inventario
                }
            }
            if (clicked.getType() == Material.AIR || amount <= 0)
                return;

            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastTxnMs.get(id);
            if (last != null && (now - last) < DEBOUNCE_MS)
                return;
            if (processing.contains(id))
                return;
            lastTxnMs.put(id, now);
            processing.add(id);

            Material mat = inv.getItem(13).getType();
            Integer denom = TokenManager.getDenoms().get(mat);
            var lock = plugin.getBonusLock();
            if (denom != null && lock != null && lock.locked(player.getUniqueId()) > 0
                    && (long) amount * denom > lock.sellable(player)) {
                player.sendMessage(lock.blockedMessage(player));
                player.closeInventory();
                processing.remove(id);
                return;
            }
            if (denom == null) {
                player.sendMessage(plugin.getMessages().getString(
                        "messages.exchange.sell.token_value_unknown",
                        "&cNo se pudo determinar el valor de esa ficha."));
                player.closeInventory();
                processing.remove(id);
                return;
            }

            int sold = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == mat && tokenManager.isToken(item)) {
                    int take = Math.min(item.getAmount(), amount - sold);
                    item.setAmount(item.getAmount() - take);
                    sold += take;
                    if (item.getAmount() <= 0)
                        item.setType(Material.AIR);
                    if (sold >= amount)
                        break;
                }
            }
            if (sold > 0) {
                double money = sold * plugin.getConfig().getDouble("exchange.money_per_unit", 1.0) * denom;
                boolean ok = plugin.exchangeGiveMoney(player, money);
                if (!ok) {
                    // Refund tokens if economy is not available / deposit failed.
                    ItemStack refund = tokenManager.createToken(mat, sold);
                    var leftover = player.getInventory().addItem(refund);
                    if (!leftover.isEmpty()) {
                        for (ItemStack lf : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), lf);
                        }
                    }
                    player.sendMessage(plugin.getMessages().getString(
                            "messages.exchange.sell.deposit_failed",
                            "&cNo se pudo darte dinero (¿Vault/Economía?). No se vendieron tus fichas."));
                    player.closeInventory();
                    processing.remove(id);
                    return;
                }

                player.sendMessage(plugin.getMessages().format(
                        "messages.exchange.sell.success",
                        "&aVendiste &e{amount}&a fichas por &e{money}&a de dinero del server.",
                        Map.of(
                                "amount", String.valueOf(sold),
                                "money", String.valueOf(money))));
            } else {
                player.sendMessage(plugin.getMessages().getString(
                        "messages.exchange.sell.not_enough_tokens",
                        "&cNo tienes suficientes fichas para vender."));
            }
            player.closeInventory();
            processing.remove(id);
        }
    }
}
