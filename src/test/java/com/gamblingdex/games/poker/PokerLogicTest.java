package com.gamblingdex.games.poker;

import com.gamblingdex.games.blackjack.Card;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PokerLogicTest {

    /** "As Kd 10h 2c" -> cartas. */
    static List<Card> cards(String spec) {
        List<Card> out = new ArrayList<>();
        for (String t : spec.trim().split("\\s+")) {
            String r = t.substring(0, t.length() - 1);
            char s = t.charAt(t.length() - 1);
            Card.Rank rank = switch (r) {
                case "A" -> Card.Rank.ACE;
                case "K" -> Card.Rank.KING;
                case "Q" -> Card.Rank.QUEEN;
                case "J" -> Card.Rank.JACK;
                case "10", "T" -> Card.Rank.TEN;
                default -> Card.Rank.values()[Integer.parseInt(r) - 1];
            };
            Card.Suit suit = switch (s) {
                case 's' -> Card.Suit.SPADES;
                case 'h' -> Card.Suit.HEARTS;
                case 'd' -> Card.Suit.DIAMONDS;
                default -> Card.Suit.CLUBS;
            };
            out.add(new Card(rank, suit));
        }
        return out;
    }

    static PokerHandEvaluator.Result eval(String spec) {
        return PokerHandEvaluator.evaluate(cards(spec));
    }

    @Test
    void categories() {
        assertEquals(PokerHandEvaluator.Category.STRAIGHT_FLUSH, eval("As Ks Qs Js 10s 2d 3c").category());
        assertEquals("Escalera Real", eval("As Ks Qs Js 10s 2d 3c").name());
        assertEquals(PokerHandEvaluator.Category.FOUR_OF_A_KIND, eval("9s 9h 9d 9c Kd 2c 3s").category());
        assertEquals(PokerHandEvaluator.Category.FULL_HOUSE, eval("9s 9h 9d Kc Kd 2c 3s").category());
        assertEquals(PokerHandEvaluator.Category.FLUSH, eval("2h 7h 9h Jh Kh As Ad").category());
        assertEquals(PokerHandEvaluator.Category.STRAIGHT, eval("5s 6h 7d 8c 9d Ks Kd").category());
        assertEquals(PokerHandEvaluator.Category.THREE_OF_A_KIND, eval("7s 7h 7d 2c 9d Ks 4d").category());
        assertEquals(PokerHandEvaluator.Category.TWO_PAIR, eval("7s 7h 9d 9c 2d Ks 4d").category());
        assertEquals(PokerHandEvaluator.Category.PAIR, eval("7s 7h 9d Jc 2d Ks 4d").category());
        assertEquals(PokerHandEvaluator.Category.HIGH_CARD, eval("7s 3h 9d Jc 2d Ks 4d").category());
    }

    @Test
    void wheelIsLowestStraight() {
        PokerHandEvaluator.Result wheel = eval("As 2h 3d 4c 5d Kd Qc");
        assertEquals(PokerHandEvaluator.Category.STRAIGHT, wheel.category());
        assertEquals("Escalera al Cinco", wheel.name());
        assertTrue(eval("2s 3h 4d 5c 6d Kd Qc").score() > wheel.score());
    }

    @Test
    void kickersDecide() {
        // Misma pareja de Ases, gana el mejor kicker.
        assertTrue(eval("As Ah Kd 7c 5d 3s 2h").score() > eval("As Ah Qd 7c 5d 3s 2h").score());
        // Doble pareja: decide la pareja alta antes que la baja.
        assertTrue(eval("Ks Kh 2d 2c 9d").score() > eval("Qs Qh Jd Jc 9d").score());
        // Full: decide el trío.
        assertTrue(eval("3s 3h 3d 2c 2d").score() > eval("2s 2h 2d Ac Ad").score());
        // Color contra escalera.
        assertTrue(eval("2h 4h 6h 8h 10h").score() > eval("9s 10h Jd Qc Kd").score());
    }

    @Test
    void boardPlaysIsSplit() {
        String board = "As Ks Qd Jc 10h";
        long a = PokerHandEvaluator.evaluate(cards(board + " 2c 3d")).score();
        long b = PokerHandEvaluator.evaluate(cards(board + " 4c 5d")).score();
        assertEquals(a, b);
    }

    @Test
    void preflopPairIsDetected() {
        assertEquals("Pareja de Reyes", eval("Ks Kd").name());
        assertEquals("Carta alta: As", eval("As 7d").name());
    }

    @Test
    void simplePotHasEveryone() {
        Map<Integer, Long> contributed = Map.of(0, 100L, 1, 100L, 2, 100L);
        List<PokerPots.Pot> pots = PokerPots.build(contributed, Set.of());
        assertEquals(1, pots.size());
        assertEquals(300L, pots.get(0).amount());
        assertEquals(3, pots.get(0).eligible().size());
    }

    @Test
    void sidePotsFromAllIns() {
        // 0 va all-in con 50, 1 con 200, 2 y 3 apuestan 500 (3 se retira).
        Map<Integer, Long> contributed = new HashMap<>();
        contributed.put(0, 50L);
        contributed.put(1, 200L);
        contributed.put(2, 500L);
        contributed.put(3, 500L);
        List<PokerPots.Pot> pots = PokerPots.build(contributed, Set.of(3));

        assertEquals(3, pots.size());
        assertEquals(200L, pots.get(0).amount()); // 50 * 4
        assertEquals(Set.of(0, 1, 2), new HashSet<>(pots.get(0).eligible()));
        assertEquals(450L, pots.get(1).amount()); // 150 * 3
        assertEquals(Set.of(1, 2), new HashSet<>(pots.get(1).eligible()));
        assertEquals(600L, pots.get(2).amount()); // 300 * 2
        assertEquals(Set.of(2), new HashSet<>(pots.get(2).eligible()));

        long total = pots.stream().mapToLong(PokerPots.Pot::amount).sum();
        assertEquals(1250L, total, "no se pierden ni se crean fichas");
    }

    @Test
    void foldedMoneyStaysInPot() {
        Map<Integer, Long> contributed = Map.of(0, 10L, 1, 40L, 2, 40L);
        List<PokerPots.Pot> pots = PokerPots.build(contributed, Set.of(0));
        assertEquals(1, pots.size());
        assertEquals(90L, pots.get(0).amount());
        assertEquals(Set.of(1, 2), new HashSet<>(pots.get(0).eligible()));
    }
}
