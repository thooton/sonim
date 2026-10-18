package com.tablet.xp3;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Upload: the phone's storage, one line per folder or file, to pick a PDF or
 * ePub to put on the tablet. Up and Down move the highlighter, OK opens a
 * folder or picks a file, Back goes up a folder. Laid out as Transforms is,
 * so the list never moves under the eye.
 */
final class UploadScreen extends FrameLayout {

    /** One line: a place, a folder or a file. */
    static final class Line {
        final String name;
        final String about;
        final boolean folder;

        Line(String name, String about, boolean folder) {
            this.name = name;
            this.about = about;
            this.folder = folder;
        }
    }

    private final TextView where = new TextView(getContext());
    private final ScrollView scroll;
    private final LinearLayout list;

    UploadScreen(Context c) {
        super(c);
        setBackgroundColor(Look.PAPER);
        addView(Parts.header(c, "Upload", 0, where), Parts.headerAt(c));
        where.setEllipsize(TextUtils.TruncateAt.START);
        where.setMaxWidth(Look.dp(c, 110));
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

    /** `selected` is the highlighted line, or -1 while the drawer or a sheet is over the list. */
    void show(String path, List<Line> lines, int selected, String empty) {
        Context c = getContext();
        where.setText(path);
        list.removeAllViews();
        if (lines.isEmpty()) {
            TextView none = Look.text(c, empty, Look.ui, 10.5f, Look.GRAPHITE);
            none.setPadding(Look.dp(c, 4), Look.dp(c, 4), Look.dp(c, 4), Look.dp(c, 4));
            list.addView(none);
            return;
        }
        View chosen = null;
        for (int i = 0; i < lines.size(); i++) {
            View row = row(c, lines.get(i), i == selected, i < lines.size() - 1);
            list.addView(row);
            if (i == selected) chosen = row;
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

    private static View row(Context c, Line line, boolean on, boolean divider) {
        LinearLayout outer = Parts.column(c);
        LinearLayout row = Parts.row(c);
        row.setPadding(Look.dp(c, 2), Look.dp(c, 5), Look.dp(c, 4), Look.dp(c, 5));
        ImageView glyph = new ImageView(c);
        glyph.setImageDrawable(Look.icon(c, line.folder ? R.drawable.ic_folder : R.drawable.ic_file, Look.INK, 14));
        glyph.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(glyph, Parts.line(Look.dp(c, 18), Look.dp(c, 18)));

        LinearLayout text = Parts.column(c);
        Mark title = new Mark(c);
        Look.style(title, on ? Look.uiBold : Look.ui, 11, Look.INK);
        title.setText(line.name);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Look.dp(c, 2), Look.dp(c, 1), Look.dp(c, 2), Look.dp(c, 1));
        title.mark(on);
        text.addView(title, Parts.line(Parts.WRAP, Parts.WRAP));
        if (line.about.length() > 0) {
            TextView sub = Look.text(c, line.about, Look.ui, 9, on ? Look.INK : Look.GRAPHITE);
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
