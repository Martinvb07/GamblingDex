package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.games.rouletteworld.WorldRouletteTable;
import com.gamblingdex.games.rouletteworld.WorldRouletteTables;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.*;

/**
 * Pleno con la rueda: los 38 números en el orden de la rueda americana, en dos
 * óvalos (el de afuera y el de adentro), con su color. Abajo tus fichas: eliges
 * una y la echas en los números que quieras (click = 1 ficha, shift = 5).
 *
 * <pre>
 *  0 ─ 8   óvalo de afuera (24 números)     10 ─ 16  óvalo de adentro (14)
 *  20 ficha elegida · 22 estado · 24 tus plenos (y tus fichas)
 *  45 volver · 46..52 fichas · 53 repetir
 * </pre>
 */
public class RouletteNumberMenu {

    public static final String TITLE = "§6§lRULETA §8| §ePleno";

    /** Ficha elegida por cada jugador (valor en unidades). */
    private static final Map<UUID, Integer> CHIP = new HashMap<>();

    /** Recorrido de los dos óvalos, en el sentido de las agujas del reloj. */
    private static final int[] RING;
    static {
        List<Integer> r = new ArrayList<>();
        for (int c = 0; c <= 8; c++)
            r.add(c); // arriba
        for (int row = 1; row <= 3; row++)
            r.add(row * 9 + 8); // derecha
        for (int c = 8; c >= 0; c--)
            r.add(36 + c); // abajo
        for (int row = 3; row >= 1; row--)
            r.add(row * 9); // izquierda
        for (int c = 1; c <= 7; c++)
            r.add(9 + c); // adentro: arriba
        r.add(25); // adentro: derecha
        for (int c = 7; c >= 1; c--)
            r.add(27 + c); // adentro: abajo
        r.add(19); // adentro: izquierda
        RING = r.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final int S_CHIP = 20, S_STATUS = 22, S_MINE = 24;
    private static final int S_BACK = 45, S_REPEAT = 53;
    private static final int[] CHIP_SLOTS = { 46, 47, 48, 49, 50, 51, 52 };

    private final GamblingDexPlugin plugin;

    public RouletteNumberMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    private static String u(long v) {
        return NumberFormat.getInstance(Locale.forLanguageTag("es-ES")).format(v);
    }

    /** Fichas que el jugador tiene en el inventario: valor → cantidad (de menor a mayor). */
    public static SortedMap<Integer, Integer> chipsInInventory(Player p) {
        SortedMap<Integer, Integer> out = new TreeMap<>();
        TokenManager tm = GamblingDexPlugin.getInstance().getTokenManager();
        if (tm == null)
            return out;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || !tm.isToken(it))
                continue;
            Integer v = tm.getTokenValue(it);
            if (v != null && v > 0)
                out.merge(v, it.getAmount(), Integer::sum);
        }
        return out;
    }

    /** Ficha elegida; si ya no la tiene, la más chica que tenga en el inventario. */
    public static int selectedChip(Player p) {
        SortedMap<Integer, Integer> have = chipsInInventory(p);
        Integer c = CHIP.get(p.getUniqueId());
        if (c != null && (have.isEmpty() || have.containsKey(c)))
            return c;
        return have.isEmpty() ? (c != null ? c : 1) : have.firstKey();
    }

    public static void selectChip(Player p, int value) {
        CHIP.put(p.getUniqueId(), value);
    }

    public void open(Player player, WorldRouletteTable table) {
        Inventory inv = com.gamblingdex.pack.CasinoPack.inventory(new RouletteNumberMenuHolder(table.getTableKey()), 54,
                com.gamblingdex.pack.CasinoPack.ROULETTE_NUM_BG, TITLE);
        render(player, table, inv);
        player.openInventory(inv);
    }

    /** Vuelve a dibujar el menú abierto (después de apostar o elegir ficha). */
    public void refresh(Player player, WorldRouletteTable table) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof RouletteNumberMenuHolder)
            render(player, table, top);
        else
            open(player, table);
    }

    private void render(Player player, WorldRouletteTable table, Inventory inv) {
        inv.clear();
        UUID id = player.getUniqueId();
        int chip = selectedChip(player);
        boolean open = table.getState() == WorldRouletteTable.State.COUNTDOWN;

        // Los números en los óvalos
        int[] order = WorldRouletteTables.WHEEL_ORDER;
        long totalOnNumbers = 0;
        List<String> mine = new ArrayList<>();
        for (int k = 0; k < RING.length; k++) {
            int slot = RING[k];
            if (k >= order.length) {
                if (!com.gamblingdex.pack.CasinoPack.customGui()) // con el pack, el fondo pinta una bolita
                    inv.setItem(slot, pane(Material.YELLOW_STAINED_GLASS_PANE, "§6●"));
                continue;
            }
            int n = order[k];
            long bet = table.betOnNumber(id, n);
            if (bet > 0) {
                totalOnNumbers += bet;
                mine.add("§f" + WorldRouletteTables.formatNumber(n) + " §8→ §e" + u(bet));
            }
            inv.setItem(slot, numberItem(n, bet, chip, open));
        }

        // Centro
        inv.setItem(S_CHIP, chipIcon(chip, true, "§6§lFicha elegida: §e" + u(chip),
                List.of("§7Click a un número para echar", "§7esta ficha. Shift: 5 fichas.", "",
                        "§7Cambia de ficha abajo.")));
        String status = switch (table.getState()) {
            case COUNTDOWN -> "§aApuestas abiertas §8(§f" + table.getCountdownSeconds() + "s§8)";
            case SPINNING -> "§6Girando...";
            default -> "§cApuestas cerradas";
        };
        inv.setItem(S_STATUS, button("noop", Material.CLOCK, "§6§lRULETA", List.of(status, "",
                "§7Los números van en el orden", "§7de la rueda americana.",
                "§7Pleno paga §f35 a 1 §8(36x)")));
        List<String> mineLore = new ArrayList<>();
        if (mine.isEmpty())
            mineLore.add("§7Aún no tienes fichas en números.");
        else {
            mineLore.addAll(mine);
            mineLore.add("");
            mineLore.add("§7Total en plenos: §e" + u(totalOnNumbers));
        }
        mineLore.add("");
        mineLore.add("§7Tus fichas: §e" + u(TokenWallet.balance(player)));
        mineLore.add("§7En la mesa esta ronda: §e" + table.totalBet(id));
        inv.setItem(S_MINE, button("noop", Material.WRITABLE_BOOK, "§e§lTus plenos", mineLore));

        long last = table.lastBetTotal(id);
        if (last > 0) {
            List<String> repeat = new ArrayList<>(table.describeLastBets(id));
            repeat.add("");
            repeat.add("§7Total: §e" + u(last));
            repeat.add(open ? "§eClick para apostar lo mismo otra vez" : "§cApuestas cerradas");
            inv.setItem(S_REPEAT, button("repeat", Material.EMERALD, "§a§lRepetir apuesta", repeat));
        } else {
            inv.setItem(S_REPEAT, button("noop", Material.GRAY_DYE, "§7Repetir apuesta",
                    List.of("§8Disponible después de tu primera ronda")));
        }

        // Abajo: volver, fichas, saldo
        inv.setItem(S_BACK, button("back", Material.ARROW, "§7« Volver", List.of("§7Al menú de apuestas")));
        // Solo las fichas que tiene en el inventario (con cuántas tiene)
        SortedMap<Integer, Integer> have = chipsInInventory(player);
        List<Map.Entry<Integer, Integer>> owned = new ArrayList<>(have.entrySet());
        if (owned.isEmpty())
            inv.setItem(49, pane(Material.BARRIER, "§cNo tienes fichas en el inventario"));
        if (owned.size() > CHIP_SLOTS.length) // si tiene más tipos de los que caben, las más grandes
            owned = owned.subList(owned.size() - CHIP_SLOTS.length, owned.size());
        for (int k = 0; k < owned.size(); k++) {
            int value = owned.get(k).getKey();
            int count = owned.get(k).getValue();
            boolean sel = value == chip;
            ItemStack it = chipIcon(value, sel, (sel ? "§a▶ " : "") + "§eFicha de " + u(value),
                    List.of("§7Tienes: §f" + count, "", sel ? "§aElegida" : "§eClick para elegirla"));
            it.setAmount(Math.max(1, Math.min(64, count)));
            ItemMeta im = it.getItemMeta();
            if (im != null) {
                im.getPersistentDataContainer().set(key(RouletteBetMenu.KEY_ACTION), PersistentDataType.STRING, "chip");
                im.getPersistentDataContainer().set(key(RouletteBetMenu.KEY_VALUE), PersistentDataType.INTEGER, value);
                it.setItemMeta(im);
            }
            inv.setItem(CHIP_SLOTS[k], it);
        }
        for (int i = 0; i < inv.getSize() && !com.gamblingdex.pack.CasinoPack.customGui(); i++)
            if (inv.getItem(i) == null)
                inv.setItem(i, pane(i >= 45 ? Material.BLACK_STAINED_GLASS_PANE : Material.GREEN_STAINED_GLASS_PANE, " "));
    }

    private ItemStack numberItem(int n, long bet, int chip, boolean open) {
        Material mat = WorldRouletteTables.isZero(n) ? Material.LIME_CONCRETE
                : WorldRouletteTables.isRed(n) ? Material.RED_CONCRETE : Material.BLACK_CONCRETE;
        String color = WorldRouletteTables.isZero(n) ? "§a" : WorldRouletteTables.isRed(n) ? "§c" : "§8";
        List<String> lore = new ArrayList<>();
        if (bet > 0)
            lore.add("§7Tus fichas aquí: §e" + u(bet));
        lore.add("§7Paga: §f35 a 1 §8(36x)");
        lore.add("");
        lore.add(open ? "§eClick: §f+" + u(chip) + " §8| §eShift: §f+" + u(chip * 5L) : "§cApuestas cerradas");
        ItemStack it = new ItemStack(mat, bet > 0 ? (int) Math.max(1, Math.min(64, bet / Math.max(1, chip))) : 1);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(color + "§l" + WorldRouletteTables.formatNumber(n) + (bet > 0 ? " §8| §e" + u(bet) : ""));
            im.setLore(lore);
            im.addItemFlags(ItemFlag.values());
            if (bet > 0)
                im.addEnchant(Enchantment.LURE, 1, true);
            im.getPersistentDataContainer().set(key(RouletteBetMenu.KEY_ACTION), PersistentDataType.STRING, "wheel");
            im.getPersistentDataContainer().set(key(RouletteBetMenu.KEY_VALUE), PersistentDataType.INTEGER, n);
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack chipIcon(int value, boolean glow, String name, List<String> lore) {
        Material mat = Material.GOLD_NUGGET;
        for (Map.Entry<Material, Integer> e : TokenManager.getDenoms().entrySet())
            if (e.getValue() == value)
                mat = e.getKey();
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            im.setLore(lore);
            im.addItemFlags(ItemFlag.values());
            if (glow)
                im.addEnchant(Enchantment.LURE, 1, true);
            it.setItemMeta(im);
        }
        return it;
    }

    private NamespacedKey key(String k) {
        return new NamespacedKey(plugin, k);
    }

    private ItemStack pane(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack button(String action, Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            im.setLore(lore);
            im.addItemFlags(ItemFlag.values());
            im.getPersistentDataContainer().set(key(RouletteBetMenu.KEY_ACTION), PersistentDataType.STRING, action);
            im.getPersistentDataContainer().set(key(RouletteBetMenu.KEY_VALUE), PersistentDataType.STRING, action);
            it.setItemMeta(im);
        }
        return it;
    }
}
