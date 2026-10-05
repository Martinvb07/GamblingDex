package com.gamblingdex.commands;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.modules.GameModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /gdx help ordenado por categorías (con enlaces clickeables):
 * <pre>
 * /gdx help          → categorías
 * /gdx help player   → comandos del jugador
 * /gdx help games    → lista de juegos
 * /gdx help admin    → montaje de mesas y administración
 * /gdx help &lt;juego&gt;  → cómo se juega / cómo se monta ese juego
 * </pre>
 */
public final class HelpMenu {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private HelpMenu() {
    }

    private static GamblingDexPlugin plugin() {
        return GamblingDexPlugin.getInstance();
    }

    private static Component text(String s) {
        return LEGACY.deserialize(plugin().color(s));
    }

    /** Texto clickeable que ejecuta {@code command} al hacer click. */
    private static Component link(String label, String hover, String command) {
        return text(label)
                .hoverEvent(HoverEvent.showText(text(hover)))
                .clickEvent(ClickEvent.runCommand(command));
    }

    /** Texto clickeable que escribe {@code command} en el chat (para completar). */
    private static Component suggest(String label, String hover, String command) {
        return text(label)
                .hoverEvent(HoverEvent.showText(text(hover)))
                .clickEvent(ClickEvent.suggestCommand(command));
    }

    private static void header(Player p, String section) {
        p.sendMessage(text(" "));
        p.sendMessage(text("&6&l✦ GamblingDex &7v" + plugin().getDescription().getVersion()
                + (section == null ? "" : " &8» &e" + section)));
    }

    private static void back(Player p) {
        p.sendMessage(link("&8« &7Volver a las categorías", "&7Click para volver", "/gdx help"));
    }

    // ------------------------------------------------------------------

    public static void send(Player p, String[] args) {
        String topic = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean admin = p.hasPermission("gamblingdex.admin");
        if (!admin) {
            // Los jugadores solo ven su parte (los juegos se juegan con click, sin comandos)
            playerHome(p);
            return;
        }
        switch (topic) {
            case "" -> categories(p, admin);
            case "player", "jugador" -> player(p);
            case "games", "juegos" -> games(p, admin);
            case "admin" -> {
                if (admin)
                    admin(p);
                else
                    categories(p, false);
            }
            default -> game(p, topic, admin);
        }
    }

    private static void categories(Player p, boolean admin) {
        header(p, null);
        // Resumen del plugin
        int games = coreGames().size() - 1; // sin contar el cambio
        var mm = plugin().getModuleManager();
        if (mm != null)
            for (GameModule m : mm.getModules())
                if (mm.byCommand(m.id()) != null)
                    games++;
        p.sendMessage(text("&7Casino completo con &f" + games + " juegos&7: mesas con dealer, ruleta, slots,"));
        p.sendMessage(text("&7crash, carreras, rueda, plinko, mines, tower y más. Todo se juega con &ffichas&7."));
        p.sendMessage(text("&7Tus fichas: &e" + com.gamblingdex.economy.TokenWallet.balance(p)
                + " &8| &7Compra/vende fichas en la &fmesa de cambio&7."));
        p.sendMessage(text(" "));
        p.sendMessage(text("&7¿Con qué necesitas ayuda? &8(click)&7:"));
        p.sendMessage(link("  &e▸ &fJugador", "&7Saldo, estadísticas y ranking\n&8/gdx help player", "/gdx help player")
                .append(text(" &8- &7saldo, estadísticas, ranking")));
        p.sendMessage(link("  &e▸ &fJuegos", "&7Cómo se juega cada juego\n&8/gdx help games", "/gdx help games")
                .append(text(" &8- &7cómo jugar cada juego")));
        if (admin)
            p.sendMessage(link("  &c▸ &fAdmin", "&7Montar mesas y administrar\n&8/gdx help admin", "/gdx help admin")
                    .append(text(" &8- &7montar mesas, recargar, fichas")));
        p.sendMessage(text("&8Tip: usa &7TAB &8para autocompletar los comandos."));
    }

    /** Lo que ve un jugador normal: resumen y sus comandos. */
    private static void playerHome(Player p) {
        header(p, null);
        p.sendMessage(text("&7Casino con mesas con dealer, ruleta, slots, crash, carreras,"));
        p.sendMessage(text("&7rueda, plinko, mines, tower y más. Todo se juega con &ffichas&7."));
        p.sendMessage(text("&7Tus fichas: &e" + com.gamblingdex.economy.TokenWallet.balance(p)
                + " &8| &7Compra/vende fichas en la &fmesa de cambio&7."));
        p.sendMessage(text("&7Las mesas se juegan con &fclick derecho&7 o parándote en un asiento."));
        p.sendMessage(text(" "));
        playerLines(p);
    }

