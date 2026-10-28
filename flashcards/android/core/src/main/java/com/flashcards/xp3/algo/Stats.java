package com.flashcards.xp3.algo;

import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;

import io.github.openspacedrepetition.State;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Anki's Statistics window, cut to what the cards' own state can tell (the
 * app keeps no review log): Today, Future Due, Card Counts, Review Intervals
 * and Card Retrievability. The tablet's `stats.go` is the same arithmetic;
 * change them together.
 */
public final class Stats {

    /** Card Counts, in Anki's order. */
    public static final String[] COUNTS = {"New", "Learning", "Relearning", "Young", "Mature"};
    public static final int NEW = 0, LEARNING = 1, RELEARNING = 2, YOUNG = 3, MATURE = 4;
    /** A review card is mature from this interval on, as in Anki. */
    public static final int MATURE_DAYS = 21;
    /** Future Due covers today and the 29 days after it. */
    public static final int DUE_DAYS = 30;
    /** Review Intervals' bins: each holds intervals up to its bound, in days; the last, the rest. */
    public static final int[] INTERVAL_BOUNDS = {1, 3, 7, 14, 30, 90, 180, 365};
    public static final String[] INTERVAL_LABELS = {"1d", "3d", "1w", "2w", "1m", "3m", "6m", "1y", "1y+"};
    /** Card Retrievability's bins: 0-10%, 10-20% ... 90-100%. */
    public static final int RECALL_BINS = 10;

    public int total;
    /** Cards answered today, and of those how many for the first time. */
    public int studied;
    public int introduced;
    public final int[] counts = new int[COUNTS.length];
    /** Cards due on each day from today; today's includes what is overdue. */
    public final int[] due = new int[DUE_DAYS];
    public final int[] intervals = new int[INTERVAL_LABELS.length];
    /** Over review cards, in days; 0 when there are none. */
    public double averageInterval;
    public int longestInterval;
    public final int[] recall = new int[RECALL_BINS];
    /** Over studied cards; 0 when there are none. */
    public double averageRecall;
    /** The sum of every studied card's retrievability: how many it would recall now. */
    public double knowledge;

    private Stats() {}

    public static Stats of(Iterable<DeckCard> cards, long now, ZoneId zone) {
        LocalDate day = Instant.ofEpochSecond(now).atZone(zone)
            .minusHours(ReviewSession.ROLLOVER_HOUR).toLocalDate();
        long dayStart = ZonedDateTime.of(day.atTime(ReviewSession.ROLLOVER_HOUR, 0), zone).toEpochSecond();
        long dayEnd = ZonedDateTime.of(day.plusDays(1).atTime(ReviewSession.ROLLOVER_HOUR, 0), zone).toEpochSecond();

        Stats s = new Stats();
        long intervalSum = 0;
        int reviewCards = 0;
        int seen = 0;
        for (DeckCard card : cards) {
            s.total++;
            if (card.isNew()) {
                s.counts[NEW]++;
                continue;
            }
            seen++;
            long last = card.fsrs.getLastReview().getEpochSecond();
            long due = card.fsrs.getDue().getEpochSecond();
            if (last >= dayStart) s.studied++;
            if (card.intro >= dayStart) s.introduced++;

            State state = card.fsrs.getState();
            if (state == State.LEARNING) {
                s.counts[LEARNING]++;
            } else if (state == State.RELEARNING) {
                s.counts[RELEARNING]++;
            } else {
                int ivl = intervalDays(last, due);
                s.counts[ivl >= MATURE_DAYS ? MATURE : YOUNG]++;
                s.intervals[intervalBin(ivl)]++;
                intervalSum += ivl;
                s.longestInterval = Math.max(s.longestInterval, ivl);
                reviewCards++;
            }

            long at = due < dayEnd ? 0 : 1 + (due - dayEnd) / 86400L;
            if (at < DUE_DAYS) s.due[(int) at]++;

            long lastDay = Instant.ofEpochSecond(last).atZone(zone)
                .minusHours(ReviewSession.ROLLOVER_HOUR).toLocalDate().toEpochDay();
            int elapsed = (int) Math.max(0, day.toEpochDay() - lastDay);
            double r = Math.max(0, Math.min(1, Fsrs.retrievability(card.fsrs, elapsed)));
            s.recall[Math.min(RECALL_BINS - 1, (int) Math.floor(r * RECALL_BINS))]++;
            s.knowledge += r;
        }
        if (reviewCards > 0) s.averageInterval = (double) intervalSum / reviewCards;
        if (seen > 0) s.averageRecall = s.knowledge / seen;
        return s;
    }

    /** All of a collection's cards, deck by deck. */
    public static Stats ofDecks(List<Deck> decks, long now, ZoneId zone) {
        List<DeckCard> all = new ArrayList<>();
        for (Deck deck : decks) all.addAll(deck.cards());
        return of(all, now, zone);
    }

    /** A review card's interval, in whole days (Anki's ivl). */
    static int intervalDays(long last, long due) {
        return (int) Math.floor((due - last) / 86400.0 + 0.5);
    }

    static int intervalBin(int days) {
        for (int i = 0; i < INTERVAL_BOUNDS.length; i++) {
            if (days <= INTERVAL_BOUNDS[i]) return i;
        }
        return INTERVAL_BOUNDS.length;
    }

    public int dueTotal() {
        int sum = 0;
        for (int n : due) sum += n;
        return sum;
    }

    /** A share of all cards: "12.5%". */
    public String percent(int count) {
        return total == 0 ? "0%" : String.format(Locale.US, "%.1f%%", 100.0 * count / total);
    }

    /** A length of time as Anki's statistics put it: "1 day", "13.9 days", "1.4 months", "2.1 years". */
    public static String span(double days) {
        if (days < 30) return amount(days, "day");
        if (days < 365) return amount(days / 30, "month");
        return amount(days / 365, "year");
    }

    /** "1 day", "2 days", "13.9 days". */
    static String amount(double n, String unit) {
        double rounded = Math.round(n * 10) / 10.0;
        String figure = rounded == Math.rint(rounded)
            ? Long.toString((long) rounded)
            : String.format(Locale.US, "%.1f", rounded);
        return figure + " " + unit + (rounded == 1 ? "" : "s");
    }

    /** One decimal, as Anki's footers give averages: "12.3". */
    public static String decimal(double n) {
        double rounded = Math.round(n * 10) / 10.0;
        return rounded == Math.rint(rounded)
            ? Long.toString((long) rounded)
            : String.format(Locale.US, "%.1f", rounded);
    }
}
