package com.gamblingdex.modules.crash;

import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.gui.AmountPickerMenu;
import com.gamblingdex.gui.Icons;
import com.gamblingdex.modules.GameModule;
import com.gamblingdex.modules.crash.CrashModule.Bet;
import com.gamblingdex.modules.crash.CrashModule.State;
import com.gamblingdex.modules.crash.CrashModule.Station;
import com.gamblingdex.pack.CasinoPack;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * Menú de una mesa de Crash: se actualiza solo mientras está abierto.
 *
 * <pre>
 *  0..8   dónde explotaron las últimas rondas (la más reciente a la izquierda)
 *  9      multiplicador · 18 estado · 27 tu apuesta
 *  10..34 gráfico: el cohete sube por las columnas 1..7 (filas 1-3) dejando estela
 *  17     jugadores · 26 mayor retiro de la ronda
 *  36..44 jugadores de la ronda (cabezas)
 *  45 mín · 46 ÷2 · 47 apuesta · 48 x2 · 49 máx · 50 retiro automático · 51 info · 52 fichas · 53 APOSTAR / RETIRAR
 * </pre>
 */
final class CrashMenu implements Listener {

    private static final int S_MULT = 9, S_STATE = 18, S_MINE = 27, S_PLAYERS = 17, S_BEST = 26;
    private static final int S_MIN = 45, S_HALF = 46, S_CUSTOM = 47, S_DOUBLE = 48, S_MAX = 49;
    private static final int S_AUTO = 50, S_INFO = 51, S_BALANCE = 52, S_ACTION = 53;
    private static final double[] AUTO = { 0, 1.5, 2, 3, 5, 10, 25 };

    static final class Session implements InventoryHolder {
        final UUID player;
        final Station st;
        Inventory inv;

        Session(UUID player, Station st) {
            this.player = player;
            this.st = st;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final CrashModule m;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastBet = new HashMap<>();
    private final Map<UUID, Integer> autoIdx = new HashMap<>();

    CrashMenu(CrashModule module) {
        this.m = module;
    }

    private long min() {
        return Math.max(1, m.config().getLong("min_bet", 10));
    }

    private long max() {
        return Math.max(0, m.config().getLong("max_bet", 0));
    }

    private long bet(UUID id) {
        long hi = max() > 0 ? max() : Long.MAX_VALUE;
        return Math.max(min(), Math.min(hi, lastBet.getOrDefault(id, min())));
    }

    private double auto(UUID id) {
        return AUTO[Math.floorMod(autoIdx.getOrDefault(id, 0), AUTO.length)];
    }

    void open(Player p, Station st) {
        Session s = new Session(p.getUniqueId(), st);
        s.inv = CasinoPack.customGui()
                ? Bukkit.createInventory(s, 54, CasinoPack.titleWithBackground(CasinoPack.CRASH_BG, m.color("&c&lCRASH")))
                : Bukkit.createInventory(s, 54, m.color("&8&l✦ &c&lCRASH &8&l✦"));
        sessions.put(p.getUniqueId(), s);
        render(p, s);
        p.openInventory(s.inv);
        CasinoPack.sound(p, "slots.coin", Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
    }

    /** Lo llama el módulo cada pocos ticks: redibuja los menús abiertos de esa mesa. */
    void refresh(Station st) {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (s.st != st)
                continue;
            Player p = Bukkit.getPlayer(s.player);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() == s)
                render(p, s);
        }
    }

