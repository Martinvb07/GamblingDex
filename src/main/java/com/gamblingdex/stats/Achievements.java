package com.gamblingdex.stats;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.gui.Icons;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Logros del casino (achievements.yml): se comprueban cada vez que se resuelve
 * una apuesta ({@link #onPlay}) o cuando un juego avisa de un evento especial
 * ({@link #trigger}). Dan fichas y/o ejecutan comandos. Se ven con /gdx achievements.
 */
public class Achievements implements Listener {

    private record Def(String id, String name, String description, Material icon, String type, String game,
            String event, long amount, long reward, List<String> commands) {
    }

    private static final class MenuHolder implements InventoryHolder {
        final int page;

        MenuHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final GamblingDexPlugin plugin;
    private final File configFile;
    private final File dataFile;
    private YamlConfiguration config;
    private final List<Def> defs = new ArrayList<>();
    private final Map<UUID, Set<String>> unlocked = new HashMap<>();
    private final Map<UUID, Set<String>> gamesPlayed = new HashMap<>();
    private boolean dirty;
    private final BukkitTask saveTask;

    public Achievements(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "achievements.yml");
        this.dataFile = new File(plugin.getDataFolder(), "achievements_data.yml");
        reload();
        loadData();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty)
                save();
        }, 20L * 60, 20L * 60);
    }

    public void reload() {
        if (!configFile.exists() && plugin.getResource("achievements.yml") != null)
            plugin.saveResource("achievements.yml", false);
        config = YamlConfiguration.loadConfiguration(configFile);
        var in = plugin.getResource("achievements.yml");
        if (in != null)
            config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        defs.clear();
        // Solo los de la sección del archivo del servidor (los defaults no deben revivir logros borrados)
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(configFile).getConfigurationSection("achievements");
        if (sec == null)
            sec = config.getConfigurationSection("achievements");
        if (sec == null)
            return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection a = sec.getConfigurationSection(id);
            if (a == null)
                continue;
            Material icon = Material.matchMaterial(a.getString("icon", "PAPER"));
            defs.add(new Def(id, a.getString("name", id), a.getString("description", ""),
                    icon == null || !icon.isItem() ? Material.PAPER : icon,
                    a.getString("type", "event").toLowerCase(Locale.ROOT), a.getString("game", ""),
                    a.getString("event", ""), a.getLong("amount", 0), a.getLong("reward", 0),
                    a.getStringList("commands")));
        }
    }

    private boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    private String text(String key, String def) {
        return config.getString("messages." + key, def);
    }

    // ------------------------------------------------------------------
    // Comprobaciones
    // ------------------------------------------------------------------

    /** Se llama desde {@link GameStats#record} con cada apuesta resuelta. */
    public void onPlay(UUID player, String game, long wager, long payout, long totalRounds) {
        if (!enabled() || player == null)
            return;
        Set<String> games = gamesPlayed.computeIfAbsent(player, k -> new HashSet<>());
        if (game != null && games.add(game))
            dirty = true;
        long net = payout - wager;
        double mult = wager > 0 ? payout / (double) wager : 0;
        for (Def d : defs) {
            if (has(player, d.id))
                continue;
            boolean gameOk = d.game.isEmpty() || d.game.equalsIgnoreCase(game);
            boolean ok = switch (d.type) {
                case "rounds" -> totalRounds >= d.amount;
                case "win" -> gameOk && net >= Math.max(1, d.amount);
                case "wager" -> gameOk && wager >= Math.max(1, d.amount);
                case "multiplier" -> gameOk && wager > 0 && mult >= d.amount;
                case "game_win" -> gameOk && net > 0;
                case "games" -> games.size() >= d.amount;
                default -> false;
            };
            if (ok)
                unlock(player, d);
        }
    }

    /** Evento especial de un juego (blackjack_natural, roulette_jackpot, mines_clear...). */
    public void trigger(UUID player, String event, long amount) {
        if (!enabled() || player == null)
            return;
        for (Def d : defs)
            if (d.type.equals("event") && d.event.equalsIgnoreCase(event) && amount >= d.amount && !has(player, d.id))
                unlock(player, d);
    }

    public boolean has(UUID player, String id) {
        Set<String> s = unlocked.get(player);
        return s != null && s.contains(id);
    }

    public int count(UUID player) {
        Set<String> s = unlocked.get(player);
        if (s == null)
            return 0;
        int n = 0;
        for (Def d : defs)
            if (s.contains(d.id))
                n++;
        return n;
    }

    public int total() {
        return defs.size();
    }

    private void unlock(UUID player, Def d) {
        unlocked.computeIfAbsent(player, k -> new HashSet<>()).add(d.id);
        dirty = true;
        Player p = Bukkit.getPlayer(player);
        String name = p != null ? p.getName() : Bukkit.getOfflinePlayer(player).getName();
        if (p != null) {
            p.sendMessage(plugin.color(text("unlocked", "&6&l✦ LOGRO &8» &e{name} &7- {description}")
                    .replace("{name}", d.name).replace("{description}", d.description)));
            if (d.reward > 0)
                p.sendMessage(plugin.color(text("reward", "&7Recompensa: &e+{reward} fichas")
                        .replace("{reward}", fmt(d.reward))));
            p.sendTitle(plugin.color("&6&l✦ LOGRO ✦"), plugin.color(d.name), 5, 50, 15);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
        if (d.reward > 0)
            TokenWallet.give(player, d.reward);
        for (String cmd : d.commands)
            if (name != null && !cmd.isBlank())
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", name));
        if (config.getBoolean("announce", true) && name != null) {
            String msg = plugin.color(text("broadcast", "&6&l✦ &e{player} &7desbloqueó el logro &e{name}&7.")
                    .replace("{player}", name).replace("{name}", d.name));
            for (Player o : Bukkit.getOnlinePlayers())
                if (!o.getUniqueId().equals(player))
                    o.sendMessage(msg);
        }
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    public void open(Player p, int page) {
        int pages = Math.max(1, (defs.size() + 44) / 45);
        page = Math.max(0, Math.min(pages - 1, page));
        Inventory inv = Bukkit.createInventory(new MenuHolder(page), 54,
                plugin.color(text("menu_title", "&8&l✦ &6&lLOGROS &8&l✦")));
        UUID id = p.getUniqueId();
        for (int i = 0; i < 45 && page * 45 + i < defs.size(); i++) {
            Def d = defs.get(page * 45 + i);
            boolean got = has(id, d.id);
            List<String> lore = new ArrayList<>();
            lore.add("&7" + d.description);
            lore.add("");
            if (d.reward > 0)
                lore.add("&7Recompensa: &e" + fmt(d.reward) + " fichas");
            lore.add(got ? "&a✔ Desbloqueado" : "&c✘ Bloqueado");
            inv.setItem(i, Icons.of(got ? d.icon : Material.GRAY_DYE, 1, got ? d.name : "&8" + org.bukkit.ChatColor.stripColor(plugin.color(d.name)),
                    lore, got));
        }
        int n = count(id);
        inv.setItem(49, Icons.of(Material.NETHER_STAR, 1, "&6&lTus logros: &e" + n + "&7/&e" + defs.size(),
                List.of("&7Completado: &f" + (defs.isEmpty() ? 0 : n * 100 / defs.size()) + "%"), true));
        if (page > 0)
            inv.setItem(45, Icons.of(Material.ARROW, "&ePágina anterior", null));
        if (page < pages - 1)
            inv.setItem(53, Icons.of(Material.ARROW, "&eSiguiente página", null));
        for (int i = 45; i < 54; i++)
            if (inv.getItem(i) == null)
                inv.setItem(i, Icons.of(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder h))
            return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p))
            return;
        if (e.getRawSlot() == 45 && h.page > 0)
            open(p, h.page - 1);
        else if (e.getRawSlot() == 53)
            open(p, h.page + 1);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder)
            e.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // Archivo
    // ------------------------------------------------------------------

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null)
            return;
        for (String k : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                unlocked.put(id, new HashSet<>(sec.getStringList(k + ".unlocked")));
                gamesPlayed.put(id, new HashSet<>(sec.getStringList(k + ".games")));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        Set<UUID> ids = new HashSet<>(unlocked.keySet());
        ids.addAll(gamesPlayed.keySet());
        for (UUID id : ids) {
            y.set("players." + id + ".unlocked", new ArrayList<>(unlocked.getOrDefault(id, Set.of())));
            y.set("players." + id + ".games", new ArrayList<>(gamesPlayed.getOrDefault(id, Set.of())));
        }
        try {
            y.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar achievements_data.yml: " + e.getMessage());
        }
    }

    public void shutdown() {
        saveTask.cancel();
        save();
    }

    private static String fmt(long v) {
        return java.text.NumberFormat.getInstance(Locale.forLanguageTag("es-ES")).format(v);
    }
}
