package com.flashcards.xp3;

import com.flashcards.xp3.algo.Fsrs;
import com.flashcards.xp3.algo.GroupBuilder;
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
 * top — the 85%-targeted group builder and the review session.
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
        groupSizeAndDistinct(c);
        hardDeckDoesNotIntroduceNewCards(c);
        newCardsEnterAutomatically(c);
        learningCardBlocksNewIntroductions(c);
        cardsAboveNinetyAreNeverSelected(c);
        cardAtNinetyRemainsEligible(c);
        groupTargetsEightyFive(c);
        groupPreferUrgent(c);
        scheduleEndToEnd(c);
        equalCardsDoNotStarve(c);
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

    /* --- group builder: shape --- */

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

    private static void groupSizeAndDistinct(Check c) {
        c.run("group size & distinct", () -> {
            // a deck with many reviewed cards yields a full group of ten
            Deck big = deckOf(30);
            long now = T0;
            for (DeckCard card : big.cards()) {
                card.fsrs = reviewed(5.0, 5.0, now - 6L * 86400L);
            }
            List<DeckCard> group =
                new GroupBuilder(big.cards(), now, new Random(1L)).build();
            c.same("full group is ten", 10, group.size());
            // and every card in it is distinct
            boolean distinct = true;
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    if (group.get(i) == group.get(j)) distinct = false;
                }
            }
            c.ok("no card twice", distinct);
        });
    }

    private static void hardDeckDoesNotIntroduceNewCards(Check c) {
        c.run("hard deck does not introduce new cards", () -> {
            Deck deck = deckOf(12);
            long now = T0;
            for (int i = 0; i < 10; i++) {
                deck.cards().get(i).fsrs = reviewed(1.0, 5.0, now - 2L * 86400L);
            }

            List<DeckCard> group =
                new GroupBuilder(deck.cards(), now, new Random(8L)).build();

            c.ok("fixture is below target", deck.cards().get(0).probability(now) < GroupBuilder.TARGET);
            c.same("review group stays full", GroupBuilder.GROUP_SIZE, group.size());
            for (DeckCard card : group) {
                c.ok("new cards are outside accuracy math", !card.isNew());
            }
        });
    }

    private static void newCardsEnterAutomatically(Check c) {
        c.run("new cards enter automatically", () -> {
            Deck deck = deckOf(12);
            long now = T0;
            for (int i = 0; i < 10; i++) {
                deck.cards().get(i).fsrs = reviewed(5.0, 3.0, now - 6L * 86400L);
            }
            DeckCard firstNew = deck.cards().get(10);
            DeckCard secondNew = deck.cards().get(11);

            ReviewSession scheduler = new ReviewSession(deck, now, new Random(9L));
            scheduler.next();

            int introduced = 0;
            if (scheduler.group().contains(firstNew)) introduced++;
            if (scheduler.group().contains(secondNew)) introduced++;
            c.ok("fixture is above target", deck.cards().get(0).probability(now) > GroupBuilder.TARGET);
            c.same("only one new card is auto-added", 1, introduced);
            c.same("new card occupies one group slot", GroupBuilder.GROUP_SIZE, scheduler.group().size());
        });
    }

    private static void learningCardBlocksNewIntroductions(Check c) {
        c.run("learning card blocks new introductions", () -> {
            Deck deck = deckOf(3);
            long now = T0;
            deck.cards().get(0).fsrs = reviewed(10.0, 3.0, now);
            deck.cards().get(1).fsrs =
                Card.builder()
                    .state(State.LEARNING)
                    .step(0)
                    .stability(1.0)
                    .difficulty(5.0)
                    .due(Instant.ofEpochSecond(now))
                    .lastReview(Instant.ofEpochSecond(now - 2L * 86400L))
                    .build();

            List<DeckCard> group =
                new GroupBuilder(deck.cards(), now, new Random(11L)).build();

            c.ok("existing learning card is selected", group.contains(deck.cards().get(1)));
            c.ok("new card waits for learning to finish", !group.contains(deck.cards().get(2)));
        });
    }

    private static void cardsAboveNinetyAreNeverSelected(Check c) {
        c.run("cards above 90% are never selected", () -> {
            Deck deck = deckOf(3);
            long now = T0;
            DeckCard tooFresh = deck.cards().get(0);
            DeckCard ready = deck.cards().get(1);
            DeckCard unseen = deck.cards().get(2);
            tooFresh.fsrs = reviewed(10.0, 3.0, now);
            ready.fsrs = reviewed(1.0, 5.0, now - 2L * 86400L);

            List<DeckCard> group =
                new GroupBuilder(deck.cards(), now, new Random(10L)).build();

            c.ok("fixture starts above 90%", tooFresh.probability(now) > 0.90);
            c.ok("above-90% card is excluded", !group.contains(tooFresh));
            c.ok("eligible reviewed card remains", group.contains(ready));
            c.ok("new card remains eligible", group.contains(unseen));
        });
    }

    private static void cardAtNinetyRemainsEligible(Check c) {
        c.run("card at 90% remains eligible", () -> {
            Deck deck = deckOf(1);
            long now = T0;
            DeckCard boundary = deck.cards().get(0);
            boundary.fsrs = reviewed(1.0, 5.0, now - 86400L);

            List<DeckCard> group =
                new GroupBuilder(deck.cards(), now, new Random(12L)).build();

            c.close("fixture is exactly 90%", 0.90, boundary.probability(now), 1e-9);
            c.ok("90% card is selected", group.contains(boundary));
        });
    }

    /* --- group builder: the 85% target --- */

    // Builds a realistic deck: most cards reviewed recently enough to carry a
    // high retrievability, a graduating tail of cards that have aged. With the
    // pool rich enough above the 85% target, the builder must genuinely mix
    // to land the group mean near it — the behavior this test exists to pin.
    private static void groupTargetsEightyFive(Check c) {
        c.run("group targets 85%", () -> {
            Deck deck = deckOf(30);
            long now = T0;
            for (int i = 0; i < 30; i++) {
                double s = 3.0 + (i % 6) * 0.7;          // 3.0..6.5
                long elapsed = (long) ((i % 8) * 8) + 1; // 1..57 days
                deck.cards().get(i).fsrs = reviewed(s, 5.0, now - elapsed * 86400L);
            }
            long sum = 0;
            int trials = 200;
            for (int t = 0; t < trials; t++) {
                List<DeckCard> group =
                    new GroupBuilder(deck.cards(), now, new Random(t)).build();
                for (DeckCard card : group) {
                    sum += Math.round(card.probability(now) * 1000.0);
                }
            }
            double mean = (sum / 1000.0) / (trials * GroupBuilder.GROUP_SIZE);
            c.close("mean predicted accuracy near 85%", GroupBuilder.TARGET, mean, 0.08);
        });
    }

    /* --- group builder: phase 1 prefers urgent cards --- */

    // When the deck is small and spotty, the builder must still serve the most
    // urgent cards rather than lazily taking the easiest, up to MIN_URGENT.
    private static void groupPreferUrgent(Check c) {
        c.run("group takes urgent", () -> {
            Deck deck = deckOf(20);
            long now = T0;
            // Nineteen moderately fresh cards plus one gone stale. The stale card
            // must survive selection into a ten-card group.
            for (int i = 0; i < 19; i++) {
                deck.cards().get(i).fsrs = reviewed(5.0, 3.0, now - 6L * 86400L);
            }
            deck.cards().get(19).fsrs = reviewed(1.0, 5.0, now - 30L * 86400L);
            List<DeckCard> group =
                new GroupBuilder(deck.cards(), now, new Random(7L)).build();
            c.same("large deck still builds ten", 10, group.size());
            c.ok("stale card is served", group.contains(deck.cards().get(19)));
        });
    }

    /* --- scheduler: end to end --- */

    private static void scheduleEndToEnd(Check c) {
        c.run("scheduler end to end", () -> {
            long now = T0;
            long later = now + 30L * 86400L;
            long originalReview = now - 5L * 86400L;
            Deck deck = deckOf(12);
            for (DeckCard card : deck.cards()) {
                card.fsrs = reviewed(4.0, 5.0, originalReview);
            }
            ReviewSession s = new ReviewSession(deck, now, new Random(3L));

            // A deck of 12 yields a first group of exactly GROUP_SIZE.
            DeckCard first = s.next();
            c.ok("group starts on first next", first != null);
            c.same("first group is ten", GroupBuilder.GROUP_SIZE, s.group().size());

            // Serve the whole first group, rating each card (1..4, cycling); every one
            // is distinct within the group.
            int served = 0;
            DeckCard card;
            while (s.hasCurrent()) {
                card = s.current();
                served++;
                s.rate(1 + (served % 4), later);
            }
            c.same("served the full group", GroupBuilder.GROUP_SIZE, served);

            // After the group is spent, the next request rebuilds a fresh
            // group from the updated FSRS state. The ten just reviewed cards
            // are above 90%, so only the two untouched cards are eligible.
            DeckCard again = s.next();
            c.ok("resumes after group exhausted", again != null);
            c.same("fresh cards are left out of rebuild", 2, s.group().size());

            // The two cards omitted from the first group continued to decay
            // while the first ten were refreshed. Both must now be urgent.
            int untouchedInSecond = 0;
            for (DeckCard d : s.group()) {
                if (d.fsrs.getLastReview().getEpochSecond() == originalReview) {
                    untouchedInSecond++;
                }
            }
            c.same("second group includes both still-urgent cards", 2, untouchedInSecond);
        });
    }

    private static void equalCardsDoNotStarve(Check c) {
        c.run("equal-probability fairness", () -> {
            long now = T0;
            Deck deck = deckOf(30);
            for (DeckCard card : deck.cards()) {
                card.fsrs = reviewed(5.0, 5.0, now - 6L * 86400L);
            }
            boolean[] seen = new boolean[30];
            for (int pass = 0; pass < 30; pass++) {
                List<DeckCard> group =
                    new GroupBuilder(deck.cards(), now, new Random(pass)).build();
                for (DeckCard card : group) {
                    int id = Integer.parseInt(card.def.key.substring(3));
                    seen[id] = true;
                }
            }
            boolean allSeen = true;
            for (boolean value : seen) allSeen &= value;
            c.ok("random tie-breaking reaches whole deck", allSeen);
        });
    }
}
