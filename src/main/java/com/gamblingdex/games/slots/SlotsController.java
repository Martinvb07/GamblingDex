package com.gamblingdex.games.slots;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.gui.AmountPickerMenu;
import com.gamblingdex.gui.Icons;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tragamonedas en un menú de 6 filas: tres rodillos que giran de verdad (se ven
 * el símbolo de arriba, el de la línea de pago y el de abajo) y se detienen uno
 * por uno. La apuesta se elige con botones (como Mines/Plinko) y se cobra al girar.
 *
 * <pre>
 *  0..8   luces (parpadean al girar), 4 = título
 * 11 13 15   rodillos (arriba)
 * 20 22 24   línea de pago (19 y 25 = flechas)
 * 29 31 33   rodillos (abajo)
 *  9 tabla de pagos · 17 tus fichas · 27 último resultado · 35 estadísticas
 * 45 mín · 46 ÷2 · 47 apuesta · 48 x2 · 49 máx · 53 GIRAR
 * </pre>
 */
public class SlotsController {

    private static final List<Material> REELS = List.of(Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT,
            Material.IRON_INGOT, Material.AMETHYST_SHARD, Material.NETHER_STAR);
    private static final int[][] REEL_SLOTS = { { 11, 20, 29 }, { 13, 22, 31 }, { 15, 24, 33 } };
    private static final int[] STOP_FRAME = { 12, 17, 22 }; // cada rodillo frena después del anterior

    private static final int S_PAYTABLE = 9, S_BALANCE = 17, S_LAST = 27, S_STATS = 35;
    private static final int S_MIN = 45, S_HALF = 46, S_CUSTOM = 47, S_DOUBLE = 48, S_MAX = 49, S_SPIN = 53;

    private final GamblingDexPlugin plugin;
    private final Map<UUID, SlotsState> states = new HashMap<>();

