package com.gamblingdex.util;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

public class SimpleHologram {
    private final ArmorStand stand;

    public SimpleHologram(Location loc, String text, Plugin plugin) {
        this.stand = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(new Vector(0, 0.3, 0)),
                EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setCustomNameVisible(true);
        stand.setCustomName(text);
        stand.setGravity(false);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setInvulnerable(true);
        stand.setCollidable(false);
        stand.setSilent(true);
    }

    public void setText(String text) {
        stand.setCustomName(text);
    }

    public void remove() {
        stand.remove();
    }

    public ArmorStand getEntity() {
        return stand;
    }
}
