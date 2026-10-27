package com.flashcards.xp3;

import com.flashcards.xp3.algo.Fsrs;
import com.flashcards.xp3.algo.ReviewSession;
import com.flashcards.xp3.data.CardDef;
import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;

import com.xp3.common.Check;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The algorithm half of the app, checked on a desktop JVM. The scheduling
 * engine under test is the Java-8 FSRS-6 adaptation in package
 * io.github.openspacedrepetition. Legacy java-fsrs vectors are retained with
 * their original parameter set, alongside the app-level behaviors built on
 * top — the Anki-style review queue.
 */
public final class AlgoTests {

    public static void run(Check c) {
        fsrs(c);
        review(c);
        libraryVectors(c);
        learningSteps(c);
        correctedFsrsEdges(c);
        fuzz(c);
        stabilityFloor(c);
        autoTakesNewCardsAsTheyAreLearned(c);
        autoWaitsForTheWindow(c);
        autoWaitsForReviews(c);
        reviewsBeforeNewCards(c);
        dueReviewsOnly(c);
        learningComesBackWhenDue(c);
        previewMatchesRating(c);
    }

    /* --- the engine's own entry points --- */

    private static void fsrs(Check c) {
        c.run("fsrs", () -> {
            Scheduler sch = Fsrs.defaults();
            // R(S, S) == 0.9 by construction of FACTOR: stability 1, one day ago.
            Card s1 = reviewed(1.0, 5.0, T0);
            c.close(
                "curve at own stability is 0.9",
                0.9,
                sch.getCardRetrievability(s1, Instant.ofEpochSecond(T0 + 86400L)),
                1e-9);
            // R is 1 the instant a card is reviewed (t == 0).
            c.close(
                "curve at t=0 is 1",
                1.0,
                sch.getCardRetrievability(s1, Instant.ofEpochSecond(T0)),
                1e-9);
            // R falls as the card ages.
            double fresh =
                sch.getCardRetrievability(s1, Instant.ofEpochSecond(T0 + 86400L));
            double aged =
                sch.getCardRetrievability(s1, Instant.ofEpochSecond(T0 + 30L * 86400L));
            c.ok("decays with time", fresh > aged);
            // R of a never-seen card is 0.
            c.close(
                "new card retrievability 0",
                0.0,
                sch.getCardRetrievability(
                    Card.builder().cardId(1).build(), Instant.ofEpochSecond(T0)),
                1e-9);
            // A Good first answer lands on the library weights' W[2] (3.2602).
            double[] w = Fsrs.defaults().getParameters();
            Card good = Fsrs.review(Card.builder().cardId(2).build(), 3, T0);
            c.close("init stability good", w[2], good.getStability(), 1e-9);
            c.ok(
                "init difficulty within band",
                good.getDifficulty() >= 1.0 && good.getDifficulty() <= 10.0);
            // harder rating -> higher difficulty
            Card again = Fsrs.review(Card.builder().cardId(3).build(), 1, T0);
            c.ok("harder rating -> higher difficulty", again.getDifficulty() > good.getDifficulty());
        });
    }

    private static void review(Check c) {
        c.run("review", () -> {
            long now = 1_000_000L;
            Card card = Card.builder().cardId(10).build();
            c.ok("starts new", card.getLastReview() == null);
            c.close("retrievability of a new card is 0", 0.0, Fsrs.retrievability(card, 12345L), 1e-9);

            Card good = Fsrs.review(card, 3, now);
            c.ok("no longer new", good.getLastReview() != null);
            c.same("reviewed at now", now, good.getLastReview().getEpochSecond());
            double[] w = Fsrs.defaults().getParameters();
            c.close("stability set", w[2], good.getStability(), 1e-9);
            c.close("difficulty set", w[4] - Math.exp(w[5] * 2.0) + 1.0, good.getDifficulty(), 1e-9);

            // A Good first answer stays in learning (1-min / 10-min steps), due
            // not further than one step away.
            c.same("first good stays learning", State.LEARNING, good.getState());
            c.same("step is 1", 1, good.getStep());

            // A repeated Good two days later graduates the card and grows stability.
            long later = now + 2L * 86400L;
            Card good2 = Fsrs.review(good, 3, later);
            c.same("second good graduates", State.REVIEW, good2.getState());
            c.ok("graduated step cleared", good2.getStep() == null);
            c.ok("stability grows on success", good2.getStability() > good.getStability());
            c.ok(
                "graduation waits at least a day",
                Duration.between(good2.getLastReview(), good2.getDue()).toDays() >= 1);

            // An Again on a reviewed card, outside the same-day window, is a
            // lapse: it lowers stability and sends the card back to relearn.
            Card lapsed = Fsrs.review(good2, 1, later + 3L * 86400L);
            c.ok("again lowers stability", lapsed.getStability() < good2.getStability());
            c.same("again sends to relearn", State.RELEARNING, lapsed.getState());
        });
    }

