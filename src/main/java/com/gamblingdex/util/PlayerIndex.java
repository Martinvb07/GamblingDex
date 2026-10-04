package com.gamblingdex.util;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class PlayerIndex {

    private final GamblingDexPlugin plugin;
    private final File file;
    private FileConfiguration cfg;

    private final Map<String, UUID> nameToUuid = new HashMap<>();
    private final Map<UUID, String> uuidToName = new HashMap<>();

    public PlayerIndex(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        load();
    }

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta de datos del plugin.");
        }

        if (!file.exists()) {
            try {
                if (!file.createNewFile()) {
                    plugin.getLogger().warning("No se pudo crear players.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Error creando players.yml: " + e.getMessage());
            }
        }

        this.nameToUuid.clear();
        this.uuidToName.clear();

        try {
            this.cfg = YamlConfiguration.loadConfiguration(file);
        } catch (Throwable t) {
            plugin.getLogger().warning("Error cargando players.yml (se ignorará): " + t.getMessage());
            this.cfg = new YamlConfiguration();
            return;
        }

        if (cfg.isConfigurationSection("players")) {
            for (String uuidStr : cfg.getConfigurationSection("players").getKeys(false)) {
                String name = cfg.getString("players." + uuidStr + ".name");
                if (name == null || name.isBlank())
                    continue;
                try {
                    UUID id = UUID.fromString(uuidStr);
                    uuidToName.put(id, name);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // Build name index from uuid->name (source of truth)
        for (Map.Entry<UUID, String> e : uuidToName.entrySet()) {
            String name = e.getValue();
            if (name == null || name.isBlank())
                continue;
            nameToUuid.put(name.toLowerCase(Locale.ROOT), e.getKey());
        }

        // Backwards compatibility: accept legacy "names" section only as a fallback
        // resolver.
        // We don't persist these aliases on save.
        if (cfg.isConfigurationSection("names")) {
            for (String lower : cfg.getConfigurationSection("names").getKeys(false)) {
                if (lower == null || lower.isBlank())
                    continue;
                String uuidStr = cfg.getString("names." + lower);
                if (uuidStr == null || uuidStr.isBlank())
                    continue;
                try {
                    UUID id = UUID.fromString(uuidStr);
                    nameToUuid.putIfAbsent(lower.toLowerCase(Locale.ROOT), id);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public void save() {
        YamlConfiguration out = new YamlConfiguration();

        // Persist only canonical state:
        // - players.<uuid>.name
        // - names.<lowerName> -> uuid (derived from players)
        for (Map.Entry<UUID, String> e : uuidToName.entrySet()) {
            if (e.getKey() == null)
                continue;
            String name = e.getValue();
            if (name == null || name.isBlank())
                continue;
            out.set("players." + e.getKey().toString() + ".name", name);
            out.set("names." + name.toLowerCase(Locale.ROOT), e.getKey().toString());
        }

        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");
        try {
            out.save(tmp.toFile());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar players.yml: " + e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    public void update(Player player) {
        if (player == null)
            return;
        UUID id = player.getUniqueId();
        String name = player.getName();
        if (name == null || name.isBlank())
            return;

        String prev = uuidToName.put(id, name);
        String lower = name.toLowerCase(Locale.ROOT);
        nameToUuid.put(lower, id);

        // If the player changed name, remove the old mapping if it pointed to this
        // UUID.
        if (prev != null && !prev.isBlank() && !prev.equals(name)) {
            String oldLower = prev.toLowerCase(Locale.ROOT);
            UUID mapped = nameToUuid.get(oldLower);
            if (id.equals(mapped)) {
                nameToUuid.remove(oldLower);
            }
        }
    }

    public UUID resolveNameToUuid(String name) {
        if (name == null || name.isBlank())
            return null;
        return nameToUuid.get(name.toLowerCase(Locale.ROOT));
    }

    public String getLastKnownName(UUID id) {
        if (id == null)
            return null;
        String name = uuidToName.get(id);
        if (name != null)
            return name;
        try {
            OfflinePlayer off = plugin.getServer().getOfflinePlayer(id);
            return off == null ? null : off.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
