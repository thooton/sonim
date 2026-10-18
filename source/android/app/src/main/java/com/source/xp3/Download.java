package com.source.xp3;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import com.source.xp3.data.History;
import com.source.xp3.net.Books;
import com.source.xp3.net.Music;
import com.source.xp3.net.Names;
import com.xp3.common.Disk;
import com.xp3.common.Login;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything that talks to the network, one thing at a time: a music or book
 * search, or a download. It is kept outside the activities so it carries on
 * when the phone is shut or a screen is rebuilt, and so does what it found:
 * the search's log and status line, the last music results, the last page of
 * book results, and every download of this session with its own state and
 * log. Downloads wait their turn in a queue; a search goes ahead of the queue
 * but waits for the download that is running, since two runs at once would
 * log the command center's Soulseek account in twice. Everything is saved
 * onto the SD card, never the phone's own storage. Saved downloads are
 * remembered across restarts in History. Activities attach a Listener to
 * watch; everything they are told arrives on the main thread, which owns all
 * of this state.
 *
 * Music starts with a search on the command center, which streams its log
 * and ends with the results to pick from. Downloading one is three steps, as
 * `delta music` does them: the command center downloads it and streams its
 * log, then the app fetches the job's files as a tar straight into the
 * Music folder on the SD card, then it removes the job.
 *
 * Books are `delta books`: the phone logs in to the book source with the
 * account kept on the command center, searches, and downloads the chosen
 * result into the Books folder on the SD card.
 */
final class Download {

    interface Listener {
        void changed();
    }

    /** Where books are saved: a folder of their own on the SD card. */
    static final String BOOKS_FOLDER = "Books";
    static final String BOOK = "book";

    /** Where a result stands. */
    static final int IDLE = 0;
    static final int QUEUED = 1;
    static final int RUNNING = 2;
    static final int SAVED = 3;
    static final int FAILED = 4;

    /** One download: a song, an album's folder or a book. Changed on the main thread only. */
    static final class Job {
        final String key;
        /** Music.SONG, Music.ALBUM or BOOK. */
        final String kind;
        final Music.Hit hit;
        final Books.Book book;
        final String title;
        /** The folders a music result sits in, or a book's author. */
        final String folders;
        // Idle until queue() takes it; queue() passes over a job already waiting or running.
        int state = IDLE;
        /** What it is doing now, while it runs. */
        String phase = "";
        long done;
        /** Bytes expected; 0 or less while that isn't known. */
        long total;
        String error;
        /** How it ended, when it was saved: where to, and what was already there. */
        String note = "";
        final List<String> log = new ArrayList<String>();
        /** Where its files were saved, absolute, as they arrive. */
        final List<String> files = new ArrayList<String>();
        /** Its tracks' file names that have arrived, for an album's track list. */
        final Set<String> arrived = new HashSet<String>();
        Login login;

        Job(Music.Hit hit) {
            this.key = hit.link;
            this.kind = hit.album() ? Music.ALBUM : Music.SONG;
            this.hit = hit;
            this.book = null;
            this.title = hit.name();
            this.folders = hit.folders();
        }

        Job(Books.Book book) {
            this.key = key(book);
            this.kind = BOOK;
            this.hit = null;
            this.book = book;
            this.title = book.title.length() > 0 ? book.title : "Untitled";
            this.folders = book.author;
        }

        /** How far it is, 0 to 1, or -1 while that isn't known. */
        float progress() {
            if (state != RUNNING || total <= 0) return -1;
            return Math.min(1f, done / (float) total);
        }

        boolean music() {
            return hit != null;
        }
    }

    /**
     * What runs on the worker thread; told how it ended on the main thread.
     * As it stands it is a search, which ends in its log and status line; a
     * download overrides how it ends.
     */
    private abstract static class Task {
        abstract void run() throws IOException, InterruptedException;

        abstract void cancel();

        void cancelled() {
            apply("Cancelled.", "Cancelled");
        }

        void failed(String why) {
            searchError = why;
            apply("✗ " + why, "Failed: " + why);
        }
    }

    private static final int MAX_LINES = 300;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String HISTORY_FILE = "history.json";

