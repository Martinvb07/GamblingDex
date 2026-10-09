package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.games.poker.PokerTable;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compra de fichas para la mesa de póker: los tokens del inventario pasan al
 * stack de la mesa. Se devuelven al bajarse del asiento.
 */
public class PokerBuyInMenu {

    public static final String KEY_ACTION = "gdx_pk_buy_action";
    public static final String KEY_VALUE = "gdx_pk_buy_value";

    private final GamblingDexPlugin plugin;

    public PokerBuyInMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, PokerTable table) {
        if (player == null || table == null)
            return;
        UUID id = player.getUniqueId();

        Inventory inv = com.gamblingdex.pack.CasinoPack.inventory(new PokerBuyInMenuHolder(table.getTableKey()), 36,
                com.gamblingdex.pack.CasinoPack.POKER_BUYIN_BG, "§6§lPóker §8- §eFichas de mesa");

        int lastRowStart = inv.getSize() - 9;
        for (int i = 0; i < inv.getSize() && !com.gamblingdex.pack.CasinoPack.customGui(); i++) {
            boolean border = i < 9 || i >= lastRowStart || i % 9 == 0 || i % 9 == 8;
            inv.setItem(i, MenuUtils.createButton(" ",
                    border ? Material.GREEN_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE));
        }

        if (table.isTournament()) {
            openTournament(player, table, inv);
            return;
        }

        int[] tokenSlots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21 };
        int idx = 0;
        TokenManager tokenManager = plugin.getTokenManager();
        for (Map.Entry<Material, Integer> entry : TokenManager.getDenoms().entrySet()) {
            if (idx >= tokenSlots.length)
                break;
            Material mat = entry.getKey();
            int denom = entry.getValue();
            int count = countTokens(player, mat);

            ItemStack icon = tokenManager.createToken(mat, 1);
            icon.setAmount(Math.min(64, Math.max(1, count)));
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e+" + PokerTable.units(denom) + " ⛃");
                List<String> lore = new ArrayList<>();
                lore.add("§7Tienes: §f" + count);
                lore.add("§7Click: §a+1 §8| §7Shift+Click: §a+todas las que entren");
                meta.setLore(lore);
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(new NamespacedKey(plugin, KEY_ACTION), PersistentDataType.STRING, "denom");
                pdc.set(new NamespacedKey(plugin, KEY_VALUE), PersistentDataType.STRING, mat.name());
                icon.setItemMeta(meta);
            }
            inv.setItem(tokenSlots[idx++], icon);
        }

        long stack = table.getStack(id);
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.setDisplayName("§6Tus fichas en la mesa: §e" + PokerTable.units(stack));
            List<String> lore = new ArrayList<>();
            lore.add("§7Ciegas: §f" + PokerTable.units(table.getSmallBlind()) + "/"
                    + PokerTable.units(table.getBigBlind()));
            lore.add("§7Compra mínima: §f" + PokerTable.units(table.getMinBuyIn()));
            lore.add("§7Compra máxima: §f" + PokerTable.units(table.getMaxBuyIn()));
            lore.add("");
            String deny = table.depositDenyReason(id);
            if (deny != null) {
                lore.add(deny);
            } else if (stack < table.getMinBuyIn()) {
                lore.add("§eTe faltan §f" + PokerTable.units(table.getMinBuyIn() - stack) + " §epara jugar.");
            } else {
                lore.add("§a¡Listo! Entrarás en la próxima mano.");
            }
            lore.add("");
            lore.add("§7Bájate del asiento para irte");
            lore.add("§7con todas tus fichas.");
            im.setLore(lore);
            info.setItemMeta(im);
        }
        inv.setItem(22, info);
        addTipButton(inv, player);

        ItemStack close = MenuUtils.createButton("§7Cerrar", Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        if (cm != null) {
            cm.getPersistentDataContainer().set(new NamespacedKey(plugin, KEY_ACTION), PersistentDataType.STRING,
                    "close");
            close.setItemMeta(cm);
        }
        inv.setItem(31, close);

        player.openInventory(inv);
    }

    /** Versión torneo: inscribirse (o ver el estado si ya está inscrito). */
    private void openTournament(Player player, PokerTable table, Inventory inv) {
        UUID id = player.getUniqueId();
        boolean registered = table.isRegistered(id);
        List<String> lore = new ArrayList<>();
        lore.add("§7Inscripción: §e" + PokerTable.units(table.getTournamentFee()));
        lore.add("§7Fichas de torneo: §f" + PokerTable.units(table.getTournamentStack()));
        lore.add("§7Inscritos: §f" + table.getTournamentRegisteredCount());
        lore.add("");
        lore.add("§7Las ciegas suben con el tiempo.");
        lore.add("§7El que se queda sin fichas queda eliminado.");
        lore.add("§cSi te levantas o te desconectas, quedas eliminado.");

        if (table.isTournamentRegistering() && !registered) {
            lore.add("");
            lore.add("§aClick para inscribirte");
            ItemStack btn = MenuUtils.createButton("§d§lInscribirme al torneo", Material.NETHER_STAR);
            ItemMeta meta = btn.getItemMeta();
            if (meta != null) {
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, KEY_ACTION),
                        PersistentDataType.STRING, "register");
                btn.setItemMeta(meta);
            }
            inv.setItem(13, btn);
        } else {
            lore.add("");
            lore.add(registered ? "§a¡Estás inscrito! Tus fichas: §f" + PokerTable.units(table.getStack(id))
                    : "§cEl torneo ya empezó.");
            ItemStack info = MenuUtils.createButton("§d§lTorneo de póker", Material.NETHER_STAR);
            ItemMeta meta = info.getItemMeta();
            if (meta != null) {
                meta.setLore(lore);
                info.setItemMeta(meta);
            }
            inv.setItem(13, info);
        }
        inv.setItem(22, MenuUtils.createButton("§7Tus fichas (tokens): §e"
                + PokerTable.units(com.gamblingdex.economy.TokenWallet.balance(player)), Material.SUNFLOWER));
        addTipButton(inv, player);

        ItemStack close = MenuUtils.createButton("§7Cerrar", Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        if (cm != null) {
            cm.getPersistentDataContainer().set(new NamespacedKey(plugin, KEY_ACTION), PersistentDataType.STRING,
                    "close");
            close.setItemMeta(cm);
        }
        inv.setItem(31, close);
        player.openInventory(inv);
    }

    /** Botón de propina al crupier (poker.yml → tips). */
    private void addTipButton(Inventory inv, Player player) {
        if (!plugin.getConfig().getBoolean("poker.tips.enabled", true))
            return;
        ItemStack tip = MenuUtils.createButton("§6§lDar propina al crupier", Material.GOLD_NUGGET);
        ItemMeta meta = tip.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of("§7Un detalle para el crupier.", "§7Sale de tus fichas (tokens), no de la mesa.",
                    "", "§7Tus fichas: §e" + PokerTable.units(com.gamblingdex.economy.TokenWallet.balance(player)),
                    "", "§eClick para elegir cuánto"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, KEY_ACTION), PersistentDataType.STRING, "tip");
            tip.setItemMeta(meta);
        }
        inv.setItem(24, tip);
    }

    private int countTokens(Player player, Material mat) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == mat && plugin.getTokenManager().isToken(it))
                total += it.getAmount();
        }
        return total;
    }
}
