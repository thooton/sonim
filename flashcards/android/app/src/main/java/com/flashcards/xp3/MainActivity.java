package com.flashcards.xp3;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.flashcards.xp3.algo.Scheduler;
import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;
import com.flashcards.xp3.data.Store;

import io.github.openspacedrepetition.State;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole app: two tabs, the deck list and the study screen, and the keys
 * that move between them.
 *
 * Decks ship in the APK as plain-text assets (no import). FSRS state is
 * written down after every rating and on pause, one small line-per-card file
 * per deck in the app's private directory. An interrupted session is rebuilt
 * from the exact saved card memory states when the app next opens.
 *
 * DPAD_LEFT and DPAD_RIGHT move between the two tabs, exactly as they do in
 * the Todo and Tesla apps; everything else on the study screen is owned by
 * {@link ReviewPane} (which reads OK and the 1..4 numpad keys).
 */
public final class MainActivity extends Activity {

    private static final int TAB_DECKS = 0;
    private static final int TAB_REVIEW = 1;
    private static final int TABS = 2;

    /** The bundled decks, in the order they appear in the Decks list. */
    private static final String[][] DECK_ASSETS = {
        {"Spanish words", "decks/spanish-words.tsv", "decks/spanish-words.state"},
    };

    private FrameLayout content;
    private Button[] tabButtons;
    private int currentTab = TAB_DECKS;

    private final List<LoadedDeck> decks = new ArrayList<>();
    private int activeDeck = 0;
    private ReviewPane reviewPane;
    private Scheduler scheduler;

    private static final class LoadedDeck {
        final String name;
        final String asset;
        final String initialStateAsset;
        final Deck deck;

        LoadedDeck(String name, String asset, String initialStateAsset, Deck deck) {
            this.name = name;
            this.asset = asset;
            this.initialStateAsset = initialStateAsset;
            this.deck = deck;
        }

        File stateFile(File dir) {
            return new File(dir, asset.replace('/', '_') + ".state");
        }
    }

