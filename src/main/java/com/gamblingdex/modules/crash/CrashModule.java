package com.gamblingdex.modules.crash;

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
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
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
 * Crash: rondas globales. Un multiplicador sube desde x1.00 y "explota" en un
 * punto sorteado al empezar la ronda. Quien retira antes cobra apuesta x
 * multiplicador.
 *
 * Punto de explosión: crash = (1 - edge) / (1 - U), con U uniforme [0,1).
 * Así P(llegar a m) = (1 - edge) / m y cualquier estrategia de retiro devuelve
 * (1 - edge) de lo apostado a la larga.
 *
 * Se juega en mesas: /gdx station set crash (mirando un bloque). Click derecho
 * a la mesa = apostar; shift + click derecho = retirar. El holograma muestra
 * el multiplicador y cada jugador ve su apuesta y lo que cobraría sobre la
 * barra de experiencia.
 */
public class CrashModule extends GameModule {

    private static final SecureRandom RNG = new SecureRandom();

    private enum State {
        BETTING,
        RUNNING,
        PAUSE
    }

    private static final class Bet {
        final long amount;
        double auto; // 0 = sin retiro automático
        double cashedAt; // 0 = no retiró

        Bet(long amount, double auto) {
            this.amount = amount;
            this.auto = auto;
        }
    }

    /** Mesa de Crash: un bloque con holograma. */
    private static final class Station {
        final Location loc;
        UUID holo;

        Station(Location loc) {
            this.loc = loc;
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private State state = State.PAUSE;
    private final Map<UUID, Bet> bets = new LinkedHashMap<>();
    private final Map<UUID, Double> chosenAuto = new HashMap<>();
    private final Deque<Double> history = new ArrayDeque<>();
    private double crashPoint = 1.0;
    private double multiplier = 1.0;
    private long runStartMs;
    private int secondsLeft;
    private int tickCounter;
    private BossBar bar;
    private final Map<String, Station> stations = new LinkedHashMap<>();
    /** Últimos retiros de la ronda, para el holograma. */
    private final Deque<String> recentCashouts = new ArrayDeque<>();

    @Override
    public String id() {
        return "crash";
    }

    @Override
    public String displayName() {
        return "Crash";
    }

    @Override
    public void enable() {
        stations.clear();
        for (String k : loadData().getStringList("stations")) {
            Location l = BlackjackTables.parseKey(k);
            if (l != null)
                stations.put(k, new Station(l));
        }
        bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
        listen(new Events());
        startBetting();
        // 2 ticks = 0,1 s: suficiente para que el número suba suave.
        runTimer(this::tick, 2L, 2L);
    }

    @Override
    public void disable() {
        // Ronda cancelada: devolver lo apostado a quien no retiró. En la pausa
        // la ronda ya terminó (lo que no se retiró ya se perdió).
        for (Map.Entry<UUID, Bet> e : bets.entrySet()) {
            if (e.getValue().cashedAt > 0 || state == State.PAUSE)
                continue;
            TokenWallet.give(e.getKey(), e.getValue().amount);
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null)
                p.sendMessage(msg("refunded", "&7La ronda de Crash se canceló. Se te devolvieron &e{amount}&7.",
                        "amount", units(e.getValue().amount)));
        }
        bets.clear();
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        for (Station st : stations.values())
            removeHolo(st);
    }

    @Override
    public List<String> helpLines(boolean admin) {
        List<String> l = new ArrayList<>();
        l.add("&6&lCrash");
        l.add("&8• &fClick derecho&7 a la mesa: apostar &8| &fShift + click derecho&7: retirar");
        if (commandAllowed(admin)) {
            l.add("&8• &e/gdx crash &7- Abrir el menú");
            l.add("&8• &e/gdx crash apostar <monto> [auto] &7- Ej: &f/gdx crash apostar 100 2");
        }
        l.add("&8• &e/gdx crash retirar &7- Cobrar ahora");
        if (admin)
            l.add("&8• &e/gdx station set crash &7- Crear mesa (mirando un bloque)");
        l.add("");
        return l;
    }

