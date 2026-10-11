/* (C)2025 open-spaced-repetition (MIT). Vendored from the java-fsrs library,
 * de-Lombok'd: https://github.com/open-spaced-repetition/java-fsrs */
package io.github.openspacedrepetition;

/** The three FSRS states a card can be in. */
public enum State {
    LEARNING(1),
    REVIEW(2),
    RELEARNING(3);

    private final int value;

    State(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static State fromValue(int value) {
        switch (value) {
            case 1:
                return LEARNING;
            case 2:
                return REVIEW;
            case 3:
                return RELEARNING;
            default:
                throw new IllegalArgumentException("unknown state " + value);
        }
    }
}