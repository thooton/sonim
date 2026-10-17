package com.dictionary.xp3;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
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
 * Home is the Word of the Day; OK opens it. A word's page has the headword
 * and two tabs, Definitions and Thesaurus, which Left and Right switch
 * between; Up and Down scroll it, and OK opens a menu to pronounce the word
 * or search. Menu opens the search anywhere. The search lists recent searches and, as a query is typed, the
 * words it could mean; OK opens the one picked, or the best match. Back goes
 * back a page, and leaves from home.
 */
public class MainActivity extends Activity {

    private static final int HOME = -1;
    private static final int RECENTS = 20;
    private static final int SUGGESTIONS = 8;
    /** In a list of rows, the Clear button rather than a word. */
    private static final int CLEAR = -2;

    private Dictionary dictionary;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean speaks;

    private int page = HOME;
    private final List<Integer> back = new ArrayList<>();
    private boolean thesaurus;
    private boolean searching;
    private int wordOfTheDay;

    // The search's rows: word indexes, or CLEAR; and which one the d-pad is on.
    private final List<Integer> items = new ArrayList<>();
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
    private ScrollView home;
    private ScrollView entry;
    private TextView headword;
    private TextView tabDefinitions;
    private TextView tabThesaurus;
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
        home = findViewById(R.id.home);
        entry = findViewById(R.id.entry);
        headword = findViewById(R.id.headword);
        tabDefinitions = findViewById(R.id.tab_definitions);
        tabThesaurus = findViewById(R.id.tab_thesaurus);
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
            if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_BACK) {
                if (released) closeSearch();
                return true;
            }
            if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) && picked >= 0) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) move(key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (first && page != HOME) {
                boolean right = key == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (right != thesaurus && tabThesaurus.getVisibility() == View.VISIBLE) {
                    thesaurus = right;
                    showEntry();
                }
            }
            return true;
        }
        if (isOk(key)) {
            // On the release, so it does not land on the menu just opened.
            if (released && page != HOME) showActions();
            else if (first && page == HOME) open(wordOfTheDay);
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            // On the release, so it does not land on the box just opened.
            if (released) openSearch();
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

    /** Up and Down off the search: a screenful at a time. */
    private void move(int direction) {
        ScrollView scroll = page == HOME ? home : entry;
        int line = body.getLineHeight();
        int step = Math.max(scroll.getHeight() - 2 * line, line);
        scroll.smoothScrollBy(0, direction * step);
    }

    // Pages

    private void open(int index) {
        if (index != page) back.add(page);
        page = index;
        thesaurus = false;
        show();
    }

    private void goBack() {
        if (back.isEmpty()) {
            finish();
            return;
        }
        page = back.remove(back.size() - 1);
        thesaurus = false;
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
        List<Dictionary.Sense> senses = dictionary.senses(page);
        String word = display(page, senses);
        headword.setText(word);
        // A word WordNet gives no synonyms for has no Thesaurus tab.
        boolean any = false;
        for (Dictionary.Sense sense : senses) any |= !synonyms(word, sense).isEmpty();
        thesaurus &= any;
        tabThesaurus.setVisibility(any ? View.VISIBLE : View.GONE);
        styleTab(tabDefinitions, !thesaurus);
        styleTab(tabThesaurus, thesaurus);
        body.setText(thesaurus ? thesaurus(word, senses) : definitions(senses));
        entry.scrollTo(0, 0);
    }

    private void styleTab(TextView tab, boolean open) {
        tab.setTypeface(open ? semibold : sans);
        tab.setBackgroundResource(open ? R.drawable.tab_selected : 0);
    }

    /** OK on a word: a menu over the headword, to hear it or to search. */
    private void showActions() {
        PopupMenu menu = new PopupMenu(this, headword);
        menu.getMenu().add(0, 1, 0, R.string.pronounce).setEnabled(speaks);
        menu.getMenu().add(0, 2, 1, R.string.search);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                tts.speak(headword.getText(), TextToSpeech.QUEUE_FLUSH, null, "word");
            } else {
                openSearch();
            }
            return true;
        });
        menu.show();
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

    private void openSearch() {
        searching = true;
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
        search.setText("");
        search.setFocusable(false);
        search.setFocusableInTouchMode(false);
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /** OK while searching: the row picked, or else the best match for the query. */
    private void choose() {
        if (picked >= 0) {
            int item = items.get(picked);
            if (item == CLEAR) {
                prefs.edit().remove("recent").apply();
                showRows();
            } else {
                found(item);
            }
            return;
        }
        if (Dictionary.normalize(search.getText().toString()).isEmpty()) return;
        int found = dictionary.find(search.getText().toString());
        if (found >= 0) found(found);
    }

    private void found(int index) {
        remember(dictionary.word(index));
        endSearch();
        open(index);
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
        itemViews.clear();
        picked = -1;
        String query = search.getText().toString();
        String q = Dictionary.normalize(query);
        if (q.isEmpty()) {
            search.setTextColor(getColor(R.color.ink));
            List<Integer> recent = new ArrayList<>();
            for (String word : recent()) {
                int at = dictionary.lowerBound(word);
                if (at < dictionary.size() && dictionary.word(at).equals(word)) recent.add(at);
            }
            if (recent.isEmpty()) return;
            rows.addView(recentHeader());
            for (int index : recent) addRow(index, R.drawable.ic_history, null);
        } else {
            List<Integer> suggestions = dictionary.suggest(query, SUGGESTIONS);
            search.setTextColor(getColor(suggestions.isEmpty() ? R.color.not_found : R.color.ink));
            if (suggestions.isEmpty()) {
                TextView none = text(getString(R.string.no_results), 12.5f, R.color.text_muted, sans);
                none.setPadding(dp(12), dp(10), dp(12), dp(10));
                rows.addView(none);
            }
            for (int index : suggestions) addRow(index, R.drawable.ic_search_thin, q);
        }
        results.scrollTo(0, 0);
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
        itemViews.add(clear);
        return header;
    }

    /** A row: an icon, the word, and an arrow, over a hairline. */
    private void addRow(int index, int icon, String typed) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), 0, dp(12), 0);
        ImageView start = new ImageView(this);
        start.setImageResource(icon);
        line.addView(start, new LinearLayout.LayoutParams(dp(13), dp(13)));
        String word = display(index, null);
        TextView label = text(word, 12.5f, R.color.ink, sans);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        if (typed != null && word.toLowerCase(Locale.ROOT).startsWith(typed) && typed.length() < word.length()) {
            // The rest of a suggestion is set in semibold, past what was typed.
            SpannableStringBuilder s = new SpannableStringBuilder(word);
            s.setSpan(new Spans.Font(semibold), typed.length(), word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setText(s);
        }
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

    /** The headword as its meanings write it ("March", "Paris"), lowercase as itself when it is one. */
    private String display(int index, List<Dictionary.Sense> senses) {
        String word = dictionary.word(index);
        if (senses == null) senses = dictionary.senses(index);
        String cased = null;
        for (Dictionary.Sense sense : senses) {
            for (String w : sense.words) {
                if (w.equals(word)) return word;
                if (cased == null && w.equalsIgnoreCase(word)) cased = w;
            }
        }
        return cased != null ? cased : word;
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
