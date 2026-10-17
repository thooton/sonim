package com.books.xp3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.books.xp3.shelf.Shelf;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The library: every .epub and .txt in the Books folder of the phone and of
 * its SD card, the book last read first.
 *
 * Keys: Up and Down choose, OK opens, Menu marks a book read or unread or
 * looks for new books.
 */
public final class LibraryActivity extends BaseActivity implements ListPanel.Listener {

    private static final int ASK_STORAGE = 1;
    private static final String BOOKS = "Books";
    private static final int MENU_UNREAD = 1;
    private static final int MENU_FINISHED = 2;
    private static final int MENU_REFRESH = 3;

    private Shelf shelf;
    private Covers covers;
    private FrameLayout root;
    private LibraryView view;
    /** The Menu key's sheet, over the bottom of the list while it is open. */
    private ListPanel menu;
    private boolean menuOpen;
    private final ExecutorService scans = Ui.worker("books-scan");
    private final ExecutorService coverLoads = Ui.worker("books-covers");
    private boolean scanning;
    /** Set when a book is opened, so the list comes back with it on top. */
    private boolean returning;
    private Shelf.Entry menuBook;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        shelf = Shared.shelf(this);
        covers = new Covers(this);
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
        if (returning) {
            view.focusFirst();
            returning = false;
        }
        scan();
    }

    private boolean allowed() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
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

    // ------------------------------------------------------------------
    // Finding books
    // ------------------------------------------------------------------

    private void scan() {
        if (scanning) return;
        scanning = true;
        final List<File> roots = roots();
        scans.execute(() -> {
            final List<Shelf.Entry> found = shelf.scan(roots);
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
        returning = true;
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_PATH, book.path);
        startActivity(intent);
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
        }
        rows.add(new ListPanel.Row(MENU_REFRESH, ListPanel.PLAIN, "Look for New Books"));
        menu.show(menuBook != null ? menuBook.displayTitle() : "Library", true, rows, 0);
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
            default:
                return;
        }
        Shared.save(shelf);
        show(shelf.books());
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
    }
}
