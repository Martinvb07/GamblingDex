package com.gamblingdex.modules.tower;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.gui.AmountPickerMenu;
import com.gamblingdex.gui.Icons;
import com.gamblingdex.modules.GameModule;
import com.gamblingdex.modules.tower.TowerModule.Difficulty;
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
 * Menú de Tower (estación de un bloque). Arriba se ve una ventana de 5 pisos de
 * la torre (el más alto arriba) que sube a medida que el jugador avanza; abajo,
 * la apuesta, la dificultad y el botón de jugar/subir/retirarse.
 *
 * <pre>
 *  col 0: piso y multiplicador | cols 1..7: puertas | col 8: "estás aquí"
 *  45 mín · 46 ÷2 · 47 apuesta · 48 x2 · 49 máx · 50 dificultad · 51 info · 52 fichas · 53 acción
 * </pre>
 */
final class TowerMenu implements Listener {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int ROWS = 5;

    private static final int S_MIN = 45, S_HALF = 46, S_CUSTOM = 47, S_DOUBLE = 48, S_MAX = 49;
    private static final int S_DIFF = 50, S_INFO = 51, S_BALANCE = 52, S_ACTION = 53;

    static final class Session implements InventoryHolder {
        final UUID player;
        Inventory inv;
        long bet;
        int diff;
        boolean playing;
        boolean over;
        boolean lost;
        boolean[][] trap = new boolean[0][0];
        int[] chosen = new int[0]; // puerta elegida en cada piso (-1 = ninguna)
        int cleared;               // pisos superados
        long lastPay;

        Session(UUID player, long bet, int diff) {
            this.player = player;
            this.bet = bet;
            this.diff = diff;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final TowerModule m;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, long[]> last = new HashMap<>(); // apuesta y dificultad de la última vez

    TowerMenu(TowerModule module) {
        this.m = module;
    }

    private long min() {
        return Math.max(1, m.config().getLong("min_bet", 10));
    }

    private long max() {
        return Math.max(0, m.config().getLong("max_bet", 0));
    }

    private Difficulty diff(Session s) {
        List<Difficulty> all = m.difficulties();
        return all.get(Math.floorMod(s.diff, all.size()));
    }

    /** Columnas de las puertas según cuántas haya. */
    private static int[] doorCols(int doors) {
        return switch (doors) {
            case 2 -> new int[] { 3, 5 };
            case 4 -> new int[] { 1, 3, 5, 7 };
            default -> new int[] { 2, 4, 6 };
        };
    }

    /** Piso más bajo visible en la ventana de 5 pisos. */
    private int base(Session s, int floors) {
        return Math.max(0, Math.min(floors - ROWS, s.cleared - 1));
    }

    // ------------------------------------------------------------------

    void open(Player p) {
        Session old = sessions.get(p.getUniqueId());
        if (old != null && old.playing) {
            p.openInventory(old.inv);
            return;
        }
        long[] l = last.getOrDefault(p.getUniqueId(), new long[] { min(), defaultDiff() });
        Session s = new Session(p.getUniqueId(), Math.max(min(), l[0]), (int) l[1]);
        s.inv = Bukkit.createInventory(s, 54, m.color("&8&l✦ &b&lTOWER &8&l✦"));
        sessions.put(p.getUniqueId(), s);
        render(p, s);
        p.openInventory(s.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
    }

    private int defaultDiff() {
        List<Difficulty> all = m.difficulties();
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).id().equalsIgnoreCase("normal"))
                return i;
        return 0;
    }

