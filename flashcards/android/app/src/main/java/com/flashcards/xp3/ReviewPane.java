package com.flashcards.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.flashcards.xp3.algo.Scheduler;
import com.flashcards.xp3.data.DeckCard;

/**
 * The study screen for one deck.
 *
 * A card is served front-first: the key fills the middle of the screen, the
 * answer hidden. Pressing OK (DPAD_CENTER/ENTER) reveals it — the value and
 * any auxiliary line together with the four neutral answer keys at the
 * bottom. New cards are introductions instead: prompt and answer are visible
 * together, and 5 advances after recording the introduction as Again in
 * FSRS. Ordinary reviews use 1 Again, 2 Hard, 3 Good, 4 Easy.
 */
public final class ReviewPane extends LinearLayout {

    /** Notified after every rating so the caller can write state to disk. */
    public interface Listener {
        void onRated();
    }

    private final Scheduler scheduler;
    private final Listener listener;

    private boolean revealed;
    private final TextView statusView;
    private final TextView keyView;
    private final TextView valueView;
    private final TextView auxView;
    private final ScrollView cardScroll;
    private final LinearLayout chipsRow;

    public ReviewPane(Context context, Scheduler scheduler, Listener listener) {
        super(context);
        this.scheduler = scheduler;
        this.listener = listener;

        setOrientation(VERTICAL);
        int pad = dp(8);
        setPadding(pad, dp(4), pad, dp(4));
        setFocusable(true);
        setFocusableInTouchMode(true);

        statusView = new TextView(context);
        statusView.setTextColor(color(R.color.warning));
        statusView.setTextSize(11);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setVisibility(View.GONE);
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

    /** Serves the first card (building the first group), or says the deck is empty. */
    private void showFirst() {
        DeckCard card = scheduler.next();
        if (card == null) showIdle();
        else showCard(card);
    }

    private void showCard(DeckCard card) {
        revealed = false;
        cardScroll.scrollTo(0, 0);
        keyView.setTextSize(22);
        keyView.setMaxLines(3);
        if (card.isNew()) {
            statusView.setText("New card");
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }
        keyView.setText(card.def.key);
        keyView.setVisibility(View.VISIBLE);
        if (card.isNew()) {
            revealed = true;
            showAnswer(card);
            showNextChip();
        } else {
            valueView.setVisibility(View.GONE);
            auxView.setVisibility(View.GONE);
            hideChips();
        }
    }

    private void showIdle() {
        revealed = false;
        statusView.setVisibility(View.GONE);
        cardScroll.scrollTo(0, 0);
        keyView.setTextSize(16);
        keyView.setMaxLines(5);
        keyView.setText(scheduler.hasCards() ? "Nothing to review yet." : "This deck is empty.");
        keyView.setVisibility(View.VISIBLE);
        valueView.setVisibility(View.GONE);
        auxView.setVisibility(View.GONE);
        hideChips();
    }

    private void reveal() {
        if (revealed) return;
        DeckCard card = scheduler.current();
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

    /** The four answer keys in one neutral row along the bottom edge. */
    private void showRatingChips() {
        chipsRow.removeAllViews();
        String[] labels = {"1 Again", "2 Hard", "3 Good", "4 Easy"};
        for (String label : labels) {
            chipsRow.addView(chip(label), new LayoutParams(0, chipHeight(), 1f));
        }
    }

    /** A new card is presented, not quizzed: there is only one way forward. */
    private void showNextChip() {
        chipsRow.removeAllViews();
        chipsRow.addView(
            chip("5 Next"),
            new LayoutParams(LayoutParams.MATCH_PARENT, chipHeight()));
    }

    private void hideChips() {
        chipsRow.removeAllViews();
    }

    private int chipHeight() {
        return dp(34);
    }

    private TextView chip(final String label) {
        TextView chip = new TextView(getContext());
        chip.setText(label);
        chip.setTextSize(12);
        chip.setTextColor(color(R.color.chip_text));
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
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
        if (event.getRepeatCount() == 0
            && nextKey(keyCode)
            && scheduler.current() != null
            && scheduler.current().isNew()) {
            rateAndAdvance(1);
            return true;
        }
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
                || nextKey(keyCode)
                || gradeKey(keyCode);
        }
        // OK reveals the answer.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!revealed) {
                if (scheduler.current() == null) showFirst();
                else reveal();
                return true;
            }
            return true;
        }
        // 1..4 grade; on a flip phone the numpad numerals may arrive as
        // either the top-row codes or the numpad variants.
        if (gradeKey(keyCode)) {
            if (!revealed) return true; // keys do nothing before the answer is shown
            if (scheduler.current() != null && scheduler.current().isNew()) return true;
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

    private static boolean nextKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_5 || keyCode == KeyEvent.KEYCODE_NUMPAD_5;
    }

    private static int gradeOf(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_1 || keyCode == KeyEvent.KEYCODE_NUMPAD_1) return 1;
        if (keyCode == KeyEvent.KEYCODE_2 || keyCode == KeyEvent.KEYCODE_NUMPAD_2) return 2;
        if (keyCode == KeyEvent.KEYCODE_3 || keyCode == KeyEvent.KEYCODE_NUMPAD_3) return 3;
        return 4;
    }

    private void rateAndAdvance(int rating) {
        long now = System.currentTimeMillis() / 1000L;
        scheduler.rate(rating, now);
        listener.onRated();
        DeckCard next = scheduler.next();
        if (next == null) showIdle();
        else showCard(next);
    }

    private int color(int res) {
        return getContext().getColor(res);
    }

    private int dp(int n) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * n);
    }
}
