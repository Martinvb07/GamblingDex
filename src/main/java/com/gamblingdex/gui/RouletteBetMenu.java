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
        Inventory inv = Bukkit.createInventory(new RouletteBetMenuHolder(table.getTableKey()), 27, TITLE);

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler());
        }

        inv.setItem(10, actionButton("type", WorldRouletteBetType.RED.name(), Material.RED_WOOL,
                "§cROJO", List.of("§7Apuesta a color rojo", "§7Paga: §f2x")));

        inv.setItem(11, actionButton("type", WorldRouletteBetType.BLACK.name(), Material.BLACK_WOOL,
                "§8NEGRO", List.of("§7Apuesta a color negro", "§7Paga: §f2x")));

        inv.setItem(12, actionButton("type", WorldRouletteBetType.EVEN.name(), Material.LIME_DYE,
                "§aPAR", List.of("§7Apuesta a número par", "§7Paga: §f2x")));

        inv.setItem(13, actionButton("type", WorldRouletteBetType.ODD.name(), Material.ORANGE_DYE,
                "§6IMPAR", List.of("§7Apuesta a número impar", "§7Paga: §f2x")));

        inv.setItem(14, actionButton("open", "numbers", Material.PAPER,
                "§eNÚMERO", List.of("§7Elige un número", "§7Paga: §f36x")));

        inv.setItem(16, actionButton("close", "close", Material.BARRIER,
                "§cCerrar", List.of("§7Cerrar menú")));

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
