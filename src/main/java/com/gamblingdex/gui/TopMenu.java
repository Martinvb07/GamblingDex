package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.modules.GameModule;
import com.gamblingdex.stats.GameStats;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * /gdx top como menú: podio con las cabezas de los 3 primeros y del 4° al 10°
 * debajo. Abajo se cambia el periodo (siempre / semana), qué se mide (ganancias,
 * mayor premio, más apostado, póker) y el juego.
 *
 * <pre>
 *        13 = #1
 *   21 = #2     23 = #3
 *   37..43 = #4..#10
 * 45 periodo · 47 medida · 49 cerrar · 51 juego · 53 tu puesto
 * </pre>
 */
public final class TopMenu implements Listener {

    private enum Measure {
        PROFIT("&a&lGanancias", Material.EMERALD), BIGGEST("&6&lMayor premio", Material.GOLD_INGOT),
        WAGERED("&e&lMás apostado", Material.GOLD_NUGGET), POKER("&5&lPóker: ganancias", Material.PAPER),
        POKER_POT("&5&lPóker: bote mayor", Material.EMERALD_BLOCK);

        final String label;
        final Material icon;

        Measure(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }

        boolean poker() {
            return this == POKER || this == POKER_POT;
        }
    }

    private static final List<String> GAMES = List.of("", "blackjack", "ruleta", "slots", "baccarat", "crash", "mines",
            "plinko", "tower", "rueda", "carrera", "coinflip", "rasca", "bingo", "loteria");
    private static final int[] SLOTS = { 13, 21, 23, 37, 38, 39, 40, 41, 42, 43 };
    private static final int S_PERIOD = 45, S_MEASURE = 47, S_CLOSE = 49, S_GAME = 51, S_ME = 53;

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        boolean week;
        int measure;
        int game;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GamblingDexPlugin plugin;

