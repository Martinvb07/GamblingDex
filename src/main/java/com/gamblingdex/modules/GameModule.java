package com.gamblingdex.modules;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.*;

/**
 * Base de un minijuego. Cada juego vive en su propio paquete
 * {@code com.gamblingdex.modules.<juego>} con una clase {@code XxxModule} que
 * extiende esta; {@link ModuleManager} la encuentra sola (no hay que registrarla
 * en ningún archivo compartido).
 *
 * Cada módulo tiene su archivo {@code plugins/GamblingDex/modules/<id>.yml}
 * (copiado desde {@code modules/<id>.yml} del jar). Los valores del jar sirven
 * de defaults, así que un archivo viejo sigue funcionando con opciones nuevas.
 * Los mensajes van en la sección {@code messages:} de ese mismo archivo.
 */
public abstract class GameModule {

    protected GamblingDexPlugin plugin;
    private File configFile;
    private FileConfiguration config;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    /** Id del módulo: nombre del archivo de config y comando principal (/gdx id). */
    public abstract String id();

    /** Nombre para mostrar. */
    public abstract String displayName();

    /** Otros nombres para el comando (/gdx alias). */
    public List<String> aliases() {
        return List.of();
    }

    /** Se llama al habilitar el plugin (o al recargar si estaba apagado). */
    public abstract void enable();

    /**
     * Al apagar: devolver las fichas de rondas en curso. Las tareas y listeners
     * registrados con {@link #runTimer}/{@link #listen} se cancelan solos.
     */
    public void disable() {
    }

    /** /gdx reload: la config ya se recargó cuando se llama esto. */
    public void reload() {
    }

    /** /gdx id [args...]. {@code args} no incluye el nombre del comando. */
    public abstract boolean onCommand(Player player, String[] args);

    /** Líneas para /gdx help (con colores &). */
    public List<String> helpLines(boolean admin) {
        return List.of();
    }

    // ------------------------------------------------------------------
    // Estaciones (/gdx station set <tipo> ...)
    // ------------------------------------------------------------------

    /** Tipos que acepta /gdx station set para este juego (el primero es el principal). */
    public List<String> stationTypes() {
        return List.of();
    }

    /** Uso que se muestra en la ayuda de /gdx station set (ej. "carrera <distancia> <carriles>"). */
    public String stationUsage() {
        return stationTypes().isEmpty() ? "" : stationTypes().get(0);
    }

    /** /gdx station set &lt;tipo&gt; [args...] mirando {@code target}. {@code args} va sin el tipo. */
    public void createStation(Player player, Block target, String[] args) {
    }

    /** /gdx station remove mirando {@code target}: true si era una estación de este juego. */
    public boolean removeStation(Player player, Block target) {
        return false;
    }

    /** Líneas para /gdx station list. */
    public List<String> stationListLines() {
        return List.of();
    }

    // ------------------------------------------------------------------
    // Ciclo de vida (lo usa ModuleManager)
    // ------------------------------------------------------------------

    final void init(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    final void shutdown() {
        try {
            disable();
        } finally {
            for (BukkitTask t : tasks)
                t.cancel();
            tasks.clear();
            for (Listener l : listeners)
                HandlerList.unregisterAll(l);
            listeners.clear();
        }
    }

    public boolean isEnabledInConfig() {
        return config.getBoolean("enabled", true);
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    final void loadConfig() {
        File dir = new File(plugin.getDataFolder(), "modules");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta modules/");
        }
        configFile = new File(dir, id() + ".yml");
        String resource = "modules/" + id() + ".yml";
        if (!configFile.exists() && plugin.getResource(resource) != null) {
            plugin.saveResource(resource, false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream in = plugin.getResource(resource);
        if (in != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    public FileConfiguration config() {
        return config;
    }

    /** Guarda modules/&lt;id&gt;.yml (por ejemplo, después de agregar una mesa a table_names). */
    protected void saveConfigFile() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[" + id() + "] No se pudo guardar " + configFile.getName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Datos persistentes del módulo (plugins/GamblingDex/modules/<id>_data.yml)
    // ------------------------------------------------------------------

    protected File dataFile() {
        return new File(new File(plugin.getDataFolder(), "modules"), id() + "_data.yml");
    }

    protected YamlConfiguration loadData() {
        return YamlConfiguration.loadConfiguration(dataFile());
    }

    protected void saveData(YamlConfiguration data) {
        try {
            data.save(dataFile());
        } catch (IOException e) {
            plugin.getLogger().warning("[" + id() + "] Error guardando datos: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    protected void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    protected BukkitTask runTimer(Runnable r, long delayTicks, long periodTicks) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, r, delayTicks, periodTicks);
        tasks.add(t);
        return t;
    }

    protected BukkitTask runLater(Runnable r, long delayTicks) {
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, r, delayTicks);
        tasks.add(t);
        tasks.removeIf(BukkitTask::isCancelled);
        return t;
    }

    /** Mensaje de messages.&lt;key&gt; en el yml del módulo, con {placeholders} y colores. */
    public String msg(String key, String def, String... kv) {
        String raw = config.getString("messages." + key, def);
        if (raw == null)
            raw = def;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return plugin.color(raw.replace("\\n", "\n"));
    }

    public String color(String s) {
        return plugin.color(s);
    }

    public boolean isAdmin(Player p) {
        return p.hasPermission("gamblingdex.admin");
    }

    public static String units(long v) {
        try {
            return NumberFormat.getInstance(Locale.forLanguageTag("es-ES")).format(v);
        } catch (Exception ignored) {
            return String.valueOf(v);
        }
    }

    public static long parseAmount(String s) {
        try {
            String t = s.trim().toLowerCase(Locale.ROOT).replace(".", "").replace(",", "");
            long mult = 1;
            if (t.endsWith("k")) {
                mult = 1_000;
                t = t.substring(0, t.length() - 1);
            } else if (t.endsWith("m")) {
                mult = 1_000_000;
                t = t.substring(0, t.length() - 1);
            }
            return Long.parseLong(t) * mult;
        } catch (Exception e) {
            return -1L;
        }
    }
}
