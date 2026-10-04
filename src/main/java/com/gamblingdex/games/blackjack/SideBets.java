package com.gamblingdex.games.blackjack;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Arrays;

/**
 * Apuestas laterales estándar de casino:
 * - Pares Perfectos: las 2 primeras cartas del jugador.
 * - 21+3: las 2 cartas del jugador + la carta visible del dealer (póker de 3 cartas).
 *
 * Los multiplicadores son "X a 1" (ganancia neta); el pago total incluye la apuesta.
 */
public final class SideBets {

    private SideBets() {
    }

    public record Result(String name, int multiplier) {
        public long totalPayout(long bet) {
            return bet + bet * (long) multiplier;
        }
    }

    // ---------------- Pares Perfectos ----------------

    public static Result perfectPairs(Card a, Card b, FileConfiguration cfg) {
        if (a == null || b == null || a.rank() != b.rank())
            return null;
        String base = "blackjack.side_bets.perfect_pairs.";
        if (a.suit() == b.suit()) {
            return new Result("Par Perfecto", cfg.getInt(base + "perfect_pair", 25));
        }
        if (isRed(a) == isRed(b)) {
            return new Result("Par de Color", cfg.getInt(base + "colored_pair", 12));
        }
        return new Result("Par Mixto", cfg.getInt(base + "mixed_pair", 6));
    }

    // ---------------- 21+3 ----------------

    public static Result twentyOnePlusThree(Card a, Card b, Card dealerUp, FileConfiguration cfg) {
        if (a == null || b == null || dealerUp == null)
            return null;
        String base = "blackjack.side_bets.21_plus_3.";

        boolean flush = a.suit() == b.suit() && b.suit() == dealerUp.suit();
        boolean trips = a.rank() == b.rank() && b.rank() == dealerUp.rank();
        boolean straight = isStraight(a, b, dealerUp);

        if (trips && flush)
            return new Result("Trío del mismo palo", cfg.getInt(base + "suited_trips", 100));
        if (straight && flush)
            return new Result("Escalera de color", cfg.getInt(base + "straight_flush", 40));
        if (trips)
            return new Result("Trío", cfg.getInt(base + "three_of_a_kind", 30));
        if (straight)
            return new Result("Escalera", cfg.getInt(base + "straight", 10));
        if (flush)
            return new Result("Color", cfg.getInt(base + "flush", 5));
        return null;
    }

    private static boolean isStraight(Card... cards) {
        int[] r = new int[cards.length];
        for (int i = 0; i < cards.length; i++) {
            r[i] = rankOrder(cards[i].rank());
        }
        Arrays.sort(r);
        if (r[0] + 1 == r[1] && r[1] + 1 == r[2])
            return true;
        // Q-K-A (el As también cuenta alto)
        return r[0] == 1 && r[1] == 12 && r[2] == 13;
    }

    private static int rankOrder(Card.Rank rank) {
        // A=1, 2..10, J=11, Q=12, K=13
        return rank.ordinal() + 1;
    }

    private static boolean isRed(Card c) {
        return c.suit() == Card.Suit.HEARTS || c.suit() == Card.Suit.DIAMONDS;
    }
}