    /* --- the java-fsrs library's own canonical vectors, reproduced --- */

    private static void libraryVectors(Check c) {
        c.run("java-fsrs vectors", () -> {
            // testReviewCard: the interval history for the chain
            // G G G G G G A A G G G G G (fuzz off).
            Scheduler sch =
                Scheduler.builder()
                    .parameters(JAVA_FSRS_1_PARAMETERS)
                    .enableFuzzing(false)
                    .build();
            Rating[] ratings = {
                Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD,
                Rating.AGAIN, Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD,
                Rating.GOOD,
            };
            Card card = Card.builder().cardId(100).build();
            Instant rev = Instant.parse("2022-11-29T12:30:00Z");
            List<Integer> ivlHistory = new ArrayList<>();
            for (Rating rating : ratings) {
                card = sch.reviewCard(card, rating, rev).getCard();
                ivlHistory.add((int) ChronoUnit.DAYS.between(card.getLastReview(), card.getDue()));
                rev = card.getDue();
            }
            List<Integer> expected =
                Arrays.asList(0, 4, 14, 45, 135, 372, 0, 0, 2, 5, 10, 20, 40);
            c.ok("interval history matches java-fsrs", expected.equals(ivlHistory));

            // testMemoState: AGAIN G G G G G then G (fuzz on, stability/difficulty unaffected).
            Scheduler sch2 =
                Scheduler.builder()
                    .parameters(JAVA_FSRS_1_PARAMETERS)
                    .build();
            Card memo = Card.builder().cardId(101).build();
            Instant t = Instant.parse("2022-11-29T12:30:00Z");
            Rating[] chain = {
                Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD,
            };
            int[] gaps = {0, 0, 1, 3, 8, 21};
            for (int i = 0; i < chain.length; i++) {
                t = t.plus(Duration.ofDays(gaps[i]));
                memo = sch2.reviewCard(memo, chain[i], t).getCard();
            }
            memo = sch2.reviewCard(memo, Rating.GOOD, t).getCard();
            c.close("memo state stability", 49.4472, memo.getStability(), 1e-4);
            c.close("memo state difficulty", 6.8271, memo.getDifficulty(), 1e-4);

            // testRepeatedCorrectReviews: ten same-instant Easy ratings floor difficulty at 1.0.
            Scheduler sch3 =
                Scheduler.builder().enableFuzzing(false).build();
            Card floor = Card.builder().cardId(102).build();
            for (int i = 0; i < 10; i++) {
                floor =
                    sch3
                        .reviewCard(
                            floor, Rating.EASY, Instant.parse("2022-11-29T12:30:00Z").plusNanos(i * 1000))
                        .getCard();
            }
            c.close("repeated easy floors difficulty", 1.0, floor.getDifficulty(), 1e-9);
        });
    }

