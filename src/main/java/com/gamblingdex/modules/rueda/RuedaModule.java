package com.gamblingdex.modules.rueda;

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
import org.bukkit.block.data.Lightable;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.security.SecureRandom;
import java.util.*;

/**
 * Rueda de la Fortuna: un bloque con un holograma de rueda. Los jugadores
 * apuestan a un segmento (x1, x2, x5...), la rueda gira y frena, y paga a
 * quienes acertaron. Cada rueda funciona por separado.
 *
 * Rueda física: /gdx station set wheel mirando un faro. Alrededor del faro se
 * construye una pared de lámparas de redstone con una fila de 7 bloques de
 * concreto encima del faro; al girar el concreto se desplaza, las lámparas se
 * encienden en cadena y gana el color que queda encima del faro.
 */
public class RuedaModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();

    private enum State {
        IDLE,
        BETTING,
        SPINNING,
        RESULT
    }

    private record Segment(long multiplier, int count, String color, Material icon) {
        String label() {
            return color + "x" + multiplier;
        }
    }

    private static final class Wheel {
        final String name;
        final Location loc;
        State state = State.IDLE;
        int countdown;
        // jugador -> (multiplicador -> monto)
        final Map<UUID, Map<Long, Long>> bets = new LinkedHashMap<>();
        int pointer;
        int target;
        int stepsLeft;
        int totalSteps;
        int nextStepIn;
        UUID displayId;
        Segment last;
        /** Rueda física construida con /gdx station set wheel. */
        boolean physical;
        int rx, rz; // derecha vista de frente
        final Map<String, String> built = new LinkedHashMap<>();
        final List<Location> lamps = new ArrayList<>();
        final List<Location> row = new ArrayList<>();
        int lampPhase;

        Wheel(String name, Location loc) {
            this.name = name;
            this.loc = loc;
        }
    }

    private static final class BetHolder implements InventoryHolder {
        final String wheel;
        final Map<Integer, Long> slotToMult = new HashMap<>();

        BetHolder(String wheel) {
            this.wheel = wheel;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Map<String, Wheel> wheels = new LinkedHashMap<>();
    private List<Segment> segments = new ArrayList<>();
    private List<Segment> strip = new ArrayList<>();
    private int tickCount;

    @Override
    public String id() {
        return "rueda";
    }

    @Override
    public String displayName() {
        return "Rueda de la Fortuna";
    }

    @Override
    public List<String> aliases() {
        return List.of("wheel", "ruedafortuna");
    }

    @Override
    public void enable() {
        loadSegments();
        YamlConfiguration data = loadData();
        ConfigurationSection sec = data.getConfigurationSection("wheels");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                Location l = BlackjackTables.parseKey(sec.getString(name + ".location"));
                if (l == null)
                    continue;
                Wheel w = new Wheel(name, l);
                if (sec.getBoolean(name + ".physical", false)) {
                    w.physical = true;
                    w.rx = sec.getInt(name + ".rx");
                    w.rz = sec.getInt(name + ".rz");
                    for (String b : sec.getStringList(name + ".built")) {
                        int bar = b.indexOf('|');
                        if (bar > 0)
                            w.built.put(b.substring(0, bar), b.substring(bar + 1));
                    }
                    layout(w);
                }
                wheels.put(name.toLowerCase(Locale.ROOT), w);
            }
        }
        listen(new Events());
        runTimer(this::tick, 1L, 1L);
    }

    @Override
    public void reload() {
        loadSegments();
    }

    @Override
    public void disable() {
        for (Wheel w : wheels.values()) {
            refundAll(w);
            removeDisplay(w);
        }
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lRueda de la Fortuna",
                "&8• &7Apostar: &fclick derecho&7 al faro o al bloque de la rueda"));
        if (admin) {
            l.add("&8• &e/gdx station set wheel &7- Construir la rueda física (mirando un faro)");
            l.add("&8• &e/gdx station remove &7- Quitarla (mirando el faro; restaura los bloques)");
            l.add("&8• &e/gdx wheel create <name>&7|&eremove <name>&7|&elist &7- Ruedas de solo holograma");
        }
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        if (!isAdmin(player) || args.length == 0) {
            player.sendMessage(msg("usage", "&7Haz &fclick derecho &7al bloque de la rueda para apostar."));
            if (isAdmin(player))
                player.sendMessage(msg("usage_admin", "&cUso: /gdx wheel <create|remove|list> [name]"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "crear", "create" -> {
                if (args.length < 2) {
                    player.sendMessage(msg("usage_admin", "&cUso: /gdx wheel <create|remove|list> [name]"));
                    return true;
                }
                Block b = player.getTargetBlockExact(6);
                if (b == null) {
                    player.sendMessage(msg("look_block", "&cMira el bloque de la rueda (a 6 bloques o menos)."));
                    return true;
                }
                String name = args[1].replaceAll("[^a-zA-Z0-9_\\-]", "");
                if (name.isBlank() || wheels.containsKey(name.toLowerCase(Locale.ROOT)) || byBlock(b) != null) {
                    player.sendMessage(msg("exists", "&cYa existe una rueda con ese nombre o en ese bloque."));
                    return true;
                }
                Wheel w = new Wheel(name, b.getLocation());
                wheels.put(name.toLowerCase(Locale.ROOT), w);
                save();
                updateDisplay(w);
                player.sendMessage(msg("created", "&aRueda &f{name}&a creada.", "name", name));
            }
            case "borrar", "remove", "delete" -> {
                Wheel w = args.length >= 2 ? wheels.get(args[1].toLowerCase(Locale.ROOT)) : null;
                if (w == null) {
                    player.sendMessage(msg("not_found", "&cNo existe una rueda con ese nombre."));
                    return true;
                }
                removeWheel(w);
                player.sendMessage(msg("removed", "&aRueda eliminada (se devolvieron las apuestas)."));
            }
            case "lista", "list" -> {
                player.sendMessage(color("&7Ruedas: &f" + wheels.size()));
                for (Wheel w : wheels.values())
                    player.sendMessage(color("&8- &f" + w.name + " &7" + BlackjackTables.key(w.loc)));
            }
            default -> player.sendMessage(msg("usage_admin", "&cUso: /gdx wheel <create|remove|list> [name]"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Segmentos
    // ------------------------------------------------------------------

    private void loadSegments() {
        List<Segment> list = new ArrayList<>();
        for (Map<?, ?> m : config().getMapList("segments")) {
            long mult = m.get("multiplier") instanceof Number n ? n.longValue() : 0;
            int count = m.get("count") instanceof Number n ? n.intValue() : 0;
            if (mult <= 0 || count <= 0)
                continue;
            String c = m.get("color") == null ? "&f" : String.valueOf(m.get("color"));
            Material icon = Material.matchMaterial(String.valueOf(m.get("icon")));
            list.add(new Segment(mult, count, color(c), icon == null ? Material.WHITE_CONCRETE : icon));
        }
        if (list.isEmpty())
            list.add(new Segment(1, 1, color("&f"), Material.WHITE_CONCRETE));
        segments = list;
        strip = buildStrip(list);
    }

    /** Reparte los segmentos alrededor de la rueda (los raros quedan separados). */
    private static List<Segment> buildStrip(List<Segment> segs) {
        int total = 0;
        for (Segment s : segs)
            total += s.count();
        Segment[] out = new Segment[total];
        List<Segment> sorted = new ArrayList<>(segs);
        sorted.sort(Comparator.comparingInt(Segment::count)); // raros primero
        for (Segment s : sorted) {
            for (int k = 0; k < s.count(); k++) {
                int pos = (int) Math.floor((k + 0.5) * total / (double) s.count()) % total;
                while (out[pos] != null)
                    pos = (pos + 1) % total;
                out[pos] = s;
            }
        }
        return new ArrayList<>(Arrays.asList(out));
    }

    private Segment segmentFor(long mult) {
        for (Segment s : segments)
            if (s.multiplier() == mult)
                return s;
        return null;
    }

    // ------------------------------------------------------------------
    // Rondas
    // ------------------------------------------------------------------

    private void tick() {
        tickCount++;
        for (Wheel w : wheels.values()) {
            if (w.physical && tickCount % 10 == 0 && (w.state == State.BETTING || w.state == State.RESULT)) {
                w.lampPhase++;
                renderBlocks(w);
            }
            switch (w.state) {
                case BETTING -> {
                    if (tickCount % 20 == 0) {
                        if (--w.countdown <= 0)
                            startSpin(w);
                        updateDisplay(w);
                    }
                }
                case SPINNING -> spinStep(w);
                case RESULT -> {
                    if (tickCount % 20 == 0 && --w.countdown <= 0) {
                        w.state = State.IDLE;
                        updateDisplay(w);
                    }
                }
                case IDLE -> {
                    if (tickCount % 100 == 0)
                        updateDisplay(w); // por si el holograma se descargó
                }
            }
        }
    }

    private void startSpin(Wheel w) {
        w.state = State.SPINNING;
        int n = strip.size();
        w.target = RNG.nextInt(n);
        int dist = Math.floorMod(w.target - w.pointer, n);
        w.totalSteps = n + dist; // 1 vuelta completa + hasta el resultado
        w.stepsLeft = w.totalSteps;
        w.nextStepIn = 0;
        announce(w, msg("spinning", "&6&lRueda &8» &e¡La rueda está girando!"));
    }

    private void spinStep(Wheel w) {
        if (--w.nextStepIn > 0)
            return;
        int n = strip.size();
        w.pointer = (w.pointer + 1) % n;
        w.stepsLeft--;
        // Frena de a poco: los últimos pasos tardan más.
        double progress = 1.0 - (w.stepsLeft / (double) Math.max(1, w.totalSteps));
        int spinTicks = Math.max(40, config().getInt("spin_seconds", 7) * 20);
        // 1 tick por paso al principio; el frenado reparte el tiempo que sobra.
        double slow = Math.max(0.0, (spinTicks - w.totalSteps) * 5.0 / Math.max(1, w.totalSteps));
        w.nextStepIn = 1 + (int) Math.round(Math.pow(progress, 4) * slow);
        w.lampPhase++;
        updateDisplay(w);
        playNear(w, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.6f);
        if (w.stepsLeft <= 0) {
            w.pointer = w.target;
            finishSpin(w);
        }
    }

    private void finishSpin(Wheel w) {
        Segment result = strip.get(w.pointer);
        w.last = result;
        w.state = State.RESULT;
        w.countdown = Math.max(2, config().getInt("result_seconds", 6));
        announce(w, msg("result", "&6&lRueda &8» &7Salió {segment}&7!", "segment", result.label()));
        playNear(w, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);

        // Ganadores, para avisarles a todos los que jugaron esta ronda.
        List<String> winners = new ArrayList<>();
        for (Map.Entry<UUID, Map<Long, Long>> e : w.bets.entrySet()) {
            long onResult = e.getValue().getOrDefault(result.multiplier(), 0L);
            if (onResult > 0) {
                String n = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("?");
                winners.add("&f" + n + " &e+" + units(onResult * (result.multiplier() + 1)));
            }
        }
        String summary = msg("summary", "&6&lRueda &8» &7Ganadores: {players}", "players",
                winners.isEmpty() ? color("&8nadie") : color(String.join("&7, ", winners)));

        long paidTotal = 0, betTotal = 0;
        for (Map.Entry<UUID, Map<Long, Long>> e : w.bets.entrySet()) {
            UUID id = e.getKey();
            long staked = 0, won = 0;
            for (Map.Entry<Long, Long> b : e.getValue().entrySet()) {
                staked += b.getValue();
                if (b.getKey() == result.multiplier())
                    won += b.getValue() * (result.multiplier() + 1);
            }
            betTotal += staked;
            paidTotal += won;
            GamblingDexPlugin.recordStats(id, "rueda", staked, won);
            Player p = Bukkit.getPlayer(id);
            if (won > 0) {
                TokenWallet.give(id, won);
                if (p != null) {
                    p.sendMessage(msg("win", "&a&l¡GANASTE! &7Salió {segment} &7→ &e+{amount}",
                            "segment", result.label(), "amount", units(won)));
                    p.sendTitle(color("&a&l¡GANASTE!"), color("&e+" + units(won)), 5, 50, 15);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.2f);
                }
            } else if (p != null) {
                p.sendMessage(msg("lose", "&7Salió {segment}&7. Perdiste &e{amount}&7.",
                        "segment", result.label(), "amount", units(staked)));
            }
            if (p != null)
                p.sendMessage(summary);
        }
        w.bets.clear();
        plugin.getLogger().info("[Rueda] " + w.name + " salió x" + result.multiplier() + " | apostado " + betTotal
                + " pagado " + paidTotal);
        updateDisplay(w);
    }

    private void placeBet(Player p, Wheel w, long mult, long amount) {
        if (w.state == State.SPINNING || w.state == State.RESULT) {
            p.sendMessage(msg("bets_closed", "&cLa rueda está girando. Espera la próxima ronda."));
            return;
        }
        long min = Math.max(1, config().getLong("min_bet", 10));
        long max = Math.max(0, config().getLong("max_bet", 0));
        if (amount < min) {
            p.sendMessage(msg("min_bet", "&cLa apuesta mínima es &e{min}&c.", "min", units(min)));
            return;
        }
        if (max > 0 && amount > max) {
            p.sendMessage(msg("max_bet", "&cLa apuesta máxima es &e{max}&c.", "max", units(max)));
            return;
        }
        if (!TokenWallet.take(p, amount)) {
            p.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", units(TokenWallet.balance(p))));
            return;
        }
        Map<Long, Long> mine = w.bets.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        long total = mine.merge(mult, amount, Long::sum);
        Segment s = segmentFor(mult);
        p.sendMessage(msg("bet_placed", "&aApostaste &e{amount}&a a &f{segment}&a.",
                "amount", units(amount), "segment", s == null ? "x" + mult : s.label(), "total", units(total)));
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.4f);

        if (w.state == State.IDLE) {
            w.state = State.BETTING;
            w.countdown = Math.max(5, config().getInt("bet_window_seconds", 20));
            announce(w, msg("countdown_started", "&6&lRueda &8» &7¡Apuestas abiertas! Gira en &f{seconds}s&7.",
                    "seconds", String.valueOf(w.countdown)));
        }
        updateDisplay(w);
    }

    private void clearBets(Player p, Wheel w) {
        if (w.state != State.BETTING && w.state != State.IDLE) {
            p.sendMessage(msg("bets_closed", "&cLa rueda está girando. Espera la próxima ronda."));
            return;
        }
        Map<Long, Long> mine = w.bets.remove(p.getUniqueId());
        long total = 0;
        if (mine != null)
            for (long v : mine.values())
                total += v;
        if (total > 0) {
            TokenWallet.give(p.getUniqueId(), total);
            p.sendMessage(msg("bets_cleared", "&7Retiraste tus apuestas: &e{amount}", "amount", units(total)));
        }
        if (w.bets.isEmpty() && w.state == State.BETTING)
            w.state = State.IDLE;
        updateDisplay(w);
    }

    private void refundAll(Wheel w) {
        if (w.state == State.RESULT) {
            w.bets.clear();
            return;
        }
        for (Map.Entry<UUID, Map<Long, Long>> e : w.bets.entrySet()) {
            long total = 0;
            for (long v : e.getValue().values())
                total += v;
            TokenWallet.give(e.getKey(), total);
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && total > 0)
                p.sendMessage(msg("refunded", "&7La ronda de la rueda se canceló. Se te devolvieron &e{amount}&7.",
                        "amount", units(total)));
        }
        w.bets.clear();
        w.state = State.IDLE;
    }

    // ------------------------------------------------------------------
    // Rueda física (/gdx station set wheel, mirando un faro)
    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("wheel", "rueda", "ruedafortuna");
    }

    @Override
    public String stationUsage() {
        return "wheel (mirando un faro)";
    }

    @Override
    public List<String> stationListLines() {
        int n = 0;
        for (Wheel w : wheels.values())
            if (w.physical)
                n++;
        return List.of("&8- &6Rueda&7: &f" + n + " &8(" + wheels.size() + " ruedas en total)");
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        Wheel w = byBlock(target);
        if (w == null)
            return false;
        removeWheel(w);
        player.sendMessage(msg("station_removed",
                "&aRueda &f{name}&a eliminada: se devolvieron las apuestas y se restauraron los bloques.",
                "name", w.name));
        return true;
    }

    @Override
    public void createStation(Player p, Block target, String[] args) {
        if (target.getType() != Material.BEACON) {
            p.sendMessage(msg("need_beacon", "&cMira un &ffaro&c (beacon). La rueda se construye alrededor de él."));
            return;
        }
        if (byBlock(target) != null) {
            p.sendMessage(msg("exists", "&cYa existe una rueda con ese nombre o en ese bloque."));
            return;
        }
        String name;
        if (args.length >= 1) {
            name = args[0].replaceAll("[^a-zA-Z0-9_\\-]", "");
            if (name.isBlank() || wheels.containsKey(name.toLowerCase(Locale.ROOT))) {
                p.sendMessage(msg("exists", "&cYa existe una rueda con ese nombre o en ese bloque."));
                return;
            }
        } else {
            int n = 1;
            while (wheels.containsKey("rueda" + n))
                n++;
            name = "rueda" + n;
        }

        BlockFace f = p.getFacing();
        Wheel w = new Wheel(name, target.getLocation());
        w.physical = true;
        w.rx = -f.getModZ();
        w.rz = f.getModX();
        layout(w);

        List<Location> all = new ArrayList<>(w.lamps);
        all.addAll(w.row);
        for (Location l : all) {
            Block b = l.getBlock();
            if (b.getType().isSolid() && b.getType() != Material.REDSTONE_LAMP && byBlock(b) == null) {
                p.sendMessage(msg("station_blocked",
                        "&cHay un bloque en el camino en &f{x} {y} {z}&c ({block}). Despeja alrededor del faro.",
                        "x", String.valueOf(b.getX()), "y", String.valueOf(b.getY()), "z", String.valueOf(b.getZ()),
                        "block", b.getType().name()));
                return;
            }
        }
        for (Location l : all) {
            Block b = l.getBlock();
            w.built.putIfAbsent(BlackjackTables.key(l), b.getBlockData().getAsString());
            b.setType(w.lamps.contains(l) ? Material.REDSTONE_LAMP : Material.WHITE_CONCRETE, false);
        }
        wheels.put(name.toLowerCase(Locale.ROOT), w);
        save();
        updateDisplay(w);
        p.sendMessage(msg("station_created",
                "&aRueda &f{name}&a construida. &7Los jugadores apuestan con click derecho al faro.", "name", name));
    }

    /** Posiciones de la pared: lámparas en el borde (en orden, girando) y 7 de concreto encima del faro. */
    private void layout(Wheel w) {
        w.lamps.clear();
        w.row.clear();
        for (int o = -4; o <= 4; o++)
            w.lamps.add(wallPos(w, o, 2));
        w.lamps.add(wallPos(w, 4, 1));
        for (int o = 4; o >= -4; o--)
            if (o != 0)
                w.lamps.add(wallPos(w, o, 0));
        w.lamps.add(wallPos(w, -4, 1));
        for (int k = -3; k <= 3; k++)
            w.row.add(wallPos(w, k, 1));
    }

    private static Location wallPos(Wheel w, int offset, int height) {
        return w.loc.clone().add(w.rx * offset, height, w.rz * offset);
    }

    /** Pone el concreto según la franja actual y anima las lámparas según el estado. */
    private void renderBlocks(Wheel w) {
        if (!w.physical || !loaded(w) || strip.isEmpty())
            return;
        int n = strip.size();
        for (int k = 0; k < w.row.size(); k++) {
            Material m = strip.get(Math.floorMod(w.pointer + k - 3, n)).icon();
            if (!m.isBlock())
                m = Material.WHITE_CONCRETE;
            Block b = w.row.get(k).getBlock();
            if (b.getType() != m)
                b.setType(m, false);
        }
        for (int i = 0; i < w.lamps.size(); i++) {
            boolean lit = switch (w.state) {
                case IDLE -> true;
                case BETTING -> Math.floorMod(i + w.lampPhase, 2) == 0;
                case SPINNING -> Math.floorMod(i - w.lampPhase, 4) == 0;
                case RESULT -> w.lampPhase % 2 == 0;
            };
            Block b = w.lamps.get(i).getBlock();
            if (b.getBlockData() instanceof Lightable lamp && lamp.isLit() != lit) {
                lamp.setLit(lit);
                b.setBlockData(lamp, false);
            }
        }
    }

    private void removeWheel(Wheel w) {
        refundAll(w);
        removeDisplay(w);
        List<Map.Entry<String, String>> entries = new ArrayList<>(w.built.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<String, String> e : entries) {
            Location l = BlackjackTables.parseKey(e.getKey());
            if (l == null)
                continue;
            try {
                l.getBlock().setBlockData(Bukkit.createBlockData(e.getValue()), false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[Rueda] No se pudo restaurar " + e.getKey() + ": " + e.getValue());
            }
        }
        w.built.clear();
        wheels.remove(w.name.toLowerCase(Locale.ROOT));
        save();
    }

    // ------------------------------------------------------------------
    // Holograma
    // ------------------------------------------------------------------

    private boolean loaded(Wheel w) {
        World world = w.loc.getWorld();
        return world != null && world.isChunkLoaded(w.loc.getBlockX() >> 4, w.loc.getBlockZ() >> 4);
    }

    private void updateDisplay(Wheel w) {
        if (!loaded(w))
            return;
        World world = w.loc.getWorld();
        renderBlocks(w);
        Location at = w.physical
                ? w.loc.clone().add(0.5, config().getDouble("physical.holo_height", 3.4), 0.5)
                : w.loc.clone().add(0.5, config().getDouble("holo_height", 1.0), 0.5);
        Entity e = w.displayId == null ? null : world.getEntity(w.displayId);
        TextDisplay td;
        if (e instanceof TextDisplay existing && existing.isValid()) {
            td = existing;
            if (td.getLocation().distanceSquared(at) > 0.01)
                td.teleport(at);
        } else {
            td = world.spawn(at, TextDisplay.class);
            td.setPersistent(false);
            td.setBillboard(Display.Billboard.CENTER);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            td.setShadowed(true);
            td.setLineWidth(300);
            w.displayId = td.getUniqueId();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("&6&l✦ RUEDA DE LA FORTUNA ✦\n");
        if (w.physical) {
            // Lo que paga cada color (la rueda en sí son los bloques)
            List<String> legend = new ArrayList<>();
            for (Segment s : segments)
                legend.add(s.color() + "■ x" + s.multiplier());
            sb.append(String.join("  ", legend)).append('\n');
        } else {
            sb.append(ticker(w)).append('\n');
        }
        switch (w.state) {
            case IDLE -> sb.append(w.physical ? "&7Click derecho al faro para apostar" : "&7Click derecho para apostar");
            case BETTING -> sb.append("&eGira en &f").append(w.countdown).append("s &8| &7Apostado: &e")
                    .append(units(totalBets(w)));
            case SPINNING -> sb.append("&e¡Girando!");
            case RESULT -> sb.append("&a¡Salió ").append(w.last == null ? "?" : w.last.label()).append("&a!");
        }
        td.setText(color(sb.toString()));
    }

    /** Franja de la rueda: 7 casillas con la del medio marcada. */
    private String ticker(Wheel w) {
        int n = strip.size();
        StringBuilder sb = new StringBuilder();
        for (int k = -3; k <= 3; k++) {
            Segment s = strip.get(Math.floorMod(w.pointer + k, n));
            if (k == 0) {
                sb.append("&f&l▶").append(s.label()).append("&f&l◀");
            } else {
                sb.append("&8[").append(s.label()).append("&8]");
            }
            if (k < 3)
                sb.append(' ');
        }
        return sb.toString();
    }

    private long totalBets(Wheel w) {
        long t = 0;
        for (Map<Long, Long> m : w.bets.values())
            for (long v : m.values())
                t += v;
        return t;
    }

    private void removeDisplay(Wheel w) {
        World world = w.loc.getWorld();
        if (world != null && w.displayId != null) {
            Entity e = world.getEntity(w.displayId);
            if (e != null)
                e.remove();
        }
        w.displayId = null;
    }

    /** Mensaje para los que apostaron en esta rueda (nadie más). */
    private void announce(Wheel w, String text) {
        for (UUID id : w.bets.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                p.sendMessage(text);
        }
    }

    private void playNear(Wheel w, Sound s, float vol, float pitch) {
        if (loaded(w))
            w.loc.getWorld().playSound(w.loc, s, vol, pitch);
    }

    private Wheel byBlock(Block b) {
        if (b == null)
            return null;
        String key = BlackjackTables.key(b.getLocation());
        for (Wheel w : wheels.values())
            if (key != null && (key.equals(BlackjackTables.key(w.loc)) || w.built.containsKey(key)))
                return w;
        return null;
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Wheel w : wheels.values()) {
            String b = "wheels." + w.name + ".";
            data.set(b + "location", BlackjackTables.key(w.loc));
            if (w.physical) {
                data.set(b + "physical", true);
                data.set(b + "rx", w.rx);
                data.set(b + "rz", w.rz);
                List<String> built = new ArrayList<>();
                for (Map.Entry<String, String> e : w.built.entrySet())
                    built.add(e.getKey() + "|" + e.getValue());
                data.set(b + "built", built);
            }
        }
        saveData(data);
    }

    // ------------------------------------------------------------------
    // Menú de apuestas
    // ------------------------------------------------------------------

    private void openBetMenu(Player p, Wheel w) {
        BetHolder h = new BetHolder(w.name);
        Inventory inv = Bukkit.createInventory(h, 27, color("&6&lRueda &8- &e" + w.name));
        for (int i = 0; i < 27; i++)
            inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        int total = strip.size();
        Map<Long, Long> mine = w.bets.getOrDefault(p.getUniqueId(), Map.of());
        int[] slots = { 10, 11, 12, 14, 15, 16, 13 };
        for (int i = 0; i < segments.size() && i < slots.length; i++) {
            Segment s = segments.get(i);
            double chance = 100.0 * s.count() / total;
            List<String> lore = new ArrayList<>();
            lore.add("&7Paga &f" + s.multiplier() + " a 1");
            lore.add("&7Casillas: &f" + s.count() + "/" + total + " &8(" + String.format(Locale.ROOT, "%.1f", chance) + "%)");
            long my = mine.getOrDefault(s.multiplier(), 0L);
            if (my > 0)
                lore.add("&aTu apuesta aquí: &e" + units(my));
            lore.add("");
            lore.add("&eClick para apostar");
            inv.setItem(slots[i], item(s.icon(), s.label(), lore));
            h.slotToMult.put(slots[i], s.multiplier());
        }
        inv.setItem(22, item(Material.SUNFLOWER, "&7Tus fichas: &e" + units(TokenWallet.balance(p)), null));
        inv.setItem(26, item(Material.REDSTONE, "&cRetirar mis apuestas", List.of("&7Solo antes de que gire")));
        p.openInventory(inv);
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
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK)
                return;
            Wheel w = byBlock(e.getClickedBlock());
            if (w == null)
                return;
            e.setCancelled(true); // también la otra mano: que no se abra el menú del faro
            if (e.getHand() == EquipmentSlot.HAND)
                openBetMenu(e.getPlayer(), w);
        }

        @EventHandler
        public void onBreak(BlockBreakEvent e) {
            if (byBlock(e.getBlock()) == null)
                return;
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("cannot_break", "&cNo puedes romper la rueda. Un admin la quita con &f/gdx station remove&c."));
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof BetHolder h))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            Wheel w = wheels.get(h.wheel.toLowerCase(Locale.ROOT));
            if (w == null) {
                p.closeInventory();
                return;
            }
            int slot = e.getRawSlot();
            if (slot == 26) {
                clearBets(p, w);
                openBetMenu(p, w);
                return;
            }
            Long mult = h.slotToMult.get(slot);
            if (mult == null)
                return;
            Segment s = segmentFor(mult);
            long min = Math.max(1, config().getLong("min_bet", 10));
            AmountPickerMenu.open(p, "&6&lRueda &8- " + (s == null ? "x" + mult : s.label()), min,
                    Math.max(0, config().getLong("max_bet", 0)), min,
                    List.of("&7Paga &f" + mult + " a 1"),
                    amount -> {
                        placeBet(p, w, mult, amount);
                        openBetMenu(p, w);
                    }, () -> openBetMenu(p, w));
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof BetHolder)
                e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Autocompletar (TAB)
    // ------------------------------------------------------------------

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (!isAdmin(player))
            return List.of();
        if (args.length == 1)
            return List.of("create", "remove", "list");
        String a = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (a.equals("create") || a.equals("crear")))
            return List.of("<name>");
        if (args.length == 2 && List.of("remove", "borrar", "delete").contains(a)) {
            List<String> names = new ArrayList<>();
            for (Wheel w : wheels.values())
                names.add(w.name);
            return names;
        }
        return List.of();
    }

    @Override
    public List<String> stationTabComplete(Player player, String[] args) {
        return args.length == 1 ? List.of("[name]") : List.of();
    }
}
