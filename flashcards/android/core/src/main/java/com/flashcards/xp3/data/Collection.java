package com.flashcards.xp3.data;

import com.xp3.common.Json;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every deck and card, as this device has them: the copy of the command
 * center's collection the app studies from, offline or not.
 *
 * It is kept as JSON in the app's private directory ({@link #write},
 * {@link #read}), in the shape the command center's /flashcards/sync speaks,
 * plus a `dirty` flag on whatever changed here since the last sync. A sync
 * sends the dirty records ({@link #outgoing}) with the `usn` last seen, and
 * takes back what changed there ({@link #apply}): where both sides changed a
 * record, the later `mod` wins, as on the command center.
 */
public final class Collection {

    /** The command center's sequence number this copy last caught up to. */
    public long usn;
    /**
     * This phone's time zone, sent with every sync: the tablet keeps UTC and
     * takes it from the command center, so the day rolls over at 4am here on both.
     */
    public String zone;
    private final List<Deck> decks = new ArrayList<>();
    private final Map<String, Deck> decksById = new LinkedHashMap<>();
    private final Map<String, DeckCard> cardsById = new LinkedHashMap<>();
    private final Map<String, String> deckOf = new LinkedHashMap<>();

    public List<Deck> decks() {
        return decks;
    }

    public Deck deck(String id) {
        return decksById.get(id);
    }

    public int cardCount() {
        return cardsById.size();
    }

    /** Whether anything changed here that the command center hasn't got. */
    public boolean dirty() {
        for (Deck deck : decks) {
            if (deck.dirty) return true;
        }
        for (DeckCard card : cardsById.values()) {
            if (card.dirty) return true;
        }
        return false;
    }

    /** Adds a deck, with its cards, that this device brings (the bundled one). */
    public void add(Deck deck) {
        decks.add(deck);
        decksById.put(deck.id, deck);
        for (DeckCard card : deck.cards()) {
            cardsById.put(card.def.identity, card);
            deckOf.put(card.def.identity, deck.id);
        }
    }

    /**
     * A deck this device brings, as the bundled Spanish deck was before
     * the command center kept the decks: every card in its order, each
     * changed as of its last review, so the command center takes it unless
     * it has a later review of that card. The deck itself goes as of 0, so
     * a setting already changed elsewhere stays.
     */
    public static Deck bundled(String id, String name, List<DeckCard> cards) {
        Deck deck = new Deck(id, name, Deck.AUTO, cards);
        int ord = 0;
        for (DeckCard card : deck.cards()) {
            card.ord = ord++;
            card.mod = card.isNew() ? 0 : card.fsrs.getLastReview().toEpochMilli();
            card.dirty = true;
        }
        deck.dirty = true;
        return deck;
    }

    /** Marks a deck's setting changed, to be sent at the next sync. */
    public static void touch(Deck deck, long nowMillis) {
        deck.mod = Math.max(nowMillis, deck.mod + 1);
        deck.dirty = true;
    }

    /** Marks a card changed (it was answered), to be sent at the next sync. */
    public static void touch(DeckCard card, long nowMillis) {
        card.mod = Math.max(nowMillis, card.mod + 1);
        card.dirty = true;
    }

    // ---------------------------------------------------------------------
    // Sync

    /** What one sync sends, and the mods it sent, to tell later changes apart. */
    public static final class Outgoing {
        public final String body;
        final Map<String, Long> deckMods = new LinkedHashMap<>();
        final Map<String, Long> cardMods = new LinkedHashMap<>();

        Outgoing(Collection from) {
            List<Object> decks = new ArrayList<>();
            List<Object> cards = new ArrayList<>();
            for (Deck deck : from.decks) {
                if (!deck.dirty) continue;
                decks.add(deckJson(deck, false));
                deckMods.put(deck.id, deck.mod);
            }
            for (Map.Entry<String, DeckCard> e : from.cardsById.entrySet()) {
                DeckCard card = e.getValue();
                if (!card.dirty) continue;
                cards.add(cardJson(card, from.deckOf.get(e.getKey()), false));
                cardMods.put(card.def.identity, card.mod);
            }
            Map<String, Object> body = Json.map("usn", from.usn, "decks", decks, "cards", cards);
            if (from.zone != null) body.put("zone", from.zone);
            this.body = Json.write(body);
        }
    }

    /** The body of a sync: everything changed here, and the usn last seen. */
    public Outgoing outgoing() {
        return new Outgoing(this);
    }

    /**
     * Takes the command center's answer to `sent`: what it sent is clean
     * unless it changed again meanwhile, and every record it sends back
     * replaces this copy's, unless this copy changed it later.
     */
    public void apply(String response, Outgoing sent) {
        Map<String, Object> root = Json.object(Json.parse(response));
        for (Map.Entry<String, Long> e : sent.deckMods.entrySet()) {
            Deck deck = decksById.get(e.getKey());
            if (deck != null && deck.mod == e.getValue()) deck.dirty = false;
        }
        for (Map.Entry<String, Long> e : sent.cardMods.entrySet()) {
            DeckCard card = cardsById.get(e.getKey());
            if (card != null && card.mod == e.getValue()) card.dirty = false;
        }
        merge(root, false);
        usn = longOf(root, "usn", usn);
    }

    /**
     * Merges decks and cards in the sync shape. Reading this copy's own file
     * takes the dirty flags as written; a sync's records are clean.
     */
    private void merge(Map<String, Object> root, boolean local) {
        for (Object item : Json.children(root, "decks")) {
            Map<String, Object> d = Json.object(item);
            String id = Json.text(d, "id");
            String name = Json.text(d, "name");
            if (id == null || id.isEmpty() || name == null) continue;
            long mod = longOf(d, "mod", 0);
            int newPerDay = (int) Math.max(Deck.AUTO, longOf(d, "new_per_day", Deck.AUTO));
            Deck deck = decksById.get(id);
            if (deck == null) {
                deck = new Deck(id, name, newPerDay, new ArrayList<DeckCard>());
                decks.add(deck);
                decksById.put(id, deck);
            } else if (!local && deck.dirty && deck.mod > mod) {
                continue;
            }
            deck.name = name;
            deck.newPerDay = newPerDay;
            deck.mod = mod;
            deck.dirty = local && Json.flag(d, "dirty");
        }
        List<Deck> touched = new ArrayList<>();
        for (Object item : Json.children(root, "cards")) {
            Map<String, Object> c = Json.object(item);
            String id = Json.text(c, "id");
            String deckId = Json.text(c, "deck");
            Deck deck = deckId == null ? null : decksById.get(deckId);
            Card fsrs = id == null ? null : fsrsOf(c, DeckCard.idFor(id));
            if (deck == null || fsrs == null) continue;
            long mod = longOf(c, "mod", 0);
            DeckCard card = cardsById.get(id);
            if (card == null) {
                card = new DeckCard(new CardDef(text(c, "front"), text(c, "back"), text(c, "aux"), id), fsrs);
                card.ord = (int) longOf(c, "ord", 0);
                deck.add(card);
                cardsById.put(id, card);
                deckOf.put(id, deckId);
                if (!touched.contains(deck)) touched.add(deck);
            } else if (!local && card.dirty && card.mod > mod) {
                continue;
            } else {
                card.fsrs = fsrs;
                // Text corrected on the command center.
                CardDef def = new CardDef(text(c, "front"), text(c, "back"), text(c, "aux"), id);
                if (!local && !(def.key.equals(card.def.key) && def.value.equals(card.def.value) && def.aux.equals(card.def.aux))) {
                    card.def = def;
                }
            }
            card.intro = longOf(c, "intro", 0);
            card.mod = mod;
            card.dirty = local && Json.flag(c, "dirty");
        }
        for (Deck deck : touched) deck.sort();
    }

    // ---------------------------------------------------------------------
    // The file

    /** This copy as JSON, dirty flags included. */
    public String write() {
        List<Object> decks = new ArrayList<>();
        List<Object> cards = new ArrayList<>();
        for (Deck deck : this.decks) {
            decks.add(deckJson(deck, true));
            for (DeckCard card : deck.cards()) cards.add(cardJson(card, deck.id, true));
        }
        return Json.write(Json.map("usn", usn, "decks", decks, "cards", cards));
    }

    /** Reads what {@link #write} wrote; throws IllegalArgumentException if it can't. */
    public static Collection read(String json) {
        Collection collection = new Collection();
        Map<String, Object> root = Json.object(Json.parse(json));
        collection.usn = longOf(root, "usn", 0);
        collection.merge(root, true);
        return collection;
    }

    private static Map<String, Object> deckJson(Deck deck, boolean local) {
        Map<String, Object> d = Json.map(
            "id", deck.id,
            "name", deck.name,
            "new_per_day", (long) deck.newPerDay,
            "mod", deck.mod);
        if (local && deck.dirty) d.put("dirty", true);
        return d;
    }

    private static Map<String, Object> cardJson(DeckCard card, String deckId, boolean local) {
        Card f = card.fsrs;
        Map<String, Object> c = Json.map(
            "id", card.def.identity,
            "deck", deckId,
            "ord", (long) card.ord,
            "front", card.def.key,
            "back", card.def.value,
            "aux", card.def.aux,
            "state", (long) f.getState().getValue(),
            "step", f.getStep() == null ? null : (Object) (long) f.getStep(),
            "stability", f.getStability(),
            "difficulty", f.getDifficulty(),
            "due", f.getDue().getEpochSecond(),
            "last", f.getLastReview() == null ? 0L : f.getLastReview().getEpochSecond(),
            "intro", card.intro,
            "mod", card.mod);
        if (local && card.dirty) c.put("dirty", true);
        return c;
    }

    /** A card's FSRS state, or null when it isn't one java-fsrs could hold. */
    static Card fsrsOf(Map<String, Object> c, int cardId) {
        State state;
        try {
            state = State.fromValue((int) longOf(c, "state", 0));
        } catch (IllegalArgumentException e) {
            return null;
        }
        Double rawStep = Json.number(c, "step");
        Integer step = rawStep == null ? null : (Integer) rawStep.intValue();
        Double stability = Json.number(c, "stability");
        Double difficulty = Json.number(c, "difficulty");
        long last = longOf(c, "last", 0);
        if (state == State.REVIEW) {
            if (step != null) return null;
        } else if (step == null || step < 0) {
            return null;
        }
        if (last == 0) {
            if (state != State.LEARNING || stability != null || difficulty != null) return null;
        } else if (stability == null || !(stability > 0) || Double.isInfinite(stability)
            || difficulty == null || !(difficulty >= 1.0 && difficulty <= 10.0)) {
            return null;
        }
        return Card.builder()
            .cardId(cardId)
            .state(state)
            .step(step)
            .stability(stability)
            .difficulty(difficulty)
            .due(Instant.ofEpochSecond(longOf(c, "due", 0)))
            .lastReview(last == 0 ? null : Instant.ofEpochSecond(last))
            .build();
    }

    private static String text(Map<String, Object> source, String key) {
        String value = Json.text(source, key);
        return value == null ? "" : value;
    }

    private static long longOf(Map<String, Object> source, String key, long otherwise) {
        Object value = source.get(key);
        return value instanceof Number ? ((Number) value).longValue() : otherwise;
    }
}
