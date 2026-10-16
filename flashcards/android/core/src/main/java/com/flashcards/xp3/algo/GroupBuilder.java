package com.flashcards.xp3.algo;

import com.flashcards.xp3.data.DeckCard;

import io.github.openspacedrepetition.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Selects the 10-card group to serve next, targeted at 85% predicted
 * accuracy. This is the direct port of the review-hole fill in
 * ../mimi_backend/src/lesson.rs (the "Builder" struct), which itself follows
 * "The Eighty-Five Percent Rule for Optimal Learning" from Wu et al.
 *
 * A flashcard differs from a mimi sentence in one simplifying way: a mimi
 * sentence may grade several words, and taking it claims all of them; a card
 * is atomic and independent, with a single success probability. So there is no
 * "mode", no "overlap", no "claim" here — a card is either in the group or
 * not, and each carries one probability. Everything else survives unchanged.
 *
 * Only reviewed cards participate in the 85% calculation. At most one unseen
 * card is added separately when the reviewed deck's mean retrievability has
 * risen above the target (or there is no review history yet), and only while
 * no introduced card remains in learning or relearning.
 */
public final class GroupBuilder {

    /** How many cards go in one group. */
    public static final int GROUP_SIZE = 10;
    /** The predicted accuracy we schedule toward. */
    public static final double TARGET = 0.85;
    /** Reviewed cards above this recall probability are not shown yet. */
    public static final double MAX_RETRIEVABILITY = 0.90;
    /**
     * While filling, if the remaining cards would need an average success
     * probability above this, stop taking urgent (hard) cards.
     */
    public static final double MAX_NEEDED = 0.925;
    /** Always take at least this many urgent cards, even if it makes it hard. */
    public static final int MIN_URGENT = 2;
    private final Random rng;
    private final List<DeckCard> pool;
    private final long timestamp;
    private final DeckCard newCard;
    // what we have chosen, in the order they are to be served after `order()`
    private final List<DeckCard> picks;
    // the summed success probability of `picks`, for the running average
    private double total;
    private final int holes;

    public GroupBuilder(List<DeckCard> pool, long timestamp, Random rng) {
        this.rng = rng;
        this.timestamp = timestamp;
        this.pool = new ArrayList<>();
        DeckCard chosenNew = null;
        int unseen = 0;
        int reviewed = 0;
        double reviewedTotal = 0.0;
        boolean hasLearningCard = false;
        for (DeckCard card : pool) {
            if (card.isNew()) {
                unseen++;
                if (rng.nextInt(unseen) == 0) chosenNew = card;
                continue;
            }
            double probability = probability(card);
            reviewed++;
            reviewedTotal += probability;
            State state = card.fsrs.getState();
            if (state == State.LEARNING || state == State.RELEARNING) {
                hasLearningCard = true;
            }
            if (probability <= MAX_RETRIEVABILITY) this.pool.add(card);
        }
        boolean deckIsTooEasy =
            reviewed == 0 || reviewedTotal / (double) reviewed > TARGET;
        this.newCard = !hasLearningCard && deckIsTooEasy ? chosenNew : null;
        this.picks = new ArrayList<>();
        this.total = 0.0;
        int reviewSlots = GROUP_SIZE - (newCard == null ? 0 : 1);
        this.holes = Math.min(reviewSlots, this.pool.size());
    }

    /**
     * Builds the next group: urgent picks first, then top-up, then ordering.
     * Returns the group, or an empty list when there is nothing to study.
     */
    public List<DeckCard> build() {
        if (holes > 0) {
            takeUrgent();
            topUp();
            order();
        }
        if (newCard != null) {
            picks.add(rng.nextInt(picks.size() + 1), newCard);
        }
        return picks;
    }

    /** A card's predicted success probability right now. */
    private double probability(DeckCard card) {
        return card.probability(timestamp);
    }

    /** Given what is in so far, what average the rest needs to hit TARGET. */
    private double neededAverage() {
        double slots = (double) holes;
        return (TARGET * slots - total) / (slots - (double) picks.size());
    }

