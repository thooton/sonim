package com.tree.xp3;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.tree.xp3.qa.Center;
import com.tree.xp3.qa.Finding;
import com.tree.xp3.qa.Qa;
import com.tree.xp3.qa.Words;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * One app: Grow (OK switches it), Run QA now for it alone, what is known
 * of it, then its findings, newest first; OK on one opens it. Menu has the
 * same switch and run, and Refresh. The findings are asked for again each
 * time the screen comes back.
 */
public final class AppActivity extends Screen {

    static final String APP = "app";
    private static final int LIMIT = 100;

    private static final int GROW = 0;
    private static final int RUN = 1;
    private static final int FACTS = 2;
    private static final int FINDING = 3;
    private static final int STATE = 4;

    private String id;
    private List<Finding> findings;
    private String error;

    private ListView list;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra(APP);
        if (id == null) id = "";
        list = new ListView(this);
        Family.list(list);
        list.setItemsCanFocus(false);
        list.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, rowId) -> press(position));
        Ui.onSelect(list, position -> adapter.notifyDataSetChanged());
        Ui.fill(content, list);
        list.requestFocus();
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (app() == null) loadQa();
        load();
    }

    private Qa.App app() {
        return Tree.qa == null ? null : Tree.qa.app(id);
    }

    private void loadQa() {
        if (!Tree.login.ready()) return;
        work(() -> Tree.fetch(this, false), qa -> {
            Tree.qa = qa;
            Tree.fetched = System.currentTimeMillis();
            show();
        }, null);
    }

    private void load() {
        final Center center = center();
        if (center == null) return;
        work(() -> center.findings(id, LIMIT), got -> {
            findings = got;
            error = null;
            show();
        }, why -> {
            error = why;
            if (findings != null && !findings.isEmpty()) toast(why);
            show();
        });
    }

    @Override
    protected void changed() {
        show();
        loadQa();
    }

    private void show() {
        Qa.App app = app();
        String dir = id.indexOf('/') >= 0 ? id.substring(id.indexOf('/') + 1) : id;
        String repo = id.indexOf('/') >= 0 ? id.substring(0, id.indexOf('/')) : "";
        header(app != null ? app.name() : Words.name(dir), Words.tag(app != null ? app.repo : repo));
        String keep = adapter.key(list.getSelectedItemPosition());
        adapter.notifyDataSetChanged();
        int at = adapter.find(keep);
        if (at >= 0 && at != list.getSelectedItemPosition()) list.setSelection(at);
        else if (list.getSelectedItemPosition() < 0) list.setSelection(0);
        // The run button greys once a run starts: the focus leaves it.
        int now = list.getSelectedItemPosition();
        if (now >= 0 && !adapter.isEnabled(now)) list.setSelection(selectable(now));
    }

    /** The nearest row the focus can rest on, below `from` first, else above. */
    private int selectable(int from) {
        for (int i = from + 1; i < adapter.getCount(); i++) if (adapter.isEnabled(i)) return i;
        for (int i = from - 1; i >= 0; i--) if (adapter.isEnabled(i)) return i;
        return 0;
    }

    private void press(int position) {
        int type = adapter.getItemViewType(position);
        if (type == GROW) grow();
        else if (type == RUN) run();
        else if (type == FINDING) {
            Finding finding = (Finding) adapter.getItem(position);
            startActivity(new Intent(this, FindingActivity.class)
                .putExtra(FindingActivity.FINDING, finding.toJson())
                .putExtra(FindingActivity.APP, id));
        }
    }

    private void grow() {
        final Qa.App app = app();
        final Center center = center();
        if (app == null || center == null) return;
        final boolean next = !app.grow;
        work(() -> center.grow(app, next), now -> {
            if (Tree.qa != null) Tree.qa = Tree.qa.withGrow(id, now);
            toast(now ? "Growing on" : "Growing off");
            show();
        }, null);
    }

    private void run() {
        if (running() != null) return;
        startRun(Collections.singletonList(id));
    }

    @Override
    protected void menu() {
        final Qa.App app = app();
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (app != null) {
            labels.add(app.grow ? "Turn growing off" : "Turn growing on");
            actions.add(this::grow);
        }
        if (running() == null) {
            labels.add("Run QA now");
            actions.add(this::run);
        }
        labels.add("Refresh");
        actions.add(() -> {
            loadQa();
            load();
        });
        popup(labels.toArray(new String[0]), which -> actions.get(which).run());
    }

    /** "Spec written · explored Oct 27 · last run: skipped, no emulator". */
    private String facts(Qa.App app) {
        List<String> parts = new ArrayList<>();
        parts.add(app.spec ? "Spec written" : "No spec yet");
        if (app.explored > 0) parts.add("explored " + Words.when(app.explored, System.currentTimeMillis(), TimeZone.getDefault()));
        else parts.add("never explored");
        if (app.last != null && app.last.status.length() > 0) {
            String last = "last run: " + Words.progress(app.last.status).toLowerCase(java.util.Locale.US);
            if (app.last.why.trim().length() > 0) last += ", " + app.last.why.trim();
            parts.add(last);
        }
        return TextUtils.join(" · ", parts);
    }

    private final class Adapter extends BaseAdapter {

        /** Grow, Run and the facts, when the app is known. */
        private int top() {
            return app() == null ? 0 : 3;
        }

        private boolean hasFindings() {
            return findings != null && !findings.isEmpty();
        }

        String key(int position) {
            if (position < 0 || position >= getCount()) return null;
            Object item = getItem(position);
            return item instanceof Finding ? ((Finding) item).id : "row" + getItemViewType(position);
        }

        int find(String key) {
            if (key == null) return -1;
            for (int i = 0; i < getCount(); i++) if (key.equals(key(i))) return i;
            return -1;
        }

        @Override
        public int getCount() {
            return top() + (hasFindings() ? findings.size() : 1);
        }

        @Override
        public Object getItem(int position) {
            int at = position - top();
            return at >= 0 && hasFindings() ? findings.get(at) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 5;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < top()) return position;
            return hasFindings() ? FINDING : STATE;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        /** The run button is passed over while it is greyed: OK would do nothing there. */
        @Override
        public boolean isEnabled(int position) {
            int type = getItemViewType(position);
            return type == GROW || type == RUN && running() == null || type == FINDING;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            int type = getItemViewType(position);
            boolean selected = position == list.getSelectedItemPosition();
            Qa.App app = app();
            switch (type) {
                case GROW: {
                    LinearLayout row = convert != null ? (LinearLayout) convert : growRow();
                    TextView state = (TextView) row.getChildAt(1);
                    state.setText(app.grow ? "On" : "Off");
                    state.setTextColor(color(app.grow ? R.color.focus_rule : R.color.text_muted));
                    row.setSelected(selected);
                    return row;
                }
                case RUN: {
                    FrameLayout row = convert != null ? (FrameLayout) convert : runRow();
                    TextView button = (TextView) row.getChildAt(0);
                    Qa.Run going = running();
                    Qa.Progress mine = going == null ? null : going.progress(id);
                    button.setText(going == null ? "Run QA now"
                        : mine != null && !mine.finished() ? Words.progress(mine.status) : "A run is going");
                    button.setEnabled(going == null);
                    button.setSelected(selected);
                    return row;
                }
                case FACTS: {
                    TextView view = convert != null ? (TextView) convert : note();
                    view.setText(facts(app));
                    return view;
                }
                case FINDING: {
                    FindingRow row = convert instanceof FindingRow ? (FindingRow) convert : new FindingRow();
                    row.bind((Finding) getItem(position));
                    row.setSelected(selected);
                    return row;
                }
                default: {
                    TextView view = convert != null ? (TextView) convert : note();
                    view.setText(Family.emptyText(AppActivity.this,
                        error != null ? "Couldn't read the findings" : findings == null ? "Loading findings" : "No findings yet",
                        error));
                    view.setGravity(Gravity.CENTER);
                    view.setPadding(dp(10), dp(18), dp(10), dp(18));
                    return view;
                }
            }
        }
    }

    private LinearLayout growRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(com.xp3.common.ui.R.drawable.family_focus);
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setMinimumHeight(dp(38));
        row.addView(text("Grow", 13.5f, color(R.color.text)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = medium("", 13.5f, color(R.color.text_muted));
        row.addView(state);
        row.setLayoutParams(new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private FrameLayout runRow() {
        FrameLayout row = new FrameLayout(this);
        row.setBackgroundColor(color(R.color.row));
        row.setPadding(dp(6), dp(5), dp(6), dp(5));
        TextView button = new TextView(this, null, 0, com.xp3.common.ui.R.style.FamilyButton);
        button.setFocusable(false);
        row.addView(button, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        return row;
    }

    private TextView note() {
        TextView view = text("", 11.5f, color(R.color.text_muted));
        view.setBackgroundColor(color(R.color.row));
        view.setLineSpacing(dp(1.5f), 1f);
        view.setPadding(dp(10), dp(4), dp(10), dp(8));
        return view;
    }

    /** A finding: its title, then its kind, status and why on one line. */
    private final class FindingRow extends LinearLayout {
        final TextView title;
        final TextView line;

        FindingRow() {
            super(AppActivity.this);
            setOrientation(VERTICAL);
            setBackgroundResource(com.xp3.common.ui.R.drawable.family_focus);
            setPadding(dp(10), dp(7), dp(10), dp(8));
            title = medium("", 13.5f, color(R.color.text));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(dp(1.5f), 1f);
            addView(title);
            line = text("", 11.5f, color(R.color.text_muted));
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams under = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            under.topMargin = dp(3);
            addView(line, under);
        }

        void bind(Finding finding) {
            title.setText(finding.title.length() > 0 ? finding.title : "Untitled");
            SpannableStringBuilder out = new SpannableStringBuilder();
            String verdict = Words.verdict(finding.verdict);
            if (verdict.length() > 0) out.append(verdict).append(' ');
            out.append(Words.kind(finding.kind)).append(" · ");
            int start = out.length();
            out.append(Words.status(finding.status));
            out.setSpan(new ForegroundColorSpan(statusColor(AppActivity.this, finding)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new TypefaceSpan("sans-serif-medium"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            String reason = finding.reason.trim().replaceAll("\\s+", " ");
            if (reason.length() > 0) out.append(" · ").append(reason);
            line.setText(out);
        }
    }

    /**
     * Fixed is the family's green; what waits on the user (left for them, a
     * grown feature without a verdict) is the accent; what is settled the
     * other way is muted.
     */
    static int statusColor(Screen screen, Finding finding) {
        String status = finding.status;
        if ("fixed".equals(status)) return screen.getColor(R.color.done);
        if ("passed".equals(status) || finding.awaiting()) return screen.getColor(R.color.focus_rule);
        if ("open".equals(status)) return screen.getColor(R.color.text);
        return screen.getColor(R.color.text_muted);
    }
}
