package com.gamblingdex.games.blackjack;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class BlackjackManager {

    private final GamblingDexPlugin plugin;
    private final File tablesFile;
    private FileConfiguration tables;

    private final Map<String, BlackjackTable> tablesByCenter = new HashMap<>();
    private final Map<String, BlackjackTable> tablesByName = new HashMap<>();

    private BukkitTask seatScanTask;
    private BukkitTask lookTask;

    public BlackjackManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.tablesFile = new File(plugin.getDataFolder(), "blackjack_tables.yml");
        reload();
    }

    /** Al apagar el plugin: devuelve las fichas de las rondas en curso. */
    public void abortAllRounds() {
        for (BlackjackTable t : tablesByCenter.values()) {
            try {
                t.abortRound();
            } catch (Throwable ignored) {
            }
        }
    }

    public void reload() {
        if (seatScanTask != null) {
            seatScanTask.cancel();
            seatScanTask = null;
        }
        if (lookTask != null) {
            lookTask.cancel();
            lookTask = null;
        }

        for (BlackjackTable t : tablesByCenter.values()) {
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
                    plugin.getLogger().warning("No se pudo crear blackjack_tables.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Error creando blackjack_tables.yml: " + e.getMessage());
            }
        }

        this.tables = YamlConfiguration.loadConfiguration(tablesFile);
        this.tablesByCenter.clear();
        this.tablesByName.clear();

        if (tables.isConfigurationSection("tables")) {
            for (String centerKey : tables.getConfigurationSection("tables").getKeys(false)) {
                String base = "tables." + centerKey + ".";
                Location center = BlackjackTables.parseKey(centerKey);
                if (center == null)
                    continue;

                String name = tables.getString(base + "name", null);
                name = normalizeName(name);
                if (name == null) {
                    name = autoNameFor(centerKey);
                    tables.set(base + "name", name);
                }

                String prettyName = tables.getString(base + "pretty_name", null);

                List<String> holoStr = tables.getStringList(base + "displays.holo");
                List<UUID> holo = parseUuids(holoStr);

                List<String> seats = tables.getStringList(base + "seats");

                UUID dealerId = null;
                String dealerStr = tables.getString(base + "displays.dealer");
                if (dealerStr != null && !dealerStr.isBlank()) {
                    try {
                        dealerId = UUID.fromString(dealerStr.trim());
                    } catch (Exception ignored) {
                    }
                }

                BlackjackTable table = new BlackjackTable(plugin, centerKey, center, holo, dealerId);
                table.setBetLimitsSilently(tables.getLong(base + "min_bet", 0), tables.getLong(base + "max_bet", 0));
                if (tables.contains(base + "dealer_yaw"))
                    table.setDealerYaw((float) tables.getDouble(base + "dealer_yaw"));
                table.setPersistDisplaysCallback(() -> persistDisplays(table));
                table.setSeatKeys(seats);
                table.setDisplayName(name);
                table.setPrettyDisplayName(prettyName);
                table.refreshHologram();
                tablesByCenter.put(centerKey, table);

                // Enforce unique names (if collision, append _2, _3...)
                String uniqueName = uniqueName(name);
                if (!uniqueName.equals(name)) {
                    table.setDisplayName(uniqueName);
                    tables.set(base + "name", uniqueName);
                    name = uniqueName;
                }
                tablesByName.put(name.toLowerCase(Locale.ROOT), table);
            }
        }

        save();

        startSeatScanTask();
    }

    private void startSeatScanTask() {
        if (seatScanTask != null)
            return;

        seatScanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (BlackjackTable t : tablesByCenter.values()) {
                try {
                    t.tickSeatScan();
                } catch (Throwable ignored) {
                }
            }
        }, 20L, 20L);
        if (lookTask == null)
            lookTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (BlackjackTable t : tablesByCenter.values()) {
                    try {
                        t.tickDealerLook();
                    } catch (Throwable ignored) {
                    }
                }
            }, 10L, 5L);
    }

    private void persistDisplays(BlackjackTable table) {
        if (table == null)
            return;
        String base = "tables." + table.getTableKey() + ".";
        tables.set(base + "name", table.getDisplayName());
        String pretty = table.getPrettyDisplayName();
        tables.set(base + "pretty_name", (pretty == null || pretty.isBlank()) ? null : pretty);
        tables.set(base + "displays.holo", toStringList(table.getHoloDisplayIds()));
        tables.set(base + "displays.dealer", table.getDealerId() == null ? null : table.getDealerId().toString());
        tables.set(base + "dealer_yaw", table.getDealerYaw() == null ? null : (double) table.getDealerYaw());
        tables.set(base + "min_bet", table.getMinBetRaw() > 0 ? table.getMinBetRaw() : null);
        tables.set(base + "max_bet", table.getMaxBetRaw() > 0 ? table.getMaxBetRaw() : null);
        save();
    }

    public boolean setPrettyName(String tableName, String prettyName) {
        BlackjackTable table = getByName(tableName);
        if (table == null) {
            return false;
        }

        table.setPrettyDisplayName(prettyName);
        persistDisplays(table);
        return true;
    }

    public BlackjackTable getByBlock(Block block) {
        if (block == null)
            return null;
        String key = BlackjackTables.key(block.getLocation());
        if (key == null)
            return null;
        return tablesByCenter.get(key);
    }

    public BlackjackTable getByKey(String tableKey) {
        if (tableKey == null || tableKey.isBlank())
            return null;
        return tablesByCenter.get(tableKey.trim());
    }

    public BlackjackTable getByName(String name) {
        name = normalizeName(name);
        if (name == null)
            return null;
        return tablesByName.get(name.toLowerCase(Locale.ROOT));
    }

    public BlackjackTable getByDealer(UUID dealerId) {
        if (dealerId == null)
            return null;
        for (BlackjackTable t : tablesByCenter.values()) {
            if (dealerId.equals(t.getDealerId())) {
                return t;
            }
        }
        return null;
    }

    public int getTableCount() {
        return tablesByCenter.size();
    }

    public Collection<BlackjackTable> getTables() {
        return Collections.unmodifiableCollection(tablesByCenter.values());
    }

    public Collection<String> getTableNames() {
        List<String> names = new ArrayList<>();
        for (BlackjackTable t : tablesByCenter.values()) {
            if (t.getDisplayName() != null && !t.getDisplayName().isBlank()) {
                names.add(t.getDisplayName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Crea la mesa con el dealer mirando hacia {@code facing} (el admin que la crea). */
    public BlackjackTable createTable(String name, Block centerBlock, Location facing) {
        BlackjackTable t = createTable(name, centerBlock);
        if (t != null && facing != null)
            faceTowards(t, facing);
        return t;
    }

    /** Apuesta mínima y máxima de una mesa (0 = la de blackjack.yml). */
    public void setLimits(BlackjackTable t, long min, long max) {
        t.setBetLimits(min, max);
        persistDisplays(t);
    }

    /** Gira el dealer hacia {@code facing} y lo guarda. */
    public void faceTowards(BlackjackTable t, Location facing) {
        t.setDealerYaw(t.yawTowards(facing));
        persistDisplays(t);
    }

    public BlackjackTable createTable(String name, Block centerBlock) {
        name = normalizeName(name);
        if (name == null || centerBlock == null)
            return null;

        String key = BlackjackTables.key(centerBlock.getLocation());
        if (key == null)
            return null;

        // If a table with that name exists, remove it first (keeps names unique).
        BlackjackTable existingByName = getByName(name);
        if (existingByName != null) {
            existingByName.removeDisplays();
            existingByName.abortRound();
            tablesByName.remove(name.toLowerCase(Locale.ROOT));
            tablesByCenter.remove(existingByName.getTableKey());
            tables.set("tables." + existingByName.getTableKey(), null);
        }

        BlackjackTable existing = tablesByCenter.get(key);
        if (existing != null) {
            existing.removeDisplays();
            existing.abortRound();
            tablesByCenter.remove(key);
            tables.set("tables." + key, null);
            if (existing.getDisplayName() != null) {
                tablesByName.remove(existing.getDisplayName().toLowerCase(Locale.ROOT));
            }
        }

        BlackjackTable table = new BlackjackTable(plugin, key, centerBlock.getLocation(), new ArrayList<>());
        table.setPersistDisplaysCallback(() -> persistDisplays(table));
        table.setSeatKeys(Collections.emptyList());
        table.setDisplayName(name);
        table.refreshHologram();

        tablesByCenter.put(key, table);

        tablesByName.put(name.toLowerCase(Locale.ROOT), table);

        tables.set("tables." + key + ".created_at", System.currentTimeMillis());
        tables.set("tables." + key + ".name", name);
        tables.set("tables." + key + ".seats", Collections.emptyList());
        persistDisplays(table);
        return table;
    }

    // Backwards-compatible helper
    public BlackjackTable createTable(Block centerBlock) {
        return createTable(autoNameFor(BlackjackTables.key(centerBlock == null ? null : centerBlock.getLocation())),
                centerBlock);
    }

    public boolean addSeat(Block centerBlock, Block seatBlock) {
        BlackjackTable table = getByBlock(centerBlock);
        if (table == null || seatBlock == null)
            return false;

        String seatKey = BlackjackTables.key(seatBlock.getLocation());
        if (seatKey == null)
            return false;

        List<String> keys = table.getSeatKeys();
        if (keys.contains(seatKey))
            return true;

        keys.add(seatKey);
        table.setSeatKeys(keys);

        String base = "tables." + table.getTableKey() + ".";
        tables.set(base + "seats", keys);
        save();

        table.refreshHologram();
        return true;
    }

    public boolean addSeat(String tableName, Block seatBlock) {
        BlackjackTable table = getByName(tableName);
        if (table == null || seatBlock == null)
            return false;
        return addSeat(table.getCenter().getBlock(), seatBlock);
    }

    public boolean removeSeat(Block centerBlock, Block seatBlock) {
        BlackjackTable table = getByBlock(centerBlock);
        if (table == null || seatBlock == null)
            return false;

        String seatKey = BlackjackTables.key(seatBlock.getLocation());
        if (seatKey == null)
            return false;

        List<String> keys = table.getSeatKeys();
        boolean removed = keys.remove(seatKey);
        if (!removed)
            return false;

        table.setSeatKeys(keys);
        String base = "tables." + table.getTableKey() + ".";
        tables.set(base + "seats", keys);
        save();

        table.refreshHologram();
        return true;
    }

    public boolean removeSeat(String tableName, Block seatBlock) {
        BlackjackTable table = getByName(tableName);
        if (table == null || seatBlock == null)
            return false;
        return removeSeat(table.getCenter().getBlock(), seatBlock);
    }

    public List<String> listSeats(Block centerBlock) {
        BlackjackTable table = getByBlock(centerBlock);
        if (table == null)
            return Collections.emptyList();
        return table.getSeatKeys();
    }

    public List<String> listSeats(String tableName) {
        BlackjackTable table = getByName(tableName);
        if (table == null)
            return Collections.emptyList();
        return table.getSeatKeys();
    }

    public boolean clearSeats(Block centerBlock) {
        BlackjackTable table = getByBlock(centerBlock);
        if (table == null)
            return false;

        table.setSeatKeys(Collections.emptyList());
        String base = "tables." + table.getTableKey() + ".";
        tables.set(base + "seats", Collections.emptyList());
        save();
        table.refreshHologram();
        return true;
    }

    public boolean clearSeats(String tableName) {
        BlackjackTable table = getByName(tableName);
        if (table == null)
            return false;
        return clearSeats(table.getCenter().getBlock());
    }

    public boolean removeTable(Block centerBlock, boolean removeDisplays) {
        if (centerBlock == null)
            return false;
        String key = BlackjackTables.key(centerBlock.getLocation());
        if (key == null)
            return false;

        BlackjackTable table = tablesByCenter.get(key);
        if (table == null)
            return false;
        table.abortRound();
        if (removeDisplays)
            table.removeDisplays();

        tablesByCenter.remove(key);
        if (table.getDisplayName() != null) {
            tablesByName.remove(table.getDisplayName().toLowerCase(Locale.ROOT));
        }
        tables.set("tables." + key, null);
        save();
        return true;
    }

    public boolean removeTable(String tableName, boolean removeDisplays) {
        BlackjackTable table = getByName(tableName);
        if (table == null)
            return false;
        return removeTable(table.getCenter().getBlock(), removeDisplays);
    }

    private String uniqueName(String base) {
        if (base == null)
            return null;
        String n = base;
        int i = 2;
        while (tablesByName.containsKey(n.toLowerCase(Locale.ROOT))) {
            n = base + "_" + i;
            i++;
        }
        return n;
    }

    private static String normalizeName(String name) {
        if (name == null)
            return null;
        name = name.trim();
        if (name.isBlank())
            return null;
        // Keep it command-friendly
        name = name.replace(' ', '_');
        name = name.replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (name.isBlank())
            return null;
        if (name.length() > 24) {
            name = name.substring(0, 24);
        }
        return name;
    }

    private static String autoNameFor(String centerKey) {
        if (centerKey == null || centerKey.isBlank()) {
            return "blackjack";
        }
        int h = Math.abs(centerKey.hashCode());
        return "bj_" + Integer.toString(h, 36);
    }

    private void save() {
        try {
            tables.save(tablesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Error guardando blackjack_tables.yml: " + e.getMessage());
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
        for (UUID u : uuids) {
            if (u != null)
                out.add(u.toString());
        }
        return out;
    }
}
