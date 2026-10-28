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
