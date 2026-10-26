package com.flashcards.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.flashcards.xp3.algo.ReviewSession;
import com.flashcards.xp3.data.DeckCard;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

/**
 * The study screen for one deck.
 *
 * As in Anki: the new, learning and review counts along the top, the card
 * served front-first with the answer hidden, and OK (DPAD_CENTER/ENTER)
 * revealing it — the value and any auxiliary line — together with the four
 * answer keys at the bottom, each with the interval it gives: 1 Again,
 * 2 Hard, 3 Good, 4 Easy. New cards are answered the same way.
 */
public final class ReviewPane extends LinearLayout {

    /** Notified after every rating so the caller can write state to disk. */
    public interface Listener {
        void onRated();
    }

    private final ReviewSession session;
    private final Listener listener;

    private boolean revealed;
    private final TextView statusView;
    private final TextView keyView;
    private final TextView valueView;
    private final TextView auxView;
    private final ScrollView cardScroll;
    private final LinearLayout chipsRow;

    public ReviewPane(Context context, ReviewSession session, Listener listener) {
        super(context);
        this.session = session;
        this.listener = listener;

        setOrientation(VERTICAL);
        int pad = dp(8);
        setPadding(pad, dp(4), pad, dp(4));
        setFocusable(true);
        setFocusableInTouchMode(true);

        statusView = new TextView(context);
        statusView.setTextSize(13);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER);
        addView(
            statusView,
            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        cardScroll = new ScrollView(context);
        cardScroll.setFillViewport(true);
        cardScroll.setVerticalFadingEdgeEnabled(true);
        cardScroll.setFadingEdgeLength(dp(12));
        addView(cardScroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setGravity(Gravity.CENTER);
        cardScroll.addView(
            card,
            new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        keyView = new TextView(context);
        keyView.setTextColor(color(R.color.text));
        keyView.setTextSize(22);
        keyView.setTypeface(Typeface.DEFAULT_BOLD);
        keyView.setGravity(Gravity.CENTER);
        keyView.setMaxLines(3);
        keyView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(keyView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        valueView = new TextView(context);
        valueView.setTextColor(color(R.color.text));
        valueView.setTextSize(18);
        valueView.setGravity(Gravity.CENTER);
        valueView.setMaxLines(4);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setPadding(0, dp(6), 0, 0);
        card.addView(valueView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        auxView = new TextView(context);
        auxView.setTextColor(color(R.color.text_muted));
        auxView.setTextSize(12);
        auxView.setGravity(Gravity.CENTER);
        auxView.setPadding(0, dp(2), 0, 0);
        card.addView(auxView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(HORIZONTAL);
        chipsRow.setPadding(0, dp(6), 0, 0);
        addView(chipsRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        showFirst();
    }

    private final Runnable recheck = this::showFirst;

    /** Serves the next card due, or says the deck is done for now. */
    private void showFirst() {
        removeCallbacks(recheck);
        DeckCard card = session.next(now());
        if (card == null) showIdle();
        else showCard(card);
    }

    private void showCard(DeckCard card) {
        revealed = false;
        cardScroll.scrollTo(0, 0);
        keyView.setTextSize(22);
        keyView.setMaxLines(3);
        showCounts(card);
        keyView.setText(card.def.key);
        keyView.setVisibility(View.VISIBLE);
        valueView.setVisibility(View.GONE);
        auxView.setVisibility(View.GONE);
        hideChips();
    }

    /** Anki's "new + learning + review" counts, the current card's underlined. */
    private void showCounts(DeckCard card) {
        int[] counts = session.counts(now());
        int queue = -1;
        if (card != null) {
            State state = card.fsrs.getState();
            if (card.isNew()) queue = 0;
            else if (state == State.LEARNING || state == State.RELEARNING) queue = 1;
            else queue = 2;
        }
        int[] colors = {R.color.count_new, R.color.count_learn, R.color.count_due};
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) text.append(" + ");
            int start = text.length();
            text.append(Integer.toString(counts[i]));
            text.setSpan(
                new ForegroundColorSpan(color(colors[i])),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i == queue) {
                text.setSpan(new UnderlineSpan(), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        statusView.setTextColor(color(R.color.text_muted));
        statusView.setText(text);
    }

    private void showIdle() {
        revealed = false;
        showCounts(null);
        cardScroll.scrollTo(0, 0);
        keyView.setTextSize(16);
        keyView.setMaxLines(5);
        String text = "This deck is empty.";
        if (session.hasCards()) {
            text = "Congratulations! You have finished this deck for now.";
            long now = now();
            long next = session.nextLearningDue(now);
            if (next > 0) {
                long minutes = Math.max(1, (next - now + 59) / 60);
                text += "\n\nThe next learning card is ready in "
                    + minutes + (minutes == 1 ? " minute." : " minutes.");
                // Learn-ahead serves it 20 minutes early, as Anki does.
                long wait = Math.max(1, next - ReviewSession.LEARN_AHEAD - now);
                postDelayed(recheck, wait * 1000L);
            }
        }
        keyView.setText(text);
        keyView.setVisibility(View.VISIBLE);
        valueView.setVisibility(View.GONE);
        auxView.setVisibility(View.GONE);
        hideChips();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(recheck);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Coming back to the tab finds whatever has fallen due meanwhile.
        if (session.current() == null) showFirst();
    }

    private void reveal() {
        if (revealed) return;
        DeckCard card = session.current();
        if (card == null) return;
        revealed = true;
        showAnswer(card);
        showRatingChips();
    }

    private void showAnswer(DeckCard card) {
        valueView.setText(card.def.value);
        valueView.setVisibility(View.VISIBLE);
        if (card.def.aux != null && card.def.aux.length() > 0) {
            auxView.setText(card.def.aux);
            auxView.setVisibility(View.VISIBLE);
        } else {
            auxView.setVisibility(View.GONE);
        }
    }

    /** The four answer keys along the bottom edge, each over its interval. */
    private void showRatingChips() {
        chipsRow.removeAllViews();
        String[] labels = {"1 Again", "2 Hard", "3 Good", "4 Easy"};
        DeckCard card = session.current();
        long now = now();
        for (int i = 0; i < labels.length; i++) {
            Card after = ReviewSession.preview(card.fsrs, i + 1, now);
            String interval = interval(after.getDue().getEpochSecond() - now);
            chipsRow.addView(chip(interval + "\n" + labels[i]), new LayoutParams(0, chipHeight(), 1f));
        }
    }

    /** An interval the way Anki's buttons put it: <1m, 10m, 3h, 4d, 1.5mo, 2.1y. */
    static String interval(long seconds) {
        if (seconds < 60) return "<1m";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h";
        double days = seconds / 86400.0;
        if (days < 30) return Math.round(days) + "d";
        if (days < 365) return String.format(java.util.Locale.US, "%.1fmo", days / 30.0);
        return String.format(java.util.Locale.US, "%.1fy", days / 365.0);
    }

    private void hideChips() {
        chipsRow.removeAllViews();
    }

    private int chipHeight() {
        return dp(38);
    }

    private TextView chip(final String label) {
        TextView chip = new TextView(getContext());
        chip.setText(label);
        chip.setTextSize(11);
        chip.setTextColor(color(R.color.chip_text));
        chip.setGravity(Gravity.CENTER);
        chip.setMaxLines(2);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(dp(2), 0, dp(2), 0);
        chip.setBackgroundColor(color(R.color.chip));
        LinearLayout.LayoutParams p = new LayoutParams(0, chipHeight(), 1f);
        p.rightMargin = dp(2);
        p.leftMargin = dp(2);
        chip.setLayoutParams(p);
        return chip;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Examples are not truncated. On cards whose examples are taller than
        // the display, the phone's up/down keys reveal the remaining text.
        if (revealed && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            cardScroll.smoothScrollBy(0, -dp(48));
            return true;
        }
        if (revealed && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            cardScroll.smoothScrollBy(0, dp(48));
            return true;
        }
        if (event.getRepeatCount() > 0) {
            return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || gradeKey(keyCode);
        }
        // OK reveals the answer.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!revealed) {
                if (session.current() == null) showFirst();
                else reveal();
                return true;
            }
            return true;
        }
        // 1..4 grade; on a flip phone the numpad numerals may arrive as
        // either the top-row codes or the numpad variants.
        if (gradeKey(keyCode)) {
            if (!revealed) return true; // keys do nothing before the answer is shown
            rateAndAdvance(gradeOf(keyCode));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static boolean gradeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_1
            || keyCode == KeyEvent.KEYCODE_2
            || keyCode == KeyEvent.KEYCODE_3
            || keyCode == KeyEvent.KEYCODE_4
            || keyCode == KeyEvent.KEYCODE_NUMPAD_1
            || keyCode == KeyEvent.KEYCODE_NUMPAD_2
            || keyCode == KeyEvent.KEYCODE_NUMPAD_3
            || keyCode == KeyEvent.KEYCODE_NUMPAD_4;
    }

    private static int gradeOf(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_1 || keyCode == KeyEvent.KEYCODE_NUMPAD_1) return 1;
        if (keyCode == KeyEvent.KEYCODE_2 || keyCode == KeyEvent.KEYCODE_NUMPAD_2) return 2;
        if (keyCode == KeyEvent.KEYCODE_3 || keyCode == KeyEvent.KEYCODE_NUMPAD_3) return 3;
        return 4;
    }

    private void rateAndAdvance(int rating) {
        session.rate(rating, now());
        listener.onRated();
        showFirst();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private int color(int res) {
        return getContext().getColor(res);
    }

    private int dp(int n) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * n);
    }
}
