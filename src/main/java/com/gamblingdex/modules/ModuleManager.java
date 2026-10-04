package com.gamblingdex.modules;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.gui.AmountPickerMenu;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Busca dentro del jar todas las clases {@code com.gamblingdex.modules.*.*Module}
 * que extienden {@link GameModule} y las activa. Así cada minijuego es una
 * carpeta independiente: agregar uno nuevo no toca archivos compartidos.
 */
public class ModuleManager {

    private static final String PACKAGE_PATH = "com/gamblingdex/modules/";

    private final GamblingDexPlugin plugin;
    private final Map<String, GameModule> modules = new LinkedHashMap<>();
    private final Set<String> enabled = new HashSet<>();

    public ModuleManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(new AmountPickerMenu.Listener(), plugin);
        discover();
    }

    private void discover() {
        File jar = plugin.getPluginJar();
        if (jar == null || !jar.isFile()) {
            plugin.getLogger().warning("[Módulos] No se encontró el jar del plugin; no hay minijuegos extra.");
            return;
        }
        List<String> classNames = new ArrayList<>();
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName();
                // Solo subpaquetes: com/gamblingdex/modules/<juego>/XxxModule.class
                if (!n.startsWith(PACKAGE_PATH) || !n.endsWith("Module.class") || n.contains("$"))
                    continue;
                if (n.substring(PACKAGE_PATH.length()).indexOf('/') < 0)
                    continue;
                classNames.add(n.substring(0, n.length() - ".class".length()).replace('/', '.'));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Módulos] Error leyendo el jar: " + e.getMessage());
            return;
        }
        Collections.sort(classNames);

        for (String cn : classNames) {
            try {
                Class<?> c = Class.forName(cn, true, plugin.getClass().getClassLoader());
                if (!GameModule.class.isAssignableFrom(c) || Modifier.isAbstract(c.getModifiers()))
                    continue;
                GameModule m = (GameModule) c.getDeclaredConstructor().newInstance();
                m.init(plugin);
                modules.put(m.id().toLowerCase(Locale.ROOT), m);
            } catch (Throwable t) {
                plugin.getLogger().warning("[Módulos] No se pudo cargar " + cn + ": " + t);
            }
        }
    }

    public void enableAll() {
        for (GameModule m : modules.values()) {
            if (!m.isEnabledInConfig())
                continue;
            try {
                m.enable();
                enabled.add(m.id());
                plugin.getLogger().info("[Módulos] " + m.displayName() + " habilitado.");
            } catch (Throwable t) {
                plugin.getLogger().warning("[Módulos] Error habilitando " + m.id() + ": " + t);
                t.printStackTrace();
            }
        }
    }

    public void disableAll() {
        for (GameModule m : modules.values()) {
            if (!enabled.remove(m.id()))
                continue;
            try {
                m.shutdown();
            } catch (Throwable t) {
                plugin.getLogger().warning("[Módulos] Error apagando " + m.id() + ": " + t);
            }
        }
    }

    /** /gdx reload: recarga configs; enciende/apaga según "enabled". */
    public void reloadAll() {
        for (GameModule m : modules.values()) {
            try {
                m.loadConfig();
                boolean want = m.isEnabledInConfig();
                boolean is = enabled.contains(m.id());
                if (want && !is) {
                    m.enable();
                    enabled.add(m.id());
                } else if (!want && is) {
                    m.shutdown();
                    enabled.remove(m.id());
                } else if (is) {
                    m.reload();
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[Módulos] Error recargando " + m.id() + ": " + t);
            }
        }
    }

    /** Módulo activo que responde a /gdx &lt;sub&gt;, o null. */
    public GameModule byCommand(String sub) {
        if (sub == null)
            return null;
        String s = sub.toLowerCase(Locale.ROOT);
        for (GameModule m : modules.values()) {
            if (!enabled.contains(m.id()))
                continue;
            if (m.id().equalsIgnoreCase(s))
                return m;
            for (String a : m.aliases()) {
                if (a.equalsIgnoreCase(s))
                    return m;
            }
        }
        return null;
    }

    /** Módulo (activo o no) por id o alias, o null. */
    public GameModule find(String name) {
        if (name == null)
            return null;
        for (GameModule m : modules.values()) {
            if (m.id().equalsIgnoreCase(name))
                return m;
            for (String a : m.aliases())
                if (a.equalsIgnoreCase(name))
                    return m;
        }
        return null;
    }

    /**
     * Apaga y vuelve a encender un módulo: su disable() devuelve las apuestas en
     * curso y queda limpio (lo usa el modo mantenimiento).
     */
    public void restart(String id) {
        GameModule m = modules.get(id.toLowerCase(Locale.ROOT));
        if (m == null || !enabled.contains(m.id()))
            return;
        try {
            m.shutdown();
        } catch (Throwable t) {
            plugin.getLogger().warning("[Módulos] Error apagando " + m.id() + ": " + t);
        }
        try {
            m.enable();
        } catch (Throwable t) {
            enabled.remove(m.id());
            plugin.getLogger().warning("[Módulos] Error encendiendo " + m.id() + ": " + t);
        }
    }

    public boolean dispatch(Player player, String sub, String[] fullArgs) {
        GameModule m = byCommand(sub);
        if (m == null)
            return false;
        if (!m.isAdmin(player) && !m.isOpenFor(player))
            return true;
        String[] rest = Arrays.copyOfRange(fullArgs, 1, fullArgs.length);
        try {
            m.onCommand(player, rest);
        } catch (Throwable t) {
            player.sendMessage(plugin.color("&cError en " + m.displayName() + ". Revisa la consola."));
            plugin.getLogger().warning("[" + m.id() + "] Error en comando: " + t);
            t.printStackTrace();
        }
        return true;
    }

    /** Módulo activo que maneja /gdx station set &lt;tipo&gt;, o null. */
    public GameModule byStationType(String type) {
        if (type == null)
            return null;
        for (GameModule m : modules.values()) {
            if (!enabled.contains(m.id()))
                continue;
            for (String t : m.stationTypes()) {
                if (t.equalsIgnoreCase(type))
                    return m;
            }
        }
        return null;
    }

    /** Usos de /gdx station set de los módulos activos (ej. "crash", "race <distance> <lanes>"). */
    public List<String> stationUsages() {
        List<String> out = new ArrayList<>();
        for (GameModule m : modules.values()) {
            if (enabled.contains(m.id()) && !m.stationTypes().isEmpty())
                out.add(m.stationUsage());
        }
        return out;
    }

    /** /gdx station remove: true si algún módulo tenía una estación en ese bloque. */
    public boolean removeStation(Player player, Block target) {
        for (GameModule m : modules.values()) {
            if (!enabled.contains(m.id()))
                continue;
            try {
                if (m.removeStation(player, target))
                    return true;
            } catch (Throwable t) {
                plugin.getLogger().warning("[" + m.id() + "] Error quitando estación: " + t);
            }
        }
        return false;
    }

    public List<String> stationListLines() {
        List<String> out = new ArrayList<>();
        for (GameModule m : modules.values()) {
            if (!enabled.contains(m.id()))
                continue;
            for (String l : m.stationListLines())
                out.add(plugin.color(l));
        }
        return out;
    }

    public List<String> helpLines(boolean admin) {
        List<String> out = new ArrayList<>();
        for (GameModule m : modules.values()) {
            if (!enabled.contains(m.id()))
                continue;
            for (String l : m.helpLines(admin))
                out.add(plugin.color(l));
        }
        return out;
    }

    public Collection<GameModule> getModules() {
        return Collections.unmodifiableCollection(modules.values());
    }
}
