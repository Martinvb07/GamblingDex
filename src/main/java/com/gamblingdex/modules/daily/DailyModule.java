package com.gamblingdex.modules.daily;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.modules.GameModule;
import com.gamblingdex.modules.SimpleStations;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Bono diario: fichas gratis una vez al día en una estación (click derecho).
 * Racha: cada día seguido suma un % extra (hasta max_streak). Los rangos con
 * permiso pueden recibir más (tiers). El día cambia a medianoche (stats.timezone).
 */
public class DailyModule extends GameModule {

    private SimpleStations stations;
    private final Map<UUID, LocalDate> last = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();

    @Override
    public String id() {
        return "daily";
    }

    @Override
    public String displayName() {
        return "Bono diario";
    }

    @Override
    public List<String> aliases() {
        return List.of("bono", "diario", "bonus");
    }

    @Override
    public void enable() {
        last.clear();
        streak.clear();
        YamlConfiguration d = loadData();
        ConfigurationSection sec = d.getConfigurationSection("players");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    last.put(id, LocalDate.parse(sec.getString(k + ".last", "")));
                    streak.put(id, sec.getInt(k + ".streak", 1));
                } catch (Exception ignored) {
                }
            }
        }
        stations = new SimpleStations(this,
                () -> config().getString("station_holo", "&a&lBONO DIARIO\n&7Click para reclamar"));
        stations.load();
        listen(new Events());
        runTimer(stations::refreshAll, 40L, 100L);
    }

    @Override
    public void disable() {
        if (stations != null)
            stations.removeHolos();
        save();
    }

    private void save() {
        YamlConfiguration d = new YamlConfiguration();
        for (Map.Entry<UUID, LocalDate> e : last.entrySet()) {
            d.set("players." + e.getKey() + ".last", e.getValue().toString());
            d.set("players." + e.getKey() + ".streak", streak.getOrDefault(e.getKey(), 1));
        }
        saveData(d);
    }

    private ZoneId zone() {
        String tz = plugin.getConfig().getString("stats.timezone", "");
        try {
            return tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>();
        l.add("&6&lBono diario");
        l.add("&8• &fClick derecho&7 a la estación: fichas gratis una vez al día");
        if (admin)
            l.add("&8• &e/gdx station set daily &7- Crear estación (mirando un bloque)");
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        player.sendMessage(msg("use_station", "&7El bono diario se reclama en su estación del casino (&fclick derecho&7)."));
        if (isAdmin(player))
            player.sendMessage(msg("admin_hint", "&7Admin: &f/gdx station set daily &7(mirando un bloque) | &f/gdx station remove"));
        return true;
    }

    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("daily", "bono");
    }

    @Override
    public void createStation(Player player, Block target, String[] args) {
        if (!stations.add(target)) {
            player.sendMessage(msg("exists", "&cEse bloque ya es una estación de bono diario."));
            return;
        }
        player.sendMessage(msg("station_created", "&aEstación de bono diario creada."));
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        if (!stations.remove(target))
            return false;
        player.sendMessage(msg("station_removed", "&aEstación de bono diario eliminada."));
        return true;
    }

    @Override
    public List<String> stationListLines() {
        return List.of("&8- &6Bono diario&7: &f" + stations.size());
    }

    // ------------------------------------------------------------------

    /** Fichas base del jugador: el tier con permiso que más da, o "amount". */
    private long baseAmount(Player p) {
        long best = Math.max(0, config().getLong("amount", 500));
        ConfigurationSection tiers = config().getConfigurationSection("tiers");
        if (tiers != null)
            for (String k : tiers.getKeys(false)) {
                String perm = tiers.getString(k + ".permission", "");
                if (!perm.isBlank() && p.hasPermission(perm))
                    best = Math.max(best, tiers.getLong(k + ".amount", 0));
            }
        return best;
    }

    private void claim(Player p) {
        if (!isOpenFor(p))
            return;
        UUID id = p.getUniqueId();
        ZonedDateTime now = ZonedDateTime.now(zone());
        LocalDate today = now.toLocalDate();
        LocalDate prev = last.get(id);
        if (today.equals(prev)) {
            Duration left = Duration.between(now, today.plusDays(1).atStartOfDay(zone()));
            p.sendMessage(msg("already", "&cYa reclamaste tu bono hoy. Vuelve en &f{time}&c.",
                    "time", left.toHours() + "h " + left.toMinutesPart() + "m"));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        int maxStreak = Math.max(1, config().getInt("max_streak", 7));
        int s = prev != null && prev.plusDays(1).equals(today) ? Math.min(maxStreak, streak.getOrDefault(id, 1) + 1) : 1;
        long base = baseAmount(p);
        long amount = base + (long) Math.floor(base * (s - 1) * Math.max(0, config().getDouble("streak_bonus_percent", 10)) / 100.0);
        if (amount <= 0)
            return;
        last.put(id, today);
        streak.put(id, s);
        save();
        TokenWallet.give(id, amount);
        p.sendMessage(msg("claimed", "&a&l✦ BONO DIARIO &8» &7Recibiste &e{amount} &7fichas. &8(racha: &f{streak} &8día(s))",
                "amount", units(amount), "streak", String.valueOf(s)));
        p.sendTitle(color("&a&lBONO DIARIO"), color("&e+" + units(amount) + " fichas"), 5, 40, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        for (String n : new String[] { "HAPPY_VILLAGER", "VILLAGER_HAPPY" }) { // cambió de nombre en 1.20.5
            try {
                p.getWorld().spawnParticle(Particle.valueOf(n), p.getLocation().add(0, 1.2, 0), 20, 0.4, 0.5, 0.4);
                break;
            } catch (IllegalArgumentException ignored) {
            }
        }
        GamblingDexPlugin.achievement(id, "daily_bonus");
    }

    private final class Events implements Listener {

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            if (!stations.contains(e.getClickedBlock()))
                return;
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND && e.getAction() == Action.RIGHT_CLICK_BLOCK)
                claim(e.getPlayer());
        }

        @EventHandler
        public void onBreak(BlockBreakEvent e) {
            if (stations.contains(e.getBlock()))
                e.setCancelled(true);
        }
    }
}
