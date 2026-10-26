package com.flashcards.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.widget.TextView;

import com.xp3.common.ui.Ui;

/**
 * The pieces of Anki's desktop look the screens share: its buttons (a white
 * to pale grey face, a grey border, 5dp corners, and the blue focus ring),
 * the rounded wash under the row the d-pad is on, and the queue colours.
 */
final class Anki {

    private Anki() {}

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** The three queues, New, Learning and Review, in Anki's order. */
    static final int[] QUEUE_COLORS = {R.color.state_new, R.color.state_learn, R.color.state_review};

    /** An Anki push button; focused, it wears the blue ring. */
    static TextView button(Context context, String label) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(context.getColor(R.color.text));
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        int pad = Ui.dp(context, 12);
        button.setPadding(pad, 0, pad, 0);
        button.setMinHeight(Ui.dp(context, 28));
        button.setFocusable(true);
        button.setBackground(buttonFace(context));
        return button;
    }

    static Drawable buttonFace(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, face(context, R.color.pressed, R.color.pressed, R.color.focus, 2));
        states.addState(new int[]{android.R.attr.state_focused}, face(context, R.color.button_top, R.color.button_bottom, R.color.focus, 2));
        states.addState(new int[]{}, face(context, R.color.button_top, R.color.button_bottom, R.color.border, 1));
        return states;
    }

    /** The face of the button Enter would press: Anki rings it in blue. */
    static void ring(TextView button) {
        Context context = button.getContext();
        button.setBackground(face(context, R.color.button_top, R.color.button_bottom, R.color.focus, 2));
    }

    private static GradientDrawable face(Context context, int top, int bottom, int border, float width) {
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{context.getColor(top), context.getColor(bottom)});
        face.setCornerRadius(Ui.dp(context, 5));
        face.setStroke(Ui.dp(context, width), context.getColor(border));
        return face;
    }

    /** The rounded grey Anki lays under the deck row the pointer is on. */
    static Drawable rowFace(Context context) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(context.getColor(R.color.hover));
        on.setCornerRadius(Ui.dp(context, 6));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, on);
        states.addState(new int[]{android.R.attr.state_focused}, on);
        states.addState(new int[]{android.R.attr.state_pressed}, on);
        return states;
    }

    /** A queue's count, in its colour, or the faint grey Anki gives a zero. */
    static int countColor(Context context, int queue, int count) {
        return context.getColor(count == 0 ? R.color.text_faint : QUEUE_COLORS[queue]);
    }

    /** "20 + 3 + 41", each in its queue's colour, the current card's underlined. */
    static CharSequence counts(Context context, int[] counts, int current) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) text.append(" + ");
            int start = text.length();
            text.append(Integer.toString(counts[i]));
            text.setSpan(new ForegroundColorSpan(context.getColor(QUEUE_COLORS[i])),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i == current) {
                text.setSpan(new UnderlineSpan(), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return text;
    }

    /** An interval the way Anki's answer buttons put it: <1m, 10m, 3h, 4d, 1.5mo, 2.1y. */
    static String interval(long seconds) {
        if (seconds < 60) return "<1m";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h";
        double days = seconds / 86400.0;
        if (days < 30) return Math.round(days) + "d";
        if (days < 365) return String.format(java.util.Locale.US, "%.1fmo", days / 30.0);
        return String.format(java.util.Locale.US, "%.1fy", days / 365.0);
    }
}
