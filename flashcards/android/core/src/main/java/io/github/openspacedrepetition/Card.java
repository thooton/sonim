/* (C)2025 open-spaced-repetition (MIT). Vendored from the java-fsrs library,
 * de-Lombok'd: https://github.com/open-spaced-repetition/java-fsrs */
package io.github.openspacedrepetition;

import java.time.Instant;

/**
 * The FSRS memory state of a single card: its state (learning / review /
 * relearning), the learning-step it is on, its stability and difficulty, and
 * when it is due and was last reviewed. Instances are produced by {@link
 * Scheduler#reviewCard}, which returns a fresh card for each review.
 */
public class Card {

    private final int cardId;

    private State state;
    private Integer step;
    private Double stability;
    private Double difficulty;
    private Instant due;
    private Instant lastReview;

    private Card(Builder builder) {
        this.cardId = builder.cardId;
        this.state = builder.state;
        this.step = builder.step;
        this.stability = builder.stability;
        this.difficulty = builder.difficulty;
        this.due = builder.due;
        this.lastReview = builder.lastReview;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private Integer cardId = null;
        private State state = State.LEARNING;
        private Integer step = null;
        private Double stability = null;
        private Double difficulty = null;
        private Instant due = null;
        private Instant lastReview = null;

        public Builder cardId(Integer cardId) {
            this.cardId = cardId;
            return this;
        }

        public Builder state(State state) {
            this.state = state;
            return this;
        }

        public Builder step(Integer step) {
            this.step = step;
            return this;
        }

        public Builder stability(Double stability) {
            this.stability = stability;
            return this;
        }

        public Builder difficulty(Double difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        public Builder due(Instant due) {
            this.due = due;
            return this;
        }

        public Builder lastReview(Instant lastReview) {
            this.lastReview = lastReview;
            return this;
        }

        public Card build() {

            if (this.cardId == null) {
                this.cardId = (int) Instant.now().toEpochMilli();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restore interrupted status
                }
            }

            if (this.state == State.LEARNING && this.step == null) {
                this.step = 0;
            }

            if (this.due == null) {
                this.due = Instant.now();
            }

            return new Card(this);
        }
    }

    public Card(Card otherCard) {

        this.cardId = otherCard.cardId;
        this.state = otherCard.state;
        this.step = otherCard.step;
        this.stability = otherCard.stability;
        this.difficulty = otherCard.difficulty;
        this.due = otherCard.due;
        this.lastReview = otherCard.lastReview;
    }

    public int getCardId() {
        return cardId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    public Double getStability() {
        return stability;
    }

    public void setStability(Double stability) {
        this.stability = stability;
    }

    public Double getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Double difficulty) {
        this.difficulty = difficulty;
    }

    public Instant getDue() {
        return due;
    }

    public void setDue(Instant due) {
        this.due = due;
    }

    public Instant getLastReview() {
        return lastReview;
    }

    public void setLastReview(Instant lastReview) {
        this.lastReview = lastReview;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Card)) return false;
        Card other = (Card) o;
        if (cardId != other.cardId) return false;
        if (state != other.state) return false;
        if (step != null ? !step.equals(other.step) : other.step != null) return false;
        if (stability != null ? !stability.equals(other.stability) : other.stability != null)
                return false;
        if (difficulty != null ? !difficulty.equals(other.difficulty) : other.difficulty != null)
                return false;
        if (due != null ? !due.equals(other.due) : other.due != null) return false;
        return lastReview != null ? lastReview.equals(other.lastReview) : other.lastReview == null;
    }

    @Override
    public int hashCode() {
        int result = cardId;
        result = 31 * result + (state != null ? state.hashCode() : 0);
        result = 31 * result + (step != null ? step.hashCode() : 0);
        result = 31 * result + (stability != null ? stability.hashCode() : 0);
        result = 31 * result + (difficulty != null ? difficulty.hashCode() : 0);
        result = 31 * result + (due != null ? due.hashCode() : 0);
        result = 31 * result + (lastReview != null ? lastReview.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Card(cardId=" + cardId + ", state=" + state + ", step=" + step
                + ", stability=" + stability + ", difficulty=" + difficulty + ", due=" + due
                + ", lastReview=" + lastReview + ")";
    }
}