    void closeAll() {
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() == s)
                p.closeInventory();
        }
        sessions.clear();
    }

    private static String multColor(double x) {
        return x < 2 ? "&a" : x < 5 ? "&e" : x < 10 ? "&6" : "&c";
    }

    private void render(Player p, Session s) {
        Inventory inv = s.inv;
        Station st = s.st;
        boolean custom = CasinoPack.customGui();
        inv.clear();
        Bet mine = st.bets.get(p.getUniqueId());
        long bet = bet(p.getUniqueId());
        double auto = auto(p.getUniqueId());

        // Historial
        int i = 0;
        for (double x : m.recentCrashes) {
            if (i >= 9)
                break;
            Material mat = x < 2 ? Material.RED_CONCRETE : x < 10 ? Material.LIME_CONCRETE : Material.GOLD_BLOCK;
            inv.setItem(i, Icons.of(mat, 1, multColor(x) + "&lx" + CrashModule.fmt(x),
                    List.of(i == 0 ? "&7Última ronda" : "&7Hace " + (i + 1) + " rondas"), false));
            i++;
        }

        // Gráfico: progreso en escala logarítmica hasta x10
        double shown = st.state == State.BETTING ? 1.0 : st.state == State.RUNNING ? st.multiplier : st.crashPoint;
        double prog = Math.max(0, Math.min(1, Math.log(shown) / Math.log(10)));
        int head = (int) Math.round(prog * 6); // 0..6 → columnas 1..7
        for (int c = 0; c <= head; c++) {
            int row = 3 - (int) Math.round(c / 6.0 * 2); // fila 3 (abajo) → 1 (arriba)
            int slot = row * 9 + 1 + c;
            if (c < head)
                inv.setItem(slot, Icons.of(st.state == State.PAUSE ? Material.RED_STAINED_GLASS_PANE
                        : Material.LIME_STAINED_GLASS_PANE, "&7Estela", null));
            else if (st.state == State.PAUSE)
                inv.setItem(slot, Icons.of(Material.TNT, 1, "&c&l¡EXPLOTÓ! &fx" + CrashModule.fmt(st.crashPoint), null, true));
            else
                inv.setItem(slot, Icons.of(Material.FIREWORK_ROCKET, 1,
                        st.state == State.BETTING ? "&eEn la plataforma" : "&a&lx" + CrashModule.fmt(st.multiplier), null,
                        st.state == State.RUNNING));
        }

        // Columna izquierda
        String multName = switch (st.state) {
            case BETTING -> "&e&lx1.00";
            case RUNNING -> multColor(st.multiplier) + "&lx" + CrashModule.fmt(st.multiplier);
            case PAUSE -> "&c&lx" + CrashModule.fmt(st.crashPoint);
        };
        inv.setItem(S_MULT, Icons.of(st.state == State.PAUSE ? Material.TNT : Material.EXPERIENCE_BOTTLE, 1, multName,
                List.of(st.state == State.PAUSE ? "&7Explotó aquí" : "&7Multiplicador actual"), st.state == State.RUNNING));
        inv.setItem(S_STATE, Icons.of(Material.CLOCK, 1, switch (st.state) {
            case BETTING -> "&eApuestas abiertas: &f" + st.secondsLeft + "s";
            case RUNNING -> "&a&lEn vuelo";
            case PAUSE -> "&cSiguiente ronda en &f" + st.secondsLeft + "s";
        }, null, false));
        List<String> mineLore = new ArrayList<>();
        String mineName;
        if (mine == null) {
            mineName = "&7No apostaste en esta ronda";
        } else if (mine.cashedAt > 0) {
            long pay = m.payoutFor(mine, mine.cashedAt);
            mineName = "&a&lRetiraste en x" + CrashModule.fmt(mine.cashedAt);
            mineLore.add("&7Cobraste &e" + GameModule.units(pay) + " &a(+" + GameModule.units(pay - mine.amount) + ")");
        } else {
            mineName = "&6Tu apuesta: &e" + GameModule.units(mine.amount);
            if (st.state == State.RUNNING)
                mineLore.add("&7Cobrarías: &e" + GameModule.units(m.payoutFor(mine, st.multiplier)));
            if (mine.auto > 0)
                mineLore.add("&7Retiro automático: &fx" + CrashModule.fmt(mine.auto));
            if (st.state == State.PAUSE)
                mineLore.add("&cLa perdiste");
        }
        inv.setItem(S_MINE, Icons.of(Material.GOLD_NUGGET, 1, mineName, mineLore, mine != null && mine.cashedAt > 0));

        // Columna derecha
        inv.setItem(S_PLAYERS, Icons.of(Material.PLAYER_HEAD, 1, "&fJugadores: &e" + st.bets.size(), null, false));
        double best = 0;
        for (Bet b : st.bets.values())
            best = Math.max(best, b.cashedAt);
        inv.setItem(S_BEST, Icons.of(Material.GOLD_INGOT, 1, best > 0 ? "&6Mejor retiro: &fx" + CrashModule.fmt(best)
                : "&7Nadie retiró aún", null, false));

        // Jugadores de la ronda
        int slot = 36;
        for (Map.Entry<UUID, Bet> e : st.bets.entrySet()) {
            if (slot > 44)
                break;
            Bet b = e.getValue();
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            String status = b.cashedAt > 0 ? "&aRetiró en x" + CrashModule.fmt(b.cashedAt)
                    : st.state == State.PAUSE ? "&cPerdió" : st.state == State.RUNNING ? "&eEn vuelo" : "&7Esperando";
            inv.setItem(slot++, head(op, "&f" + Optional.ofNullable(op.getName()).orElse("?"),
                    List.of("&7Apuesta: &e" + GameModule.units(b.amount), status)));
        }

        // Controles
        boolean canBet = st.state == State.BETTING && mine == null;
        String u = GameModule.units(bet);
        if (canBet || !custom) {
            inv.setItem(S_MIN, Icons.of(Material.IRON_NUGGET, "&fMínimo", List.of("&7Apuesta " + GameModule.units(min()))));
            inv.setItem(S_HALF, Icons.of(Material.RED_STAINED_GLASS_PANE, "&c÷2", List.of("&7Mitad de la apuesta")));
            inv.setItem(S_CUSTOM, Icons.of(Material.GOLD_NUGGET, "&6Apuesta: &e" + u, List.of("&7Click para escribir otra cantidad")));
            inv.setItem(S_DOUBLE, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&ax2", List.of("&7Doble de la apuesta")));
            inv.setItem(S_MAX, Icons.of(Material.GOLD_BLOCK, "&6Máximo", List.of("&7Todas tus fichas" + (max() > 0 ? " (hasta " + GameModule.units(max()) + ")" : ""))));
            inv.setItem(S_AUTO, Icons.of(Material.REPEATER, 1, auto > 0 ? "&bRetiro automático: &fx" + CrashModule.fmt(auto)
                    : "&bRetiro automático: &7no", List.of("&7Se retira solo al llegar ahí", "&7Click: siguiente &8| &7Shift: anterior"), auto > 0));
        }
        inv.setItem(S_INFO, Icons.of(Material.BOOK, 1, "&c&lCRASH", List.of(
                "&7El multiplicador sube desde x1.00",
                "&7hasta que el cohete &cexplota&7.",
                "&7Retírate antes y cobras tu apuesta",
                "&7por el multiplicador; si explota",
                "&7antes, la pierdes.", "",
                "&7Shift + click derecho a la mesa",
                "&7también te retira."), false));
        inv.setItem(S_BALANCE, Icons.of(Material.SUNFLOWER, "&7Tus fichas: &e" + GameModule.units(TokenWallet.balance(p)), null));

        ItemStack action;
        if (canBet) {
            action = Icons.of(Material.LIME_CONCRETE, 1, "&a&lAPOSTAR &8» &e" + u, List.of(
                    "&7Despega en &f" + st.secondsLeft + "s",
                    auto > 0 ? "&7Retiro automático: &fx" + CrashModule.fmt(auto) : "&7Sin retiro automático"), true);
        } else if (st.state == State.BETTING) {
            action = Icons.of(Material.CLOCK, 1, "&eApostaste &6" + GameModule.units(mine.amount),
                    List.of("&7Despega en &f" + st.secondsLeft + "s"), false);
        } else if (st.state == State.RUNNING && mine != null && mine.cashedAt == 0) {
            long pay = m.payoutFor(mine, st.multiplier);
            action = Icons.of(Material.EMERALD_BLOCK, 1, "&a&lRETIRAR &8» &e" + GameModule.units(pay),
                    List.of("&7En &fx" + CrashModule.fmt(st.multiplier), "&a+" + GameModule.units(pay - mine.amount)), true);
        } else if (st.state == State.RUNNING) {
            action = Icons.of(Material.GRAY_CONCRETE, 1, mine != null ? "&aYa retiraste" : "&7Ronda en curso",
                    List.of("&7Apuesta en la siguiente"), false);
        } else {
            action = Icons.of(Material.RED_CONCRETE, 1, "&cExplotó en &fx" + CrashModule.fmt(st.crashPoint),
                    List.of("&7Siguiente ronda en &f" + st.secondsLeft + "s"), false);
        }
        inv.setItem(S_ACTION, action);
        if (!custom)
            Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack head(OfflinePlayer op, String name, List<String> lore) {
        ItemStack it = Icons.of(Material.PLAYER_HEAD, 1, name, lore, false);
        if (it.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(op);
            it.setItemMeta(sm);
        }
        return it;
    }

    private void setBet(Player p, Session s, long v) {
        long hi = max() > 0 ? max() : Long.MAX_VALUE;
        lastBet.put(p.getUniqueId(), Math.max(min(), Math.min(hi, v)));
        render(p, s);
        CasinoPack.sound(p, "slots.coin", Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
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
        Station st = s.st;
        Bet mine = st.bets.get(p.getUniqueId());
        boolean canBet = st.state == State.BETTING && mine == null;
        switch (e.getRawSlot()) {
            case S_ACTION -> {
                if (canBet) {
                    m.placeBet(p, st, bet(p.getUniqueId()), auto(p.getUniqueId()));
                    render(p, s);
                } else if (st.state == State.RUNNING && mine != null && mine.cashedAt == 0) {
                    m.cashOut(p, st);
                    render(p, s);
                }
            }
            case S_MIN -> {
                if (canBet)
                    setBet(p, s, min());
            }
            case S_HALF -> {
                if (canBet)
                    setBet(p, s, bet(p.getUniqueId()) / 2);
            }
            case S_DOUBLE -> {
                if (canBet)
                    setBet(p, s, bet(p.getUniqueId()) * 2);
            }
            case S_MAX -> {
                if (canBet)
                    setBet(p, s, Math.max(min(), TokenWallet.balance(p)));
            }
            case S_AUTO -> {
                if (!canBet)
                    return;
                autoIdx.put(p.getUniqueId(), Math.floorMod(autoIdx.getOrDefault(p.getUniqueId(), 0) + (shift ? -1 : 1), AUTO.length));
                render(p, s);
                CasinoPack.sound(p, "slots.coin", Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
            }
            case S_CUSTOM -> {
                if (!canBet)
                    return;
                p.closeInventory();
                AmountPickerMenu.open(p, "&c&lCrash &8- &eTu apuesta", min(), max(), bet(p.getUniqueId()), List.of(),
                        amount -> {
                            lastBet.put(p.getUniqueId(), amount);
                            open(p, st);
                        }, () -> open(p, st));
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
        if (e.getInventory().getHolder() instanceof Session s)
            sessions.remove(s.player, s);
    }
}
