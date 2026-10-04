package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.economy.TokenWallet;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.NumberFormat;
import java.util.*;

/**
 * Mesa de cambio en un solo menú: pestañas COMPRAR / VENDER, las fichas en el
 * centro, la cantidad abajo y un botón de confirmar que muestra el total. El
 * menú queda abierto después de cada operación para seguir cambiando.
 *
 * <pre>
 *  0..8  luces, 4 = tu info
 * 11 COMPRAR · 15 VENDER
 * 19..25, 28..34  fichas
 * 37 -64 · 38 -10 · 39 -1 · 40 cantidad · 41 +1 · 42 +10 · 43 +64
 * 45 cerrar · 47 todo · 49 CONFIRMAR · 51 vender todas · 53 tasas
 * </pre>
 */
public class ExchangeMenu {

    public static final String DEFAULT_TITLE = "&8&l✦ &e&lCAMBIO &8&l✦";
    private static final int[] TOKEN_SLOTS = { 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };
    private static final int S_HEAD = 4, S_BUY = 11, S_SELL = 15, S_AMOUNT = 40;
    private static final int S_CLOSE = 45, S_ALL = 47, S_CONFIRM = 49, S_SELL_ALL = 51, S_RATES = 53;
    private static final int MAX_AMOUNT = 64 * 36;

    /** Menú abierto de un jugador. */
    public static final class Session implements InventoryHolder {
        final UUID player;
        Inventory inv;
        boolean selling;
        Material token;
        int amount = 1;
        long lastAction;

        Session(UUID player) {
            this.player = player;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GamblingDexPlugin plugin;

    public ExchangeMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        open(player, false);
    }

    public void open(Player player, boolean selling) {
        Session s = new Session(player.getUniqueId());
        s.selling = selling;
        List<Material> denoms = new ArrayList<>(TokenManager.getDenoms().keySet());
        if (!denoms.isEmpty())
            s.token = denoms.get(0);
        String raw = plugin.getConfig().getString("gui.exchange.title", DEFAULT_TITLE);
        if (raw == null || raw.equals("§e§lCAMBIO") || raw.equals("&e&lCAMBIO")) // título de la versión anterior
            raw = DEFAULT_TITLE;
        s.inv = Bukkit.createInventory(s, 54, plugin.color(raw));
        render(player, s);
        player.openInventory(s.inv);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
    }

    // ------------------------------------------------------------------

    private double rate() {
        return plugin.getConfig().getDouble("exchange.money_per_unit", 1.0);
    }

    private int denom(Material m) {
        Integer v = m == null ? null : TokenManager.getDenoms().get(m);
        return v == null ? 0 : v;
    }

    private int owned(Player p, Material mat) {
        int total = 0;
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() == mat && plugin.getTokenManager().isToken(it))
                total += it.getAmount();
        return total;
    }

