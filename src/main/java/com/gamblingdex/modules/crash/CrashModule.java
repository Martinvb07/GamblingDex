package com.gamblingdex.modules.crash;

import com.gamblingdex.economy.TokenWallet;
import com.gamblingdex.games.blackjack.BlackjackTables;
import com.gamblingdex.gui.AmountPickerMenu;
import com.gamblingdex.modules.GameModule;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

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

    private State state = State.PAUSE;
    private final Map<UUID, Bet> bets = new LinkedHashMap<>();
    private double crashPoint = 1.0;
    private double multiplier = 1.0;
    private long runStartMs;
    private int secondsLeft;
    private int tickCounter;
    private BossBar bar;
    private final Map<String, Station> stations = new LinkedHashMap<>();

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
        if (admin)
            l.add("&8• &e/gdx station set crash &7- Crear mesa (mirando un bloque)");
        l.add("");
        return l;
    }

    @Override
    public boolean onCommand(Player player, String[] args) {
        // Crash solo se juega en las mesas: no hay comandos de jugador.
        player.sendMessage(msg("use_station",
                "&7Crash se juega en las mesas: &fclick derecho&7 para apostar, &fshift + click derecho&7 para retirar."));
        if (isAdmin(player))
            player.sendMessage(msg("admin_hint", "&7Admin: &f/gdx station set crash &7(mirando un bloque) | &f/gdx station remove"));
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
                        amount -> placeBet(p, amount, 0.0), null);
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
        // Solo el multiplicador (lo de cada jugador va sobre su barra de experiencia).
        String text = switch (state) {
            case BETTING -> "&e&lx1.00\n&7Apuestas: &f" + secondsLeft + "s";
            case RUNNING -> (multiplier < 2 ? "&a&l" : multiplier < 5 ? "&e&l" : "&c&l") + "x" + fmt(multiplier);
            case PAUSE -> "&c&lx" + fmt(crashPoint) + "\n&7Explotó";
        };
        td.setText(color(text));
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
        secondsLeft = Math.max(3, config().getInt("bet_window_seconds", 15));
        tickCounter = 0;
        multiplier = 1.0;
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
    }

    private void crash() {
        state = State.PAUSE;
        secondsLeft = Math.max(1, config().getInt("pause_seconds", 5));

        // Resumen solo para los que jugaron la ronda.
        List<String> winners = new ArrayList<>();
        List<String> losers = new ArrayList<>();
        for (Map.Entry<UUID, Bet> e : bets.entrySet()) {
            Bet b = e.getValue();
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("?");
            if (b.cashedAt > 0)
                winners.add("&f" + name + " &7(x" + fmt(b.cashedAt) + ", &e+" + units(payoutFor(b, b.cashedAt) - b.amount) + "&7)");
            else
                losers.add("&f" + name);
        }
        String summary = msg("crashed_all", "&6&lCrash &8» &c&lEXPLOTÓ en x{mult}", "mult", fmt(crashPoint))
                + "\n" + msg("summary_winners", "&7Ganaron: {players}", "players",
                        winners.isEmpty() ? color("&8nadie") : color(String.join("&7, ", winners)))
                + "\n" + msg("summary_losers", "&7Perdieron: {players}", "players",
                        losers.isEmpty() ? color("&8nadie") : color(String.join("&7, ", losers)));
        for (Map.Entry<UUID, Bet> e : bets.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null)
                continue;
            Bet b = e.getValue();
            if (b.cashedAt == 0) {
                p.sendMessage(msg("crashed_lost", "&c&l¡EXPLOTÓ en x{mult}! &7Perdiste &e{amount}&7.",
                        "mult", fmt(crashPoint), "amount", units(b.amount)));
                p.sendTitle(color("&c&lx" + fmt(crashPoint)), color("&7Explotó"), 2, 30, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.0f);
            }
            p.sendMessage(summary);
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
        if (p != null) {
            p.sendMessage(msg("cashed_out", "&a&l¡RETIRASTE! &7en &fx{mult} &7→ &e+{amount}",
                    "mult", fmt(mult), "amount", units(payout)));
            p.sendTitle(color("&a&lx" + fmt(mult)), color("&e+" + units(payout)), 2, 30, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.3f);
        }
        String others = msg("cashout_others", "&6&lCrash &8» &f{player} &7retiró en &ax{mult} &7y ganó &e{amount}",
                "player", p == null ? "?" : p.getName(), "mult", fmt(mult), "amount", units(payout));
        for (UUID other : bets.keySet()) {
            Player o = Bukkit.getPlayer(other);
            if (o != null && !other.equals(id))
                o.sendMessage(others);
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
        // Solo los que apostaron en la ronda (o todos si show_bar_to_everyone).
        if (everyone) {
            for (Player p : Bukkit.getOnlinePlayers())
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
