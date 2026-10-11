/* (C)2025 open-spaced-repetition (MIT). Vendored from the java-fsrs library,
 * de-Lombok'd: https://github.com/open-spaced-repetition/java-fsrs */
package io.github.openspacedrepetition;

/** The four ratings a user can give a review, matching the app's numpad 1..4. */
public enum Rating {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4);

    private final int value;

    Rating(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}