    private void render(Player p, Session s) {
        Inventory inv = s.inv;
        inv.clear();
        Difficulty d = diff(s);
        int floors = m.floors();
        int[] cols = doorCols(d.doors());
        String u = GameModule.units(s.bet);
        int base = base(s, floors);

        // Torre
        for (int r = 0; r < ROWS && r < floors; r++) {
            int f = base + (Math.min(ROWS, floors) - 1) - r;
            int row = r * 9;
            boolean current = s.playing && f == s.cleared;
            boolean done = f < s.cleared;
            double mult = m.multiplier(d, f + 1);

            Material label = done ? Material.LIME_DYE : current ? Material.YELLOW_DYE : Material.GRAY_DYE;
            String color = done ? "&a" : current ? "&e&l" : "&7";
            inv.setItem(row, Icons.of(label, f + 1, color + "Piso " + (f + 1) + " &8· &fx" + TowerModule.fmt(mult),
                    List.of(done ? "&aSuperado" : current ? "&eElige una puerta" : "&8Por subir",
                            "&7Cobrarías: &e" + GameModule.units(capped((long) Math.floor(s.bet * mult)))),
                    current));

            for (int i = 0; i < cols.length; i++) {
                int slot = row + cols[i];
                boolean picked = f < s.chosen.length && s.chosen[f] == i;
                boolean isTrap = f < s.trap.length && s.trap[f][i];
                if (s.over && f < s.trap.length) {
                    if (isTrap)
                        inv.setItem(slot, Icons.of(Material.TNT, 1, picked ? "&c&l¡TRAMPA!" : "&cTrampa", null, picked));
                    else if (picked)
                        inv.setItem(slot, Icons.of(Material.EMERALD_BLOCK, 1, "&a&lSegura", null, true));
                    else
                        inv.setItem(slot, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&aSegura", null));
                } else if (done) {
                    inv.setItem(slot, picked
                            ? Icons.of(Material.EMERALD_BLOCK, 1, "&a&lSegura", null, true)
                            : Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&8?", null));
                } else if (current) {
                    inv.setItem(slot, Icons.of(Material.OAK_DOOR, 1, "&e&lPuerta " + (i + 1), List.of(
                            "&7Click para abrirla",
                            "&7Si es segura: &ax" + TowerModule.fmt(mult) + " &8(&e" + GameModule.units(capped((long) Math.floor(s.bet * mult))) + "&8)",
                            "&8Trampas en este piso: " + d.traps() + "/" + d.doors()), false));
                } else {
                    inv.setItem(slot, Icons.of(Material.IRON_DOOR, "&8Puerta cerrada",
                            List.of(s.playing ? "&7Sube hasta aquí para abrirla" : "&7Elige tu apuesta y pulsa &a&lJUGAR")));
                }
            }
            if (current)
                inv.setItem(row + 8, Icons.of(Material.ARROW, 1, "&e&l◀ Estás aquí", List.of("&7Piso " + (f + 1) + " de " + floors), true));
            else if (f == floors - 1)
                inv.setItem(row + 8, Icons.of(Material.NETHER_STAR, 1, "&6&lCima",
                        List.of("&7Llega arriba y cobra &fx" + TowerModule.fmt(m.multiplier(d, floors))), true));
        }

        // Fila inferior
        if (s.playing) {
            for (int i = S_MIN; i <= S_DIFF; i++)
                inv.setItem(i, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&8Partida en curso", null));
        } else {
            inv.setItem(S_MIN, Icons.of(Material.IRON_NUGGET, "&fMínimo", List.of("&7Apuesta " + GameModule.units(min()))));
            inv.setItem(S_HALF, Icons.of(Material.RED_STAINED_GLASS_PANE, "&c÷2", List.of("&7Mitad de la apuesta")));
            inv.setItem(S_CUSTOM, Icons.of(Material.GOLD_NUGGET, "&6Apuesta: &e" + u, List.of("&7Click para escribir otra cantidad")));
            inv.setItem(S_DOUBLE, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&ax2", List.of("&7Doble de la apuesta")));
            inv.setItem(S_MAX, Icons.of(Material.GOLD_BLOCK, "&6Máximo", List.of("&7Todas tus fichas" + (max() > 0 ? " (hasta " + GameModule.units(max()) + ")" : ""))));
            List<String> lore = new ArrayList<>();
            for (Difficulty o : m.difficulties())
                lore.add((o.id().equals(d.id()) ? "&a▶ " : "&8  ") + o.name() + " &8(" + o.traps() + " de " + o.doors() + " puertas con trampa)");
            lore.add("");
            lore.add("&7Click: siguiente &8| &7Shift: anterior");
            inv.setItem(S_DIFF, Icons.of(Material.COMPARATOR, 1, "&bDificultad: " + d.name(), lore, true));
        }
        inv.setItem(S_INFO, Icons.of(Material.BOOK, 1, "&b&lTOWER", List.of(
                "&7Sube la torre piso a piso.",
                "&7En cada piso elige una puerta:",
                "&7detrás de alguna hay una &ctrampa&7.",
                "&7Cada piso superado sube el premio.",
                "&7Retírate cuando quieras; si caes",
                "&7en una trampa pierdes la apuesta.", "",
                "&7Dificultad: " + d.name(),
                "&7Primer piso: &fx" + TowerModule.fmt(m.multiplier(d, 1)),
                "&7Cima (" + floors + " pisos): &fx" + TowerModule.fmt(m.multiplier(d, floors))), false));
        inv.setItem(S_BALANCE, Icons.of(Material.SUNFLOWER, "&7Tus fichas: &e" + GameModule.units(TokenWallet.balance(p)), null));

        if (s.playing) {
            long pay = pay(s);
            inv.setItem(S_ACTION, Icons.of(Material.EMERALD_BLOCK, 1, "&a&lRETIRARSE &8» &e" + GameModule.units(pay), List.of(
                    "&7Cobras tu apuesta x" + TowerModule.fmt(s.cleared == 0 ? 1.0 : m.multiplier(d, s.cleared)),
                    s.cleared == 0 ? "&8(sin subir ningún piso se te devuelve)" : "&7Pisos superados: &f" + s.cleared,
                    "&8Cerrar el menú también te retira"), s.cleared > 0));
        } else if (s.over) {
            inv.setItem(S_ACTION, Icons.of(Material.LIME_CONCRETE, 1, "&a&lJUGAR OTRA VEZ", List.of(
                    s.lost ? "&cCaíste en una trampa." : "&aCobraste &e" + GameModule.units(s.lastPay),
                    "", "&7Apuesta: &e" + u + " &8| &7" + d.name()), true));
        } else {
            inv.setItem(S_ACTION, Icons.of(Material.LIME_CONCRETE, 1, "&a&lJUGAR", List.of(
                    "&7Apuesta: &e" + u, "&7Dificultad: " + d.name(),
                    "&7Primer piso: &fx" + TowerModule.fmt(m.multiplier(d, 1)),
                    "&7Cima: &fx" + TowerModule.fmt(m.multiplier(d, floors))), true));
        }
        Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    private long capped(long win) {
        long maxWin = m.config().getLong("max_win", 0L);
        return maxWin > 0 ? Math.min(win, maxWin) : win;
    }

    private long pay(Session s) {
        return s.cleared == 0 ? s.bet : capped((long) Math.floor(s.bet * m.multiplier(diff(s), s.cleared)));
    }

    private void setBet(Player p, Session s, long v) {
        long hi = max() > 0 ? max() : Long.MAX_VALUE;
        s.bet = Math.max(min(), Math.min(hi, v));
        remember(s);
        render(p, s);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private void remember(Session s) {
        last.put(s.player, new long[] { s.bet, s.diff });
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
        Difficulty d = diff(s);
        int floors = m.floors();
        s.playing = true;
        s.over = false;
        s.lost = false;
        s.cleared = 0;
        s.trap = new boolean[floors][d.doors()];
        s.chosen = new int[floors];
        Arrays.fill(s.chosen, -1);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < d.doors(); i++)
            idx.add(i);
        for (int f = 0; f < floors; f++) {
            Collections.shuffle(idx, RNG);
            for (int t = 0; t < d.traps(); t++)
                s.trap[f][idx.get(t)] = true;
        }
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.2f);
        render(p, s);
    }

    private void pick(Player p, Session s, int door) {
        if (!s.playing || s.cleared >= s.chosen.length)
            return;
        int f = s.cleared;
        s.chosen[f] = door;
        if (s.trap[f][door]) {
            s.playing = false;
            s.over = true;
            s.lost = true;
            GamblingDexPlugin.recordStats(s.player, "tower", s.bet, 0L);
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
            p.sendMessage(m.msg("lost", "&c&l¡TRAMPA! &7en el piso &f{floor}&7. Perdiste &e{amount}&7.",
                    "floor", String.valueOf(f + 1), "amount", GameModule.units(s.bet)));
            render(p, s);
            return;
        }
        s.cleared++;
        p.playSound(p.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 1.0f);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 0.8f + s.cleared * 0.08f);
        if (s.cleared >= s.chosen.length) {
            GamblingDexPlugin.achievement(s.player, "tower_top");
            cashOut(p, s);
            return;
        }
        render(p, s);
    }

    private void cashOut(Player p, Session s) {
        if (!s.playing)
            return;
        long pay = pay(s);
        s.playing = false;
        s.over = true;
        s.lastPay = pay;
        TokenWallet.give(s.player, pay);
        if (s.cleared > 0) {
            GamblingDexPlugin.recordStats(s.player, "tower", s.bet, pay);
            if (p != null) {
                boolean top = s.cleared >= s.chosen.length;
                p.sendMessage(top
                        ? m.msg("top", "&6&l¡CIMA! &7Subiste los &f{floors}&7 pisos &fx{mult} &7→ &e+{amount}",
                                "floors", String.valueOf(s.cleared), "mult", TowerModule.fmt(m.multiplier(diff(s), s.cleared)),
                                "amount", GameModule.units(pay))
                        : m.msg("cashed_out", "&a&l¡RETIRASTE! &7en el piso &f{floor}&7 &fx{mult} &7→ &e+{amount}",
                                "floor", String.valueOf(s.cleared), "mult", TowerModule.fmt(m.multiplier(diff(s), s.cleared)),
                                "amount", GameModule.units(pay)));
                p.playSound(p.getLocation(), top ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
            }
        } else if (p != null) {
            p.sendMessage(m.msg("refunded", "&7No subiste ningún piso: se te devolvieron &e{amount}&7.",
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

        if (slot < ROWS * 9) {
            if (!s.playing)
                return;
            int floors = m.floors();
            int r = slot / 9;
            int f = base(s, floors) + (Math.min(ROWS, floors) - 1) - r;
            if (f != s.cleared)
                return;
            int[] cols = doorCols(diff(s).doors());
            for (int i = 0; i < cols.length; i++)
                if (cols[i] == slot % 9)
                    pick(p, s, i);
            return;
        }
        if (slot == S_ACTION) {
            if (s.playing)
                cashOut(p, s);
            else
                start(p, s);
            return;
        }
        if (s.playing)
            return;
        switch (slot) {
            case S_MIN -> setBet(p, s, min());
            case S_HALF -> setBet(p, s, s.bet / 2);
            case S_DOUBLE -> setBet(p, s, s.bet * 2);
            case S_MAX -> setBet(p, s, Math.max(min(), TokenWallet.balance(p)));
            case S_CUSTOM -> {
                int diff = s.diff;
                String name = diff(s).name();
                p.closeInventory();
                AmountPickerMenu.open(p, "&b&lTower &8- &eTu apuesta", min(), max(), s.bet,
                        List.of("&7Dificultad: " + name), amount -> {
                            last.put(p.getUniqueId(), new long[] { amount, diff });
                            open(p);
                        }, () -> open(p));
            }
            case S_DIFF -> {
                int n = m.difficulties().size();
                s.diff = Math.floorMod(s.diff + (shift ? -1 : 1), n);
                s.over = false;
                s.cleared = 0;
                s.trap = new boolean[0][0];
                s.chosen = new int[0];
                remember(s);
                render(p, s);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
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
        Player p = e.getPlayer() instanceof Player pl ? pl : null;
        if (s.playing)
            cashOut(p, s); // cerrar = retirarse con lo que lleva
        sessions.remove(s.player, s);
    }
}
