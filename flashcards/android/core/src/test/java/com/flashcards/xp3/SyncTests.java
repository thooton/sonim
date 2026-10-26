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
            c.ok("lowest recall first", first.probability(NOON) <= (first == soon ? later : soon).probability(NOON));
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
            c.same("all on offer", 25, s.counts(NOON)[0]);
        });
    }
}
