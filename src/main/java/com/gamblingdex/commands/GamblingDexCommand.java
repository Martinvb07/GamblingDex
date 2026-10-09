package com.gamblingdex.commands;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.items.GameItemType;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

public class GamblingDexCommand implements CommandExecutor {

    private static String cfg(String path, String def) {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        if (plugin == null)
            return def;
        if (path != null && path.startsWith("messages.")) {
            String key = path.substring("messages.".length());
            if (plugin.getMessages() != null) {
                return plugin.getMessages().getString(key, def);
            }
        }
        return plugin.color(plugin.getConfig().getString(path, def));
    }

    /** /gdx history: las últimas 10 apuestas del jugador. */
    private static void sendHistory(Player player) {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        var stats = plugin.getGameStats();
        java.util.List<com.gamblingdex.stats.GameStats.Play> plays = stats == null ? java.util.List.of()
                : stats.history(player.getUniqueId());
        player.sendMessage(plugin.color("&8&m        &r &6&lTus últimas apuestas &8&m        "));
        if (plays.isEmpty()) {
            player.sendMessage(plugin.color("&7Aún no has jugado nada."));
            return;
        }
        long now = System.currentTimeMillis();
        for (var pl : plays) {
            long net = pl.net();
            String result = pl.wager() <= 0 ? "&a+" + formatLong(pl.payout())
                    : net > 0 ? "&a+" + formatLong(net) : net == 0 ? "&e±0" : "&c-" + formatLong(-net);
            String bet = pl.wager() > 0 ? " &7apostó &e" + formatLong(pl.wager()) : "";
            player.sendMessage(plugin.color("&8" + ago(now - pl.time()) + " &f"
                    + com.gamblingdex.stats.GameStats.gameName(pl.game()) + bet + " &8» " + result));
        }
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60)
            return "hace " + s + "s";
        if (s < 3600)
            return "hace " + s / 60 + "m";
        if (s < 86400)
            return "hace " + s / 3600 + "h";
        return "hace " + s / 86400 + "d";
    }

    /** /gdx pack: genera el resource pack del casino (y lo junta con el de ModelEngine). */
    private static void handlePack(CommandSender sender) {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        var rp = plugin.getResourcePackManager();
        sender.sendMessage(plugin.color("&7Generando el resource pack..."));
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String[] lines;
            try {
                var r = rp.build();
                java.util.List<String> out = new java.util.ArrayList<>();
                out.add("&aResource pack listo: &fplugins/GamblingDex/" + r.pack().getName());
                out.add("&7SHA-1: &f" + r.packSha1());
                if (r.merged() != null) {
                    out.add("&aCon los modelos de ModelEngine: &fplugins/GamblingDex/" + r.merged().getName());
                    out.add("&7SHA-1: &f" + r.mergedSha1());
                    out.add("&7Sube &fGamblingDex-merged.zip&7 (no el de ModelEngine por separado).");
                } else if (plugin.getServer().getPluginManager().getPlugin("ModelEngine") != null) {
                    out.add("&eNo encontré el pack de ModelEngine: usa &f/meg reload&e y luego &f/gdx pack&e otra vez.");
                }
                out.add("&7Súbelo a una web (p. ej. mc-packs.net) y pon la URL y el SHA-1 en");
                out.add("&7config.yml → resource_pack.send (o en server.properties).");
                lines = out.toArray(new String[0]);
            } catch (Exception e) {
                plugin.getLogger().warning("[Pack] " + e);
                lines = new String[] { "&cNo se pudo generar el resource pack: &f" + e.getMessage() };
            }
            String[] msg = lines;
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                for (String l : msg)
                    sender.sendMessage(plugin.color(l));
            });
        });
    }

    /** /gdx disable &lt;juego&gt; | /gdx enable &lt;juego&gt; | /gdx maintenance (lista). */
    private static void handleMaintenance(Player player, String sub, String[] args) {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        var mt = plugin.getMaintenance();
        if (sub.equals("maintenance") || sub.equals("mantenimiento") || args.length < 2) {
            java.util.Set<String> closed = mt.closedGames();
            player.sendMessage(plugin.color(closed.isEmpty() ? "&aNingún juego en mantenimiento."
                    : "&eEn mantenimiento: &f" + String.join(", ", closed.stream()
                            .map(com.gamblingdex.stats.GameStats::gameName).toList())));
            player.sendMessage(plugin.color("&7Uso: &f/gdx disable <game> &7| &f/gdx enable <game>"));
            return;
        }
        String game = mt.resolve(args[1]);
        if (game == null) {
            player.sendMessage(plugin.color("&cJuego desconocido: &f" + args[1]));
            return;
        }
        String name = com.gamblingdex.stats.GameStats.gameName(game);
        if (sub.equals("disable")) {
            if (!mt.close(game)) {
                player.sendMessage(plugin.color("&e" + name + " ya estaba en mantenimiento."));
                return;
            }
            player.sendMessage(plugin.color("&a" + name + " &7cerrado por mantenimiento. Se devolvieron las apuestas en curso."));
        } else {
            if (!mt.open(game)) {
                player.sendMessage(plugin.color("&e" + name + " no estaba en mantenimiento."));
                return;
            }
            player.sendMessage(plugin.color("&a" + name + " &7vuelve a estar abierto."));
        }
    }

    private static String applyPlaceholders(String template, Map<String, String> values) {
        String out = template == null ? "" : template;
        if (values == null || values.isEmpty())
            return out;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey() == null)
                continue;
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Allow reload from console as well.
        if (args.length >= 1) {
            String sub = args[0].toLowerCase();
            if (sub.equals("reload") || sub.equals("recargar")) {
                if (!sender.hasPermission("gamblingdex.admin")) {
                    sender.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }
                GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
                java.util.List<String> errors = plugin.validateYamlFiles();
                if (!errors.isEmpty()) {
                    sender.sendMessage(cfg("messages.admin.reload.yaml_error",
                            "&cNo se recargó: hay errores en los archivos. Corrígelos y vuelve a intentar:"));
                    for (String err : errors) {
                        sender.sendMessage(plugin.color("&8- &e" + err));
                        plugin.getLogger().warning("[Reload] " + err);
                    }
                    sender.sendMessage(plugin.color(
                            "&7Tip: usa espacios (no TAB) y pon entre comillas los textos con &, : o #."));
                    return true;
                }
                plugin.reloadAll();
                sender.sendMessage(cfg("messages.admin.reload.success", "&aGamblingDex recargado."));
                int warnings = new com.gamblingdex.config.ConfigCheck(plugin).run().size();
                if (warnings > 0)
                    sender.sendMessage(plugin.color("&e⚠ Hay &f" + warnings
                            + "&e valor(es) raros en la config. Míralos con &f/gdx config check&e."));
                return true;
            }
            if (sub.equals("pack") || sub.equals("resourcepack")) {
                if (!sender.hasPermission("gamblingdex.admin")) {
                    sender.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }
                handlePack(sender);
                return true;
            }
            if (sub.equals("config")) {
                if (!sender.hasPermission("gamblingdex.admin")) {
                    sender.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }
                GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
                if (args.length < 2 || !args[1].equalsIgnoreCase("check")) {
                    sender.sendMessage(plugin.color("&7Uso: &f/gdx config check"));
                    return true;
                }
                java.util.List<String> problems = new com.gamblingdex.config.ConfigCheck(plugin).run();
                sender.sendMessage(plugin.color("&8&m        &r &6&lRevisión de la config &8&m        "));
                if (problems.isEmpty()) {
                    sender.sendMessage(plugin.color("&a✔ Todo bien: no encontré valores raros en los archivos."));
                    return true;
                }
                for (String pr : problems)
                    sender.sendMessage(plugin.color("&c✖ &e" + pr));
                sender.sendMessage(plugin.color("&7" + problems.size()
                        + " problema(s). Corrígelos y usa &f/gdx reload&7."));
                return true;
            }
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg("messages.gdx.console_only", "&cSolo jugadores pueden usar este comando."));
            return true;
        }

        if (!player.hasPermission("gamblingdex.use")) {
            player.sendMessage(cfg("messages.gdx.no_permission", "&cNo tienes permiso para usar GamblingDex."));
            return true;
        }

        if (args.length >= 1) {
            String sub = args[0].toLowerCase();

            if (sub.equals("balance") || sub.equals("bal") || sub.equals("saldo")) {
                // Fichas que tiene en el inventario (es la moneda con la que se juega)
                long balance = com.gamblingdex.economy.TokenWallet.balance(player);
                String currencyName = GamblingDexPlugin.getInstance().color(
                        GamblingDexPlugin.getInstance().getConfig().getString("currency.name", "Moneda GDX"));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.balance_tokens", "&aTienes &e{amount}&a {currency} en fichas."),
                        Map.of("amount", formatLong(balance), "currency", currencyName)));
                return true;
            }

            if (sub.equals("profile") || sub.equals("perfil")) {
                if (GamblingDexPlugin.getInstance().getInspectMenu() != null)
                    GamblingDexPlugin.getInstance().getInspectMenu().openProfile(player);
                return true;
            }

            if (sub.equals("history") || sub.equals("historial")) {
                sendHistory(player);
                return true;
            }

            if (sub.equals("achievements") || sub.equals("logros")) {
                var ach = GamblingDexPlugin.getInstance().getAchievements();
                if (ach != null)
                    ach.open(player, 0);
                return true;
            }

            if (sub.equals("sign") || sub.equals("signs")) {
                GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }
                var cs = plugin.getCasinoSigns();
                String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (action.equals("list")) {
                    java.util.List<String> lines = cs.describe();
                    player.sendMessage(plugin.color(lines.isEmpty() ? "&7No hay carteles del casino."
                            : "&6&lCarteles del casino &7(" + lines.size() + ")"));
                    for (String l : lines)
                        player.sendMessage(plugin.color(l));
                    return true;
                }
                if (!action.equals("add") && !action.equals("remove")) {
                    HelpMenu.send(player, new String[] { "help", "signs" });
                    return true;
                }
                org.bukkit.block.Block target = com.gamblingdex.stats.CasinoSigns.target(player);
                if (target == null) {
                    player.sendMessage(plugin.color("&cMira un cartel (a menos de 6 bloques)."));
                    return true;
                }
                if (action.equals("remove")) {
                    player.sendMessage(plugin.color(cs.remove(target) ? "&aCartel quitado. &7(el texto se queda como está)"
                            : "&cEse cartel no es del casino."));
                    return true;
                }
                String type = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
                if (!com.gamblingdex.stats.CasinoSigns.TYPES.contains(type)) {
                    player.sendMessage(plugin.color("&cTipo desconocido. Tipos: &f"
                            + String.join(", ", com.gamblingdex.stats.CasinoSigns.TYPES)));
                    return true;
                }
                int n = 1;
                String arg = "";
                if (type.equals("table")) {
                    if (args.length < 4 || !cs.tableExists(args[3])) {
                        player.sendMessage(plugin.color("&cIndica una mesa de blackjack o póker: &f/gdx sign add table <name>"
                                + (cs.tableNames().isEmpty() ? "" : " &7(" + String.join(", ", cs.tableNames()) + ")")));
                        return true;
                    }
                    arg = args[3];
                    if (args.length >= 5 && args[4].equals("2"))
                        n = 2; // segundo cartel (póker: compra, bote y calle)
                } else if (type.equals("poker_board") || type.equals("poker_winner")) {
                    var pm = plugin.getPokerManager();
                    if (args.length < 4 || pm == null || pm.getByName(args[3]) == null) {
                        player.sendMessage(plugin.color("&cIndica la mesa de póker: &f/gdx sign add " + type + " <name>"
                                + (pm == null ? "" : " &7(" + String.join(", ", pm.getTableNames()) + ")")));
                        return true;
                    }
                    arg = pm.getByName(args[3]).getName();
                } else if (type.equals("baccarat_road")) {
                    if (args.length >= 4) {
                        var mm = plugin.getModuleManager();
                        var m = mm == null ? null : mm.find("baccarat");
                        if (!(m instanceof com.gamblingdex.modules.baccarat.BaccaratModule bm) || bm.road(args[3]) == null) {
                            player.sendMessage(plugin.color("&cNo existe esa mesa de baccarat. &7(sin nombre = la más cercana al cartel)"));
                            return true;
                        }
                        arg = args[3];
                    }
                } else if (type.equals("game_status")) {
                    String g = args.length >= 4 && plugin.getMaintenance() != null
                            ? plugin.getMaintenance().resolve(args[3]) : null;
                    if (g == null) {
                        player.sendMessage(plugin.color("&cIndica el juego: &f/gdx sign add game_status <game>"));
                        return true;
                    }
                    arg = g;
                } else {
                    for (int i = 3; i < args.length; i++) {
                        try {
                            n = Integer.parseInt(args[i]);
                        } catch (NumberFormatException ex) {
                            String g = com.gamblingdex.stats.GameStats.gameId(args[i].toLowerCase(Locale.ROOT));
                            if (g == null || !com.gamblingdex.stats.CasinoSigns.PER_GAME.contains(type)) {
                                player.sendMessage(plugin.color("&cNo entiendo &f" + args[i] + "&c para este cartel."));
                                return true;
                            }
                            arg = g;
                        }
                    }
                    if (n < 1 || n > 10) {
                        player.sendMessage(plugin.color("&cEl puesto tiene que ser de 1 a 10."));
                        return true;
                    }
                }
                cs.add(target, type, n, arg);
                player.sendMessage(plugin.color("&aCartel del casino creado: &f" + type
                        + (com.gamblingdex.stats.CasinoSigns.RANKED.contains(type) ? " #" + n : "")
                        + (arg.isEmpty() ? "" : " &7(" + arg + ")") + "&a. Se actualiza solo."));
                return true;
            }

            if (sub.equals("schedule") || sub.equals("inspect")) {
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }
                GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
                if (sub.equals("schedule")) {
                    if (plugin.getSchedule() != null)
                        plugin.getSchedule().open(player);
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(plugin.color("&7Uso: &f/gdx inspect <player>"));
                    return true;
                }
                UUID target = null;
                String targetName = args[1];
                Player on = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (on != null) {
                    target = on.getUniqueId();
                    targetName = on.getName();
                } else if (plugin.getPlayerIndex() != null) {
                    target = plugin.getPlayerIndex().resolveNameToUuid(args[1]);
                    if (target != null) {
                        String known = plugin.getPlayerIndex().getLastKnownName(target);
                        if (known != null && !known.isBlank())
                            targetName = known;
                    }
                }
                if (target == null) {
                    OfflinePlayer cached = org.bukkit.Bukkit.getOfflinePlayerIfCached(args[1]);
                    if (cached != null) {
                        target = cached.getUniqueId();
                        if (cached.getName() != null)
                            targetName = cached.getName();
                    }
                }
                if (target == null) {
                    player.sendMessage(plugin.color("&cJugador no encontrado: &f" + args[1]
                            + " &c(tiene que haber entrado al servidor)"));
                    return true;
                }
                plugin.getInspectMenu().open(player, target, targetName);
                return true;
            }

            if (sub.equals("disable") || sub.equals("enable") || sub.equals("maintenance")
                    || sub.equals("mantenimiento")) {
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }
                handleMaintenance(player, sub, args);
                return true;
            }

            if (sub.equals("stats") || sub.equals("estadisticas") || sub.equals("estadísticas")) {
                if (!player.hasPermission("gamblingdex.stats.self")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }

                UUID targetId = player.getUniqueId();
                String targetName = player.getName();
                if (args.length >= 2) {
                    if (!player.hasPermission("gamblingdex.stats.others")) {
                        player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                        return true;
                    }

                    String input = args[1];

                    // 1) UUID support
                    UUID asUuid = null;
                    try {
                        asUuid = UUID.fromString(input);
                    } catch (IllegalArgumentException ignored) {
                    }
                    if (asUuid != null) {
                        OfflinePlayer off = org.bukkit.Bukkit.getOfflinePlayer(asUuid);
                        targetId = asUuid;
                        targetName = (off != null && off.getName() != null) ? off.getName()
                                : asUuid.toString().substring(0, 8);
                    } else {
                        // 2) Online player
                        Player other = org.bukkit.Bukkit.getPlayerExact(input);
                        if (other != null) {
                            targetId = other.getUniqueId();
                            targetName = other.getName();
                        } else {
                            // 3) Offline cached (Paper)
                            OfflinePlayer cached = org.bukkit.Bukkit.getOfflinePlayerIfCached(input);
                            if (cached == null) {
                                // 4) Persisted index (players.yml)
                                UUID resolved = null;
                                if (GamblingDexPlugin.getInstance().getPlayerIndex() != null) {
                                    resolved = GamblingDexPlugin.getInstance().getPlayerIndex()
                                            .resolveNameToUuid(input);
                                }

                                if (resolved == null) {
                                    player.sendMessage(
                                            "§cJugador no encontrado: §f" + input
                                                    + "§c (usa su UUID o debe haber entrado al servidor)");
                                    return true;
                                }

                                targetId = resolved;
                                String known = GamblingDexPlugin.getInstance().getPlayerIndex()
                                        .getLastKnownName(resolved);
                                targetName = (known == null || known.isBlank()) ? input : known;
                            } else {
                                targetId = cached.getUniqueId();
                                targetName = cached.getName() == null ? input : cached.getName();
                            }
                        }
                    }
                }

                var slots = GamblingDexPlugin.getInstance().getSlotsStatsManager().get(targetId);
                var roulette = GamblingDexPlugin.getInstance().getRouletteStatsManager().get(targetId);
                String currencyName = GamblingDexPlugin.getInstance().color(
                        GamblingDexPlugin.getInstance().getConfig().getString("currency.name", "⛃"));

                long slotsLost = Math.max(0L, slots.getTotalWagerUnits() - slots.getTotalPayoutUnits());
                long rouletteLost = Math.max(0L, roulette.getTotalWagerUnits() - roulette.getTotalPayoutUnits());
                long totalWager = slots.getTotalWagerUnits() + roulette.getTotalWagerUnits();
                long totalWon = slots.getTotalPayoutUnits() + roulette.getTotalPayoutUnits();
                long totalLost = Math.max(0L, totalWager - totalWon);

                player.sendMessage(applyPlaceholders(cfg("messages.gdx.stats.header", "&6&lStats &7({player})"),
                        Map.of("player", targetName)));

                player.sendMessage(cfg("messages.gdx.stats.slots_title", "&d&lSlots"));
                player.sendMessage(applyPlaceholders(cfg("messages.gdx.stats.lines.spins", "&8- &7Spins: &f{spins}"),
                        Map.of("spins", formatLong(slots.getSpins()))));
                player.sendMessage(applyPlaceholders(cfg("messages.gdx.stats.lines.wins", "&8- &7Wins: &f{wins}"),
                        Map.of("wins", formatLong(slots.getWins()))));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_wager",
                                "&8- &7Total apostado: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(slots.getTotalWagerUnits()), "currency", currencyName)));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_won",
                                "&8- &7Total ganado: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(slots.getTotalPayoutUnits()), "currency", currencyName)));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_lost",
                                "&8- &7Total perdido: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(slotsLost), "currency", currencyName)));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.biggest_win",
                                "&8- &7Mejor premio: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(slots.getBiggestWinUnits()), "currency", currencyName)));

                player.sendMessage(cfg("messages.gdx.stats.roulette_title", "&c&lRuleta"));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.rounds", "&8- &7Rondas: &f{rounds}"),
                        Map.of("rounds", formatLong(roulette.getRounds()))));
                player.sendMessage(applyPlaceholders(cfg("messages.gdx.stats.lines.wins", "&8- &7Wins: &f{wins}"),
                        Map.of("wins", formatLong(roulette.getWins()))));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_wager",
                                "&8- &7Total apostado: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(roulette.getTotalWagerUnits()), "currency", currencyName)));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_won",
                                "&8- &7Total ganado: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(roulette.getTotalPayoutUnits()), "currency", currencyName)));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_lost",
                                "&8- &7Total perdido: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(rouletteLost), "currency", currencyName)));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.biggest_win",
                                "&8- &7Mejor premio: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(roulette.getBiggestWinUnits()), "currency", currencyName)));

                player.sendMessage(cfg("messages.gdx.stats.total_title", "&6&lTotal (Slots+Ruleta)"));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_wager",
                                "&8- &7Total apostado: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(totalWager), "currency", currencyName)));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_won",
                                "&8- &7Total ganado: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(totalWon), "currency", currencyName)));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.stats.lines.total_lost",
                                "&8- &7Total perdido: &f{amount} &7{currency}"),
                        Map.of("amount", formatLong(totalLost), "currency", currencyName)));
                return true;
            }

            if (sub.equals("top") || sub.equals("ranking")) {
                if (!player.hasPermission("gamblingdex.top")) {
                    player.sendMessage(cfg("messages.gdx.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }

                // /gdx top (sin nada) = menú con el podio; /gdx top [week] [n] = en el chat.
                if (args.length == 1 && GamblingDexPlugin.getInstance().getTopMenu() != null) {
                    GamblingDexPlugin.getInstance().getTopMenu().open(player);
                    return true;
                }
                int limit = 10;
                boolean week = false;
                for (int ai = 1; ai < args.length; ai++) {
                    String a = args[ai].toLowerCase(java.util.Locale.ROOT);
                    if (a.equals("semana") || a.equals("semanal") || a.equals("week") || a.equals("weekly")) {
                        week = true;
                        continue;
                    }
                    try {
                        limit = Integer.parseInt(a);
                    } catch (NumberFormatException ignored) {
                    }
                }

                String currencyName = GamblingDexPlugin.getInstance().color(
                        GamblingDexPlugin.getInstance().getConfig().getString("currency.name", "⛃"));

                player.sendMessage(week
                        ? cfg("messages.gdx.top.header_week", "&6&lTop Ganancias &7(esta semana, todos los juegos)")
                        : cfg("messages.gdx.top.header_all", "&6&lTop Ganancias &7(todos los juegos)"));

                var stats = GamblingDexPlugin.getInstance().getGameStats();
                List<Map.Entry<UUID, Long>> list = stats == null ? List.of()
                        : stats.top(com.gamblingdex.stats.GameStats.Metric.PROFIT, week);

                int pos = 1;
                int safeLimit = Math.max(1, Math.min(50, limit));
                for (Map.Entry<UUID, Long> e : list) {
                    long won = e.getValue();
                    if (won <= 0)
                        continue;

                    String name = null;
                    try {
                        if (GamblingDexPlugin.getInstance().getPlayerIndex() != null) {
                            name = GamblingDexPlugin.getInstance().getPlayerIndex().getLastKnownName(e.getKey());
                        }
                    } catch (Throwable ignored) {
                    }
                    if (name == null || name.isBlank()) {
                        name = org.bukkit.Bukkit.getOfflinePlayer(e.getKey()).getName();
                    }
                    if (name == null || name.isBlank())
                        name = e.getKey().toString().substring(0, 8);

                    String line = cfg("messages.gdx.top.line",
                            "&8{pos}. &f{name} &7- &a{won} &7{currency}");
                    line = applyPlaceholders(line, Map.of(
                            "pos", String.valueOf(pos),
                            "name", name,
                            "won", formatLong(won),
                            "currency", currencyName));
                    player.sendMessage(line);
                    pos++;
                    if (pos > safeLimit)
                        break;
                }

                if (pos == 1) {
                    player.sendMessage(cfg("messages.gdx.top.empty", "&7Aún no hay ganancias registradas."));
                }
                return true;
            }

            if (sub.equals("mint") || sub.equals("token") || sub.equals("tokens")) {
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }

                // Usage:
                // /gdx token <amount> -> yellow (1)
                // /gdx token <color|valor> <amount> -> chosen denom
                int amount = 1;
                org.bukkit.Material denomMat = org.bukkit.Material.YELLOW_DYE;

                if (args.length == 2) {
                    // Could be amount, or denom
                    try {
                        amount = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                        denomMat = GamblingDexPlugin.getInstance().getTokenManager().parseDenomination(args[1]);
                        amount = 1;
                    }
                } else if (args.length >= 3) {
                    denomMat = GamblingDexPlugin.getInstance().getTokenManager().parseDenomination(args[1]);
                    try {
                        amount = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {
                        amount = 1;
                    }
                }

                ItemStack tokens = GamblingDexPlugin.getInstance().getTokenManager().createToken(denomMat, amount);
                player.getInventory().addItem(tokens);
                player.sendMessage(applyPlaceholders(
                        cfg("messages.admin.token.given", "&aTe di &e{amount}&a token(s) para Shopkeepers."),
                        Map.of("amount", String.valueOf(tokens.getAmount()))));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.admin.token.denoms_help", "&7Denoms: &f{help}"),
                        Map.of("help", GamblingDexPlugin.getInstance().getTokenManager().getDenominationsHelp())));
                return true;
            }

            if (sub.equals("help") || sub.equals("ayuda")) {
                HelpMenu.send(player, args);
                return true;
            }

            if (sub.equals("item") || sub.equals("items")) {
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(
                            cfg("messages.admin.item.usage", "&cUso: /gdx item <roulette|slots> [amount]"));
                    return true;
                }

                GameItemType type = GameItemType.fromArg(args[1]);
                if (type == null) {
                    player.sendMessage(
                            cfg("messages.admin.item.invalid_type", "&cTipo inválido. Usa: roulette, slots"));
                    return true;
                }

                if (type == GameItemType.EXCHANGE) {
                    player.sendMessage(cfg("messages.admin.item.is_station",
                            "&cEse tipo es una estación (LOOM), no un ítem. Usa: /gdx station set exchange"));
                    return true;
                }

                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {
                        amount = 1;
                    }
                }

                ItemStack item = GamblingDexPlugin.getInstance().getGameItemManager().create(type, amount);
                player.getInventory().addItem(item);
                player.sendMessage(applyPlaceholders(
                        cfg("messages.admin.item.given", "&aTe di &e{amount}&a item(s): &f{type}"),
                        Map.of("amount", String.valueOf(item.getAmount()), "type", type.getId())));
                player.sendMessage(cfg("messages.admin.item.hint_open", "&7Click derecho para abrir su GUI."));
                return true;
            }

            if (sub.equals("station") || sub.equals("mesa") || sub.equals("mesas")) {
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(
                            cfg("messages.admin.station.usage", "&cUso: /gdx station <set|remove|list|debug> ..."));
                    player.sendMessage(cfg("messages.admin.station.example",
                            "&7Ej: &f/gdx station set slots&7 (mirando el bloque)"));
                    return true;
                }

                String action = args[1].toLowerCase();
                if (action.equals("set") || action.equals("add")) {
                    if (args.length < 3) {
                        player.sendMessage(
                                cfg("messages.admin.station.set.usage", "&cUso: /gdx station set <slots|exchange>"));
                        sendModuleStationTypes(player);
                        player.sendMessage(cfg("messages.admin.station.set.roulette_tip",
                                "&7(Para ruleta física usa: &f/gdx roulette build [radius] [yOffset]&7)"));
                        return true;
                    }

                    // Juegos con estación propia (crash, carrera, ...)
                    var stationModules = GamblingDexPlugin.getInstance().getModuleManager();
                    var stationModule = stationModules == null ? null : stationModules.byStationType(args[2]);
                    if (stationModule != null) {
                        Block target = player.getTargetBlockExact(6);
                        if (target == null) {
                            player.sendMessage(cfg("messages.admin.station.set.look_block",
                                    "&cMira un bloque a menos de 6 bloques."));
                            return true;
                        }
                        stationModule.createStation(player, target, Arrays.copyOfRange(args, 3, args.length));
                        return true;
                    }

                    GameItemType type = GameItemType.fromArg(args[2]);
                    if (type == null) {
                        player.sendMessage(cfg("messages.admin.station.set.invalid_type",
                                "&cTipo inválido. Usa: slots, exchange"));
                        sendModuleStationTypes(player);
                        return true;
                    }

                    Block target = player.getTargetBlockExact(6);
                    if (target == null) {
                        player.sendMessage(
                                cfg("messages.admin.station.set.look_block", "&cMira un bloque a menos de 6 bloques."));
                        return true;
                    }

                    if (type == GameItemType.ROULETTE) {
                        player.sendMessage(cfg("messages.admin.station.set.roulette_is_physical",
                                "&cLa ruleta ahora es una mesa física. Usa: &f/gdx roulette build [radius] [yOffset]"));
                        return true;
                    }

                    if (target.getType() != type.getMaterial()) {
                        player.sendMessage(applyPlaceholders(
                                cfg("messages.admin.station.set.wrong_block", "&cEse bloque debe ser: &f{material}"),
                                Map.of("material", type.getMaterial().name())));
                        return true;
                    }

                    // Slots con tema: /gdx station set slots <tema>
                    String theme = null;
                    if (type == GameItemType.SLOTS && args.length >= 4) {
                        var sc = GamblingDexPlugin.getInstance().getSlotsController();
                        String wanted = args[3].toLowerCase(Locale.ROOT);
                        if (sc == null || !sc.themeExists(wanted)) {
                            player.sendMessage(GamblingDexPlugin.getInstance().color("&cEse tema no existe. Temas: &f"
                                    + (sc == null || sc.themes().isEmpty() ? "-" : String.join(", ", sc.themes()))
                                    + " &7(slots.yml → themes)"));
                            return true;
                        }
                        theme = wanted;
                    }
                    GamblingDexPlugin.getInstance().getStationManager().setStation(target.getLocation(), type, true);
                    if (type == GameItemType.SLOTS)
                        GamblingDexPlugin.getInstance().getStationManager().setTheme(target.getLocation(), theme);
                    if (theme != null)
                        player.sendMessage(GamblingDexPlugin.getInstance().color("&7Tema de la máquina: &f"
                                + GamblingDexPlugin.getInstance().getSlotsController().themeName(theme)));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.set.success",
                                    "&aMesa colocada: &f{type} &7(Click derecho en el bloque)"),
                            Map.of("type", type.getId())));
                    player.sendMessage(
                            cfg("messages.admin.station.set.holo_created", "&7Se creó un holograma arriba."));
                    return true;
                }

                if (action.equals("remove") || action.equals("del") || action.equals("delete")) {
                    Block target = player.getTargetBlockExact(6);
                    if (target == null) {
                        player.sendMessage(cfg("messages.admin.station.remove.look_station",
                                "&cMira la mesa a menos de 6 bloques."));
                        return true;
                    }

                    boolean removed = GamblingDexPlugin.getInstance().getStationManager()
                            .removeStation(target.getLocation());
                    var removeModules = GamblingDexPlugin.getInstance().getModuleManager();
                    if (!removed && removeModules != null && removeModules.removeStation(player, target)) {
                        return true; // el juego ya avisó
                    }
                    if (removed) {
                        player.sendMessage(cfg("messages.admin.station.remove.success", "&aMesa removida."));
                    } else {
                        player.sendMessage(cfg("messages.admin.station.remove.not_registered",
                                "&cEse bloque no está registrado como mesa."));
                    }
                    return true;
                }

                if (action.equals("rotate") || action.equals("girar")) {
                    Block target = player.getTargetBlockExact(6);
                    var sm = GamblingDexPlugin.getInstance().getStationManager();
                    GameItemType rotType = target == null ? null : sm.getStationType(target);
                    if (rotType != GameItemType.SLOTS && rotType != GameItemType.EXCHANGE
                            && rotType != GameItemType.ROULETTE) {
                        player.sendMessage(GamblingDexPlugin.getInstance()
                                .color("&cMira una estación de slots, de cambio o el centro de una ruleta (a menos de 6 bloques)."));
                        return true;
                    }
                    float yaw = sm.getModelYaw(target.getLocation()) + 90f;
                    sm.setModelYaw(target.getLocation(), yaw);
                    var models = GamblingDexPlugin.getInstance().getStationModels();
                    if (models != null)
                        models.respawn(target.getLocation());
                    player.sendMessage(GamblingDexPlugin.getInstance().color("&aModelo de la máquina girado: &f"
                            + Math.round(sm.getModelYaw(target.getLocation())) + "°"
                            + (models == null || !models.active(rotType) ? " &7(ModelEngine no está activo)" : "")));
                    return true;
                }

                if (action.equals("cleanholo")) {
                    if (!player.hasPermission("gamblingdex.admin")) {
                        player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                        return true;
                    }
                    int holos = GamblingDexPlugin.getInstance().getStationManager().cleanOrphanHolograms();
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.cleanup_orphans",
                                    "&aSe eliminaron &e{amount}&a hologramas huérfanos de estaciones."),
                            Map.of("amount", String.valueOf(holos))));
                    return true;
                }

                if (action.equals("list")) {
                    var counts = GamblingDexPlugin.getInstance().getStationManager().countByType();
                    player.sendMessage(cfg("messages.admin.station.list.header", "&7Mesas registradas:"));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.list.roulette", "&8- &cRoulette&7: &f{count}"),
                            Map.of("count", String.valueOf(GamblingDexPlugin.getInstance().getWorldRouletteManager() == null
                                    ? 0 : GamblingDexPlugin.getInstance().getWorldRouletteManager().getTableCount()))));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.list.slots", "&8- &dSlots&7: &f{count}"),
                            Map.of("count", String.valueOf(counts.getOrDefault(GameItemType.SLOTS, 0)))));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.list.exchange", "&8- &eExchange&7: &f{count}"),
                            Map.of("count", String.valueOf(counts.getOrDefault(GameItemType.EXCHANGE, 0)))));
                    var listModules = GamblingDexPlugin.getInstance().getModuleManager();
                    if (listModules != null) {
                        for (String line : listModules.stationListLines())
                            player.sendMessage(line);
                    }
                    return true;
                }

                if (action.equals("debug") || action.equals("info")) {
                    Block target = player.getTargetBlockExact(6);
                    if (target == null) {
                        player.sendMessage(cfg("messages.admin.station.remove.look_station",
                                "&cMira la mesa a menos de 6 bloques."));
                        return true;
                    }

                    var sm = GamblingDexPlugin.getInstance().getStationManager();
                    GameItemType type = sm.getStationType(target);

                    player.sendMessage(cfg("messages.admin.station.debug.header", "&7Station debug:"));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.debug.block",
                                    "&8- &7Block: &f{material} &7@ &f{x},{y},{z} &7(world=&f{world}&7)"),
                            Map.of(
                                    "material", target.getType().name(),
                                    "x", String.valueOf(target.getX()),
                                    "y", String.valueOf(target.getY()),
                                    "z", String.valueOf(target.getZ()),
                                    "world", target.getWorld() == null ? "?" : target.getWorld().getName())));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.debug.resolved_type", "&8- &7Resolved type: &f{type}"),
                            Map.of("type", (type == null ? "<null>" : type.getId()))));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.debug.stored", "&8- &7Stored: &f{stored}"),
                            Map.of("stored", sm.debugLocation(target.getLocation()))));
                    if (type == null) {
                        player.sendMessage(cfg("messages.admin.station.debug.not_found",
                                "&cNo se encontró la estación para esa ubicación."));
                        player.sendMessage(applyPlaceholders(
                                cfg("messages.admin.station.debug.key", "&7Clave buscada: &f{key}"),
                                Map.of("key", sm.key(target.getLocation()))));
                        var stations = sm.getAllStationKeys();
                        player.sendMessage(applyPlaceholders(
                                cfg("messages.admin.station.debug.keys_in_file", "&7Keys en stations.yml: &f{keys}"),
                                Map.of("keys", String.join(", ", stations))));
                        player.sendMessage(cfg("messages.admin.station.debug.coords_tip",
                                "&8- &7Tip: El nombre del mundo y coords deben coincidir EXACTAMENTE con la clave en stations.yml."));
                    }
                    player.sendMessage(cfg("messages.admin.station.debug.tip",
                            "&8- &7Tip: si Resolved type es <null>, el click nunca va a abrir GUI."));
                    return true;
                }

                player.sendMessage(cfg("messages.admin.station.invalid_action",
                        "&cAcción inválida. Usa: set, remove, list, debug"));
                return true;
            }

            // Minijuegos (módulos): /gdx <juego> ...
            var modules = GamblingDexPlugin.getInstance().getModuleManager();
            if (modules != null && modules.dispatch(player, sub, args)) {
                return true;
            }

            if (sub.equals("poker") || sub.equals("pk")) {
                return handlePoker(player, args);
            }

            if (sub.equals("blackjack") || sub.equals("bj")) {
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(cfg(
                            "messages.admin.blackjack.usage",
                        "&cUso: /gdx blackjack <create|remove|list|seat|rename|face|limits> ..."));
                    player.sendMessage(cfg(
                            "messages.admin.blackjack.example",
                            "&7Ej: &f/gdx blackjack create Mesa1&7 (mirando el bloque centro)"));
                    return true;
                }

                String action = args[1].toLowerCase();
                if (action.equals("create") || action.equals("set") || action.equals("add")) {
                    if (args.length < 3) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.create_usage",
                                "&cUso: /gdx blackjack create <name> [min] [max]"));
                        return true;
                    }

                    String name = args[2];
                    Block target = player.getTargetBlockExact(6);
                    if (target == null) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.look_block",
                                "&cMira el bloque donde quieres crear el dealer (<= 6 bloques)."));
                        return true;
                    }

                    var bm = GamblingDexPlugin.getInstance().getBlackjackManager();
                    if (bm == null) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.not_available",
                                "&cBlackjack no está disponible."));
                        return true;
                    }

                    var table = bm.createTable(name, target, player.getLocation());
                    if (table == null) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.create_failed",
                                "&cNo se pudo crear el dealer."));
                        return true;
                    }
                    // /gdx blackjack create <name> [min] [max]
                    if (args.length >= 4) {
                        long min = com.gamblingdex.modules.GameModule.parseAmount(args[3]);
                        long max = args.length >= 5 ? com.gamblingdex.modules.GameModule.parseAmount(args[4]) : 0;
                        if (min > 0 && (max <= 0 || max >= min)) {
                            bm.setLimits(table, min, Math.max(0, max));
                            player.sendMessage(GamblingDexPlugin.getInstance().color("&7Apuesta de la mesa: &e"
                                    + formatLong(min) + " &7- &e" + (max > 0 ? formatLong(max) : "∞")
                                    + " &8| &7Laterales: mín. &e" + formatLong(table.getSideMinBet()) + " &7(sin tope)"));
                        }
                    }
                    GamblingDexPlugin.getInstance().syncTableNames();

                    player.sendMessage(cfg(
                            "messages.admin.blackjack.created",
                            "&aDealer de Blackjack creado. &7(Jugadores: párense en asientos; menús automáticos)"));
                    return true;
                }

                if (action.equals("close") || action.equals("open") || action.equals("move")) {
                    GamblingDexPlugin pl = GamblingDexPlugin.getInstance();
                    var bm = pl.getBlackjackManager();
                    var t = bm == null || args.length < 3 ? null : bm.getByName(args[2]);
                    if (t == null) {
                        player.sendMessage(pl.color("&cUso: /gdx blackjack " + action + " <name>"
                                + (bm == null ? "" : " &7(mesas: &f" + String.join(", ", bm.getTableNames()) + "&7)")));
                        return true;
                    }
                    if (action.equals("move")) {
                        Block target = player.getTargetBlockExact(6);
                        if (target == null) {
                            player.sendMessage(pl.color("&cMira el bloque de la mesa nueva (a menos de 6 bloques)."));
                            return true;
                        }
                        String err = bm.moveTable(t.getDisplayName(), target, player.getLocation());
                        player.sendMessage(pl.color(err == null
                                ? "&aMesa &f" + args[2] + " &amovida. &7Nombre, límites y asientos se conservan (los asientos se movieron lo mismo que la mesa)."
                                : err.equals("busy") ? "&cHay una ronda en curso. Espera a que termine."
                                        : err.equals("occupied") ? "&cAhí ya hay otra mesa de blackjack." : "&cNo se pudo mover la mesa."));
                        if (err == null)
                            pl.syncTableNames();
                        return true;
                    }
                    var mt = pl.getMaintenance();
                    if (action.equals("close")) {
                        if (!mt.closeTable("blackjack", t.getDisplayName())) {
                            player.sendMessage(pl.color("&eEsa mesa ya estaba cerrada."));
                            return true;
                        }
                        t.abortRound();
                        t.refreshHologram();
                        player.sendMessage(pl.color("&aMesa &f" + t.getDisplayName() + " &acerrada. &7Se devolvieron las apuestas; las demás mesas siguen abiertas."));
                    } else {
                        player.sendMessage(pl.color(mt.openTable("blackjack", t.getDisplayName())
                                ? "&aMesa &f" + t.getDisplayName() + " &aabierta otra vez." : "&eEsa mesa no estaba cerrada."));
                    }
                    return true;
                }

                if (action.equals("remove") || action.equals("del") || action.equals("delete")) {
                    if (args.length < 3) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.remove_usage",
                                "&cUso: /gdx blackjack remove <name>"));
                        return true;
                    }

                    String name = args[2];

                    var bm = GamblingDexPlugin.getInstance().getBlackjackManager();
                    if (bm == null) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.not_available",
                                "&cBlackjack no está disponible."));
                        return true;
                    }

                    boolean removed = bm.removeTable(name, true);
                    if (removed) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.removed",
                                "&aMesa de Blackjack removida."));
                    } else {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.not_found",
                                "&cNo existe un Blackjack con ese nombre."));
                    }
                    return true;
                }

                if (action.equals("list")) {
                    var bm = GamblingDexPlugin.getInstance().getBlackjackManager();
                    int count = bm == null ? 0 : bm.getTableCount();
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.blackjack.list", "&7Mesas Blackjack: &f{count}"),
                            Map.of("count", String.valueOf(count))));

                    if (bm != null) {
                        var names = bm.getTableNames();
                        if (!names.isEmpty()) {
                            player.sendMessage(cfg("messages.admin.blackjack.list_names_header", "&7Nombres:"));
                            for (String n : names) {
                                player.sendMessage(applyPlaceholders(
                                        cfg("messages.admin.blackjack.list_names_line", "&8- &f{name}"),
                                        Map.of("name", n)));
                            }
                        }
                    }
                    return true;
                }

                if (action.equals("limits") || action.equals("limit") || action.equals("bets")
                        || action.equals("limites")) {
                    var bm = GamblingDexPlugin.getInstance().getBlackjackManager();
                    var t = bm == null || args.length < 4 ? null : bm.getByName(args[2]);
                    long min = args.length >= 4 ? com.gamblingdex.modules.GameModule.parseAmount(args[3]) : -1;
                    long max = args.length >= 5 ? com.gamblingdex.modules.GameModule.parseAmount(args[4]) : 0;
                    if (t == null || min < 0 || max < 0 || (max > 0 && max < min)) {
                        player.sendMessage(cfg("messages.admin.blackjack.limits_usage",
                                "&cUso: /gdx blackjack limits <table> <min> [max] &7(max 0 = sin tope; 10k, 1m...)"));
                        return true;
                    }
                    bm.setLimits(t, min, max);
                    player.sendMessage(GamblingDexPlugin.getInstance().color("&aMesa &f" + t.getDisplayName()
                            + "&a: apuesta &e" + formatLong(t.getMinBet()) + " &a- &e"
                            + (t.getMaxBet() > 0 ? formatLong(t.getMaxBet()) : "∞")
                            + " &8| &7Laterales: mín. &e" + formatLong(t.getSideMinBet()) + " &7(sin tope)"));
                    return true;
                }

                if (action.equals("face") || action.equals("look") || action.equals("mirar")) {
                    var bm = GamblingDexPlugin.getInstance().getBlackjackManager();
                    var t = bm == null || args.length < 3 ? null : bm.getByName(args[2]);
                    if (t == null) {
                        player.sendMessage(cfg("messages.admin.blackjack.face_usage",
                                "&cUso: /gdx blackjack face <table> &7(el dealer mirará hacia donde estás)"));
                        return true;
                    }
                    bm.faceTowards(t, player.getLocation());
                    player.sendMessage(cfg("messages.admin.blackjack.faced", "&aEl dealer ahora mira hacia ti."));
                    return true;
                }

                if (action.equals("rename") || action.equals("renombrar")) {
                    if (args.length < 4) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.rename_usage",
                                "&cUso: /gdx blackjack rename <table> <name...>"));
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.rename_example",
                                "&7Ej: &f/gdx blackjack rename Mesa1 &6&l♠ BLACKJACK &8| &eVIP"));
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.rename_reset",
                                "&7Tip: usa &f- &7para resetear al nombre interno."));
                        return true;
                    }

                    String name = args[2];
                    StringBuilder sb = new StringBuilder();
                    for (int i = 3; i < args.length; i++) {
                        if (i > 3) {
                            sb.append(' ');
                        }
                        sb.append(args[i]);
                    }
                    String pretty = sb.toString().trim();
                    if (pretty.equals("-") || pretty.equalsIgnoreCase("reset")) {
                        pretty = null;
                    }

                    var bm = GamblingDexPlugin.getInstance().getBlackjackManager();
                    if (bm == null) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.not_available",
                                "&cBlackjack no está disponible."));
                        return true;
                    }

                    var bjTable = bm.getByName(name);
                    if (bjTable != null)
                        GamblingDexPlugin.getInstance().setTableNameInConfig("blackjack", bjTable.getDisplayName(), pretty);
                    boolean ok = bm.setPrettyName(name, pretty);
                    if (ok) {
                        if (bjTable != null)
                            bjTable.refreshHologram();
                        player.sendMessage(GamblingDexPlugin.getInstance().color("&aMesa renombrada: &r"
                                + GamblingDexPlugin.getInstance().color(pretty == null ? name : pretty)));
                    } else {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.not_found",
                                "&cNo existe un Blackjack con ese nombre."));
                    }
                    return true;
                }

                if (action.equals("seat") || action.equals("seats")) {
                    if (args.length < 4) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.seat_usage",
                                "&cUso: /gdx blackjack seat <add|remove|list|clear> <name>"));
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.seat_example",
                                "&7Ej: &f/gdx blackjack seat add Mesa1&7 (parado en el asiento)"));
                        return true;
                    }

                    String seatAction = args[2].toLowerCase();
                    String name = args[3];

                    var bm = GamblingDexPlugin.getInstance().getBlackjackManager();
                    if (bm == null) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.not_available",
                                "&cBlackjack no está disponible."));
                        return true;
                    }
                    if (seatAction.equals("add") || seatAction.equals("set")) {
                        Block seat = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
                        boolean ok = bm.addSeat(name, seat);
                        if (ok) {
                            player.sendMessage(applyPlaceholders(
                                    cfg("messages.admin.blackjack.seat_added", "&aAsiento agregado: &f{x},{y},{z}"),
                                    Map.of(
                                            "x", String.valueOf(seat.getX()),
                                            "y", String.valueOf(seat.getY()),
                                            "z", String.valueOf(seat.getZ()))));
                        } else {
                            player.sendMessage(cfg("messages.admin.blackjack.not_found",
                                    "&cNo existe un Blackjack con ese nombre."));
                        }
                        return true;
                    }

                    if (seatAction.equals("remove") || seatAction.equals("del") || seatAction.equals("delete")) {
                        Block seat = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
                        boolean ok = bm.removeSeat(name, seat);
                        if (ok) {
                            player.sendMessage(applyPlaceholders(
                                    cfg("messages.admin.blackjack.seat_removed", "&aAsiento removido: &f{x},{y},{z}"),
                                    Map.of(
                                            "x", String.valueOf(seat.getX()),
                                            "y", String.valueOf(seat.getY()),
                                            "z", String.valueOf(seat.getZ()))));
                        } else {
                            player.sendMessage(cfg("messages.admin.blackjack.seat_not_found", "&cEse asiento no está registrado."));
                        }
                        return true;
                    }

                    if (seatAction.equals("clear") || seatAction.equals("reset")) {
                        boolean ok = bm.clearSeats(name);
                        if (ok) {
                            player.sendMessage(cfg("messages.admin.blackjack.seat_cleared", "&aAsientos limpiados."));
                        } else {
                            player.sendMessage(cfg("messages.admin.blackjack.not_found",
                                    "&cNo existe un Blackjack con ese nombre."));
                        }
                        return true;
                    }

                    if (seatAction.equals("list")) {
                        List<String> seats = bm.listSeats(name);
                        player.sendMessage(applyPlaceholders(
                                cfg("messages.admin.blackjack.seat_list_header", "&7Asientos: &f{count}"),
                                Map.of("count", String.valueOf(seats.size()))));
                        for (String s : seats) {
                            player.sendMessage(applyPlaceholders(
                                    cfg("messages.admin.blackjack.seat_list_line", "&8- &f{seat}"),
                                    Map.of("seat", s)));
                        }
                        return true;
                    }

                    player.sendMessage(cfg("messages.admin.blackjack.seat_invalid_action",
                            "&cAcción inválida. Usa: add, remove, list, clear"));
                    return true;
                }

                player.sendMessage(cfg(
                        "messages.admin.blackjack.invalid_action",
                    "&cAcción inválida. Usa: create, remove, list, seat, rename, face, limits"));
                return true;
            }

            if (sub.equals("roulette") || sub.equals("ruleta")) {
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(
                            cfg("messages.admin.roulette.usage", "&cUso: /gdx roulette <build|remove|list> ..."));
                    player.sendMessage(cfg("messages.admin.roulette.example",
                            "&7Ej: &f/gdx roulette build 6 -1&7 (mirando el centro)"));
                    return true;
                }

                String action = args[1].toLowerCase();
                if (action.equals("build") || action.equals("crear")) {
                    Block target = player.getTargetBlockExact(6);
                    if (target == null) {
                        player.sendMessage(cfg("messages.admin.roulette.build.look_center",
                                "&cMira un bloque (será el centro) a menos de 6 bloques."));
                        return true;
                    }

                    int radius = GamblingDexPlugin.getInstance().getConfig().getInt("roulette_world.radius", 6);
                    if (args.length >= 3) {
                        try {
                            radius = Integer.parseInt(args[2]);
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    Integer yOffset = null;
                    if (args.length >= 4) {
                        try {
                            yOffset = Integer.parseInt(args[3]);
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    var table = GamblingDexPlugin.getInstance().getWorldRouletteManager()
                            .buildTable(target, radius, yOffset);
                    if (table == null) {
                        player.sendMessage(cfg("messages.admin.roulette.build.failed",
                                "&cNo se pudo construir la ruleta aquí."));
                        return true;
                    }
                    player.sendMessage(cfg("messages.admin.roulette.build.success",
                            "&aRuleta construida. &7Click izquierdo al centro: cambia apuesta."));
                    player.sendMessage(cfg("messages.admin.roulette.build.how_to_1",
                            "&7Click derecho con tokens: apuesta. Click a números para elegir."));
                    return true;
                }

                if (action.equals("close") || action.equals("open")) {
                    GamblingDexPlugin pl = GamblingDexPlugin.getInstance();
                    Block target = player.getTargetBlockExact(6);
                    var rt = target == null ? null : pl.getWorldRouletteManager().getByBlock(target);
                    if (rt == null) {
                        player.sendMessage(pl.color("&cMira la ruleta (el centro o una casilla) a menos de 6 bloques."));
                        return true;
                    }
                    var mt = pl.getMaintenance();
                    if (action.equals("close")) {
                        if (!mt.closeTable("ruleta", rt.getTableKey())) {
                            player.sendMessage(pl.color("&eEsa ruleta ya estaba cerrada."));
                            return true;
                        }
                        rt.abortRound();
                        player.sendMessage(pl.color("&aRuleta cerrada. &7Se devolvieron las apuestas; las demás siguen abiertas."));
                    } else {
                        player.sendMessage(pl.color(mt.openTable("ruleta", rt.getTableKey())
                                ? "&aRuleta abierta otra vez." : "&eEsa ruleta no estaba cerrada."));
                    }
                    return true;
                }

                if (action.equals("remove") || action.equals("delete") || action.equals("del")) {
                    Block target = player.getTargetBlockExact(6);
                    if (target == null) {
                        player.sendMessage(cfg("messages.admin.roulette.remove.look_center",
                                "&cMira el centro de la ruleta a menos de 6 bloques."));
                        return true;
                    }

                    boolean ok = GamblingDexPlugin.getInstance().getWorldRouletteManager().removeTable(target, true,
                            true);
                    if (ok) {
                        player.sendMessage(cfg("messages.admin.roulette.remove.success",
                                "&aRuleta eliminada: &7hologramas y bloques removidos."));
                    } else {
                        player.sendMessage(cfg("messages.admin.roulette.remove.none",
                                "&cNo hay una ruleta registrada en ese bloque."));
                    }
                    return true;
                }

                if (action.equals("list")) {
                    int count = GamblingDexPlugin.getInstance().getWorldRouletteManager().getTableCount();
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.roulette.list", "&7Ruletas registradas: &f{count}"),
                            Map.of("count", String.valueOf(count))));
                    return true;
                }

                player.sendMessage(cfg("messages.admin.roulette.invalid_action",
                        "&cAcción inválida. Usa: build, remove, list"));
                return true;
            }
        }

        HelpMenu.send(player, new String[0]);
        return true;
    }

    private static String formatLong(long n) {
        try {
            return NumberFormat.getInstance(new Locale("es", "ES")).format(n);
        } catch (Exception ignored) {
            return String.valueOf(n);
        }
    }

    // =====================================================================
    // /gdx poker
    // =====================================================================

    private static String pk(String key, String def, String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            m.put(kv[i], kv[i + 1]);
        return GamblingDexPlugin.getInstance().getMessages().format("poker.admin." + key, def, m);
    }

    private static long parseUnits(String s) {
        try {
            return Long.parseLong(s.replace(".", "").replace(",", "").trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private boolean handlePoker(Player player, String[] args) {
        var pm = GamblingDexPlugin.getInstance().getPokerManager();
        if (pm == null) {
            player.sendMessage(pk("not_available", "&cEl póker no está disponible."));
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "help";

        // Acción de jugador: abrir el menú de su mesa.
        if (action.equals("menu")) {
            var t = pm.getTableOf(player.getUniqueId());
            if (t == null) {
                player.sendMessage(pk("not_seated", "&cNo estás sentado en una mesa de póker."));
            } else {
                t.openMenu(player);
            }
            return true;
        }

        if (action.equals("join") || action.equals("inscribirme") || action.equals("register")) {
            var t = pm.getTableOf(player.getUniqueId());
            if (t == null) {
                player.sendMessage(pk("not_seated", "&cNo estás sentado en una mesa de póker."));
            } else {
                t.register(player);
            }
            return true;
        }

        boolean admin = player.hasPermission("gamblingdex.admin");
        if (action.equals("help") || !admin) {
            player.sendMessage(pk("help_player",
                    "&6Póker &8» &7Párate en un asiento de la mesa para jugar. &f/gdx poker menu &7abre tu menú."));
            if (admin) {
                player.sendMessage(pk("help_admin_1",
                        "&e/gdx poker create <name> [small blind] [big blind] &7(mirando el bloque centro)"));
                player.sendMessage(pk("help_admin_2",
                        "&e/gdx poker seat <add|remove|list|clear> <name> &7(parado sobre el asiento)"));
                player.sendMessage(pk("help_admin_3",
                        "&e/gdx poker stakes <name> <small> <big> &8| &e/gdx poker remove <name>"));
                player.sendMessage(pk("help_admin_4", "&e/gdx poker list &8| &e/gdx poker rake"));
                player.sendMessage(pk("help_admin_5",
                        "&e/gdx poker tournament <table> <fee> [chips] [minutes] &8| &e/gdx poker tournament <start|cancel> <table>"));
            }
            return true;
        }

        switch (action) {
            case "create", "add", "set" -> {
                if (args.length < 3) {
                    player.sendMessage(pk("create_usage",
                            "&cUso: /gdx poker create <name> [small blind] [big blind]"));
                    return true;
                }
                Block target = player.getTargetBlockExact(6);
                if (target == null) {
                    player.sendMessage(pk("look_block", "&cMira el bloque centro de la mesa (<= 6 bloques)."));
                    return true;
                }
                long sb = args.length >= 4 ? parseUnits(args[3]) : 0L;
                long bb = args.length >= 5 ? parseUnits(args[4]) : 0L;
                if (sb < 0 || bb < 0 || (bb > 0 && bb < sb)) {
                    player.sendMessage(pk("invalid_stakes", "&cCiegas inválidas. La grande debe ser >= la chica."));
                    return true;
                }
                var t = pm.createTable(args[2], target, sb, bb);
                if (t == null) {
                    player.sendMessage(pk("create_failed",
                            "&cNo se pudo crear: ya existe una mesa con ese nombre o en ese bloque."));
                    return true;
                }
                GamblingDexPlugin.getInstance().syncTableNames();
                player.sendMessage(pk("created",
                        "&aMesa de póker &f{table}&a creada (ciegas &e{sb}/{bb}&a). Agrega asientos con &f/gdx poker seat add {table}&a, en orden horario.",
                        "table", t.getName(), "sb", String.valueOf(t.getSmallBlind()),
                        "bb", String.valueOf(t.getBigBlind())));
            }
            case "close", "open" -> {
                var t = args.length < 3 ? null : pm.getByName(args[2]);
                if (t == null) {
                    player.sendMessage(GamblingDexPlugin.getInstance().color("&cUso: /gdx poker " + action
                            + " <name> &7(mesas: &f" + String.join(", ", pm.getTableNames()) + "&7)"));
                    return true;
                }
                var mt = GamblingDexPlugin.getInstance().getMaintenance();
                if (action.equals("close")) {
                    if (!mt.closeTable("poker", t.getName())) {
                        player.sendMessage(GamblingDexPlugin.getInstance().color("&eEsa mesa ya estaba cerrada."));
                        return true;
                    }
                    t.shutdown(); // devuelve las fichas y levanta a todos
                    t.updateDisplays();
                    player.sendMessage(GamblingDexPlugin.getInstance().color("&aMesa &f" + t.getName()
                            + " &acerrada. &7Se devolvieron las fichas; las demás mesas siguen abiertas."));
                } else {
                    player.sendMessage(GamblingDexPlugin.getInstance().color(mt.openTable("poker", t.getName())
                            ? "&aMesa &f" + t.getName() + " &aabierta otra vez." : "&eEsa mesa no estaba cerrada."));
                }
            }
            case "move" -> {
                Block target = player.getTargetBlockExact(6);
                if (args.length < 3 || target == null) {
                    player.sendMessage(GamblingDexPlugin.getInstance().color("&cUso: /gdx poker move <name> &7(mirando el centro de la mesa nueva)"));
                    return true;
                }
                String err = pm.moveTable(args[2], target);
                player.sendMessage(GamblingDexPlugin.getInstance().color(err == null
                        ? "&aMesa &f" + args[2] + " &amovida. &7Nombre, ciegas y asientos se conservan (los asientos se movieron lo mismo que la mesa)."
                        : err.equals("busy") ? "&cLa mesa tiene que estar vacía (sin jugadores ni torneo)."
                                : err.equals("occupied") ? "&cAhí ya hay otra mesa de póker." : pokerError("not_found")));
                if (err == null)
                    GamblingDexPlugin.getInstance().syncTableNames();
            }
            case "remove", "del", "delete" -> {
                if (args.length < 3) {
                    player.sendMessage(pk("remove_usage", "&cUso: /gdx poker remove <name>"));
                    return true;
                }
                player.sendMessage(pm.removeTable(args[2])
                        ? pk("removed", "&aMesa de póker eliminada. Se devolvieron las fichas a los jugadores.")
                        : pokerError("not_found"));
            }
            case "list" -> {
                var names = pm.getTableNames();
                player.sendMessage(pk("list", "&7Mesas de póker: &f{count}", "count", String.valueOf(names.size())));
                for (String n : names) {
                    var t = pm.getByName(n);
                    player.sendMessage(pk("list_line", "&8- &f{table} &7ciegas &e{sb}/{bb} &7asientos &f{seats}",
                            "table", n, "sb", String.valueOf(t.getSmallBlind()),
                            "bb", String.valueOf(t.getBigBlind()),
                            "seats", String.valueOf(t.getSeatKeys().size())));
                }
            }
            case "rename", "renombrar" -> {
                if (args.length < 4 || pm.getByName(args[2]) == null) {
                    player.sendMessage(pk("rename_usage", "&cUso: /gdx poker rename <table> <name...> &7(&f-&7 = nombre interno)"));
                    return true;
                }
                var t = pm.getByName(args[2]);
                String pretty = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
                if (pretty.equals("-") || pretty.equalsIgnoreCase("reset"))
                    pretty = null;
                GamblingDexPlugin.getInstance().setTableNameInConfig("poker", t.getName(), pretty);
                t.updateDisplays();
                player.sendMessage(GamblingDexPlugin.getInstance().color("&aMesa renombrada: &r" + t.getDisplayName()));
            }
            case "stakes", "blinds", "ciegas" -> {
                if (args.length < 5) {
                    player.sendMessage(pk("stakes_usage",
                            "&cUso: /gdx poker stakes <name> <small blind> <big blind>"));
                    return true;
                }
                String err = pm.setStakes(args[2], parseUnits(args[3]), parseUnits(args[4]));
                player.sendMessage(err == null ? pk("stakes_set", "&aCiegas actualizadas.") : pokerError(err));
            }
            case "torneo", "tournament" -> handlePokerTournament(player, pm, args);
            case "rake" -> player.sendMessage(pk("rake_total",
                    "&7Comisión total cobrada por las mesas de póker: &e{amount}",
                    "amount", String.valueOf(pm.getRakeTotal())));
            case "seat", "seats" -> {
                if (args.length < 4) {
                    player.sendMessage(pk("seat_usage", "&cUso: /gdx poker seat <add|remove|list|clear> <name>"));
                    player.sendMessage(pk("seat_tip", "&7Párate sobre el bloque del asiento. Agrégalos en orden horario."));
                    return true;
                }
                String seatAction = args[2].toLowerCase(Locale.ROOT);
                String name = args[3];
                Block seat = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
                String coords = seat.getX() + "," + seat.getY() + "," + seat.getZ();
                switch (seatAction) {
                    case "add", "set" -> {
                        String err = pm.addSeat(name, seat);
                        player.sendMessage(err == null
                                ? pk("seat_added", "&aAsiento agregado: &f{coords}", "coords", coords)
                                : pokerError(err));
                    }
                    case "remove", "del", "delete" -> {
                        String err = pm.removeSeat(name, seat);
                        player.sendMessage(err == null
                                ? pk("seat_removed", "&aAsiento quitado: &f{coords}", "coords", coords)
                                : pokerError(err));
                    }
                    case "clear", "reset" -> {
                        String err = pm.clearSeats(name);
                        player.sendMessage(err == null ? pk("seats_cleared", "&aAsientos borrados.") : pokerError(err));
                    }
                    case "list" -> {
                        var t = pm.getByName(name);
                        if (t == null) {
                            player.sendMessage(pokerError("not_found"));
                            return true;
                        }
                        List<String> keys = t.getSeatKeys();
                        player.sendMessage(pk("seat_list", "&7Asientos: &f{count}", "count", String.valueOf(keys.size())));
                        for (int i = 0; i < keys.size(); i++) {
                            player.sendMessage(pk("seat_list_line", "&8{n}. &f{seat}",
                                    "n", String.valueOf(i + 1), "seat", keys.get(i)));
                        }
                    }
                    default -> player.sendMessage(pk("seat_usage",
                            "&cUso: /gdx poker seat <add|remove|list|clear> <name>"));
                }
            }
            default -> player.sendMessage(pk("invalid_action",
                    "&cAcción inválida. Usa: create, remove, list, seat, stakes, rake, menu"));
        }
        return true;
    }

    /**
     * /gdx poker tournament &lt;mesa&gt; &lt;inscripción&gt; [fichas] [minutos]
     * /gdx poker tournament start|cancelar &lt;mesa&gt;
     */
    private void handlePokerTournament(Player player, com.gamblingdex.games.poker.PokerManager pm, String[] args) {
        if (args.length < 3) {
            player.sendMessage(pk("tournament_usage",
                    "&cUso: /gdx poker tournament <table> <fee> [chips] [minutes per level]"));
            player.sendMessage(pk("tournament_usage2", "&c     /gdx poker tournament <start|cancel> <table>"));
            return;
        }
        String sub = args[2].toLowerCase(Locale.ROOT);
        if (sub.equals("empezar") || sub.equals("start") || sub.equals("cancelar") || sub.equals("cancel")) {
            var t = args.length >= 4 ? pm.getByName(args[3]) : null;
            if (t == null) {
                player.sendMessage(pokerError("not_found"));
                return;
            }
            if (sub.startsWith("e") || sub.equals("start")) {
                String err = t.startTournament();
                if (err != null)
                    player.sendMessage(err);
            } else {
                if (!t.isTournament()) {
                    player.sendMessage(pk("tournament_none", "&cEsa mesa no tiene torneo."));
                    return;
                }
                t.cancelTournament();
                player.sendMessage(pk("tournament_cancelled", "&aTorneo cancelado. Se devolvieron las inscripciones."));
            }
            return;
        }

        var t = pm.getByName(args[2]);
        if (t == null) {
            player.sendMessage(pokerError("not_found"));
            return;
        }
        if (args.length < 4) {
            player.sendMessage(pk("tournament_usage",
                    "&cUso: /gdx poker tournament <table> <fee> [chips] [minutes per level]"));
            return;
        }
        var cfg = GamblingDexPlugin.getInstance().getConfig();
        long fee = parseUnits(args[3]);
        long stack = args.length >= 5 ? parseUnits(args[4])
                : t.getBigBlind() * Math.max(10, cfg.getLong("poker.tournament.starting_stack_bb", 100));
        int minutes = cfg.getInt("poker.tournament.level_minutes", 5);
        if (args.length >= 6) {
            try {
                minutes = Integer.parseInt(args[5]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (fee <= 0 || stack <= 0 || minutes <= 0) {
            player.sendMessage(pk("tournament_invalid", "&cValores inválidos. La inscripción y las fichas deben ser > 0."));
            return;
        }
        String err = t.openTournament(fee, stack, minutes);
        if (err != null) {
            player.sendMessage(err);
            return;
        }
        player.sendMessage(pk("tournament_opened",
                "&aInscripción abierta en &f{table}&a. Cuando estén todos: &f/gdx poker tournament start {table}",
                "table", t.getName()));
    }

    private static String pokerError(String code) {
        return switch (code) {
            case "not_found" -> pk("not_found", "&cNo existe una mesa de póker con ese nombre.");
            case "hand_running" -> pk("hand_running", "&cHay una mano en curso. Espera a que termine.");
            case "exists" -> pk("seat_exists", "&eEse asiento ya está registrado.");
            case "missing" -> pk("seat_missing", "&cEse asiento no está registrado.");
            case "full" -> pk("seats_full", "&cLa mesa ya tiene el máximo de asientos (poker.max_seats).");
            case "invalid" -> pk("invalid_stakes", "&cCiegas inválidas. La grande debe ser >= la chica.");
            default -> pk("error", "&cNo se pudo completar la acción.");
        };
    }

    /** Tipos de /gdx station set que agregan los juegos (crash, carrera, ...). */
    private static void sendModuleStationTypes(Player player) {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        var modules = plugin.getModuleManager();
        if (modules == null)
            return;
        List<String> usages = modules.stationUsages();
        if (usages.isEmpty())
            return;
        player.sendMessage(plugin.color("&7Juegos: &f" + String.join("&7, &f", usages)));
    }
}
