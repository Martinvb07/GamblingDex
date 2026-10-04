package com.gamblingdex.games.blackjack;

public record Card(Rank rank, Suit suit) {

    public enum Suit {
        CLUBS("♣"),
        DIAMONDS("♦"),
        HEARTS("♥"),
        SPADES("♠");

        private final String symbol;

        Suit(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    public enum Rank {
        ACE("A", 11),
        TWO("2", 2),
        THREE("3", 3),
        FOUR("4", 4),
        FIVE("5", 5),
        SIX("6", 6),
        SEVEN("7", 7),
        EIGHT("8", 8),
        NINE("9", 9),
        TEN("10", 10),
        JACK("J", 10),
        QUEEN("Q", 10),
        KING("K", 10);

        private final String label;
        private final int value;

        Rank(String label, int value) {
            this.label = label;
            this.value = value;
        }

        public String label() {
            return label;
        }

        public int value() {
            return value;
        }
    }

    public String shortName() {
        if (rank == null || suit == null)
            return "?";
        return rank.label() + suit.symbol();
    }
}