    private static void learningSteps(Check c) {
        c.run("learning steps", () -> {
            Scheduler sch =
                Scheduler.builder().build();
            Card card = Card.builder().cardId(200).build();
            c.same("starts learning step 0", 0, card.getStep());

            // GOOD -> step 1, still learning; a second GOOD graduates to review.
            Card once = sch.reviewCard(card, Rating.GOOD, card.getDue()).getCard();
            c.same("good -> step 1", 1, once.getStep());
            Card twice = sch.reviewCard(once, Rating.GOOD, once.getDue()).getCard();
            c.same("second good graduates", State.REVIEW, twice.getState());
            c.ok("step cleared on graduation", twice.getStep() == null);

            // AGAIN resets to step 0 and reschedules within the first 1-min step.
            Card again = sch.reviewCard(card, Rating.AGAIN, card.getDue()).getCard();
            c.same("again resets step", 0, again.getStep());
            c.ok(
                "again reschedules within the minute",
                Duration.between(card.getDue(), again.getDue()).toMinutes() <= 1);

            // EASY skips the steps entirely and graduates at once.
            Card easy = sch.reviewCard(card, Rating.EASY, card.getDue()).getCard();
            c.same("easy graduates at once", State.REVIEW, easy.getState());

            // A scheduler with no learning steps serves long-term intervals right away.
            Scheduler sch0 =
                Scheduler.builder()
                    .learningSteps(new Duration[0])
                    .build();
            Card r0 =
                sch0
                    .reviewCard(
                        Card.builder().cardId(201).build(), Rating.AGAIN, card.getDue())
                    .getCard();
            c.same("no learning steps -> review", State.REVIEW, r0.getState());
            c.ok(
                "long-term interval at least a day",
                Duration.between(r0.getLastReview(), r0.getDue()).toDays() >= 1);
        });
    }

    private static void correctedFsrsEdges(Check c) {
        c.run("corrected FSRS edges", () -> {
            Scheduler sch =
                Scheduler.builder()
                    .learningSteps(new Duration[]{Duration.ofMinutes(1)})
                    .relearningSteps(
                        new Duration[]{Duration.ofMinutes(2), Duration.ofMinutes(20)})
                    .enableFuzzing(false)
                    .build();
            Instant now = Instant.ofEpochSecond(T0);
            Card relearning =
                Card.builder()
                    .cardId(250)
                    .state(State.RELEARNING)
                    .step(0)
                    .stability(5.0)
                    .difficulty(5.0)
                    .due(now)
                    .lastReview(now)
                    .build();
            Card hard = sch.reviewCard(relearning, Rating.HARD, now).getCard();
            c.same(
                "custom relearning Hard averages relearning steps",
                11L,
                Duration.between(hard.getLastReview(), hard.getDue()).toMinutes());
            c.ok("same-day Hard does not lower stability", hard.getStability() >= 5.0);

            c.fails(
                "rejects wrong parameter count",
                "21 parameters",
                () -> Scheduler.builder()
                    .parameters(new double[20])
                    .build());
            c.fails(
                "rejects invalid rating",
                "between 1 and 4",
                () -> Fsrs.review(Card.builder().cardId(251).build(), 5, T0));
        });
    }

    private static void fuzz(Check c) {
        c.run("fuzz", () -> {
            // At S=7 and desired retention 0.9, the unfuzzed interval is seven
            // days. Reference fuzz bounds for seven days are [5, 9].
            int[] seeds = {0, 1, 42, 12345, Integer.MAX_VALUE};
            for (int seed : seeds) {
                Scheduler sch =
                    Scheduler.builder()
                        .randomSeedNumber(seed)
                        .build();
                Instant t = Instant.parse("2022-11-29T12:30:00Z");
                Card card = reviewed(7.0, 5.0, t.getEpochSecond());
                card = sch.reviewCard(card, Rating.GOOD, t).getCard();
                int intervalDays = (int) Duration.between(card.getLastReview(), card.getDue()).toDays();
                c.ok("fuzz seed " + seed + " stays in bounds", intervalDays >= 5 && intervalDays <= 9);
            }
        });
    }

