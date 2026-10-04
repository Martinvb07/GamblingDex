package com.gamblingdex.commands;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class BalanceCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().getString(
                    "messages.gdx.console_only",
                    "Solo jugadores pueden usar este comando."));
            return true;
        }

        if (!player.hasPermission("gamblingdex.use")) {
            player.sendMessage(plugin.getMessages().getString(
                    "messages.gdx.no_permission",
                    "&cNo tienes permiso para hacer eso."));
            return true;
        }

        long balance = plugin.getEconomy().getBalance(player.getUniqueId());
        String currencyName = plugin.color(plugin.getConfig().getString("currency.name", "Moneda GDX"));
        player.sendMessage(plugin.getMessages().format(
                "messages.gdx.balance",
                "&aTu balance es: &e{amount}&a {currency}.",
                Map.of(
                        "amount", String.valueOf(balance),
                        "currency", currencyName)));
        return true;
    }
}
