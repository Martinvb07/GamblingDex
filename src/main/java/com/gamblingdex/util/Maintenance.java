package com.gamblingdex.util;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.modules.GameModule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Modo mantenimiento por juego: /gdx disable &lt;juego&gt; cierra el juego al
 * momento (sin reiniciar), devuelve las apuestas en curso y nadie puede jugar
 * hasta /gdx enable &lt;juego&gt;. Se guarda en maintenance.yml (sobrevive a reinicios).
 */
public class Maintenance {

    /** Juegos base (no son módulos): id → nombres aceptados. */
    private static final Map<String, List<String>> CORE = new LinkedHashMap<>();
    static {
        CORE.put("blackjack", List.of("blackjack", "bj"));
        CORE.put("poker", List.of("poker", "pk"));
        CORE.put("ruleta", List.of("ruleta", "roulette"));
        CORE.put("slots", List.of("slots", "tragamonedas"));
    }

    private final GamblingDexPlugin plugin;
    private final File file;
    private final Set<String> closed = new LinkedHashSet<>();
    /** Mesas cerradas una por una: "juego:mesa" (en minúsculas). */
    private final Set<String> closedTables = new LinkedHashSet<>();
    private final Map<String, Long> lastNotice = new HashMap<>();

    public Maintenance(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "maintenance.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        closed.addAll(y.getStringList("closed"));
        closedTables.addAll(y.getStringList("closed_tables"));
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("closed", new ArrayList<>(closed));
        y.set("closed_tables", new ArrayList<>(closedTables));
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar maintenance.yml: " + e.getMessage());
        }
    }

    /** Id del juego (core o módulo) a partir de lo que escribió el admin, o null. */
    public String resolve(String name) {
        if (name == null)
            return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : CORE.entrySet())
            if (e.getValue().contains(n))
                return e.getKey();
        var mm = plugin.getModuleManager();
        GameModule m = mm == null ? null : mm.find(n);
        return m == null ? null : m.id();
    }

    /** Nombres para el autocompletar. */
    public List<String> gameNames() {
        List<String> out = new ArrayList<>(List.of("blackjack", "poker", "roulette", "slots"));
        var mm = plugin.getModuleManager();
        if (mm != null)
            for (GameModule m : mm.getModules())
                out.add(m.id());
        return out;
    }

    public boolean isClosed(String game) {
        return game != null && closed.contains(game);
    }

    public Set<String> closedGames() {
        return Collections.unmodifiableSet(closed);
    }

    private static String tableKey(String game, String table) {
        return (game + ":" + table).toLowerCase(Locale.ROOT);
    }

    /** ¿Esa mesa concreta está cerrada? (blackjack/poker/baccarat: nombre; ruleta: bloque centro) */
    public boolean isTableClosed(String game, String table) {
        return table != null && closedTables.contains(tableKey(game, table));
    }

    /** Cierra una sola mesa (la devolución de apuestas la hace quien llama). false si ya estaba cerrada. */
    public boolean closeTable(String game, String table) {
        if (!closedTables.add(tableKey(game, table)))
            return false;
        save();
        return true;
    }

    public boolean openTable(String game, String table) {
        if (!closedTables.remove(tableKey(game, table)))
            return false;
        save();
        return true;
    }

    public Set<String> closedTables() {
        return Collections.unmodifiableSet(closedTables);
    }

    /** Como {@link #allow}, y además que esa mesa no esté cerrada. */
    public boolean allowTable(Player p, String game, String table) {
        if (!allow(p, game))
            return false;
        if (!isTableClosed(game, table))
            return true;
        if (p != null) {
            String k = p.getUniqueId() + ":table:" + table;
            long now = System.currentTimeMillis();
            Long last = lastNotice.get(k);
            if (last == null || now - last > 5000L) {
                lastNotice.put(k, now);
                p.sendMessage(plugin.color(plugin.getMessages().getString("gdx.maintenance.table_closed",
                        "&c&l⚠ &cEsta mesa está cerrada por mantenimiento. Prueba en otra.")));
            }
        }
        return false;
    }

    /** Cierra el juego y devuelve las apuestas en curso. false si ya estaba cerrado. */
    public boolean close(String game) {
        if (!closed.add(game))
            return false;
        save();
        refund(game);
        return true;
    }

    public boolean open(String game) {
        if (!closed.remove(game))
            return false;
        save();
        return true;
    }

    /** Cierre del casino por horario: devuelve las apuestas en curso de todos los juegos. */
    public void refundAll() {
        for (String g : gameNames()) {
            String id = resolve(g);
            if (id != null)
                refund(id);
        }
    }

    private void refund(String game) {
        try {
            switch (game) {
                case "blackjack" -> {
                    if (plugin.getBlackjackManager() != null)
                        plugin.getBlackjackManager().abortAllRounds();
                }
                case "ruleta" -> {
                    if (plugin.getWorldRouletteManager() != null)
                        plugin.getWorldRouletteManager().abortAllRounds();
                }
                case "poker" -> {
                    if (plugin.getPokerManager() != null)
                        for (var t : plugin.getPokerManager().getTables()) {
                            t.shutdown(); // devuelve fichas (y la inscripción de un torneo)
                            t.updateDisplays();
                        }
                }
                case "slots" -> {
                    // cada tirada se resuelve al instante: no hay nada que devolver
                }
                default -> {
                    var mm = plugin.getModuleManager();
                    if (mm != null)
                        mm.restart(game); // disable() del módulo devuelve las apuestas
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Mantenimiento] Error cerrando " + game + ": " + t);
        }
    }

    /**
     * true si el jugador puede jugar a {@code game}. Si está cerrado avisa (como
     * mucho cada 5 s, porque algunos juegos lo comprueban cada tick).
     */
    public boolean allow(Player p, String game) {
        var schedule = plugin.getSchedule();
        if (schedule != null && !schedule.isOpenNow()
                && !(p != null && schedule.adminBypass() && p.hasPermission("gamblingdex.admin"))) {
            if (p != null) {
                String k = p.getUniqueId() + ":schedule";
                long now = System.currentTimeMillis();
                Long last = lastNotice.get(k);
                if (last == null || now - last > 5000L) {
                    lastNotice.put(k, now);
                    p.sendMessage(schedule.closedMessage());
                }
            }
            return false;
        }
        if (!isClosed(game))
            return true;
        if (p != null) {
            String k = p.getUniqueId() + ":" + game;
            long now = System.currentTimeMillis();
            Long last = lastNotice.get(k);
            if (last == null || now - last > 5000L) {
                lastNotice.put(k, now);
                p.sendMessage(plugin.color(plugin.getMessages().getString("gdx.maintenance.closed",
                        "&c&l⚠ &c{game} está en mantenimiento. Vuelve más tarde.")
                        .replace("{game}", com.gamblingdex.stats.GameStats.gameName(game))));
            }
        }
        return false;
    }
}
