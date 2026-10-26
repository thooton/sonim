package com.flashcards.xp3.algo;

import com.flashcards.xp3.data.Collection;
import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
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
 * - then new cards, in deck order: on a deck set to AUTO, while the mean
 *   predicted recall of every card already studied is above 85%, with no
 *   daily limit (when recall sinks below the target, only the old cards
 *   come up until it recovers); on a deck with a number, that many a day,
 *   as Anki does, counting every card first answered since the rollover on
 *   any device,
 * - then learning cards due within the 20-minute learn-ahead limit,
 * - then nothing: the deck is done for now; unless reviewing ahead (Anki's
 *   "Review ahead"), which goes on to studied cards not yet due, the ones
 *   most likely forgotten first (lowest predicted recall, then earliest due,
 *   then deck order), never the card just answered twice running.
 *
 * Every card, new or not, is shown front first and answered 1..4. FSRS-6
 * (desired retention 0.9) sets each next due date; the fuzz is seeded by the
 * card and its last review, as Anki's is, so {@link #preview} shows exactly
 * the interval a rating will give.
 */
public final class ReviewSession {

    /** New cards come in while the studied cards' mean recall is above this. */
    public static final double TARGET = 0.85;
    public static final long LEARN_AHEAD = 20L * 60L;
    public static final int ROLLOVER_HOUR = 4;

    private final Deck deck;
    private final ZoneId zone;
    private DeckCard current;
    private boolean ahead;
    private DeckCard last;

    public ReviewSession(Deck deck, ZoneId zone) {
        this.deck = deck;
        this.zone = zone;
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
        if (fresh != null && !wantsNew(now)) fresh = null;

        if (learning != null && dueOf(learning) <= now) current = learning;
        else if (review != null) current = review;
        else if (fresh != null) current = fresh;
        else if (learning != null && dueOf(learning) <= now + LEARN_AHEAD) current = learning;
        else current = ahead ? earliest(now) : null;
        return current;
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
            double recall = card.probability(now);
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
        if (card.isNew()) card.intro = now;
        card.fsrs = answer(card.fsrs, rating, now);
        Collection.touch(card, now * 1000L);
        last = card;
        current = null;
    }

    public Deck deck() {
        return deck;
    }

    /** What a rating would make of the card: its new state and due date. */
    public static Card preview(Card card, int rating, long now) {
        return answer(card, rating, now);
    }

    /** The mean predicted recall of every card studied so far (1 when none). */
    public double recall(long now) {
        double total = 0.0;
        int studied = 0;
        for (DeckCard card : deck.cards()) {
            if (card.isNew()) continue;
            total += card.probability(now);
            studied++;
        }
        return studied == 0 ? 1.0 : total / studied;
    }

    /**
     * Whether a new card may come in: on AUTO, whether recall is high
     * enough; otherwise whether today's limit has room.
     */
    public boolean wantsNew(long now) {
        if (deck.isAuto()) return recall(now) > TARGET;
        return newLeft(now) > 0;
    }

    /** How many more new cards today's limit allows (AUTO: no limit). */
    public int newLeft(long now) {
        if (deck.isAuto()) return Integer.MAX_VALUE;
        long dayStart = dayEnd(now) - 86400L;
        int today = 0;
        for (DeckCard card : deck.cards()) {
            if (!card.isNew() && card.intro >= dayStart) today++;
        }
        return Math.max(0, deck.newPerDay - today);
    }

    /**
     * Anki's counts for the deck right now: new cards (while recall lets them
     * in), learning cards due within learn-ahead, and reviews due today.
     */
    public int[] counts(long now) {
        long dayEnd = dayEnd(now);
        int unseen = 0;
        int learning = 0;
        int reviews = 0;
        for (DeckCard card : deck.cards()) {
            if (card.isNew()) {
                unseen++;
                continue;
            }
            long due = dueOf(card);
            if (isLearning(card)) {
                if (due <= now + LEARN_AHEAD) learning++;
            } else if (due < dayEnd) {
                reviews++;
            }
        }
        int fresh = wantsNew(now) ? Math.min(unseen, newLeft(now)) : 0;
        return new int[]{fresh, learning, reviews};
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

    private static Card answer(Card card, int rating, long now) {
        if (rating < 1 || rating > 4) {
            throw new IllegalArgumentException("rating must be between 1 and 4");
        }
        long last = card.getLastReview() == null ? 0L : card.getLastReview().getEpochSecond();
        Scheduler fsrs = Scheduler.builder()
            .randomSeedNumber(card.getCardId() * 31 + Long.hashCode(last))
            .build();
        return fsrs.reviewCard(
                card, Rating.values()[rating - 1], Instant.ofEpochSecond(Math.max(now, last)))
            .getCard();
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
