package com.flashcards.xp3.data;

import com.flashcards.xp3.algo.Fsrs;

import io.github.openspacedrepetition.Card;

/**
 * One card as the app knows it: the definition from the bundled deck plus the
 * FSRS memory state, which lives split across the definitions (never changing)
 * and the per-user review log (always changing). The FSRS state is the
 * {@link Card} class of the vendored java-fsrs library; the app only ever
 * routes it through {@link Fsrs} or stores it verbatim.
 */
public final class DeckCard {

    /** The immutable definition from the bundled deck. */
    public final CardDef def;

    /** The mutable FSRS state, owned by the scheduler (java-fsrs Card). */
    public Card fsrs;

    public DeckCard(CardDef def) {
        this.def = def;
        this.fsrs = Card.builder().cardId(idFor(def.identity)).build();
    }

    public DeckCard(CardDef def, Card fsrs) {
        this.def = def;
        this.fsrs = fsrs;
    }

    /** Estimated probability of recall right now, per FSRS. */
    public double probability(long now) {
        return Fsrs.retrievability(fsrs, now);
    }

    /** Whether the user has never reviewed this card. */
    public boolean isNew() {
        return fsrs.getLastReview() == null;
    }

    /** A stable, deterministic id derived from the deck definition. */
    public static int idFor(String key, String value) {
        return (key + "\u0000" + value).hashCode();
    }

    public static int idFor(String identity) {
        return identity.hashCode();
    }
}
