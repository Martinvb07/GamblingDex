package com.gamblingdex.games.rouletteworld;

public enum WorldRouletteBetType {
    RED("ROJO", 1),
    BLACK("NEGRO", 1),
    EVEN("PAR", 1),
    ODD("IMPAR", 1),
    LOW("1-18", 1),
    HIGH("19-36", 1),
    DOZEN_1("1ª DOCENA (1-12)", 2),
    DOZEN_2("2ª DOCENA (13-24)", 2),
    DOZEN_3("3ª DOCENA (25-36)", 2),
    COLUMN_1("1ª COLUMNA", 2),
    COLUMN_2("2ª COLUMNA", 2),
    COLUMN_3("3ª COLUMNA", 2),
    NUMBER("NÚMERO", 35);

    private final String label;
    private final int payoutToOne;

    WorldRouletteBetType(String label, int payoutToOne) {
        this.label = label;
        this.payoutToOne = payoutToOne;
    }

    public String label() {
        return label;
    }

    /** Pago "X a 1" (sin contar la apuesta que se devuelve). */
    public int payoutToOne() {
        return payoutToOne;
    }

    /** ¿Gana esta apuesta si sale {@code n}? (0 y 00 solo los gana NUMBER). */
    public boolean wins(int n, Integer chosenNumber, boolean isRed) {
        if (this == NUMBER)
            return chosenNumber != null && chosenNumber == n;
        if (WorldRouletteTables.isZero(n))
            return false;
        return switch (this) {
            case RED -> isRed;
            case BLACK -> !isRed;
            case EVEN -> n % 2 == 0;
            case ODD -> n % 2 == 1;
            case LOW -> n <= 18;
            case HIGH -> n >= 19;
            case DOZEN_1 -> n <= 12;
            case DOZEN_2 -> n >= 13 && n <= 24;
            case DOZEN_3 -> n >= 25;
            case COLUMN_1 -> n % 3 == 1;
            case COLUMN_2 -> n % 3 == 2;
            case COLUMN_3 -> n % 3 == 0;
            case NUMBER -> false;
        };
    }
}