    private double money(Player p) {
        try {
            if (plugin.getVaultEconomy() != null && plugin.getVaultEconomy().isAvailable())
                return plugin.getVaultEconomy().getBalance(p);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private int clampAmount(Player p, Session s, int v) {
        int hi = s.selling ? owned(p, s.token) : MAX_AMOUNT;
        return Math.max(hi <= 0 ? 0 : 1, Math.min(Math.max(hi, 0), v));
    }

    private void render(Player p, Session s) {
        Inventory inv = s.inv;
        inv.clear();
        if (s.selling)
            s.amount = clampAmount(p, s, s.amount);

        Material light = s.selling ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
        for (int i = 0; i < 9; i++)
            inv.setItem(i, Icons.of(i % 2 == 0 ? light : Material.YELLOW_STAINED_GLASS_PANE, " ", null));
        inv.setItem(S_HEAD, head(p));

        // Pestañas
        inv.setItem(S_BUY, Icons.of(Material.EMERALD, 1, (s.selling ? "&7" : "&a&l▶ ") + "COMPRAR FICHAS",
                List.of("&7Cambia dinero del servidor", "&7por fichas del casino.", "", s.selling ? "&eClick para cambiar" : "&a✔ Seleccionado"),
                !s.selling));
        inv.setItem(S_SELL, Icons.of(Material.GOLD_INGOT, 1, (s.selling ? "&6&l▶ " : "&7") + "VENDER FICHAS",
                List.of("&7Cambia tus fichas por", "&7dinero del servidor.", "", s.selling ? "&a✔ Seleccionado" : "&eClick para cambiar"),
                s.selling));

        // Fichas
        int i = 0;
        for (Map.Entry<Material, Integer> e : TokenManager.getDenoms().entrySet()) {
            if (i >= TOKEN_SLOTS.length)
                break;
            Material mat = e.getKey();
            int have = owned(p, mat);
            boolean sel = mat == s.token;
            List<String> lore = new ArrayList<>();
            lore.add("&7Valor: &e" + fmt(e.getValue()) + " &7fichas");
            lore.add((s.selling ? "&7Te pagan: &a$" : "&7Precio: &a$") + money(e.getValue() * rate()) + " &7c/u");
            lore.add("&7Tienes: &f" + fmt(have));
            lore.add("");
            if (s.selling && have <= 0)
                lore.add("&8No tienes de esta ficha");
            else
                lore.add(sel ? "&a✔ Seleccionada" : "&eClick para elegir");
            inv.setItem(TOKEN_SLOTS[i], token(mat, sel ? Math.max(1, Math.min(64, s.amount)) : 1, lore, sel));
            i++;
        }

        // Cantidad
        int[][] steps = { { 37, -64 }, { 38, -10 }, { 39, -1 }, { 41, 1 }, { 42, 10 }, { 43, 64 } };
        for (int[] st : steps) {
            boolean plus = st[1] > 0;
            inv.setItem(st[0], Icons.of(plus ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    Math.abs(st[1]), (plus ? "&a+" : "&c") + st[1], List.of("&7Cantidad de fichas"), false));
        }
        long units = (long) denom(s.token) * s.amount;
        double total = units * rate();
        inv.setItem(S_AMOUNT, Icons.of(Material.SUNFLOWER, 1, "&6&lCantidad: &e" + fmt(s.amount),
                List.of("&7Ficha: &f" + fmt(denom(s.token)), "&7Fichas totales: &e" + fmt(units),
                        "&7Total: &a$" + money(total)), true));

        // Acciones
        inv.setItem(S_CLOSE, Icons.of(Material.BARRIER, "&cCerrar", null));
        inv.setItem(S_ALL, Icons.of(Material.HOPPER, s.selling ? "&6Todas las de esta ficha" : "&aLo máximo que puedo pagar",
                List.of(s.selling ? "&7Pone todas las que tienes" : "&7Según tu dinero")));
        if (s.selling) {
            boolean ok = s.amount > 0;
            inv.setItem(S_CONFIRM, Icons.of(ok ? Material.GOLD_BLOCK : Material.GRAY_CONCRETE, 1,
                    ok ? "&6&lVENDER &e" + fmt(s.amount) + " &6&lpor &a$" + money(total) : "&7No tienes de esta ficha",
                    List.of("&7Recibes el dinero al instante"), ok));
            long all = 0;
            for (ItemStack it : p.getInventory().getContents()) {
                Integer v = plugin.getTokenManager().getTokenValue(it);
                if (v != null)
                    all += (long) v * it.getAmount();
            }
            inv.setItem(S_SELL_ALL, Icons.of(Material.CHEST, 1, "&e&lVender TODAS mis fichas",
                    List.of("&7Valor: &e" + fmt(all) + " &7fichas", "&7Recibes: &a$" + money(all * rate())), all > 0));
        } else {
            double have = money(p);
            boolean ok = have < 0 || have >= total;
            inv.setItem(S_CONFIRM, Icons.of(ok ? Material.EMERALD_BLOCK : Material.RED_CONCRETE, 1,
                    "&a&lCOMPRAR &e" + fmt(s.amount) + " &a&lpor &a$" + money(total),
                    List.of(ok ? "&7Click para comprar" : "&cNo te alcanza el dinero"), ok));
        }
        boolean showRates = plugin.getConfig().getBoolean("exchange.show_rates_item", false);
        String perm = plugin.getConfig().getString("exchange.rates_item_permission", "gamblingdex.admin");
        if (showRates && (perm == null || perm.isBlank() || p.hasPermission(perm)))
            inv.setItem(S_RATES, Icons.of(Material.PAPER, "&bTasa", List.of("&71 ficha = &a$" + money(rate()))));
        Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    private ItemStack token(Material mat, int amount, List<String> lore, boolean glow) {
        ItemStack it = plugin.getTokenManager().createToken(mat, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<String> l = new ArrayList<>();
            for (String s : lore)
                l.add(plugin.color(s));
            meta.setLore(l);
            if (glow) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
                if (ench != null)
                    meta.addEnchant(ench, 1, true);
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack head(Player p) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (it.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(p);
            sm.setDisplayName(plugin.color("&e&l" + p.getName()));
            double m = money(p);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.color("&7Dinero: " + (m < 0 ? "&cN/A" : "&a$" + money(m))));
            lore.add(plugin.color("&7Fichas: &e" + fmt(TokenWallet.balance(p))));
            lore.add(plugin.color("&71 ficha = &a$" + money(rate())));
            var lock = plugin.getBonusLock();
            if (lock != null && lock.locked(p.getUniqueId()) > 0) {
                lore.add("");
                lore.add(plugin.color("&6Fichas de bono: &e" + fmt(lock.locked(p.getUniqueId())) + " &8(no se venden)"));
                lore.add(plugin.color("&7Apuesta &e" + fmt(lock.remaining(p.getUniqueId())) + " &7más para liberarlas"));
            }
            sm.setLore(lore);
            it.setItemMeta(sm);
        }
        return it;
    }

    // ------------------------------------------------------------------
    // Clicks (los manda ExchangeMenuListener)
    // ------------------------------------------------------------------

    public void handleClick(Player p, Session s, int slot, boolean shift) {
        switch (slot) {
            case S_BUY, S_SELL -> {
                boolean sell = slot == S_SELL;
                if (s.selling != sell) {
                    s.selling = sell;
                    s.amount = 1;
                    click(p);
                    render(p, s);
                }
            }
            case 37, 38, 39, 41, 42, 43 -> {
                int d = switch (slot) {
                    case 37 -> -64;
                    case 38 -> -10;
                    case 39 -> -1;
                    case 41 -> 1;
                    case 42 -> 10;
                    default -> 64;
                };
                s.amount = clampAmount(p, s, s.amount + d);
                click(p);
                render(p, s);
            }
            case S_ALL -> {
                if (s.selling) {
                    s.amount = clampAmount(p, s, MAX_AMOUNT);
                } else {
                    double m = money(p);
                    double each = denom(s.token) * rate();
                    s.amount = m < 0 || each <= 0 ? s.amount : (int) Math.max(1, Math.min(MAX_AMOUNT, Math.floor(m / each)));
                }
                click(p);
                render(p, s);
            }
            case S_CONFIRM -> {
                if (debounce(s))
                    return;
                if (s.selling)
                    sell(p, s);
                else
                    buy(p, s);
                render(p, s);
            }
            case S_SELL_ALL -> {
                if (!s.selling || debounce(s))
                    return;
                sellAll(p);
                render(p, s);
            }
            case S_CLOSE -> p.closeInventory();
            default -> {
                for (int i = 0; i < TOKEN_SLOTS.length; i++) {
                    if (TOKEN_SLOTS[i] != slot)
                        continue;
                    List<Material> denoms = new ArrayList<>(TokenManager.getDenoms().keySet());
                    if (i < denoms.size() && denoms.get(i) != s.token) {
                        s.token = denoms.get(i);
                        s.amount = 1;
                        click(p);
                        render(p, s);
                    }
                    return;
                }
            }
        }
    }

    private boolean debounce(Session s) {
        long now = System.currentTimeMillis();
        if (now - s.lastAction < 300L)
            return true;
        s.lastAction = now;
        return false;
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
    }

    private void buy(Player p, Session s) {
        int d = denom(s.token);
        if (d <= 0 || s.amount <= 0)
            return;
        double cost = (double) s.amount * d * rate();
        if (!plugin.exchangeTakeMoney(p, cost)) {
            p.sendMessage(plugin.getMessages().getString("messages.exchange.buy.not_enough_money",
                    "&cNo tienes dinero suficiente o no hay economía disponible."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        int left = s.amount;
        while (left > 0) {
            int n = Math.min(64, left);
            for (ItemStack lf : p.getInventory().addItem(plugin.getTokenManager().createToken(s.token, n)).values())
                p.getWorld().dropItemNaturally(p.getLocation(), lf);
            left -= n;
        }
        p.sendMessage(plugin.getMessages().format("messages.exchange.buy.success",
                "&a¡Has comprado &e{amount}&a ficha(s) por &e{money}&a!",
                Map.of("amount", fmt(s.amount), "money", money(cost))));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    private void sell(Player p, Session s) {
        int d = denom(s.token);
        int want = clampAmount(p, s, s.amount);
        if (d <= 0 || want <= 0) {
            p.sendMessage(plugin.getMessages().getString("messages.exchange.sell.not_enough_tokens",
                    "&cNo tienes suficientes fichas para vender."));
            return;
        }
        var lock = plugin.getBonusLock();
        if (lock != null && lock.locked(p.getUniqueId()) > 0) {
            int allowed = (int) Math.min(want, lock.sellable(p) / d);
            if (allowed <= 0) {
                p.sendMessage(lock.blockedMessage(p));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return;
            }
            if (allowed < want) {
                p.sendMessage(lock.blockedMessage(p));
                want = allowed;
            }
        }
        int sold = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType() != s.token || !plugin.getTokenManager().isToken(it))
                continue;
            int take = Math.min(it.getAmount(), want - sold);
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0)
                it.setType(Material.AIR);
            sold += take;
            if (sold >= want)
                break;
        }
        double money = (double) sold * d * rate();
        if (!plugin.exchangeGiveMoney(p, money)) {
            int left = sold;
            while (left > 0) {
                int n = Math.min(64, left);
                for (ItemStack lf : p.getInventory().addItem(plugin.getTokenManager().createToken(s.token, n)).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), lf);
                left -= n;
            }
            p.sendMessage(plugin.getMessages().getString("messages.exchange.sell.deposit_failed",
                    "&cNo se pudo darte dinero (¿Vault/Economía?). No se vendieron tus fichas."));
            return;
        }
        p.sendMessage(plugin.getMessages().format("messages.exchange.sell.success",
                "&aVendiste &e{amount}&a fichas por &e{money}&a de dinero del server.",
                Map.of("amount", fmt(sold), "money", money(money))));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
    }

    private void sellAll(Player p) {
        var lock = plugin.getBonusLock();
        long keep = lock == null ? 0 : Math.min(lock.locked(p.getUniqueId()), TokenWallet.balance(p));
        if (keep > 0 && lock.sellable(p) <= 0) {
            p.sendMessage(lock.blockedMessage(p));
            return;
        }
        long units = plugin.exchangeTakeAllTokens(p);
        if (keep > 0) {
            plugin.exchangeGiveTokens(p, keep); // las de bono se quedan
            units -= keep;
            p.sendMessage(lock.blockedMessage(p));
        }
        if (units <= 0) {
            p.sendMessage(plugin.getMessages().getString("messages.exchange.sell_all.no_tokens",
                    "&cNo tienes tokens para vender."));
            return;
        }
        double money = units * rate();
        if (!plugin.exchangeGiveMoney(p, money)) {
            plugin.exchangeGiveTokens(p, units);
            p.sendMessage(plugin.getMessages().getString("messages.exchange.sell_all.deposit_failed",
                    "&cNo se pudo darte dinero (¿Vault/Economía?). No se vendieron tus tokens."));
            return;
        }
        p.sendMessage(plugin.getMessages().format("messages.exchange.sell_all.success",
                "&aVendiste TODO por &e{money}&a de dinero del server.", Map.of("money", money(money))));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    // ------------------------------------------------------------------

    private static String fmt(long n) {
        return NumberFormat.getInstance(Locale.forLanguageTag("es-ES")).format(n);
    }

    private static String money(double n) {
        NumberFormat nf = NumberFormat.getInstance(Locale.forLanguageTag("es-ES"));
        nf.setMaximumFractionDigits(2);
        nf.setMinimumFractionDigits(0);
        return nf.format(n);
    }
}
