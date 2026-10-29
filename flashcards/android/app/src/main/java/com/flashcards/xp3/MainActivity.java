package com.flashcards.xp3;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.flashcards.xp3.algo.ReviewSession;
import com.flashcards.xp3.data.Collection;
import com.flashcards.xp3.data.Deck;
import com.flashcards.xp3.data.DeckCard;
import com.flashcards.xp3.data.Fkv;
import com.flashcards.xp3.data.Store;
import com.flashcards.xp3.sync.Center;
import com.xp3.common.Disk;
import com.xp3.common.Login;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Card;
import com.xp3.common.ui.CardGrant;
import com.xp3.common.ui.Ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Anki's desktop window on a flip phone: the deck list, a deck's overview
 * with Study Now, the reviewer ({@link ReviewPane}), the deck's options
 * ({@link OptionsPane}) and its statistics ({@link StatsPane}).
 *
 * The decks are the command center's (see {@link Collection}): the app
 * studies its own copy, saved in the app's private directory, and syncs it
 * when it opens, a little after answers and when it closes. The first run of
 * this version brings the bundled Spanish deck, with the state it had here,
 * into that copy, and its first sync takes it to the command center.
 *
 * Without a login it pairs, as the tablet does: the deck list shows a code
 * to type on the website's Tablet page, and the token that brings reaches
 * only the flashcards. Menu > Command center still takes a typed login.
 *
 * Menu > Import makes a new deck of an FKV file ({@link Fkv}) from
 * Documents or Download, where Tablet saves Transform's flashcards.
 */
public final class MainActivity extends BaseActivity {

    private static final String FILE = "collection.json";
    private static final String PREFS = "flashcards";
    /** Options > Show progress, kept on this device for every deck. */
    private static final String PREF_PROGRESS = "show_progress";
    /** The deck the app bundled before the command center kept them. */
    private static final String[] BUNDLED = {
        "spanish-words", "Spanish words", "decks/spanish-words.tsv", "decks/spanish-words.state"};
    private static final long SAVE_AFTER_MS = 1500;
    private static final long SYNC_AFTER_MS = 30000;
    private static final int READ_STORAGE = 1;
    private static final int CARD_GRANT = 2;
    private static final long MAX_FKV = 16L << 20;

    private enum Screen { DECKS, OVERVIEW, REVIEW, OPTIONS, STATS }

    private final ExecutorService net = Ui.worker("flashcards-sync");
    private final ExecutorService disk = Ui.worker("flashcards-import");
    private final ExecutorService saver = Ui.worker("flashcards-save");
    /** The file to import once Android lets the app read the SD card. */
    private File importing;
    private final ZoneId zone = ZoneId.systemDefault();
    private FrameLayout content;

    private Collection collection;
    private final Map<String, ReviewSession> sessions = new LinkedHashMap<>();
    private Login login = Login.blank();
    private Screen screen = Screen.DECKS;
    private Deck open;
    private int deckAt;
    private ReviewPane review;

    private boolean syncing;
    private boolean syncAgain;
    private String syncError;
    private boolean unpaired;

    private Center.Pairing pairing;
    private boolean pairingBusy;
    private String pairError;
    private boolean resumed;

    private final Runnable saveSoon = this::save;
    private final Runnable syncSoon = this::sync;
    private final Runnable refresh = () -> {
        if (screen == Screen.OVERVIEW) showOverview();
    };

    @Override
    protected void onCreate(Bundle onIce) {
        super.onCreate(onIce);
        setContentView(R.layout.activity_main);
        content = findViewById(R.id.content);
        NightShift.apply(getWindow().getDecorView(), nightShift());
        login = loadLogin(prefs());
        collection = load();
        collection.zone = zone.getId();
        showDecks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (login.ready()) sync();
        else if (pairing != null) later(pollSoon, 1000);
        else pair();
    }

    @Override
    protected void onPause() {
        resumed = false;
        getWindow().getDecorView().removeCallbacks(pollSoon);
        save();
        if (collection.dirty()) sync();
        super.onPause();
    }

    // ---------------------------------------------------------------------
    // The collection on disk

