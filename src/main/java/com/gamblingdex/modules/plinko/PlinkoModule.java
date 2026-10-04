package com.gamblingdex.modules.plinko;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.games.blackjack.BlackjackTables;
import com.gamblingdex.gui.AmountPickerMenu;
import com.gamblingdex.modules.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.security.SecureRandom;
import java.util.*;

/**
 * Plinko: una pared con clavos (varillas del End) construida en el mundo. Cada
 * apuesta suelta una bola desde arriba; en cada fila rebota a la izquierda o a
 * la derecha (50/50) y cae en una casilla con multiplicador. Las casillas de
 * los extremos pagan más porque son menos probables (distribución binomial).
 */
public class PlinkoModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int TICKS_PER_ROW = 5;

    private static final class Board {
        final String name;
        final Location control;
        final int rows;
        final int rx, rz, fx, fz;
        final Map<String, String> built = new LinkedHashMap<>();
        final List<UUID> labels = new ArrayList<>();
        UUID holo;
        final List<Ball> balls = new ArrayList<>();

        Board(String name, Location control, int rows, int rx, int rz, int fx, int fz) {
            this.name = name;
            this.control = control;
            this.rows = rows;
            this.rx = rx;
            this.rz = rz;
            this.fx = fx;
            this.fz = fz;
        }
    }

    private static final class Ball {
        final UUID player;
        final long bet;
        final boolean[] rights; // camino: true = derecha
        int tick;
        UUID entity;

        Ball(UUID player, long bet, boolean[] rights) {
            this.player = player;
            this.bet = bet;
            this.rights = rights;
        }
    }

    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<UUID, Long> lastBet = new HashMap<>();

    @Override
    public String id() {
        return "plinko";
    }

    @Override
    public String displayName() {
        return "Plinko";
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
                Board b = new Board(name, l, sec.getInt(name + ".rows", 8), sec.getInt(name + ".rx"),
                        sec.getInt(name + ".rz"), sec.getInt(name + ".fx"), sec.getInt(name + ".fz"));
                for (String s : sec.getStringList(name + ".built")) {
                    int bar = s.indexOf('|');
                    if (bar > 0)
                        b.built.put(s.substring(0, bar), s.substring(bar + 1));
                }
                boards.put(name.toLowerCase(Locale.ROOT), b);
            }
        }
        listen(new Events());
        runTimer(this::tick, 1L, 1L);
    }

    @Override
    public void disable() {
        for (Board b : boards.values()) {
            // Bolas en el aire: se pagan ya (el resultado ya estaba decidido)
            for (Ball ball : new ArrayList<>(b.balls))
                land(b, ball);
            b.balls.clear();
            removeDisplays(b);
        }
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>();
        l.add("&6&lPlinko");
        l.add("&8• &fClick derecho&7 a la mesa: soltar una bola &8| &fShift + click derecho&7: otra con la misma apuesta");
        if (admin)
            l.add("&8• &e/gdx station set plinko [6|8|10|12] &7- Construir el tablero (mirando el bloque de la mesa)");
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        player.sendMessage(msg("use_station", "&7Plinko se juega en el tablero: &fclick derecho&7 a la mesa para soltar una bola."));
        if (isAdmin(player))
            player.sendMessage(msg("admin_hint", "&7Admin: &f/gdx station set plinko [6|8|10|12] &7(mirando la mesa) | &f/gdx station remove"));
        return true;
    }

    // ------------------------------------------------------------------
    // Construcción
    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("plinko");
    }

    @Override
    public String stationUsage() {
        return "plinko [6|8|10|12]";
    }

    @Override
    public List<String> stationListLines() {
        return List.of("&8- &6Plinko&7: &f" + boards.size());
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        Board b = byBlock(target);
        if (b == null)
            return false;
        for (Ball ball : new ArrayList<>(b.balls))
            land(b, ball);
        b.balls.clear();
        removeDisplays(b);
        restore(b);
        boards.remove(b.name.toLowerCase(Locale.ROOT));
        save();
        player.sendMessage(msg("station_removed", "&aTablero de Plinko eliminado y bloques restaurados."));
        return true;
    }

    /**
     * Pared a 1 bloque detrás de la mesa (clavos, divisiones y casillas) con un
     * fondo a 2 bloques. Ancho 2*filas+3, alto 2*filas+3.
     */
    @Override
    public void createStation(Player p, Block target, String[] args) {
        if (byBlock(target) != null) {
            p.sendMessage(msg("exists", "&cAhí ya hay un tablero de Plinko."));
            return;
        }
        int rows = 8;
        if (args.length >= 1) {
            try {
                rows = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (multipliers(rows) == null) {
            p.sendMessage(msg("bad_rows", "&cFilas válidas: &f{rows}&c (según multipliers en plinko.yml).",
                    "rows", String.join(", ", validRows())));
            return;
        }
        BlockFace f = p.getFacing();
        int n = 1;
        while (boards.containsKey("plinko" + n))
            n++;
        Board b = new Board("plinko" + n, target.getLocation(), rows, -f.getModZ(), f.getModX(), f.getModX(), f.getModZ());

        Material back = material("background_material", Material.BLACK_CONCRETE);
        Material frame = material("frame_material", Material.POLISHED_BLACKSTONE_BRICKS);
        Material peg = material("peg_material", Material.END_ROD);
        int half = rows + 1;
        int top = 2 * rows + 2;
        Map<Block, BlockData> plan = new LinkedHashMap<>();
        // Fondo
        for (int o = -half; o <= half; o++)
            for (int y = 0; y <= top; y++)
                plan.put(pos(b, o, y, 2).getBlock(), back.createBlockData());
        // Plano de juego: marco, clavos, divisiones y casillas
        List<Double> mult = multipliers(rows);
        for (int o = -half; o <= half; o++) {
            for (int y = 0; y <= top; y++) {
                Block blk = pos(b, o, y, 1).getBlock();
                BlockData data = Material.AIR.createBlockData();
                boolean slotRow = y == 0;
                if (Math.abs(o) == half || y == top) {
                    data = frame.createBlockData();
                } else if (slotRow) {
                    // Casillas en posiciones de la misma paridad que las filas; divisiones en medio
                    if (Math.floorMod(o + rows, 2) == 0) {
                        int k = (o + rows) / 2;
                        data = slotMaterial(mult.get(k)).createBlockData();
                    } else {
                        data = frame.createBlockData();
                    }
                } else if (y == 1 && Math.floorMod(o + rows, 2) != 0) {
                    data = frame.createBlockData(); // divisiones entre casillas
                } else if (y >= 2 && y % 2 == 0) {
                    // Clavo de la fila r: debajo de cada posición posible de la bola
                    int r = rows - y / 2;
                    if (r >= 0 && r < rows && Math.abs(o) <= r && Math.floorMod(o + r, 2) == 0) {
                        data = peg.createBlockData();
                        if (data instanceof Directional dir)
                            dir.setFacing(f.getOppositeFace()); // que apunte hacia el jugador
                    }
                }
                plan.put(blk, data);
            }
        }
        for (Map.Entry<Block, BlockData> e : plan.entrySet()) {
            Block blk = e.getKey();
            if (blk.getType().isSolid() && byBlock(blk) == null) {
                p.sendMessage(msg("station_blocked",
                        "&cHay un bloque en el camino en &f{x} {y} {z}&c ({block}). El tablero mide {w}x{h}: despeja detrás de la mesa.",
                        "x", String.valueOf(blk.getX()), "y", String.valueOf(blk.getY()), "z", String.valueOf(blk.getZ()),
                        "block", blk.getType().name(), "w", String.valueOf(2 * half + 1), "h", String.valueOf(top + 1)));
                return;
            }
        }
        for (Map.Entry<Block, BlockData> e : plan.entrySet()) {
            Block blk = e.getKey();
            if (blk.getBlockData().matches(e.getValue()))
                continue;
            b.built.putIfAbsent(BlackjackTables.key(blk.getLocation()), blk.getBlockData().getAsString());
            blk.setBlockData(e.getValue(), false);
        }
        boards.put(b.name, b);
        save();
        updateDisplays(b);
        p.sendMessage(msg("station_created",
                "&aTablero de Plinko construido ({rows} filas). &7Los jugadores sueltan bolas con click derecho a la mesa.",
                "rows", String.valueOf(rows)));
    }

    /** Bloque en el plano: offset lateral, altura sobre la mesa y profundidad detrás de la mesa. */
    private static Location pos(Board b, int offset, int height, int depth) {
        return b.control.clone().add(b.fx * depth + b.rx * offset, height, b.fz * depth + b.rz * offset);
    }

    private Material material(String path, Material def) {
        Material m = Material.matchMaterial(config().getString(path, def.name()));
        return m == null || !m.isBlock() ? def : m;
    }

    private Material slotMaterial(double mult) {
        if (mult >= 5)
            return material("slot_materials.high", Material.RED_CONCRETE);
        if (mult >= 2)
            return material("slot_materials.medium", Material.ORANGE_CONCRETE);
        if (mult >= 1)
            return material("slot_materials.low", Material.YELLOW_CONCRETE);
        return material("slot_materials.loss", Material.LIME_CONCRETE);
    }

    private List<String> validRows() {
        ConfigurationSection sec = config().getConfigurationSection("multipliers");
        return sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
    }

    /** Multiplicadores para {@code rows} filas (rows+1 casillas), o null si no están en la config. */
    private List<Double> multipliers(int rows) {
        List<Double> list = new ArrayList<>();
        for (Object o : config().getList("multipliers." + rows, List.of()))
            if (o instanceof Number num)
                list.add(num.doubleValue());
        return list.size() == rows + 1 ? list : null;
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
                plugin.getLogger().warning("[Plinko] No se pudo restaurar " + e.getKey());
            }
        }
        b.built.clear();
    }

    private void save() {
        YamlConfiguration d = new YamlConfiguration();
        for (Board b : boards.values()) {
            String k = "boards." + b.name + ".";
            d.set(k + "control", BlackjackTables.key(b.control));
            d.set(k + "rows", b.rows);
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

    // ------------------------------------------------------------------
    // Juego
    // ------------------------------------------------------------------

    private void askBet(Player p, Board b) {
        long min = Math.max(1, config().getLong("min_bet", 10));
        long max = Math.max(0, config().getLong("max_bet", 0));
        List<Double> mult = multipliers(b.rows);
        String best = mult == null ? "?" : fmt(Collections.max(mult));
        AmountPickerMenu.open(p, "&6&lPlinko &8- &eTu apuesta", min, max, lastBet.getOrDefault(p.getUniqueId(), min),
                List.of("&7Filas: &f" + b.rows, "&7Premio máximo: &fx" + best,
                        "&7Shift + click derecho a la mesa: otra bola igual"),
                amount -> drop(p, b, amount), null);
    }

    private void drop(Player p, Board b, long amount) {
        List<Double> mult = multipliers(b.rows);
        if (mult == null) {
            p.sendMessage(msg("bad_rows", "&cEste tablero no tiene multiplicadores configurados."));
            return;
        }
        long min = Math.max(1, config().getLong("min_bet", 10));
        long max = Math.max(0, config().getLong("max_bet", 0));
        if (amount < min || (max > 0 && amount > max)) {
            p.sendMessage(msg("bad_amount", "&cLa apuesta debe estar entre &e{min}&c y &e{max}&c.",
                    "min", units(min), "max", max > 0 ? units(max) : "∞"));
            return;
        }
        int maxBalls = Math.max(1, config().getInt("max_balls_per_player", 5));
        int mine = 0;
        for (Ball ball : b.balls)
            if (ball.player.equals(p.getUniqueId()))
                mine++;
        if (mine >= maxBalls) {
            p.sendMessage(msg("too_many", "&cEspera a que caigan tus bolas (máximo {max} a la vez).",
                    "max", String.valueOf(maxBalls)));
            return;
        }
        if (!TokenWallet.take(p, amount)) {
            p.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", units(TokenWallet.balance(p))));
            return;
        }
        lastBet.put(p.getUniqueId(), amount);
        boolean[] rights = new boolean[b.rows];
        for (int i = 0; i < b.rows; i++)
            rights[i] = RNG.nextBoolean();
        Ball ball = new Ball(p.getUniqueId(), amount, rights);
        b.balls.add(ball);
        spawnBall(b, ball);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.4f);
    }

    /** Posición de la bola en el tick {@code t} (rebota de fila en fila con un pequeño salto). */
    private Location ballPos(Board b, Ball ball, int t) {
        int row = Math.min(b.rows, t / TICKS_PER_ROW);
        double frac = row >= b.rows ? 0 : (t % TICKS_PER_ROW) / (double) TICKS_PER_ROW;
        int off = 0;
        for (int i = 0; i < row; i++)
            off += ball.rights[i] ? 1 : -1;
        double offset = off;
        double y = 1 + 2 * (b.rows - row); // fila r a la altura 1 + 2(filas - r); r = filas = casilla
        if (row < b.rows) {
            int dir = ball.rights[row] ? 1 : -1;
            offset += dir * frac;
            y -= 2 * frac;
            y += Math.sin(Math.PI * frac) * 0.45; // saltito al tocar el clavo
        }
        Location base = pos(b, 0, 0, 1).add(0.5, 0.5, 0.5);
        return base.add(b.rx * offset - b.fx * 0.6, y, b.rz * offset - b.fz * 0.6);
    }

    private void spawnBall(Board b, Ball ball) {
        World w = b.control.getWorld();
        if (w == null)
            return;
        Material m = Material.matchMaterial(config().getString("ball_item", "SLIME_BALL"));
        ItemDisplay d = w.spawn(ballPos(b, ball, 0), ItemDisplay.class);
        d.setPersistent(false);
        d.setItemStack(new ItemStack(m == null ? Material.SLIME_BALL : m));
        d.setBillboard(Display.Billboard.CENTER);
        ball.entity = d.getUniqueId();
    }

    private int tickCount;

    private void tick() {
        tickCount++;
        for (Board b : boards.values()) {
            if (tickCount % 100 == 0)
                updateDisplays(b); // hologramas (se pierden si el chunk se descarga o al reiniciar)
            if (b.balls.isEmpty())
                continue;
            World w = b.control.getWorld();
            for (Ball ball : new ArrayList<>(b.balls)) {
                ball.tick++;
                Entity e = w == null || ball.entity == null ? null : w.getEntity(ball.entity);
                if (e != null)
                    e.teleport(ballPos(b, ball, ball.tick));
                if (ball.tick % TICKS_PER_ROW == 0 && ball.tick / TICKS_PER_ROW <= b.rows && w != null)
                    w.playSound(e != null ? e.getLocation() : b.control, Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.8f);
                if (ball.tick >= b.rows * TICKS_PER_ROW + 10)
                    land(b, ball);
            }
        }
    }

    private void land(Board b, Ball ball) {
        b.balls.remove(ball);
        World w = b.control.getWorld();
        Entity e = w == null || ball.entity == null ? null : w.getEntity(ball.entity);
        if (e != null)
            e.remove();
        int k = 0;
        for (boolean r : ball.rights)
            if (r)
                k++;
        List<Double> mult = multipliers(b.rows);
        double m = mult == null ? 1.0 : mult.get(k);
        long pay = (long) Math.floor(ball.bet * m);
        long maxWin = config().getLong("max_win", 0L);
        if (maxWin > 0)
            pay = Math.min(pay, maxWin);
        if (pay > 0)
            TokenWallet.give(ball.player, pay);
        GamblingDexPlugin.recordStats(ball.player, "plinko", ball.bet, pay);
        Player p = Bukkit.getPlayer(ball.player);
        if (p != null) {
            long net = pay - ball.bet;
            p.sendMessage(msg("landed", "&6&lPlinko &8» &7Cayó en &fx{mult} &8→ {result}",
                    "mult", fmt(m), "result", net >= 0 ? color("&a+" + units(pay)) : color("&c" + units(pay) + " &8(-" + units(-net) + ")")));
            p.playSound(p.getLocation(), m >= 2 ? Sound.ENTITY_PLAYER_LEVELUP : m >= 1 ? Sound.BLOCK_NOTE_BLOCK_BELL
                    : Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.2f);
            if (m >= config().getDouble("title_min_multiplier", 5.0))
                p.sendTitle(color("&6&lx" + fmt(m)), color("&e+" + units(pay)), 5, 40, 10);
        }
    }

    // ------------------------------------------------------------------
    // Hologramas: título y multiplicador de cada casilla
    // ------------------------------------------------------------------

    private void updateDisplays(Board b) {
        World w = b.control.getWorld();
        if (w == null || !w.isChunkLoaded(b.control.getBlockX() >> 4, b.control.getBlockZ() >> 4))
            return;
        Entity existing = b.holo == null ? null : w.getEntity(b.holo);
        if (!(existing instanceof TextDisplay) || !existing.isValid()) {
            TextDisplay td = text(w, pos(b, 0, 2 * b.rows + 3, 1).add(0.5, 0.4, 0.5),
                    "&6&l✦ PLINKO ✦\n&7Click derecho a la mesa para soltar una bola");
            b.holo = td.getUniqueId();
        }
        boolean labelsOk = b.labels.size() == b.rows + 1;
        for (UUID id : b.labels) {
            Entity e = w.getEntity(id);
            if (e == null || !e.isValid())
                labelsOk = false;
        }
        if (labelsOk)
            return;
        for (UUID id : b.labels) {
            Entity e = w.getEntity(id);
            if (e != null)
                e.remove();
        }
        b.labels.clear();
        List<Double> mult = multipliers(b.rows);
        if (mult == null)
            return;
        for (int k = 0; k <= b.rows; k++) {
            int offset = 2 * k - b.rows;
            Location at = pos(b, offset, 0, 1).add(0.5 - b.fx * 0.6, 0.25, 0.5 - b.fz * 0.6);
            double m = mult.get(k);
            String c = m >= 5 ? "&c&l" : m >= 2 ? "&6&l" : m >= 1 ? "&e&l" : "&a&l";
            b.labels.add(text(w, at, c + "x" + fmt(m)).getUniqueId());
        }
    }

    private TextDisplay text(World w, Location at, String s) {
        TextDisplay td = w.spawn(at, TextDisplay.class);
        td.setPersistent(false);
        td.setBillboard(Display.Billboard.CENTER);
        td.setDefaultBackground(false);
        td.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
        td.setShadowed(true);
        td.setLineWidth(300);
        td.setText(color(s));
        return td;
    }

    private void removeDisplays(Board b) {
        World w = b.control.getWorld();
        if (w == null)
            return;
        List<UUID> all = new ArrayList<>(b.labels);
        if (b.holo != null)
            all.add(b.holo);
        for (UUID id : all) {
            Entity e = w.getEntity(id);
            if (e != null)
                e.remove();
        }
        b.labels.clear();
        b.holo = null;
    }

    private static String fmt(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    private final class Events implements Listener {

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            Board b = byBlock(e.getClickedBlock());
            if (b == null)
                return;
            e.setCancelled(true);
            if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK)
                return;
            Player p = e.getPlayer();
            Long last = lastBet.get(p.getUniqueId());
            if (p.isSneaking() && last != null)
                drop(p, b, last);
            else
                askBet(p, b);
        }

        @EventHandler
        public void onBreak(BlockBreakEvent e) {
            if (byBlock(e.getBlock()) != null)
                e.setCancelled(true);
        }
    }

    @Override
    public void reload() {
        for (Board b : boards.values()) {
            removeDisplays(b);
            updateDisplays(b);
        }
    }
}
