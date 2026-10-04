package com.gamblingdex.games.rouletteworld;

import java.util.UUID;

public class WorldRouletteBet {

    private final UUID playerId;
    private final WorldRouletteBetType type;
    private final Integer number;
    private final long amount;

    public WorldRouletteBet(UUID playerId, WorldRouletteBetType type, Integer number, long amount) {
        this.playerId = playerId;
        this.type = type;
        this.number = number;
        this.amount = amount;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public WorldRouletteBetType getType() {
        return type;
    }

    public Integer getNumber() {
        return number;
    }

    public long getAmount() {
        return amount;
    }
}
