package com.source.xp3;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import com.source.xp3.net.Books;
import com.source.xp3.net.Login;
import com.source.xp3.net.Music;
import com.source.xp3.net.Names;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one thing that can run at a time: a music download, a book search or a
 * book download. It is kept outside the activity so it carries on when the
 * phone is shut or the screen is rebuilt, and so does what it found: the log,
 * the status line, and the last page of book results. The activity attaches a
 * Listener to watch it; everything it is told arrives on the main thread.
 *
 * A music download is three steps, as `delta music` does them: the command
 * center runs it and streams its log, then the app fetches the job's files as
 * a tar straight into the shared Music folder, then it removes the job.
 *
 * Books are `delta books`: the phone logs in to the book source with the
 * account kept on the command center, searches, and downloads the chosen
 * result into the shared Books folder.
 */
final class Download {

    interface Listener {
        void changed();
    }

    /** What runs on the worker thread. */
    private interface Work {
        void run() throws IOException, InterruptedException;

        void cancel();
    }

    /** Where books are saved: a folder of their own beside Music. */
    static final String BOOKS_FOLDER = "Books";

    private static final int MAX_LINES = 300;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Download current;
    private static final List<String> log = new ArrayList<String>();
    private static String status = "";
    private static Listener listener;
    private static Books.Page results;
    private static final Set<String> savedBooks = new HashSet<String>();

    private final Context app;
    private final Work work;
    private volatile boolean running = true;

    private Download(Context app, Work work) {
        this.app = app;
        this.work = work;
    }

    static boolean running() {
        return current != null && current.running;
    }

    static String status() {
        return status;
    }

    static List<String> log() {
        return log;
    }

    /** The last page of book results, or null before any search. */
    static Books.Page results() {
        return results;
    }

    /** Whether this result has been downloaded since the app started. */
    static boolean saved(Books.Book book) {
        return savedBooks.contains(book.id + "/" + book.hash);
    }

    static void listen(Listener l) {
        listener = l;
    }

    static void clearLog() {
        if (running()) return;
        log.clear();
        status = "";
        changed();
    }

    static void clearResults() {
        if (running()) return;
        results = null;
        status = "";
        changed();
    }

    /** Stops whatever is running; a music job is dropped on the command center too. */
    static void cancel() {
        Download d = current;
        if (d == null || !d.running) return;
        d.work.cancel();
        setStatus("Cancelling…");
    }

    private static void start(Context context, String name, Work work) {
        final Download download = new Download(context.getApplicationContext(), work);
        current = download;
        new Thread(() -> {
            download.work();
        }, name).start();
    }

