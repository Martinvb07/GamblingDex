package com.gamblingdex.config;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.*;

/**
 * /gdx config check: revisa los valores de los archivos (no solo que el YAML esté
 * bien escrito): materiales y sonidos que no existen, símbolo del jackpot que no
 * está en symbol_weights, pesos en 0, apuesta mínima mayor que la máxima, horario
 * mal escrito, etc.
 */
public final class ConfigCheck {

    private final GamblingDexPlugin plugin;
    private final List<String> problems = new ArrayList<>();
    private String file;

    public ConfigCheck(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    /** Revisa todo y devuelve los problemas ("archivo: qué pasa"). Vacía si todo está bien. */
    public List<String> run() {
        problems.clear();
        for (String err : plugin.validateYamlFiles())
            problems.add(err + " &7(error de escritura: el archivo se ignora)");

        File data = plugin.getDataFolder();
        List<String> names = new ArrayList<>(List.of("config.yml", "achievements.yml", "schedule.yml"));
        File[] mods = new File(data, "modules").listFiles((d, n) -> n.endsWith(".yml") && !n.endsWith("_data.yml"));
        if (mods != null)
            for (File f : mods)
                names.add("modules/" + f.getName());

        for (String name : names) {
            File f = new File(data, name);
            if (!f.exists())
                continue;
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.load(f);
            } catch (Exception e) {
                continue; // ya salió como error de escritura
            }
            file = name;
            walk(y, "");
            specific(name, y);
        }
        return new ArrayList<>(problems);
    }

    private void add(String path, String what) {
        problems.add(file + " &8→ &f" + path + "&7: " + what);
    }

    // ------------------------------------------------------------------
    // Revisión general por nombre de opción
    // ------------------------------------------------------------------

