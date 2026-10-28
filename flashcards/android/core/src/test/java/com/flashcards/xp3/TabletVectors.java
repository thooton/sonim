package com.flashcards.xp3;

import com.flashcards.xp3.algo.ReviewSession;
import com.flashcards.xp3.data.CardDef;
import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Writes the tablet's testdata/java-fsrs.json: cards walked through random
 * answers by this scheduler (ReviewSession.preview), at all hours of a day
 * in the phone's time zone, early, on time and late, so the tablet's port is
 * checked against every path, the 4am rollover included. Run it with the
 * core's test classes on the classpath and redirect its output there.
 */
public final class TabletVectors {

    public static void main(String[] args) {
        String zone = "America/New_York";
        Random rng = new Random(20261028L);
        List<DeckCard> cards = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            cards.add(new DeckCard(new CardDef("k" + i, "v" + i, "", "anki:" + (1737562244867L + i * 7919L))));
        }
        ReviewSession session = new ReviewSession(new Deck("vectors", cards), ZoneId.of(zone));
        StringBuilder out = new StringBuilder("[\n");
        int n = 0;
        for (DeckCard card : cards) {
            String id = card.def.identity;
            long now = 1_700_000_000L + rng.nextInt(86400);
            Card fsrs = Card.builder().cardId(card.fsrs.getCardId())
                .due(Instant.ofEpochSecond(1_700_000_000L)).build();
            for (int i = 0; i < 25; i++) {
                int rating = pick(rng);
                Card next = session.preview(fsrs, rating, now);
                out.append(n++ == 0 ? "" : ",\n")
                    .append("{\"id\":\"").append(id).append("\",\"zone\":\"").append(zone)
                    .append("\",\"now\":").append(now).append(",\"rating\":").append(rating)
                    .append(",\"in\":").append(json(fsrs)).append(",\"out\":").append(json(next))
                    .append('}');
                fsrs = next;
                now = when(rng, fsrs, now);
            }
        }
        System.out.println(out.append("\n]"));
    }

    /** Good most often, as people answer. */
    private static int pick(Random rng) {
        int r = rng.nextInt(10);
        return r < 2 ? 1 : r < 3 ? 2 : r < 9 ? 3 : 4;
    }

    /** When the card is next answered: on time, early, late, or at another hour. */
    private static long when(Random rng, Card card, long now) {
        long due = card.getDue().getEpochSecond();
        switch (rng.nextInt(6)) {
            case 0: return now + 60 + rng.nextInt(3600);
            case 1: return due + rng.nextInt(86400) - 43200 > now ? due + rng.nextInt(86400) - 43200 : due;
            case 2: return due + rng.nextInt(20) * 86400L + rng.nextInt(86400);
            default: return Math.max(now + 1, due + rng.nextInt(600));
        }
    }

    private static String json(Card c) {
        return "{\"state\":" + c.getState().getValue()
            + ",\"step\":" + c.getStep()
            + ",\"stability\":" + c.getStability()
            + ",\"difficulty\":" + c.getDifficulty()
            + ",\"due\":" + c.getDue().getEpochSecond()
            + ",\"last\":" + (c.getLastReview() == null ? 0 : c.getLastReview().getEpochSecond())
            + "}";
    }
}
