package com.books.xp3.shelf;

import com.xp3.common.Check;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public final class ShelfTests {

    public static void run(final Check check) {
        check.run("shelf", () -> shelf(check));
    }

    private static void shelf(Check check) throws IOException {
        File root = new File(System.getProperty("java.io.tmpdir"), "books-shelf-" + System.nanoTime());
        File storage = new File(root, "storage");
        File books = new File(storage, "Books");
        File byAuthor = new File(books, "Some Author");
        File elsewhere = new File(storage, "Download");
        File state = new File(root, "state");
        byAuthor.mkdirs();
        elsewhere.mkdirs();
        state.mkdirs();
        try {
            write(new File(books, "Zed_Tale.txt"), "Just text.\n");
            write(new File(books, "alpha.txt"), "Title: Alpha\tTabs\nAuthor: A\n*** START OF IT ***\nText.\n");
            write(new File(books, "notes.md"), "not a book");
            write(new File(elsewhere, "not-in-books.epub"), "outside the Books folder");
            write(new File(storage, "loose.txt"), "outside the Books folder");
            write(new File(books, ".hidden.txt"), "dot file");
            write(new File(byAuthor, "nested.txt"), "Title: Nested\n*** START OF IT ***\nText.\n");

            List<File> roots = Collections.singletonList(books);
            Shelf shelf = new Shelf(state);
            List<Shelf.Entry> found = shelf.scan(roots);
            check.same("only the Books folder, subfolders included", 3, found.size());
            check.same("sorted by title, white space tidied", "Alpha Tabs", found.get(0).displayTitle());
            check.same("subfolder book", "Nested", found.get(1).displayTitle());
            check.same("file name stands in", "Zed Tale", found.get(2).displayTitle());
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
            check.same("missing Books folder finds nothing", 0,
                new Shelf(state).scan(Collections.singletonList(new File(storage, "NoSuchFolder"))).size());

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

            // A deleted book leaves the shelf and its index.
            String alpha = new File(books, "alpha.txt").getAbsolutePath();
            again.progress(alpha, 5, 100, 4000);
            again.remove(alpha);
            check.ok("removed", again.get(alpha) == null);
            check.ok("saved without it", again.save());
            check.ok("not read back", new Shelf(state).get(alpha) == null);

            // A book the phone won't let the app read is not taken as untitled:
            // once it can be read (through the SD card's grant), it is titled.
            File refusing = new File(root, "refusing");
            refusing.mkdirs();
            String nestedPath = new File(byAuthor, "nested.txt").getAbsolutePath();
            Shelf.Reader refused = file -> {
                if (file.getAbsolutePath().equals(nestedPath)) throw new java.io.FileNotFoundException("Can't read");
                return file;
            };
            Shelf locked = new Shelf(refusing);
            locked.scan(roots, refused);
            check.ok("unreadable book listed untitled", locked.get(nestedPath).title == null);
            check.same("by its file name", "nested", locked.get(nestedPath).displayTitle());
            locked.scan(roots);
            check.same("titled once it can be read", "Nested", locked.get(nestedPath).title);

            // Shelves from before 2 kept such books with an empty title: they are read again.
            write(new File(refusing, "shelf.tsv"), "books-shelf 1\n" + Shelf.escape(nestedPath)
                + "\t" + new File(nestedPath).length() + "\t" + new File(nestedPath).lastModified()
                + "\t\t\t-1\t0\t0\n");
            Shelf older = new Shelf(refusing);
            check.ok("old empty title dropped", older.get(nestedPath).title == null);
            older.scan(roots);
            check.same("old untitled book titled", "Nested", older.get(nestedPath).title);

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
