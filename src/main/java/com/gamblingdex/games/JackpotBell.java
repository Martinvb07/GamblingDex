package com.gamblingdex.games;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.items.GameItemType;
import com.gamblingdex.modules.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Campana del jackpot: cuando el pozo de los slots o de la ruleta pasa de un
 * mínimo, suena una campana y sale un aviso, solo para los que estén cerca de
 * las estaciones de slots o de las mesas de ruleta. Mientras siga por encima,
 * vuelve a sonar cada {@code repeat_minutes}.
 *
 * Config: slots.yml → jackpot.bell, ruleta.yml → jackpot.bell.
 */
public final class JackpotBell {

    private record Pot(String base, boolean enabledByDefault, LongSupplier amount, Supplier<List<Location>> places,
            String defMessage) {
    }

    private final GamblingDexPlugin plugin;
    private final List<Pot> pots = new ArrayList<>();
    private final Map<String, Long> lastRing = new HashMap<>();

    public JackpotBell(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        pots.add(new Pot("games.slots.jackpot", true,
                () -> plugin.getSlotsController() == null ? 0L : plugin.getSlotsController().getJackpot(),
                () -> plugin.getStationManager() == null ? List.of() : plugin.getStationManager().locationsOf(GameItemType.SLOTS),
                "&6&l🔔 JACKPOT &8» &7¡El pozo de los slots ya va en &6&l{amount}&7 fichas! ¿Te lo llevas?"));
        pots.add(new Pot("roulette_world.jackpot", true,
                () -> plugin.getWorldRouletteManager() == null ? 0L : plugin.getWorldRouletteManager().getJackpot(),
                () -> plugin.getWorldRouletteManager() == null ? List.of() : plugin.getWorldRouletteManager().getTableCenters(),
                "&6&l🔔 JACKPOT &8» &7¡El pozo de la ruleta ya va en &6&l{amount}&7 fichas!"));
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 200L); // cada 10 s
    }

    private void tick() {
        FileConfiguration cfg = plugin.getConfig();
        long now = System.currentTimeMillis();
        for (Pot pot : pots) {
            String b = pot.base() + ".bell";
            if (!cfg.getBoolean(pot.base() + ".enabled", pot.enabledByDefault()) || !cfg.getBoolean(b + ".enabled", true))
                continue;
            long amount = pot.amount().getAsLong();
            long min = Math.max(1, cfg.getLong(b + ".min_pot", 300000));
            if (amount < min) {
                lastRing.remove(pot.base()); // bajó (se lo ganaron): vuelve a sonar al pasar el mínimo
                continue;
            }
            Long last = lastRing.get(pot.base());
            long repeat = Math.max(0, cfg.getLong(b + ".repeat_minutes", 30)) * 60_000L;
            if (last != null && (repeat == 0 || now - last < repeat))
                continue;
            if (ring(cfg, b, amount, pot))
                lastRing.put(pot.base(), now); // si no había nadie cerca, se intenta otra vez en 10 s
        }
    }

    private boolean ring(FileConfiguration cfg, String b, long amount, Pot pot) {
        double r = Math.max(2, cfg.getDouble(b + ".radius", 20));
        Set<Player> near = new HashSet<>();
        for (Location l : pot.places().get()) {
            if (l.getWorld() == null)
                continue;
            for (Player p : l.getWorld().getPlayers())
                if (p.getLocation().distanceSquared(l) <= r * r)
                    near.add(p);
        }
        if (near.isEmpty())
            return false;
        String msg = plugin.color(cfg.getString(b + ".message", pot.defMessage()).replace("{amount}", GameModule.units(amount)));
        String sound = cfg.getString(b + ".sound", "block.bell.use");
        float vol = (float) cfg.getDouble(b + ".volume", 1.0);
        int times = Math.max(1, Math.min(10, cfg.getInt(b + ".rings", 3)));
        for (Player p : near)
            p.sendMessage(msg);
        if (sound == null || sound.isBlank())
            return true;
        for (int i = 0; i < times; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p : near)
                    if (p.isOnline())
                        try {
                            p.playSound(p.getLocation(), sound.toLowerCase(Locale.ROOT), vol, 1.0f);
                        } catch (Exception ignored) {
                        }
            }, i * 10L);
        }
        return true;
    }
}
