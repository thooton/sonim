package com.books.xp3.shelf;

import com.books.xp3.Check;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ShelfTests {

    public static void run(final Check check) {
        check.run("shelf", new Check.Body() {
            @Override
            public void run() throws Exception {
                shelf(check);
            }
        });
    }

    private static void shelf(Check check) throws IOException {
        File root = new File(System.getProperty("java.io.tmpdir"), "books-shelf-" + System.nanoTime());
        File storage = new File(root, "storage");
        File books = new File(storage, "Books");
        File hidden = new File(storage, "Android/data/x");
        File state = new File(root, "state");
        books.mkdirs();
        hidden.mkdirs();
        state.mkdirs();
        try {
            write(new File(books, "Zed_Tale.txt"), "Just text.\n");
            write(new File(books, "alpha.txt"), "Title: Alpha\tTabs\nAuthor: A\n*** START OF IT ***\nText.\n");
            write(new File(books, "notes.md"), "not a book");
            write(new File(hidden, "skipped.txt"), "in Android/");
            write(new File(storage, ".hidden.txt"), "dot file");

            List<File> roots = Collections.singletonList(storage);
            Shelf shelf = new Shelf(state);
            List<Shelf.Entry> found = shelf.scan(roots);
            check.same("found", 2, found.size());
            check.same("sorted by title, white space tidied", "Alpha Tabs", found.get(0).displayTitle());
            check.same("file name stands in", "Zed Tale", found.get(1).displayTitle());
            check.ok("new", found.get(0).isNew());

            String zed = new File(books, "Zed_Tale.txt").getAbsolutePath();
            shelf.progress(zed, 50, 200, 1000);
            check.ok("saved", shelf.save());
            check.same("percent", 25, shelf.get(zed).percent());

            Shelf again = new Shelf(state);
            List<Shelf.Entry> reloaded = again.scan(roots);
            check.same("recent first", zed, reloaded.get(0).path);
            check.same("position kept", Long.valueOf(50), Long.valueOf(reloaded.get(0).position));
            check.same("title kept", "Alpha Tabs", reloaded.get(1).title);

            again.progress(zed, 200, 200, 2000);
            check.ok("finished", again.get(zed).finished());
            again.forget(zed);
            check.ok("forgotten", again.get(zed).isNew());

            // A changed file of a different size starts again from the beginning.
            again.progress(zed, 10, 200, 3000);
            write(new File(books, "Zed_Tale.txt"), "Different and longer text.\n");
            List<Shelf.Entry> changed = again.scan(roots);
            Shelf.Entry entry = null;
            for (Shelf.Entry e : changed) if (e.path.equals(zed)) entry = e;
            check.ok("changed file is new", entry != null && entry.isNew());

            write(new File(state, "shelf.tsv"), "garbage\n");
            check.same("damaged index is empty", 0, new Shelf(state).books().size());
        } finally {
            delete(root);
        }
        check.same("escape round trip", "a\\b\tc\nd", Shelf.unescape(Shelf.escape("a\\b\tc\nd")));
    }

    private static void write(File file, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        out.write(text.getBytes("UTF-8"));
        out.close();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