    /**
     * Phase 1: take the most urgent cards first — the ones with the lowest
     * success probability. We always take at least MIN_URGENT of them (that
     * is what reviews the cards most at risk of being forgotten), and keep
     * taking them while the remaining average stays within MAX_NEEDED; past
     * that, a run of very hard cards would only hurt, so we stop and let the
     * top-up bring the average back toward the target.
     */
    private void takeUrgent() {
        List<DeckCard> urgent = new ArrayList<>(pool);
        // Preserve urgency while giving exact probability ties a fair order.
        Collections.shuffle(urgent, rng);
        Collections.sort(urgent, (a, b) -> Double.compare(probability(a), probability(b)));
        for (DeckCard card : urgent) {
            if (picks.size() >= holes) break;
            if (picks.size() >= MIN_URGENT && neededAverage() > MAX_NEEDED) {
                break;
            }
            take(card);
        }
    }

    /**
     * Phase 2: fill the rest with the cards that bring the average success
     * probability closest to the target. Repeatedly we work out the average
     * the remaining slots need to land on 85%, cap it at 92.5%, and take the
     * not-yet-picked card whose probability is closest to that number.
     */
    private void topUp() {
        List<DeckCard> remaining = new ArrayList<>(pool);
        // sort by probability; a stable sort plus a pre-shuffle makes the
        // exact ties land at random rather than by pool order
        Collections.shuffle(remaining, rng);
        Collections.sort(remaining, (a, b) -> Double.compare(probability(a), probability(b)));
        while (picks.size() < holes) {
            double needed = Math.min(neededAverage(), MAX_NEEDED);
            // `needed` splits remaining into two queues: those below it,
            // nearest one last, and those above it, nearest one first, so the
            // closest card is at the head of one of the two. `lo` is the head
            // of the low queue, `hi` of the high queue; both start at the
            // split and walk outward as we reject candidates that repeat a
            // card already picked.
            int lo = 0;
            while (lo < remaining.size() && probability(remaining.get(lo)) < needed) lo++;
            int hi = lo;
            int chosenIdx = -1;
            while (true) {
                boolean leftExists = lo > 0;
                boolean rightExists = hi < remaining.size();
                if (!leftExists && !rightExists) break; // exhausted
                boolean takeLeft;
                if (leftExists && rightExists) {
                    double pl = probability(remaining.get(lo - 1));
                    double pr = probability(remaining.get(hi));
                    takeLeft = (needed - pl) <= (pr - needed);
                } else {
                    takeLeft = leftExists;
                }
                if (takeLeft) {
                    lo--;
                    if (!picks.contains(remaining.get(lo))) { chosenIdx = lo; break; }
                } else {
                    hi++;
                    if (!picks.contains(remaining.get(hi - 1))) { chosenIdx = hi - 1; break; }
                }
            }
            if (chosenIdx < 0) break; // nothing left that isn't already picked
            DeckCard chosen = remaining.get(chosenIdx);
            // drain the contiguous explored block [lo, hi): the cards walked
            // past on either side together with the chosen one, exactly as
            // mimi's candidates.drain(lo..hi) does. The chosen card is in the
            // block (either at lo after a left move, or at hi-1 after a right
            // move), so it is removed along with the ones we passed over.
            List<DeckCard> kept = new ArrayList<>();
            for (int i = 0; i < remaining.size(); i++) {
                if (i < lo || i >= hi) kept.add(remaining.get(i));
            }
            remaining = kept;
            take(chosen);
        }
    }

    private void take(DeckCard card) {
        total += probability(card);
        picks.add(card);
    }

    /**
     * Ordering: easiest card first (a warm-up), second easiest last (to end on
     * a high note), and everything in between shuffled. Only serves when there
     * are enough cards; a short or empty group is left in place.
     */
    private void order() {
        if (picks.size() <= 3) return;
        Collections.sort(picks, (a, b) -> Double.compare(probability(b), probability(a)));
        DeckCard opener = picks.remove(0);
        DeckCard closer = picks.remove(0);
        List<DeckCard> middle = new ArrayList<>(picks);
        Collections.shuffle(middle, rng);
        picks.clear();
        picks.add(opener);
        picks.addAll(middle);
        picks.add(closer);
    }
}
