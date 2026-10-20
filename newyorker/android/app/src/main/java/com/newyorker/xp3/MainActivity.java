package com.newyorker.xp3;

import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.newyorker.xp3.news.Feed;
import com.newyorker.xp3.news.Saved;
import com.newyorker.xp3.news.Site;
import com.newyorker.xp3.news.Story;
import com.xp3.common.Disk;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Today, the Magazine and Saved, as the New Yorker app's tabs, with Left and
 * Right moving between them.
 *
 * Today is the site's front page and the Magazine its current issue, each
 * read from the page's HTML. The last copy of each is kept on disk and shown
 * at once on launch while a fresh one is fetched; the list keeps its place
 * when the fresh one replaces it.
 */
public final class MainActivity extends BaseActivity {

    static final int TODAY = 0;
    static final int MAGAZINE = 1;
    static final int SAVED = 2;
    private static final String[] TABS = {"Today", "Magazine", "Saved"};
    private static final String[] PAGES = {Site.HOME, Site.MAGAZINE};
    private static final String[] CACHES = {"today.json", "magazine.json"};

    static Saved saved;

    private final ExecutorService worker = Ui.worker("pages");
    private final Feed[] feeds = new Feed[2];
    private final boolean[] loading = new boolean[2];
    private final String[] errors = new String[2];
    /** The story each tab last had under the d-pad, kept across tab changes and refreshes. */
    private final String[] places = new String[3];
    /** When each tab was last fetched, in this run of the app. */
    private final long[] fetched = new long[2];

    private int tab = TODAY;
    private TextView header;
    private View headerRule;
    private FrameLayout content;
    private ListView list;
    private StoryAdapter adapter;
    private final TextView[] tabViews = new TextView[3];

