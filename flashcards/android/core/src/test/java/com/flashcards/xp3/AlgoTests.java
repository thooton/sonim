package com.flashcards.xp3;

import com.flashcards.xp3.algo.Fsrs;
import com.flashcards.xp3.algo.ReviewSession;
import com.flashcards.xp3.data.CardDef;
import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;

import com.xp3.common.Check;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The algorithm half of the app, checked on a desktop JVM: the scheduler
 * ({@link Fsrs}, Anki with the FSRS4Anki v6.1.1 scheduler), pinned to numbers
 * the script itself gives, and the Anki-style review queue built on top.
 */
public final class AlgoTests {

    public static void run(Check c) {
        newCards(c);
        learningSteps(c);
        hardInLearning(c);
        learningIsShortTermOvernight(c);
        stepPastTheRollover(c);
        reviewsCountAnkiDays(c);
        sameDayReview(c);
        buttonsInOrder(c);
        fuzz(c);
        scriptVectors(c);
        lapses(c);
        rounding(c);
        autoTakesNewCardsAsTheyAreLearned(c);
        autoTopsUpToTheTarget(c);
        newCardsMixIntoReviews(c);
        numberedDeckLimitsNew(c);
        dueReviewsOnly(c);
        learningComesBackWhenDue(c);
        previewMatchesRating(c);
    }

    /* --- the scheduler --- */

    private static final long DAY = 86400L;

    private static void newCards(Check c) {
        c.run("new cards start from the script's initial state", () -> {
            Card fresh = Card.builder().cardId(2).build();
            Card again = Fsrs.review(fresh, 1, T0, 0, 10 * 3600L);
            c.close("again stability", 0.21, again.getStability(), 0);
            c.close("again difficulty", 6.41, again.getDifficulty(), 0);
            Card good = Fsrs.review(fresh, 3, T0, 0, 10 * 3600L);
            c.close("good stability", 2.31, good.getStability(), 0);
            c.close("good difficulty", 2.12, good.getDifficulty(), 0);
            c.same("good stays learning", State.LEARNING, good.getState());
            c.same("good moves to the second step", 1, good.getStep());
            Card easy = Fsrs.review(fresh, 4, T0, 0, 10 * 3600L);
            c.close("easy stability", 8.3, easy.getStability(), 0);
            c.close("easy difficulty", 1.0, easy.getDifficulty(), 0);
            c.same("easy graduates", State.REVIEW, easy.getState());
            long days = (easy.getDue().getEpochSecond() - T0) / DAY;
            c.ok("easy waits 7 to 10 days: " + days, days >= 7 && days <= 10);
            c.fails("rejects invalid rating", "between 1 and 4",
                () -> Fsrs.review(fresh, 5, T0, 0, 10 * 3600L));
        });
    }

    private static void learningSteps(Check c) {
        c.run("Anki's steps, up to 25% (at most 5 minutes) later", () -> {
            for (int id = 0; id < 200; id++) {
                Card fresh = Card.builder().cardId(id).build();
                long again = Fsrs.review(fresh, 1, T0, 0, 10 * 3600L).getDue().getEpochSecond() - T0;
                long hard = Fsrs.review(fresh, 2, T0, 0, 10 * 3600L).getDue().getEpochSecond() - T0;
                Card good = Fsrs.review(fresh, 3, T0, 0, 10 * 3600L);
                long ten = good.getDue().getEpochSecond() - T0;
                if (again < 60 || again >= 75 || hard < 330 || hard >= 412 || ten < 600 || ten >= 750) {
                    c.ok("card " + id + ": again " + again + " hard " + hard + " good " + ten, false);
                    return;
                }
                Card second = Fsrs.review(good, 2, T0 + ten, 0, 10 * 3600L);
                long repeat = second.getDue().getEpochSecond() - T0 - ten;
                c.ok("hard on the second step repeats it", repeat >= 600 && repeat < 750 && second.getStep() == 1);
            }
            Card relearning = Card.builder().cardId(9).state(State.RELEARNING).step(0)
                .stability(3.0).difficulty(5.0).due(Instant.ofEpochSecond(T0))
                .lastReview(Instant.ofEpochSecond(T0 - 600)).build();
            long hard = Fsrs.review(relearning, 2, T0, 0, 10 * 3600L).getDue().getEpochSecond() - T0;
            c.ok("hard on the lone relearning step waits half again: " + hard, hard >= 900 && hard < 1125);
            Card good = Fsrs.review(relearning, 3, T0, 0, 10 * 3600L);
            c.same("good after the lone relearning step graduates", State.REVIEW, good.getState());
        });
    }

