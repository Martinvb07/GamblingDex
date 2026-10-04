package com.gamblingdex.economy;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;

/**
 * Fichas de bono (bono diario/semanal/mensual) que no se pueden vender por
 * dinero hasta apostar N veces lo reclamado (por defecto x3).
 *
 * <ul>
 * <li>{@code locked}: fichas de bono que aún no se pueden vender. Baja cuando el
 * jugador pierde apuestas (perdió fichas de bono).</li>
 * <li>{@code remaining}: lo que le falta apostar para liberarlas. Baja con cada
 * apuesta (lo apostado, gane o pierda). Al llegar a 0, todo queda libre.</li>
 * </ul>
 * Al vender en el cambio solo se puede vender {@code fichas - locked}.
 */
public class BonusLock {

    private static final class Entry {
        long locked;
        long remaining;
    }

    private final GamblingDexPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private boolean dirty;
    private final BukkitTask saveTask;

    public BonusLock(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bonus_lock.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec != null)
            for (String k : sec.getKeys(false)) {
                try {
                    Entry e = new Entry();
                    e.locked = sec.getLong(k + ".locked");
                    e.remaining = sec.getLong(k + ".remaining");
                    if (e.locked > 0 && e.remaining > 0)
                        entries.put(UUID.fromString(k), e);
                } catch (IllegalArgumentException ignored) {
                }
            }
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty)
                save();
        }, 20L * 60, 20L * 60);
    }

    /** Se reclamó un bono de {@code amount} fichas: quedan bloqueadas hasta apostar amount x multiplier. */
    public void addBonus(UUID player, long amount, double multiplier) {
        if (player == null || amount <= 0 || multiplier <= 0)
            return;
        Entry e = entries.computeIfAbsent(player, k -> new Entry());
        e.locked += amount;
        e.remaining += (long) Math.ceil(amount * multiplier);
        dirty = true;
    }

    /** Cada apuesta resuelta (lo llama GameStats.record). */
    public void onWager(UUID player, long wager, long payout) {
        Entry e = entries.get(player);
        if (e == null || wager <= 0)
            return;
        e.remaining -= wager;
        if (payout < wager)
            e.locked -= Math.min(e.locked, wager - payout); // perdió fichas (primero las de bono)
        dirty = true;
        if (e.remaining <= 0 || e.locked <= 0) {
            boolean unlocked = e.remaining <= 0 && e.locked > 0;
            entries.remove(player);
            Player p = Bukkit.getPlayer(player);
            if (p != null && unlocked)
                p.sendMessage(plugin.color("&a&l✦ BONO &8» &7¡Cumpliste la apuesta del bono! Tus fichas ya se pueden vender."));
        }
    }

    /** Fichas de bono que aún no se pueden vender. */
    public long locked(UUID player) {
        Entry e = entries.get(player);
        return e == null ? 0 : e.locked;
    }

    /** Lo que falta apostar para liberarlas. */
    public long remaining(UUID player) {
        Entry e = entries.get(player);
        return e == null ? 0 : Math.max(0, e.remaining);
    }

    /** Valor que el jugador puede vender ahora (sus fichas menos las de bono). */
    public long sellable(Player p) {
        return Math.max(0, TokenWallet.balance(p) - locked(p.getUniqueId()));
    }

    /** Aviso de por qué no puede vender más. */
    public String blockedMessage(Player p) {
        NumberFormat nf = NumberFormat.getInstance(Locale.forLanguageTag("es-ES"));
        return plugin.color("&cTienes &e" + nf.format(locked(p.getUniqueId()))
                + " &cfichas de bono bloqueadas. Apuesta &e" + nf.format(remaining(p.getUniqueId()))
                + " &cmás para poder venderlas.");
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            y.set("players." + e.getKey() + ".locked", e.getValue().locked);
            y.set("players." + e.getKey() + ".remaining", e.getValue().remaining);
        }
        try {
            y.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar bonus_lock.yml: " + ex.getMessage());
        }
    }

    public void shutdown() {
        saveTask.cancel();
        save();
    }
}
