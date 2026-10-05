package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.modules.GameModule;
import com.gamblingdex.stats.GameStats;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * /gdx inspect &lt;jugador&gt; (admin): todo lo del jugador en un menú — fichas,
 * fichas de bono bloqueadas, ganancias (total y semana), juego favorito, póker,
 * logros y sus últimas apuestas.
 */
public final class InspectMenu implements Listener {

    private static final List<String> GAMES = List.of("blackjack", "ruleta", "slots", "baccarat", "crash", "mines",
            "plinko", "tower", "rueda", "carrera", "coinflip", "rasca", "bingo", "loteria");

    private static final class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GamblingDexPlugin plugin;

    public InspectMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    private static String u(long v) {
        return GameModule.units(v);
    }

    private static String signed(long v) {
        return v > 0 ? "&a+" + u(v) : v < 0 ? "&c-" + u(-v) : "&e±0";
    }

    public void open(Player viewer, UUID target, String name) {
        GameStats stats = plugin.getGameStats();
        if (stats == null)
            return;
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 54, plugin.color("&8&l✦ &6&lINSPECCIONAR &8» &f" + name));
        Inventory inv = h.inv;
        Player online = Bukkit.getPlayer(target);

        // Cabeza: fichas y bono
        List<String> head = new ArrayList<>();
        head.add(online != null ? "&a● Conectado" : "&7● Desconectado");
        head.add("");
        head.add(online != null ? "&7Fichas (tokens): &e" + u(TokenWallet.balance(online))
                : "&7Fichas: &8(solo se ven si está conectado)");
        var lock = plugin.getBonusLock();
        long locked = lock == null ? 0 : lock.locked(target);
        if (locked > 0) {
            head.add("&7Fichas de bono bloqueadas: &6" + u(locked));
            head.add("&7Le falta apostar: &f" + u(lock.remaining(target)));
        } else {
            head.add("&7Fichas de bono bloqueadas: &a0");
        }
        head.add("");
        head.add("&8" + target);
        ItemStack skull = Icons.of(Material.PLAYER_HEAD, 1, "&6&l" + name, head, false);
        if (skull.getItemMeta() instanceof SkullMeta sm) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(target);
            sm.setOwningPlayer(op);
            skull.setItemMeta(sm);
        }
        inv.setItem(4, skull);

        // Totales
        inv.setItem(19, entryIcon(Material.BOOK, "&e&lDesde siempre", stats.get(target, false)));
        inv.setItem(21, entryIcon(Material.CLOCK, "&b&lEsta semana", stats.get(target, true)));

        // Juegos (de más a menos jugado)
        List<String[]> rows = new ArrayList<>();
        String fav = null;
        long favRounds = 0;
        for (String g : GAMES) {
            GameStats.Entry e = stats.get(target, g, false);
            if (e.rounds() <= 0)
                continue;
            if (e.rounds() > favRounds) {
                favRounds = e.rounds();
                fav = g;
            }
            rows.add(new String[] { String.valueOf(e.rounds()),
                    "&f" + GameStats.gameName(g) + " &8» &7" + e.rounds() + " apuestas, " + signed(e.profit()) });
        }
        rows.sort((a, b) -> Long.compare(Long.parseLong(b[0]), Long.parseLong(a[0])));
        List<String> gl = new ArrayList<>();
        gl.add(fav == null ? "&7Aún no juega a nada." : "&7Favorito: &6&l" + GameStats.gameName(fav));
        gl.add("");
        for (int i = 0; i < Math.min(10, rows.size()); i++)
            gl.add(rows.get(i)[1]);
        inv.setItem(23, Icons.of(Material.COMPASS, 1, "&d&lJuegos", gl, fav != null));

        // Póker
        GameStats.PokerEntry pk = stats.poker(target, false);
        inv.setItem(25, Icons.of(Material.PAPER, 1, "&5&lPóker", List.of(
                "&7Manos: &f" + pk.hands() + " &8(&a" + pk.handsWon() + " &7ganadas&8)",
                "&7Ganancia: " + signed(pk.profit()),
                "&7Bote más grande: &e" + u(pk.biggestPot()),
                "&7Torneos ganados: &f" + pk.tournaments()), false));

        // Logros
        var ach = plugin.getAchievements();
        if (ach != null)
            inv.setItem(31, Icons.of(Material.GOLDEN_HELMET, 1, "&6&lLogros",
                    List.of("&7Desbloqueados: &f" + ach.count(target) + "&7/&f" + ach.total()), false));

        // Últimas apuestas
        List<GameStats.Play> plays = stats.history(target);
        SimpleDateFormat df = new SimpleDateFormat("dd/MM HH:mm");
        if (plays.isEmpty())
            inv.setItem(40, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&7Sin apuestas recientes", null));
        for (int i = 0; i < Math.min(9, plays.size()); i++) {
            GameStats.Play p = plays.get(i);
            long net = p.net();
            Material m = net > 0 ? Material.LIME_WOOL : net < 0 ? Material.RED_WOOL : Material.YELLOW_WOOL;
            inv.setItem(36 + i, Icons.of(m, 1, "&f" + GameStats.gameName(p.game()) + " &8» " + signed(net), List.of(
                    "&7Apostó: &e" + u(p.wager()),
                    "&7Cobró: &e" + u(p.payout()),
                    "&8" + df.format(new Date(p.time()))), false));
        }

        inv.setItem(49, Icons.of(Material.BARRIER, "&cCerrar", null));
        Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        viewer.openInventory(inv);
    }

    private ItemStack entryIcon(Material m, String title, GameStats.Entry e) {
        return Icons.of(m, 1, title, List.of(
                "&7Apuestas: &f" + e.rounds(),
                "&7Apostado: &e" + u(e.wagered()),
                "&7Cobrado: &e" + u(e.paid()),
                "&7Ganancia: " + signed(e.profit()),
                "&7Mayor premio: &e" + u(e.biggestWin())), false);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder))
            return;
        e.setCancelled(true);
        if (e.getRawSlot() == 49 && e.getWhoClicked() instanceof Player p)
            p.closeInventory();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder)
            e.setCancelled(true);
    }
}