    private static void hardInLearning(Check c) {
        c.run("Hard in learning lowers stability, as the script's does", () -> {
            Card fresh = Card.builder().cardId(3).build();
            Card good = Fsrs.review(fresh, 3, T0, 0, 10 * 3600L);
            Card hard = Fsrs.review(good, 2, T0 + 600, 0, 10 * 3600L);
            c.close("hard: 2.31 -> 1.34", 1.34, hard.getStability(), 0);
            Card graduated = Fsrs.review(hard, 3, T0 + 1200, 0, 10 * 3600L);
            c.close("good: short-term growth", 1.38, graduated.getStability(), 0);
            c.same("graduates", State.REVIEW, graduated.getState());
            c.same("in a day", T0 + 1200 + DAY, graduated.getDue().getEpochSecond());
        });
    }

    private static void learningIsShortTermOvernight(Check c) {
        c.run("learning stays short-term even days later", () -> {
            Card learning = Card.builder().cardId(4).state(State.LEARNING).step(1)
                .stability(2.31).difficulty(5.0).due(Instant.ofEpochSecond(T0 + 600))
                .lastReview(Instant.ofEpochSecond(T0)).build();
            Card good = Fsrs.review(learning, 3, T0 + 3 * DAY, 3, 10 * 3600L);
            c.close("good keeps 2.31", 2.31, good.getStability(), 0);
            c.same("graduates at 2 days", T0 + 5 * DAY, good.getDue().getEpochSecond());
        });
    }

    private static void stepPastTheRollover(Check c) {
        c.run("a step past the rollover waits for the next day", () -> {
            Card fresh = Card.builder().cardId(5).build();
            Card good = Fsrs.review(fresh, 3, T0, 0, 300);
            c.same("due as the day starts", T0 + 300, good.getDue().getEpochSecond());
            Card again = Fsrs.review(fresh, 1, T0, 0, 300);
            long wait = again.getDue().getEpochSecond() - T0;
            c.ok("a minute still fits: " + wait, wait >= 60 && wait < 75);
        });
    }

    private static void reviewsCountAnkiDays(Check c) {
        c.run("a review counts Anki days, not 24 hours", () -> {
            // answered at 23:00 UTC, a day's interval; studied again at 09:00
            long last = NOON + 11 * 3600L;
            Deck deck = deckOf(1);
            DeckCard card = deck.cards().get(0);
            card.fsrs = Card.builder().cardId(6).state(State.REVIEW).stability(1.0).difficulty(5.0)
                .due(Instant.ofEpochSecond(last + DAY)).lastReview(Instant.ofEpochSecond(last)).build();
            ReviewSession s = session(deck);
            long morning = last + 10 * 3600L;
            Card good = s.preview(card.fsrs, 3, morning);
            c.close("a day has passed: 1 -> 4.23", 4.23, good.getStability(), 0);
            long days = (good.getDue().getEpochSecond() - morning) / DAY;
            c.ok("3 to 5 days: " + days, days >= 3 && days <= 5);
            Card sameDay = s.preview(card.fsrs, 3, last + 3600L);
            c.close("before the rollover, no time has passed", 1.0, sameDay.getStability(), 0);
            c.ok("recall then is 1", s.recall(card.fsrs, last + 3600L) == 1.0);
            c.close("recall the next morning is 0.9", 0.9, s.recall(card.fsrs, morning), 1e-9);
        });
    }

