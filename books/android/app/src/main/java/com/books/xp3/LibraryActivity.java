package com.books.xp3;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.books.xp3.shelf.Shelf;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The library: every .epub and .txt on the phone and its SD card, the book
 * last read first.
 *
 * Keys: Up and Down choose, OK opens, Menu marks a book read or unread or
 * looks for new books.
 */
public final class LibraryActivity extends Activity {

    private static final int ASK_STORAGE = 1;
    private static final int MENU_UNREAD = 1;
    private static final int MENU_FINISHED = 2;
    private static final int MENU_REFRESH = 3;

    private Shelf shelf;
    private Covers covers;
    private LibraryView view;
    private final ExecutorService scans = Shared.single("books-scan");
    private final ExecutorService coverLoads = Shared.single("books-covers");
    private boolean scanning;
    /** Set when a book is opened, so the list comes back with it on top. */
    private boolean returning;
    private Shelf.Entry menuBook;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        shelf = Shared.shelf(this);
        covers = new Covers(this);
        view = new LibraryView(this);
        setContentView(view);
        registerForContextMenu(view);
        if (!allowed()) {
            view.setMessage("Find your books", "Books needs to read the phone\u2019s storage to find them.\nPress OK to allow.");
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
            view.setMessage("Storage is turned off",
                "Allow Storage for Books in Settings \u203A Apps \u203A Books \u203A Permissions.");
        } else {
            view.setMessage("Find your books", "Books needs to read the phone\u2019s storage to find them.\nPress OK to allow.");
        }
    }

    // ------------------------------------------------------------------
    // Finding books
    // ------------------------------------------------------------------

    private void scan() {
        if (scanning) return;
        scanning = true;
        final List<File> roots = roots();
        scans.execute(new Runnable() {
            @Override
            public void run() {
                final List<Shelf.Entry> found = shelf.scan(roots);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (isFinishing()) return;
                        show(found);
                    }
                });
            }
        });
    }

    private void show(List<Shelf.Entry> books) {
        if (books.isEmpty()) {
            view.setBooks(books);
            view.setMessage("No books yet",
                "Copy .epub or .txt files to the phone or its SD card, into Books or any folder, and they will show up here.");
            return;
        }
        view.setBooks(books);
        loadCovers(books);
    }

    /** Internal storage, and the root of each SD card. */
    private List<File> roots() {
        List<File> roots = new ArrayList<File>();
        File main = Environment.getExternalStorageDirectory();
        if (main != null) roots.add(main);
        File[] volumes = getExternalFilesDirs(null);
        if (volumes != null) {
            for (File dir : volumes) {
                if (dir == null) continue;
                String path = dir.getAbsolutePath();
                int cut = path.indexOf("/Android/");
                if (cut <= 0) continue;
                File root = new File(path.substring(0, cut));
                if (main != null && root.getAbsolutePath().equals(main.getAbsolutePath())) continue;
                roots.add(root);
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
        coverLoads.execute(new Runnable() {
            @Override
            public void run() {
                for (final Shelf.Entry book : wanted) {
                    final Bitmap bitmap = covers.load(book, width, height);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            view.setCover(book.path, bitmap);
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
                if (event.isTracking() && !event.isCanceled()) ok();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (event.isTracking() && !event.isCanceled()) showMenu();
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
    // The Menu key: the platform's own popup, beside the chosen book
    // ------------------------------------------------------------------

    private void showMenu() {
        if (!allowed()) return;
        menuBook = view.hasBooks() ? view.focused() : null;
        float y = menuBook != null ? view.focusedBottom() : view.getHeight() / 2f;
        view.showContextMenu(view.getWidth() / 4f, Math.min(y, view.getHeight() - 1));
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo info) {
        int order = 0;
        if (menuBook != null) {
            if (!menuBook.isNew()) menu.add(Menu.NONE, MENU_UNREAD, order++, "Mark as Unread");
            if (!menuBook.finished() && menuBook.length > 0) menu.add(Menu.NONE, MENU_FINISHED, order++, "Mark as Finished");
        }
        menu.add(Menu.NONE, MENU_REFRESH, order, "Look for New Books");
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Shelf.Entry book = menuBook;
        switch (item.getItemId()) {
            case MENU_UNREAD:
                if (book != null) shelf.forget(book.path);
                break;
            case MENU_FINISHED:
                if (book != null) shelf.progress(book.path, book.length, book.length, book.opened);
                break;
            case MENU_REFRESH:
                scan();
                return true;
            default:
                return false;
        }
        Shared.save(shelf);
        show(shelf.books());
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scans.shutdown();
        coverLoads.shutdownNow();
    }
}