    /** El menú por comando: siempre para admins; para jugadores según allow_command. */
    private boolean commandAllowed(boolean admin) {
        return admin || config().getBoolean("allow_command", false);
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean cashout = sub.equals("retirar") || sub.equals("cashout") || sub.equals("r");
        if (!cashout && !commandAllowed(isAdmin(player))) {
            player.sendMessage(msg("use_station", "&7Crash se juega en las mesas del casino: &fclick derecho&7 a una mesa de Crash."));
            return true;
        }
        if (args.length == 0) {
            openMenu(player);
            return true;
        }
        switch (sub) {
            case "apostar", "bet", "a" -> {
                if (args.length < 2) {
                    startBetFlow(player);
                    return true;
                }
                long amount = parseAmount(args[1]);
                double auto = 0;
                if (args.length >= 3) {
                    try {
                        auto = Double.parseDouble(args[2].replace("x", "").replace(",", "."));
                    } catch (NumberFormatException ignored) {
                    }
                }
                placeBet(player, amount, auto);
            }
            case "retirar", "cashout", "r" -> cashOut(player);
            default -> player.sendMessage(msg("usage", "&cUso: /gdx crash [apostar <monto> [auto] | retirar]"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Mesas (/gdx station set crash)
    // ------------------------------------------------------------------

    @Override
    public List<String> stationTypes() {
        return List.of("crash");
    }

    @Override
    public void createStation(Player player, Block target, String[] args) {
        String k = BlackjackTables.key(target.getLocation());
        if (stations.containsKey(k)) {
            player.sendMessage(msg("station_exists", "&eEse bloque ya es una mesa de Crash."));
            return;
        }
        Station st = new Station(target.getLocation());
        stations.put(k, st);
        saveStations();
        updateHolo(st);
        player.sendMessage(msg("station_created", "&aMesa de Crash creada. &7Click derecho al bloque para jugar."));
    }

    @Override
    public boolean removeStation(Player player, Block target) {
        Station st = stations.remove(BlackjackTables.key(target.getLocation()));
        if (st == null)
            return false;
        removeHolo(st);
        saveStations();
        player.sendMessage(msg("station_removed", "&aMesa de Crash eliminada."));
        return true;
    }

    @Override
    public List<String> stationListLines() {
        return List.of("&8- &6Crash&7: &f" + stations.size());
    }

    /** Click derecho a la mesa = apostar; shift + click derecho = retirar. */
    private void stationClick(Player p, boolean sneaking) {
        Bet b = bets.get(p.getUniqueId());
        switch (state) {
            case BETTING -> {
                if (b != null) {
                    p.sendMessage(msg("already_in_station", "&7Ya apostaste &e{amount}&7. Despega en &f{seconds}s&7.",
                            "amount", units(b.amount), "seconds", String.valueOf(secondsLeft)));
                    return;
                }
                long min = Math.max(1, config().getLong("min_bet", 10L));
                long max = Math.max(0, config().getLong("max_bet", 0L));
                AmountPickerMenu.open(p, "&6&lCrash &8- &eTu apuesta", min, max, min,
                        List.of("&7Despega en &f" + secondsLeft + "s",
                                "&7Shift + click derecho a la mesa para retirar"),
                        amount -> placeBet(p, amount, chosenAuto.getOrDefault(p.getUniqueId(), 0.0)), null);
            }
            case RUNNING -> {
                if (b == null) {
                    p.sendMessage(msg("wait_next", "&7Ronda en curso. Apuesta en la próxima."));
                } else if (b.cashedAt > 0) {
                    p.sendMessage(msg("already_cashed", "&7Ya retiraste en &fx{mult}&7.", "mult", fmt(b.cashedAt)));
                } else if (sneaking) {
                    cashOut(p);
                } else {
                    p.sendMessage(msg("cashout_hint", "&eShift + click derecho &7a la mesa para retirar."));
                }
            }
            case PAUSE -> p.sendMessage(msg("wait_next_seconds", "&7Siguiente ronda en &f{seconds}s&7.",
                    "seconds", String.valueOf(secondsLeft)));
        }
    }

    private void saveStations() {
        YamlConfiguration d = new YamlConfiguration();
        d.set("stations", new ArrayList<>(stations.keySet()));
        saveData(d);
    }

    private Station stationAt(Block b) {
        return b == null ? null : stations.get(BlackjackTables.key(b.getLocation()));
    }

    private boolean loaded(Station st) {
        World w = st.loc.getWorld();
        return w != null && w.isChunkLoaded(st.loc.getBlockX() >> 4, st.loc.getBlockZ() >> 4);
    }

    private void updateHolo(Station st) {
        if (!loaded(st))
            return;
        World w = st.loc.getWorld();
        Location at = st.loc.clone().add(0.5, config().getDouble("station_holo_height", 1.2), 0.5);
        Entity e = st.holo == null ? null : w.getEntity(st.holo);
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
            st.holo = td.getUniqueId();
        }
        long total = 0;
        for (Bet b : bets.values())
            total += b.amount;
        String players = "&7Jugadores: &f" + bets.size() + " &8| &7Apostado: &e" + units(total);
        StringBuilder sb = new StringBuilder("&6&l✦ CRASH ✦\n");
        switch (state) {
            case BETTING -> sb.append("&eApuestas abiertas: &f").append(secondsLeft).append("s\n").append(players)
                    .append("\n&7Click derecho: &fapostar");
            case RUNNING -> {
                sb.append(multiplier < 2 ? "&a&l" : multiplier < 5 ? "&e&l" : "&c&l").append("x")
                        .append(fmt(multiplier)).append("\n").append(players);
                for (String c : recentCashouts)
                    sb.append("\n").append(c);
                sb.append("\n&7Shift + click derecho: &fretirar");
            }
            case PAUSE -> sb.append("&c&lEXPLOTÓ en x").append(fmt(crashPoint)).append("\n&7Siguiente ronda en &f")
                    .append(secondsLeft).append("s");
        }
        td.setText(color(sb.toString()));
    }

    private void removeHolo(Station st) {
        if (st.holo == null)
            return;
        World w = st.loc.getWorld();
        Entity e = w == null ? null : w.getEntity(st.holo);
        if (e != null)
            e.remove();
        st.holo = null;
    }

    // ------------------------------------------------------------------
    // Rondas
    // ------------------------------------------------------------------

    private void startBetting() {
        state = State.BETTING;
        bets.clear();
        recentCashouts.clear();
        secondsLeft = Math.max(3, config().getInt("bet_window_seconds", 15));
        tickCounter = 0;
        multiplier = 1.0;
        String open = msg("round_open", "&6&lCrash &8» &7Nueva ronda: apuesta en los próximos &f{seconds}s",
                "seconds", String.valueOf(secondsLeft));
        for (Player p : bar.getPlayers())
            p.sendMessage(open);
    }

    private void startRun() {
        if (bets.isEmpty()) {
            // Nadie apostó: otra ventana de apuestas sin despegar.
            startBetting();
            return;
        }
        state = State.RUNNING;
        double edge = Math.max(0.0, Math.min(50.0, config().getDouble("house_edge_percent", 4.0))) / 100.0;
        double maxMult = Math.max(2.0, config().getDouble("max_multiplier", 1000.0));
        double u = RNG.nextDouble();
        double cp = Math.floor(100.0 * (1.0 - edge) / (1.0 - u)) / 100.0;
        crashPoint = Math.max(1.0, Math.min(maxMult, cp));
        multiplier = 1.0;
        runStartMs = System.currentTimeMillis();
        for (UUID id : bets.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null)
                p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8f, 1.0f);
        }
    }

    private void tick() {
        tickCounter++;
        switch (state) {
            case BETTING -> {
                if (tickCounter % 10 == 0 && --secondsLeft <= 0)
                    startRun();
                else
                    sendActionBars();
            }
            case RUNNING -> {
                double growth = Math.max(0.01, config().getDouble("growth_per_second", 0.07));
                double t = (System.currentTimeMillis() - runStartMs) / 1000.0;
                multiplier = Math.floor(Math.exp(growth * t) * 100.0) / 100.0;

                // Retiros automáticos que ya se alcanzaron (antes de explotar).
                for (Map.Entry<UUID, Bet> e : bets.entrySet()) {
                    Bet b = e.getValue();
                    if (b.cashedAt == 0 && b.auto >= 1.01 && b.auto <= Math.min(multiplier, crashPoint)) {
                        doCashOut(e.getKey(), b, b.auto);
                    }
                }
                if (multiplier >= crashPoint) {
                    multiplier = crashPoint;
                    crash();
                } else {
                    sendActionBars();
                }
            }
            case PAUSE -> {
                if (tickCounter % 10 == 0 && --secondsLeft <= 0)
                    startBetting();
            }
        }
        updateBar();
        for (Station st : stations.values())
            updateHolo(st);
        if (tickCounter % 2 == 0)
            refreshMenus();
    }

    private void crash() {
        state = State.PAUSE;
        secondsLeft = Math.max(1, config().getInt("pause_seconds", 5));
        history.addFirst(crashPoint);
        while (history.size() > 9)
            history.removeLast();

        String all = msg("crashed_all", "&6&lCrash &8» &c&lEXPLOTÓ en x{mult}", "mult", fmt(crashPoint));
        Set<UUID> told = new HashSet<>();
        for (Map.Entry<UUID, Bet> e : bets.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null)
                continue;
            told.add(p.getUniqueId());
            Bet b = e.getValue();
            if (b.cashedAt == 0) {
                p.sendMessage(msg("crashed_lost", "&c&l¡EXPLOTÓ en x{mult}! &7Perdiste &e{amount}&7.",
                        "mult", fmt(crashPoint), "amount", units(b.amount)));
                p.sendTitle(color("&c&lx" + fmt(crashPoint)), color("&7Explotó"), 2, 30, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.0f);
            } else {
                p.sendMessage(all);
            }
        }
        if (bar != null) {
            for (Player p : bar.getPlayers())
                if (!told.contains(p.getUniqueId()))
                    p.sendMessage(all);
        }
        long totalBet = 0, totalPaid = 0;
        for (Bet b : bets.values()) {
            totalBet += b.amount;
            if (b.cashedAt > 0)
                totalPaid += payoutFor(b, b.cashedAt);
        }
        plugin.getLogger().info("[Crash] Explotó en x" + fmt(crashPoint) + " | apostado " + totalBet
                + " pagado " + totalPaid + " jugadores " + bets.size());
    }

