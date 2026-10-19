package com.source.xp3;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.source.xp3.data.History;
import com.source.xp3.data.Recents;
import com.source.xp3.net.Books;
import com.source.xp3.net.Music;
import com.xp3.common.Login;
import com.xp3.common.ui.Ui;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The list screen: three things to fetch, a song, an album or a book, as
 * pill tabs over a search pill. Type "Artist - Title" (or "Artist - Album")
 * and press OK, and the command center searches Soulseek while its log
 * scrolls by below; then the results take the log's place, best first by the
 * music config's preferences, the first one picked. OK on a row opens its
 * page, where it is downloaded (or plays a saved one); a row being downloaded
 * ends in its state, and downloads queue up behind one another. Books work the same way, by title,
 * author or ISBN. Before a search the screen shows what is downloading, the
 * recent searches, and how full the phone is.
 *
 * Keys: OK searches, or opens the picked row; Up and Down move between the
 * box and the rows (or scroll the log); Left and Right switch between Song,
 * Album and Book while the keys aren't typing, and turn the page of book
 * results; Back stops a search, goes back to the box, or leaves; Menu opens
 * Options.
 */
public final class MainActivity extends Tonal {

    private static final String BOOK = Download.BOOK;
    private static final String[] MODES = {Music.SONG, Music.ALBUM, BOOK};
    private static final int[] TAB_NAMES = {R.string.tab_song, R.string.tab_album, R.string.tab_book};

    /** What the content area shows. */
    private static final int HOME = 0;
    private static final int LOG = 1;
    private static final int RESULTS = 2;
    private static final int EMPTY = 3;
    private static final int LOADING = 4;

    private EditText query;
    private View searchBox;
    private ImageView searchIcon;
    private View searchClear;
    private LinearLayout[] tabs;
    private ImageView[] tabIcons;
    private TextView[] tabLabels;
    private View header;
    private ProgressBar spinner;
    private TextView statusView;
    private TextView chip;
    private ScrollView home;
    private LinearLayout homeList;
    private ScrollView scroll;
    private TextView logView;
    private ListView resultList;
    private LinearLayout empty;
    private LinearLayout skeleton;
    private StorageCard storage;
    private final ResultAdapter adapter = new ResultAdapter();

    private String mode = Music.SONG;
    private int screen = -1;
    /** The row under the d-pad, or -1 while the keys go to the box. */
    private int picked = -1;
    /** What the screen was showing, so new results start at their top. */
    private Object listed;
    /** Where the d-pad was left in the results, for coming back to them from the log. */
    private Object resultsLeft;
    private int resultsPicked;
    /** A passing word for the status line, until the next search. */
    private String said = "";
    private Recents recents;
    /** The home screen's or empty state's rows that can be picked, with what OK does on each. */
    private final List<View> pickable = new ArrayList<View>();
    private final List<Runnable> actions = new ArrayList<Runnable>();
    private final List<String> verbs = new ArrayList<String>();
    private final List<Integer> verbIcons = new ArrayList<Integer>();
    /** The music results in the order they are shown, by the sort chosen in Options. */
    private List<Music.Hit> sorted = new ArrayList<Music.Hit>();
    private Music.Found sortedFrom;
    private String sortedBy = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        query = (EditText) findViewById(R.id.query);
        searchBox = findViewById(R.id.search);
        searchIcon = (ImageView) findViewById(R.id.search_icon);
        searchClear = findViewById(R.id.search_clear);
        buildTabs((LinearLayout) findViewById(R.id.tabs));
        header = findViewById(R.id.header);
        spinner = (ProgressBar) findViewById(R.id.spinner);
        statusView = (TextView) findViewById(R.id.status);
        chip = (TextView) findViewById(R.id.chip);
        home = (ScrollView) findViewById(R.id.home);
        homeList = (LinearLayout) findViewById(R.id.home_list);
        scroll = (ScrollView) findViewById(R.id.scroll);
        logView = (TextView) findViewById(R.id.log);
        resultList = (ListView) findViewById(R.id.results);
        empty = (LinearLayout) findViewById(R.id.empty);
        skeleton = (LinearLayout) findViewById(R.id.skeleton);
        resultList.setAdapter(adapter);
        resultList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        resultList.setCacheColorHint(0);
        storage = new StorageCard(this, true);
        buildSkeleton();

