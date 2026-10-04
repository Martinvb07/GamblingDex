package com.gamblingdex.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class RouletteBetMenuHolder implements InventoryHolder {

    private final String tableKey;

    public RouletteBetMenuHolder(String tableKey) {
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
