package com.gamblingdex.economy;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

/**
 * Cobrar y pagar en fichas (tokens del inventario) desde cualquier juego.
 */
public final class TokenWallet {

    private TokenWallet() {
    }

    private static GamblingDexPlugin plugin() {
        return GamblingDexPlugin.getInstance();
    }

    /** Valor total de las fichas que el jugador tiene en el inventario. */
    public static long balance(Player player) {
        if (player == null)
            return 0L;
        TokenManager tm = plugin().getTokenManager();
        long total = 0L;
        for (ItemStack it : player.getInventory().getContents()) {
            Integer v = tm.getTokenValue(it);
            if (v != null)
                total += (long) v * it.getAmount();
        }
        return total;
    }

    /**
     * Cobra exactamente {@code amount}: quita fichas (de las más chicas a las más
     * grandes) y devuelve el vuelto. Si no alcanza, no quita nada.
     */
    public static boolean take(Player player, long amount) {
        if (player == null || amount < 0)
            return false;
        if (amount == 0)
            return true;
        if (balance(player) < amount)
            return false;

        TokenManager tm = plugin().getTokenManager();
        PlayerInventory inv = player.getInventory();
        long removed = 0L;

        // Primero fichas de menor valor, para no romper fichas grandes sin necesidad.
        while (removed < amount) {
            int bestSlot = -1;
            int bestValue = Integer.MAX_VALUE;
            for (int i = 0; i < inv.getSize(); i++) {
                Integer v = tm.getTokenValue(inv.getItem(i));
                if (v != null && v < bestValue) {
                    bestValue = v;
                    bestSlot = i;
                }
            }
            if (bestSlot < 0)
                break;
            ItemStack it = inv.getItem(bestSlot);
            long need = amount - removed;
            long canTake = Math.min(it.getAmount(), Math.max(1L, (need + bestValue - 1) / bestValue));
            int take = (int) canTake;
            removed += (long) take * bestValue;
            if (take >= it.getAmount()) {
                inv.setItem(bestSlot, null);
            } else {
                it.setAmount(it.getAmount() - take);
                inv.setItem(bestSlot, it);
            }
        }

        if (removed < amount) {
            // No debería pasar (ya se verificó el saldo): devolver lo quitado.
            give(player.getUniqueId(), removed);
            return false;
        }
        if (removed > amount) {
            give(player.getUniqueId(), removed - amount);
        }
        return true;
    }

    /** Paga en fichas; si el jugador no está conectado, se le paga al volver. */
    public static void give(UUID playerId, long amount) {
        if (playerId == null || amount <= 0)
            return;
        Player p = Bukkit.getPlayer(playerId);
        if (p != null && p.isOnline()) {
            plugin().getTokenPayout().pay(p, amount);
        } else if (plugin().getPendingPayouts() != null) {
            plugin().getPendingPayouts().add(playerId, amount);
        }
    }
}
