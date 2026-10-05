package com.gamblingdex.games.poker;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.games.blackjack.BlackjackTables;
import com.gamblingdex.games.blackjack.Card;
import com.gamblingdex.gui.PokerActionMenu;
import com.gamblingdex.gui.PokerActionMenuHolder;
import com.gamblingdex.gui.PokerBuyInMenu;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.text.NumberFormat;
import java.util.*;
import java.util.function.IntPredicate;

/**
 * Mesa física de Texas Hold'em No-Limit.
 *
 * Flujo: los jugadores se paran en los asientos, compran fichas para la mesa
 * (buy-in con tokens), y con 2+ listos arranca la mano: ciegas, 2 cartas
 * privadas, preflop/flop/turn/river con rondas de apuestas, showdown y reparto
 * de botes (principal + laterales). Al bajarse del asiento se devuelven las
 * fichas como tokens.
 *
 * Todo el estado vive en el hilo principal; {@link #tick()} se llama cada
 * segundo desde {@link PokerManager}.
 */
public class PokerTable {

    public enum State {
        WAITING,
        STARTING,
        BETTING,
        RUNOUT,
        SHOWDOWN
    }

    public enum Street {
        PREFLOP,
        FLOP,
        TURN,
        RIVER
    }

    private static final SecureRandom RNG = new SecureRandom();

    static final class Seat {
        final String key;
        UUID player;
        String name;
        long stack;
        long invested; // fichas compradas en esta sesión (para la ganancia del jugador)
        boolean boughtIn;
        boolean leaving;
        boolean forceOut;
        int missedTurns;
        long offSeatSince;

        Seat(String key) {
            this.key = key;
        }

        void clearPlayer() {
            player = null;
            name = null;
            stack = 0L;
            invested = 0L;
            boughtIn = false;
            leaving = false;
            forceOut = false;
            missedTurns = 0;
            offSeatSince = 0L;
        }
    }

    private final GamblingDexPlugin plugin;
    private final PokerManager manager;
    private final String tableKey;
    private final Location center;
    private String name;
    private long smallBlind;
    private long bigBlind;

    private final List<Seat> seats = new ArrayList<>();

    // ---- Estado de la mano ----
    private State state = State.WAITING;
    private Street street;
    private int handNumber;
    private int countdown;
    private final List<Integer> dealt = new ArrayList<>();
    private final Map<Integer, List<Card>> hole = new HashMap<>();
    private final Set<Integer> folded = new HashSet<>();
    private final Set<Integer> allIn = new HashSet<>();
    private final Set<Integer> acted = new HashSet<>();
    private final Map<Integer, Long> streetBet = new HashMap<>();
    private final Map<Integer, Long> contributed = new HashMap<>();
    private final List<Card> board = new ArrayList<>();
    private Deque<Card> deck;
    private long currentBet;
    private long lastRaiseSize;
    private int button = -1;
    private int sbSeat = -1;
    private int bbSeat = -1;
    private int actor = -1;
    private boolean flopSeen;
    private boolean revealAll;
    private final Map<Integer, String> showdownText = new HashMap<>();
    private final Map<Integer, Long> lastWin = new HashMap<>();
    private final Map<UUID, Long> raiseTarget = new HashMap<>();
    private String lastAction = "";

    private BukkitTask menuTask;

    // ---- Hologramas (no persistentes: se recrean solos) ----
    private UUID boardDisplayId;
    private final Map<Integer, UUID> seatDisplayIds = new HashMap<>();
    // A quién sigue cada holograma de asiento (null = asiento libre).
    private final Map<Integer, UUID> seatDisplayOwner = new HashMap<>();
    private final Map<UUID, Long> buyInPromptAt = new HashMap<>();

    public PokerTable(GamblingDexPlugin plugin, PokerManager manager, String tableKey, Location center, String name,
            long smallBlind, long bigBlind) {
        this.plugin = plugin;
        this.manager = manager;
        this.tableKey = tableKey;
        this.center = center;
        this.name = name;
        this.smallBlind = Math.max(1L, smallBlind);
        this.bigBlind = Math.max(this.smallBlind, bigBlind);
    }

    // =====================================================================
    // Datos básicos
    // =====================================================================

    public String getTableKey() {
        return tableKey;
    }

    public String getName() {
        return name;
    }

    /** Nombre para mostrar: poker.table_names.<table> de modules/poker.yml, o el nombre normal. */
    public String getDisplayName() {
        String pretty = plugin.tableNameFromConfig("poker", name);
        return pretty != null ? pretty : name;
    }

    public Location getCenter() {
        return center;
    }

    public long getSmallBlind() {
        return smallBlind;
    }

    public long getBigBlind() {
        return bigBlind;
    }

    public State getState() {
        return state;
    }

    public boolean isCenter(Block block) {
        return block != null && Objects.equals(BlackjackTables.key(block.getLocation()), tableKey);
    }

    public boolean isHandRunning() {
        return state == State.BETTING || state == State.RUNOUT || state == State.SHOWDOWN;
    }

    /** Cambia las ciegas. Solo entre manos. */
    public boolean setStakes(long sb, long bb) {
        if (isHandRunning())
            return false;
        this.smallBlind = Math.max(1L, sb);
        this.bigBlind = Math.max(this.smallBlind, bb);
        updateDisplays();
        return true;
    }

    public List<String> getSeatKeys() {
        List<String> out = new ArrayList<>();
        for (Seat s : seats)
            out.add(s.key);
        return out;
    }

    /**
     * Redefine los asientos. Solo entre manos; los jugadores sentados en asientos
     * eliminados reciben sus fichas.
     */
    public boolean setSeatKeys(List<String> keys) {
        if (isHandRunning())
            return false;

        Map<String, Seat> old = new LinkedHashMap<>();
        for (Seat s : seats)
            old.put(s.key, s);

        List<Seat> rebuilt = new ArrayList<>();
        for (String k : keys) {
            if (k == null || k.isBlank())
                continue;
            Seat s = old.remove(k.trim());
            rebuilt.add(s != null ? s : new Seat(k.trim()));
        }
        // Asientos borrados con jugador: devolver fichas.
        for (Seat s : old.values()) {
            if (s.player != null) {
                payOut(s.player, s.stack, false);
                recordSession(s, s.stack);
                s.clearPlayer();
            }
        }

        removeSeatDisplays();
        seats.clear();
        seats.addAll(rebuilt);
        button = -1;
        if (state == State.STARTING && eligibleCount() < minPlayers()) {
            state = State.WAITING;
        }
        updateDisplays();
        return true;
    }

    // =====================================================================
    // Configuración
    // =====================================================================

    private int cfgInt(String path, int def, int min, int max) {
        int v = plugin.getConfig().getInt("poker." + path, def);
        return Math.max(min, Math.min(max, v));
    }

    private boolean cfgBool(String path, boolean def) {
        return plugin.getConfig().getBoolean("poker." + path, def);
    }

    private int minPlayers() {
        return cfgInt("min_players", 2, 2, 10);
    }

    public long getMinBuyIn() {
        return bigBlind * cfgInt("buyin.min_bb", 20, 1, 10_000);
    }

    public long getMaxBuyIn() {
        return Math.max(getMinBuyIn(), bigBlind * cfgInt("buyin.max_bb", 100, 1, 100_000));
    }

    private long ante() {
        return Math.max(0L, plugin.getConfig().getLong("poker.ante", 0L));
    }

    // =====================================================================
    // Tick (cada segundo)
    // =====================================================================

    public void tick() {
        scanSeats();

        switch (state) {
            case WAITING -> maybeStartCountdown();
            case STARTING -> {
                if (eligibleCount() < minPlayers()) {
                    state = State.WAITING;
                    broadcast(msg("not_enough_players", "&7No hay suficientes jugadores. Esperando..."));
                } else if (--countdown <= 0) {
                    startHand();
                }
            }
            case BETTING -> tickBetting();
            case RUNOUT -> {
                if (--countdown <= 0)
                    runoutStep();
            }
            case SHOWDOWN -> {
                if (--countdown <= 0)
                    finishHand();
            }
        }

        sendActionBars();
        updateDisplays();
    }

    private void tickBetting() {
        if (actor < 0) {
            endStreet();
            return;
        }
        Seat s = seats.get(actor);
        Player p = s.player == null ? null : Bukkit.getPlayer(s.player);
        if (p == null) {
            doFold(actor, true);
            return;
        }
        spawnTurnMarker(p);
        if (--countdown > 0)
            return;

        // Tiempo agotado: pasar si se puede, si no retirarse.
        s.missedTurns++;
        int maxMissed = cfgInt("max_missed_turns", 2, 1, 100);
        if (s.missedTurns >= maxMissed) {
            s.leaving = true;
            s.forceOut = true;
            p.sendMessage(msg("inactive_kick", "&cEstuviste inactivo. Saldrás de la mesa al terminar la mano."));
        } else {
            p.sendMessage(msg("timeout", "&7Se acabó tu tiempo."));
        }
        closeActionMenu(p);
        if (bet(actor) >= currentBet) {
            doCheck(actor);
        } else {
            doFold(actor, false);
        }
    }

    // =====================================================================
    // Asientos
    // =====================================================================