    private static Context app;
    /** What is running, or null. */
    private static Task current;
    /** The job `current` is downloading, or null while it is a search. */
    private static Job runningJob;
    /** A search waiting for the running download to finish. */
    private static Runnable waiting;
    private static final List<String> log = new ArrayList<String>();
    private static String status = "";
    /** What the last search looked for (Music.SONG, Music.ALBUM or BOOK), and why it failed, if it did. */
    private static String searchMode;
    private static String searchError;
    private static final List<Listener> listeners = new ArrayList<Listener>();
    private static Books.Page results;
    private static Music.Found musicFound;
    /** Whether the log is showing in place of the music results. */
    private static boolean logShown = true;
    /** This session's downloads, in the order they were asked for. */
    private static final Map<String, Job> jobs = new LinkedHashMap<String, Job>();
    private static History history = new History();

    private Download() {}

    /** Reads what was saved before; every screen calls it first. */
    static void init(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        history = History.parse(Disk.read(new File(app.getFilesDir(), HISTORY_FILE), 4 * 1024 * 1024));
    }

    static boolean busy() {
        return current != null;
    }

    /** Whether a search is running, or waiting to. */
    static boolean searching() {
        return waiting != null || current != null && runningJob == null;
    }

    static String status() {
        return status;
    }

    /** What the last search looked for: Music.SONG, Music.ALBUM or BOOK; null before any. */
    static String searchMode() {
        return searchMode;
    }

    /** Why the last search failed, or null. */
    static String searchError() {
        return searchError;
    }

    /** The search's log. */
    static List<String> log() {
        return log;
    }

    /** The last page of book results, or null before any search. */
    static Books.Page results() {
        return results;
    }

    /** The last music search, or null before any. */
    static Music.Found found() {
        return musicFound;
    }

    /** Whether the music results, rather than the log, are to be shown. */
    static boolean resultsShown() {
        return musicFound != null && !musicFound.hits.isEmpty() && !logShown;
    }

    /** Back to the music results from the log, when there are any. */
    static void showResults(boolean show) {
        if (searching()) return;
        logShown = !show;
        changed();
    }

