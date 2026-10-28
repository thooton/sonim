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
 * A screen of rows under a header, as Transforms and Upload are laid out: a
 * glyph, a title (under the highlighter when selected) and perhaps a line
 * under it. Every row keeps its size whatever is selected, so the list never
 * moves under the eye.
 */
abstract class RowsScreen extends FrameLayout {

    private final ScrollView scroll;
    private final LinearLayout list;

    RowsScreen(Context c, String title, TextView side) {
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

    /** What an empty list says, in lines `spacing` apart. */
    final void showEmpty(String empty, float spacing) {
        Context c = getContext();
        list.removeAllViews();
        TextView none = Look.text(c, empty, Look.ui, 10.5f, Look.GRAPHITE);
        none.setLineSpacing(0, spacing);
        none.setPadding(Look.dp(c, 4), Look.dp(c, 4), Look.dp(c, 4), Look.dp(c, 4));
        list.addView(none);
    }

    /** Shows `rows`, scrolling the `selected` one (or none, at -1) into view. */
    final void showRows(List<View> rows, int selected) {
        list.removeAllViews();
        for (View row : rows) list.addView(row);
        final View open = selected >= 0 && selected < rows.size() ? rows.get(selected) : null;
        scroll.post(() -> {
            if (open == null) return;
            int room = scroll.getHeight();
            int y = scroll.getScrollY();
            if (open.getTop() < y) scroll.scrollTo(0, open.getTop());
            else if (open.getBottom() > y + room) scroll.scrollTo(0, open.getBottom() - room);
        });
    }

    /**
     * One row: `glyph` in a square `size` dp across, the title in `face`,
     * and `about` under it unless it is null; a rule under all but the last.
     */
    static View row(Context c, View glyph, int size, int pad, Typeface face, CharSequence title,
                    String about, boolean on, boolean divider) {
        LinearLayout outer = Parts.column(c);
        LinearLayout row = Parts.row(c);
        row.setPadding(Look.dp(c, 2), Look.dp(c, pad), Look.dp(c, 4), Look.dp(c, pad));
        row.addView(glyph, Parts.line(Look.dp(c, size), Look.dp(c, size)));

        LinearLayout text = Parts.column(c);
        Mark heading = new Mark(c);
        Look.style(heading, face, 11, Look.INK);
        heading.setText(title);
        heading.setSingleLine();
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setPadding(Look.dp(c, 2), Look.dp(c, 1), Look.dp(c, 2), Look.dp(c, 1));
        heading.mark(on);
        text.addView(heading, Parts.line(Parts.WRAP, Parts.WRAP));
        if (about != null) {
            TextView sub = Look.text(c, about, Look.ui, 9, on ? Look.INK : Look.GRAPHITE);
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(Look.dp(c, 2), 0, 0, 0);
            LinearLayout.LayoutParams under = Parts.line(Parts.FILL, Parts.WRAP);
            under.topMargin = Look.dp(c, 2);
            text.addView(sub, under);
        }
        LinearLayout.LayoutParams lp = Parts.line(0, Parts.WRAP);
        lp.weight = 1;
        lp.leftMargin = Look.dp(c, 6);
        row.addView(text, lp);

        outer.addView(row);
        if (divider) outer.addView(Parts.rule(c), Parts.line(Parts.FILL, 1));
        return outer;
    }
}
