package com.source.xp3;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.source.xp3.net.Books;
import com.source.xp3.net.Login;
import com.source.xp3.net.Music;
import com.xp3.common.ui.BaseActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * One screen, three things to fetch: a song, an album or a book. Type
 * "Artist - Title" (or "Artist - Album") and press OK, and the command center
 * finds and downloads it while its log scrolls by below; the files land in the
 * phone's Music folder, albums in a folder of their own. Or type a book's
 * title, author or ISBN and press OK, and the results take the log's place:
 * Down moves into them, OK downloads the one picked into the Books folder.
 *
 * Keys: OK downloads (or searches, for books); Left and Right switch between
 * Song, Album and Book while the box is empty, and turn the page of book
 * results while picking; Up and Down scroll the log or move through the
 * results; Back cancels, stops picking, or leaves; Menu has the rest.
 */
public final class MainActivity extends BaseActivity implements Download.Listener {

    private static final int ASK_STORAGE = 1;
    private static final String PREFS = "source";
    static final String BOOK = "book";
    private static final String[] MODES = {Music.SONG, Music.ALBUM, BOOK};

    private EditText query;
    private TextView modeView;
    private TextView statusView;
    private TextView logView;
    private ScrollView scroll;
    private ListView resultList;
    private final ResultAdapter adapter = new ResultAdapter();
    private String mode = Music.SONG;
    private Login login = Login.blank();
    /** Whether the keys are moving through the book results rather than typing. */
    private boolean picking;
    private int picked;
    private Books.Page shown;
    private Runnable whenAllowed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        query = (EditText) findViewById(R.id.query);
        modeView = (TextView) findViewById(R.id.mode);
        statusView = (TextView) findViewById(R.id.status);
        logView = (TextView) findViewById(R.id.log);
        scroll = (ScrollView) findViewById(R.id.scroll);
        resultList = (ListView) findViewById(R.id.results);
        resultList.setAdapter(adapter);

