package com.gamblingdex.modules.daily;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.gui.Icons;
import com.gamblingdex.modules.GameModule;
import com.gamblingdex.modules.SimpleStations;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * Bonos: estación con un menú de recompensas (diaria, semanal y mensual por
 * defecto, configurables en daily.yml → rewards). Cada una tiene su propio
 * tiempo de espera contado desde que se reclamó (24 h, 7 días, 30 días).
 *
 * Las fichas de bono no se pueden vender por dinero hasta apostar N veces lo
 * reclamado (wagering.multiplier, x3 por defecto); ver {@link com.gamblingdex.economy.BonusLock}.
 */
public class DailyModule extends GameModule {

    private static final int[] SLOTS_3 = { 11, 13, 15 };

    private SimpleStations stations;
    private final Map<UUID, Map<String, Long>> lastClaim = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();

    private record Reward(String id, String name, Material icon, long amount, long cooldownMs, boolean streak) {
    }

    private static final class MenuHolder implements InventoryHolder {
        final Map<Integer, String> slots = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    @Override
    public String id() {
        return "daily";
    }

    @Override
    public String displayName() {
        return "Bonos";
    }

    @Override
    public List<String> aliases() {
        return List.of("bono", "bonos", "diario", "bonus");
    }

    @Override
    public void enable() {
        lastClaim.clear();
        streak.clear();
        YamlConfiguration d = loadData();
        ConfigurationSection sec = d.getConfigurationSection("players");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    Map<String, Long> m = new HashMap<>();
                    ConfigurationSection c = sec.getConfigurationSection(k + ".claims");
                    if (c != null)
                        for (String r : c.getKeys(false))
                            m.put(r, c.getLong(r));
                    lastClaim.put(id, m);
                    streak.put(id, sec.getInt(k + ".streak", 0));
                } catch (Exception ignored) {
                }
            }
        }
        stations = new SimpleStations(this,
                () -> config().getString("station_holo", "&a&lBONOS\n&7Click para reclamar"));
        stations.load();
        listen(new Events());
        runTimer(stations::refreshAll, 40L, 100L);
    }

    @Override
    public void disable() {
        if (stations != null)
            stations.removeHolos();
        save();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)
                p.closeInventory();
    }

    private void save() {
        YamlConfiguration d = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Long>> e : lastClaim.entrySet()) {
            for (Map.Entry<String, Long> c : e.getValue().entrySet())
                d.set("players." + e.getKey() + ".claims." + c.getKey(), c.getValue());
            d.set("players." + e.getKey() + ".streak", streak.getOrDefault(e.getKey(), 0));
        }
        saveData(d);
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>();
        l.add("&6&lBonos");
        l.add("&8• &fClick derecho&7 a la estación: bono diario, semanal y mensual");
        if (admin)
            l.add("&8• &e/gdx station set daily &7- Crear estación (mirando un bloque)");
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        player.sendMessage(msg("use_station", "&7Los bonos se reclaman en su estación del casino (&fclick derecho&7)."));
        if (isAdmin(player))
            player.sendMessage(msg("admin_hint", "&7Admin: &f/gdx station set daily &7(mirando un bloque) | &f/gdx station remove"));
        return true;
    }

    // ------------------------------------------------------------------
    // Estaciones
    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("daily", "bono", "bonos");
    }

    @Override
    public void createStation(Player player, Block target, String[] args) {
        if (!stations.add(target)) {
            player.sendMessage(msg("exists", "&cEse bloque ya es una estación de bonos."));
            return;
        }
        player.sendMessage(msg("station_created", "&aEstación de bonos creada."));
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        if (!stations.remove(target))
            return false;
        player.sendMessage(msg("station_removed", "&aEstación de bonos eliminada."));
        return true;
    }

    @Override
    public List<String> stationListLines() {
        return List.of("&8- &6Bonos&7: &f" + stations.size());
    }

    // ------------------------------------------------------------------
    // Recompensas
    // ------------------------------------------------------------------

    private List<Reward> rewards() {
        List<Reward> out = new ArrayList<>();
        ConfigurationSection sec = config().getConfigurationSection("rewards");
        if (sec == null)
            return out;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null || r.getLong("amount", 0) <= 0)
                continue;
            Material icon = Material.matchMaterial(r.getString("icon", "SUNFLOWER"));
            out.add(new Reward(id, r.getString("name", id), icon == null || !icon.isItem() ? Material.SUNFLOWER : icon,
                    r.getLong("amount"), Math.max(1, r.getLong("cooldown_hours", 24)) * 3_600_000L,
                    r.getBoolean("streak", false)));
        }
        return out;
    }

    private int currentStreak(UUID id, Reward r) {
        Long last = lastClaim.getOrDefault(id, Map.of()).get(r.id());
        // La racha sigue si reclama antes de que pase el doble del tiempo de espera
        if (last == null || System.currentTimeMillis() - last > r.cooldownMs() * 2)
            return 0;
        return streak.getOrDefault(id, 0);
    }

    private long amountFor(Player p, Reward r, int streakDays) {
        double amount = r.amount();
        if (r.streak()) {
            int max = Math.max(1, config().getInt("max_streak", 7));
            int s = Math.min(max, streakDays + 1);
            amount += amount * (s - 1) * Math.max(0, config().getDouble("streak_bonus_percent", 10)) / 100.0;
        }
        return (long) Math.floor(amount);
    }

    private long leftMs(UUID id, Reward r) {
        Long last = lastClaim.getOrDefault(id, Map.of()).get(r.id());
        return last == null ? 0 : Math.max(0, last + r.cooldownMs() - System.currentTimeMillis());
    }

    private static String time(long ms) {
        long s = ms / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0)
            return d + "d " + h + "h";
        if (h > 0)
            return h + "h " + m + "m";
        return m + "m " + (s % 60) + "s";
    }

    private double wagerMult() {
        return config().getBoolean("wagering.enabled", true) ? Math.max(0, config().getDouble("wagering.multiplier", 3)) : 0;
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    private void open(Player p) {
        MenuHolder h = new MenuHolder();
        Inventory inv = Bukkit.createInventory(h, 27, color(config().getString("menu_title", "&8&l✦ &a&lBONOS &8&l✦")));
        for (int i = 0; i < 27; i++)
            inv.setItem(i, Icons.of(i < 9 || i >= 18 ? Material.LIME_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE, " ", null));

        List<Reward> list = rewards();
        int[] slots = list.size() == 3 ? SLOTS_3 : spread(list.size());
        UUID id = p.getUniqueId();
        double wm = wagerMult();
        for (int i = 0; i < list.size() && i < slots.length; i++) {
            Reward r = list.get(i);
            long left = leftMs(id, r);
            int st = currentStreak(id, r);
            long amount = amountFor(p, r, st);
            List<String> lore = new ArrayList<>();
            lore.add("&7Recompensa: &e" + units(amount) + " fichas");
            if (r.streak())
                lore.add("&7Racha: &f" + st + " &7(+" + (int) config().getDouble("streak_bonus_percent", 10) + "% por día seguido)");
            lore.add("&7Cada: &f" + time(r.cooldownMs()));
            if (wm > 0)
                lore.add("&8Para venderlas: apuesta x" + trim(wm) + " lo reclamado");
            lore.add("");
            lore.add(left > 0 ? "&cDisponible en &f" + time(left) : "&a&l¡Click para reclamar!");
            inv.setItem(slots[i], Icons.of(left > 0 ? Material.GRAY_DYE : r.icon(), 1, r.name(), lore, left <= 0));
            h.slots.put(slots[i], r.id());
        }

        // Tu estado
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(p);
            sm.setDisplayName(color("&e&l" + p.getName()));
            List<String> lore = new ArrayList<>();
            lore.add(color("&7Fichas: &e" + units(TokenWallet.balance(p))));
            var lock = plugin.getBonusLock();
            if (lock != null && lock.locked(id) > 0) {
                lore.add(color("&7Fichas de bono: &6" + units(lock.locked(id)) + " &8(no se pueden vender)"));
                lore.add(color("&7Apuesta &e" + units(lock.remaining(id)) + " &7más para liberarlas"));
            } else {
                lore.add(color("&aNo tienes fichas de bono bloqueadas"));
            }
            sm.setLore(lore);
            head.setItemMeta(sm);
        }
        inv.setItem(22, head);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
    }

    private static int[] spread(int n) {
        int[] all = { 10, 11, 12, 13, 14, 15, 16 };
        int c = Math.min(7, n);
        int start = (7 - c) / 2;
        return Arrays.copyOfRange(all, start, start + c);
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private void claim(Player p, String rewardId) {
        if (!isOpenFor(p))
            return;
        Reward r = null;
        for (Reward x : rewards())
            if (x.id().equals(rewardId))
                r = x;
        if (r == null)
            return;
        UUID id = p.getUniqueId();
        long left = leftMs(id, r);
        if (left > 0) {
            p.sendMessage(msg("already", "&cYa reclamaste {reward}&c. Vuelve en &f{time}&c.",
                    "reward", color(r.name()), "time", time(left)));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        int st = currentStreak(id, r);
        long amount = amountFor(p, r, st);
        lastClaim.computeIfAbsent(id, k -> new HashMap<>()).put(r.id(), System.currentTimeMillis());
        if (r.streak())
            streak.put(id, Math.min(Math.max(1, config().getInt("max_streak", 7)), st + 1));
        save();
        TokenWallet.give(id, amount);
        double wm = wagerMult();
        var lock = plugin.getBonusLock();
        if (lock != null && wm > 0)
            lock.addBonus(id, amount, wm);
        p.sendMessage(msg("claimed", "&a&l✦ BONO &8» &7Recibiste &e{amount} &7fichas ({reward}&7).",
                "amount", units(amount), "reward", color(r.name()), "streak", String.valueOf(st + 1)));
        if (wm > 0)
            p.sendMessage(msg("wagering", "&8Estas fichas se pueden vender cuando hayas apostado x{mult} lo reclamado.",
                    "mult", trim(wm)));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        for (String n : new String[] { "HAPPY_VILLAGER", "VILLAGER_HAPPY" }) { // cambió de nombre en 1.20.5
            try {
                p.getWorld().spawnParticle(Particle.valueOf(n), p.getLocation().add(0, 1.2, 0), 20, 0.4, 0.5, 0.4);
                break;
            } catch (IllegalArgumentException ignored) {
            }
        }
        GamblingDexPlugin.achievement(id, "daily_bonus");
        open(p);
    }

    private final class Events implements Listener {

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            if (!stations.contains(e.getClickedBlock()))
                return;
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND && e.getAction() == Action.RIGHT_CLICK_BLOCK && isOpenFor(e.getPlayer()))
                open(e.getPlayer());
        }

        @EventHandler
        public void onBreak(BlockBreakEvent e) {
            if (stations.contains(e.getBlock()))
                e.setCancelled(true);
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof MenuHolder h))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory())
                return;
            String r = h.slots.get(e.getRawSlot());
            if (r != null)
                claim(p, r);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof MenuHolder)
                e.setCancelled(true);
        }
    }
}
