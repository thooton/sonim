package com.flashcards.xp3;

import com.flashcards.xp3.algo.Stats;
import com.flashcards.xp3.data.CardDef;
import com.flashcards.xp3.data.DeckCard;

import com.xp3.common.Check;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Statistics screen's figures. The tablet's TestStatsMatchThePhone
 * builds the same six cards and wants the same numbers.
 */
public final class StatsTests {

    /** 2001-09-09 12:00 UTC: the day ends at 04:00 tomorrow, 16 hours on. */
    static final long NOW = 1_000_036_800L;
    static final long H = 3600L, D = 86400L;

    public static void run(Check c) {
        c.run("stats", () -> {
            Stats s = Stats.of(fixture(), NOW, ZoneOffset.UTC);
            c.same("total", 6, s.total);
            c.same("studied today", 2, s.studied);
            c.same("first answered today", 1, s.introduced);
            c.same("counts", "[1, 1, 1, 2, 1]", Arrays.toString(s.counts));
            int[] due = new int[Stats.DUE_DAYS];
            due[0] = 3;
            due[3] = 1;
            due[20] = 1;
            c.same("future due", Arrays.toString(due), Arrays.toString(s.due));
            c.same("due in all", 5, s.dueTotal());
            c.same("intervals", "[0, 0, 1, 1, 1, 0, 0, 0, 0]", Arrays.toString(s.intervals));
            c.close("average interval", (5 + 30 + 9) / 3.0, s.averageInterval, 1e-9);
            c.same("longest interval", 30, s.longestInterval);
            int binned = 0;
            for (int n : s.recall) binned += n;
            c.same("every studied card has a recall", 5, binned);
            c.close("knowledge", 4.7935417018, s.knowledge, 1e-6);
            c.close("average recall", s.knowledge / 5, s.averageRecall, 1e-12);
            c.same("percent", "33.3%", s.percent(2));
        });
        c.run("stats of nothing", () -> {
            Stats s = Stats.of(new ArrayList<DeckCard>(), NOW, ZoneOffset.UTC);
            c.same("no cards", 0, s.total);
            c.same("no percent", "0%", s.percent(0));
            c.close("no average", 0, s.averageRecall, 0);
        });
        c.run("spans", () -> {
            c.same("one day", "1 day", Stats.span(1));
            c.same("days", "13.9 days", Stats.span(13.94));
            c.same("months", "1.5 months", Stats.span(45));
            c.same("years", "2 years", Stats.span(730));
            c.same("decimal", "12.3", Stats.decimal(12.34));
        });
    }

    static List<DeckCard> fixture() {
        List<DeckCard> cards = new ArrayList<>();
        cards.add(card("new", null, 0, 0, 0, 0));
        cards.add(card("learning", State.LEARNING, NOW - 600, NOW + 600, 2.3, NOW - 600));
        cards.add(card("relearning", State.RELEARNING, NOW - H, NOW + 60, 1.2, NOW - 40 * D));
        cards.add(card("young", State.REVIEW, NOW - 2 * D, NOW + 3 * D, 5.1, NOW - 9 * D));
        cards.add(card("mature", State.REVIEW, NOW - 10 * D, NOW + 20 * D, 31.0, NOW - 90 * D));
        cards.add(card("overdue", State.REVIEW, NOW - 10 * D, NOW - D, 8.0, NOW - 30 * D));
        return cards;
    }

    private static DeckCard card(String id, State state, long last, long due, double stability, long intro) {
        CardDef def = new CardDef(id, id, "", "fixture:" + id);
        Card.Builder fsrs = Card.builder().cardId(DeckCard.idFor(def.identity));
        if (state != null) {
            fsrs.state(state)
                .step(state == State.REVIEW ? null : 0)
                .stability(stability)
                .difficulty(5.0)
                .due(Instant.ofEpochSecond(due))
                .lastReview(Instant.ofEpochSecond(last));
        }
        DeckCard card = new DeckCard(def, fsrs.build());
        card.intro = intro;
        return card;
    }
}
