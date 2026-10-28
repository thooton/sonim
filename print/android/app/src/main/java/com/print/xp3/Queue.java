package com.print.xp3;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The jobs sent from this phone (screen 16), one at a time in the order
 * sent, by {@link PrintService}. Main thread only: the service's worker
 * posts its news here.
 */
final class Queue {

    private Queue() {}

    static final int WAITING = 0;
    static final int RUNNING = 1;
    static final int DONE = 2;
    static final int FAILED = 3;
    static final int CANCELED = 4;

    /** One job. */
    static final class Entry {
        final int id;
        final File file;
        final String title;
        Printer printer;
        final Options options;
        final int pages;
        int state = WAITING;
        /** The engine's latest word on it; null before it starts. */
        Job.Status status;
        long finished;
        /** Whether the printer has taken it (the Done screen shows from then). */
        boolean accepted;
        Job job;

        Entry(int id, File file, String title, Printer printer, Options options, int pages) {
            this.id = id;
            this.file = file;
            this.title = title;
            this.printer = printer;
            this.options = options;
            this.pages = pages;
        }

        boolean active() {
            return state == WAITING || state == RUNNING;
        }

        /** Pages that come out: the range, times the copies. */
        int sheetsOfPaper() {
            return (options.to - options.from + 1) * Math.max(1, options.copies);
        }
    }

    interface Listener {
        void changed(Entry entry);
    }

    private static final List<Entry> entries = new ArrayList<Entry>();
    private static final List<Listener> listeners = new ArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int nextId = 1;

    static void listen(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    static void unlisten(Listener listener) {
        listeners.remove(listener);
    }

    static void changed(final Entry entry) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> changed(entry));
            return;
        }
        for (Listener listener : new ArrayList<Listener>(listeners)) listener.changed(entry);
    }

    /** Puts a job on the queue and starts the service if it isn't running. */
    static Entry add(Context context, File file, String title, Printer printer, Options options, int pages) {
        Entry entry = new Entry(nextId++, file, title, printer, Store.copy(options), pages);
        entries.add(entry);
        PrintService.wake(context);
        changed(entry);
        return entry;
    }

    /** Sends a failed or canceled job again, to `printer` (or the same one). */
    static Entry again(Context context, Entry old, Printer printer, boolean color) {
        Options options = Store.copy(old.options);
        if (!color) options.color = false;
        // Sent again, the old job's "didn't print" is no longer news.
        PrintService.forget(context, old);
        return add(context, old.file, old.title, printer == null ? old.printer : printer, options, old.pages);
    }

    static Entry get(int id) {
        for (Entry entry : entries) if (entry.id == id) return entry;
        return null;
    }

    /** The next job to run, or null. */
    static Entry next() {
        for (Entry entry : entries) if (entry.state == WAITING) return entry;
        return null;
    }

    static void cancel(Entry entry) {
        if (entry.state == WAITING) {
            entry.state = CANCELED;
            entry.finished = System.currentTimeMillis();
            changed(entry);
        } else if (entry.state == RUNNING && entry.job != null) {
            entry.job.cancel();
        }
    }

    /** Jobs in progress, then those finished today (earlier ones are cleared at midnight). */
    static List<Entry> shown() {
        long midnight = midnight();
        List<Entry> out = new ArrayList<Entry>();
        for (Entry entry : entries) if (entry.active()) out.add(entry);
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (!entry.active() && entry.finished >= midnight) out.add(entry);
        }
        return out;
    }

    static int activeCount() {
        int count = 0;
        for (Entry entry : entries) if (entry.active()) count++;
        return count;
    }

    static long midnight() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return today.getTimeInMillis();
    }

    static Intent open(Context context, Entry entry) {
        return new Intent(context, MainActivity.class).putExtra(MainActivity.EXTRA_JOB, entry.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
