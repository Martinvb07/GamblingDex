package com.gamblingdex.modules.tower;

import com.gamblingdex.modules.GameModule;
import com.gamblingdex.modules.SimpleStations;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;

/**
 * Tower: como Mines pero vertical. En cada piso hay varias puertas y detrás de
 * alguna hay una trampa; elegir una segura sube un piso y multiplica la apuesta.
 * Te retiras cuando quieras. Se juega en estaciones de un bloque con menú
 * (/gdx station set tower).
 *
 * Multiplicador tras k pisos = (1 - edge) x (puertas / (puertas - trampas))^k
 * → devolución esperada (1 - edge) sin importar cuándo te retires.
 */
public class TowerModule extends GameModule {

    /** Dificultad: puertas por piso y cuántas tienen trampa. */
    record Difficulty(String id, String name, int doors, int traps) {
    }

    private SimpleStations stations;
    private TowerMenu menu;

    @Override
    public String id() {
        return "tower";
    }

    @Override
    public String displayName() {
        return "Tower";
    }

    @Override
    public List<String> aliases() {
        return List.of("torre");
    }

    @Override
    public void enable() {
        menu = new TowerMenu(this);
        listen(menu);
        stations = new SimpleStations(this,
                () -> config().getString("station_holo", "&b&lTOWER\n&7Click para jugar"));
        stations.load();
        listen(new Events());
        runTimer(stations::refreshAll, 40L, 100L);
    }

    @Override
    public void disable() {
        if (menu != null)
            menu.closeAll();
        if (stations != null)
            stations.removeHolos();
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>();
        l.add("&6&lTower");
        l.add("&8• &fClick derecho&7 a la estación: elige puertas y sube pisos; retírate cuando quieras");
        if (admin)
            l.add("&8• &e/gdx station set tower &7- Crear estación (mirando un bloque)");
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        player.sendMessage(msg("use_station", "&7Tower se juega en las estaciones: &fclick derecho&7 para abrir la torre."));
        if (isAdmin(player))
            player.sendMessage(msg("admin_hint", "&7Admin: &f/gdx station set tower &7(mirando un bloque) | &f/gdx station remove"));
        return true;
    }

    // ------------------------------------------------------------------
    // Estaciones
    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("tower", "torre");
    }

    @Override
    public void createStation(Player player, Block target, String[] args) {
        if (!stations.add(target)) {
            player.sendMessage(msg("exists", "&cEse bloque ya es una estación de Tower."));
            return;
        }
        player.sendMessage(msg("station_created", "&aEstación de Tower creada. &7Click derecho al bloque para jugar."));
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        if (!stations.remove(target))
            return false;
        player.sendMessage(msg("station_removed", "&aEstación de Tower eliminada."));
        return true;
    }

    @Override
    public List<String> stationListLines() {
        return List.of("&8- &6Tower&7: &f" + stations.size());
    }

    // ------------------------------------------------------------------
    // Reglas
    // ------------------------------------------------------------------

    int floors() {
        return Math.max(3, Math.min(12, config().getInt("floors", 8)));
    }

    double edge() {
        return Math.max(0.0, Math.min(50.0, config().getDouble("house_edge_percent", 3.0))) / 100.0;
    }

    List<Difficulty> difficulties() {
        List<Difficulty> out = new ArrayList<>();
        ConfigurationSection sec = config().getConfigurationSection("difficulties");
        if (sec == null || sec.getKeys(false).isEmpty())
            sec = config().getDefaults() == null ? null : config().getDefaults().getConfigurationSection("difficulties");
        if (sec != null)
            for (String id : sec.getKeys(false)) {
                int doors = Math.max(2, Math.min(4, sec.getInt(id + ".doors", 3)));
                int traps = Math.max(1, Math.min(doors - 1, sec.getInt(id + ".traps", 1)));
                out.add(new Difficulty(id, sec.getString(id + ".name", id), doors, traps));
            }
        if (out.isEmpty())
            out.add(new Difficulty("normal", "&eNormal", 3, 1));
        return out;
    }

    /** Multiplicador después de subir {@code floors} pisos. */
    double multiplier(Difficulty d, int floors) {
        if (floors <= 0)
            return 1.0;
        double m = (1.0 - edge()) * Math.pow(d.doors() / (double) (d.doors() - d.traps()), floors);
        return Math.floor(m * 100.0) / 100.0;
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private final class Events implements Listener {

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            if (!stations.contains(e.getClickedBlock()))
                return;
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND && e.getAction() == Action.RIGHT_CLICK_BLOCK && isOpenFor(e.getPlayer()))
                menu.open(e.getPlayer());
        }

        @EventHandler
        public void onBreak(BlockBreakEvent e) {
            if (stations.contains(e.getBlock()))
                e.setCancelled(true);
        }
    }
}
