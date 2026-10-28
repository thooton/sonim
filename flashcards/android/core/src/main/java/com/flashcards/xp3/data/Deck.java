package com.flashcards.xp3.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deck: its name, its new-cards-per-day setting, and its cards, each
 * pairing a definition with its FSRS state.
 *
 * Decks live on the command center and every device keeps a copy (see
 * {@link Collection}). The deck keeps its cards in their order (`ord`), so
 * new cards come in the same order everywhere.
 */
public final class Deck {

    /** {@link #newPerDay} for AUTO: new cards come in as earlier ones are learned (ReviewSession). */
    public static final int AUTO = -1;
    /** What a deck made from scratch starts with, as in Anki. */
    public static final int DEFAULT_NEW_PER_DAY = 20;

    public final String id;
    public String name;
    /** {@link #AUTO}, or how many new cards a day. */
    public int newPerDay;
    /** When the deck last changed, in device milliseconds (for sync). */
    public long mod;
    /** Changed here since the last sync. */
    public boolean dirty;

    private final List<DeckCard> cards;
    private final Map<String, DeckCard> byId;

    public Deck(String id, String name, int newPerDay, List<DeckCard> cards) {
        this.id = id;
        this.name = name;
        this.newPerDay = newPerDay;
        this.cards = new ArrayList<>(cards);
        this.byId = new LinkedHashMap<>();
        for (DeckCard card : cards) byId.put(id(card.def), card);
    }

    /** A deck on AUTO, as the bundled deck always was. */
    public Deck(String name, List<DeckCard> cards) {
        this(name, name, AUTO, cards);
    }

    public List<DeckCard> cards() {
        return cards;
    }

    public int size() {
        return cards.size();
    }

    public boolean isAuto() {
        return newPerDay < 0;
    }

    /** Looks a card up by its definition identity, to rejoin FSRS state. */
    public DeckCard byId(String cardId) {
        return byId.get(cardId);
    }

    /** Adds a card that arrived in a sync, keeping the deck in its order. */
    void add(DeckCard card) {
        byId.put(id(card.def), card);
        cards.add(card);
    }

    /** Takes a card out (Menu > Remove from deck). */
    void remove(DeckCard card) {
        byId.remove(id(card.def));
        cards.remove(card);
    }

    void sort() {
        Collections.sort(cards, (a, b) -> Integer.compare(a.ord, b.ord));
    }

    public static String id(CardDef def) {
        return def.identity;
    }
}
