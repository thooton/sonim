package com.flashcards.xp3.algo;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;

import java.time.Instant;

/**
 * The app's seam to the established FSRS implementation.
 *
     * The scheduling itself is not hand-rolled: the core vends an Android/Java-8
     * adaptation of java-fsrs 1.0.0 under {@code io.github.openspacedrepetition},
     * updated to the reference FSRS-6 defaults and corrected fuzz/short-term
     * behavior. This class translates
 * the app's world — epoch-second timestamps, the numpad's 1..4 rating — into
 * the library's API, and nothing more.
 */
public final class Fsrs {

    /** One library-configured scheduler, shared app-wide (like Anki's). */
    public static final Scheduler ENGINE = Scheduler.builder().build();

    private Fsrs() {}

    /** A freshly built, default-configured library scheduler. */
    public static Scheduler defaults() {
        return Scheduler.builder().build();
    }

    /** Retrievability of a card at an epoch-second timestamp (0 when unseen). */
    public static double retrievability(Card card, long nowSeconds) {
        return ENGINE.getCardRetrievability(card, Instant.ofEpochSecond(nowSeconds));
    }

    /** Applies a rating (1=again, 2=hard, 3=good, 4=easy), returning the new card. */
    public static Card review(Card card, int rating, long nowSeconds) {
        if (rating < 1 || rating > 4) {
            throw new IllegalArgumentException("rating must be between 1 and 4");
        }
        return ENGINE.reviewCard(
                card, Rating.values()[rating - 1], Instant.ofEpochSecond(nowSeconds))
            .getCard();
    }
}
