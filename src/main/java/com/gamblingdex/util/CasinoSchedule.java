package com.gamblingdex.util;

import com.gamblingdex.GamblingDexPlugin;
import com.gamblingdex.config.ConfigUpdater;
import com.gamblingdex.gui.Icons;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Horario del casino: abre y cierra solo a las horas de schedule.yml (hora de
 * Colombia por defecto). Fuera de horario nadie puede jugar; al cerrar se
 * devuelven las apuestas en curso. Se configura con /gdx schedule (menú) o en
 * el archivo.
 */
public final class CasinoSchedule implements Listener {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale ES = Locale.forLanguageTag("es-ES");

    private static final int S_TOGGLE = 10, S_OPEN = 12, S_CLOSE = 14, S_STATUS = 16;

    private static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GamblingDexPlugin plugin;
    private final File file;
    private YamlConfiguration cfg;
    private Boolean wasOpen;
    private final Set<Integer> warned = new HashSet<>();

    public CasinoSchedule(GamblingDexPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "schedule.yml");
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 200L); // cada 10 s
    }

    public void reload() {
        if (!file.exists() && plugin.getResource("schedule.yml") != null)
            plugin.saveResource("schedule.yml", false);
        cfg = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration jar = ConfigUpdater.jarConfig(plugin, "schedule.yml");
        if (jar != null) {
            cfg.setDefaults(jar);
            ConfigUpdater.update(plugin, "schedule.yml", file, cfg, jar);
        }
    }

    private void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar schedule.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Horario
    // ------------------------------------------------------------------

    public boolean enabled() {
        return cfg.getBoolean("enabled", false);
    }

    /** Los admins pueden jugar fuera de horario (para probar). */
    public boolean adminBypass() {
        return cfg.getBoolean("admin_bypass", false);
    }

    /** Zona horaria del casino (schedule.yml → timezone, Colombia por defecto). */
    public ZoneId zone() {
        try {
            return ZoneId.of(cfg.getString("timezone", "America/Bogota"));
        } catch (Exception e) {
            return ZoneId.of("America/Bogota");
        }
    }

    private LocalTime time(String key, String def) {
        try {
            return LocalTime.parse(cfg.getString(key, def), HM);
        } catch (Exception e) {
            return LocalTime.parse(def, HM);
        }
    }

    private LocalTime openTime() {
        return time("open", "18:00");
    }

    private LocalTime closeTime() {
        return time("close", "02:00");
    }

    private Set<DayOfWeek> days() {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        List<String> list = cfg.isList("days") ? cfg.getStringList("days") : List.of();
        for (String s : list)
            try {
                out.add(DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        if (!cfg.isList("days"))
            out.addAll(EnumSet.allOf(DayOfWeek.class));
        return out;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(zone());
    }

    /** ¿Está abierto en este momento? (siempre true si el horario está apagado) */
    public boolean isOpenNow() {
        return !enabled() || isOpenAt(now());
    }

    private boolean isOpenAt(ZonedDateTime t) {
        LocalTime open = openTime(), close = closeTime(), now = t.toLocalTime();
        Set<DayOfWeek> days = days();
        DayOfWeek today = t.getDayOfWeek();
        if (open.equals(close))
            return days.contains(today); // todo el día
        if (open.isBefore(close))
            return days.contains(today) && !now.isBefore(open) && now.isBefore(close);
        // Cruza la medianoche: el turno pertenece al día en que abre
        return (days.contains(today) && !now.isBefore(open))
                || (days.contains(today.minus(1)) && now.isBefore(close));
    }

    /** Minutos que faltan para el próximo cambio (abrir o cerrar), máx. 8 días. */
    private long minutesToChange() {
        ZonedDateTime t = now().withSecond(0).withNano(0);
        boolean open = isOpenAt(t);
        for (int m = 1; m <= 8 * 24 * 60; m++)
            if (isOpenAt(t.plusMinutes(m)) != open)
                return m;
        return -1;
    }

    private String fmtWait(long minutes) {
        if (minutes < 0)
            return "-";
        if (minutes < 60)
            return minutes + " min";
        long h = minutes / 60, m = minutes % 60;
        if (h < 24)
            return h + " h" + (m > 0 ? " " + m + " min" : "");
        return h / 24 + " d " + h % 24 + " h";
    }

    private String msg(String key, String def) {
        return plugin.color(cfg.getString("messages." + key, def)
                .replace("{open}", openTime().format(HM))
                .replace("{close}", closeTime().format(HM))
                .replace("{zone}", cfg.getString("zone_name", "hora Colombia")));
    }

    /** Aviso para el que intenta jugar fuera de horario. */
    public String closedMessage() {
        return msg("closed", "&c&l⚠ &cEl casino está cerrado. Abre a las &e{open} &7({zone})&c.");
    }

    private void tick() {
        if (!enabled()) {
            wasOpen = null;
            warned.clear();
            return;
        }
        boolean open = isOpenAt(now());
        if (wasOpen != null && wasOpen != open) {
            warned.clear();
            if (open) {
                Bukkit.broadcastMessage(msg("opened", "&a&l✦ CASINO &8» &a¡El casino está abierto! &7Cierra a las &e{close}&7."));
            } else {
                Bukkit.broadcastMessage(msg("closed_now", "&c&l✦ CASINO &8» &cEl casino cerró. &7Vuelve a las &e{open}&7."));
                var mt = plugin.getMaintenance();
                if (mt != null)
                    mt.refundAll();
            }
        } else if (open) {
            long left = minutesToChange();
            for (int w : cfg.getIntegerList("warn_minutes"))
                if (left > 0 && left <= w && warned.add(w)) {
                    Bukkit.broadcastMessage(msg("closing_soon", "&e&l✦ CASINO &8» &eEl casino cierra en &f{minutes} min&e.")
                            .replace("{minutes}", String.valueOf(left)));
                    break;
                }
        }
        wasOpen = open;
    }

    // ------------------------------------------------------------------
    // Menú (/gdx schedule)
    // ------------------------------------------------------------------

    public void open(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 27, plugin.color("&8&l✦ &6&lHORARIO DEL CASINO &8&l✦"));
        render(m.inv);
        p.openInventory(m.inv);
    }

    private static final String[] ADJUST = { "&7Click izquierdo: &a+1 hora", "&7Click derecho: &c-1 hora",
            "&7Shift + izquierdo: &a+15 min", "&7Shift + derecho: &c-15 min" };

    private void render(Inventory inv) {
        inv.clear();
        boolean on = enabled();
        inv.setItem(S_TOGGLE, Icons.of(on ? Material.LIME_CONCRETE : Material.RED_CONCRETE, 1,
                on ? "&a&lHorario ACTIVADO" : "&c&lHorario DESACTIVADO",
                List.of(on ? "&7El casino abre y cierra solo." : "&7El casino está abierto siempre.", "",
                        "&eClick para " + (on ? "desactivar" : "activar")), on));
        List<String> openLore = new ArrayList<>(List.of("&7Hora en que abre el casino.", ""));
        openLore.addAll(List.of(ADJUST));
        inv.setItem(S_OPEN, Icons.of(Material.CLOCK, 1, "&aAbre: &f&l" + openTime().format(HM), openLore, false));
        List<String> closeLore = new ArrayList<>(List.of("&7Hora en que cierra el casino.",
                "&8(si es antes de la apertura, cierra al día siguiente)", ""));
        closeLore.addAll(List.of(ADJUST));
        inv.setItem(S_CLOSE, Icons.of(Material.CLOCK, 1, "&cCierra: &f&l" + closeTime().format(HM), closeLore, false));

        ZonedDateTime n = now();
        boolean openNow = isOpenNow();
        long change = on ? minutesToChange() : -1;
        inv.setItem(S_STATUS, Icons.of(openNow ? Material.EMERALD : Material.REDSTONE, 1,
                openNow ? "&a&lAbierto ahora" : "&c&lCerrado ahora", List.of(
                        "&7Hora actual: &f" + n.format(HM) + " &8(" + cfg.getString("zone_name", "hora Colombia") + ")",
                        "&7Hoy: &f" + cap(n.getDayOfWeek().getDisplayName(TextStyle.FULL, ES)),
                        on ? "&7" + (openNow ? "Cierra" : "Abre") + " en: &f" + fmtWait(change) : "&8(horario desactivado)"),
                false));

        Set<DayOfWeek> days = days();
        DayOfWeek[] all = DayOfWeek.values();
        for (int i = 0; i < 7; i++) {
            DayOfWeek d = all[i];
            boolean opens = days.contains(d);
            inv.setItem(19 + i, Icons.of(opens ? Material.LIME_DYE : Material.GRAY_DYE, 1,
                    (opens ? "&a" : "&7") + cap(d.getDisplayName(TextStyle.FULL, ES)),
                    List.of(opens ? "&aAbre este día" : "&8Cerrado este día", "&eClick para cambiar"), opens));
        }
        Icons.fill(inv, Material.BLACK_STAINED_GLASS_PANE);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m))
            return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()
                || !p.hasPermission("gamblingdex.admin"))
            return;
        int slot = e.getRawSlot();
        int step = e.isShiftClick() ? 15 : 60;
        int sign = e.isRightClick() ? -1 : 1;
        switch (slot) {
            case S_TOGGLE -> {
                cfg.set("enabled", !enabled());
                wasOpen = true; // si al activarlo ya es hora de cerrar, el próximo tick cierra y devuelve las apuestas
            }
            case S_OPEN -> cfg.set("open", openTime().plusMinutes((long) sign * step).format(HM));
            case S_CLOSE -> cfg.set("close", closeTime().plusMinutes((long) sign * step).format(HM));
            default -> {
                if (slot < 19 || slot > 25)
                    return;
                DayOfWeek d = DayOfWeek.values()[slot - 19];
                Set<DayOfWeek> days = days();
                if (!days.remove(d))
                    days.add(d);
                List<String> list = new ArrayList<>();
                for (DayOfWeek x : DayOfWeek.values())
                    if (days.contains(x))
                        list.add(x.name());
                cfg.set("days", list);
            }
        }
        save();
        render(m.inv);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu)
            e.setCancelled(true);
    }
}
