package com.gamblingdex.util;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * El crupier habla: frases al azar según lo que pasa en la mesa (blackjack, propina
 * en el póker...). Se ven en el chat de los que están cerca y en un globo de texto
 * sobre el crupier unos segundos. Todo se configura en
 * {@code <juego>.dealer_talk} (frases, probabilidad, radio, globo).
 */
public final class DealerTalk {

    /** Frases por defecto si el archivo no tiene la sección. */
    private static final Map<String, List<String>> DEFAULTS = Map.ofEntries(
            Map.entry("round_start", List.of("Apuestas cerradas. ¡Repartimos!", "Mucha suerte a todos.", "Vamos con las cartas.")),
            Map.entry("player_blackjack", List.of("¡Blackjack para {player}! Buena mano.", "¡Qué mano, {player}! Blackjack.")),
            Map.entry("dealer_blackjack", List.of("¡Blackjack para la casa!", "Lo siento, la casa tiene blackjack.")),
            Map.entry("player_bust", List.of("Uy, {player} se pasó.", "Te pasaste, {player}. A la próxima.")),
            Map.entry("dealer_bust", List.of("Me pasé... ¡ganan los que siguen en pie!", "La casa se pasa. ¡Felicidades!")),
            Map.entry("player_double", List.of("{player} dobla. ¡Valiente!", "Doble para {player}, una sola carta.")),
            Map.entry("player_split", List.of("{player} divide. Veamos qué sale.", "Dos manos para {player}.")),
            Map.entry("player_win", List.of("Buena mano, {player}.", "Bien jugado, {player}.")),
            Map.entry("house_wins", List.of("La casa gana esta vez.", "Esta ronda es para la casa.")),
            Map.entry("tip", List.of("¡Gracias por la propina, {player}!", "Muy amable, {player}. ¡Suerte en la mesa!")));

    private static final Map<String, Long> lastSay = new HashMap<>();

    private DealerTalk() {
    }

    /**
     * @param base     ruta de la config (ej. "blackjack.dealer_talk")
     * @param key      id de la mesa (para el tiempo entre frases)
     * @param center   dónde está la mesa (los de cerca leen la frase)
     * @param bubbleAt parte de arriba de los hologramas de la mesa: el globo sale bubble_height más arriba,
     *                 así nunca se encima con ellos (null = solo chat)
     * @param event    tipo de frase (round_start, player_blackjack, tip...)
     * @param dealerName nombre que sale en el chat
     * @param force    ignora la probabilidad y el tiempo entre frases (propinas)
     */
    public static void say(GamblingDexPlugin plugin, String base, String key, Location center, Location bubbleAt,
            String event, String dealerName, Map<String, String> vars, boolean force) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean(base + ".enabled", true) || center == null || center.getWorld() == null)
            return;
        List<String> phrases = cfg.isList(base + ".phrases." + event)
                ? cfg.getStringList(base + ".phrases." + event)
                : DEFAULTS.getOrDefault(event, List.of());
        if (phrases.isEmpty())
            return;
        if (!force) {
            int chance = Math.max(0, Math.min(100, cfg.getInt(base + ".chance_percent", 100)));
            if (ThreadLocalRandom.current().nextInt(100) >= chance)
                return;
            long now = System.currentTimeMillis();
            long cd = (long) (cfg.getDouble(base + ".cooldown_seconds", 1.5) * 1000);
            Long last = lastSay.get(key);
            if (last != null && now - last < cd)
                return;
            lastSay.put(key, now);
        }

        String msg = phrases.get(ThreadLocalRandom.current().nextInt(phrases.size()));
        if (vars != null)
            for (Map.Entry<String, String> e : vars.entrySet())
                msg = msg.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());

        String line = cfg.getString(base + ".format", "&6&l{dealer} &8» &f{message}")
                .replace("{dealer}", dealerName != null ? dealerName : cfg.getString(base + ".name", "Crupier"))
                .replace("{message}", msg);
        String colored = plugin.color(line);
        double r = Math.max(2, cfg.getDouble(base + ".radius", 10));
        World w = center.getWorld();
        for (Player p : w.getPlayers())
            if (p.getLocation().distanceSquared(center) <= r * r)
                p.sendMessage(colored);

        if (bubbleAt != null && bubbleAt.getWorld() != null && cfg.getBoolean(base + ".bubble", true))
            bubble(plugin, key, bubbleAt, plugin.color(cfg.getString(base + ".bubble_format", "&f{message}").replace("{message}", msg)),
                    cfg.getDouble(base + ".bubble_height", 0.5), Math.max(20, cfg.getInt(base + ".bubble_ticks", 60)));
    }

    private static final Map<String, UUID> bubbles = new HashMap<>(); // mesa → globo

    private static void bubble(GamblingDexPlugin plugin, String key, Location at, String text, double height, int ticks) {
        UUID old = bubbles.remove(key);
        if (old != null) {
            Entity e = Bukkit.getEntity(old);
            if (e != null)
                e.remove();
        }
        Location l = at.clone().add(0, height, 0);
        TextDisplay td = at.getWorld().spawn(l, TextDisplay.class);
        td.setBillboard(Display.Billboard.CENTER);
        td.setText(text);
        td.setShadowed(true);
        td.setPersistent(false);
        td.setLineWidth(160);
        bubbles.put(key, td.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!td.isDead())
                td.remove();
            bubbles.remove(key, td.getUniqueId());
        }, ticks);
    }

    /** Al apagar: quitar los globos que queden. */
    public static void removeAll() {
        for (UUID id : bubbles.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null)
                e.remove();
        }
        bubbles.clear();
    }
}
