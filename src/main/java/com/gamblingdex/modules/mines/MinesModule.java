package com.gamblingdex.modules.mines;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.games.blackjack.BlackjackTables;
import com.gamblingdex.gui.AmountPickerMenu;
import com.gamblingdex.modules.GameModule;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.security.SecureRandom;
import java.util.*;

/**
 * Mines: una pared de 5x5 bloques construida en el mundo. El jugador elige
 * cuántas minas y su apuesta en la mesa, y va rompiendo casillas con click
 * derecho: cada casilla segura (esmeralda) sube el multiplicador; si sale una
 * mina (TNT) pierde. Shift + click derecho = retirarse y cobrar.
 *
 * Multiplicador tras k casillas seguras con m minas (25 casillas):
 * (1 - edge) * prod_{i<k} (25 - i) / (25 - m - i)  → devolución esperada (1 - edge).
 */
public class MinesModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int SIZE = 5;
    private static final int TILES = SIZE * SIZE;
    private static final int[] MINE_OPTIONS = { 1, 3, 5, 8, 12, 16, 20, 24 };

    private static final class Board {
        final String name;
        final Location control; // bloque mesa
        final int rx, rz, fx, fz;
        final Map<String, String> built = new LinkedHashMap<>();
        final List<Location> tiles = new ArrayList<>(); // índice = fila*5 + columna
        UUID holo;

        // Partida en curso
        UUID player;
        String playerName;
        long bet;
        int mines;
        boolean[] mine;
        boolean[] open;
        int safeOpened;
        long lastAction;
        boolean ending; // mostrando el resultado

        Board(String name, Location control, int rx, int rz, int fx, int fz) {
            this.name = name;
            this.control = control;
            this.rx = rx;
            this.rz = rz;
            this.fx = fx;
            this.fz = fz;
        }

        boolean playing() {
            return player != null && !ending;
        }
    }

    private static final class SetupHolder implements InventoryHolder {
        final String board;

        SetupHolder(String board) {
            this.board = board;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Map<String, Board> boards = new LinkedHashMap<>();
    private int tickCount;
    private MinesMenu menu;
    private com.gamblingdex.modules.SimpleStations menuStations;

    @Override
    public String id() {
        return "mines";
    }

    @Override
    public String displayName() {
        return "Mines";
    }

    @Override
    public List<String> aliases() {
        return List.of("minas", "buscaminas");
    }

    @Override
    public void enable() {
        YamlConfiguration d = loadData();
        ConfigurationSection sec = d.getConfigurationSection("boards");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                Location l = BlackjackTables.parseKey(sec.getString(name + ".control"));
                if (l == null)
                    continue;
                Board b = new Board(name, l, sec.getInt(name + ".rx"), sec.getInt(name + ".rz"),
                        sec.getInt(name + ".fx"), sec.getInt(name + ".fz"));
                for (String s : sec.getStringList(name + ".built")) {
                    int bar = s.indexOf('|');
                    if (bar > 0)
                        b.built.put(s.substring(0, bar), s.substring(bar + 1));
                }
                layout(b);
                boards.put(name.toLowerCase(Locale.ROOT), b);
            }
        }
        menu = new MinesMenu(this);
        listen(menu);
        menuStations = new com.gamblingdex.modules.SimpleStations(this,
                () -> config().getString("station_holo", "&c&lMINES\n&7Click para jugar"));
        menuStations.load();
        listen(new Events());
        runTimer(this::tick, 20L, 10L);
    }

    @Override
    public void disable() {
        if (menu != null)
            menu.closeAll();
        if (menuStations != null)
            menuStations.removeHolos();
        for (Board b : boards.values()) {
            if (b.playing())
                cashOut(b, true); // al apagar: se cobra lo que lleve (o se devuelve si no abrió nada)
            removeHolo(b);
        }
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>();
        l.add("&6&lMines");
        l.add("&8• &fClick derecho&7 a la estación: abre el tablero (menú)");
        if (admin) {
            l.add("&8• &e/gdx station set mines &7- Estación con menú (mirando un bloque)");
            l.add("&8• &e/gdx station set mines wall &7- Construir la pared 5x5 en el mundo");
        }
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        player.sendMessage(msg("use_station", "&7Mines se juega en las estaciones: &fclick derecho&7 para abrir el tablero."));
        if (isAdmin(player))
            player.sendMessage(msg("admin_hint", "&7Admin: &f/gdx station set mines [wall] &7(mirando un bloque) | &f/gdx station remove"));
        return true;
    }

    // ------------------------------------------------------------------
    // Construcción (/gdx station set mines)
    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("mines", "minas", "buscaminas");
    }

    @Override
    public String stationUsage() {
        return "mines [wall]";
    }

    @Override
    public List<String> stationTabComplete(Player player, String[] args) {
        return args.length == 1 ? List.of("wall") : List.of();
    }

    @Override
    public List<String> stationListLines() {
        return List.of("&8- &6Mines&7: &f" + menuStations.size() + " &7(menú) &8+ &f" + boards.size() + " &7(pared)");
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        if (menuStations.remove(target)) {
            player.sendMessage(msg("menu_station_removed", "&aEstación de Mines eliminada."));
            return true;
        }
        Board b = byBlock(target);
        if (b == null)
            return false;
        if (b.playing())
            cashOut(b, true);
        removeHolo(b);
        restore(b);
        boards.remove(b.name.toLowerCase(Locale.ROOT));
        save();
        player.sendMessage(msg("station_removed", "&aTablero de Mines eliminado y bloques restaurados."));
        return true;
    }

    /**
     * Mesa = el bloque mirado. Detrás, a 1 bloque, se levanta la pared: 5x5
     * casillas (desde el nivel de la mesa hacia arriba) con un marco alrededor.
     */
    @Override
    public void createStation(Player p, Block target, String[] args) {
        if (args.length == 0 || !(args[0].equalsIgnoreCase("wall") || args[0].equalsIgnoreCase("pared"))) {
            if (byBlock(target) != null || !menuStations.add(target)) {
                p.sendMessage(msg("exists", "&cAhí ya hay una estación de Mines."));
                return;
            }
            p.sendMessage(msg("menu_station_created", "&aEstación de Mines creada. &7Click derecho al bloque para jugar."));
            return;
        }
        if (byBlock(target) != null || menuStations.contains(target)) {
            p.sendMessage(msg("exists", "&cAhí ya hay un tablero de Mines."));
            return;
        }
        BlockFace f = p.getFacing();
        String name;
        int n = 1;
        while (boards.containsKey("mines" + n))
            n++;
        name = "mines" + n;
        Board b = new Board(name, target.getLocation(), -f.getModZ(), f.getModX(), f.getModX(), f.getModZ());
        layout(b);

        Material tileMat = material("tile_material", Material.SMOOTH_QUARTZ);
        Material frameMat = material("frame_material", Material.POLISHED_BLACKSTONE_BRICKS);
        Map<Block, Material> plan = new LinkedHashMap<>();
        for (Location l : b.tiles)
            plan.put(l.getBlock(), tileMat);
        for (int o = -3; o <= 3; o++) {
            for (int y = 0; y <= SIZE; y++) {
                if (Math.abs(o) <= 2 && y < SIZE)
                    continue; // casilla
                plan.put(wallPos(b, o, y).getBlock(), frameMat);
            }
        }
        for (Block blk : plan.keySet()) {
            if (blk.getType().isSolid() && byBlock(blk) == null) {
                p.sendMessage(msg("station_blocked",
                        "&cHay un bloque en el camino en &f{x} {y} {z}&c ({block}). Despeja detrás de la mesa.",
                        "x", String.valueOf(blk.getX()), "y", String.valueOf(blk.getY()), "z", String.valueOf(blk.getZ()),
                        "block", blk.getType().name()));
                return;
            }
        }
        for (Map.Entry<Block, Material> e : plan.entrySet()) {
            Block blk = e.getKey();
            b.built.putIfAbsent(BlackjackTables.key(blk.getLocation()), blk.getBlockData().getAsString());
            blk.setType(e.getValue(), false);
        }
        boards.put(name, b);
        save();
        updateHolo(b);
        p.sendMessage(msg("station_created",
                "&aTablero de Mines construido. &7Los jugadores empiezan con click derecho a la mesa."));
    }

    private Material material(String path, Material def) {
        Material m = Material.matchMaterial(config().getString(path, def.name()));
        return m == null || !m.isBlock() ? def : m;
    }

    private void layout(Board b) {
        b.tiles.clear();
        for (int row = 0; row < SIZE; row++) // fila 0 = arriba
            for (int col = 0; col < SIZE; col++)
                b.tiles.add(wallPos(b, col - 2, SIZE - 1 - row));
    }

    /** Pared a 1 bloque detrás de la mesa: offset lateral y altura sobre la mesa. */
    private static Location wallPos(Board b, int offset, int height) {
        return b.control.clone().add(b.fx + b.rx * offset, height, b.fz + b.rz * offset);
    }

    private void restore(Board b) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(b.built.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<String, String> e : entries) {
            Location l = BlackjackTables.parseKey(e.getKey());
            if (l == null)
                continue;
            try {
                l.getBlock().setBlockData(Bukkit.createBlockData(e.getValue()), false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[Mines] No se pudo restaurar " + e.getKey());
            }
        }
        b.built.clear();
    }

    private void save() {
        YamlConfiguration d = new YamlConfiguration();
        for (Board b : boards.values()) {
            String k = "boards." + b.name + ".";
            d.set(k + "control", BlackjackTables.key(b.control));
            d.set(k + "rx", b.rx);
            d.set(k + "rz", b.rz);
            d.set(k + "fx", b.fx);
            d.set(k + "fz", b.fz);
            List<String> built = new ArrayList<>();
            for (Map.Entry<String, String> e : b.built.entrySet())
                built.add(e.getKey() + "|" + e.getValue());
            d.set(k + "built", built);
        }
        saveData(d);
    }

    private Board byBlock(Block blk) {
        if (blk == null)
            return null;
        String k = BlackjackTables.key(blk.getLocation());
        for (Board b : boards.values())
            if (k.equals(BlackjackTables.key(b.control)) || b.built.containsKey(k))
                return b;
        return null;
    }

    private int tileIndex(Board b, Block blk) {
        String k = BlackjackTables.key(blk.getLocation());
        for (int i = 0; i < b.tiles.size(); i++)
            if (k.equals(BlackjackTables.key(b.tiles.get(i))))
                return i;
        return -1;
    }

    // ------------------------------------------------------------------
    // Partida
    // ------------------------------------------------------------------

    private double edge() {
        return Math.max(0.0, Math.min(50.0, config().getDouble("house_edge_percent", 3.0))) / 100.0;
    }

    /** Multiplicador después de {@code safe} casillas seguras con {@code mines} minas. */
    double multiplier(int mines, int safe) {
        if (safe <= 0)
            return 1.0;
        double m = 1.0 - edge();
        for (int i = 0; i < safe; i++)
            m *= (TILES - i) / (double) (TILES - mines - i);
        return Math.floor(m * 100.0) / 100.0;
    }

    private long payout(Board b) {
        long win = (long) Math.floor(b.bet * multiplier(b.mines, b.safeOpened));
        long maxWin = config().getLong("max_win", 0L);
        return maxWin > 0 ? Math.min(win, maxWin) : win;
    }

    private void openSetup(Player p, Board b) {
        Inventory inv = Bukkit.createInventory(new SetupHolder(b.name), 27, color("&6&lMines &8- &eElige las minas"));
        for (int i = 0; i < 27; i++)
            inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        int[] slots = { 10, 11, 12, 13, 14, 15, 16, 22 };
        for (int i = 0; i < MINE_OPTIONS.length; i++) {
            int m = MINE_OPTIONS[i];
            inv.setItem(slots[i], item(m <= 3 ? Material.LIME_CONCRETE : m <= 8 ? Material.YELLOW_CONCRETE
                    : m <= 16 ? Material.ORANGE_CONCRETE : Material.RED_CONCRETE,
                    "&f&l" + m + (m == 1 ? " mina" : " minas"), List.of(
                            "&7Primera casilla: &fx" + fmt(multiplier(m, 1)),
                            "&73 casillas: &fx" + fmt(multiplier(m, Math.min(3, TILES - m))),
                            "&7Todas las seguras: &fx" + fmt(multiplier(m, TILES - m)),
                            "", "&eClick para elegir")));
        }
        inv.setItem(4, item(Material.TNT, "&c&lMINES", List.of(
                "&7Más minas = más riesgo y más premio.",
                "&7Abre casillas con &fclick derecho&7.",
                "&7Retírate con &fshift + click derecho&7.")));
        inv.setItem(26, item(Material.SUNFLOWER, "&7Tus fichas: &e" + units(TokenWallet.balance(p)), null));
        p.openInventory(inv);
    }

    private void start(Player p, Board b, int mines, long amount) {
        if (b.player != null) {
            p.sendMessage(msg("busy", "&cEl tablero está ocupado por &f{player}&c.", "player", String.valueOf(b.playerName)));
            return;
        }
        long min = Math.max(1, config().getLong("min_bet", 10));
        long max = Math.max(0, config().getLong("max_bet", 0));
        if (amount < min || (max > 0 && amount > max)) {
            p.sendMessage(msg("bad_amount", "&cLa apuesta debe estar entre &e{min}&c y &e{max}&c.",
                    "min", units(min), "max", max > 0 ? units(max) : "∞"));
            return;
        }
        if (!isOpenFor(p))
            return;
        if (!TokenWallet.take(p, amount)) {
            p.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", units(TokenWallet.balance(p))));
            return;
        }
        b.player = p.getUniqueId();
        b.playerName = p.getName();
        b.bet = amount;
        b.mines = mines;
        b.mine = new boolean[TILES];
        b.open = new boolean[TILES];
        b.safeOpened = 0;
        b.ending = false;
        b.lastAction = System.currentTimeMillis();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < TILES; i++)
            idx.add(i);
        Collections.shuffle(idx, RNG);
        for (int i = 0; i < mines; i++)
            b.mine[idx.get(i)] = true;
        resetTiles(b);
        p.sendMessage(msg("started", "&aEmpezaste con &e{amount}&a y &f{mines}&a minas. &7Click derecho a una casilla para abrirla.",
                "amount", units(amount), "mines", String.valueOf(mines)));
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.2f);
        updateHolo(b);
        actionBar(b);
    }

    private void reveal(Player p, Board b, int i) {
        if (b.open[i])
            return;
        b.open[i] = true;
        b.lastAction = System.currentTimeMillis();
        Location l = b.tiles.get(i);
        if (b.mine[i]) {
            l.getBlock().setType(material("mine_material", Material.TNT), false);
            World w = l.getWorld();
            if (w != null) {
                Particle boom = particle("EXPLOSION", "EXPLOSION_LARGE");
                if (boom != null)
                    w.spawnParticle(boom, l.clone().add(0.5, 0.5, 0.5), 1);
                w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.0f);
            }
            lose(b);
            return;
        }
        l.getBlock().setType(material("safe_material", Material.EMERALD_BLOCK), false);
        b.safeOpened++;
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 0.8f + b.safeOpened * 0.05f);
        if (b.safeOpened >= TILES - b.mines) {
            GamblingDexPlugin.achievement(b.player, "mines_clear");
            cashOut(b, false); // abrió todas las seguras
            return;
        }
        updateHolo(b);
        actionBar(b);
    }

    private void lose(Board b) {
        Player p = Bukkit.getPlayer(b.player);
        GamblingDexPlugin.recordStats(b.player, "mines", b.bet, 0L);
        if (p != null) {
            p.sendMessage(msg("lost", "&c&l¡BOOM! &7Era una mina. Perdiste &e{amount}&7.", "amount", units(b.bet)));
            p.sendTitle(color("&c&l¡BOOM!"), color("&7-" + units(b.bet)), 2, 40, 10);
        }
        finish(b);
    }

    /** Retirarse: cobra apuesta x multiplicador (si no abrió ninguna, se le devuelve). */
    private void cashOut(Board b, boolean silentIfNone) {
        Player p = Bukkit.getPlayer(b.player);
        long pay = b.safeOpened == 0 ? b.bet : payout(b);
        TokenWallet.give(b.player, pay);
        if (b.safeOpened > 0)
            GamblingDexPlugin.recordStats(b.player, "mines", b.bet, pay);
        if (p != null) {
            if (b.safeOpened == 0) {
                if (!silentIfNone)
                    p.sendMessage(msg("refunded", "&7No abriste ninguna casilla: se te devolvieron &e{amount}&7.",
                            "amount", units(pay)));
            } else {
                p.sendMessage(msg("cashed_out", "&a&l¡RETIRASTE! &7en &fx{mult} &7→ &e+{amount}",
                        "mult", fmt(multiplier(b.mines, b.safeOpened)), "amount", units(pay)));
                p.sendTitle(color("&a&lx" + fmt(multiplier(b.mines, b.safeOpened))), color("&e+" + units(pay)), 2, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
            }
        }
        finish(b);
    }

    /** Muestra dónde estaban las minas unos segundos y deja el tablero libre. */
    private void finish(Board b) {
        b.ending = true;
        Material mineMat = material("mine_material", Material.TNT);
        Material hidden = material("hidden_safe_material", Material.LIGHT_GRAY_CONCRETE);
        for (int i = 0; i < TILES; i++) {
            if (b.open[i])
                continue;
            b.tiles.get(i).getBlock().setType(b.mine[i] ? mineMat : hidden, false);
        }
        updateHolo(b);
        runLater(() -> {
            b.player = null;
            b.playerName = null;
            b.ending = false;
            resetTiles(b);
            updateHolo(b);
        }, 20L * Math.max(1, config().getInt("result_seconds", 4)));
    }

    private void resetTiles(Board b) {
        Material tileMat = material("tile_material", Material.SMOOTH_QUARTZ);
        for (Location l : b.tiles)
            l.getBlock().setType(tileMat, false);
    }

    private void tick() {
        tickCount++;
        long timeout = Math.max(10, config().getInt("idle_timeout_seconds", 60)) * 1000L;
        for (Board b : boards.values()) {
            if (b.playing()) {
                if (System.currentTimeMillis() - b.lastAction > timeout) {
                    Player p = Bukkit.getPlayer(b.player);
                    if (p != null)
                        p.sendMessage(msg("timeout", "&7Te quedaste quieto mucho tiempo: se cobró lo que llevabas."));
                    cashOut(b, false);
                } else {
                    actionBar(b);
                }
            }
            if (tickCount % 10 == 0)
                updateHolo(b); // por si el chunk se recargó
        }
        if (tickCount % 10 == 0)
            menuStations.refreshAll();
    }

    private void actionBar(Board b) {
        Player p = Bukkit.getPlayer(b.player);
        if (p == null || !b.playing())
            return;
        double now = multiplier(b.mines, b.safeOpened);
        double next = multiplier(b.mines, b.safeOpened + 1);
        String text = "&7Apuesta: &e" + units(b.bet) + " &8| &a&lx" + fmt(now) + " &8| &7Siguiente: &fx" + fmt(next)
                + " &8| &7Cobras: &e" + units(b.safeOpened == 0 ? b.bet : payout(b)) + " &8| &fShift+Click = retirar";
        p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(color(text)));
    }

    // ------------------------------------------------------------------
    // Holograma (sobre la pared)
    // ------------------------------------------------------------------

    private boolean loaded(Board b) {
        World w = b.control.getWorld();
        return w != null && w.isChunkLoaded(b.control.getBlockX() >> 4, b.control.getBlockZ() >> 4);
    }

    private void updateHolo(Board b) {
        if (!loaded(b))
            return;
        World w = b.control.getWorld();
        Location at = wallPos(b, 0, SIZE + 1).add(0.5, config().getDouble("holo_offset", 0.3), 0.5);
        Entity e = b.holo == null ? null : w.getEntity(b.holo);
        TextDisplay td;
        if (e instanceof TextDisplay existing && existing.isValid()) {
            td = existing;
        } else {
            td = w.spawn(at, TextDisplay.class);
            td.setPersistent(false);
            td.setBillboard(Display.Billboard.CENTER);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            td.setShadowed(true);
            td.setLineWidth(300);
            b.holo = td.getUniqueId();
        }
        String text;
        if (b.player == null) {
            text = "&c&l✦ MINES ✦\n&7Click derecho a la mesa para jugar";
        } else if (b.ending) {
            text = "&c&l✦ MINES ✦\n&f" + b.playerName;
        } else {
            text = "&c&l✦ MINES ✦\n&f" + b.playerName + " &8| &7Minas: &f" + b.mines + "\n&a&lx"
                    + fmt(multiplier(b.mines, b.safeOpened));
        }
        td.setText(color(text));
    }

    private void removeHolo(Board b) {
        World w = b.control.getWorld();
        Entity e = b.holo == null || w == null ? null : w.getEntity(b.holo);
        if (e != null)
            e.remove();
        b.holo = null;
    }

    ItemStack item(Material m, String name, List<String> lore) {
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

    /** La partícula cambió de nombre entre versiones (1.20.4 vs 1.21): se busca por nombre. */
    private static Particle particle(String... names) {
        for (String n : names) {
            try {
                return Particle.valueOf(n);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    private final class Events implements Listener {

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            Block blk = e.getClickedBlock();
            if (menuStations.contains(blk)) {
                e.setCancelled(true);
                if (e.getHand() == EquipmentSlot.HAND && e.getAction() == Action.RIGHT_CLICK_BLOCK
                        && isOpenFor(e.getPlayer()))
                    menu.open(e.getPlayer());
                return;
            }
            Board b = byBlock(blk);
            if (b == null)
                return;
            e.setCancelled(true);
            if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK)
                return;
            Player p = e.getPlayer();
            boolean isControl = BlackjackTables.key(blk.getLocation()).equals(BlackjackTables.key(b.control));
            int tile = tileIndex(b, blk);

            if (b.player == null) {
                if ((isControl || tile >= 0) && isOpenFor(p))
                    openSetup(p, b);
                return;
            }
            if (!b.player.equals(p.getUniqueId())) {
                p.sendMessage(msg("busy", "&cEl tablero está ocupado por &f{player}&c.", "player", String.valueOf(b.playerName)));
                return;
            }
            if (b.ending)
                return;
            if (p.isSneaking()) {
                cashOut(b, false);
                return;
            }
            if (tile >= 0)
                reveal(p, b, tile);
            else
                p.sendMessage(msg("hint", "&7Click derecho a una casilla para abrirla, &fshift + click derecho&7 para retirarte."));
        }

        @EventHandler
        public void onBreak(BlockBreakEvent e) {
            if (byBlock(e.getBlock()) == null && !menuStations.contains(e.getBlock()))
                return;
            e.setCancelled(true);
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof SetupHolder h))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            Board b = boards.get(h.board.toLowerCase(Locale.ROOT));
            if (b == null)
                return;
            int[] slots = { 10, 11, 12, 13, 14, 15, 16, 22 };
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != e.getRawSlot())
                    continue;
                int mines = MINE_OPTIONS[i];
                long min = Math.max(1, config().getLong("min_bet", 10));
                AmountPickerMenu.open(p, "&6&lMines &8- &f" + mines + " minas", min,
                        Math.max(0, config().getLong("max_bet", 0)), min,
                        List.of("&7Minas: &f" + mines, "&7Primera casilla: &fx" + fmt(multiplier(mines, 1))),
                        amount -> start(p, b, mines, amount), null);
                return;
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof SetupHolder)
                e.setCancelled(true);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            for (Board b : boards.values())
                if (b.playing() && e.getPlayer().getUniqueId().equals(b.player))
                    cashOut(b, true);
        }
    }
}
