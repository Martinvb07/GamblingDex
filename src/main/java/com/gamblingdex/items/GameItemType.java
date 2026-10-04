package com.gamblingdex.items;

import org.bukkit.Material;

public enum GameItemType {
    ROULETTE("roulette", Material.LODESTONE),
    SLOTS("slots", Material.ENCHANTING_TABLE),
    EXCHANGE("exchange", Material.LOOM);

    private final String id;
    private final Material material;

    GameItemType(String id, Material material) {
        this.id = id;
        this.material = material;
    }

    public String getId() {
        return id;
    }

    public Material getMaterial() {
        return material;
    }

    public static GameItemType fromArg(String arg) {
        if (arg == null)
            return null;
        String s = arg.trim().toLowerCase();
        return switch (s) {
            case "roulette", "ruleta" -> ROULETTE;
            case "slots", "slot", "tragamonedas", "tragam", "trag" -> SLOTS;
            case "exchange", "cambio", "cambios", "loom", "lomm" -> EXCHANGE;
            default -> null;
        };
    }
}
