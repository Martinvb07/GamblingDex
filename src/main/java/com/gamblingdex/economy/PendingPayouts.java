package com.gamblingdex.economy;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Fichas que se le deben a jugadores que estaban desconectados cuando se
 * resolvió su apuesta (ruleta/blackjack). Se guardan en pending_payouts.yml y
 * se entregan cuando el jugador vuelve a entrar.
 */
public class PendingPayouts {

    private final GamblingDexPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    public PendingPayouts(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending_payouts.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public void add(UUID playerId, long units) {
        if (playerId == null || units <= 0)
            return;
        String path = "players." + playerId;
        data.set(path, data.getLong(path, 0L) + units);
        save();
    }

    /** Entrega lo pendiente (si hay) y lo borra del archivo. */
    public void claim(Player player) {
        if (player == null)
            return;
        String path = "players." + player.getUniqueId();
        long units = data.getLong(path, 0L);
        if (units <= 0)
            return;
        data.set(path, null);
        save();
        plugin.getTokenPayout().pay(player, units);
        player.sendMessage(plugin.getMessages().format(
                "gdx.pending_payout",
                "&aTe entregamos &e{amount}&a en fichas de apuestas que se resolvieron mientras no estabas.",
                Map.of("amount", String.valueOf(units))));
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Error guardando pending_payouts.yml: " + e.getMessage());
        }
    }
}
