package com.gamblingdex.config;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public class Messages {

    private final GamblingDexPlugin plugin;
    private final File messagesDir;
    private final Map<String, FileConfiguration> namespaces = new HashMap<>();

    // Default files packaged in the JAR (copied on first run).
    private static final List<String> DEFAULT_RESOURCES = List.of(
            "messages/gdx.yml",
            "messages/admin.yml",
            "messages/roulette_world.yml",
            "messages/blackjack.yml",
            "messages/poker.yml",
            "messages/exchange.yml",
            "messages/slots.yml",
            "messages/stations.yml",
            "messages/menus.yml",
            "messages/items.yml",
            "messages/join.yml");

    public Messages(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.messagesDir = new File(plugin.getDataFolder(), "messages");
    }

    public void ensureDefaults() {
        if (!messagesDir.exists()) {
            // noinspection ResultOfMethodCallIgnored
            messagesDir.mkdirs();
        }

        for (String res : DEFAULT_RESOURCES) {
            File out = new File(plugin.getDataFolder(), res);
            if (out.exists())
                continue;
            try {
                plugin.saveResource(res, false);
            } catch (IllegalArgumentException ignored) {
                // Resource not packaged; ignore.
            }
        }
    }

    public void reload() {
        ensureDefaults();
        namespaces.clear();

        File[] files = messagesDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null)
            return;

        for (File f : files) {
            String fileName = f.getName();
            String ns = fileName.substring(0, fileName.length() - 4); // drop .yml
            if (ns.isBlank())
                continue;
            namespaces.put(ns, YamlConfiguration.loadConfiguration(f));
        }
    }

    public String getString(String key, String def) {
        String raw = getRaw(key);
        if (raw == null)
            raw = def;
        return plugin.color(raw);
    }

    public List<String> getStringList(String key) {
        List<String> list = getRawList(key);
        if (list == null)
            return Collections.emptyList();
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) {
            out.add(plugin.color(s));
        }
        return out;
    }

    public String format(String key, String def, Map<String, String> placeholders) {
        String raw = getRaw(key);
        if (raw == null)
            raw = def;
        raw = applyPlaceholders(raw, placeholders);
        return plugin.color(raw);
    }

    public String format(String key, Map<String, String> placeholders) {
        return format(key, "", placeholders);
    }

    private String normalizeKey(String key) {
        if (key == null)
            return "";
        String k = key.trim();
        if (k.startsWith("messages.")) {
            k = k.substring("messages.".length());
        }
        return k;
    }

    private String getRaw(String key) {
        String k = normalizeKey(key);
        if (k.isBlank())
            return null;

        int dot = k.indexOf('.');
        if (dot > 0) {
            String ns = k.substring(0, dot);
            String path = k.substring(dot + 1);
            FileConfiguration cfg = namespaces.get(ns);
            if (cfg != null) {
                String v = cfg.getString(path, null);
                if (v != null)
                    return v;
            }
        }

        // Fallback to config.yml under messages.<key>
        String fromConfig = plugin.getConfig().getString("messages." + k, null);
        if (fromConfig != null)
            return fromConfig;

        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getRawList(String key) {
        String k = normalizeKey(key);
        if (k.isBlank())
            return null;

        int dot = k.indexOf('.');
        if (dot > 0) {
            String ns = k.substring(0, dot);
            String path = k.substring(dot + 1);
            FileConfiguration cfg = namespaces.get(ns);
            if (cfg != null && cfg.isList(path)) {
                List<?> l = cfg.getList(path);
                if (l != null) {
                    List<String> out = new ArrayList<>(l.size());
                    for (Object o : l) {
                        if (o != null)
                            out.add(String.valueOf(o));
                    }
                    return out;
                }
            }
        }

        if (plugin.getConfig().isList("messages." + k)) {
            return plugin.getConfig().getStringList("messages." + k);
        }

        return null;
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
}