        SharedPreferences prefs = prefs();
        mode = Music.SONG;
        for (String m : MODES) if (m.equals(prefs.getString("mode", Music.SONG))) mode = m;
        login = loadLogin(prefs);

        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                begin();
                return true;
            }
            return false;
        });
        showMode();
        query.requestFocus();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from the menu) is kept.
     */
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

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean books() {
        return BOOK.equals(mode);
    }

    /** Only EPUBs, which reflow on a small screen, unless every format was asked for. */
    private String format() {
        return prefs().getBoolean("any_format", false) ? null : "epub";
    }

    @Override
    protected void onStart() {
        super.onStart();
        Download.listen(this);
        changed();
    }

    @Override
    protected void onStop() {
        Download.listen(null);
        super.onStop();
    }

    @Override
    public void changed() {
        boolean running = Download.running();
        Books.Page page = Download.results();
        if (page != shown) {
            // A new page of results: start at its top.
            shown = page;
            picked = 0;
            picking = page != null && !page.books.isEmpty() && books();
        }
        if (page == null || page.books.isEmpty()) picking = false;

        String status = Download.status();
        if (status.length() == 0) {
            if (!login.ready()) status = "No command center: Menu → Command center.";
            else if (books()) status = "OK searches for books.";
            else status = "OK downloads into Music.";
        }
        statusView.setText(status);

        boolean list = books() && page != null;
        resultList.setVisibility(list ? View.VISIBLE : View.GONE);
        scroll.setVisibility(list ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        if (list) {
            resultList.post(() -> {
                resultList.smoothScrollToPosition(picked);
                query.requestFocus();
            });
        } else {
            StringBuilder text = new StringBuilder();
            for (String line : Download.log()) {
                if (text.length() > 0) text.append('\n');
                text.append(line);
            }
            logView.setText(text);
            scroll.post(() -> {
                scroll.fullScroll(View.FOCUS_DOWN);
                query.requestFocus();
            });
        }
        query.setEnabled(!running);
    }

    private void showMode() {
        if (Music.ALBUM.equals(mode)) {
            modeView.setText("◂ Album ▸");
            query.setHint(R.string.hint_album);
        } else if (books()) {
            modeView.setText("◂ Book ▸");
            query.setHint(R.string.hint_book);
        } else {
            modeView.setText("◂ Song ▸");
            query.setHint(R.string.hint_song);
        }
    }

    /** Song, Album, Book and round again; `step` is 1 or -1. */
    private void cycleMode(int step) {
        int i = 0;
        while (!MODES[i].equals(mode)) i++;
        setMode(MODES[(i + step + MODES.length) % MODES.length]);
    }

    private void setMode(String next) {
        mode = next;
        picking = false;
        prefs().edit().putString("mode", mode).apply();
        showMode();
        changed();
    }

    private void begin() {
        if (Download.running()) return;
        String text = query.getText().toString().trim();
        if (text.length() == 0) {
            if (books() && hasResults()) setPicking(true);
            return;
        }
        if (!login.ready()) {
            askLogin();
            return;
        }
        if (books()) {
            Download.search(this, login, text, 1, format());
            return;
        }
        if (text.startsWith("-")) {
            statusView.setText("A name can't start with \"-\".");
            return;
        }
        if (needStorage(() -> begin())) return;
        Download.music(this, login, mode, text);
        query.setText("");
    }

    private void downloadPicked() {
        if (Download.running() || !hasResults()) return;
        if (!login.ready()) {
            askLogin();
            return;
        }
        if (needStorage(() -> { downloadPicked(); })) return;
        Download.book(this, login, Download.results().books.get(picked));
    }

    private void turnPage(int step) {
        Books.Page page = Download.results();
        if (Download.running() || page == null || !login.ready()) return;
        int next = page.page + step;
        if (next < 1 || next > page.pages) {
            statusView.setText(next < 1 ? "This is the first page." : "This is the last page.");
            return;
        }
        Download.search(this, login, page.query, next, page.format);
    }

    /** Asks for storage once, running `then` when it is granted; true while asking. */
    private boolean needStorage(Runnable then) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;
        whenAllowed = then;
        requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_STORAGE) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Runnable then = whenAllowed;
        whenAllowed = null;
        if (granted && then != null) {
            then.run();
        } else if (!granted) {
            statusView.setText("Source needs storage access to save into the Music and Books folders.");
        }
    }

    private boolean hasResults() {
        Books.Page page = Download.results();
        return page != null && !page.books.isEmpty();
    }

    private void setPicking(boolean on) {
        picking = on && hasResults();
        adapter.notifyDataSetChanged();
        if (picking) resultList.smoothScrollToPosition(picked);
    }

    private void movePick(int step) {
        int count = Download.results().books.size();
        int next = picked + step;
        if (next < 0) {
            setPicking(false);
            return;
        }
        picked = Math.min(next, count - 1);
        adapter.notifyDataSetChanged();
        resultList.smoothScrollToPosition(picked);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        boolean empty = query.getText().length() == 0;
        boolean running = Download.running();
        if (picking) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (first) downloadPicked();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) movePick(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (first) turnPage(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (!down) {
                        if (running) Download.cancel();
                        else setPicking(false);
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    if (!down) menu();
                    return true;
                default:
                    // Anything else is typing: back to the box with it.
                    if (down && !running && event.isPrintingKey()) setPicking(false);
                    return super.dispatchKeyEvent(event);
            }
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // On the press: T9 eats the first release.
                if (first) begin();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (empty || running) {
                    if (first && !running) cycleMode(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (books()) {
                    if (first && key == KeyEvent.KEYCODE_DPAD_DOWN) setPicking(true);
                } else if (down) {
                    int step = (int) (40 * getResources().getDisplayMetrics().density);
                    scroll.smoothScrollBy(0, key == KeyEvent.KEYCODE_DPAD_UP ? -step : step);
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!down) menu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (running) {
                    if (!down) Download.cancel();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    private void menu() {
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        if (Download.running()) {
            labels.add("Cancel");
            actions.add(() -> Download.cancel());
        } else {
            if (!Music.SONG.equals(mode)) {
                labels.add("Download a song");
                actions.add(() -> setMode(Music.SONG));
            }
            if (!Music.ALBUM.equals(mode)) {
                labels.add("Download an album");
                actions.add(() -> setMode(Music.ALBUM));
            }
            if (!books()) {
                labels.add("Find a book");
                actions.add(() -> setMode(BOOK));
            }
            if (books()) {
                final Books.Page page = Download.results();
                if (page != null && page.page < page.pages) {
                    labels.add("Next page");
                    actions.add(() -> turnPage(1));
                }
                if (page != null && page.page > 1) {
                    labels.add("Previous page");
                    actions.add(() -> turnPage(-1));
                }
                final boolean any = format() == null;
                labels.add(any ? "Search EPUBs only" : "Search every format");
                actions.add(() -> {
                    prefs().edit().putBoolean("any_format", !any).apply();
                    statusView.setText(any ? "Searches look for EPUBs first." : "Searches show every format.");
                });
                if (page != null) {
                    labels.add("Clear results");
                    actions.add(() -> Download.clearResults());
                }
            } else {
                labels.add("Clear log");
                actions.add(() -> Download.clearLog());
                labels.add("Reconnect music account");
                actions.add(() -> confirmRelogin());
            }
        }
        labels.add("Command center…");
        actions.add(() -> askLogin());
        new AlertDialog.Builder(this)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
            .show();
    }

    /** As `delta music re-login`: only when downloads keep failing to connect. */
    private void confirmRelogin() {
        if (!login.ready()) {
            askLogin();
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage("Only if downloads keep saying they couldn't connect: too many new logins get the command center throttled. Reconnect?")
            .setPositiveButton("Reconnect", (dialog, which) -> relogin())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void relogin() {
        statusView.setText("Reconnecting…");
        final Login using = login;
        new Thread(() -> {
            String result;
            try {
                new Music(using).relogin();
                result = "Reconnected. Try the download again.";
            } catch (Exception e) {
                result = "Couldn't reconnect: " + e.getMessage();
            }
            final String shown = result;
            runOnUiThread(() -> statusView.setText(shown));
        }).start();
    }

    /** The fallback for a build without a bundled login. */
    private void askLogin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText server = new EditText(this);
        server.setHint("https://…");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(login.server() == null ? "" : login.server());
        final EditText token = new EditText(this);
        token.setHint(login.ready() ? "Token (blank keeps it)" : "Delta token");
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
                    statusView.setText("Saved: " + next.host());
                } catch (IllegalArgumentException e) {
                    statusView.setText(e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** The book results: title over "author · year · language · format · size". */
    private final class ResultAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            Books.Page page = Download.results();
            return page == null ? 0 : page.books.size();
        }

        @Override
        public Object getItem(int position) {
            return Download.results().books.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                float density = getResources().getDisplayMetrics().density;
                int padH = getResources().getDimensionPixelSize(R.dimen.pad_h);
                int padV = (int) (5 * density);
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(padH, padV, padH, padV);
                TextView title = new TextView(MainActivity.this);
                title.setTextColor(getColor(R.color.text));
                title.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_book));
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                TextView meta = new TextView(MainActivity.this);
                meta.setTextColor(getColor(R.color.text_muted));
                meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_log));
                meta.setSingleLine(true);
                meta.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title);
                row.addView(meta);
            }
            Books.Book book = Download.results().books.get(position);
            ((TextView) row.getChildAt(0)).setText(book.title.length() > 0 ? book.title : "Untitled");
            String meta = book.meta();
            if (Download.saved(book)) meta = "✓ Saved · " + meta;
            ((TextView) row.getChildAt(1)).setText(meta);
            if (picking && position == picked) row.setBackgroundResource(R.drawable.row_focus);
            else row.setBackgroundColor(getColor(R.color.row));
            return row;
        }
    }
}
