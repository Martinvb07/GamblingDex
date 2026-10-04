package com.gamblingdex.modules.bingo;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.modules.GameModule;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
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
 * Bingo de 75 bolas. Venta de cartones → se cantan bolas cada pocos segundos →
 * premio por LÍNEA (fila/columna/diagonal) y por BINGO (cartón lleno). Los
 * cartones se marcan solos.
 */
public class BingoModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String LETTERS = "BINGO";

    private enum State {
        IDLE,
        SALE,
        PLAYING
    }

    /** Cartón 5x5; índice = fila*5 + columna. El centro (12) es libre. */
    static final class Card {
        final int[] numbers = new int[25];
        final boolean[] marked = new boolean[25];

        Card() {
            for (int col = 0; col < 5; col++) {
                List<Integer> pool = new ArrayList<>();
                for (int n = col * 15 + 1; n <= col * 15 + 15; n++)
                    pool.add(n);
                Collections.shuffle(pool, RNG);
                for (int row = 0; row < 5; row++)
                    numbers[row * 5 + col] = pool.get(row);
            }
            numbers[12] = 0;
            marked[12] = true;
        }

        boolean mark(int n) {
            for (int i = 0; i < 25; i++) {
                if (numbers[i] == n) {
                    marked[i] = true;
                    return true;
                }
            }
            return false;
        }

        boolean hasLine() {
            for (int r = 0; r < 5; r++) {
                boolean ok = true;
                for (int c = 0; c < 5; c++)
                    ok &= marked[r * 5 + c];
                if (ok)
                    return true;
            }
            for (int c = 0; c < 5; c++) {
                boolean ok = true;
                for (int r = 0; r < 5; r++)
                    ok &= marked[r * 5 + c];
                if (ok)
                    return true;
            }
            boolean d1 = true, d2 = true;
            for (int k = 0; k < 5; k++) {
                d1 &= marked[k * 5 + k];
                d2 &= marked[k * 5 + (4 - k)];
            }
            return d1 || d2;
        }

        int missing() {
            int m = 0;
            for (boolean b : marked)
                if (!b)
                    m++;
            return m;
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        int cardIndex;

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private State state = State.IDLE;
    private final Map<UUID, List<Card>> cards = new LinkedHashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Long> paid = new HashMap<>();
    private final Map<UUID, Long> won = new HashMap<>();
    private final List<Integer> drawn = new ArrayList<>();
    private final Deque<Integer> balls = new ArrayDeque<>();
    private long soldUnits;
    private boolean linePaid;
    private long nextAutoStart;
    private int countdown;
    private int callTimer;
    private BossBar bar;

    @Override
    public String id() {
        return "bingo";
    }

    @Override
    public String displayName() {
        return "Bingo";
    }

    @Override
    public void enable() {
        bar = Bukkit.createBossBar("", BarColor.PINK, BarStyle.SEGMENTED_10);
        scheduleNextAuto();
        listen(new Events());
        runTimer(this::tick, 20L, 20L);
    }

    @Override
    public void disable() {
        if (state == State.SALE) {
            refundAll();
        } else if (state == State.PLAYING) {
            // Terminar la partida al instante para que nadie pierda lo que compró.
            int guard = 0;
            while (state == State.PLAYING && guard++ < 100)
                callBall();
        }
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lBingo",
                "&8• &e/gdx bingo &7- Comprar y ver tus cartones",
                "&8• &e/gdx bingo buy <amount> &7- Comprar cartones (durante la venta)",
                "&8• &7Partidas automáticas: &f" + String.join("&7, &f", config().getStringList("start_times"))));
        if (admin)
            l.add("&8• &e/gdx bingo start &7- Abrir la venta ahora");
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (args.length == 0) {
            openMenu(player, 0);
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
            case "iniciar", "start" -> {
                if (!isAdmin(player))
                    player.sendMessage(msg("usage", "&cUso: /gdx bingo [buy <amount>]"));
                else if (state != State.IDLE)
                    player.sendMessage(msg("already_running", "&cYa hay un bingo en curso."));
                else
                    openSale();
            }
            default -> player.sendMessage(msg("usage", "&cUso: /gdx bingo [buy <amount>]"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Ciclo
    // ------------------------------------------------------------------

    /**
     * Próxima partida automática: la siguiente hora de start_times (en
     * timezone). Si start_times está vacío, cada interval_minutes.
     */
    private void scheduleNextAuto() {
        List<String> times = config().getStringList("start_times");
        if (times.isEmpty()) {
            nextAutoStart = System.currentTimeMillis()
                    + Math.max(1, config().getLong("interval_minutes", 15)) * 60_000L;
            return;
        }
        ZoneId zone;
        String tz = config().getString("timezone", "");
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (Exception e) {
            plugin.getLogger().warning("[Bingo] Zona horaria inválida: " + tz + " (se usa la del servidor)");
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime best = null;
        for (String t : times) {
            try {
                LocalTime lt = LocalTime.parse(t.trim(), DateTimeFormatter.ofPattern("H:mm"));
                ZonedDateTime c = now.with(lt).withSecond(0).withNano(0);
                if (!c.isAfter(now.plusSeconds(30)))
                    c = c.plusDays(1);
                if (best == null || c.isBefore(best))
                    best = c;
            } catch (Exception e) {
                plugin.getLogger().warning("[Bingo] Hora inválida en start_times: " + t + " (usa HH:mm, ej. 14:00)");
            }
        }
        nextAutoStart = best == null ? Long.MAX_VALUE : best.toInstant().toEpochMilli();
    }

    @Override
    public void reload() {
        if (state == State.IDLE)
            scheduleNextAuto();
    }

    private static String timeLeft(long ms) {
        long min = Math.max(0, ms / 60_000L);
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }

    private long price() {
        return Math.max(1L, config().getLong("card_price", 100L));
    }

    private long pot() {
        double cut = Math.max(0.0, Math.min(90.0, config().getDouble("house_cut_percent", 10.0))) / 100.0;
        return (long) Math.floor(soldUnits * (1.0 - cut));
    }

    private long linePrize() {
        double pct = Math.max(0.0, Math.min(100.0, config().getDouble("line_prize_percent", 30.0))) / 100.0;
        return (long) Math.floor(pot() * pct);
    }

    private void tick() {
        switch (state) {
            case IDLE -> {
                if (config().getBoolean("auto_start", true) && System.currentTimeMillis() >= nextAutoStart)
                    openSale();
            }
            case SALE -> {
                countdown--;
                if (countdown == 30 || countdown == 10) {
                    broadcast(msg("sale_closing", "&6&lBingo &8» &7Quedan &f{seconds}s &7para comprar cartones. Pozo: &e{pot}",
                            "seconds", String.valueOf(countdown), "pot", units(pot())));
                }
                if (countdown <= 0)
                    startGame();
            }
            case PLAYING -> {
                if (++callTimer >= Math.max(1, config().getInt("call_seconds", 4))) {
                    callTimer = 0;
                    callBall();
                }
            }
        }
        updateBar();
    }

    private void openSale() {
        state = State.SALE;
        cards.clear();
        names.clear();
        paid.clear();
        drawn.clear();
        balls.clear();
        soldUnits = 0;
        linePaid = false;
        countdown = Math.max(10, config().getInt("sale_seconds", 60));
        broadcast(msg("sale_open", "&6&lBingo &8» &e¡Venta de cartones abierta! &7Cartón: &e{price}",
                "price", units(price()), "seconds", String.valueOf(countdown)));
    }

    private void startGame() {
        int min = Math.max(1, config().getInt("min_players", 2));
        if (cards.size() < min) {
            if (!cards.isEmpty())
                broadcast(msg("not_enough_players", "&6&lBingo &8» &7No hubo suficientes jugadores (mínimo {min}).",
                        "min", String.valueOf(min)));
            refundAll();
            endGame();
            return;
        }
        List<Integer> all = new ArrayList<>();
        for (int n = 1; n <= 75; n++)
            all.add(n);
        Collections.shuffle(all, RNG);
        balls.addAll(all);
        state = State.PLAYING;
        callTimer = 0;
        broadcast(msg("start", "&6&lBingo &8» &a¡Empieza! &7Jugadores: &f{players} &8| &7Pozo: &e{pot}",
                "players", String.valueOf(cards.size()), "pot", units(pot()),
                "line", units(linePrize()), "bingo", units(pot() - linePrize())));
    }

    private void callBall() {
        Integer n = balls.pollFirst();
        if (n == null) {
            endGame();
            return;
        }
        drawn.add(n);
        String label = ballLabel(n);

        for (Map.Entry<UUID, List<Card>> e : cards.entrySet()) {
            boolean hit = false;
            int bestMissing = 25;
            for (Card c : e.getValue()) {
                hit |= c.mark(n);
                bestMissing = Math.min(bestMissing, c.missing());
            }
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                String bar = "&d&lBOLA &f&l" + label + (hit ? " &a✔ ¡La tienes!" : "") + " &8| &7Te faltan &f"
                        + bestMissing + " &7para BINGO";
                p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(color(bar)));
                p.playSound(p.getLocation(), hit ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_HAT,
                        0.6f, hit ? 1.6f : 1.0f);
                if (hit && bestMissing == 1)
                    p.sendMessage(msg("near", "&e¡Te falta 1 número para BINGO!"));
            }
        }

        if (!linePaid) {
            List<UUID> lineWinners = new ArrayList<>();
            for (Map.Entry<UUID, List<Card>> e : cards.entrySet()) {
                for (Card c : e.getValue()) {
                    if (c.hasLine()) {
                        lineWinners.add(e.getKey());
                        break;
                    }
                }
            }
            if (!lineWinners.isEmpty()) {
                linePaid = true;
                payWinners(lineWinners, linePrize(), label, false);
            }
        }

        List<UUID> bingoWinners = new ArrayList<>();
        for (Map.Entry<UUID, List<Card>> e : cards.entrySet()) {
            for (Card c : e.getValue()) {
                if (c.missing() == 0) {
                    bingoWinners.add(e.getKey());
                    break;
                }
            }
        }
        if (!bingoWinners.isEmpty()) {
            // Si nadie cantó línea antes, el bingo se lleva todo el pozo.
            long prize = linePaid ? pot() - linePrize() : pot();
            linePaid = true;
            payWinners(bingoWinners, prize, label, true);
            plugin.getLogger().info("[Bingo] Terminó con " + drawn.size() + " bolas. Vendido " + soldUnits
                    + ", pozo " + pot());
            endGame();
            return;
        }
        refreshMenus();
    }

    private void payWinners(List<UUID> winners, long prize, String ball, boolean bingo) {
        long share = prize / winners.size();
        long rem = prize % winners.size();
        List<String> wn = new ArrayList<>();
        for (int i = 0; i < winners.size(); i++) {
            UUID id = winners.get(i);
            long amount = share + (i < rem ? 1 : 0);
            TokenWallet.give(id, amount);
            won.merge(id, amount, Long::sum);
            wn.add(names.getOrDefault(id, "?"));
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(bingo
                        ? msg("you_won_bingo", "&6&l¡BINGO! &7Cobras &e{amount}", "amount", units(amount))
                        : msg("you_won_line", "&a&l¡LÍNEA! &7Cobras &e{amount}", "amount", units(amount)));
                p.sendTitle(color(bingo ? "&6&l¡BINGO!" : "&a&l¡LÍNEA!"), color("&e+" + units(amount)), 5, 60, 15);
                p.playSound(p.getLocation(), bingo ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP,
                        1f, 1f);
            }
        }
        String text = bingo
                ? msg("bingo_winner", "&6&lBingo &8» &6&l¡BINGO! &f{players} &7con la bola &f{ball} &7→ &e{amount}",
                        "players", String.join(", ", wn), "ball", ball, "amount", units(prize))
                : msg("line_winner", "&6&lBingo &8» &a&l¡LÍNEA! &f{players} &7con la bola &f{ball} &7→ &e{amount}",
                        "players", String.join(", ", wn), "ball", ball, "amount", units(prize));
        broadcast(text);
    }

    private void endGame() {
        // Estadísticas de la partida (si se canceló, paid ya está vacío)
        for (Map.Entry<UUID, Long> e : paid.entrySet())
            GamblingDexPlugin.recordStats(e.getKey(), "bingo", e.getValue(), won.getOrDefault(e.getKey(), 0L));
        paid.clear();
        won.clear();
        state = State.IDLE;
        scheduleNextAuto();
        refreshMenus();
    }

    private void refundAll() {
        for (Map.Entry<UUID, Long> e : paid.entrySet()) {
            TokenWallet.give(e.getKey(), e.getValue());
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null)
                p.sendMessage(msg("refunded", "&7Se te devolvieron &e{amount}&7 de tus cartones de bingo.",
                        "amount", units(e.getValue())));
        }
        paid.clear();
        cards.clear();
        soldUnits = 0;
    }

    private void buy(Player p, int n) {
        if (state != State.SALE) {
            p.sendMessage(msg("not_sale", "&cAhora no se venden cartones."));
            return;
        }
        int max = Math.max(1, config().getInt("max_cards_per_player", 4));
        List<Card> mine = cards.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        n = Math.min(n, max - mine.size());
        if (n <= 0) {
            if (mine.isEmpty())
                cards.remove(p.getUniqueId());
            p.sendMessage(msg("max_cards", "&cSolo puedes tener &e{max}&c cartones.", "max", String.valueOf(max)));
            return;
        }
        long cost = price() * n;
        if (!TokenWallet.take(p, cost)) {
            if (mine.isEmpty())
                cards.remove(p.getUniqueId());
            p.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Necesitas &e{price}&c y tienes &e{balance}&c.",
                    "price", units(cost), "balance", units(TokenWallet.balance(p))));
            return;
        }
        for (int i = 0; i < n; i++)
            mine.add(new Card());
        names.put(p.getUniqueId(), p.getName());
        paid.merge(p.getUniqueId(), cost, Long::sum);
        soldUnits += cost;
        p.sendMessage(msg("bought", "&aCompraste &e{amount} &acartón(es). Tienes &f{total}&a.",
                "amount", String.valueOf(n), "total", String.valueOf(mine.size())));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.3f);
    }

    // ------------------------------------------------------------------
    // Pantalla
    // ------------------------------------------------------------------

    private static String ballLabel(int n) {
        return LETTERS.charAt((n - 1) / 15) + "-" + n;
    }

    private void updateBar() {
        if (bar == null)
            return;
        String title;
        double progress;
        switch (state) {
            case SALE -> {
                title = "&d&lBINGO &8| &eVenta de cartones: &f" + countdown + "s &8| &7Pozo: &e" + units(pot())
                        + " &8| &f/gdx bingo";
                progress = Math.min(1.0, countdown / (double) Math.max(10, config().getInt("sale_seconds", 60)));
            }
            case PLAYING -> {
                String last = drawn.isEmpty() ? "-" : ballLabel(drawn.get(drawn.size() - 1));
                title = "&d&lBINGO &8| &7Bola: &f&l" + last + " &8| &7Bolas: &f" + drawn.size() + "/75 &8| &7"
                        + (linePaid ? "Bingo: &e" + units(pot() - linePrize()) : "Línea: &e" + units(linePrize()));
                progress = drawn.size() / 75.0;
            }
            default -> {
                bar.removeAll();
                return;
            }
        }
        bar.setTitle(color(title));
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        // En la venta lo ve todo el server; jugando, solo quienes tienen cartón.
        Set<UUID> wanted = new HashSet<>(cards.keySet());
        if (state == State.SALE) {
            for (Player p : Bukkit.getOnlinePlayers())
                wanted.add(p.getUniqueId());
        }
        for (Player p : new ArrayList<>(bar.getPlayers()))
            if (!wanted.contains(p.getUniqueId()))
                bar.removePlayer(p);
        for (UUID id : wanted) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !bar.getPlayers().contains(p))
                bar.addPlayer(p);
        }
    }

    private void openMenu(Player p, int cardIndex) {
        MenuHolder h = new MenuHolder();
        Inventory inv = Bukkit.createInventory(h, 54, color("&d&lBingo"));
        h.cardIndex = cardIndex;
        fill(p, inv, h);
        p.openInventory(inv);
    }

    private void refreshMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MenuHolder h)
                fill(p, top, h);
        }
    }

    private void fill(Player p, Inventory inv, MenuHolder h) {
        for (int i = 0; i < 54; i++)
            inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, " ", null));

        List<Card> mine = cards.getOrDefault(p.getUniqueId(), List.of());
        String status = switch (state) {
            case SALE -> "&eVenta: &f" + countdown + "s";
            case PLAYING -> "&aJugando &8| &7Bolas: &f" + drawn.size();
            default -> !config().getBoolean("auto_start", true) || nextAutoStart == Long.MAX_VALUE
                    ? "&7Sin partidas automáticas"
                    : "&7Próxima partida en &f" + timeLeft(nextAutoStart - System.currentTimeMillis());
        };
        inv.setItem(0, item(Material.GOLD_BLOCK, "&6&lPozo: &e" + units(pot()), List.of(
                status,
                "&7Línea: &e" + units(linePrize()) + (linePaid ? " &8(ya salió)" : ""),
                "&7Bingo: &e" + units(pot() - linePrize()),
                "&7Cartón: &e" + units(price()))));

        if (state == State.SALE) {
            inv.setItem(18, item(Material.PAPER, "&a&lComprar cartón", List.of(
                    "&7Precio: &e" + units(price()),
                    "&7Tienes: &f" + mine.size() + "/" + Math.max(1, config().getInt("max_cards_per_player", 4)))));
        }

        if (!mine.isEmpty()) {
            int idx = Math.floorMod(h.cardIndex, mine.size());
            h.cardIndex = idx;
            Card c = mine.get(idx);
            for (int col = 0; col < 5; col++) {
                inv.setItem(2 + col, item(Material.PURPLE_STAINED_GLASS_PANE, "&d&l" + LETTERS.charAt(col), null));
            }
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 5; col++) {
                    int i = row * 5 + col;
                    int slot = (row + 1) * 9 + 2 + col;
                    if (c.numbers[i] == 0) {
                        inv.setItem(slot, item(Material.GOLD_NUGGET, "&6&lLIBRE", null));
                    } else if (c.marked[i]) {
                        inv.setItem(slot, item(Material.LIME_CONCRETE, "&a&l" + c.numbers[i] + " ✔", null));
                    } else {
                        inv.setItem(slot, item(Material.WHITE_CONCRETE, "&f" + c.numbers[i], null));
                    }
                }
            }
            inv.setItem(45, item(Material.ARROW, "&7◀ Cartón anterior", null));
            inv.setItem(49, item(Material.MAP, "&fCartón &e" + (idx + 1) + "&f/" + mine.size(),
                    List.of("&7Te faltan &f" + c.missing() + " &7para BINGO")));
            inv.setItem(53, item(Material.ARROW, "&7Cartón siguiente ▶", null));
        } else {
            inv.setItem(22, item(Material.PAPER, "&7No tienes cartones",
                    List.of(state == State.SALE ? "&aCompra uno a la izquierda" : "&7Espera la próxima venta")));
        }

        // Últimas bolas (columna derecha)
        int[] lastSlots = { 8, 17, 26, 35, 44 };
        for (int k = 0; k < lastSlots.length && k < drawn.size(); k++) {
            int n = drawn.get(drawn.size() - 1 - k);
            inv.setItem(lastSlots[k], item(k == 0 ? Material.MAGENTA_CONCRETE : Material.PINK_STAINED_GLASS_PANE,
                    (k == 0 ? "&d&l" : "&f") + ballLabel(n), List.of(k == 0 ? "&7Última bola" : "&8Anterior")));
        }
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

    private void broadcast(String text) {
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendMessage(text);
    }

    private final class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof MenuHolder h))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            switch (e.getRawSlot()) {
                case 18 -> {
                    buy(p, 1);
                    fill(p, e.getInventory(), h);
                }
                case 45 -> {
                    h.cardIndex--;
                    fill(p, e.getInventory(), h);
                }
                case 53 -> {
                    h.cardIndex++;
                    fill(p, e.getInventory(), h);
                }
                default -> {
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
            return isAdmin(player) ? List.of("buy", "start") : List.of("buy");
        String a = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (a.equals("buy") || a.equals("comprar")))
            return List.of("<amount>", "1", "2", "4");
        return List.of();
    }
}
