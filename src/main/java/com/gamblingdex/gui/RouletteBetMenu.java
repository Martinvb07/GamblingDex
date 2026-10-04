package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.games.rouletteworld.WorldRouletteBetType;
import com.gamblingdex.games.rouletteworld.WorldRouletteTable;
import com.gamblingdex.games.rouletteworld.WorldRouletteTables;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Menú de apuestas de la ruleta, ordenado como la mesa: apuestas simples,
 * docenas, columnas y pleno. Click a una apuesta → eliges el monto → listo.
 */
public class RouletteBetMenu {

    public static final String TITLE = "§6§lRULETA §8| §eApuesta";

    public static final String KEY_ACTION = "gdx_rb_action";
    public static final String KEY_VALUE = "gdx_rb_value";

    private final GamblingDexPlugin plugin;

    public RouletteBetMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, WorldRouletteTable table) {
        Inventory inv = Bukkit.createInventory(new RouletteBetMenuHolder(table.getTableKey()), 54, TITLE);

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler(Material.GRAY_STAINED_GLASS_PANE));
        }
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler(Material.BLACK_STAINED_GLASS_PANE));
        }

        // Estado de la ronda
        String status = switch (table.getState()) {
            case COUNTDOWN -> "§aApuestas abiertas";
            case SPINNING -> "§6Girando...";
            default -> "§cApuestas cerradas §7(espera la próxima ronda)";
        };
        inv.setItem(4, actionButton("noop", "status", Material.CLOCK, "§6§lRULETA", List.of(
                status,
                "",
                "§71. Click a la apuesta que quieras",
                "§72. Elige cuántas fichas",
                "§7El 0 y el 00 solo pagan si apostaste a ese número.")));

        // Columnas a la izquierda (pagan 2 a 1)
        inv.setItem(9, typeButton(WorldRouletteBetType.COLUMN_1, Material.CYAN_CONCRETE, "§3", "§71, 4, 7 ... 34"));
        inv.setItem(18, typeButton(WorldRouletteBetType.COLUMN_2, Material.LIGHT_BLUE_CONCRETE, "§b", "§72, 5, 8 ... 35"));
        inv.setItem(27, typeButton(WorldRouletteBetType.COLUMN_3, Material.BLUE_CONCRETE, "§9", "§73, 6, 9 ... 36"));

        // Simples (pagan 1 a 1), en parejas: rojo/negro, par/impar, 1-18/19-36
        inv.setItem(11, typeButton(WorldRouletteBetType.RED, Material.RED_CONCRETE, "§c", "§7Cualquier número rojo"));
        inv.setItem(20, typeButton(WorldRouletteBetType.BLACK, Material.BLACK_CONCRETE, "§8", "§7Cualquier número negro"));
        inv.setItem(13, typeButton(WorldRouletteBetType.EVEN, Material.LIME_DYE, "§a", "§72, 4, 6 ... 36"));
        inv.setItem(22, typeButton(WorldRouletteBetType.ODD, Material.ORANGE_DYE, "§6", "§71, 3, 5 ... 35"));
        inv.setItem(15, typeButton(WorldRouletteBetType.LOW, Material.WHITE_CONCRETE, "§f", "§7Del 1 al 18"));
        inv.setItem(24, typeButton(WorldRouletteBetType.HIGH, Material.LIGHT_GRAY_CONCRETE, "§7", "§7Del 19 al 36"));

        // Docenas (pagan 2 a 1)
        inv.setItem(29, typeButton(WorldRouletteBetType.DOZEN_1, Material.YELLOW_CONCRETE, "§e", "§7Del 1 al 12"));
        inv.setItem(31, typeButton(WorldRouletteBetType.DOZEN_2, Material.ORANGE_CONCRETE, "§6", "§7Del 13 al 24"));
        inv.setItem(33, typeButton(WorldRouletteBetType.DOZEN_3, Material.RED_TERRACOTTA, "§c", "§7Del 25 al 36"));

        // Pleno (paga 35 a 1): 0, elegir número, 00
        inv.setItem(38, numberButton(0));
        inv.setItem(40, actionButton("open", "numbers", Material.PAPER, "§e§lElegir número (1-36)",
                List.of("§7Paga §f35 a 1 §8(36x)", "", "§eClick para ver los números")));
        inv.setItem(42, numberButton(WorldRouletteTables.DOUBLE_ZERO));

        // Abajo: tus apuestas, repetir, cerrar, fichas
        List<String> mine = table.describeBets(player.getUniqueId());
        List<String> lore = new ArrayList<>();
        if (mine.isEmpty()) {
            lore.add("§7Aún no apostaste en esta ronda.");
        } else {
            lore.addAll(mine);
            lore.add("");
            lore.add("§7Total: §e" + table.totalBet(player.getUniqueId()));
        }
        inv.setItem(46, actionButton("noop", "mine", Material.WRITABLE_BOOK, "§e§lTus apuestas", lore));

        long last = table.lastBetTotal(player.getUniqueId());
        if (last > 0) {
            List<String> repeat = new ArrayList<>(table.describeLastBets(player.getUniqueId()));
            repeat.add("");
            repeat.add("§7Total: §e" + last);
            repeat.add("§eClick para apostar lo mismo otra vez");
            inv.setItem(48, actionButton("repeat", "repeat", Material.EMERALD, "§a§lRepetir apuesta", repeat));
        } else {
            inv.setItem(48, actionButton("noop", "repeat", Material.GRAY_DYE, "§7Repetir apuesta",
                    List.of("§8Disponible después de tu primera ronda")));
        }

        inv.setItem(50, actionButton("close", "close", Material.BARRIER, "§cCerrar", List.of("§7Cerrar menú")));
        inv.setItem(52, actionButton("noop", "balance", Material.SUNFLOWER,
                "§7Tus fichas: §e" + TokenWallet.balance(player), List.of(
                        "§8También puedes apostar con fichas en la mano:",
                        "§8click derecho al centro §7(shift = todo el stack)")));

        player.openInventory(inv);
    }

    private ItemStack typeButton(WorldRouletteBetType type, Material mat, String color, String desc) {
        int p = type.payoutToOne();
        return actionButton("type", type.name(), mat, color + "§l" + type.label(),
                List.of(desc, "§7Paga: §f" + p + " a 1 §8(" + (p + 1) + "x)", "", "§eClick para apostar"));
    }

    private ItemStack numberButton(int n) {
        ItemStack it = actionButton("number", "", Material.LIME_CONCRETE, "§a§l" + WorldRouletteTables.formatNumber(n),
                List.of("§7Paga: §f35 a 1 §8(36x)", "", "§eClick para apostar"));
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.getPersistentDataContainer().set(new NamespacedKey(plugin, KEY_VALUE), PersistentDataType.INTEGER, n);
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack filler(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(" ");
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack actionButton(String action, String value, Material material, String name, List<String> lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            im.setLore(lore);
            im.addItemFlags(ItemFlag.values());
            NamespacedKey kAction = new NamespacedKey(plugin, KEY_ACTION);
            NamespacedKey kValue = new NamespacedKey(plugin, KEY_VALUE);
            im.getPersistentDataContainer().set(kAction, PersistentDataType.STRING, action);
            im.getPersistentDataContainer().set(kValue, PersistentDataType.STRING, value);
            it.setItemMeta(im);
        }
        return it;
    }
}
