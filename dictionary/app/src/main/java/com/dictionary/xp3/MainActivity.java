package com.dictionary.xp3;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The dictionary: home, a word's page, and the search, under a search capsule.
 *
 * Home is the Word of the Day; OK opens it. A word's page has the headword and up to three
 * tabs, Definitions, Thesaurus and Shorthand (its Gregg outlines in Grascii),
 * which Left and Right switch between. Up and
 * Down scroll a page, and Up from its top moves into the search box. Menu
 * opens a menu over the page: the Word of the Day or Search at home,
 * Pronounce or Search on a word. OK opens nothing on a word. The search
 * lists recent searches and, as a query is typed, the words it could mean;
 * the shorthand search (Shorthand in the menu, or Up from a Shorthand tab)
 * reads it instead as a Grascii outline and lists the words it could be.
 * Menu while searching switches between the two. OK opens the row picked,
 * or the best match. Back closes the search, goes back a page, and leaves
 * from home.
 */
public class MainActivity extends Activity {

    private static final int HOME = -1;
    private static final int RECENTS = 20;
    private static final int SUGGESTIONS = 8;
    private static final int OUTLINES = 12;
    /** In a list of rows, the Clear button rather than a word. */
    private static final int CLEAR = -2;
    /**
     * Pages from here down are words only the shorthand dictionary has ("could",
     * "theirs"): page ONLY_SHORTHAND - g is its group g.
     */
    private static final int ONLY_SHORTHAND = -10;

    private static final int DEFINITIONS = 0;
    private static final int THESAURUS = 1;
    private static final int SHORTHAND = 2;

    private Dictionary dictionary;
    private Shorthand shorthand;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean speaks;

    private int page = HOME;
    private final List<Integer> back = new ArrayList<>();
    private int tab = DEFINITIONS;
    private boolean searching;
    /** Whether the search reads the query as a shorthand outline rather than a word. */
    private boolean outlineSearch;
    private int wordOfTheDay;

    // The search's rows: pages, or CLEAR, and the tab each opens on; and
    // which one the d-pad is on.
    private final List<Integer> items = new ArrayList<>();
    private final List<Integer> itemTabs = new ArrayList<>();
    private final List<View> itemViews = new ArrayList<>();
    private int picked = -1;

    // The key whose press this activity saw, so that a release only acts
    // when its press did too (not the end of a press made elsewhere).
    private int pressed = -1;

    private Typeface sans;
    private Typeface sansItalic;
    private Typeface semibold;
    private Typeface semiboldItalic;
    private Typeface serif;

    private ImageView nav;
    private EditText search;
    private ImageView searchIcon;
    private ScrollView home;
    private ScrollView entry;
    private TextView headword;
    private TextView tabDefinitions;
    private TextView tabThesaurus;
    private TextView tabShorthand;
    private TextView body;
    private ScrollView results;
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        prefs = getPreferences(MODE_PRIVATE);
        sans = getResources().getFont(R.font.plex_sans);
        sansItalic = getResources().getFont(R.font.plex_sans_italic);
        semibold = getResources().getFont(R.font.plex_sans_semibold);
        semiboldItalic = getResources().getFont(R.font.plex_sans_semibold_italic);
        serif = getResources().getFont(R.font.plex_serif_semibold);

        nav = findViewById(R.id.nav);
        search = findViewById(R.id.search);
        searchIcon = findViewById(R.id.search_icon);
        home = findViewById(R.id.home);
        entry = findViewById(R.id.entry);
        headword = findViewById(R.id.headword);
        tabDefinitions = findViewById(R.id.tab_definitions);
        tabThesaurus = findViewById(R.id.tab_thesaurus);
        tabShorthand = findViewById(R.id.tab_shorthand);
        body = findViewById(R.id.body);
        results = findViewById(R.id.results);
        rows = findViewById(R.id.rows);

        headword.setTypeface(serif);
        this.<TextView>findViewById(R.id.wotd_label).setTypeface(semibold);
        this.<TextView>findViewById(R.id.wotd_word).setTypeface(semibold);
        TextView link = findViewById(R.id.wotd_link);
        link.getPaint().setUnderlineText(true);

