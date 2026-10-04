package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class GuiItem {

    private GuiItem() {
    }

    public static ItemStack button(GamblingDexPlugin plugin, Material material, String name, List<String> lore,
            String action, Integer number, boolean glow) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> l = new ArrayList<>();
                for (String line : lore)
                    l.add(plugin.color(line));
                meta.setLore(l);
            }

            meta.getPersistentDataContainer().set(GuiKeys.actionKey(plugin), PersistentDataType.STRING, action);
            if (number != null) {
                meta.getPersistentDataContainer().set(GuiKeys.intKey(plugin), PersistentDataType.INTEGER, number);
            }

            if (glow) {
                Enchantment ench = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("unbreaking"));
                if (ench != null)
                    meta.addEnchant(ench, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static String getAction(GamblingDexPlugin plugin, ItemStack item) {
        if (item == null || item.getItemMeta() == null)
            return null;
        return item.getItemMeta().getPersistentDataContainer().get(GuiKeys.actionKey(plugin),
                PersistentDataType.STRING);
    }

    public static Integer getInt(GamblingDexPlugin plugin, ItemStack item) {
        if (item == null || item.getItemMeta() == null)
            return null;
        return item.getItemMeta().getPersistentDataContainer().get(GuiKeys.intKey(plugin), PersistentDataType.INTEGER);
    }
}
