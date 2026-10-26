package com.print.xp3;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Print Queue (screen 16): jobs sent from this phone. Those in progress
 * come first with a progress bar, waiting ones say "Next", and today's
 * finished ones follow. OK on a job offers what can be done with it.
 */
final class QueueScreen extends Screen implements Queue.Listener {

    private LinearLayout list;
    private View firstRow;

    @Override
    View build() {
        Context c = app;
        list = Parts.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.addView(list);
        return Parts.page(c, Parts.nav(c, "Print Queue", true, null), scroll);
    }

    @Override
    View first() {
        return firstRow;
    }

    @Override
    void shown() {
        Queue.listen(this);
        fill();
    }

    @Override
    void hidden() {
        Queue.unlisten(this);
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean pending;

    /** Progress comes several times a second; the list is redrawn at most twice. */
    @Override
    public void changed(Queue.Entry entry) {
        if (pending) return;
        pending = true;
        main.postDelayed(() -> {
            pending = false;
            if (app != null && app.top() == this) fill();
        }, 500);
    }

    private void fill() {
        Context c = app;
        View focused = list.findFocus();
        Object focusedId = focused == null ? null : focused.getTag();
        list.removeAllViews();
        firstRow = null;
        List<Queue.Entry> shown = Queue.shown();
        if (shown.isEmpty()) {
            list.addView(Parts.status(c, Parts.badge(c, R.drawable.ic_list, Look.ICON_FILL, Look.BLUE), 56,
                "No print jobs", "Jobs you send from this phone show up here until midnight.", null, null, 40));
            return;
        }
        List<Queue.Entry> active = new ArrayList<Queue.Entry>();
        List<Queue.Entry> finished = new ArrayList<Queue.Entry>();
        for (Queue.Entry entry : shown) (entry.active() ? active : finished).add(entry);
        View refocus = null;
        boolean nextShown = false;
        if (!active.isEmpty()) {
            list.addView(Parts.header(c, "In progress"));
            for (Queue.Entry entry : active) {
                boolean next = entry.state == Queue.WAITING && !nextShown;
                if (entry.state == Queue.WAITING) nextShown = true;
                View row = row(c, entry, next);
                if (firstRow == null) firstRow = row;
                if (Integer.valueOf(entry.id).equals(focusedId)) refocus = row;
            }
        }
        if (!finished.isEmpty()) {
            list.addView(Parts.header(c, "Finished today"));
            for (Queue.Entry entry : finished) {
                View row = row(c, entry, false);
                if (firstRow == null) firstRow = row;
                if (Integer.valueOf(entry.id).equals(focusedId)) refocus = row;
            }
        }
        list.addView(Parts.foot(c, "OK on a job to cancel it or print it again."));
        View target = refocus != null ? refocus : firstRow;
        if (target != null && app.top() == this) target.requestFocus();
    }

    private View row(Context c, final Queue.Entry entry, boolean next) {
        Parts.Row row = new Parts.Row(c, entry.state == Queue.RUNNING ? 50 : 44);
        row.setTag(entry.id);
        final ImageView thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setBackgroundColor(0xFFDCDEE3);
        thumb.setPadding(1, 1, 1, 1);
        row.lead(thumb, Look.dp(c, 24), Look.dp(c, 31));
        Pages.draw(entry.file, 0, Look.dp(c, 22), Look.dp(c, 29), bitmap -> {
            if (bitmap != null) thumb.setImageBitmap(bitmap);
        });
        String printer = RecentScreen.shortName(entry.printer.shown());
        row.titled(entry.title, line(entry) + " · " + printer);
        if (entry.status != null && entry.status.problem && entry.active()) row.sub.setTextColor(Look.WARN_TEXT);
        if (entry.state == Queue.FAILED) row.sub.setTextColor(Look.ERR);
        if (entry.state == Queue.RUNNING) {
            Parts.Bar bar = new Parts.Bar(c);
            int percent = entry.status == null ? -1 : entry.status.phase < Job.PRINTING ? Math.max(0, entry.status.percent) : -1;
            bar.set(percent);
            LinearLayout.LayoutParams lp = Parts.line(Look.dp(c, 110), Look.dp(c, 3));
            lp.topMargin = Look.dp(c, 4);
            row.text.addView(bar, lp);
            row.chevron();
        } else if (entry.state == Queue.WAITING) {
            if (next) row.trailing(Parts.pill(c, "Next", 0xFFEEF0F4, Look.MUTE));
        } else if (entry.state == Queue.DONE) {
            row.trailing(Parts.image(c, R.drawable.ic_check, Look.OK, 14));
        } else {
            row.trailing(Parts.image(c, R.drawable.ic_cross, entry.state == Queue.FAILED ? Look.ERR : Look.MUTE, 12));
        }
        row.setOnClickListener(v -> act(entry));
        list.addView(row, Parts.line(Parts.FILL, Parts.WRAP));
        return row;
    }

    /** "Printing page 3 of 6", "Waiting", "Printed 1:12 PM", "Failed: out of paper". */
    private static String line(Queue.Entry entry) {
        String time = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(entry.finished));
        String said = entry.status == null ? "" : entry.status.text;
        switch (entry.state) {
            case Queue.WAITING:
                return "Waiting";
            case Queue.RUNNING:
                if (entry.status == null) return "Starting";
                if (entry.status.phase == Job.PRINTING && !entry.status.problem) return "Printing";
                return said;
            case Queue.DONE:
                return "Printed " + time;
            case Queue.CANCELED:
                return "Canceled " + time;
            default:
                return "Didn't print: " + said;
        }
    }

    private void act(final Queue.Entry entry) {
        List<MainActivity.Choice> choices = new ArrayList<MainActivity.Choice>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        choices.add(new MainActivity.Choice("Show progress", null));
        actions.add(() -> app.open(new PrintingScreen(entry)));
        if (entry.active()) {
            choices.add(new MainActivity.Choice("Cancel print", null));
            actions.add(() -> app.ask("Cancel this print job?", null, "Cancel print", Parts.DANGER,
                () -> Queue.cancel(entry), "Keep printing"));
        } else {
            choices.add(new MainActivity.Choice("Print again", "To " + entry.printer.shown()));
            actions.add(() -> app.open(new PrintingScreen(Queue.again(app, entry, null, entry.options.color))));
        }
        app.choose(entry.title, choices, -1, null, which -> actions.get(which).run());
    }

    @Override
    void options(List<Option> out) {
        final View focused = view == null ? null : view.findFocus();
        if (focused != null && focused.getTag() != null) out.add(new Option("This print job", focused::performClick));
    }
}