    private long payoutFor(Bet b, double mult) {
        long win = (long) Math.floor(b.amount * mult);
        long maxWin = config().getLong("max_win", 0L);
        return maxWin > 0 ? Math.min(win, maxWin) : win;
    }

    // ------------------------------------------------------------------
    // Apostar / retirar
    // ------------------------------------------------------------------

    private void startBetFlow(Player player) {
        if (state != State.BETTING) {
            player.sendMessage(msg("not_betting", "&cAhora no se puede apostar. Espera la próxima ronda."));
            return;
        }
        long min = Math.max(1, config().getLong("min_bet", 10L));
        long max = Math.max(0, config().getLong("max_bet", 0L));
        double auto = chosenAuto.getOrDefault(player.getUniqueId(), 0.0);
        AmountPickerMenu.open(player, "&6&lCrash &8- &eApuesta", min, max, min,
                List.of(auto > 0 ? "&7Retiro automático: &fx" + fmt(auto) : "&7Sin retiro automático"),
                amount -> {
                    placeBet(player, amount, chosenAuto.getOrDefault(player.getUniqueId(), 0.0));
                    openMenu(player);
                }, () -> openMenu(player));
    }

    private void placeBet(Player player, long amount, double auto) {
        if (state != State.BETTING) {
            player.sendMessage(msg("not_betting", "&cAhora no se puede apostar. Espera la próxima ronda."));
            return;
        }
        if (bets.containsKey(player.getUniqueId())) {
            player.sendMessage(msg("already_in", "&cYa apostaste en esta ronda."));
            return;
        }
        long min = Math.max(1, config().getLong("min_bet", 10L));
        long max = Math.max(0, config().getLong("max_bet", 0L));
        if (amount < min) {
            player.sendMessage(msg("min_bet", "&cLa apuesta mínima es &e{min}&c.", "min", units(min)));
            return;
        }
        if (max > 0 && amount > max) {
            player.sendMessage(msg("max_bet", "&cLa apuesta máxima es &e{max}&c.", "max", units(max)));
            return;
        }
        if (!TokenWallet.take(player, amount)) {
            player.sendMessage(msg("not_enough", "&cNo te alcanzan las fichas. Tienes &e{balance}&c.",
                    "balance", units(TokenWallet.balance(player))));
            return;
        }
        double a = auto >= 1.01 ? Math.floor(auto * 100.0) / 100.0 : 0.0;
        bets.put(player.getUniqueId(), new Bet(amount, a));
        String suffix = a > 0 ? msg("auto_suffix", " &7Retiro automático en &fx{auto}&7.", "auto", fmt(a)) : "";
        player.sendMessage(msg("joined", "&aApostaste &e{amount}&a en Crash.{auto}",
                "amount", units(amount), "auto", suffix));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.4f);
    }

