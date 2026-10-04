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
 *
 * %gamblingdex_last_win_1%              "Martin +400.000 en Blackjack" (1 = el más reciente, hasta 10)
 * %gamblingdex_last_win_1_name|value|game%
 * %gamblingdex_record_win_name|value|game%  el premio más grande de la historia
 * %gamblingdex_players_playing%         jugadores que jugaron en los últimos 5 minutos
 * %gamblingdex_achievements%            logros del jugador  (_total = cuántos hay)
 *
 * Póker (manos de mesas normales):
 * %gamblingdex_poker_hands%  _hands_won  _profit  _biggest_pot  _tournaments  (weekly_poker_... = semana)
 * %gamblingdex_top_poker_profit_1_name% / _value   (topweek_poker_... = semana)
 *   (métricas: profit, hands, hands_won, biggest_pot, tournaments)
 *
 * En vivo:
 * %gamblingdex_crash_multiplier%  _state  _seconds  _players
 * %gamblingdex_bingo_next%  %gamblingdex_bingo_pot%
 * %gamblingdex_lottery_pot%  %gamblingdex_lottery_next%
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

        if (p.startsWith("last_win_"))
            return lastWin(stats, p.substring(9));
        if (p.startsWith("record_win")) {
            GameStats.Play r = stats.record();
            return switch (p) {
                case "record_win_name" -> r == null ? "-" : r.name();
                case "record_win_value" -> r == null ? "0" : fmt(r.net());
                case "record_win_game" -> r == null ? "-" : GameStats.gameName(r.game());
                case "record_win" -> r == null ? "-" : r.name() + " +" + fmt(r.net()) + " en " + GameStats.gameName(r.game());
                default -> null;
            };
        }
        if (p.equals("players_playing"))
            return String.valueOf(stats.activePlayers(5));
        if (p.equals("achievements_total"))
            return plugin.getAchievements() == null ? "0" : String.valueOf(plugin.getAchievements().total());

        // Top: top_<métrica>_<n>_<name|value>  /  topweek_<métrica>_<n>_<name|value>  (métrica puede llevar _)
        if (p.startsWith("top_") || p.startsWith("topweek_")) {
            boolean week = p.startsWith("topweek_");
            String rest = p.substring(week ? 8 : 4);
            boolean poker = rest.startsWith("poker_");
            if (poker)
                rest = rest.substring(6);
            int a = rest.lastIndexOf('_');
            int b = a <= 0 ? -1 : rest.lastIndexOf('_', a - 1);
            if (b <= 0)
                return "";
            String metricName = rest.substring(0, b);
            int n;
            try {
                n = Integer.parseInt(rest.substring(b + 1, a));
            } catch (NumberFormatException e) {
                return "";
            }
            if (n < 1)
                return "";
            List<Map.Entry<UUID, Long>> top;
            if (poker) {
                GameStats.PokerMetric pm = GameStats.PokerMetric.parse(metricName);
                if (pm == null)
                    return "";
                top = stats.pokerTop(pm, week);
            } else {
                GameStats.Metric metric = GameStats.Metric.parse(metricName);
                if (metric == null)
                    return "";
                top = stats.top(metric, week);
            }
            boolean name = rest.substring(a + 1).equals("name");
            if (n > top.size())
                return name ? "-" : "0";
            Map.Entry<UUID, Long> e = top.get(n - 1);
            return name ? nameOf(e.getKey()) : fmt(e.getValue());
        }

        // En vivo de cada juego: <juego>_<dato> (crash_multiplier, bingo_next, lottery_pot...)
        var mm = plugin.getModuleManager();
        int us = p.indexOf('_');
        if (mm != null && us > 0) {
            var m = mm.byCommand(p.substring(0, us));
            if (m != null) {
                String v = m.placeholder(p.substring(us + 1));
                if (v != null)
                    return v;
            }
        }

        if (player == null)
            return "";

        if (p.equals("achievements"))
            return plugin.getAchievements() == null ? "0" : String.valueOf(plugin.getAchievements().count(player.getUniqueId()));

        if (p.equals("balance")) {
            Player online = player.getPlayer();
            return online == null ? "0" : fmt(TokenWallet.balance(online));
        }

        boolean week = p.startsWith("weekly_");
        String key = week ? p.substring(7) : p;
        if (key.startsWith("poker_")) {
            GameStats.PokerEntry pe = stats.poker(player.getUniqueId(), week);
            return switch (key.substring(6)) {
                case "hands" -> fmt(pe.hands());
                case "hands_won", "won" -> fmt(pe.handsWon());
                case "profit" -> fmt(pe.profit());
                case "biggest_pot", "pot" -> fmt(pe.biggestPot());
                case "tournaments" -> fmt(pe.tournaments());
                default -> null;
            };
        }
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

    /** last_win_&lt;n&gt;[_name|_value|_game]. */
    private String lastWin(GameStats stats, String rest) {
        String[] parts = rest.split("_", 2);
        int n;
        try {
            n = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        List<GameStats.Play> wins = stats.recentWins();
        GameStats.Play w = n >= 1 && n <= wins.size() ? wins.get(n - 1) : null;
        String field = parts.length > 1 ? parts[1] : "";
        return switch (field) {
            case "name" -> w == null ? "-" : w.name();
            case "value" -> w == null ? "0" : fmt(w.net());
            case "game" -> w == null ? "-" : GameStats.gameName(w.game());
            case "" -> w == null ? "-" : w.name() + " +" + fmt(w.net()) + " en " + GameStats.gameName(w.game());
            default -> null;
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
