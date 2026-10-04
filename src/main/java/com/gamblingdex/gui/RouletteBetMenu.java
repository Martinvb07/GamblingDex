package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.rouletteworld.WorldRouletteBetType;
import com.gamblingdex.games.rouletteworld.WorldRouletteTable;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class RouletteBetMenu {

    public static final String TITLE = "§6§lRULETA §8| §eApuesta";

    public static final String KEY_ACTION = "gdx_rb_action";
    public static final String KEY_VALUE = "gdx_rb_value";

    private final GamblingDexPlugin plugin;

    public RouletteBetMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, WorldRouletteTable table) {
        Inventory inv = Bukkit.createInventory(new RouletteBetMenuHolder(table.getTableKey()), 45, TITLE);

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler());
        }

        // Fila 1: apuestas simples (pagan 1 a 1) y número.
        inv.setItem(10, typeButton(WorldRouletteBetType.RED, Material.RED_WOOL, "§c", "§7Cualquier número rojo"));
        inv.setItem(11, typeButton(WorldRouletteBetType.BLACK, Material.BLACK_WOOL, "§8", "§7Cualquier número negro"));
        inv.setItem(12, typeButton(WorldRouletteBetType.EVEN, Material.LIME_DYE, "§a", "§72, 4, 6 ... 36"));
        inv.setItem(13, typeButton(WorldRouletteBetType.ODD, Material.ORANGE_DYE, "§6", "§71, 3, 5 ... 35"));
        inv.setItem(14, typeButton(WorldRouletteBetType.LOW, Material.LIGHT_BLUE_DYE, "§b", "§7Números del 1 al 18"));
        inv.setItem(15, typeButton(WorldRouletteBetType.HIGH, Material.BLUE_DYE, "§9", "§7Números del 19 al 36"));
        inv.setItem(16, actionButton("open", "numbers", Material.PAPER,
                "§eNÚMERO", List.of("§7Elige un número", "§7Paga: §f35 a 1 §8(36x)")));

        // Fila 2: docenas (pagan 2 a 1).
        inv.setItem(20, typeButton(WorldRouletteBetType.DOZEN_1, Material.YELLOW_CONCRETE, "§e", "§7Del 1 al 12"));
        inv.setItem(21, typeButton(WorldRouletteBetType.DOZEN_2, Material.ORANGE_CONCRETE, "§6", "§7Del 13 al 24"));
        inv.setItem(22, typeButton(WorldRouletteBetType.DOZEN_3, Material.RED_CONCRETE, "§c", "§7Del 25 al 36"));

        // Fila 3: columnas (pagan 2 a 1).
        inv.setItem(29, typeButton(WorldRouletteBetType.COLUMN_1, Material.CYAN_CONCRETE, "§3",
                "§71, 4, 7, 10 ... 34"));
        inv.setItem(30, typeButton(WorldRouletteBetType.COLUMN_2, Material.LIGHT_BLUE_CONCRETE, "§b",
                "§72, 5, 8, 11 ... 35"));
        inv.setItem(31, typeButton(WorldRouletteBetType.COLUMN_3, Material.BLUE_CONCRETE, "§9",
                "§73, 6, 9, 12 ... 36"));

        inv.setItem(24, actionButton("noop", "info", Material.BOOK, "§e§lCómo apostar", List.of(
                "§71. Elige el tipo de apuesta aquí.",
                "§72. Click derecho al centro de la ruleta",
                "§7   con fichas en la mano §8(shift = todo el stack)§7.",
                "§7El 0 y el 00 solo pagan si apostaste a ese número.")));

        inv.setItem(40, actionButton("close", "close", Material.BARRIER,
                "§cCerrar", List.of("§7Cerrar menú")));

        player.openInventory(inv);
    }

    private ItemStack typeButton(WorldRouletteBetType type, Material mat, String color, String desc) {
        int p = type.payoutToOne();
        return actionButton("type", type.name(), mat, color + "§l" + type.label(),
                List.of(desc, "§7Paga: §f" + p + " a 1 §8(" + (p + 1) + "x)", "", "§eClick para elegir"));
    }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
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
            im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            NamespacedKey kAction = new NamespacedKey(plugin, KEY_ACTION);
            NamespacedKey kValue = new NamespacedKey(plugin, KEY_VALUE);
            im.getPersistentDataContainer().set(kAction, PersistentDataType.STRING, action);
            im.getPersistentDataContainer().set(kValue, PersistentDataType.STRING, value);
            it.setItemMeta(im);
        }
        return it;
    }
}
