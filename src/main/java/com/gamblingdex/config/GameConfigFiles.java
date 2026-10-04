package com.gamblingdex.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * La config de cada juego de mesa vive en su archivo
 * {@code plugins/GamblingDex/modules/<juego>.yml}; config.yml solo tiene lo
 * general (fichas, cambio, estaciones). Al cargar, cada archivo se copia dentro
 * de {@code getConfig()} en la ruta de siempre, así el resto del código sigue
 * leyendo {@code blackjack.*}, {@code poker.*}, etc. sin cambios.
 *
 * Si un archivo no existe y config.yml todavía tiene la sección vieja, el
 * archivo se crea con esos valores (no se pierde lo que ya estaba configurado).
 */
public final class GameConfigFiles {

    /** Parte de un archivo de juego → ruta en getConfig(). {@code key} vacío = el resto del archivo. */
    private record Part(String file, String key, String path) {
    }

    private static final List<Part> PARTS = List.of(
            new Part("blackjack", "", "blackjack"),
            new Part("poker", "", "poker"),
            new Part("ruleta", "item", "games.items.roulette"),
            new Part("ruleta", "", "roulette_world"),
            new Part("slots", "item", "games.items.slots"),
            new Part("slots", "gui", "gui.slots"),
            new Part("slots", "", "games.slots"));

    private static final List<String> FILES = List.of("blackjack", "poker", "ruleta", "slots");

    private GameConfigFiles() {
    }

    public static void merge(JavaPlugin plugin, FileConfiguration config) {
        File dir = new File(plugin.getDataFolder(), "modules");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta modules/");
            return;
        }
        for (String name : FILES) {
            File f = new File(dir, name + ".yml");
            if (!f.exists())
                create(plugin, config, name, f);
            YamlConfiguration game = YamlConfiguration.loadConfiguration(f);
            for (Part part : PARTS) {
                if (!part.file().equals(name))
                    continue;
                // La sección vieja de config.yml ya no cuenta: manda el archivo del juego.
                config.set(part.path(), null);
                ConfigurationSection src = part.key().isEmpty() ? game : game.getConfigurationSection(part.key());
                if (src == null)
                    continue;
                for (String k : src.getKeys(true)) {
                    if (part.key().isEmpty() && isOtherPart(name, k))
                        continue;
                    if (!src.isConfigurationSection(k))
                        config.set(part.path() + "." + k, src.get(k));
                }
            }
        }
    }

    /** ¿La clave pertenece a otra parte del mismo archivo (ej. "item" en ruleta.yml)? */
    private static boolean isOtherPart(String file, String key) {
        for (Part p : PARTS) {
            if (p.file().equals(file) && !p.key().isEmpty()
                    && (key.equals(p.key()) || key.startsWith(p.key() + ".")))
                return true;
        }
        return false;
    }

    /** Crea modules/&lt;juego&gt;.yml desde el jar, con los valores viejos de config.yml si los hay. */
    private static void create(JavaPlugin plugin, FileConfiguration config, String name, File f) {
        String resource = "modules/" + name + ".yml";
        YamlConfiguration game = new YamlConfiguration();
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null)
                game.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo leer " + resource + " del jar: " + e.getMessage());
        }
        boolean migrated = false;
        for (Part part : PARTS) {
            if (!part.file().equals(name))
                continue;
            ConfigurationSection old = config.getConfigurationSection(part.path());
            if (old == null)
                continue;
            for (String k : old.getKeys(true)) {
                if (old.isConfigurationSection(k))
                    continue;
                game.set(part.key().isEmpty() ? k : part.key() + "." + k, old.get(k));
                migrated = true;
            }
        }
        try {
            game.save(f);
            if (migrated)
                plugin.getLogger().info("Config de " + name + " movida de config.yml a modules/" + name
                        + ".yml (la sección vieja de config.yml ya no se usa).");
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo crear modules/" + name + ".yml: " + e.getMessage());
        }
    }
}
