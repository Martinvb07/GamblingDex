package com.gamblingdex.games.blackjack;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.gui.BlackjackActionMenu;
import com.gamblingdex.gui.BlackjackBetMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BlackjackTable {

    private static final int HARD_MAX_PLAYERS = 7;

    public enum State {
        WAITING,
        BETTING,
        PLAYING,
        // El dealer revela su carta, pide y se muestran los resultados.
        DEALER
    }

    /** Dónde caen las fichas que el jugador pone en el menú de apuestas. */
    public enum BetSpot {
        MAIN,
        PERFECT_PAIRS,
        TWENTY_ONE_PLUS_THREE
    }

    private final GamblingDexPlugin plugin;
    private final String tableKey;
    private final Location center;

    // Admin-friendly name used for commands and for the dealer villager name tag.
    private String displayName;

    // Optional aesthetic name shown on the dealer name tag (can include
    // colors/spaces).
    private String prettyDisplayName;

    private final LinkedHashSet<UUID> seated = new LinkedHashSet<>();
    private final Map<UUID, Long> bets = new HashMap<>();
    private final Map<UUID, Long> pairsBets = new HashMap<>();
    private final Map<UUID, Long> plus3Bets = new HashMap<>();
    private final Map<UUID, BetSpot> selectedSpot = new HashMap<>();
    private final Map<UUID, Hand> hands = new HashMap<>();
    private final Set<UUID> stoodOrDone = new HashSet<>();

    private Hand dealerHand;
    private Deque<Card> deck;

    private State state = State.WAITING;
    private int bettingSecondsLeft = 0;
    private UUID currentTurn;

    private BukkitTask bettingTask;
    private BukkitTask turnTask;
    private int turnSecondsLeft = 0;

    // Fase del dealer: revelado + robo carta a carta + resultados visibles.
    private BukkitTask dealerTask;
    private int resultSecondsLeft = 0;
    private boolean resultsShown = false;
    private final Map<UUID, String> roundResults = new HashMap<>();

    private final List<UUID> holoDisplayIds;
    private UUID dealerId;

    // Ephemeral per-round displays (not persisted)
    private final Map<UUID, UUID> playerHandDisplayIds = new HashMap<>();

    // Dealer hand display shown above the villager during a round (not persisted)
    private UUID dealerHandDisplayId;

    // Action-menu open task (used for delaying menu open)
    private BukkitTask actionMenuOpenTask;

    // Anti-spam: notify players standing on seats during an active round.
    private final Map<UUID, Long> waitToJoinMsgAt = new HashMap<>();

    // Physical seats (persisted by manager)
    private final List<String> seatKeys = new ArrayList<>();
    private final Map<UUID, String> assignedSeatByPlayer = new HashMap<>();

    private Runnable persistDisplaysCallback;

    public BlackjackTable(
            GamblingDexPlugin plugin,
            String tableKey,
            Location center,
            List<UUID> holoDisplayIds) {
        this(plugin, tableKey, center, holoDisplayIds, null);
    }

    public BlackjackTable(
            GamblingDexPlugin plugin,
            String tableKey,
            Location center,
            List<UUID> holoDisplayIds,
            UUID dealerId) {
        this.plugin = plugin;
        this.tableKey = tableKey;
        this.center = center;
        this.holoDisplayIds = (holoDisplayIds == null ? new ArrayList<>() : new ArrayList<>(holoDisplayIds));
        this.dealerId = dealerId;
    }

    public void setPersistDisplaysCallback(Runnable callback) {
        this.persistDisplaysCallback = callback;
    }

    public String getTableKey() {
        return tableKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPrettyDisplayName() {
        return prettyDisplayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = (displayName == null ? null : displayName.trim());
        // Best-effort: update existing dealer name tag if spawned.
        World w = center.getWorld();
        if (w != null && dealerId != null) {
            Entity e = w.getEntity(dealerId);
            if (e instanceof Villager v && !v.isDead()) {
                String name = resolveDealerName();
                v.setCustomName(plugin.color(name));
                v.setCustomNameVisible(true);
            }
        }
    }

    public void setPrettyDisplayName(String prettyDisplayName) {
        this.prettyDisplayName = (prettyDisplayName == null ? null : prettyDisplayName.trim());
        // Best-effort: update existing dealer name tag if spawned.
        World w = center.getWorld();
        if (w != null && dealerId != null) {
            Entity e = w.getEntity(dealerId);
            if (e instanceof Villager v && !v.isDead()) {
                String name = resolveDealerName();
                v.setCustomName(plugin.color(name));
                v.setCustomNameVisible(true);
            }
        }
    }

    public Location getCenter() {
        return center;
    }

    public UUID getDealerId() {
        return dealerId;
    }

    public List<String> getSeatKeys() {
        return new ArrayList<>(seatKeys);
    }

    public void setSeatKeys(List<String> keys) {
        seatKeys.clear();
        if (keys != null) {
            for (String k : keys) {
                if (k != null && !k.isBlank()) {
                    seatKeys.add(k.trim());
                }
            }
        }
        // Drop seat assignments that no longer exist.
        for (UUID id : new ArrayList<>(assignedSeatByPlayer.keySet())) {
            String sk = assignedSeatByPlayer.get(id);
            if (sk == null || !seatKeys.contains(sk)) {
                assignedSeatByPlayer.remove(id);
            }
        }
    }

    public State getState() {
        return state;
    }

    public boolean isCenter(org.bukkit.block.Block block) {
        if (block == null)
            return false;
        return Objects.equals(BlackjackTables.key(block.getLocation()), tableKey);
    }

    public boolean isSeated(UUID playerId) {
        return playerId != null && seated.contains(playerId);
    }

    public UUID getCurrentTurn() {
        return currentTurn;
    }

    public long getBetUnits(UUID playerId) {
        if (playerId == null)
            return 0L;
        return bets.getOrDefault(playerId, 0L);
    }

    public long getBetUnits(UUID playerId, BetSpot spot) {
        if (playerId == null || spot == null)
            return 0L;
        return betMap(spot).getOrDefault(playerId, 0L);
    }

    public BetSpot getSelectedSpot(UUID playerId) {
        if (playerId == null || !sideBetsEnabled())
            return BetSpot.MAIN;
        return selectedSpot.getOrDefault(playerId, BetSpot.MAIN);
    }

    public void setSelectedSpot(UUID playerId, BetSpot spot) {
        if (playerId == null || spot == null)
            return;
        selectedSpot.put(playerId, spot);
    }

    public static String spotName(BetSpot spot) {
        return switch (spot) {
            case MAIN -> "Principal";
            case PERFECT_PAIRS -> "Pares Perfectos";
            case TWENTY_ONE_PLUS_THREE -> "21+3";
        };
    }

    public boolean sideBetsEnabled() {
        return plugin.getConfig().getBoolean("blackjack.side_bets.enabled", true);
    }

    private Map<UUID, Long> betMap(BetSpot spot) {
        return switch (spot) {
            case MAIN -> bets;
            case PERFECT_PAIRS -> pairsBets;
            case TWENTY_ONE_PLUS_THREE -> plus3Bets;
        };
    }

    /** Devuelve (en fichas) todas las apuestas laterales del jugador y las borra. */
    private void refundSideBets(Player player) {
        UUID id = player.getUniqueId();
        long side = pairsBets.getOrDefault(id, 0L) + plus3Bets.getOrDefault(id, 0L);
        pairsBets.remove(id);
        plus3Bets.remove(id);
        if (side > 0) {
            plugin.getTokenPayout().pay(player, side);
        }
    }

    /**
     * Cancela la ronda en curso (reload / apagado / mesa eliminada) devolviendo
     * las fichas apostadas y quitando los hologramas temporales.
     */
    public void abortRound() {
        stopAllTasks();

        // Si ya se mostraron los resultados, los pagos ya se hicieron.
        boolean alreadyPaid = state == State.DEALER && resultsShown;
        if (!alreadyPaid) {
            Set<UUID> ids = new HashSet<>(bets.keySet());
            ids.addAll(pairsBets.keySet());
            ids.addAll(plus3Bets.keySet());
            for (UUID id : ids) {
                long total = bets.getOrDefault(id, 0L)
                        + pairsBets.getOrDefault(id, 0L)
                        + plus3Bets.getOrDefault(id, 0L);
                Player p = Bukkit.getPlayer(id);
                if (total <= 0)
                    continue;
                if (p == null) {
                    plugin.getPendingPayouts().add(id, total);
                    continue;
                }
                plugin.getTokenPayout().pay(p, total);
                p.sendMessage(plugin.getMessages().format(
                        "blackjack.round_aborted",
                        "&eLa ronda de Blackjack se canceló. Se te devolvieron &f{amount}&e en fichas.",
                        Map.of("amount", prettyUnits(total))));
            }
        }

        for (UUID id : hands.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory()
                    .getHolder() instanceof com.gamblingdex.gui.BlackjackActionMenuHolder) {
                p.closeInventory();
            }
        }

        bets.clear();
        pairsBets.clear();
        plus3Bets.clear();
        hands.clear();
        stoodOrDone.clear();
        roundResults.clear();
        dealerHand = null;
        currentTurn = null;
        resultsShown = false;
        state = State.WAITING;

        removePlayerHandDisplays();
        removeDealerHandDisplay();
    }

    public void stopAllTasks() {
        if (dealerTask != null) {
            dealerTask.cancel();
            dealerTask = null;
        }
        if (bettingTask != null) {
            bettingTask.cancel();
            bettingTask = null;
        }
        if (turnTask != null) {
            turnTask.cancel();
            turnTask = null;
        }

        if (actionMenuOpenTask != null) {
            actionMenuOpenTask.cancel();
            actionMenuOpenTask = null;
        }
    }

    public void refreshHologram() {
        ensureDealerEntity();
        ensureHologramEntities(1);
        updateHologramText();
    }

    private void ensureDealerEntity() {
        World w = center.getWorld();
        if (w == null)
            return;

        Entity existing = (dealerId == null) ? null : w.getEntity(dealerId);
        if (existing instanceof Villager v && !existing.isDead()) {
            Location desired = dealerSpawnLocation();
            if (!isClose(v.getLocation(), desired)) {
                v.teleport(desired);
            }

            // Keep name updated if table name changes.
            String name = resolveDealerName();
            v.setCustomName(plugin.color(name));
            v.setCustomNameVisible(true);
            return;
        }

        Location spawn = dealerSpawnLocation();
        Entity spawned = w.spawnEntity(spawn, EntityType.VILLAGER);
        if (!(spawned instanceof Villager v))
            return;

        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setCollidable(false);
        v.setRemoveWhenFarAway(false);
        v.setPersistent(true);
        v.setCanPickupItems(false);

        String name = resolveDealerName();
        v.setCustomName(plugin.color(name));
        v.setCustomNameVisible(true);

        this.dealerId = v.getUniqueId();
        if (persistDisplaysCallback != null) {
            persistDisplaysCallback.run();
        }
    }

    private String resolveDealerName() {
        if (prettyDisplayName != null && !prettyDisplayName.isBlank()) {
            return prettyDisplayName;
        }
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        String name = plugin.getMessages().getString("blackjack.table_name", null);
        if (name == null || name.isBlank()) {
            name = plugin.getMessages().getString("blackjack.holo.title", "&6&lBLACKJACK");
        }
        if (name == null || name.isBlank()) {
            name = plugin.getMessages().getString("blackjack.dealer_name", "&cDealer");
        }
        return name;
    }

    private int actionMenuDelaySeconds() {
        return Math.max(0, Math.min(30, plugin.getConfig().getInt("blackjack.action_menu_delay_seconds", 5)));
    }

    private void cancelActionMenuOpenTask() {
        if (actionMenuOpenTask != null) {
            actionMenuOpenTask.cancel();
            actionMenuOpenTask = null;
        }
    }

    private void scheduleActionMenuOpen(UUID playerId) {
        cancelActionMenuOpenTask();
        if (playerId == null)
            return;

        int delay = actionMenuDelaySeconds();
        if (delay <= 0) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline() && state == State.PLAYING && Objects.equals(currentTurn, playerId)) {
                new BlackjackActionMenu(plugin).open(p, this);
            }
            return;
        }

        actionMenuOpenTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != State.PLAYING)
                return;
            if (!Objects.equals(currentTurn, playerId))
                return;
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                new BlackjackActionMenu(plugin).open(p, this);
            }
        }, delay * 20L);
    }

    private void openActionMenuNow(UUID playerId) {
        cancelActionMenuOpenTask();
        // Siguiente tick: evita pelear con el cierre del inventario en curso.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (state != State.PLAYING || !Objects.equals(currentTurn, playerId))
                return;
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                new BlackjackActionMenu(plugin).open(p, this);
            }
        });
    }

    public void requestActionMenuOpen(UUID playerId) {
        if (playerId == null)
            return;
        if (state != State.PLAYING)
            return;
        if (!Objects.equals(currentTurn, playerId))
            return;
        scheduleActionMenuOpen(playerId);
    }

    private Location dealerSpawnLocation() {
        // Spawn ABOVE the target block center (avoid spawning inside solid blocks).
        Location base = center.clone().add(0.5, 1.0, 0.5);
        World w = base.getWorld();
        if (w == null) {
            return base;
        }

        Location candidate = base.clone();
        // Try a few blocks up to find a passable spot.
        for (int i = 0; i < 4; i++) {
            if (candidate.getBlock().isPassable()) {
                return candidate;
            }
            candidate.add(0, 1.0, 0);
        }
        return base;
    }

    private static boolean isClose(Location a, Location b) {
        if (a == null || b == null)
            return false;
        if (a.getWorld() == null || b.getWorld() == null)
            return false;
        if (!a.getWorld().equals(b.getWorld()))
            return false;
        return a.distanceSquared(b) < 0.25; // within 0.5 blocks
    }

    public boolean join(Player player) {
        if (player == null)
            return false;

        if (plugin.getConfig().getBoolean("blackjack.seating.enabled", true)) {
            String mode = plugin.getConfig().getString("blackjack.seating.mode", "manual");
            if (mode == null)
                mode = "manual";
            mode = mode.trim().toLowerCase(Locale.ROOT);

            if (mode.equals("manual") && seatKeys.isEmpty()) {
                player.sendMessage(plugin.getMessages().getString(
                        "blackjack.no_seats_configured",
                        "&cEsta mesa no tiene asientos configurados."));
                return false;
            }

            if (!isStandingOnSeatPad(player)) {
                player.sendMessage(plugin.getMessages().getString(
                        "blackjack.must_stand_on_seat",
                        "&cPárate en una losa de la mesa para jugar."));
                return false;
            }
        }

        if (state == State.PLAYING || state == State.DEALER) {
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.cannot_join_in_game",
                    "&cNo puedes unirte: la partida ya empezó."));
            return false;
        }

        int maxPlayers = getConfiguredMaxPlayers();
        if (seated.size() >= maxPlayers) {
            player.sendMessage(plugin.getMessages().format(
                    "blackjack.table_full",
                    "&cMesa llena. (&f{max}&c)",
                    Map.of("max", String.valueOf(maxPlayers))));
            return false;
        }

        boolean wasBetting = state == State.BETTING;

        boolean added = seated.add(player.getUniqueId());
        if (!added) {
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.already_joined",
                    "&eYa estás en esta mesa."));
            return false;
        }

        player.sendMessage(plugin.getMessages().getString(
                "blackjack.joined",
                "&aTe uniste a la mesa de Blackjack."));

        String seat = detectPlayersSeatKey(player);
        if (seat != null) {
            assignedSeatByPlayer.put(player.getUniqueId(), seat);
        }

        maybeStartBetting();

        // Si el jugador se sienta durante BETTING, el menú debe abrirse automático.
        if (wasBetting && state == State.BETTING) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!seated.contains(player.getUniqueId()))
                    return;
                if (state != State.BETTING)
                    return;
                new BlackjackBetMenu(plugin).open(player, this);
            });
        }
        updateHologramText();
        return true;
    }

    /**
     * Physical seating: players are considered "seated" only while standing on
     * the configured seat pads around the center.
     */
    public void tickSeatScan() {
        if (!plugin.getConfig().getBoolean("blackjack.seating.enabled", true))
            return;
        boolean playing = state == State.PLAYING || state == State.DEALER;

        World w = center.getWorld();
        if (w == null)
            return;

        int maxPlayers = getConfiguredMaxPlayers();

        String mode = plugin.getConfig().getString("blackjack.seating.mode", "manual");
        if (mode == null)
            mode = "manual";
        mode = mode.trim().toLowerCase(Locale.ROOT);

        boolean changed = false;
        Set<UUID> desired = new LinkedHashSet<>();

        if (mode.equals("manual")) {
            for (String seatKey : seatKeys) {
                Location l = BlackjackTables.parseKey(seatKey);
                if (l == null || l.getWorld() == null)
                    continue;
                if (!l.getWorld().equals(w))
                    continue;

                Block seatBlock = l.getBlock();
                Player occupant = findPlayerOnSeat(seatBlock);
                if (occupant != null) {
                    desired.add(occupant.getUniqueId());
                    assignedSeatByPlayer.put(occupant.getUniqueId(), seatKey);
                }
            }
        } else {
            for (int i = 0; i < maxPlayers; i++) {
                Block seatBlock = getSeatBlock(i, maxPlayers);
                if (seatBlock == null)
                    continue;
                if (!isSeatMaterial(seatBlock.getType()))
                    continue;

                Player occupant = findPlayerOnSeat(seatBlock);
                if (occupant != null) {
                    desired.add(occupant.getUniqueId());
                }
            }
        }

        // If a round is in progress, keep participants fixed; only show a hint.
        if (playing) {
            long now = System.currentTimeMillis();
            long cooldownMs = 5000L;
            for (UUID id : desired) {
                if (seated.contains(id))
                    continue;
                Player p = Bukkit.getPlayer(id);
                if (p == null)
                    continue;
                Long last = waitToJoinMsgAt.get(id);
                if (last != null && (now - last) < cooldownMs)
                    continue;
                waitToJoinMsgAt.put(id, now);
                p.sendMessage(plugin.getMessages().getString(
                        "blackjack.wait_next_round",
                        "&eLa ronda está en curso. &7Espera a que termine para entrar."));
            }
            return;
        }

        // Join newly seated players
        for (UUID id : desired) {
            if (!seated.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    if (join(p)) {
                        changed = true;
                    }
                }
            }
        }

        // Remove players that stepped off seats (refund during BETTING)
        for (UUID id : new ArrayList<>(seated)) {
            if (desired.contains(id))
                continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                if (leave(p, state == State.BETTING)) {
                    changed = true;
                }
            } else {
                // Se desconectó durante las apuestas: devolver sus fichas al volver.
                long owed = bets.getOrDefault(id, 0L) + pairsBets.getOrDefault(id, 0L)
                        + plus3Bets.getOrDefault(id, 0L);
                plugin.getPendingPayouts().add(id, owed);
                seated.remove(id);
                bets.remove(id);
                pairsBets.remove(id);
                plus3Bets.remove(id);
                selectedSpot.remove(id);
                hands.remove(id);
                stoodOrDone.remove(id);
                assignedSeatByPlayer.remove(id);
                if (Objects.equals(currentTurn, id)) {
                    advanceTurn();
                }
                changed = true;
            }
        }

        if (changed) {
            updateHologramText();
        }
    }

    private int getConfiguredMaxPlayers() {
        int configured = plugin.getConfig().getInt("blackjack.max_players", HARD_MAX_PLAYERS);
        return Math.min(HARD_MAX_PLAYERS, Math.max(2, configured));
    }

    private Block getSeatBlock(int seatIndex, int seatCount) {
        World w = center.getWorld();
        if (w == null)
            return null;

        int radius = Math.max(1, plugin.getConfig().getInt("blackjack.seating.radius_blocks", 2));
        seatCount = Math.max(2, Math.min(HARD_MAX_PLAYERS, seatCount));
        seatIndex = Math.floorMod(seatIndex, seatCount);
        double angle = (2.0 * Math.PI) * ((double) seatIndex / (double) seatCount);
        int dx = (int) Math.round(Math.cos(angle) * radius);
        int dz = (int) Math.round(Math.sin(angle) * radius);

        return w.getBlockAt(center.getBlockX() + dx, center.getBlockY(), center.getBlockZ() + dz);
    }

    private boolean isSeatMaterial(Material m) {
        if (m == null)
            return false;
        List<String> mats = plugin.getConfig().getStringList("blackjack.seating.materials");
        if (mats == null || mats.isEmpty()) {
            return m == Material.QUARTZ_SLAB || m == Material.QUARTZ_BLOCK;
        }
        for (String s : mats) {
            if (s == null || s.isBlank())
                continue;
            Material mm = Material.matchMaterial(s.trim());
            if (mm != null && mm == m)
                return true;
        }
        return false;
    }

    private Player findPlayerOnSeat(Block seatBlock) {
        if (seatBlock == null)
            return null;
        World w = seatBlock.getWorld();
        if (w == null)
            return null;
        Location seatCenter = seatBlock.getLocation().add(0.5, 0.5, 0.5);

        for (Player p : w.getPlayers()) {
            if (p == null || !p.isOnline())
                continue;
            if (p.getLocation().distanceSquared(seatCenter) > (2.0 * 2.0))
                continue;

            Block feet = p.getLocation().getBlock();
            Block below = feet.getRelative(BlockFace.DOWN);
            if (feet.equals(seatBlock) || below.equals(seatBlock)) {
                return p;
            }
        }
        return null;
    }

    private boolean isStandingOnSeatPad(Player player) {
        if (player == null)
            return false;
        World w = player.getWorld();
        if (w == null || center.getWorld() == null || !w.equals(center.getWorld()))
            return false;

        String mode = plugin.getConfig().getString("blackjack.seating.mode", "manual");
        if (mode == null)
            mode = "manual";
        mode = mode.trim().toLowerCase(Locale.ROOT);

        Block feet = player.getLocation().getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);

        if (mode.equals("manual")) {
            if (seatKeys.isEmpty())
                return false;
            String feetKey = BlackjackTables.key(feet.getLocation());
            String belowKey = BlackjackTables.key(below.getLocation());
            for (String k : seatKeys) {
                if (k == null)
                    continue;
                if (k.equals(feetKey) || k.equals(belowKey))
                    return true;
            }
            return false;
        }

        int maxPlayers = getConfiguredMaxPlayers();

        for (int i = 0; i < maxPlayers; i++) {
            Block seat = getSeatBlock(i, maxPlayers);
            if (seat == null)
                continue;
            if (!isSeatMaterial(seat.getType()))
                continue;
            if (feet.equals(seat) || below.equals(seat))
                return true;
        }
        return false;
    }

    private String detectPlayersSeatKey(Player player) {
        if (player == null)
            return null;
        World w = player.getWorld();
        if (w == null || center.getWorld() == null || !w.equals(center.getWorld()))
            return null;

        if (seatKeys.isEmpty())
            return null;

        Block feet = player.getLocation().getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);
        String feetKey = BlackjackTables.key(feet.getLocation());
        String belowKey = BlackjackTables.key(below.getLocation());

        for (String k : seatKeys) {
            if (k == null)
                continue;
            if (k.equals(feetKey) || k.equals(belowKey))
                return k;
        }
        return null;
    }

    public boolean leave(Player player, boolean refundBetIfAny) {
        if (player == null)
            return false;
        UUID id = player.getUniqueId();
        if (!seated.remove(id))
            return false;

        if (refundBetIfAny) {
            Long bet = bets.remove(id);
            if (bet != null && bet > 0) {
                plugin.getTokenPayout().pay(player, bet);
            }
            refundSideBets(player);
        }
        selectedSpot.remove(id);

        hands.remove(id);
        stoodOrDone.remove(id);
        assignedSeatByPlayer.remove(id);
        if (Objects.equals(currentTurn, id)) {
            advanceTurn();
        }

        player.sendMessage(plugin.getMessages().getString(
                "blackjack.left",
                "&7Saliste de la mesa."));

        updateHologramText();
        return true;
    }

    public boolean placeBet(Player player, long amountUnits) {
        return placeBet(player, BetSpot.MAIN, amountUnits);
    }

    public boolean placeBet(Player player, BetSpot spot, long amountUnits) {
        if (player == null)
            return false;
        if (spot == null || (spot != BetSpot.MAIN && !sideBetsEnabled()))
            spot = BetSpot.MAIN;
        if (amountUnits <= 0)
            return false;

        if (state != State.BETTING) {
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.bets_closed",
                    "&cApuestas cerradas."));
            return false;
        }
        if (!seated.contains(player.getUniqueId())) {
            join(player);
            if (!seated.contains(player.getUniqueId()))
                return false;
        }

        Map<UUID, Long> target = betMap(spot);
        long newBet = target.getOrDefault(player.getUniqueId(), 0L) + amountUnits;
        target.put(player.getUniqueId(), newBet);

        player.sendMessage(plugin.getMessages().format(
                "blackjack.bet_placed",
                "&aApuesta &7({spot})&a: &e+{amount}&a (total: &e{total}&a)",
                Map.of(
                        "spot", spotName(spot),
                        "amount", prettyUnits(amountUnits),
                        "total", prettyUnits(newBet))));
        updateHologramText();
        return true;
    }

    public boolean clearBet(Player player) {
        if (player == null)
            return false;
        if (state != State.BETTING) {
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.bets_closed",
                    "&cApuestas cerradas."));
            return false;
        }

        UUID id = player.getUniqueId();
        long bet = bets.getOrDefault(id, 0L)
                + pairsBets.getOrDefault(id, 0L)
                + plus3Bets.getOrDefault(id, 0L);
        if (bet <= 0) {
            return true;
        }

        bets.remove(id);
        pairsBets.remove(id);
        plus3Bets.remove(id);
        plugin.getTokenPayout().pay(player, bet);
        player.sendMessage(plugin.getMessages().format(
                "blackjack.bet_cleared",
                "&7Retiraste tu apuesta: &e{amount}",
                java.util.Map.of("amount", prettyUnits(bet))));
        updateHologramText();
        return true;
    }

    /**
     * Motivo por el que el jugador NO puede doblar, o null si sí puede.
     */
    public String doubleDenyReason(Player player) {
        if (player == null)
            return "";
        UUID id = player.getUniqueId();
        Hand hand = hands.get(id);
        if (hand == null) {
            return plugin.getMessages().getString(
                    "blackjack.not_in_round",
                    "&cNo estás en esta ronda.");
        }
        if (hand.size() != 2) {
            return plugin.getMessages().getString(
                    "blackjack.double_not_allowed",
                    "&cSolo puedes doblar con 2 cartas.");
        }
        long currentBet = bets.getOrDefault(id, 0L);
        if (currentBet <= 0) {
            return plugin.getMessages().getString(
                    "blackjack.double_not_allowed",
                    "&cNo puedes doblar ahora.");
        }
        if (countTokenUnits(player) < currentBet) {
            return plugin.getMessages().format(
                    "blackjack.double_not_enough_tokens",
                    "&cNo tienes fichas suficientes para doblar. &7(Necesitas &f{needed}&7)",
                    Map.of("needed", prettyUnits(currentBet)));
        }
        return null;
    }

    /**
     * Texto corto con la mano del jugador y la carta visible del dealer (para el menú).
     */
    public String describePlayerHand(UUID playerId) {
        Hand h = playerId == null ? null : hands.get(playerId);
        if (h == null)
            return "-";
        return h.describe(false) + " §8(§f" + h.bestValue() + "§8)";
    }

    public String describeDealerUpCard() {
        if (dealerHand == null || dealerHand.size() == 0)
            return "-";
        Card up = dealerHand.cards().get(0);
        return up.shortName() + " ?? §8(§f" + up.rank().value() + "§8)";
    }

    public void doubleDown(Player player) {
        if (!isPlayersTurn(player))
            return;

        UUID id = player.getUniqueId();
        String deny = doubleDenyReason(player);
        if (deny != null) {
            player.sendMessage(deny);
            // No perder el turno: volver a mostrar el menú para pedir o plantarse.
            openActionMenuNow(id);
            return;
        }
        Hand hand = hands.get(id);
        long currentBet = bets.getOrDefault(id, 0L);

        long takenUnits = takeTokenUnitsFromInventory(player, currentBet);
        if (takenUnits < currentBet) {
            // refund anything we took
            if (takenUnits > 0) {
                plugin.getTokenPayout().pay(player, takenUnits);
            }
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.double_not_enough_tokens",
                    "&cNo tienes fichas suficientes para doblar."));
            openActionMenuNow(id);
            return;
        }

        // overpay? give change.
        long change = takenUnits - currentBet;
        if (change > 0) {
            plugin.getTokenPayout().pay(player, change);
        }

        long newBet = currentBet + currentBet;
        bets.put(id, newBet);

        // One card then stand.
        hand.add(draw());
        player.sendMessage(plugin.getMessages().format(
                "blackjack.double",
                "&eDoblas. Apuesta total: &f{total}",
                java.util.Map.of("total", prettyUnits(newBet))));

        if (hand.isBust()) {
            player.sendMessage(plugin.getMessages().format(
                    "blackjack.bust",
                    "&cTe pasaste. (&f{value}&c)",
                    java.util.Map.of("value", String.valueOf(hand.bestValue()))));
        }

        stoodOrDone.add(id);
        advanceTurn();
        updateHologramText();
    }

    public void hit(Player player) {
        if (!isPlayersTurn(player))
            return;
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.not_in_round",
                    "&cNo estás en esta ronda."));
            return;
        }

        hand.add(draw());
        if (hand.isBust()) {
            player.sendMessage(plugin.getMessages().format(
                    "blackjack.bust",
                    "&cTe pasaste. (&f{value}&c)",
                    Map.of("value", String.valueOf(hand.bestValue()))));
            stoodOrDone.add(player.getUniqueId());
            advanceTurn();
        } else {
            player.sendMessage(plugin.getMessages().format(
                    "blackjack.hit",
                    "&eHit. &7Valor: &f{value}",
                    Map.of("value", String.valueOf(hand.bestValue()))));
            // keep turn
            resetTurnTimeout();
            scheduleActionMenuOpen(player.getUniqueId());
        }
        updateHologramText();
    }

    public void stand(Player player) {
        if (!isPlayersTurn(player))
            return;
        stoodOrDone.add(player.getUniqueId());
        player.sendMessage(plugin.getMessages().getString(
                "blackjack.stand",
                "&7Stand."));
        advanceTurn();
        updateHologramText();
    }

    private boolean isPlayersTurn(Player player) {
        if (player == null)
            return false;
        if (state != State.PLAYING) {
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.not_playing",
                    "&cLa partida no está en curso."));
            return false;
        }
        if (!Objects.equals(currentTurn, player.getUniqueId())) {
            player.sendMessage(plugin.getMessages().getString(
                    "blackjack.not_your_turn",
                    "&cNo es tu turno."));
            return false;
        }
        return true;
    }

    private void maybeStartBetting() {
        if (state != State.WAITING)
            return;

        int minPlayers = Math.max(2, plugin.getConfig().getInt("blackjack.min_players", 2));
        if (seated.size() < minPlayers)
            return;

        startBetting();
    }

    private void startBetting() {
        if (bettingTask != null)
            return;

        state = State.BETTING;
        bettingSecondsLeft = Math.max(5, plugin.getConfig().getInt("blackjack.bet_window_seconds", 20));
        bets.clear();
        pairsBets.clear();
        plus3Bets.clear();
        hands.clear();
        stoodOrDone.clear();
        dealerHand = null;
        currentTurn = null;

        ensureDealerEntity();
        removePlayerHandDisplays();

        broadcastToSeated(plugin.getMessages().format(
                "blackjack.betting_started",
                "&eApuestas abiertas por &f{seconds}&e segundos. Abre el menú para apostar.",
                Map.of("seconds", String.valueOf(bettingSecondsLeft))));

        // Open GUI for everyone seated.
        for (UUID id : seated) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                new BlackjackBetMenu(plugin).open(p, this);
            }
        }

        updateHologramText();

        bettingTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != State.BETTING) {
                stopBettingTask();
                return;
            }

            bettingSecondsLeft--;
            if (bettingSecondsLeft <= 0) {
                stopBettingTask();
                startRound();
                return;
            }
            updateHologramText();
        }, 20L, 20L);
    }

    private void stopBettingTask() {
        if (bettingTask != null) {
            bettingTask.cancel();
            bettingTask = null;
        }
    }

    private void startRound() {
        int minPlayers = Math.max(2, plugin.getConfig().getInt("blackjack.min_players", 2));

        // Participants: seated players with a bet.
        List<UUID> participants = new ArrayList<>();
        for (UUID id : seated) {
            long bet = bets.getOrDefault(id, 0L);
            if (bet > 0)
                participants.add(id);
        }

        // Side bets without a main bet are not allowed: refund them.
        for (UUID id : new ArrayList<>(seated)) {
            if (participants.contains(id))
                continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                refundSideBets(p);
            } else {
                pairsBets.remove(id);
                plus3Bets.remove(id);
            }
        }

        if (participants.size() < minPlayers) {
            // Refund any placed bets.
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    plugin.getTokenPayout().pay(p, bets.getOrDefault(id, 0L));
                    refundSideBets(p);
                }
            }
            bets.clear();
            pairsBets.clear();
            plus3Bets.clear();
            state = State.WAITING;
            broadcastToSeated(plugin.getMessages().format(
                    "blackjack.not_enough_bets",
                    "&cNo hubo suficientes apuestas. (mínimo {min})",
                    Map.of("min", String.valueOf(minPlayers))));
            updateHologramText();
            return;
        }

        // Shoe: se rebaraja cuando se pasa la carta de corte.
        if (deck == null || deck.size() < reshuffleThreshold()) {
            this.deck = new ArrayDeque<>(buildShuffledDeck());
            broadcastToParticipants(participants, plugin.getMessages().getString(
                    "blackjack.shuffle",
                    "&7El dealer baraja el zapato..."));
        }
        this.dealerHand = new Hand();

        for (UUID id : participants) {
            hands.put(id, new Hand());
        }

        // 2 cards each, then dealer
        for (int i = 0; i < 2; i++) {
            for (UUID id : participants) {
                Hand h = hands.get(id);
                if (h != null)
                    h.add(draw());
            }
            dealerHand.add(draw());
        }

        state = State.PLAYING;
        broadcastToParticipants(participants, plugin.getMessages().getString(
                "blackjack.round_started",
                "&aRonda iniciada. ¡Suerte!"));

        // Close the bet GUI so players can see their cards before the action menu
        // opens.
        for (UUID id : new ArrayList<>(seated)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.closeInventory();
            }
        }

        // Side bets se pagan apenas se reparte (como en el casino).
        resolveSideBets(participants);

        // Mark naturals as done.
        for (UUID id : participants) {
            Hand h = hands.get(id);
            if (h != null && h.isBlackjack()) {
                stoodOrDone.add(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.sendMessage(plugin.getMessages().getString(
                            "blackjack.blackjack",
                            "&6&lBLACKJACK!"));
                }
            }
        }

        // Peek: si el dealer muestra A o figura y tiene Blackjack, la ronda termina ya.
        if (dealerHand.isBlackjack()) {
            broadcastToParticipants(participants, plugin.getMessages().getString(
                    "blackjack.dealer_blackjack",
                    "&c&lEl dealer tiene BLACKJACK."));
            stoodOrDone.addAll(participants);
            startDealerTurn();
            return;
        }

        // Start first turn
        currentTurn = nextTurnCandidate(null);
        if (currentTurn == null) {
            // Todos tienen Blackjack.
            startDealerTurn();
            return;
        }
        beginTurnTimeout();
        updateHologramText();
    }

    private void resolveSideBets(List<UUID> participants) {
        if (dealerHand == null || dealerHand.size() == 0)
            return;
        Card dealerUp = dealerHand.cards().get(0);
        FileConfiguration cfg = plugin.getConfig();

        for (UUID id : participants) {
            Hand h = hands.get(id);
            Player p = Bukkit.getPlayer(id);
            if (h == null || h.size() < 2)
                continue;
            Card c1 = h.cards().get(0);
            Card c2 = h.cards().get(1);

            Long pairBet = pairsBets.remove(id);
            if (pairBet != null && pairBet > 0) {
                SideBets.Result r = SideBets.perfectPairs(c1, c2, cfg);
                if (p != null) {
                    sendSideBetResult(p, "Pares Perfectos", pairBet, r);
                } else if (r != null) {
                    plugin.getPendingPayouts().add(id, r.totalPayout(pairBet));
                }
            }

            Long plus3Bet = plus3Bets.remove(id);
            if (plus3Bet != null && plus3Bet > 0) {
                SideBets.Result r = SideBets.twentyOnePlusThree(c1, c2, dealerUp, cfg);
                if (p != null) {
                    sendSideBetResult(p, "21+3", plus3Bet, r);
                } else if (r != null) {
                    plugin.getPendingPayouts().add(id, r.totalPayout(plus3Bet));
                }
            }
        }
    }

    private void sendSideBetResult(Player p, String betName, long bet, SideBets.Result r) {
        if (r == null) {
            p.sendMessage(plugin.getMessages().format(
                    "blackjack.side_bet.lose",
                    "&8[&d{bet}&8] &7Sin premio. &c-{amount}",
                    Map.of("bet", betName, "amount", prettyUnits(bet))));
            return;
        }
        long total = r.totalPayout(bet);
        plugin.getTokenPayout().pay(p, total);
        p.sendMessage(plugin.getMessages().format(
                "blackjack.side_bet.win",
                "&8[&d{bet}&8] &a&l{hand}! &7Paga &f{mult}:1 &8→ &a+{amount}",
                Map.of(
                        "bet", betName,
                        "hand", r.name(),
                        "mult", String.valueOf(r.multiplier()),
                        "amount", prettyUnits(total - bet))));
    }

    private void beginTurnTimeout() {
        if (turnTask != null) {
            turnTask.cancel();
            turnTask = null;
        }

        cancelActionMenuOpenTask();

        if (state != State.PLAYING) {
            return;
        }
        int timeout = Math.max(5, plugin.getConfig().getInt("blackjack.turn_timeout_seconds", 20));
        turnSecondsLeft = timeout + actionMenuDelaySeconds();

        Player p = currentTurn == null ? null : Bukkit.getPlayer(currentTurn);
        if (p != null) {
            p.sendMessage(plugin.getMessages().format(
                    "blackjack.your_turn",
                    "&eTu turno: el menú se abre automáticamente. (&f{seconds}&7s)",
                    Map.of("seconds", String.valueOf(timeout))));

            scheduleActionMenuOpen(p.getUniqueId());
        }

        turnTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != State.PLAYING) {
                stopTurnTask();
                return;
            }
            if (currentTurn == null) {
                stopTurnTask();
                startDealerTurn();
                return;
            }

            spawnTurnMarkerParticles();

            turnSecondsLeft--;
            if (turnSecondsLeft <= 0) {
                Player cp = Bukkit.getPlayer(currentTurn);
                if (cp != null) {
                    cp.sendMessage(plugin.getMessages().getString(
                            "blackjack.turn_timeout",
                            "&7Tiempo. Se aplicó Stand."));
                }
                stoodOrDone.add(currentTurn);
                advanceTurn();
            }
            updateHologramText();
        }, 20L, 20L);
    }

    private void spawnTurnMarkerParticles() {
        if (!plugin.getConfig().getBoolean("blackjack.turn_marker.enabled", true))
            return;
        if (currentTurn == null)
            return;
        Player p = Bukkit.getPlayer(currentTurn);
        if (p == null)
            return;

        Particle particle = Particle.END_ROD;
        String configured = plugin.getConfig().getString("blackjack.turn_marker.particle", "END_ROD");
        if (configured != null && !configured.isBlank()) {
            try {
                particle = Particle.valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
            }
        }

        int count = Math.max(1, plugin.getConfig().getInt("blackjack.turn_marker.count", 1));
        World w = p.getWorld();
        Location base = p.getLocation().clone().add(0, 2.9, 0);

        // Arrow pointing DOWN: shaft above, head below.
        for (int i = 0; i < 5; i++) {
            w.spawnParticle(particle, base.clone().add(0, i * 0.18, 0), count, 0, 0, 0, 0);
        }

        double headY = -0.12;
        w.spawnParticle(particle, base.clone().add(0.18, headY + 0.18, 0), count, 0, 0, 0, 0);
        w.spawnParticle(particle, base.clone().add(-0.18, headY + 0.18, 0), count, 0, 0, 0, 0);
        w.spawnParticle(particle, base.clone().add(0, headY, 0), count, 0, 0, 0, 0);
    }

    private void resetTurnTimeout() {
        if (state != State.PLAYING)
            return;
        int timeout = Math.max(5, plugin.getConfig().getInt("blackjack.turn_timeout_seconds", 20));
        turnSecondsLeft = timeout + actionMenuDelaySeconds();
    }

    private void stopTurnTask() {
        if (turnTask != null) {
            turnTask.cancel();
            turnTask = null;
        }
    }

    private void advanceTurn() {
        if (state != State.PLAYING)
            return;
        stopTurnTask();
        cancelActionMenuOpenTask();

        currentTurn = nextTurnCandidate(currentTurn);
        if (currentTurn == null) {
            startDealerTurn();
            return;
        }
        beginTurnTimeout();
    }

    private UUID nextTurnCandidate(UUID after) {
        // iterate in seated order
        boolean start = (after == null);
        for (UUID id : seated) {
            if (!hands.containsKey(id))
                continue; // not in this round
            if (!start) {
                if (Objects.equals(id, after))
                    start = true;
                continue;
            }
            if (stoodOrDone.contains(id))
                continue;
            Hand h = hands.get(id);
            if (h == null)
                continue;
            if (h.isBust()) {
                stoodOrDone.add(id);
                continue;
            }
            return id;
        }
        return null;
    }

    private int dealerDrawIntervalTicks() {
        return Math.max(10, Math.min(100, plugin.getConfig().getInt("blackjack.dealer_draw_interval_ticks", 40)));
    }

    private int resultDisplaySeconds() {
        return Math.max(3, Math.min(60, plugin.getConfig().getInt("blackjack.result_display_seconds", 7)));
    }

    private void cancelDealerTask() {
        if (dealerTask != null) {
            dealerTask.cancel();
            dealerTask = null;
        }
    }

    /**
     * Turno del dealer: voltea la carta oculta y pide carta por carta (con pausa
     * entre cada una) para que todos vean lo que saca. Luego paga y deja los
     * resultados visibles unos segundos antes de la siguiente ronda.
     */
    private void startDealerTurn() {
        if (state != State.PLAYING)
            return;
        stopTurnTask();
        cancelActionMenuOpenTask();
        cancelDealerTask();

        if (dealerHand == null)
            dealerHand = new Hand();

        state = State.DEALER;
        currentTurn = null;
        resultsShown = false;
        roundResults.clear();

        // Cerrar menús de acción que hayan quedado abiertos.
        for (UUID id : hands.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory()
                    .getHolder() instanceof com.gamblingdex.gui.BlackjackActionMenuHolder) {
                p.closeInventory();
            }
        }

        broadcastToRound(plugin.getMessages().format(
                "blackjack.dealer_reveal",
                "&7El dealer voltea su carta: &f{cards} &8(&f{value}&8)",
                Map.of(
                        "cards", dealerHand.describe(false),
                        "value", String.valueOf(dealerHand.bestValue()))));
        updateHologramText();

        // Si todos se pasaron o tienen Blackjack, el dealer no necesita pedir.
        final boolean mustDraw = anyLiveHand();

        dealerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != State.DEALER) {
                cancelDealerTask();
                return;
            }
            if (mustDraw && dealerShouldHit()) {
                Card c = draw();
                dealerHand.add(c);
                String value = dealerHand.isBust()
                        ? "BUST"
                        : String.valueOf(dealerHand.bestValue());
                broadcastToRound(plugin.getMessages().format(
                        "blackjack.dealer_hit",
                        "&7El dealer pide: &f{card} &8→ &f{value}",
                        Map.of("card", c.shortName(), "value", value)));
                updateHologramText();
                return;
            }
            cancelDealerTask();
            showResults();
        }, dealerDrawIntervalTicks(), dealerDrawIntervalTicks());
    }

    private boolean anyLiveHand() {
        for (Hand h : hands.values()) {
            if (h != null && !h.isBust() && !h.isBlackjack())
                return true;
        }
        return false;
    }

    private boolean dealerShouldHit() {
        int value = dealerHand.bestValue();
        if (value > 21)
            return false;
        if (value < 17)
            return true;
        boolean hitSoft17 = plugin.getConfig().getBoolean("blackjack.dealer_hits_soft_17", false);
        return value == 17 && hitSoft17 && dealerHand.isSoft17();
    }

    private void showResults() {
        resultsShown = true;

        boolean dealerBust = dealerHand.isBust();
        boolean dealerBj = dealerHand.isBlackjack();
        int dealerValue = dealerHand.bestValue();
        String dealerVal = dealerBust ? "BUST" : (dealerBj ? "BLACKJACK" : String.valueOf(dealerValue));

        broadcastToRound(plugin.getMessages().format(
                "blackjack.dealer_final",
                "&cDealer&8: &f{cards} &8(&f{value}&8)",
                Map.of("cards", dealerHand.describe(false), "value", dealerVal)));

        for (UUID id : new ArrayList<>(hands.keySet())) {
            Player p = Bukkit.getPlayer(id);
            long bet = bets.getOrDefault(id, 0L);
            Hand h = hands.get(id);
            if (h == null || bet <= 0)
                continue;

            long payout;
            if (h.isBust()) {
                payout = 0L;
            } else if (h.isBlackjack()) {
                payout = dealerBj ? bet : blackjackTotalPayout(bet);
            } else if (dealerBj) {
                // El Blackjack del dealer le gana a cualquier 21 de 3+ cartas.
                payout = 0L;
            } else if (dealerBust) {
                payout = bet * 2L;
            } else {
                int playerValue = h.bestValue();
                if (playerValue > dealerValue) {
                    payout = bet * 2L;
                } else if (playerValue == dealerValue) {
                    payout = bet;
                } else {
                    payout = 0L;
                }
            }

            if (payout <= 0) {
                roundResults.put(id, "&c✖ PIERDE");
            } else if (payout == bet) {
                roundResults.put(id, "&e= EMPATE");
            } else {
                roundResults.put(id, "&a✔ +" + prettyUnits(payout - bet));
            }

            if (p == null) {
                // Desconectado: se le paga cuando vuelva a entrar.
                plugin.getPendingPayouts().add(id, payout);
                continue;
            }

            if (payout > 0) {
                plugin.getTokenPayout().pay(p, payout);
            }

            String youVal = h.isBust() ? "BUST" : String.valueOf(h.bestValue());

            if (payout <= 0) {
                p.sendMessage(plugin.getMessages().format(
                        "blackjack.result.lose",
                        "&cPerdiste tu apuesta. &8(&fTú {you} &8vs &fDealer {dealer}&8)",
                        Map.of("you", youVal, "dealer", dealerVal)));
                p.sendTitle(plugin.color("&cPerdiste"),
                        plugin.color("&7Tú &f" + youVal + " &8vs &7Dealer &f" + dealerVal), 5, 60, 15);
            } else if (payout == bet) {
                p.sendMessage(plugin.getMessages().format(
                        "blackjack.result.push",
                        "&eEmpate. &7Recuperas &f{amount}&7 en fichas.",
                        Map.of(
                                "amount", prettyUnits(bet),
                                "you", youVal,
                                "dealer", dealerVal)));
                p.sendTitle(plugin.color("&eEmpate"),
                        plugin.color("&7Tú &f" + youVal + " &8vs &7Dealer &f" + dealerVal), 5, 60, 15);
            } else {
                long profit = Math.max(0L, payout - bet);
                p.sendMessage(plugin.getMessages().format(
                        "blackjack.result.win",
                        "&aGanaste &f{amount}&a en fichas.",
                        Map.of(
                                "amount", prettyUnits(profit),
                                "you", youVal,
                                "dealer", dealerVal)));
                p.sendTitle(plugin.color(h.isBlackjack() ? "&6&lBLACKJACK!" : "&a&l¡Ganaste!"),
                        plugin.color("&a+" + prettyUnits(profit) + " &7fichas"), 5, 60, 15);
            }
        }

        // Mantener cartas y resultados en pantalla antes de limpiar la mesa.
        resultSecondsLeft = resultDisplaySeconds();
        updateHologramText();
        dealerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            resultSecondsLeft--;
            if (resultSecondsLeft <= 0 || state != State.DEALER) {
                finishRound();
                return;
            }
            updateHologramText();
        }, 20L, 20L);
    }

    private void finishRound() {
        cancelDealerTask();

        state = State.WAITING;
        bets.clear();
        pairsBets.clear();
        plus3Bets.clear();
        hands.clear();
        stoodOrDone.clear();
        roundResults.clear();
        dealerHand = null;
        currentTurn = null;
        resultsShown = false;

        removePlayerHandDisplays();
        updateHologramText();

        // Siguiente ronda automática si siguen sentados suficientes jugadores.
        maybeStartBetting();
    }

    private void broadcastToRound(String msg) {
        for (UUID id : hands.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                p.sendMessage(msg);
        }
    }

    private long blackjackTotalPayout(long bet) {
        // total payout (includes returning stake) for a natural blackjack.
        String rule = plugin.getConfig().getString("blackjack.blackjack_payout", "3:2");
        if (rule == null)
            rule = "3:2";
        rule = rule.trim();

        // Default: 3:2 => profit 1.5x, total 2.5x
        // 6:5 => profit 1.2x, total 2.2x
        double totalMult;
        if (rule.equalsIgnoreCase("6:5") || rule.equalsIgnoreCase("6/5")) {
            totalMult = 2.2;
        } else {
            totalMult = 2.5;
        }

        long out = (long) Math.floor(bet * totalMult);
        // Safety: never pay less than stake back on a blackjack win.
        return Math.max(bet, out);
    }

    private int deckCount() {
        return Math.max(1, Math.min(8, plugin.getConfig().getInt("blackjack.decks", 6)));
    }

    private int reshuffleThreshold() {
        int pct = Math.max(10, Math.min(50, plugin.getConfig().getInt("blackjack.reshuffle_at_percent", 25)));
        return deckCount() * 52 * pct / 100;
    }

    private List<Card> buildShuffledDeck() {
        int decks = deckCount();
        List<Card> cards = new ArrayList<>(52 * decks);
        for (int d = 0; d < decks; d++) {
            for (Card.Suit s : Card.Suit.values()) {
                for (Card.Rank r : Card.Rank.values()) {
                    cards.add(new Card(r, s));
                }
            }
        }
        Collections.shuffle(cards, ThreadLocalRandom.current());
        return cards;
    }

    private Card draw() {
        if (deck == null || deck.isEmpty()) {
            deck = new ArrayDeque<>(buildShuffledDeck());
        }
        return deck.pollFirst();
    }

    private void broadcastToSeated(String msg) {
        for (UUID id : seated) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                p.sendMessage(msg);
        }
    }

    private void broadcastToParticipants(List<UUID> participants, String msg) {
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                p.sendMessage(msg);
        }
    }

    private void ensureHologramEntities(int lineCount) {
        World w = center.getWorld();
        if (w == null)
            return;

        FileConfiguration cfg = plugin.getConfig();
        double height = cfg.getDouble("blackjack.holo_height", 2.3);
        Location base;
        Entity dealer = (dealerId == null) ? null : w.getEntity(dealerId);
        if (dealer != null && !dealer.isDead()) {
            // Add a small extra offset so the hologram doesn't overlap the villager name
            // tag.
            base = dealer.getLocation().clone().add(0, height + 0.6, 0);
        } else {
            // Fallback: above the center.
            base = center.clone().add(0.5, height + 0.6, 0.5);
        }

        boolean changed = false;
        for (int i = 0; i < lineCount; i++) {
            TextDisplay existing = getTextDisplayByIndex(i);
            Location l = base.clone().add(0, -0.25 * i, 0);
            if (existing != null) {
                if (!isClose(existing.getLocation(), l)) {
                    existing.teleport(l);
                }
                continue;
            }

            TextDisplay td = w.spawn(l, TextDisplay.class);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setDefaultBackground(false);
            td.setShadowed(true);

            if (i < holoDisplayIds.size()) {
                holoDisplayIds.set(i, td.getUniqueId());
            } else {
                holoDisplayIds.add(td.getUniqueId());
            }
            changed = true;
        }

        // Trim extra saved ids (and remove those TextDisplays in-world)
        while (holoDisplayIds.size() > lineCount) {
            UUID removedId = holoDisplayIds.remove(holoDisplayIds.size() - 1);
            if (removedId != null) {
                Entity e = w.getEntity(removedId);
                if (e != null) {
                    e.remove();
                }
            }
            changed = true;
        }

        if (changed && persistDisplaysCallback != null) {
            persistDisplaysCallback.run();
        }
    }

    private TextDisplay getTextDisplayByIndex(int idx) {
        if (idx < 0 || idx >= holoDisplayIds.size())
            return null;
        World w = center.getWorld();
        if (w == null)
            return null;
        UUID id = holoDisplayIds.get(idx);
        if (id == null)
            return null;
        Entity e = w.getEntity(id);
        if (e instanceof TextDisplay td) {
            return td;
        }
        return null;
    }

    private void updateHologramText() {
        ensureDealerEntity();
        ensureHologramEntities(1);

        String status;
        if (state == State.WAITING) {
            int min = Math.max(2, plugin.getConfig().getInt("blackjack.min_players", 2));
            int max = getConfiguredMaxPlayers();
            status = plugin.getMessages().format(
                    "blackjack.holo.waiting",
                    "&7Esperando jugadores... &8(&f{players}&7/&f{max}&8)",
                    Map.of(
                            "players", String.valueOf(seated.size()),
                            "min", String.valueOf(min),
                            "max", String.valueOf(max)));
        } else if (state == State.BETTING) {
            status = plugin.getMessages().format(
                    "blackjack.holo.betting",
                    "&eApuestas: &f{seconds}&7s &8(&f{players}&7)",
                    Map.of(
                            "seconds", String.valueOf(Math.max(0, bettingSecondsLeft)),
                            "players", String.valueOf(seated.size())));
        } else if (state == State.DEALER) {
            status = resultsShown
                    ? plugin.getMessages().format(
                            "blackjack.holo.results",
                            "&6Resultados &8| &7Siguiente ronda en &f{seconds}&7s",
                            Map.of("seconds", String.valueOf(Math.max(0, resultSecondsLeft))))
                    : plugin.getMessages().getString(
                            "blackjack.holo.dealer_turn",
                            "&cTurno del dealer...");
        } else {
            String turnName = currentTurn == null
                    ? "-"
                    : Optional.ofNullable(Bukkit.getPlayer(currentTurn)).map(Player::getName).orElse("-");
            status = plugin.getMessages().format(
                    "blackjack.holo.playing",
                    "&aEn juego &8| &7Turno: &f{turn} &8(&f{seconds}&7s&8)",
                    Map.of(
                            "turn", turnName,
                            "seconds", String.valueOf(Math.max(0, turnSecondsLeft))));
        }

        // Single line above the dealer showing current state.
        setHoloLine(0, status);

        updateDealerHandDisplay();

        updatePlayerHandDisplays();
    }

    private void updateDealerHandDisplay() {
        if (state != State.PLAYING && state != State.DEALER) {
            removeDealerHandDisplay();
            return;
        }

        World w = center.getWorld();
        if (w == null) {
            return;
        }

        Entity dealer = (dealerId == null) ? null : w.getEntity(dealerId);
        if (dealer == null || dealer.isDead()) {
            removeDealerHandDisplay();
            return;
        }

        Location desired = dealerHandDisplayLocation(dealer);
        TextDisplay td = getDealerHandDisplay();
        if (td == null) {
            td = spawnDealerHandDisplay(desired);
            dealerHandDisplayId = td.getUniqueId();
        } else if (desired != null && !isClose(td.getLocation(), desired)) {
            td.teleport(desired);
        }

        String cards;
        String value;
        if (dealerHand == null) {
            cards = "-";
            value = "-";
        } else if (state == State.PLAYING && dealerHand.size() >= 2) {
            // Carta oculta hasta el turno del dealer: solo cuenta la carta visible.
            cards = dealerHand.describe(true);
            value = String.valueOf(dealerHand.cards().get(0).rank().value());
        } else {
            cards = dealerHand.describe(false);
            value = dealerHand.isBust() ? "BUST"
                    : (dealerHand.isBlackjack() ? "BLACKJACK" : String.valueOf(dealerHand.bestValue()));
        }

        String line = plugin.getMessages().format(
                "blackjack.holo.dealer",
                "&cDealer&8: &f{cards} &8(&f{value}&8)",
                Map.of(
                        "cards", cards,
                        "value", value));
        td.setText(plugin.color(line));
    }

    private Location dealerHandDisplayLocation(Entity dealer) {
        if (dealer == null)
            return null;

        FileConfiguration cfg = plugin.getConfig();
        double height = cfg.getDouble("blackjack.holo_height", 2.3);
        // Place dealer hand between the villager name tag and the status line.
        return dealer.getLocation().clone().add(0, height + 0.25, 0);
    }

    private TextDisplay getDealerHandDisplay() {
        if (dealerHandDisplayId == null)
            return null;
        World w = center.getWorld();
        if (w == null)
            return null;
        Entity e = w.getEntity(dealerHandDisplayId);
        if (e instanceof TextDisplay td) {
            return td;
        }
        return null;
    }

    private TextDisplay spawnDealerHandDisplay(Location l) {
        World w = center.getWorld();
        if (w == null) {
            throw new IllegalStateException("World is null");
        }
        if (l == null) {
            l = center.clone().add(0.5, 2.55, 0.5);
        }

        TextDisplay td = w.spawn(l, TextDisplay.class);
        td.setBillboard(Display.Billboard.CENTER);
        td.setSeeThrough(true);
        td.setDefaultBackground(false);
        td.setShadowed(true);
        td.setText(" ");
        return td;
    }

    private void removeDealerHandDisplay() {
        World w = center.getWorld();
        if (w == null) {
            dealerHandDisplayId = null;
            return;
        }
        if (dealerHandDisplayId == null) {
            return;
        }
        Entity e = w.getEntity(dealerHandDisplayId);
        if (e != null) {
            e.remove();
        }
        dealerHandDisplayId = null;
    }

    private void updatePlayerHandDisplays() {
        if (state != State.PLAYING && state != State.DEALER) {
            removePlayerHandDisplays();
            return;
        }

        World w = center.getWorld();
        if (w == null)
            return;

        // Ensure a display for every participant hand.
        for (UUID id : hands.keySet()) {
            UUID displayId = playerHandDisplayIds.get(id);
            Entity e = (displayId == null) ? null : w.getEntity(displayId);
            if (!(e instanceof TextDisplay)) {
                TextDisplay td = spawnPlayerHandDisplay(id);
                playerHandDisplayIds.put(id, td.getUniqueId());
            }
        }

        // Remove displays that no longer correspond to active hands.
        for (UUID id : new ArrayList<>(playerHandDisplayIds.keySet())) {
            if (!hands.containsKey(id)) {
                removePlayerHandDisplay(id);
            }
        }

        for (UUID id : hands.keySet()) {
            UUID displayId = playerHandDisplayIds.get(id);
            if (displayId == null)
                continue;

            Entity e = w.getEntity(displayId);
            if (!(e instanceof TextDisplay td))
                continue;

            Player p = Bukkit.getPlayer(id);
            String name = (p == null ? "<offline>" : p.getName());
            Hand h = hands.get(id);
            if (h == null)
                continue;

            String turnMark = Objects.equals(currentTurn, id) ? "&e▶ " : "&8- ";
            String cards = h.describe(false);
            String value = h.isBust() ? "BUST" : String.valueOf(h.bestValue());

            String line = plugin.getMessages().format(
                    "blackjack.holo.player",
                    "&e{turn}{player}&8: &f{cards} &8(&f{value}&8)",
                    Map.of(
                            "turn", turnMark,
                            "player", name,
                            "cards", cards,
                            "value", value));
            String result = roundResults.get(id);
            if (result != null) {
                line = line + " " + result;
            }
            td.setText(plugin.color(line));

            Location desired = playerHandDisplayLocation(id);
            if (desired != null && !isClose(td.getLocation(), desired)) {
                td.teleport(desired);
            }
        }
    }

    private TextDisplay spawnPlayerHandDisplay(UUID playerId) {
        World w = center.getWorld();
        Location l = playerHandDisplayLocation(playerId);
        if (w == null || l == null) {
            l = center.clone().add(0.5, 1.55, 0.5);
        }

        TextDisplay td = w.spawn(l, TextDisplay.class);
        td.setBillboard(Display.Billboard.CENTER);
        td.setSeeThrough(true);
        td.setDefaultBackground(false);
        td.setShadowed(true);
        td.setText(" ");
        return td;
    }

    private Location playerHandDisplayLocation(UUID playerId) {
        if (playerId == null)
            return null;

        Player p = Bukkit.getPlayer(playerId);
        if (p != null) {
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();
            if (dir != null) {
                dir = dir.normalize();
            } else {
                dir = new Vector(0, 0, 1);
            }

            // A bit below eye level and a block-ish in front.
            Location base = eye.clone().add(0, -0.35, 0);
            return base.add(dir.multiply(1.2));
        }

        // Fallback: place around the table based on join order.
        int seat = 0;
        int idx = 0;
        for (UUID id : seated) {
            if (Objects.equals(id, playerId)) {
                seat = idx;
                break;
            }
            idx++;
        }
        int seatCount = getConfiguredMaxPlayers();
        seat = Math.floorMod(seat, seatCount);

        double radius = 1.6;
        double y = 1.55;
        double angle = (2.0 * Math.PI) * ((double) seat / (double) seatCount);
        double ox = Math.cos(angle) * radius;
        double oz = Math.sin(angle) * radius;

        return center.clone().add(0.5 + ox, y, 0.5 + oz);
    }

    private long countTokenUnits(Player player) {
        long total = 0L;
        for (org.bukkit.inventory.ItemStack it : player.getInventory().getContents()) {
            Integer v = plugin.getTokenManager().getTokenValue(it);
            if (v == null)
                continue;
            total += (long) v * (long) it.getAmount();
        }
        return total;
    }

    private long takeTokenUnitsFromInventory(Player player, long targetUnits) {
        if (player == null || targetUnits <= 0)
            return 0L;

        if (countTokenUnits(player) < targetUnits) {
            return 0L;
        }

        long removedUnits = 0L;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            org.bukkit.inventory.ItemStack it = inv.getItem(i);
            Integer v = plugin.getTokenManager().getTokenValue(it);
            if (v == null)
                continue;

            int amt = it.getAmount();
            inv.setItem(i, null);
            removedUnits += (long) v * (long) amt;

            if (removedUnits >= targetUnits) {
                break;
            }
        }

        return removedUnits;
    }

    private void removePlayerHandDisplay(UUID playerId) {
        if (playerId == null)
            return;
        World w = center.getWorld();
        if (w == null)
            return;
        UUID displayId = playerHandDisplayIds.remove(playerId);
        if (displayId == null)
            return;
        Entity e = w.getEntity(displayId);
        if (e != null) {
            e.remove();
        }
    }

    private void removePlayerHandDisplays() {
        for (UUID id : new ArrayList<>(playerHandDisplayIds.keySet())) {
            removePlayerHandDisplay(id);
        }
        playerHandDisplayIds.clear();
    }

    private void setHoloLine(int idx, String text) {
        TextDisplay td = getTextDisplayByIndex(idx);
        if (td == null)
            return;
        td.setText(plugin.color(text == null ? "" : text));
    }

    private static String prettyUnits(long units) {
        try {
            return NumberFormat.getInstance(new Locale("es", "ES")).format(units);
        } catch (Exception ignored) {
            return String.valueOf(units);
        }
    }

    public void removeDisplays() {
        World w = center.getWorld();
        if (w == null)
            return;
        for (UUID id : new ArrayList<>(holoDisplayIds)) {
            if (id == null)
                continue;
            Entity e = w.getEntity(id);
            if (e != null)
                e.remove();
        }

        removePlayerHandDisplays();
        removeDealerHandDisplay();

        if (dealerId != null) {
            Entity e = w.getEntity(dealerId);
            if (e != null)
                e.remove();
            dealerId = null;
            if (persistDisplaysCallback != null) {
                persistDisplaysCallback.run();
            }
        }
    }

    public List<UUID> getHoloDisplayIds() {
        return new ArrayList<>(holoDisplayIds);
    }
}
