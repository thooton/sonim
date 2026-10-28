package com.flashcards.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.flashcards.xp3.algo.ReviewSession;
import com.flashcards.xp3.data.DeckCard;
import com.xp3.common.ui.Ui;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

/**
 * Anki's reviewer: the card on white, the front alone and then the front,
 * a rule and the back; and under it Anki's bottom bar, the queue counts
 * over Show Answer, then the four answer buttons, each under the interval
 * it gives and labelled with its key. OK shows the answer; only 1 to 4
 * answer, Again, Hard, Good and Easy. Menu > Remove from deck (the
 * activity's) takes the card out and shows the next.
 */
final class ReviewPane extends LinearLayout {

    interface Listener {
        /** A card was answered: save, and sync before long. */
        void onRated();

        /** Nothing is due now: back to the deck's overview. */
        void onDone();
    }

    private final ReviewSession session;
    private final Listener listener;

    private boolean revealed;
    private final ScrollView scroll;
    private final TextView front;
    private final View rule;
    private final TextView back;
    private final TextView aux;
    private final TextView counts;
    private final LinearLayout answers;

    ReviewPane(Context context, ReviewSession session, Listener listener) {
        super(context);
        this.session = session;
        this.listener = listener;
        setOrientation(VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);

        scroll = new ScrollView(context);
        scroll.setBackgroundColor(color(R.color.elevated));
        scroll.setFillViewport(true);
        scroll.setFocusable(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(12));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(10), dp(8), dp(10));
        scroll.addView(card, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // Anki's card template: Arial 20px, black, centred.
        front = cardText(20, R.color.text);
        card.addView(front, wide());
        rule = new View(context);
        rule.setBackgroundColor(color(R.color.border));
        LayoutParams ruleAt = new LayoutParams(LayoutParams.MATCH_PARENT, 1);
        ruleAt.topMargin = dp(10);
        ruleAt.bottomMargin = dp(10);
        card.addView(rule, ruleAt);
        back = cardText(20, R.color.text);
        card.addView(back, wide());
        aux = cardText(13, R.color.text_subtle);
        aux.setPadding(0, dp(8), 0, 0);
        card.addView(aux, wide());

        // The bottom bar.
        View hairline = new View(context);
        hairline.setBackgroundColor(color(R.color.border));
        addView(hairline, new LayoutParams(LayoutParams.MATCH_PARENT, 1));
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(VERTICAL);
        bar.setBackgroundColor(color(R.color.canvas));
        bar.setPadding(dp(4), dp(3), dp(4), dp(5));
        addView(bar, wide());
        counts = new TextView(context);
        counts.setTextSize(12);
        counts.setGravity(Gravity.CENTER);
        counts.setIncludeFontPadding(false);
        counts.setPadding(0, 0, 0, dp(3));
        bar.addView(counts, wide());
        answers = new LinearLayout(context);
        answers.setOrientation(HORIZONTAL);
        answers.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bar.addView(answers, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));

        showNext();
    }

    private TextView cardText(int sp, int colorRes) {
        TextView text = new TextView(getContext());
        text.setTextSize(sp);
        text.setTextColor(color(colorRes));
        text.setTypeface(Typeface.SANS_SERIF);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        return text;
    }

    /** Serves the next card due, or hands back to the overview. */
    void showNext() {
        DeckCard card = session.next(now());
        if (card == null) {
            listener.onDone();
            return;
        }
        revealed = false;
        scroll.scrollTo(0, 0);
        front.setText(card.def.key);
        rule.setVisibility(GONE);
        back.setVisibility(GONE);
        aux.setVisibility(GONE);
        showCounts(card);
        answers.removeAllViews();
        TextView show = Anki.button(getContext(), "Show Answer");
        show.setFocusable(false);
        Anki.ring(show);
        answers.addView(show, new LayoutParams(dp(130), dp(28)));
    }

    /** Anki's "new + learning + review", the current card's queue underlined. */
    private void showCounts(DeckCard card) {
        int queue;
        State state = card.fsrs.getState();
        if (card.isNew()) queue = 0;
        else if (state == State.LEARNING || state == State.RELEARNING) queue = 1;
        else queue = 2;
        counts.setText(Anki.counts(getContext(), session.counts(now()), queue));
    }

    private void reveal() {
        DeckCard card = session.current();
        if (card == null || revealed) return;
        revealed = true;
        back.setText(card.def.value);
        rule.setVisibility(VISIBLE);
        back.setVisibility(VISIBLE);
        if (card.def.aux != null && card.def.aux.length() > 0) {
            aux.setText(card.def.aux);
            aux.setVisibility(VISIBLE);
        }
        answers.removeAllViews();
        String[] labels = {"1 Again", "2 Hard", "3 Good", "4 Easy"};
        long now = now();
        for (int i = 0; i < 4; i++) {
            Card after = session.preview(card.fsrs, i + 1, now);
            answers.addView(answer(Anki.interval(after.getDue().getEpochSecond() - now), labels[i]),
                new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        }
    }

    /** One answer button under its interval; none wears the ring, since only its key answers. */
    private View answer(String interval, String label) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(2), 0, dp(2), 0);
        TextView due = new TextView(getContext());
        due.setText(interval);
        due.setTextSize(11);
        due.setTextColor(color(R.color.text_subtle));
        due.setGravity(Gravity.CENTER);
        due.setSingleLine(true);
        due.setIncludeFontPadding(false);
        due.setPadding(0, 0, 0, dp(3));
        box.addView(due, wide());
        TextView button = Anki.button(getContext(), label);
        button.setTextSize(12);
        button.setPadding(0, 0, 0, 0);
        button.setFocusable(false);
        box.addView(button, new LayoutParams(LayoutParams.MATCH_PARENT, dp(28)));
        return box;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            scroll.smoothScrollBy(0, -dp(48));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            scroll.smoothScrollBy(0, dp(48));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        boolean ok = Ui.isOk(keyCode);
        int grade = gradeOf(keyCode);
        if (!ok && grade == 0) return super.onKeyDown(keyCode, event);
        // A held key answers one card, not a run of them.
        if (event.getRepeatCount() > 0) return true;
        if (!revealed) {
            if (ok) reveal();
            return true;
        }
        if (grade != 0) rate(grade);
        return true;
    }

    private static int gradeOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_NUMPAD_1: return 1;
            case KeyEvent.KEYCODE_2: case KeyEvent.KEYCODE_NUMPAD_2: return 2;
            case KeyEvent.KEYCODE_3: case KeyEvent.KEYCODE_NUMPAD_3: return 3;
            case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_NUMPAD_4: return 4;
            default: return 0;
        }
    }

    private void rate(int rating) {
        session.rate(rating, now());
        listener.onRated();
        showNext();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static LayoutParams wide() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return getContext().getColor(res);
    }

    private int dp(float n) {
        return Ui.dp(getContext(), n);
    }
}
