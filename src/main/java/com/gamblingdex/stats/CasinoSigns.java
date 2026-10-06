package com.gamblingdex.stats;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.modules.GameModule;
import com.gamblingdex.util.CasinoSchedule;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Carteles del casino que se actualizan solos (no hace falta PlaceholderAPI):
 * ganadores, récord, jackpots, tops, horario, estado de los juegos, info de las
 * mesas, últimos números de la ruleta y de Crash, lotería, bingo y cambio.
 * Se crean mirando un cartel con /gdx sign add &lt;tipo&gt; [n|mesa|juego] [juego].
 * Los textos de cada tipo están en config.yml → signs.formats (4 líneas de ~15 letras).
 */
public final class CasinoSigns implements Listener {

    public static final List<String> TYPES = List.of("last_win", "record", "jackpot_slots", "jackpot_roulette", "top",
            "top_week", "top_biggest", "top_wagered", "top_poker", "top_poker_pot", "schedule", "players_now",
            "game_status", "table", "roulette_last", "crash_last", "lottery", "bingo", "exchange", "baccarat_road", "poker_board", "poker_winner");

    /** Tipos que llevan puesto (1..10). */
    public static final Set<String> RANKED = Set.of("last_win", "top", "top_week", "top_biggest", "top_wagered",
            "top_poker", "top_poker_pot");
    /** Tipos que aceptan un juego opcional. */
    public static final Set<String> PER_GAME = Set.of("top", "top_week", "top_biggest", "top_wagered");

    /** Un cartel registrado. {@code n} = puesto, {@code arg} = juego o nombre de la mesa (o vacío). */
    private record Entry(String world, int x, int y, int z, String type, int n, String arg) {
        String key() {
            return world + ";" + x + ";" + y + ";" + z;
        }
    }

    private final GamblingDexPlugin plugin;
    private final File file;
    private final Map<String, Entry> signs = new LinkedHashMap<>();

    public CasinoSigns(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "signs.yml");
        for (String s : YamlConfiguration.loadConfiguration(file).getStringList("signs")) {
            String[] p = s.split(";", -1);
            if (p.length < 7)
                continue;
            try {
                Entry e = new Entry(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[4],
                        Integer.parseInt(p[5]), p[6]);
                signs.put(e.key(), e);
            } catch (NumberFormatException ignored) {
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long every = Math.max(2, plugin.getConfig().getLong("signs.update_seconds", 10)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 60L, every);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (Entry e : signs.values())
            list.add(String.join(";", e.world(), String.valueOf(e.x()), String.valueOf(e.y()), String.valueOf(e.z()),
                    e.type(), String.valueOf(e.n()), e.arg()));
        y.set("signs", list);
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar signs.yml: " + ex.getMessage());
        }
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public boolean add(Block b, String type, int n, String arg) {
        if (!(b.getState() instanceof Sign))
            return false;
        Entry e = new Entry(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), type, Math.max(1, Math.min(10, n)),
                arg == null ? "" : arg.replace(";", ""));
        signs.put(e.key(), e);
        save();
        update(e);
        return true;
    }

    public boolean remove(Block b) {
        if (signs.remove(key(b)) == null)
            return false;
        save();
        return true;
    }

