package com.gamblingdex.games.rouletteworld;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.economy.TokenWallet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class WorldRouletteTable {

    private static String applyPlaceholders(String template, Map<String, String> values) {
        String out = template == null ? "" : template;
        if (values == null || values.isEmpty())
            return out;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey() == null)
                continue;
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private String msg(String path, String def) {
        if (path != null && path.startsWith("messages.") && plugin.getMessages() != null) {
            return plugin.getMessages().getString(path.substring("messages.".length()), def);
        }
        return plugin.color(plugin.getConfig().getString(path, def));
    }

    private String msg(String path, String def, Map<String, String> placeholders) {
        if (path != null && path.startsWith("messages.") && plugin.getMessages() != null) {
            return plugin.getMessages().format(path.substring("messages.".length()), def, placeholders);
        }
        String raw = plugin.getConfig().getString(path, def);
        return plugin.color(applyPlaceholders(raw, placeholders));
    }

    private static void sendMultiline(Player player, String message) {
        if (player == null || message == null)
            return;
        String[] lines = message.split("\n", -1);
        for (String line : lines) {
            if (line == null)
                continue;
            player.sendMessage(line.isEmpty() ? " " : line);
        }
    }

    public static final class OriginalBlock {
        private final Material type;
        private final String blockData;

        public OriginalBlock(Material type, String blockData) {
            this.type = type;
            this.blockData = blockData;
        }

        public Material getType() {
            return type;
        }

        public String getBlockData() {
            return blockData;
        }
    }

    public enum State {
        WAITING,
        COUNTDOWN,
        SPINNING
    }

    private final GamblingDexPlugin plugin;
    private final String tableKey;
    private final Location center;
    private final int radius;

    private final Map<String, Integer> segmentKeyToNumber;
    private final Map<Integer, Location> numberToBlockLocation;
    // locationKey -> original block info (center + segments)
    private final Map<String, OriginalBlock> originals;

    private final List<UUID> numberDisplayIds;
    private final List<UUID> holoDisplayIds;

    private final Map<UUID, WorldRouletteSelection> selections = new HashMap<>();
    // player -> (betKey -> bet)
    private final Map<UUID, Map<String, WorldRouletteBet>> bets = new HashMap<>();
    // apuestas de la ronda anterior de cada jugador (para "Repetir apuesta")
    private final Map<UUID, List<WorldRouletteBet>> lastBets = new HashMap<>();
    // número del jackpot en esta ronda (null = ronda normal)
    private Integer jackpotNumber;

    private State state = State.WAITING;
    private int countdownSeconds = 0;
    private BukkitTask countdownTask;
    private BukkitTask spinTask;
    private BukkitTask cycleTask;
    private String lastHighlightKey;

    public WorldRouletteTable(
            GamblingDexPlugin plugin,
            String tableKey,
            Location center,
            int radius,
            Map<String, Integer> segmentKeyToNumber,
            Map<Integer, Location> numberToBlockLocation,
            Map<String, OriginalBlock> originals,
            List<UUID> numberDisplayIds,
            List<UUID> holoDisplayIds) {
        this.plugin = plugin;
        this.tableKey = tableKey;
        this.center = center;
        this.radius = radius;
        this.segmentKeyToNumber = segmentKeyToNumber;
        this.numberToBlockLocation = numberToBlockLocation;
        this.originals = (originals == null ? new HashMap<>() : originals);
        this.numberDisplayIds = new ArrayList<>(numberDisplayIds == null ? List.of() : numberDisplayIds);
        this.holoDisplayIds = new ArrayList<>(holoDisplayIds == null ? List.of() : holoDisplayIds);
    }

    /** Marca de los hologramas de la ruleta (para encontrarlos y no duplicarlos). */
    public static final String TAG = "gdx_roulette";

    public List<UUID> getNumberDisplayIds() {
        return numberDisplayIds;
    }

    public List<UUID> getHoloDisplayIds() {
        return holoDisplayIds;
    }

    /** Historial guardado en roulette_tables.yml (el más reciente primero). */
    public void loadRecentNumbers(List<Integer> list) {
        recentNumbers.clear();
        if (list != null)
            for (Integer n : list)
                if (n != null && recentNumbers.size() < 10)
                    recentNumbers.addLast(n);
    }

    private static boolean entitiesLoaded(Location l) {
        World w = l.getWorld();
        return w != null && w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)
                && w.getChunkAt(l.getBlockX() >> 4, l.getBlockZ() >> 4).isEntitiesLoaded();
    }

    /** Quita TextDisplay sueltos justo en {@code l} (copias viejas) menos los de {@code keep}. */
    private static void removeStrays(Location l, Collection<UUID> keep) {
        for (Entity e : l.getWorld().getNearbyEntities(l, 0.3, 0.3, 0.3))
            if (e instanceof TextDisplay && !keep.contains(e.getUniqueId()))
                e.remove();
    }

    private TextDisplay spawnText(Location l, String text) {
        TextDisplay td = l.getWorld().spawn(l, TextDisplay.class);
        td.addScoreboardTag(TAG);
        td.setBillboard(Display.Billboard.CENTER);
        td.setSeeThrough(true);
        td.setDefaultBackground(false);
        td.setShadowed(true);
        td.setText(text);
        return td;
    }

    /**
     * Vuelve a crear los hologramas de la ruleta que falten (el número de cada
     * casilla y el cartel de arriba). Solo con los chunks y sus entidades
     * cargados, para no duplicarlos. true si cambió algo (hay que guardar los ids).
     */
    public boolean repairDisplays() {
        World w = center.getWorld();
        if (w == null || !entitiesLoaded(center))
            return false;
        for (Location l : numberToBlockLocation.values())
            if (!entitiesLoaded(l))
                return false;
        boolean changed = false;

        double holoHeight = plugin.getConfig().getDouble("roulette_world.holo_height", 2.3);
        Location holoBase = center.clone().add(0.5, holoHeight, 0.5);
        List<String> lines = plugin.getConfig().getStringList("roulette_world.holo_lines");
        if (lines == null || lines.isEmpty())
            lines = List.of("§6§lRULETA", "§7Click derecho con tokens para apostar", "§eEsperando jugadores...");
        int want = Math.min(3, lines.size());
        for (int i = 0; i < want; i++) {
            Entity e = i < holoDisplayIds.size() ? w.getEntity(holoDisplayIds.get(i)) : null;
            if (e instanceof TextDisplay) {
                e.addScoreboardTag(TAG);
                continue;
            }
            Location l = holoBase.clone().add(0, -0.25 * i, 0);
            removeStrays(l, holoDisplayIds);
            TextDisplay td = spawnText(l, plugin.color(lines.get(i)));
            if (i < holoDisplayIds.size())
                holoDisplayIds.set(i, td.getUniqueId());
            else
                holoDisplayIds.add(td.getUniqueId());
            changed = true;
        }

        int alive = 0;
        for (UUID id : numberDisplayIds) {
            Entity e = w.getEntity(id);
            if (e instanceof TextDisplay) {
                e.addScoreboardTag(TAG);
                alive++;
            }
        }
        if (alive < numberToBlockLocation.size()) {
            for (UUID id : numberDisplayIds) {
                Entity e = w.getEntity(id);
                if (e != null)
                    e.remove();
            }
            numberDisplayIds.clear();
            for (Map.Entry<Integer, Location> en : numberToBlockLocation.entrySet()) {
                int number = en.getKey();
                Location l = en.getValue().clone().add(0.5, 1.15, 0.5);
                removeStrays(l, Set.of());
                String color = WorldRouletteTables.isZero(number) ? "§a"
                        : (WorldRouletteTables.isRed(number) ? "§c" : "§8");
                numberDisplayIds.add(spawnText(l, color + "§l" + WorldRouletteTables.formatNumber(number)).getUniqueId());
            }
            changed = true;
        }
        return changed;
    }

    public Map<String, OriginalBlock> getOriginals() {
        return originals;
    }

    public String getTableKey() {
        return tableKey;
    }

    public Location getCenter() {
        return center;
    }

    public int getRadius() {
        return radius;
    }

    public State getState() {
        return state;
    }

    /** Segundos que quedan para apostar (mientras la ronda está abierta). */
    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    /** Fichas que el jugador tiene puestas en un número en esta ronda. */
    public long betOnNumber(UUID playerId, int number) {
        Map<String, WorldRouletteBet> m = bets.get(playerId);
        if (m == null)
            return 0L;
        WorldRouletteBet b = m.get(betKey(WorldRouletteBetType.NUMBER, number));
        return b == null ? 0L : b.getAmount();
    }

    public Integer getNumberForBlock(Block block) {
        if (block == null)
            return null;
        return segmentKeyToNumber.get(WorldRouletteTables.key(block.getLocation()));
    }

    public boolean isCenter(Block block) {
        if (block == null)
            return false;
        return WorldRouletteTables.key(block.getLocation()).equals(tableKey);
    }

    public boolean isPartOfTable(Block block) {
        if (block == null)
            return false;
        String key = WorldRouletteTables.key(block.getLocation());
        return key.equals(tableKey) || segmentKeyToNumber.containsKey(key);
    }

    public WorldRouletteSelection getSelection(UUID playerId) {
        return selections.computeIfAbsent(playerId, id -> new WorldRouletteSelection(WorldRouletteBetType.RED, null));
    }

    public void setSelectionType(Player player, WorldRouletteBetType type) {
        if (player == null || type == null)
            return;
        WorldRouletteSelection sel = getSelection(player.getUniqueId());
        sel.setType(type);
        if (type != WorldRouletteBetType.NUMBER) {
            sel.setNumber(null);
        }
    }

    public String describeSelection(UUID playerId) {
        WorldRouletteSelection sel = getSelection(playerId);
        if (sel.getType() == WorldRouletteBetType.NUMBER) {
            if (sel.getNumber() == null)
                return "NÚMERO (sin seleccionar)";
            return "NÚMERO " + WorldRouletteTables.formatNumber(sel.getNumber());
        }
        return sel.getType().label();
    }

    public void selectNumber(Player player, int number) {
        WorldRouletteSelection sel = getSelection(player.getUniqueId());
        sel.setType(WorldRouletteBetType.NUMBER);
        sel.setNumber(number);
        player.sendMessage(msg(
                "messages.roulette_world.number_selected",
                "&eNúmero seleccionado: &f{number}",
                Map.of("number", WorldRouletteTables.formatNumber(number))));
    }

    public boolean hasBet(UUID playerId) {
        Map<String, WorldRouletteBet> m = bets.get(playerId);
        return m != null && !m.isEmpty();
    }

    public int getBetCount(UUID playerId) {
        Map<String, WorldRouletteBet> m = bets.get(playerId);
        return (m == null) ? 0 : m.size();
    }

    public boolean placeBet(Player player, long amountUnits) {
        if (amountUnits <= 0) {
            player.sendMessage(msg("messages.roulette_world.need_tokens", "&cNecesitas apostar tokens."));
            return false;
        }

        // Bets are only allowed during the betting window.
        if (state != State.COUNTDOWN) {
            player.sendMessage(msg(
                    "messages.roulette_world.bets_closed",
                    "&cApuestas cerradas. &7Espera a que se abran."));
            return false;
        }

        // (state == COUNTDOWN here)

        int maxBets = Math.max(1, plugin.getConfig().getInt("roulette_world.max_bets_per_player", 30));
        int currentCount = getBetCount(player.getUniqueId());

        WorldRouletteSelection sel = getSelection(player.getUniqueId());
        if (sel.getType() == WorldRouletteBetType.NUMBER && sel.getNumber() == null) {
            sendMultiline(player, msg(
                    "messages.roulette_world.select_number_first",
                    "&cSelecciona un número primero (click a un número).\n&7Tip: click izquierdo al centro para cambiar tipo."));
            return false;
        }

        WorldRouletteBetType type = sel.getType();
        Integer number = sel.getNumber();
        String betKey = betKey(type, number);

        Map<String, WorldRouletteBet> map = bets.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        WorldRouletteBet existing = map.get(betKey);
        boolean isNewKey = existing == null;
        if (isNewKey && currentCount >= maxBets) {
            player.sendMessage(msg(
                    "messages.roulette_world.max_bets",
                    "&cLlegaste al máximo de apuestas por ronda: &f{max}",
                    Map.of("max", String.valueOf(maxBets))));
            return false;
        }

        long newAmount = amountUnits;
        if (existing != null) {
            newAmount = existing.getAmount() + amountUnits;
        }

        WorldRouletteBet bet = new WorldRouletteBet(player.getUniqueId(), type, number, newAmount);
        map.put(betKey, bet);

        try {
            plugin.getRouletteStatsManager().recordWager(player.getUniqueId(), amountUnits);
        } catch (Throwable ignored) {
        }

        String pretty = prettyUnits(amountUnits);
        if (existing == null) {
            player.sendMessage(msg(
                    "messages.roulette_world.bet_added",
                    "&aApuesta agregada: &e{amount}&a en &f{bet}&7 ({current}/{max})",
                    Map.of(
                            "amount", pretty,
                            "bet", describeBet(bet),
                            "current", String.valueOf(currentCount + 1),
                            "max", String.valueOf(maxBets))));
        } else {
            player.sendMessage(msg(
                    "messages.roulette_world.bet_updated",
                    "&aApuesta actualizada: &e+{amount}&a en &f{bet}&7 (total: &f{total}&7)",
                    Map.of(
                            "amount", pretty,
                            "bet", describeBet(bet),
                            "total", prettyUnits(newAmount))));
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Apostar desde el menú (fichas del inventario, sin tope salvo max_bet)
    // ------------------------------------------------------------------

    /** Cobra {@code amount} en fichas y apuesta a {@code type}/{@code number}. Devuelve las fichas si falla. */
    public boolean placeBetFromWallet(Player player, WorldRouletteBetType type, Integer number, long amount) {
        if (state != State.COUNTDOWN) {
            player.sendMessage(msg("messages.roulette_world.bets_closed",
                    "&cApuestas cerradas. &7Espera a que se abran."));
            return false;
        }
        if (!TokenWallet.take(player, amount)) {
            player.sendMessage(msg("messages.roulette_world.not_enough",
                    "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    Map.of("balance", prettyUnits(TokenWallet.balance(player)))));
            return false;
        }
        WorldRouletteSelection sel = getSelection(player.getUniqueId());
        sel.setType(type);
        sel.setNumber(type == WorldRouletteBetType.NUMBER ? number : null);
        if (!placeBet(player, amount)) {
            TokenWallet.give(player.getUniqueId(), amount);
            return false;
        }
        return true;
    }

    /** Vuelve a poner las mismas apuestas de la ronda anterior. */
    public void repeatLastBet(Player player) {
        List<WorldRouletteBet> last = lastBets.get(player.getUniqueId());
        if (last == null || last.isEmpty()) {
            player.sendMessage(msg("messages.roulette_world.no_last_bet", "&7No tienes una apuesta anterior para repetir."));
            return;
        }
        if (state != State.COUNTDOWN) {
            player.sendMessage(msg("messages.roulette_world.bets_closed",
                    "&cApuestas cerradas. &7Espera a que se abran."));
            return;
        }
        long total = lastBetTotal(player.getUniqueId());
        if (!TokenWallet.take(player, total)) {
            player.sendMessage(msg("messages.roulette_world.not_enough",
                    "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    Map.of("balance", prettyUnits(TokenWallet.balance(player)))));
            return;
        }
        long refund = 0;
        for (WorldRouletteBet b : last) {
            WorldRouletteSelection sel = getSelection(player.getUniqueId());
            sel.setType(b.getType());
            sel.setNumber(b.getNumber());
            if (!placeBet(player, b.getAmount()))
                refund += b.getAmount();
        }
        if (refund > 0)
            TokenWallet.give(player.getUniqueId(), refund);
    }

    public long lastBetTotal(UUID playerId) {
        long t = 0;
        for (WorldRouletteBet b : lastBets.getOrDefault(playerId, List.of()))
            t += b.getAmount();
        return t;
    }

    public List<String> describeLastBets(UUID playerId) {
        List<String> out = new ArrayList<>();
        for (WorldRouletteBet b : lastBets.getOrDefault(playerId, List.of()))
            out.add("§f" + describeBet(b) + " §8» §e" + prettyUnits(b.getAmount()));
        return out;
    }

    public List<String> describeBets(UUID playerId) {
        List<String> out = new ArrayList<>();
        Map<String, WorldRouletteBet> m = bets.get(playerId);
        if (m != null)
            for (WorldRouletteBet b : m.values())
                out.add("§f" + describeBet(b) + " §8» §e" + prettyUnits(b.getAmount()));
        return out;
    }

    public String totalBet(UUID playerId) {
        long t = 0;
        Map<String, WorldRouletteBet> m = bets.get(playerId);
        if (m != null)
            for (WorldRouletteBet b : m.values())
                t += b.getAmount();
        return prettyUnits(t);
    }

    public void ensureAutoCycleStarted() {
        boolean enabled = plugin.getConfig().getBoolean("roulette_world.auto_cycle.enabled", true);
        if (!enabled)
            return;

        if (cycleTask != null)
            return;

        int intervalSeconds = Math.max(5, plugin.getConfig().getInt("roulette_world.auto_cycle.interval_seconds", 45));

        cycleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != State.WAITING)
                return;
            // Don't open if nobody is around in this world.
            World w = center.getWorld();
            if (w == null)
                return;
            if (w.getPlayers().isEmpty())
                return;
            if (plugin.getMaintenance() != null && plugin.getMaintenance().isTableClosed("ruleta", tableKey))
                return; // mesa cerrada

            startCountdown();
        }, 20L, intervalSeconds * 20L);
    }

    public void stopAllTasks() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (spinTask != null) {
            spinTask.cancel();
            spinTask = null;
        }
        if (cycleTask != null) {
            cycleTask.cancel();
            cycleTask = null;
        }
    }

    /**
     * Cancela la ronda en curso (reload / apagado / mesa eliminada) devolviendo
     * las fichas apostadas, para que no se pierdan.
     */
    public void abortRound() {
        stopAllTasks();
        for (Map.Entry<UUID, Map<String, WorldRouletteBet>> e : bets.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (e.getValue() == null)
                continue;
            long total = 0L;
            for (WorldRouletteBet b : e.getValue().values()) {
                total += b.getAmount();
            }
            if (p == null) {
                plugin.getPendingPayouts().add(e.getKey(), total);
                continue;
            }
            if (total > 0) {
                plugin.getTokenPayout().pay(p, total);
                p.sendMessage(msg(
                        "messages.roulette_world.round_aborted",
                        "&eLa ronda de ruleta se canceló. Se te devolvieron &f{amount}&e en fichas.",
                        Map.of("amount", prettyUnits(total))));
            }
        }
        bets.clear();
        restoreGlowstoneSegmentsExcept(null);
        lastHighlightKey = null;
        state = State.WAITING;
    }

    public void refreshHologramFromConfig() {
        World w = center.getWorld();
        if (w == null)
            return;

        double holoHeight = plugin.getConfig().getDouble("roulette_world.holo_height", 2.3);
        Location holoBase = center.clone().add(0.5, holoHeight, 0.5);

        List<String> lines = plugin.getConfig().getStringList("roulette_world.holo_lines");
        if (lines == null || lines.isEmpty()) {
            lines = List.of("§6§lRULETA", "§7Click derecho con tokens para apostar", "§eEsperando jugadores...");
        }

        for (int i = 0; i < holoDisplayIds.size(); i++) {
            Entity e = w.getEntity(holoDisplayIds.get(i));
            if (!(e instanceof TextDisplay td))
                continue;

            Location l = holoBase.clone().add(0, -0.25 * i, 0);
            td.teleport(l);

            String text = (i < lines.size()) ? plugin.color(lines.get(i)) : "";
            td.setText(text);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setDefaultBackground(false);
            td.setShadowed(true);
        }
    }

    private static String betKey(WorldRouletteBetType type, Integer number) {
        if (type == null)
            return "?";
        if (type == WorldRouletteBetType.NUMBER) {
            return "NUMBER:" + (number == null ? "?" : number);
        }
        return type.name();
    }

    private static String prettyUnits(long units) {
        try {
            return NumberFormat.getInstance(new Locale("es", "ES")).format(units);
        } catch (Exception ignored) {
            return String.valueOf(units);
        }
    }

    private static String describeBet(WorldRouletteBet bet) {
        if (bet.getType() == WorldRouletteBetType.NUMBER) {
            return "NÚMERO " + (bet.getNumber() == null ? "?" : WorldRouletteTables.formatNumber(bet.getNumber()));
        }
        return bet.getType().label();
    }

    private void startCountdown() {
        state = State.COUNTDOWN;
        countdownSeconds = plugin.getConfig().getInt("roulette_world.bet_window_seconds", 40);
        rollJackpot();

        updateHologramLine(2, "§aAPUESTAS ABIERTAS §7(" + countdownSeconds + "s)");

        if (countdownTask != null)
            countdownTask.cancel();
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != State.COUNTDOWN)
                return;

            countdownSeconds--;
            if (countdownSeconds <= 0) {
                if (countdownTask != null)
                    countdownTask.cancel();
                countdownTask = null;
                closeBetsAndMaybeSpin();
                return;
            }

            updateHologramLine(2, "§aAPUESTAS ABIERTAS §7(" + countdownSeconds + "s)");
        }, 20L, 20L);
    }

    private void closeBetsAndMaybeSpin() {
        // Remove bets from offline players (avoids phantom participants).
        // Sus fichas se devuelven cuando vuelvan a entrar.
        bets.entrySet().removeIf(e -> {
            if (e.getValue() == null || e.getValue().isEmpty())
                return true;
            if (Bukkit.getPlayer(e.getKey()) != null)
                return false;
            long total = 0L;
            for (WorldRouletteBet b : e.getValue().values()) {
                total += b.getAmount();
            }
            plugin.getPendingPayouts().add(e.getKey(), total);
            return true;
        });
        selections.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);

        updateHologramLine(2, "§cAPUESTAS CERRADAS");

        // If nobody bet, do not spin.
        if (bets.isEmpty()) {
            state = State.WAITING;
            updateHologramLine(2, "§cAPUESTAS CERRADAS");
            return;
        }

        startSpin();
    }

    private void startSpin() {
        state = State.SPINNING;
        updateHologramLine(2, "§6Girando...");
        restoreLastHighlight();

        // Basic animation: hop through wheel order for N ticks, then stop on winner.
        List<Integer> sequence = new ArrayList<>();
        for (int n : WorldRouletteTables.WHEEL_ORDER) {
            if (numberToBlockLocation.containsKey(n)) {
                sequence.add(n);
            }
        }
        if (sequence.isEmpty()) {
            sequence.addAll(numberToBlockLocation.keySet());
        }

        int winningNumber = sequence.get(ThreadLocalRandom.current().nextInt(sequence.size()));

        // La luz arranca en un número al azar, da vueltas rápido unos
        // segundos y frena de a poco hasta parar EXACTAMENTE en el ganador.
        int n = sequence.size();
        int startIdx = ThreadLocalRandom.current().nextInt(n);
        int winIdx = sequence.indexOf(winningNumber);
        int fastTicks = Math.max(20, (int) Math.round(plugin.getConfig().getDouble("roulette_world.spin_fast_seconds", 4.0) * 20));
        int minSlowSteps = Math.max(5, plugin.getConfig().getInt("roulette_world.spin_slow_steps", 18));
        int maxDelay = Math.max(2, plugin.getConfig().getInt("roulette_world.spin_slow_max_ticks", 12));
        // pasos rápidos (1 por tick) + pasos lentos, cuidando que el último caiga en el ganador
        int fastSteps = fastTicks;
        int slowSteps = minSlowSteps;
        while (Math.floorMod(startIdx + fastSteps + slowSteps - winIdx, n) != 0)
            fastSteps++; // como mucho una vuelta extra rápida (< 2 s)
        int[] delays = new int[fastSteps + slowSteps];
        for (int k = 0; k < fastSteps; k++)
            delays[k] = 1;
        for (int k = 0; k < slowSteps; k++) {
            double f = (k + 1) / (double) slowSteps;
            delays[fastSteps + k] = 1 + (int) Math.round((maxDelay - 1) * f * f);
        }

        if (spinTask != null)
            spinTask.cancel();
        spinTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int step = 0;
            int wait = 1;

            @Override
            public void run() {
                if (--wait > 0)
                    return;
                int current = sequence.get(Math.floorMod(startIdx + step + 1, n));
                highlightNumber(current);
                step++;
                if (step >= delays.length) {
                    if (spinTask != null)
                        spinTask.cancel();
                    spinTask = null;
                    finishRound(winningNumber);
                    return;
                }
                wait = delays[step];
            }
        }, 1L, 1L);
    }

    private void highlightNumber(int number) {
        Location loc = numberToBlockLocation.get(number);
        if (loc == null)
            return;
        World w = loc.getWorld();
        if (w == null)
            return;

        String key = WorldRouletteTables.key(loc);
        restoreGlowstoneSegmentsExcept(key);
        if (lastHighlightKey != null && !lastHighlightKey.equals(key)) {
            restoreBlock(lastHighlightKey);
        }

        if (!originals.containsKey(key)) {
            Block b = w.getBlockAt(loc);
            originals.put(key, new OriginalBlock(b.getType(), b.getBlockData().getAsString()));
        }

        w.getBlockAt(loc).setType(Material.GLOWSTONE, false);
        lastHighlightKey = key;
        w.playSound(loc, org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
    }

    private void restoreGlowstoneSegmentsExcept(String keepKey) {
        if (segmentKeyToNumber == null || segmentKeyToNumber.isEmpty())
            return;
        for (String key : segmentKeyToNumber.keySet()) {
            if (key == null || key.equals(keepKey))
                continue;
            Location loc = parseKey(key);
            if (loc == null || loc.getWorld() == null)
                continue;
            if (loc.getWorld().getBlockAt(loc).getType() == Material.GLOWSTONE) {
                restoreBlock(key);
            }
        }
    }

    private void restoreLastHighlight() {
        if (lastHighlightKey == null)
            return;
        restoreBlock(lastHighlightKey);
        lastHighlightKey = null;
    }

    private void restoreBlock(String key) {
        if (key == null || originals == null)
            return;
        Integer number = segmentKeyToNumber.get(key);
        if (number != null) {
            Location loc = parseKey(key);
            if (loc == null || loc.getWorld() == null)
                return;
            Material mat = WorldRouletteTables.isZero(number)
                    ? Material.LIME_CONCRETE
                    : (WorldRouletteTables.isRed(number) ? Material.RED_CONCRETE : Material.BLACK_CONCRETE);
            loc.getWorld().getBlockAt(loc).setType(mat, false);
            return;
        }
        OriginalBlock ob = originals.get(key);
        if (ob == null || ob.getType() == null)
            return;
        Location loc = parseKey(key);
        if (loc == null || loc.getWorld() == null)
            return;
        Block b = loc.getWorld().getBlockAt(loc);
        try {
            b.setType(ob.getType(), false);
        } catch (Throwable t) {
            b.setType(ob.getType());
        }
        String dataStr = ob.getBlockData();
        if (dataStr != null && !dataStr.isBlank()) {
            try {
                BlockData bd = Bukkit.createBlockData(dataStr);
                b.setBlockData(bd, false);
            } catch (Exception ignored) {
            }
        }
    }

    /** Últimos números que salieron en esta mesa, el más reciente primero. */
    private final java.util.Deque<Integer> recentNumbers = new java.util.ArrayDeque<>();

    public java.util.List<Integer> getRecentNumbers() {
        return new java.util.ArrayList<>(recentNumbers);
    }

    private void finishRound(int winningNumber) {
        recentNumbers.addFirst(winningNumber);
        while (recentNumbers.size() > 10)
            recentNumbers.removeLast();
        if (plugin.getWorldRouletteManager() != null)
            plugin.getWorldRouletteManager().saveRecent(tableKey, getRecentNumbers());
        updateHologramLine(2, "§aGanó el número: §f" + WorldRouletteTables.formatNumber(winningNumber));

        boolean isRed = WorldRouletteTables.isRed(winningNumber);

        Map<String, Long> winners = new LinkedHashMap<>();
        List<UUID> participants = new ArrayList<>(bets.keySet());

        for (Map.Entry<UUID, Map<String, WorldRouletteBet>> entry : bets.entrySet()) {
            UUID pid = entry.getKey();
            Player p = Bukkit.getPlayer(pid);

            try {
                plugin.getRouletteStatsManager().recordRound(pid);
            } catch (Throwable ignored) {
            }

            long totalPayout = 0L;
            long staked = 0L;
            for (WorldRouletteBet bet : entry.getValue().values()) {
                totalPayout += calculatePayout(bet, winningNumber, isRed);
                staked += bet.getAmount();
            }
            GamblingDexPlugin.recordStats(pid, "ruleta", staked, totalPayout);

            if (p == null) {
                // Se desconectó durante el giro: se le paga cuando vuelva.
                if (totalPayout > 0) {
                    plugin.getPendingPayouts().add(pid, totalPayout);
                    try {
                        plugin.getRouletteStatsManager().recordPayout(pid, totalPayout);
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }

            if (totalPayout > 0) {
                plugin.getTokenPayout().pay(p, totalPayout);
                p.sendMessage(msg(
                        "messages.roulette_world.win",
                        "&aGanaste &e{amount}&a en la ruleta.",
                        Map.of("amount", prettyUnits(totalPayout))));
                winners.put(p.getName(), totalPayout);
                try {
                    plugin.getRouletteStatsManager().recordPayout(pid, totalPayout);
                } catch (Throwable ignored) {
                }
            } else {
                p.sendMessage(msg(
                        "messages.roulette_world.lose",
                        "&cPerdiste tus apuestas en la ruleta."));
            }
        }

        boolean broadcast = plugin.getConfig().getBoolean("roulette_world.broadcast_winners", false);
        String winningNumberText = WorldRouletteTables.formatNumber(winningNumber);
        String winnersHeader = msg(
                "messages.roulette_world.winners_header",
                "&6&lRuleta ganadores &8(&f{number}&8)",
                Map.of("number", winningNumberText));
        if (broadcast) {
            Bukkit.broadcastMessage(winnersHeader);
            if (winners.isEmpty()) {
                Bukkit.broadcastMessage(msg(
                        "messages.roulette_world.winners_none",
                        "&7Nadie ganó esta ronda."));
            } else {
                for (Map.Entry<String, Long> w : winners.entrySet()) {
                    Bukkit.broadcastMessage(msg(
                            "messages.roulette_world.winners_entry",
                            "&e{name} &8» &a+{amount}",
                            Map.of("name", w.getKey(), "amount", prettyUnits(w.getValue()))));
                }
            }
        } else {
            // only send to participants
            for (UUID pid : participants) {
                Player p = Bukkit.getPlayer(pid);
                if (p == null)
                    continue;
                p.sendMessage(winnersHeader);
                if (winners.isEmpty()) {
                    p.sendMessage(msg(
                            "messages.roulette_world.winners_none",
                            "&7Nadie ganó esta ronda."));
                } else {
                    for (Map.Entry<String, Long> w : winners.entrySet()) {
                        p.sendMessage(msg(
                                "messages.roulette_world.winners_entry",
                                "&e{name} &8» &a+{amount}",
                                Map.of("name", w.getKey(), "amount", prettyUnits(w.getValue()))));
                    }
                }
            }
        }

        settleJackpot(winningNumber, participants);

        Bukkit.getScheduler().runTaskLater(plugin, this::restoreLastHighlight, 160L);

        for (Map.Entry<UUID, Map<String, WorldRouletteBet>> e : bets.entrySet())
            lastBets.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        bets.clear();
        selections.clear();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            state = State.WAITING;
            updateHologramLine(2, "§cAPUESTAS CERRADAS");
        }, 60L);
    }

    // ------------------------------------------------------------------
    // Jackpot
    // ------------------------------------------------------------------

    /** Al abrir las apuestas: a veces la ronda es de jackpot en un número al azar. */
    private void rollJackpot() {
        jackpotNumber = null;
        var cfg = plugin.getConfig();
        WorldRouletteManager mgr = plugin.getWorldRouletteManager();
        if (mgr == null || !cfg.getBoolean("roulette_world.jackpot.enabled", true))
            return;
        if (mgr.getJackpot() < Math.max(1L, cfg.getLong("roulette_world.jackpot.min_pot", 1000L)))
            return;
        double chance = Math.max(0.0, Math.min(100.0, cfg.getDouble("roulette_world.jackpot.chance_percent", 15.0)));
        if (ThreadLocalRandom.current().nextDouble(100.0) >= chance)
            return;
        List<Integer> nums = new ArrayList<>(numberToBlockLocation.keySet());
        if (nums.isEmpty())
            return;
        jackpotNumber = nums.get(ThreadLocalRandom.current().nextInt(nums.size()));
        updateHologramLine(1, plugin.color(msg("messages.roulette_world.jackpot_holo",
                "&6&l★ JACKPOT en el {number}: &e&l{pot} &6&l★",
                Map.of("number", WorldRouletteTables.formatNumber(jackpotNumber), "pot", prettyUnits(mgr.getJackpot())))));
        double r = cfg.getDouble("roulette_world.jackpot.announce_radius", 20.0);
        World w = center.getWorld();
        if (w != null && r > 0) {
            String text = msg("messages.roulette_world.jackpot_round",
                    "&6&l★ JACKPOT ★ &7Si sale el &f&l{number}&7, los que le apostaron &fpleno&7 se reparten &e&l{pot}&7!",
                    Map.of("number", WorldRouletteTables.formatNumber(jackpotNumber), "pot", prettyUnits(mgr.getJackpot())));
            for (Player p : w.getNearbyPlayers(center, r))
                p.sendMessage(text);
        }
    }

    /** Al terminar la ronda: lo perdido alimenta el pozo y, si salió el número del jackpot, se paga. */
    private void settleJackpot(int winningNumber, List<UUID> participants) {
        WorldRouletteManager mgr = plugin.getWorldRouletteManager();
        Integer jn = jackpotNumber;
        jackpotNumber = null;
        if (mgr == null || !plugin.getConfig().getBoolean("roulette_world.jackpot.enabled", true))
            return;

        // Quiénes le apostaron pleno al número del jackpot (antes de sumar lo perdido)
        Map<UUID, Long> onNumber = new LinkedHashMap<>();
        long onNumberTotal = 0;
        if (jn != null && jn == winningNumber) {
            for (Map.Entry<UUID, Map<String, WorldRouletteBet>> e : bets.entrySet()) {
                for (WorldRouletteBet b : e.getValue().values()) {
                    if (b.getType() == WorldRouletteBetType.NUMBER && b.getNumber() != null && b.getNumber() == jn) {
                        onNumber.merge(e.getKey(), b.getAmount(), Long::sum);
                        onNumberTotal += b.getAmount();
                    }
                }
            }
        }

        // Lo que perdió cada jugador va en parte al pozo
        double pct = Math.max(0.0, Math.min(100.0,
                plugin.getConfig().getDouble("roulette_world.jackpot.contribution_percent", 5.0))) / 100.0;
        boolean isRed = WorldRouletteTables.isRed(winningNumber);
        long add = 0;
        for (Map<String, WorldRouletteBet> m : bets.values()) {
            long staked = 0, paid = 0;
            for (WorldRouletteBet b : m.values()) {
                staked += b.getAmount();
                paid += calculatePayout(b, winningNumber, isRed);
            }
            if (staked > paid)
                add += (long) Math.floor((staked - paid) * pct);
        }

        if (jn != null && jn == winningNumber && onNumberTotal > 0) {
            long pot = mgr.getJackpot();
            long given = 0;
            List<String> names = new ArrayList<>();
            int i = 0;
            for (Map.Entry<UUID, Long> e : onNumber.entrySet()) {
                i++;
                long share = i == onNumber.size() ? pot - given
                        : (long) Math.floor(pot * (e.getValue() / (double) onNumberTotal));
                given += share;
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    plugin.getTokenPayout().pay(p, share);
                    p.sendTitle(plugin.color("&6&l★ JACKPOT ★"), plugin.color("&e+" + prettyUnits(share)), 10, 80, 20);
                    p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                } else {
                    plugin.getPendingPayouts().add(e.getKey(), share);
                }
                com.gamblingdex.GamblingDexPlugin.recordStats(e.getKey(), "ruleta", 0L, share);
                com.gamblingdex.GamblingDexPlugin.achievement(e.getKey(), "roulette_jackpot");
                String n = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("?");
                names.add(n + " (+" + prettyUnits(share) + ")");
            }
            String text = msg("messages.roulette_world.jackpot_won",
                    "&6&l★ JACKPOT ★ &7¡Salió el &f{number}&7! &e{players} &7se llevan &e&l{pot}",
                    Map.of("number", WorldRouletteTables.formatNumber(jn), "players", String.join(", ", names),
                            "pot", prettyUnits(pot)));
            if (plugin.getConfig().getBoolean("roulette_world.jackpot.broadcast_win", true)) {
                Bukkit.broadcastMessage(text);
            } else {
                // Solo a los que están cerca de la ruleta y a los que apostaron
                Set<UUID> to = new HashSet<>(participants);
                double r = plugin.getConfig().getDouble("roulette_world.jackpot.announce_radius", 20.0);
                World cw = center.getWorld();
                if (cw != null && r > 0)
                    for (Player p : cw.getNearbyPlayers(center, r))
                        to.add(p.getUniqueId());
                for (UUID id : to) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null)
                        p.sendMessage(text);
                }
            }
            plugin.getLogger().info("[Ruleta] Jackpot de " + pot + " en el " + WorldRouletteTables.formatNumber(jn)
                    + " para " + names);
            mgr.resetJackpot();
        } else if (jn != null) {
            String text = msg("messages.roulette_world.jackpot_missed",
                    "&6★ &7El jackpot era el &f{number}&7. Sigue acumulando: &e{pot}",
                    Map.of("number", WorldRouletteTables.formatNumber(jn), "pot", prettyUnits(mgr.getJackpot() + add)));
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null)
                    p.sendMessage(text);
            }
        }
        mgr.addToJackpot(add);

        // El renglón del holograma vuelve a su texto normal
        List<String> lines = plugin.getConfig().getStringList("roulette_world.holo_lines");
        if (lines.size() > 1)
            updateHologramLine(1, plugin.color(lines.get(1)));
    }

    private static long calculatePayout(WorldRouletteBet bet, int winningNumber, boolean isRed) {
        // Return total paid (includes returning stake) to match previous behavior.
        if (bet.getAmount() <= 0)
            return 0;

        WorldRouletteBetType type = bet.getType();
        if (!type.wins(winningNumber, bet.getNumber(), isRed))
            return 0L;
        // Paga "X a 1" + la apuesta (rojo 2x, docena 3x, número 36x).
        return bet.getAmount() * (type.payoutToOne() + 1L);
    }

    public void removeDisplays() {
        World w = center.getWorld();
        if (w == null)
            return;
        for (UUID id : numberDisplayIds) {
            Entity e = w.getEntity(id);
            if (e != null)
                e.remove();
        }
        for (UUID id : holoDisplayIds) {
            Entity e = w.getEntity(id);
            if (e != null)
                e.remove();
        }
    }

    public void removeBlocks() {
        restoreOriginalBlocks();
    }

    public void restoreOriginalBlocks() {
        if (originals == null || originals.isEmpty()) {
            // fallback: clear to air (older tables built before originals existed)
            World w = center.getWorld();
            if (w != null)
                w.getBlockAt(center).setType(Material.AIR);
            for (String segKey : segmentKeyToNumber.keySet()) {
                Location loc = parseKey(segKey);
                if (loc == null || loc.getWorld() == null)
                    continue;
                loc.getWorld().getBlockAt(loc).setType(Material.AIR);
            }
            return;
        }

        for (Map.Entry<String, OriginalBlock> e : originals.entrySet()) {
            Location loc = parseKey(e.getKey());
            if (loc == null || loc.getWorld() == null)
                continue;

            OriginalBlock ob = e.getValue();
            if (ob == null || ob.getType() == null)
                continue;

            Block b = loc.getWorld().getBlockAt(loc);
            try {
                b.setType(ob.getType(), false);
            } catch (Throwable t) {
                b.setType(ob.getType());
            }

            String dataStr = ob.getBlockData();
            if (dataStr != null && !dataStr.isBlank()) {
                try {
                    BlockData bd = Bukkit.createBlockData(dataStr);
                    b.setBlockData(bd, false);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Location parseKey(String key) {
        try {
            String[] parts = key.split(";");
            if (parts.length != 4)
                return null;
            World w = Bukkit.getWorld(parts[0]);
            if (w == null)
                return null;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(w, x, y, z);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void updateHologramLine(int index, String text) {
        if (index < 0 || index >= holoDisplayIds.size())
            return;
        World w = center.getWorld();
        if (w == null)
            return;
        Entity e = w.getEntity(holoDisplayIds.get(index));
        if (e instanceof TextDisplay td) {
            td.setText(text);
        }
    }
}
