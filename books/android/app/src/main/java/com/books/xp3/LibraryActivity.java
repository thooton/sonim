package com.books.xp3;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.books.xp3.shelf.Shelf;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.CardGrant;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * The library: every .epub and .txt in the Books folder of the phone and of
 * its SD card, the book last read first.
 *
 * Keys: Up and Down choose, OK opens, Menu marks a book read or unread,
 * converts an EPUB to MOBI, deletes a book, or looks for new books.
 */
public final class LibraryActivity extends BaseActivity implements ListPanel.Listener {

    private static final int ASK_STORAGE = 1;
    private static final int ASK_WRITE = 2;
    private static final int ASK_CARD = 3;
    private static final String BOOKS = "Books";
    private static final int MENU_UNREAD = 1;
    private static final int MENU_FINISHED = 2;
    private static final int MENU_REFRESH = 3;
    private static final int MENU_CONVERT = 4;
    private static final int MENU_DELETE = 5;
    private static final int MENU_DELETE_NOW = 6;
    private static final int MENU_KEEP = 7;
    /** How long a notice stays in the header. */
    private static final long NOTICE_MS = 5000;

    private Shelf shelf;
    private CardFiles cards;
    private Covers covers;
    private FrameLayout root;
    private LibraryView view;
    /** The Menu key's sheet, over the bottom of the list while it is open. */
    private ListPanel menu;
    private boolean menuOpen;
    private final ExecutorService scans = Ui.worker("books-scan");
    private final ExecutorService coverLoads = Ui.worker("books-covers");
    private final ExecutorService conversions = Ui.worker("books-convert");
    /** True while a book is being converted; one at a time. */
    private boolean converting;
    /** What was chosen before storage or the SD card was allowed: done once it is. */
    private Runnable afterWrite;
    private final Runnable clearNotice = () -> view.setNotice(converting ? "Converting\u2026" : null);
    private boolean scanning;
    private Shelf.Entry menuBook;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        shelf = Shared.shelf(this);
        cards = new CardFiles(this);
        covers = new Covers(this, cards);
        root = new FrameLayout(this);
        view = new LibraryView(this);
        root.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        menu = new ListPanel(this);
        menu.setListener(this);
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));
        setContentView(root);
        if (!allowed()) {
            view.setMessage("No storage access", null);
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Night Shift is set in the reader, and warms the library too.
        NightShift.apply(root, new Settings(this).nightShift);
        if (!allowed()) return;
        List<Shelf.Entry> known = shelf.books();
        if (!known.isEmpty()) show(known);
        else if (!view.hasBooks()) view.setMessage("Looking for books\u2026", null);
        scan();
    }

    private boolean allowed() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == ASK_WRITE) {
            Runnable action = afterWrite;
            afterWrite = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (action != null) action.run();
            } else {
                notice("Not allowed to change storage");
            }
            return;
        }
        if (code != ASK_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            view.setMessage("Looking for books\u2026", null);
            scan();
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            view.setMessage("Storage is turned off", null);
        } else {
            view.setMessage("No storage access", null);
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != ASK_CARD) return;
        Runnable action = afterWrite;
        afterWrite = null;
        if (CardGrant.answered(this, result, data)) {
            // Books that couldn't be read are given their titles and covers.
            scan();
            if (action != null) action.run();
        } else {
            notice("Not allowed to use the SD card");
        }
    }

    // ------------------------------------------------------------------
    // Finding books
    // ------------------------------------------------------------------

    private void scan() {
        if (scanning) return;
        scanning = true;
        final List<File> roots = roots();
        scans.execute(() -> {
            final List<Shelf.Entry> found = shelf.scan(roots, cards);
            runOnUiThread(() -> {
                scanning = false;
                if (isFinishing()) return;
                show(found);
            });
        });
    }

    private void show(List<Shelf.Entry> books) {
        if (books.isEmpty()) {
            view.setBooks(books);
            view.setMessage("No books yet", null);
            return;
        }
        view.setBooks(books);
        loadCovers(books);
    }

    /**
     * The only places books are looked for: the Books folder at the top of
     * the phone's storage (beside Pictures and Music), and the one at the top
     * of each SD card.
     */
    private List<File> roots() {
        List<File> roots = new ArrayList<File>();
        File main = Environment.getExternalStorageDirectory();
        if (main != null) roots.add(new File(main, BOOKS));
        File[] volumes = getExternalFilesDirs(null);
        if (volumes != null) {
            for (File dir : volumes) {
                if (dir == null) continue;
                String path = dir.getAbsolutePath();
                int cut = path.indexOf("/Android/");
                if (cut <= 0) continue;
                File root = new File(path.substring(0, cut));
                if (main != null && root.getAbsolutePath().equals(main.getAbsolutePath())) continue;
                roots.add(new File(root, BOOKS));
            }
        }
        return roots;
    }

    private void loadCovers(List<Shelf.Entry> books) {
        final List<Shelf.Entry> wanted = new ArrayList<Shelf.Entry>();
        for (Shelf.Entry book : books) if (!view.hasCover(book.path)) wanted.add(book);
        if (wanted.isEmpty()) return;
        final int width = view.coverWidth;
        final int height = view.coverHeight;
        coverLoads.execute(() -> {
            for (final Shelf.Entry book : wanted) {
                final Bitmap bitmap = covers.load(book, width, height);
                runOnUiThread(() -> view.setCover(book.path, bitmap));
            }
        });
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (menuOpen) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                menu.move(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                menu.move(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                view.move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                view.move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                view.move(-5);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                view.move(5);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) event.startTracking();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!event.isTracking() || event.isCanceled()) return true;
                if (menuOpen) menu.activate();
                else ok();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!event.isTracking() || event.isCanceled()) return true;
                if (menuOpen) closeMenu();
                else showMenu();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void ok() {
        if (!allowed()) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
            return;
        }
        Shelf.Entry book = view.hasBooks() ? view.focused() : null;
        if (book == null) {
            scan();
            return;
        }
        final String path = book.path;
        Runnable open = () -> {
            Intent intent = new Intent(this, ReaderActivity.class);
            intent.putExtra(ReaderActivity.EXTRA_PATH, path);
            startActivity(intent);
        };
        if (cards.needsGrant(book.file())) askCard(open);
        else open.run();
    }

    // ------------------------------------------------------------------
    // The Menu key: an action sheet for the chosen book
    // ------------------------------------------------------------------

    /*
     * Drawn by the app rather than the platform's popup, which is a window of
     * its own that Night Shift's filter cannot reach.
     */
    private void showMenu() {
        if (!allowed()) return;
        menuBook = view.hasBooks() ? view.focused() : null;
        List<ListPanel.Row> rows = new ArrayList<ListPanel.Row>();
        if (menuBook != null) {
            if (!menuBook.isNew()) rows.add(new ListPanel.Row(MENU_UNREAD, ListPanel.PLAIN, "Mark as Unread"));
            if (!menuBook.finished() && menuBook.length > 0) {
                rows.add(new ListPanel.Row(MENU_FINISHED, ListPanel.PLAIN, "Mark as Finished"));
            }
            if (isEpub(menuBook)) rows.add(new ListPanel.Row(MENU_CONVERT, ListPanel.PLAIN, "Convert to MOBI"));
        }
        rows.add(new ListPanel.Row(MENU_REFRESH, ListPanel.PLAIN, "Look for New Books"));
        if (menuBook != null) {
            ListPanel.Row delete = new ListPanel.Row(MENU_DELETE, ListPanel.PLAIN, "Delete Book");
            delete.destructive = true;
            rows.add(delete);
        }
        openSheet(menuBook != null ? menuBook.displayTitle() : "Library", rows, 0);
    }

    /** Asks before deleting, Cancel first so a stray OK keeps the book. */
    private void confirmDelete(Shelf.Entry book) {
        menuBook = book;
        List<ListPanel.Row> rows = new ArrayList<ListPanel.Row>();
        rows.add(new ListPanel.Row(0, ListPanel.HEADER, "Erase it from the phone?"));
        rows.add(new ListPanel.Row(MENU_KEEP, ListPanel.PLAIN, "Cancel"));
        ListPanel.Row delete = new ListPanel.Row(MENU_DELETE_NOW, ListPanel.PLAIN, "Delete");
        delete.destructive = true;
        rows.add(delete);
        openSheet(book.displayTitle(), rows, 1);
    }

    private void openSheet(String title, List<ListPanel.Row> rows, int focus) {
        menu.show(title, true, rows, focus);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) menu.getLayoutParams();
        params.height = menu.sheetHeight(root.getHeight());
        menu.setLayoutParams(params);
        menu.setVisibility(View.VISIBLE);
        menuOpen = true;
    }

    private void closeMenu() {
        menuOpen = false;
        menu.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (menuOpen) closeMenu();
        else super.onBackPressed();
    }

    @Override
    public void onActivate(ListPanel.Row row) {
        Shelf.Entry book = menuBook;
        closeMenu();
        switch (row.id) {
            case MENU_UNREAD:
                if (book != null) shelf.forget(book.path);
                break;
            case MENU_FINISHED:
                if (book != null) shelf.progress(book.path, book.length, book.length, book.opened);
                break;
            case MENU_REFRESH:
                scan();
                return;
            case MENU_CONVERT:
                if (book != null) withWrite(book, () -> convert(book));
                return;
            case MENU_DELETE:
                if (book != null) confirmDelete(book);
                return;
            case MENU_DELETE_NOW:
                if (book != null) withWrite(book, () -> delete(book));
                return;
            default:
                return;
        }
        Shared.save(shelf);
        show(shelf.books());
    }

    // ------------------------------------------------------------------
    // Deleting and converting: both write to storage
    // ------------------------------------------------------------------

    /*
     * On the phone's storage, writing is in the same permission group as
     * reading, which the library already has, so the system grants it without
     * asking. On the SD card it takes Android's grant for the card, asked for
     * once (CardFiles).
     */
    private void withWrite(Shelf.Entry book, Runnable action) {
        if (cards.onCard(book.file())) {
            if (cards.granted()) action.run();
            else askCard(action);
            return;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        afterWrite = action;
        requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_WRITE);
    }

    /** Android's "Allow Books to access SD card?", then `action` if it is. */
    private void askCard(Runnable action) {
        Intent ask = CardGrant.ask(this);
        if (ask != null) {
            try {
                afterWrite = action;
                startActivityForResult(ask, ASK_CARD);
                return;
            } catch (ActivityNotFoundException e) {
                afterWrite = null;
            }
        }
        // No way to ask: the action says why it can't.
        action.run();
    }

    /** Deletes the book's file from the phone, and the book from the shelf. */
    private void delete(Shelf.Entry book) {
        File file = book.file();
        if (!cards.delete(file)) {
            notice("Couldn\u2019t delete it");
            return;
        }
        shelf.remove(book.path);
        Shared.save(shelf);
        MediaScannerConnection.scanFile(getApplicationContext(), new String[] {file.getAbsolutePath()}, null, null);
        show(shelf.books());
        notice("Deleted");
    }

    /**
     * Writes the book as a MOBI beside it, under the same name (replacing an
     * earlier conversion), off the main thread. The library doesn't list
     * MOBIs; the file is there for a Kindle.
     */
    private void convert(Shelf.Entry book) {
        if (converting) {
            notice("Already converting a book");
            return;
        }
        final File epub = book.file();
        String name = epub.getName();
        final File mobi = new File(epub.getParentFile(), name.substring(0, name.length() - ".epub".length()) + ".mobi");
        converting = true;
        view.removeCallbacks(clearNotice);
        view.setNotice("Converting\u2026");
        final Context app = getApplicationContext();
        final CardFiles files = cards;
        conversions.execute(() -> {
            String said;
            try {
                files.saveMobi(epub, mobi, new KindleImages());
                MediaScannerConnection.scanFile(app, new String[] {mobi.getAbsolutePath()}, null, null);
                said = "Saved as MOBI";
            } catch (FileNotFoundException e) {
                // "Can't read it" is the book; anything else, the MOBI couldn't be written.
                String message = e.getMessage();
                said = message != null && message.startsWith("Can\u2019t read")
                    ? message : "Couldn\u2019t save it beside the book";
            } catch (IOException e) {
                said = "Couldn\u2019t convert it";
            } catch (RuntimeException | OutOfMemoryError e) {
                said = "Couldn\u2019t convert it";
            }
            final String done = said;
            runOnUiThread(() -> {
                converting = false;
                if (!isDestroyed()) notice(done);
            });
        });
    }

    private static boolean isEpub(Shelf.Entry book) {
        return book.path.toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    /** A line in the header for a few seconds: no toasts, which Night Shift can't reach. */
    private void notice(String text) {
        view.removeCallbacks(clearNotice);
        view.setNotice(text);
        view.postDelayed(clearNotice, NOTICE_MS);
    }

    @Override
    public void onAdjust(ListPanel.Row row, int delta) {
        // The sheet has nothing to adjust.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scans.shutdown();
        coverLoads.shutdownNow();
        // A conversion under way is finished, so no half-written file is left.
        conversions.shutdown();
        view.removeCallbacks(clearNotice);
    }
}
