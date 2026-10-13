package com.gamblingdex.modules;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Módulo cuyas estaciones con menú llevan una máquina 3D de ModelEngine
 * (StationModels): dónde están, su holograma, hacia dónde miran y qué pasa al
 * clickear el modelo. La config del modelo va en {@code modules/<id>.yml → model}.
 */
public interface ModelStations {

    List<Location> modelStationLocations();

    UUID modelStationHologram(Location station);

    float modelStationYaw(Location station);

    /** Click en la máquina 3D: lo mismo que click derecho al bloque de la estación. */
    void openFromModel(Player player, Location station);
}