    public TopMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 54, plugin.color("&8&l✦ &6&lRANKING DEL CASINO &8&l✦"));
        render(p, h);
        p.openInventory(h.inv);
    }

    private static String u(long v) {
        return GameModule.units(v);
    }

    private List<Map.Entry<UUID, Long>> list(Holder h) {
        GameStats st = plugin.getGameStats();
        if (st == null)
            return List.of();
        Measure m = Measure.values()[h.measure];
        if (m.poker())
            return st.pokerTop(m == Measure.POKER ? GameStats.PokerMetric.PROFIT : GameStats.PokerMetric.POT, h.week);
        GameStats.Metric metric = switch (m) {
            case BIGGEST -> GameStats.Metric.BIGGEST_WIN;
            case WAGERED -> GameStats.Metric.WAGERED;
            default -> GameStats.Metric.PROFIT;
        };
        String game = GAMES.get(h.game);
        return game.isEmpty() ? st.top(metric, h.week) : st.top(game, metric, h.week);
    }

    private String value(Measure m, long v) {
        return switch (m) {
            case PROFIT, POKER -> v > 0 ? "&a+" + u(v) : v < 0 ? "&c-" + u(-v) : "&e0";
            default -> "&e" + u(v);
        };
    }

    private void render(Player viewer, Holder h) {
        Inventory inv = h.inv;
        inv.clear();
        Measure m = Measure.values()[h.measure];
        String game = m.poker() ? "Póker" : GAMES.get(h.game).isEmpty() ? "Todos los juegos" : GameStats.gameName(GAMES.get(h.game));
        List<Map.Entry<UUID, Long>> top = list(h);

        String[] medal = { "&6&l#1", "&f&l#2", "&c&l#3" };
        for (int i = 0; i < SLOTS.length; i++) {
            if (i < top.size()) {
                Map.Entry<UUID, Long> e = top.get(i);
                String name = nameOf(e.getKey());
                ItemStack head = Icons.of(Material.PLAYER_HEAD, 1, (i < 3 ? medal[i] : "&7#" + (i + 1)) + " &f" + name,
                        List.of(m.label + "&7: " + value(m, e.getValue()), "&7" + game,
                                h.week ? "&8Esta semana" : "&8Desde siempre"), i == 0);
                if (head.getItemMeta() instanceof SkullMeta sm) {
                    sm.setOwningPlayer(Bukkit.getOfflinePlayer(e.getKey()));
                    head.setItemMeta(sm);
                }
                inv.setItem(SLOTS[i], head);
            } else {
                inv.setItem(SLOTS[i], Icons.of(Material.SKELETON_SKULL, (i < 3 ? medal[i] : "&7#" + (i + 1)) + " &8Libre",
                        List.of("&7Nadie aún. ¡Puede ser tuyo!")));
            }
        }
        // Podio
        inv.setItem(22, Icons.of(Material.GOLD_BLOCK, "&6&l1°", null));
        inv.setItem(30, Icons.of(Material.IRON_BLOCK, "&f&l2°", null));
        inv.setItem(32, Icons.of(Material.COPPER_BLOCK, "&c&l3°", null));
        for (int c = 0; c < 9; c++)
            inv.setItem(c, Icons.of(Material.YELLOW_STAINED_GLASS_PANE, " ", null));
        inv.setItem(4, Icons.of(Material.GOLDEN_HELMET, 1, "&6&lRANKING", List.of(m.label, "&7" + game,
                h.week ? "&7Esta semana" : "&7Desde siempre"), true));

        // Controles
        inv.setItem(S_PERIOD, Icons.of(Material.CLOCK, 1, h.week ? "&b&lEsta semana" : "&e&lDesde siempre",
                List.of("&7Click para ver " + (h.week ? "desde siempre" : "esta semana")), false));
        List<String> ml = new ArrayList<>();
        for (int i = 0; i < Measure.values().length; i++)
            ml.add((i == h.measure ? "&a▶ " : "&8  ") + Measure.values()[i].label);
        ml.add("");
        ml.add("&7Click: siguiente &8| &7Derecho: anterior");
        inv.setItem(S_MEASURE, Icons.of(m.icon, 1, "&fMedida: " + m.label, ml, true));
        if (m.poker()) {
            inv.setItem(S_GAME, Icons.of(Material.GRAY_DYE, "&8Juego: Póker", List.of("&7Los tops de póker son aparte")));
        } else {
            inv.setItem(S_GAME, Icons.of(Material.COMPASS, 1, "&fJuego: &e" + game,
                    List.of("&7Click: siguiente &8| &7Derecho: anterior"), false));
        }
        inv.setItem(S_CLOSE, Icons.of(Material.BARRIER, "&cCerrar", null));

        int pos = 0;
        long mine = 0;
        for (int i = 0; i < top.size(); i++)
            if (top.get(i).getKey().equals(viewer.getUniqueId())) {
                pos = i + 1;
                mine = top.get(i).getValue();
                break;
            }
        ItemStack me = Icons.of(Material.PLAYER_HEAD, 1, "&fTu puesto: " + (pos > 0 ? "&6#" + pos : "&8sin puesto"),
                List.of(pos > 0 ? m.label + "&7: " + value(m, mine) : "&7Juega para entrar al ranking", "",
                        "&7Click para ver tu perfil"), false);
        if (me.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(viewer);
            me.setItemMeta(sm);
        }
        inv.setItem(S_ME, me);
        Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    private String nameOf(UUID id) {
        String n = plugin.getPlayerIndex() == null ? null : plugin.getPlayerIndex().getLastKnownName(id);
        if (n == null || n.isBlank())
            n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h))
            return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory())
            return;
        int step = e.isRightClick() ? -1 : 1;
        switch (e.getRawSlot()) {
            case S_PERIOD -> h.week = !h.week;
            case S_MEASURE -> h.measure = Math.floorMod(h.measure + step, Measure.values().length);
            case S_GAME -> {
                if (Measure.values()[h.measure].poker())
                    return;
                h.game = Math.floorMod(h.game + step, GAMES.size());
            }
            case S_CLOSE -> {
                p.closeInventory();
                return;
            }
            case S_ME -> {
                if (plugin.getInspectMenu() != null)
                    plugin.getInspectMenu().openProfile(p);
                return;
            }
            default -> {
                return;
            }
        }
        render(p, h);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder)
            e.setCancelled(true);
    }
}
