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
                long balance = GamblingDexPlugin.getInstance().getEconomy().getBalance(player.getUniqueId());
                String currencyName = GamblingDexPlugin.getInstance().color(
                        GamblingDexPlugin.getInstance().getConfig().getString("currency.name", "Moneda GDX"));
                player.sendMessage(applyPlaceholders(
                        cfg("messages.gdx.balance", "&aTu balance es: &e{amount}&a {currency}."),
                        Map.of("amount", String.valueOf(balance), "currency", currencyName)));
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

                int limit = 10;
                if (args.length >= 2) {
                    try {
                        limit = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }

                String currencyName = GamblingDexPlugin.getInstance().color(
                        GamblingDexPlugin.getInstance().getConfig().getString("currency.name", "⛃"));

                player.sendMessage(cfg("messages.gdx.top.header", "&6&lTop Ganancias &7(Slots+Ruleta)"));

                Map<UUID, Long> totalWonByPlayer = new HashMap<>();
                var slotsSnap = GamblingDexPlugin.getInstance().getSlotsStatsManager().snapshot();
                var rouletteSnap = GamblingDexPlugin.getInstance().getRouletteStatsManager().snapshot();

                Set<UUID> all = new HashSet<>();
                all.addAll(slotsSnap.keySet());
                all.addAll(rouletteSnap.keySet());

                for (UUID id : all) {
                    long won = 0L;
                    var s = slotsSnap.get(id);
                    if (s != null)
                        won += s.getTotalPayoutUnits();
                    var r = rouletteSnap.get(id);
                    if (r != null)
                        won += r.getTotalPayoutUnits();
                    totalWonByPlayer.put(id, won);
                }

                List<Map.Entry<UUID, Long>> list = new ArrayList<>(totalWonByPlayer.entrySet());
                list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

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
                // /gdx token <cantidad> -> yellow (1)
                // /gdx token <color|valor> <cantidad> -> chosen denom
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

            if (sub.equals("help")) {
                sendHelp(player);
                return true;
            }

            if (sub.equals("item") || sub.equals("items")) {
                if (!player.hasPermission("gamblingdex.admin")) {
                    player.sendMessage(cfg("messages.admin.no_permission", "&cNo tienes permiso para hacer eso."));
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(
                            cfg("messages.admin.item.usage", "&cUso: /gdx item <roulette|slots> [cantidad]"));
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
                        player.sendMessage(cfg("messages.admin.station.set.roulette_tip",
                                "&7(Para ruleta física usa: &f/gdx roulette build [radio] [yOffset]&7)"));
                        return true;
                    }

                    GameItemType type = GameItemType.fromArg(args[2]);
                    if (type == null) {
                        player.sendMessage(cfg("messages.admin.station.set.invalid_type",
                                "&cTipo inválido. Usa: slots, exchange"));
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
                                "&cLa ruleta ahora es una mesa física. Usa: &f/gdx roulette build [radio] [yOffset]"));
                        return true;
                    }

                    if (target.getType() != type.getMaterial()) {
                        player.sendMessage(applyPlaceholders(
                                cfg("messages.admin.station.set.wrong_block", "&cEse bloque debe ser: &f{material}"),
                                Map.of("material", type.getMaterial().name())));
                        return true;
                    }

                    GamblingDexPlugin.getInstance().getStationManager().setStation(target.getLocation(), type, true);
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
                    if (removed) {
                        player.sendMessage(cfg("messages.admin.station.remove.success", "&aMesa removida."));
                    } else {
                        player.sendMessage(cfg("messages.admin.station.remove.not_registered",
                                "&cEse bloque no está registrado como mesa."));
                    }
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
                            Map.of("count", String.valueOf(counts.getOrDefault(GameItemType.ROULETTE, 0)))));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.list.slots", "&8- &dSlots&7: &f{count}"),
                            Map.of("count", String.valueOf(counts.getOrDefault(GameItemType.SLOTS, 0)))));
                    player.sendMessage(applyPlaceholders(
                            cfg("messages.admin.station.list.exchange", "&8- &eExchange&7: &f{count}"),
                            Map.of("count", String.valueOf(counts.getOrDefault(GameItemType.EXCHANGE, 0)))));
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
                        "&cUso: /gdx blackjack <create|remove|list|seat|displayname> ..."));
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
                                "&cUso: /gdx blackjack create <nombre>"));
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

                    var table = bm.createTable(name, target);
                    if (table == null) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.create_failed",
                                "&cNo se pudo crear el dealer."));
                        return true;
                    }

                    player.sendMessage(cfg(
                            "messages.admin.blackjack.created",
                            "&aDealer de Blackjack creado. &7(Jugadores: párense en asientos; menús automáticos)"));
                    return true;
                }

                if (action.equals("remove") || action.equals("del") || action.equals("delete")) {
                    if (args.length < 3) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.remove_usage",
                                "&cUso: /gdx blackjack remove <nombre>"));
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

                if (action.equals("displayname") || action.equals("prettyname") || action.equals("name")) {
                    if (args.length < 4) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.displayname_usage",
                                "&cUso: /gdx blackjack displayname <mesa> <nombre...>"));
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.displayname_example",
                                "&7Ej: &f/gdx blackjack displayname Mesa1 &6&lBlackjack &8| &eVIP"));
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.displayname_reset",
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

                    boolean ok = bm.setPrettyName(name, pretty);
                    if (ok) {
                        player.sendMessage(cfg(
                                "messages.admin.blackjack.displayname_set",
                                "&aNombre estético actualizado."));
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
                                "&cUso: /gdx blackjack seat <add|remove|list|clear> <nombre>"));
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
                    "&cAcción inválida. Usa: create, remove, list, seat, displayname"));
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

        sendHelp(player);
        return true;
    }

    private static void sendHelp(Player player) {
        String version = GamblingDexPlugin.getInstance().getDescription().getVersion();
        player.sendMessage(applyPlaceholders(cfg("messages.gdx.help.header", "&a&lGamblingDex &7v{version}"),
                Map.of("version", version)));
        player.sendMessage(cfg("messages.gdx.help.subheader", "&7Comandos: &f/gdx &7(aliás: &f/gamblingdex&7)"));

        List<String> playerLines = GamblingDexPlugin.getInstance().getMessages() == null
                ? List.of()
                : GamblingDexPlugin.getInstance().getMessages().getStringList("gdx.help.player_lines");
        for (String line : playerLines) {
            if (line == null)
                continue;
            if (line.isBlank())
                player.sendMessage(" ");
            else
                player.sendMessage(line);
        }

        if (player.hasPermission("gamblingdex.admin")) {
            List<String> adminLines = GamblingDexPlugin.getInstance().getMessages() == null
                    ? List.of()
                    : GamblingDexPlugin.getInstance().getMessages().getStringList("gdx.help.admin_lines");
            for (String line : adminLines) {
                if (line == null)
                    continue;
                if (line.isBlank())
                    player.sendMessage(" ");
                else
                    player.sendMessage(line);
            }
        }

        player.sendMessage(cfg("messages.gdx.help.footer",
                "&7Config: &fplugins/GamblingDex/config.yml &8(ver sección &fPERMISOS&8)"));
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

        boolean admin = player.hasPermission("gamblingdex.admin");
        if (action.equals("help") || !admin) {
            player.sendMessage(pk("help_player",
                    "&6Póker &8» &7Párate en un asiento de la mesa para jugar. &f/gdx poker menu &7abre tu menú."));
            if (admin) {
                player.sendMessage(pk("help_admin_1",
                        "&e/gdx poker create <nombre> [ciega chica] [ciega grande] &7(mirando el bloque centro)"));
                player.sendMessage(pk("help_admin_2",
                        "&e/gdx poker seat <add|remove|list|clear> <nombre> &7(parado sobre el asiento)"));
                player.sendMessage(pk("help_admin_3",
                        "&e/gdx poker stakes <nombre> <chica> <grande> &8| &e/gdx poker remove <nombre>"));
                player.sendMessage(pk("help_admin_4", "&e/gdx poker list &8| &e/gdx poker rake"));
            }
            return true;
        }

        switch (action) {
            case "create", "add", "set" -> {
                if (args.length < 3) {
                    player.sendMessage(pk("create_usage",
                            "&cUso: /gdx poker create <nombre> [ciega chica] [ciega grande]"));
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
                player.sendMessage(pk("created",
                        "&aMesa de póker &f{table}&a creada (ciegas &e{sb}/{bb}&a). Agrega asientos con &f/gdx poker seat add {table}&a, en orden horario.",
                        "table", t.getName(), "sb", String.valueOf(t.getSmallBlind()),
                        "bb", String.valueOf(t.getBigBlind())));
            }
            case "remove", "del", "delete" -> {
                if (args.length < 3) {
                    player.sendMessage(pk("remove_usage", "&cUso: /gdx poker remove <nombre>"));
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
            case "stakes", "blinds", "ciegas" -> {
                if (args.length < 5) {
                    player.sendMessage(pk("stakes_usage",
                            "&cUso: /gdx poker stakes <nombre> <ciega chica> <ciega grande>"));
                    return true;
                }
                String err = pm.setStakes(args[2], parseUnits(args[3]), parseUnits(args[4]));
                player.sendMessage(err == null ? pk("stakes_set", "&aCiegas actualizadas.") : pokerError(err));
            }
            case "rake" -> player.sendMessage(pk("rake_total",
                    "&7Comisión total cobrada por las mesas de póker: &e{amount}",
                    "amount", String.valueOf(pm.getRakeTotal())));
            case "seat", "seats" -> {
                if (args.length < 4) {
                    player.sendMessage(pk("seat_usage", "&cUso: /gdx poker seat <add|remove|list|clear> <nombre>"));
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
                            "&cUso: /gdx poker seat <add|remove|list|clear> <nombre>"));
                }
            }
            default -> player.sendMessage(pk("invalid_action",
                    "&cAcción inválida. Usa: create, remove, list, seat, stakes, rake, menu"));
        }
        return true;
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
}
