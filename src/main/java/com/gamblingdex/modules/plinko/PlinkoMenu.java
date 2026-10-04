package com.gamblingdex.modules.plinko;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.gui.AmountPickerMenu;
import com.gamblingdex.gui.Icons;
import com.gamblingdex.modules.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.security.SecureRandom;
import java.util.*;

/**
 * Plinko en un menú (estación de un bloque). 8 filas de clavos: la bola baja
 * por las 4 filas de arriba (dos rebotes por fila) y cae en una de las 9
 * casillas de la fila 5. Abajo, la apuesta y el botón de soltar.
 *
 * <pre>
 *  0..35  tablero (clavos y bola)
 * 36..44  casillas con su multiplicador
 * 45 mín · 46 ÷2 · 47 apuesta · 48 x2 · 49 máx · 50 últimas · 51 fichas · 53 soltar
 * </pre>
 */
final class PlinkoMenu implements Listener {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int ROWS = 8;
    private static final int STEP_TICKS = 3;

    private static final int S_MIN = 45, S_HALF = 46, S_CUSTOM = 47, S_DOUBLE = 48, S_MAX = 49;
    private static final int S_HISTORY = 50, S_BALANCE = 51, S_DROP = 53;

    static final class Session implements InventoryHolder {
        final UUID player;
        Inventory inv;
        long bet;
        int queued; // bolas pendientes (shift = 5)
        boolean[] path; // bola actual
        long ballBet;
        int step = -1; // -1 = sin bola
        int ticks;
        int landed = -1; // casilla resaltada
        final Deque<String> history = new ArrayDeque<>();

        Session(UUID player, long bet) {
            this.player = player;
            this.bet = bet;
        }

