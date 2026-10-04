package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.GdxEconomy;
import com.gamblingdex.economy.TokenManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class TokenRedeemListener implements Listener {

    private final GamblingDexPlugin plugin;

    public TokenRedeemListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND)
            return;

        // Disabled by default: tokens are meant to be used in machines/menus.
        // Enable only if you really want "right click air to redeem".
        if (!plugin.getConfig().getBoolean("currency.token.redeem.enabled", false))
            return;

        Action action = event.getAction();
        // Only redeem on AIR so it doesn't hijack station/roulette interactions.
        if (action != Action.RIGHT_CLICK_AIR)
            return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        TokenManager tokenManager = plugin.getTokenManager();
        if (!tokenManager.isToken(item))
            return;

        Integer unitValue = tokenManager.getTokenValue(item);
        if (unitValue == null || unitValue <= 0)
            return;

        event.setCancelled(true);

        int redeemItems = player.isSneaking() ? item.getAmount() : 1;
        if (redeemItems <= 0)
            return;
        var lock = plugin.getBonusLock();
        if (lock != null && lock.locked(player.getUniqueId()) > 0
                && (long) unitValue * redeemItems > lock.sellable(player)) {
            player.sendMessage(lock.blockedMessage(player));
            return;
        }

        item.setAmount(item.getAmount() - redeemItems);

        long redeemValue = (long) unitValue * (long) redeemItems;

        GdxEconomy economy = plugin.getEconomy();
        economy.deposit(player.getUniqueId(), redeemValue);

        String currencyName = plugin.color(plugin.getConfig().getString("currency.name", "Moneda GDX"));
        player.sendMessage(plugin.getMessages().format(
                "messages.gdx.redeem_success",
                "&aCanjeaste &e{amount}&a {currency}.",
                Map.of(
                        "amount", String.valueOf(redeemValue),
                        "currency", currencyName)));

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }
}
