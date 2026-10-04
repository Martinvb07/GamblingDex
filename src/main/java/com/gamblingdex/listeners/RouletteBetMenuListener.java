package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.rouletteworld.WorldRouletteBetType;
import com.gamblingdex.games.rouletteworld.WorldRouletteTable;
import com.gamblingdex.gui.RouletteBetMenu;
import com.gamblingdex.gui.RouletteBetMenuHolder;
import com.gamblingdex.gui.RouletteNumberMenu;
import com.gamblingdex.gui.RouletteNumberMenuHolder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class RouletteBetMenuListener implements Listener {

    private final GamblingDexPlugin plugin;

    public RouletteBetMenuListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;

        Object holder = event.getInventory().getHolder();
        boolean isBetMenu = holder instanceof RouletteBetMenuHolder;
        boolean isNumberMenu = holder instanceof RouletteNumberMenuHolder;
        if (!isBetMenu && !isNumberMenu)
            return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null)
            return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null)
            return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey kAction = new NamespacedKey(plugin, RouletteBetMenu.KEY_ACTION);
        NamespacedKey kValue = new NamespacedKey(plugin, RouletteBetMenu.KEY_VALUE);
        String action = pdc.get(kAction, PersistentDataType.STRING);
        if (action == null)
            return;

        String tableKey = isBetMenu ? ((RouletteBetMenuHolder) holder).getTableKey()
                : ((RouletteNumberMenuHolder) holder).getTableKey();
        WorldRouletteTable table = plugin.getWorldRouletteManager().getByKey(tableKey);
        if (table == null) {
            player.closeInventory();
            player.sendMessage(plugin.getMessages().getString(
                    "roulette_world.bet_menu.table_missing",
                    "&cEsa ruleta ya no existe."));
            return;
        }

        switch (action) {
            case "type" -> {
                String typeName = pdc.get(kValue, PersistentDataType.STRING);
                if (typeName == null)
                    return;
                WorldRouletteBetType type;
                try {
                    type = WorldRouletteBetType.valueOf(typeName);
                } catch (Exception ex) {
                    return;
                }
                table.setSelectionType(player, type);
                player.sendMessage(plugin.getMessages().format(
                        "roulette_world.bet_menu.selection",
                        "&eApuesta seleccionada: &f{selection}",
                        java.util.Map.of("selection", table.describeSelection(player.getUniqueId()))));
            }
            case "open" -> {
                String open = pdc.get(kValue, PersistentDataType.STRING);
                if (open == null)
                    return;
                if (open.equalsIgnoreCase("numbers")) {
                    new RouletteNumberMenu(plugin).open(player, table);
                }
            }
            case "number" -> {
                Integer number = pdc.get(kValue, PersistentDataType.INTEGER);
                if (number == null)
                    return;
                table.selectNumber(player, number);
                player.sendMessage(plugin.getMessages().format(
                        "roulette_world.bet_menu.selection",
                        "&eApuesta seleccionada: &f{selection}",
                        java.util.Map.of("selection", table.describeSelection(player.getUniqueId()))));
                player.closeInventory();
            }
            case "back" -> {
                new RouletteBetMenu(plugin).open(player, table);
            }
            case "close" -> player.closeInventory();
        }
    }
}
