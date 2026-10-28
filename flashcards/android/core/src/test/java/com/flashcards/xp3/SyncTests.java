package com.flashcards.xp3;

import com.flashcards.xp3.algo.ReviewSession;
import com.flashcards.xp3.data.CardDef;
import com.flashcards.xp3.data.Collection;
import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;

import com.xp3.common.Check;
import com.xp3.common.Json;
import io.github.openspacedrepetition.State;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The collection's file and its sync with the command center, and the
 * daily new-card limit a deck can have instead of AUTO.
 */
public final class SyncTests {

    private static final long NOON = 1_700_049_600L;

    public static void run(Check c) {
        fileRoundTrip(c);
        firstSyncSendsEverything(c);
        laterChangeWins(c);
        newCardsArrive(c);
        dailyLimit(c);
        autoHasNoLimit(c);
        reviewAhead(c);
        correctedText(c);
        importedDeck(c);
        removedCard(c);
        copyIsApart(c);
    }

    private static void copyIsApart(Check c) {
        c.run("a copy to save writes what was, whatever changes after", () -> {
            Collection one = bundled(3);
            one.remove(one.decks().get(0).cards().get(2), 5000L);
            Collection copy = one.copy();
            String before = one.write();
            c.same("writes the same", before, copy.write());
            ReviewSession s = new ReviewSession(one.decks().get(0), ZoneOffset.UTC);
            s.next(NOON);
            s.rate(3, NOON);
            one.remove(one.decks().get(0).cards().get(1), 6000L);
            c.same("unchanged by an answer or a removal", before, copy.write());
        });
    }

    private static void removedCard(Check c) {
        c.run("a removed card is gone, and its removal goes out", () -> {
            Collection one = bundled(3);
            one.apply(Json.write(Json.map("usn", 1L)), one.outgoing());
            Deck deck = one.decks().get(0);
            DeckCard gone = deck.cards().get(1);
            one.remove(gone, 9000L);
            c.same("out of the deck", 2, deck.size());
            c.ok("not looked up", deck.byId("id1") == null);
            c.same("not counted", 2, one.cardCount());
            Collection kept = Collection.read(one.write());
            c.same("still gone in the file", 2, kept.decks().get(0).size());
            c.ok("still to send", kept.dirty());
            Collection.Outgoing sent = kept.outgoing();
            List<Object> cards = Json.children(Json.object(Json.parse(sent.body)), "cards");
            c.same("sent once", 1, cards.size());
            Map<String, Object> tomb = Json.object(cards.get(0));
            c.same("as itself", "id1", Json.text(tomb, "id"));
            c.ok("removed", Json.flag(tomb, "deleted"));
            // The command center sends back an answer the tablet made meanwhile.
            kept.apply(Json.write(Json.map("usn", 2L, "decks", Json.list(),
                "cards", Json.list(Json.object(Json.parse(cardJson("id1", 2, 99_000L)))))), sent);
            c.same("the removal wins", 2, kept.decks().get(0).size());
            c.ok("sent", !kept.dirty());

            Map<String, Object> elsewhere = Json.object(Json.parse(cardJson("id0", 1, 0L)));
            elsewhere.put("deleted", true);
            Deck mine = kept.decks().get(0);
            ReviewSession s = new ReviewSession(mine, ZoneOffset.UTC);
            s.next(NOON);
            s.rate(3, NOON);
            kept.apply(Json.write(Json.map("usn", 3L, "decks", Json.list(), "cards", Json.list(elsewhere))), kept.outgoing());
            c.same("removed elsewhere, though answered here", 1, mine.size());
            c.same("what is left", "id2", mine.cards().get(0).def.identity);
        });
    }

