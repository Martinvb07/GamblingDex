package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.games.blackjack.BlackjackTable;
import com.gamblingdex.gui.BlackjackActionMenu;
import com.gamblingdex.gui.BlackjackActionMenuHolder;
import com.gamblingdex.gui.BlackjackBetMenu;
import com.gamblingdex.gui.BlackjackBetMenuHolder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlackjackMenuListener implements Listener {

    private final GamblingDexPlugin plugin;

    // Players that just chose an action; their close shouldn't reopen.
    private final Set<UUID> actionChosen = ConcurrentHashMap.newKeySet();

    public BlackjackMenuListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory inv = event.getInventory();
        Object holder = inv.getHolder();
        boolean isBetMenu = holder instanceof BlackjackBetMenuHolder;
        boolean isActionMenu = holder instanceof BlackjackActionMenuHolder;
        if (!isBetMenu && !isActionMenu) {
            return;
        }

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (isBetMenu) {
            NamespacedKey kAction = new NamespacedKey(plugin, BlackjackBetMenu.KEY_ACTION);
            NamespacedKey kValue = new NamespacedKey(plugin, BlackjackBetMenu.KEY_VALUE);
            String action = pdc.get(kAction, PersistentDataType.STRING);
            if (action == null) {
                return;
            }

            String tableKey = ((BlackjackBetMenuHolder) holder).getTableKey();
            BlackjackTable table = plugin.getBlackjackManager() == null ? null
                    : plugin.getBlackjackManager().getByKey(tableKey);
            if (table == null) {
                player.closeInventory();
                player.sendMessage(plugin.getMessages().getString(
                        "blackjack.table_missing",
                        "&cEsa mesa ya no existe."));
                return;
            }

            if (action.equals("close")) {
                player.closeInventory();
                return;
            }

            if (action.equals("clear")) {
                table.clearBet(player);
                new BlackjackBetMenu(plugin).open(player, table);
                return;
            }

            if (action.equals("repeat")) {
                table.repeatLastBet(player);
                new BlackjackBetMenu(plugin).open(player, table);
                return;
            }

            if (action.equals("spot")) {
                String spotName = pdc.get(kValue, PersistentDataType.STRING);
                try {
                    table.setSelectedSpot(player.getUniqueId(), BlackjackTable.BetSpot.valueOf(spotName));
                } catch (Exception ignored) {
                    return;
                }
                new BlackjackBetMenu(plugin).open(player, table);
                return;
            }

            if (!action.equals("denom")) {
                return;
            }

            String matName = pdc.get(kValue, PersistentDataType.STRING);
            if (matName == null || matName.isBlank()) {
                return;
            }

            Material mat;
            try {
                mat = Material.valueOf(matName.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ex) {
                return;
            }

            Integer denom = TokenManager.getDenoms().get(mat);
            if (denom == null) {
                return;
            }
            if (denom < table.getMinChip()) {
                player.sendMessage(plugin.getMessages().format("blackjack.chip_too_small",
                        "&cEsta mesa solo acepta fichas de &e{min}&c o más.",
                        java.util.Map.of("min", String.valueOf(table.getMinChip()))));
                return;
            }

            int wanted = event.isShiftClick() ? countTokens(player, mat) : 1;
            if (wanted <= 0) {
                player.sendMessage(plugin.getMessages().getString(
                        "blackjack.bet_menu.no_tokens",
                        "&cNo tienes fichas de ese tipo."));
                return;
            }

            int removed = removeTokens(player, mat, wanted);
            if (removed <= 0) {
                player.sendMessage(plugin.getMessages().getString(
                        "blackjack.bet_menu.no_tokens",
                        "&cNo tienes fichas de ese tipo."));
                return;
            }

            long units = (long) denom * (long) removed;
            boolean ok = table.placeBet(player, table.getSelectedSpot(player.getUniqueId()), units);
            if (!ok) {
                plugin.getTokenPayout().pay(player, units);
            }

            new BlackjackBetMenu(plugin).open(player, table);
            return;
        }

        // Action menu
        NamespacedKey kAction = new NamespacedKey(plugin, BlackjackActionMenu.KEY_ACTION);
        String action = pdc.get(kAction, PersistentDataType.STRING);
        if (action == null) {
            return;
        }

        String tableKey = ((BlackjackActionMenuHolder) holder).getTableKey();
        BlackjackTable table = plugin.getBlackjackManager() == null ? null
                : plugin.getBlackjackManager().getByKey(tableKey);
        if (table == null) {
            player.closeInventory();
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.table_missing",
                    "&cEsa mesa ya no existe."));
            return;
        }

        switch (action) {
            case "hit" -> {
                actionChosen.add(player.getUniqueId());
                player.closeInventory();
                table.hit(player);
            }
            case "stand" -> {
                actionChosen.add(player.getUniqueId());
                player.closeInventory();
                table.stand(player);
            }
            case "double" -> {
                // Validar ANTES de cerrar: si no puede doblar, el menú sigue abierto
                // para que pueda pedir o plantarse.
                String deny = table.doubleDenyReason(player);
                if (deny != null) {
                    player.sendMessage(deny);
                    return;
                }
                actionChosen.add(player.getUniqueId());
                player.closeInventory();
                table.doubleDown(player);
            }
            case "split" -> {
                // Igual que doblar: si no puede, el menú sigue abierto.
                String deny = table.splitDenyReason(player);
                if (deny != null) {
                    player.sendMessage(deny);
                    return;
                }
                actionChosen.add(player.getUniqueId());
                player.closeInventory();
                table.split(player);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Object holder = event.getInventory().getHolder();
        if (holder instanceof BlackjackBetMenuHolder betHolder) {
            // Cerró el menú de apuestas: si todos ya apostaron en todo, se reparte.
            BlackjackTable t = plugin.getBlackjackManager() == null ? null
                    : plugin.getBlackjackManager().getByKey(betHolder.getTableKey());
            if (t != null)
                t.onBetMenuClosed();
            return;
        }
        if (!(holder instanceof BlackjackActionMenuHolder actionHolder)) {
            return;
        }

        // If they actually picked an action, allow close.
        if (actionChosen.remove(player.getUniqueId())) {
            return;
        }

        BlackjackTable table = plugin.getBlackjackManager() == null ? null
                : plugin.getBlackjackManager().getByKey(actionHolder.getTableKey());
        if (table == null) {
            return;
        }

        boolean stillYourTurn = table.getState() == BlackjackTable.State.PLAYING
                && player.getUniqueId().equals(table.getCurrentTurn());
        if (!stillYourTurn) {
            return;
        }

        // Re-open using the table's configured delay (run next tick to avoid fighting
        // the close event).
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            BlackjackTable t2 = plugin.getBlackjackManager() == null ? null
                    : plugin.getBlackjackManager().getByKey(actionHolder.getTableKey());
            if (t2 == null) {
                return;
            }
            t2.requestActionMenuOpen(player.getUniqueId());
        });
    }

    private int countTokens(Player player, Material mat) {
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == mat && plugin.getTokenManager().isToken(it)) {
                total += it.getAmount();
            }
        }
        return total;
    }

    private int removeTokens(Player player, Material mat, int amount) {
        if (player == null || mat == null || amount <= 0) {
            return 0;
        }

        int removed = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) {
                continue;
            }
            if (item.getType() != mat) {
                continue;
            }
            if (!plugin.getTokenManager().isToken(item)) {
                continue;
            }

            int take = Math.min(item.getAmount(), amount - removed);
            item.setAmount(item.getAmount() - take);
            removed += take;
            if (item.getAmount() <= 0) {
                item.setType(Material.AIR);
            }

            if (removed >= amount) {
                break;
            }
        }

        return removed;
    }
}
