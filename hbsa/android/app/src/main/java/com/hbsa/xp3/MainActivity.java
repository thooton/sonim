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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.xp3.common.Http;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.SelectableAdapter;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

/**
 * The club's posts, newest first, under a gold card for the next event any
 * of them mentions. The posts from last time are up at once; fresh ones are
 * fetched behind them on launch and whenever the app comes back after a
 * quarter of an hour.
 */
public final class MainActivity extends BaseActivity {

    private static final long STALE_MS = 15 * 60 * 1000;

    private final ExecutorService worker = Ui.worker("instagram");
    private final Http http = new Http();
    private boolean loading;
    /** Why the last fetch failed, or null. */
    private String error;

    private TextView status;
    private FrameLayout content;
    private ListView list;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = vertical();
        root.setBackgroundColor(color(R.color.page));

        LinearLayout header = vertical();
        header.setBackgroundColor(color(R.color.primary));
        header.setPadding(dp(10), dp(5), dp(10), dp(7));
        TextView title = text("@" + Instagram.ACCOUNT, 15, color(R.color.on_primary));
        title.setTypeface(Family.MEDIUM);
        header.addView(title);
        status = text("", 11, color(R.color.on_primary_dim));
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(0, dp(3), 0, 0);
        header.addView(status);
        root.addView(header);

