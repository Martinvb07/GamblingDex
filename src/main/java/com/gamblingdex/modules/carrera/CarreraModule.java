package com.gamblingdex.modules.carrera;

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
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.security.SecureRandom;
import java.util.*;

/**
 * Carrera de caballos con apuestas mutuas. Una pista son N carriles paralelos
 * de L bloques. Caballos reales corren (teletransportados cada tick) con
 * velocidades aleatorias y arranques; todos tienen la misma probabilidad.
 *
 * Pista automática: /gdx station set race &lt;distancia&gt; &lt;carriles&gt; mirando un
 * bloque. Ese bloque queda como mesa de apuestas y detrás se construye la pista
 * (vallas, puertas de salida y meta). Los bloques originales se guardan y se
 * restauran al quitarla con /gdx station remove.
 */
public class CarreraModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_LANES = 8;
    private static final int[] MENU_SLOTS = { 10, 11, 12, 13, 14, 15, 16, 22 };
    private static final Material[] HORSE_ICONS = { Material.RED_WOOL, Material.BLUE_WOOL, Material.LIME_WOOL,
            Material.YELLOW_WOOL, Material.PURPLE_WOOL, Material.ORANGE_WOOL, Material.CYAN_WOOL,
            Material.PINK_WOOL };
    private static final String[] HORSE_COLORS = { "&c", "&9", "&a", "&e", "&5", "&6", "&3", "&d" };

    private enum State {
        IDLE,
        BETTING,
        RACING,
        RESULT
    }

    private static final class Track {
        final String name;
        final Location start;
        final int dx, dz;
        final int lanes;
        final int length;
        State state = State.IDLE;
        int countdown;
        final Map<UUID, Map<Integer, Long>> bets = new LinkedHashMap<>();
        final List<UUID> horses = new ArrayList<>();
        double[] progress;
        double[] base;
        int[] burst;
        int winner = -1;
        BossBar bar;
        /** Separación entre carriles; 0 = la de la config (pistas viejas). */
        double spacing;
        /** Bloque de la mesa (solo pistas construidas con /gdx station set race). */
        Location station;
        /** Bloques que cambió la construcción: clave → BlockData original. */
        final Map<String, String> built = new LinkedHashMap<>();
        final List<String> gates = new ArrayList<>();
        UUID holo;
        UUID finishHolo;

        Track(String name, Location start, int dx, int dz, int lanes, int length) {
            this.name = name;
            this.start = start;
            this.dx = dx;
            this.dz = dz;
            this.lanes = lanes;
            this.length = length;
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        final String track;

        MenuHolder(String track) {
            this.track = track;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Map<String, Track> tracks = new LinkedHashMap<>();
    /** true mientras el plugin crea sus caballos (para que otros plugins no los bloqueen). */
    private boolean spawningHorses;
    private boolean warnedSpawnBlocked;
    private int tickCount;

    @Override
    public String id() {
        return "carrera";
    }

    @Override
    public String displayName() {
        return "Carrera de caballos";
    }

    @Override
    public List<String> aliases() {
        return List.of("race", "carreras", "horses", "hipodromo");
    }

    @Override
    public void enable() {
        YamlConfiguration d = loadData();
        ConfigurationSection sec = d.getConfigurationSection("tracks");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                Location l = BlackjackTables.parseKey(sec.getString(name + ".start"));
                if (l == null)
                    continue;
                Track t = new Track(name, l,
                        sec.getInt(name + ".dx"), sec.getInt(name + ".dz"),
                        sec.getInt(name + ".lanes", 6), sec.getInt(name + ".length", 30));
                t.spacing = sec.getDouble(name + ".spacing", 0);
                t.station = BlackjackTables.parseKey(sec.getString(name + ".station"));
                for (String b : sec.getStringList(name + ".built")) {
                    int bar = b.indexOf('|');
                    if (bar > 0)
                        t.built.put(b.substring(0, bar), b.substring(bar + 1));
                }
                t.gates.addAll(sec.getStringList(name + ".gates"));
                tracks.put(name.toLowerCase(Locale.ROOT), t);
            }
        }
        listen(new Events());
        runTimer(this::tick, 1L, 1L);
    }

    @Override
    public void disable() {
        for (Track t : tracks.values()) {
            if (t.state == State.BETTING || t.state == State.RACING)
                refundAll(t);
            removeHorses(t);
            if (t.bar != null)
                t.bar.removeAll();
            if (t.state == State.RACING || t.state == State.RESULT)
                setGates(t, false);
            removeHolos(t);
        }
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>(List.of(
                "&6&lCarrera de caballos",
                "&8• &7Apostar: &fclick derecho&7 a la mesa de la pista"));
        if (admin) {
            l.add("&8• &e/gdx station set race <distance> <lanes> &7- Construir pista (mirando un bloque)");
            l.add("&8• &e/gdx station remove &7- Quitar la pista (mirando su mesa; restaura los bloques)");
            l.add("&8• &e/gdx race create <name> [lanes] [length] &7- Pista manual, en la salida mirando a la meta");
            l.add("&8• &e/gdx race remove <name>&7|&elist&7|&estart <name>");
        }
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (isAdmin(player)) {
            switch (a) {
                case "crear", "create" -> {
                    create(player, args);
                    return true;
                }
                case "borrar", "remove", "delete" -> {
                    Track t = args.length >= 2 ? tracks.get(args[1].toLowerCase(Locale.ROOT)) : null;
                    if (t == null) {
                        notFound(player);
                        return true;
                    }
                    deleteTrack(t);
                    player.sendMessage(msg("removed", "&aPista eliminada (se devolvieron las apuestas)."));
                    return true;
                }
                case "lista", "list" -> {
                    player.sendMessage(color("&7Pistas: &f" + tracks.size()));
                    for (Track t : tracks.values())
                        player.sendMessage(color("&8- &f" + t.name + " &7" + t.lanes + " carriles, " + t.length
                                + " bloques, " + t.state));
                    return true;
                }
                case "iniciar", "start" -> {
                    Track t = args.length >= 2 ? tracks.get(args[1].toLowerCase(Locale.ROOT)) : null;
                    if (t == null) {
                        notFound(player);
                    } else if (t.state == State.IDLE) {
                        startBetting(t);
                    }
                    return true;
                }
                default -> {
                }
            }
        }
        // Se apuesta solo en la mesa de la pista: no hay comandos de jugador.
        player.sendMessage(msg("use_station", "&7Para apostar, haz &fclick derecho&7 a la mesa de la pista."));
        if (isAdmin(player))
            player.sendMessage(msg("usage_admin", "&cUso: /gdx race <create|remove|list|start> [name] [lanes] [length]"));
        return true;
    }

    private void notFound(Player p) {
        List<String> names = new ArrayList<>();
        for (Track t : tracks.values())
            names.add(t.name);
        p.sendMessage(msg("not_found", "&cNo existe esa pista. Pistas: &f{tracks}", "tracks", String.join(", ", names)));
    }

    private void create(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(msg("usage_admin", "&cUso: /gdx race <create|remove|list|start> [name] [lanes] [length]"));
            return;
        }
        String name = args[1].replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (name.isBlank() || tracks.containsKey(name.toLowerCase(Locale.ROOT))) {
            p.sendMessage(msg("exists", "&cYa existe una pista con ese nombre."));
            return;
        }
        int lanes = config().getInt("default_lanes", 6);
        int length = config().getInt("default_length", 30);
        try {
            if (args.length >= 3)
                lanes = Integer.parseInt(args[2]);
            if (args.length >= 4)
                length = Integer.parseInt(args[3]);
        } catch (NumberFormatException ignored) {
        }
        lanes = Math.max(2, Math.min(MAX_LANES, lanes));
        length = Math.max(10, Math.min(200, length));
        BlockFace f = p.getFacing();
        Location start = p.getLocation().getBlock().getLocation();
        Track t = new Track(name, start, f.getModX(), f.getModZ(), lanes, length);
        tracks.put(name.toLowerCase(Locale.ROOT), t);
        save();
        p.sendMessage(msg("created", "&aPista &f{name}&a creada: &f{lanes}&a carriles de &f{length}&a bloques.",
                "name", name, "lanes", String.valueOf(lanes), "length", String.valueOf(length)));
    }

    private void save() {
        YamlConfiguration d = new YamlConfiguration();
        for (Track t : tracks.values()) {
            String b = "tracks." + t.name + ".";
            d.set(b + "start", BlackjackTables.key(t.start));
            d.set(b + "dx", t.dx);
            d.set(b + "dz", t.dz);
            d.set(b + "lanes", t.lanes);
            d.set(b + "length", t.length);
            if (t.spacing > 0)
                d.set(b + "spacing", t.spacing);
            if (t.station != null) {
                d.set(b + "station", BlackjackTables.key(t.station));
                List<String> built = new ArrayList<>();
                for (Map.Entry<String, String> e : t.built.entrySet())
                    built.add(e.getKey() + "|" + e.getValue());
                d.set(b + "built", built);
                d.set(b + "gates", t.gates);
            }
        }
        saveData(d);
    }

    // ------------------------------------------------------------------
    // Pista automática (/gdx station set race <distance> <lanes>)
    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("race", "carrera", "racehorse", "caballos", "hipodromo");
    }

    @Override
    public String stationUsage() {
        return "race <distance> <lanes>";
    }

    @Override
    public List<String> stationListLines() {
        int n = 0;
        for (Track t : tracks.values())
            if (t.station != null)
                n++;
        return List.of("&8- &6Carrera&7: &f" + n + " &8(" + tracks.size() + " pistas en total)");
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        String k = BlackjackTables.key(target.getLocation());
        for (Track t : new ArrayList<>(tracks.values())) {
            if (t.station != null && k.equals(BlackjackTables.key(t.station))) {
                deleteTrack(t);
                player.sendMessage(msg("station_removed",
                        "&aPista &f{track}&a eliminada: se devolvieron las apuestas y se restauraron los bloques.",
                        "track", t.name));
                return true;
            }
        }
        return false;
    }

    /**
     * Construye la pista detrás del bloque mirado, en la dirección en que mira
     * el admin. Filas desde la mesa (d): 1 valla trasera, 2 salida de los
     * caballos, 3 puertas, 2+largo meta (piso a cuadros), 3+largo valla final.
     * Carriles de 1 bloque separados por líneas de vallas.
     */
    @Override
    public void createStation(Player p, Block target, String[] args) {
        int length = config().getInt("default_length", 30);
        int lanes = config().getInt("default_lanes", 6);
        try {
            if (args.length >= 1)
                length = Integer.parseInt(args[0]);
            if (args.length >= 2)
                lanes = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            p.sendMessage(msg("station_usage", "&cUso: /gdx station set race <distance> <lanes> [name]"));
            return;
        }
        length = Math.max(10, Math.min(200, length));
        lanes = Math.max(2, Math.min(MAX_LANES, lanes));

        String name;
        if (args.length >= 3) {
            name = args[2].replaceAll("[^a-zA-Z0-9_\\-]", "");
            if (name.isBlank() || tracks.containsKey(name.toLowerCase(Locale.ROOT))) {
                p.sendMessage(msg("exists", "&cYa existe una pista con ese nombre."));
                return;
            }
        } else {
            int n = 1;
            while (tracks.containsKey("pista" + n))
                n++;
            name = "pista" + n;
        }
        for (Track t : tracks.values()) {
            if (t.station != null && BlackjackTables.key(t.station).equals(BlackjackTables.key(target.getLocation()))) {
                p.sendMessage(msg("station_exists", "&eEse bloque ya es la mesa de la pista &f{track}&e.", "track", t.name));
                return;
            }
        }

        BlockFace f = p.getFacing();
        int dx = f.getModX(), dz = f.getModZ();
        int rx = -dz, rz = dx; // derecha del sentido de carrera
        World w = target.getWorld();
        int y = p.getLocation().getBlockY(); // nivel del piso donde corren los caballos
        int bx = target.getX(), bz = target.getZ();
        int last = length + 3;

        Material fenceMat = material("station.fence_material", Material.OAK_FENCE);
        Material gateMat = material("station.gate_material", Material.OAK_FENCE_GATE);
        String floorName = config().getString("station.floor_material", "COARSE_DIRT");
        Material floorMat = floorName == null || floorName.isBlank() ? null : Material.matchMaterial(floorName);
        List<String> finishNames = config().getStringList("station.finish_materials");
        Material finishA = finishNames.size() > 0 ? Material.matchMaterial(finishNames.get(0)) : null;
        Material finishB = finishNames.size() > 1 ? Material.matchMaterial(finishNames.get(1)) : null;
        if (finishA == null)
            finishA = Material.WHITE_CONCRETE;
        if (finishB == null)
            finishB = Material.BLACK_CONCRETE;
        if (!Tag.FENCES.isTagged(fenceMat))
            fenceMat = Material.OAK_FENCE;
        if (!Tag.FENCE_GATES.isTagged(gateMat))
            gateMat = Material.OAK_FENCE_GATE;
        if (floorMat != null && !floorMat.isBlock())
            floorMat = null;

        // Plan: bloque → lo que va ahí. Primero se revisa que no haya nada sólido en el camino.
        Map<Block, BlockData> plan = new LinkedHashMap<>();
        List<Block> fences = new ArrayList<>();
        List<Block> gates = new ArrayList<>();
        Block blocked = null;
        for (int d = 1; d <= last; d++) {
            for (int o = -lanes; o <= lanes; o++) {
                boolean fenceLine = Math.floorMod(o + lanes, 2) == 0;
                int x = bx + dx * d + rx * o, z = bz + dz * d + rz * o;
                for (int yy = y; yy <= y + 2; yy++) {
                    Block b = w.getBlockAt(x, yy, z);
                    BlockData want;
                    if (yy == y && (fenceLine || d == 1 || d == last)) {
                        want = fenceMat.createBlockData();
                        fences.add(b);
                    } else if (yy == y && d == 3) {
                        Gate g = (Gate) gateMat.createBlockData();
                        g.setFacing(f);
                        g.setOpen(false);
                        want = g;
                        gates.add(b);
                    } else {
                        want = Material.AIR.createBlockData();
                    }
                    if (b.getType().isSolid() && b.getType() != want.getMaterial() && blocked == null)
                        blocked = b;
                    plan.put(b, want);
                }
                // Piso
                Block floor = w.getBlockAt(x, y - 1, z);
                if (d == length + 2) {
                    plan.put(floor, (Math.floorMod(o, 2) == 0 ? finishA : finishB).createBlockData());
                } else if (!fenceLine && floorMat != null) {
                    plan.put(floor, floorMat.createBlockData());
                }
            }
        }
        if (blocked != null) {
            p.sendMessage(msg("station_blocked",
                    "&cHay un bloque en el camino en &f{x} {y} {z}&c ({block}). Despeja el área o mira hacia otro lado.",
                    "x", String.valueOf(blocked.getX()), "y", String.valueOf(blocked.getY()),
                    "z", String.valueOf(blocked.getZ()), "block", blocked.getType().name()));
            return;
        }

        Location start = new Location(w, bx + dx * 2 - rx * (lanes - 1), y, bz + dz * 2 - rz * (lanes - 1));
        Track t = new Track(name, start, dx, dz, lanes, length);
        t.spacing = 2;
        t.station = target.getLocation();
        for (Map.Entry<Block, BlockData> e : plan.entrySet()) {
            Block b = e.getKey();
            if (b.getBlockData().matches(e.getValue()))
                continue;
            t.built.putIfAbsent(BlackjackTables.key(b.getLocation()), b.getBlockData().getAsString());
            b.setBlockData(e.getValue(), false);
        }
        // Conexiones de las vallas (sin física no se calculan solas)
        for (Block b : fences)
            connectFence(b);
        for (Block b : gates)
            t.gates.add(BlackjackTables.key(b.getLocation()));

        tracks.put(name.toLowerCase(Locale.ROOT), t);
        save();
        spawnHorses(t);
        updateHolos(t);
        p.sendMessage(msg("station_created",
                "&aPista &f{track}&a construida: &f{lanes}&a carriles de &f{length}&a bloques. &7Click derecho a la mesa para apostar.",
                "track", name, "lanes", String.valueOf(lanes), "length", String.valueOf(length)));
    }

    private Material material(String path, Material def) {
        Material m = Material.matchMaterial(config().getString(path, def.name()));
        return m == null ? def : m;
    }

    private static void connectFence(Block b) {
        if (!(b.getBlockData() instanceof Fence fd))
            return;
        for (BlockFace face : fd.getAllowedFaces())
            fd.setFace(face, fenceConnects(b, face));
        b.setBlockData(fd, false);
    }

    private static boolean fenceConnects(Block from, BlockFace face) {
        Block n = from.getRelative(face);
        Material m = n.getType();
        if (Tag.FENCES.isTagged(m))
            return true;
        if (n.getBlockData() instanceof Gate g) {
            // La puerta se une por sus costados, no por el frente.
            return g.getFacing() != face && g.getFacing() != face.getOppositeFace();
        }
        return m.isOccluding();
    }

    private void setGates(Track t, boolean open) {
        boolean any = false;
        for (String k : t.gates) {
            Location l = BlackjackTables.parseKey(k);
            if (l == null)
                continue;
            Block b = l.getBlock();
            if (b.getBlockData() instanceof Gate g && g.isOpen() != open) {
                g.setOpen(open);
                b.setBlockData(g, false);
                any = true;
            }
        }
        if (any && loaded(t))
            playNear(t, open ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_FENCE_GATE_CLOSE, 1f, 1f);
    }

    /** Devuelve cada bloque cambiado a como estaba antes de construir la pista. */
    private void restoreBlocks(Track t) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(t.built.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<String, String> e : entries) {
            Location l = BlackjackTables.parseKey(e.getKey());
            if (l == null)
                continue;
            try {
                l.getBlock().setBlockData(Bukkit.createBlockData(e.getValue()), false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[Carrera] No se pudo restaurar " + e.getKey() + ": " + e.getValue());
            }
        }
        t.built.clear();
        t.gates.clear();
    }

    private void deleteTrack(Track t) {
        refundAll(t);
        removeHorses(t);
        if (t.bar != null)
            t.bar.removeAll();
        removeHolos(t);
        restoreBlocks(t);
        tracks.remove(t.name.toLowerCase(Locale.ROOT));
        save();
    }

    private boolean horsesMissing(Track t) {
        if (t.horses.size() != t.lanes)
            return true;
        World w = t.start.getWorld();
        for (UUID id : t.horses) {
            Entity e = w == null ? null : w.getEntity(id);
            if (e == null || !e.isValid())
                return true;
        }
        return false;
    }

    private void updateHolos(Track t) {
        if (t.station == null || !loaded(t))
            return;
        StringBuilder sb = new StringBuilder("&6&l✦ CARRERA ✦ &7").append(t.name).append("\n");
        switch (t.state) {
            case IDLE -> sb.append("&7Apuesta para abrir la carrera");
            case BETTING -> sb.append("&eSale en &f").append(t.countdown).append("s &8| &7Pozo: &e").append(units(pot(t)));
            case RACING -> sb.append("&a¡En carrera! &8| &7Pozo: &e").append(units(pot(t)));
            case RESULT -> sb.append("&6Ganó el #").append(t.winner + 1).append(" ").append(horseName(t.winner));
        }
        sb.append("\n&7Click derecho para apostar");
        t.holo = textDisplay(t.holo, t.station.clone().add(0.5, config().getDouble("station.holo_height", 1.2), 0.5),
                sb.toString());
        t.finishHolo = textDisplay(t.finishHolo, lanePos(t, 0, t.length).add(
                (-t.dz) * (t.lanes - 1) * (t.spacing / 2.0), 2.5, t.dx * (t.lanes - 1) * (t.spacing / 2.0)),
                "&f&l» META «");
    }

    /** Crea o mueve un holograma y le pone el texto. Devuelve su UUID. */
    private UUID textDisplay(UUID id, Location at, String text) {
        World w = at.getWorld();
        Entity e = id == null ? null : w.getEntity(id);
        TextDisplay td;
        if (e instanceof TextDisplay existing && existing.isValid()) {
            td = existing;
            if (td.getLocation().distanceSquared(at) > 0.01)
                td.teleport(at);
        } else {
            td = w.spawn(at, TextDisplay.class);
            td.setPersistent(false);
            td.setBillboard(Display.Billboard.CENTER);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            td.setShadowed(true);
            td.setLineWidth(300);
        }
        td.setText(color(text));
        return td.getUniqueId();
    }

    private void removeHolos(Track t) {
        World w = t.start.getWorld();
        if (w == null)
            return;
        for (UUID id : new UUID[] { t.holo, t.finishHolo }) {
            Entity e = id == null ? null : w.getEntity(id);
            if (e != null)
                e.remove();
        }
        t.holo = null;
        t.finishHolo = null;
    }

    // ------------------------------------------------------------------
    // Carrera
    // ------------------------------------------------------------------

    private String horseName(int i) {
        List<String> names = config().getStringList("horse_names");
        String n = i < names.size() ? names.get(i) : "Caballo " + (i + 1);
        return HORSE_COLORS[i % HORSE_COLORS.length] + n;
    }

    /** Posición en el carril {@code lane} a {@code dist} bloques de la salida. */
    private Location lanePos(Track t, int lane, double dist) {
        double spacing = t.spacing > 0 ? t.spacing : config().getDouble("lane_spacing", 2.0);
        // Derecha del sentido de carrera: (-dz, dx)
        double rx = -t.dz, rz = t.dx;
        Location l = t.start.clone().add(0.5 + rx * lane * spacing + t.dx * dist, 0,
                0.5 + rz * lane * spacing + t.dz * dist);
        float yaw = (float) Math.toDegrees(Math.atan2(-t.dx, t.dz));
        l.setYaw(yaw);
        l.setPitch(0);
        return l;
    }

    private boolean loaded(Track t) {
        World w = t.start.getWorld();
        return w != null && w.isChunkLoaded(t.start.getBlockX() >> 4, t.start.getBlockZ() >> 4);
    }

    private void startBetting(Track t) {
        t.state = State.BETTING;
        t.countdown = Math.max(10, config().getInt("bet_window_seconds", 40));
        t.progress = new double[t.lanes];
        t.winner = -1;
        spawnHorses(t);
        announce(t, msg("countdown", "&6&lCarrera &8» &7¡Apuestas abiertas en &f{track}&7! Sale en &f{seconds}s",
                "track", t.name, "seconds", String.valueOf(t.countdown)));
    }

    private void spawnHorses(Track t) {
        removeHorses(t);
        if (!loaded(t))
            return;
        Horse.Color[] colors = Horse.Color.values();
        for (int i = 0; i < t.lanes; i++) {
            Location l = lanePos(t, i, 0);
            final int lane = i;
            Horse h;
            spawningHorses = true;
            try {
                java.util.function.Consumer<Horse> setup = horse -> {
                    horse.setPersistent(false);
                    horse.setAI(false);
                    horse.setInvulnerable(true);
                    horse.setAdult();
                    horse.setTamed(true);
                    horse.setColor(colors[lane % colors.length]);
                    horse.setStyle(Horse.Style.values()[RNG.nextInt(Horse.Style.values().length)]);
                    horse.setCustomName(color("&f#" + (lane + 1) + " " + horseName(lane)));
                    horse.setCustomNameVisible(true);
                };
                h = l.getWorld().spawn(l, Horse.class, setup);
            } finally {
                spawningHorses = false;
            }
            if (h.isValid()) {
                t.horses.add(h.getUniqueId());
            } else if (!warnedSpawnBlocked) {
                warnedSpawnBlocked = true;
                plugin.getLogger().warning("[Carrera] Otro plugin no deja aparecer los caballos de la pista " + t.name
                        + " (¿WorldGuard o un plugin de protección con mobs desactivados en esa zona?).");
            }
        }
    }

    private void removeHorses(Track t) {
        World w = t.start.getWorld();
        if (w != null) {
            for (UUID id : t.horses) {
                Entity e = w.getEntity(id);
                if (e != null)
                    e.remove();
            }
        }
        t.horses.clear();
    }

    private void startRace(Track t) {
        if (t.bets.isEmpty()) {
            if (t.station == null)
                removeHorses(t);
            t.state = State.IDLE;
            return;
        }
        if (t.horses.size() != t.lanes)
            spawnHorses(t);
        t.state = State.RACING;
        setGates(t, true);
        t.base = new double[t.lanes];
        t.burst = new int[t.lanes];
        double speed = Math.max(0.2, config().getDouble("race_speed", 1.0));
        for (int i = 0; i < t.lanes; i++)
            t.base[i] = (0.085 + RNG.nextDouble() * 0.03) * speed;
        announce(t, msg("start", "&6&lCarrera &8» &a¡Y ARRANCAN! &7Pozo: &e{pot}", "pot", units(pot(t))));
        playNear(t, Sound.ENTITY_HORSE_GALLOP, 1f, 1f);
        if (t.bar == null)
            t.bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
    }

    private void raceTick(Track t) {
        World w = t.start.getWorld();
        if (w == null)
            return;
        double speedMult = Math.max(0.2, config().getDouble("race_speed", 1.0));
        int leader = 0;
        for (int i = 0; i < t.lanes; i++) {
            if (t.burst[i] > 0) {
                t.burst[i]--;
            } else if (RNG.nextDouble() < 0.015) {
                t.burst[i] = 15 + RNG.nextInt(20); // arranque
            }
            double v = t.base[i] * (0.7 + RNG.nextDouble() * 0.6);
            if (t.burst[i] > 0)
                v += 0.04 * speedMult;
            t.progress[i] = Math.min(t.length, t.progress[i] + v);
            if (t.progress[i] > t.progress[leader])
                leader = i;
            if (i < t.horses.size()) {
                Entity e = w.getEntity(t.horses.get(i));
                if (e != null)
                    e.teleport(lanePos(t, i, t.progress[i]));
            }
        }
        if (tickCount % 6 == 0)
            playNear(t, Sound.ENTITY_HORSE_GALLOP, 0.6f, 1f);

        updateRaceScreens(t, leader);

        if (t.progress[leader] >= t.length) {
            // Si varios cruzan en el mismo tick, gana el que más avanzó (ya es "leader").
            finishRace(t, leader);
        }
    }

    private void finishRace(Track t, int winner) {
        t.state = State.RESULT;
        t.winner = winner;
        t.countdown = Math.max(3, config().getInt("result_seconds", 10));

        long pot = pot(t);
        long winnerPool = pool(t, winner);
        String odds = fixedOdds() ? fmt(fixedMultiplier(t)) : winnerPool > 0 ? fmt(pot / (double) winnerPool) : "-";
        announce(t, msg("winner", "&6&lCarrera &8» &a&l¡Ganó el #{horse} {name}! &7Paga &fx{odds}",
                "horse", String.valueOf(winner + 1), "name", color(horseName(winner)), "odds", odds));
        playNear(t, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        if (fixedOdds()) {
            payFixed(t, winner);
        } else if (winnerPool <= 0) {
            announce(t, msg("no_winner_bets", "&6&lCarrera &8» &7Nadie apostó al ganador: se devuelven las apuestas."));
            refundAll(t);
        } else {
            for (Map.Entry<UUID, Map<Integer, Long>> e : t.bets.entrySet()) {
                long staked = 0;
                for (long v : e.getValue().values())
                    staked += v;
                long onWinner = e.getValue().getOrDefault(winner, 0L);
                Player p = Bukkit.getPlayer(e.getKey());
                GamblingDexPlugin.recordStats(e.getKey(), "carrera", staked,
                        onWinner > 0 ? (long) Math.floor(onWinner * (double) pot / winnerPool) : 0L);
                if (onWinner > 0) {
                    long won = (long) Math.floor(onWinner * (double) pot / winnerPool);
                    TokenWallet.give(e.getKey(), won);
                    if (p != null) {
                        p.sendMessage(msg("you_won", "&a&l¡GANASTE! &7Tu caballo #{horse} ganó → &e+{amount}",
                                "horse", String.valueOf(winner + 1), "amount", units(won)));
                        p.sendTitle(color("&a&l¡GANASTE!"), color("&e+" + units(won)), 5, 60, 15);
                    }
                } else if (p != null) {
                    p.sendMessage(msg("you_lost", "&7Ganó el #{horse}. Perdiste &e{amount}&7.",
                            "horse", String.valueOf(winner + 1), "amount", units(staked)));
                }
            }
            t.bets.clear();
        }
        plugin.getLogger().info("[Carrera] " + t.name + " ganó #" + (winner + 1) + " | pozo " + pot);
        if (t.bar != null)
            t.bar.setTitle(color("&a&l¡Ganó el #" + (winner + 1) + " " + horseName(winner) + "&a&l!"));
    }

    /** Cuota fija: el que acertó cobra apuesta x multiplicador; lo demás se pierde. */
    private void payFixed(Track t, int winner) {
        double mult = fixedMultiplier(t);
        List<String> winners = new ArrayList<>();
        for (Map.Entry<UUID, Map<Integer, Long>> e : t.bets.entrySet()) {
            long staked = 0;
            for (long v : e.getValue().values())
                staked += v;
            long onWinner = e.getValue().getOrDefault(winner, 0L);
            Player p = Bukkit.getPlayer(e.getKey());
            GamblingDexPlugin.recordStats(e.getKey(), "carrera", staked,
                    onWinner > 0 ? (long) Math.floor(onWinner * mult) : 0L);
            if (onWinner > 0) {
                long won = (long) Math.floor(onWinner * mult);
                TokenWallet.give(e.getKey(), won);
                String n = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("?");
                winners.add("&f" + n + " &e+" + units(won));
                if (p != null) {
                    p.sendMessage(msg("you_won", "&a&l¡GANASTE! &7Tu caballo #{horse} ganó → &e+{amount}",
                            "horse", String.valueOf(winner + 1), "amount", units(won)));
                    p.sendTitle(color("&a&l¡GANASTE!"), color("&e+" + units(won)), 5, 60, 15);
                }
            } else if (p != null) {
                p.sendMessage(msg("you_lost", "&7Ganó el #{horse}. Perdiste &e{amount}&7.",
                        "horse", String.valueOf(winner + 1), "amount", units(staked)));
            }
        }
        announce(t, msg("summary", "&6&lCarrera &8» &7Ganadores: {players}", "players",
                winners.isEmpty() ? color("&8nadie") : color(String.join("&7, ", winners))));
        t.bets.clear();
    }

    private void tick() {
        tickCount++;
        for (Track t : tracks.values()) {
            switch (t.state) {
                case BETTING -> {
                    if (tickCount % 20 == 0) {
                        t.countdown--;
                        if (t.countdown == 10)
                            announce(t, msg("countdown", "&6&lCarrera &8» &7Sale en &f{seconds}s",
                                    "track", t.name, "seconds", "10"));
                        if (t.countdown <= 0)
                            startRace(t);
                        else if (horsesMissing(t))
                            spawnHorses(t); // el chunk no estaba cargado
                    }
                }
                case RACING -> raceTick(t);
                case RESULT -> {
                    if (tickCount % 20 == 0 && --t.countdown <= 0) {
                        removeHorses(t);
                        t.state = State.IDLE;
                        if (t.bar != null)
                            t.bar.removeAll();
                        if (t.station != null) {
                            setGates(t, false);
                            spawnHorses(t); // de vuelta a la salida
                        }
                    }
                }
                default -> {
                }
            }
            if (t.station != null && tickCount % 20 == 0 && loaded(t)) {
                // Caballos esperando en la salida (se pierden si el chunk se descarga)
                if (t.state == State.IDLE && horsesMissing(t))
                    spawnHorses(t);
                updateHolos(t);
            }
        }
        if (tickCount % 10 == 0)
            refreshMenus();
    }

    private void updateRaceScreens(Track t, int leader) {
        if (t.bar == null)
            return;
        double pct = t.progress[leader] / t.length;
        t.bar.setTitle(color("&6&lCARRERA &8| &7Líder: &f#" + (leader + 1) + " " + horseName(leader)
                + " &8| &f" + Math.round(pct * 100) + "%"));
        t.bar.setProgress(Math.max(0, Math.min(1, pct)));

        // Barra solo para los que apostaron.
        Set<UUID> wanted = new HashSet<>(t.bets.keySet());
        for (UUID id : wanted) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !t.bar.getPlayers().contains(p))
                t.bar.addPlayer(p);
        }

        if (tickCount % 10 != 0)
            return;
        Integer[] order = new Integer[t.lanes];
        for (int i = 0; i < t.lanes; i++)
            order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(t.progress[b], t.progress[a]));
        for (Map.Entry<UUID, Map<Integer, Long>> e : t.bets.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null)
                continue;
            StringBuilder sb = new StringBuilder("&7Tus caballos: ");
            for (int horse : e.getValue().keySet()) {
                int place = 1;
                for (int k = 0; k < order.length; k++)
                    if (order[k] == horse)
                        place = k + 1;
                sb.append("&f#").append(horse + 1).append(" &e").append(place).append("° ");
            }
            p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(color(sb.toString())));
        }
    }

    // ------------------------------------------------------------------
    // Apuestas
    // ------------------------------------------------------------------

    private long totalBets(Track t) {
        long s = 0;
        for (Map<Integer, Long> m : t.bets.values())
            for (long v : m.values())
                s += v;
        return s;
    }

    private long pool(Track t, int horse) {
        long s = 0;
        for (Map<Integer, Long> m : t.bets.values())
            s += m.getOrDefault(horse, 0L);
        return s;
    }

    /**
     * Cuota fija (por defecto): cada caballo paga carriles x (1 - comisión), así se
     * puede jugar solo. "mutual": apuestas mutuas, el pozo se reparte entre los que acertaron.
     */
    private boolean fixedOdds() {
        return !"mutual".equalsIgnoreCase(config().getString("odds_mode", "fixed"));
    }

    private double cut() {
        return Math.max(0.0, Math.min(50.0, config().getDouble("house_cut_percent", 8.0))) / 100.0;
    }

    private double fixedMultiplier(Track t) {
        return Math.floor(t.lanes * (1.0 - cut()) * 100.0) / 100.0;
    }

    private long pot(Track t) {
        double cut = Math.max(0.0, Math.min(50.0, config().getDouble("house_cut_percent", 8.0))) / 100.0;
        return (long) Math.floor(totalBets(t) * (1.0 - cut));
    }

    private String oddsText(Track t, int horse) {
        if (fixedOdds())
            return fmt(fixedMultiplier(t));
        long pool = pool(t, horse);
        return pool > 0 ? fmt(pot(t) / (double) pool) : "-";
    }

    private void placeBet(Player p, Track t, int horse, long amount) {
        if (t.state != State.IDLE && t.state != State.BETTING) {
            p.sendMessage(msg("bets_closed", "&cLa carrera ya empezó. Espera la próxima."));
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
        t.bets.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>()).merge(horse, amount, Long::sum);
        if (t.state == State.IDLE)
            startBetting(t);
        p.sendMessage(msg("bet_placed", "&aApostaste &e{amount}&a al &f#{horse} {name}&a. &7Cuota actual: &fx{odds}",
                "amount", units(amount), "horse", String.valueOf(horse + 1), "name", color(horseName(horse)),
                "odds", oddsText(t, horse)));
        p.playSound(p.getLocation(), Sound.ENTITY_HORSE_AMBIENT, 0.6f, 1.2f);
    }

    private void refundAll(Track t) {
        for (Map.Entry<UUID, Map<Integer, Long>> e : t.bets.entrySet()) {
            long total = 0;
            for (long v : e.getValue().values())
                total += v;
            TokenWallet.give(e.getKey(), total);
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && total > 0)
                p.sendMessage(msg("refunded", "&7La carrera se canceló. Se te devolvieron &e{amount}&7.",
                        "amount", units(total)));
        }
        t.bets.clear();
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    private void openMenu(Player p, Track t) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(t.name), 27, color("&6&lCarrera &8- &e" + t.name));
        fill(p, inv, t);
        p.openInventory(inv);
    }

    private void refreshMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MenuHolder h) {
                Track t = tracks.get(h.track.toLowerCase(Locale.ROOT));
                if (t != null)
                    fill(p, top, t);
            }
        }
    }

    private void fill(Player p, Inventory inv, Track t) {
        for (int i = 0; i < 27; i++)
            inv.setItem(i, item(Material.BROWN_STAINED_GLASS_PANE, " ", null));
        Map<Integer, Long> mine = t.bets.getOrDefault(p.getUniqueId(), Map.of());
        for (int i = 0; i < t.lanes && i < MENU_SLOTS.length; i++) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Apostado a este caballo: &e" + units(pool(t, i)));
            lore.add("&7Cuota actual: &fx" + oddsText(t, i));
            long my = mine.getOrDefault(i, 0L);
            if (my > 0)
                lore.add("&aTu apuesta: &e" + units(my));
            if (t.state == State.RESULT && t.winner == i)
                lore.add("&a&l¡GANADOR!");
            lore.add("");
            lore.add(t.state == State.IDLE || t.state == State.BETTING ? "&eClick para apostar" : "&8Carrera en curso");
            inv.setItem(MENU_SLOTS[i], item(HORSE_ICONS[i % HORSE_ICONS.length],
                    "&f#" + (i + 1) + " " + horseName(i), lore));
        }
        String status = switch (t.state) {
            case IDLE -> "&7Apuesta para abrir la carrera";
            case BETTING -> "&eSale en &f" + t.countdown + "s";
            case RACING -> "&a¡En carrera!";
            case RESULT -> "&6Ganó el #" + (t.winner + 1);
        };
        if (fixedOdds()) {
            inv.setItem(4, item(Material.GOLDEN_HORSE_ARMOR, "&6&lCada caballo paga &fx" + fmt(fixedMultiplier(t)),
                    List.of(status, "&7Apostado en esta carrera: &e" + units(totalBets(t)),
                            "&7Si tu caballo gana: apuesta x" + fmt(fixedMultiplier(t)))));
        } else {
            inv.setItem(4, item(Material.GOLDEN_HORSE_ARMOR, "&6&lPozo: &e" + units(pot(t)), List.of(status,
                    "&7La cuota cambia según lo que apuesten todos.",
                    "&7Si nadie acierta, se devuelve todo.")));
        }
        inv.setItem(26, item(Material.SUNFLOWER, "&7Tus fichas: &e" + units(TokenWallet.balance(p)), null));
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

    /** Mensaje solo para los que apostaron en esta carrera. */
    private void announce(Track t, String text) {
        for (UUID id : t.bets.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                p.sendMessage(text);
        }
    }

    private void playNear(Track t, Sound s, float vol, float pitch) {
        if (loaded(t))
            t.start.getWorld().playSound(t.start, s, vol, pitch);
    }

    private Track trackOfHorse(UUID entity) {
        for (Track t : tracks.values())
            if (t.horses.contains(entity))
                return t;
        return null;
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    private final class Events implements Listener {

        @EventHandler
        public void onInteractHorse(PlayerInteractEntityEvent e) {
            Track t = trackOfHorse(e.getRightClicked().getUniqueId());
            if (t == null)
                return;
            e.setCancelled(true); // no se pueden montar
            if (e.getHand() != EquipmentSlot.HAND)
                return;
            if (t.station != null)
                e.getPlayer().sendMessage(msg("use_station", "&7Para apostar, haz &fclick derecho&7 a la mesa de la pista."));
            else
                openMenu(e.getPlayer(), t); // pistas manuales (sin mesa)
        }

        @EventHandler
        public void onInteractBlock(PlayerInteractEvent e) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null)
                return;
            String k = BlackjackTables.key(e.getClickedBlock().getLocation());
            for (Track t : tracks.values()) {
                if (t.station != null && k.equals(BlackjackTables.key(t.station))) {
                    e.setCancelled(true);
                    if (e.getHand() == EquipmentSlot.HAND)
                        openMenu(e.getPlayer(), t);
                    return;
                }
                if (t.gates.contains(k)) {
                    e.setCancelled(true); // las puertas solo las abre la carrera
                    return;
                }
            }
        }

        @EventHandler
        public void onBreak(BlockBreakEvent e) {
            String k = BlackjackTables.key(e.getBlock().getLocation());
            for (Track t : tracks.values()) {
                boolean isStation = t.station != null && k.equals(BlackjackTables.key(t.station));
                if (isStation || t.built.containsKey(k)) {
                    e.setCancelled(true);
                    e.getPlayer().sendMessage(msg("cannot_break",
                            "&cEs parte de la pista &f{track}&c. Quítala con &f/gdx station remove&c (mirando la mesa).",
                            "track", t.name));
                    return;
                }
            }
        }

        /** Los caballos de la pista los crea el plugin: que una protección de mobs no los cancele. */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSpawn(CreatureSpawnEvent e) {
            if (spawningHorses && e.getEntity() instanceof Horse)
                e.setCancelled(false);
        }

        @EventHandler
        public void onDamage(EntityDamageEvent e) {
            if (trackOfHorse(e.getEntity().getUniqueId()) != null)
                e.setCancelled(true);
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof MenuHolder h))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            Track t = tracks.get(h.track.toLowerCase(Locale.ROOT));
            if (t == null)
                return;
            int slot = e.getRawSlot();
            for (int i = 0; i < t.lanes && i < MENU_SLOTS.length; i++) {
                if (MENU_SLOTS[i] != slot)
                    continue;
                if (t.state != State.IDLE && t.state != State.BETTING) {
                    p.sendMessage(msg("bets_closed", "&cLa carrera ya empezó. Espera la próxima."));
                    return;
                }
                final int horse = i;
                long min = Math.max(1, config().getLong("min_bet", 10));
                AmountPickerMenu.open(p, "&6&lCarrera &8- &f#" + (i + 1), min,
                        Math.max(0, config().getLong("max_bet", 0)), min,
                        List.of("&7Caballo: &f#" + (i + 1) + " " + horseName(i), "&7Cuota actual: &fx" + oddsText(t, i)),
                        amount -> {
                            placeBet(p, t, horse, amount);
                            openMenu(p, t);
                        }, () -> openMenu(p, t));
                return;
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof MenuHolder)
                e.setCancelled(true);
        }
    }
}