        for (String m : MODES) if (m.equals(prefs().getString("mode", Music.SONG))) mode = m;
        recents = Recents.parse(prefs().getString("recents", ""));

        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                begin();
                return true;
            }
            return false;
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                searchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });
        showMode();
        query.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        storage.refresh();
    }

    private boolean books() {
        return BOOK.equals(mode);
    }

    /** Only EPUBs, which reflow on a small screen, unless every format was asked for. */
    private String format() {
        return prefs().getBoolean("any_format", false) ? null : "epub";
    }

    private boolean fastest() {
        return "fast".equals(prefs().getString("sort", "best"));
    }

    /** Whether the last search was for what this tab finds. */
    private boolean mine() {
        return mode.equals(Download.searchMode());
    }

    /** This tab's music results, while they are shown rather than the log. */
    private Music.Found hits() {
        Music.Found found = Download.found();
        return !books() && Download.resultsShown() && found.mode.equals(mode) ? found : null;
    }

    /** This tab's music results, when the log is showing in their place. */
    private boolean hitsBehindLog() {
        Music.Found found = Download.found();
        return !books() && found != null && found.mode.equals(mode) && !found.hits.isEmpty() && !Download.resultsShown();
    }

    private List<Music.Hit> hitList() {
        Music.Found found = hits();
        if (found == null) return Collections.emptyList();
        String by = fastest() ? "fast" : "best";
        if (found != sortedFrom || !by.equals(sortedBy)) {
            sorted = new ArrayList<Music.Hit>(found.hits);
            // A free slot first, then the fastest: the stable sort keeps the
            // config's order among equals.
            if (fastest()) Collections.sort(sorted, (a, b) -> a.free != b.free ? (a.free ? -1 : 1) : Double.compare(b.speed, a.speed));
            sortedFrom = found;
            sortedBy = by;
        }
        return sorted;
    }

    private int screenFor() {
        if (Download.searching() && mine()) return books() ? LOADING : LOG;
        if (books()) {
            Books.Page page = Download.results();
            if (page != null && !page.books.isEmpty()) return RESULTS;
            if (mine() && (page != null || Download.searchError() != null)) return EMPTY;
            return HOME;
        }
        Music.Found found = Download.found();
        boolean ours = found != null && found.mode.equals(mode);
        if (ours && Download.resultsShown()) return RESULTS;
        if (hitsBehindLog()) return LOG;
        if (mine() && (Download.searchError() != null || ours && found.hits.isEmpty())) return EMPTY;
        if (mine() && !Download.log().isEmpty()) return LOG;
        return HOME;
    }

    /** What is on screen, for telling new results (or a new state) from those already shown. */
    private Object shownFor(int which) {
        if (which == RESULTS || which == EMPTY) {
            Object results = books() ? Download.results() : Download.found();
            return which == EMPTY && Download.searchError() != null ? Download.searchError() : results;
        }
        return which;
    }

    private int rows() {
        if (screen == RESULTS) {
            if (books()) {
                Books.Page page = Download.results();
                return page == null ? 0 : page.books.size();
            }
            return hitList().size();
        }
        if (screen == HOME || screen == EMPTY) return pickable.size();
        return 0;
    }

    @Override
    public void changed() {
        int next = screenFor();
        Object shown = shownFor(next);
        if (next != screen || shown != listed) {
            boolean back = next == RESULTS && shown == resultsLeft;
            screen = next;
            listed = shown;
            // New results start with the first picked; an empty state with its next step.
            picked = next == RESULTS ? (back ? resultsPicked : 0) : next == EMPTY ? 0 : -1;
        }
        if (screen == RESULTS) {
            resultsLeft = listed;
            if (picked >= 0) resultsPicked = picked;
        }

        home.setVisibility(screen == HOME ? View.VISIBLE : View.GONE);
        scroll.setVisibility(screen == LOG ? View.VISIBLE : View.GONE);
        resultList.setVisibility(screen == RESULTS ? View.VISIBLE : View.GONE);
        empty.setVisibility(screen == EMPTY ? View.VISIBLE : View.GONE);
        skeleton.setVisibility(screen == LOADING ? View.VISIBLE : View.GONE);
        pickable.clear();
        actions.clear();
        verbs.clear();
        verbIcons.clear();
        if (screen == HOME) showHome();
        else if (screen == EMPTY) showEmpty();
        else if (screen == LOG) showLog();
        if (picked >= rows()) picked = rows() - 1;
        adapter.notifyDataSetChanged();
        if (screen == RESULTS && picked >= 0) keepInView(resultList, picked);
        showPicked();
        showHeader();
        query.setEnabled(!Download.searching());
        query.requestFocus();
    }

    /** The row under the d-pad, on screens built of views rather than a list. */
    private void showPicked() {
        for (int i = 0; i < pickable.size(); i++) {
            View view = pickable.get(i);
            view.setActivated(i == picked);
            TextView label = (TextView) view.findViewById(android.R.id.text1);
            if (label != null) label.setTextColor(getColor(i == picked ? R.color.on_accent_container : R.color.text));
            if (i == picked && screen == HOME) {
                final View row = view;
                home.post(() -> {
                    int top = row.getTop() - dp(18);
                    int bottom = row.getBottom() + dp(4);
                    if (top < home.getScrollY()) home.smoothScrollTo(0, Math.max(0, top));
                    else if (bottom > home.getScrollY() + home.getHeight()) home.smoothScrollTo(0, bottom - home.getHeight());
                });
            }
        }
        if (screen == HOME && picked < 0) home.post(() -> home.smoothScrollTo(0, 0));
        boolean typing = picked < 0 && !Download.searching();
        searchBox.setActivated(typing);
        searchIcon.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(typing ? R.color.accent : R.color.text_muted)));
    }

    /** The status line, the spinner and the options chip. */
    private void showHeader() {
        boolean searching = Download.searching() && mine();
        String text = said;
        if (text.length() == 0 && screen != HOME && mine()) text = Download.status();
        if (text.length() == 0 && !login.ready()) text = "No command center";
        CharSequence shown = text;
        if (said.length() == 0 && screen == RESULTS && mine()) shown = found();
        statusView.setText(shown);
        statusView.setTextColor(getColor(searching ? R.color.accent : R.color.text_muted));
        spinner.setVisibility(searching ? View.VISIBLE : View.GONE);
        String option = null;
        int icon = 0;
        if (screen == RESULTS || screen == LOADING) {
            if (books()) {
                option = format() == null ? "Every format" : "EPUB first";
                icon = R.drawable.ic_book;
            } else {
                option = fastest() ? "Fastest" : "Best match";
                icon = R.drawable.ic_sort;
            }
        }
        chip.setVisibility(option == null ? View.GONE : View.VISIBLE);
        if (option != null) {
            chip.setText(option);
            chip.setCompoundDrawablesRelative(icon(icon, R.color.text_muted, 11), null, null, null);
        }
        header.setVisibility(text.length() == 0 && option == null && !searching ? View.GONE : View.VISIBLE);
    }

    // ---- The screens.

    private void showHome() {
        homeList.removeAllViews();
        List<Download.Job> active = Download.active();
        if (!active.isEmpty()) {
            section(active.size() == 1 ? "Downloading" : "Downloading, " + (active.size() - 1) + " queued");
            for (int i = 0; i < active.size() && i < 2; i++) {
                final Download.Job job = active.get(i);
                ResultRow row = new ResultRow(this);
                row.job(job, i == picked);
                pick(row, () -> openDetail(job.key), "Open", 0);
                homeList.addView(row);
            }
        }
        List<Recents.Search> searches = recents.searches();
        if (!searches.isEmpty()) {
            section("Recent");
            for (final Recents.Search search : searches) {
                homeList.addView(recentRow(search));
            }
        }
        if (storage.getParent() != null) ((ViewGroup) storage.getParent()).removeView(storage);
        LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setMargins(dp(10), dp(10), dp(10), 0);
        homeList.addView(storage, card);
        pick(storage, () -> startActivity(new Intent(this, DownloadsActivity.class)), "Downloads", R.drawable.ic_download);
    }

    private void section(String title) {
        TextView view = line(title, 10.5f, getColor(R.color.text_muted));
        view.setTypeface(MEDIUM);
        view.setPadding(dp(12), dp(8), dp(12), dp(4));
        homeList.addView(view);
    }

    private View recentRow(final Recents.Search search) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.row_pick);
        row.setPadding(dp(12), 0, dp(14), 0);
        row.setMinimumHeight(dp(34));
        ImageView icon = new ImageView(this);
        icon.setBackgroundResource(R.drawable.round_tonal);
        icon.setImageDrawable(icon(glyph(search.mode), R.color.text_muted, 12));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = line(search.query, 12.5f, getColor(R.color.text));
        label.setId(android.R.id.text1);
        label.setTypeface(BOLD);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(8), 0, dp(6), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView back = new ImageView(this);
        back.setImageDrawable(icon(R.drawable.ic_north_west, R.color.text_muted, 12));
        row.addView(back, new LinearLayout.LayoutParams(dp(12), dp(12)));
        pick(row, () -> {
            setMode(search.mode);
            query.setText(search.query);
            query.setSelection(query.length());
            begin();
        }, "Search", R.drawable.ic_search);
        return row;
    }

    private void pick(View view, Runnable action, String verb, int icon) {
        pickable.add(view);
        actions.add(action);
        verbs.add(verb);
        verbIcons.add(icon);
    }

    /** Nothing found, or the search failed: what is so, the next steps, and the log's last lines. */
    private void showEmpty() {
        empty.removeAllViews();
        final String failed = Download.searchError();
        final String what = books() ? "books" : Music.ALBUM.equals(mode) ? "albums" : "songs";
        String title;
        String detail;
        if (failed != null) {
            title = "The search failed";
            detail = failed;
        } else {
            title = "No " + what + " found";
            Music.Found found = Download.found();
            detail = !books() && found != null && found.error != null ? found.error : null;
        }
        final String again = lastQuery();
        FrameLayout blob = new FrameLayout(this);
        blob.setBackgroundResource(R.drawable.empty_blob);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(icon(failed != null ? R.drawable.ic_error : R.drawable.ic_search, R.color.on_accent_container, 24));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));
        blob.addView(icon, new FrameLayout.LayoutParams(dp(48), dp(48)));
        empty.addView(blob, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView head = line(title, 14, getColor(R.color.text));
        head.setTypeface(BOLD);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, dp(8), 0, 0);
        empty.addView(head);
        if (detail != null && detail.length() > 0) {
            TextView sub = line(detail, 11, getColor(R.color.text_muted));
            sub.setGravity(Gravity.CENTER);
            sub.setMaxLines(3);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(4), 0, 0);
            empty.addView(sub);
        }
        if (failed == null && !books() && again.length() > 0) {
            final String other = Music.ALBUM.equals(mode) ? Music.SONG : Music.ALBUM;
            empty.addView(button("Search " + (Music.ALBUM.equals(other) ? "albums" : "songs"), R.drawable.ic_search, () -> {
                setMode(other);
                query.setText(again);
                query.setSelection(query.length());
                begin();
            }, "Search"));
        }
        if (again.length() > 0) {
            empty.addView(button(failed != null ? "Try again" : "Search again", R.drawable.ic_refresh, () -> {
                query.setText(again);
                query.setSelection(query.length());
                begin();
            }, failed != null ? "Retry" : "Search"));
        }
        // The engine's last words say why.
        List<String> log = Download.log();
        if (!books() && !log.isEmpty()) {
            StringBuilder tail = new StringBuilder();
            for (int i = Math.max(0, log.size() - 6); i < log.size(); i++) {
                if (tail.length() > 0) tail.append('\n');
                tail.append(log.get(i));
            }
            TextView view = line(tail, 10, getColor(R.color.text_muted));
            view.setTypeface(android.graphics.Typeface.MONOSPACE);
            view.setBackgroundResource(R.drawable.log_card);
            view.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            size.topMargin = dp(10);
            empty.addView(view, size);
        }
    }

    /** What the last search of this tab was for. */
    private String lastQuery() {
        if (books()) {
            Books.Page page = Download.results();
            if (page != null) return page.query;
        } else {
            Music.Found found = Download.found();
            if (found != null && found.mode.equals(mode)) return found.query;
        }
        return query.getText().toString().trim();
    }

    private View button(String label, int icon, Runnable action, String verb) {
        TextView view = line(label, 12.5f, getColor(R.color.text));
        view.setId(android.R.id.text1);
        view.setTypeface(BOLD);
        view.setBackgroundResource(R.drawable.button_pick);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setCompoundDrawablePadding(dp(6));
        view.setCompoundDrawablesRelative(icon(icon, R.color.on_accent_container, 14), null, null, null);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.topMargin = dp(8);
        view.setLayoutParams(size);
        pick(view, action, verb, icon);
        return view;
    }

    private TextView line(CharSequence value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void showLog() {
        StringBuilder text = new StringBuilder();
        for (String line : Download.log()) {
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        // Downloads change the screen twice a second; only new lines move the log.
        if (text.toString().contentEquals(logView.getText())) return;
        logView.setText(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Book results on their way: four rows the shape of results, which never change. */
    private void buildSkeleton() {
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(7), dp(12), dp(7));
            View cover = new View(this);
            cover.setBackgroundResource(R.drawable.skeleton);
            row.addView(cover, new LinearLayout.LayoutParams(dp(22), dp(32)));
            LinearLayout lines = new LinearLayout(this);
            lines.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams linesSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            linesSize.leftMargin = dp(10);
            linesSize.rightMargin = dp(10);
            row.addView(lines, linesSize);
            int[] widths = {new int[] {120, 96, 136, 108}[i], new int[] {70, 84, 60, 76}[i]};
            for (int j = 0; j < 2; j++) {
                View bar = new View(this);
                bar.setBackgroundResource(R.drawable.skeleton);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(widths[j]), dp(j == 0 ? 9 : 7));
                size.bottomMargin = j == 0 ? dp(6) : 0;
                lines.addView(bar, size);
            }
            View round = new View(this);
            round.setBackgroundResource(R.drawable.round_tonal);
            row.addView(round, new LinearLayout.LayoutParams(dp(26), dp(26)));
            skeleton.addView(row);
        }
    }

    // ---- Modes.

    /** Song, Album and Book: each a pill with its icon beside its name, centred. */
    private void buildTabs(LinearLayout track) {
        tabs = new LinearLayout[MODES.length];
        tabIcons = new ImageView[MODES.length];
        tabLabels = new TextView[MODES.length];
        for (int i = 0; i < MODES.length; i++) {
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setGravity(Gravity.CENTER);
            tab.setBackgroundResource(R.drawable.tab_pill);
            ImageView icon = new ImageView(this);
            tab.addView(icon, new LinearLayout.LayoutParams(dp(12), dp(12)));
            TextView label = line(getString(TAB_NAMES[i]), 11.5f, 0);
            label.setSingleLine(true);
            label.setPadding(dp(4), 0, 0, 0);
            tab.addView(label);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) size.leftMargin = dp(2);
            track.addView(tab, size);
            tabs[i] = tab;
            tabIcons[i] = icon;
            tabLabels[i] = label;
        }
    }

    /** "Songs · 24 found", the count in the text's own colour. */
    private CharSequence found() {
        String what = books() ? "Books" : Music.ALBUM.equals(mode) ? "Albums" : "Songs";
        String count;
        if (books()) {
            Books.Page page = Download.results();
            count = page == null ? "" : page.pages > 1 ? "page " + page.page + " of " + page.pages : page.books.size() + " found";
        } else {
            count = hitList().size() + " found";
        }
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(what + " · ");
        int start = out.length();
        out.append(count);
        out.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.text)), start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new Face(BOLD), start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private void showMode() {
        for (int i = 0; i < MODES.length; i++) {
            boolean on = MODES[i].equals(mode);
            int ink = getColor(on ? R.color.on_accent_container : R.color.text_muted);
            tabs[i].setSelected(on);
            tabLabels[i].setTextColor(ink);
            tabLabels[i].setTypeface(on ? BOLD : MEDIUM);
            tabIcons[i].setImageDrawable(icon(glyph(MODES[i]), on ? R.color.on_accent_container : R.color.text_muted, 12));
        }
        if (Music.ALBUM.equals(mode)) query.setHint(R.string.hint_album);
        else if (books()) query.setHint(R.string.hint_book);
        else query.setHint(R.string.hint_song);
    }

    /** Song, Album, Book and round again; `step` is 1 or -1. */
    private void cycleMode(int step) {
        int i = 0;
        while (!MODES[i].equals(mode)) i++;
        setMode(MODES[(i + step + MODES.length) % MODES.length]);
    }

    private void setMode(String next) {
        if (next.equals(mode)) return;
        mode = next;
        said = "";
        prefs().edit().putString("mode", mode).apply();
        showMode();
        // The new tab's results, if any, wait unpicked under the box.
        screen = screenFor();
        listed = shownFor(screen);
        picked = -1;
        changed();
    }

    // ---- Doing things.

    private void say(String text) {
        said = text;
        showHeader();
    }

    private void begin() {
        if (Download.searching()) return;
        String text = query.getText().toString().trim();
        if (text.length() == 0) {
            if (hitsBehindLog()) backToResults();
            return;
        }
        if (!login.ready()) {
            askLogin();
            return;
        }
        if (!books() && text.startsWith("-")) {
            say("A name can't start with \"-\".");
            return;
        }
        said = "";
        recents.add(mode, text);
        prefs().edit().putString("recents", recents.toJson()).apply();
        if (books()) Download.search(login, text, 1, format());
        else Download.findMusic(login, mode, text);
    }

    private void ok() {
        if (Download.searching() && mine()) {
            Download.cancelSearch();
            return;
        }
        if (picked < 0) {
            begin();
            return;
        }
        if (screen == RESULTS) {
            act(picked);
        } else if (picked < actions.size()) {
            actions.get(picked).run();
        }
    }

    /** The key of the result at `i`: a music result's link, or a book's id and hash. */
    private String keyAt(int i) {
        if (books()) {
            Books.Page page = Download.results();
            return page == null || i < 0 || i >= page.books.size() ? "" : Download.key(page.books.get(i));
        }
        List<Music.Hit> list = hitList();
        return i < 0 || i >= list.size() ? "" : list.get(i).link;
    }

    /**
     * OK on a result: play or read it once saved, else open its page, where
     * it can be looked over before it is downloaded.
     */
    private void act(int i) {
        String key = keyAt(i);
        if (key.length() == 0) return;
        if (Download.state(key) == Download.SAVED) open(this, key);
        else openDetail(key);
    }

    /** Plays or reads what was saved for `key`. */
    static void open(Tonal activity, String key) {
        Download.Job job = Download.job(key);
        List<String> files = job != null && job.state == Download.SAVED ? job.files : null;
        String kind = job != null ? job.kind : null;
        if (files == null) {
            History.Entry entry = Download.saved(key);
            if (entry == null) {
                activity.note("It's no longer on the phone");
                return;
            }
            files = entry.files;
            kind = entry.kind;
        }
        if (files.isEmpty()) return;
        if (BOOK.equals(kind)) Files.read(activity, files.get(0));
        else Files.play(activity, Files.firstAudio(files));
    }

    private void openDetail(String key) {
        startActivity(new Intent(this, DetailActivity.class).putExtra(DetailActivity.KEY, key));
    }

    /** From the log back to the music results, where they were left. */
    private void backToResults() {
        Download.showResults(true);
    }

    private void turnPage(int step) {
        Books.Page page = Download.results();
        if (Download.searching() || page == null || !login.ready()) return;
        int next = page.page + step;
        if (next < 1 || next > page.pages) {
            say(next < 1 ? "This is the first page." : "This is the last page.");
            return;
        }
        Download.search(login, page.query, next, page.format);
    }

    private void movePick(int step) {
        if (screen == LOG && picked < 0) {
            scroll.smoothScrollBy(0, step * dp(40));
            return;
        }
        int count = rows();
        if (count == 0) return;
        int next = picked + step;
        picked = next < 0 ? -1 : Math.min(next, count - 1);
        if (screen == RESULTS) {
            if (picked >= 0) resultsPicked = picked;
            adapter.notifyDataSetChanged();
            if (picked >= 0) keepInView(resultList, picked);
        }
        showPicked();
    }

    /** What Back does here, or null when it leaves. */
    private Runnable back() {
        if (Download.searching()) return () -> Download.cancelSearch();
        if (picked >= 0) return () -> movePick(-picked - 1);
        if (hitsBehindLog()) return () -> backToResults();
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        if (Ui.isOk(key)) {
            // On the press: T9 eats the first release.
            if (first) ok();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_MENU:
                if (!down) options();
                return true;
            case KeyEvent.KEYCODE_BACK: {
                Runnable back = back();
                if (back == null) break;
                if (!down) back.run();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) movePick(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (picked >= 0 || query.length() == 0 || Download.searching()) {
                    int step = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                    if (first) {
                        if (picked >= 0 && screen == RESULTS && books()) turnPage(step);
                        else cycleMode(step);
                    }
                    return true;
                }
                break;
            default:
                // Anything else is typing: back to the box with it.
                boolean typing = event.isPrintingKey() || key == KeyEvent.KEYCODE_DEL;
                if (down && picked >= 0 && typing && !Download.searching()) {
                    picked = -1;
                    adapter.notifyDataSetChanged();
                    showPicked();
                }
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Options.

    private void options() {
        Sheet sheet = new Sheet(this);
        if (screen == RESULTS && picked >= 0) {
            final String key = keyAt(picked);
            sheet.add(R.drawable.ic_info, "Details", null, () -> openDetail(key));
            final Download.Job job = Download.job(key);
            if (job != null && (job.state == Download.QUEUED || job.state == Download.RUNNING)) {
                sheet.add(R.drawable.ic_close, "Cancel download", null, () -> Download.cancel(job));
            }
        }
        int active = Download.active().size();
        sheet.add(R.drawable.ic_download, "Downloads", active == 0 ? null : active + " to go",
            () -> startActivity(new Intent(this, DownloadsActivity.class)));
        if (books()) {
            final Books.Page page = Download.results();
            if (page != null && page.page < page.pages) {
                sheet.add(R.drawable.ic_next, "Next page", page.page + 1 + " of " + page.pages, () -> turnPage(1));
            }
            if (page != null && page.page > 1) {
                sheet.add(R.drawable.ic_prev, "Previous page", page.page - 1 + " of " + page.pages, () -> turnPage(-1));
            }
            final boolean any = format() == null;
            sheet.add(R.drawable.ic_book, "Formats", any ? "Every format" : "EPUB first", () -> {
                prefs().edit().putBoolean("any_format", !any).apply();
                changed();
            });
            if (page != null && !Download.searching()) {
                sheet.add(R.drawable.ic_clear_all, "Clear results", null, () -> Download.clearResults());
            }
        } else {
            final boolean fast = fastest();
            sheet.add(R.drawable.ic_sort, "Sort by", fast ? "Fastest" : "Best match", () -> {
                prefs().edit().putString("sort", fast ? "best" : "fast").apply();
                changed();
            });
            if (hitsBehindLog()) {
                sheet.add(R.drawable.ic_article, "Show results", null, () -> backToResults());
            } else if (hits() != null) {
                sheet.add(R.drawable.ic_article, "Show log", null, () -> Download.showResults(false));
            }
            if (Download.found() != null && !Download.searching()) {
                sheet.add(R.drawable.ic_clear_all, "Clear results", null, () -> Download.clearFound());
            }
            if (!Download.log().isEmpty() && !Download.searching()) {
                sheet.add(R.drawable.ic_clear_all, "Clear log", null, () -> Download.clearLog());
            }
            sheet.add(R.drawable.ic_sync, "Reconnect music account", null, () -> confirmRelogin());
        }
        if (!recents.searches().isEmpty()) {
            sheet.add(R.drawable.ic_history, "Clear recent searches", null, () -> {
                recents.clear();
                prefs().edit().putString("recents", recents.toJson()).apply();
                changed();
            });
        }
        final boolean dark = dark(this);
        sheet.add(R.drawable.ic_dark_mode, "Theme", dark ? "Dark" : "Light", () -> setDark(!dark));
        sheet.add(R.drawable.ic_dns, "Command center", login.ready() ? login.host() : null, () -> askLogin());
        sheet.show();
    }

    /** As `delta music re-login`: only when downloads keep failing to connect. */
    private void confirmRelogin() {
        if (!login.ready()) {
            askLogin();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Reconnect music account?")
            .setPositiveButton("Reconnect", (dialog, which) -> relogin())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void relogin() {
        say("Reconnecting…");
        final Login using = login;
        new Thread(() -> {
            String result;
            try {
                new Music(using).relogin();
                result = "Reconnected";
            } catch (Exception e) {
                result = "Couldn't reconnect: " + e.getMessage();
            }
            final String shown = result;
            runOnUiThread(() -> say(shown));
        }).start();
    }

    /** The fallback for a build without a bundled login. */
    private void askLogin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText server = new EditText(this);
        server.setHint("https://…");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(login.server() == null ? "" : login.server());
        final EditText token = new EditText(this);
        token.setHint(login.ready() ? "••••••••" : "Token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(server);
        box.addView(token);
        new AlertDialog.Builder(this)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", (dialog, which) -> {
                String typed = token.getText().toString();
                if (typed.trim().length() == 0 && login.ready()) typed = login.token();
                try {
                    Login next = Login.of(server.getText().toString(), typed);
                    if (!next.ready()) throw new IllegalArgumentException("Both the address and the token are needed");
                    login = next;
                    prefs().edit().putString("login", next.toJson()).apply();
                    say("Saved: " + next.host());
                } catch (IllegalArgumentException e) {
                    say(e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** The results, songs, albums or books, each ending in its state button. */
    private final class ResultAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return screen == RESULTS ? rows() : 0;
        }

        @Override
        public Object getItem(int position) {
            return keyAt(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            ResultRow row = convert instanceof ResultRow ? (ResultRow) convert : new ResultRow(MainActivity.this);
            String key = keyAt(position);
            int state = Download.state(key);
            Download.Job job = Download.job(key);
            float progress = job == null ? -1 : job.progress();
            boolean on = position == picked;
            if (books()) row.book(Download.results().books.get(position), state, progress, on);
            else row.hit(hitList().get(position), state, progress, on);
            return row;
        }
    }
}
