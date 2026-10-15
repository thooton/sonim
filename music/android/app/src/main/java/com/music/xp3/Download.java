package com.music.xp3;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import com.music.xp3.net.Login;
import com.music.xp3.net.Music;
import com.music.xp3.net.Names;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one download that can run at a time, kept outside the activity so it
 * carries on when the phone is shut or the screen is rebuilt. The activity
 * attaches a Listener to watch it; everything it is told arrives on the main
 * thread.
 *
 * A download is three steps, as `delta music` does them: the command center
 * runs it and streams its log, then the app fetches the job's files as a tar
 * straight into the shared Music folder, then it removes the job.
 */
final class Download {

    interface Listener {
        void changed();
    }

    private static final int MAX_LINES = 300;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Download current;
    private static final List<String> log = new ArrayList<String>();
    private static String status = "";
    private static Listener listener;

    private final Music music;
    private final String mode;
    private final String query;
    private final Context app;
    private volatile boolean running = true;

    private Download(Context app, Login login, String mode, String query) {
        this.app = app;
        this.music = new Music(login);
        this.mode = mode;
        this.query = query;
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

    static void listen(Listener l) {
        listener = l;
    }

    static void clearLog() {
        if (running()) return;
        log.clear();
        status = "";
        changed();
    }

    static void start(Context context, Login login, String mode, String query) {
        if (running()) return;
        final Download download = new Download(context.getApplicationContext(), login, mode, query);
        current = download;
        log.clear();
        line("» " + (Music.ALBUM.equals(mode) ? "Album: " : "Song: ") + query);
        setStatus("Searching… (Back cancels)");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                download.work();
            }
        }, "music-download");
        thread.start();
    }

    /** Stops the running download; the command center drops the job too. */
    static void cancel() {
        Download d = current;
        if (d == null || !d.running) return;
        d.music.cancel();
        setStatus("Cancelling…");
    }

    private void work() {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "music:download");
        wake.acquire(45 * 60 * 1000L);
        String job = null;
        try {
            Music.Result result = music.run(mode, query, new Music.Listener() {
                @Override
                public void line(String text, boolean error) {
                    Download.line(text);
                }
            });
            job = result.job;
            if (job != null) {
                String size = result.bytes > 0 ? " (" + human(result.bytes) + ")" : "";
                post(null, "Saving " + result.paths.size() + " file(s)" + size + "…");
                Saved saved = save(job, result.bytes);
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
        } catch (InterruptedException e) {
            post("Cancelled.", "Cancelled");
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            post("✗ " + why, "Failed: " + why);
        } finally {
            if (job != null) {
                final String left = job;
                try {
                    // delete() still works on a cancelled client.
                    music.delete(left);
                } catch (Exception ignored) {
                    // Removed by the command center after a day anyway.
                }
            }
            running = false;
            if (wake.isHeld()) wake.release();
            post(null, null);
        }
    }

    private static final class Saved {
        int count;
        int kept;
        String folder;
    }

    /** Fetches the job's files into the shared Music folder. */
    private Saved save(String job, final long total) throws IOException, InterruptedException {
        final File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Can't open the Music folder.");
        final Saved saved = new Saved();
        final List<String> scan = new ArrayList<String>();
        final long[] done = {0};
        music.fetch(job, new Music.Sink() {
            @Override
            public void file(String path, long size, InputStream body) throws IOException, InterruptedException {
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
            }
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
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                apply(text, newStatus);
            }
        });
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
