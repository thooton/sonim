/* (C)2025 open-spaced-repetition (MIT). Vendored from the java-fsrs library,
 * de-Lombok'd: https://github.com/open-spaced-repetition/java-fsrs */
package io.github.openspacedrepetition;

/** The result of a single review: the updated card and its review log. */
public final class CardAndReviewLog {

    private final Card card;
    private final ReviewLog reviewLog;

    public CardAndReviewLog(Card card, ReviewLog reviewLog) {
        this.card = card;
        this.reviewLog = reviewLog;
    }

    public Card getCard() {
        return card;
    }

    public ReviewLog getReviewLog() {
        return reviewLog;
    }

    /** Record-style accessor, matching the released jar's API. */
    public Card card() {
        return card;
    }

    /** Record-style accessor, matching the released jar's API. */
    public ReviewLog reviewLog() {
        return reviewLog;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CardAndReviewLog)) return false;
        CardAndReviewLog other = (CardAndReviewLog) o;
        if (card != null ? !card.equals(other.card) : other.card != null) return false;
        return reviewLog != null
                ? reviewLog.equals(other.reviewLog)
                : other.reviewLog == null;
    }

    @Override
    public int hashCode() {
        int result = card != null ? card.hashCode() : 0;
        result = 31 * result + (reviewLog != null ? reviewLog.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "CardAndReviewLog(card=" + card + ", reviewLog=" + reviewLog + ")";
    }
}