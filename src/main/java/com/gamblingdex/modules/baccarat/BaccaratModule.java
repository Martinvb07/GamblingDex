package com.gamblingdex.modules.baccarat;

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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
 * Baccarat (Punto Banco) con mesa física como el blackjack: dealer, asientos,
 * y el menú de apuestas se abre solo al sentarse. Apuestas: Jugador (1:1),
 * Banca (0.95:1), Empate (8:1), Pareja Jugador / Pareja Banca (11:1).
 * Reglas estándar de tercera carta.
 */
public class BaccaratModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String[] SUITS = { "♠", "♥", "♦", "♣" };
    private static final String[] RANKS = { "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K" };

    enum Spot {
        PLAYER("Jugador"), BANKER("Banca"), TIE("Empate"), PLAYER_PAIR("Pareja Jugador"), BANKER_PAIR("Pareja Banca");

        final String label;

        Spot(String label) {
            this.label = label;
        }
    }

    private enum State {
        WAITING, BETTING, DEALING, RESULT
    }

    /** Carta: rango 0..12 (A..K) y palo 0..3. */
    private record Card(int rank, int suit) {
        int value() {
            return rank >= 9 ? 0 : rank + 1; // A=1, 2-9, 10/J/Q/K = 0
        }

        String text() {
            return (suit == 1 || suit == 2 ? "&c" : "&f") + RANKS[rank] + SUITS[suit];
        }
    }

    private static final class Table {
        final String name;
        final Location center;
        final int fx, fz; // hacia dónde mira el dealer (hacia los jugadores)
        final List<String> seats = new ArrayList<>();
        final Set<UUID> seated = new LinkedHashSet<>();
        final Map<UUID, EnumMap<Spot, Long>> bets = new LinkedHashMap<>();
        final Map<UUID, EnumMap<Spot, Long>> lastBets = new HashMap<>();
        State state = State.WAITING;
        int countdown;
        Deque<Card> shoe = new ArrayDeque<>();
        final List<Card> playerHand = new ArrayList<>();
        final List<Card> bankerHand = new ArrayList<>();
        final Deque<Runnable> dealSteps = new ArrayDeque<>();
        int stepWait;
        String resultLine;
        UUID dealer;
        UUID holo;

        Table(String name, Location center, int fx, int fz) {
            this.name = name;
            this.center = center;
            this.fx = fx;
            this.fz = fz;
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        final String table;

        MenuHolder(String table) {
            this.table = table;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final Map<String, Table> tables = new LinkedHashMap<>();
    private int tickCount;
    private boolean spawningDealer;

    @Override
    public String id() {
        return "baccarat";
    }

    @Override
    public String displayName() {
        return "Baccarat";
    }

    @Override
    public List<String> aliases() {
        return List.of("bacarat", "baccara", "bc");
    }

    @Override
    public void enable() {
        YamlConfiguration d = loadData();
        ConfigurationSection sec = d.getConfigurationSection("tables");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                Location l = BlackjackTables.parseKey(sec.getString(name + ".center"));
                if (l == null)
                    continue;
                Table t = new Table(name, l, sec.getInt(name + ".fx"), sec.getInt(name + ".fz"));
                t.seats.addAll(sec.getStringList(name + ".seats"));
                tables.put(name.toLowerCase(Locale.ROOT), t);
            }
        }
        listen(new Events());
        runTimer(this::tick, 20L, 5L);
    }

    @Override
    public void disable() {
        for (Table t : tables.values()) {
            if (t.state == State.BETTING || t.state == State.DEALING)
                refundAll(t);
            removeEntities(t);
        }
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>();
        l.add("&6&lBaccarat");
        l.add("&8• &7Jugar: &fpárate en un asiento&7 de la mesa; el menú de apuestas se abre solo");
        if (admin) {
            l.add("&8• &e/gdx station set baccarat [name] &7- Crear mesa (mirando el bloque de la mesa)");
            l.add("&8• &e/gdx baccarat seat <add|remove|list|clear> <table> &7- Asientos (parado encima)");
            l.add("&8• &e/gdx baccarat rename <table> <name...> &7- Título de la mesa (con colores)");
            l.add("&8• &e/gdx station remove &7- Quitar la mesa (mirando el bloque)");
        }
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (isAdmin(player) && (a.equals("asiento") || a.equals("asientos") || a.equals("seat"))) {
            seatCommand(player, args);
            return true;
        }
        if (isAdmin(player) && (a.equals("rename") || a.equals("renombrar"))) {
            Table t = args.length < 3 ? null : tables.get(args[1].toLowerCase(Locale.ROOT));
            if (t == null) {
                player.sendMessage(msg("rename_usage", "&cUso: /gdx baccarat rename <table> <name...> &7(&f-&7 = título por defecto)"));
                return true;
            }
            String pretty = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            String key = t.name;
            ConfigurationSection sec = config().getConfigurationSection("table_names");
            if (sec != null)
                for (String k : sec.getKeys(false))
                    if (k.equalsIgnoreCase(t.name))
                        key = k;
            boolean reset = pretty.equals("-") || pretty.equalsIgnoreCase("reset");
            config().set("table_names." + key, reset ? null : pretty);
            saveConfigFile();
            ensureDealer(t);
            updateHolo(t);
            player.sendMessage(color("&aMesa renombrada: &r" + title(t)));
            return true;
        }
        if (isAdmin(player) && (a.equals("lista") || a.equals("list"))) {
            player.sendMessage(color("&7Mesas de baccarat: &f" + tables.size()));
            for (Table t : tables.values())
                player.sendMessage(color("&8- &f" + t.name + " &7asientos: &f" + t.seats.size() + " &7" + t.state));
            return true;
        }
        player.sendMessage(msg("use_table", "&7Para jugar baccarat, &fpárate en un asiento&7 de la mesa."));
        if (isAdmin(player))
            player.sendMessage(msg("usage_admin",
                    "&cUso: /gdx baccarat seat <add|remove|list|clear> <table> &7| &c/gdx baccarat list"));
        return true;
    }

    private void seatCommand(Player p, String[] args) {
        if (args.length < 3) {
            p.sendMessage(msg("seat_usage", "&cUso: /gdx baccarat seat <add|remove|list|clear> <table> &7(parado en el asiento)"));
            return;
        }
        Table t = tables.get(args[2].toLowerCase(Locale.ROOT));
        if (t == null) {
            p.sendMessage(msg("not_found", "&cNo existe esa mesa. Mesas: &f{tables}", "tables", String.join(", ", tableNames())));
            return;
        }
        String key = BlackjackTables.key(p.getLocation().getBlock().getRelative(BlockFace.DOWN).getLocation());
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "agregar", "add" -> {
                if (t.seats.contains(key)) {
                    p.sendMessage(msg("seat_exists", "&eEse asiento ya está registrado."));
                } else if (t.seats.size() >= Math.max(1, config().getInt("max_seats", 7))) {
                    p.sendMessage(msg("seats_full", "&cLa mesa ya tiene el máximo de asientos."));
                } else {
                    t.seats.add(key);
                    save();
                    p.sendMessage(msg("seat_added", "&aAsiento &f#{n}&a agregado a &f{table}&a.",
                            "n", String.valueOf(t.seats.size()), "table", t.name));
                }
            }
            case "quitar", "remove" -> {
                if (t.seats.remove(key)) {
                    save();
                    p.sendMessage(msg("seat_removed", "&aAsiento quitado."));
                } else {
                    p.sendMessage(msg("seat_missing", "&cNo estás parado sobre un asiento de esa mesa."));
                }
            }
            case "lista", "list" -> {
                p.sendMessage(color("&7Asientos de &f" + t.name + "&7: &f" + t.seats.size()));
                for (String s : t.seats)
                    p.sendMessage(color("&8- &7" + s));
            }
            case "limpiar", "clear" -> {
                t.seats.clear();
                save();
                p.sendMessage(msg("seats_cleared", "&aSe quitaron todos los asientos."));
            }
            default -> p.sendMessage(msg("seat_usage", "&cUso: /gdx baccarat seat <add|remove|list|clear> <table>"));
        }
    }

    private List<String> tableNames() {
        List<String> n = new ArrayList<>();
        for (Table t : tables.values())
            n.add(t.name);
        return n;
    }

    // ------------------------------------------------------------------
    // Crear / quitar mesa (/gdx station set baccarat)
    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("baccarat", "bacarat", "baccara");
    }

    @Override
    public String stationUsage() {
        return "baccarat [name]";
    }

    @Override
    public List<String> stationListLines() {
        return List.of("&8- &6Baccarat&7: &f" + tables.size());
    }

    @Override
    public void createStation(Player p, Block target, String[] args) {
        String key = BlackjackTables.key(target.getLocation());
        for (Table t : tables.values())
            if (BlackjackTables.key(t.center).equals(key)) {
                p.sendMessage(msg("exists", "&cEse bloque ya es una mesa de baccarat."));
                return;
            }
        String name;
        if (args.length >= 1) {
            name = args[0].replaceAll("[^a-zA-Z0-9_\\-]", "");
            if (name.isBlank() || tables.containsKey(name.toLowerCase(Locale.ROOT))) {
                p.sendMessage(msg("name_taken", "&cYa existe una mesa con ese nombre."));
                return;
            }
        } else {
            int n = 1;
            while (tables.containsKey("baccarat" + n))
                n++;
            name = "baccarat" + n;
        }
        BlockFace f = p.getFacing().getOppositeFace(); // el dealer mira hacia quien crea la mesa
        Table t = new Table(name, target.getLocation(), f.getModX(), f.getModZ());
        tables.put(name.toLowerCase(Locale.ROOT), t);
        save();
        // Que aparezca en baccarat.yml → table_names para ponerle título
        if (!config().isSet("table_names." + name)) {
            config().set("table_names." + name, name);
            saveConfigFile();
        }
        ensureDealer(t);
        updateHolo(t);
        p.sendMessage(msg("created",
                "&aMesa de baccarat &f{table}&a creada. Agrega asientos con &f/gdx baccarat seat add {table}&a (parado en cada asiento).",
                "table", name));
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        String key = BlackjackTables.key(target.getLocation());
        for (Table t : new ArrayList<>(tables.values())) {
            if (!BlackjackTables.key(t.center).equals(key))
                continue;
            refundAll(t);
            removeEntities(t);
            tables.remove(t.name.toLowerCase(Locale.ROOT));
            save();
            player.sendMessage(msg("removed", "&aMesa de baccarat eliminada (se devolvieron las apuestas)."));
            return true;
        }
        return false;
    }

    private void save() {
        YamlConfiguration d = new YamlConfiguration();
        for (Table t : tables.values()) {
            String k = "tables." + t.name + ".";
            d.set(k + "center", BlackjackTables.key(t.center));
            d.set(k + "fx", t.fx);
            d.set(k + "fz", t.fz);
            d.set(k + "seats", t.seats);
        }
        saveData(d);
    }

    // ------------------------------------------------------------------
    // Ciclo de la mesa
    // ------------------------------------------------------------------

    private boolean loaded(Table t) {
        World w = t.center.getWorld();
        return w != null && w.isChunkLoaded(t.center.getBlockX() >> 4, t.center.getBlockZ() >> 4);
    }

    private void tick() {
        tickCount++;
        for (Table t : tables.values()) {
            if (!loaded(t))
                continue;
            if (tickCount % 4 == 0) {
                ensureDealer(t);
                scanSeats(t);
            }
            switch (t.state) {
                case WAITING -> {
                    int min = Math.max(1, config().getInt("min_players", 1));
                    if (t.seated.size() >= min)
                        startBetting(t);
                }
                case BETTING -> {
                    if (tickCount % 4 == 0 && --t.countdown <= 0)
                        closeBets(t);
                }
                case DEALING -> {
                    if (--t.stepWait <= 0) {
                        Runnable step = t.dealSteps.poll();
                        if (step == null) {
                            finishRound(t);
                        } else {
                            step.run();
                            t.stepWait = Math.max(1, config().getInt("card_delay_ticks", 20) / 5);
                        }
                    }
                }
                case RESULT -> {
                    if (tickCount % 4 == 0 && --t.countdown <= 0) {
                        t.playerHand.clear();
                        t.bankerHand.clear();
                        t.resultLine = null;
                        t.state = State.WAITING;
                    }
                }
            }
            updateHolo(t);
            actionBars(t);
        }
    }

    /** Quién está parado sobre algún asiento (como el blackjack). */
    private void scanSeats(Table t) {
        World w = t.center.getWorld();
        Set<UUID> now = new LinkedHashSet<>();
        for (String key : t.seats) {
            Location l = BlackjackTables.parseKey(key);
            if (l == null || l.getWorld() != w)
                continue;
            Block seat = l.getBlock();
            Location c = seat.getLocation().add(0.5, 0.5, 0.5);
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(c) > 4.0)
                    continue;
                Block feet = p.getLocation().getBlock();
                if (feet.equals(seat) || feet.getRelative(BlockFace.DOWN).equals(seat)) {
                    now.add(p.getUniqueId());
                    break;
                }
            }
        }
        // Se sentaron
        for (UUID id : now) {
            if (t.seated.contains(id))
                continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null)
                continue;
            p.sendMessage(msg("sat", "&aTe sentaste en la mesa de baccarat &f{table}&a.", "table", title(t)));
            if (t.state == State.BETTING)
                openMenu(p, t);
            else if (t.state != State.WAITING)
                p.sendMessage(msg("wait_round", "&7Hay una mano en curso. Apuesta en la siguiente."));
        }
        // Se pararon: si aún se podía apostar, se les devuelve
        for (UUID id : new ArrayList<>(t.seated)) {
            if (now.contains(id))
                continue;
            Player p = Bukkit.getPlayer(id);
            if (t.state == State.BETTING) {
                long back = total(t.bets.remove(id));
                if (back > 0)
                    TokenWallet.give(id, back);
                if (p != null && back > 0)
                    p.sendMessage(msg("left_refund", "&7Te levantaste: se te devolvieron &e{amount}&7.", "amount", units(back)));
            }
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)
                p.closeInventory();
        }
        t.seated.clear();
        t.seated.addAll(now);
    }

    private void startBetting(Table t) {
        t.state = State.BETTING;
        t.countdown = Math.max(5, config().getInt("bet_window_seconds", 20));
        t.bets.clear();
        for (UUID id : t.seated) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                openMenu(p, t);
        }
    }

    private void closeBets(Table t) {
        // Solo cuentan los que siguen sentados
        t.bets.keySet().removeIf(id -> !t.seated.contains(id));
        for (UUID id : t.seated) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)
                p.closeInventory();
        }
        if (t.bets.isEmpty()) {
            t.state = State.WAITING;
            return;
        }
        for (Map.Entry<UUID, EnumMap<Spot, Long>> e : t.bets.entrySet())
            t.lastBets.put(e.getKey(), new EnumMap<>(e.getValue()));
        t.state = State.DEALING;
        t.playerHand.clear();
        t.bankerHand.clear();
        if (t.shoe.size() < 6 * 4 || t.shoe.size() < Math.max(8, config().getInt("decks", 8)) * 52
                * Math.max(5, config().getInt("reshuffle_at_percent", 25)) / 100) {
            buildShoe(t);
            announce(t, msg("shuffle", "&7El dealer baraja el zapato..."));
        }
        // Reparto: J, B, J, B y luego la regla de tercera carta
        t.dealSteps.clear();
        t.dealSteps.add(() -> deal(t, t.playerHand));
        t.dealSteps.add(() -> deal(t, t.bankerHand));
        t.dealSteps.add(() -> deal(t, t.playerHand));
        t.dealSteps.add(() -> deal(t, t.bankerHand));
        t.dealSteps.add(() -> thirdCards(t));
        t.stepWait = 2;
        announce(t, msg("dealing", "&6&lBaccarat &8» &7Apuestas cerradas. ¡Se reparten las cartas!"));
    }

    private void buildShoe(Table t) {
        List<Card> cards = new ArrayList<>();
        int decks = Math.max(1, config().getInt("decks", 8));
        for (int d = 0; d < decks; d++)
            for (int s = 0; s < 4; s++)
                for (int r = 0; r < 13; r++)
                    cards.add(new Card(r, s));
        Collections.shuffle(cards, RNG);
        t.shoe = new ArrayDeque<>(cards);
    }

    private void deal(Table t, List<Card> hand) {
        if (t.shoe.isEmpty())
            buildShoe(t);
        hand.add(t.shoe.poll());
        World w = t.center.getWorld();
        if (w != null)
            w.playSound(t.center, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
    }

    private static int points(List<Card> hand) {
        int s = 0;
        for (Card c : hand)
            s += c.value();
        return s % 10;
    }

    /** Reglas de Punto Banco para la tercera carta. */
    private void thirdCards(Table t) {
        int p = points(t.playerHand), b = points(t.bankerHand);
        if (p >= 8 || b >= 8)
            return; // natural: nadie pide
        if (p <= 5) {
            t.dealSteps.addFirst(() -> {
                deal(t, t.playerHand);
                bankerAfterPlayerThird(t, t.playerHand.get(t.playerHand.size() - 1));
            });
            return;
        }
        // El jugador se plantó (6-7): la banca pide con 0-5
        if (b <= 5)
            t.dealSteps.addFirst(() -> deal(t, t.bankerHand));
    }

    private void bankerAfterPlayerThird(Table t, Card third) {
        int b = points(t.bankerHand);
        int v = third == null ? 0 : third.value();
        boolean draw = switch (b) {
            case 0, 1, 2 -> true;
            case 3 -> v != 8;
            case 4 -> v >= 2 && v <= 7;
            case 5 -> v >= 4 && v <= 7;
            case 6 -> v == 6 || v == 7;
            default -> false;
        };
        if (draw)
            t.dealSteps.addFirst(() -> deal(t, t.bankerHand));
    }

    private void finishRound(Table t) {
        int p = points(t.playerHand), b = points(t.bankerHand);
        Spot winner = p > b ? Spot.PLAYER : b > p ? Spot.BANKER : Spot.TIE;
        boolean playerPair = t.playerHand.size() >= 2 && t.playerHand.get(0).rank() == t.playerHand.get(1).rank();
        boolean bankerPair = t.bankerHand.size() >= 2 && t.bankerHand.get(0).rank() == t.bankerHand.get(1).rank();
        double bankerPays = Math.max(0.0, config().getDouble("banker_payout", 0.95));
        double tiePays = Math.max(0.0, config().getDouble("tie_payout", 8.0));
        double pairPays = Math.max(0.0, config().getDouble("pair_payout", 11.0));

        String winText = switch (winner) {
            case PLAYER -> msg("win_player", "&9&lGANA JUGADOR");
            case BANKER -> msg("win_banker", "&c&lGANA BANCA");
            default -> msg("win_tie", "&a&lEMPATE");
        };
        t.resultLine = winText + color(" &8(&f" + p + " &8vs &f" + b + "&8)");
        announce(t, msg("result", "&6&lBaccarat &8» {result} &8| &9Jugador {player} &8- &cBanca {banker}",
                "result", winText, "player", cardsText(t.playerHand) + color(" &7(" + p + ")"),
                "banker", cardsText(t.bankerHand) + color(" &7(" + b + ")")));

        List<String> winners = new ArrayList<>();
        for (Map.Entry<UUID, EnumMap<Spot, Long>> e : t.bets.entrySet()) {
            UUID id = e.getKey();
            EnumMap<Spot, Long> m = e.getValue();
            long staked = total(m);
            long paid = 0;
            for (Map.Entry<Spot, Long> bet : m.entrySet()) {
                long a = bet.getValue();
                paid += switch (bet.getKey()) {
                    case PLAYER -> winner == Spot.PLAYER ? a * 2 : winner == Spot.TIE ? a : 0;
                    case BANKER -> winner == Spot.BANKER ? a + (long) Math.floor(a * bankerPays) : winner == Spot.TIE ? a : 0;
                    case TIE -> winner == Spot.TIE ? a + (long) Math.floor(a * tiePays) : 0;
                    case PLAYER_PAIR -> playerPair ? a + (long) Math.floor(a * pairPays) : 0;
                    case BANKER_PAIR -> bankerPair ? a + (long) Math.floor(a * pairPays) : 0;
                };
            }
            if (paid > 0)
                TokenWallet.give(id, paid);
            GamblingDexPlugin.recordStats(id, "baccarat", staked, paid);
            long net = paid - staked;
            String n = Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("?");
            if (net > 0)
                winners.add("&f" + n + " &a+" + units(net));
            Player pl = Bukkit.getPlayer(id);
            if (pl == null)
                continue;
            if (net > 0) {
                pl.sendMessage(msg("you_won", "&a&l¡Ganaste! &7Apostaste &e{bet} &7y cobras &e{paid} &a(+{net})",
                        "bet", units(staked), "paid", units(paid), "net", units(net)));
                pl.sendTitle(color("&a&l¡Ganaste!"), color("&a+" + units(net) + " &7fichas"), 5, 50, 10);
                pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            } else if (net == 0) {
                pl.sendMessage(msg("you_push", "&eEmpate para ti: &7recuperas &e{paid}&7.", "paid", units(paid)));
            } else {
                pl.sendMessage(msg("you_lost", "&cPerdiste &e{amount}&c.", "amount", units(-net)));
                pl.sendTitle(color("&cPerdiste"), color("&c-" + units(-net)), 5, 50, 10);
            }
        }
        announce(t, msg("summary", "&6&lBaccarat &8» &7Ganadores: {players}", "players",
                winners.isEmpty() ? color("&8nadie") : color(String.join("&7, ", winners))));
        t.bets.clear();
        t.state = State.RESULT;
        t.countdown = Math.max(2, config().getInt("result_seconds", 6));
    }

    private void refundAll(Table t) {
        for (Map.Entry<UUID, EnumMap<Spot, Long>> e : t.bets.entrySet()) {
            long back = total(e.getValue());
            if (back <= 0)
                continue;
            TokenWallet.give(e.getKey(), back);
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null)
                p.sendMessage(msg("refunded", "&7La mano de baccarat se canceló. Se te devolvieron &e{amount}&7.",
                        "amount", units(back)));
        }
        t.bets.clear();
        t.state = State.WAITING;
    }

    private static long total(Map<Spot, Long> m) {
        long s = 0;
        if (m != null)
            for (long v : m.values())
                s += v;
        return s;
    }

    // ------------------------------------------------------------------
    // Apuestas
    // ------------------------------------------------------------------

    private void placeBet(Player p, Table t, Spot spot, long amount) {
        if (t.state != State.BETTING) {
            p.sendMessage(msg("bets_closed", "&cApuestas cerradas. Espera la siguiente mano."));
            return;
        }
        if (!t.seated.contains(p.getUniqueId())) {
            p.sendMessage(msg("not_seated", "&cTienes que estar sentado en la mesa."));
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
        long tot = t.bets.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Spot.class)).merge(spot, amount, Long::sum);
        p.sendMessage(msg("bet_placed", "&aApuesta a &f{spot}&a: &e+{amount} &7(total: &e{total}&7)",
                "spot", spot.label, "amount", units(amount), "total", units(tot)));
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.4f);
    }

    private void repeatBet(Player p, Table t) {
        EnumMap<Spot, Long> last = t.lastBets.get(p.getUniqueId());
        long tot = total(last);
        if (tot <= 0) {
            p.sendMessage(msg("no_last_bet", "&7No tienes una apuesta anterior para repetir."));
            return;
        }
        if (t.state != State.BETTING || !t.seated.contains(p.getUniqueId())) {
            p.sendMessage(msg("bets_closed", "&cApuestas cerradas. Espera la siguiente mano."));
            return;
        }
        if (!isOpenFor(p))
            return;
        if (!TokenWallet.take(p, tot)) {
            p.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", units(TokenWallet.balance(p))));
            return;
        }
        EnumMap<Spot, Long> mine = t.bets.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Spot.class));
        for (Map.Entry<Spot, Long> e : last.entrySet())
            mine.merge(e.getKey(), e.getValue(), Long::sum);
        p.sendMessage(msg("repeated", "&aRepetiste tu apuesta: &e{amount}", "amount", units(tot)));
    }

    private void clearBets(Player p, Table t) {
        if (t.state != State.BETTING)
            return;
        long back = total(t.bets.remove(p.getUniqueId()));
        if (back > 0) {
            TokenWallet.give(p.getUniqueId(), back);
            p.sendMessage(msg("cleared", "&7Retiraste tus apuestas: &e{amount}", "amount", units(back)));
        }
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    private void openMenu(Player p, Table t) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(t.name), 36, color("&6&lBaccarat &8- &eApuesta"));
        fillMenu(p, t, inv);
        p.openInventory(inv);
    }

    private void fillMenu(Player p, Table t, Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++)
            inv.setItem(i, item(i < 9 || i >= 27 ? Material.BLACK_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, " ", null));
        EnumMap<Spot, Long> mine = t.bets.getOrDefault(p.getUniqueId(), new EnumMap<>(Spot.class));
        double bp = config().getDouble("banker_payout", 0.95);
        double tp = config().getDouble("tie_payout", 8.0);
        double pp = config().getDouble("pair_payout", 11.0);
        inv.setItem(4, item(Material.CLOCK, "&6&lBACCARAT &8| &e" + Math.max(0, t.countdown) + "s",
                List.of("&7Elige a quién le apuestas y cuánto.", "&7Cierra el menú cuando termines.")));
        inv.setItem(11, spotItem(Spot.PLAYER, Material.BLUE_CONCRETE, "&9", "Paga &f1 a 1", mine));
        inv.setItem(13, spotItem(Spot.TIE, Material.LIME_CONCRETE, "&a", "Paga &f" + fmt(tp) + " a 1", mine));
        inv.setItem(15, spotItem(Spot.BANKER, Material.RED_CONCRETE, "&c", "Paga &f" + fmt(bp) + " a 1 &8(comisión 5%)", mine));
        inv.setItem(20, spotItem(Spot.PLAYER_PAIR, Material.LIGHT_BLUE_CONCRETE, "&b", "Paga &f" + fmt(pp) + " a 1 &8(2 primeras iguales)", mine));
        inv.setItem(24, spotItem(Spot.BANKER_PAIR, Material.PINK_CONCRETE, "&d", "Paga &f" + fmt(pp) + " a 1 &8(2 primeras iguales)", mine));
        inv.setItem(22, item(Material.WRITABLE_BOOK, "&e&lTu apuesta: &f" + units(total(mine)), betLore(mine)));
        long last = total(t.lastBets.get(p.getUniqueId()));
        inv.setItem(29, last > 0
                ? item(Material.EMERALD, "&a&lRepetir apuesta", concat(betLore(t.lastBets.get(p.getUniqueId())),
                        List.of("", "&7Total: &e" + units(last), "&eClick para repetir")))
                : item(Material.GRAY_DYE, "&7Repetir apuesta", List.of("&8Disponible después de tu primera mano")));
        inv.setItem(31, item(Material.SUNFLOWER, "&7Tus fichas: &e" + units(TokenWallet.balance(p)), null));
        inv.setItem(33, item(Material.REDSTONE, "&cRetirar apuestas", List.of("&7Solo antes de repartir")));
    }

    private ItemStack spotItem(Spot spot, Material m, String c, String pays, EnumMap<Spot, Long> mine) {
        List<String> lore = new ArrayList<>();
        lore.add("&7" + pays);
        long my = mine.getOrDefault(spot, 0L);
        if (my > 0)
            lore.add("&aTu apuesta: &e" + units(my));
        lore.add("");
        lore.add("&eClick para apostar");
        return item(m, c + "&l" + spot.label.toUpperCase(Locale.ROOT), lore);
    }

    private List<String> betLore(Map<Spot, Long> m) {
        List<String> l = new ArrayList<>();
        if (m == null || m.isEmpty()) {
            l.add("&7Sin apuestas");
            return l;
        }
        for (Map.Entry<Spot, Long> e : m.entrySet())
            l.add("&f" + e.getKey().label + " &8» &e" + units(e.getValue()));
        return l;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
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
    // Dealer, holograma y pantalla
    // ------------------------------------------------------------------

    /** Título de la mesa: baccarat.yml → table_names.<table>, o "BACCARAT". */
    private String title(Table t) {
        ConfigurationSection sec = config().getConfigurationSection("table_names");
        if (sec != null)
            for (String k : sec.getKeys(false))
                if (k.equalsIgnoreCase(t.name) && sec.getString(k) != null)
                    return color(sec.getString(k));
        return color(config().getString("default_title", "&6&l♦ BACCARAT ♦"));
    }

    /**
     * Detrás de la mesa, a la MISMA altura del bloque registrado (parado en el piso).
     * Si ahí hay un bloque sólido, sube hasta encontrar espacio.
     */
    private Location dealerSpot(Table t) {
        Location l = t.center.clone().add(0.5 + t.fx * -1, 0.0, 0.5 + t.fz * -1);
        for (int i = 0; i < 3 && !l.getBlock().isPassable(); i++)
            l.add(0, 1, 0);
        l.setDirection(new org.bukkit.util.Vector(t.fx, 0, t.fz));
        return l;
    }

    private void ensureDealer(Table t) {
        World w = t.center.getWorld();
        if (w == null)
            return;
        Entity e = t.dealer == null ? null : w.getEntity(t.dealer);
        if (e instanceof Villager v && v.isValid()) {
            v.setCustomName(title(t));
            Location want = dealerSpot(t);
            if (v.getLocation().distanceSquared(want) > 0.04)
                v.teleport(want);
            return;
        }
        spawningDealer = true;
        try {
            java.util.function.Consumer<Villager> setup = v -> {
                v.setPersistent(false);
                v.setAI(false);
                v.setInvulnerable(true);
                v.setSilent(true);
                v.setCollidable(false);
                v.setCustomName(title(t));
                v.setCustomNameVisible(true);
            };
            Villager v = w.spawn(dealerSpot(t), Villager.class, setup);
            if (v.isValid())
                t.dealer = v.getUniqueId();
        } finally {
            spawningDealer = false;
        }
    }

    private String cardsText(List<Card> hand) {
        if (hand.isEmpty())
            return color("&8-");
        StringBuilder sb = new StringBuilder();
        for (Card c : hand)
            sb.append(c.text()).append(' ');
        return color(sb.toString().trim());
    }

    private void updateHolo(Table t) {
        World w = t.center.getWorld();
        if (w == null)
            return;
        // Encima de la cabeza del dealer (por arriba de su nombre)
        Location at = dealerSpot(t).add(0, config().getDouble("holo_above_dealer", 2.7), 0);
        at.setYaw(0);
        at.setPitch(0);
        Entity e = t.holo == null ? null : w.getEntity(t.holo);
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
            t.holo = td.getUniqueId();
        }
        String status = switch (t.state) {
            case WAITING -> "&7Siéntate para jugar &8(&f" + t.seated.size() + "&8/&f" + t.seats.size() + "&8)";
            case BETTING -> "&aApuestas abiertas &7(" + t.countdown + "s)";
            case DEALING -> "&eRepartiendo...";
            case RESULT -> t.resultLine == null ? "" : t.resultLine;
        };
        StringBuilder sb = new StringBuilder(title(t)).append('\n').append(color(status));
        if (!t.playerHand.isEmpty() || !t.bankerHand.isEmpty()) {
            sb.append('\n').append(color("&9Jugador: ")).append(cardsText(t.playerHand))
                    .append(color(" &7(" + points(t.playerHand) + ")"));
            sb.append('\n').append(color("&cBanca: ")).append(cardsText(t.bankerHand))
                    .append(color(" &7(" + points(t.bankerHand) + ")"));
        }
        td.setText(sb.toString());
    }

    private void actionBars(Table t) {
        if (t.playerHand.isEmpty() && t.bankerHand.isEmpty())
            return;
        String line = color("&9Jugador: ") + cardsText(t.playerHand) + color(" &7(" + points(t.playerHand) + ") &8| &cBanca: ")
                + cardsText(t.bankerHand) + color(" &7(" + points(t.bankerHand) + ")");
        for (UUID id : t.seated) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(line));
        }
    }

    private void announce(Table t, String text) {
        Set<UUID> to = new LinkedHashSet<>(t.seated);
        to.addAll(t.bets.keySet());
        for (UUID id : to) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                p.sendMessage(text);
        }
    }

    private void removeEntities(Table t) {
        World w = t.center.getWorld();
        if (w == null)
            return;
        for (UUID id : new UUID[] { t.dealer, t.holo }) {
            Entity e = id == null ? null : w.getEntity(id);
            if (e != null)
                e.remove();
        }
        t.dealer = null;
        t.holo = null;
    }

    private Table tableOf(Block b) {
        if (b == null)
            return null;
        String k = BlackjackTables.key(b.getLocation());
        for (Table t : tables.values())
            if (BlackjackTables.key(t.center).equals(k))
                return t;
        return null;
    }

    private Table tableOfDealer(UUID entity) {
        for (Table t : tables.values())
            if (entity.equals(t.dealer))
                return t;
        return null;
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    private final class Events implements Listener {

        private void tryOpen(Player p, Table t) {
            if (t.state == State.BETTING && t.seated.contains(p.getUniqueId()))
                openMenu(p, t);
            else if (!t.seated.contains(p.getUniqueId()))
                p.sendMessage(msg("use_table", "&7Para jugar baccarat, &fpárate en un asiento&7 de la mesa."));
            else
                p.sendMessage(msg("wait_round", "&7Hay una mano en curso. Apuesta en la siguiente."));
        }

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK)
                return;
            Table t = tableOf(e.getClickedBlock());
            if (t == null)
                return;
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND)
                tryOpen(e.getPlayer(), t);
        }

        @EventHandler
        public void onInteractDealer(PlayerInteractEntityEvent e) {
            Table t = tableOfDealer(e.getRightClicked().getUniqueId());
            if (t == null)
                return;
            e.setCancelled(true); // que no se abra el comercio del aldeano
            if (e.getHand() == EquipmentSlot.HAND)
                tryOpen(e.getPlayer(), t);
        }

        @EventHandler
        public void onDamage(EntityDamageEvent e) {
            if (tableOfDealer(e.getEntity().getUniqueId()) != null)
                e.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSpawn(CreatureSpawnEvent e) {
            if (spawningDealer && e.getEntity() instanceof Villager)
                e.setCancelled(false);
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof MenuHolder h))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            Table t = tables.get(h.table.toLowerCase(Locale.ROOT));
            if (t == null)
                return;
            Spot spot = switch (e.getRawSlot()) {
                case 11 -> Spot.PLAYER;
                case 13 -> Spot.TIE;
                case 15 -> Spot.BANKER;
                case 20 -> Spot.PLAYER_PAIR;
                case 24 -> Spot.BANKER_PAIR;
                default -> null;
            };
            if (spot != null) {
                long min = Math.max(1, config().getLong("min_bet", 10));
                AmountPickerMenu.open(p, "&6&lBaccarat &8- &f" + spot.label, min,
                        Math.max(0, config().getLong("max_bet", 0)), min, List.of("&7Apuesta a: &f" + spot.label),
                        amount -> {
                            placeBet(p, t, spot, amount);
                            if (t.state == State.BETTING && t.seated.contains(p.getUniqueId()))
                                openMenu(p, t);
                        }, () -> {
                            if (t.state == State.BETTING && t.seated.contains(p.getUniqueId()))
                                openMenu(p, t);
                        });
                return;
            }
            if (e.getRawSlot() == 29) {
                repeatBet(p, t);
                fillMenu(p, t, e.getInventory());
            } else if (e.getRawSlot() == 33) {
                clearBets(p, t);
                fillMenu(p, t, e.getInventory());
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof MenuHolder)
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
            return List.of("seat", "list", "rename");
        String a = args[0].toLowerCase(Locale.ROOT);
        if (a.equals("rename") || a.equals("renombrar"))
            return args.length == 2 ? tableNames() : args.length == 3 ? List.of("<name...>") : List.of();
        if (!a.equals("seat") && !a.equals("asiento") && !a.equals("asientos"))
            return List.of();
        if (args.length == 2)
            return List.of("add", "remove", "list", "clear");
        return args.length == 3 ? tableNames() : List.of();
    }

    @Override
    public List<String> stationTabComplete(Player player, String[] args) {
        return args.length == 1 ? List.of("[name]") : List.of();
    }
}
