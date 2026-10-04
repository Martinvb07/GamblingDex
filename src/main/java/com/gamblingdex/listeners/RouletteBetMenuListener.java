package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.rouletteworld.WorldRouletteBetType;
import com.gamblingdex.games.rouletteworld.WorldRouletteTable;
import com.gamblingdex.games.rouletteworld.WorldRouletteTables;
import com.gamblingdex.gui.AmountPickerMenu;
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
                askAmount(player, table, type, null);
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
                askAmount(player, table, WorldRouletteBetType.NUMBER, number);
            }
            case "repeat" -> {
                table.repeatLastBet(player);
                new RouletteBetMenu(plugin).open(player, table);
            }
            case "back" -> {
                new RouletteBetMenu(plugin).open(player, table);
            }
            case "close" -> player.closeInventory();
        }
    }

    /** Elegir cuántas fichas apostar (sin tope salvo roulette_world.max_bet) y volver al menú. */
    private void askAmount(Player player, WorldRouletteTable table, WorldRouletteBetType type, Integer number) {
        if (table.getState() != WorldRouletteTable.State.COUNTDOWN) {
            player.sendMessage(plugin.getMessages().getString("roulette_world.bets_closed",
                    "&cApuestas cerradas. &7Espera a que se abran."));
            return;
        }
        long min = Math.max(1, plugin.getConfig().getLong("roulette_world.min_bet", 1));
        long max = Math.max(0, plugin.getConfig().getLong("roulette_world.max_bet", 0));
        String what = type == WorldRouletteBetType.NUMBER && number != null
                ? "NÚMERO " + WorldRouletteTables.formatNumber(number)
                : type.label();
        int p = type.payoutToOne();
        AmountPickerMenu.open(player, "&6&lRuleta &8- &f" + what, min, max, min,
                java.util.List.of("&7Apuesta: &f" + what, "&7Paga: &f" + p + " a 1 &8(" + (p + 1) + "x)"),
                amount -> {
                    table.placeBetFromWallet(player, type, number, amount);
                    new RouletteBetMenu(plugin).open(player, table);
                }, () -> new RouletteBetMenu(plugin).open(player, table));
    }
}
