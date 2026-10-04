package com.gamblingdex.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class PokerActionMenuHolder implements InventoryHolder {

    private final String tableKey;

    public PokerActionMenuHolder(String tableKey) {
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
