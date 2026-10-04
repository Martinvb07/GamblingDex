package com.gamblingdex.economy;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class TokenPayout {

    private final TokenManager tokenManager;

    public TokenPayout(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
    }

    /**
     * Pays the given amount (in internal currency units) using token items.
     */
    public void pay(Player player, long amountUnits) {
        if (player == null || amountUnits <= 0)
            return;
        List<ItemStack> stacks = tokenManager.createTokensForValue(amountUnits);
        for (ItemStack stack : stacks) {
            var leftover = player.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                for (ItemStack lf : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), lf);
                }
            }
        }
    }
}
