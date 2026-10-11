import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.CardAndReviewLog;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Historical deterministic fingerprint used to verify the initial java-fsrs
 * 1.0.0 vendoring. The checked-in output files document parity at that point.
 * The app's current FSRS-6 adaptation intentionally differs in its defaults,
 * same-day Hard handling, fuzzing, and configurable relearning-step fix.
 *
 * Cards are built with an explicit cardId and due so the builder's
 * wall-clock/sleep defaults never influence the output.
 */
public class Probe {

    static final Instant EPOCH = Instant.parse("2022-11-29T12:30:00Z");

    public static void main(String[] args) {
        canonicalChain();
        scripted(false, 42);
        scripted(true, 42);
        scripted(true, 12345);
        retrievabilitySamples();
        floorAndCap();
        memoState();
        System.out.println("END");
    }

    /** The official testReviewCard vector, fuzz off. */
    static void canonicalChain() {
        Scheduler sch = Scheduler.builder().enableFuzzing(false).build();
        Card card = Card.builder().cardId(1).state(State.LEARNING).due(EPOCH).build();
        int[] ratings = {3, 3, 3, 3, 3, 3, 1, 1, 3, 3, 3, 3, 3};
        Instant t = EPOCH;
        List<Integer> ivls = new ArrayList<Integer>();
        for (int r : ratings) {
            card = sch.reviewCard(card, byValue(r), t).card();
            ivls.add((int) ChronoUnit.DAYS.between(card.getLastReview(), card.getDue()));
            t = card.getDue();
        }
        int[] expected = {0, 4, 14, 45, 135, 372, 0, 0, 2, 5, 10, 20, 40};
        boolean ok = ivls.size() == expected.length;
        if (ok) {
            for (int i = 0; i < expected.length; i++) ok = ok && ivls.get(i) == expected[i];
        }
        System.out.printf(Locale.ROOT, "0.canonical ok=%s ivls=%s%n", ok, ivls);
        if (!ok) System.exit(1);
    }

    /** A long mixed script covering learning steps, graduation, lapses,
     *  relearning, same-day short-term paths, and long-term decay. */
    static void scripted(boolean fuzz, int seed) {
        Scheduler sch = Scheduler.builder().enableFuzzing(fuzz).randomSeedNumber(seed).build();
        Card card = Card.builder().cardId(7).state(State.LEARNING).due(EPOCH).build();
        int[] ratings = {
            3, 2, 3, 4, 3, 1, 3, 3, 4, 2, 3, 1, 3, 3, 3, 4, 2, 3, 3, 1,
            3, 3, 3, 3, 3, 2, 4, 3, 3, 1, 3, 3, 3, 3, 3, 4, 3, 2, 3, 1,
            3, 3, 3, 3, 3, 3, 2, 3, 4, 3, 3, 1, 3, 3, 3, 3, 3, 3, 3, 3,
        };
        int[] days = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 5, 1, 3, 7, 0, 1, 2, 0,
            1, 2, 4, 8, 16, 0, 1, 2, 3, 0, 1, 2, 4, 8, 16, 32, 0, 0, 1, 2,
            3, 5, 8, 13, 21, 34, 55, 89, 0, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144,
        };
        Instant t = EPOCH;
        for (int i = 0; i < ratings.length; i++) {
            t = t.plus(Duration.ofDays(days[i]));
            CardAndReviewLog res = sch.reviewCard(card, byValue(ratings[i]), t, 4);
            card = res.card();
            long ivl = Duration.between(card.getLastReview(), card.getDue()).toMillis();
            double r = sch.getCardRetrievability(card, card.getDue());
            System.out.printf(
                Locale.ROOT,
                "%s.seed%d.%02d st=%d step=%s S=%.17e D=%.17e ivlMs=%d Rdue=%.17e%n",
                fuzz ? "2" : "1",
                seed,
                i,
                card.getState().getValue(),
                card.getStep() == null ? "n" : Integer.toString(card.getStep()),
                card.getStability(),
                card.getDifficulty(),
                ivl,
                r);
        }
    }

    static void retrievabilitySamples() {
        Scheduler sch = Scheduler.builder().build();
        Card card = Card.builder()
            .cardId(9)
            .state(State.REVIEW)
            .step(null)
            .stability(20.0)
            .difficulty(6.0)
            .due(Instant.parse("2022-11-29T12:30:00Z"))
            .lastReview(Instant.parse("2022-11-29T12:30:00Z"))
            .build();
        long[] days = {0, 1, 7, 30, 365, 3650};
        for (long d : days) {
            double r = sch.getCardRetrievability(card, EPOCH.plus(Duration.ofDays(d)));
            System.out.printf(Locale.ROOT, "3.retrievability d=%d R=%.17e%n", d, r);
        }
    }

    static void floorAndCap() {
        Scheduler sch = Scheduler.builder().build();
        Card card = Card.builder().cardId(11).state(State.LEARNING).due(EPOCH).build();
        Instant t = EPOCH;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (int i = 0; i < 1000; i++) {
            t = t.plus(Duration.ofDays(1));
            card = sch.reviewCard(card, Rating.AGAIN, t).card();
            min = Math.min(min, card.getStability());
        }
        Scheduler cap = Scheduler.builder().maximumInterval(100).build();
        Card c2 = Card.builder().cardId(12).state(State.LEARNING).due(EPOCH).build();
        Instant u = EPOCH;
        c2 = cap.reviewCard(c2, Rating.EASY, u).card();
        for (int i = 0; i < 4; i++) {
            c2 = cap.reviewCard(c2, Rating.GOOD, c2.getDue()).card();
            u = c2.getDue();
        }
        long capped =
            Duration.between(c2.getLastReview(), c2.getDue()).toDays();
        System.out.printf(
            Locale.ROOT,
            "4.floor minStability=%.17e floor=%b capped=%d/%d%n",
            min,
            min >= Scheduler.STABILITY_MIN,
            capped,
            cap.getMaximumInterval());
        if (!(min >= Scheduler.STABILITY_MIN) || capped > 100) System.exit(1);
    }

    /** The library's testMemoState trajectory (fuzz on; S/D unaffected). */
    static void memoState() {
        Scheduler sch = Scheduler.builder().build();
        Card card = Card.builder().cardId(21).state(State.LEARNING).due(EPOCH).build();
        int[] ratings = {1, 3, 3, 3, 3, 3};
        int[] gaps = {0, 0, 1, 3, 8, 21};
        Instant t = EPOCH;
        for (int i = 0; i < ratings.length; i++) {
            t = t.plus(Duration.ofDays(gaps[i]));
            card = sch.reviewCard(card, byValue(ratings[i]), t).card();
        }
        card = sch.reviewCard(card, Rating.GOOD, t).card();
        double s = card.getStability();
        double d = card.getDifficulty();
        boolean ok = Math.abs(s - 49.4472) < 1e-4 && Math.abs(d - 6.8271) < 1e-4;
        System.out.printf(Locale.ROOT, "5.memo S=%.6f D=%.6f ok=%b%n", s, d, ok);
        if (!ok) System.exit(1);
    }

    static Rating byValue(int v) {
        for (Rating r : Rating.values()) {
            if (r.getValue() == v) return r;
        }
        throw new IllegalArgumentException("rating " + v);
    }
}
