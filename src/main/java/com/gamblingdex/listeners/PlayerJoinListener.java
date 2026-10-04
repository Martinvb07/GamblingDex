package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final GamblingDexPlugin plugin;

    public PlayerJoinListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            if (plugin != null && plugin.getPlayerIndex() != null) {
                plugin.getPlayerIndex().update(event.getPlayer());
                // Save opportunistically so name lookups work after restart.
                plugin.getPlayerIndex().save();
            }
        } catch (Throwable ignored) {
        }
        // Fichas de apuestas que se resolvieron mientras estaba desconectado.
        // Un tick después para que el inventario ya esté cargado.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline() && plugin.getPendingPayouts() != null) {
                plugin.getPendingPayouts().claim(event.getPlayer());
            }
        }, 20L);
        event.getPlayer().sendMessage(plugin.getMessages().getString(
                "join.tip",
                "&7[&aGamblingDex&7] &fEscribe &e/gamblingdex &fpara ver los comandos."));
    }
}
