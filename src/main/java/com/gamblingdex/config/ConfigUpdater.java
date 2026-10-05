package com.gamblingdex.config;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Al actualizar el plugin, añade a los archivos del servidor las opciones nuevas
 * del jar (con sus comentarios) sin tocar los valores que ya cambió el admin.
 *
 * <p>Para no revivir lo que el admin borró (una mesa, un logro, un símbolo...),
 * se recuerda en {@code config_keys.yml} qué opciones ya se ofrecieron en cada
 * archivo: solo se añaden las que aparecen por primera vez en el jar.</p>
 *
 * <p>La primera vez (sin registro) no se sabe qué borró el admin, así que solo se
 * añaden opciones sueltas o secciones enteras que falten, pero nunca entradas
 * nuevas dentro de listas del admin ({@link #USER_MAPS}), salvo las de
 * {@link #FIRST_RUN_NEW}.</p>
 */
public final class ConfigUpdater {

    /** Secciones cuyas claves son entradas del admin (mesas, logros, símbolos...). */
    private static final Set<String> USER_MAPS = Set.of("table_names", "symbol_weights", "symbol_names", "themes", "payouts", "multipliers",
            "tiers", "rewards", "difficulties", "achievements");

    /** Entradas nuevas de la 1.2.0 que se añaden aunque no haya registro todavía. */
    private static final Map<String, List<String>> FIRST_RUN_NEW = Map.of(
            "achievements.yml", List.of("achievements.slots_jackpot", "achievements.tower_top"));

    private static File registryFile;
    private static YamlConfiguration registry;

    private ConfigUpdater() {
    }

    /**
     * Añade a {@code server} lo nuevo de {@code jar} y lo guarda en {@code file}.
     *
     * @param name nombre del archivo relativo a la carpeta del plugin (p. ej. "modules/blackjack.yml")
     * @return cuántas opciones se añadieron
     */
    public static int update(GamblingDexPlugin plugin, String name, File file, FileConfiguration server, Configuration jar) {
        if (server == null || jar == null || file == null || !file.exists())
            return 0;
        YamlConfiguration reg = registry(plugin);
        String regKey = "files." + name.replace('.', '_').replace('/', '_');
        boolean firstRun = !reg.isList(regKey);
        Set<String> offered = new HashSet<>(reg.getStringList(regKey));

        List<String> added = new ArrayList<>();
        Set<String> addedSections = new HashSet<>();
        for (String path : jar.getKeys(true)) {
            if (server.isSet(path))
                continue;
            String parent = parentOf(path);
            if (firstRun) {
                if (!allowedFirstRun(name, path, parent, server, addedSections))
                    continue;
            } else {
                if (offered.contains(path))
                    continue;
                // Si el padre se borró, solo crearlo si también es nuevo
                if (parent != null && !ownSection(server, parent) && offered.contains(parent))
                    continue;
            }
            if (jar.isConfigurationSection(path)) {
                server.createSection(path);
                addedSections.add(path);
            } else {
                server.set(path, jar.get(path));
            }
            copyComments(server, jar, path);
            added.add(path);
        }

        reg.set(regKey, new ArrayList<>(jar.getKeys(true)));
        saveRegistry(plugin);

        if (added.isEmpty())
            return 0;
        try {
            server.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo actualizar " + name + ": " + e.getMessage());
            return 0;
        }
        long leaves = added.stream().filter(p -> !jar.isConfigurationSection(p)).count();
        plugin.getLogger().info("[Config] " + name + ": " + leaves + " opciones nuevas añadidas ("
                + String.join(", ", topLevel(added)) + ").");
        return (int) leaves;
    }

    private static boolean allowedFirstRun(String name, String path, String parent, FileConfiguration server,
            Set<String> addedSections) {
        for (String forced : FIRST_RUN_NEW.getOrDefault(name, List.of()))
            if (path.equals(forced) || path.startsWith(forced + "."))
                return true;
        if (parent == null)
            return true; // opción o sección de primer nivel que falta
        if (addedSections.contains(parent))
            return true; // dentro de una sección que se acaba de añadir entera
        if (!ownSection(server, parent))
            return false;
        // Nunca entradas nuevas dentro de una lista del admin
        for (String seg : parent.split("\\."))
            if (USER_MAPS.contains(seg))
                return false;
        return true;
    }

    private static void copyComments(FileConfiguration server, Configuration jar, String path) {
        if (!(jar instanceof FileConfiguration fc))
            return;
        try {
            List<String> c = fc.getComments(path);
            if (!c.isEmpty())
                server.setComments(path, c);
            List<String> ic = fc.getInlineComments(path);
            if (!ic.isEmpty())
                server.setInlineComments(path, ic);
        } catch (Throwable ignored) {
            // API sin comentarios: no pasa nada
        }
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? null : path.substring(0, i);
    }

    private static List<String> topLevel(List<String> paths) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : paths) {
            int i = p.indexOf('.');
            out.add(i < 0 ? p : p.substring(0, i));
        }
        return new ArrayList<>(out);
    }

    private static YamlConfiguration registry(GamblingDexPlugin plugin) {
        if (registry == null) {
            registryFile = new File(plugin.getDataFolder(), "config_keys.yml");
            registry = YamlConfiguration.loadConfiguration(registryFile);
            registry.options().setHeader(List.of(
                    "Uso interno de GamblingDex: opciones de cada archivo que ya se añadieron al actualizar.",
                    "No lo edites. Si lo borras, el plugin solo añadirá opciones sueltas que falten."));
        }
        return registry;
    }

    private static void saveRegistry(GamblingDexPlugin plugin) {
        try {
            registry(plugin).save(registryFile);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar config_keys.yml: " + e.getMessage());
        }
    }

    /** Lee un archivo del jar como YAML (con comentarios), o null si no existe. */
    public static YamlConfiguration jarConfig(GamblingDexPlugin plugin, String resource) {
        var in = plugin.getResource(resource);
        if (in == null)
            return null;
        return YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Sección escrita en el archivo del servidor (sin mirar los valores por defecto del jar). */
    private static boolean ownSection(FileConfiguration server, String path) {
        return server.get(path, null) instanceof ConfigurationSection;
    }
}