    private static void importedDeck(Check c) {
        c.run("an imported deck goes out at the next sync", () -> {
            Collection one = bundled(2);
            one.apply(Json.write(Json.map("usn", 1L)), one.outgoing());
            List<com.flashcards.xp3.data.Fkv.Card> cards = com.flashcards.xp3.data.Fkv.parse(
                "uno||one\ndos||two||Dos gatos.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Deck deck = one.imported("fkv-1", "Numbers", cards, 5000L);
            Deck again = one.imported("fkv-2", "Numbers", cards, 6000L);
            c.same("a name taken is numbered", "Numbers (2)", again.name);
            c.same("new cards a day", Deck.DEFAULT_NEW_PER_DAY, deck.newPerDay);
            c.same("in order", "dos", deck.cards().get(1).def.key);
            c.same("metadata is aux", "Dos gatos.", deck.cards().get(1).def.aux);
            c.same("none is empty", "", deck.cards().get(0).def.aux);
            c.same("ids are the deck's", "fkv-1:1", deck.cards().get(1).def.identity);
            c.ok("new", deck.cards().get(0).isNew());
            Map<String, Object> out = Json.object(Json.parse(one.outgoing().body));
            c.same("both decks sent", 2, Json.children(out, "decks").size());
            c.same("every card sent", 4, Json.children(out, "cards").size());
            Collection back = Collection.read(one.write());
            c.same("kept in the file", 3, back.decks().size());
            c.same("its cards too", 2, back.deck("fkv-1").size());
            c.ok("still to send", back.dirty());
        });
    }

    private static void correctedText(Check c) {
        c.run("a card's corrected text arrives", () -> {
            Collection one = bundled(2);
            one.apply(Json.write(Json.map("usn", 1L)), one.outgoing());
            Map<String, Object> fixed = Json.object(Json.parse(cardJson("id0", 1, 0L)));
            fixed.put("deck", "spanish");
            fixed.put("front", "el perro");
            one.apply(Json.write(Json.map("usn", 2L, "decks", Json.list(), "cards", Json.list(fixed))), one.outgoing());
            c.same("new text", "el perro", one.decks().get(0).cards().get(0).def.key);
            c.same("same identity", "id0", one.decks().get(0).cards().get(0).def.identity);
        });
    }

    private static void reviewAhead(Check c) {
        c.run("Review Ahead goes on once nothing is due", () -> {
            Deck deck = bundled(3).decks().get(0);
            deck.newPerDay = 0;
            ReviewSession s = new ReviewSession(deck, ZoneOffset.UTC);
            // Two studied cards, due in 10 and 40 days; the second was studied
            // longer ago and is likelier forgotten.
            DeckCard soon = deck.cards().get(0);
            DeckCard later = deck.cards().get(1);
            soon.fsrs = reviewed(soon, 30.0, NOON - 20L * 86400L, NOON + 10L * 86400L);
            later.fsrs = reviewed(later, 5.0, NOON - 2L * 86400L, NOON + 40L * 86400L);
            c.ok("nothing due", s.next(NOON) == null);
            s.setAhead(true);
            DeckCard first = s.next(NOON);
            c.ok("the likelier forgotten first", first == soon || first == later);
            c.ok("lowest recall first", s.recall(first.fsrs, NOON) <= s.recall((first == soon ? later : soon).fsrs, NOON));
            s.rate(3, NOON);
            DeckCard second = s.next(NOON + 10);
            c.ok("not the same card twice running", second != first && second != null);
            c.ok("never a new card", !second.isNew());
            s.setAhead(false);
            c.ok("off again: nothing", s.next(NOON + 20) == null);
        });
    }

    private static io.github.openspacedrepetition.Card reviewed(DeckCard card, double stability, long last, long due) {
        return io.github.openspacedrepetition.Card.builder()
            .cardId(DeckCard.idFor(card.def.identity))
            .state(State.REVIEW).step(null).stability(stability).difficulty(5.0)
            .due(java.time.Instant.ofEpochSecond(due))
            .lastReview(java.time.Instant.ofEpochSecond(last))
            .build();
    }

    private static Collection bundled(int n) {
        List<DeckCard> cards = new ArrayList<>();
        for (int i = 0; i < n; i++) cards.add(new DeckCard(new CardDef("key" + i, "value" + i, "aux" + i, "id" + i)));
        Collection collection = new Collection();
        collection.add(Collection.bundled("spanish", "Spanish", cards));
        return collection;
    }

    private static void fileRoundTrip(Check c) {
        c.run("the file round trip", () -> {
            Collection one = bundled(3);
            Deck deck = one.decks().get(0);
            ReviewSession s = new ReviewSession(deck, ZoneOffset.UTC);
            s.next(NOON);
            s.rate(3, NOON);
            deck.newPerDay = 7;
            one.usn = 12;
            Collection two = Collection.read(one.write());
            Deck back = two.decks().get(0);
            c.same("usn", 12L, two.usn);
            c.same("setting", 7, back.newPerDay);
            c.same("cards", 3, back.size());
            c.same("order", "id1", back.cards().get(1).def.identity);
            c.same("aux", "aux2", back.cards().get(2).def.aux);
            DeckCard answered = back.cards().get(0);
            c.ok("answered", !answered.isNew());
            c.same("intro", NOON, answered.intro);
            c.same("due", deck.cards().get(0).fsrs.getDue(), answered.fsrs.getDue());
            c.ok("still dirty", two.dirty());
        });
    }

    private static void firstSyncSendsEverything(Check c) {
        c.run("the first sync sends every card", () -> {
            Collection one = bundled(4);
            Collection.Outgoing out = one.outgoing();
            Map<String, Object> body = Json.object(Json.parse(out.body));
            c.same("decks", 1, Json.children(body, "decks").size());
            c.same("cards", 4, Json.children(body, "cards").size());
            // The command center keeps them and answers with all of it.
            one.apply(Json.write(Json.map("usn", 1L, "decks", Json.children(body, "decks"), "cards", Json.children(body, "cards"))), out);
            c.ok("clean after", !one.dirty());
            c.same("usn", 1L, one.usn);
        });
    }

    private static void laterChangeWins(Check c) {
        c.run("the later change wins", () -> {
            Collection one = bundled(2);
            one.apply(Json.write(Json.map("usn", 1L)), one.outgoing());
            Deck deck = one.decks().get(0);
            DeckCard card = deck.cards().get(0);
            ReviewSession s = new ReviewSession(deck, ZoneOffset.UTC);
            s.next(NOON);
            s.rate(3, NOON);
            Collection.Outgoing out = one.outgoing();
            // Elsewhere, an older review of the same card, and a newer one of the other.
            Map<String, Object> older = Json.object(Json.parse(cardJson("id0", 2, (NOON - 60) * 1000L)));
            Map<String, Object> newer = Json.object(Json.parse(cardJson("id1", 2, (NOON + 60) * 1000L)));
            String reply = Json.write(Json.map("usn", 5L, "decks", Json.list(), "cards", Json.list(older, newer)));
            // Answered again before the reply came: that stays dirty.
            Collection.touch(card, NOON * 1000L + 5000);
            one.apply(reply, out);
            c.same("ours kept", State.LEARNING, card.fsrs.getState());
            c.ok("ours still to send", card.dirty);
            c.same("theirs taken", State.REVIEW, deck.cards().get(1).fsrs.getState());
            c.ok("theirs clean", !deck.cards().get(1).dirty);
            c.same("usn", 5L, one.usn);
        });
    }

    private static void newCardsArrive(Check c) {
        c.run("cards made elsewhere arrive in order", () -> {
            Collection one = new Collection();
            String reply = Json.write(Json.map("usn", 3L,
                "decks", Json.list(Json.map("id", "d", "name", "French", "new_per_day", 10L, "mod", 1L)),
                "cards", Json.list(
                    Json.object(Json.parse(cardJson("b", 1, 1L).replace("\"ord\":0", "\"ord\":2"))),
                    Json.object(Json.parse(cardJson("a", 1, 1L))))));
            one.apply(reply, one.outgoing());
            Deck deck = one.deck("d");
            c.same("deck", "French", deck.name);
            c.same("limit", 10, deck.newPerDay);
            c.same("first in order", "a", deck.cards().get(0).def.identity);
            c.ok("clean", !one.dirty());
        });
    }

    private static String cardJson(String id, int state, long mod) {
        String fsrs = state == 2
            ? "\"state\":2,\"step\":null,\"stability\":20.0,\"difficulty\":5.0,\"due\":" + (NOON + 20 * 86400L) + ",\"last\":" + (NOON - 60)
            : "\"state\":1,\"step\":0,\"stability\":null,\"difficulty\":null,\"due\":" + NOON + ",\"last\":0";
        String deck = id.length() == 1 ? "d" : "spanish";
        return "{\"id\":\"" + id + "\",\"deck\":\"" + deck + "\",\"ord\":0,\"front\":\"f\",\"back\":\"b\",\"aux\":\"\","
            + fsrs + ",\"intro\":0,\"mod\":" + mod + "}";
    }

    private static void dailyLimit(Check c) {
        c.run("a number limits new cards a day", () -> {
            Deck deck = bundled(10).decks().get(0);
            deck.newPerDay = 3;
            ReviewSession s = new ReviewSession(deck, ZoneOffset.UTC);
            c.same("three on offer", 3, s.counts(NOON)[0]);
            int introduced = 0;
            long now = NOON;
            DeckCard card;
            while ((card = s.next(now)) != null) {
                if (card.isNew()) introduced++;
                s.rate(4, now);
                now += 10;
            }
            c.same("three came in", 3, introduced);
            c.same("none left today", 0, s.counts(now)[0]);
            c.same("three more tomorrow", 3, s.counts(NOON + 86400L)[0]);
            deck.newPerDay = 0;
            c.ok("zero means none", s.next(NOON + 86400L) == null || !s.next(NOON + 86400L).isNew());
        });
    }

    private static void autoHasNoLimit(Check c) {
        c.run("AUTO has no daily number", () -> {
            Deck deck = bundled(25).decks().get(0);
            c.ok("bundled deck is AUTO", deck.isAuto());
            ReviewSession s = new ReviewSession(deck, ZoneOffset.UTC);
            c.same("the target's worth on offer", ReviewSession.AUTO_TARGET, s.counts(NOON)[0]);
        });
    }
}
