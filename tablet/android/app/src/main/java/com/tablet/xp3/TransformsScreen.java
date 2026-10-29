package com.tablet.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tablet.xp3.data.Job;

import java.util.List;
import java.util.TimeZone;

/**
 * Transforms: the jobs, one line each, running ones first. Up and Down move
 * the highlighter from job to job; OK opens what can be done with the
 * selected one (Sheet.job), as OK on a file opens its menu. Every row keeps
 * its size whatever is selected, so the list never moves under the eye.
 */
final class TransformsScreen extends FrameLayout {

    private final TextView counts = new TextView(getContext());
    private final ScrollView scroll;
    private final LinearLayout list;

    TransformsScreen(Context c) {
        super(c);
        setBackgroundColor(Look.PAPER);
        addView(Parts.header(c, "Transforms", 0, counts), Parts.headerAt(c));
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

    /** `selected` is the highlighted job's place in `jobs`, or -1 while the drawer is over the list. */
    void show(List<Job> jobs, int active, int selected, String empty) {
        Context c = getContext();
        counts.setText(active > 0 ? active + " running" : "");
        list.removeAllViews();
        if (jobs.isEmpty()) {
            // The same mid-screen caption as an empty Library.
            TextView none = Look.text(c, empty, Look.serifBold, 13, Look.INK);
            none.setGravity(Gravity.CENTER_HORIZONTAL);
            none.setPadding(0, Look.dp(c, 100), 0, 0);
            list.addView(none, Parts.line(Parts.FILL, Parts.WRAP));
            return;
        }
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        boolean hours24 = DateFormat.is24HourFormat(c);
        View chosen = null;
        for (int i = 0; i < jobs.size(); i++) {
            View row = row(c, jobs.get(i), i == selected, jobs.get(i).summary(now, zone, hours24), i < jobs.size() - 1);
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

    /** A job: its progress ring while it runs, else a round glyph for how it ended; its title (under the highlighter when selected) and one line. */
    private static View row(Context c, Job job, boolean on, String summary, boolean divider) {
        LinearLayout outer = Parts.column(c);
        LinearLayout row = Parts.row(c);
        row.setPadding(Look.dp(c, 2), Look.dp(c, 6), Look.dp(c, 4), Look.dp(c, 6));

        boolean done = job.status == Job.DONE;
        if (job.status == Job.RUNNING) {
            // How far along it is, as a ring, rather than in words.
            Ring ring = new Ring(c);
            ring.set(job.percent);
            row.addView(ring, Parts.line(Look.dp(c, 24), Look.dp(c, 24)));
        } else {
            ImageView glyph = new ImageView(c);
            glyph.setImageDrawable(Look.icon(c, done ? R.drawable.ic_check : R.drawable.ic_x, done ? Look.SHEET : Look.INK, 10));
            // Fitted inside the ring: CENTER would draw it at its own 24dp.
            glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
            glyph.setPadding(Look.dp(c, 7), Look.dp(c, 7), Look.dp(c, 7), Look.dp(c, 7));
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(done ? Look.INK : 0);
            ring.setStroke(Math.max(1, Look.dp(c, 1.3f)), Look.INK);
            glyph.setBackground(ring);
            row.addView(glyph, Parts.line(Look.dp(c, 24), Look.dp(c, 24)));
        }

        LinearLayout text = Parts.column(c);
        Mark title = new Mark(c);
        Look.style(title, Look.serifBold, 11, Look.INK);
        title.setText(job.heading());
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Look.dp(c, 2), Look.dp(c, 1), Look.dp(c, 2), Look.dp(c, 1));
        title.mark(on);
        text.addView(title, Parts.line(Parts.WRAP, Parts.WRAP));
        TextView sub = Look.text(c, summary, Look.ui, 9, on ? Look.INK : Look.GRAPHITE);
        sub.setSingleLine();
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(Look.dp(c, 2), 0, 0, 0);
        LinearLayout.LayoutParams under = Parts.line(Parts.FILL, Parts.WRAP);
        under.topMargin = Look.dp(c, 2);
        text.addView(sub, under);
        LinearLayout.LayoutParams lp = Parts.line(0, Parts.WRAP);
        lp.weight = 1;
        lp.leftMargin = Look.dp(c, 6);
        row.addView(text, lp);

        outer.addView(row);
        if (divider) outer.addView(Parts.rule(c), Parts.line(Parts.FILL, 1));
        return outer;
    }
}
