package com.gamblingdex.listeners;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.games.poker.PokerManager;
import com.gamblingdex.games.poker.PokerTable;
import com.gamblingdex.gui.PokerActionMenu;
import com.gamblingdex.gui.PokerActionMenuHolder;
import com.gamblingdex.gui.PokerBuyInMenu;
import com.gamblingdex.gui.PokerBuyInMenuHolder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class PokerListener implements Listener {

    private final GamblingDexPlugin plugin;

    // Jugadores que acaban de elegir una acción: su cierre de menú no cuenta como "cerrar".
    private final Set<UUID> actionChosen = new HashSet<>();

    public PokerListener(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    private PokerManager manager() {
        return plugin.getPokerManager();
    }

    // ------------------------------------------------------------------
    // Menús
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        Object holder = event.getInventory().getHolder();
        boolean isAction = holder instanceof PokerActionMenuHolder;
        boolean isBuyIn = holder instanceof PokerBuyInMenuHolder;
        if (!isAction && !isBuyIn)
            return;

        event.setCancelled(true);
        if (event.getRawSlot() >= event.getInventory().getSize())
            return;

        ItemStack clicked = event.getCurrentItem();
        ItemMeta meta = clicked == null ? null : clicked.getItemMeta();
        if (meta == null)
            return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String tableKey = isAction ? ((PokerActionMenuHolder) holder).getTableKey()
                : ((PokerBuyInMenuHolder) holder).getTableKey();
        PokerTable table = manager() == null ? null : manager().getByKey(tableKey);
        if (table == null) {
            player.closeInventory();
            return;
        }

        if (isAction) {
            handleActionClick(player, table, pdc);
        } else {
            handleBuyInClick(player, table, pdc, event.isShiftClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getInventory().getHolder();
        if (holder instanceof PokerActionMenuHolder || holder instanceof PokerBuyInMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void handleActionClick(Player player, PokerTable table, PersistentDataContainer pdc) {
        String action = pdc.get(new NamespacedKey(plugin, PokerActionMenu.KEY_ACTION), PersistentDataType.STRING);
        if (action == null)
            return;
        UUID id = player.getUniqueId();

        switch (action) {
            case "close" -> player.closeInventory();
            case "adjust", "set" -> {
                Long v = pdc.get(new NamespacedKey(plugin, PokerActionMenu.KEY_VALUE), PersistentDataType.LONG);
                if (v == null || !table.isTurn(id))
                    return;
                long to = action.equals("set") ? v : table.getRaiseTarget(id) + v;
                table.setRaiseTarget(id, to);
                new PokerActionMenu(plugin).open(player, table);
            }
            case "fold", "check", "raise", "allin" -> {
                if (!table.isTurn(id)) {
                    player.closeInventory();
                    return;
                }
                actionChosen.add(id);
                player.closeInventory();
                table.handleAction(player, action);
            }
            default -> {
            }
        }
    }

    private void handleBuyInClick(Player player, PokerTable table, PersistentDataContainer pdc, boolean shift) {
        String action = pdc.get(new NamespacedKey(plugin, PokerBuyInMenu.KEY_ACTION), PersistentDataType.STRING);
        if (action == null)
            return;
        if (action.equals("close")) {
            player.closeInventory();
            return;
        }
        if (!action.equals("denom"))
            return;

        String matName = pdc.get(new NamespacedKey(plugin, PokerBuyInMenu.KEY_VALUE), PersistentDataType.STRING);
        Material mat;
        try {
            mat = Material.valueOf(matName.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return;
        }
        Integer denom = TokenManager.getDenoms().get(mat);
        if (denom == null)
            return;

        UUID id = player.getUniqueId();
        String deny = table.depositDenyReason(id);
        if (deny != null) {
            player.sendMessage(deny);
            return;
        }

        long room = table.getRoomToMax(id);
        int fit = (int) Math.min(Integer.MAX_VALUE, room / denom);
        if (fit <= 0) {
            player.sendMessage(plugin.getMessages().format("poker.deposit_too_big",
                    "&cEsa ficha te pasa del máximo de la mesa (&e{max}&c). Usa una más chica.",
                    java.util.Map.of("max", PokerTable.units(table.getMaxBuyIn()))));
            return;
        }
        int have = countTokens(player, mat);
        int wanted = shift ? Math.min(have, fit) : Math.min(1, have);
        if (wanted <= 0) {
            player.sendMessage(plugin.getMessages().getString("poker.no_tokens_of_type",
                    "&cNo tienes fichas de ese tipo."));
            return;
        }

        int removed = removeTokens(player, mat, wanted);
        if (removed <= 0)
            return;
        long units = (long) denom * removed;
        if (!table.deposit(player, units)) {
            plugin.getTokenPayout().pay(player, units);
        }
        new PokerBuyInMenu(plugin).open(player, table);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player))
            return;
        if (!(event.getInventory().getHolder() instanceof PokerActionMenuHolder holder))
            return;
        if (actionChosen.remove(player.getUniqueId()))
            return;

        PokerTable table = manager() == null ? null : manager().getByKey(holder.getTableKey());
        if (table == null)
            return;
        // Siguiente tick: si solo se está reabriendo el menú (ajuste de subida), no hacer nada.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline())
                return;
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof PokerActionMenuHolder)
                return;
            table.onActionMenuClosed(player);
        });
    }

    // ------------------------------------------------------------------
    // Mesa física
    // ------------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        if (manager() == null)
            return;
        PokerTable table = manager().getByBlock(event.getClickedBlock());
        if (table == null)
            return;
        event.setCancelled(true);
        table.openMenu(event.getPlayer());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (manager() == null)
            return;
        String key = com.gamblingdex.games.blackjack.BlackjackTables.key(event.getBlock().getLocation());
        for (PokerTable t : manager().getTables()) {
            boolean isCenter = t.isCenter(event.getBlock());
            boolean isSeat = t.getSeatKeys().contains(key);
            if (!isCenter && !isSeat)
                continue;
            if (!event.getPlayer().hasPermission("gamblingdex.admin")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(plugin.getMessages().getString("poker.cannot_break",
                        "&cNo puedes romper una mesa de póker."));
                return;
            }
            if (isCenter) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(plugin.getMessages().format("poker.admin.break_hint",
                        "&eUsa &f/gdx poker remove {table} &epara quitar la mesa.",
                        java.util.Map.of("table", t.getName())));
            }
            return;
        }
    }

    // ------------------------------------------------------------------
    // Salir del servidor
    // ------------------------------------------------------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        actionChosen.remove(event.getPlayer().getUniqueId());
        if (manager() == null)
            return;
        PokerTable t = manager().getTableOf(event.getPlayer().getUniqueId());
        if (t != null)
            t.handleQuit(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------

    private int countTokens(Player player, Material mat) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == mat && plugin.getTokenManager().isToken(it))
                total += it.getAmount();
        }
        return total;
    }

    private int removeTokens(Player player, Material mat, int amount) {
        int removed = 0;
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize() && removed < amount; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() != mat || !plugin.getTokenManager().isToken(item))
                continue;
            int take = Math.min(item.getAmount(), amount - removed);
            if (take >= item.getAmount()) {
                inv.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - take);
                inv.setItem(slot, item);
            }
            removed += take;
        }
        return removed;
    }
}
