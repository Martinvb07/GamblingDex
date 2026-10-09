package com.gamblingdex.pack;

import com.gamblingdex.GamblingDexPlugin;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Lo que usan los menús y las máquinas del resource pack del casino: el fondo
 * dibujado de los menús (fuente gamblingdex:gui) y los sonidos propios
 * (gamblingdex:&lt;clave&gt;), con el sonido de Minecraft de siempre si están apagados.
 */
public final class CasinoPack {

    /** Caracteres de la fuente gamblingdex:gui (models/tools/build_pack.py). */
    public static final String BACK_8 = "", BACK_169 = "";
    public static final String SLOTS_BG = "", EXCHANGE_BG = "";

    private CasinoPack() {
    }

    /** Título de un menú con su fondo: vuelve al borde, pinta la imagen y el texto sigue en su sitio. */
    public static Component titleWithBackground(String background, String legacyTitle) {
        Component bg = Component.text(BACK_8 + background + BACK_169)
                .font(Key.key("gamblingdex", "gui"))
                .color(NamedTextColor.WHITE);
        return Component.text().append(bg)
                .append(LegacyComponentSerializer.legacySection().deserialize(legacyTitle)).build();
    }

    private static boolean customSounds() {
        GamblingDexPlugin plugin = GamblingDexPlugin.getInstance();
        return plugin != null && plugin.getResourcePackManager() != null
                && plugin.getResourcePackManager().customSounds();
    }

    /** Sonido para el jugador: el del pack (gamblingdex:&lt;key&gt;) o el de Minecraft. */
    public static void sound(Player p, String key, Sound vanilla, float volume, float pitch) {
        if (customSounds())
            p.playSound(p.getLocation(), "gamblingdex:" + key, volume, pitch);
        else
            p.playSound(p.getLocation(), vanilla, volume, pitch);
    }

    /** El mismo sonido saliendo de una máquina, para los que están cerca (menos el que juega). */
    public static void soundNear(Location at, Player except, String key, Sound vanilla, float volume, float pitch,
            double radius) {
        if (at == null || at.getWorld() == null)
            return;
        boolean custom = customSounds();
        for (Player o : at.getWorld().getPlayers()) {
            if (o.equals(except) || o.getLocation().distanceSquared(at) > radius * radius)
                continue;
            if (custom)
                o.playSound(at, "gamblingdex:" + key, volume, pitch);
            else
                o.playSound(at, vanilla, volume, pitch);
        }
    }
}
