package com.flashcards.xp3.algo;

import com.flashcards.xp3.data.Collection;
import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * One deck's study session, queued the way Anki's v3 scheduler does with FSRS
 * on and its default deck options, except for how new cards come in:
 *
 * - learning and relearning cards come first once due (steps 1m 10m, 10m),
 * - then the day's reviews, due before the next 4am rollover, oldest first,
 *   with new cards mixed in among them, spread evenly, as Anki's default
 *   "Mix with reviews" does; new cards come in deck order, and how many:
 *   on a deck with a number, that many a day, as Anki does, counting every
 *   card first answered since the rollover on any device; on AUTO, enough
 *   to bring New + Learning + Due up to {@link #AUTO_TARGET}, so none while
 *   Learning + Due is at it or over. That is the only change from Anki, and
 *   it only decides when a new card is introduced: once in, a card learns,
 *   relearns and is reviewed exactly as Anki's would. As reviews are
 *   answered, new cards come in to take their place,
 * - then learning cards due within the 20-minute learn-ahead limit,
 * - then nothing: the deck is done for now; unless reviewing ahead (Anki's
 *   "Review ahead"), which goes on to studied cards not yet due, the ones
 *   most likely forgotten first (lowest predicted recall, then earliest due,
 *   then deck order), never the card just answered twice running.
 *
 * Every card, new or not, is shown front first and answered 1..4. {@link
 * Fsrs} (Anki with the FSRS4Anki scheduler) sets each next due date, counting
 * days as Anki does, from 4am to 4am; its fuzz is seeded by the card and its
 * last review, so {@link #preview} shows exactly the interval a rating will
 * give.
 */
public final class ReviewSession {

    /** On AUTO, New + Learning + Due is kept up to this (New fills the gap). */
    public static final int AUTO_TARGET = 20;
    public static final long LEARN_AHEAD = 20L * 60L;
    public static final int ROLLOVER_HOUR = 4;

    private final Deck deck;
    private final ZoneId zone;
    private DeckCard current;
    private boolean ahead;
    private DeckCard last;
    /** New cards and reviews shown this session, to spread one among the other. */
    private int newShown;
    private int reviewsShown;

    public ReviewSession(Deck deck, ZoneId zone) {
        this.deck = deck;
        this.zone = zone;
    }

    /** How far through the deck: 100% minus the share of its cards still New. */
    public int progress() {
        int size = deck.size();
        if (size == 0) return 100;
        int fresh = 0;
        for (DeckCard card : deck.cards()) if (card.isNew()) fresh++;
        return (int) ((size - fresh) * 100L / size);
    }

    public boolean hasCards() {
        return deck.size() > 0;
    }

    /** The card being shown, or null when the deck is done for now. */
    public DeckCard current() {
        return current;
    }

    /** Picks the next card to show at `now`, or null when there is none. */
    public DeckCard next(long now) {
        long dayEnd = dayEnd(now);
        DeckCard learning = null;
        DeckCard review = null;
        DeckCard fresh = null;
        for (DeckCard card : deck.cards()) {
            if (card.isNew()) {
                if (fresh == null) fresh = card;
                continue;
            }
            long due = dueOf(card);
            if (isLearning(card)) {
                if (due < dayEnd && (learning == null || due < dueOf(learning))) learning = card;
            } else if (due < dayEnd && (review == null || due < dueOf(review))) {
                review = card;
            }
        }
        int[] counts = queued(now);
        if (counts[0] == 0) fresh = null;

        if (learning != null && dueOf(learning) <= now) current = learning;
        else if (review != null && fresh != null) current = newTurn(counts[0], counts[2]) ? fresh : review;
        else if (review != null) current = review;
        else if (fresh != null) current = fresh;
        else if (learning != null && dueOf(learning) <= now + LEARN_AHEAD) current = learning;
        else current = ahead ? earliest(now) : null;
        return current;
    }

    /**
     * Whether a new card's turn has come among the reviews: so far this
     * session, new cards have had less than their share of what is left, as
     * Anki's intersperser spreads them. A review goes first.
     */
    private boolean newTurn(int fresh, int reviews) {
        return (long) newShown * reviews < (long) reviewsShown * fresh;
    }

    /** Whether, once nothing is due, cards not yet due come up (Review Ahead). */
    public void setAhead(boolean ahead) {
        this.ahead = ahead;
        if (!ahead) last = null;
    }

    public boolean ahead() {
        return ahead;
    }

    /** The studied card most likely forgotten, other than the one just answered. */
    private DeckCard earliest(long now) {
        DeckCard best = null;
        double bestRecall = 0;
        for (DeckCard card : deck.cards()) {
            if (card.isNew() || card == last) continue;
            double recall = recall(card.fsrs, now);
            if (best == null || recall < bestRecall
                || recall == bestRecall && dueOf(card) < dueOf(best)) {
                best = card;
                bestRecall = recall;
            }
        }
        return best;
    }

    /** Answers the current card (1 Again, 2 Hard, 3 Good, 4 Easy). */
    public void rate(int rating, long now) {
        DeckCard card = current;
        if (card == null) return;
        if (card.isNew()) {
            card.intro = now;
            newShown++;
        } else if (!isLearning(card)) {
            reviewsShown++;
        }
        card.fsrs = preview(card.fsrs, rating, now);
        Collection.touch(card, now * 1000L);
        last = card;
        current = null;
    }

    public Deck deck() {
        return deck;
    }

    /** What a rating would make of the card: its new state and due date. */
    public Card preview(Card card, int rating, long now) {
        long last = card.getLastReview() == null ? now : card.getLastReview().getEpochSecond();
        now = Math.max(now, last);
        return Fsrs.review(card, rating, now, daysSince(last, now), dayEnd(now) - now);
    }

    /** The probability the card is recalled at `now`, per FSRS (0 when unseen). */
    public double recall(Card card, long now) {
        if (card.getLastReview() == null) return 0;
        return Fsrs.retrievability(card, daysSince(card.getLastReview().getEpochSecond(), now));
    }

    /** Whether a new card may come in. */
    public boolean wantsNew(long now) {
        return newLeft(now) > 0;
    }

    /**
     * How many more new cards may come in: today's limit, less those already
     * introduced; on AUTO, what brings New + Learning + Due up to the target.
     */
    public int newLeft(long now) {
        if (deck.isAuto()) {
            int[] old = oldCounts(now);
            return Math.max(0, AUTO_TARGET - old[0] - old[1]);
        }
        long dayStart = dayEnd(now) - 86400L;
        int today = 0;
        for (DeckCard card : deck.cards()) {
            if (!card.isNew() && card.intro >= dayStart) today++;
        }
        return Math.max(0, deck.newPerDay - today);
    }

    /**
     * Anki's counts for the deck right now: new cards (as many as may come
     * in), learning cards due within learn-ahead, and reviews due today.
     * Reviewing ahead, the reviews are every other studied card, as Anki's
     * filtered deck counts the cards it holds.
     */
    public int[] counts(long now) {
        int[] counts = queued(now);
        if (ahead) {
            int studied = 0;
            for (DeckCard card : deck.cards()) {
                if (!card.isNew()) studied++;
            }
            counts[2] = studied - counts[1];
        }
        return counts;
    }

    /** The counts as the queue is drawn from: what is due, never ahead. */
    private int[] queued(long now) {
        int unseen = 0;
        for (DeckCard card : deck.cards()) {
            if (card.isNew()) unseen++;
        }
        int[] old = oldCounts(now);
        return new int[]{Math.min(unseen, newLeft(now)), old[0], old[1]};
    }

    /** Learning cards due within learn-ahead, and reviews due today. */
    private int[] oldCounts(long now) {
        long dayEnd = dayEnd(now);
        int learning = 0;
        int reviews = 0;
        for (DeckCard card : deck.cards()) {
            if (card.isNew()) continue;
            long due = dueOf(card);
            if (isLearning(card)) {
                if (due <= now + LEARN_AHEAD) learning++;
            } else if (due < dayEnd) {
                reviews++;
            }
        }
        return new int[]{learning, reviews};
    }

    /** When the next learning card due later today comes up, or -1. */
    public long nextLearningDue(long now) {
        long dayEnd = dayEnd(now);
        long next = -1;
        for (DeckCard card : deck.cards()) {
            if (card.isNew() || !isLearning(card)) continue;
            long due = dueOf(card);
            if (due < dayEnd && (next < 0 || due < next)) next = due;
        }
        return next;
    }

    /** Anki days from `then` to `now`: how many 4am rollovers lie between. */
    private int daysSince(long then, long now) {
        return (int) Math.max(0, day(now) - day(then));
    }

    /** The Anki day `t` falls in, as an epoch day. */
    private long day(long t) {
        return Instant.ofEpochSecond(t).atZone(zone).minusHours(ROLLOVER_HOUR).toLocalDate()
            .toEpochDay();
    }

    /** The end of the Anki day `now` falls in: days run from 4am to 4am. */
    private long dayEnd(long now) {
        LocalDate day = Instant.ofEpochSecond(now).atZone(zone).minusHours(ROLLOVER_HOUR)
            .toLocalDate();
        return ZonedDateTime.of(day.plusDays(1).atTime(ROLLOVER_HOUR, 0), zone).toEpochSecond();
    }

    private static long dueOf(DeckCard card) {
        return card.fsrs.getDue().getEpochSecond();
    }

    private static boolean isLearning(DeckCard card) {
        State state = card.fsrs.getState();
        return state == State.LEARNING || state == State.RELEARNING;
    }
}
