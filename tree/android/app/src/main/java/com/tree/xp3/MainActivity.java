package com.tree.xp3;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tree.xp3.qa.Center;
import com.tree.xp3.qa.Qa;
import com.tree.xp3.qa.Words;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Home: the header says how the latest run stands and when the nightly one
 * is; the first row is that run (its morning summary, or its progress while
 * it goes), then every app the QA goes through. OK opens the row; Menu has
 * Run QA now (or Cancel run), Refresh, the nightly schedule and the command
 * center.
 */
public final class MainActivity extends Screen {

    /** How often the screen asks again while a run is going. */
    static final long POLL_MS = 20000;
    /** How old the QA may be when the screen comes back before it is asked for again. */
    private static final long STALE_MS = 60000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = () -> load(false);
    private boolean resumed;
    /** Why the last fetch failed, or null. */
    private String error;

    private ListView list;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        list = new ListView(this);
        Family.list(list);
        list.setItemsCanFocus(false);
        list.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(position));
        Ui.onSelect(list, position -> adapter.notifyDataSetChanged());
        show();
        if (!Tree.login.ready()) askLogin();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        show();
        if (Tree.login.ready() && !busy() && System.currentTimeMillis() - Tree.fetched > STALE_MS) load(false);
        else schedule();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(poll);
    }

    /** Asks again in a while, while a run is going and the screen is up. */
    private void schedule() {
        handler.removeCallbacks(poll);
        if (resumed && running() != null) handler.postDelayed(poll, POLL_MS);
    }

    private void load(boolean fresh) {
        handler.removeCallbacks(poll);
        if (!Tree.login.ready()) {
            show();
            return;
        }
        work(() -> Tree.fetch(this, fresh), qa -> {
            Tree.qa = qa;
            Tree.fetched = System.currentTimeMillis();
            error = null;
            show();
            if (qa.error.length() > 0) toast(qa.error);
            schedule();
        }, why -> {
            error = why;
            show();
            if (Tree.qa != null) toast(why);
            schedule();
        });
        if (Tree.qa == null) show();
    }

    @Override
    protected void changed() {
        show();
        load(false);
    }

    @Override
    protected void loggedIn() {
        load(true);
    }

    // What is on screen.

    private void show() {
        Qa qa = Tree.qa;
        if (qa == null) {
            header("", null);
            empty();
            return;
        }
        header(Words.run(qa.latest(), System.currentTimeMillis(), TimeZone.getDefault()), Words.schedule(qa.settings));
        if (qa.apps.isEmpty() && qa.latest() == null) {
            empty();
            return;
        }
        String keep = adapter.key(list.getSelectedItemPosition());
        adapter.rebuild(qa);
        boolean first = list.getParent() == null;
        if (first) {
            Ui.fill(content, list);
            list.requestFocus();
        }
        int at = adapter.find(keep);
        if (at >= 0 && at != list.getSelectedItemPosition()) list.setSelection(at);
        else if (list.getSelectedItemPosition() < 0) list.setSelection(0);
    }

    /** Nothing to list: a spinner while the first fetch runs, else why not and what to do. */
    private void empty() {
        if (busy() && Tree.qa == null) {
            FrameLayout box = new FrameLayout(this);
            box.setBackgroundColor(color(R.color.row));
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminateTintList(getColorStateList(R.color.focus_rule));
            box.addView(spinner, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            Ui.fill(content, box);
            return;
        }
        LinearLayout box = vertical();
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(color(R.color.row));
        String title = !Tree.login.ready() ? "No command center"
            : error != null ? "Couldn't reach the command center"
            : Tree.qa != null && Tree.qa.error.length() > 0 ? "Couldn't list the apps"
            : "No apps yet";
        String detail = error != null ? error : Tree.qa != null && Tree.qa.error.length() > 0 ? Tree.qa.error : null;
        TextView words = Family.empty(this, title, Tree.login.ready() ? detail : null);
        words.setFocusable(false);
        box.addView(words, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button button = new Button(this, null, 0, com.xp3.common.ui.R.style.FamilyButton);
        button.setText(Tree.login.ready() ? "Try again" : "Sign in");
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setOnClickListener(v -> {
            if (Tree.login.ready()) load(true);
            else askLogin();
        });
        box.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.fill(content, box);
        button.requestFocus();
    }

    private void open(int position) {
        Object item = adapter.getItem(position);
        if (item instanceof Qa.Run) {
            startActivity(new Intent(this, RunActivity.class).putExtra(RunActivity.RUN, ((Qa.Run) item).id));
        } else if (item instanceof Qa.App) {
            startActivity(new Intent(this, AppActivity.class).putExtra(AppActivity.APP, ((Qa.App) item).id));
        }
    }

    @Override
    protected void menu() {
        final Qa.Run run = running();
        final Qa qa = Tree.qa;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (Tree.login.ready()) {
            if (run != null) {
                labels.add("Cancel run");
                actions.add(() -> cancelRun(run.id));
            } else {
                labels.add("Run QA now");
                actions.add(() -> startRun(null));
            }
            labels.add("Refresh");
            actions.add(() -> load(true));
            if (qa != null) {
                labels.add(qa.settings.enabled ? "Turn nightly off" : "Turn nightly on");
                actions.add(() -> nightly(!qa.settings.enabled));
                labels.add("Nightly hour…");
                actions.add(this::chooseHour);
            }
        }
        labels.add("Command center…");
        actions.add(this::askLogin);
        popup(labels.toArray(new String[0]), which -> actions.get(which).run());
    }

    private void nightly(final boolean on) {
        final Center center = center();
        if (center == null) return;
        work(() -> center.nightly(on), settings -> {
            if (Tree.qa != null) Tree.qa = Tree.qa.withSettings(settings);
            toast(settings.enabled ? "Nightly on" : "Nightly off");
            show();
        }, null);
    }

    /** The 24 hours, in the command center's time zone, the current one checked. */
    private void chooseHour() {
        final Qa qa = Tree.qa;
        if (qa == null) return;
        String[] hours = new String[24];
        for (int h = 0; h < 24; h++) hours[h] = Words.hour(h);
        String zone = qa.settings.timeZone;
        new AlertDialog.Builder(this)
            .setTitle(zone.length() > 0 ? "Nightly hour, " + zone.replace('_', ' ') : "Nightly hour")
            .setSingleChoiceItems(hours, qa.settings.hour, (dialog, which) -> {
                dialog.dismiss();
                if (which == qa.settings.hour) return;
                final Center center = center();
                if (center == null) return;
                work(() -> center.hour(which), settings -> {
                    if (Tree.qa != null) Tree.qa = Tree.qa.withSettings(settings);
                    toast("Nightly at " + Words.hour(settings.hour));
                    show();
                }, null);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // The list: the latest run, then the apps.

    private final class Adapter extends BaseAdapter {

        private final List<Object> items = new ArrayList<>();

        void rebuild(Qa qa) {
            items.clear();
            Qa.Run latest = qa.latest();
            if (latest != null) items.add(latest);
            items.addAll(qa.apps);
            notifyDataSetChanged();
        }

        String key(int position) {
            Object item = position >= 0 && position < items.size() ? items.get(position) : null;
            if (item instanceof Qa.Run) return "run";
            if (item instanceof Qa.App) return ((Qa.App) item).id;
            return null;
        }

        int find(String key) {
            if (key == null) return -1;
            for (int i = 0; i < items.size(); i++) if (key.equals(key(i))) return i;
            return -1;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof Qa.Run ? 0 : 1;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Object item = items.get(position);
            Row row = convert instanceof Row ? (Row) convert : new Row();
            if (item instanceof Qa.Run) row.bind((Qa.Run) item);
            else row.bind((Qa.App) item);
            row.setSelected(position == list.getSelectedItemPosition());
            return row;
        }
    }

    /** Two lines: what leads, and what follows in muted text; an app's tag on the right. */
    private final class Row extends LinearLayout {
        final TextView first;
        final TextView tag;
        final TextView second;

        Row() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setBackgroundResource(com.xp3.common.ui.R.drawable.family_focus);
            setPadding(dp(10), dp(7), dp(10), dp(8));
            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            first = medium("", 13.5f, color(R.color.text));
            first.setSingleLine(true);
            first.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(first, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tag = text("", 11, color(R.color.text_muted));
            tag.setSingleLine(true);
            LayoutParams right = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            right.leftMargin = dp(6);
            top.addView(tag, right);
            addView(top);
            second = text("", 11.5f, color(R.color.text_muted));
            second.setLineSpacing(dp(1.5f), 1f);
            LayoutParams under = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            under.topMargin = dp(3);
            addView(second, under);
        }

        void bind(Qa.Run run) {
            tag.setText("");
            tag.setVisibility(GONE);
            if (run.isRunning()) {
                Qa.Progress now = run.current();
                first.setText(now != null ? now.name() : "refactor".equals(run.stage) ? "Refactoring" : "Starting");
                first.setTextColor(color(R.color.focus_rule));
                String started = Words.when(run.created, System.currentTimeMillis(), TimeZone.getDefault());
                String count = run.apps.isEmpty() ? "" : run.finished() + " of " + run.apps.size() + " apps";
                second.setText(join(Words.trigger(run.trigger), started.length() > 0 ? "since " + started : "", count));
                second.setMaxLines(2);
                return;
            }
            first.setTextColor(color(R.color.text));
            String summary = run.summary.trim();
            if (summary.length() > 0) {
                first.setText(firstLine(summary));
                String rest = summary.indexOf('\n') > 0 ? summary.substring(summary.indexOf('\n') + 1).trim() : "";
                second.setText(rest.length() > 0 ? rest : Words.trigger(run.trigger));
                second.setTextColor(color(R.color.text_muted));
            } else {
                first.setText("cancelled".equals(run.status) ? "Cancelled" : "No summary");
                second.setText(run.error.length() > 0 ? run.error : Words.trigger(run.trigger));
            }
            second.setMaxLines(4);
            second.setEllipsize(TextUtils.TruncateAt.END);
        }

        void bind(Qa.App app) {
            first.setText(app.name());
            first.setTextColor(color(R.color.text));
            tag.setText(Words.tag(app.repo));
            tag.setVisibility(VISIBLE);
            second.setMaxLines(2);
            second.setText(line(app));
        }
    }

    /**
     * "Grows · 2 open · 1 left for you · 1 grown", with what waits on the
     * user in the accent, led by the app's place in the run going now.
     */
    private CharSequence line(Qa.App app) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        Qa.Progress progress = Tree.qa == null ? null : Tree.qa.progress(app.id);
        if (progress != null && !progress.finished()) {
            out.append(Words.progress(progress.status));
            int color = color("running".equals(progress.status) ? R.color.focus_rule : R.color.text_muted);
            out.setSpan(new ForegroundColorSpan(color), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new TypefaceSpan("sans-serif-medium"), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (String part : Words.app(app).split(" · ")) {
            if (out.length() > 0) out.append(" · ");
            int start = out.length();
            out.append(part);
            if (part.endsWith("left for you") || part.endsWith(" grown")) {
                out.setSpan(new ForegroundColorSpan(color(R.color.focus_rule)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end).trim();
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.length() == 0) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(part);
        }
        return out.toString();
    }
}