    private Block seatBlock(int i) {
        Location l = BlackjackTables.parseKey(seats.get(i).key);
        if (l == null || l.getWorld() == null)
            return null;
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4))
            return null;
        return l.getBlock();
    }

    private Player findPlayerOnSeat(Block seatBlock) {
        if (seatBlock == null)
            return null;
        Location seatCenter = seatBlock.getLocation().add(0.5, 0.5, 0.5);
        for (Player p : seatBlock.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(seatCenter) > 4.0)
                continue;
            Block feet = p.getLocation().getBlock();
            if (feet.equals(seatBlock) || feet.getRelative(BlockFace.DOWN).equals(seatBlock)) {
                return p;
            }
        }
        return null;
    }

    private boolean isOnOwnSeat(int i, Player p) {
        Player occ = findPlayerOnSeat(seatBlock(i));
        return occ != null && p != null && occ.getUniqueId().equals(p.getUniqueId());
    }

    private void scanSeats() {
        long now = System.currentTimeMillis();
        long graceMs = cfgInt("leave_grace_seconds", 4, 0, 120) * 1000L;

        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            Block b = seatBlock(i);
            if (b == null)
                continue;
            Player occ = findPlayerOnSeat(b);

            if (s.player == null) {
                if (occ != null && manager.getTableOf(occ.getUniqueId()) == null) {
                    sit(i, occ);
                }
                continue;
            }

            Player p = Bukkit.getPlayer(s.player);
            if (p == null) {
                handleQuit(s.player);
                continue;
            }

            boolean present = occ != null && occ.getUniqueId().equals(s.player);
            if (present) {
                s.offSeatSince = 0L;
                continue;
            }
            if (s.leaving)
                continue;
            if (s.offSeatSince == 0L) {
                s.offSeatSince = now;
                if (graceMs > 0) {
                    p.sendMessage(msg("off_seat_warning",
                            "&eTe bajaste del asiento. Vuelve en &f{seconds}s &eo te levantarás de la mesa.",
                            "seconds", String.valueOf(graceMs / 1000L)));
                    continue;
                }
            }
            if (now - s.offSeatSince >= graceMs) {
                standUp(i);
            }
        }
    }

    private void sit(int i, Player p) {
        if (plugin.getMaintenance() != null && !plugin.getMaintenance().allow(p, "poker"))
            return;
        if (tournament != null && tournament.started) {
            // Torneo en curso: no entra nadie nuevo.
            long now = System.currentTimeMillis();
            Long last = buyInPromptAt.get(p.getUniqueId());
            if (last == null || now - last > 10_000L) {
                buyInPromptAt.put(p.getUniqueId(), now);
                p.sendMessage(msg("tournament.running", "&cHay un torneo en curso en esta mesa. Espera a que termine."));
            }
            return;
        }
        Seat s = seats.get(i);
        s.clearPlayer();
        s.player = p.getUniqueId();
        s.name = p.getName();

        if (tournament != null) {
            p.sendMessage(msg("tournament.sat",
                    "&d&lTORNEO &8» &7Inscripción: &e{fee} &7→ recibes &f{stack} &7fichas de torneo. Abre el menú para inscribirte.",
                    "fee", units(tournament.entryFee), "stack", units(tournament.startingStack)));
            promptBuyIn(p);
            updateDisplays();
            return;
        }

        p.sendMessage(msg("sat_down",
                "&aTe sentaste en la mesa de póker &f{table}&a. Ciegas &e{sb}/{bb}&a. Compra entre &e{min}&a y &e{max}&a fichas.",
                "table", getDisplayName(), "sb", units(smallBlind), "bb", units(bigBlind),
                "min", units(getMinBuyIn()), "max", units(getMaxBuyIn())));
        p.playSound(p.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 0.6f, 1.4f);
        broadcastExcept(p.getUniqueId(), msg("player_sat", "&7{player} se sentó en la mesa.", "player", p.getName()));
        promptBuyIn(p);
        updateDisplays();
    }

    private void promptBuyIn(Player p) {
        // Al apagar no se pueden programar tareas (y no tiene sentido abrir menús).
        if (!plugin.isEnabled())
            return;
        long now = System.currentTimeMillis();
        Long last = buyInPromptAt.get(p.getUniqueId());
        if (last != null && now - last < 5000L)
            return;
        buyInPromptAt.put(p.getUniqueId(), now);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && seatOf(p.getUniqueId()) >= 0 && !hasMenuOpen(p)) {
                new PokerBuyInMenu(plugin).open(p, this);
            }
        });
    }

    /** El jugador se bajó del asiento: si está en la mano, se retira al terminar. */
    private void standUp(int i) {
        Seat s = seats.get(i);
        if (s.player == null)
            return;
        Player p = Bukkit.getPlayer(s.player);
        if (isHandRunning() && dealt.contains(i)) {
            s.leaving = true;
            if (p != null) {
                p.sendMessage(msg("leaving_after_hand",
                        "&eTe levantaste. Recibirás tus fichas al terminar la mano."));
            }
            if (!folded.contains(i) && !allIn.contains(i) && state != State.SHOWDOWN) {
                foldOutOfTurn(i);
            }
            return;
        }
        cashOut(i, false);
    }

    /** Llamado desde el listener cuando el jugador se desconecta. */
    public void handleQuit(UUID playerId) {
        int i = seatOf(playerId);
        if (i < 0)
            return;
        Seat s = seats.get(i);
        if (isHandRunning() && dealt.contains(i)) {
            s.leaving = true;
            s.forceOut = true;
            if (!folded.contains(i) && !allIn.contains(i) && state != State.SHOWDOWN) {
                foldOutOfTurn(i);
            }
            return;
        }
        cashOut(i, true);
    }

    private void cashOut(int i, boolean forcePending) {
        Seat s = seats.get(i);
        if (s.player == null)
            return;
        if (tournament != null) {
            // Fichas de torneo: no se cobran. Salir = devolver inscripción o quedar eliminado.
            tournamentLeave(i);
            return;
        }
        UUID id = s.player;
        String pname = s.name;
        long amount = s.stack;
        recordSession(s, amount);
        s.clearPlayer();
        buyInPromptAt.remove(id);

        payOut(id, amount, forcePending);
        broadcast(msg("player_left", "&7{player} se levantó de la mesa.", "player", pname == null ? "?" : pname));
        updateDisplays();
    }

    /** Estadísticas: ganancia de la sesión = lo que se lleva - lo que compró (solo mesas normales). */
    private void recordSession(Seat s, long amount) {
        var st = plugin.getGameStats();
        if (st != null && s.player != null && s.invested > 0)
            st.pokerSession(s.player, amount - s.invested);
    }

    private void payOut(UUID id, long amount, boolean forcePending) {
        Player p = Bukkit.getPlayer(id);
        if (amount <= 0) {
            if (p != null)
                p.sendMessage(msg("left_table", "&7Saliste de la mesa de póker."));
            return;
        }
        if (!forcePending && p != null && p.isOnline()) {
            plugin.getTokenPayout().pay(p, amount);
            p.sendMessage(msg("cashed_out", "&aSaliste de la mesa con &e{amount}&a en fichas.",
                    "amount", units(amount)));
        } else {
            manager.addPending(id, amount);
        }
    }

    public int seatOf(UUID playerId) {
        if (playerId == null)
            return -1;
        for (int i = 0; i < seats.size(); i++) {
            if (playerId.equals(seats.get(i).player))
                return i;
        }
        return -1;
    }

    public boolean isSeated(UUID playerId) {
        return seatOf(playerId) >= 0;
    }

    private boolean isEligible(int i) {
        Seat s = seats.get(i);
        if (s.player == null || !s.boughtIn || s.stack <= 0 || s.leaving)
            return false;
        Player p = Bukkit.getPlayer(s.player);
        return p != null && p.isOnline();
    }

    private int eligibleCount() {
        int c = 0;
        for (int i = 0; i < seats.size(); i++) {
            if (isEligible(i))
                c++;
        }
        return c;
    }

    /** Jugadores sentados ahora (para los carteles). */
    public int getSeatedCount() {
        return seatedCount();
    }

    public int getSeatCount() {
        return seats.size();
    }

    private int seatedCount() {
        int c = 0;
        for (Seat s : seats) {
            if (s.player != null)
                c++;
        }
        return c;
    }

    // =====================================================================
    // Buy-in
    // =====================================================================

    public long getStack(UUID playerId) {
        int i = seatOf(playerId);
        return i < 0 ? 0L : seats.get(i).stack;
    }

    /** Cuántas unidades más puede meter el jugador sin pasarse del máximo. */
    public long getRoomToMax(UUID playerId) {
        int i = seatOf(playerId);
        if (i < 0)
            return 0L;
        return Math.max(0L, getMaxBuyIn() - seats.get(i).stack);
    }

    /** Motivo por el que no puede comprar fichas ahora, o null si puede. */
    public String depositDenyReason(UUID playerId) {
        int i = seatOf(playerId);
        if (i < 0)
            return msg("not_seated", "&cPárate en un asiento de la mesa para jugar.");
        if (tournament != null)
            return msg("tournament.no_deposit", "&cEn el torneo no se compran fichas: todos empiezan con las mismas.");
        if (isHandRunning() && dealt.contains(i))
            return msg("deposit_in_hand", "&cNo puedes añadir fichas en medio de una mano.");
        if (getRoomToMax(playerId) <= 0)
            return msg("deposit_max", "&cYa tienes el máximo de la mesa (&e{max}&c).", "max", units(getMaxBuyIn()));
        return null;
    }

    /**
     * Mete fichas (ya quitadas del inventario) al stack de la mesa. Devuelve
     * false si no se pudo; en ese caso quien llama debe devolver los tokens.
     */
    public boolean deposit(Player player, long amount) {
        if (player == null || amount <= 0)
            return false;
        String deny = depositDenyReason(player.getUniqueId());
        if (deny != null) {
            player.sendMessage(deny);
            return false;
        }
        if (amount > getRoomToMax(player.getUniqueId())) {
            player.sendMessage(msg("deposit_max", "&cYa tienes el máximo de la mesa (&e{max}&c).",
                    "max", units(getMaxBuyIn())));
            return false;
        }

        Seat s = seats.get(seatOf(player.getUniqueId()));
        s.stack += amount;
        if (tournament == null)
            s.invested += amount;
        boolean wasBoughtIn = s.boughtIn;
        if (s.stack >= getMinBuyIn())
            s.boughtIn = true;

        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.7f, 1.6f);
        if (s.boughtIn) {
            player.sendMessage(msg("deposited", "&aFichas en la mesa: &e{stack}",
                    "stack", units(s.stack)));
            if (!wasBoughtIn) {
                broadcastExcept(player.getUniqueId(), msg("player_bought_in",
                        "&7{player} compró &e{amount}&7 en fichas.", "player", player.getName(),
                        "amount", units(s.stack)));
            }
        } else {
            player.sendMessage(msg("deposited_need_min",
                    "&eFichas en la mesa: &f{stack}&e. Necesitas al menos &f{min}&e para jugar.",
                    "stack", units(s.stack), "min", units(getMinBuyIn())));
        }
        updateDisplays();
        return true;
    }

    // =====================================================================
    // Inicio de mano
    // =====================================================================

    private void maybeStartCountdown() {
        if (tournament != null && !tournament.started)
            return; // inscripción abierta: arranca cuando el admin lo indique
        if (eligibleCount() < minPlayers())
            return;
        state = State.STARTING;
        countdown = cfgInt("start_delay_seconds", 8, 1, 120);
        broadcast(msg("hand_starting", "&eNueva mano en &f{seconds}s&e...", "seconds", String.valueOf(countdown)));
    }

    private int nextSeat(int from, IntPredicate pred) {
        int n = seats.size();
        if (n == 0)
            return -1;
        for (int k = 1; k <= n; k++) {
            int i = Math.floorMod(from + k, n);
            if (pred.test(i))
                return i;
        }
        return -1;
    }

    private void startHand() {
        clearHand();

        for (int i = 0; i < seats.size(); i++) {
            if (isEligible(i))
                dealt.add(i);
        }
        if (dealt.size() < minPlayers()) {
            dealt.clear();
            state = State.WAITING;
            return;
        }

        handNumber++;
        button = nextSeat(button, dealt::contains);
        if (dealt.size() == 2) {
            // Heads-up: el botón pone la ciega chica.
            sbSeat = button;
            bbSeat = nextSeat(button, dealt::contains);
        } else {
            sbSeat = nextSeat(button, dealt::contains);
            bbSeat = nextSeat(sbSeat, dealt::contains);
        }

        deck = new ArrayDeque<>(buildShuffledDeck());

        long ante = ante();
        if (ante > 0) {
            for (int i : dealt) {
                long a = Math.min(ante, seats.get(i).stack);
                seats.get(i).stack -= a;
                contributed.merge(i, a, Long::sum);
                if (seats.get(i).stack == 0)
                    allIn.add(i);
            }
        }
        if (tournament != null)
            applyTournamentLevel();
        postBlind(sbSeat, smallBlind);
        postBlind(bbSeat, bigBlind);
        currentBet = bigBlind;
        lastRaiseSize = bigBlind;

        // Reparte 2 cartas a cada uno empezando por la ciega chica.
        for (int round = 0; round < 2; round++) {
            int i = sbSeat;
            for (int k = 0; k < dealt.size(); k++) {
                hole.computeIfAbsent(i, x -> new ArrayList<>()).add(deck.pollFirst());
                i = nextSeat(i, dealt::contains);
            }
        }

        street = Street.PREFLOP;
        state = State.BETTING;
        lastAction = "";

        broadcast(msg("hand_started",
                "&6&lPóker &8» &7Mano &f#{hand} &8| &7Botón: &f{button} &8| &7Ciegas: &f{sbname} &8(&e{sb}&8) &7y &f{bbname} &8(&e{bb}&8)",
                "hand", String.valueOf(handNumber), "button", seatName(button),
                "sbname", seatName(sbSeat), "sb", units(smallBlind),
                "bbname", seatName(bbSeat), "bb", units(bigBlind)));

        for (int i : dealt) {
            Player p = Bukkit.getPlayer(seats.get(i).player);
            if (p == null)
                continue;
            // Solo en la pantalla del jugador (título), nunca en el mundo.
            p.sendTitle(plugin.color(formatCards(hole.get(i))),
                    plugin.color(msg("your_cards_subtitle", "&7{hand}", "hand", getHandStrength(p.getUniqueId()))),
                    5, 40, 10);
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
        }

        int first = nextActor(bbSeat);
        if (first < 0) {
            endStreet();
        } else {
            beginTurn(first);
        }
        updateDisplays();
    }

    private void postBlind(int i, long blind) {
        Seat s = seats.get(i);
        long amt = Math.min(blind, s.stack);
        put(i, amt);
    }

    private List<Card> buildShuffledDeck() {
        List<Card> cards = new ArrayList<>(52);
        for (Card.Suit s : Card.Suit.values()) {
            for (Card.Rank r : Card.Rank.values()) {
                cards.add(new Card(r, s));
            }
        }
        Collections.shuffle(cards, RNG);
        return cards;
    }

    // =====================================================================
    // Rondas de apuestas
    // =====================================================================

    private long bet(int i) {
        return streetBet.getOrDefault(i, 0L);
    }

    private void put(int i, long amount) {
        if (amount <= 0)
            return;
        Seat s = seats.get(i);
        amount = Math.min(amount, s.stack);
        s.stack -= amount;
        streetBet.merge(i, amount, Long::sum);
        contributed.merge(i, amount, Long::sum);
        if (s.stack == 0)
            allIn.add(i);
    }

    private boolean isLive(int i) {
        return dealt.contains(i) && !folded.contains(i);
    }

    private int liveCount() {
        int c = 0;
        for (int i : dealt) {
            if (!folded.contains(i))
                c++;
        }
        return c;
    }

    private int canActCount() {
        int c = 0;
        for (int i : dealt) {
            if (!folded.contains(i) && !allIn.contains(i))
                c++;
        }
        return c;
    }

    private boolean needsToAct(int i) {
        if (!dealt.contains(i) || folded.contains(i) || allIn.contains(i))
            return false;
        if (bet(i) < currentBet)
            return true;
        if (acted.contains(i))
            return false;
        // Si nadie más puede responder, no hace falta actuar.
        return canActCount() > 1;
    }

    private int nextActor(int after) {
        return nextSeat(after, this::needsToAct);
    }

    /**
     * Puede subir si no actuó desde la última subida completa (una subida all-in
     * incompleta no reabre la apuesta), tiene fichas para superar la apuesta y
     * queda alguien que pueda responder.
     */
    private boolean canRaiseSeat(int i) {
        if (acted.contains(i))
            return false;
        if (bet(i) + seats.get(i).stack <= currentBet)
            return false;
        for (int j : dealt) {
            if (j != i && !folded.contains(j) && !allIn.contains(j))
                return true;
        }
        return false;
    }

    private long minRaiseToSeat(int i) {
        long maxTo = bet(i) + seats.get(i).stack;
        long minTo = currentBet == 0 ? bigBlind : currentBet + lastRaiseSize;
        return Math.min(maxTo, minTo);
    }

    private void beginTurn(int i) {
        actor = i;
        countdown = cfgInt("turn_timeout_seconds", 30, 5, 600);
        Seat s = seats.get(i);
        Player p = Bukkit.getPlayer(s.player);
        if (p == null) {
            doFold(i, true);
            return;
        }
        raiseTarget.put(s.player, minRaiseToSeat(i));

        long call = Math.max(0L, currentBet - bet(i));
        if (call > 0) {
            p.sendMessage(msg("your_turn_call", "&a&l¡Tu turno! &7Para seguir debes pagar &e{call}&7. (&f{seconds}s&7)",
                    "call", units(Math.min(call, s.stack)), "seconds", String.valueOf(countdown)));
        } else {
            p.sendMessage(msg("your_turn_check", "&a&l¡Tu turno! &7Puedes pasar o apostar. (&f{seconds}s&7)",
                    "seconds", String.valueOf(countdown)));
        }
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
        scheduleMenu(p, cfgInt("action_menu_delay_seconds", 1, 0, 30));
        updateDisplays();
    }

    private void scheduleMenu(Player p, int delaySeconds) {
        cancelMenuTask();
        UUID id = p.getUniqueId();
        Runnable open = () -> {
            menuTask = null;
            Player pp = Bukkit.getPlayer(id);
            if (pp != null && isTurn(id) && !hasMenuOpen(pp)) {
                new PokerActionMenu(plugin).open(pp, this);
            }
        };
        if (delaySeconds <= 0) {
            menuTask = Bukkit.getScheduler().runTask(plugin, open);
        } else {
            menuTask = Bukkit.getScheduler().runTaskLater(plugin, open, delaySeconds * 20L);
        }
    }

    private void cancelMenuTask() {
        if (menuTask != null) {
            menuTask.cancel();
            menuTask = null;
        }
    }

    /** El jugador cerró el menú de acción sin actuar: reabrirlo en unos segundos. */
    public void onActionMenuClosed(Player p) {
        if (p == null || !isTurn(p.getUniqueId()))
            return;
        int secs = cfgInt("menu_reopen_seconds", 3, 0, 60);
        p.sendMessage(msg("menu_reopen_hint",
                "&7Menú cerrado. Click derecho a la mesa para abrirlo (se reabre en &f{seconds}s&7).",
                "seconds", String.valueOf(secs)));
        scheduleMenu(p, secs);
    }

    /** Abre el menú adecuado: acción si es su turno, compra de fichas si no. */
    public void openMenu(Player p) {
        if (p == null)
            return;
        if (isTurn(p.getUniqueId())) {
            cancelMenuTask();
            new PokerActionMenu(plugin).open(p, this);
            return;
        }
        if (isSeated(p.getUniqueId())) {
            new PokerBuyInMenu(plugin).open(p, this);
            return;
        }
        p.sendMessage(msg("not_seated", "&cPárate en un asiento de la mesa para jugar."));
    }

    /**
     * Acción elegida en el menú. {@code action}: fold, check (pasa o paga), raise
     * (usa el monto seleccionado), allin.
     */
    public void handleAction(Player p, String action) {
        if (p == null)
            return;
        UUID id = p.getUniqueId();
        if (state != State.BETTING) {
            p.sendMessage(msg("not_betting", "&cNo hay apuestas en curso."));
            return;
        }
        if (!isTurn(id)) {
            p.sendMessage(msg("not_your_turn", "&cNo es tu turno."));
            return;
        }
        int i = actor;
        seats.get(i).missedTurns = 0;
        cancelMenuTask();

        switch (action) {
            case "fold" -> doFold(i, false);
            case "check" -> {
                if (bet(i) >= currentBet)
                    doCheck(i);
                else
                    doCall(i);
            }
            case "raise" -> {
                long target = raiseTarget.getOrDefault(id, minRaiseToSeat(i));
                if (!doRaise(i, target)) {
                    scheduleMenu(p, 0);
                }
            }
            case "allin" -> doAllIn(i);
            default -> scheduleMenu(p, 0);
        }
    }

    private void doFold(int i, boolean silent) {
        folded.add(i);
        acted.add(i);
        if (!silent)
            announce(i, msg("act_fold", "&c{player} se retira.", "player", seatName(i)), Sound.BLOCK_WOOL_BREAK);
        afterAction(i);
    }

    private void doCheck(int i) {
        acted.add(i);
        announce(i, msg("act_check", "&7{player} pasa.", "player", seatName(i)), Sound.BLOCK_WOOD_HIT);
        afterAction(i);
    }

    private void doCall(int i) {
        long call = Math.min(currentBet - bet(i), seats.get(i).stack);
        put(i, call);
        acted.add(i);
        if (allIn.contains(i)) {
            announce(i, msg("act_call_allin", "&6{player} paga &e{amount} &6y queda ALL-IN.",
                    "player", seatName(i), "amount", units(call)), Sound.BLOCK_CHAIN_PLACE);
        } else {
            announce(i, msg("act_call", "&f{player} paga &e{amount}&f.",
                    "player", seatName(i), "amount", units(call)), Sound.BLOCK_CHAIN_PLACE);
        }
        afterAction(i);
    }

    private boolean doRaise(int i, long target) {
        Seat s = seats.get(i);
        Player p = Bukkit.getPlayer(s.player);
        long maxTo = bet(i) + s.stack;
        if (!canRaiseSeat(i)) {
            if (p != null)
                p.sendMessage(msg("cannot_raise", "&cNo puedes subir ahora: solo pagar o retirarte."));
            return false;
        }
        if (target >= maxTo) {
            doAllIn(i);
            return true;
        }
        long minTo = currentBet == 0 ? bigBlind : currentBet + lastRaiseSize;
        if (target < minTo) {
            if (p != null)
                p.sendMessage(msg("raise_too_small", "&cLa subida mínima es a &e{min}&c.", "min", units(minTo)));
            return false;
        }

        boolean isBet = currentBet == 0;
        put(i, target - bet(i));
        lastRaiseSize = target - currentBet;
        currentBet = target;
        acted.clear();
        acted.add(i);
        if (isBet) {
            announce(i, msg("act_bet", "&e{player} apuesta &f{amount}&e.", "player", seatName(i),
                    "amount", units(target)), Sound.BLOCK_CHAIN_PLACE);
        } else {
            announce(i, msg("act_raise", "&e{player} sube a &f{amount}&e.", "player", seatName(i),
                    "amount", units(target)), Sound.BLOCK_CHAIN_PLACE);
        }
        afterAction(i);
        return true;
    }

    private void doAllIn(int i) {
        Seat s = seats.get(i);
        long target = bet(i) + s.stack;
        if (target > currentBet) {
            if (!canRaiseSeat(i)) {
                // La apuesta no está reabierta: el all-in solo puede pagar.
                doCall(i);
                return;
            }
            long raiseSize = target - currentBet;
            if (raiseSize >= lastRaiseSize) {
                lastRaiseSize = raiseSize;
                acted.clear();
            }
            currentBet = target;
        }
        long amount = s.stack;
        put(i, amount);
        acted.add(i);
        announce(i, msg("act_allin", "&6&l{player} va ALL-IN &e({amount})&6&l!",
                "player", seatName(i), "amount", units(target)), Sound.ENTITY_BLAZE_SHOOT);
        afterAction(i);
    }

    /** Retiro fuera de turno (desconexión o se levantó del asiento). */
    private void foldOutOfTurn(int i) {
        if (state == State.BETTING && actor == i) {
            doFold(i, false);
            return;
        }
        folded.add(i);
        acted.add(i);
        broadcast(msg("act_fold", "&c{player} se retira.", "player", seatName(i)));
        if (liveCount() <= 1) {
            awardUncontested();
        }
    }

    private void afterAction(int i) {
        if (actor == i) {
            Player p = Bukkit.getPlayer(seats.get(i).player);
            if (p != null)
                closeActionMenu(p);
            actor = -1;
        }
        if (liveCount() <= 1) {
            awardUncontested();
            return;
        }
        int next = nextActor(i);
        if (next < 0) {
            endStreet();
        } else {
            beginTurn(next);
        }
        updateDisplays();
    }

    private void endStreet() {
        streetBet.clear();
        currentBet = 0L;
        lastRaiseSize = bigBlind;
        acted.clear();
        actor = -1;

        if (liveCount() <= 1) {
            awardUncontested();
            return;
        }
        if (street == Street.RIVER) {
            resolve(true);
            return;
        }
        if (canActCount() <= 1) {
            startRunout();
            return;
        }

        dealNextStreet();
        int first = nextActor(button);
        if (first < 0) {
            endStreet();
        } else {
            beginTurn(first);
        }
    }

    private void dealNextStreet() {
        deck.pollFirst(); // quemar
        switch (street) {
            case PREFLOP -> {
                board.add(deck.pollFirst());
                board.add(deck.pollFirst());
                board.add(deck.pollFirst());
                street = Street.FLOP;
                flopSeen = true;
            }
            case FLOP -> {
                board.add(deck.pollFirst());
                street = Street.TURN;
            }
            case TURN -> {
                board.add(deck.pollFirst());
                street = Street.RIVER;
            }
            default -> {
            }
        }

        String label = switch (street) {
            case FLOP -> "Flop";
            case TURN -> "Turn";
            case RIVER -> "River";
            default -> "";
        };
        broadcast(msg("street", "&6&l{street} &8» {board} &8| &7Bote: &e{pot}",
                "street", label, "board", formatCards(board), "pot", units(getPot())));
        for (int i : dealt) {
            Player p = Bukkit.getPlayer(seats.get(i).player);
            if (p != null)
                p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.2f);
        }
        updateDisplays();
    }

    /** Todos (menos uno) están all-in: se muestran las cartas y se reparte el resto. */
    private void startRunout() {
        revealAll = true;
        state = State.RUNOUT;
        actor = -1;
        cancelMenuTask();
        broadcast(msg("runout", "&6&lALL-IN! &7Se muestran las cartas:"));
        for (int i : dealt) {
            if (folded.contains(i))
                continue;
            broadcast(msg("runout_hand", "&8- &f{player}&8: {cards}",
                    "player", seatName(i), "cards", formatCards(hole.get(i))));
        }
        countdown = cfgInt("runout_delay_seconds", 2, 1, 30);
        updateDisplays();
    }

    private void runoutStep() {
        if (street == Street.RIVER || board.size() >= 5) {
            resolve(true);
            return;
        }
        dealNextStreet();
        // Tras el river, el siguiente paso resuelve.
        countdown = cfgInt("runout_delay_seconds", 2, 1, 30);
    }

    // =====================================================================
    // Resolución
    // =====================================================================

    private void awardUncontested() {
        resolve(false);
    }

    private void resolve(boolean showdown) {
        cancelMenuTask();
        closeAllActionMenus();
        actor = -1;
        streetBet.clear();

        returnUncalledBet();

        List<PokerPots.Pot> pots = new ArrayList<>(PokerPots.build(contributed, folded));
        long total = 0L;
        for (PokerPots.Pot pot : pots)
            total += pot.amount();

        long rake = computeRake(total);
        if (rake > 0) {
            long left = rake;
            for (int k = 0; k < pots.size() && left > 0; k++) {
                PokerPots.Pot pot = pots.get(k);
                long take = Math.min(left, pot.amount());
                pots.set(k, new PokerPots.Pot(pot.amount() - take, pot.eligible()));
                left -= take;
            }
            manager.addRake(rake);
        }

        Map<Integer, PokerHandEvaluator.Result> results = new HashMap<>();
        if (showdown) {
            revealAll = true;
            for (int i : dealt) {
                if (folded.contains(i))
                    continue;
                List<Card> all = new ArrayList<>(hole.get(i));
                all.addAll(board);
                PokerHandEvaluator.Result r = PokerHandEvaluator.evaluate(all);
                results.put(i, r);
                showdownText.put(i, formatCards(hole.get(i)) + " §e" + r.name());
            }
            broadcast(msg("showdown", "&6&lShowdown &8» &7Mesa: {board}", "board", formatCards(board)));
            for (int i : results.keySet()) {
                broadcast(msg("showdown_hand", "&8- &f{player}&8: {cards} &8→ &e{hand}",
                        "player", seatName(i), "cards", formatCards(hole.get(i)), "hand", results.get(i).name()));
            }
        }

        StringBuilder log = new StringBuilder();
        for (int k = 0; k < pots.size(); k++) {
            PokerPots.Pot pot = pots.get(k);
            if (pot.amount() <= 0 || pot.eligible().isEmpty())
                continue;

            List<Integer> winners = new ArrayList<>();
            if (!showdown || pot.eligible().size() == 1) {
                winners.addAll(pot.eligible());
            } else {
                long best = Long.MIN_VALUE;
                for (int i : pot.eligible()) {
                    long sc = results.get(i).score();
                    if (sc > best) {
                        best = sc;
                        winners.clear();
                        winners.add(i);
                    } else if (sc == best) {
                        winners.add(i);
                    }
                }
            }
            distribute(pot.amount(), winners);

            String potName = k == 0
                    ? msg("pot_main", "Bote principal")
                    : msg("pot_side", "Bote lateral {n}", "n", String.valueOf(k));
            List<String> names = new ArrayList<>();
            for (int w : winners)
                names.add(seatName(w));
            String who = String.join(", ", names);

            if (!showdown) {
                broadcast(msg("win_uncontested", "&a&l{player} &agana &e{amount}&a. &7(todos se retiraron)",
                        "player", who, "amount", units(pot.amount())));
            } else if (winners.size() > 1) {
                broadcast(msg("win_split", "&a{pot}&7: &a&l{players} &areparten &e{amount} &7({hand})",
                        "pot", potName, "players", who, "amount", units(pot.amount()),
                        "hand", results.get(winners.get(0)).name()));
            } else {
                broadcast(msg("win_showdown", "&a{pot}&7: &a&l{player} &agana &e{amount} &7con &e{hand}",
                        "pot", potName, "player", who, "amount", units(pot.amount()),
                        "hand", results.get(winners.get(0)).name()));
            }
            log.append(potName).append('=').append(pot.amount()).append("->").append(who).append("; ");
        }

        if (rake > 0) {
            broadcast(msg("rake", "&8(Comisión de la casa: {amount})", "amount", units(rake)));
        }

        for (Map.Entry<Integer, Long> e : lastWin.entrySet()) {
            Player p = Bukkit.getPlayer(seats.get(e.getKey()).player);
            if (p == null)
                continue;
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            p.sendTitle(plugin.color(msg("title_win", "&a&l¡Ganaste!")),
                    plugin.color("&e+" + units(e.getValue())), 5, 50, 15);
        }

        if (cfgBool("log_hands", true)) {
            plugin.getLogger().info("[Poker] " + name + " #" + handNumber + " board=" + plainCards(board)
                    + " rake=" + rake + " " + log);
        }

        state = State.SHOWDOWN;
        countdown = showdown ? cfgInt("showdown_seconds", 8, 2, 60) : cfgInt("uncontested_seconds", 3, 1, 30);
        updateDisplays();
    }

    /** Si el que más apostó no fue igualado, se le devuelve la diferencia. */
    private void returnUncalledBet() {
        int top = -1;
        long topAmt = 0L;
        long second = 0L;
        for (Map.Entry<Integer, Long> e : contributed.entrySet()) {
            long v = e.getValue();
            if (v > topAmt) {
                second = topAmt;
                topAmt = v;
                top = e.getKey();
            } else if (v > second) {
                second = v;
            }
        }
        if (top < 0 || topAmt <= second)
            return;
        long refund = topAmt - second;
        contributed.put(top, second);
        seats.get(top).stack += refund;
        Player p = Bukkit.getPlayer(seats.get(top).player);
        if (p != null) {
            p.sendMessage(msg("uncalled_returned", "&7Se te devuelve la apuesta no igualada: &e{amount}",
                    "amount", units(refund)));
        }
    }

    private long computeRake(long total) {
        if (tournament != null)
            return 0L; // en torneo la casa cobra en la inscripción
        if (!cfgBool("rake.enabled", true) || total <= 0)
            return 0L;
        if (cfgBool("rake.no_flop_no_drop", true) && !flopSeen)
            return 0L;
        double pct = Math.max(0.0, Math.min(25.0, plugin.getConfig().getDouble("poker.rake.percent", 5.0)));
        long rake = (long) Math.floor(total * pct / 100.0);
        long capBb = Math.max(0L, plugin.getConfig().getLong("poker.rake.cap_bb", 3L));
        if (capBb > 0)
            rake = Math.min(rake, capBb * bigBlind);
        return Math.max(0L, rake);
    }

    /** Reparte un bote; las fichas sobrantes van al primero a la izquierda del botón. */
    private void distribute(long amount, List<Integer> winners) {
        if (winners.isEmpty() || amount <= 0)
            return;
        int n = Math.max(1, seats.size());
        winners.sort(Comparator.comparingInt(i -> Math.floorMod(i - button - 1, n)));
        long share = amount / winners.size();
        long rem = amount % winners.size();
        for (int k = 0; k < winners.size(); k++) {
            int w = winners.get(k);
            long give = share + (k < rem ? 1 : 0);
            seats.get(w).stack += give;
            lastWin.merge(w, give, Long::sum);
        }
    }

    private void finishHand() {
        // Estadísticas (solo mesas normales: en torneo son fichas de torneo)
        var st = plugin.getGameStats();
        if (st != null && tournament == null) {
            for (int i : dealt) {
                UUID id = seats.get(i).player;
                if (id == null)
                    continue;
                long won = lastWin.getOrDefault(i, 0L);
                st.pokerHand(id, won);
                if (won > 0)
                    com.gamblingdex.GamblingDexPlugin.achievement(id, "poker_pot", won);
            }
        }
        for (int i : new ArrayList<>(dealt)) {
            Seat s = seats.get(i);
            if (s.player == null)
                continue;
            Player p = Bukkit.getPlayer(s.player);
            if (s.forceOut || p == null) {
                cashOut(i, p == null);
                continue;
            }
            if (s.leaving) {
                if (isOnOwnSeat(i, p)) {
                    s.leaving = false;
                    s.offSeatSince = 0L;
                } else {
                    cashOut(i, false);
                    continue;
                }
            }
            if (s.stack <= 0) {
                if (tournament != null) {
                    tournamentLeave(i); // eliminado
                    continue;
                }
                s.boughtIn = false;
                p.sendMessage(msg("busted",
                        "&cTe quedaste sin fichas. Compra más en el menú o bájate del asiento."));
                promptBuyIn(p);
            }
        }

        clearHand();
        state = State.WAITING;
        checkTournamentEnd();
        updateDisplays();
    }

    private void clearHand() {
        cancelMenuTask();
        dealt.clear();
        hole.clear();
        folded.clear();
        allIn.clear();
        acted.clear();
        streetBet.clear();
        contributed.clear();
        board.clear();
        showdownText.clear();
        lastWin.clear();
        raiseTarget.clear();
        currentBet = 0L;
        lastRaiseSize = 0L;
        actor = -1;
        sbSeat = -1;
        bbSeat = -1;
        street = null;
        flopSeen = false;
        revealAll = false;
    }

    /**
     * Apagado/recarga/borrado de la mesa: si hay una mano a medias se devuelve lo
     * que cada uno puso, y se paga el stack de todos como tokens.
     */
    public void shutdown() {
        cancelMenuTask();
        if (tournament != null) {
            // Torneo sin terminar: se devuelven todas las inscripciones.
            cancelTournament();
        }
        if (state == State.BETTING || state == State.RUNOUT) {
            for (int i : dealt) {
                seats.get(i).stack += contributed.getOrDefault(i, 0L);
            }
        }
        clearHand();
        state = State.WAITING;
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            if (s.player == null)
                continue;
            UUID id = s.player;
            long amount = s.stack;
            if (tournament == null)
                recordSession(s, amount);
            s.clearPlayer();
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                closeAnyPokerMenu(p);
            payOut(id, amount, false);
        }
        removeDisplays();
    }

    // =====================================================================
    // Torneo (sit & go): inscripción en tokens, fichas de torneo, ciegas que
    // suben con el tiempo y premios para los primeros puestos.
    // =====================================================================

    private PokerTournament tournament;

    public boolean isTournament() {
        return tournament != null;
    }

    public boolean isTournamentRegistering() {
        return tournament != null && !tournament.started;
    }

    public boolean isRegistered(UUID id) {
        return tournament != null && tournament.registered.containsKey(id);
    }

    public long getTournamentFee() {
        return tournament == null ? 0L : tournament.entryFee;
    }

    public long getTournamentStack() {
        return tournament == null ? 0L : tournament.startingStack;
    }

    public int getTournamentRegisteredCount() {
        return tournament == null ? 0 : tournament.registered.size();
    }

    /** Abre la inscripción. null = OK; si no, el mensaje de error. */
    public String openTournament(long fee, long startingStack, int levelMinutes) {
        if (tournament != null)
            return msg("tournament.already", "&cYa hay un torneo en esta mesa.");
        if (isHandRunning() || state == State.STARTING)
            return msg("tournament.busy", "&cHay una mano en curso. Espera a que termine.");
        for (Seat s : seats) {
            if (s.player != null && s.stack > 0)
                return msg("tournament.cash_players",
                        "&cHay jugadores con fichas en la mesa. Deben levantarse antes de abrir el torneo.");
        }
        if (seats.size() < 2)
            return msg("tournament.no_seats", "&cLa mesa necesita al menos 2 asientos.");

        List<Double> levels = new ArrayList<>();
        for (Object o : plugin.getConfig().getList("poker.tournament.blind_levels",
                List.of(1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64))) {
            if (o instanceof Number n && n.doubleValue() > 0)
                levels.add(n.doubleValue());
        }
        tournament = new PokerTournament(fee, startingStack, Math.max(1, levelMinutes) * 60_000L,
                smallBlind, bigBlind, levels);
        state = State.WAITING;

        String text = msg("tournament.open",
                "&d&lTORNEO DE PÓKER &8» &7Mesa &f{table}&7: inscripción &e{fee}&7, fichas &f{stack}&7. ¡Siéntate para inscribirte!",
                "table", getDisplayName(), "fee", units(fee), "stack", units(startingStack));
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendMessage(text);
        for (Seat s : seats) {
            Player p = s.player == null ? null : Bukkit.getPlayer(s.player);
            if (p != null)
                promptBuyIn(p);
        }
        updateDisplays();
        return null;
    }

    /** El jugador sentado paga la inscripción y recibe las fichas de torneo. */
    public boolean register(Player p) {
        if (!isTournamentRegistering()) {
            p.sendMessage(msg("tournament.not_open", "&cNo hay inscripciones abiertas en esta mesa."));
            return false;
        }
        int i = seatOf(p.getUniqueId());
        if (i < 0) {
            p.sendMessage(msg("not_seated", "&cPárate en un asiento de la mesa para jugar."));
            return false;
        }
        if (tournament.registered.containsKey(p.getUniqueId())) {
            p.sendMessage(msg("tournament.already_registered", "&eYa estás inscrito."));
            return false;
        }
        if (!com.gamblingdex.economy.TokenWallet.take(p, tournament.entryFee)) {
            p.sendMessage(msg("tournament.not_enough", "&cNo te alcanzan las fichas para la inscripción (&e{fee}&c).",
                    "fee", units(tournament.entryFee)));
            return false;
        }
        tournament.registered.put(p.getUniqueId(), p.getName());
        Seat s = seats.get(i);
        s.stack = tournament.startingStack;
        s.boughtIn = true;
        p.sendMessage(msg("tournament.registered", "&a¡Inscrito! Empiezas con &f{stack}&a fichas de torneo.",
                "stack", units(tournament.startingStack)));
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.7f, 1.6f);
        broadcast(msg("tournament.player_registered", "&d{player} &7se inscribió al torneo &8({count} inscritos)",
                "player", p.getName(), "count", String.valueOf(tournament.registered.size())));
        updateDisplays();
        return true;
    }

    /** Cierra la inscripción y arranca. null = OK; si no, el mensaje de error. */
    public String startTournament() {
        if (!isTournamentRegistering())
            return msg("tournament.not_open", "&cNo hay inscripciones abiertas en esta mesa.");
        if (tournament.registered.size() < 2)
            return msg("tournament.need_players", "&cSe necesitan al menos 2 inscritos.");

        // Sentados que no se inscribieron: se levantan.
        for (Seat s : seats) {
            if (s.player != null && !tournament.registered.containsKey(s.player)) {
                Player p = Bukkit.getPlayer(s.player);
                if (p != null)
                    p.sendMessage(msg("tournament.not_registered_kick",
                            "&7El torneo empezó y no estabas inscrito: liberamos tu asiento."));
                s.clearPlayer();
            }
        }
        tournament.started = true;
        tournament.startMs = System.currentTimeMillis();
        tournament.level = 0;
        smallBlind = tournament.sbAt(0);
        bigBlind = tournament.bbAt(0);
        button = -1;

        String text = msg("tournament.started",
                "&d&lTORNEO DE PÓKER &8» &a¡Empezó en la mesa {table}! &7Jugadores: &f{count} &8| &7Premio: &e{pool}",
                "table", getDisplayName(), "count", String.valueOf(tournament.registered.size()),
                "pool", units(tournament.prizePool(cutPercent())));
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendMessage(text);
        updateDisplays();
        return null;
    }

    /** Cancela el torneo y devuelve todas las inscripciones. */
    public void cancelTournament() {
        if (tournament == null)
            return;
        if (isHandRunning() || state == State.STARTING) {
            closeAllActionMenus();
            clearHand();
            state = State.WAITING;
        }
        for (UUID id : tournament.registered.keySet())
            com.gamblingdex.economy.TokenWallet.give(id, tournament.entryFee);
        broadcast(msg("tournament.cancelled", "&7El torneo se canceló. Se devolvieron las inscripciones."));
        endTournamentCleanup();
    }

    private double cutPercent() {
        return plugin.getConfig().getDouble("poker.tournament.house_cut_percent", 10.0);
    }

    private void endTournamentCleanup() {
        smallBlind = tournament.baseSb;
        bigBlind = tournament.baseBb;
        tournament = null;
        // Las fichas de torneo no valen nada fuera del torneo.
        for (Seat s : seats) {
            if (s.player == null)
                continue;
            s.stack = 0L;
            s.boughtIn = false;
            Player p = Bukkit.getPlayer(s.player);
            if (p != null)
                promptBuyIn(p);
        }
        updateDisplays();
    }

    /** Sube las ciegas según el tiempo transcurrido (al empezar cada mano). */
    private void applyTournamentLevel() {
        int lvl = tournament.levelNow();
        if (lvl != tournament.level) {
            tournament.level = lvl;
            broadcast(msg("tournament.level_up", "&d&lTORNEO &8» &eSuben las ciegas: &fNivel {level} &8(&e{sb}/{bb}&8)",
                    "level", String.valueOf(lvl + 1), "sb", units(tournament.sbAt(lvl)),
                    "bb", units(tournament.bbAt(lvl))));
        }
        smallBlind = tournament.sbAt(tournament.level);
        bigBlind = tournament.bbAt(tournament.level);
    }

    /** Sale del torneo: antes de empezar se devuelve la inscripción; después, eliminado. */
    private void tournamentLeave(int i) {
        Seat s = seats.get(i);
        UUID id = s.player;
        s.clearPlayer();
        buyInPromptAt.remove(id);
        Player p = Bukkit.getPlayer(id);

        if (!tournament.started) {
            if (tournament.registered.remove(id) != null) {
                com.gamblingdex.economy.TokenWallet.give(id, tournament.entryFee);
                if (p != null)
                    p.sendMessage(msg("tournament.left_refund", "&7Saliste del torneo: se te devolvió la inscripción."));
            } else if (p != null) {
                p.sendMessage(msg("left_table", "&7Saliste de la mesa de póker."));
            }
        } else if (tournament.registered.containsKey(id) && !tournament.eliminated.contains(id)) {
            tournament.eliminated.add(id);
            int place = tournament.remaining() + 1;
            String pname = tournament.registered.get(id);
            broadcast(msg("tournament.eliminated", "&d&lTORNEO &8» &c{player} &7quedó eliminado en el puesto &f{place}°",
                    "player", pname, "place", String.valueOf(place)));
            if (p != null)
                p.sendMessage(msg("tournament.you_eliminated",
                        "&cQuedaste eliminado del torneo en el puesto &f{place}°&c.", "place", String.valueOf(place)));
            if (!isHandRunning())
                checkTournamentEnd();
        }
        updateDisplays();
    }

    /** Si queda un solo jugador con fichas, termina y paga los premios. */
    private void checkTournamentEnd() {
        if (tournament == null || !tournament.started || tournament.remaining() > 1 || isHandRunning())
            return;

        List<UUID> places = new ArrayList<>();
        for (UUID id : tournament.registered.keySet()) {
            if (!tournament.eliminated.contains(id))
                places.add(id);
        }
        List<UUID> out = new ArrayList<>(tournament.eliminated);
        Collections.reverse(out); // el último en caer es 2°
        places.addAll(out);

        long pool = tournament.prizePool(cutPercent());
        List<Double> pcts = PokerTournament.payoutsFor(tournament.registered.size(), payoutTable());
        double sum = 0;
        for (double d : pcts)
            sum += d;
        long[] amounts = new long[pcts.size()];
        long given = 0;
        for (int k = 0; k < pcts.size(); k++) {
            amounts[k] = (long) Math.floor(pool * pcts.get(k) / sum);
            given += amounts[k];
        }
        if (amounts.length > 0)
            amounts[0] += pool - given;

        String header = msg("tournament.finished", "&d&lTORNEO DE PÓKER &8» &6&l¡Terminó! &7Mesa &f{table}",
                "table", getDisplayName());
        for (Player o : Bukkit.getOnlinePlayers())
            o.sendMessage(header);
        StringBuilder log = new StringBuilder();
        for (int k = 0; k < amounts.length && k < places.size(); k++) {
            UUID id = places.get(k);
            String pname = tournament.registered.getOrDefault(id, "?");
            com.gamblingdex.economy.TokenWallet.give(id, amounts[k]);
            if (k == 0) {
                if (plugin.getGameStats() != null)
                    plugin.getGameStats().pokerTournamentWin(id);
                com.gamblingdex.GamblingDexPlugin.achievement(id, "poker_tournament");
            }
            String line = msg("tournament.prize", "&f{place}° &a{player} &7→ &e{amount}",
                    "place", String.valueOf(k + 1), "player", pname, "amount", units(amounts[k]));
            for (Player o : Bukkit.getOnlinePlayers())
                o.sendMessage(line);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendTitle(plugin.color(k == 0 ? "&6&l¡CAMPEÓN!" : "&a&l" + (k + 1) + "° LUGAR"),
                        plugin.color("&e+" + units(amounts[k])), 10, 80, 20);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            log.append(k + 1).append("° ").append(pname).append(" +").append(amounts[k]).append("; ");
        }
        plugin.getLogger().info("[Poker] Torneo " + name + ": " + tournament.registered.size() + " jugadores, pozo "
                + pool + " | " + log);
        endTournamentCleanup();
    }

    private Map<Integer, List<Double>> payoutTable() {
        Map<Integer, List<Double>> out = new HashMap<>();
        org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig()
                .getConfigurationSection("poker.tournament.payouts");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    List<Double> l = new ArrayList<>();
                    for (Object o : sec.getList(k, List.of())) {
                        if (o instanceof Number n && n.doubleValue() > 0)
                            l.add(n.doubleValue());
                    }
                    out.put(Integer.parseInt(k.trim()), l);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (out.isEmpty()) {
            out.put(2, List.of(100.0));
            out.put(4, List.of(70.0, 30.0));
            out.put(7, List.of(50.0, 30.0, 20.0));
        }
        return out;
    }

    private String tournamentLine() {
        if (tournament == null)
            return null;
        if (!tournament.started) {
            return msg("tournament.holo_registering", "&d&lTORNEO &8| &7Inscripción &e{fee} &8| &7Inscritos: &f{count}",
                    "fee", units(tournament.entryFee), "count", String.valueOf(tournament.registered.size()));
        }
        long ms = tournament.msToNextLevel();
        String next = ms < 0 ? "-" : (ms / 60000) + ":" + String.format(Locale.ROOT, "%02d", (ms / 1000) % 60);
        return msg("tournament.holo_running",
                "&d&lTORNEO &8| &7Nivel &f{level} &8| &7Sube en &f{next} &8| &7Quedan &f{left}",
                "level", String.valueOf(tournament.level + 1), "next", next,
                "left", String.valueOf(tournament.remaining()));
    }

    // =====================================================================
    // Datos para los menús
    // =====================================================================

    public boolean isTurn(UUID playerId) {
        return state == State.BETTING && actor >= 0 && playerId != null
                && playerId.equals(seats.get(actor).player);
    }

    public int getTurnSecondsLeft() {
        return Math.max(0, countdown);
    }

    public long getPot() {
        long t = 0L;
        for (long v : contributed.values())
            t += v;
        return t;
    }

    public List<Card> getBoard() {
        return Collections.unmodifiableList(board);
    }

    public List<Card> getHoleCards(UUID playerId) {
        int i = seatOf(playerId);
        List<Card> h = i < 0 ? null : hole.get(i);
        return h == null ? List.of() : Collections.unmodifiableList(h);
    }

    public String getHandStrength(UUID playerId) {
        int i = seatOf(playerId);
        if (i < 0 || !hole.containsKey(i))
            return "-";
        List<Card> all = new ArrayList<>(hole.get(i));
        all.addAll(board);
        return PokerHandEvaluator.evaluate(all).name();
    }

    public long getCurrentBet() {
        return currentBet;
    }

    public long getStreetBet(UUID playerId) {
        int i = seatOf(playerId);
        return i < 0 ? 0L : bet(i);
    }

    public long getToCall(UUID playerId) {
        int i = seatOf(playerId);
        if (i < 0)
            return 0L;
        return Math.max(0L, Math.min(currentBet - bet(i), seats.get(i).stack));
    }

    public boolean canRaise(UUID playerId) {
        int i = seatOf(playerId);
        return i >= 0 && state == State.BETTING && canRaiseSeat(i);
    }

    public long getMinRaiseTo(UUID playerId) {
        int i = seatOf(playerId);
        return i < 0 ? 0L : minRaiseToSeat(i);
    }

    public long getMaxRaiseTo(UUID playerId) {
        int i = seatOf(playerId);
        return i < 0 ? 0L : bet(i) + seats.get(i).stack;
    }

    /** Subida "a" una fracción del bote (bote = fichas en el medio + lo que hay que pagar). */
    public long getPotRaiseTo(UUID playerId, double fraction) {
        long call = getToCall(playerId);
        long to = currentBet + Math.round(fraction * (getPot() + call));
        return clampRaise(playerId, to);
    }

    public long clampRaise(UUID playerId, long to) {
        long min = getMinRaiseTo(playerId);
        long max = getMaxRaiseTo(playerId);
        return Math.max(min, Math.min(max, to));
    }

    public long getRaiseTarget(UUID playerId) {
        return clampRaise(playerId, raiseTarget.getOrDefault(playerId, getMinRaiseTo(playerId)));
    }

    public void setRaiseTarget(UUID playerId, long to) {
        raiseTarget.put(playerId, clampRaise(playerId, to));
    }

    public boolean isBetSituation() {
        return currentBet == 0;
    }

    /** Líneas con el estado de cada asiento ocupado (para el menú). */
    public List<String> describeSeats(UUID viewer) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            if (s.player == null)
                continue;
            StringBuilder sb = new StringBuilder();
            sb.append(i == actor ? "§a▶ " : "§8- ");
            sb.append(s.player.equals(viewer) ? "§e" : "§f").append(s.name);
            sb.append(badges(i));
            sb.append(" §7").append(units(s.stack));
            if (isHandRunning() && dealt.contains(i)) {
                if (folded.contains(i))
                    sb.append(" §8(retirado)");
                else if (allIn.contains(i))
                    sb.append(" §6ALL-IN");
                if (bet(i) > 0)
                    sb.append(" §8| §fapuesta ").append(units(bet(i)));
            } else if (!s.boughtIn) {
                sb.append(" §8(sin fichas)");
            }
            out.add(sb.toString());
        }
        return out;
    }

    // =====================================================================
    // Hologramas
    // =====================================================================

    private boolean centerLoaded() {
        World w = center.getWorld();
        return w != null && w.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4);
    }

    private TextDisplay getText(UUID id) {
        World w = center.getWorld();
        if (w == null || id == null)
            return null;
        Entity e = w.getEntity(id);
        return (e instanceof TextDisplay td && td.isValid()) ? td : null;
    }

    private TextDisplay spawnText(Location l) {
        TextDisplay td = l.getWorld().spawn(l, TextDisplay.class);
        td.setPersistent(false);
        td.setBillboard(Display.Billboard.CENTER);
        td.setShadowed(true);
        td.setDefaultBackground(false);
        td.setBackgroundColor(Color.fromARGB(100, 0, 0, 0));
        td.setLineWidth(240);
        td.setTeleportDuration(2);
        td.setText(" ");
        return td;
    }

    private TextDisplay ensureText(UUID id, Location l) {
        TextDisplay td = getText(id);
        if (td == null)
            return spawnText(l);
        if (td.getLocation().distanceSquared(l) > 0.04)
            td.teleport(l);
        return td;
    }

    public void updateDisplays() {
        if (!centerLoaded())
            return;

        double h = plugin.getConfig().getDouble("poker.holo_height", 1.0);
        TextDisplay boardTd = ensureText(boardDisplayId, center.clone().add(0.5, h, 0.5));
        boardDisplayId = boardTd.getUniqueId();
        boardTd.setText(plugin.color(boardText()));

        for (int i = 0; i < seats.size(); i++) {
            Location sl = BlackjackTables.parseKey(seats.get(i).key);
            if (sl == null || sl.getWorld() == null
                    || !sl.getWorld().isChunkLoaded(sl.getBlockX() >> 4, sl.getBlockZ() >> 4))
                continue;

            // Asiento ocupado: el holograma va ENCIMA de la cabeza del jugador y él no
            // lo ve (tiene su info en pantalla). Asiento libre: cartel chico y bajo.
            Player owner = seatOwnerOnline(i);
            UUID ownerId = owner == null ? null : owner.getUniqueId();
            if (!Objects.equals(seatDisplayOwner.get(i), ownerId)) {
                removeSeatDisplay(i);
            }
            String text = seats.get(i).player != null && owner == null ? "" : seatText(i);
            if (text.isEmpty()) {
                removeSeatDisplay(i);
                continue;
            }
            Location desired = seatDisplayLocation(i, sl, owner);
            TextDisplay td = getText(seatDisplayIds.get(i));
            if (td == null) {
                td = spawnText(desired);
                float scale = (float) plugin.getConfig().getDouble(
                        owner == null ? "poker.free_seat_holo_scale" : "poker.player_holo_scale",
                        owner == null ? 0.6 : 0.8);
                td.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                        new org.joml.Vector3f(scale, scale, scale), new org.joml.AxisAngle4f()));
                if (owner != null)
                    owner.hideEntity(plugin, td);
                seatDisplayIds.put(i, td.getUniqueId());
                seatDisplayOwner.put(i, ownerId);
            } else if (td.getLocation().distanceSquared(desired) > 0.0025) {
                td.teleport(desired);
            }
            td.setText(plugin.color(text));
        }
        updateInfoBar();
    }

    private Player seatOwnerOnline(int i) {
        UUID id = seats.get(i).player;
        Player p = id == null ? null : Bukkit.getPlayer(id);
        return p != null && p.getWorld().equals(center.getWorld()) ? p : null;
    }

    private Location seatDisplayLocation(int i, Location seatLoc, Player owner) {
        if (owner != null) {
            return owner.getLocation().clone().add(0,
                    plugin.getConfig().getDouble("poker.player_holo_height", 2.55), 0);
        }
        // Sobre la superficie donde uno se para: el bloque registrado suele ser el
        // de abajo de una losa/escalera, así que se mide la altura real del asiento.
        return seatLoc.clone().add(0.5, seatSurfaceHeight(seatLoc)
                + plugin.getConfig().getDouble("poker.free_seat_holo_height", 1.0), 0.5);
    }

    /** Altura (desde el bloque registrado) de la superficie donde se para el jugador. */
    private static double seatSurfaceHeight(Location seatLoc) {
        Block seat = seatLoc.getBlock();
        Block above = seat.getRelative(BlockFace.UP);
        if (!above.isPassable() && above.getBoundingBox().getHeight() > 0) {
            // Losa/escalera encima del bloque registrado.
            return 1.0 + (above.getBoundingBox().getMaxY() - above.getY());
        }
        double h = seat.getBoundingBox().getMaxY() - seat.getY();
        return h > 0 ? h : 1.0;
    }

    /** Llamado cada 2 ticks: los hologramas siguen la cabeza de cada jugador. */
    public void followDisplays() {
        if (!centerLoaded())
            return;
        double h = plugin.getConfig().getDouble("poker.player_holo_height", 2.55);
        for (Map.Entry<Integer, UUID> e : seatDisplayIds.entrySet()) {
            UUID owner = seatDisplayOwner.get(e.getKey());
            if (owner == null)
                continue;
            Player p = Bukkit.getPlayer(owner);
            TextDisplay td = getText(e.getValue());
            if (p == null || td == null || !p.getWorld().equals(td.getWorld()))
                continue;
            Location desired = p.getLocation().clone().add(0, h, 0);
            if (td.getLocation().distanceSquared(desired) > 0.0025)
                td.teleport(desired);
        }
    }

    private void removeSeatDisplay(int i) {
        TextDisplay td = getText(seatDisplayIds.remove(i));
        seatDisplayOwner.remove(i);
        if (td != null)
            td.remove();
    }

    private String boardText() {
        StringBuilder sb = new StringBuilder();
        sb.append(msg("holo.title", "&6&l♠ ♥ PÓKER ♦ ♣")).append('\n');
        sb.append(msg("holo.stakes", "&f{table} &8| &7NL Hold'em &e{sb}/{bb}",
                "table", getDisplayName(), "sb", units(smallBlind), "bb", units(bigBlind))).append('\n');
        String tLine = tournamentLine();
        if (tLine != null)
            sb.append(tLine).append('\n');

        StringBuilder cards = new StringBuilder(board.isEmpty() ? "" : formatCards(board));
        for (int k = board.size(); k < 5; k++) {
            if (cards.length() > 0)
                cards.append(' ');
            cards.append("§8[ ]");
        }
        sb.append(cards).append('\n');

        if (isHandRunning()) {
            sb.append(msg("holo.pot", "&7Bote: &e{pot}", "pot", units(getPot()))).append('\n');
        }

        String status = switch (state) {
            case WAITING -> msg("holo.waiting", "&7Esperando jugadores &8(&f{ready}&7/&f{min}&8)",
                    "ready", String.valueOf(eligibleCount()), "min", String.valueOf(minPlayers()),
                    "seated", String.valueOf(seatedCount()));
            case STARTING -> msg("holo.starting", "&eNueva mano en &f{seconds}s", "seconds",
                    String.valueOf(Math.max(0, countdown)));
            case BETTING -> msg("holo.turn", "&aTurno: &f{player} &8(&f{seconds}s&8)",
                    "player", actor >= 0 ? seatName(actor) : "-", "seconds", String.valueOf(Math.max(0, countdown)));
            case RUNOUT -> msg("holo.runout", "&6&lALL-IN &7- repartiendo...");
            case SHOWDOWN -> msg("holo.showdown", "&6Resultado &8| &7siguiente mano en &f{seconds}s",
                    "seconds", String.valueOf(Math.max(0, countdown)));
        };
        sb.append(status);
        if (lastAction != null && !lastAction.isBlank() && state == State.BETTING) {
            sb.append('\n').append("§8").append(stripColor(lastAction));
        }
        return sb.toString();
    }

    private String seatText(int i) {
        Seat s = seats.get(i);
        if (s.player == null) {
            return msg("holo.seat_free", "&7Asiento libre\n&8Párate aquí para jugar");
        }
        // Sobre la cabeza solo van las cartas; fichas y apuestas salen en pantalla
        // (action bar + barra superior). Fuera de una mano no se muestra nada.
        if (!isHandRunning() || !dealt.contains(i))
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append(i == actor ? "§a▶ §f§l" : "§f").append(s.name).append(badges(i)).append('\n');
        if (folded.contains(i)) {
            sb.append("§8Retirado");
        } else {
            String shown = showdownText.get(i);
            if (shown != null) {
                sb.append(shown);
            } else if (revealAll) {
                sb.append(formatCards(hole.get(i)));
            } else {
                sb.append("§8[§7?§8] [§7?§8]");
            }
            if (allIn.contains(i))
                sb.append(" §6§lALL-IN");
        }
        return sb.toString();
    }

    // =====================================================================
    // Barra superior (bote, apuesta, turno) para los sentados
    // =====================================================================

    private org.bukkit.boss.BossBar infoBar;

    private void updateInfoBar() {
        if (infoBar == null) {
            infoBar = Bukkit.createBossBar("", org.bukkit.boss.BarColor.GREEN, org.bukkit.boss.BarStyle.SOLID);
        }

        String title;
        double progress = 1.0;
        switch (state) {
            case BETTING -> {
                title = msg("bar.turn", "&6Bote: &e{pot} &8| &7Apuesta: &f{bet} &8| &aTurno: &f{player} &7({seconds}s)",
                        "pot", units(getPot()), "bet", units(currentBet),
                        "player", actor >= 0 ? seatName(actor) : "-", "seconds", String.valueOf(Math.max(0, countdown)));
                progress = Math.max(0.0, Math.min(1.0,
                        countdown / (double) cfgInt("turn_timeout_seconds", 30, 5, 600)));
            }
            case RUNOUT -> title = msg("bar.runout", "&6&lALL-IN &8| &6Bote: &e{pot}", "pot", units(getPot()));
            case SHOWDOWN -> title = msg("bar.showdown", "&6Resultado &8| &7Siguiente mano en &f{seconds}s",
                    "seconds", String.valueOf(Math.max(0, countdown)));
            case STARTING -> title = msg("bar.starting", "&ePóker {table} &8| &7Nueva mano en &f{seconds}s",
                    "table", getDisplayName(), "seconds", String.valueOf(Math.max(0, countdown)));
            default -> title = msg("bar.waiting",
                    "&ePóker {table} &8| &7Ciegas &f{sb}/{bb} &8| &7Esperando jugadores &f({ready}/{min})",
                    "table", getDisplayName(), "sb", units(smallBlind), "bb", units(bigBlind),
                    "ready", String.valueOf(eligibleCount()), "min", String.valueOf(minPlayers()));
        }
        if (tournament != null && state != State.BETTING && state != State.RUNOUT) {
            title = tournamentLine();
        }
        infoBar.setTitle(plugin.color(title));
        infoBar.setProgress(progress);

        // Solo la ven los sentados en esta mesa.
        Set<UUID> wanted = new HashSet<>();
        for (Seat s : seats) {
            if (s.player != null)
                wanted.add(s.player);
        }
        for (Player p : new ArrayList<>(infoBar.getPlayers())) {
            if (!wanted.contains(p.getUniqueId()))
                infoBar.removePlayer(p);
        }
        for (UUID id : wanted) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !infoBar.getPlayers().contains(p))
                infoBar.addPlayer(p);
        }
    }

    private void removeInfoBar() {
        if (infoBar != null) {
            infoBar.removeAll();
            infoBar = null;
        }
    }

    private String badges(int i) {
        if (!isHandRunning())
            return "";
        StringBuilder b = new StringBuilder();
        if (i == button)
            b.append(" §f§l(D)");
        if (i == sbSeat)
            b.append(" §7(SB)");
        if (i == bbSeat)
            b.append(" §7(BB)");
        return b.toString();
    }

    private void removeSeatDisplays() {
        for (UUID id : seatDisplayIds.values()) {
            TextDisplay td = getText(id);
            if (td != null)
                td.remove();
        }
        seatDisplayIds.clear();
        seatDisplayOwner.clear();
    }

    public void removeDisplays() {
        TextDisplay b = getText(boardDisplayId);
        if (b != null)
            b.remove();
        boardDisplayId = null;
        removeSeatDisplays();
        removeInfoBar();
    }

    private void sendActionBars() {
        // Sentados que no juegan esta mano (o retirados): sus fichas en pantalla.
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            if (s.player == null || (isHandRunning() && dealt.contains(i) && !folded.contains(i)))
                continue;
            Player p = Bukkit.getPlayer(s.player);
            if (p == null)
                continue;
            String text = isTournamentRegistering() && !isRegistered(s.player)
                    ? msg("tournament.actionbar_register",
                            "&d&lTORNEO &8| &7Inscríbete por &e{fee} &7(click derecho a la mesa)",
                            "fee", units(tournament.entryFee))
                    : !s.boughtIn
                    ? msg("actionbar_buyin", "&7Fichas en la mesa: &e{stack} &8| &eCompra al menos &f{min} &7(click derecho a la mesa)",
                            "stack", units(s.stack), "min", units(getMinBuyIn()))
                    : msg("actionbar_seated", "&7Fichas en la mesa: &e{stack}", "stack", units(s.stack));
            p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(plugin.color(text)));
        }
        if (!isHandRunning())
            return;
        for (int i : dealt) {
            Seat s = seats.get(i);
            if (s.player == null || folded.contains(i))
                continue;
            Player p = Bukkit.getPlayer(s.player);
            if (p == null)
                continue;
            String text;
            if (i == actor) {
                long call = getToCall(s.player);
                text = msg("actionbar_turn", "&a&l¡TU TURNO! &7{action} &8| {cards} &8| &f{seconds}s",
                        "action", call > 0 ? "Pagar §e" + units(call) : "Puedes pasar",
                        "cards", formatCards(hole.get(i)), "seconds", String.valueOf(Math.max(0, countdown)));
            } else {
                text = msg("actionbar", "&7Tus cartas: {cards} &8| &e{hand} &8| &7Fichas: &e{stack}",
                        "cards", formatCards(hole.get(i)), "hand", getHandStrength(s.player),
                        "stack", units(s.stack));
            }
            if (bet(i) > 0)
                text += " §8| §7Tu apuesta: §f" + units(bet(i));
            p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(plugin.color(text)));
        }
    }

    private void spawnTurnMarker(Player p) {
        if (!cfgBool("turn_marker.enabled", true))
            return;
        Particle particle = Particle.END_ROD;
        String configured = plugin.getConfig().getString("poker.turn_marker.particle", "END_ROD");
        try {
            particle = Particle.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
        }
        Location base = p.getLocation().clone().add(0, 2.9, 0);
        for (int k = 0; k < 4; k++) {
            p.getWorld().spawnParticle(particle, base.clone().add(0, k * 0.18, 0), 1, 0, 0, 0, 0);
        }
        p.getWorld().spawnParticle(particle, base.clone().add(0.15, 0.06, 0), 1, 0, 0, 0, 0);
        p.getWorld().spawnParticle(particle, base.clone().add(-0.15, 0.06, 0), 1, 0, 0, 0, 0);
    }

    // =====================================================================
    // Menús abiertos
    // =====================================================================

    private boolean hasMenuOpen(Player p) {
        InventoryView view = p.getOpenInventory();
        return view != null && view.getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;
    }

    private void closeActionMenu(Player p) {
        if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof PokerActionMenuHolder) {
            p.closeInventory();
        }
    }

    private void closeAllActionMenus() {
        for (Seat s : seats) {
            if (s.player == null)
                continue;
            closeActionMenu(Bukkit.getPlayer(s.player));
        }
    }

    private void closeAnyPokerMenu(Player p) {
        Object h = p.getOpenInventory().getTopInventory().getHolder();
        if (h instanceof PokerActionMenuHolder || h instanceof com.gamblingdex.gui.PokerBuyInMenuHolder) {
            p.closeInventory();
        }
    }

    // =====================================================================
    // Mensajes y utilidades
    // =====================================================================

    private String seatName(int i) {
        if (i < 0 || i >= seats.size())
            return "-";
        String n = seats.get(i).name;
        return n == null ? "-" : n;
    }

    private void announce(int i, String text, Sound sound) {
        lastAction = text;
        broadcast(text);
        for (int j : dealt) {
            Player p = Bukkit.getPlayer(seats.get(j).player);
            if (p != null)
                p.playSound(p.getLocation(), sound, 0.6f, 1.2f);
        }
    }

    /** Mensaje a los sentados y a quienes miran cerca de la mesa. */
    private void broadcast(String text) {
        Set<UUID> sent = new HashSet<>();
        for (Seat s : seats) {
            if (s.player == null)
                continue;
            Player p = Bukkit.getPlayer(s.player);
            if (p != null && sent.add(p.getUniqueId()))
                p.sendMessage(text);
        }
        double r = plugin.getConfig().getDouble("poker.spectator_radius", 10.0);
        if (r > 0 && centerLoaded()) {
            for (Player p : center.getWorld().getNearbyPlayers(center, r)) {
                if (sent.add(p.getUniqueId()))
                    p.sendMessage(text);
            }
        }
    }

    private void broadcastExcept(UUID except, String text) {
        for (Seat s : seats) {
            if (s.player == null || s.player.equals(except))
                continue;
            Player p = Bukkit.getPlayer(s.player);
            if (p != null)
                p.sendMessage(text);
        }
    }

    private String msg(String key, String def, String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) {
            m.put(kv[k], kv[k + 1]);
        }
        // Admite saltos de línea escritos como \n literal en el YAML.
        return plugin.getMessages().format("poker." + key, def, m).replace("\\n", "\n");
    }

    public static String formatCard(Card c) {
        if (c == null)
            return "§8??";
        boolean red = c.suit() == Card.Suit.HEARTS || c.suit() == Card.Suit.DIAMONDS;
        return (red ? "§c" : "§f") + "§l" + c.rank().label() + c.suit().symbol();
    }

    public static String formatCards(List<Card> cards) {
        if (cards == null || cards.isEmpty())
            return "§8-";
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (sb.length() > 0)
                sb.append(" ");
            sb.append(formatCard(c));
        }
        return sb.toString();
    }

    private static String plainCards(List<Card> cards) {
        StringBuilder sb = new StringBuilder();
        for (Card c : cards)
            sb.append(c.shortName()).append(' ');
        return sb.toString().trim();
    }

    private static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("§.", "");
    }

    public static String units(long units) {
        try {
            return NumberFormat.getInstance(new Locale("es", "ES")).format(units);
        } catch (Exception ignored) {
            return String.valueOf(units);
        }
    }
}