    private void work() {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "source:download");
        wake.acquire(45 * 60 * 1000L);
        try {
            work.run();
        } catch (InterruptedException e) {
            post("Cancelled.", "Cancelled");
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            post("✗ " + why, "Failed: " + why);
        } finally {
            running = false;
            if (wake.isHeld()) wake.release();
            post(null, null);
        }
    }

    // ---- Music.

    static void music(Context context, Login login, final String mode, final String query) {
        if (running()) return;
        final Music music = new Music(login);
        final Context app = context.getApplicationContext();
        log.clear();
        line("» " + (Music.ALBUM.equals(mode) ? "Album: " : "Song: ") + query);
        setStatus("Searching… (Back cancels)");
        start(context, "music-download", new Work() {
            @Override
            public void run() throws IOException, InterruptedException {
                runMusic(app, music, mode, query);
            }

            @Override
            public void cancel() {
                music.cancel();
            }
        });
    }

    private static void runMusic(Context app, Music music, String mode, String query) throws IOException, InterruptedException {
        String job = null;
        try {
            Music.Result result = music.run(mode, query, (text, error) -> Download.line(text));
            job = result.job;
            if (job != null) {
                String size = result.bytes > 0 ? " (" + human(result.bytes) + ")" : "";
                post(null, "Saving " + result.paths.size() + " file(s)" + size + "…");
                Saved saved = save(app, music, job, result.bytes);
                try {
                    music.delete(job);
                } catch (IOException e) {
                    // Jobs left behind are removed by the command center after a day.
                }
                job = null;
                String where = saved.folder == null ? "Music" : "Music/" + saved.folder;
                String done = "Saved " + saved.count + " file(s) to " + where;
                if (saved.kept > 0) done += ", " + saved.kept + " already there";
                post("✓ " + done, result.error == null ? done : "Saved some files; see the log");
            }
            if (result.error != null) {
                post("✗ " + result.error, result.job == null ? "Nothing downloaded; see the log" : status);
            }
        } finally {
            if (job != null) {
                try {
                    // delete() still works on a cancelled client.
                    music.delete(job);
                } catch (Exception ignored) {
                    // Removed by the command center after a day anyway.
                }
            }
        }
    }

    private static final class Saved {
        int count;
        int kept;
        String folder;
    }

    /** Fetches the job's files into the shared Music folder. */
    private static Saved save(Context app, final Music music, String job, final long total) throws IOException, InterruptedException {
        final File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Can't open the Music folder.");
        final Saved saved = new Saved();
        final List<String> scan = new ArrayList<String>();
        final long[] done = {0};
        music.fetch(job, (path, size, body) -> {
            List<String> parts = Names.parts(path);
            if (parts.isEmpty()) return;
            File dir = root;
            for (int i = 0; i < parts.size() - 1; i++) dir = new File(dir, parts.get(i));
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't make the folder " + dir.getName() + ".");
            if (parts.size() > 1 && saved.folder == null) saved.folder = parts.get(0);
            String name = parts.get(parts.size() - 1);
            File target = new File(dir, name);
            if (target.isFile() && target.length() == size) {
                // The same file from an earlier download: keep it.
                saved.kept++;
                drain(body);
                done[0] += size;
                return;
            }
            for (int n = 2; target.exists(); n++) target = new File(dir, Names.numbered(name, n));
            File part = new File(dir, "." + target.getName() + ".part");
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buffer = new byte[64 * 1024];
                long last = 0;
                for (int count; (count = body.read(buffer)) >= 0; ) {
                    if (Thread.currentThread().isInterrupted() || music.cancelled()) throw new InterruptedException();
                    out.write(buffer, 0, count);
                    done[0] += count;
                    long now = System.currentTimeMillis();
                    if (now - last > 500) {
                        last = now;
                        post(null, "Saving… " + human(done[0]) + (total > 0 ? " of " + human(total) : ""));
                    }
                }
                out.close();
            } catch (IOException | InterruptedException e) {
                try { out.close(); } catch (IOException ignored) { /* already failing */ }
                part.delete();
                throw e;
            }
            if (!part.renameTo(target)) {
                part.delete();
                throw new IOException("Can't save " + target.getName() + ".");
            }
            saved.count++;
            scan.add(target.getAbsolutePath());
            line("  " + (dir.equals(root) ? "" : dir.getName() + "/") + target.getName());
        });
        if (!scan.isEmpty()) {
            MediaScannerConnection.scanFile(app, scan.toArray(new String[0]), null, null);
        }
        return saved;
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (in.read(buffer) >= 0) {
            // Skipped: the same file is already on the phone.
        }
    }

    // ---- Books.

    private static final String SESSION_PREFS = "books";

    /** A client with the session kept from last time, keeping any new one. */
    private static Books books(Context context, Login login) {
        final SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
        Books.Session session = Books.Session.parse(prefs.getString("session", ""));
        return new Books(login, session, kept -> prefs.edit().putString("session", kept.toJson()).apply());
    }

    /**
     * Searches for `query`, in one `format` ("epub") or, when null, any. A
     * first page with nothing in that format searches every format instead.
     */
    static void search(Context context, Login login, final String query, final int page, final String format) {
        if (running()) return;
        final Books books = books(context, login);
        setStatus(page > 1 ? "Loading page " + page + "… (Back cancels)" : "Searching… (Back cancels)");
        start(context, "book-search", new Work() {
            @Override
            public void run() throws IOException, InterruptedException {
                Books.Page found = books.search(query, page, format);
                String note = "";
                if (found.books.isEmpty() && format != null && page == 1) {
                    post(null, "No " + format.toUpperCase(Locale.US) + "s; searching every format…");
                    found = books.search(query, 1, null);
                    if (!found.books.isEmpty()) note = "No " + format.toUpperCase(Locale.US) + "s, so every format. ";
                }
                final Books.Page shown = found;
                final String said = found.books.isEmpty()
                    ? "Nothing found for \"" + query + "\"."
                    : note + "Page " + found.page + " of " + found.pages + ": Down picks, OK downloads.";
                MAIN.post(() -> {
                    results = shown;
                    status = said;
                    changed();
                });
            }

            @Override
            public void cancel() {
                books.cancel();
            }
        });
    }

    static void book(Context context, Login login, final Books.Book book) {
        if (running()) return;
        final Books books = books(context, login);
        final Context app = context.getApplicationContext();
        line("» Book: " + book.title);
        setStatus("Downloading… (Back cancels)");
        start(context, "book-download", new Work() {
            @Override
            public void run() throws IOException, InterruptedException {
                File folder = new File(Environment.getExternalStorageDirectory(), BOOKS_FOLDER);
                Books.Saved saved = books.download(book, folder, (done, total) -> {
                    post(null, "Downloading… " + human(done) + (total > 0 ? " of " + human(total) : ""));
                });
                if (!saved.kept) {
                    MediaScannerConnection.scanFile(app, new String[] {saved.file.getAbsolutePath()}, null, null);
                }
                final String key = book.id + "/" + book.hash;
                MAIN.post(() -> savedBooks.add(key));
                String where = BOOKS_FOLDER + "/" + saved.file.getName();
                post("✓ " + (saved.kept ? "Already there: " : "Saved ") + where,
                    saved.kept ? "Already in " + BOOKS_FOLDER : "Saved to " + BOOKS_FOLDER + " (" + human(saved.bytes) + ")");
            }

            @Override
            public void cancel() {
                books.cancel();
            }
        });
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // Everything below hops to the main thread, which owns `log` and `status`.

    private static void line(final String text) {
        post(text, null);
    }

    private static void setStatus(String text) {
        status = text;
        changed();
    }

    private static void post(final String text, final String newStatus) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(text, newStatus);
            return;
        }
        MAIN.post(() -> apply(text, newStatus));
    }

    private static void apply(String text, String newStatus) {
        if (text != null) {
            for (String one : text.split("\n")) {
                log.add(one);
            }
            while (log.size() > MAX_LINES) log.remove(0);
        }
        if (newStatus != null) status = newStatus;
        changed();
    }

    private static void changed() {
        if (listener != null) listener.changed();
    }
}