        boolean dropping() {
            return step >= 0;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final PlinkoModule m;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastBet = new HashMap<>();

    PlinkoMenu(PlinkoModule module) {
        this.m = module;
    }

    private long min() {
        return Math.max(1, m.config().getLong("min_bet", 10));
    }

    private long max() {
        return Math.max(0, m.config().getLong("max_bet", 0));
    }

    private List<Double> mults() {
        return m.multipliers(ROWS);
    }

    // ------------------------------------------------------------------

    void open(Player p) {
        if (mults() == null) {
            p.sendMessage(m.msg("bad_rows", "&cFaltan los multiplicadores de 8 filas en plinko.yml."));
            return;
        }
        Session s = new Session(p.getUniqueId(), Math.max(min(), lastBet.getOrDefault(p.getUniqueId(), min())));
        s.inv = Bukkit.createInventory(s, 54, m.color("&8&l✦ &6&lPLINKO &8&l✦"));
        sessions.put(p.getUniqueId(), s);
        render(p, s);
        p.openInventory(s.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
    }

    /** Columna (0-8) de la bola tras {@code step} rebotes con {@code rights} a la derecha. */
    private static int ballCol(boolean[] path, int step) {
        int rights = 0;
        for (int i = 0; i < step; i++)
            if (path[i])
                rights++;
        double x = 4 + rights - step / 2.0;
        boolean lastRight = step > 0 && path[step - 1];
        return (int) (lastRight ? Math.ceil(x) : Math.floor(x)); // medio paso: hacia donde rebotó
    }

    private void render(Player p, Session s) {
        Inventory inv = s.inv;
        inv.clear();
        List<Double> mult = mults();
        Material ball = Material.matchMaterial(m.config().getString("ball_item", "SLIME_BALL"));
        if (ball == null || !ball.isItem())
            ball = Material.SLIME_BALL;

        // Clavos en triángulo (filas 0-3)
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 9; c++)
                if ((c + r) % 2 == 0 && Math.abs(c - 4) <= r * 2 + 1)
                    inv.setItem(r * 9 + c, Icons.of(Material.WHITE_STAINED_GLASS_PANE, "&f•", null));

        // Bola
        if (s.dropping() && s.step < ROWS) {
            int row = Math.min(3, s.step / 2);
            inv.setItem(row * 9 + ballCol(s.path, s.step), Icons.of(ball, 1, "&a&lBola", List.of("&7Apuesta: &e" + GameModule.units(s.ballBet)), true));
        }

        // Casillas
        for (int i = 0; i < 9; i++) {
            double x = mult.get(i);
            boolean hit = s.landed == i;
            inv.setItem(36 + i, Icons.of(hit ? Material.SEA_LANTERN : m.slotMaterial(x), 1,
                    (x >= 2 ? "&6&l" : x >= 1 ? "&e" : "&7") + "x" + PlinkoModule.fmt(x),
                    List.of("&7Pagaría: &e" + GameModule.units((long) Math.floor(s.bet * x))), hit));
        }
        if (s.dropping() && s.step >= ROWS)
            inv.setItem(27 + ballCol(s.path, ROWS), Icons.of(ball, 1, "&a&lBola", null, true));

        // Controles
        String u = GameModule.units(s.bet);
        boolean busy = s.dropping() || s.queued > 0;
        inv.setItem(S_MIN, Icons.of(Material.IRON_NUGGET, "&fMínimo", List.of("&7Apuesta " + GameModule.units(min()))));
        inv.setItem(S_HALF, Icons.of(Material.RED_STAINED_GLASS_PANE, "&c÷2", List.of("&7Mitad de la apuesta")));
        inv.setItem(S_CUSTOM, Icons.of(Material.GOLD_NUGGET, "&6Apuesta: &e" + u, List.of("&7Click para escribir otra cantidad")));
        inv.setItem(S_DOUBLE, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&ax2", List.of("&7Doble de la apuesta")));
        inv.setItem(S_MAX, Icons.of(Material.GOLD_BLOCK, "&6Máximo", List.of("&7Todas tus fichas" + (max() > 0 ? " (hasta " + GameModule.units(max()) + ")" : ""))));
        List<String> hist = new ArrayList<>();
        if (s.history.isEmpty())
            hist.add("&8Aún no soltaste bolas");
        else
            hist.addAll(s.history);
        inv.setItem(S_HISTORY, Icons.of(Material.BOOK, "&fÚltimas bolas", hist));
        inv.setItem(S_BALANCE, Icons.of(Material.SUNFLOWER, "&7Tus fichas: &e" + GameModule.units(TokenWallet.balance(p)), null));
        inv.setItem(52, Icons.of(Material.PAPER, "&6&lPLINKO", List.of(
                "&7La bola rebota a izquierda o derecha",
                "&7en cada clavo. Las orillas son las",
                "&7menos probables y las que más pagan.",
                "", "&7Premio máximo: &fx" + PlinkoModule.fmt(Collections.max(mult)))));
        inv.setItem(S_DROP, Icons.of(busy ? Material.MAGMA_CREAM : Material.SLIME_BALL, 1,
                busy ? "&e&lCayendo..." + (s.queued > 0 ? " &7(" + s.queued + " más)" : "") : "&a&lSOLTAR BOLA &8» &e" + u,
                List.of("&7Click: &f1 bola", "&7Shift + click: &f5 bolas"), !busy));
        Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    private void setBet(Player p, Session s, long v) {
        long hi = max() > 0 ? max() : Long.MAX_VALUE;
        s.bet = Math.max(min(), Math.min(hi, v));
        lastBet.put(s.player, s.bet);
        render(p, s);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    /** Cobra y suelta la siguiente bola de la cola. false si no alcanzan las fichas. */
    private boolean launch(Player p, Session s) {
        if (!m.isOpenFor(p)) {
            s.queued = 0;
            return false;
        }
        if (!TokenWallet.take(p, s.bet)) {
            s.queued = 0;
            p.sendMessage(m.msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", GameModule.units(TokenWallet.balance(p))));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return false;
        }
        s.queued = Math.max(0, s.queued - 1);
        s.ballBet = s.bet;
        s.path = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++)
            s.path[i] = RNG.nextBoolean();
        s.step = 0;
        s.ticks = 0;
        s.landed = -1;
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.4f);
        return true;
    }

    /** Lo llama el módulo cada tick. */
    void tick() {
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null)
                continue;
            if (!s.dropping()) {
                if (s.queued > 0 && launch(p, s))
                    render(p, s);
                continue;
            }
            if (++s.ticks % STEP_TICKS != 0)
                continue;
            s.step++;
            if (s.step <= ROWS)
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.5f + s.step * 0.05f);
            if (s.step > ROWS) {
                land(p, s);
                if (s.queued > 0)
                    launch(p, s);
            }
            render(p, s);
        }
    }