    private static void stabilityFloor(Check c) {
        c.run("stability floor", () -> {
            // Stability never dips under the library's STABILITY_MIN across a
            // thousand daily-late lapses.
            Scheduler sch =
                Scheduler.builder().build();
            Card card = Card.builder().cardId(400).build();
            boolean bounded = true;
            for (int i = 0; i < 1000; i++) {
                card = sch.reviewCard(card, Rating.AGAIN, card.getDue().plus(Duration.ofDays(1))).getCard();
                if (card.getStability() < Scheduler.STABILITY_MIN) {
                    bounded = false;
                }
            }
            c.ok("stability bounded below", bounded);
        });
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
    private static final double[] JAVA_FSRS_1_PARAMETERS = {
        0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069, 1.5261, 0.112,
        1.0178, 1.849, 0.1133, 0.3127, 2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2
    };

    /** A reviewed card in the library's REVIEW state with canned memory. */
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
            c.same("a window's worth on offer", ReviewSession.AUTO_WINDOW, s.counts(NOON)[0]);
            long now = NOON;
            DeckCard card;
            int introduced = 0;
            // Easy graduates a new card at once, so the window never fills
            // and the whole deck comes in, with no daily number to stop it.
            while ((card = s.next(now)) != null) {
                c.ok("only new cards", card.isNew());
                s.rate(4, now);
                introduced++;
                now += 10;
            }
            c.same("the whole deck comes in", 30, introduced);
        });
    }

    private static void autoWaitsForTheWindow(Check c) {
        c.run("AUTO: no new card while the window is full", () -> {
            Deck deck = deckOf(30);
            ReviewSession s = session(deck);
            long now = NOON;
            // Again keeps each new card in learning, so the window fills.
            for (int i = 0; i < ReviewSession.AUTO_WINDOW; i++) {
                DeckCard card = s.next(now);
                c.ok("new while there is room", card.isNew());
                s.rate(1, now);
            }
            c.same("window full", ReviewSession.AUTO_WINDOW, s.inLearning());
            c.same("no new count", 0, s.counts(now)[0]);
            DeckCard next = s.next(now);
            c.ok("learning ahead, not a new card", next != null && !next.isNew());
            // Graduating one opens one slot.
            long later = now + 3600;
            DeckCard first = s.next(later);
            s.rate(4, later);
            c.ok("it graduated", first.fsrs.getState() == State.REVIEW);
            c.same("one slot", 1, s.counts(later)[0]);
        });
    }

    private static void autoWaitsForReviews(Check c) {
        c.run("AUTO: reviews before any new card", () -> {
            Deck deck = deckOf(5);
            for (int i = 0; i < 2; i++) deck.cards().get(i).fsrs = dueAt(NOON - 3600L);
            ReviewSession s = session(deck);
            c.ok("old first", !s.next(NOON).isNew());
            s.rate(3, NOON);
            c.ok("old first again", !s.next(NOON).isNew());
            s.rate(3, NOON);
            c.ok("then new", s.next(NOON).isNew());
        });
    }

    private static void reviewsBeforeNewCards(Check c) {
        c.run("reviews before new cards", () -> {
            Deck deck = deckOf(4);
            for (int i = 0; i < 2; i++) deck.cards().get(i).fsrs = dueAt(NOON - 3600L);
            ReviewSession s = session(deck);
            c.ok("room for new cards", s.wantsNew(NOON));
            for (int i = 0; i < 2; i++) {
                DeckCard card = s.next(NOON);
                c.ok("a review first", !card.isNew());
                s.rate(3, NOON);
            }
            c.ok("then a new card", s.next(NOON).isNew());
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
            c.same("good goes to the 10m step", NOON + 60 + 600, due);
            c.same("next learning due", due, s.nextLearningDue(NOON + 60));
            c.ok("served again once due", s.next(due) == card);
            s.rate(3, due);
            c.same("second good graduates", State.REVIEW, card.fsrs.getState());
        });
    }

    private static void newCardsMixIntoReviews(Check c) {
        c.run("new mixed with reviews", () -> {
            Deck deck = deckOf(40);
            for (int i = 0; i < 20; i++) deck.cards().get(i).fsrs = dueAt(NOON - 86400L);
            ReviewSession s = session(deck);
            int newInFirstHalf = 0;
            long now = NOON;
            for (int i = 0; i < 20; i++) {
                DeckCard card = s.next(now);
                if (card.isNew()) newInFirstHalf++;
                s.rate(4, now);
                now += 10;
            }
            c.ok("about half the first twenty are new", newInFirstHalf >= 8 && newInFirstHalf <= 12);
        });
    }

    private static void previewMatchesRating(Check c) {
        c.run("preview matches rating", () -> {
            Deck deck = deckOf(1);
            DeckCard card = deck.cards().get(0);
            card.fsrs = reviewed(30.0, 5.0, NOON - 30L * 86400L);
            ReviewSession s = session(deck);
            s.next(NOON);
            Card shown = ReviewSession.preview(card.fsrs, 3, NOON);
            s.rate(3, NOON);
            c.same("same due as previewed", shown.getDue(), card.fsrs.getDue());
            Card hard = ReviewSession.preview(shown, 2, NOON + 40L * 86400L);
            Card easy = ReviewSession.preview(shown, 4, NOON + 40L * 86400L);
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
