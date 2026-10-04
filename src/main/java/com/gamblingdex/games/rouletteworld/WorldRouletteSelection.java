package com.gamblingdex.games.rouletteworld;

public class WorldRouletteSelection {

    private WorldRouletteBetType type;
    private Integer number;

    public WorldRouletteSelection(WorldRouletteBetType type, Integer number) {
        this.type = type;
        this.number = number;
    }

    public WorldRouletteBetType getType() {
        return type;
    }

    public void setType(WorldRouletteBetType type) {
        this.type = type;
        if (type != WorldRouletteBetType.NUMBER) {
            this.number = null;
        }
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }
}