    private static void playerLines(Player p) {
        line(p, "/gdx balance", "Tus fichas");
        line(p, "/gdx stats", "Tus estadísticas");
        if (p.hasPermission("gamblingdex.stats.others"))
            line(p, "/gdx stats <player>", "Estadísticas de otro jugador");
        line(p, "/gdx top", "Ranking de ganancias (todos los juegos)");
        line(p, "/gdx top week", "Ranking de esta semana");
        line(p, "/gdx history", "Tus últimas 10 apuestas");
        line(p, "/gdx achievements", "Tus logros del casino");
    }

    private static void player(Player p) {
        header(p, "Jugador");
        line(p, "/gdx balance", "Tus fichas");
        line(p, "/gdx stats", "Tus estadísticas");
        if (p.hasPermission("gamblingdex.stats.others"))
            line(p, "/gdx stats <player>", "Estadísticas de otro jugador");
        line(p, "/gdx top", "Ranking de ganancias (todos los juegos)");
        line(p, "/gdx top week", "Ranking de esta semana");
        line(p, "/gdx history", "Tus últimas 10 apuestas");
        line(p, "/gdx achievements", "Tus logros del casino");
        p.sendMessage(text("&7Las mesas se juegan con &fclick derecho&7 o parándote en un asiento."));
        back(p);
    }

    private static void line(Player p, String cmd, String desc) {
        String base = cmd.contains("<") ? cmd.substring(0, cmd.indexOf('<')) : cmd;
        boolean complete = !cmd.contains("<") && !cmd.contains("[");
        Component c = complete
                ? link("  &e" + cmd, "&7Click para ejecutar", cmd)
                : suggest("  &e" + cmd, "&7Click para escribirlo", base);
        p.sendMessage(c.append(text(" &8- &7" + desc)));
    }

