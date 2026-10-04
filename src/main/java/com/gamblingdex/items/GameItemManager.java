package com.gamblingdex.items;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class GameItemManager {

    private final GamblingDexPlugin plugin;
    private final NamespacedKey gameItemKey;

    public GameItemManager(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.gameItemKey = new NamespacedKey(plugin, "gdx_game_item");
    }

    public ItemStack create(GameItemType type, int amount) {
        int safeAmount = Math.max(1, Math.min(64, amount));
        ItemStack item = new ItemStack(type.getMaterial(), safeAmount);

        FileConfiguration cfg = plugin.getConfig();
        String base = "games.items." + type.getId() + ".";

        String name = plugin.color(cfg.getString(base + "name", defaultName(type)));
        List<String> loreCfg = cfg.getStringList(base + "lore");
        boolean glow = cfg.getBoolean(base + "glow", true);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (loreCfg != null && !loreCfg.isEmpty()) {
                List<String> lore = new ArrayList<>();
                for (String line : loreCfg)
                    lore.add(plugin.color(line));
                meta.setLore(lore);
            }

            meta.getPersistentDataContainer().set(gameItemKey, PersistentDataType.STRING, type.getId());

            if (glow) {
                Enchantment ench = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("unbreaking"));
                if (ench != null)
                    meta.addEnchant(ench, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            item.setItemMeta(meta);
        }

        return item;
    }

    public GameItemType getType(ItemStack item) {
        if (item == null || item.getItemMeta() == null)
            return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(gameItemKey, PersistentDataType.STRING);
        if (id == null)
            return null;
        for (GameItemType t : GameItemType.values()) {
            if (t.getId().equalsIgnoreCase(id))
                return t;
        }
        return null;
    }

    private static String defaultName(GameItemType type) {
        return switch (type) {
            case ROULETTE -> "&c&lRuleta";
            case SLOTS -> "&d&lTragamonedas";
            case EXCHANGE -> "&e&lCambio";
        };
    }
}
