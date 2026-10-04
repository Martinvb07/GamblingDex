package com.gamblingdex.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class RouletteNumberMenuHolder implements InventoryHolder {

    private final String tableKey;

    public RouletteNumberMenuHolder(String tableKey) {
        this.tableKey = tableKey;
    }

    public String getTableKey() {
        return tableKey;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
