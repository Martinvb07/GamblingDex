package com.gamblingdex.games.poker;

import java.util.*;

/**
 * Estado de un torneo (sit & go) en una mesa de póker. Las fichas del torneo
 * no son tokens: solo se pagan los premios finales del pozo de inscripciones.
 */
public final class PokerTournament {

    final long entryFee;
    final long startingStack;
    final long levelMs;
    final long baseSb;
    final long baseBb;
    final List<Double> levels;

    /** Inscritos en orden (pagaron la inscripción). */
    final LinkedHashMap<UUID, String> registered = new LinkedHashMap<>();
    /** Eliminados en orden de salida (el primero en salir va primero). */
    final List<UUID> eliminated = new ArrayList<>();

    boolean started;
    long startMs;
    int level;

    PokerTournament(long entryFee, long startingStack, long levelMs, long baseSb, long baseBb, List<Double> levels) {
        this.entryFee = entryFee;
        this.startingStack = startingStack;
        this.levelMs = levelMs;
        this.baseSb = baseSb;
        this.baseBb = baseBb;
        this.levels = levels.isEmpty() ? List.of(1.0) : levels;
    }

    long prizePool(double cutPercent) {
        long gross = entryFee * registered.size();
        return (long) Math.floor(gross * (1.0 - Math.max(0, Math.min(50, cutPercent)) / 100.0));
    }

    /** Nivel según el tiempo transcurrido (se aplica al empezar cada mano). */
    int levelNow() {
        if (!started || levelMs <= 0)
            return 0;
        int l = (int) ((System.currentTimeMillis() - startMs) / levelMs);
        return Math.min(l, levels.size() - 1);
    }

    long sbAt(int lvl) {
        return Math.max(1L, Math.round(baseSb * levels.get(Math.min(lvl, levels.size() - 1))));
    }

    long bbAt(int lvl) {
        return Math.max(sbAt(lvl), Math.round(baseBb * levels.get(Math.min(lvl, levels.size() - 1))));
    }

    long msToNextLevel() {
        if (!started || levelMs <= 0 || level >= levels.size() - 1)
            return -1;
        return Math.max(0, startMs + (level + 1) * levelMs - System.currentTimeMillis());
    }

    int remaining() {
        return registered.size() - eliminated.size();
    }

    /**
     * Porcentajes de premio según cuántos se inscribieron. {@code table}: mapa
     * "mínimo de jugadores" → porcentajes; se usa la entrada más alta que aplique.
     */
    static List<Double> payoutsFor(int players, Map<Integer, List<Double>> table) {
        List<Double> best = List.of(100.0);
        int bestKey = -1;
        for (Map.Entry<Integer, List<Double>> e : table.entrySet()) {
            if (e.getKey() <= players && e.getKey() > bestKey && !e.getValue().isEmpty()) {
                bestKey = e.getKey();
                best = e.getValue();
            }
        }
        // Nunca más premios que jugadores.
        return best.size() > players ? best.subList(0, players) : best;
    }
}
