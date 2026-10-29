package com.hbsa.xp3;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xp3.common.Http;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

/**
 * The club's events, earliest first, under a heading for each day, each
 * with its start time; then the posts that name no date. The list opens on
 * the first event that hasn't passed. The posts from last time are up at
 * once; fresh ones are fetched behind them on launch and whenever the app
 * comes back after a quarter of an hour, a thin bar along the top showing
 * it.
 */
public final class MainActivity extends BaseActivity {

    private static final long STALE_MS = 15 * 60 * 1000;

    private final ExecutorService worker = Ui.worker("instagram");
    private final Http http = new Http();
    private boolean loading;
    /** When the last fetch began, so a failing one isn't retried on every return to the list. */
    private long tried;
    /** Why the last fetch failed, or null. */
    private String error;

    private FrameLayout content;
    private ProgressBar working;
    private ListView list;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(color(R.color.page));
        content = new FrameLayout(this);
        root.addView(content);
        working = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        working.setIndeterminate(true);
        working.setIndeterminateTintList(getColorStateList(R.color.focus_rule));
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6), Gravity.TOP);
        top.topMargin = -dp(2);
        root.addView(working, top);

        list = new ListView(this);
        Family.list(list);
        list.setDivider(null);
        list.setItemsCanFocus(false);
        list.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(position));
        Ui.onSelect(list, position -> {
            adapter.notifyDataSetChanged();
            // The list scrolls only after telling of the move.
            list.post(() -> {
                if (list.getSelectedItemPosition() == position) showHeading(position);
            });
        });
        setContentView(root);

        show();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Left open overnight, "Today" moves on and the list should with it.
        if (Club.opened >= 0 && list.getParent() != null && Club.opened < adapter.getCount()) select(Club.opened);
        Club.opened = -1;
        if (!loading && System.currentTimeMillis() - Math.max(Club.fetched(this), tried) > STALE_MS) load();
    }

    private void load() {
        if (loading) return;
        loading = true;
        tried = System.currentTimeMillis();
        error = null;
        show();
        worker.execute(() -> {
            String failure = null;
            try {
                List<Post> posts = Instagram.fetch(http);
                Club.update(this, posts);
                Pictures.prune(this, posts);
            } catch (Exception e) {
                failure = e.getMessage() == null ? "Something went wrong." : e.getMessage();
            }
            final String failed = failure;
            runOnUiThread(() -> {
                loading = false;
                error = failed;
                show();
                if (failed != null && !Club.posts(this).isEmpty()) toast(failed);
            });
        });
    }

    /** The agenda, or while there is nothing to list, a spinner or why not. */
    private void show() {
        working.setVisibility(loading && list.getParent() != null ? View.VISIBLE : View.GONE);
        List<Post> posts = Club.posts(this);
        if (!posts.isEmpty()) {
            Agenda.Entry was = adapter.entry(list.getSelectedItemPosition());
            Agenda agenda = Agenda.build(posts, System.currentTimeMillis(), TimeZone.getDefault());
            Club.agenda = agenda;
            adapter.notifyDataSetChanged();
            boolean first = list.getParent() == null;
            if (first) {
                Ui.fill(content, list);
                list.requestFocus();
            }
            int at = first ? -1 : find(agenda, was);
            select(at >= 0 ? at : agenda.start);
            working.setVisibility(loading ? View.VISIBLE : View.GONE);
            return;
        }
        if (loading) {
            FrameLayout box = new FrameLayout(this);
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminateTintList(getColorStateList(R.color.focus_rule));
            box.addView(spinner, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            Ui.fill(content, box);
            return;
        }
        LinearLayout empty = vertical();
        empty.setGravity(Gravity.CENTER);
        empty.setBackgroundColor(color(R.color.row));
        TextView words = Family.empty(this, error != null ? "Couldn't reach Instagram" : "No posts yet", error);
        words.setFocusable(false);
        empty.addView(words, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button retry = new Button(this, null, 0, com.xp3.common.ui.R.style.FamilyButton);
        retry.setText("Try again");
        retry.setPadding(dp(18), 0, dp(18), 0);
        retry.setOnClickListener(v -> load());
        empty.addView(retry, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.fill(content, empty);
        retry.requestFocus();
    }

    /** The same event (or post) in a fresh agenda, or -1. */
    private static int find(Agenda agenda, Agenda.Entry was) {
        if (was == null || was.post == null) return -1;
        for (int i = 0; i < agenda.entries.size(); i++) {
            Agenda.Entry e = agenda.entries.get(i);
            if (e.post == null || !e.post.shortcode.equals(was.post.shortcode)) continue;
            if (was.event == null ? e.event == null : e.event != null && e.event.day == was.event.day) return i;
        }
        return -1;
    }

    /** Puts the d-pad on `position`, its day's heading in view above it. */
    private void select(int position) {
        boolean headed = position > 0 && adapter.entry(position - 1) != null && adapter.entry(position - 1).isHeading();
        list.setSelectionFromTop(position, headed ? dp(Adapter.HEADING_DP) : 0);
    }

    /**
     * Brings the heading above `position` into view when the d-pad has
     * moved up onto the first row of a day and left its heading above the
     * top, which the list's own scrolling does.
     */
    private void showHeading(int position) {
        Agenda.Entry above = adapter.entry(position - 1);
        if (above == null || !above.isHeading()) return;
        int first = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        if (first >= position || first == position - 1 && top != null && top.getTop() < 0) select(position);
    }

    private void open(int position) {
        Agenda.Entry entry = adapter.entry(position);
        if (entry == null || entry.isHeading()) return;
        startActivity(new Intent(this, PostActivity.class).putExtra(PostActivity.POSITION, position));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isMenu(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isMenu(keyCode)) {
            menu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    static boolean isMenu(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SOFT_LEFT;
    }

    private void menu() {
        View anchor = list.getParent() != null && list.getSelectedView() != null ? list.getSelectedView() : content;
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        popup.getMenu().add(0, 1, 0, "Refresh");
        popup.getMenu().add(0, 2, 0, "Open in browser");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) load();
            else browse(this, Instagram.PROFILE);
            return true;
        });
        popup.show();
    }

    static void browse(BaseActivity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No browser on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    /** Headings, which the d-pad passes over, and the events and posts under them. */
    private final class Adapter extends BaseAdapter {

        static final int HEADING_DP = 26;

        Agenda.Entry entry(int position) {
            Agenda agenda = Club.agenda;
            if (agenda == null || position < 0 || position >= agenda.entries.size()) return null;
            return agenda.entries.get(position);
        }

        @Override
        public int getCount() {
            return Club.agenda == null ? 0 : Club.agenda.entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entry(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            Agenda.Entry entry = entry(position);
            return entry != null && !entry.isHeading();
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return isEnabled(position) ? 1 : 0;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Agenda.Entry entry = entry(position);
            if (entry.isHeading()) {
                TextView heading = convert instanceof TextView ? (TextView) convert : heading();
                heading.setText(entry.heading.toUpperCase(Locale.US));
                heading.setTextColor(color(entry.past ? R.color.past : R.color.focus_rule));
                return heading;
            }
            Row row = convert instanceof Row ? (Row) convert : new Row();
            row.bind(entry);
            row.setSelected(position == list.getSelectedItemPosition());
            return row;
        }

        private TextView heading() {
            TextView view = text("", 11, color(R.color.focus_rule));
            view.setTypeface(Family.MEDIUM);
            view.setLetterSpacing(0.06f);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setGravity(Gravity.BOTTOM);
            view.setPadding(dp(10), 0, dp(10), dp(5));
            view.setLayoutParams(new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADING_DP)));
            return view;
        }
    }

    /** An event: its start time, its name and its post's picture. A post with no date has no time. */
    private final class Row extends LinearLayout {
        final TextView time;
        final TextView title;
        final ImageView thumb;

        Row() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackgroundResource(R.drawable.row);
            setPadding(dp(10), dp(6), dp(8), dp(6));
            setMinimumHeight(dp(48));

            time = text("", 13, color(R.color.text));
            time.setTextSize(12.5f);
            time.setTypeface(Family.MEDIUM);
            time.setSingleLine(true);
            addView(time, new LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT));

            title = text("", 12, color(R.color.text));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(dp(1), 1f);
            addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            thumb = new ImageView(MainActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundResource(R.drawable.thumb);
            thumb.setClipToOutline(true);
            LayoutParams size = new LayoutParams(dp(36), dp(36));
            size.leftMargin = dp(8);
            addView(thumb, size);
        }

        void bind(Agenda.Entry entry) {
            if (entry.event != null) {
                time.setVisibility(View.VISIBLE);
                time.setText(entry.event.minutes >= 0 ? Dates.clock(entry.event.minutes) : "—");
                title.setText(entry.event.title);
            } else {
                time.setVisibility(View.GONE);
                String caption = entry.post.caption.trim().replaceAll("\\s+", " ");
                title.setText(caption.isEmpty() ? "No caption" : caption);
            }
            int ink = color(entry.past ? R.color.past : R.color.text);
            time.setTextColor(entry.event != null && entry.event.minutes < 0 ? color(R.color.past) : ink);
            title.setTextColor(ink);
            thumb.setAlpha(entry.past ? 0.55f : 1f);
            Pictures.show(MainActivity.this, thumb, entry.post, 0, dp(36));
        }
    }
}
