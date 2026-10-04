package com.gamblingdex.modules.mines;

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
 * Mines en un menú (estación de un bloque): tablero de 5x5 en el centro, datos a
 * la izquierda, botón de jugar/retirar a la derecha y abajo la apuesta y las minas.
 * Varios jugadores pueden jugar a la vez, cada uno en su menú.
 *
 * <pre>
 *  0 info   | 2..6  casillas | 8  fichas
 *  9 apuesta|               | 26 jugar / retirar
 * 18 minas  |               | 44 cerrar
 * 27 multiplicador actual
 * 36 siguiente casilla
 * 45 mín · 46 ÷2 · 47 apuesta · 48 x2 · 49 máx   51 -mina · 52 minas · 53 +mina
 * </pre>
 */
final class MinesMenu implements Listener {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int SIZE = 5;
    private static final int TILES = SIZE * SIZE;

    private static final int S_INFO = 0, S_BET = 9, S_MINES = 18, S_MULT = 27, S_NEXT = 36;
    private static final int S_BALANCE = 8, S_ACTION = 26, S_CLOSE = 44;
    private static final int S_MIN = 45, S_HALF = 46, S_CUSTOM = 47, S_DOUBLE = 48, S_MAX = 49;
    private static final int S_LESS = 51, S_MINES_INFO = 52, S_MORE = 53;

    static final class Session implements InventoryHolder {
        final UUID player;
        Inventory inv;
        long bet;
        int mines;
        boolean playing;
        boolean over; // partida terminada, mostrando el tablero
        boolean lost;
        boolean[] mine = new boolean[TILES];
        boolean[] open = new boolean[TILES];
        int safe;
        long lastPay;

