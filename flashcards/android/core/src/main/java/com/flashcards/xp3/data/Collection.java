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
 *
 * A card removed from its deck ({@link #remove}) is gone from here at once,
 * and kept aside only until a sync has taken it to the command center as a
 * tombstone (`deleted`), which every device then drops. A removal wins over
 * any answer, wherever it was made.
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
    /** Cards removed here that the command center hasn't heard of yet, by id. */
    private final Map<String, DeckCard> removed = new LinkedHashMap<>();
    private final Map<String, String> removedFrom = new LinkedHashMap<>();

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
        return !removed.isEmpty();
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

    /**
     * A new deck of imported cards (Menu > Import), added to this copy: new
     * cards, in the file's order, each card's metadata its aux, on Anki's default new cards per day,
     * under `name` or, when a deck has it, "name (2)" and so on. It and its
     * cards are changed as of now, so the next sync takes them to the
     * command center, which keeps cards by id across decks: each card's id
     * is the deck's, which is `deckId`, plus its place.
     */
    public Deck imported(String deckId, String name, List<Fkv.Card> from, long nowMillis) {
        String unique = name;
        for (int n = 2; named(unique); n++) unique = name + " (" + n + ")";
        List<DeckCard> cards = new ArrayList<>();
        for (Fkv.Card card : from) {
            cards.add(new DeckCard(new CardDef(card.key, card.value, card.meta, deckId + ":" + cards.size())));
        }
        Deck deck = new Deck(deckId, unique, Deck.DEFAULT_NEW_PER_DAY, cards);
        int ord = 0;
        for (DeckCard card : deck.cards()) {
            card.ord = ord++;
            card.mod = nowMillis;
            card.dirty = true;
        }
        deck.mod = nowMillis;
        deck.dirty = true;
        add(deck);
        return deck;
    }

    private boolean named(String name) {
        for (Deck deck : decks) {
            if (deck.name.equals(name)) return true;
        }
        return false;
    }

    /**
     * Removes a card from its deck (Menu > Remove from deck): it is gone here
     * at once, and the next sync takes the removal to the command center and
     * from there to the tablet.
     */
    public void remove(DeckCard card, long nowMillis) {
        String id = card.def.identity;
        String deckId = deckOf.remove(id);
        if (deckId == null || cardsById.remove(id) == null) return;
        decksById.get(deckId).remove(card);
        touch(card, nowMillis);
        removed.put(id, card);
        removedFrom.put(id, deckId);
    }

    /** Takes a card out of this copy, as a removal elsewhere does. */
    private void drop(String id) {
        DeckCard card = cardsById.remove(id);
        String deckId = deckOf.remove(id);
        if (card != null && deckId != null) decksById.get(deckId).remove(card);
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
        final Map<String, Long> removedMods = new LinkedHashMap<>();

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
            for (Map.Entry<String, DeckCard> e : from.removed.entrySet()) {
                cards.add(tombstone(e.getValue(), from.removedFrom.get(e.getKey()), false));
                removedMods.put(e.getKey(), e.getValue().mod);
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
        // Merged while the removals are still kept aside, so an answer
        // coming back for a removed card can't bring it back.
        merge(root, false);
        for (Map.Entry<String, Long> e : sent.removedMods.entrySet()) {
            DeckCard card = removed.get(e.getKey());
            if (card != null && card.mod == e.getValue()) {
                removed.remove(e.getKey());
                removedFrom.remove(e.getKey());
            }
        }
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
            if (id == null || removed.containsKey(id)) continue;
            if (Json.flag(c, "deleted")) {
                if (local && Json.flag(c, "dirty")) {
                    // Removed here, not yet sent.
                    Card fsrs = fsrsOf(c, DeckCard.idFor(id));
                    if (fsrs == null || deckId == null) continue;
                    DeckCard card = new DeckCard(new CardDef(text(c, "front"), text(c, "back"), text(c, "aux"), id), fsrs);
                    card.mod = longOf(c, "mod", 0);
                    removed.put(id, card);
                    removedFrom.put(id, deckId);
                } else {
                    drop(id);
                }
                continue;
            }
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
        for (Map.Entry<String, DeckCard> e : removed.entrySet()) {
            cards.add(tombstone(e.getValue(), removedFrom.get(e.getKey()), true));
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

    /** A removed card as a sync sends it, and as the file keeps it until then. */
    private static Map<String, Object> tombstone(DeckCard card, String deckId, boolean local) {
        Map<String, Object> c = cardJson(card, deckId, false);
        c.put("deleted", true);
        if (local) c.put("dirty", true);
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