    private Collection load() {
        File file = new File(getFilesDir(), FILE);
        if (file.isFile()) {
            try {
                return Collection.read(Disk.read(file, 64L << 20));
            } catch (RuntimeException e) {
                // Unreadable: start again from the bundled deck, and let the
                // command center's copy win wherever it has a later review.
            }
        }
        Collection fresh = new Collection();
        try {
            fresh.add(Collection.bundled(BUNDLED[0], BUNDLED[1], bundledCards()));
        } catch (IOException e) {
            // Nothing bundled to bring: the first sync fills it in.
        }
        return fresh;
    }

    /** The bundled deck with the state saved for it here, or its starting state. */
    private List<DeckCard> bundledCards() throws IOException {
        AssetManager assets = getAssets();
        List<DeckCard> cards;
        try (InputStream in = assets.open(BUNDLED[2])) {
            cards = Store.parseDeck(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        File saved = new File(getFilesDir(), BUNDLED[2].replace('/', '_') + ".state");
        try (InputStream in = saved.isFile() ? new FileInputStream(saved) : assets.open(BUNDLED[3]);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Store.readState(reader, cards);
        }
        return cards;
    }

    /**
     * Saves the collection off the main thread: writing the whole file takes
     * long enough on the phone to hold up a key pressed meanwhile. The copy is
     * quick; saves run one at a time, in order, so the last one wins.
     */
    private void save() {
        getWindow().getDecorView().removeCallbacks(saveSoon);
        final Collection copy = collection.copy();
        final File file = new File(getFilesDir(), FILE);
        saver.execute(() -> {
            try {
                Disk.write(file, copy.write(), true);
            } catch (IOException e) {
                // The last good copy stays; the next answer tries again.
            }
        });
    }

    private void later(Runnable task, long ms) {
        View root = getWindow().getDecorView();
        root.removeCallbacks(task);
        root.postDelayed(task, ms);
    }

    // ---------------------------------------------------------------------
    // Sync

    private void sync() {
        if (!login.ready()) return;
        getWindow().getDecorView().removeCallbacks(syncSoon);
        if (syncing) {
            syncAgain = true;
            return;
        }
        syncing = true;
        syncAgain = false;
        final Collection.Outgoing out = collection.outgoing();
        final Login using = login;
        net.execute(() -> {
            String reply = null;
            String error = null;
            boolean refused = false;
            try {
                reply = new Center(using).sync(out.body);
            } catch (Center.Unpaired e) {
                refused = true;
            } catch (IOException e) {
                error = e.getMessage() == null ? "No connection" : e.getMessage();
            } catch (InterruptedException e) {
                error = "Interrupted";
            } catch (RuntimeException e) {
                error = "Couldn't sync";
            }
            final String got = reply;
            final String why = error;
            final boolean gone = refused;
            runOnUiThread(() -> {
                unpaired = gone;
                synced(out, got, why);
            });
        });
    }

    private void synced(Collection.Outgoing out, String reply, String error) {
        syncing = false;
        if (unpaired) {
            unpaired = false;
            login = Login.blank();
            prefs().edit().remove("login").apply();
            error = null;
            pair();
        }
        if (reply != null) {
            try {
                collection.apply(reply, out);
                save();
            } catch (RuntimeException e) {
                error = "The command center's answer was unreadable";
            }
        }
        syncError = error;
        if (isFinishing() || isDestroyed()) return;
        // What came in shows at once, except under a card being studied,
        // which picks it up with the next card.
        if (reply != null && error == null) {
            if (screen == Screen.DECKS) showDecks();
            else if (screen == Screen.OVERVIEW) showOverview();
        }
        if (syncAgain && error == null) sync();
    }

    // ---------------------------------------------------------------------
    // Pairing

    private final Runnable pollSoon = this::poll;

    private String server() {
        return login.server() != null ? login.server() : Center.DEFAULT_SERVER;
    }

    /** Asks for a code to type on the website. */
    private void pair() {
        if (login.ready() || pairingBusy) return;
        pairingBusy = true;
        pairError = null;
        final String server = server();
        net.execute(() -> {
            Center.Pairing got = null;
            String why = null;
            try {
                got = Center.startPairing(server);
            } catch (IOException e) {
                why = Center.reason(e);
            } catch (InterruptedException e) {
                why = "Interrupted";
            }
            final Center.Pairing p = got;
            final String error = why;
            runOnUiThread(() -> {
                pairingBusy = false;
                pairing = p;
                pairError = error;
                if (screen == Screen.DECKS) showDecks();
                if (p != null && resumed) later(pollSoon, 3000);
            });
        });
    }

    private void poll() {
        final Center.Pairing waiting = pairing;
        if (waiting == null || login.ready()) return;
        final String server = server();
        net.execute(() -> {
            String token = null;
            String why = null;
            boolean over = false;
            try {
                token = Center.poll(server, waiting);
            } catch (IOException e) {
                // Offline for now: ask again. Gone: the code expired.
                over = "The code expired".equals(e.getMessage());
                why = e.getMessage();
            } catch (InterruptedException e) {
                return;
            }
            final String got = token;
            final String error = why;
            final boolean expired = over;
            runOnUiThread(() -> paired(waiting, got, expired ? error : null));
        });
    }

    private void paired(Center.Pairing waiting, String token, String expired) {
        if (pairing != waiting) return;
        if (token != null) {
            try {
                login = Login.of(server(), token);
            } catch (IllegalArgumentException e) {
                expired = e.getMessage();
            }
        }
        if (login.ready()) {
            pairing = null;
            prefs().edit().putString("login", login.toJson()).apply();
            if (screen == Screen.DECKS) showDecks();
            sync();
        } else if (expired != null) {
            pairing = null;
            pairError = expired;
            if (screen == Screen.DECKS) showDecks();
        } else if (resumed) {
            later(pollSoon, 3000);
        }
    }

    /** The code to type on the website, where the deck list ends. */
    private View pairingPanel() {
        LinearLayout panel = vertical();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(0, dp(8), 0, dp(8));
        if (pairing != null) {
            TextView label = text("PAIRING CODE", 10, color(R.color.text_subtle));
            label.setLetterSpacing(0.1f);
            label.setGravity(Gravity.CENTER);
            panel.addView(label);
            TextView code = text(pairing.code, 24, color(R.color.text));
            code.setTypeface(Anki.MEDIUM);
            code.setLetterSpacing(0.06f);
            code.setGravity(Gravity.CENTER);
            panel.addView(code);
            TextView where = text("Delta, Tablet", 11, color(R.color.text_subtle));
            where.setGravity(Gravity.CENTER);
            panel.addView(where);
        } else if (pairError != null) {
            TextView why = text(pairError, 12, color(R.color.state_learn));
            why.setGravity(Gravity.CENTER);
            panel.addView(why);
            TextView again = Anki.button(this, "Try Again");
            again.setOnClickListener(v -> pair());
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
            at.topMargin = dp(6);
            panel.addView(again, at);
        } else {
            TextView asking = text("…", 20, color(R.color.text_subtle));
            asking.setGravity(Gravity.CENTER);
            panel.addView(asking);
        }
        return panel;
    }

    // ---------------------------------------------------------------------
    // Decks

    private void showDecks() {
        screen = Screen.DECKS;
        open = null;
        review = null;
        LinearLayout page = vertical();
        page.setPadding(dp(4), dp(2), dp(4), 0);
        List<Deck> decks = collection.decks();
        if (decks.isEmpty()) {
            if (login.ready()) {
                TextView none = text(syncing ? "Syncing" : "No decks", 13, color(R.color.text_subtle));
                none.setGravity(Gravity.CENTER);
                page.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
            } else {
                page.setGravity(Gravity.CENTER);
                page.addView(pairingPanel());
            }
            // Try Again, when there is one, takes the keys; else the page, for Menu.
            page.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            page.setFocusable(true);
            page.setDefaultFocusHighlightEnabled(false);
            Ui.fill(content, page);
            page.requestFocus();
            return;
        }
        page.addView(deckRow(text("Deck", 11, color(R.color.text_subtle)), null, true));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setItemsCanFocus(false);
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(new DeckAdapter(decks));
        list.setOnItemClickListener((parent, view, position, id) -> openDeck(decks.get(position)));
        list.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_UP || keyCode != KeyEvent.KEYCODE_MENU) return false;
            int at = list.getSelectedItemPosition();
            deckMenu(at >= 0 && at < decks.size() ? decks.get(at) : null);
            return true;
        });
        Ui.onSelect(list, at -> deckAt = at);
        page.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (!login.ready()) {
            page.addView(pairingPanel(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Ui.fill(content, page);
            list.requestFocus();
            list.setSelection(Math.min(deckAt, decks.size() - 1));
            return;
        }
        // Anki's line under the deck list.
        int studied = studiedToday();
        TextView today = text("Studied " + studied + (studied == 1 ? " card" : " cards") + " today.",
            11, color(R.color.text_subtle));
        today.setGravity(Gravity.CENTER);
        today.setPadding(0, dp(4), 0, dp(6));
        page.addView(today, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.fill(content, page);
        list.requestFocus();
        list.setSelection(Math.min(deckAt, decks.size() - 1));
    }

    private final class DeckAdapter extends BaseAdapter {
        private final List<Deck> decks;
        private final long now = System.currentTimeMillis() / 1000L;

        DeckAdapter(List<Deck> decks) {
            this.decks = decks;
        }

        @Override public int getCount() { return decks.size(); }
        @Override public Deck getItem(int position) { return decks.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View old, ViewGroup parent) {
            Deck deck = getItem(position);
            TextView name = text(deck.name, 13, color(R.color.text));
            name.setTextSize(13.5f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            View row = deckRow(name, session(deck).counts(now), false);
            row.setBackground(Anki.rowFace(MainActivity.this));
            return row;
        }
    }

    /** A row of Anki's deck table: the name, then New, Learn and Due in columns. */
    private View deckRow(TextView name, int[] counts, boolean header) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), 0, dp(4), 0);
        row.setMinimumHeight(dp(header ? 20 : 34));
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String[] heads = {"New", "Learn", "Due"};
        for (int i = 0; i < 3; i++) {
            TextView cell = header
                ? text(heads[i], 11, color(R.color.text_subtle))
                : text(Integer.toString(counts[i]), 13, Anki.countColor(this, i, counts[i]));
            cell.setGravity(Gravity.END);
            row.addView(cell, new LinearLayout.LayoutParams(dp(i == 1 ? 38 : 34), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (header) row.setBackground(null);
        return row;
    }

    private int studiedToday() {
        long now = System.currentTimeMillis() / 1000L;
        LocalDate day = Instant.ofEpochSecond(now).atZone(zone).minusHours(ReviewSession.ROLLOVER_HOUR).toLocalDate();
        long start = ZonedDateTime.of(day.atTime(ReviewSession.ROLLOVER_HOUR, 0), zone).toEpochSecond();
        int studied = 0;
        for (Deck deck : collection.decks()) {
            for (DeckCard card : deck.cards()) {
                if (!card.isNew() && card.fsrs.getLastReview().getEpochSecond() >= start) studied++;
            }
        }
        return studied;
    }

    private ReviewSession session(Deck deck) {
        ReviewSession session = sessions.get(deck.id);
        if (session == null) {
            session = new ReviewSession(deck, zone);
            sessions.put(deck.id, session);
        }
        return session;
    }

    private void deckMenu(final Deck deck) {
        List<String> items = deck == null
            ? Arrays.asList("Sync", "Import", nightShiftItem(), "Command center")
            : Arrays.asList("Options", "Stats", "Sync", "Import", nightShiftItem(), "Command center");
        listDialog(deck == null ? "Flashcards" : deck.name, items, at -> {
            String chose = items.get(at);
            if (chose.equals("Options")) showOptions(deck);
            else if (chose.equals("Stats")) showStats(deck);
            else if (chose.equals("Sync")) syncNow();
            else if (chose.equals("Import")) chooseImport();
            else if (chose.equals(nightShiftItem())) toggleNightShift();
            else askLogin();
        }, null);
    }

    private void syncNow() {
        if (!login.ready()) pair();
        else sync();
    }

    // ---------------------------------------------------------------------
    // Import

    /**
     * The .fkv files in Documents and Download, on the SD card and the
     * phone, newest first, to pick one to import. Reading them asks for
     * storage first, once.
     */
    private void chooseImport() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, READ_STORAGE);
            return;
        }
        File card = Card.root(this);
        File phone = Environment.getExternalStorageDirectory();
        final List<File> found = new ArrayList<>();
        for (File root : card == null ? new File[] {phone} : new File[] {card, phone}) {
            for (String folder : new String[] {Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_DOWNLOADS}) {
                File[] files = new File(root, folder).listFiles();
                if (files == null) continue;
                for (File file : files) {
                    if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".fkv")) found.add(file);
                }
            }
        }
        if (found.isEmpty()) {
            toast("No .fkv files in Documents or Download");
            return;
        }
        Collections.sort(found, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<String> shown = new ArrayList<>();
        for (File file : found) shown.add(deckName(file));
        listDialog("Import", shown, at -> importFile(found.get(at)), null);
    }

    private static String deckName(File file) {
        String name = file.getName().replaceAll("(?i)\\.fkv$", "").trim();
        return name.isEmpty() ? "Imported" : name;
    }

    /** Reads `file` and makes a deck of it, or says why it can't. */
    private void importFile(final File file) {
        final File card = Card.root(this);
        final Uri tree = card != null && CardGrant.on(card, file) ? CardGrant.tree(this, card) : null;
        disk.execute(() -> {
            byte[] data = null;
            List<Fkv.Card> cards = null;
            String why = null;
            boolean ask = false;
            try {
                data = read(file, card, tree);
                cards = Fkv.parse(data);
                if (cards.isEmpty()) why = "It has no cards.";
            } catch (Fkv.Malformed e) {
                why = e.getMessage();
            } catch (IOException e) {
                // Android may refuse to read what another app put on the
                // card until the app has the card's grant.
                ask = data == null && card != null && tree == null && CardGrant.on(card, file);
                why = e.getMessage() == null ? "Couldn't read it" : e.getMessage();
            }
            final List<Fkv.Card> got = cards;
            final String error = why;
            final boolean needGrant = ask;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (needGrant) askCard(file);
                else if (error != null) alert("Couldn't import " + file.getName(), error);
                else imported(deckName(file), got);
            });
        });
    }

    /** The file, plainly or, on the card with its grant, through the grant. */
    private byte[] read(File file, File card, Uri tree) throws IOException {
        if (file.length() > MAX_FKV) throw new IOException("It is too big to be flashcards");
        if (tree == null) return Files.readAllBytes(file.toPath());
        try (InputStream in = getContentResolver().openInputStream(CardGrant.document(tree, card, file))) {
            if (in == null) throw new IOException("Couldn't open it");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                if (out.size() + n > MAX_FKV) throw new IOException("It is too big to be flashcards");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void askCard(File file) {
        Intent ask = CardGrant.ask(this);
        if (ask == null) {
            alert("Couldn't import " + file.getName(), "Android didn't let the app read the SD card.");
            return;
        }
        importing = file;
        try {
            startActivityForResult(ask, CARD_GRANT);
        } catch (ActivityNotFoundException e) {
            importing = null;
            alert("Couldn't import " + file.getName(), "Android didn't let the app read the SD card.");
        }
    }

    private void imported(String name, List<Fkv.Card> cards) {
        Deck deck = collection.imported("fkv-" + UUID.randomUUID(), name, cards, System.currentTimeMillis());
        save();
        sync();
        deckAt = collection.decks().indexOf(deck);
        showDecks();
        toast("Imported " + cards.size() + (cards.size() == 1 ? " card into " : " cards into ") + deck.name);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != READ_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) chooseImport();
        else toast("Storage not allowed");
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CARD_GRANT) return;
        File file = importing;
        importing = null;
        if (file == null) return;
        if (CardGrant.answered(this, result, data)) importFile(file);
        else toast("SD card not allowed");
    }

    // ---------------------------------------------------------------------
    // A deck's overview

    private void openDeck(Deck deck) {
        open = deck;
        review = null;
        showOverview();
    }

    /** Anki's overview: the deck's name, its three counts, and Study Now; or its congratulations. */
    private void showOverview() {
        getWindow().getDecorView().removeCallbacks(refresh);
        screen = Screen.OVERVIEW;
        ReviewSession session = session(open);
        session.setAhead(false);
        long now = System.currentTimeMillis() / 1000L;
        int[] counts = session.counts(now);
        boolean any = counts[0] + counts[1] + counts[2] > 0;

        LinearLayout page = vertical();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(open.name, 16, color(R.color.text));
        title.setTypeface(Anki.MEDIUM);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(8), dp(14), dp(8), dp(10));
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView study = null;
        if (any) {
            String[] labels = {"New:", "Learning:", "To Review:"};
            for (int i = 0; i < 3; i++) {
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                TextView label = text(labels[i], 13, color(R.color.text));
                label.setGravity(Gravity.END);
                label.setPadding(0, 0, dp(6), 0);
                line.addView(label, new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
                line.addView(text(Integer.toString(counts[i]), 13, Anki.countColor(this, i, counts[i])),
                    new LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT));
                line.setPadding(0, dp(1), 0, dp(1));
                page.addView(line, new LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            study = Anki.button(this, "Study Now");
            study.setOnClickListener(v -> startReview(false));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(120), dp(30));
            at.topMargin = dp(16);
            page.addView(study, at);
        } else {
            TextView done = text("Congratulations! You have finished this deck for now.", 14, color(R.color.text));
            done.setGravity(Gravity.CENTER);
            done.setPadding(dp(14), dp(10), dp(14), 0);
            page.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            long next = session.nextLearningDue(now);
            if (next > 0) {
                long minutes = Math.max(1, (next - now + 59) / 60);
                TextView when = text("The next learning card will be ready in " + minutes
                    + (minutes == 1 ? " minute." : " minutes."), 12, color(R.color.text_subtle));
                when.setGravity(Gravity.CENTER);
                when.setPadding(dp(14), dp(10), dp(14), 0);
                page.addView(when, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                // Learn-ahead serves it 20 minutes early, as Anki does.
                later(refresh, Math.max(1, next - ReviewSession.LEARN_AHEAD - now) * 1000L);
            }
            // Anki's "Review ahead": the cards not yet due, likeliest forgotten first.
            if (studied(open)) {
                study = Anki.button(this, "Review Ahead");
                study.setOnClickListener(v -> startReview(true));
                LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(130), dp(30));
                at.topMargin = dp(16);
                page.addView(study, at);
            }
        }

        LinearLayout box = vertical();
        box.addView(page, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View hairline = new View(this);
        hairline.setBackgroundColor(color(R.color.border));
        box.addView(hairline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(0, dp(6), 0, dp(6));
        TextView options = Anki.button(this, "Options");
        options.setOnClickListener(v -> showOptions(open));
        bar.addView(options, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));
        TextView stats = Anki.button(this, "Stats");
        stats.setOnClickListener(v -> showStats(open));
        LinearLayout.LayoutParams statsAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        statsAt.leftMargin = dp(8);
        bar.addView(stats, statsAt);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.fill(content, box);
        if (study != null) {
            study.setNextFocusDownId(View.NO_ID);
            study.requestFocus();
        } else {
            options.requestFocus();
        }
    }

    private static boolean studied(Deck deck) {
        for (DeckCard card : deck.cards()) {
            if (!card.isNew()) return true;
        }
        return false;
    }

    private void startReview(boolean ahead) {
        screen = Screen.REVIEW;
        session(open).setAhead(ahead);
        review = new ReviewPane(this, session(open), prefs().getBoolean(PREF_PROGRESS, false), new ReviewPane.Listener() {
            @Override public void onRated() {
                later(saveSoon, SAVE_AFTER_MS);
                later(syncSoon, SYNC_AFTER_MS);
            }

            @Override public void onDone() {
                content.post(() -> {
                    if (screen == Screen.REVIEW) showOverview();
                });
            }
        });
        if (screen != Screen.REVIEW) return;
        Ui.fill(content, review);
        review.requestFocus();
    }

    /** Menu on a card: Remove from deck takes it out here, and the next sync everywhere. */
    private void cardMenu() {
        final DeckCard card = session(open).current();
        if (card == null || review == null) return;
        final ReviewPane shown = review;
        listDialog("Card", Arrays.asList("Remove from deck", nightShiftItem()), at -> {
            if (at == 1) {
                toggleNightShift();
                return;
            }
            if (screen != Screen.REVIEW || review != shown || session(open).current() != card) return;
            collection.remove(card, System.currentTimeMillis());
            review.showNext();
            toast("Removed from " + open.name);
            // Saved and synced as an answer is: later, off the key press.
            later(saveSoon, SAVE_AFTER_MS);
            later(syncSoon, SYNC_AFTER_MS);
        }, null);
    }

    // ---------------------------------------------------------------------
    // A deck's options

    private void showOptions(final Deck deck) {
        open = deck;
        screen = Screen.OPTIONS;
        OptionsPane pane = new OptionsPane(this, deck, prefs().getBoolean(PREF_PROGRESS, false));
        Ui.fill(content, pane);
        pane.requestFocus();
    }

    /** Leaving the options keeps what was set, as Anki's Save does. */
    private void closeOptions() {
        OptionsPane pane = (OptionsPane) content.getChildAt(0);
        prefs().edit().putBoolean(PREF_PROGRESS, pane.showProgress()).apply();
        if (pane.value() != open.newPerDay) {
            open.newPerDay = pane.value();
            Collection.touch(open, System.currentTimeMillis());
            save();
            sync();
        }
        showOverview();
    }

    // ---------------------------------------------------------------------
    // A deck's statistics

    private void showStats(Deck deck) {
        open = deck;
        screen = Screen.STATS;
        StatsPane pane = new StatsPane(this, deck, collection, zone);
        Ui.fill(content, pane);
        pane.requestFocus();
    }

    // ---------------------------------------------------------------------
    // Keys

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && !event.isCanceled()) {
            if (screen == Screen.DECKS) deckMenu(null);
            else if (screen == Screen.OVERVIEW) deckMenu(open);
            else if (screen == Screen.REVIEW) cardMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        switch (screen) {
            case REVIEW:
            case STATS:
                showOverview();
                return;
            case OPTIONS:
                closeOptions();
                return;
            case OVERVIEW:
                showDecks();
                return;
            default:
                super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------------
    // The command center login

    private boolean nightShift() {
        return prefs().getBoolean("nightShift", false);
    }

    private String nightShiftItem() {
        return nightShift() ? "Night Shift: on" : "Night Shift: off";
    }

    private void toggleNightShift() {
        boolean on = !nightShift();
        prefs().edit().putBoolean("nightShift", on).apply();
        NightShift.apply(getWindow().getDecorView(), on);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** The bundled login, adopted once per build that carries a new one, else what was typed in. */
    private Login loadLogin(SharedPreferences prefs) {
        String revision = Bundled.REVISION;
        if (revision.length() > 0 && !revision.equals(prefs.getString("bundled", ""))) {
            Login bundled;
            try {
                bundled = Login.of(Bundled.SERVER, Bundled.TOKEN);
            } catch (IllegalArgumentException e) {
                bundled = Login.blank();
            }
            if (bundled.ready()) {
                prefs.edit().putString("login", bundled.toJson()).putString("bundled", revision).apply();
                return bundled;
            }
        }
        return Login.parse(prefs.getString("login", ""));
    }

    private void askLogin() {
        LinearLayout box = vertical();
        int pad = dp(16);
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
        AlertDialog shown = new AlertDialog.Builder(this)
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
                    syncError = null;
                    pairing = null;
                    if (screen == Screen.DECKS) showDecks();
                    sync();
                } catch (IllegalArgumentException e) {
                    toast(e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
        // The fields keep Down and Up to themselves; on a keys-only phone the
        // d-pad is how one goes from field to field and on to the buttons.
        Button cancel = shown.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button save = shown.getButton(AlertDialog.BUTTON_POSITIVE);
        upDown(server, null, token);
        upDown(token, server, cancel);
        upDown(cancel, token, null);
        upDown(save, token, null);
    }

    /** Up and Down move the focus from `view` to `up` and `down` (null: stay as Android has it). */
    private static void upDown(View view, View up, View down) {
        if (view == null) return;
        view.setOnKeyListener((v, keyCode, event) -> {
            View to = keyCode == KeyEvent.KEYCODE_DPAD_UP ? up
                : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? down : null;
            if (to == null) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) to.requestFocus();
            return true;
        });
    }
}
