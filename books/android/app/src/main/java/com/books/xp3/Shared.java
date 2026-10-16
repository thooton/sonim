package com.books.xp3;

import android.content.Context;

import com.books.xp3.shelf.Shelf;
import com.xp3.common.ui.Ui;

import java.util.concurrent.ExecutorService;

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
        if (saves == null) saves = Ui.worker("books-save");
        saves.execute(() -> target.save());
    }
}