    private void cashOut(Player player) {
        Bet b = bets.get(player.getUniqueId());
        if (b == null || state != State.RUNNING || b.cashedAt > 0) {
            player.sendMessage(msg("not_in", "&cNo estás jugando esta ronda."));
            return;
        }
        doCashOut(player.getUniqueId(), b, multiplier);
    }

    private void doCashOut(UUID id, Bet b, double mult) {
        if (b.cashedAt > 0 || state != State.RUNNING || mult > crashPoint)
            return;
        b.cashedAt = mult;
        long payout = payoutFor(b, mult);
        TokenWallet.give(id, payout);
        Player p = Bukkit.getPlayer(id);
        recentCashouts.addFirst("&f" + (p == null ? "?" : p.getName()) + " &7retiró en &ax" + fmt(mult));
        while (recentCashouts.size() > 3)
            recentCashouts.removeLast();
        if (p != null) {
            p.sendMessage(msg("cashed_out", "&a&l¡RETIRASTE! &7en &fx{mult} &7→ &e+{amount}",
                    "mult", fmt(mult), "amount", units(payout)));
            p.sendTitle(color("&a&lx" + fmt(mult)), color("&e+" + units(payout)), 2, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.3f);
        }
        long minB = config().getLong("broadcast_win_min", 0L);
        if (minB > 0 && payout >= minB) {
            String b2 = msg("big_win", "&6&lCrash &8» &f{player} &7retiró en &fx{mult} &7y ganó &e{amount}&7!",
                    "player", p == null ? "?" : p.getName(), "mult", fmt(mult), "amount", units(payout));
            for (Player o : Bukkit.getOnlinePlayers())
                o.sendMessage(b2);
        }
    }