    private void land(Player p, Session s) {
        int k = ballCol(s.path, ROWS);
        double x = mults().get(k);
        long pay = (long) Math.floor(s.ballBet * x);
        long maxWin = m.config().getLong("max_win", 0L);
        if (maxWin > 0)
            pay = Math.min(pay, maxWin);
        if (pay > 0)
            TokenWallet.give(s.player, pay);
        GamblingDexPlugin.recordStats(s.player, "plinko", s.ballBet, pay);
        s.step = -1;
        s.landed = k;
        long net = pay - s.ballBet;
        s.history.addFirst((net >= 0 ? "&a" : "&c") + "x" + PlinkoModule.fmt(x) + " &8» "
                + (net >= 0 ? "&a+" + GameModule.units(pay) : "&c-" + GameModule.units(-net)));
        while (s.history.size() > 8)
            s.history.removeLast();
        if (p != null) {
            p.playSound(p.getLocation(), x >= 2 ? Sound.ENTITY_PLAYER_LEVELUP : x >= 1 ? Sound.BLOCK_NOTE_BLOCK_BELL
                    : Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.2f);
            if (x >= m.config().getDouble("title_min_multiplier", 5.0))
                p.sendTitle(m.color("&6&lx" + PlinkoModule.fmt(x)), m.color("&e+" + GameModule.units(pay)), 5, 40, 10);
        }
    }

    /** Al cerrar el menú / apagar: la bola en el aire se paga ya (el resultado ya estaba decidido). */
    private void finish(Session s) {
        s.queued = 0;
        if (s.dropping()) {
            s.step = ROWS;
            land(Bukkit.getPlayer(s.player), s);
        }
    }

    void closeAll() {
        for (Session s : new ArrayList<>(sessions.values())) {
            finish(s);
            Player p = Bukkit.getPlayer(s.player);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() == s)
                p.closeInventory();
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Session s))
            return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory())
            return;
        boolean shift = e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT;
        boolean busy = s.dropping() || s.queued > 0;
        switch (e.getRawSlot()) {
            case S_DROP -> {
                if (busy)
                    return;
                s.queued = shift ? 5 : 1;
                if (launch(p, s))
                    render(p, s);
            }
            case S_MIN -> {
                if (!busy)
                    setBet(p, s, min());
            }
            case S_HALF -> {
                if (!busy)
                    setBet(p, s, s.bet / 2);
            }
            case S_DOUBLE -> {
                if (!busy)
                    setBet(p, s, s.bet * 2);
            }
            case S_MAX -> {
                if (!busy)
                    setBet(p, s, Math.max(min(), TokenWallet.balance(p)));
            }
            case S_CUSTOM -> {
                if (busy)
                    return;
                p.closeInventory();
                AmountPickerMenu.open(p, "&6&lPlinko &8- &eTu apuesta", min(), max(), s.bet, List.of(),
                        amount -> {
                            lastBet.put(p.getUniqueId(), amount);
                            open(p);
                        }, () -> open(p));
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Session)
            e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Session s))
            return;
        finish(s);
        sessions.remove(s.player, s);
    }
}
