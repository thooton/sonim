package com.flashcards.xp3.algo;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Random;

/**
 * The scheduler: Anki (v3, its default deck options, FSRS switched off)
 * running the FSRS4Anki v6.1.1 custom scheduler
 * (github.com/open-spaced-repetition/fsrs4anki, fsrs4anki_scheduler.js),
 * ported line for line, with the script's defaults: FSRS-6's default
 * parameters, request retention 0.9, maximum interval 36500 days, fuzz on.
 *
 * Anki itself decides the learning side, and so does this class, as Anki's
 * rslib does: steps 1m 10m, relearning 10m; Again goes to the first step,
 * Hard repeats the step (the first one waits the average of the first two, or
 * half again a lone step), Good moves on or graduates, Easy graduates; a step
 * gets up to 25% more, at most 5 minutes; one that reaches past the 4am
 * rollover comes due on a later day instead. The script decides the rest:
 *
 * - a new card starts from its rating's initial stability and difficulty,
 * - every answer in learning or relearning uses the short-term formula,
 *   whatever the time since the last, and Hard there may lower stability,
 * - a review card uses the long-term formulas, recall worked out from the
 *   Anki days since its last review (0 days counts as recall 1),
 * - stability and difficulty are rounded to two decimals at every step,
 * - intervals are fuzzed by 5% and a day either side, never below the
 *   previous interval when they grew, and Hard <= Good < Easy.
 *
 * Its fuzz is the one thing not the script's: one draw per answer, from
 * java.util.Random seeded by the card and its last review, so the interval
 * shown over a button is the one it gives, on the phone and on the tablet.
 */
public final class Fsrs {

    static final double[] W = {
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
        1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    };
    public static final double REQUEST_RETENTION = 0.9;
    public static final int MAXIMUM_INTERVAL = 36500;
    static final double DECAY = -W[20];
    static final double FACTOR = Math.pow(0.9, 1 / DECAY) - 1;
    /** Anki's default learning and relearning steps, in seconds. */
    static final int[] LEARN_STEPS = {60, 600};
    static final int[] RELEARN_STEPS = {600};
    static final long DAY = 86400L;

    private Fsrs() {}

    /**
     * Answers a card (1 Again, 2 Hard, 3 Good, 4 Easy) at `now`, given the
     * Anki days since its last review and the seconds to the next rollover,
     * returning the new card.
     */
    public static Card review(Card card, int rating, long now, int elapsedDays, long untilRollover) {
        if (rating < 1 || rating > 4) {
            throw new IllegalArgumentException("rating must be between 1 and 4");
        }
        long last = card.getLastReview() == null ? 0L : card.getLastReview().getEpochSecond();
        now = Math.max(now, last);
        double fuzz = new Random(card.getCardId() * 31 + Long.hashCode(last)).nextDouble();
        Card next = new Card(card);
        next.setLastReview(Instant.ofEpochSecond(now));
        Double s = card.getStability();
        Double d = card.getDifficulty();

        if (card.getLastReview() == null) {
            // a new card: init_states
            next.setState(State.LEARNING);
            next.setStability(initStability(rating));
            next.setDifficulty(initDifficulty(rating));
            int good = nextInterval(initStability(3), fuzz, -1);
            int easy = Math.max(nextInterval(initStability(4), fuzz, -1), good + 1);
            step(next, LEARN_STEPS, 0, rating, now, untilRollover, fuzz, good, easy);
        } else if (card.getState() != State.REVIEW) {
            // learning or relearning
            if (s == null || d == null || s == 0 || d == 0) {
                s = initStability(1);
                d = initDifficulty(1);
            }
            next.setStability(shortTermStability(s, rating));
            next.setDifficulty(nextDifficulty(d, rating));
            int good = nextInterval(shortTermStability(s, 3), fuzz, -1);
            int easy = Math.max(nextInterval(shortTermStability(s, 4), fuzz, -1), good + 1);
            int[] steps = card.getState() == State.RELEARNING ? RELEARN_STEPS : LEARN_STEPS;
            int at = card.getStep() == null ? 0 : card.getStep();
            at = Math.max(0, Math.min(at, steps.length - 1));
            step(next, steps, at, rating, now, untilRollover, fuzz, good, easy);
        } else {
            int scheduled = scheduledDays(card);
            if (s == null || d == null || s == 0 || d == 0) {
                // convert_states, from Anki's default starting ease
                s = round2(Math.max(scheduled, 0.1));
                d = constrainDifficulty(11 - (2.5 - 1)
                    / (Math.exp(W[8]) * Math.pow(s, -W[9]) * (Math.exp(0.1 * W[10]) - 1)));
            }
            double r = forgettingCurve(Math.max(0, elapsedDays), s);
            next.setDifficulty(nextDifficulty(d, rating));
            if (rating == 1) {
                next.setStability(nextForgetStability(d, s, r));
                next.setState(State.RELEARNING);
                next.setStep(0);
                next.setDue(Instant.ofEpochSecond(stepDue(now, RELEARN_STEPS[0], untilRollover, fuzz)));
                return next;
            }
            int hard = nextInterval(nextRecallStability(d, s, r, 2), fuzz, scheduled);
            int good = nextInterval(nextRecallStability(d, s, r, 3), fuzz, scheduled);
            int easy = nextInterval(nextRecallStability(d, s, r, 4), fuzz, scheduled);
            hard = Math.min(hard, good);
            good = Math.max(good, hard + 1);
            easy = Math.max(easy, good + 1);
            next.setStability(nextRecallStability(d, s, r, rating));
            next.setDue(Instant.ofEpochSecond(now + (rating == 2 ? hard : rating == 3 ? good : easy) * DAY));
        }
        return next;
    }

