package com.gamblingdex.stats;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.modules.GameModule;
import org.bukkit.Bukkit;
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
 * último ganador, récord, pozos de los jackpots y tops. Se crean mirando un
 * cartel con /gdx sign add &lt;tipo&gt; [n] [juego]. Los textos de cada tipo están
 * en config.yml → signs.formats (4 líneas, unas 15 letras cada una).
 */
public final class CasinoSigns implements Listener {

    public static final List<String> TYPES = List.of("last_win", "record", "jackpot_slots", "jackpot_roulette", "top",
            "top_week");

    /** Un cartel registrado. {@code n} = puesto (1..10), {@code game} = juego del top (o vacío = todos). */
    private record Entry(String world, int x, int y, int z, String type, int n, String game) {
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
                    e.type(), String.valueOf(e.n()), e.game()));
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

    public boolean add(Block b, String type, int n, String game) {
        if (!(b.getState() instanceof Sign))
            return false;
        Entry e = new Entry(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), type, Math.max(1, Math.min(10, n)),
                game == null ? "" : game);
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
            out.add("&8- &f" + e.type() + (e.type().startsWith("top") || e.type().equals("last_win") ? " #" + e.n() : "")
                    + (e.game().isEmpty() ? "" : " &7(" + GameStats.gameName(e.game()) + ")")
                    + " &8@ &7" + e.world() + " " + e.x() + " " + e.y() + " " + e.z());
        return out;
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
            return; // ya no es un cartel (lo quitaron con WorldEdit, etc.)
        List<String> lines = lines(e);
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
        if (!sign.isWaxed()) {
            sign.setWaxed(true); // que nadie lo pueda editar
            changed = true;
        }
        if (changed)
            sign.update(true, false);
    }

    private List<String> lines(Entry e) {
        GameStats stats = plugin.getGameStats();
        String name = "-", amount = "0", game = "";
        switch (e.type()) {
            case "last_win" -> {
                List<GameStats.Play> wins = stats == null ? List.of() : stats.recentWins();
                if (e.n() <= wins.size()) {
                    GameStats.Play p = wins.get(e.n() - 1);
                    name = p.name();
                    amount = GameModule.units(p.net());
                    game = GameStats.gameName(p.game());
                }
            }
            case "record" -> {
                GameStats.Play r = stats == null ? null : stats.record();
                if (r != null) {
                    name = r.name();
                    amount = GameModule.units(r.net());
                    game = GameStats.gameName(r.game());
                }
            }
            case "jackpot_slots" -> amount = GameModule.units(
                    plugin.getSlotsController() == null ? 0 : plugin.getSlotsController().getJackpot());
            case "jackpot_roulette" -> amount = GameModule.units(
                    plugin.getWorldRouletteManager() == null ? 0 : plugin.getWorldRouletteManager().getJackpot());
            case "top", "top_week" -> {
                boolean week = e.type().equals("top_week");
                List<Map.Entry<UUID, Long>> top = stats == null ? List.of()
                        : e.game().isEmpty() ? stats.top(GameStats.Metric.PROFIT, week)
                                : stats.top(e.game(), GameStats.Metric.PROFIT, week);
                if (e.n() <= top.size()) {
                    Map.Entry<UUID, Long> t = top.get(e.n() - 1);
                    name = nameOf(t.getKey());
                    long v = t.getValue();
                    amount = (v < 0 ? "-" : "") + GameModule.units(Math.abs(v));
                }
                game = e.game().isEmpty() ? "Todos" : GameStats.gameName(e.game());
            }
            default -> {
            }
        }
        List<String> fmt = plugin.getConfig().getStringList("signs.formats." + e.type());
        if (fmt.isEmpty())
            fmt = defaults(e.type());
        List<String> out = new ArrayList<>();
        for (String l : fmt)
            out.add(plugin.color(l.replace("{n}", String.valueOf(e.n())).replace("{name}", name)
                    .replace("{amount}", amount).replace("{game}", game)));
        return out;
    }

    private static List<String> defaults(String type) {
        return switch (type) {
            case "last_win" -> List.of("&6&lGANADOR #{n}", "{name}", "&2+{amount}", "&8{game}");
            case "record" -> List.of("&6&l★ RÉCORD ★", "{name}", "&2+{amount}", "&8{game}");
            case "jackpot_slots" -> List.of("&5&lJACKPOT", "&5SLOTS", "&6&l{amount}", "&8¡Llévatelo!");
            case "jackpot_roulette" -> List.of("&4&lJACKPOT", "&4RULETA", "&6&l{amount}", "&8¡Llévatelo!");
            case "top" -> List.of("&1&lTOP #{n}", "{name}", "&2{amount}", "&8{game}");
            case "top_week" -> List.of("&1&lTOP SEMANA #{n}", "{name}", "&2{amount}", "&8{game}");
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

    /** Ubicación del cartel que mira el jugador, o null. */
    public static Block target(Player p) {
        Block b = p.getTargetBlockExact(6);
        return b != null && b.getState() instanceof Sign ? b : null;
    }
}