    static void listen(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static void unlisten(Listener l) {
        listeners.remove(l);
    }

    static void clearLog() {
        if (searching()) return;
        log.clear();
        status = "";
        searchError = null;
        changed();
    }

    static void clearResults() {
        if (searching()) return;
        results = null;
        status = "";
        searchError = null;
        changed();
    }

    static void clearFound() {
        if (searching()) return;
        musicFound = null;
        logShown = true;
        status = "";
        searchError = null;
        changed();
    }

    static String key(Books.Book book) {
        return "book:" + book.id + "/" + book.hash;
    }

    // ---- Downloads.

    /** This session's download of `key`, or null. */
    static Job job(String key) {
        return jobs.get(key);
    }

    /** Where `key` stands: this session's download of it, else whether it was saved before. */
    static int state(String key) {
        Job job = jobs.get(key);
        if (job != null) return job.state;
        return saved(key) != null ? SAVED : IDLE;
    }

    /** The earlier download of `key` whose files are still there, or null. */
    static History.Entry saved(String key) {
        History.Entry entry = history.find(key);
        if (entry == null || entry.files.isEmpty() || !new File(entry.files.get(0)).exists()) return null;
        return entry;
    }

    /** Downloads still to finish: the running one first, then the queue. */
    static List<Job> active() {
        List<Job> out = new ArrayList<Job>();
        if (runningJob != null) out.add(runningJob);
        for (Job job : jobs.values()) if (job.state == QUEUED) out.add(job);
        return out;
    }

    /** This session's downloads that failed, newest first. */
    static List<Job> failed() {
        List<Job> out = new ArrayList<Job>();
        for (Job job : jobs.values()) if (job.state == FAILED) out.add(0, job);
        return out;
    }

    /** Everything saved, this session's and before, newest first. */
    static List<History.Entry> history() {
        return history.entries();
    }

    /** Queues a music result, or a failed one again; it starts at once when nothing else runs. */
    static Job get(Login login, Music.Hit hit) {
        Job job = jobs.get(hit.link);
        return queue(job != null ? job : new Job(hit), login, "» Download " + hit.link);
    }

    static Job get(Login login, Books.Book book) {
        Job job = jobs.get(key(book));
        return queue(job != null ? job : new Job(book), login, "» Book: " + book.title);
    }

    private static Job queue(Job job, Login login, String first) {
        if (job.state == QUEUED || job.state == RUNNING) return job;
        jobs.remove(job.key);
        jobs.put(job.key, job);
        job.state = QUEUED;
        job.phase = "";
        job.error = null;
        job.note = "";
        job.done = 0;
        job.total = 0;
        job.files.clear();
        job.arrived.clear();
        job.login = login;
        if (!job.log.isEmpty()) job.log.add("");
        job.log.add(first);
        next();
        changed();
        return job;
    }

    /** Stops a download, or takes it out of the queue; either way it is as if never asked for. */
    static void cancel(Job job) {
        if (job.state == QUEUED || job.state == FAILED) {
            jobs.remove(job.key);
            changed();
        } else if (job.state == RUNNING && job == runningJob && current != null) {
            current.cancel();
            job.phase = "Cancelling…";
            changed();
        }
    }

    /** Empties the queue and stops the download that is running. */
    static void cancelAll() {
        for (Job job : new ArrayList<Job>(jobs.values())) if (job.state == QUEUED) jobs.remove(job.key);
        if (runningJob != null) cancel(runningJob);
        changed();
    }

    /** Stops the search, or the search waiting its turn. */
    static void cancelSearch() {
        if (waiting != null) {
            waiting = null;
            line("Cancelled.");
            setStatus("Cancelled");
        } else if (current != null && runningJob == null) {
            current.cancel();
            setStatus("Cancelling…");
        }
    }

    /** Starts the waiting search, else the next download in the queue, when nothing runs. */
    private static void next() {
        if (current != null) return;
        if (waiting != null) {
            Runnable search = waiting;
            waiting = null;
            search.run();
            return;
        }
        for (Job job : jobs.values()) {
            if (job.state == QUEUED) {
                startJob(job);
                return;
            }
        }
    }

    private static void startJob(final Job job) {
        job.state = RUNNING;
        if (job.music()) {
            job.phase = "Downloading from " + job.hit.user;
            final Music music = new Music(job.login);
            start(job, "music-download", new Task() {
                @Override
                void run() throws IOException, InterruptedException {
                    runMusic(job, music);
                }

                @Override
                void cancel() {
                    music.cancel();
                }

                @Override
                void cancelled() {
                    jobs.remove(job.key);
                }

                @Override
                void failed(String why) {
                    fail(job, why);
                }
            });
        } else {
            job.phase = "Downloading";
            final Books books = books(job.login);
            start(job, "book-download", new Task() {
                @Override
                void run() throws IOException, InterruptedException {
                    runBook(job, books);
                }

                @Override
                void cancel() {
                    books.cancel();
                }

                @Override
                void cancelled() {
                    jobs.remove(job.key);
                }

                @Override
                void failed(String why) {
                    fail(job, why);
                }
            });
        }
    }

    private static void fail(Job job, String why) {
        job.state = FAILED;
        job.error = why;
        job.log.add("✗ " + why);
    }

    private static void start(Job job, String name, final Task task) {
        current = task;
        runningJob = job;
        changed();
        new Thread(() -> {
            work(task);
        }, name).start();
    }

    private static void work(final Task task) {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "source:download");
        wake.acquire(45 * 60 * 1000L);
        Runnable end = null;
        try {
            task.run();
        } catch (InterruptedException e) {
            end = () -> task.cancelled();
        } catch (IOException | RuntimeException e) {
            final String why = e.getMessage() == null ? e.toString() : e.getMessage();
            end = () -> task.failed(why);
        } finally {
            if (wake.isHeld()) wake.release();
            final Runnable ended = end;
            MAIN.post(() -> {
                if (ended != null) ended.run();
                current = null;
                runningJob = null;
                next();
                changed();
            });
        }
    }

    // ---- Music.

