package com.gamblingdex.games.rouletteworld;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Set;

public final class WorldRouletteTables {

    private WorldRouletteTables() {
    }

    /**
     * Internal representation for "00".
     */
    public static final int DOUBLE_ZERO = 37;

    // Wheel order with an extra "00" inserted between 10 and 5.
    public static final int[] WHEEL_ORDER = new int[] {
            0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10,
            DOUBLE_ZERO, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26
    };

    private static final Set<Integer> REDS = Set.of(
            1, 3, 5, 7, 9,
            12, 14, 16, 18,
            19, 21, 23, 25, 27,
            30, 32, 34, 36);

    public static boolean isRed(int number) {
        return REDS.contains(number);
    }

    public static boolean isZero(int number) {
        return number == 0 || number == DOUBLE_ZERO;
    }

    public static String formatNumber(int number) {
        return number == DOUBLE_ZERO ? "00" : String.valueOf(number);
    }

    public static String key(Location location) {
        if (location == null)
            return "";
        World w = location.getWorld();
        String world = (w == null ? "world" : w.getName());
        return world + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
    }
}
