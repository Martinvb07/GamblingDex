package com.gamblingdex.games.blackjack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Hand {

    private final List<Card> cards = new ArrayList<>();

    public void add(Card card) {
        if (card == null)
            return;
        cards.add(card);
    }

    public List<Card> cards() {
        return Collections.unmodifiableList(cards);
    }

    public int size() {
        return cards.size();
    }

    public boolean isBlackjack() {
        return size() == 2 && bestValue() == 21;
    }

    public boolean isBust() {
        return minValue() > 21;
    }

    public int bestValue() {
        // Classic blackjack: start with A=11, then downgrade as needed.
        int total = 0;
        int aces = 0;
        for (Card c : cards) {
            if (c == null || c.rank() == null)
                continue;
            total += c.rank().value();
            if (c.rank() == Card.Rank.ACE)
                aces++;
        }

        while (total > 21 && aces > 0) {
            total -= 10; // 11 -> 1
            aces--;
        }
        return total;
    }

    public int minValue() {
        // Aces as 1.
        int total = 0;
        for (Card c : cards) {
            if (c == null || c.rank() == null)
                continue;
            if (c.rank() == Card.Rank.ACE) {
                total += 1;
            } else {
                total += c.rank().value();
            }
        }
        return total;
    }

    /**
     * Soft hand: at least one Ace is still counted as 11 (e.g. A+6, A+A+5).
     */
    public boolean isSoft() {
        return bestValue() != minValue();
    }

    public boolean isSoft17() {
        return bestValue() == 17 && isSoft();
    }

    public String describe(boolean hideSecondCard) {
        if (cards.isEmpty())
            return "(vacío)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0)
                sb.append(" ");
            if (hideSecondCard && i == 1) {
                sb.append("??");
            } else {
                Card c = cards.get(i);
                sb.append(c == null ? "?" : c.shortName());
            }
        }
        return sb.toString();
    }
}
