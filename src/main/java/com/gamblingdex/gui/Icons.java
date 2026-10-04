package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Ítems para los menús (nombre y lore con colores &amp;, brillo opcional). */
public final class Icons {

    private Icons() {
    }

    public static ItemStack of(Material m, String name, List<String> lore) {
        return of(m, 1, name, lore, false);
    }

    public static ItemStack of(Material m, int amount, String name, List<String> lore, boolean glow) {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return it;
        meta.setDisplayName(plugin.color(name));
        if (lore != null && !lore.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (String s : lore)
                l.add(plugin.color(s));
            meta.setLore(l);
        }
        if (glow) {
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
            if (ench != null)
                meta.addEnchant(ench, 1, true);
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    /** Rellena los huecos vacíos con un panel sin nombre. */
    public static void fill(Inventory inv, Material pane) {
        ItemStack f = of(pane, " ", null);
        for (int i = 0; i < inv.getSize(); i++)
            if (inv.getItem(i) == null)
                inv.setItem(i, f);
    }
}
