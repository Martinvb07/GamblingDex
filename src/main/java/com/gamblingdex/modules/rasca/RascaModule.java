package com.gamblingdex.modules.rasca;

import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.modules.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.security.SecureRandom;
import java.util.*;

/**
 * Rasca y Gana: boletos (ítems) que se compran con fichas. El resultado se
 * sortea al comprar y queda guardado en el ítem; al rascarlo solo se revela.
 * Gana con 3 símbolos iguales.
 */
public class RascaModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int[] CELLS = { 3, 4, 5, 12, 13, 14, 21, 22, 23 };
    private static final Material BLANK = Material.COAL;
    // Relleno extra si hay pocos premios configurados (ninguno da premio).
    private static final Material[] FILLERS = { Material.COAL, Material.FLINT, Material.CLAY_BALL, Material.BONE,
            Material.STICK };

    private NamespacedKey kTier;
    private NamespacedKey kPrice;
    private NamespacedKey kMult;
    private NamespacedKey kWin;
    private NamespacedKey kLayout;
    private NamespacedKey kId;

    private record Prize(Material material, String name, long multiplier, int weight) {
    }

    private static final class Session implements InventoryHolder {
        final UUID player;
        final String tierName;
        final long price;
        final long mult;
        final Material win;
        final Material[] layout;
        final boolean[] revealed = new boolean[9];
        boolean settled;

        Session(UUID player, String tierName, long price, long mult, Material win, Material[] layout) {
            this.player = player;
            this.tierName = tierName;
            this.price = price;
            this.mult = mult;
            this.win = win;
            this.layout = layout;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class ShopHolder implements InventoryHolder {
        final Map<Integer, String> slotToTier = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    @Override
    public String id() {
        return "rasca";
    }

    @Override
    public String displayName() {
        return "Rasca y Gana";
    }

    @Override
    public List<String> aliases() {
        return List.of("rascaygana", "scratch");
    }

    @Override
    public void enable() {
        kTier = new NamespacedKey(plugin, "scratch_tier");
        kPrice = new NamespacedKey(plugin, "scratch_price");
        kMult = new NamespacedKey(plugin, "scratch_mult");
        kWin = new NamespacedKey(plugin, "scratch_win");
        kLayout = new NamespacedKey(plugin, "scratch_layout");
        kId = new NamespacedKey(plugin, "scratch_id");
        listen(new Events());
    }

    @Override
    public void disable() {
        for (Session s : new ArrayList<>(sessions.values())) {
            settle(s);
            Player p = Bukkit.getPlayer(s.player);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() == s)
                p.closeInventory();
        }
        sessions.clear();
    }

    @Override
    public List<String> helpLines(boolean admin) {
        return List.of(
                "&6&lRasca y Gana",
                "&8• &e/gdx rasca &7- Comprar boletos",
                "&8• &7Rascar: &fclick derecho&7 con el boleto en la mano",
                "");
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (args.length == 0) {
            openShop(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("comprar") || args[0].equalsIgnoreCase("buy")) {
            if (args.length < 2) {
                player.sendMessage(msg("usage", "&cUso: /gdx rasca [comprar <tipo> [cantidad]]"));
                return true;
            }
            int qty = 1;
            if (args.length >= 3) {
                try {
                    qty = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            buy(player, args[1].toLowerCase(Locale.ROOT), qty);
            return true;
        }
        player.sendMessage(msg("usage", "&cUso: /gdx rasca [comprar <tipo> [cantidad]]"));
        return true;
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    private List<String> tierIds() {
        ConfigurationSection sec = config().getConfigurationSection("tiers");
        return sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
    }

    private long tierPrice(String tier) {
        return Math.max(1L, config().getLong("tiers." + tier + ".price", 0L));
    }

    private String tierName(String tier) {
        return config().getString("tiers." + tier + ".name", tier);
    }

    private List<Prize> prizes() {
        List<Prize> out = new ArrayList<>();
        for (Map<?, ?> m : config().getMapList("prizes")) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
            if (mat == null || mat == BLANK)
                continue;
            Object name = m.get("name");
            long mult = m.get("multiplier") instanceof Number n ? n.longValue() : 0L;
            int weight = m.get("weight") instanceof Number n ? n.intValue() : 0;
            if (mult <= 0 || weight < 0)
                continue;
            out.add(new Prize(mat, name == null ? mat.name() : String.valueOf(name), mult, weight));
        }
        return out;
    }

    private String symbolName(Material m) {
        for (Prize p : prizes()) {
            if (p.material() == m)
                return p.name();
        }
        return "&8Nada";
    }

    // ------------------------------------------------------------------
    // Compra
    // ------------------------------------------------------------------

    private void buy(Player player, String tier, int qty) {
        if (!tierIds().contains(tier)) {
            player.sendMessage(msg("unknown_tier", "&cEse boleto no existe. Tipos: &f{tiers}",
                    "tiers", String.join(", ", tierIds())));
            return;
        }
        qty = Math.max(1, Math.min(Math.max(1, config().getInt("max_buy_at_once", 64)), qty));
        long price = tierPrice(tier);
        long total = price * qty;
        if (!TokenWallet.take(player, total)) {
            player.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Necesitas &e{price}&c y tienes &e{balance}&c.",
                    "price", units(total), "balance", units(TokenWallet.balance(player))));
            return;
        }
        for (int i = 0; i < qty; i++) {
            ItemStack ticket = createTicket(tier, price);
            for (ItemStack left : player.getInventory().addItem(ticket).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        player.sendMessage(msg("bought", "&aCompraste &e{amount} &aboleto(s) &f{tier}&a por &e{price}&a.",
                "amount", String.valueOf(qty), "tier", color(tierName(tier)), "price", units(total)));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
    }

    /** Sortea el resultado y lo guarda en el boleto. */
    private ItemStack createTicket(String tier, long price) {
        List<Prize> prizes = prizes();
        int roll = RNG.nextInt(1000);
        Prize won = null;
        int acc = 0;
        for (Prize p : prizes) {
            acc += p.weight();
            if (roll < acc) {
                won = p;
                break;
            }
        }

        // Símbolos disponibles (premios + carbón como relleno).
        List<Material> symbols = new ArrayList<>();
        for (Prize p : prizes)
            symbols.add(p.material());
        // Cada símbolo puede salir hasta 2 veces sin premio: asegurar que alcanzan.
        for (Material f : FILLERS) {
            if (symbols.size() * 2 >= 9 + 2)
                break;
            if (!symbols.contains(f))
                symbols.add(f);
        }
        if (!symbols.contains(BLANK))
            symbols.add(BLANK);

        List<Material> cells = new ArrayList<>();
        Map<Material, Integer> used = new HashMap<>();
        if (won != null) {
            for (int i = 0; i < 3; i++)
                cells.add(won.material());
            used.put(won.material(), 3);
        }
        // Resto: ningún otro símbolo más de 2 veces (si no, sería otro premio).
        while (cells.size() < 9) {
            Material m = symbols.get(RNG.nextInt(symbols.size()));
            if (won != null && m == won.material())
                continue;
            int c = used.getOrDefault(m, 0);
            if (c >= 2)
                continue;
            used.put(m, c + 1);
            cells.add(m);
        }
        Collections.shuffle(cells, RNG);

        StringBuilder layout = new StringBuilder();
        for (Material m : cells) {
            if (layout.length() > 0)
                layout.append(',');
            layout.append(m.name());
        }

        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(color(tierName(tier)));
        meta.setLore(List.of(
                color("&7Valor: &e" + units(price) + " &7fichas"),
                color("&7¡3 símbolos iguales ganan!"),
                "",
                color("&aClick derecho para rascar")));
        meta.addEnchant(Enchantment.LURE, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(kTier, PersistentDataType.STRING, tier);
        pdc.set(kPrice, PersistentDataType.LONG, price);
        pdc.set(kMult, PersistentDataType.LONG, won == null ? 0L : won.multiplier());
        pdc.set(kWin, PersistentDataType.STRING, won == null ? "" : won.material().name());
        pdc.set(kLayout, PersistentDataType.STRING, layout.toString());
        pdc.set(kId, PersistentDataType.LONG, RNG.nextLong()); // no se apilan
        it.setItemMeta(meta);
        return it;
    }

    private boolean isTicket(ItemStack it) {
        return it != null && it.getType() == Material.PAPER && it.getItemMeta() != null
                && it.getItemMeta().getPersistentDataContainer().has(kLayout, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------
    // Rascar
    // ------------------------------------------------------------------

    private void startScratch(Player player, EquipmentSlot hand, ItemStack ticket) {
        if (sessions.containsKey(player.getUniqueId()))
            return;
        PersistentDataContainer pdc = ticket.getItemMeta().getPersistentDataContainer();
        String[] parts = pdc.getOrDefault(kLayout, PersistentDataType.STRING, "").split(",");
        if (parts.length != 9)
            return;
        Material[] layout = new Material[9];
        for (int i = 0; i < 9; i++) {
            Material m = Material.matchMaterial(parts[i]);
            layout[i] = m == null ? BLANK : m;
        }
        String winName = pdc.getOrDefault(kWin, PersistentDataType.STRING, "");
        Material win = winName.isEmpty() ? null : Material.matchMaterial(winName);
        String tier = pdc.getOrDefault(kTier, PersistentDataType.STRING, "?");
        Session s = new Session(player.getUniqueId(), tierName(tier),
                pdc.getOrDefault(kPrice, PersistentDataType.LONG, 0L),
                pdc.getOrDefault(kMult, PersistentDataType.LONG, 0L), win, layout);

        // Se consume el boleto al abrirlo; si cierras, se rasca solo y se paga.
        if (ticket.getAmount() > 1) {
            ticket.setAmount(ticket.getAmount() - 1);
        } else if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        sessions.put(player.getUniqueId(), s);
        render(player, s);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
    }

    private void render(Player player, Session s) {
        Inventory inv = player.getOpenInventory().getTopInventory().getHolder() == s
                ? player.getOpenInventory().getTopInventory()
                : Bukkit.createInventory(s, 27, color(s.tierName));
        for (int i = 0; i < 27; i++)
            inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        for (int k = 0; k < 9; k++) {
            if (s.revealed[k]) {
                inv.setItem(CELLS[k], item(s.layout[k], symbolName(s.layout[k]), null));
            } else {
                inv.setItem(CELLS[k], item(Material.LIGHT_GRAY_CONCRETE, "&7Rasca aquí", List.of("&8Click para rascar")));
            }
        }
        inv.setItem(9, item(Material.BOOK, "&e&lPremios", prizeLore(s.price)));
        inv.setItem(17, item(Material.BRUSH, "&a&lRascar todo", List.of("&7Revela todas las casillas")));
        if (player.getOpenInventory().getTopInventory() != inv)
            player.openInventory(inv);
    }

    private List<String> prizeLore(long price) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Boleto de &e" + units(price));
        lore.add("&73 iguales ganan:");
        for (Prize p : prizes()) {
            lore.add("&8• " + p.name() + " &7x" + p.multiplier() + " &8→ &e" + units(price * p.multiplier()));
        }
        return lore;
    }

    private void reveal(Player player, Session s, int cell) {
        if (s.settled || s.revealed[cell])
            return;
        s.revealed[cell] = true;
        player.playSound(player.getLocation(), Sound.BLOCK_SAND_BREAK, 0.8f, 1.3f);
        boolean all = true;
        for (boolean r : s.revealed)
            all &= r;
        render(player, s);
        if (all)
            settle(s);
    }

    private void revealAll(Player player, Session s) {
        Arrays.fill(s.revealed, true);
        render(player, s);
        settle(s);
    }

    private void settle(Session s) {
        if (s.settled)
            return;
        s.settled = true;
        Arrays.fill(s.revealed, true);
        sessions.remove(s.player);

        Player p = Bukkit.getPlayer(s.player);
        long prize = s.win == null ? 0L : s.price * s.mult;
        if (prize > 0) {
            TokenWallet.give(s.player, prize);
            if (p != null) {
                p.sendMessage(msg("win", "&a&l¡PREMIO! &f3x {symbol} &7→ &e+{amount} &7fichas",
                        "symbol", color(symbolName(s.win)), "amount", units(prize)));
                p.sendTitle(color("&a&l¡PREMIO!"), color("&e+" + units(prize)), 5, 50, 15);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
            }
            if (s.mult >= config().getLong("broadcast_min_multiplier", 10L)) {
                String b = msg("big_win_broadcast", "&6&lRasca y Gana &8» &f{player} &7ganó &e{amount}&7!",
                        "player", p == null ? "?" : p.getName(), "amount", units(prize),
                        "symbol", color(symbolName(s.win)));
                for (Player o : Bukkit.getOnlinePlayers())
                    o.sendMessage(b);
            }
        } else if (p != null) {
            p.sendMessage(msg("lose", "&7Sin premio esta vez. ¡Suerte para la próxima!"));
            p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.0f);
        }
    }

    // ------------------------------------------------------------------
    // Tienda
    // ------------------------------------------------------------------

    private void openShop(Player player) {
        ShopHolder h = new ShopHolder();
        Inventory inv = Bukkit.createInventory(h, 27, color("&6&lRasca y Gana &8- &eTienda"));
        for (int i = 0; i < 27; i++)
            inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        List<String> tiers = tierIds();
        int[] slots = tiers.size() <= 3 ? new int[] { 11, 13, 15 } : new int[] { 10, 11, 12, 13, 14, 15, 16 };
        for (int i = 0; i < tiers.size() && i < slots.length; i++) {
            String t = tiers.get(i);
            long price = tierPrice(t);
            List<String> lore = new ArrayList<>(prizeLore(price));
            lore.add("");
            lore.add("&aClick: &fcomprar 1 &8| &aShift+Click: &fcomprar 10");
            inv.setItem(slots[i], item(Material.PAPER, tierName(t) + " &8(&e" + units(price) + "&8)", lore));
            h.slotToTier.put(slots[i], t);
        }
        inv.setItem(22, item(Material.SUNFLOWER, "&7Tus fichas: &e" + units(TokenWallet.balance(player)), null));
        player.openInventory(inv);
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
        public void onInteract(PlayerInteractEvent e) {
            Action a = e.getAction();
            if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK)
                return;
            ItemStack it = e.getItem();
            if (!isTicket(it))
                return;
            e.setCancelled(true);
            startScratch(e.getPlayer(), e.getHand() == null ? EquipmentSlot.HAND : e.getHand(), it);
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            Object holder = e.getInventory().getHolder();
            if (!(holder instanceof Session) && !(holder instanceof ShopHolder))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            int slot = e.getRawSlot();
            if (holder instanceof ShopHolder h) {
                String tier = h.slotToTier.get(slot);
                if (tier != null) {
                    buy(p, tier, e.isShiftClick() ? 10 : 1);
                    openShop(p);
                }
                return;
            }
            Session s = (Session) holder;
            if (slot == 17) {
                revealAll(p, s);
                return;
            }
            for (int k = 0; k < 9; k++) {
                if (CELLS[k] == slot) {
                    reveal(p, s, k);
                    return;
                }
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            Object holder = e.getInventory().getHolder();
            if (holder instanceof Session || holder instanceof ShopHolder)
                e.setCancelled(true);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (e.getInventory().getHolder() instanceof Session s && !s.settled) {
                // Cerró sin terminar: se rasca solo y se paga.
                settle(s);
            }
        }
    }
}
