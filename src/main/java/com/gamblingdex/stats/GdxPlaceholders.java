package com.gamblingdex.stats;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Placeholders de GamblingDex para PlaceholderAPI (DecentHolograms, scoreboards...).
 *
 * <pre>
 * %gamblingdex_balance%                 fichas del jugador
 * %gamblingdex_profit%                  ganancia neta (todos los juegos)
 * %gamblingdex_wagered%                 total apostado
 * %gamblingdex_paid%                    total cobrado
 * %gamblingdex_biggest%                 mejor premio (ganancia de una sola apuesta)
 * %gamblingdex_rounds%                  apuestas jugadas
 * %gamblingdex_rank%                    puesto en el top de ganancias
 * %gamblingdex_weekly_profit%           (igual con weekly_ para la semana actual)
 * %gamblingdex_top_profit_1_name%       nombre del 1° en ganancias
 * %gamblingdex_top_profit_1_value%      su ganancia
 * %gamblingdex_topweek_profit_1_name%   lo mismo pero de la semana
 *   (métricas: profit, wagered, paid, biggest)
 * %gamblingdex_jackpot_ruleta%          pozo del jackpot de la ruleta
 * </pre>
 */
public class GdxPlaceholders extends PlaceholderExpansion {

    private final GamblingDexPlugin plugin;

    public GdxPlaceholders(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "gamblingdex";
    }

    @Override
    public String getAuthor() {
        return "marti";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // sobrevive a /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        GameStats stats = plugin.getGameStats();
        String p = params.toLowerCase(Locale.ROOT);

        if (p.equals("jackpot_ruleta")) {
            var rm = plugin.getWorldRouletteManager();
            return rm == null ? "0" : fmt(rm.getJackpot());
        }
        if (stats == null)
            return "";

        // Top: top_<métrica>_<n>_<name|value>  /  topweek_<métrica>_<n>_<name|value>
        if (p.startsWith("top_") || p.startsWith("topweek_")) {
            boolean week = p.startsWith("topweek_");
            String[] parts = p.substring(week ? 8 : 4).split("_");
            if (parts.length < 3)
                return "";
            GameStats.Metric metric = GameStats.Metric.parse(parts[0]);
            int n;
            try {
                n = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return "";
            }
            if (metric == null || n < 1)
                return "";
            List<Map.Entry<UUID, Long>> top = stats.top(metric, week);
            boolean name = parts[2].equals("name");
            if (n > top.size())
                return name ? "-" : "0";
            Map.Entry<UUID, Long> e = top.get(n - 1);
            return name ? nameOf(e.getKey()) : fmt(e.getValue());
        }

        if (player == null)
            return "";

        if (p.equals("balance")) {
            Player online = player.getPlayer();
            return online == null ? "0" : fmt(TokenWallet.balance(online));
        }

        boolean week = p.startsWith("weekly_");
        String key = week ? p.substring(7) : p;
        GameStats.Entry e = stats.get(player.getUniqueId(), week);
        return switch (key) {
            case "profit" -> fmt(e.profit());
            case "wagered" -> fmt(e.wagered());
            case "paid", "won" -> fmt(e.paid());
            case "biggest", "biggest_win" -> fmt(e.biggestWin());
            case "rounds" -> fmt(e.rounds());
            case "rank" -> {
                int r = stats.rank(player.getUniqueId(), GameStats.Metric.PROFIT, week);
                yield r == 0 ? "-" : String.valueOf(r);
            }
            default -> null; // placeholder desconocido
        };
    }

    private String nameOf(UUID id) {
        String n = null;
        try {
            if (plugin.getPlayerIndex() != null)
                n = plugin.getPlayerIndex().getLastKnownName(id);
        } catch (Throwable ignored) {
        }
        if (n == null || n.isBlank())
            n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    private static String fmt(long v) {
        return NumberFormat.getInstance(Locale.forLanguageTag("es-ES")).format(v);
    }
}
