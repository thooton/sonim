package com.flashcards.xp3.algo;

import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;

import io.github.openspacedrepetition.Rating;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The review session for one deck.
 *
 * Owns the current 10-card group and the deck it draws on. Serves the group
 * one card at a time, applies a 1..4 rating to the current card, and when the
 * group is spent builds the next one from the deck's updated state — which is
 * what makes the targeting "live": every group is sized and mixed at 85% from
 * the probabilities as they are right after the previous group changed them.
 * New cards are auto-added by the same arithmetic (see {@link GroupBuilder}),
 * never by a fixed daily quota and never by user choice.
 */
public final class Scheduler {

    private final Deck deck;
    private final Random rng;
    private long selectionTime;
    private final boolean liveClock;
    private final List<DeckCard> group;
    private final io.github.openspacedrepetition.Scheduler fsrs;
    private int index;
    private int groupNumber;

    private Scheduler(Deck deck, long baseTime, Random rng, boolean liveClock) {
        this.deck = deck;
        this.rng = rng;
        this.selectionTime = baseTime;
        this.liveClock = liveClock;
        this.group = new ArrayList<>();
        this.index = 0;
        this.fsrs = Fsrs.defaults();
    }

    /** Deterministic-time constructor for tests and offline simulations. */
    public Scheduler(Deck deck, long baseTime, Random rng) {
        this(deck, baseTime, rng, false);
    }

    public Scheduler(Deck deck) {
        this(deck, System.currentTimeMillis() / 1000L, new SecureRandom(), true);
    }

    public boolean hasCards() {
        return deck.size() > 0;
    }

    /**
     * Whether the current group still has a card to show. A freshly built
     * session has an empty group, so this is false until {@link #next()} is
     * first called.
     */
    public boolean hasCurrent() {
        return index < group.size();
    }

    /** The current card, or null when the group is exhausted. */
    public DeckCard current() {
        return hasCurrent() ? group.get(index) : null;
    }

    /**
     * Advances to the next card, building a fresh group if the current one is
     * spent. Returns the next card to show, or null when the deck is empty or
     * every reviewed card is currently above the 90% eligibility ceiling.
     */
    public DeckCard next() {
        if (index < group.size()) {
            return group.get(index);
        }
        rebuild();
        return hasCurrent() ? group.get(index) : null;
    }

    /** Rating of the current card (1..4) on the card shown before `next()`. */
    public void rate(int rating, long now) {
        DeckCard card = current();
        if (card == null) return;
        if (rating < 1 || rating > 4) {
            throw new IllegalArgumentException("rating must be between 1 and 4");
        }
        long effectiveNow = Math.max(now, selectionTime);
        if (card.fsrs.getLastReview() != null) {
            effectiveNow = Math.max(effectiveNow, card.fsrs.getLastReview().getEpochSecond());
        }
        card.fsrs =
            fsrs.reviewCard(
                    card.fsrs, Rating.values()[rating - 1], Instant.ofEpochSecond(effectiveNow))
                .getCard();
        selectionTime = Math.max(selectionTime, effectiveNow);
        index++;
    }

    /** The group built for the current pass, in serve order. */
    public List<DeckCard> group() {
        return group;
    }

    /** How many cards remain in the current group (unserved). */
    public int remaining() {
        return group.size() - index;
    }

    /** The ordinal of the group currently served (1 for the first). */
    public int groupNumber() {
        return groupNumber;
    }

    private void rebuild() {
        group.clear();
        index = 0;
        long observedNow = liveClock ? System.currentTimeMillis() / 1000L : selectionTime;
        long now = Math.max(selectionTime, observedNow);
        selectionTime = now;

        List<DeckCard> fresh = new GroupBuilder(deck.cards(), now, rng).build();
        group.addAll(fresh);
        groupNumber++;
    }
}
