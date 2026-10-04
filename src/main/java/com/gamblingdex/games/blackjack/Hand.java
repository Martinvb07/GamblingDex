package com.gamblingdex.games.blackjack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Hand {

    private final List<Card> cards = new ArrayList<>();

    // Estado de juego (solo para manos de jugadores).
    private long bet;
    private boolean fromSplit;
    private boolean splitAces;
    private boolean doubled;
    private boolean done;
    private boolean settled;
    private String result;

    public Hand() {
    }

    public Hand(long bet) {
        this.bet = bet;
    }

    public void add(Card card) {
        if (card == null)
            return;
        cards.add(card);
    }

    /** Quita y devuelve la segunda carta (para dividir). */
    public Card removeSecond() {
        return cards.size() >= 2 ? cards.remove(1) : null;
    }

    public List<Card> cards() {
        return Collections.unmodifiableList(cards);
    }

    public int size() {
        return cards.size();
    }

    /** Blackjack natural: 21 con las 2 primeras cartas, sin haber dividido. */
    public boolean isBlackjack() {
        return size() == 2 && bestValue() == 21 && !fromSplit;
    }

    public boolean isBust() {
        return minValue() > 21;
    }

    /** Par para dividir: 2 cartas del mismo valor (mismo rango, o cualquier 10 si se permite). */
    public boolean isPair(boolean anyTenValue) {
        if (size() != 2)
            return false;
        Card a = cards.get(0);
        Card b = cards.get(1);
        if (a.rank() == b.rank())
            return true;
        return anyTenValue && a.rank().value() == 10 && b.rank().value() == 10;
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

    /** Valor para mostrar: BUST, BJ o el total. */
    public String valueLabel() {
        if (isBust())
            return "BUST";
        if (isBlackjack())
            return "BJ";
        return String.valueOf(bestValue());
    }

    public long bet() {
        return bet;
    }

    public void setBet(long bet) {
        this.bet = bet;
    }

    public boolean isFromSplit() {
        return fromSplit;
    }

    public void setFromSplit(boolean fromSplit) {
        this.fromSplit = fromSplit;
    }

    public boolean isSplitAces() {
        return splitAces;
    }

    public void setSplitAces(boolean splitAces) {
        this.splitAces = splitAces;
    }

    public boolean isDoubled() {
        return doubled;
    }

    public void setDoubled(boolean doubled) {
        this.doubled = doubled;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    /** Ya se pagó/resolvió (p. ej. blackjack natural pagado al instante). */
    public boolean isSettled() {
        return settled;
    }

    public void setSettled(boolean settled) {
        this.settled = settled;
    }

    public String result() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }
}
