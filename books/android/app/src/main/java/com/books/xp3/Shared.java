package com.books.xp3;

import android.content.Context;

import com.books.xp3.shelf.Shelf;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** What the library and the reader share: the shelf, and a thread to save it on. */
final class Shared {

    private static Shelf shelf;
    private static ExecutorService saves;

    private Shared() {}

    static synchronized Shelf shelf(Context context) {
        if (shelf == null) shelf = new Shelf(context.getApplicationContext().getFilesDir());
        return shelf;
    }

    /** Writes the shelf down off the main thread, in the order asked. */
    static synchronized void save(final Shelf target) {
        if (saves == null) saves = single("books-save");
        saves.execute(new Runnable() {
            @Override
            public void run() {
                target.save();
            }
        });
    }

    static ExecutorService single(final String name) {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        });
    }
}