    /**
     * Searches for a song or an album; the results replace the log when
     * there are any. While a download runs, the search waits for it.
     */
    static void findMusic(Login login, final String mode, final String query) {
        if (searching()) return;
        final Music music = new Music(login);
        log.clear();
        logShown = true;
        searchMode = mode;
        searchError = null;
        line("» Search for " + (Music.ALBUM.equals(mode) ? "album: " : "song: ") + query);
        search(new Task() {
            @Override
            void run() throws IOException, InterruptedException {
                final Music.Found result = music.search(mode, query, (text, error) -> Download.line(text));
                final int count = result.hits.size();
                final String said = count == 0 ? "Nothing found" : count + (count == 1 ? " result" : " results");
                if (result.error != null) line("✗ " + result.error);
                else line("✓ " + said);
                MAIN.post(() -> {
                    musicFound = result;
                    logShown = count == 0;
                    status = said;
                });
            }

            @Override
            void cancel() {
                music.cancel();
            }
        }, "music-search", "Searching…");
    }

    private static void search(final Task task, final String name, final String searching) {
        Runnable go = () -> {
            current = task;
            runningJob = null;
            setStatus(searching);
            new Thread(() -> {
                work(task);
            }, name).start();
        };
        if (current == null) {
            go.run();
        } else {
            waiting = go;
            setStatus("Waiting for a download…");
        }
    }

    /** Downloads a music result into the Music folder on the SD card, its log in the job. */
    private static void runMusic(Job job, Music music) throws IOException, InterruptedException {
        String id = null;
        try {
            Music.Result result = music.download(job.hit, (text, error) -> say(job, text));
            id = result.job;
            if (id != null) {
                long total = result.bytes > 0 ? result.bytes : job.hit.bytes();
                progress(job, "Saving to the SD card", 0, total);
                Saved saved = save(job, music, id, total);
                try {
                    music.delete(id);
                } catch (IOException e) {
                    // Jobs left behind are removed by the command center after a day.
                }
                id = null;
                String where = saved.folder == null ? MUSIC_FOLDER : MUSIC_FOLDER + "/" + saved.folder;
                String done = "Saved " + saved.count + (saved.count == 1 ? " file" : " files") + " to " + where;
                if (saved.kept > 0) done += ", " + saved.kept + " already there";
                say(job, "✓ " + done);
                if (result.error != null) say(job, "✗ " + result.error);
                finish(job, result.error == null ? done : done + ", not all of it", total);
                return;
            }
            throw new IOException(result.error != null ? result.error : "Nothing was downloaded.");
        } finally {
            if (id != null) {
                try {
                    // delete() still works on a cancelled client.
                    music.delete(id);
                } catch (Exception ignored) {
                    // Removed by the command center after a day anyway.
                }
            }
        }
    }

    /** Where music is saved: a folder of its own on the SD card. */
    static final String MUSIC_FOLDER = "Music";

    private static final class Saved {
        int count;
        int kept;
        String folder;
    }

    /** Fetches the job's files into the Music folder on the SD card. */
    private static Saved save(final Job job, final Music music, String id, final long total) throws IOException, InterruptedException {
        File card = Files.card(app);
        if (card == null) throw new IOException("No SD card in the phone.");
        final File root = new File(card, MUSIC_FOLDER);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Can't open the Music folder.");
        final Saved saved = new Saved();
        final List<String> scan = new ArrayList<String>();
        final long[] done = {0};
        music.fetch(id, (path, size, body) -> {
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
                arrived(job, name, target);
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
                        progress(job, "Saving to the SD card", done[0], total);
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
            say(job, "  " + (dir.equals(root) ? "" : dir.getName() + "/") + target.getName());
            arrived(job, name, target);
            progress(job, "Saving to the SD card", done[0], total);
        });
        if (!scan.isEmpty()) {
            MediaScannerConnection.scanFile(app, scan.toArray(new String[0]), null, null);
        }
        return saved;
    }

