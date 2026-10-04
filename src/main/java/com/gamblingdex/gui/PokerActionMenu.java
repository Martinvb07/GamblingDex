package com.gamblingdex.gui;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.poker.PokerTable;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Menú de turno del póker: retirarse, pasar/pagar, subir (con tamaños
 * predefinidos o ajuste manual) y all-in. Muestra tus cartas, la mesa y el
 * estado de los demás jugadores.
 */
public class PokerActionMenu {

    public static final String KEY_ACTION = "gdx_pk_action";
    public static final String KEY_VALUE = "gdx_pk_value";

    private final GamblingDexPlugin plugin;

    public PokerActionMenu(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, PokerTable table) {
        if (player == null || table == null)
            return;
        UUID id = player.getUniqueId();

        Inventory inv = Bukkit.createInventory(new PokerActionMenuHolder(table.getTableKey()), 54,
                "§6§lPóker §8- §aTu turno §8(§f" + table.getTurnSecondsLeft() + "s§8)");

        for (int i = 0; i < inv.getSize(); i++) {
            boolean border = i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8;
            inv.setItem(i, MenuUtils.createButton(" ",
                    border ? Material.GREEN_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE));
        }

        long bb = table.getBigBlind();
        long toCall = table.getToCall(id);
        long stack = table.getStack(id);
        long pot = table.getPot();

        // --- Fila superior: tus cartas, mesa, bote ---
        inv.setItem(11, info(Material.PAPER, "§e§lTus cartas",
                List.of(PokerTable.formatCards(table.getHoleCards(id)),
                        "§7Mano: §e" + table.getHandStrength(id),
                        "",
                        "§7Tus fichas: §e" + u(stack))));
        inv.setItem(13, info(Material.MAP, "§6§lMesa",
                List.of(table.getBoard().isEmpty() ? "§8(aún sin cartas)" : PokerTable.formatCards(table.getBoard()),
                        "",
                        "§7Bote: §e" + u(pot),
                        "§7Apuesta actual: §f" + u(table.getCurrentBet()),
                        "§7Ciegas: §f" + u(table.getSmallBlind()) + "/" + u(bb))));
        List<String> seatsLore = new ArrayList<>(table.describeSeats(id));
        inv.setItem(15, info(Material.PLAYER_HEAD, "§b§lJugadores", seatsLore));

        // --- Acciones principales ---
        inv.setItem(29, button(Material.RED_CONCRETE, "§c§lRetirarse",
                List.of("§7Tiras tus cartas y pierdes", "§7lo que ya apostaste en esta mano."), "fold", null));

        if (toCall <= 0) {
            inv.setItem(31, button(Material.LIME_CONCRETE, "§a§lPasar",
                    List.of("§7No apuestas nada y sigues en la mano."), "check", null));
        } else {
            boolean callAllIn = toCall >= stack;
            inv.setItem(31, button(Material.LIME_CONCRETE,
                    callAllIn ? "§a§lPagar §e" + u(toCall) + " §6(ALL-IN)" : "§a§lPagar §e" + u(toCall),
                    List.of("§7Igualas la apuesta actual."), "check", null));
        }

        boolean canRaise = table.canRaise(id);
        long target = table.getRaiseTarget(id);
        boolean betWord = table.isBetSituation();
        String verb = betWord ? "Apostar" : "Subir a";
        if (canRaise) {
            inv.setItem(33, button(Material.GOLD_BLOCK, "§6§l" + verb + " §e" + u(target),
                    List.of("§7Confirma tu " + (betWord ? "apuesta" : "subida") + ".",
                            "§7Ajusta el monto en la fila de abajo.",
                            "§8Mín: " + u(table.getMinRaiseTo(id)) + " §8| Máx: " + u(table.getMaxRaiseTo(id))),
                    "raise", null));
        } else {
            inv.setItem(33, info(Material.GRAY_DYE, "§8" + verb + " (no disponible)",
                    List.of("§7Solo puedes pagar o retirarte.")));
        }

        List<String> allInLore = new ArrayList<>(List.of("§7Apuestas todas tus fichas."));
        if (!canRaise && stack > toCall)
            allInLore.add("§8(no puedes subir: contará como pago)");
        inv.setItem(35, button(Material.TNT, "§4§lALL-IN §e" + u(stack), allInLore, "allin", null));

        // --- Ajuste de la subida ---
        if (canRaise) {
            inv.setItem(37, button(Material.RED_DYE, "§c-5 BB", List.of("§7Resta " + u(bb * 5)), "adjust",
                    -bb * 5));
            inv.setItem(38, button(Material.RED_DYE, "§c-1 BB", List.of("§7Resta " + u(bb)), "adjust", -bb));
            inv.setItem(39, preset(table, id, "§fMínimo", table.getMinRaiseTo(id), target));
            inv.setItem(40, preset(table, id, "§f½ Bote", table.getPotRaiseTo(id, 0.5), target));
            inv.setItem(41, preset(table, id, "§f¾ Bote", table.getPotRaiseTo(id, 0.75), target));
            inv.setItem(42, preset(table, id, "§fBote", table.getPotRaiseTo(id, 1.0), target));
            inv.setItem(43, button(Material.LIME_DYE, "§a+1 BB", List.of("§7Suma " + u(bb)), "adjust", bb));
            inv.setItem(44, button(Material.LIME_DYE, "§a+5 BB", List.of("§7Suma " + u(bb * 5)), "adjust",
                    bb * 5));
        }

        inv.setItem(49, button(Material.BARRIER, "§7Cerrar",
                List.of("§7Click derecho a la mesa para reabrir."), "close", null));

        player.openInventory(inv);
    }

    private ItemStack preset(PokerTable table, UUID id, String name, long amount, long selected) {
        boolean sel = amount == selected;
        ItemStack it = button(Material.GOLD_NUGGET, (sel ? "§a▶ " : "") + name + " §8→ §e" + u(amount),
                List.of(sel ? "§aSeleccionado" : "§7Click para elegir este monto"), "set", amount);
        if (sel) {
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.LURE, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                it.setItemMeta(meta);
            }
        }
        return it;
    }

    private ItemStack info(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack button(Material mat, String name, List<String> lore, String action, Long value) {
        ItemStack it = info(mat, name, lore);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(new NamespacedKey(plugin, KEY_ACTION), PersistentDataType.STRING, action);
            if (value != null)
                pdc.set(new NamespacedKey(plugin, KEY_VALUE), PersistentDataType.LONG, value);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static String u(long v) {
        return PokerTable.units(v);
    }
}
