package com.gamblingdex.games.slots;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenManager;
import com.gamblingdex.gui.GuiItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.ConfigurationSection;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SlotsController {

    private final GamblingDexPlugin plugin;
    private final Map<UUID, SlotsState> states = new HashMap<>();

    private static final List<Material> REELS = List.of(
            Material.DIAMOND,
            Material.EMERALD,
            Material.GOLD_INGOT,
            Material.IRON_INGOT,
            Material.AMETHYST_SHARD,
            Material.NETHER_STAR);
    private static final int[] BET_SLOTS = { 36, 37, 38 };

    public SlotsController(GamblingDexPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        SlotsState state = states.computeIfAbsent(player.getUniqueId(), k -> new SlotsState());

        SlotsHolder holder = new SlotsHolder(player.getUniqueId());
        String title = plugin.color(plugin.getConfig().getString("gui.slots.title", "&dTragamonedas"));
        Inventory inv = Bukkit.createInventory(holder, 45, title);
        holder.setInventory(inv);

        render(inv, state, null);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
    }

    public boolean isSlotsInventory(Inventory inv, Player player) {
        if (inv == null || !(inv.getHolder() instanceof SlotsHolder holder))
            return false;
        return holder.getOwner().equals(player.getUniqueId());
    }

    public void handleClick(Player player, Inventory inv, ItemStack clicked) {
        SlotsState state = states.computeIfAbsent(player.getUniqueId(), k -> new SlotsState());
        String action = GuiItem.getAction(plugin, clicked);
        if (action == null)
            return;

        switch (action) {
            case "spin" -> spin(player, inv, state);
        }
    }

    public void handleClose(Player player) {
        SlotsState state = states.computeIfAbsent(player.getUniqueId(), k -> new SlotsState());
        if (state.betTokens.isEmpty())
            return;
        for (Map.Entry<Material, Integer> e : state.betTokens.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int give = Math.min(64, left);
                ItemStack stack = plugin.getTokenManager().createToken(e.getKey(), give);
                var leftover = player.getInventory().addItem(stack);
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                }
                left -= give;
            }
        }
        state.betTokens.clear();
    }

    private void spin(Player player, Inventory inv, SlotsState state) {
        if (state.spinning) {
            player.sendMessage(colorCfg("messages.slots.spin_in_progress", "&cYa estás girando..."));
            return;
        }

        long betUnits = getBetUnits(state);
        if (betUnits <= 0) {
            player.sendMessage(colorCfg("messages.slots.need_bet", "&cDebes ingresar fichas para apostar."));
            return;
        }

        long cooldownMs = Math.max(0L, plugin.getConfig().getLong("games.slots.spin_cooldown_ms", 0L));
        long now = System.currentTimeMillis();
        if (cooldownMs > 0 && (now - state.lastSpinAtMs) < cooldownMs) {
            long left = Math.max(0L, cooldownMs - (now - state.lastSpinAtMs));
            player.sendMessage(colorCfg("messages.slots.cooldown", "&cEspera {ms}ms para girar de nuevo.")
                    .replace("{ms}", String.valueOf(left)));
            return;
        }

        state.spinning = true;
        state.lastSpinAtMs = now;

        // Stats: record wager at spin start (payout recorded at end)
        try {
            plugin.getSlotsStatsManager().recordSpin(player.getUniqueId(), betUnits);
        } catch (Throwable ignored) {
        }

        // Animación lenta
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            Material[] result = new Material[3];

            @Override
            public void run() {
                // If the player closed the inventory or switched GUI, cancel to avoid exploits.
                try {
                    if (!player.isOnline() || player.getOpenInventory() == null
                            || player.getOpenInventory().getTopInventory() != inv) {
                        state.spinning = false;
                        this.cancel();
                        return;
                    }
                } catch (Throwable ignored) {
                    state.spinning = false;
                    this.cancel();
                    return;
                }

                if (ticks < 20) {
                    // Girar reels
                    inv.setItem(11, symbolItem(randomSymbolWeighted()));
                    inv.setItem(13, symbolItem(randomSymbolWeighted()));
                    inv.setItem(15, symbolItem(randomSymbolWeighted()));

                    // Sonido por frame mientras los ítems se mueven
                    // (solo si el jugador sigue con el inventario abierto)
                    try {
                        if (player.isOnline() && player.getOpenInventory() != null
                                && player.getOpenInventory().getTopInventory() == inv) {
                            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.45f, 1.7f);
                        }
                    } catch (Throwable ignored) {
                        // ignore sound errors (version differences)
                    }
                } else if (ticks == 20) {
                    // Resultado final
                    result[0] = randomSymbolWeighted();
                    result[1] = randomSymbolWeighted();
                    result[2] = randomSymbolWeighted();
                    inv.setItem(11, symbolItem(result[0]));
                    inv.setItem(13, symbolItem(result[1]));
                    inv.setItem(15, symbolItem(result[2]));

                    long tripleMult = Math.max(1L, plugin.getConfig().getLong("games.slots.payout.triple_multiplier",
                            5L));
                    long doubleMult = Math.max(1L, plugin.getConfig().getLong("games.slots.payout.double_multiplier",
                            2L));

                    // Calcular premio
                    long payout = 0;
                    if (result[0] == result[1] && result[1] == result[2]) {
                        payout = betUnits * tripleMult;
                    } else if (result[0] == result[1] || result[1] == result[2] || result[0] == result[2]) {
                        payout = betUnits * doubleMult;
                    }
                    if (payout > 0) {
                        plugin.getTokenPayout().pay(player, payout);
                    }
                    String currencyName = plugin.color(plugin.getConfig().getString("currency.name", "⛃"));
                    String msg;
                    if (payout > 0) {
                        msg = colorCfg("messages.slots.win",
                                "&a¡Premio! &7Ganaste fichas equivalentes a &e{amount} &7{currency}")
                                .replace("{amount}", formatUnits(payout))
                                .replace("{currency}", currencyName);
                    } else {
                        msg = colorCfg("messages.slots.lose", "&cNada esta vez...");
                    }
                    player.sendMessage(msg);
                    player.playSound(player.getLocation(),
                            payout > 0 ? org.bukkit.Sound.ENTITY_PLAYER_LEVELUP : org.bukkit.Sound.ENTITY_ITEM_BREAK,
                            0.8f, payout > 0 ? 1.2f : 0.9f);
                    state.betTokens.clear();
                    state.spinning = false;

                    // Stats: record payout at the end
                    try {
                        plugin.getSlotsStatsManager().recordPayout(player.getUniqueId(), payout);
                    } catch (Throwable ignored) {
                    }

                    // Optional: log big wins
                    long big = Math.max(0L, plugin.getConfig().getLong("games.slots.log_big_wins.threshold_units", 0L));
                    if (big > 0 && payout >= big) {
                        plugin.getLogger().info("[Slots] Big win player=" + player.getName() + " bet=" + betUnits
                                + " payout=" + payout);
                    }
                    render(inv, state, result);
                    this.cancel();
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 4L); // 4 ticks = 0.2s entre frames
    }

    private void render(Inventory inv, SlotsState state, Material[] last) {
        inv.clear();

        ItemStack filler = new ItemStack(Material.PURPLE_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }
        for (int i = 0; i < inv.getSize(); i++)
            inv.setItem(i, filler);

        // reels in the middle row
        inv.setItem(11, symbolItem(last != null ? last[0] : Material.BARRIER));
        inv.setItem(13, symbolItem(last != null ? last[1] : Material.BARRIER));
        inv.setItem(15, symbolItem(last != null ? last[2] : Material.BARRIER));

        // bet slots (tokens inserted)
        for (int slot : BET_SLOTS) {
            inv.setItem(slot, placeholder(Material.RED_STAINED_GLASS_PANE));
        }
        int slotIdx = 0;
        for (Map.Entry<Material, Integer> e : state.betTokens.entrySet()) {
            if (slotIdx >= BET_SLOTS.length)
                break;
            Material mat = e.getKey();
            int amount = e.getValue();
            ItemStack token = plugin.getTokenManager().createToken(mat, Math.min(64, amount));
            ItemMeta meta = token.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add(plugin.color("&7Apuesta"));
                lore.add(plugin.color("&fCantidad: &e" + amount));
                lore.add(plugin.color("&fValor unitario: &e" + TokenManager.getDenoms().get(mat) + " ⛃"));
                meta.setLore(lore);
                token.setItemMeta(meta);
            }
            inv.setItem(BET_SLOTS[slotIdx], token);
            slotIdx++;
        }

        // spin
        long tripleMult = Math.max(1L,
                plugin.getConfig().getLong("games.slots.payout.triple_multiplier", 5L));
        long doubleMult = Math.max(1L,
                plugin.getConfig().getLong("games.slots.payout.double_multiplier", 2L));
        String spinName = plugin.getConfig().getString("gui.slots.spin_button_name", "&a&lGirar");
        String spinLoreBet = plugin.getConfig().getString("gui.slots.spin_button_lore_bet", "&7Apuesta: &e{bet}");
        String spinLorePay = plugin.getConfig().getString("gui.slots.spin_button_lore_pay",
                "&7Paga: &a{triple}x&7 (triple), &a{double}x&7 (doble)");
        inv.setItem(40, GuiItem.button(plugin, Material.EMERALD_BLOCK, plugin.color(spinName),
                List.of(plugin.color(spinLoreBet.replace("{bet}", String.valueOf(getBetUnits(state)))),
                        plugin.color(spinLorePay
                                .replace("{triple}", String.valueOf(tripleMult))
                                .replace("{double}", String.valueOf(doubleMult)))),
                "spin", null, true));

        // info
        String payTitle = plugin.getConfig().getString("gui.slots.paytable_name", "&eTabla de pagos");
        String payLine1 = plugin.getConfig().getString("gui.slots.paytable_line_triple", "&7Triple: &a{triple}x");
        String payLine2 = plugin.getConfig().getString("gui.slots.paytable_line_double", "&7Doble: &a{double}x");
        inv.setItem(31, GuiItem.button(plugin, Material.PAPER, plugin.color(payTitle),
                List.of(plugin.color(payLine1.replace("{triple}", String.valueOf(tripleMult))),
                        plugin.color(payLine2.replace("{double}", String.valueOf(doubleMult)))),
                "noop", null, false));
    }

    private ItemStack symbolItem(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color("&f" + material.name()));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material randomSymbolWeighted() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("games.slots.symbol_weights");
        if (sec == null) {
            return REELS.get(ThreadLocalRandom.current().nextInt(REELS.size()));
        }

        List<Material> mats = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (String k : sec.getKeys(false)) {
            Material mat = Material.matchMaterial(k);
            if (mat == null)
                continue;
            int w = Math.max(0, sec.getInt(k, 0));
            if (w <= 0)
                continue;
            mats.add(mat);
            weights.add(w);
            total += w;
        }

        if (total <= 0 || mats.isEmpty()) {
            return REELS.get(ThreadLocalRandom.current().nextInt(REELS.size()));
        }

        int r = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (int i = 0; i < mats.size(); i++) {
            acc += weights.get(i);
            if (r < acc)
                return mats.get(i);
        }
        return mats.get(mats.size() - 1);
    }

    private String colorCfg(String path, String def) {
        if (path != null && path.startsWith("messages.")) {
            return plugin.getMessages().getString(path, def);
        }
        return plugin.color(plugin.getConfig().getString(path, def));
    }

    private static ItemStack placeholder(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String formatUnits(long units) {
        try {
            return NumberFormat.getInstance(new Locale("es", "ES")).format(units);
        } catch (Exception ignored) {
            return String.valueOf(units);
        }
    }

    private long getBetUnits(SlotsState state) {
        long total = 0L;
        for (Map.Entry<Material, Integer> e : state.betTokens.entrySet()) {
            Integer denom = TokenManager.getDenoms().get(e.getKey());
            if (denom != null) {
                total += (long) denom * (long) e.getValue();
            }
        }
        return total;
    }

    public boolean isBetSlot(int slot) {
        for (int s : BET_SLOTS) {
            if (s == slot)
                return true;
        }
        return false;
    }

    public int addBetTokens(Player player, Material mat, int amount) {
        if (mat == null || amount <= 0)
            return 0;
        SlotsState state = states.computeIfAbsent(player.getUniqueId(), k -> new SlotsState());
        // Durante el giro la apuesta está bloqueada.
        if (state.spinning)
            return 0;
        if (!state.betTokens.containsKey(mat) && state.betTokens.size() >= BET_SLOTS.length)
            return 0;
        int current = state.betTokens.getOrDefault(mat, 0);
        int updated = Math.min(9999, current + amount);
        state.betTokens.put(mat, updated);
        // Solo lo que realmente entró (el resto se queda en el inventario).
        return updated - current;
    }

    public int removeBetTokens(Player player, Material mat) {
        if (mat == null)
            return 0;
        SlotsState state = states.computeIfAbsent(player.getUniqueId(), k -> new SlotsState());
        // No se puede retirar la apuesta mientras gira (antes daba giros gratis).
        if (state.spinning)
            return 0;
        Integer removed = state.betTokens.remove(mat);
        return removed == null ? 0 : removed;
    }

    public void refresh(Player player, Inventory inv) {
        SlotsState state = states.computeIfAbsent(player.getUniqueId(), k -> new SlotsState());
        render(inv, state, state.last);
    }

    private static final class SlotsState {
        Material[] last = null;
        private final Map<Material, Integer> betTokens = new LinkedHashMap<>();
        private boolean spinning = false;
        private long lastSpinAtMs = 0L;
    }
}
