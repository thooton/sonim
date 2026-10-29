package com.tablet.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.Gravity;
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
 * its size whatever is selected, so the list never moves under the eye.
 */
final class TransformsScreen extends ListScreen {

    TransformsScreen(Context c) {
        super(c, "Transforms");
    }

    /** `selected` is the highlighted job's place in `jobs`, or -1 while the drawer is over the list. */
    void show(List<Job> jobs, int active, int selected, String empty) {
        Context c = getContext();
        side.setText(active > 0 ? active + " running" : "");
        if (jobs.isEmpty()) {
            // The same mid-screen caption as an empty Library.
            TextView none = Look.text(c, empty, Look.serifBold, 13, Look.INK);
            none.setGravity(Gravity.CENTER_HORIZONTAL);
            none.setPadding(0, Look.dp(c, 100), 0, 0);
            showEmpty(none, Parts.line(Parts.FILL, Parts.WRAP));
            return;
        }
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        boolean hours24 = DateFormat.is24HourFormat(c);
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            rows.add(row(c, glyph(c, job), 24, 6, Look.serifBold, job.heading(), i == selected, job.summary(now, zone, hours24)));
        }
        showRows(rows, selected);
    }

    /** A running job's progress ring, else a round glyph for how it ended. */
    private static View glyph(Context c, Job job) {
        if (job.status == Job.RUNNING) {
            // How far along it is, as a ring, rather than in words.
            Ring ring = new Ring(c);
            ring.set(job.percent);
            return ring;
        }
        boolean done = job.status == Job.DONE;
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
