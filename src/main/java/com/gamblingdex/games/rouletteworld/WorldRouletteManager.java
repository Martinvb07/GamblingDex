package com.gamblingdex.games.rouletteworld;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.items.GameItemType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class WorldRouletteManager {

    private final GamblingDexPlugin plugin;
    private final File tablesFile;
    private FileConfiguration tables;

    private final Map<String, WorldRouletteTable> tablesByCenter = new HashMap<>();
    private final Map<String, String> segmentToCenter = new HashMap<>();

    public WorldRouletteManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.tablesFile = new File(plugin.getDataFolder(), "roulette_tables.yml");
        this.jackpotFile = new File(plugin.getDataFolder(), "roulette_jackpot.yml");
        this.jackpot = YamlConfiguration.loadConfiguration(jackpotFile).getLong("pot", 0L);
        reload();
    }

    // ------------------------------------------------------------------
    // Jackpot (uno solo para todas las ruletas)
    // ------------------------------------------------------------------

    private final File jackpotFile;
    private long jackpot;

    /** Pozo actual del jackpot de la ruleta. */
    public long getJackpot() {
        return jackpot;
    }

    public void addToJackpot(long amount) {
        if (amount <= 0)
            return;
        jackpot += amount;
        saveJackpot();
    }

    /** Se lo ganaron: vuelve al monto inicial (roulette_world.jackpot.seed). */
    public void resetJackpot() {
        jackpot = Math.max(0L, plugin.getConfig().getLong("roulette_world.jackpot.seed", 0L));
        saveJackpot();
    }

    private void saveJackpot() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("pot", jackpot);
        try {
            y.save(jackpotFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("No se pudo guardar roulette_jackpot.yml: " + e.getMessage());
        }
    }

    /** Al apagar el plugin: devuelve las fichas de las rondas en curso. */
    public void abortAllRounds() {
        for (WorldRouletteTable t : tablesByCenter.values()) {
            try {
                t.abortRound();
            } catch (Throwable ignored) {
            }
        }
    }

    public void reload() {
        // Cancel tasks from previously loaded tables so /gdx reload won't duplicate
        // timers.
        for (WorldRouletteTable t : tablesByCenter.values()) {
            try {
                t.abortRound();
            } catch (Throwable ignored) {
            }
        }

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta de datos del plugin.");
        }
        if (!tablesFile.exists()) {
            try {
                if (!tablesFile.createNewFile()) {
                    plugin.getLogger().warning("No se pudo crear roulette_tables.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Error creando roulette_tables.yml: " + e.getMessage());
            }
        }

        this.tables = YamlConfiguration.loadConfiguration(tablesFile);
        this.tablesByCenter.clear();
        this.segmentToCenter.clear();

        if (!tables.isConfigurationSection("tables"))
            return;

        for (String centerKey : tables.getConfigurationSection("tables").getKeys(false)) {
            String base = "tables." + centerKey + ".";
            int radius = tables.getInt(base + "radius", plugin.getConfig().getInt("roulette_world.radius", 6));

            Location center = parseKey(centerKey);
            if (center == null)
                continue;

            Map<String, Integer> segments = new HashMap<>();
            Map<Integer, Location> numberToLoc = new HashMap<>();

            if (tables.isConfigurationSection(base + "segments")) {
                for (String segKey : tables.getConfigurationSection(base + "segments").getKeys(false)) {
                    int num = tables.getInt(base + "segments." + segKey);
                    segments.put(segKey, num);
                    Location segLoc = parseKey(segKey);
                    if (segLoc != null)
                        numberToLoc.put(num, segLoc);
                    segmentToCenter.put(segKey, centerKey);
                }
            }

            List<String> numberDisplaysStr = tables.getStringList(base + "displays.numbers");
            List<String> holoDisplaysStr = tables.getStringList(base + "displays.holo");
            List<UUID> numberDisplays = parseUuids(numberDisplaysStr);
            List<UUID> holoDisplays = parseUuids(holoDisplaysStr);

            Map<String, WorldRouletteTable.OriginalBlock> originals = new HashMap<>();
            if (tables.isConfigurationSection(base + "originals")) {
                for (String locKey : tables.getConfigurationSection(base + "originals").getKeys(false)) {
                    String typeStr = tables.getString(base + "originals." + locKey + ".type", "AIR");
                    String dataStr = tables.getString(base + "originals." + locKey + ".data", "");
                    Material mat = Material.matchMaterial(typeStr);
                    if (mat == null)
                        mat = Material.AIR;
                    originals.put(locKey, new WorldRouletteTable.OriginalBlock(mat, dataStr));
                }
            }

            WorldRouletteTable table = new WorldRouletteTable(
                    plugin,
                    centerKey,
                    center,
                    radius,
                    segments,
                    numberToLoc,
                    originals,
                    numberDisplays,
                    holoDisplays);
            table.refreshHologramFromConfig();
            table.ensureAutoCycleStarted();
            tablesByCenter.put(centerKey, table);
        }
    }

    public WorldRouletteTable getByCenter(Block centerBlock) {
        if (centerBlock == null)
            return null;
        return tablesByCenter.get(WorldRouletteTables.key(centerBlock.getLocation()));
    }

    public WorldRouletteTable getByBlock(Block anyBlock) {
        if (anyBlock == null)
            return null;
        String key = WorldRouletteTables.key(anyBlock.getLocation());
        if (tablesByCenter.containsKey(key))
            return tablesByCenter.get(key);
        String centerKey = segmentToCenter.get(key);
        if (centerKey == null)
            return null;
        return tablesByCenter.get(centerKey);
    }

    public WorldRouletteTable getByKey(String centerKey) {
        if (centerKey == null)
            return null;
        return tablesByCenter.get(centerKey);
    }

    public boolean isRouletteBlock(Block block) {
        return getByBlock(block) != null;
    }

    /** Centros de todas las mesas (para avisos cerca de la ruleta). */
    public java.util.List<org.bukkit.Location> getTableCenters() {
        java.util.List<org.bukkit.Location> out = new java.util.ArrayList<>();
        for (WorldRouletteTable t : tablesByCenter.values())
            if (t.getCenter() != null)
                out.add(t.getCenter());
        return out;
    }

    public int getTableCount() {
        return tablesByCenter.size();
    }

    public WorldRouletteTable buildTable(Block centerBlock, int radius) {
        return buildTable(centerBlock, radius, null);
    }

    public WorldRouletteTable buildTable(Block centerBlock, int radius, Integer segmentYOffsetOverride) {
        if (centerBlock == null)
            return null;
        Location center = centerBlock.getLocation();
        World w = center.getWorld();
        if (w == null)
            return null;

        int r = Math.max(3, Math.min(12, radius));

        String centerKey = WorldRouletteTables.key(center);
        // Remove existing table at this center (displays only) so rebuilding is safe.
        WorldRouletteTable existing = tablesByCenter.get(centerKey);
        Map<String, WorldRouletteTable.OriginalBlock> originals = null;
        if (existing != null) {
            existing.removeDisplays();
            originals = existing.getOriginals();
            unregisterTable(existing, false);
        }

        if (originals == null || originals.isEmpty()) {
            originals = captureOriginalBlocks(centerBlock, centerKey);
        }

        centerBlock.setType(Material.LODESTONE);
        plugin.getStationManager().setStation(center, GameItemType.ROULETTE);

        Map<String, Integer> segments = new HashMap<>();
        Map<Integer, Location> numberToLoc = new HashMap<>();
        Set<String> used = new HashSet<>();
        used.add(centerKey);

        int segmentYOffset = (segmentYOffsetOverride != null)
                ? segmentYOffsetOverride
                : plugin.getConfig().getInt("roulette_world.segment_y_offset", -1);
        for (int i = 0; i < WorldRouletteTables.WHEEL_ORDER.length; i++) {
            int number = WorldRouletteTables.WHEEL_ORDER[i];

            double angle = (2.0 * Math.PI) * (i / (double) WorldRouletteTables.WHEEL_ORDER.length);
            int dx = (int) Math.round(r * Math.cos(angle));
            int dz = (int) Math.round(r * Math.sin(angle));

            Location base = center.clone().add(dx, segmentYOffset, dz);
            Location loc = findFree(base, used);
            if (loc == null)
                continue;

            String segKey = WorldRouletteTables.key(loc);
            used.add(segKey);

            // capture original segment block before overwriting
            if (!originals.containsKey(segKey)) {
                Block b = w.getBlockAt(loc);
                originals.put(segKey,
                        new WorldRouletteTable.OriginalBlock(b.getType(), b.getBlockData().getAsString()));
            }

            Material mat;
            if (WorldRouletteTables.isZero(number)) {
                mat = Material.LIME_CONCRETE;
            } else {
                mat = WorldRouletteTables.isRed(number) ? Material.RED_CONCRETE : Material.BLACK_CONCRETE;
            }
            w.getBlockAt(loc).setType(mat);

            segments.put(segKey, number);
            numberToLoc.put(number, loc);
        }

        // Displays: numbers
        List<UUID> numberDisplays = new ArrayList<>();
        for (Map.Entry<Integer, Location> e : numberToLoc.entrySet()) {
            int number = e.getKey();
            Location loc = e.getValue().clone().add(0.5, 1.15, 0.5);
            TextDisplay td = w.spawn(loc, TextDisplay.class);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setDefaultBackground(false);
            td.setShadowed(true);

            String color = WorldRouletteTables.isZero(number) ? "§a"
                    : (WorldRouletteTables.isRed(number) ? "§c" : "§8");
            td.setText(color + "§l" + WorldRouletteTables.formatNumber(number));
            numberDisplays.add(td.getUniqueId());
        }

        // Displays: hologram lines
        List<UUID> holoDisplays = new ArrayList<>();
        double holoHeight = plugin.getConfig().getDouble("roulette_world.holo_height", 2.3);
        Location holoBase = center.clone().add(0.5, holoHeight, 0.5);

        List<String> lines = plugin.getConfig().getStringList("roulette_world.holo_lines");
        if (lines == null || lines.isEmpty()) {
            lines = List.of("§6§lRULETA", "§7Click derecho con tokens para apostar", "§eEsperando jugadores...");
        }

        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            Location l = holoBase.clone().add(0, -0.25 * i, 0);
            TextDisplay td = w.spawn(l, TextDisplay.class);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setDefaultBackground(false);
            td.setShadowed(true);
            td.setText(plugin.color(lines.get(i)));
            holoDisplays.add(td.getUniqueId());
        }

        WorldRouletteTable table = new WorldRouletteTable(plugin, centerKey, center, r, segments, numberToLoc,
                originals, numberDisplays, holoDisplays);
        table.ensureAutoCycleStarted();
        tablesByCenter.put(centerKey, table);
        for (String segKey : segments.keySet()) {
            segmentToCenter.put(segKey, centerKey);
        }

        persistTable(centerKey, r, segments, originals, numberDisplays, holoDisplays);

        return table;
    }

    private Map<String, WorldRouletteTable.OriginalBlock> captureOriginalBlocks(Block centerBlock, String centerKey) {
        Map<String, WorldRouletteTable.OriginalBlock> originals = new HashMap<>();
        if (centerBlock == null)
            return originals;
        originals.put(centerKey,
                new WorldRouletteTable.OriginalBlock(centerBlock.getType(), centerBlock.getBlockData().getAsString()));
        return originals;
    }

    public boolean removeTable(Block centerBlock, boolean removeDisplays) {
        return removeTable(centerBlock, removeDisplays, false);
    }

    public boolean removeTable(Block centerBlock, boolean removeDisplays, boolean removeBlocks) {
        if (centerBlock == null)
            return false;
        String centerKey = WorldRouletteTables.key(centerBlock.getLocation());
        WorldRouletteTable table = tablesByCenter.get(centerKey);
        if (table == null)
            return false;

        if (removeDisplays)
            table.removeDisplays();
        if (removeBlocks)
            table.removeBlocks();
        unregisterTable(table, true);
        plugin.getStationManager().removeStation(centerBlock.getLocation());
        return true;
    }

    private void unregisterTable(WorldRouletteTable table, boolean updateFile) {
        table.abortRound();
        tablesByCenter.remove(table.getTableKey());

        // clean segment index
        segmentToCenter.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(table.getTableKey()));

        if (updateFile) {
            tables.set("tables." + table.getTableKey(), null);
            save();
        }
    }

    private void persistTable(
            String centerKey,
            int radius,
            Map<String, Integer> segments,
            Map<String, WorldRouletteTable.OriginalBlock> originals,
            List<UUID> numberDisplays,
            List<UUID> holoDisplays) {
        String base = "tables." + centerKey + ".";
        tables.set(base + "radius", radius);

        tables.set(base + "segments", null);
        for (Map.Entry<String, Integer> e : segments.entrySet()) {
            tables.set(base + "segments." + e.getKey(), e.getValue());
        }

        tables.set(base + "originals", null);
        if (originals != null && !originals.isEmpty()) {
            for (Map.Entry<String, WorldRouletteTable.OriginalBlock> e : originals.entrySet()) {
                WorldRouletteTable.OriginalBlock ob = e.getValue();
                if (ob == null)
                    continue;
                tables.set(base + "originals." + e.getKey() + ".type",
                        ob.getType() == null ? "AIR" : ob.getType().name());
                tables.set(base + "originals." + e.getKey() + ".data",
                        ob.getBlockData() == null ? "" : ob.getBlockData());
            }
        }

        tables.set(base + "displays.numbers", toStringList(numberDisplays));
        tables.set(base + "displays.holo", toStringList(holoDisplays));
        save();
    }

    private void save() {
        try {
            tables.save(tablesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Error guardando roulette_tables.yml: " + e.getMessage());
        }
    }

    private static Location findFree(Location base, Set<String> usedKeys) {
        if (base == null)
            return null;
        String k = WorldRouletteTables.key(base);
        if (!usedKeys.contains(k))
            return base;

        // search nearby offsets to avoid collisions caused by rounding
        int[] deltas = new int[] { 0, 1, -1, 2, -2, 3, -3 };
        for (int dx : deltas) {
            for (int dz : deltas) {
                Location l = base.clone().add(dx, 0, dz);
                String kk = WorldRouletteTables.key(l);
                if (!usedKeys.contains(kk))
                    return l;
            }
        }
        return null;
    }

    private static Location parseKey(String key) {
        try {
            String[] parts = key.split(";");
            if (parts.length != 4)
                return null;
            World w = Bukkit.getWorld(parts[0]);
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

    private static List<UUID> parseUuids(List<String> values) {
        if (values == null)
            return new ArrayList<>();
        List<UUID> out = new ArrayList<>();
        for (String s : values) {
            try {
                out.add(UUID.fromString(s));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static List<String> toStringList(List<UUID> uuids) {
        List<String> out = new ArrayList<>();
        for (UUID u : uuids)
            out.add(u.toString());
        return out;
    }
}