        content = new FrameLayout(this);
        root.addView(content, Ui.weighted());
        list = new ListView(this);
        Family.list(list);
        list.setItemsCanFocus(false);
        list.setVerticalScrollBarEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(adapter.post(position)));
        Ui.onSelect(list, adapter::setSelectedPosition);
        setContentView(root);

        show();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A post opened is no longer new; and an app left open overnight
        // shouldn't show yesterday's list.
        adapter.notifyDataSetChanged();
        if (!loading && System.currentTimeMillis() - Club.fetched(this) > STALE_MS) load();
        else showStatus();
    }

    private void load() {
        if (loading) return;
        loading = true;
        error = null;
        show();
        worker.execute(() -> {
            String failure = null;
            List<Post> posts = null;
            try {
                posts = Instagram.fetch(http);
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

    /** The list, or while there is nothing to list, a spinner or why not. */
    private void show() {
        List<Post> posts = Club.posts(this);
        showStatus();
        if (!posts.isEmpty()) {
            String place = adapter.selectedKey();
            adapter.set(posts, Dates.next(posts, System.currentTimeMillis(), TimeZone.getDefault()));
            if (list.getParent() == null) {
                Ui.fill(content, list);
                list.requestFocus();
            }
            int at = adapter.positionOf(place);
            list.setSelection(at >= 0 ? at : 0);
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

    private void showStatus() {
        long fetched = Club.fetched(this);
        if (loading) status.setText("Updating…");
        else if (fetched == 0) status.setText("");
        else {
            String ago = Dates.ago(fetched, System.currentTimeMillis(), TimeZone.getDefault());
            String updated = ago.equals("just now") ? "Updated just now" : "Updated " + ago;
            status.setText(error != null ? "Couldn't update · " + updated.toLowerCase(java.util.Locale.US) : updated);
        }
    }

    private void open(Post post) {
        if (post == null) return;
        List<Post> posts = Club.posts(this);
        startActivity(new Intent(this, PostActivity.class).putExtra(PostActivity.INDEX, posts.indexOf(post)));
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
        View anchor = list.getParent() != null && list.getSelectedView() != null ? list.getSelectedView() : status;
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
            android.widget.Toast.makeText(activity, "No browser on this phone", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** The next-event card, if there is an event, then a row for each post. */
    private final class Adapter extends SelectableAdapter {

        private List<Post> posts = new ArrayList<Post>();
        private Dates.Event next;

        void set(List<Post> posts, Dates.Event next) {
            this.posts = new ArrayList<Post>(posts);
            this.next = next;
            notifyDataSetChanged();
        }

        private int offset() {
            return next == null ? 0 : 1;
        }

        /** The post a position opens: the card opens the post it came from. */
        Post post(int position) {
            if (position < 0) return null;
            if (next != null && position == 0) return next.post;
            int at = position - offset();
            return at < posts.size() ? posts.get(at) : null;
        }

        /** What is under the d-pad, to find it again after a refresh. */
        String selectedKey() {
            int at = getSelectedPosition();
            if (at < 0) return null;
            if (next != null && at == 0) return "next";
            Post post = post(at);
            return post == null ? null : post.shortcode;
        }

        int positionOf(String key) {
            if (key == null) return -1;
            if (key.equals("next")) return next != null ? 0 : -1;
            for (int i = 0; i < posts.size(); i++) if (posts.get(i).shortcode.equals(key)) return i + offset();
            return -1;
        }

        @Override
        public int getCount() {
            return posts.size() + offset();
        }

        @Override
        public Object getItem(int position) {
            return post(position);
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
            return next != null && position == 0 ? 0 : 1;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View view = getItemViewType(position) == 0
                ? card(convert instanceof Card ? (Card) convert : new Card())
                : row(convert instanceof Row ? (Row) convert : new Row(), post(position));
            view.setSelected(isSelected(position));
            return view;
        }

        private View card(Card card) {
            long now = System.currentTimeMillis();
            TimeZone zone = TimeZone.getDefault();
            String[] page = Dates.page(next.day, zone);
            card.month.setText(page[0]);
            card.day.setText(page[1]);
            card.name.setText(Dates.title(next.post.caption));
            card.when.setText(Dates.when(next, now, zone));
            return card;
        }

        private View row(Row row, Post post) {
            boolean fresh = Club.isNew(MainActivity.this, post);
            row.dot.setVisibility(fresh ? View.VISIBLE : View.GONE);
            row.time.setText(Dates.ago(post.postedAt, System.currentTimeMillis(), TimeZone.getDefault()));
            row.time.setTextColor(color(fresh ? R.color.focus_rule : R.color.text_muted));
            row.time.setTypeface(fresh ? Family.MEDIUM : android.graphics.Typeface.DEFAULT);
            String caption = post.caption.trim().replaceAll("\\s+", " ");
            row.caption.setText(caption.isEmpty() ? "No caption" : caption);
            row.caption.setTextColor(color(caption.isEmpty() ? R.color.text_muted : R.color.text));
            Pictures.show(MainActivity.this, row.thumb, post, 0, dp(44));
            return row;
        }
    }

    /** The next event: a calendar page, "NEXT UP", its name and when it is. */
    private final class Card extends LinearLayout {
        final TextView month;
        final TextView day;
        final TextView name;
        final TextView when;

        Card() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackgroundResource(R.drawable.next_card);
            setPadding(dp(14), dp(12), dp(14), dp(10));

            LinearLayout page = vertical();
            page.setBackgroundResource(R.drawable.calendar_page);
            month = text("", 8, color(R.color.on_primary));
            month.setTypeface(Family.MEDIUM);
            month.setLetterSpacing(0.08f);
            month.setGravity(Gravity.CENTER);
            month.setBackgroundResource(R.drawable.calendar_month);
            page.addView(month, new LayoutParams(LayoutParams.MATCH_PARENT, dp(12)));
            day = text("", 15, color(R.color.text));
            day.setTypeface(Family.MEDIUM);
            day.setGravity(Gravity.CENTER);
            page.addView(day, new LayoutParams(LayoutParams.MATCH_PARENT, dp(21)));
            addView(page, new LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout words = vertical();
            words.setPadding(dp(9), 0, 0, 0);
            TextView label = text("NEXT UP", 9, color(R.color.gold_ink));
            label.setTypeface(Family.MEDIUM);
            label.setLetterSpacing(0.08f);
            words.addView(label);
            name = text("", 13, color(R.color.text));
            name.setTypeface(Family.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(2), 0, dp(2));
            words.addView(name);
            when = text("", 11, color(R.color.gold_muted));
            when.setSingleLine(true);
            words.addView(when);
            addView(words, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }

    /** A post: its picture, when it went up, and the start of its caption. */
    private final class Row extends LinearLayout {
        final ImageView thumb;
        final View dot;
        final TextView time;
        final TextView caption;

        Row() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setBackgroundResource(com.xp3.common.ui.R.drawable.family_focus);
            setPadding(dp(10), dp(7), dp(10), dp(7));

            thumb = new ImageView(MainActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundResource(R.drawable.thumb);
            thumb.setClipToOutline(true);
            addView(thumb, new LayoutParams(dp(44), dp(44)));

            LinearLayout words = vertical();
            words.setPadding(dp(8), 0, 0, 0);
            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            dot = new View(MainActivity.this);
            dot.setBackgroundResource(R.drawable.new_dot);
            LayoutParams dotSize = new LayoutParams(dp(6), dp(6));
            dotSize.rightMargin = dp(5);
            top.addView(dot, dotSize);
            time = text("", 11, color(R.color.text_muted));
            top.addView(time);
            words.addView(top);
            caption = text("", 12, color(R.color.text));
            caption.setMaxLines(2);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setLineSpacing(dp(1), 1f);
            caption.setPadding(0, dp(3), 0, 0);
            words.addView(caption);
            addView(words, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }
}
