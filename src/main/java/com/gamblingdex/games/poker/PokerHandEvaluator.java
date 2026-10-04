package com.gamblingdex.games.poker;

import com.gamblingdex.games.blackjack.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evaluador de manos de Texas Hold'em: elige la mejor mano de 5 cartas entre
 * las disponibles (2 a 7). Con menos de 5 cartas solo detecta parejas/tríos
 * (útil para mostrar la fuerza de la mano antes del flop).
 */
public final class PokerHandEvaluator {

    private PokerHandEvaluator() {
    }

    public enum Category {
        HIGH_CARD,
        PAIR,
        TWO_PAIR,
        THREE_OF_A_KIND,
        STRAIGHT,
        FLUSH,
        FULL_HOUSE,
        FOUR_OF_A_KIND,
        STRAIGHT_FLUSH
    }

    /**
     * Resultado comparable: mayor {@code score} = mejor mano. Manos con el mismo
     * score empatan (se reparte el bote).
     */
    public record Result(long score, Category category, List<Card> cards, String name)
            implements Comparable<Result> {

        @Override
        public int compareTo(Result o) {
            return Long.compare(score, o.score);
        }
    }

    /** A=14, K=13 ... 2=2. */
    public static int strength(Card.Rank rank) {
        return rank == Card.Rank.ACE ? 14 : rank.ordinal() + 1;
    }

    public static Result evaluate(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return new Result(0L, Category.HIGH_CARD, List.of(), "-");
        }
        if (cards.size() <= 5) {
            return evalExact(cards);
        }

        Result best = null;
        int n = cards.size();
        List<Card> combo = new ArrayList<>(5);
        for (int a = 0; a < n - 4; a++)
            for (int b = a + 1; b < n - 3; b++)
                for (int c = b + 1; c < n - 2; c++)
                    for (int d = c + 1; d < n - 1; d++)
                        for (int e = d + 1; e < n; e++) {
                            combo.clear();
                            combo.add(cards.get(a));
                            combo.add(cards.get(b));
                            combo.add(cards.get(c));
                            combo.add(cards.get(d));
                            combo.add(cards.get(e));
                            Result r = evalExact(combo);
                            if (best == null || r.score() > best.score()) {
                                best = r;
                            }
                        }
        return best;
    }

    private static Result evalExact(List<Card> cards) {
        int n = cards.size();
        int[] counts = new int[15];
        for (Card c : cards) {
            counts[strength(c.rank())]++;
        }

        boolean flush = false;
        if (n == 5) {
            flush = true;
            Card.Suit suit = cards.get(0).suit();
            for (Card c : cards) {
                if (c.suit() != suit) {
                    flush = false;
                    break;
                }
            }
        }
        int straightHigh = n == 5 ? straightHigh(counts) : 0;

        // Rangos distintos ordenados por (cantidad desc, rango desc).
        List<Integer> ordered = new ArrayList<>();
        for (int r = 14; r >= 2; r--) {
            if (counts[r] > 0)
                ordered.add(r);
        }
        ordered.sort((x, y) -> counts[y] != counts[x] ? counts[y] - counts[x] : y - x);

        int top = counts[ordered.get(0)];
        int second = ordered.size() > 1 ? counts[ordered.get(1)] : 0;

        Category cat;
        List<Integer> tiebreak;
        if (flush && straightHigh > 0) {
            cat = Category.STRAIGHT_FLUSH;
            tiebreak = List.of(straightHigh);
        } else if (top == 4) {
            cat = Category.FOUR_OF_A_KIND;
            tiebreak = ordered;
        } else if (top == 3 && second >= 2) {
            cat = Category.FULL_HOUSE;
            tiebreak = ordered;
        } else if (flush) {
            cat = Category.FLUSH;
            tiebreak = ordered;
        } else if (straightHigh > 0) {
            cat = Category.STRAIGHT;
            tiebreak = List.of(straightHigh);
        } else if (top == 3) {
            cat = Category.THREE_OF_A_KIND;
            tiebreak = ordered;
        } else if (top == 2 && second == 2) {
            cat = Category.TWO_PAIR;
            tiebreak = ordered;
        } else if (top == 2) {
            cat = Category.PAIR;
            tiebreak = ordered;
        } else {
            cat = Category.HIGH_CARD;
            tiebreak = ordered;
        }

        long score = (long) cat.ordinal() << 20;
        for (int i = 0; i < 5; i++) {
            int v = i < tiebreak.size() ? tiebreak.get(i) : 0;
            score |= (long) v << (16 - 4 * i);
        }

        return new Result(score, cat, Collections.unmodifiableList(new ArrayList<>(cards)),
                describe(cat, tiebreak));
    }

    /** Carta alta de la escalera (5 para A-2-3-4-5), o 0 si no hay escalera. */
    private static int straightHigh(int[] counts) {
        for (int high = 14; high >= 6; high--) {
            boolean ok = true;
            for (int r = high; r > high - 5; r--) {
                if (counts[r] == 0) {
                    ok = false;
                    break;
                }
            }
            if (ok)
                return high;
        }
        if (counts[14] > 0 && counts[2] > 0 && counts[3] > 0 && counts[4] > 0 && counts[5] > 0) {
            return 5;
        }
        return 0;
    }

    private static final String[] SINGULAR = { "", "", "Dos", "Tres", "Cuatro", "Cinco", "Seis", "Siete", "Ocho",
            "Nueve", "Diez", "Jota", "Reina", "Rey", "As" };
    private static final String[] PLURAL = { "", "", "Doses", "Treses", "Cuatros", "Cincos", "Seises", "Sietes",
            "Ochos", "Nueves", "Dieces", "Jotas", "Reinas", "Reyes", "Ases" };

    private static String describe(Category cat, List<Integer> t) {
        return switch (cat) {
            case HIGH_CARD -> "Carta alta: " + SINGULAR[t.get(0)];
            case PAIR -> "Pareja de " + PLURAL[t.get(0)];
            case TWO_PAIR -> "Doble pareja: " + PLURAL[t.get(0)] + " y " + PLURAL[t.get(1)];
            case THREE_OF_A_KIND -> "Trío de " + PLURAL[t.get(0)];
            case STRAIGHT -> "Escalera al " + SINGULAR[t.get(0)];
            case FLUSH -> "Color al " + SINGULAR[t.get(0)];
            case FULL_HOUSE -> "Full: " + PLURAL[t.get(0)] + " con " + PLURAL[t.get(1)];
            case FOUR_OF_A_KIND -> "Póker de " + PLURAL[t.get(0)];
            case STRAIGHT_FLUSH -> t.get(0) == 14 ? "Escalera Real" : "Escalera de color al " + SINGULAR[t.get(0)];
        };
    }
}