    private static void sameDayReview(Check c) {
        c.run("a review card answered the same day counts recall as 1", () -> {
            Card card = reviewed(10.0, 5.0, T0);
            Card good = Fsrs.review(card, 3, T0 + 3600, 0, 10 * 3600L);
            c.close("good leaves stability", 10.0, good.getStability(), 0);
            Card again = Fsrs.review(card, 1, T0 + 3600, 0, 10 * 3600L);
            c.close("again: the lapse formula at recall 1", Fsrs.nextForgetStability(5.0, 10.0, 1.0),
                again.getStability(), 0);
            c.close("which is 1.18", 1.18, again.getStability(), 0);
        });
    }

    private static void buttonsInOrder(Check c) {
        c.run("Hard <= Good < Easy, Good never below the last interval", () -> {
            for (int id = 0; id < 300; id++) {
                Card card = Card.builder().cardId(id).state(State.REVIEW).stability(0.5)
                    .difficulty(9.0).due(Instant.ofEpochSecond(T0 + DAY))
                    .lastReview(Instant.ofEpochSecond(T0)).build();
                long now = T0 + DAY;
                long hard = days(Fsrs.review(card, 2, now, 1, 10 * 3600L), now);
                long good = days(Fsrs.review(card, 3, now, 1, 10 * 3600L), now);
                long easy = days(Fsrs.review(card, 4, now, 1, 10 * 3600L), now);
                if (!(hard >= 1 && good > hard && easy > good)) {
                    c.ok("card " + id + ": " + hard + " " + good + " " + easy, false);
                    return;
                }
            }
            c.ok("in order", true);
        });
    }

    private static void fuzz(Check c) {
        c.run("fuzz: 5% and a day either side", () -> {
            int lo = Integer.MAX_VALUE;
            int hi = 0;
            for (int id = 0; id < 400; id++) {
                // stability 40, answered on time: Good alone would be 120ish days
                Card card = Card.builder().cardId(id).state(State.REVIEW).stability(100.0)
                    .difficulty(5.0).due(Instant.ofEpochSecond(T0 + 100 * DAY))
                    .lastReview(Instant.ofEpochSecond(T0)).build();
                long now = T0 + 100 * DAY;
                int raw = Fsrs.nextInterval(Fsrs.nextRecallStability(5.0, 100.0,
                    Fsrs.forgettingCurve(100, 100.0), 3), 0.5, -1);
                int got = (int) days(Fsrs.review(card, 3, now, 100, 10 * 3600L), now);
                lo = Math.min(lo, got - raw);
                hi = Math.max(hi, got - raw);
            }
            c.ok("spread " + lo + ".." + hi, lo >= -Math.round(0.05 * 300 + 1) && hi <= Math.round(0.05 * 300 + 1) && hi > lo);
            for (int id = 0; id < 200; id++) {
                // Good, a day late on a 10-day card, may not drop to 10 or under
                Card card = Card.builder().cardId(id).state(State.REVIEW).stability(4.0)
                    .difficulty(8.0).due(Instant.ofEpochSecond(T0 + 10 * DAY))
                    .lastReview(Instant.ofEpochSecond(T0)).build();
                long now = T0 + 11 * DAY;
                long good = days(Fsrs.review(card, 3, now, 11, 10 * 3600L), now);
                if (good <= 10) {
                    c.ok("card " + id + " grew to only " + good, false);
                    return;
                }
            }
            c.ok("grown intervals clear the last one", true);
        });
    }