    /** The probability of recall after `elapsedDays` Anki days (0 when unseen). */
    public static double retrievability(Card card, int elapsedDays) {
        if (card.getLastReview() == null || card.getStability() == null) return 0;
        return forgettingCurve(Math.max(0, elapsedDays), card.getStability());
    }

    /** One step of Anki's learning or relearning, or graduation. */
    private static void step(Card next, int[] steps, int at, int rating, long now,
                             long untilRollover, double fuzz, int goodDays, int easyDays) {
        int wait;
        if (rating == 1) {
            at = 0;
            wait = steps[0];
        } else if (rating == 2) {
            wait = at > 0 ? steps[at]
                : steps.length > 1 ? (steps[0] + steps[1]) / 2
                : (int) Math.min(steps[0] * 3L / 2, steps[0] + DAY);
        } else if (rating == 3 && at + 1 < steps.length) {
            at++;
            wait = steps[at];
        } else {
            next.setState(State.REVIEW);
            next.setStep(null);
            next.setDue(Instant.ofEpochSecond(now + (rating == 3 ? goodDays : easyDays) * DAY));
            return;
        }
        next.setStep(at);
        next.setDue(Instant.ofEpochSecond(stepDue(now, wait, untilRollover, fuzz)));
    }

    /**
     * When a learning step of `secs` comes due: Anki adds up to 25%, at most
     * five minutes, unless the step reaches the rollover, when it becomes a
     * day's wait instead, due as the day starts.
     */
    static long stepDue(long now, int secs, long untilRollover, double fuzz) {
        if (secs >= untilRollover) {
            long days = (secs - untilRollover) / DAY + 1;
            return now + untilRollover + (days - 1) * DAY;
        }
        int upper = secs + (int) Math.floor(Math.min(secs * 0.25, 300.0));
        if (secs >= upper) return now + secs;
        return now + secs + (long) Math.floor(fuzz * (upper - secs));
    }

    /** A review card's current interval, in days. */
    private static int scheduledDays(Card card) {
        long last = card.getLastReview().getEpochSecond();
        return (int) jsRound((card.getDue().getEpochSecond() - last) / (double) DAY);
    }

    /* --- fsrs4anki_scheduler.js --- */

    public static double constrainDifficulty(double difficulty) {
        return Math.min(Math.max(round2(difficulty), 1), 10);
    }

    /** apply_fuzz; `floor` is the review card's current interval, or -1. */
    private static double applyFuzz(double ivl, double fuzz, int floor) {
        if (ivl < 2.5) return ivl;
        ivl = jsRound(ivl);
        double minIvl = Math.max(2, jsRound(ivl * 0.95 - 1));
        double maxIvl = jsRound(ivl * 1.05 + 1);
        if (floor >= 0 && ivl > floor) {
            minIvl = Math.max(minIvl, floor + 1);
        }
        return Math.floor(fuzz * (maxIvl - minIvl + 1) + minIvl);
    }

    public static double forgettingCurve(double elapsedDays, double stability) {
        return Math.pow(1 + FACTOR * elapsedDays / stability, DECAY);
    }

    public static int nextInterval(double stability, double fuzz, int floor) {
        double newInterval = applyFuzz(
            stability / FACTOR * (Math.pow(REQUEST_RETENTION, 1 / DECAY) - 1), fuzz, floor);
        return (int) Math.min(Math.max(jsRound(newInterval), 1), MAXIMUM_INTERVAL);
    }

    private static double linearDamping(double deltaD, double oldD) {
        return deltaD * (10 - oldD) / 9;
    }

    public static double nextDifficulty(double d, int rating) {
        double deltaD = -W[6] * (rating - 3);
        double nextD = d + linearDamping(deltaD, d);
        return constrainDifficulty(meanReversion(initDifficulty(4), nextD));
    }

    private static double meanReversion(double init, double current) {
        return W[7] * init + (1 - W[7]) * current;
    }

    public static double nextRecallStability(double d, double s, double r, int rating) {
        double hardPenalty = rating == 2 ? W[15] : 1;
        double easyBonus = rating == 4 ? W[16] : 1;
        return round2(s * (1 + Math.exp(W[8])
            * (11 - d)
            * Math.pow(s, -W[9])
            * (Math.exp((1 - r) * W[10]) - 1)
            * hardPenalty
            * easyBonus));
    }

    public static double nextForgetStability(double d, double s, double r) {
        double sMin = s / Math.exp(W[17] * W[18]);
        return round2(Math.min(W[11]
            * Math.pow(d, -W[12])
            * (Math.pow(s + 1, W[13]) - 1)
            * Math.exp((1 - r) * W[14]), sMin));
    }

    public static double shortTermStability(double s, int rating) {
        double sinc = Math.exp(W[17] * (rating - 3 + W[18])) * Math.pow(s, -W[19]);
        if (rating >= 3) {
            sinc = Math.max(sinc, 1);
        }
        return round2(s * sinc);
    }

    public static double initDifficulty(int rating) {
        return round2(constrainDifficulty(W[4] - Math.exp(W[5] * (rating - 1)) + 1));
    }

    public static double initStability(int rating) {
        return round2(Math.max(W[rating - 1], 0.1));
    }

    /** JavaScript's +x.toFixed(2): the exact value, to two decimals, halves away from zero. */
    public static double round2(double x) {
        return new BigDecimal(x).setScale(2, RoundingMode.HALF_UP).unscaledValue().longValue()
            / 100.0;
    }

    /** JavaScript's Math.round: the nearest whole number, halves up. */
    public static double jsRound(double x) {
        double t = Math.floor(x);
        return x - t >= 0.5 ? t + 1 : t;
    }
}
