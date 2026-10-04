package com.gamblingdex.modules;

import com.gamblingdex.games.blackjack.BlackjackTables;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/**
 * Estaciones de un solo bloque (click derecho = abre el menú del juego) con
 * holograma encima. Se guardan en {@code modules/<id>_stations.yml} para no
 * mezclarse con los datos propios del módulo.
 */
public final class SimpleStations {

    private final GameModule module;
    private final Supplier<String> holoText;
    private final Map<String, Location> stations = new LinkedHashMap<>();
    private final Map<String, UUID> holos = new HashMap<>();

    public SimpleStations(GameModule module, Supplier<String> holoText) {
        this.module = module;
        this.holoText = holoText;
    }

    private File file() {
        return new File(new File(module.plugin.getDataFolder(), "modules"), module.id() + "_stations.yml");
    }

    public void load() {
        stations.clear();
        for (String k : YamlConfiguration.loadConfiguration(file()).getStringList("stations")) {
            Location l = BlackjackTables.parseKey(k);
            if (l != null)
                stations.put(k, l);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("stations", new ArrayList<>(stations.keySet()));
        try {
            y.save(file());
        } catch (IOException e) {
            module.plugin.getLogger().warning("[" + module.id() + "] No se pudo guardar las estaciones: " + e.getMessage());
        }
    }

    public boolean contains(Block b) {
        return b != null && stations.containsKey(BlackjackTables.key(b.getLocation()));
    }

    /** false si ya era una estación. */
    public boolean add(Block b) {
        String k = BlackjackTables.key(b.getLocation());
        if (stations.containsKey(k))
            return false;
        stations.put(k, b.getLocation());
        save();
        refresh(k);
        return true;
    }

    public boolean remove(Block b) {
        String k = BlackjackTables.key(b.getLocation());
        if (stations.remove(k) == null)
            return false;
        removeHolo(k);
        save();
        return true;
    }

    public int size() {
        return stations.size();
    }

    /** Crea o actualiza los hologramas (llamar cada cierto tiempo: se pierden al descargar el chunk). */
    public void refreshAll() {
        for (String k : stations.keySet())
            refresh(k);
    }

    private void refresh(String k) {
        Location loc = stations.get(k);
        World w = loc == null ? null : loc.getWorld();
        if (w == null || !w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4))
            return;
        double h = module.config().getDouble("station_holo_height", 1.2);
        if (h == 1.3)
            h = 1.2; // valor de la primera versión (quedaba alto)
        Location at = loc.clone().add(0.5, h, 0.5);
        UUID id = holos.get(k);
        Entity e = id == null ? null : w.getEntity(id);
        TextDisplay td;
        if (e instanceof TextDisplay existing && existing.isValid()) {
            td = existing;
            if (td.getLocation().distanceSquared(at) > 0.01)
                td.teleport(at);
        } else {
            td = w.spawn(at, TextDisplay.class);
            td.setPersistent(false);
            td.setBillboard(Display.Billboard.CENTER);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            td.setShadowed(true);
            td.setLineWidth(300);
            holos.put(k, td.getUniqueId());
        }
        String text = holoText.get();
        if (text != null && text.contains("Click derecho para ")) // textos largos de la primera versión
            text = text.replace("Click derecho para reclamar", "Click para reclamar")
                    .replace("Click derecho para jugar", "Click para jugar").replace("✦ ", "").replace(" ✦", "");
        td.setText(module.color(text));
    }

    private void removeHolo(String k) {
        UUID id = holos.remove(k);
        Location loc = stations.get(k);
        if (id == null)
            return;
        for (World w : module.plugin.getServer().getWorlds()) {
            Entity e = w.getEntity(id);
            if (e != null) {
                e.remove();
                return;
            }
        }
        if (loc != null && loc.getWorld() != null) {
            Entity e = loc.getWorld().getEntity(id);
            if (e != null)
                e.remove();
        }
    }

    public void removeHolos() {
        for (String k : new ArrayList<>(holos.keySet()))
            removeHolo(k);
    }
}