    public SlotsController(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class SlotsState {
        long bet;
        boolean spinning;
        long lastSpinAtMs;
        long spinBet;
        Material[][] reels = new Material[3][3]; // [rodillo][fila]
        Material[] result;
        long lastPay = -1;
        BukkitTask task;
        int frame;
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    private long min() {
        return Math.max(1, plugin.getConfig().getLong("games.slots.min_bet", 10));
    }

    private long max() {
        return Math.max(0, plugin.getConfig().getLong("games.slots.max_bet", 0));
    }

    private long tripleMult() {
        return Math.max(1L, plugin.getConfig().getLong("games.slots.payout.triple_multiplier", 5L));
    }

    private long doubleMult() {
        return Math.max(1L, plugin.getConfig().getLong("games.slots.payout.double_multiplier", 2L));
    }

    // ------------------------------------------------------------------

    public void open(Player player) {
        SlotsState s = states.computeIfAbsent(player.getUniqueId(), k -> new SlotsState());
        if (s.bet <= 0)
            s.bet = min();
        if (s.reels[0][0] == null)
            for (int r = 0; r < 3; r++)
                for (int y = 0; y < 3; y++)
                    s.reels[r][y] = randomSymbolWeighted();
        SlotsHolder holder = new SlotsHolder(player.getUniqueId());
        String raw = plugin.getConfig().getString("gui.slots.title", "&8&l✦ &d&lTRAGAMONEDAS &8&l✦");
        if (raw == null || raw.equals("&dTragamonedas")) // título de la versión anterior
            raw = "&8&l✦ &d&lTRAGAMONEDAS &8&l✦";
        String title = plugin.color(raw);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        render(player, inv, s);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
    }

    public boolean isSlotsInventory(Inventory inv, Player player) {
        return inv != null && inv.getHolder() instanceof SlotsHolder h && h.getOwner().equals(player.getUniqueId());
    }

    private void render(Player p, Inventory inv, SlotsState s) {
        inv.clear();
        boolean flash = s.spinning && s.frame % 2 == 0;

        // Luces de arriba y abajo de los rodillos
        for (int i = 0; i < 9; i++) {
            boolean a = (i + s.frame) % 2 == 0;
            Material light = s.spinning ? (a ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE)
                    : s.lastPay > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.MAGENTA_STAINED_GLASS_PANE;
            inv.setItem(i, Icons.of(light, " ", null));
            inv.setItem(36 + i, Icons.of(light, " ", null));
        }
        inv.setItem(4, Icons.of(Material.NETHER_STAR, 1, "&d&lTRAGAMONEDAS",
                List.of("&7Tres iguales en la línea: &ax" + tripleMult(), "&7Dos iguales: &ax" + doubleMult()), true));

        // Marco de los rodillos
        for (int row = 1; row <= 3; row++)
            for (int col : new int[] { 1, 3, 5, 7 })
                inv.setItem(row * 9 + col, Icons.of(Material.BLACK_STAINED_GLASS_PANE, " ", null));

        // Rodillos
        for (int r = 0; r < 3; r++) {
            for (int y = 0; y < 3; y++) {
                Material m = s.reels[r][y];
                boolean line = y == 1;
                boolean winning = line && !s.spinning && s.lastPay > 0 && s.result != null && isWinning(s.result, r);
                inv.setItem(REEL_SLOTS[r][y], Icons.of(m == null ? Material.BARRIER : m, 1,
                        (line ? "&f&l" : "&7") + symbolName(m), null, winning));
            }
        }
        // Flechas de la línea de pago
        Material arrow = flash ? Material.YELLOW_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
        inv.setItem(19, Icons.of(arrow, "&a&l▶ Línea de pago", null));
        inv.setItem(25, Icons.of(arrow, "&a&l◀ Línea de pago", null));

        // Lado izquierdo / derecho
        List<String> pay = new ArrayList<>();
        pay.add("&7Tres iguales: &a&lx" + tripleMult());
        pay.add("&7Dos iguales: &ax" + doubleMult());
        pay.add("");
        pay.add("&7Símbolos:");
        for (Material m : symbols())
            pay.add("&8• &f" + symbolName(m));
        inv.setItem(S_PAYTABLE, Icons.of(Material.BOOK, 1, "&e&lTabla de pagos", pay, false));
        inv.setItem(S_BALANCE, Icons.of(Material.SUNFLOWER, "&7Tus fichas: &e" + fmt(TokenWallet.balance(p)), null));
        if (s.lastPay < 0)
            inv.setItem(S_LAST, Icons.of(Material.CLOCK, "&7Aún no has girado", null));
        else if (s.lastPay > 0)
            inv.setItem(S_LAST, Icons.of(Material.EMERALD, 1, "&a&l¡Premio! &e+" + fmt(s.lastPay),
                    List.of("&7Apuesta: &e" + fmt(s.spinBet)), true));
        else
            inv.setItem(S_LAST, Icons.of(Material.REDSTONE, "&cSin premio", List.of("&7Apuesta: &e" + fmt(s.spinBet))));
        var st = plugin.getGameStats() == null ? null : plugin.getGameStats().get(p.getUniqueId(), false);
        if (st != null)
            inv.setItem(S_STATS, Icons.of(Material.PAPER, "&fTus estadísticas", List.of(
                    "&7Apuestas: &f" + fmt(st.rounds()),
                    "&7Mejor premio: &a" + fmt(st.biggestWin()),
                    "&7Ganancia neta: " + (st.profit() >= 0 ? "&a+" : "&c") + fmt(st.profit()),
                    "&8(todos los juegos)")));

        // Controles
        String u = fmt(s.bet);
        if (s.spinning) {
            for (int i = 45; i <= 52; i++)
                inv.setItem(i, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&8Girando...", null));
            inv.setItem(S_SPIN, Icons.of(Material.MAGMA_CREAM, 1, "&e&lGIRANDO...", null, false));
        } else {
            inv.setItem(S_MIN, Icons.of(Material.IRON_NUGGET, "&fMínimo", List.of("&7Apuesta " + fmt(min()))));
            inv.setItem(S_HALF, Icons.of(Material.RED_STAINED_GLASS_PANE, "&c÷2", List.of("&7Mitad de la apuesta")));
            inv.setItem(S_CUSTOM, Icons.of(Material.GOLD_NUGGET, "&6Apuesta: &e" + u, List.of("&7Click para escribir otra cantidad")));
            inv.setItem(S_DOUBLE, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&ax2", List.of("&7Doble de la apuesta")));
            inv.setItem(S_MAX, Icons.of(Material.GOLD_BLOCK, "&6Máximo", List.of("&7Todas tus fichas" + (max() > 0 ? " (hasta " + fmt(max()) + ")" : ""))));
            inv.setItem(S_SPIN, Icons.of(Material.LEVER, 1, "&a&lGIRAR &8» &e" + u, List.of(
                    "&7Tres iguales: &a" + fmt(s.bet * tripleMult()),
                    "&7Dos iguales: &a" + fmt(s.bet * doubleMult())), true));
        }
        Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    /** ¿El rodillo r forma parte de la combinación ganadora? */
    private static boolean isWinning(Material[] res, int r) {
        if (res[0] == res[1] && res[1] == res[2])
            return true;
        for (int o = 0; o < 3; o++)
            if (o != r && res[o] == res[r])
                return true;
        return false;
    }

    // ------------------------------------------------------------------
    // Clicks (los manda SlotsMenuListener)
    // ------------------------------------------------------------------

    public void handleClick(Player p, Inventory inv, int slot, boolean shift) {
        SlotsState s = states.computeIfAbsent(p.getUniqueId(), k -> new SlotsState());
        if (s.spinning)
            return;
        long hi = max() > 0 ? max() : Long.MAX_VALUE;
        switch (slot) {
            case S_SPIN -> spin(p, inv, s);
            case S_MIN -> setBet(p, inv, s, min());
            case S_HALF -> setBet(p, inv, s, s.bet / 2);
            case S_DOUBLE -> setBet(p, inv, s, Math.min(hi, s.bet * 2));
            case S_MAX -> setBet(p, inv, s, Math.min(hi, Math.max(min(), TokenWallet.balance(p))));
            case S_CUSTOM -> {
                p.closeInventory();
                AmountPickerMenu.open(p, "&d&lSlots &8- &eTu apuesta", min(), max(), s.bet, List.of(),
                        amount -> {
                            s.bet = amount;
                            open(p);
                        }, () -> open(p));
            }
            default -> {
            }
        }
    }

    private void setBet(Player p, Inventory inv, SlotsState s, long v) {
        long hi = max() > 0 ? max() : Long.MAX_VALUE;
        s.bet = Math.max(min(), Math.min(hi, v));
        render(p, inv, s);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private void spin(Player p, Inventory inv, SlotsState s) {
        long cooldownMs = Math.max(0L, plugin.getConfig().getLong("games.slots.spin_cooldown_ms", 0L));
        long now = System.currentTimeMillis();
        if (cooldownMs > 0 && now - s.lastSpinAtMs < cooldownMs) {
            p.sendMessage(colorCfg("messages.slots.cooldown", "&cEspera {ms}ms para girar de nuevo.")
                    .replace("{ms}", String.valueOf(cooldownMs - (now - s.lastSpinAtMs))));
            return;
        }
        if (plugin.getMaintenance() != null && !plugin.getMaintenance().allow(p, "slots"))
            return;
        if (!TokenWallet.take(p, s.bet)) {
            p.sendMessage(plugin.color("&cNo te alcanzan las fichas. Tienes &e" + fmt(TokenWallet.balance(p)) + "&c."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        s.spinning = true;
        s.lastSpinAtMs = now;
        s.spinBet = s.bet;
        s.frame = 0;
        s.result = new Material[] { randomSymbolWeighted(), randomSymbolWeighted(), randomSymbolWeighted() };
        try {
            plugin.getSlotsStatsManager().recordSpin(p.getUniqueId(), s.spinBet);
        } catch (Throwable ignored) {
        }
        p.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.8f, 1.0f);
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> frame(p, inv, s), 2L, 2L);
    }

    private void frame(Player p, Inventory inv, SlotsState s) {
        s.frame++;
        for (int r = 0; r < 3; r++) {
            if (s.frame < STOP_FRAME[r]) {
                // baja una posición
                s.reels[r][2] = s.reels[r][1];
                s.reels[r][1] = s.reels[r][0];
                s.reels[r][0] = randomSymbolWeighted();
            } else if (s.frame == STOP_FRAME[r]) {
                s.reels[r][0] = randomSymbolWeighted();
                s.reels[r][1] = s.result[r];
                s.reels[r][2] = randomSymbolWeighted();
                if (p.isOnline())
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 1.0f + r * 0.2f);
            }
        }
        if (s.frame < STOP_FRAME[2] && p.isOnline())
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.8f);
        if (s.frame >= STOP_FRAME[2])
            finish(p, s);
        if (p.isOnline() && p.getOpenInventory().getTopInventory() == inv)
            render(p, inv, s);
    }

    /** Termina el giro (también al cerrar el menú o desconectarse: el resultado ya estaba decidido). */
    private void finish(Player p, SlotsState s) {
        if (!s.spinning)
            return;
        if (s.task != null) {
            s.task.cancel();
            s.task = null;
        }
        for (int r = 0; r < 3; r++)
            s.reels[r][1] = s.result[r];
        s.spinning = false;
        Material[] res = s.result;
        long payout = 0;
        boolean triple = res[0] == res[1] && res[1] == res[2];
        if (triple)
            payout = s.spinBet * tripleMult();
        else if (res[0] == res[1] || res[1] == res[2] || res[0] == res[2])
            payout = s.spinBet * doubleMult();
        s.lastPay = payout;
        if (payout > 0)
            TokenWallet.give(p.getUniqueId(), payout);
        try {
            plugin.getSlotsStatsManager().recordPayout(p.getUniqueId(), payout);
        } catch (Throwable ignored) {
        }
        GamblingDexPlugin.recordStats(p.getUniqueId(), "slots", s.spinBet, payout);
        long big = Math.max(0L, plugin.getConfig().getLong("games.slots.log_big_wins.threshold_units", 0L));
        if (big > 0 && payout >= big)
            plugin.getLogger().info("[Slots] Big win player=" + p.getName() + " bet=" + s.spinBet + " payout=" + payout);
        if (!p.isOnline())
            return;
        String currencyName = plugin.color(plugin.getConfig().getString("currency.name", "⛃"));
        if (payout > 0) {
            p.sendMessage(colorCfg("messages.slots.win", "&a¡Premio! &7Ganaste fichas equivalentes a &e{amount} &7{currency}")
                    .replace("{amount}", fmt(payout)).replace("{currency}", currencyName));
            p.playSound(p.getLocation(), triple ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            if (triple)
                p.sendTitle(plugin.color("&d&l¡TRIPLE!"), plugin.color("&e+" + fmt(payout)), 5, 40, 10);
        } else {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
        }
    }

    public void handleClose(Player player) {
        SlotsState s = states.get(player.getUniqueId());
        if (s != null && s.spinning)
            finish(player, s);
    }

    /** Al apagar el plugin: termina los giros en curso. */
    public void shutdown() {
        for (Map.Entry<UUID, SlotsState> e : states.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && e.getValue().spinning)
                finish(p, e.getValue());
        }
    }

    // ------------------------------------------------------------------

    private List<Material> symbols() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("games.slots.symbol_weights");
        List<Material> out = new ArrayList<>();
        if (sec != null)
            for (String k : sec.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m != null && m.isItem() && sec.getInt(k, 0) > 0)
                    out.add(m);
            }
        return out.isEmpty() ? REELS : out;
    }

    private Material randomSymbolWeighted() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("games.slots.symbol_weights");
        if (sec == null)
            return REELS.get(ThreadLocalRandom.current().nextInt(REELS.size()));
        List<Material> mats = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (String k : sec.getKeys(false)) {
            Material mat = Material.matchMaterial(k);
            int w = Math.max(0, sec.getInt(k, 0));
            if (mat == null || !mat.isItem() || w <= 0)
                continue;
            mats.add(mat);
            weights.add(w);
            total += w;
        }
        if (total <= 0)
            return REELS.get(ThreadLocalRandom.current().nextInt(REELS.size()));
        int r = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (int i = 0; i < mats.size(); i++) {
            acc += weights.get(i);
            if (r < acc)
                return mats.get(i);
        }
        return mats.get(mats.size() - 1);
    }

    private static String symbolName(Material m) {
        if (m == null)
            return "?";
        return switch (m) {
            case DIAMOND -> "Diamante";
            case EMERALD -> "Esmeralda";
            case GOLD_INGOT -> "Oro";
            case IRON_INGOT -> "Hierro";
            case AMETHYST_SHARD -> "Amatista";
            case NETHER_STAR -> "Estrella";
            default -> {
                String n = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
                yield Character.toUpperCase(n.charAt(0)) + n.substring(1);
            }
        };
    }

    private String colorCfg(String path, String def) {
        if (path != null && path.startsWith("messages."))
            return plugin.getMessages().getString(path, def);
        return plugin.color(plugin.getConfig().getString(path, def));
    }

    private static String fmt(long units) {
        try {
            return NumberFormat.getInstance(Locale.forLanguageTag("es-ES")).format(units);
        } catch (Exception ignored) {
            return String.valueOf(units);
        }
    }
}