    // ------------------------------------------------------------------
    // Pantalla: barra superior, action bar y menú
    // ------------------------------------------------------------------

    private void updateBar() {
        if (bar == null)
            return;
        String title;
        double progress;
        switch (state) {
            case BETTING -> {
                title = "&6&lCRASH &8| &eApuestas abiertas: &f" + secondsLeft + "s &8| &7Jugadores: &f" + bets.size();
                progress = Math.max(0, Math.min(1, secondsLeft / (double) Math.max(3, config().getInt("bet_window_seconds", 15))));
                bar.setColor(BarColor.YELLOW);
            }
            case RUNNING -> {
                title = "&6&lCRASH &8| &a&lx" + fmt(multiplier) + " &8| &7Jugadores: &f" + bets.size();
                progress = 1.0 - Math.min(1.0, (multiplier - 1.0) / 9.0);
                bar.setColor(multiplier < 2 ? BarColor.GREEN : multiplier < 5 ? BarColor.YELLOW : BarColor.RED);
            }
            default -> {
                title = "&6&lCRASH &8| &c&lExplotó en x" + fmt(crashPoint) + " &8| &7Siguiente en &f" + secondsLeft + "s";
                progress = 0.0;
                bar.setColor(BarColor.RED);
            }
        }
        bar.setTitle(color(title));
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));

        boolean everyone = config().getBoolean("show_bar_to_everyone", false);
        Set<UUID> wanted = new HashSet<>(bets.keySet());
        double radius = config().getDouble("station_bar_radius", 8.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (everyone || p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder
                    || nearStation(p, radius))
                wanted.add(p.getUniqueId());
        }
        for (Player p : new ArrayList<>(bar.getPlayers())) {
            if (!wanted.contains(p.getUniqueId()))
                bar.removePlayer(p);
        }
        for (UUID id : wanted) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !bar.getPlayers().contains(p))
                bar.addPlayer(p);
        }
    }

    private boolean nearStation(Player p, double radius) {
        if (radius <= 0)
            return false;
        Location pl = p.getLocation();
        for (Station st : stations.values()) {
            if (st.loc.getWorld() == pl.getWorld() && st.loc.distanceSquared(pl) <= radius * radius)
                return true;
        }
        return false;
    }

    private void sendActionBars() {
        for (Map.Entry<UUID, Bet> e : bets.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null)
                continue;
            Bet b = e.getValue();
            String text;
            if (state == State.BETTING) {
                text = "&7Tu apuesta: &e" + units(b.amount) + " &8| &7Despega en &f" + secondsLeft + "s"
                        + (b.auto > 0 ? " &8| &7Auto: &fx" + fmt(b.auto) : "");
            } else if (b.cashedAt > 0) {
                long pay = payoutFor(b, b.cashedAt);
                text = "&aRetiraste en x" + fmt(b.cashedAt) + " &8| &7Cobraste &e" + units(pay)
                        + " &a(+" + units(pay - b.amount) + ")";
            } else {
                long pay = payoutFor(b, multiplier);
                text = "&7Apuesta: &e" + units(b.amount) + " &8| &a&lx" + fmt(multiplier) + " &8| &7Cobras: &e"
                        + units(pay) + " &a(+" + units(pay - b.amount) + ") &8| &fShift+Click derecho = retirar";
            }
            p.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(color(text)));
        }
    }

    private void openMenu(Player player) {
        Inventory inv = Bukkit.createInventory(new MenuHolder(), 27, color("&6&lCrash"));
        fillMenu(player, inv);
        player.openInventory(inv);
    }

    private void refreshMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MenuHolder)
                fillMenu(p, top);
        }
    }

    private void fillMenu(Player p, Inventory inv) {
        for (int i = 0; i < 18; i++)
            inv.setItem(i, item(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        Bet b = bets.get(p.getUniqueId());
        double auto = chosenAuto.getOrDefault(p.getUniqueId(), 0.0);

        switch (state) {
            case BETTING -> {
                if (b == null) {
                    inv.setItem(11, item(Material.GOLD_BLOCK, "&a&lApostar",
                            List.of("&7Despega en &f" + secondsLeft + "s", "&7Tus fichas: &e" + units(TokenWallet.balance(p)))));
                } else {
                    inv.setItem(11, item(Material.LIME_STAINED_GLASS, "&aYa apostaste &e" + units(b.amount),
                            List.of("&7Despega en &f" + secondsLeft + "s")));
                }
                inv.setItem(13, item(Material.CLOCK, "&eApuestas abiertas: &f" + secondsLeft + "s",
                        List.of("&7Jugadores: &f" + bets.size())));
                inv.setItem(15, item(Material.HOPPER, "&bRetiro automático: &f" + (auto > 0 ? "x" + fmt(auto) : "no"),
                        List.of("&7Click para cambiar", "&7Retira solo al llegar a ese x")));
            }
            case RUNNING -> {
                if (b != null && b.cashedAt == 0) {
                    inv.setItem(13, item(Material.EMERALD_BLOCK, "&a&lRETIRAR &fx" + fmt(multiplier),
                            List.of("&7Cobras: &e" + units(payoutFor(b, multiplier)), "&7Apostaste: &f" + units(b.amount))));
                } else if (b != null) {
                    inv.setItem(13, item(Material.LIME_STAINED_GLASS, "&aRetiraste en x" + fmt(b.cashedAt),
                            List.of("&e+" + units(payoutFor(b, b.cashedAt)))));
                } else {
                    inv.setItem(13, item(Material.FIREWORK_ROCKET, "&a&lx" + fmt(multiplier),
                            List.of("&7No estás en esta ronda")));
                }
            }
            case PAUSE -> inv.setItem(13, item(Material.TNT, "&c&lExplotó en x" + fmt(crashPoint),
                    List.of("&7Siguiente ronda en &f" + secondsLeft + "s")));
        }

        // Historial (últimas explosiones)
        int slot = 18;
        for (int i = 18; i < 27; i++)
            inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        for (double h : history) {
            Material m = h < 2 ? Material.RED_STAINED_GLASS_PANE
                    : h < 5 ? Material.YELLOW_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
            inv.setItem(slot++, item(m, "&fx" + fmt(h), List.of("&8Ronda anterior")));
            if (slot >= 27)
                break;
        }
    }

    private void cycleAuto(Player p) {
        List<Double> opts = new ArrayList<>();
        opts.add(0.0);
        for (Object o : config().getList("auto_cashout_options", List.of(1.5, 2.0, 3.0, 5.0, 10.0))) {
            if (o instanceof Number n && n.doubleValue() >= 1.01)
                opts.add(n.doubleValue());
        }
        double cur = chosenAuto.getOrDefault(p.getUniqueId(), 0.0);
        int idx = 0;
        for (int i = 0; i < opts.size(); i++) {
            if (Math.abs(opts.get(i) - cur) < 0.001) {
                idx = i;
                break;
            }
        }
        double next = opts.get((idx + 1) % opts.size());
        chosenAuto.put(p.getUniqueId(), next);
        // Si ya apostó en esta ronda, se aplica a su apuesta.
        Bet b = bets.get(p.getUniqueId());
        if (b != null && state == State.BETTING)
            b.auto = next;
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

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    private final class Events implements Listener {

        @EventHandler
        public void onInteract(PlayerInteractEvent e) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK || stationAt(e.getClickedBlock()) == null)
                return;
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND)
                stationClick(e.getPlayer(), e.getPlayer().isSneaking());
        }

        @EventHandler
        public void onBreak(BlockBreakEvent e) {
            if (stationAt(e.getBlock()) == null)
                return;
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("cannot_break", "&cEs una mesa de Crash. Quítala con &f/gdx station remove&c."));
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof MenuHolder))
                return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p))
                return;
            int slot = e.getRawSlot();
            if (state == State.BETTING && slot == 11 && !bets.containsKey(p.getUniqueId())) {
                startBetFlow(p);
            } else if (state == State.BETTING && slot == 15) {
                cycleAuto(p);
                fillMenu(p, e.getInventory());
            } else if (state == State.RUNNING && slot == 13) {
                cashOut(p);
                fillMenu(p, e.getInventory());
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof MenuHolder)
                e.setCancelled(true);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            Bet b = bets.get(id);
            if (b == null)
                return;
            if (state == State.BETTING) {
                // Aún no despegó: devolver.
                bets.remove(id);
                TokenWallet.give(id, b.amount);
            } else if (state == State.RUNNING && b.cashedAt == 0) {
                // Desconectarse = retirar en el multiplicador actual.
                doCashOut(id, b, multiplier);
            }
        }
    }
}
