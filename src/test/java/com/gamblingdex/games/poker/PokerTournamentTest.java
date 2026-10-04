package com.gamblingdex.games.poker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PokerTournamentTest {

    private static final Map<Integer, List<Double>> PAYOUTS = Map.of(
            2, List.of(100.0),
            4, List.of(70.0, 30.0),
            7, List.of(50.0, 30.0, 20.0));

    @Test
    void payoutsDependOnPlayerCount() {
        assertEquals(List.of(100.0), PokerTournament.payoutsFor(2, PAYOUTS));
        assertEquals(List.of(100.0), PokerTournament.payoutsFor(3, PAYOUTS));
        assertEquals(List.of(70.0, 30.0), PokerTournament.payoutsFor(5, PAYOUTS));
        assertEquals(List.of(50.0, 30.0, 20.0), PokerTournament.payoutsFor(9, PAYOUTS));
    }

    @Test
    void neverMorePrizesThanPlayers() {
        Map<Integer, List<Double>> bad = Map.of(2, List.of(50.0, 30.0, 20.0));
        assertEquals(2, PokerTournament.payoutsFor(2, bad).size());
    }

    @Test
    void blindsScaleWithLevel() {
        PokerTournament t = new PokerTournament(100, 1000, 60_000, 5, 10, List.of(1.0, 2.0, 4.0));
        assertEquals(5, t.sbAt(0));
        assertEquals(10, t.bbAt(0));
        assertEquals(20, t.sbAt(2));
        assertEquals(40, t.bbAt(2));
        assertEquals(40, t.bbAt(99), "después del último nivel se queda en el último");
    }

    @Test
    void prizePoolAppliesHouseCut() {
        PokerTournament t = new PokerTournament(100, 1000, 60_000, 5, 10, List.of(1.0));
        for (int i = 0; i < 4; i++)
            t.registered.put(UUID.randomUUID(), "p" + i);
        assertEquals(360, t.prizePool(10.0));
        t.eliminated.add(t.registered.keySet().iterator().next());
        assertEquals(3, t.remaining());
    }
}
