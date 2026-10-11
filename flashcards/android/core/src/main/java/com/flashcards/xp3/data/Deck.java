package com.flashcards.xp3.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deck: a name plus its cards, each pairing a bundled definition with its
 * FSRS state.
 *
 * Decks are bundled with the app at build time; there is no import. The deck
 * keeps the card order stable (the deck's definition order is its list order)
 * so the app opens the same on every launch. The FSRS state is stored
 * separately per user, keyed by card identity, and applied on load.
 */
public final class Deck {

    public final String name;
    private final List<DeckCard> cards;
    private final Map<String, DeckCard> byId;

    public Deck(String name, List<DeckCard> cards) {
        this.name = name;
        this.cards = cards;
        this.byId = new LinkedHashMap<>();
        for (DeckCard card : cards) byId.put(id(card.def), card);
    }

    public List<DeckCard> cards() {
        return cards;
    }

    public int size() {
        return cards.size();
    }

    /** Looks a card up by its definition identity, to rejoin FSRS state. */
    public DeckCard byId(String cardId) {
        return byId.get(cardId);
    }

    public static String id(CardDef def) {
        return def.identity;
    }

}