    @Override
    protected void onCreate(Bundle onIce) {
        super.onCreate(onIce);
        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);
        tabButtons = new Button[]{
            findViewById(R.id.tab_decks),
            findViewById(R.id.tab_review),
        };
        for (int i = 0; i < tabButtons.length; i++) {
            final int tab = i;
            tabButtons[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showTab(tab, true);
                }
            });
        }

        loadDecks();
        if (decks.isEmpty()) {
            // Without a bundled deck the app still opens, on an empty screen.
            activeDeck = -1;
        }
        showTab(TAB_DECKS, false);
    }

    @Override
    protected void onPause() {
        persist();
        super.onPause();
    }

    /** Persists after every rating too, so closing the phone mid-group loses nothing. */
    private void persist() {
        File dir = getFilesDir();
        for (LoadedDeck deck : decks) {
            File f = deck.stateFile(dir);
            File pending = new File(dir, f.getName() + ".pending");
            try {
                FileOutputStream stream = new FileOutputStream(pending);
                Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
                try {
                    Store.writeState(out, deck.deck.cards());
                    stream.getFD().sync();
                } finally {
                    out.close();
                }
                try {
                    Files.move(
                        pending.toPath(),
                        f.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(
                        pending.toPath(),
                        f.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                // The prior complete state file remains intact. A pending file
                // can be overwritten safely on the next successful save.
            }
        }
    }

    /** Loads every bundled deck and reconnects the FSRS state saved for it. */
    private void loadDecks() {
        File dir = getFilesDir();
        AssetManager assets = getAssets();
        for (String[] entry : DECK_ASSETS) {
            String name = entry[0];
            String asset = entry[1];
            String initialStateAsset = entry[2];
            try {
                InputStream in = assets.open(asset);
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                List<DeckCard> cards;
                try {
                    cards = Store.parseDeck(reader);
                } finally {
                    in.close();
                }
                File state = new File(dir, asset.replace('/', '_') + ".state");
                if (state.exists()) {
                    InputStream sin = new FileInputStream(state);
                    Reader sreader = new InputStreamReader(sin, StandardCharsets.UTF_8);
                    try {
                        Store.readState(sreader, cards);
                    } finally {
                        sin.close();
                    }
                } else {
                    InputStream sin = assets.open(initialStateAsset);
                    Reader sreader = new InputStreamReader(sin, StandardCharsets.UTF_8);
                    try {
                        Store.readState(sreader, cards);
                    } finally {
                        sin.close();
                    }
                }
                decks.add(new LoadedDeck(name, asset, initialStateAsset, new Deck(name, cards)));
            } catch (IOException e) {
                // One bad asset must not take the whole app down.
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // First press only, and not when a text field owns the keys.
        if (event.getAction() == KeyEvent.ACTION_DOWN
            && event.getRepeatCount() == 0
            && !(getCurrentFocus() instanceof android.widget.EditText)) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                showTab((currentTab + TABS - 1) % TABS, true);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                showTab((currentTab + 1) % TABS, true);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void showTab(int tab, boolean focusContent) {
        currentTab = tab;
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setSelected(i == tab);
        }
        if (tab == TAB_DECKS) renderDecks();
        else renderReview();
    }

    /**
     * The study screen. The same ReviewPane is re-added, not rebuilt, so
     * stepping over to Decks and back keeps the current card in place.
     */
    private void renderReview() {
        if (reviewPane == null) {
            if (activeDeck >= 0 && activeDeck < decks.size()) {
                scheduler = new Scheduler(decks.get(activeDeck).deck);
                reviewPane = new ReviewPane(this, scheduler, new ReviewPane.Listener() {
                    @Override
                    public void onRated() {
                        persist();
                    }
                });
            } else {
                reviewPane = new ReviewPane(this, new Scheduler(emptyDeck()), new ReviewPane.Listener() {
                    @Override
                    public void onRated() {
                        persist();
                    }
                });
            }
        }
        replaceContent(reviewPane);
        reviewPane.requestFocus();
    }

    private static Deck emptyDeck() {
        return new Deck("None", new ArrayList<DeckCard>());
    }

    /** The Decks tab: one row per bundled deck with Anki-style queue counts. */
    private void renderDecks() {
        final DeckAdapter adapter = new DeckAdapter();
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setDivider(new ColorDrawable(color(R.color.divider)));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(color(R.color.focus_fill)));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switchDeck(position);
            }
        });
        list.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_UP) return false;
                boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
                int at = ((ListView) view).getSelectedItemPosition();
                if (ok && at >= 0 && at < decks.size()) {
                    switchDeck(at);
                    return true;
                }
                return false;
            }
        });
        replaceContent(list);
        list.requestFocus();
    }

    private final class DeckAdapter extends BaseAdapter {
        @Override public int getCount() { return decks.size(); }
        @Override public LoadedDeck getItem(int position) { return decks.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View oldView, ViewGroup parent) {
            LoadedDeck loaded = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(5), dp(10), dp(5));
            row.setBackgroundResource(R.drawable.focus_selector);

            TextView name = new TextView(MainActivity.this);
            name.setText((position == activeDeck ? "› " : "  ") + loaded.name);
            name.setTextColor(color(R.color.text));
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT, position == activeDeck ? Typeface.BOLD : Typeface.NORMAL);
            row.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int[] counts = deckCounts(loaded.deck);
            LinearLayout stats = new LinearLayout(MainActivity.this);
            stats.setOrientation(LinearLayout.HORIZONTAL);
            stats.setPadding(dp(10), dp(2), 0, 0);
            addCount(stats, "New " + counts[0], R.color.count_new);
            addCount(stats, "Learn " + counts[1], R.color.count_learn);
            addCount(stats, "Due " + counts[2], R.color.count_due);
            row.addView(stats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setMinimumHeight(dp(54));
            return row;
        }
    }

    private int[] deckCounts(Deck deck) {
        int fresh = 0;
        int learning = 0;
        int due = 0;
        long now = System.currentTimeMillis() / 1000L;
        for (DeckCard card : deck.cards()) {
            if (card.isNew()) {
                fresh++;
            } else if (card.fsrs.getState() == State.LEARNING
                || card.fsrs.getState() == State.RELEARNING) {
                learning++;
            } else if (card.fsrs.getDue().getEpochSecond() <= now) {
                due++;
            }
        }
        return new int[]{fresh, learning, due};
    }

    private void addCount(LinearLayout row, String label, int colorRes) {
        TextView count = new TextView(this);
        count.setText(label);
        count.setTextColor(color(colorRes));
        count.setTextSize(11);
        count.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void switchDeck(int index) {
        if (index < 0 || index >= decks.size()) return;
        if (index != activeDeck) {
            activeDeck = index;
            // The study screen starts a fresh session on the new deck.
            reviewPane = null;
            scheduler = null;
        }
        showTab(TAB_REVIEW, true);
    }

    private void replaceContent(View view) {
        content.removeAllViews();
        content.addView(
            view,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        );
    }

    private int color(int res) {
        return getColor(res);
    }

    private int dp(int n) {
        return Math.round(getResources().getDisplayMetrics().density * n);
    }
}