    /** Juegos de mesa del núcleo (no son módulos). */
    private static Map<String, String> coreGames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("blackjack", "&6Blackjack");
        m.put("poker", "&6Póker");
        m.put("roulette", "&6Ruleta");
        m.put("slots", "&6Tragamonedas");
        m.put("exchange", "&6Cambio de fichas");
        return m;
    }

    private static void games(Player p, boolean admin) {
        header(p, "Juegos");
        p.sendMessage(text("&7Click en un juego para ver cómo se juega:"));
        List<Component> row = new ArrayList<>();
        for (Map.Entry<String, String> e : coreGames().entrySet())
            row.add(link(e.getValue(), "&7/gdx help " + e.getKey(), "/gdx help " + e.getKey()));
        var mm = plugin().getModuleManager();
        if (mm != null)
            for (GameModule m : mm.getModules())
                if (mm.byCommand(m.id()) != null)
                    row.add(link("&6" + m.displayName(), "&7/gdx help " + m.id(), "/gdx help " + m.id()));
        Component out = text("  ");
        for (int i = 0; i < row.size(); i++) {
            out = out.append(row.get(i));
            if (i < row.size() - 1)
                out = out.append(text(" &8| "));
            if ((i + 1) % 5 == 0 && i < row.size() - 1) {
                p.sendMessage(out);
                out = text("  ");
            }
        }
        p.sendMessage(out);
        back(p);
    }

    private static void admin(Player p) {
        header(p, "Admin");
        line(p, "/gdx reload", "Recargar configuración y mensajes");
        line(p, "/gdx config check", "Revisar la config: materiales, sonidos, pesos, horario...");
        line(p, "/gdx station set <type>", "Crear una mesa/estación (mirando el bloque)");
        line(p, "/gdx station remove", "Quitar la mesa que miras (restaura los bloques)");
        line(p, "/gdx station list", "Mesas registradas");
        line(p, "/gdx disable <game>", "Cerrar un juego por mantenimiento (devuelve apuestas)");
        line(p, "/gdx enable <game>", "Volver a abrir un juego");
        line(p, "/gdx maintenance", "Juegos en mantenimiento");
        line(p, "/gdx schedule", "Horario del casino: abre y cierra solo (menú)");
        line(p, "/gdx inspect <player>", "Ver fichas, bono, ganancias y últimas apuestas de un jugador");
        line(p, "/gdx token <color|value> <amount>", "Crear fichas (ej. para Shopkeepers)");
        line(p, "/gdx item <roulette|slots> [amount]", "Ítems que abren menús");
        p.sendMessage(text("&7Montaje de cada juego &8(click)&7:"));
        games(p, true);
    }

    private static void game(Player p, String topic, boolean admin) {
        List<String> lines = switch (topic) {
            case "blackjack", "bj" -> blackjack(admin);
            case "poker", "pk" -> poker(admin);
            case "roulette", "ruleta" -> roulette(admin);
            case "slots" -> slots(admin);
            case "exchange", "cambio" -> exchange(admin);
            default -> {
                var mm = plugin().getModuleManager();
                GameModule m = mm == null ? null : mm.byCommand(topic);
                yield m == null ? null : m.helpLines(admin);
            }
        };
        if (lines == null) {
            p.sendMessage(text("&cNo hay ayuda para &f" + topic + "&c."));
            games(p, admin);
            return;
        }
        header(p, null);
        for (String l : lines) {
            if (l.isBlank())
                continue;
            // Si la línea trae un comando, hacerlo clickeable (se escribe en el chat para completarlo)
            int i = l.indexOf("/gdx ");
            if (i >= 0) {
                String cmd = l.substring(i).replaceAll("&[0-9a-fk-orA-FK-OR]", "");
                int end = cmd.indexOf(" - ");
                if (end > 0)
                    cmd = cmd.substring(0, end);
                int arg = cmd.indexOf(" <");
                int opt = cmd.indexOf(" [");
                int pipe = cmd.indexOf('|');
                int cut = cmd.length();
                for (int c : new int[] { arg, opt, pipe })
                    if (c > 0 && c < cut)
                        cut = c;
                String base = cmd.substring(0, cut).trim();
                if (pipe > 0 && pipe == cut) // ej. "/gdx race remove <name>|list": quedarse con el comando
                    base = base.substring(0, Math.max(base.lastIndexOf(' '), 4)).trim();
                p.sendMessage(text(l).hoverEvent(HoverEvent.showText(text("&7Click para escribir: &f" + base)))
                        .clickEvent(ClickEvent.suggestCommand(base + " ")));
            } else {
                p.sendMessage(text(l));
            }
        }
        p.sendMessage(link("&8« &7Volver a los juegos", "&7Click para volver", "/gdx help games"));
    }

    private static List<String> blackjack(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lBlackjack",
                "&8• &7Jugar: &fpárate en un asiento&7; el menú de apuestas se abre solo",
                "&8• &7Apuestas laterales: &fPares Perfectos&7 y &f21+3&7; botón &fRepetir apuesta"));
        if (admin) {
            l.add("&8• &e/gdx blackjack create <name> [min] [max] &7- Crear mesa (mirando el bloque)");
            l.add("&8• &e/gdx blackjack limits <name> <min> [max] &7- Apuesta mínima/máxima de la mesa");
            l.add("&8• &e/gdx blackjack seat <add|remove|list|clear> <name> &7- Asientos (parado encima)");
            l.add("&8• &e/gdx blackjack remove <name>&7|&elist &7- Gestionar mesas");
            l.add("&8• &e/gdx blackjack face <name> &7- El dealer mira hacia donde estás");
            l.add("&8• &e/gdx blackjack rename <table> <name...> &7- Nombre de la mesa (con colores)");
            l.add("&8• &7Título de la mesa: &fmodules/blackjack.yml &7→ &ftable_names");
        }
        return l;
    }

    private static List<String> poker(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lPóker (Texas Hold'em)",
                "&8• &7Jugar: &fpárate en un asiento&7 y compra fichas; al pararte se te devuelven",
                "&8• &7Tu menú: &fclick derecho&7 al centro de la mesa (también para inscribirte a torneos)"));
        if (admin) {
            l.add("&8• &e/gdx poker create <name> [small] [big] &7- Crear mesa (mirando el centro)");
            l.add("&8• &e/gdx poker seat <add|remove|list|clear> <name> &7- Asientos (en orden horario)");
            l.add("&8• &e/gdx poker stakes <name> <small> <big> &7- Cambiar ciegas");
            l.add("&8• &e/gdx poker rename <table> <name...> &7- Nombre de la mesa (con colores)");
            l.add("&8• &e/gdx poker tournament <table> <fee> [chips] [minutes] &7- Abrir torneo");
            l.add("&8• &e/gdx poker tournament <start|cancel> <table>");
            l.add("&8• &e/gdx poker remove <name>&7|&elist&7|&erake");
            l.add("&8• &7Título de la mesa: &fmodules/poker.yml &7→ &ftable_names");
        }
        return l;
    }

    private static List<String> roulette(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lRuleta americana",
                "&8• &7Jugar: &fclick derecho&7 al centro → eliges apuesta y monto",
                "&8• &7También: fichas en la mano + click derecho &8(shift = todo el stack)",
                "&8• &7Botón &fRepetir apuesta&7 · &6Jackpot&7: si sale su número, los plenos se lo reparten"));
        if (admin) {
            l.add("&8• &e/gdx roulette build [radius] [yOffset] &7- Construir (mirando el centro)");
            l.add("&8• &e/gdx roulette remove&7|&elist");
        }
        return l;
    }

    private static List<String> slots(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lTragamonedas",
                "&8• &7Jugar: &fclick derecho&7 a la estación de slots"));
        if (admin)
            l.add("&8• &e/gdx station set slots &7- Registrar estación (mirando el bloque)");
        return l;
    }

    private static List<String> exchange(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lCambio de fichas",
                "&8• &7Click derecho a la estación de cambio: comprar o vender fichas con dinero"));
        if (admin)
            l.add("&8• &e/gdx station set exchange &7- Registrar estación (mirando el bloque)");
        return l;
    }
}
