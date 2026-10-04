package com.gamblingdex.games.blackjack;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class BlackjackTables {

    private BlackjackTables() {
    }

    public static String key(Location loc) {
        if (loc == null || loc.getWorld() == null)
            return null;
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    public static Location parseKey(String key) {
        try {
            if (key == null)
                return null;
            String[] parts = key.split(";", -1);
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
}