    /**
     * Answers fsrs4anki_scheduler.js v6.1.1 gave, run in Node on these
     * cards (state, stability, difficulty, Anki days elapsed, interval and
     * rating in; stability and difficulty out).
     */
    private static void scriptVectors(Check c) {
        c.run("the script's own answers", () -> {
            double[][] v = {
                // state, s, d, elapsed, scheduled, rating -> s, d (state 2 review, 1 learning)
                {2, 39.21, 6.86, 37, 33, 1, 2.53, 8.96},
                {2, 39.21, 6.86, 37, 33, 2, 66.59, 7.91},
                {2, 39.21, 6.86, 37, 33, 3, 84.73, 6.85},
                {2, 39.21, 6.86, 37, 33, 4, 124.47, 5.8},
                {2, 3.5, 2, 12, 4, 1, 0.96, 7.36},
                {2, 3.5, 2, 12, 4, 2, 21.05, 4.68},
                {2, 3.5, 2, 12, 4, 3, 32.68, 2},
                {2, 3.5, 2, 12, 4, 4, 58.15, 1},
                {2, 250, 9.5, 120, 200, 1, 4.65, 9.83},
                {2, 250, 9.5, 120, 200, 2, 277.49, 9.66},
                {2, 250, 9.5, 120, 200, 3, 295.71, 9.49},
                {2, 250, 9.5, 120, 200, 4, 335.6, 9.32},
                {1, 2.31, 2.12, 0, 0, 1, 0.78, 7.4},
                {1, 2.31, 2.12, 0, 0, 2, 1.34, 4.76},
                {1, 2.31, 2.12, 0, 0, 3, 2.31, 2.12},
                {1, 2.31, 2.12, 0, 0, 4, 3.95, 1},
                {1, 0.21, 6.41, 0, 0, 1, 0.08, 8.81},
                {1, 0.21, 6.41, 0, 0, 2, 0.14, 7.61},
                {1, 0.21, 6.41, 0, 0, 3, 0.24, 6.4},
                {1, 0.21, 6.41, 0, 0, 4, 0.42, 5.2},
            };
            for (double[] x : v) {
                int rating = (int) x[5];
                double s = x[1];
                double d = x[2];
                double gotS;
                double gotD = Fsrs.nextDifficulty(d, rating);
                if (x[0] == 2) {
                    double r = Fsrs.forgettingCurve(x[3], s);
                    gotS = rating == 1 ? Fsrs.nextForgetStability(d, s, r)
                        : Fsrs.nextRecallStability(d, s, r, rating);
                } else {
                    gotS = Fsrs.shortTermStability(s, rating);
                }
                c.close("stability " + Arrays.toString(x), x[6], gotS, 0);
                c.close("difficulty " + Arrays.toString(x), x[7], gotD, 0);
            }
        });
    }

    private static void lapses(Check c) {
        c.run("Again on a review relearns, and S = 0.00 starts over", () -> {
            Card card = reviewed(20.0, 5.0, T0);
            Card again = Fsrs.review(card, 1, T0 + 20 * DAY, 20, 10 * 3600L);
            c.same("relearning", State.RELEARNING, again.getState());
            c.same("first step", 0, again.getStep());
            long wait = again.getDue().getEpochSecond() - T0 - 20 * DAY;
            c.ok("ten minutes, give or take: " + wait, wait >= 600 && wait < 750);
            c.ok("stability falls", again.getStability() < 20.0);
            Card spent = Card.builder().cardId(7).state(State.LEARNING).step(0).stability(0.0)
                .difficulty(9.0).due(Instant.ofEpochSecond(T0)).lastReview(Instant.ofEpochSecond(T0)).build();
            Card restarted = Fsrs.review(spent, 1, T0 + 60, 0, 10 * 3600L);
            c.close("from Again's initial state", Fsrs.shortTermStability(0.21, 1), restarted.getStability(), 0);
            c.close("difficulty too", Fsrs.nextDifficulty(6.41, 1), restarted.getDifficulty(), 0);
        });
    }