        Session(UUID player, long bet, int mines) {
            this.player = player;
            this.bet = bet;
            this.mines = mines;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final MinesModule m;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, long[]> last = new HashMap<>(); // apuesta y minas de la última vez

    MinesMenu(MinesModule module) {
        this.m = module;
    }

    private long min() {
        return Math.max(1, m.config().getLong("min_bet", 10));
    }

    private long max() {
        return Math.max(0, m.config().getLong("max_bet", 0));
    }

    private static int slot(int tile) {
        return (tile / SIZE) * 9 + 2 + tile % SIZE;
    }

    private static int tileAt(int slot) {
        int row = slot / 9, col = slot % 9 - 2;
        return row < SIZE && col >= 0 && col < SIZE ? row * SIZE + col : -1;
    }

    // ------------------------------------------------------------------

    void open(Player p) {
        Session old = sessions.get(p.getUniqueId());
        if (old != null && old.playing) {
            p.openInventory(old.inv);
            return;
        }
        long[] l = last.getOrDefault(p.getUniqueId(), new long[] { min(), 3 });
        Session s = new Session(p.getUniqueId(), Math.max(min(), l[0]), (int) l[1]);
        s.inv = Bukkit.createInventory(s, 54, m.color("&8&l✦ &c&lMINES &8&l✦"));
        sessions.put(p.getUniqueId(), s);
        render(p, s);
        p.openInventory(s.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
    }

    private void render(Player p, Session s) {
        Inventory inv = s.inv;
        inv.clear();
        String u = GameModule.units(s.bet);
        double now = m.multiplier(s.mines, s.safe);
        double next = s.safe < TILES - s.mines ? m.multiplier(s.mines, s.safe + 1) : now;

        // Tablero
        for (int t = 0; t < TILES; t++) {
            if (s.open[t] || s.over) {
                if (s.mine[t])
                    inv.setItem(slot(t), Icons.of(Material.TNT, 1, s.open[t] ? "&c&l¡BOOM!" : "&cMina", null, s.open[t]));
                else if (s.open[t])
                    inv.setItem(slot(t), Icons.of(Material.EMERALD, 1, "&a&lSegura", null, true));
                else
                    inv.setItem(slot(t), Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Segura", null));
            } else if (s.playing) {
                inv.setItem(slot(t), Icons.of(Material.GRAY_CONCRETE, 1,
                        "&e&l?", List.of("&7Click para abrir", "&7Si es segura: &ax" + MinesModule.fmt(next)), false));
            } else {
                inv.setItem(slot(t), Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&8?",
                        List.of("&7Elige tu apuesta y pulsa &a&lJUGAR")));
            }
        }

        // Columna izquierda
        inv.setItem(S_INFO, Icons.of(Material.TNT, 1, "&c&lMINES", List.of(
                "&7Debajo de las casillas hay minas.",
                "&7Cada casilla segura sube el premio.",
                "&7Retírate cuando quieras con el",
                "&7botón verde. Si tocas una mina,",
                "&7pierdes la apuesta.", "",
                "&7Más minas = más riesgo y más premio."), false));
        inv.setItem(S_BET, Icons.of(Material.GOLD_INGOT, "&6Apuesta: &e" + u, null));
        inv.setItem(S_MINES, Icons.of(Material.TNT_MINECART, "&cMinas: &f" + s.mines,
                List.of("&7Casillas seguras: &f" + (TILES - s.mines))));
        inv.setItem(S_MULT, Icons.of(Material.EXPERIENCE_BOTTLE, "&aMultiplicador: &f&lx" + MinesModule.fmt(now),
                List.of("&7Cobrarías: &e" + GameModule.units(s.safe == 0 ? s.bet : payout(s)))));
        inv.setItem(S_NEXT, Icons.of(Material.SPYGLASS, "&bSiguiente casilla: &fx" + MinesModule.fmt(next),
                List.of("&7Abiertas: &f" + s.safe + "&7/&f" + (TILES - s.mines))));

        // Columna derecha
        inv.setItem(S_BALANCE, Icons.of(Material.SUNFLOWER, "&7Tus fichas: &e" + GameModule.units(TokenWallet.balance(p)), null));
        if (s.playing) {
            long pay = s.safe == 0 ? s.bet : payout(s);
            inv.setItem(S_ACTION, Icons.of(Material.EMERALD_BLOCK, 1, "&a&lRETIRARSE &8» &e" + GameModule.units(pay),
                    List.of("&7Cobras tu apuesta x" + MinesModule.fmt(s.safe == 0 ? 1.0 : now),
                            s.safe == 0 ? "&8(sin abrir casillas se te devuelve)" : ""), s.safe > 0));
        } else if (s.over) {
            inv.setItem(S_ACTION, Icons.of(Material.LIME_CONCRETE, 1, "&a&lJUGAR OTRA VEZ", List.of(
                    s.lost ? "&cTocaste una mina." : "&aCobraste &e" + GameModule.units(s.lastPay),
                    "", "&7Apuesta: &e" + u + " &8| &7Minas: &f" + s.mines), true));
        } else {
            inv.setItem(S_ACTION, Icons.of(Material.LIME_CONCRETE, 1, "&a&lJUGAR", List.of(
                    "&7Apuesta: &e" + u, "&7Minas: &f" + s.mines,
                    "&7Primera casilla: &fx" + MinesModule.fmt(m.multiplier(s.mines, 1)),
                    "&7Todas las seguras: &fx" + MinesModule.fmt(m.multiplier(s.mines, TILES - s.mines))), true));
        }
        inv.setItem(S_CLOSE, Icons.of(Material.BARRIER, s.playing ? "&cCerrar &7(cobra lo que llevas)" : "&cCerrar", null));

        // Fila inferior
        if (s.playing) {
            for (int i = 45; i <= 53; i++)
                inv.setItem(i, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&8Partida en curso", null));
        } else {
            inv.setItem(S_MIN, Icons.of(Material.IRON_NUGGET, "&fMínimo", List.of("&7Apuesta " + GameModule.units(min()))));
            inv.setItem(S_HALF, Icons.of(Material.RED_STAINED_GLASS_PANE, "&c÷2", List.of("&7Mitad de la apuesta")));
            inv.setItem(S_CUSTOM, Icons.of(Material.GOLD_NUGGET, "&6Apuesta: &e" + u, List.of("&7Click para escribir otra cantidad")));
            inv.setItem(S_DOUBLE, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&ax2", List.of("&7Doble de la apuesta")));
            inv.setItem(S_MAX, Icons.of(Material.GOLD_BLOCK, "&6Máximo", List.of("&7Todas tus fichas" + (max() > 0 ? " (hasta " + GameModule.units(max()) + ")" : ""))));
            inv.setItem(S_LESS, Icons.of(Material.RED_DYE, "&c-1 mina", List.of("&7Shift: -5")));
            inv.setItem(S_MINES_INFO, Icons.of(Material.TNT, Math.max(1, s.mines), "&cMinas: &f" + s.mines, null, false));
            inv.setItem(S_MORE, Icons.of(Material.LIME_DYE, "&a+1 mina", List.of("&7Shift: +5")));
        }
        Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    private long payout(Session s) {
        long win = (long) Math.floor(s.bet * m.multiplier(s.mines, s.safe));
        long maxWin = m.config().getLong("max_win", 0L);
        return maxWin > 0 ? Math.min(win, maxWin) : win;
    }

    private void setBet(Player p, Session s, long v) {
        long hi = max() > 0 ? max() : Long.MAX_VALUE;
        s.bet = Math.max(min(), Math.min(hi, v));
        remember(s);
        render(p, s);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private void remember(Session s) {
        last.put(s.player, new long[] { s.bet, s.mines });
    }

    private void start(Player p, Session s) {
        if (!m.isOpenFor(p))
            return;
        if (!TokenWallet.take(p, s.bet)) {
            p.sendMessage(m.msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", GameModule.units(TokenWallet.balance(p))));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        remember(s);
        s.playing = true;
        s.over = false;
        s.lost = false;
        s.safe = 0;
        s.mine = new boolean[TILES];
        s.open = new boolean[TILES];
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < TILES; i++)
            idx.add(i);
        Collections.shuffle(idx, RNG);
        for (int i = 0; i < s.mines; i++)
            s.mine[idx.get(i)] = true;
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.2f);
        render(p, s);
    }

    private void reveal(Player p, Session s, int t) {
        if (!s.playing || s.open[t])
            return;
        s.open[t] = true;
        if (s.mine[t]) {
            s.playing = false;
            s.over = true;
            s.lost = true;
            GamblingDexPlugin.recordStats(s.player, "mines", s.bet, 0L);
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
            p.sendMessage(m.msg("lost", "&c&l¡BOOM! &7Era una mina. Perdiste &e{amount}&7.", "amount", GameModule.units(s.bet)));
            render(p, s);
            return;
        }
        s.safe++;
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 0.8f + s.safe * 0.05f);
        if (s.safe >= TILES - s.mines) {
            GamblingDexPlugin.achievement(s.player, "mines_clear");
            cashOut(p, s);
            return;
        }
        render(p, s);
    }

    private void cashOut(Player p, Session s) {
        if (!s.playing)
            return;
        long pay = s.safe == 0 ? s.bet : payout(s);
        s.playing = false;
        s.over = true;
        s.lastPay = pay;
        TokenWallet.give(s.player, pay);
        if (s.safe > 0) {
            GamblingDexPlugin.recordStats(s.player, "mines", s.bet, pay);
            if (p != null) {
                p.sendMessage(m.msg("cashed_out", "&a&l¡RETIRASTE! &7en &fx{mult} &7→ &e+{amount}",
                        "mult", MinesModule.fmt(m.multiplier(s.mines, s.safe)), "amount", GameModule.units(pay)));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
            }
        } else if (p != null) {
            p.sendMessage(m.msg("refunded", "&7No abriste ninguna casilla: se te devolvieron &e{amount}&7.",
                    "amount", GameModule.units(pay)));
        }
        if (p != null && p.getOpenInventory().getTopInventory().getHolder() == s)
            render(p, s);
    }

    /** Al apagar o poner en mantenimiento: cobrar las partidas abiertas. */
    void closeAll() {
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            cashOut(p, s);
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
        int slot = e.getRawSlot();
        boolean shift = e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT;

        int tile = tileAt(slot);
        if (tile >= 0) {
            if (s.playing)
                reveal(p, s, tile);
            return;
        }
        switch (slot) {
            case S_ACTION -> {
                if (s.playing)
                    cashOut(p, s);
                else
                    start(p, s);
            }
            case S_CLOSE -> p.closeInventory();
            default -> {
                if (s.playing)
                    return;
                switch (slot) {
                    case S_MIN -> setBet(p, s, min());
                    case S_HALF -> setBet(p, s, s.bet / 2);
                    case S_DOUBLE -> setBet(p, s, s.bet * 2);
                    case S_MAX -> setBet(p, s, Math.max(min(), TokenWallet.balance(p)));
                    case S_CUSTOM -> {
                        long mines = s.mines;
                        p.closeInventory();
                        AmountPickerMenu.open(p, "&c&lMines &8- &eTu apuesta", min(), max(), s.bet,
                                List.of("&7Minas: &f" + mines), amount -> {
                                    last.put(p.getUniqueId(), new long[] { amount, mines });
                                    open(p);
                                }, () -> open(p));
                    }
                    case S_LESS, S_MORE -> {
                        int d = (slot == S_LESS ? -1 : 1) * (shift ? 5 : 1);
                        s.mines = Math.max(1, Math.min(TILES - 1, s.mines + d));
                        s.over = false;
                        s.open = new boolean[TILES];
                        remember(s);
                        render(p, s);
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
                    }
                    default -> {
                    }
                }
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
        Player p = e.getPlayer() instanceof Player pl ? pl : null;
        if (s.playing)
            cashOut(p, s); // cerrar = retirarse con lo que lleva
        sessions.remove(s.player, s);
    }
}