    private void walk(ConfigurationSection sec, String prefix) {
        for (String k : sec.getKeys(false)) {
            String path = prefix.isEmpty() ? k : prefix + "." + k;
            Object v = sec.get(k);
            if (v instanceof ConfigurationSection cs) {
                if (k.equals("symbol_weights"))
                    checkWeights(cs, path);
                else if (k.endsWith("materials"))
                    for (String c : cs.getKeys(false))
                        checkMaterial(path + "." + c, cs.getString(c), true);
                else if (k.equals("sounds"))
                    for (String c : cs.getKeys(false))
                        if (cs.isString(c))
                            checkSound(path + "." + c, cs.getString(c));
                walk(cs, path);
            } else if (v instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object o = list.get(i);
                    if (o instanceof String s && k.endsWith("materials"))
                        checkMaterial(path + "[" + (i + 1) + "]", s, true);
                    else if (o instanceof Map<?, ?> m)
                        for (Map.Entry<?, ?> e : m.entrySet())
                            if (e.getValue() instanceof String s)
                                leaf(path + "[" + (i + 1) + "]." + e.getKey(), String.valueOf(e.getKey()), s);
                }
            } else if (v instanceof String s) {
                leaf(path, k, s);
            }
        }
    }

    private void leaf(String path, String k, String s) {
        if (k.equals("icon") || k.equals("symbol") || k.equals("material"))
            checkMaterial(path, s, false);
        else if (k.endsWith("_material"))
            checkMaterial(path, s, true);
        else if (k.equals("sound"))
            checkSound(path, s);
        else if (k.equals("particle"))
            checkParticle(path, s);
    }

    private void checkMaterial(String path, String s, boolean block) {
        if (s == null || s.isBlank())
            return;
        Material m = Material.matchMaterial(s.trim());
        if (m == null)
            add(path, "el material &c" + s + "&7 no existe");
        else if (block && !m.isBlock())
            add(path, "&c" + s + "&7 no es un bloque");
        else if (!block && !m.isItem())
            add(path, "&c" + s + "&7 no se puede mostrar como ítem");
    }

    private void checkSound(String path, String s) {
        if (s == null || s.isBlank())
            return; // vacío = sin sonido
        NamespacedKey key = NamespacedKey.fromString(s.trim().toLowerCase(Locale.ROOT));
        if (key == null || (key.getNamespace().equals("minecraft") && Registry.SOUNDS.get(key) == null))
            add(path, "el sonido &c" + s + "&7 no existe (ej. block.bell.use)");
    }

    private void checkParticle(String path, String s) {
        try {
            Particle.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            add(path, "la partícula &c" + s + "&7 no existe (ej. END_ROD)");
        }
    }

    private void checkWeights(ConfigurationSection cs, String path) {
        double sum = 0;
        for (String mat : cs.getKeys(false)) {
            checkMaterial(path + "." + mat, mat, false);
            double w = cs.getDouble(mat);
            if (w <= 0)
                add(path + "." + mat, "peso &c" + cs.get(mat) + "&7: tiene que ser mayor que 0 (si no, nunca sale)");
            sum += Math.max(0, w);
        }
        if (!cs.getKeys(false).isEmpty() && sum <= 0)
            add(path, "todos los pesos son 0");
    }

    // ------------------------------------------------------------------
    // Revisiones de cada archivo
    // ------------------------------------------------------------------

    private void specific(String name, YamlConfiguration y) {
        // Apuestas mínima y máxima (cualquier juego)
        if (y.isSet("min_bet") && y.getLong("max_bet", 0) > 0 && y.getLong("min_bet") > y.getLong("max_bet"))
            add("min_bet", "la apuesta mínima (&c" + y.get("min_bet") + "&7) es mayor que la máxima (&c" + y.get("max_bet") + "&7)");
        if (y.isSet("min_bet") && y.getLong("min_bet") < 0)
            add("min_bet", "no puede ser negativa");
        if (y.isSet("house_edge_percent") && (y.getDouble("house_edge_percent") < 0 || y.getDouble("house_edge_percent") > 50))
            add("house_edge_percent", "tiene que estar entre 0 y 50");

        switch (name) {
            case "modules/slots.yml" -> {
                String sym = y.getString("jackpot.symbol", "NETHER_STAR");
                ConfigurationSection w = y.getConfigurationSection("symbol_weights");
                if (y.getBoolean("jackpot.enabled", true) && w != null && !w.getKeys(false).isEmpty()) {
                    boolean found = false;
                    for (String k : w.getKeys(false))
                        if (k.equalsIgnoreCase(sym) && w.getDouble(k) > 0)
                            found = true;
                    if (!found)
                        add("jackpot.symbol", "el símbolo &c" + sym + "&7 no está en symbol_weights (o tiene peso 0): el jackpot nunca saldría");
                }
                ConfigurationSection themes = y.getConfigurationSection("themes");
                if (themes != null && y.getBoolean("jackpot.enabled", true))
                    for (String t : themes.getKeys(false)) {
                        String tsym = themes.getString(t + ".jackpot.symbol", sym);
                        ConfigurationSection tw = themes.getConfigurationSection(t + ".symbol_weights");
                        if (tw == null)
                            tw = w;
                        if (tw == null || tw.getKeys(false).isEmpty())
                            continue;
                        boolean ok = false;
                        for (String k : tw.getKeys(false))
                            if (k.equalsIgnoreCase(tsym) && tw.getDouble(k) > 0)
                                ok = true;
                        if (!ok)
                            add("themes." + t + ".jackpot.symbol", "el símbolo &c" + tsym
                                    + "&7 no está en los symbol_weights del tema: en esa máquina el jackpot nunca saldría");
                    }
                double pct = y.getDouble("jackpot.contribution_percent", 2.0);
                if (pct < 0 || pct > 50)
                    add("jackpot.contribution_percent", "tiene que estar entre 0 y 50");
            }
            case "modules/tower.yml" -> {
                ConfigurationSection d = y.getConfigurationSection("difficulties");
                if (d != null)
                    for (String k : d.getKeys(false)) {
                        int doors = d.getInt(k + ".doors", 3), traps = d.getInt(k + ".traps", 1);
                        if (doors < 2 || doors > 4)
                            add("difficulties." + k + ".doors", "tiene que ser de 2 a 4");
                        if (traps < 1 || traps >= doors)
                            add("difficulties." + k + ".traps", "tiene que ser al menos 1 y menos que las puertas");
                    }
                int floors = y.getInt("floors", 8);
                if (floors < 3 || floors > 12)
                    add("floors", "tiene que ser de 3 a 12");
            }
            case "modules/rasca.yml" -> {
                int sum = 0;
                for (Map<?, ?> m : y.getMapList("prizes"))
                    if (m.get("weight") instanceof Number n)
                        sum += n.intValue();
                if (sum > 1000)
                    add("prizes", "la suma de los pesos (&c" + sum + "&7) pasa de 1000");
            }
            case "modules/blackjack.yml" -> {
                long min = y.getLong("min_bet", 0), max = y.getLong("max_bet", 0);
                if (max > 0 && min > max)
                    add("min_bet", "la mínima es mayor que la máxima");
                int deal = y.getInt("deal_interval_ticks", 8);
                if (deal < 0 || deal > 100)
                    add("deal_interval_ticks", "tiene que estar entre 0 y 100");
            }
            case "schedule.yml" -> {
                for (String k : List.of("open", "close"))
                    if (y.isSet(k) && com.gamblingdex.util.CasinoSchedule.parse(y.getString(k)) == null)
                        add(k, "&c" + y.getString(k) + "&7 no es una hora válida (ej. \"6:00 PM\")");
                try {
                    ZoneId.of(y.getString("timezone", "America/Bogota"));
                } catch (Exception e) {
                    add("timezone", "la zona &c" + y.getString("timezone") + "&7 no existe (ej. America/Bogota)");
                }
                for (String d : y.getStringList("days"))
                    try {
                        DayOfWeek.valueOf(d.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        add("days", "&c" + d + "&7 no es un día (MONDAY, TUESDAY...)");
                    }
                if (y.getBoolean("enabled", false) && y.isList("days") && y.getStringList("days").isEmpty())
                    add("days", "no hay ningún día: el casino estaría siempre cerrado");
            }
            case "achievements.yml" -> {
                Set<String> types = Set.of("rounds", "win", "wager", "multiplier", "game_win", "games", "event");
                ConfigurationSection a = y.getConfigurationSection("achievements");
                if (a != null)
                    for (String k : a.getKeys(false)) {
                        String t = a.getString(k + ".type", "");
                        if (!types.contains(t))
                            add("achievements." + k + ".type", "el tipo &c" + t + "&7 no existe");
                    }
            }
            default -> {
            }
        }
    }
}
