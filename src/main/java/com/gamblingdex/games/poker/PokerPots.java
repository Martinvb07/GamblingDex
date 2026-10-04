package com.gamblingdex.games.poker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Arma el bote principal y los botes laterales a partir de lo que aportó cada
 * asiento en la mano. Las fichas de jugadores que se retiraron (fold) quedan
 * como dinero muerto en los botes, pero ellos no pueden ganarlos.
 */
public final class PokerPots {

    private PokerPots() {
    }

    public record Pot(long amount, List<Integer> eligible) {
    }

    public static List<Pot> build(Map<Integer, Long> contributed, Set<Integer> folded) {
        List<Pot> pots = new ArrayList<>();
        if (contributed == null || contributed.isEmpty())
            return pots;

        TreeSet<Long> levels = new TreeSet<>();
        for (Map.Entry<Integer, Long> e : contributed.entrySet()) {
            if (!folded.contains(e.getKey()) && e.getValue() > 0) {
                levels.add(e.getValue());
            }
        }

        long total = 0L;
        for (long v : contributed.values())
            total += v;

        long prev = 0L;
        long assigned = 0L;
        for (long level : levels) {
            long amount = 0L;
            List<Integer> eligible = new ArrayList<>();
            for (Map.Entry<Integer, Long> e : contributed.entrySet()) {
                long c = e.getValue();
                amount += Math.min(c, level) - Math.min(c, prev);
                if (!folded.contains(e.getKey()) && c >= level) {
                    eligible.add(e.getKey());
                }
            }
            if (amount > 0) {
                pots.add(new Pot(amount, eligible));
                assigned += amount;
            }
            prev = level;
        }

        // Dinero muerto por encima del mayor aporte vivo (raro): va al último bote.
        long leftover = total - assigned;
        if (leftover > 0 && !pots.isEmpty()) {
            Pot last = pots.remove(pots.size() - 1);
            pots.add(new Pot(last.amount() + leftover, last.eligible()));
        }
        return pots;
    }
}
