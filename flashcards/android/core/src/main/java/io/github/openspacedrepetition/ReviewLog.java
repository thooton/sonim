/* (C)2025 open-spaced-repetition (MIT). Vendored from the java-fsrs library,
 * de-Lombok'd: https://github.com/open-spaced-repetition/java-fsrs */
package io.github.openspacedrepetition;

import java.time.Instant;

/** One review event: which card, what rating, when, and for how long. */
public final class ReviewLog {

    private final int cardId;
    private final Rating rating;
    private final Instant reviewDatetime;
    private final Integer reviewDuration;

    public ReviewLog(int cardId, Rating rating, Instant reviewDatetime, Integer reviewDuration) {
        this.cardId = cardId;
        this.rating = rating;
        this.reviewDatetime = reviewDatetime;
        this.reviewDuration = reviewDuration;
    }

    public ReviewLog(ReviewLog otherReviewLog) {
        this(
                otherReviewLog.cardId,
                otherReviewLog.rating,
                otherReviewLog.reviewDatetime,
                otherReviewLog.reviewDuration);
    }

    public int getCardId() {
        return cardId;
    }

    public Rating getRating() {
        return rating;
    }

    public Instant getReviewDatetime() {
        return reviewDatetime;
    }

    public Integer getReviewDuration() {
        return reviewDuration;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReviewLog)) return false;
        ReviewLog other = (ReviewLog) o;
        if (cardId != other.cardId) return false;
        if (rating != other.rating) return false;
        if (reviewDatetime != null
                ? !reviewDatetime.equals(other.reviewDatetime)
                : other.reviewDatetime != null) return false;
        return reviewDuration != null
                ? reviewDuration.equals(other.reviewDuration)
                : other.reviewDuration == null;
    }

    @Override
    public int hashCode() {
        int result = cardId;
        result = 31 * result + (rating != null ? rating.hashCode() : 0);
        result = 31 * result + (reviewDatetime != null ? reviewDatetime.hashCode() : 0);
        result = 31 * result + (reviewDuration != null ? reviewDuration.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ReviewLog(cardId=" + cardId + ", rating=" + rating + ", reviewDatetime="
                + reviewDatetime + ", reviewDuration=" + reviewDuration + ")";
    }
}