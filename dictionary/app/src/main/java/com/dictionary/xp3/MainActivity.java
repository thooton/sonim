package com.dictionary.xp3;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The dictionary as a book: one headword per page.
 *
 * Left/Right turn the page, Up/Down scroll it, and Menu opens a search box
 * that jumps to the word as it is typed. OK keeps the page found; Back
 * returns to the page the search started from.
 */
public class MainActivity extends Activity {

    private Dictionary dictionary;
    private int index;
    private int beforeSearch;
    // The key whose press this activity saw, so that a release only acts
    // when its press did too (not the end of a press made elsewhere).
    private int pressed = -1;

    private EditText search;
    private TextView word;
    private TextView page;
    private TextView body;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        search = findViewById(R.id.search);
        word = findViewById(R.id.word);
        page = findViewById(R.id.page);
        body = findViewById(R.id.body);
        scroll = findViewById(R.id.scroll);

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
                if (searching()) lookUp(s.toString());
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
                closeSearch();
                return true;
            }
        });

        // Open where the reader left off.
        String saved = getPreferences(MODE_PRIVATE).getString("word", "");
        show(Math.min(dictionary.lowerBound(saved), dictionary.size() - 1));
        scroll.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getPreferences(MODE_PRIVATE).edit().putString("word", dictionary.word(index)).apply();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean released = !down && pressed == key && !event.isCanceled();
        if (down && event.getRepeatCount() == 0) pressed = key;
        else if (!down) pressed = -1;

        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) scrollPage(key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            return true;
        }
        if (searching()) {
            // OK acts on its press: T9 can swallow the first release.
            if (isOk(key)) {
                if (down && event.getRepeatCount() == 0) closeSearch();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MENU) {
                if (released) closeSearch();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK) {
                if (released) {
                    closeSearch();
                    show(beforeSearch);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Repeats included, so holding the key leafs through the book.
            if (down) {
                int next = index + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (next >= 0 && next < dictionary.size()) show(next);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            // On the release, so it does not land on the box just opened.
            if (released) openSearch();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isOk(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER
            || key == KeyEvent.KEYCODE_ENTER
            || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean searching() {
        return search.getVisibility() == View.VISIBLE;
    }

    private void openSearch() {
        beforeSearch = index;
        search.setText("");
        search.setTextColor(getColor(R.color.text));
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
    }

    private void closeSearch() {
        search.setVisibility(View.GONE);
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
        scroll.requestFocus();
    }

    /** Turns to the query's page as it is typed; red text means there is none. */
    private void lookUp(String query) {
        if (Dictionary.normalize(query).isEmpty()) {
            search.setTextColor(getColor(R.color.text));
            show(beforeSearch);
            return;
        }
        int found = dictionary.find(query);
        search.setTextColor(getColor(found < 0 ? R.color.not_found : R.color.text));
        if (found >= 0) show(found);
    }

    private void scrollPage(int direction) {
        int step = Math.max(scroll.getHeight() - 2 * body.getLineHeight(), body.getLineHeight());
        scroll.smoothScrollBy(0, direction * step);
    }

    private void show(int index) {
        this.index = index;
        word.setText(dictionary.word(index));
        page.setText(String.format("%,d", index + 1));
        body.setText(format(dictionary.word(index), dictionary.senses(index)));
        scroll.scrollTo(0, 0);
    }

    /**
     * noun
     * 1. a member of the genus Canis ...
     *    "the dog barked all night"
     *    = domestic dog, Canis familiaris
     */
    private CharSequence format(String headword, List<Dictionary.Sense> senses) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int muted = getColor(R.color.text_muted);
        char pos = 0;
        int number = 0;
        for (Dictionary.Sense sense : senses) {
            if (sense.pos != pos) {
                pos = sense.pos;
                number = 0;
                if (text.length() > 0) text.append("\n\n");
                append(text, label(pos), new StyleSpan(Typeface.BOLD_ITALIC), new ForegroundColorSpan(muted));
            }
            String gloss = sense.gloss;
            int examples = gloss.indexOf("; \"");
            text.append("\n").append(String.valueOf(++number)).append(". ");
            text.append(examples < 0 ? gloss : gloss.substring(0, examples));
            if (examples >= 0) {
                text.append("\n");
                append(text, gloss.substring(examples + 2).replace("; \"", "\n\""),
                    new StyleSpan(Typeface.ITALIC), new ForegroundColorSpan(muted));
            }
            List<String> others = new ArrayList<>();
            for (String w : sense.words) {
                if (!w.equalsIgnoreCase(headword) && !others.contains(w)) others.add(w);
            }
            if (!others.isEmpty()) {
                text.append("\n");
                append(text, "= " + String.join(", ", others), new ForegroundColorSpan(muted));
            }
        }
        return text;
    }

    private static String label(char pos) {
        switch (pos) {
            case 'n': return "noun";
            case 'v': return "verb";
            case 'a': return "adjective";
            default: return "adverb";
        }
    }

    private static void append(SpannableStringBuilder text, String part, Object... spans) {
        int start = text.length();
        text.append(part);
        for (Object span : spans) {
            text.setSpan(span, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
