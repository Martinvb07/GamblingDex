package com.gamblingdex.games.poker;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.blackjack.BlackjackTables;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Carga/guarda las mesas de póker (poker_tables.yml), corre el tick de todas
 * las mesas. Las fichas de jugadores que se desconectan en medio de una mano
 * van a {@link com.gamblingdex.economy.PendingPayouts} (se pagan al volver).
 */
public class PokerManager {

    private final GamblingDexPlugin plugin;
    private final File file;
    private FileConfiguration data;

    private final Map<String, PokerTable> tablesByKey = new LinkedHashMap<>();
    private BukkitTask tickTask;

    public PokerManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "poker_tables.yml");
        load();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (PokerTable t : new ArrayList<>(tablesByKey.values())) {
                try {
                    t.tick();
                } catch (Throwable ex) {
                    plugin.getLogger().warning("[Poker] Error en la mesa " + t.getName() + ": " + ex);
                }
            }
        }, 20L, 20L);
    }

    private void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta de datos del plugin.");
        }
        data = YamlConfiguration.loadConfiguration(file);
        tablesByKey.clear();

        ConfigurationSection sec = data.getConfigurationSection("tables");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Location center = BlackjackTables.parseKey(key);
                if (center == null) {
                    plugin.getLogger().warning("[Poker] Mesa con mundo inválido, se ignora: " + key);
                    continue;
                }
                String base = "tables." + key + ".";
                String name = data.getString(base + "name", "poker");
                long sb = data.getLong(base + "small_blind", defaultSb());
                long bb = data.getLong(base + "big_blind", defaultBb());
                PokerTable t = new PokerTable(plugin, this, key, center, name, sb, bb);
                t.setSeatKeys(data.getStringList(base + "seats"));
                tablesByKey.put(key, t);
            }
        }
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Error guardando poker_tables.yml: " + e.getMessage());
        }
    }

    private void persistTable(PokerTable t) {
        String base = "tables." + t.getTableKey() + ".";
        data.set(base + "name", t.getName());
        data.set(base + "small_blind", t.getSmallBlind());
        data.set(base + "big_blind", t.getBigBlind());
        data.set(base + "seats", t.getSeatKeys());
        save();
    }

    private long defaultSb() {
        return Math.max(1L, plugin.getConfig().getLong("poker.default_small_blind", 5L));
    }

    private long defaultBb() {
        return Math.max(defaultSb(), plugin.getConfig().getLong("poker.default_big_blind", 10L));
    }

    /** /gdx reload: las manos en curso siguen; solo se refrescan hologramas. */
    public void reload() {
        for (PokerTable t : tablesByKey.values())
            t.updateDisplays();
    }

    /** Apagado del plugin: devuelve todas las fichas y quita hologramas. */
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (PokerTable t : tablesByKey.values()) {
            try {
                t.shutdown();
            } catch (Throwable ex) {
                plugin.getLogger().warning("[Poker] Error cerrando mesa " + t.getName() + ": " + ex);
            }
        }
        save();
    }

    // ---- Mesas ----

    public Collection<PokerTable> getTables() {
        return Collections.unmodifiableCollection(tablesByKey.values());
    }

    public PokerTable getByKey(String key) {
        return key == null ? null : tablesByKey.get(key);
    }

    public PokerTable getByBlock(Block block) {
        if (block == null)
            return null;
        return tablesByKey.get(BlackjackTables.key(block.getLocation()));
    }

    public PokerTable getByName(String name) {
        if (name == null)
            return null;
        for (PokerTable t : tablesByKey.values()) {
            if (t.getName().equalsIgnoreCase(name.trim()))
                return t;
        }
        return null;
    }

    /** Mesa (cualquiera) donde está sentado el jugador, o null. */
    public PokerTable getTableOf(UUID playerId) {
        for (PokerTable t : tablesByKey.values()) {
            if (t.isSeated(playerId))
                return t;
        }
        return null;
    }

    public List<String> getTableNames() {
        List<String> out = new ArrayList<>();
        for (PokerTable t : tablesByKey.values())
            out.add(t.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public PokerTable createTable(String name, Block center, long sb, long bb) {
        name = normalizeName(name);
        if (name == null || center == null)
            return null;
        String key = BlackjackTables.key(center.getLocation());
        if (key == null || tablesByKey.containsKey(key) || getByName(name) != null)
            return null;

        if (sb <= 0)
            sb = defaultSb();
        if (bb <= 0)
            bb = Math.max(sb * 2, defaultBb());
        PokerTable t = new PokerTable(plugin, this, key, center.getLocation(), name, sb, Math.max(sb, bb));
        tablesByKey.put(key, t);
        data.set("tables." + key + ".created_at", System.currentTimeMillis());
        persistTable(t);
        t.updateDisplays();
        return t;
    }

    public boolean removeTable(String name) {
        PokerTable t = getByName(name);
        if (t == null)
            return false;
        t.shutdown();
        tablesByKey.remove(t.getTableKey());
        data.set("tables." + t.getTableKey(), null);
        save();
        return true;
    }

    /** null = OK; si no, el motivo. */
    public String setStakes(String name, long sb, long bb) {
        PokerTable t = getByName(name);
        if (t == null)
            return "not_found";
        if (sb <= 0 || bb < sb)
            return "invalid";
        if (!t.setStakes(sb, bb))
            return "hand_running";
        persistTable(t);
        return null;
    }

    /** null = OK; si no, el motivo ("not_found", "hand_running", "exists", "missing"). */
    public String addSeat(String name, Block seat) {
        PokerTable t = getByName(name);
        if (t == null)
            return "not_found";
        String key = BlackjackTables.key(seat.getLocation());
        List<String> keys = t.getSeatKeys();
        if (keys.contains(key))
            return "exists";
        int max = Math.max(2, Math.min(10, plugin.getConfig().getInt("poker.max_seats", 9)));
        if (keys.size() >= max)
            return "full";
        keys.add(key);
        if (!t.setSeatKeys(keys))
            return "hand_running";
        persistTable(t);
        return null;
    }

    public String removeSeat(String name, Block seat) {
        PokerTable t = getByName(name);
        if (t == null)
            return "not_found";
        List<String> keys = t.getSeatKeys();
        if (!keys.remove(BlackjackTables.key(seat.getLocation())))
            return "missing";
        if (!t.setSeatKeys(keys))
            return "hand_running";
        persistTable(t);
        return null;
    }

    public String clearSeats(String name) {
        PokerTable t = getByName(name);
        if (t == null)
            return "not_found";
        if (!t.setSeatKeys(Collections.emptyList()))
            return "hand_running";
        persistTable(t);
        return null;
    }

    // ---- Rake y fichas pendientes ----

    public void addRake(long amount) {
        if (amount <= 0)
            return;
        data.set("stats.rake_total", data.getLong("stats.rake_total", 0L) + amount);
        save();
    }

    public long getRakeTotal() {
        return data.getLong("stats.rake_total", 0L);
    }

    public void addPending(UUID playerId, long amount) {
        if (playerId == null || amount <= 0)
            return;
        if (plugin.getPendingPayouts() != null) {
            plugin.getPendingPayouts().add(playerId, amount);
        } else {
            plugin.getLogger().warning("[Poker] No se pudo guardar el pago pendiente de " + playerId + ": " + amount);
        }
    }

    private static String normalizeName(String name) {
        if (name == null)
            return null;
        name = name.trim().replace(' ', '_').replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (name.isBlank())
            return null;
        return name.length() > 24 ? name.substring(0, 24) : name;
    }
}
