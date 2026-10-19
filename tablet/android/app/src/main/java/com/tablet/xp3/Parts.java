package com.tablet.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The pieces more than one screen is built from. */
final class Parts {

    private Parts() {}

    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    static final int FILL = LinearLayout.LayoutParams.MATCH_PARENT;

    /** A view placed at (left, top) dp in a FrameLayout. */
    static FrameLayout.LayoutParams at(Context c, float left, float top, int width, int height) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        lp.leftMargin = Look.dp(c, left);
        lp.topMargin = Look.dp(c, top);
        return lp;
    }

    static LinearLayout.LayoutParams line(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    static LinearLayout column(Context c) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    /**
     * A screen's header: the menu glyph (the Menu key opens the drawer), the
     * screen's name in Literata, and how things stand at the right.
     */
    static LinearLayout header(Context c, String title, int sideIcon, TextView side) {
        LinearLayout row = row(c);
        ImageView menu = new ImageView(c);
        menu.setImageResource(R.drawable.ic_menu);
        menu.setImageTintList(android.content.res.ColorStateList.valueOf(Look.INK));
        row.addView(menu, line(Look.dp(c, 15), Look.dp(c, 15)));
        TextView name = Look.text(c, title, Look.serifBold, 17, Look.INK);
        name.setLetterSpacing(-0.012f);
        LinearLayout.LayoutParams lp = line(0, WRAP);
        lp.weight = 1;
        lp.leftMargin = Look.dp(c, 6);
        row.addView(name, lp);
        Look.style(side, Look.ui, 9.5f, Look.GRAPHITE);
        side.setSingleLine();
        if (sideIcon != 0) {
            side.setCompoundDrawables(Look.icon(c, sideIcon, Look.GRAPHITE, 9.5f), null, null, null);
            side.setCompoundDrawablePadding(Look.dp(c, 3));
        }
        row.addView(side, line(WRAP, WRAP));
        return row;
    }

    static FrameLayout.LayoutParams headerAt(Context c) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FILL, WRAP);
        lp.leftMargin = Look.dp(c, 8);
        lp.rightMargin = Look.dp(c, 10);
        lp.topMargin = Look.dp(c, 5);
        return lp;
    }

    /**
     * One choice in a sheet: icon and label, with the highlighter behind
     * both when it is the selected one, and an optional note at the right.
     */
    static final class Action extends LinearLayout {
        final Mark label;
        final TextView note;
        private int icon;
        private boolean lit;

        Action(Context c, int icon, String text) {
            super(c);
            this.icon = icon;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            label = new Mark(c);
            Look.style(label, Look.ui, 11.5f, Look.INK);
            label.setText(text);
            label.setSingleLine();
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setCompoundDrawablePadding(Look.dp(c, 9));
            label.setPadding(Look.dp(c, 7), Look.dp(c, 2), Look.dp(c, 7), Look.dp(c, 2));
            LayoutParams lp = new LayoutParams(0, WRAP);
            lp.weight = 1;
            LinearLayout holder = new LinearLayout(c);
            holder.addView(label, new LayoutParams(WRAP, WRAP));
            addView(holder, lp);
            note = Look.text(c, "", Look.ui, 9, Look.GRAPHITE);
            addView(note, new LayoutParams(WRAP, WRAP));
            setPadding(Look.dp(c, 2), 0, Look.dp(c, 9), 0);
            on(false);
        }

        void on(boolean value) {
            lit = value;
            label.mark(value);
            label.setTypeface(value ? Look.uiBold : Look.ui);
            if (icon != 0) {
                label.setCompoundDrawables(Look.icon(getContext(), icon, value ? Look.INK : Look.GRAPHITE, 12), null, null, null);
            }
        }

        /** Draws it with another icon: a box ticked or unticked. */
        void icon(int next) {
            icon = next;
            on(lit);
        }
    }

    static LinearLayout.LayoutParams actionLine(Context c) {
        return line(FILL, Look.dp(c, 22.5f));
    }

    /** The sheet's paper: rounded at the top, lifted off the page. */
    static GradientDrawable sheet(Context c) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Look.SHEET);
        float r = Look.px(c, 10);
        shape.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        return shape;
    }

    static View rule(Context c) {
        View rule = new View(c);
        rule.setBackgroundColor(Look.RULE);
        return rule;
    }
}
