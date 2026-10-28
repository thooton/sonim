package com.tree.xp3;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tree.xp3.qa.Center;
import com.tree.xp3.qa.Qa;
import com.tree.xp3.qa.Words;
import com.xp3.common.ui.Ui;

import java.util.TimeZone;

/**
 * One run, whole: the button for what OK does (Cancel run while it goes,
 * Run QA now after), its summary, each app's place in it and each
 * job. Up and Down scroll; it asks again every 20 seconds while the run
 * goes.
 */
public final class RunActivity extends Screen {

    static final String RUN = "run";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::load;
    private boolean resumed;
    private String id;
    private Qa.Run run;
    private String error;

    private ScrollView scroll;
    private LinearLayout page;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra(RUN);
        if (id == null) id = "";
        // What the home screen already had, until the whole run arrives.
        if (Tree.qa != null) {
            for (Qa.Run known : Tree.qa.runs) if (known.id.equals(id)) run = known;
            if (Tree.qa.running != null && Tree.qa.running.id.equals(id)) run = Tree.qa.running;
        }
        scroll = new ScrollView(this);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setFocusable(true);
        scroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        page = vertical();
        page.setPadding(0, 0, 0, dp(8));
        scroll.addView(page);
        Ui.fill(content, scroll);
        scroll.requestFocus();
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(poll);
    }

    private void load() {
        handler.removeCallbacks(poll);
        final Center center = center();
        if (center == null || id.length() == 0) return;
        work(() -> center.run(id), got -> {
            run = got;
            error = null;
            if (Tree.qa != null && (got.isRunning() || Tree.qa.running != null && Tree.qa.running.id.equals(got.id))) {
                Tree.qa = Tree.qa.withRunning(got);
            }
            show();
            if (resumed && got.isRunning()) handler.postDelayed(poll, MainActivity.POLL_MS);
        }, why -> {
            error = why;
            show();
        });
    }

    @Override
    protected void changed() {
        // Run QA now from here shows the run it started.
        Qa.Run now = running();
        if (now != null && !now.id.equals(id)) {
            id = now.id;
            run = now;
            show();
        }
        load();
    }

    private void show() {
        int y = scroll.getScrollY();
        page.removeAllViews();
        if (run == null) {
            header("Run", null);
            TextView words = com.xp3.common.ui.Family.empty(this, error != null ? "Couldn't read the run" : "Loading", error);
            words.setFocusable(false);
            words.setBackgroundColor(0);
            page.addView(words, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        header(Words.run(run, now, zone), Words.trigger(run.trigger));

        // What OK does. Not focusable: OK is read here, and the button is
        // drawn as the focused one because it always is.
        TextView action = new TextView(this, null, 0, com.xp3.common.ui.R.style.FamilyButton);
        boolean other = !run.isRunning() && running() != null;
        action.setText(run.isRunning() ? "Cancel run" : other ? "Another run is going" : "Run QA now");
        action.setEnabled(run.isRunning() || !other);
        action.setSelected(true);
        action.setFocusable(false);
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        button.setMargins(dp(6), dp(8), dp(6), 0);
        page.addView(action, button);

        if (error != null) page.addView(problem(error));
        if (run.error.length() > 0) page.addView(problem(run.error));

        LinearLayout times = card();
        times.addView(label("Started"));
        times.addView(lead(Words.when(run.created, now, zone)));
        if (run.ended > 0) {
            TextView ended = label("Ended");
            ended.setPadding(0, dp(6), 0, dp(3));
            times.addView(ended);
            times.addView(lead(Words.when(run.ended, now, zone)));
        }
        page.addView(times, cardSpacing());

        if (run.summary.trim().length() > 0) {
            LinearLayout summary = card();
            summary.addView(label("Summary"));
            summary.addView(lead(run.summary.trim()));
            page.addView(summary, cardSpacing());
        }

        if (!run.apps.isEmpty()) {
            LinearLayout apps = card();
            apps.addView(label("Apps"));
            for (Qa.Progress progress : run.apps) {
                apps.addView(line(tagged(progress.name(), Words.tag(progress.repo)), Words.progress(progress.status),
                    progressColor(progress.status), progress.why));
            }
            page.addView(apps, cardSpacing());
        }

        if (!run.jobs.isEmpty()) {
            LinearLayout jobs = card();
            jobs.addView(label("Jobs"));
            for (Qa.Job job : run.jobs) {
                String app = job.app.length() > 0 ? Words.name(job.app.substring(job.app.indexOf('/') + 1)) : Words.name(job.repo);
                jobs.addView(line(Words.role(job.role) + " · " + app, Words.name(job.status), jobColor(job.status), job.summary));
            }
            page.addView(jobs, cardSpacing());
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** An app's name with its tag after it, small and muted as on Home, kept on the name's line. */
    private CharSequence tagged(String name, String tag) {
        SpannableStringBuilder out = new SpannableStringBuilder(name);
        if (tag.length() == 0) return out;
        out.append("\u00A0\u00A0");
        int start = out.length();
        out.append(tag);
        out.setSpan(new AbsoluteSizeSpan(11, true), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(color(R.color.text_muted)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    /** One line of a card: what on the left, its state on the right, and why under it. */
    private View line(CharSequence what, String state, int stateColor, String why) {
        LinearLayout box = vertical();
        box.setPadding(0, dp(3), 0, dp(3));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(what, 13.5f, color(R.color.text)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(medium(state, 12, stateColor));
        box.addView(top);
        if (why != null && why.trim().length() > 0) {
            TextView under = text(why.trim(), 11.5f, color(R.color.text_muted));
            under.setPadding(0, dp(2), 0, 0);
            under.setLineSpacing(dp(1.5f), 1f);
            box.addView(under);
        }
        return box;
    }

    private TextView problem(String why) {
        TextView view = text(why, 11.5f, color(R.color.failed));
        view.setPadding(dp(10), dp(6), dp(10), 0);
        return view;
    }

    private int progressColor(String status) {
        if ("running".equals(status)) return color(R.color.focus_rule);
        if ("done".equals(status)) return color(R.color.done);
        return color(R.color.text_muted);
    }

    private int jobColor(String status) {
        if ("running".equals(status)) return color(R.color.focus_rule);
        if ("done".equals(status)) return color(R.color.done);
        if ("failed".equals(status)) return color(R.color.failed);
        return color(R.color.text_muted);
    }

    private void act() {
        if (run == null) return;
        if (run.isRunning()) cancelRun(run.id);
        else if (running() == null) startRun(null);
        else toast("A run is already going");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (Ui.isOk(key)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) act();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
            if (down) scroll.scrollBy(0, key == KeyEvent.KEYCODE_DPAD_DOWN ? dp(48) : -dp(48));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void menu() {
        if (run == null) {
            popup(new String[] {"Refresh"}, which -> load());
            return;
        }
        boolean going = run.isRunning();
        String first = going ? "Cancel run" : "Run QA now";
        popup(new String[] {first, "Refresh"}, which -> {
            if (which == 0) act();
            else load();
        });
    }
}
