package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Menú genérico para elegir un monto en fichas (lo usan los minijuegos).
 * Botones -/+ por escalones, mínimo, todo lo que tienes, confirmar y cancelar.
 * Este menú NO cobra: al confirmar llama a {@code onConfirm} con el monto y el
 * juego cobra con {@link TokenWallet#take}.
 */
public final class AmountPickerMenu {

    private static final long[] STEPS = { 1, 10, 100, 1_000, 10_000 };

    private AmountPickerMenu() {
    }

    public static final class Holder implements InventoryHolder {
        final String title;
        final long min;
        final long max;
        long amount;
        final Consumer<Long> onConfirm;
        final Runnable onCancel;
        final List<String> info;

        Holder(String title, long min, long max, long amount, List<String> info, Consumer<Long> onConfirm,
                Runnable onCancel) {
            this.title = title;
            this.min = min;
            this.max = max;
            this.amount = amount;
            this.info = info;
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /**
     * @param min       apuesta mínima
     * @param max       apuesta máxima (0 = sin tope; igual se limita a lo que tiene)
     * @param info      líneas extra para el ítem central (puede ser vacío)
     * @param onConfirm recibe el monto elegido (el menú ya se cerró)
     * @param onCancel  opcional
     */
    public static void open(Player player, String title, long min, long max, long initial, List<String> info,
            Consumer<Long> onConfirm, Runnable onCancel) {
        Holder h = new Holder(title, Math.max(1, min), max, Math.max(Math.max(1, min), initial), info, onConfirm,
                onCancel);
        render(player, h);
    }

    private static void render(Player player, Holder h) {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        Inventory inv = Bukkit.createInventory(h, 27, plugin.color(h.title));
        for (int i = 0; i < 27; i++)
            inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " ", null));

        long balance = TokenWallet.balance(player);
        // Restar: slots 9-12 (-10000.. -10); sumar: 14-17
        int[] minus = { 9, 10, 11, 12 };
        int[] plus = { 14, 15, 16, 17 };
        for (int k = 0; k < 4; k++) {
            long step = STEPS[4 - k];
            inv.setItem(minus[k], item(Material.RED_STAINED_GLASS_PANE, "§c-" + u(step), List.of("§7Resta " + u(step))));
            long pstep = STEPS[k + 1];
            inv.setItem(plus[k], item(Material.LIME_STAINED_GLASS_PANE, "§a+" + u(pstep), List.of("§7Suma " + u(pstep))));
        }
        inv.setItem(4, item(Material.LIME_DYE, "§a+1", List.of("§7Suma 1")));
        inv.setItem(22, item(Material.RED_DYE, "§c-1", List.of("§7Resta 1")));

        List<String> lore = new ArrayList<>();
        lore.add("§7Tus fichas: §e" + u(balance));
        lore.add("§7Mínimo: §f" + u(h.min) + (h.max > 0 ? " §8| §7Máximo: §f" + u(h.max) : ""));
        if (h.info != null && !h.info.isEmpty()) {
            lore.add("");
            for (String l : h.info)
                lore.add(plugin.color(l));
        }
        inv.setItem(13, item(Material.SUNFLOWER, "§6§lApuesta: §e" + u(h.amount), lore));

        inv.setItem(18, item(Material.IRON_NUGGET, "§fMínimo", List.of("§7Pone " + u(h.min))));
        inv.setItem(19, item(Material.GOLD_NUGGET, "§fTodo", List.of("§7Pone todas tus fichas" + (h.max > 0 ? " (hasta el máximo)" : ""))));
        inv.setItem(26, item(Material.EMERALD_BLOCK, "§a§lConfirmar §e" + u(h.amount),
                List.of(balance >= h.amount ? "§7Click para apostar" : "§cNo te alcanzan las fichas")));
        inv.setItem(0, item(Material.BARRIER, "§cCancelar", null));

        player.openInventory(inv);
    }

    private static long clampAmount(Holder h, long v) {
        long hi = h.max > 0 ? h.max : Long.MAX_VALUE;
        v = Math.min(v, hi);
        return Math.max(h.min, v);
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null)
                meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static String u(long v) {
        return NumberFormat.getInstance(Locale.forLanguageTag("es-ES")).format(v);
    }

    /** Listener único (lo registra ModuleManager). */
    public static final class Listener implements org.bukkit.event.Listener {

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p))
                return;
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot < 0 || slot >= 27)
                return;

            long balance = TokenWallet.balance(p);
            long before = h.amount;
            switch (slot) {
                case 0 -> {
                    p.closeInventory();
                    if (h.onCancel != null)
                        h.onCancel.run();
                    return;
                }
                case 4 -> h.amount += 1;
                case 22 -> h.amount -= 1;
                case 9, 10, 11, 12 -> h.amount -= STEPS[4 - (slot - 9)];
                case 14, 15, 16, 17 -> h.amount += STEPS[slot - 14 + 1];
                case 18 -> h.amount = h.min;
                case 19 -> h.amount = balance;
                case 26 -> {
                    if (balance < h.amount) {
                        p.sendMessage(GamblingDexPlugin.getInstance().color(
                                "&cNo te alcanzan las fichas. Tienes &e" + u(balance) + "&c."));
                        return;
                    }
                    p.closeInventory();
                    h.onConfirm.accept(h.amount);
                    return;
                }
                default -> {
                    return;
                }
            }
            h.amount = clampAmount(h, h.amount);
            if (h.amount != before)
                render(p, h);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof Holder)
                e.setCancelled(true);
        }
    }
}
