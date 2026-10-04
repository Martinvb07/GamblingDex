package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.blackjack.BlackjackTable;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class BlackjackActionMenu {

    public static final String KEY_ACTION = "gdx_bj_action";

    private final GamblingDexPlugin plugin;

    public BlackjackActionMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, BlackjackTable table) {
        if (player == null || table == null) {
            return;
        }

        Inventory inv = Bukkit.createInventory(new BlackjackActionMenuHolder(table.getTableKey()), 27,
                "§6§lBlackjack §8- §aTu turno");

        for (int i = 0; i < inv.getSize(); i++) {
            if (i < 9 || i > 17 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, MenuUtils.createButton("", Material.WHITE_STAINED_GLASS_PANE));
            } else {
                inv.setItem(i, MenuUtils.createButton("", Material.GRAY_STAINED_GLASS_PANE));
            }
        }

        inv.setItem(4, MenuUtils.createButton("§fTu mano: " + table.describePlayerHand(player.getUniqueId()),
                Material.PAPER));
        inv.setItem(22, MenuUtils.createButton("§cDealer: §f" + table.describeDealerUpCard(), Material.VILLAGER_SPAWN_EGG));

        inv.setItem(10, actionButton("§aPedir", Material.LIME_DYE, "hit", List.of("§7Pide una carta.")));

        String denyDouble = table.doubleDenyReason(player);
        if (denyDouble == null) {
            inv.setItem(12, actionButton("§eDoblar", Material.GOLD_INGOT, "double",
                    List.of("§7Doblas tu apuesta.", "§7Recibes 1 carta y te plantas.")));
        } else {
            inv.setItem(12, actionButton("§8Doblar (no disponible)", Material.GRAY_DYE, "double",
                    List.of(denyDouble)));
        }

        String denySplit = table.splitDenyReason(player);
        if (denySplit == null) {
            inv.setItem(14, actionButton("§bDividir", Material.SHEARS, "split",
                    List.of("§7Separas tu par en 2 manos.",
                            "§7Pones otra apuesta igual a la tuya.",
                            "§8Ases divididos: 1 carta cada uno.",
                            "§821 tras dividir paga 1:1.")));
        } else {
            inv.setItem(14, actionButton("§8Dividir (no disponible)", Material.GRAY_DYE, "split",
                    List.of(denySplit)));
        }

        inv.setItem(16, actionButton("§cPlantarse", Material.RED_DYE, "stand", List.of("§7Termina esta mano.")));

        player.openInventory(inv);
    }

    private ItemStack actionButton(String name, Material mat, String action, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) {
                meta.setLore(lore);
            }
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            NamespacedKey kAction = new NamespacedKey(plugin, KEY_ACTION);
            pdc.set(kAction, PersistentDataType.STRING, action);
            it.setItemMeta(meta);
        }
        return it;
    }
}
