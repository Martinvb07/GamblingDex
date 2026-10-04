package com.gamblingdex.modules.loteria;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.modules.GameModule;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Lotería del servidor: boletos a precio fijo, sorteos a horas fijas, premios
 * para los primeros lugares (sorteados según cantidad de boletos). Todo se
 * guarda en modules/loteria_data.yml para sobrevivir reinicios.
 */
public class LoteriaModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int[] BUY_SLOTS = { 10, 11, 12, 14 };
    private static final int[] BUY_AMOUNTS = { 1, 5, 10, 50 };

    private final Map<UUID, Integer> tickets = new LinkedHashMap<>();
    private final Map<UUID, Long> paid = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final List<String> history = new ArrayList<>();
    private final Set<Integer> remindersSent = new HashSet<>();
    private long soldUnits;
    private long rollover;
    private long nextDraw;

    private static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    @Override
    public String id() {
        return "loteria";
    }

    @Override
    public String displayName() {
        return "Lotería";
    }

    @Override
    public List<String> aliases() {
        return List.of("lottery", "lotto");
    }

    @Override
    public void enable() {
        load();
        if (nextDraw <= 0)
            nextDraw = computeNextDraw();
        listen(new Events());
        runTimer(this::tick, 20L, 20L);
    }

    @Override
    public void reload() {
        // Si cambiaron las horas de sorteo, recalcular (sin adelantar uno ya vencido).
        long fresh = computeNextDraw();
        if (fresh < nextDraw || nextDraw <= System.currentTimeMillis())
            nextDraw = fresh;
        remindersSent.clear();
        save();
    }

    @Override
    public void disable() {
        save();
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lLotería",
                "&8• &e/gdx lottery &7- Ver pozo y comprar boletos",
                "&8• &e/gdx lottery buy <amount> &7- Comprar boletos"));
        if (admin)
            l.add("&8• &e/gdx lottery draw &7- Sortear ahora");
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (args.length == 0) {
            openMenu(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "comprar", "buy" -> {
                int n = 1;
                if (args.length >= 2) {
                    try {
                        n = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                buy(player, n);
            }
            case "sortear", "draw" -> {
                if (!isAdmin(player))
                    return true;
                draw();
            }
            default -> player.sendMessage(msg("usage", "&cUso: /gdx lottery [buy <amount>]"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Boletos
    // ------------------------------------------------------------------

    private long price() {
        return Math.max(1L, config().getLong("ticket_price", 100L));
    }

    private int totalTickets() {
        int t = 0;
        for (int v : tickets.values())
            t += v;
        return t;
    }

    /** %gamblingdex_lottery_pot%, %gamblingdex_lottery_next%. */
    @Override
    public String placeholder(String key) {
        return switch (key) {
            case "pot" -> units(pot());
            case "next" -> timeLeft();
            default -> null;
        };
    }

    private long pot() {
        double cut = Math.max(0.0, Math.min(90.0, config().getDouble("house_cut_percent", 10.0))) / 100.0;
        return (long) Math.floor(soldUnits * (1.0 - cut)) + rollover;
    }

    private void buy(Player p, int n) {
        if (n <= 0)
            return;
        int max = config().getInt("max_tickets_per_player", 0);
        int have = tickets.getOrDefault(p.getUniqueId(), 0);
        if (max > 0 && have + n > max) {
            n = max - have;
            if (n <= 0) {
                p.sendMessage(msg("max_tickets", "&cSolo puedes tener &e{max}&c boletos por sorteo.",
                        "max", String.valueOf(max)));
                return;
            }
        }
        long cost = price() * n;
        if (!isOpenFor(p))
            return;
        if (!TokenWallet.take(p, cost)) {
            p.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Necesitas &e{price}&c y tienes &e{balance}&c.",
                    "price", units(cost), "balance", units(TokenWallet.balance(p))));
            return;
        }
        tickets.merge(p.getUniqueId(), n, Integer::sum);
        paid.merge(p.getUniqueId(), cost, Long::sum);
        names.put(p.getUniqueId(), p.getName());
        soldUnits += cost;
        save();
        p.sendMessage(msg("bought", "&aCompraste &e{amount} &aboleto(s) por &e{price}&a. Tienes &f{total}&a.",
                "amount", String.valueOf(n), "price", units(cost),
                "total", String.valueOf(tickets.get(p.getUniqueId()))));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.3f);
    }

    // ------------------------------------------------------------------
    // Sorteo
    // ------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        long left = nextDraw - now;
        if (left <= 0) {
            draw();
            return;
        }
        int minutesLeft = (int) Math.ceil(left / 60000.0);
        for (Object o : config().getList("reminders_minutes", List.of(60, 30, 10, 5, 1))) {
            if (!(o instanceof Number num))
                continue;
            int m = num.intValue();
            if (minutesLeft == m && remindersSent.add(m) && (totalTickets() > 0 || rollover > 0)) {
                String text = msg("reminder", "&6&lLotería &8» &7Sorteo en &f{time}&7. Pozo: &e{pot}&7.",
                        "time", m + " min", "pot", units(pot()));
                for (Player p : Bukkit.getOnlinePlayers())
                    p.sendMessage(text);
            }
        }
    }

    private void draw() {
        long pot = pot();
        int minPlayers = Math.max(1, config().getInt("min_players", 2));

        if (tickets.size() < minPlayers) {
            if (!tickets.isEmpty() || rollover > 0) {
                broadcast(msg("not_enough_players", "&6&lLotería &8» &7No hubo suficientes jugadores (mínimo {min}).",
                        "min", String.valueOf(minPlayers)));
            }
            if (config().getBoolean("rollover_if_not_enough", false)) {
                rollover = pot;
                if (pot > 0)
                    broadcast(msg("rollover", "&6&lLotería &8» &7El pozo de &e{pot}&7 pasa al próximo sorteo.",
                            "pot", units(pot)));
            } else {
                for (Map.Entry<UUID, Long> e : paid.entrySet()) {
                    TokenWallet.give(e.getKey(), e.getValue());
                    Player p = Bukkit.getPlayer(e.getKey());
                    if (p != null)
                        p.sendMessage(msg("refunded", "&7Se te devolvieron &e{amount}&7 de tus boletos de lotería.",
                                "amount", units(e.getValue())));
                }
            }
            resetRound(false);
            return;
        }

        broadcast(msg("drawing", "&6&lLotería &8» &e¡Comienza el sorteo! &7Pozo: &e{pot}", "pot", units(pot)));

        // Porcentajes de premios; si hay menos jugadores que premios, el resto va al 1°.
        List<Double> pcts = new ArrayList<>();
        for (Object o : config().getList("prizes", List.of(70, 20, 10))) {
            if (o instanceof Number n && n.doubleValue() > 0)
                pcts.add(n.doubleValue());
        }
        if (pcts.isEmpty())
            pcts.add(100.0);
        int places = Math.min(pcts.size(), tickets.size());

        Map<UUID, Integer> pool = new LinkedHashMap<>(tickets);
        List<UUID> winners = new ArrayList<>();
        for (int k = 0; k < places; k++) {
            int total = 0;
            for (int v : pool.values())
                total += v;
            int r = RNG.nextInt(total);
            UUID chosen = null;
            for (Map.Entry<UUID, Integer> e : pool.entrySet()) {
                r -= e.getValue();
                if (r < 0) {
                    chosen = e.getKey();
                    break;
                }
            }
            winners.add(chosen);
            pool.remove(chosen);
        }

        double pctSum = 0;
        for (double d : pcts)
            pctSum += d;
        long[] amounts = new long[places];
        long given = 0;
        for (int k = 0; k < places; k++) {
            amounts[k] = (long) Math.floor(pot * pcts.get(k) / pctSum);
            given += amounts[k];
        }
        amounts[0] += pot - given; // premios sin dueño y redondeo al 1°

        Map<UUID, Long> prizes = new HashMap<>();
        for (int k = 0; k < places; k++)
            prizes.merge(winners.get(k), amounts[k], Long::sum);
        for (Map.Entry<UUID, Integer> e : tickets.entrySet())
            GamblingDexPlugin.recordStats(e.getKey(), "loteria", e.getValue() * price(),
                    prizes.getOrDefault(e.getKey(), 0L));

        history.clear();
        for (int k = 0; k < places; k++) {
            UUID id = winners.get(k);
            String name = names.getOrDefault(id, "?");
            TokenWallet.give(id, amounts[k]);
            broadcast(msg("winner", "&6&lLotería &8» &f{place}° lugar: &a&l{player} &7gana &e{amount}",
                    "place", String.valueOf(k + 1), "player", name,
                    "tickets", String.valueOf(tickets.get(id)), "amount", units(amounts[k])));
            history.add((k + 1) + "° " + name + " +" + units(amounts[k]));
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(msg("you_won", "&a&l¡GANASTE LA LOTERÍA! &7{place}° lugar → &e+{amount}",
                        "place", String.valueOf(k + 1), "amount", units(amounts[k])));
                p.sendTitle(color("&6&l¡LOTERÍA!"), color("&a" + (k + 1) + "° lugar &e+" + units(amounts[k])), 10, 80, 20);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }
        plugin.getLogger().info("[Lotería] Sorteo: pozo " + pot + ", vendido " + soldUnits + ", " + tickets.size()
                + " jugadores, ganadores " + history);
        resetRound(true);
    }

    private void resetRound(boolean clearRollover) {
        tickets.clear();
        paid.clear();
        names.clear();
        soldUnits = 0;
        if (clearRollover)
            rollover = 0; // se repartió; si no, el acumulado sigue para el próximo
        remindersSent.clear();
        nextDraw = computeNextDraw();
        save();
    }

    private long computeNextDraw() {
        ZoneId zone;
        String tz = config().getString("timezone", "");
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime best = null;
        for (String t : config().getStringList("draw_times")) {
            try {
                LocalTime lt = LocalTime.parse(t.trim(), DateTimeFormatter.ofPattern("H:mm"));
                ZonedDateTime c = now.with(lt).withSecond(0).withNano(0);
                if (!c.isAfter(now.plusSeconds(30)))
                    c = c.plusDays(1);
                if (best == null || c.isBefore(best))
                    best = c;
            } catch (Exception ignored) {
            }
        }
        if (best == null)
            best = now.plusDays(1);
        return best.toInstant().toEpochMilli();
    }

    private String timeLeft() {
        long s = Math.max(0, (nextDraw - System.currentTimeMillis()) / 1000);
        long h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s";
    }

    private void broadcast(String text) {
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendMessage(text);
    }

    // ------------------------------------------------------------------
    // Guardado
    // ------------------------------------------------------------------

    private void load() {
        YamlConfiguration d = loadData();
        soldUnits = d.getLong("sold", 0L);
        rollover = d.getLong("rollover", 0L);
        nextDraw = d.getLong("next_draw", 0L);
        history.clear();
        history.addAll(d.getStringList("history"));
        ConfigurationSection sec = d.getConfigurationSection("players");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    tickets.put(id, sec.getInt(k + ".tickets"));
                    paid.put(id, sec.getLong(k + ".paid"));
                    names.put(id, sec.getString(k + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void save() {
        YamlConfiguration d = new YamlConfiguration();
        d.set("sold", soldUnits);
        d.set("rollover", rollover);
        d.set("next_draw", nextDraw);
        d.set("history", history);
        for (Map.Entry<UUID, Integer> e : tickets.entrySet()) {
            String base = "players." + e.getKey() + ".";
            d.set(base + "tickets", e.getValue());
            d.set(base + "paid", paid.getOrDefault(e.getKey(), 0L));
            d.set(base + "name", names.getOrDefault(e.getKey(), "?"));
        }
        saveData(d);
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    private void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(), 27, color("&6&lLotería"));
        for (int i = 0; i < 27; i++)
            inv.setItem(i, item(Material.YELLOW_STAINED_GLASS_PANE, " ", null));

        int mine = tickets.getOrDefault(p.getUniqueId(), 0);
        int total = totalTickets();
        double chance = total == 0 ? 0 : 100.0 * mine / total;
        inv.setItem(4, item(Material.GOLD_BLOCK, "&6&lPozo: &e" + units(pot()), List.of(
                "&7Sorteo en: &f" + timeLeft(),
                "&7Jugadores: &f" + tickets.size() + " &8| &7Boletos vendidos: &f" + total,
                "",
                "&7Tus boletos: &a" + mine + " &8(" + String.format(Locale.ROOT, "%.1f", chance) + "% de ganar el 1°)")));

        for (int i = 0; i < BUY_SLOTS.length; i++) {
            int n = BUY_AMOUNTS[i];
            inv.setItem(BUY_SLOTS[i], item(Material.PAPER, "&aComprar " + n + " boleto" + (n > 1 ? "s" : ""),
                    List.of("&7Precio: &e" + units(price() * n))));
        }
        inv.setItem(16, item(Material.SUNFLOWER, "&7Tus fichas: &e" + units(TokenWallet.balance(p)), null));

        List<String> pr = new ArrayList<>();
        int place = 1;
        for (Object o : config().getList("prizes", List.of(70, 20, 10))) {
            if (o instanceof Number n)
                pr.add("&f" + (place++) + "° lugar: &e" + n + "% &7del pozo");
        }
        inv.setItem(21, item(Material.BOOK, "&e&lPremios", pr));
        List<String> hist = new ArrayList<>();
        for (String h : history)
            hist.add("&7" + h);
        if (hist.isEmpty())
            hist.add("&8Aún no hay sorteos");
        inv.setItem(23, item(Material.CLOCK, "&e&lÚltimo sorteo", hist));
        p.openInventory(inv);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null) {
                List<String> l = new ArrayList<>();
                for (String s : lore)
                    l.add(color(s));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private final class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof MenuHolder))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            for (int i = 0; i < BUY_SLOTS.length; i++) {
                if (BUY_SLOTS[i] == e.getRawSlot()) {
                    buy(p, BUY_AMOUNTS[i]);
                    openMenu(p);
                    return;
                }
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof MenuHolder)
                e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Autocompletar (TAB)
    // ------------------------------------------------------------------

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1)
            return isAdmin(player) ? List.of("buy", "draw") : List.of("buy");
        String a = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (a.equals("buy") || a.equals("comprar")))
            return List.of("<amount>", "1", "10", "100");
        return List.of();
    }
}
