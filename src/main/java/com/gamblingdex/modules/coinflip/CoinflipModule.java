package com.gamblingdex.modules.coinflip;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.gui.AmountPickerMenu;
import com.gamblingdex.modules.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.util.*;

/**
 * Coinflip 1 vs 1: un jugador abre una apuesta (sus fichas quedan en garantía),
 * otro la acepta con el mismo monto y una moneda decide. El resultado se
 * sortea al aceptar; la animación es solo visual.
 */
public class CoinflipModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();

    private record Flip(int id, UUID creator, String creatorName, long amount, boolean heads, long createdAt) {
    }

    private static final class Game {
        final Flip flip;
        final UUID taker;
        final String takerName;
        final boolean resultHeads;
        BukkitTask task;
        boolean paid;

        Game(Flip flip, UUID taker, String takerName, boolean resultHeads) {
            this.flip = flip;
            this.taker = taker;
            this.takerName = takerName;
            this.resultHeads = resultHeads;
        }
    }

    private final Map<Integer, Flip> open = new LinkedHashMap<>();
    private final List<Game> running = new ArrayList<>();
    private int nextId = 1;

    @Override
    public String id() {
        return "coinflip";
    }

    @Override
    public String displayName() {
        return "Coinflip";
    }

    @Override
    public List<String> aliases() {
        return List.of("cf", "moneda");
    }

    @Override
    public void enable() {
        listen(new Events());
        runTimer(this::expireOld, 20L * 20, 20L * 20);
    }

    @Override
    public void disable() {
        // Apuestas abiertas: devolver. Partidas girando: pagar ya el resultado.
        for (Flip f : new ArrayList<>(open.values())) {
            TokenWallet.give(f.creator(), f.amount());
        }
        open.clear();
        for (Game g : new ArrayList<>(running)) {
            if (g.task != null)
                g.task.cancel();
            settle(g, false);
        }
        running.clear();
    }

    @Override
    public List<String> helpLines(boolean admin) {
        return List.of(
                "&6&lCoinflip (cara o sello 1 vs 1)",
                "&8• &e/gdx coinflip &7- Ver y aceptar apuestas abiertas",
                "&8• &e/gdx coinflip crear <monto> [cara|sello] &7- Abrir una apuesta",
                "&8• &e/gdx coinflip cancelar &7- Retirar tu apuesta",
                "");
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("lista") || args[0].equalsIgnoreCase("list")) {
            openLobby(player);
            return true;
        }
        String a = args[0].toLowerCase(Locale.ROOT);
        switch (a) {
            case "crear", "create", "c" -> {
                if (args.length < 2) {
                    startCreate(player);
                    return true;
                }
                long amount = parseAmount(args[1]);
                if (amount <= 0) {
                    player.sendMessage(msg("usage", "&cUso: /gdx coinflip [crear <monto> [cara|sello] | cancelar]"));
                    return true;
                }
                if (args.length >= 3) {
                    String side = args[2].toLowerCase(Locale.ROOT);
                    create(player, amount, !(side.startsWith("s") || side.startsWith("t")));
                } else {
                    openSideMenu(player, amount);
                }
            }
            case "cancelar", "cancel" -> cancelOwn(player);
            default -> player.sendMessage(msg("usage", "&cUso: /gdx coinflip [crear <monto> [cara|sello] | cancelar]"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Lógica
    // ------------------------------------------------------------------

    private long minBet() {
        return Math.max(1L, config().getLong("min_bet", 10L));
    }

    private long maxBet() {
        return Math.max(0L, config().getLong("max_bet", 0L));
    }

    private String sideName(boolean heads) {
        return heads ? "Cara" : "Sello";
    }

    private int openCount(UUID id) {
        int c = 0;
        for (Flip f : open.values())
            if (f.creator().equals(id))
                c++;
        return c;
    }

    private void startCreate(Player player) {
        AmountPickerMenu.open(player, "&6&lCoinflip &8- &eMonto", minBet(), maxBet(), minBet(),
                List.of("&7Elige cuánto apostar.", "&7Luego eliges cara o sello."),
                amount -> openSideMenu(player, amount), () -> openLobby(player));
    }

    private boolean create(Player player, long amount, boolean heads) {
        if (amount < minBet()) {
            player.sendMessage(msg("min_bet", "&cLa apuesta mínima es &e{min}&c.", "min", units(minBet())));
            return false;
        }
        if (maxBet() > 0 && amount > maxBet()) {
            player.sendMessage(msg("max_bet", "&cLa apuesta máxima es &e{max}&c.", "max", units(maxBet())));
            return false;
        }
        int maxOpen = Math.max(1, config().getInt("max_open_per_player", 1));
        if (openCount(player.getUniqueId()) >= maxOpen) {
            player.sendMessage(msg("too_many", "&cYa tienes una apuesta abierta."));
            return false;
        }
        if (!TokenWallet.take(player, amount)) {
            player.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", units(TokenWallet.balance(player))));
            return false;
        }

        Flip f = new Flip(nextId++, player.getUniqueId(), player.getName(), amount, heads, System.currentTimeMillis());
        open.put(f.id(), f);
        player.sendMessage(msg("created", "&aCreaste una apuesta de &e{amount}&a por &f{side}&a.",
                "amount", units(amount), "side", sideName(heads)));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.4f);

        if (config().getBoolean("broadcast.enabled", true) && amount >= config().getLong("broadcast.min_amount", 0L)) {
            String b = msg("broadcast", "&6&lCoinflip &8» &f{player} &7apuesta &e{amount}&7 a &f{side}&7.",
                    "player", player.getName(), "amount", units(amount), "side", sideName(heads));
            for (Player p : Bukkit.getOnlinePlayers())
                if (!p.equals(player))
                    p.sendMessage(b);
        }
        return true;
    }

    private void cancelOwn(Player player) {
        Flip mine = null;
        for (Flip f : open.values()) {
            if (f.creator().equals(player.getUniqueId())) {
                mine = f;
                break;
            }
        }
        if (mine == null) {
            player.sendMessage(msg("no_open", "&cNo tienes ninguna apuesta abierta."));
            return;
        }
        open.remove(mine.id());
        TokenWallet.give(mine.creator(), mine.amount());
        player.sendMessage(msg("cancelled", "&7Retiraste tu apuesta. Se te devolvieron &e{amount}&7.",
                "amount", units(mine.amount())));
    }

    private void expireOld() {
        long maxAge = Math.max(1, config().getLong("expire_minutes", 10L)) * 60_000L;
        long now = System.currentTimeMillis();
        for (Flip f : new ArrayList<>(open.values())) {
            if (now - f.createdAt() < maxAge)
                continue;
            open.remove(f.id());
            TokenWallet.give(f.creator(), f.amount());
            Player p = Bukkit.getPlayer(f.creator());
            if (p != null)
                p.sendMessage(msg("expired", "&7Nadie aceptó tu coinflip. Se te devolvieron &e{amount}&7.",
                        "amount", units(f.amount())));
        }
    }

    private void accept(Player taker, int flipId) {
        Flip f = open.get(flipId);
        if (f == null) {
            taker.sendMessage(msg("gone", "&cEsa apuesta ya no está disponible."));
            openLobby(taker);
            return;
        }
        if (f.creator().equals(taker.getUniqueId())) {
            taker.sendMessage(msg("own_flip", "&cNo puedes aceptar tu propia apuesta."));
            return;
        }
        if (!TokenWallet.take(taker, f.amount())) {
            taker.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", units(TokenWallet.balance(taker))));
            return;
        }
        open.remove(flipId);

        Game g = new Game(f, taker.getUniqueId(), taker.getName(), RNG.nextBoolean());
        running.add(g);

        Player creator = Bukkit.getPlayer(f.creator());
        if (creator != null) {
            creator.sendMessage(msg("accepted", "&e{player} &aaceptó tu coinflip de &e{amount}&a. ¡Gira la moneda!",
                    "player", taker.getName(), "amount", units(f.amount())));
        }
        animate(g);
    }

    private void animate(Game g) {
        int total = Math.max(10, config().getInt("animation_ticks", 50));
        final int[] tick = { 0 };
        final int[] nextFlipAt = { 0 };
        final boolean[] showHeads = { true };

        g.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (tick[0] >= total) {
                g.task.cancel();
                settle(g, true);
                return;
            }
            if (tick[0] >= nextFlipAt[0]) {
                // La moneda gira cada vez más lento.
                int gap = 1 + (tick[0] * 6) / total;
                nextFlipAt[0] = tick[0] + gap;
                showHeads[0] = !showHeads[0];
                // El último giro cae en el resultado.
                if (nextFlipAt[0] >= total)
                    showHeads[0] = g.resultHeads;
                for (UUID id : List.of(g.flip.creator(), g.taker)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null)
                        continue;
                    showCoin(p, g, showHeads[0], false, tick[0] == 0);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
                }
            }
            tick[0]++;
        }, 0L, 1L);
    }

    /** Paga al ganador. {@code show}: mostrar el resultado en pantalla. */
    private void settle(Game g, boolean show) {
        if (g.paid)
            return;
        g.paid = true;
        running.remove(g);

        boolean creatorWins = g.resultHeads == g.flip.heads();
        UUID winner = creatorWins ? g.flip.creator() : g.taker;
        UUID loser = creatorWins ? g.taker : g.flip.creator();
        String winnerName = creatorWins ? g.flip.creatorName() : g.takerName;
        String loserName = creatorWins ? g.takerName : g.flip.creatorName();

        long pot = g.flip.amount() * 2;
        double pct = Math.max(0.0, Math.min(50.0, config().getDouble("fee_percent", 5.0)));
        long fee = (long) Math.floor(pot * pct / 100.0);
        long prize = pot - fee;
        TokenWallet.give(winner, prize);
        GamblingDexPlugin.recordStats(winner, "coinflip", g.flip.amount(), prize);
        GamblingDexPlugin.recordStats(loser, "coinflip", g.flip.amount(), 0L);

        String side = sideName(g.resultHeads);
        Player wp = Bukkit.getPlayer(winner);
        Player lp = Bukkit.getPlayer(loser);
        if (wp != null) {
            wp.sendMessage(msg("win", "&a&l¡Ganaste! &7Salió &f{side}&7. Cobras &e{amount}&7.",
                    "side", side, "amount", units(prize)));
            wp.sendTitle(color("&a&l¡GANASTE!"), color("&e+" + units(prize - g.flip.amount())), 5, 50, 15);
            wp.playSound(wp.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            if (show)
                showCoin(wp, g, g.resultHeads, true, false);
        }
        if (lp != null) {
            lp.sendMessage(msg("lose", "&c&lPerdiste. &7Salió &f{side}&7. Ganó &f{winner}&7.",
                    "side", side, "winner", winnerName));
            lp.sendTitle(color("&c&lPerdiste"), color("&7Salió &f" + side), 5, 50, 15);
            lp.playSound(lp.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            if (show)
                showCoin(lp, g, g.resultHeads, true, false);
        }

        if (config().getBoolean("broadcast.enabled", true)
                && g.flip.amount() >= config().getLong("broadcast.min_amount", 0L)) {
            String b = msg("result_broadcast", "&6&lCoinflip &8» &f{winner} &7le ganó &e{amount}&7 a &f{loser}&7.",
                    "winner", winnerName, "loser", loserName, "amount", units(prize));
            for (Player p : Bukkit.getOnlinePlayers())
                if (!p.getUniqueId().equals(winner) && !p.getUniqueId().equals(loser))
                    p.sendMessage(b);
        }
        plugin.getLogger().info("[Coinflip] " + winnerName + " le ganó " + prize + " a " + loserName
                + " (apuesta " + g.flip.amount() + ", comisión " + fee + ")");
    }

    // ------------------------------------------------------------------
    // Menús
    // ------------------------------------------------------------------

    private static final class LobbyHolder implements InventoryHolder {
        final Map<Integer, Integer> slotToFlip = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class SideHolder implements InventoryHolder {
        final long amount;

        SideHolder(long amount) {
            this.amount = amount;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class CoinHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private void openLobby(Player player) {
        LobbyHolder h = new LobbyHolder();
        Inventory inv = Bukkit.createInventory(h, 54, color("&6&lCoinflip &8- &eApuestas abiertas"));
        for (int i = 45; i < 54; i++)
            inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, " ", null));

        int slot = 0;
        for (Flip f : open.values()) {
            if (slot >= 45)
                break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta sm) {
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(f.creator()));
                sm.setDisplayName(color("&f" + f.creatorName()));
                boolean mine = f.creator().equals(player.getUniqueId());
                sm.setLore(List.of(
                        color("&7Apuesta: &e" + units(f.amount())),
                        color("&7Eligió: &f" + sideName(f.heads()) + " &8(tú serías " + sideName(!f.heads()) + ")"),
                        "",
                        color(mine ? "&8Es tu apuesta" : "&aClick para aceptar")));
                head.setItemMeta(sm);
            }
            inv.setItem(slot, head);
            h.slotToFlip.put(slot, f.id());
            slot++;
        }
        if (open.isEmpty()) {
            inv.setItem(22, item(Material.PAPER, "&7No hay apuestas abiertas",
                    List.of("&7¡Crea la primera!")));
        }

        inv.setItem(48, item(Material.REDSTONE, "&cCancelar mi apuesta", List.of("&7Te devuelve las fichas")));
        inv.setItem(49, item(Material.GOLD_BLOCK, "&a&lCrear apuesta",
                List.of("&7Elige monto y cara o sello", "&7Tus fichas: &e" + units(TokenWallet.balance(player)))));
        inv.setItem(50, item(Material.CLOCK, "&eActualizar", null));
        player.openInventory(inv);
    }

    private void openSideMenu(Player player, long amount) {
        Inventory inv = Bukkit.createInventory(new SideHolder(amount), 27,
                color("&6&lCoinflip &8- &e" + units(amount)));
        for (int i = 0; i < 27; i++)
            inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        inv.setItem(11, item(Material.GOLD_BLOCK, "&6&lCara", List.of("&7Apuestas &e" + units(amount) + " &7a Cara")));
        inv.setItem(15, item(Material.IRON_BLOCK, "&f&lSello", List.of("&7Apuestas &e" + units(amount) + " &7a Sello")));
        inv.setItem(22, item(Material.BARRIER, "&cCancelar", null));
        player.openInventory(inv);
    }

    /** Actualiza la moneda; solo abre el menú si {@code open} (al empezar el giro). */
    private void showCoin(Player p, Game g, boolean heads, boolean finalResult, boolean open) {
        Inventory top = p.getOpenInventory().getTopInventory();
        Inventory inv;
        if (top.getHolder() instanceof CoinHolder) {
            inv = top;
        } else if (!open) {
            return;
        } else {
            inv = Bukkit.createInventory(new CoinHolder(), 27, color("&6&lCoinflip &8- &e" + units(g.flip.amount() * 2)));
            for (int i = 0; i < 27; i++)
                inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, " ", null));
            inv.setItem(10, item(g.flip.heads() ? Material.GOLD_INGOT : Material.IRON_INGOT,
                    "&f" + g.flip.creatorName(), List.of("&7" + sideName(g.flip.heads()))));
            inv.setItem(16, item(g.flip.heads() ? Material.IRON_INGOT : Material.GOLD_INGOT,
                    "&f" + g.takerName, List.of("&7" + sideName(!g.flip.heads()))));
            p.openInventory(inv);
        }
        String who = heads == g.flip.heads() ? g.flip.creatorName() : g.takerName;
        inv.setItem(13, item(heads ? Material.GOLD_BLOCK : Material.IRON_BLOCK,
                (finalResult ? "&a&l¡" : "&e") + sideName(heads) + (finalResult ? "!" : ""),
                List.of(finalResult ? "&7Gana &f" + who : "&7Girando...")));
        Material pane = finalResult ? Material.LIME_STAINED_GLASS_PANE
                : (heads ? Material.YELLOW_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE);
        for (int s : new int[] { 3, 4, 5, 12, 14, 21, 22, 23 })
            inv.setItem(s, item(pane, " ", null));
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null) {
                List<String> l = new ArrayList<>();
                for (String s : lore)
                    l.add(color(s));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    private final class Events implements Listener {

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            Object holder = e.getInventory().getHolder();
            if (!(holder instanceof LobbyHolder) && !(holder instanceof SideHolder) && !(holder instanceof CoinHolder))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            int slot = e.getRawSlot();
            if (slot < 0 || slot >= e.getInventory().getSize())
                return;

            if (holder instanceof LobbyHolder h) {
                Integer flipId = h.slotToFlip.get(slot);
                if (flipId != null) {
                    accept(p, flipId);
                } else if (slot == 49) {
                    startCreate(p);
                } else if (slot == 48) {
                    cancelOwn(p);
                    openLobby(p);
                } else if (slot == 50) {
                    openLobby(p);
                }
            } else if (holder instanceof SideHolder h) {
                if (slot == 11 || slot == 15) {
                    p.closeInventory();
                    if (create(p, h.amount, slot == 11))
                        openLobby(p);
                } else if (slot == 22) {
                    openLobby(p);
                }
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            Object holder = e.getInventory().getHolder();
            if (holder instanceof LobbyHolder || holder instanceof SideHolder || holder instanceof CoinHolder)
                e.setCancelled(true);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            for (Flip f : new ArrayList<>(open.values())) {
                if (f.creator().equals(id)) {
                    open.remove(f.id());
                    TokenWallet.give(id, f.amount());
                }
            }
        }
    }
}
