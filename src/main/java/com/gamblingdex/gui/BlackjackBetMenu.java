package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.games.blackjack.BlackjackTable;
import com.gamblingdex.games.blackjack.BlackjackTable.BetSpot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class BlackjackBetMenu {

    public static final String KEY_ACTION = "gdx_bj_bet_action";
    public static final String KEY_VALUE = "gdx_bj_bet_value";

    private final GamblingDexPlugin plugin;

    public BlackjackBetMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, BlackjackTable table) {
        if (player == null || table == null) {
            return;
        }

        Inventory inv = Bukkit.createInventory(new BlackjackBetMenuHolder(table.getTableKey()), 36,
                "§6§lBlackjack §8- §eApuesta");

        // Border panes
        int lastRowStart = inv.getSize() - 9;
        for (int i = 0; i < inv.getSize(); i++) {
            if (i < 9 || i >= lastRowStart || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, MenuUtils.createButton("", Material.WHITE_STAINED_GLASS_PANE));
            } else {
                inv.setItem(i, MenuUtils.createButton("", Material.GRAY_STAINED_GLASS_PANE));
            }
        }

        // Denomination buttons
        int[] tokenSlots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21 };
        int idx = 0;
        TokenManager tokenManager = plugin.getTokenManager();

        for (Map.Entry<Material, Integer> entry : TokenManager.getDenoms().entrySet()) {
            if (idx >= tokenSlots.length) {
                break;
            }
            Material mat = entry.getKey();
            int denom = entry.getValue();
            int count = countTokens(player, mat);

            ItemStack icon = tokenManager.createToken(mat, 1);
            icon.setAmount(Math.min(64, Math.max(1, count)));

            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e+" + denom + " ⛃");
                List<String> lore = new ArrayList<>();
                lore.add("§7Disponible: §f" + count);
                lore.add("§7Click: §a+1 §8| §7Shift+Click: §a+Todo");
                meta.setLore(lore);

                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                NamespacedKey kAction = new NamespacedKey(plugin, KEY_ACTION);
                NamespacedKey kValue = new NamespacedKey(plugin, KEY_VALUE);
                pdc.set(kAction, PersistentDataType.STRING, "denom");
                pdc.set(kValue, PersistentDataType.STRING, mat.name());

                icon.setItemMeta(meta);
            }

            inv.setItem(tokenSlots[idx], icon);
            idx++;
        }

        UUID id = player.getUniqueId();
        boolean sideBets = table.sideBetsEnabled();
        BetSpot selected = table.getSelectedSpot(id);

        // Info
        long bet = table.getBetUnits(id);
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName("§6Apuesta principal: §e" + prettyUnits(bet));
            List<String> lore = new ArrayList<>();
            if (sideBets) {
                lore.add("§dPares Perfectos: §e" + prettyUnits(table.getBetUnits(id, BetSpot.PERFECT_PAIRS)));
                lore.add("§b21+3: §e" + prettyUnits(table.getBetUnits(id, BetSpot.TWENTY_ONE_PLUS_THREE)));
                lore.add("");
                lore.add("§7Apostando en: §f" + BlackjackTable.spotName(selected));
            }
            lore.add("§7Haz clic en una ficha para apostar.");
            lore.add("§7Cierra el menú cuando termines.");
            infoMeta.setLore(lore);
            info.setItemMeta(infoMeta);
        }
        inv.setItem(22, info);

        // Tabla de pagos
        inv.setItem(23, paytable());

        // Clear bet
        inv.setItem(24, actionButton("§cRetirar apuestas", Material.REDSTONE, "clear"));
        // Close
        inv.setItem(26, actionButton("§7Cerrar", Material.BARRIER, "close"));

        // Selector de apuesta (fila inferior)
        if (sideBets) {
            inv.setItem(29, spotButton(BetSpot.MAIN, selected, Material.GOLD_BLOCK, "§6",
                    List.of("§7Tu mano contra el dealer.", "§7Gana: §f1:1 §8| §7Blackjack: §f"
                            + plugin.getConfig().getString("blackjack.blackjack_payout", "3:2"))));
            inv.setItem(31, spotButton(BetSpot.PERFECT_PAIRS, selected, Material.AMETHYST_CLUSTER, "§d",
                    List.of("§7Gana si tus 2 primeras cartas", "§7forman un par.")));
            inv.setItem(33, spotButton(BetSpot.TWENTY_ONE_PLUS_THREE, selected, Material.EMERALD, "§b",
                    List.of("§7Tus 2 cartas + la carta visible", "§7del dealer forman jugada de póker.")));
        }

        player.openInventory(inv);
    }

    private ItemStack spotButton(BetSpot spot, BetSpot selected, Material mat, String color, List<String> desc) {
        boolean isSelected = spot == selected;
        ItemStack it = actionButton((isSelected ? "§a▶ " : "") + color + "§l" + BlackjackTable.spotName(spot), mat,
                "spot");
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>(desc);
            lore.add("");
            lore.add(isSelected ? "§aSeleccionado: tus fichas van aquí" : "§eClick para apostar aquí");
            meta.setLore(lore);
            if (isSelected) {
                meta.addEnchant(Enchantment.LURE, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, KEY_VALUE), PersistentDataType.STRING,
                    spot.name());
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack paytable() {
        FileConfiguration cfg = plugin.getConfig();
        String pp = "blackjack.side_bets.perfect_pairs.";
        String p3 = "blackjack.side_bets.21_plus_3.";
        boolean h17 = cfg.getBoolean("blackjack.dealer_hits_soft_17", false);

        List<String> lore = new ArrayList<>();
        lore.add("§6§lPrincipal");
        lore.add("§7Ganar: §f1:1");
        lore.add("§7Blackjack: §f" + cfg.getString("blackjack.blackjack_payout", "3:2"));
        lore.add("§7Empate: §fse devuelve la apuesta");
        lore.add("§7Dealer pide hasta 17 " + (h17 ? "§8(pide con 17 suave)" : "§8(se planta en todo 17)"));
        lore.add("§7Doblar: §fsolo con 2 cartas");
        if (cfg.getBoolean("blackjack.side_bets.enabled", true)) {
            lore.add("");
            lore.add("§d§lPares Perfectos");
            lore.add("§7Par perfecto §8(mismo palo)§7: §f" + cfg.getInt(pp + "perfect_pair", 25) + ":1");
            lore.add("§7Par de color §8(mismo color)§7: §f" + cfg.getInt(pp + "colored_pair", 12) + ":1");
            lore.add("§7Par mixto §8(distinto color)§7: §f" + cfg.getInt(pp + "mixed_pair", 6) + ":1");
            lore.add("");
            lore.add("§b§l21+3");
            lore.add("§7Trío del mismo palo: §f" + cfg.getInt(p3 + "suited_trips", 100) + ":1");
            lore.add("§7Escalera de color: §f" + cfg.getInt(p3 + "straight_flush", 40) + ":1");
            lore.add("§7Trío: §f" + cfg.getInt(p3 + "three_of_a_kind", 30) + ":1");
            lore.add("§7Escalera: §f" + cfg.getInt(p3 + "straight", 10) + ":1");
            lore.add("§7Color §8(mismo palo)§7: §f" + cfg.getInt(p3 + "flush", 5) + ":1");
        }

        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e§lTabla de pagos");
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack actionButton(String name, Material mat, String action) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            NamespacedKey kAction = new NamespacedKey(plugin, KEY_ACTION);
            pdc.set(kAction, PersistentDataType.STRING, action);
            it.setItemMeta(meta);
        }
        return it;
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

    private static String prettyUnits(long units) {
        try {
            return NumberFormat.getInstance(new Locale("es", "ES")).format(units);
        } catch (Exception ignored) {
            return String.valueOf(units);
        }
    }
}