    /** One of a job's files is on the phone: `name` as it was shared, `file` where it is. */
    private static void arrived(final Job job, final String name, final File file) {
        final String path = file.getAbsolutePath();
        MAIN.post(() -> {
            job.arrived.add(name);
            job.files.add(path);
            changed();
        });
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
    private static Books books(Login login) {
        final SharedPreferences prefs = app.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
        Books.Session session = Books.Session.parse(prefs.getString("session", ""));
        return new Books(login, session, kept -> prefs.edit().putString("session", kept.toJson()).apply());
    }

    /**
     * Searches for `query`, in one `format` ("epub") or, when null, any. A
     * first page with nothing in that format searches every format instead.
     */
    static void search(Login login, final String query, final int page, final String format) {
        if (searching()) return;
        final Books books = books(login);
        searchMode = BOOK;
        searchError = null;
        search(new Task() {
            @Override
            void run() throws IOException, InterruptedException {
                Books.Page found = books.search(query, page, format);
                String note = "";
                if (found.books.isEmpty() && format != null && page == 1) {
                    post(null, "No " + format.toUpperCase(Locale.US) + "s; searching every format…");
                    found = books.search(query, 1, null);
                    if (!found.books.isEmpty()) note = "No " + format.toUpperCase(Locale.US) + "s, so every format. ";
                }
                final Books.Page shown = found;
                final String said = found.books.isEmpty()
                    ? "Nothing found"
                    : note + "Page " + found.page + " of " + found.pages;
                MAIN.post(() -> {
                    results = shown;
                    status = said;
                });
            }

            @Override
            void cancel() {
                books.cancel();
            }
        }, "book-search", page > 1 ? "Loading page " + page + "…" : "Searching…");
    }

    private static void runBook(Job job, Books books) throws IOException, InterruptedException {
        File card = Files.card(app);
        if (card == null) throw new IOException("No SD card in the phone.");
        File folder = new File(card, BOOKS_FOLDER);
        Books.Saved saved = books.download(job.book, folder, (done, total) -> progress(job, "Downloading", done, total));
        if (!saved.kept) {
            MediaScannerConnection.scanFile(app, new String[] {saved.file.getAbsolutePath()}, null, null);
        }
        String where = BOOKS_FOLDER + "/" + saved.file.getName();
        say(job, "✓ " + (saved.kept ? "Already there: " : "Saved ") + where);
        arrived(job, saved.file.getName(), saved.file);
        finish(job, saved.kept ? "Already in " + BOOKS_FOLDER : "Saved to " + BOOKS_FOLDER, saved.bytes);
    }

    /** A job saved: kept in the history, so it shows as saved from now on. */
    private static void finish(final Job job, final String note, final long bytes) {
        MAIN.post(() -> {
            job.state = SAVED;
            job.note = note;
            if (job.files.isEmpty()) return;
            history.add(new History.Entry(job.key, job.kind, job.title, job.folders, job.files,
                bytes > 0 ? bytes : job.total, System.currentTimeMillis()));
            final String json = history.toJson();
            final File file = new File(app.getFilesDir(), HISTORY_FILE);
            new Thread(() -> {
                synchronized (Download.class) {
                    try {
                        Disk.write(file, json, false);
                    } catch (IOException e) {
                        // Only the record of what was saved is lost; the files are there.
                    }
                }
            }, "history").start();
        });
    }

    static String human(long bytes) {
        return Music.human(bytes);
    }

    // Everything below hops to the main thread, which owns all of the state.

    private static void progress(final Job job, final String phase, final long done, final long total) {
        MAIN.post(() -> {
            if (job.state != RUNNING) return;
            job.phase = phase;
            job.done = done;
            job.total = total;
            changed();
        });
    }

    /** A line of a job's log. */
    private static void say(final Job job, final String text) {
        MAIN.post(() -> {
            for (String one : text.split("\n")) job.log.add(one);
            while (job.log.size() > MAX_LINES) job.log.remove(0);
            changed();
        });
    }

    /** A line of the search's log. */
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
            Collections.addAll(log, text.split("\n"));
            while (log.size() > MAX_LINES) log.remove(0);
        }
        if (newStatus != null) status = newStatus;
        changed();
    }

    private static void changed() {
        for (Listener l : new ArrayList<Listener>(listeners)) l.changed();
    }
}