    private static void rounding(Check c) {
        c.run("two decimals throughout, as the script keeps them", () -> {
            Random rng = new Random(7);
            Card card = Card.builder().cardId(8).build();
            long now = T0;
            for (int i = 0; i < 300; i++) {
                card = Fsrs.review(card, 1 + rng.nextInt(4), now, i % 3, 10 * 3600L);
                double s = card.getStability() * 100;
                double d = card.getDifficulty() * 100;
                if (Math.abs(s - Math.rint(s)) > 1e-6 || Math.abs(d - Math.rint(d)) > 1e-6) {
                    c.ok("answer " + i + ": " + card, false);
                    return;
                }
                now = Math.max(now + 60, card.getDue().getEpochSecond());
            }
            c.close("round2 halves up", 2.13, Fsrs.round2(2.125), 0);
            c.close("round2 of 0.005 as a double", 0.01, Fsrs.round2(0.005000000000000001), 0);
            c.close("round2 of 1.005 as a double (1.00499...)", 1.0, Fsrs.round2(1.005), 0);
        });
    }

    private static long days(Card card, long now) {
        return (card.getDue().getEpochSecond() - now) / DAY;
    }

    /* --- the Anki queue --- */

    private static Deck deckOf(int n) {
        List<DeckCard> cards = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cards.add(new DeckCard(new CardDef("key" + i, "value" + i)));
        }
        return new Deck("test", cards);
    }

    private static final long T0 = 1_000_000_000L;
    private static final Random RNG = new Random(42L);

    /** A review card with canned memory, answered at `lastSeconds` and due then. */
    private static Card reviewed(double stability, double difficulty, long lastSeconds) {
        return Card.builder()
            .state(State.REVIEW)
            .stability(stability)
            .difficulty(difficulty)
            .due(Instant.ofEpochSecond(lastSeconds))
            .lastReview(Instant.ofEpochSecond(lastSeconds))
            .build();
    }

    // noon UTC, so the 4am rollover is eight hours behind and sixteen ahead
    private static final long NOON = 1_700_049_600L;

    private static ReviewSession session(Deck deck) {
        return new ReviewSession(deck, ZoneOffset.UTC);
    }

    private static void autoTakesNewCardsAsTheyAreLearned(Check c) {
        c.run("AUTO: new cards as fast as they are learned", () -> {
            Deck deck = deckOf(30);
            ReviewSession s = session(deck);
            c.same("the target's worth on offer", ReviewSession.AUTO_TARGET, s.counts(NOON)[0]);
            long now = NOON;
            DeckCard card;
            int introduced = 0;
            // Easy graduates a new card at once, so nothing piles up and the
            // whole deck comes in, with no daily number to stop it.
            while ((card = s.next(now)) != null) {
                c.ok("only new cards", card.isNew());
                s.rate(4, now);
                introduced++;
                now += 10;
            }
            c.same("the whole deck comes in", 30, introduced);
        });
    }

    private static void autoTopsUpToTheTarget(Check c) {
        c.run("AUTO: New + Learning + Due is kept at the target", () -> {
            Deck deck = deckOf(40);
            for (int i = 0; i < 12; i++) deck.cards().get(i).fsrs = dueAt(NOON - 3600L);
            ReviewSession s = session(deck);
            int[] n = s.counts(NOON);
            c.same("12 due", 12, n[2]);
            c.same("8 new to make 20", 8, n[0]);
            c.same("total", ReviewSession.AUTO_TARGET, n[0] + n[1] + n[2]);

            Deck full = deckOf(40);
            for (int i = 0; i < 25; i++) full.cards().get(i).fsrs = dueAt(NOON - 3600L);
            ReviewSession busy = session(full);
            c.same("none new with 25 due", 0, busy.counts(NOON)[0]);
            for (int i = 0; i < 6; i++) {
                c.ok("reviews while over the target", !busy.next(NOON).isNew());
                busy.rate(3, NOON);
            }
            c.same("19 due leaves room for 1", 1, busy.counts(NOON)[0]);
        });
    }

    private static void newCardsMixIntoReviews(Check c) {
        c.run("new cards mixed in among reviews", () -> {
            Deck deck = deckOf(40);
            for (int i = 0; i < 10; i++) deck.cards().get(i).fsrs = dueAt(NOON - 3600L);
            ReviewSession s = session(deck);
            StringBuilder order = new StringBuilder();
            long now = NOON;
            for (int i = 0; i < 12; i++) {
                DeckCard card = s.next(now);
                order.append(card.isNew() ? 'n' : 'r');
                s.rate(4, now);
                now += 10;
            }
            String seen = order.toString();
            c.ok("a review first", seen.charAt(0) == 'r');
            c.ok("new cards among the reviews, not after them: " + seen, seen.indexOf('n') < 4 && seen.lastIndexOf('r') > 6);
            c.ok("no long run of either: " + seen, !seen.contains("rrrr") && !seen.contains("nnnn"));
        });
    }

    private static void numberedDeckLimitsNew(Check c) {
        c.run("a numbered deck: that many new a day, mixed in", () -> {
            Deck deck = deckOf(10);
            deck.newPerDay = 2;
            for (int i = 0; i < 4; i++) deck.cards().get(i).fsrs = dueAt(NOON - 3600L);
            ReviewSession s = session(deck);
            c.ok("room for new cards", s.wantsNew(NOON));
            int fresh = 0;
            long now = NOON;
            DeckCard card;
            while ((card = s.next(now)) != null) {
                if (card.isNew()) fresh++;
                s.rate(4, now);
                now += 10;
            }
            c.same("two new", 2, fresh);
        });
    }

    private static void dueReviewsOnly(Check c) {
        c.run("only reviews due today", () -> {
            Deck deck = deckOf(2);
            ReviewSession s = session(deck);
            DeckCard dueTonight = deck.cards().get(0);
            DeckCard dueTomorrow = deck.cards().get(1);
            dueTonight.fsrs = dueAt(NOON + 10L * 3600L);
            dueTomorrow.fsrs = dueAt(NOON + 20L * 3600L);
            c.same("review count", 1, s.counts(NOON)[2]);
            c.ok("due before 4am is shown", s.next(NOON) == dueTonight);
            s.rate(3, NOON);
            c.ok("due after 4am waits", s.next(NOON + 60) == null);
            c.ok("comes up after rollover", s.next(NOON + 17L * 3600L) == dueTomorrow);
        });
    }

    private static void learningComesBackWhenDue(Check c) {
        c.run("learning steps", () -> {
            Deck deck = deckOf(1);
            ReviewSession s = session(deck);
            DeckCard card = s.next(NOON);
            s.rate(1, NOON);
            c.same("again keeps it learning", State.LEARNING, card.fsrs.getState());
            c.same("learning count", 1, s.counts(NOON)[1]);
            c.ok("learn ahead serves it when nothing else is left", s.next(NOON + 1) == card);
            s.rate(3, NOON + 60);
            long due = card.fsrs.getDue().getEpochSecond();
            c.ok("good goes to the 10m step, give or take", due >= NOON + 60 + 600 && due < NOON + 60 + 750);
            c.same("next learning due", due, s.nextLearningDue(NOON + 60));
            c.ok("served again once due", s.next(due) == card);
            s.rate(3, due);
            c.same("second good graduates", State.REVIEW, card.fsrs.getState());
        });
    }

    private static void previewMatchesRating(Check c) {
        c.run("preview matches rating", () -> {
            Deck deck = deckOf(1);
            DeckCard card = deck.cards().get(0);
            card.fsrs = reviewed(30.0, 5.0, NOON - 30L * 86400L);
            ReviewSession s = session(deck);
            s.next(NOON);
            Card shown = s.preview(card.fsrs, 3, NOON);
            s.rate(3, NOON);
            c.same("same due as previewed", shown.getDue(), card.fsrs.getDue());
            Card hard = s.preview(shown, 2, NOON + 40L * 86400L);
            Card easy = s.preview(shown, 4, NOON + 40L * 86400L);
            c.ok("easy waits longer than hard", easy.getDue().isAfter(hard.getDue()));
        });
    }

    private static Card dueAt(long due) {
        return Card.builder()
            .state(State.REVIEW)
            .stability(10.0)
            .difficulty(5.0)
            .due(Instant.ofEpochSecond(due))
            .lastReview(Instant.ofEpochSecond(due - 10L * 86400L))
            .build();
    }
}
