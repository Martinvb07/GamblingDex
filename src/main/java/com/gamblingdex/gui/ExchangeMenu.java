package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public class ExchangeMenu {

    public static final String DEFAULT_TITLE = "§e§lCAMBIO";

    private final GamblingDexPlugin plugin;

    public ExchangeMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        String title = plugin.color(cfg.getString("gui.exchange.title", DEFAULT_TITLE));
        int size = normalizeSize(cfg.getInt("gui.exchange.size", 27));
        Inventory inv = Bukkit.createInventory(new ExchangeMenuHolder(), size, title);

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler(cfg));
        }

        setIfValid(inv,
                cfg.getInt("gui.exchange.buttons.buy.slot", 10),
                button(cfg,
                        materialFrom(cfg.getString("gui.exchange.buttons.buy.material"), Material.EMERALD),
                        cfg.getString("gui.exchange.buttons.buy.name", "&aComprar fichas"),
                        cfg.getStringList("gui.exchange.buttons.buy.lore"),
                        player,
                        null));

        setIfValid(inv,
                cfg.getInt("gui.exchange.buttons.sell_hand.slot", 12),
                button(cfg,
                        materialFrom(cfg.getString("gui.exchange.buttons.sell_hand.material"), Material.GOLD_INGOT),
                        cfg.getString("gui.exchange.buttons.sell_hand.name", "&eVender ficha (mano)"),
                        cfg.getStringList("gui.exchange.buttons.sell_hand.lore"),
                        player,
                        null));

        setIfValid(inv,
                cfg.getInt("gui.exchange.buttons.sell_all.slot", 14),
                button(cfg,
                        materialFrom(cfg.getString("gui.exchange.buttons.sell_all.material"), Material.CHEST),
                        cfg.getString("gui.exchange.buttons.sell_all.name", "&6Vender todo (inventario)"),
                        cfg.getStringList("gui.exchange.buttons.sell_all.lore"),
                        player,
                        null));

        boolean headEnabled = cfg.getBoolean("gui.exchange.info_head.enabled", true);
        if (headEnabled) {
            setIfValid(inv,
                    cfg.getInt("gui.exchange.info_head.slot", 22),
                    playerInfoHead(player, cfg));
        }

        boolean showRates = cfg.getBoolean("exchange.show_rates_item", false);
        String perm = cfg.getString("exchange.rates_item_permission", "gamblingdex.admin");
        boolean hasPerm = perm == null || perm.isBlank() || player.hasPermission(perm);
        if (showRates && hasPerm) {
            double moneyPerUnit = cfg.getDouble("exchange.money_per_unit", 1.0);
            setIfValid(inv,
                    cfg.getInt("gui.exchange.buttons.rates.slot", 16),
                    button(cfg,
                            materialFrom(cfg.getString("gui.exchange.buttons.rates.material"), Material.PAPER),
                            cfg.getString("gui.exchange.buttons.rates.name", "&bTasas"),
                            cfg.getStringList("gui.exchange.buttons.rates.lore"),
                            player,
                            new Placeholder("{money_per_unit}", formatMoney(moneyPerUnit))));
        }

        player.openInventory(inv);
    }

    private ItemStack playerInfoHead(Player player, FileConfiguration cfg) {
        String currencyName = plugin.color(plugin.getConfig().getString("currency.name", "⛃"));

        var slots = plugin.getSlotsStatsManager().get(player.getUniqueId());
        var roulette = plugin.getRouletteStatsManager().get(player.getUniqueId());

        long totalWager = slots.getTotalWagerUnits() + roulette.getTotalWagerUnits();
        long totalWon = slots.getTotalPayoutUnits() + roulette.getTotalPayoutUnits();
        long totalLost = Math.max(0L, totalWager - totalWon);

        String moneyLine;
        try {
            String moneyLineTemplate = cfg.getString("gui.exchange.info_head.money_line", "&7Dinero: &a{money}");
            String moneyNa = cfg.getString("gui.exchange.info_head.money_na", "&7Dinero: &cN/A");
            if (plugin.getVaultEconomy() != null && plugin.getVaultEconomy().isAvailable()) {
                double money = plugin.getVaultEconomy().getBalance(player);
                moneyLine = plugin.color(moneyLineTemplate.replace("{money}", formatMoney(money)));
            } else {
                moneyLine = plugin.color(moneyNa);
            }
        } catch (Throwable t) {
            moneyLine = plugin.color(cfg.getString("gui.exchange.info_head.money_na", "&7Dinero: &cN/A"));
        }

        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta base = it.getItemMeta();
        if (base instanceof SkullMeta sm) {
            sm.setOwningPlayer(player);
            sm.setDisplayName(plugin.color(cfg.getString("gui.exchange.info_head.name", "&e&lTu info")));

            List<String> loreTpl = cfg.getStringList("gui.exchange.info_head.lore");
            if (loreTpl == null || loreTpl.isEmpty()) {
                loreTpl = List.of(
                        "{money_line}",
                        "&8(Slots + Ruleta)",
                        "&7Total apostado: &e{wager} &7{currency}",
                        "&7Total ganado: &a{won} &7{currency}",
                        "&7Total perdido: &c{lost} &7{currency}");
            }

            List<String> lore = new ArrayList<>();
            for (String line : loreTpl) {
                if (line == null)
                    continue;
                String out = line
                        .replace("{money_line}", moneyLine)
                        .replace("{wager}", formatLong(totalWager))
                        .replace("{won}", formatLong(totalWon))
                        .replace("{lost}", formatLong(totalLost))
                        .replace("{currency}", currencyName);
                lore.add(plugin.color(out));
            }
            sm.setLore(lore);
            sm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(sm);
        }
        return it;
    }

    private static String formatLong(long n) {
        try {
            return NumberFormat.getInstance(new Locale("es", "ES")).format(n);
        } catch (Exception ignored) {
            return String.valueOf(n);
        }
    }

    private static String formatMoney(double n) {
        try {
            NumberFormat nf = NumberFormat.getInstance(new Locale("es", "ES"));
            nf.setMaximumFractionDigits(2);
            nf.setMinimumFractionDigits(0);
            return nf.format(n);
        } catch (Exception ignored) {
            return String.valueOf(n);
        }
    }

    private ItemStack filler(FileConfiguration cfg) {
        Material mat = materialFrom(cfg.getString("gui.exchange.filler.material"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(plugin.color(cfg.getString("gui.exchange.filler.name", " ")));
            it.setItemMeta(im);
        }
        return it;
    }

    private ItemStack button(FileConfiguration cfg, Material mat, String name, List<String> lore, Player player,
            Placeholder placeholder) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(plugin.color(name));

            List<String> outLore;
            if (lore == null || lore.isEmpty()) {
                outLore = List.of();
            } else {
                outLore = new ArrayList<>();
                for (String line : lore) {
                    if (line == null)
                        continue;
                    String out = line;
                    if (placeholder != null) {
                        out = out.replace(placeholder.key, placeholder.value);
                    }
                    out = out.replace("{player}", player == null ? "" : player.getName());
                    outLore.add(plugin.color(out));
                }
            }
            im.setLore(outLore);
            im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(im);
        }
        return it;
    }

    private static void setIfValid(Inventory inv, int slot, ItemStack item) {
        if (inv == null || item == null)
            return;
        if (slot < 0 || slot >= inv.getSize())
            return;
        inv.setItem(slot, item);
    }

    private static int normalizeSize(int raw) {
        int size = raw;
        if (size < 9)
            size = 9;
        if (size > 54)
            size = 54;
        int mod = size % 9;
        if (mod != 0) {
            size = size + (9 - mod);
            if (size > 54)
                size = 54;
        }
        return size;
    }

    private static Material materialFrom(String name, Material fallback) {
        if (name == null || name.isBlank())
            return fallback;
        try {
            Material m = Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
            return m == null ? fallback : m;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class Placeholder {
        private final String key;
        private final String value;

        private Placeholder(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