    static synchronized Saved saved(android.content.Context context) {
        if (saved == null) saved = new Saved(new File(context.getFilesDir(), "saved"));
        return saved;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = vertical();
        root.setBackgroundColor(color(R.color.page));

        header = text("", 20, color(R.color.ink));
        header.setTypeface(Look.display(this));
        header.setGravity(Gravity.CENTER);
        header.setSingleLine(true);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        headerRule = new View(this);
        headerRule.setBackgroundColor(color(R.color.rule));
        root.addView(headerRule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        content = new FrameLayout(this);
        root.addView(content, Ui.weighted());
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(R.drawable.row_selector);
        list.setDrawSelectorOnTop(false);
        list.setItemsCanFocus(false);
        list.setVerticalScrollBarEnabled(false);
        adapter = new StoryAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(adapter.story(position)));
        Ui.onSelect(list, position -> {
            Story story = adapter.story(position);
            if (story != null) places[tab] = story.url;
        });

        root.addView(tabBar());
        setContentView(root);

        for (int which = TODAY; which <= MAGAZINE; which++) {
            String cached = Disk.read(new File(getFilesDir(), CACHES[which]), 8000000);
            if (cached == null) continue;
            try {
                feeds[which] = Feed.fromJson(cached);
            } catch (RuntimeException e) {
                feeds[which] = null;
            }
        }
        show(TODAY);
        load(TODAY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // An article saved or removed while it was open shows here at once.
        if (tab == SAVED) show(SAVED);
    }

    private LinearLayout tabBar() {
        LinearLayout bar = vertical();
        View rule = new View(this);
        rule.setBackgroundColor(color(R.color.rule));
        bar.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TABS.length; i++) {
            TextView label = text(TABS[i], 11, color(R.color.muted));
            label.setGravity(Gravity.CENTER);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(26), 1));
            tabViews[i] = label;
        }
        bar.addView(row);
        return bar;
    }

    private void show(int which) {
        tab = which;
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == which;
            tabViews[i].setTextColor(color(on ? R.color.ink : R.color.muted));
            tabViews[i].setTypeface(Look.sans(this, on));
            // The open tab is underlined from above, as the app's bar marks it.
            tabViews[i].setBackground(on ? getDrawable(R.drawable.tab_on) : null);
        }
        if (which == TODAY) {
            header.setText("The New Yorker");
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        } else {
            header.setText(which == MAGAZINE ? "The Magazine" : "Saved");
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        }

        if (which == SAVED) {
            List<Story> stories = saved(this).stories();
            if (stories.isEmpty()) {
                state("Nothing saved yet", "", false);
                return;
            }
            adapter.setStories(stories);
            showList();
            return;
        }
        Feed feed = feeds[which];
        if (feed == null || feed.isEmpty()) {
            if (loading[which]) state(null, null, false);
            else if (errors[which] != null) state("Couldn’t load " + (which == TODAY ? "today’s stories" : "the issue"), errors[which], true);
            else load(which);
            return;
        }
        adapter.setFeed(which == MAGAZINE ? feed.title : "", feed, which == TODAY);
        showList();
    }

    /**
     * Back on the story last chosen in this tab; or, the first time, at the
     * top, the issue's date in view, the d-pad landing on the first story.
     * (Selecting that story outright would scroll the date off.)
     */
    private void showList() {
        Ui.fill(content, list);
        int at = places[tab] == null ? -1 : adapter.positionOf(places[tab]);
        if (at >= 0) list.setSelection(at);
        else list.setAdapter(adapter);
        list.requestFocus();
    }

    /** Loading (a spinner), or an empty or failed tab with, when `retry`, a button to try again. */
    private void state(String title, String detail, boolean retry) {
        if (title == null) {
            FrameLayout box = new FrameLayout(this);
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminateTintList(getColorStateList(R.color.ink));
            box.addView(spinner, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
            Ui.fill(content, box);
            return;
        }
        LinearLayout box = vertical();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), 0, dp(16), dp(10));
        TextView heading = Look.text(this, Look.caslon(this, Look.REGULAR), 16, R.color.ink);
        heading.setGravity(Gravity.CENTER);
        heading.setText(title);
        box.addView(heading);
        if (!detail.isEmpty()) {
            TextView line = Look.text(this, Look.sans(this, false), 11, R.color.muted);
            line.setGravity(Gravity.CENTER);
            line.setText(detail);
            line.setPadding(0, dp(6), 0, 0);
            box.addView(line);
        }
        if (retry) {
            TextView button = Look.text(this, Look.sans(this, true), 11, R.color.ink);
            button.setText("Try again");
            button.setAllCaps(true);
            button.setLetterSpacing(0.08f);
            button.setGravity(Gravity.CENTER);
            button.setBackgroundResource(R.drawable.button);
            button.setPadding(dp(16), dp(7), dp(16), dp(7));
            button.setFocusable(true);
            button.setOnFocusChangeListener((view, focused) ->
                button.setTextColor(color(focused ? R.color.page : R.color.ink)));
            final int which = tab;
            button.setOnClickListener(view -> load(which));
            LinearLayout.LayoutParams placed = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            placed.topMargin = dp(14);
            box.addView(button, placed);
            Ui.fill(content, box);
            button.requestFocus();
            return;
        }
        Ui.fill(content, box);
    }

    /** Fetches the tab's page afresh. A copy already on screen stays until the new one is in. */
    private void load(final int which) {
        if (loading[which]) return;
        loading[which] = true;
        errors[which] = null;
        if (tab == which && (feeds[which] == null || feeds[which].isEmpty())) state(null, null, false);
        worker.execute(() -> {
            Feed feed = null;
            String error = null;
            try {
                feed = Feed.parse(Net.page(PAGES[which]), Net.PICTURE_WIDTH);
                if (feed.isEmpty()) error = "The page had no stories in it.";
                else Disk.write(new File(getFilesDir(), CACHES[which]), feed.toJson(), false);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                error = e.getMessage() == null ? "Something went wrong." : e.getMessage();
            }
            final Feed result = feed;
            final String failure = error;
            runOnUiThread(() -> loaded(which, result, failure));
        });
    }

    private void loaded(int which, Feed feed, String error) {
        loading[which] = false;
        if (isDestroyed()) return;
        boolean had = feeds[which] != null && !feeds[which].isEmpty();
        if (error == null) {
            feeds[which] = feed;
            fetched[which] = System.currentTimeMillis();
        } else {
            errors[which] = error;
            if (had && tab == which) toast("Couldn’t refresh: " + error);
        }
        if (tab == which) show(which);
    }

    private void open(Story story) {
        if (story == null) return;
        places[tab] = story.url;
        startActivity(ArticleActivity.intent(this, story));
    }

    /** Taken before the views see them, so the list and the Try again button never keep Left and Right. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) {
                int step = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? TABS.length - 1 : 1;
                show((tab + step) % TABS.length);
                if (tab != SAVED && feeds[tab] != null && !loading[tab] && stale(tab)) load(tab);
            }
            return true;
        }
        if (isMenu(keyCode)) {
            if (!down && !event.isCanceled()) menu();
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)
            && list.getParent() != null && list.hasFocus() && list.getSelectedItemPosition() >= 0) {
            if (down) move(keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Up and Down on a list. The next story is chosen straight away when it
     * is already wholly on screen. Otherwise the list moves a fixed distance,
     * the same every time, rather than jumping to bring the next story into
     * full view, and the next story is chosen once it has come far enough
     * onto the screen: its top past the lower part of the screen going down,
     * its bottom past the upper part going up. Section titles and the issue's
     * date, which can't be chosen, come into view the same way.
     */
    private void move(int direction) {
        int selected = list.getSelectedItemPosition();
        int target = adapter.nextStory(selected, direction);
        int height = list.getHeight();
        View view = childAt(target);
        if (view != null && view.getTop() >= 0 && view.getBottom() <= height) {
            list.setSelectionFromTop(target, view.getTop());
            return;
        }
        list.scrollListBy(direction * Math.round(height * 0.4f));
        view = childAt(target);
        if (view == null) return;
        boolean far = direction > 0 ? view.getTop() < height * 0.6f : view.getBottom() > height * 0.4f;
        if (far) list.setSelectionFromTop(target, view.getTop());
    }

    /** The row at `position` if it is laid out on screen, else null. */
    private View childAt(int position) {
        if (position < 0) return null;
        return list.getChildAt(position - list.getFirstVisiblePosition());
    }

    static boolean isMenu(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SOFT_LEFT;
    }

    /** A tab not fetched in the last quarter of an hour is fetched again when it is opened. */
    private boolean stale(int which) {
        return System.currentTimeMillis() - fetched[which] > 15 * 60 * 1000;
    }

    private void menu() {
        View anchor = list.getParent() != null && list.getSelectedView() != null ? list.getSelectedView() : header;
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        Menu menu = popup.getMenu();
        final Story story = list.getParent() != null ? adapter.story(list.getSelectedItemPosition()) : null;
        if (story != null) {
            menu.add(0, 1, 0, "Open");
            if (tab == SAVED) menu.add(0, 2, 0, "Remove from Saved");
        }
        if (tab != SAVED) menu.add(0, 3, 0, "Refresh");
        if (menu.size() == 0) return;
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    open(story);
                    return true;
                case 2:
                    try {
                        saved(this).remove(story.url);
                    } catch (java.io.IOException e) {
                        toast("Couldn’t remove it: " + e.getMessage());
                    }
                    show(SAVED);
                    return true;
                case 3:
                    load(tab);
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
