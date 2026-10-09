package com.gamblingdex.stations;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.items.GameItemType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class StationManager {
    /** Marca de los hologramas de estaciones (slots/cambio). */
    public static final String TAG = "gdx_station";

    /**
     * Borra los hologramas de estaciones (slots/cambio) que quedaron sin estación
     * (duplicados o de estaciones borradas). Solo toca los suyos: los marcados con
     * {@link #TAG} o, los viejos sin marca, los que están justo donde va el cartel
     * de una estación. Antes borraba TODOS los TextDisplay del mundo (la ruleta,
     * las mesas...), y la ruleta se quedaba sin hologramas tras /gdx reload.
     * Devuelve la cantidad de hologramas eliminados.
     */
    public int cleanOrphanHolograms() {
        int removed = 0;
        double h = plugin.getConfig().getDouble("stations.holo_height", 1.2);
        List<Location> spots = new ArrayList<>();
        if (stations != null && stations.isConfigurationSection("stations"))
            for (String k : stations.getConfigurationSection("stations").getKeys(false)) {
                Location l = parseKey(k);
                if (l != null && getStationType(l) != GameItemType.ROULETTE)
                    spots.add(l.clone().add(0.5, h, 0.5));
            }
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                UUID id = entity.getUniqueId();
                if (hologramToStation.containsKey(id))
                    continue;
                Set<String> tags = entity.getScoreboardTags();
                boolean ours = tags.contains(TAG);
                if (!ours && tags.isEmpty()) {
                    Location el = entity.getLocation();
                    for (Location s : spots)
                        if (s.getWorld() == el.getWorld() && s.distanceSquared(el) < 0.36) {
                            ours = true;
                            break;
                        }
                }
                if (ours) {
                    entity.remove();
                    removed++;
                }
            }
        }

        return removed;
    }

    private final GamblingDexPlugin plugin;
    private final File stationsFile;
    private FileConfiguration stations;
    private final Map<UUID, Location> hologramToStation = new HashMap<>();

    public StationManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.stationsFile = new File(plugin.getDataFolder(), "stations.yml");
        reload();
    }

    public void reload() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta de datos del plugin.");
        }
        if (!stationsFile.exists()) {
            try {
                if (!stationsFile.createNewFile()) {
                    plugin.getLogger().warning("No se pudo crear stations.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Error creando stations.yml: " + e.getMessage());
            }
        }
        this.stations = YamlConfiguration.loadConfiguration(stationsFile);

        // rebuild hologram index (useful after /gdx reload)
        hologramToStation.clear();
        if (stations.isConfigurationSection("stations")) {
            for (String k : stations.getConfigurationSection("stations").getKeys(false)) {
                String holoStr = stations.getString("stations." + k + ".holo");
                if (holoStr == null)
                    continue;
                try {
                    UUID id = UUID.fromString(holoStr);
                    Location loc = parseKey(k);
                    if (loc != null) {
                        hologramToStation.put(id, loc);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // Limpieza automática de hologramas huérfanos
        int holos = cleanOrphanHolograms();
        if (holos > 0) {
            plugin.getLogger().info("Se eliminaron " + holos + " hologramas huérfanos de estaciones.");
        }

        // Apply config changes to existing station holograms (text/height)
        refreshStationHologramsFromConfig();
    }

    private void refreshStationHologramsFromConfig() {
        if (!stations.isConfigurationSection("stations"))
            return;

        boolean changed = false;
        double height = plugin.getConfig().getDouble("stations.holo_height", 1.2);

        for (String k : stations.getConfigurationSection("stations").getKeys(false)) {
            Location stationLoc = parseKey(k);
            if (stationLoc == null)
                continue;

            GameItemType type = getStationType(stationLoc);
            if (type == null || type == GameItemType.ROULETTE)
                continue;

            String base = "stations." + k + ".";
            String holoStr = stations.getString(base + "holo");
            UUID holoId = null;
            if (holoStr != null) {
                try {
                    holoId = UUID.fromString(holoStr);
                } catch (IllegalArgumentException ignored) {
                }
            }

            World w = stationLoc.getWorld();
            if (w == null)
                continue;

            TextDisplay td = null;
            if (holoId != null) {
                org.bukkit.entity.Entity e = w.getEntity(holoId);
                if (e instanceof TextDisplay)
                    td = (TextDisplay) e;
            }

            // If missing, recreate hologram and persist it (solo con el chunk y sus
            // entidades cargados; si no, aún no se sabe si existe y saldría duplicado).
            if (td == null && (!w.isChunkLoaded(stationLoc.getBlockX() >> 4, stationLoc.getBlockZ() >> 4)
                    || !w.getChunkAt(stationLoc).isEntitiesLoaded()))
                continue;
            if (td == null) {
                UUID newId = ensureHologram(stationLoc, type);
                if (newId != null) {
                    stations.set(base + "holo", newId.toString());
                    hologramToStation.put(newId, stationLoc);
                    changed = true;
                }
                continue;
            }

            String title = holoTitle(stationLoc, type);
            td.setText(title);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setDefaultBackground(false);
            td.setShadowed(true);

            Location newLoc = stationLoc.clone().add(0.5, height + extraHoloHeight(type), 0.5);
            td.teleport(newLoc);
        }

        if (changed) {
            save();
        }
    }

    public void setStation(Location location, GameItemType type) {
        if (location == null || type == null)
            return;
        // Roulette has its own hologram managed by world-roulette.
        boolean createHolo = type != GameItemType.ROULETTE;
        setStation(location, type, createHolo);
    }

    public void setStation(Location location, GameItemType type, boolean createHologram) {
        if (location == null || type == null)
            return;
        String k = key(location);

        // Backward compatible upgrade:
        // - Old format: stations.<key> = "slots"
        // - New format: stations.<key>.type = "slots"
        // IMPORTANT: Do not set stations.<key> = null for new stations, because that
        // can prevent
        // stations.<key>.type from being written/read and breaks GUI clicks.
        String path = "stations." + k;
        if (stations.contains(path) && !stations.isConfigurationSection(path)) {
            stations.set(path, null);
        }

        String base = path + ".";
        stations.set(base + "type", type.getId());

        if (createHologram) {
            // Remove previous hologram (prevents leaving orphaned displays when resetting).
            String oldHoloStr = stations.getString(base + "holo");
            if (oldHoloStr != null) {
                try {
                    UUID oldId = UUID.fromString(oldHoloStr);
                    hologramToStation.remove(oldId);
                    World w = location.getWorld();
                    if (w != null) {
                        Entity e = w.getEntity(oldId);
                        if (e != null)
                            e.remove();
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }

            UUID holoId = ensureHologram(location, type);
            if (holoId != null) {
                stations.set(base + "holo", holoId.toString());
                hologramToStation.put(holoId, location);
            }
        }

        save();
    }

    public boolean removeStation(Location location) {
        if (location == null)
            return false;
        String key = key(location);
        String path = "stations." + key;
        if (!stations.contains(path))
            return false;

        // remove hologram if present
        String holoStr = stations.getString(path + ".holo");
        if (holoStr != null) {
            try {
                UUID id = UUID.fromString(holoStr);
                hologramToStation.remove(id);
                World w = location.getWorld();
                if (w != null) {
                    Entity e = w.getEntity(id);
                    if (e != null)
                        e.remove();
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        stations.set(path, null);
        save();
        return true;
    }

    public GameItemType getStationType(Location location) {
        if (location == null)
            return null;
        String keyLower = key(location);
        String base = "stations." + keyLower;

        // Si la clave es una sección, solo usar el campo .type
        Object raw = stations.get(base);
        String id = null;
        if (raw instanceof org.bukkit.configuration.MemorySection) {
            id = stations.getString(base + ".type");
        } else {
            id = stations.getString(base);
            if (id == null) {
                id = stations.getString(base + ".type");
            }
        }
        // 2. Si no encuentra, busca entre todas las keys ignorando
        // mayúsculas/minúsculas y coordenadas
        if (id == null && stations.isConfigurationSection("stations")) {
            for (String k : stations.getConfigurationSection("stations").getKeys(false)) {
                String[] parts = k.split(";");
                if (parts.length == 4) {
                    try {
                        int x = Integer.parseInt(parts[1]);
                        int y = Integer.parseInt(parts[2]);
                        int z = Integer.parseInt(parts[3]);
                        if (x == location.getBlockX() && y == location.getBlockY() && z == location.getBlockZ()) {
                            // Coinciden coords, ignorar case del mundo
                            String id2 = stations.getString("stations." + k);
                            if (id2 == null)
                                id2 = stations.getString("stations." + k + ".type");
                            if (id2 != null) {
                                id = id2;
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (id == null)
            return null;
        for (GameItemType t : GameItemType.values()) {
            if (t.getId().equalsIgnoreCase(id))
                return t;
        }
        return null;
    }

    public GameItemType getStationType(Block block) {
        if (block == null)
            return null;
        return getStationType(block.getLocation());
    }

    public boolean isStation(Block block) {
        return getStationType(block) != null;
    }

    public Location getStationLocationByHologram(UUID hologramId) {
        if (hologramId == null)
            return null;
        return hologramToStation.get(hologramId);
    }

    /** Ubicaciones de todas las estaciones de un tipo (para avisos cerca de ellas). */
    public java.util.List<Location> locationsOf(GameItemType type) {
        java.util.List<Location> out = new java.util.ArrayList<>();
        if (!stations.isConfigurationSection("stations"))
            return out;
        for (String k : stations.getConfigurationSection("stations").getKeys(false)) {
            String id = stations.isConfigurationSection("stations." + k)
                    ? stations.getString("stations." + k + ".type")
                    : stations.getString("stations." + k);
            if (id == null || !id.equalsIgnoreCase(type.getId()))
                continue;
            Location l = parseKey(k);
            if (l != null)
                out.add(l);
        }
        return out;
    }

    public Map<GameItemType, Integer> countByType() {
        Map<GameItemType, Integer> map = new EnumMap<>(GameItemType.class);
        for (GameItemType t : GameItemType.values())
            map.put(t, 0);

        if (!stations.isConfigurationSection("stations"))
            return map;

        for (String k : stations.getConfigurationSection("stations").getKeys(false)) {
            // Formato nuevo: stations.<k>.type ; formato viejo: stations.<k> = "slots"
            String id = stations.isConfigurationSection("stations." + k)
                    ? stations.getString("stations." + k + ".type")
                    : stations.getString("stations." + k);
            if (id == null)
                continue;
            for (GameItemType t : GameItemType.values()) {
                if (t.getId().equalsIgnoreCase(id)) {
                    map.put(t, map.get(t) + 1);
                    break;
                }
            }
        }
        return map;
    }

    /**
     * Debug helper: returns a one-line summary of how this location is stored in
     * stations.yml.
     */
    public String debugLocation(Location location) {
        // DEBUG: Si no se resuelve, loguea todas las keys y valores
        boolean debug = true; // Cambia a false si no quieres spam
        if (location == null)
            return "<null location>";

        String k = key(location);
        String base = "stations." + k;

        boolean exists = stations.contains(base);
        boolean isSection = stations.isConfigurationSection(base);
        String oldFormat = stations.getString(base);
        String newFormat = stations.getString(base + ".type");
        String holo = stations.getString(base + ".holo");

        return "key=" + k + " exists=" + exists + " section=" + isSection + " old=" + oldFormat + " type="
                + newFormat + " holo=" + holo;
    }

    /** Tema de una estación de slots (null = clásico). */
    public String getTheme(Location location) {
        if (location == null)
            return null;
        String t = stations.getString("stations." + key(location) + ".theme");
        return t == null || t.isBlank() ? null : t;
    }

    /** Cambia el tema de una estación de slots y rehace su holograma. */
    public void setTheme(Location location, String theme) {
        String base = "stations." + key(location) + ".";
        stations.set(base + "theme", theme);
        save();
        setStation(location, GameItemType.SLOTS, true);
    }

    /** Hacia dónde mira el modelo 3D de una estación de slots (grados, 0 = sur). */
    public float getModelYaw(Location location) {
        if (location == null)
            return 0f;
        return (float) stations.getDouble("stations." + key(location) + ".model_yaw", 0.0);
    }

    public void setModelYaw(Location location, float yaw) {
        stations.set("stations." + key(location) + ".model_yaw", (double) (((yaw % 360) + 360) % 360));
        save();
    }

    /** Con el modelo 3D de slots encima de la estación, el holograma va más alto. */
    private double extraHoloHeight(GameItemType type) {
        if (type != GameItemType.SLOTS || plugin.getSlotsModels() == null)
            return 0;
        return plugin.getSlotsModels().holoExtraHeight();
    }

    /** Texto del holograma: el del tema (slots.yml → themes.&lt;tema&gt;.holo) o el de siempre. */
    private String holoTitle(Location location, GameItemType type) {
        String theme = type == GameItemType.SLOTS ? getTheme(location) : null;
        String text = theme == null ? null : plugin.getConfig().getString("games.slots.themes." + theme + ".holo");
        if (text == null)
            text = plugin.getConfig().getString("stations.holograms." + type.getId(), defaultHolo(type));
        return plugin.color(text);
    }

    private UUID ensureHologram(Location location, GameItemType type) {
        World w = location.getWorld();
        if (w == null)
            return null;

        String title = holoTitle(location, type);

        Location holoLoc = location.clone().add(0.5,
                plugin.getConfig().getDouble("stations.holo_height", 1.2) + extraHoloHeight(type), 0.5);
        TextDisplay td = w.spawn(holoLoc, TextDisplay.class);
        td.addScoreboardTag(TAG);
        td.setBillboard(Display.Billboard.CENTER);
        td.setSeeThrough(true);
        td.setDefaultBackground(false);
        td.setShadowed(true);
        td.setText(title);
        return td.getUniqueId();
    }

    // ...existing code...
    private static String defaultHolo(GameItemType type) {
        return switch (type) {
            case ROULETTE -> "&6&lRULETA";
            case SLOTS -> "&d&lSLOTS";
            case EXCHANGE -> "&e&lCAMBIO";
        };
    }

    private void save() {
        try {
            stations.save(stationsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Error guardando stations.yml: " + e.getMessage());
        }
    }

    private static Location parseKey(String key) {
        try {
            String[] parts = key.split(";");
            if (parts.length != 4)
                return null;
            // Buscar el mundo ignorando mayúsculas/minúsculas
            String worldName = parts[0];
            World w = null;
            for (World candidate : org.bukkit.Bukkit.getWorlds()) {
                if (candidate.getName().equalsIgnoreCase(worldName)) {
                    w = candidate;
                    break;
                }
            }
            if (w == null)
                return null;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(w, x, y, z);
        } catch (Exception ignored) {
            return null;
        }
    }

    // Métodos utilitarios para debug
    public java.util.Set<String> getAllStationKeys() {
        if (!stations.isConfigurationSection("stations"))
            return java.util.Collections.emptySet();
        return stations.getConfigurationSection("stations").getKeys(false);
    }

    public static String key(Location location) {
        World w = location.getWorld();
        String world = (w == null ? "world" : w.getName());
        // Forzar minúsculas para evitar problemas de case-sensitive
        return world.toLowerCase() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";"
                + location.getBlockZ();
    }
}
