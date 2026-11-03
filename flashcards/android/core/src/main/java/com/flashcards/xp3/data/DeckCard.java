package com.flashcards.xp3.data;

import io.github.openspacedrepetition.Card;

/**
 * One card as the app knows it: its definition, its place in the deck, and
 * the FSRS memory state (the {@link Card} class kept from the java-fsrs
 * library, which only the scheduler, {@code algo.Fsrs}, changes), with what
 * sync needs to know about it.
 */
public final class DeckCard {

    /**
     * The definition: front, back, aux and identity. The identity never
     * changes; the text does when it is corrected on the command center.
     */
    public CardDef def;

    /** The mutable FSRS state, owned by the scheduler (java-fsrs Card). */
    public Card fsrs;

    /** Its place in the deck: new cards come in this order. */
    public int ord;
    /** When it was first answered, Unix seconds, or 0 (never, or unknown). */
    public long intro;
    /**
     * The highest stability this card has ever reached, in days, or null when
     * it has never been reviewed. Unlike {@link Card#getStability()} (which a
     * lapse lowers), this only ever rises, so the "learned" progress built on
     * it ({@link com.flashcards.xp3.algo.ReviewSession#progress}) never goes
     * backwards. It rides along with the FSRS state through sync, merged as a
     * maximum so no device can lower it.
     */
    public Double peak;
    /** When it last changed, device milliseconds, for sync. */
    public long mod;
    /** Changed here since the last sync. */
    public boolean dirty;

    public DeckCard(CardDef def) {
        this.def = def;
        this.fsrs = Card.builder().cardId(idFor(def.identity)).build();
    }

    public DeckCard(CardDef def, Card fsrs) {
        this.def = def;
        this.fsrs = fsrs;
    }

    /** Another record of the same card, sharing its definition and memory state. */
    DeckCard copy() {
        DeckCard copy = new DeckCard(def, fsrs);
        copy.ord = ord;
        copy.intro = intro;
        copy.peak = peak;
        copy.mod = mod;
        copy.dirty = dirty;
        return copy;
    }

    /** Whether the user has never reviewed this card. */
    public boolean isNew() {
        return fsrs.getLastReview() == null;
    }

    /**
     * The best-ever stability to measure learning by: the stored {@link #peak}
     * if it is higher than the current stability, else the current one (so a
     * record from before peaks were kept, or one synced without a peak, still
     * counts at least its stability). 0 for a card never reviewed.
     */
    public double peakStability() {
        double s = fsrs.getStability() == null ? 0 : fsrs.getStability();
        double p = peak == null ? 0 : peak;
        return Math.max(s, p);
    }

    /** A stable, deterministic id derived from the deck definition. */
    public static int idFor(String key, String value) {
        return (key + "\u0000" + value).hashCode();
    }

    public static int idFor(String identity) {
        return identity.hashCode();
    }
}
