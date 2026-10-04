package com.gamblingdex.commands;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.modules.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Autocompletar (TAB) de /gdx. Muestra las opciones y, donde hay que escribir
 * algo libre, una pista entre &lt;&gt; (ej. &lt;name&gt;, &lt;small&gt;).
 */
public class GdxTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player p))
            return args.length == 1 ? filter(List.of("reload"), args[0]) : List.of();
        boolean admin = p.hasPermission("gamblingdex.admin");
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        var mm = plugin.getModuleManager();
        String a0 = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("help", "balance", "stats", "top"));
            if (admin)
                out.addAll(List.of("reload", "station", "blackjack", "poker", "roulette", "item", "token"));
            else
                out.add("poker");
            if (mm != null)
                for (GameModule m : mm.getModules())
                    if (mm.byCommand(m.id()) != null)
                        out.add(primaryCommand(m));
            return filter(out, args[0]);
        }

        String cur = args[args.length - 1];
        int n = args.length; // posición del argumento que se escribe (1 = el subcomando)

        switch (a0) {
            case "help", "ayuda" -> {
                if (n != 2)
                    return List.of();
                List<String> out = new ArrayList<>(List.of("player", "games", "blackjack", "poker", "roulette", "slots", "exchange"));
                if (admin)
                    out.add("admin");
                if (mm != null)
                    for (GameModule m : mm.getModules())
                        if (mm.byCommand(m.id()) != null)
                            out.add(m.id());
                return filter(out, cur);
            }
            case "stats", "estadisticas" -> {
                return n == 2 && p.hasPermission("gamblingdex.stats.others") ? filter(onlineNames(), cur) : List.of();
            }
            case "top", "ranking" -> {
                return n == 2 ? filter(List.of("week", "10", "20"), cur) : n == 3 ? filter(List.of("10", "20"), cur) : List.of();
            }
        }

        if (admin) {
            switch (a0) {
                case "station", "mesa", "mesas" -> {
                    return station(p, args, cur, n, mm);
                }
                case "blackjack", "bj" -> {
                    return blackjack(args, cur, n);
                }
                case "poker", "pk" -> {
                    return poker(args, cur, n, true);
                }
                case "roulette", "ruleta" -> {
                    if (n == 2)
                        return filter(List.of("build", "remove", "list"), cur);
                    if (args[1].equalsIgnoreCase("build"))
                        return n == 3 ? hint(cur, "[radius]", "6", "8") : n == 4 ? hint(cur, "[yOffset]", "-1", "0") : List.of();
                    return List.of();
                }
                case "item", "items" -> {
                    return n == 2 ? filter(List.of("roulette", "slots"), cur) : n == 3 ? hint(cur, "[amount]", "1", "16", "64") : List.of();
                }
                case "token", "tokens", "mint" -> {
                    if (n == 2) {
                        List<String> vals = new ArrayList<>();
                        for (int v : TokenManager.getDenoms().values())
                            vals.add(String.valueOf(v));
                        return hint(cur, "<value>", vals.toArray(new String[0]));
                    }
                    return n == 3 ? hint(cur, "<amount>", "1", "16", "64") : List.of();
                }
                case "reload" -> {
                    return List.of();
                }
            }
        } else if (a0.equals("poker") || a0.equals("pk")) {
            return poker(args, cur, n, false);
        }

        // Juegos (módulos)
        GameModule m = mm == null ? null : mm.byCommand(a0);
        if (m != null) {
            try {
                return filter(m.tabComplete(p, Arrays.copyOfRange(args, 1, args.length)), cur);
            } catch (Throwable ignored) {
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------

    private List<String> station(Player p, String[] args, String cur, int n, com.gamblingdex.modules.ModuleManager mm) {
        if (n == 2)
            return filter(List.of("set", "remove", "list", "debug", "cleanholo"), cur);
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("set") && !action.equals("add"))
            return List.of();
        if (n == 3) {
            List<String> types = new ArrayList<>(List.of("slots", "exchange"));
            if (mm != null)
                for (GameModule m : mm.getModules())
                    if (mm.byCommand(m.id()) != null && !m.stationTypes().isEmpty())
                        types.add(m.stationTypes().get(0));
            return filter(types, cur);
        }
        GameModule m = mm == null ? null : mm.byStationType(args[2]);
        if (m == null)
            return List.of();
        return filter(m.stationTabComplete(p, Arrays.copyOfRange(args, 3, args.length)), cur);
    }

    private List<String> blackjack(String[] args, String cur, int n) {
        if (n == 2)
            return filter(List.of("create", "remove", "list", "seat", "displayname"), cur);
        String action = args[1].toLowerCase(Locale.ROOT);
        List<String> tables = blackjackTables();
        switch (action) {
            case "create", "add", "set" -> {
                return n == 3 ? hint(cur, "<name>") : List.of();
            }
            case "remove", "del", "delete" -> {
                return n == 3 ? filter(tables, cur) : List.of();
            }
            case "seat", "seats" -> {
                if (n == 3)
                    return filter(List.of("add", "remove", "list", "clear"), cur);
                return n == 4 ? filter(tables, cur) : List.of();
            }
            case "displayname", "prettyname", "name" -> {
                return n == 3 ? filter(tables, cur) : n == 4 ? hint(cur, "<title...>") : List.of();
            }
        }
        return List.of();
    }

    private List<String> poker(String[] args, String cur, int n, boolean admin) {
        if (n == 2) {
            List<String> out = new ArrayList<>(List.of("menu", "join"));
            if (admin)
                out.addAll(List.of("create", "remove", "list", "stakes", "seat", "tournament", "rake"));
            return filter(out, cur);
        }
        if (!admin)
            return List.of();
        String action = args[1].toLowerCase(Locale.ROOT);
        List<String> tables = pokerTables();
        switch (action) {
            case "create", "add", "set" -> {
                return n == 3 ? hint(cur, "<name>") : n == 4 ? hint(cur, "[small]", "5", "10", "1000")
                        : n == 5 ? hint(cur, "[big]", "10", "20", "2000") : List.of();
            }
            case "remove", "del", "delete" -> {
                return n == 3 ? filter(tables, cur) : List.of();
            }
            case "stakes", "blinds", "ciegas" -> {
                return n == 3 ? filter(tables, cur) : n == 4 ? hint(cur, "<small>", "5", "10")
                        : n == 5 ? hint(cur, "<big>", "10", "20") : List.of();
            }
            case "seat", "seats" -> {
                if (n == 3)
                    return filter(List.of("add", "remove", "list", "clear"), cur);
                return n == 4 ? filter(tables, cur) : List.of();
            }
            case "tournament", "torneo" -> {
                if (n == 3) {
                    List<String> out = new ArrayList<>(List.of("start", "cancel"));
                    out.addAll(tables);
                    return filter(out, cur);
                }
                String t = args[2].toLowerCase(Locale.ROOT);
                if (t.equals("start") || t.equals("cancel") || t.equals("empezar") || t.equals("cancelar"))
                    return n == 4 ? filter(tables, cur) : List.of();
                return n == 4 ? hint(cur, "<fee>", "1000") : n == 5 ? hint(cur, "[chips]", "10000")
                        : n == 6 ? hint(cur, "[minutes]", "5", "10") : List.of();
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------

    private static String primaryCommand(GameModule m) {
        // Nombre en inglés si el juego lo tiene como alias (race, wheel, lottery, scratch)
        for (String a : m.aliases())
            if (Set.of("race", "wheel", "lottery", "scratch").contains(a))
                return a;
        return m.id();
    }

    private static List<String> blackjackTables() {
        var bm = GamblingDexPlugin.getInstance().getBlackjackManager();
        return bm == null ? List.of() : new ArrayList<>(bm.getTableNames());
    }

    private static List<String> pokerTables() {
        var pm = GamblingDexPlugin.getInstance().getPokerManager();
        List<String> out = new ArrayList<>();
        if (pm != null)
            for (var t : pm.getTables())
                out.add(t.getName());
        return out;
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            out.add(p.getName());
        return out;
    }

    /** Opciones que empiezan con lo escrito; las pistas &lt;...&gt; solo se muestran si no se escribió nada. */
    static List<String> filter(List<String> options, String typed) {
        String t = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o == null)
                continue;
            boolean isHint = o.startsWith("<") || o.startsWith("[");
            if (isHint ? t.isEmpty() : o.toLowerCase(Locale.ROOT).startsWith(t))
                if (!out.contains(o))
                    out.add(o);
        }
        return out;
    }

    /** Pista + ejemplos de valores. */
    static List<String> hint(String typed, String hint, String... examples) {
        List<String> l = new ArrayList<>();
        l.add(hint);
        l.addAll(Arrays.asList(examples));
        return filter(l, typed);
    }
}
