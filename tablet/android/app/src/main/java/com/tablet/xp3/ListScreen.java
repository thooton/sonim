package com.tablet.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * A screen that is a list under its header, as Transforms and Upload are:
 * one line per row, ruled between, scrolled only as far as keeps the
 * highlighted row in view, so the list never moves under the eye otherwise.
 */
abstract class ListScreen extends FrameLayout {

    /** What the header says at its right. */
    final TextView side = new TextView(getContext());
    private final ScrollView scroll;
    private final LinearLayout list;

    ListScreen(Context c, String title) {
        super(c);
        setBackgroundColor(Look.PAPER);
        addView(Parts.header(c, title, 0, side), Parts.headerAt(c));
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = Parts.column(c);
        list.setPadding(0, 0, 0, Look.dp(c, 8));
        scroll.addView(list, new LayoutParams(Parts.FILL, Parts.WRAP));
        LayoutParams lp = new LayoutParams(Parts.FILL, Parts.FILL);
        lp.topMargin = Look.dp(c, 34);
        lp.leftMargin = Look.dp(c, 8);
        lp.rightMargin = Look.dp(c, 8);
        addView(scroll, lp);
    }

    /** No rows, just `note` saying so. */
    void showEmpty(View note, LinearLayout.LayoutParams lp) {
        list.removeAllViews();
        list.addView(note, lp);
    }

    /** `rows`, the `selected`th (-1: none) kept in view. */
    void showRows(List<View> rows, int selected) {
        Context c = getContext();
        list.removeAllViews();
        View chosen = null;
        for (int i = 0; i < rows.size(); i++) {
            LinearLayout outer = Parts.column(c);
            outer.addView(rows.get(i));
            if (i < rows.size() - 1) outer.addView(Parts.rule(c), Parts.line(Parts.FILL, 1));
            list.addView(outer);
            if (i == selected) chosen = outer;
        }
        final View open = chosen;
        scroll.post(() -> {
            if (open == null) return;
            int room = scroll.getHeight();
            int y = scroll.getScrollY();
            if (open.getTop() < y) scroll.scrollTo(0, open.getTop());
            else if (open.getBottom() > y + room) scroll.scrollTo(0, open.getBottom() - room);
        });
    }

    /**
     * One row: `glyph` in a `side`-square box, then the title (under the
     * highlighter when `on`) set in `face`, and `sub` under it unless it is
     * null. `pad` is above and below.
     */
    static LinearLayout row(Context c, View glyph, float side, float pad, Typeface face, String title, boolean on, String sub) {
        LinearLayout row = Parts.row(c);
        row.setPadding(Look.dp(c, 2), Look.dp(c, pad), Look.dp(c, 4), Look.dp(c, pad));
        row.addView(glyph, Parts.line(Look.dp(c, side), Look.dp(c, side)));

        LinearLayout text = Parts.column(c);
        Mark name = new Mark(c);
        Look.style(name, face, 11, Look.INK);
        name.setText(title);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(Look.dp(c, 2), Look.dp(c, 1), Look.dp(c, 2), Look.dp(c, 1));
        name.mark(on);
        text.addView(name, Parts.line(Parts.WRAP, Parts.WRAP));
        if (sub != null) {
            TextView under = Look.text(c, sub, Look.ui, 9, on ? Look.INK : Look.GRAPHITE);
            under.setSingleLine();
            under.setEllipsize(TextUtils.TruncateAt.END);
            under.setPadding(Look.dp(c, 2), 0, 0, 0);
            LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Parts.WRAP);
            lp.topMargin = Look.dp(c, 2);
            text.addView(under, lp);
        }
        LinearLayout.LayoutParams lp = Parts.line(0, Parts.WRAP);
        lp.weight = 1;
        lp.leftMargin = Look.dp(c, 6);
        row.addView(text, lp);
        return row;
    }
}