        try {
            dictionary = new Dictionary(getAssets());
            shorthand = new Shorthand(getAssets());
        } catch (IOException e) {
            throw new IllegalStateException("The bundled dictionary is unreadable", e);
        }

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (searching) showRows();
            }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            choose();
            return true;
        });

        showWordOfTheDay();
        show();

        // Pronounce works once the phone's text-to-speech says it can speak
        // English.
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                speaks = tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE;
            }
        });
    }

    /**
     * Launched again from its icon while still running (singleTask delivers
     * it here): it opens on home with an empty Back history, as a fresh start.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (searching) endSearch();
        back.clear();
        page = HOME;
        tab = DEFINITIONS;
        show();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        tts = null;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        boolean released = !down && pressed == key && !event.isCanceled();
        if (first) pressed = key;
        else if (!down) pressed = -1;

        if (searching) {
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) pick(key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                return true;
            }
            // OK acts on its press: T9 can swallow the first release.
            if (isOk(key)) {
                if (first) choose();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MENU) {
                if (released) showSearchActions();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) {
                if (released) closeSearch();
                return true;
            }
            if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) && picked >= 0) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) move(key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1, first);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (first && page != HOME) {
                TextView[] tabs = {tabDefinitions, tabThesaurus, tabShorthand};
                int step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                for (int next = tab + step; next >= 0 && next < tabs.length; next += step) {
                    if (tabs[next].getVisibility() == View.VISIBLE) {
                        tab = next;
                        showEntry();
                        break;
                    }
                }
            }
            return true;
        }
        // OK opens the Word of the Day at home, and nothing on a word.
        if (isOk(key)) {
            if (first && page == HOME) open(wordOfTheDay, DEFINITIONS);
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            // On the release, so it does not land on the menu just opened.
            if (released) showActions();
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            if (released) goBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isOk(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER
            || key == KeyEvent.KEYCODE_ENTER
            || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /**
     * Up and Down off the search: a screenful at a time, and Up from the top
     * into the search box (on a fresh press, so holding Up to get to the top
     * stops there).
     */
    private void move(int direction, boolean first) {
        ScrollView scroll = page == HOME ? home : entry;
        if (direction < 0 && scroll.getScrollY() == 0) {
            // From a word's Shorthand tab, into the shorthand search.
            if (first) openSearch(page != HOME && tab == SHORTHAND);
            return;
        }
        int line = body.getLineHeight();
        int step = Math.max(scroll.getHeight() - 2 * line, line);
        scroll.smoothScrollBy(0, direction * step);
    }

    // Pages

    private void open(int index, int onTab) {
        if (index != page) back.add(page);
        page = index;
        tab = onTab;
        show();
    }

    private void goBack() {
        if (back.isEmpty()) {
            finish();
            return;
        }
        page = back.remove(back.size() - 1);
        tab = DEFINITIONS;
        show();
    }

    private void show() {
        // The back arrow shows only where Back has somewhere to go.
        nav.setVisibility(searching || page != HOME ? View.VISIBLE : View.GONE);
        home.setVisibility(page == HOME && !searching ? View.VISIBLE : View.GONE);
        entry.setVisibility(page != HOME && !searching ? View.VISIBLE : View.GONE);
        results.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (searching) return;
        if (page == HOME) {
            home.scrollTo(0, 0);
            home.requestFocus();
        } else {
            showEntry();
            entry.requestFocus();
        }
    }

    private void showEntry() {
        boolean wordNet = page >= 0;
        List<Dictionary.Sense> senses = wordNet ? dictionary.senses(page) : new ArrayList<>();
        int group = group(page);
        String word = wordNet ? display(page, senses) : shorthand.name(group);
        headword.setText(word);
        fitHeadword(word);
        // A word WordNet gives no synonyms for has no Thesaurus tab, one
        // Grascii has no outline for no Shorthand tab, and one only Grascii
        // has no Definitions.
        boolean any = false;
        for (Dictionary.Sense sense : senses) any |= !synonyms(word, sense).isEmpty();
        boolean[] shown = {wordNet, any, group >= 0};
        if (!shown[tab]) tab = wordNet ? DEFINITIONS : SHORTHAND;
        TextView[] tabs = {tabDefinitions, tabThesaurus, tabShorthand};
        boolean firstShown = true;
        for (int t = 0; t < tabs.length; t++) {
            tabs[t].setVisibility(shown[t] ? View.VISIBLE : View.GONE);
            ((LinearLayout.LayoutParams) tabs[t].getLayoutParams()).leftMargin = firstShown ? 0 : dp(2);
            firstShown &= !shown[t];
            styleTab(tabs[t], t == tab);
        }
        if (tab == THESAURUS) body.setText(thesaurus(word, senses));
        else if (tab == SHORTHAND) body.setText(outlines(group));
        else body.setText(definitions(senses));
        entry.scrollTo(0, 0);
    }

    /**
     * The headword at 25sp, or smaller when one of its words is wider than the
     * line: a word with no break in it would otherwise wrap mid-word.
     */
    private void fitHeadword(String word) {
        float width = getResources().getDisplayMetrics().widthPixels
            - headword.getTotalPaddingLeft() - headword.getTotalPaddingRight() - dp(24);
        TextPaint paint = new TextPaint(headword.getPaint());
        float size = sp(25);
        paint.setTextSize(size);
        for (String part : word.split("(?<=-)| ")) {
            while (size > sp(10) && paint.measureText(part) > width) paint.setTextSize(--size);
        }
        headword.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    private void styleTab(TextView tab, boolean open) {
        tab.setTypeface(open ? semibold : sans);
        tab.setBackgroundResource(open ? R.drawable.tab_selected : 0);
    }

    /** The shorthand group for a page, or -1 for a word Grascii has no outline for. */
    private int group(int page) {
        if (page <= ONLY_SHORTHAND) return ONLY_SHORTHAND - page;
        return shorthand.find(dictionary.word(page));
    }

    /** The page a shorthand group opens: the word's own page when WordNet has it. */
    private int pageFor(int group) {
        String key = shorthand.key(group);
        int at = dictionary.lowerBound(key);
        if (at < dictionary.size() && dictionary.word(at).equals(key)) return at;
        return ONLY_SHORTHAND - group;
    }

    /** A page's word, as the search and its recents compare them. */
    private String key(int page) {
        return page <= ONLY_SHORTHAND ? shorthand.key(ONLY_SHORTHAND - page) : dictionary.word(page);
    }

    /** A page's word, as the page writes it. */
    private String name(int page) {
        return page <= ONLY_SHORTHAND ? shorthand.name(ONLY_SHORTHAND - page) : display(page, null);
    }

    /** The page for a word, either dictionary's, or -1. */
    private int pageOf(String word) {
        int at = dictionary.lowerBound(word);
        if (at < dictionary.size() && dictionary.word(at).equals(word)) return at;
        int group = shorthand.find(word);
        return group >= 0 ? ONLY_SHORTHAND - group : -1;
    }

    /**
     * Menu on a page: a menu under the search capsule, where it has the
     * screen to itself (from the Word of the Day card it was squeezed into
     * what was left below it). At home, the Word of the Day, Search or
     * Shorthand; on a word, Pronounce (greyed when the phone cannot speak),
     * Search or Shorthand.
     */
    private void showActions() {
        boolean atHome = page == HOME;
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.pill));
        if (atHome) menu.getMenu().add(0, R.string.word_of_the_day_item, 0, R.string.word_of_the_day_item);
        else menu.getMenu().add(0, R.string.pronounce, 0, R.string.pronounce).setEnabled(speaks);
        menu.getMenu().add(0, R.string.search, 1, R.string.search);
        menu.getMenu().add(0, R.string.shorthand, 2, R.string.shorthand);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.string.pronounce) tts.speak(headword.getText(), TextToSpeech.QUEUE_FLUSH, null, "word");
            else if (id == R.string.word_of_the_day_item) open(wordOfTheDay, DEFINITIONS);
            else openSearch(id == R.string.shorthand);
            return true;
        });
        menu.show();
    }

    /** Menu while searching: switch to the other search, keeping what was typed. */
    private void showSearchActions() {
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.pill));
        int other = outlineSearch ? R.string.search_words : R.string.shorthand;
        menu.getMenu().add(0, other, 0, other);
        menu.setOnMenuItemClickListener(item -> {
            setOutlineSearch(!outlineSearch);
            showRows();
            return true;
        });
        menu.show();
    }

    /**
     * The shorthand search looks its part: a pen in the search button, the
     * outline's format as the hint, and capitals as Grascii is written.
     */
    private void setOutlineSearch(boolean outlines) {
        outlineSearch = outlines;
        searchIcon.setImageResource(outlines ? R.drawable.ic_shorthand_button : R.drawable.ic_search);
        search.setHint(outlines ? R.string.outline_hint : R.string.search_hint);
        int selection = search.getSelectionEnd();
        search.setInputType(outlines
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            : InputType.TYPE_CLASS_TEXT);
        if (selection >= 0) search.setSelection(Math.min(selection, search.length()));
    }

    private void showWordOfTheDay() {
        long now = System.currentTimeMillis();
        long day = (now + TimeZone.getDefault().getOffset(now)) / 86400000L;
        wordOfTheDay = dictionary.wordOfTheDay(day);
        List<Dictionary.Sense> senses = dictionary.senses(wordOfTheDay);
        this.<TextView>findViewById(R.id.wotd_date).setText(
            new SimpleDateFormat("MMMM d, yyyy", Locale.US).format(new Date(now)).toUpperCase(Locale.US));
        this.<TextView>findViewById(R.id.wotd_word).setText(display(wordOfTheDay, senses));
        TextView pos = findViewById(R.id.wotd_pos);
        pos.setTypeface(sansItalic);
        pos.setText(label(senses.get(0).pos));
        this.<TextView>findViewById(R.id.wotd_gloss).setText(definition(senses.get(0).gloss));
    }

    // Search

    private void openSearch(boolean outlines) {
        searching = true;
        setOutlineSearch(outlines);
        show();
        search.setFocusable(true);
        search.setFocusableInTouchMode(true);
        search.setText("");
        search.setTextColor(getColor(R.color.ink));
        search.requestFocus();
        showRows();
    }

    private void closeSearch() {
        endSearch();
        show();
    }

    private void endSearch() {
        searching = false;
        setOutlineSearch(false);
        search.setText("");
        search.setFocusable(false);
        search.setFocusableInTouchMode(false);
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /**
     * OK while searching: the row picked, or else the best match for the
     * query: the word, or in the shorthand search its first row.
     */
    private void choose() {
        if (picked >= 0) {
            int item = items.get(picked);
            if (item == CLEAR) {
                prefs.edit().remove("recent").apply();
                showRows();
            } else {
                found(item, itemTabs.get(picked));
            }
            return;
        }
        String query = search.getText().toString();
        if (Dictionary.normalize(query).isEmpty()) return;
        if (outlineSearch) {
            // The first row: the best reading, or a phrase's first that opens.
            if (!items.isEmpty()) found(items.get(0), itemTabs.get(0));
            return;
        }
        int found = dictionary.find(query);
        String q = Dictionary.normalize(query);
        if (found < 0 || (!dictionary.word(found).equals(q) && dictionary.word(found).startsWith(q))) {
            // Only a word it begins: a word only the shorthand has (into) comes first.
            int exact = pageOf(q);
            if (exact >= 0) found = exact;
        }
        if (found >= 0) {
            found(found, DEFINITIONS);
            return;
        }
        List<Integer> suggestions = suggest(query);
        if (!suggestions.isEmpty()) found(suggestions.get(0), DEFINITIONS);
    }

    private void found(int index, int onTab) {
        remember(key(index));
        endSearch();
        open(index, onTab);
    }

    /**
     * The words to offer for a query: WordNet's, with those only the
     * shorthand dictionary has ("could", "theirs") among them in order.
     */
    private List<Integer> suggest(String query) {
        List<Integer> out = dictionary.suggest(query, SUGGESTIONS);
        String q = Dictionary.normalize(query);
        // A base form WordNet puts first (mice: mouse) stays first.
        int from = !out.isEmpty() && !key(out.get(0)).startsWith(q) ? 1 : 0;
        for (int group : shorthand.prefixed(query, SUGGESTIONS)) {
            int page = pageFor(group);
            if (page >= 0) continue;
            int at = from;
            while (at < out.size() && key(out.get(at)).compareTo(shorthand.key(group)) < 0) at++;
            out.add(at, page);
        }
        while (out.size() > SUGGESTIONS) out.remove(out.size() - 1);
        return out;
    }

    private List<String> recent() {
        String saved = prefs.getString("recent", "");
        return saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split("\n")));
    }

    private void remember(String word) {
        List<String> recent = recent();
        recent.remove(word);
        recent.add(0, word);
        while (recent.size() > RECENTS) recent.remove(recent.size() - 1);
        prefs.edit().putString("recent", String.join("\n", recent)).apply();
    }

    /** Recent searches while the box is empty; what the query could be once it isn't. */
    private void showRows() {
        rows.removeAllViews();
        items.clear();
        itemTabs.clear();
        itemViews.clear();
        picked = -1;
        String query = search.getText().toString();
        String q = Dictionary.normalize(query);
        if (q.isEmpty()) {
            search.setTextColor(getColor(R.color.ink));
            List<Integer> recent = new ArrayList<>();
            for (String word : recent()) {
                int at = pageOf(word);
                if (at != -1) recent.add(at);
            }
            if (recent.isEmpty()) return;
            rows.addView(recentHeader());
            for (int index : recent) {
                if (outlineSearch) addRow(index, SHORTHAND, R.drawable.ic_history, name(index));
                else addRow(index, R.drawable.ic_history, null);
            }
        } else if (outlineSearch && q.indexOf(' ') >= 0) {
            showPhrase(q.split(" "));
        } else if (outlineSearch) {
            List<Shorthand.Match> outlines = shorthand.read(query, OUTLINES);
            showNone(outlines.isEmpty());
            for (Shorthand.Match match : outlines) addOutline(match.entry);
        } else {
            List<Integer> suggestions = suggest(query);
            showNone(suggestions.isEmpty());
            for (int index : suggestions) addRow(index, R.drawable.ic_search_thin, q);
        }
        results.scrollTo(0, 0);
    }

    /** Red text, and "No results", when nothing matches. */
    private void showNone(boolean none) {
        search.setTextColor(getColor(none ? R.color.not_found : R.color.ink));
        if (!none) return;
        TextView empty = text(getString(R.string.no_results), 12.5f, R.color.text_muted, sans);
        empty.setPadding(dp(12), dp(10), dp(12), dp(10));
        rows.addView(empty);
    }

    /**
     * Outlines typed apart, as a phrase is written: the English each reads as
     * first, together ("TH S": this is), then each outline's own rows. Should
     * they make one outline, those come first (Grascii joins a few phrases,
     * IS'US: ice house). A '?' stands for an outline that reads as nothing.
     */
    private void showPhrase(String[] typed) {
        List<Shorthand.Match> joined = shorthand.read(TextUtils.join("", typed), OUTLINES);
        List<List<Shorthand.Match>> words = new ArrayList<>();
        StringBuilder english = new StringBuilder();
        boolean any = false;
        for (String outline : typed) {
            List<Shorthand.Match> read = shorthand.read(outline, Math.max(2, OUTLINES / typed.length));
            words.add(read);
            if (english.length() > 0) english.append(' ');
            english.append(read.isEmpty() ? "?" : shorthand.translation(read.get(0).entry));
            any |= !read.isEmpty();
        }
        showNone(!any && joined.isEmpty());
        if (!any && joined.isEmpty()) return;
        List<Integer> shown = new ArrayList<>();
        for (Shorthand.Match match : joined) {
            if (match.kind != Shorthand.EXACT) continue;
            addOutline(match.entry);
            shown.add(match.entry);
        }
        if (any) {
            // The phrase opens when a dictionary has it ("ice cream"), and
            // otherwise is only read.
            int page = pageOf(Dictionary.normalize(english.toString()));
            SpannableStringBuilder s = new SpannableStringBuilder();
            append(s, english.toString(), new Spans.Font(semibold));
            if (page != -1) {
                addRow(page, DEFINITIONS, R.drawable.ic_search_thin, s);
            } else {
                TextView reading = text("", 12.5f, R.color.ink, sans);
                reading.setText(s);
                reading.setPadding(dp(12), dp(6), dp(12), dp(4));
                rows.addView(reading);
            }
        }
        for (List<Shorthand.Match> read : words) {
            // Not again what the joined outline already showed.
            for (Shorthand.Match match : read) {
                if (!shown.contains(match.entry)) addOutline(match.entry);
            }
        }
    }

    /** A row of the shorthand search: the outline in semibold, then its English in grey. */
    private void addOutline(int entry) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        append(s, shorthand.outline(entry), new Spans.Font(semibold));
        s.append("  ");
        append(s, shorthand.translation(entry), new ForegroundColorSpan(getColor(R.color.text_muted)));
        addRow(pageFor(shorthand.group(entry)), SHORTHAND, R.drawable.ic_shorthand, s);
    }

    private View recentHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(6), dp(8), dp(4));
        TextView title = text(getString(R.string.recent_searches), 12f, R.color.ink, semibold);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = text(getString(R.string.clear), 12f, R.color.clear, semibold);
        clear.setPadding(dp(4), dp(2), dp(4), dp(2));
        header.addView(clear);
        items.add(CLEAR);
        itemTabs.add(DEFINITIONS);
        itemViews.add(clear);
        return header;
    }

    /** A word's row: the rest of a suggestion, past what was typed, set in semibold. */
    private void addRow(int index, int icon, String typed) {
        String word = name(index);
        CharSequence label = word;
        if (typed != null && word.toLowerCase(Locale.ROOT).startsWith(typed) && typed.length() < word.length()) {
            SpannableStringBuilder s = new SpannableStringBuilder(word);
            s.setSpan(new Spans.Font(semibold), typed.length(), word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label = s;
        }
        addRow(index, DEFINITIONS, icon, label);
    }

    /** A row: an icon, the label, and an arrow, over a hairline. */
    private void addRow(int index, int onTab, int icon, CharSequence text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), 0, dp(12), 0);
        ImageView start = new ImageView(this);
        start.setImageResource(icon);
        line.addView(start, new LinearLayout.LayoutParams(dp(13), dp(13)));
        TextView label = text("", 12.5f, R.color.ink, sans);
        label.setText(text);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(9);
        lp.rightMargin = dp(6);
        line.addView(label, lp);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_top_right);
        line.addView(arrow, new LinearLayout.LayoutParams(dp(12), dp(12)));
        row.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(29)));
        View rule = new View(this);
        rule.setBackgroundColor(getColor(R.color.hairline));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.leftMargin = dp(12);
        rp.rightMargin = dp(12);
        row.addView(rule, rp);
        rows.addView(row);
        items.add(index);
        itemTabs.add(onTab);
        itemViews.add(line);
    }

    /** Up and Down while searching: through the rows, Clear included, and back to the box. */
    private void pick(int direction) {
        if (items.isEmpty()) return;
        int next;
        if (direction > 0) {
            next = picked < 0 ? (items.get(0) == CLEAR && items.size() > 1 ? 1 : 0) : Math.min(picked + 1, items.size() - 1);
        } else {
            next = picked - 1;
        }
        if (picked >= 0) itemViews.get(picked).setBackground(null);
        picked = Math.max(next, -1);
        if (picked < 0) {
            results.smoothScrollTo(0, 0);
            return;
        }
        View view = itemViews.get(picked);
        if (items.get(picked) == CLEAR) view.setBackgroundResource(R.drawable.clear_focused);
        else view.setBackgroundColor(getColor(R.color.highlight));
        View row = (View) view.getParent();
        int top = row.getTop();
        int bottom = row.getBottom();
        int scroll = results.getScrollY();
        if (picked <= 1) results.smoothScrollTo(0, 0);
        else if (top < scroll) results.smoothScrollTo(0, top);
        else if (bottom > scroll + results.getHeight()) results.smoothScrollTo(0, bottom - results.getHeight());
    }

    // Text

    /**
     * noun
     * 1  a distinctive and pervasive quality or character; air; atmosphere:
     *    | an aura of respectability
     */
    private CharSequence definitions(List<Dictionary.Sense> senses) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int muted = getColor(R.color.text_muted);
        char pos = 0;
        int number = 0;
        for (Dictionary.Sense sense : senses) {
            if (sense.pos != pos) {
                pos = sense.pos;
                number = 0;
                gap(text, 12);
                append(text, label(pos), new Spans.Font(serif), size(17));
            }
            String gloss = sense.gloss;
            int examples = gloss.indexOf("; \"");
            gap(text, 5);
            int start = text.length();
            text.append(definition(gloss)).append(examples < 0 ? "" : ":");
            text.setSpan(new Spans.Number(++number, dp(15), sans, sp(11), muted),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (examples < 0) continue;
            for (String example : gloss.substring(examples + 2).split("; (?=\")")) {
                text.append("\n");
                append(text, example.replace("\"", "").trim(),
                    new Spans.Font(sansItalic),
                    new Spans.Example(dp(15), dp(2), dp(7), getColor(R.color.example_rule)));
            }
        }
        return text;
    }

    /**
     * noun as in air, atmosphere
     * quality  feeling  mood
     * ‾‾‾‾‾‾‾  ‾‾‾‾‾‾‾  ‾‾‾‾
     */
    private CharSequence thesaurus(String word, List<Dictionary.Sense> senses) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int muted = getColor(R.color.text_muted);
        for (Dictionary.Sense sense : senses) {
            List<String> others = synonyms(word, sense);
            if (others.isEmpty()) continue;
            if (text.length() > 0) gap(text, 14);
            append(text, label(sense.pos), new Spans.Font(serif), size(15));
            append(text, " as in ", new Spans.Font(sansItalic), new ForegroundColorSpan(muted), size(11.5f));
            append(text, gist(sense.gloss), new Spans.Font(semiboldItalic), new ForegroundColorSpan(muted), size(11.5f));
            gap(text, 6);
            for (String other : others) {
                append(text, other, new Spans.Synonym(dp(2), dp(1), dp(10), getColor(R.color.synonym)));
                text.append(" ");
            }
        }
        return text;
    }

    /**
     * 'ABT
     * ' · A · B · T
     */
    private CharSequence outlines(int group) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int muted = getColor(R.color.text_muted);
        for (int entry : shorthand.entries(group)) {
            gap(text, 8);
            String outline = shorthand.outline(entry);
            append(text, outline, new Spans.Font(semibold), size(18));
            // How Grascii reads it, stroke by stroke.
            List<String> strokes = Shorthand.split(outline);
            if (strokes != null && strokes.size() > 1) {
                text.append("\n");
                append(text, TextUtils.join("  ·  ", strokes), new ForegroundColorSpan(muted), size(11));
            }
        }
        return text;
    }

    private static List<String> synonyms(String word, Dictionary.Sense sense) {
        List<String> others = new ArrayList<>();
        for (String w : sense.words) {
            if (!w.equalsIgnoreCase(word) && !others.contains(w)) others.add(w);
        }
        return others;
    }

    /** A gloss without its examples. */
    private static String definition(String gloss) {
        int examples = gloss.indexOf("; \"");
        return examples < 0 ? gloss : gloss.substring(0, examples);
    }

    /** The first few words of a gloss, for "as in": up to its first ; or ( and at most 34 letters. */
    private static String gist(String gloss) {
        String gist = definition(gloss);
        int cut = gist.length();
        for (char c : new char[] {';', '('}) {
            int at = gist.indexOf(c);
            if (at > 0) cut = Math.min(cut, at);
        }
        gist = gist.substring(0, cut).trim();
        if (gist.length() > 34) {
            int space = gist.lastIndexOf(' ', 34);
            gist = gist.substring(0, space > 0 ? space : 34).replaceAll("[,: ]+$", "") + "…";
        }
        return gist;
    }

    /** The headword as its first meaning writes it ("March", "Paris", "ice cream"). */
    private String display(int index, List<Dictionary.Sense> senses) {
        String word = dictionary.word(index);
        if (senses == null) senses = dictionary.senses(index);
        for (Dictionary.Sense sense : senses) {
            for (String w : sense.words) {
                if (w.equalsIgnoreCase(word)) return w;
            }
        }
        return word;
    }

    private static String label(char pos) {
        switch (pos) {
            case 'n': return "noun";
            case 'v': return "verb";
            case 'a': return "adjective";
            default: return "adverb";
        }
    }

    /** A blank line {@code height} dp tall, between paragraphs. */
    private void gap(SpannableStringBuilder text, int height) {
        if (text.length() == 0) return;
        text.append("\n");
        int start = text.length();
        text.append("\n");
        text.setSpan(new AbsoluteSizeSpan(dp(height)), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void append(SpannableStringBuilder text, String part, Object... spans) {
        int start = text.length();
        text.append(part);
        for (Object span : spans) {
            text.setSpan(span, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private TextView text(String value, float size, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        view.setTypeface(typeface);
        return view;
    }

    private AbsoluteSizeSpan size(float sp) {
        return new AbsoluteSizeSpan(Math.round(sp(sp)));
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private float sp(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }
}
