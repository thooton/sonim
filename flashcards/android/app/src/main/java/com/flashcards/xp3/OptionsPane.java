package com.flashcards.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.flashcards.xp3.data.Deck;
import com.xp3.common.ui.Ui;

/**
 * Anki's deck options, cut to what the app has: the Daily Limits card with
 * New cards/day in a spin box. Left and Right step it, digits type it, and
 * one step below 0 is Auto, where new cards come in while recall allows
 * rather than a number a day. Back keeps it.
 */
final class OptionsPane extends LinearLayout {

    private static final int MAX = 9999;

    private int value;
    /** Digits typed since the box took the keys, so typing replaces rather than appends. */
    private boolean typing;
    private final TextView box;

    OptionsPane(Context context, Deck deck) {
        super(context);
        value = deck.newPerDay;
        setOrientation(VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setPadding(dp(6), dp(6), dp(6), 0);

        TextView title = text(deck.name, 12, R.color.text_subtle);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(6));
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Anki's options sit on white cards, each under its section's name.
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(10), dp(8), dp(8), dp(10));
        GradientDrawable face = new GradientDrawable();
        face.setColor(context.getColor(R.color.elevated));
        face.setCornerRadius(dp(6));
        face.setStroke(1, context.getColor(R.color.border_subtle));
        card.setBackground(face);
        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView section = text("Daily Limits", 15, R.color.text);
        section.setTypeface(Anki.MEDIUM);
        section.setPadding(0, 0, 0, dp(8));
        card.addView(section);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        row.addView(text("New cards/day", 13, R.color.text), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        box = Anki.button(context, "");
        box.setTextSize(13);
        box.setPadding(dp(4), 0, dp(4), 0);
        box.setFocusable(false);
        Anki.ring(box);
        row.addView(box, new LayoutParams(dp(84), dp(28)));
        show();
    }

    /** {@link Deck#AUTO} or the number set. */
    int value() {
        return value;
    }

    private void show() {
        box.setText("‹  " + (value < 0 ? "Auto" : Integer.toString(value)) + "  ›");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int digit = digitOf(keyCode);
        if (digit >= 0) {
            int base = typing && value > 0 ? value : 0;
            value = Math.min(MAX, base * 10 + digit);
            typing = true;
            show();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            value = Math.max(Deck.AUTO, value - 1);
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            value = Math.min(MAX, value + 1);
        } else if (keyCode == KeyEvent.KEYCODE_STAR) {
            value = Deck.AUTO;
        } else if (keyCode == KeyEvent.KEYCODE_DEL) {
            value = value <= 0 ? Deck.AUTO : value / 10;
        } else {
            return super.onKeyDown(keyCode, event);
        }
        typing = false;
        show();
        return true;
    }

    private static int digitOf(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return keyCode - KeyEvent.KEYCODE_0;
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private TextView text(String value, float sp, int colorRes) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(getContext().getColor(colorRes));
        return text;
    }

    private int dp(float n) {
        return Ui.dp(getContext(), n);
    }
}
