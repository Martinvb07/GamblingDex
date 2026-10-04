package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.rouletteworld.WorldRouletteTables;
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

import java.util.ArrayList;
import java.util.List;

public class RouletteNumberMenu {

    public static final String TITLE = "§6§lRULETA §8| §eNúmero";

    private final GamblingDexPlugin plugin;

    public RouletteNumberMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, WorldRouletteTable table) {
        Inventory inv = Bukkit.createInventory(new RouletteNumberMenuHolder(table.getTableKey()), 54, TITLE);

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler());
        }

        List<Integer> numbers = new ArrayList<>();
        for (int n = 0; n <= 36; n++)
            numbers.add(n);
        numbers.add(WorldRouletteTables.DOUBLE_ZERO);

        int slot = 0;
        for (int n : numbers) {
            Material mat;
            if (WorldRouletteTables.isZero(n)) {
                mat = Material.LIME_CONCRETE;
            } else {
                mat = WorldRouletteTables.isRed(n) ? Material.RED_CONCRETE : Material.BLACK_CONCRETE;
            }

            inv.setItem(slot, numberButton(n, mat));
            slot++;
            if (slot >= 45)
                break; // keep bottom row for controls
        }

        inv.setItem(49, backButton());

        player.openInventory(inv);
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

    private ItemStack backButton() {
        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName("§cVolver");
            im.setLore(List.of("§7Regresar al menú anterior"));
            im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            NamespacedKey kAction = new NamespacedKey(plugin, RouletteBetMenu.KEY_ACTION);
            NamespacedKey kValue = new NamespacedKey(plugin, RouletteBetMenu.KEY_VALUE);
            im.getPersistentDataContainer().set(kAction, PersistentDataType.STRING, "back");
            im.getPersistentDataContainer().set(kValue, PersistentDataType.STRING, "back");
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack numberButton(int number, Material material) {
        ItemStack it = new ItemStack(material);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName("§f" + WorldRouletteTables.formatNumber(number));
            im.setLore(List.of("§7Seleccionar número"));
            im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            NamespacedKey kAction = new NamespacedKey(plugin, RouletteBetMenu.KEY_ACTION);
            NamespacedKey kValue = new NamespacedKey(plugin, RouletteBetMenu.KEY_VALUE);
            im.getPersistentDataContainer().set(kAction, PersistentDataType.STRING, "number");
            im.getPersistentDataContainer().set(kValue, PersistentDataType.INTEGER, number);
            it.setItemMeta(im);
        }
        return it;
    }
}
