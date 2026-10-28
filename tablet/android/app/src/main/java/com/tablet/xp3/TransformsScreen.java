package com.tablet.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.tablet.xp3.data.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Transforms: the jobs, one line each, running ones first. Up and Down move
 * the highlighter from job to job; OK opens what can be done with the
 * selected one (Sheet.job), as OK on a file opens its menu. Every row keeps
 * its size whatever is selected (see RowsScreen).
 */
final class TransformsScreen extends RowsScreen {

    private final TextView counts;

    TransformsScreen(Context c) {
        this(c, new TextView(c));
    }

    private TransformsScreen(Context c, TextView counts) {
        super(c, "Transforms", counts);
        this.counts = counts;
    }

    /** `selected` is the highlighted job's place in `jobs`, or -1 while the drawer is over the list. */
    void show(List<Job> jobs, int active, int selected, String empty) {
        Context c = getContext();
        counts.setText(active + " running");
        if (jobs.isEmpty()) {
            showEmpty(empty, 1.3f);
            return;
        }
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        boolean hours24 = DateFormat.is24HourFormat(c);
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            rows.add(row(c, glyph(c, job), 24, 6, Look.serifBold, job.heading(),
                job.summary(now, zone, hours24), i == selected, i < jobs.size() - 1));
        }
        showRows(rows, selected);
    }

    /** Its progress ring while it runs, else a round glyph for how it ended. */
    private static View glyph(Context c, Job job) {
        boolean done = job.status == Job.DONE;
        if (job.status == Job.RUNNING) {
            // How far along it is, as a ring, rather than in words.
            Ring ring = new Ring(c);
            ring.set(job.percent);
            return ring;
        }
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
        return glyph;
    }
}