    public boolean isSign(Block b) {
        return b != null && signs.containsKey(key(b));
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Entry e : signs.values())
            out.add("&8- &f" + e.type() + (RANKED.contains(e.type()) ? " #" + e.n() : "")
                    + (e.arg().isEmpty() ? "" : " &7(" + e.arg() + ")")
                    + " &8@ &7" + e.world() + " " + e.x() + " " + e.y() + " " + e.z());
        return out;
    }

    /** Nombres de las mesas de blackjack y póker (para /gdx sign add table). */
    public List<String> tableNames() {
        List<String> out = new ArrayList<>();
        if (plugin.getBlackjackManager() != null)
            out.addAll(plugin.getBlackjackManager().getTableNames());
        if (plugin.getPokerManager() != null)
            for (var t : plugin.getPokerManager().getTables())
                out.add(t.getName());
        return out;
    }

    public boolean tableExists(String name) {
        return (plugin.getBlackjackManager() != null && plugin.getBlackjackManager().getByName(name) != null)
                || (plugin.getPokerManager() != null && plugin.getPokerManager().getByName(name) != null);
    }

    // ------------------------------------------------------------------

    private void updateAll() {
        for (Entry e : new ArrayList<>(signs.values()))
            update(e);
    }

    private void update(Entry e) {
        World w = Bukkit.getWorld(e.world());
        if (w == null || !w.isChunkLoaded(e.x() >> 4, e.z() >> 4))
            return;
        Block b = w.getBlockAt(e.x(), e.y(), e.z());
        if (!(b.getState() instanceof Sign sign))
            return; // ya no es un cartel
        List<String> lines;
        try {
            lines = lines(e, b);
        } catch (Exception ex) {
            return; // un juego apagado o recargándose: se reintenta en la próxima vuelta
        }
        boolean changed = false;
        for (Side side : Side.values()) {
            SignSide ss = sign.getSide(side);
            for (int i = 0; i < 4; i++) {
                String text = i < lines.size() ? lines.get(i) : "";
                if (!text.equals(ss.getLine(i))) {
                    ss.setLine(i, text);
                    changed = true;
                }
            }
        }
        boolean glow = plugin.getConfig().getBoolean("signs.glowing", true);
        for (Side side : Side.values())
            if (sign.getSide(side).isGlowingText() != glow) {
                sign.getSide(side).setGlowingText(glow);
                changed = true;
            }
        if (!sign.isWaxed()) {
            sign.setWaxed(true); // que nadie lo pueda editar
            changed = true;
        }
        if (changed)
            sign.update(true, false);
    }

    private List<String> lines(Entry e, Block at) {
        Map<String, String> v = new HashMap<>();
        v.put("n", String.valueOf(e.n()));
        String format = e.type();
        GameStats stats = plugin.getGameStats();

        switch (e.type()) {
            case "last_win", "record" -> {
                GameStats.Play p = null;
                if (stats != null) {
                    if (e.type().equals("record"))
                        p = stats.record();
                    else if (e.n() <= stats.recentWins().size())
                        p = stats.recentWins().get(e.n() - 1);
                }
                v.put("name", p == null ? "-" : short15(p.name()));
                v.put("amount", p == null ? "0" : GameModule.units(p.net()));
                v.put("game", p == null ? "" : GameStats.gameName(p.game()));
            }
            case "jackpot_slots" -> v.put("amount", GameModule.units(
                    plugin.getSlotsController() == null ? 0 : plugin.getSlotsController().getJackpot()));
            case "jackpot_roulette" -> v.put("amount", GameModule.units(
                    plugin.getWorldRouletteManager() == null ? 0 : plugin.getWorldRouletteManager().getJackpot()));
            case "top", "top_week", "top_biggest", "top_wagered" -> {
                boolean week = e.type().equals("top_week");
                GameStats.Metric m = switch (e.type()) {
                    case "top_biggest" -> GameStats.Metric.BIGGEST_WIN;
                    case "top_wagered" -> GameStats.Metric.WAGERED;
                    default -> GameStats.Metric.PROFIT;
                };
                List<Map.Entry<UUID, Long>> top = stats == null ? List.of()
                        : e.arg().isEmpty() ? stats.top(m, week) : stats.top(e.arg(), m, week);
                putRank(v, top, e.n());
                v.put("game", e.arg().isEmpty() ? "Todos" : GameStats.gameName(e.arg()));
            }
            case "top_poker", "top_poker_pot" -> putRank(v, stats == null ? List.of()
                    : stats.pokerTop(e.type().equals("top_poker") ? GameStats.PokerMetric.PROFIT
                            : GameStats.PokerMetric.POT, false), e.n());
            case "schedule" -> {
                CasinoSchedule s = plugin.getSchedule();
                if (s == null || !s.enabled()) {
                    format = "schedule_always";
                } else {
                    format = s.isOpenNow() ? "schedule_open" : "schedule_closed";
                    v.put("open", CasinoSchedule.fmt(s.openTime()));
                    v.put("close", CasinoSchedule.fmt(s.closeTime()));
                    v.put("zone", s.zoneName());
                }
            }
            case "players_now" -> v.put("count", String.valueOf(stats == null ? 0 : stats.activePlayers(5)));
            case "game_status" -> {
                v.put("game", GameStats.gameName(e.arg()));
                CasinoSchedule s = plugin.getSchedule();
                var mt = plugin.getMaintenance();
                if (s != null && !s.isOpenNow()) {
                    format = "game_closed_hours";
                    v.put("open", CasinoSchedule.fmt(s.openTime()));
                } else {
                    format = mt != null && mt.isClosed(e.arg()) ? "game_maintenance" : "game_open";
                }
            }
            case "table" -> format = table(e.arg(), e.n(), v);
            case "poker_board", "poker_winner" -> {
                var pm = plugin.getPokerManager();
                var pk = pm == null ? null : pm.getByName(e.arg());
                if (pk == null) {
                    v.put("name", short15(e.arg()));
                    format = "table_missing";
                    break;
                }
                v.put("name", short15(pk.getDisplayName()));
                if (e.type().equals("poker_winner")) {
                    v.put("winner", short15(pk.getLastWinnerName()));
                    v.put("hand", short15(pk.getLastWinnerHand()));
                    v.put("amount", pk.getLastWinnerAmount() > 0 ? "+" + GameModule.units(pk.getLastWinnerAmount()) : "-");
                } else if (pk.isHandRunning() && !pk.getBoard().isEmpty()) {
                    v.put("street", pk.getStreetName().toUpperCase(Locale.ROOT));
                    v.put("cards", signCards(pk.getBoard()));
                    v.put("pot", GameModule.units(pk.getPot()));
                } else if (pk.isHandRunning()) {
                    v.put("street", "PREFLOP");
                    v.put("cards", "&8? ? ? ? ?");
                    v.put("pot", GameModule.units(pk.getPot()));
                } else {
                    format = "poker_board_last";
                    v.put("cards", pk.getLastBoard().isEmpty() ? "&8-" : signCards(pk.getLastBoard()));
                    v.put("winner", short15(pk.getLastWinnerName()));
                }
            }
            case "baccarat_road" -> {
                var mm = plugin.getModuleManager();
                GameModule m = mm == null ? null : mm.find("baccarat");
                List<Character> road = null;
                if (m instanceof com.gamblingdex.modules.baccarat.BaccaratModule bm) {
                    String table = e.arg().isEmpty() ? bm.nearestTable(at.getLocation()) : e.arg();
                    road = bm.road(table);
                }
                if (road == null)
                    road = List.of();
                int b = 0, j = 0, t = 0;
                for (char c : road) {
                    if (c == 'B')
                        b++;
                    else if (c == 'J')
                        j++;
                    else
                        t++;
                }
                v.put("row1", roadRow(road, 0));
                v.put("row2", roadRow(road, 8));
                v.put("banker", String.valueOf(b));
                v.put("player", String.valueOf(j));
                v.put("tie", String.valueOf(t));
            }
            case "roulette_last" -> {
                var rm = plugin.getWorldRouletteManager();
                var t = rm == null ? null : rm.getNearest(at.getLocation());
                List<Integer> nums = t == null ? List.of() : t.getRecentNumbers();
                for (int i = 1; i <= 10; i++) {
                    if (i > nums.size()) {
                        v.put("r" + i, "&8-");
                        continue;
                    }
                    int num = nums.get(i - 1);
                    String color = com.gamblingdex.games.rouletteworld.WorldRouletteTables.isZero(num) ? "&2"
                            : com.gamblingdex.games.rouletteworld.WorldRouletteTables.isRed(num) ? "&4" : "&0";
                    v.put("r" + i, color + com.gamblingdex.games.rouletteworld.WorldRouletteTables.formatNumber(num));
                }
            }
            case "crash_last" -> {
                for (int i = 1; i <= 10; i++) {
                    String c = module("crash", "last_" + i);
                    if (c == null || c.equals("-")) {
                        v.put("c" + i, "&8-");
                        continue;
                    }
                    double x = 1;
                    try {
                        x = Double.parseDouble(c.substring(1).replace(",", "."));
                    } catch (NumberFormatException ignored) {
                    }
                    v.put("c" + i, (x >= 10 ? "&6" : x >= 2 ? "&2" : "&4") + c);
                }
            }
            case "lottery" -> {
                v.put("pot", orDash(module("loteria", "pot")));
                v.put("next", orDash(module("loteria", "next")));
                v.put("price", orDash(module("loteria", "price")));
            }
            case "bingo" -> {
                v.put("pot", orDash(module("bingo", "pot")));
                v.put("next", orDash(module("bingo", "next")));
                v.put("price", orDash(module("bingo", "price")));
            }
            case "exchange" -> {
                double price = plugin.getConfig().getDouble("exchange.money_per_unit", 1.0);
                v.put("price", price == Math.floor(price) ? GameModule.units((long) price)
                        : String.format(Locale.ROOT, "%.2f", price));
                v.put("price1000", GameModule.units((long) Math.floor(price * 1000)));
            }
            default -> {
            }
        }

        List<String> fmt = plugin.getConfig().getStringList("signs.formats." + format);
        if (fmt.isEmpty())
            fmt = defaults(format);
        List<String> out = new ArrayList<>();
        for (String l : fmt) {
            for (Map.Entry<String, String> x : v.entrySet())
                l = l.replace("{" + x.getKey() + "}", x.getValue());
            out.add(plugin.color(l));
        }
        return out;
    }

    /** Cartas para un cartel: rojo oscuro (♥ ♦) y negro (♠ ♣), que se leen sobre la madera. */
    private static String signCards(List<com.gamblingdex.games.blackjack.Card> cards) {
        StringBuilder sb = new StringBuilder();
        for (var c : cards) {
            if (sb.length() > 0)
                sb.append(' ');
            boolean red = c.suit() == com.gamblingdex.games.blackjack.Card.Suit.HEARTS
                    || c.suit() == com.gamblingdex.games.blackjack.Card.Suit.DIAMONDS;
            sb.append(red ? "&4" : "&0").append(c.rank().label()).append(c.suit().symbol());
        }
        return sb.toString();
    }

    /** 8 resultados del baccarat desde {@code from}: B banca (rojo), J jugador (azul), E empate (verde). */
    private static String roadRow(List<Character> road, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(road.size(), from + 8); i++) {
            char c = road.get(i);
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(c == 'B' ? "&4B" : c == 'J' ? "&1J" : "&2E");
        }
        return sb.length() == 0 ? "&8-" : sb.toString();
    }

    private String table(String name, int n, Map<String, String> v) {
        var mt = plugin.getMaintenance();
        var bm = plugin.getBlackjackManager();
        var bj = bm == null ? null : bm.getByName(name);
        if (bj != null && mt != null && mt.isTableClosed("blackjack", bj.getDisplayName())) {
            v.put("name", short15(bj.getPrettyDisplayName() != null ? bj.getPrettyDisplayName() : bj.getDisplayName()));
            return "table_closed";
        }
        if (bj != null) {
            v.put("name", short15(bj.getPrettyDisplayName() != null ? bj.getPrettyDisplayName() : bj.getDisplayName()));
            v.put("players", String.valueOf(bj.getSeatedCount()));
            v.put("seats", String.valueOf(bj.getSeatKeys().size()));
            long min = bj.getMinBet(), max = bj.getMaxBet();
            v.put("limits", max > 0 && max < Long.MAX_VALUE / 2 ? compact(min) + " - " + compact(max) : "Mín " + compact(min));
            v.put("state", switch (bj.getState()) {
                case WAITING -> "Esperando";
                case BETTING -> "Apostando";
                default -> "Ronda en curso";
            });
            return "table_blackjack";
        }
        var pm = plugin.getPokerManager();
        var pk = pm == null ? null : pm.getByName(name);
        if (pk != null && mt != null && mt.isTableClosed("poker", pk.getName())) {
            v.put("name", short15(pk.getDisplayName()));
            return "table_closed";
        }
        if (pk != null && n == 2) {
            v.put("name", short15(pk.getDisplayName()));
            v.put("buyin", compact(pk.getMinBuyIn()) + "-" + compact(pk.getMaxBuyIn()));
            v.put("pot", pk.isHandRunning() ? GameModule.units(pk.getPot()) : "-");
            v.put("street", pk.isTournament() ? "Torneo · " + pk.getStreetName() : pk.getStreetName());
            return "table_poker_extra";
        }
        if (pk != null) {
            v.put("name", short15(pk.getDisplayName()));
            v.put("players", String.valueOf(pk.getSeatedCount()));
            v.put("seats", String.valueOf(pk.getSeatCount()));
            v.put("blinds", compact(pk.getSmallBlind()) + "/" + compact(pk.getBigBlind()));
            v.put("state", pk.isTournament() ? (pk.isTournamentRegistering() ? "Torneo: inscríbete" : "Torneo en curso")
                    : pk.getState() == com.gamblingdex.games.poker.PokerTable.State.WAITING ? "Esperando" : "Mano en curso");
            return "table_poker";
        }
        v.put("name", short15(name));
        return "table_missing";
    }

    private void putRank(Map<String, String> v, List<Map.Entry<UUID, Long>> top, int n) {
        if (n <= top.size()) {
            Map.Entry<UUID, Long> t = top.get(n - 1);
            long val = t.getValue();
            v.put("name", short15(nameOf(t.getKey())));
            v.put("amount", (val < 0 ? "-" : "") + GameModule.units(Math.abs(val)));
        } else {
            v.put("name", "-");
            v.put("amount", "0");
        }
    }

    private String module(String id, String key) {
        var mm = plugin.getModuleManager();
        GameModule m = mm == null ? null : mm.find(id);
        return m == null ? null : m.placeholder(key);
    }

    private static String orDash(String s) {
        return s == null ? "-" : s;
    }

    /** Nombre sin colores y de 15 letras como mucho (lo que cabe en un cartel). */
    private String short15(String s) {
        if (s == null)
            return "-";
        String plain = ChatColor.stripColor(plugin.color(s));
        return plain.length() > 15 ? plain.substring(0, 15) : plain;
    }

    /** 25000 → 25k, 1500 → 1.5k, 2000000 → 2M (para que quepa en el cartel). */
    static String compact(long v) {
        if (v < 1000)
            return String.valueOf(v);
        String[] u = { "k", "M", "B" };
        double d = v;
        int i = -1;
        while (d >= 1000 && i < u.length - 1) {
            d /= 1000;
            i++;
        }
        String s = d >= 100 || d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
        return s.replace(".0", "") + u[i];
    }

    private static List<String> defaults(String type) {
        return switch (type) {
            case "last_win" -> List.of("&6&lGANADOR #{n}", "{name}", "&2+{amount}", "&8{game}");
            case "record" -> List.of("&6&l★ RÉCORD ★", "{name}", "&2+{amount}", "&8{game}");
            case "jackpot_slots" -> List.of("&5&lJACKPOT", "&5SLOTS", "&6&l{amount}", "&8¡Llévatelo!");
            case "jackpot_roulette" -> List.of("&4&lJACKPOT", "&4RULETA", "&6&l{amount}", "&8¡Llévatelo!");
            case "top" -> List.of("&1&lTOP #{n}", "{name}", "&2{amount}", "&8{game}");
            case "top_week" -> List.of("&1&lTOP SEMANA #{n}", "{name}", "&2{amount}", "&8{game}");
            case "top_biggest" -> List.of("&6&lMAYOR PREMIO", "&8#{n} &0{name}", "&2+{amount}", "&8{game}");
            case "top_wagered" -> List.of("&1&lMÁS APOSTADO", "&8#{n} &0{name}", "&2{amount}", "&8{game}");
            case "top_poker" -> List.of("&5&lTOP PÓKER #{n}", "{name}", "&2{amount}", "&8ganancias");
            case "top_poker_pot" -> List.of("&5&lBOTE MAYOR #{n}", "{name}", "&2{amount}", "&8póker");
            case "schedule_open" -> List.of("&6&l✦ CASINO ✦", "&2&lABIERTO", "Cierra {close}", "&8{zone}");
            case "schedule_closed" -> List.of("&6&l✦ CASINO ✦", "&4&lCERRADO", "Abre {open}", "&8{zone}");
            case "schedule_always" -> List.of("&6&l✦ CASINO ✦", "&2&lABIERTO", "Todo el día", "");
            case "players_now" -> List.of("&6&l✦ CASINO ✦", "&8Jugando ahora", "&2&l{count}", "&8jugadores");
            case "game_open" -> List.of("&l{game}", "&2&lABIERTO", "", "&8¡A jugar!");
            case "game_maintenance" -> List.of("&l{game}", "&4&lMANTENIMIENTO", "", "&8Vuelve pronto");
            case "game_closed_hours" -> List.of("&l{game}", "&4&lCERRADO", "Abre {open}", "");
            case "table_blackjack" -> List.of("&l{name}", "Jugadores {players}/{seats}", "&2{limits}", "&8{state}");
            case "table_poker" -> List.of("&l{name}", "Jugadores {players}/{seats}", "&2Ciegas {blinds}", "&8{state}");
            case "table_missing" -> List.of("&l{name}", "&4Mesa no", "&4encontrada", "");
            case "table_closed" -> List.of("&l{name}", "&4&lCERRADA", "&8Mantenimiento", "");
            case "table_poker_extra" -> List.of("&l{name}", "Compra &2{buyin}", "Bote: &2{pot}", "&8{street}");
            case "poker_board" -> List.of("&l{name}", "&5&l{street}", "{cards}", "Bote: &2{pot}");
            case "poker_board_last" -> List.of("&l{name}", "&8Última mano", "{cards}", "&8{winner}");
            case "poker_winner" -> List.of("&6&lÚLTIMA MANO", "{winner}", "&8{hand}", "&2{amount}");
            case "baccarat_road" -> List.of("&6&lBACCARAT", "{row1}", "{row2}", "&4B{banker} &1J{player} &2E{tie}");
            case "roulette_last" -> List.of("&4&lRULETA", "&8Últimos números", "{r1} {r2} {r3} {r4} {r5}",
                    "{r6} {r7} {r8} {r9} {r10}");
            case "crash_last" -> List.of("&4&lCRASH ↑", "{c1}  {c2}", "{c3}  {c4}", "{c5}  {c6}");
            case "lottery" -> List.of("&6&lLOTERÍA", "Bote: &2{pot}", "&8Sorteo en", "{next}");
            case "bingo" -> List.of("&5&lBINGO", "{next}", "Cartón: &2{price}", "Bote: &2{pot}");
            case "exchange" -> List.of("&e&lCAMBIO", "1 ficha = &2${price}", "1.000 = &2${price1000}", "&8compra y venta");
            default -> List.of();
        };
    }

    private String nameOf(UUID id) {
        String n = null;
        if (plugin.getPlayerIndex() != null)
            n = plugin.getPlayerIndex().getLastKnownName(id);
        if (n == null || n.isBlank())
            n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!isSign(e.getBlock()))
            return;
        Player p = e.getPlayer();
        if (!p.hasPermission("gamblingdex.admin")) {
            e.setCancelled(true);
            return;
        }
        remove(e.getBlock());
        p.sendMessage(plugin.color("&7Cartel del casino quitado."));
    }

    /** El cartel que mira el jugador, o null. */
    public static Block target(Player p) {
        Block b = p.getTargetBlockExact(6);
        return b != null && b.getState() instanceof Sign ? b : null;
    }
}
