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
 * one step below 0 is Auto, where new cards come in as earlier ones are learned
 * rather than a number a day. Back keeps it.
 */
final class OptionsPane extends LinearLayout {

    private static final int MAX = 9999;

    private int value;
    /** Digits typed since the box took the keys, so typing replaces rather than appends. */
    private boolean typing;
    private final TextView box;
    private final TextView progressBox;
    private boolean showProgress;
    /** Which row has the keys: 0 New cards/day, 1 Show progress. */
    private int row;

    OptionsPane(Context context, Deck deck, boolean showProgress) {
        super(context);
        this.showProgress = showProgress;
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

        section(card, "Daily Limits", 0);
        box = setting(card, "New cards/day");
        // Not part of the deck: kept on this device, for every deck.
        section(card, "Display", dp(12));
        progressBox = setting(card, "Show progress");
        show();
    }

    private void section(LinearLayout card, String name, int above) {
        TextView section = text(name, 15, R.color.text);
        section.setTypeface(Anki.MEDIUM);
        section.setPadding(0, above, 0, dp(8));
        card.addView(section);
    }

    /** A setting's line: its name, and the spin box that shows its value. */
    private TextView setting(LinearLayout card, String name) {
        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        line.addView(text(name, 13, R.color.text), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView spin = Anki.button(getContext(), "");
        spin.setTextSize(13);
        spin.setPadding(dp(4), 0, dp(4), 0);
        spin.setFocusable(false);
        line.addView(spin, new LayoutParams(dp(84), dp(28)));
        return spin;
    }

    boolean showProgress() {
        return showProgress;
    }

    /** {@link Deck#AUTO} or the number set. */
    int value() {
        return value;
    }

    private void show() {
        box.setText("‹  " + (value < 0 ? "Auto" : Integer.toString(value)) + "  ›");
        progressBox.setText("‹  " + (showProgress ? "On" : "Off") + "  ›");
        // The row with the keys wears Anki's blue ring.
        TextView on = row == 0 ? box : progressBox;
        TextView off = row == 0 ? progressBox : box;
        Anki.ring(on);
        off.setBackground(Anki.buttonFace(getContext()));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            row = keyCode == KeyEvent.KEYCODE_DPAD_UP ? 0 : 1;
            show();
            return true;
        }
        if (row == 1) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || Ui.isOk(keyCode)) {
                showProgress = !showProgress;
                show();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
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
