package com.books.xp3;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.books.xp3.book.Mobi;
import com.books.xp3.shelf.Shelf;
import com.xp3.common.ui.Card;
import com.xp3.common.ui.CardGrant;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Books on the SD card, through Android's grant for the card (`CardGrant`).
 * Android 8.1 refuses plain writes on a removable card outside an app's own
 * folder, and can refuse plain reads of files another app (a file manager,
 * Source) put there, while still listing them. So a book that can't be
 * opened where it lies is copied through the grant into the cache and read
 * from there, and deleting a book or saving a MOBI on the card go through
 * `DocumentsContract`. Books on the phone's own storage are plain files.
 *
 * readable() and saveMobi() read and write files: call them off the main
 * thread.
 */
final class CardFiles implements Shelf.Reader {

    /** How many copies of unreadable books the cache keeps. */
    private static final int COPIES = 4;
    private static final Object LOCK = new Object();

    private final Context context;
    private final ContentResolver resolver;
    private final File copies;

    CardFiles(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.copies = new File(this.context.getCacheDir(), "card");
    }

    /** Whether `file` is on the SD card. */
    boolean onCard(File file) {
        File card = Card.root(context);
        return card != null && CardGrant.on(card, file);
    }

    /** Whether the app has been let use the card that is in, or there is none. */
    boolean granted() {
        return CardGrant.ready(context);
    }

    /** Whether opening `file` needs the card's grant, which hasn't been given. */
    boolean needsGrant(File file) {
        return onCard(file) && !canOpen(file) && !granted();
    }

    /**
     * `file` itself if it opens as it is, else a copy of it in the cache:
     * read plainly when only its size is misreported (state()), through the
     * card's grant when it can't be opened at all.
     */
    @Override
    public File readable(File file) throws IOException {
        int state = state(file);
        if (state == OPENS) return file;
        Uri doc = state == STALE ? null : document(file);
        if (state == REFUSED && doc == null) throw new FileNotFoundException("Can’t read it");
        synchronized (LOCK) {
            // Under its own name, so the reader is chosen and titled as for the book.
            File dir = new File(copies, key(file));
            File copy = new File(dir, file.getName());
            if (copy.isFile() && copy.length() == file.length()) {
                copy.setLastModified(System.currentTimeMillis());
                return copy;
            }
            prune();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can’t copy it");
            InputStream in;
            try {
                in = doc == null ? new FileInputStream(file) : resolver.openInputStream(doc);
            } catch (IOException | RuntimeException e) {
                in = null;
            }
            if (in == null) throw new FileNotFoundException("Can’t read it");
            try {
                OutputStream out = new FileOutputStream(copy);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                } finally {
                    out.close();
                }
            } catch (IOException e) {
                copy.delete();
                throw e;
            } finally {
                in.close();
            }
            if (copy.length() != file.length()) {
                copy.delete();
                throw new FileNotFoundException("Can’t read it");
            }
            return copy;
        }
    }

    /** Erases the book's file; false if it is still there. */
    boolean delete(File file) {
        Uri doc = onCard(file) ? document(file) : null;
        if (doc == null) return file.delete() || !file.exists();
        try {
            DocumentsContract.deleteDocument(resolver, doc);
        } catch (IOException | RuntimeException e) {
            // Still there: said below.
        }
        return !file.exists();
    }

    /**
     * Writes `epub` as a MOBI to `target` beside it, replacing an earlier
     * one, through a hidden part file renamed into place. A FileNotFoundException
     * says why the book couldn't be read, or has no message if the MOBI
     * couldn't be written there.
     */
    void saveMobi(File epub, File target, Mobi.Images images) throws IOException {
        File book = readable(epub);
        if (!onCard(target)) {
            try {
                Mobi.convert(book, target, images);
            } catch (FileNotFoundException e) {
                throw new FileNotFoundException();
            }
            return;
        }
        File card = Card.root(context);
        Uri tree = card == null ? null : CardGrant.tree(context, card);
        if (tree == null) throw new FileNotFoundException();
        Uri folder = CardGrant.document(tree, card, target.getParentFile());
        // A plain name, so the card takes it as it is.
        Uri part;
        try {
            part = DocumentsContract.createDocument(resolver, folder, "application/octet-stream",
                ".books-" + Long.toHexString(System.nanoTime()) + ".part");
        } catch (IOException | RuntimeException e) {
            part = null;
        }
        if (part == null) throw new FileNotFoundException();
        try {
            OutputStream raw = resolver.openOutputStream(part);
            if (raw == null) throw new FileNotFoundException();
            OutputStream out = new BufferedOutputStream(raw, 64 * 1024);
            try {
                Mobi.convert(book, images, out);
            } finally {
                out.close();
            }
            try {
                // The card would give a second file of the name a " (1)".
                if (target.exists()) DocumentsContract.deleteDocument(resolver, CardGrant.document(tree, card, target));
                Uri saved = DocumentsContract.renameDocument(resolver, part, target.getName());
                if (saved != null) part = saved;
            } catch (IOException | RuntimeException e) {
                throw new FileNotFoundException();
            }
            if (!target.equals(CardGrant.file(card, part))) throw new FileNotFoundException();
        } catch (IOException | RuntimeException | Error e) {
            // Out of memory included: never leave half a book behind.
            try {
                DocumentsContract.deleteDocument(resolver, part);
            } catch (IOException | RuntimeException ignored) {
                // Left behind: a hidden part file the library doesn't list.
            }
            throw e;
        }
    }

    /** The file as a document under the card's grant; null without one. */
    private Uri document(File file) {
        File card = Card.root(context);
        if (card == null || !CardGrant.on(card, file)) return null;
        Uri tree = CardGrant.tree(context, card);
        return tree == null ? null : CardGrant.document(tree, card, file);
    }

    private static boolean canOpen(File file) {
        return state(file) != REFUSED;
    }

    /** Opens and reads as it is. */
    private static final int OPENS = 0;
    /** Opens, but seeking to its end finds a stale size (see state()). */
    private static final int STALE = 1;
    /** Can't be opened here. */
    private static final int REFUSED = 2;

    /**
     * Whether `file` can be read where it lies. A file another app wrote
     * through Android's documents provider (Source's downloads, a file
     * manager's copies) can keep, in this app's view of the card, the size
     * it had when it was created empty: stat and reads give the whole file,
     * but seeking to its end stops at 0, so java.util.zip, which finds a
     * zip's end that way, calls the book "empty" and it is "Not an EPUB".
     */
    private static int state(File file) {
        FileInputStream in;
        try {
            in = new FileInputStream(file);
        } catch (IOException e) {
            return REFUSED;
        }
        try {
            FileDescriptor fd = in.getFD();
            long end = Os.lseek(fd, 0, OsConstants.SEEK_END);
            return end == Os.fstat(fd).st_size ? OPENS : STALE;
        } catch (ErrnoException | IOException e) {
            return OPENS;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Only read.
            }
        }
    }

    /** Keeps the newest few copies, so the cache doesn't fill with books. */
    private void prune() {
        File[] dirs = copies.listFiles();
        if (dirs == null || dirs.length < COPIES) return;
        Arrays.sort(dirs, (a, b) -> Long.compare(newest(b), newest(a)));
        for (int i = COPIES - 1; i < dirs.length; i++) {
            File[] inside = dirs[i].listFiles();
            if (inside != null) for (File f : inside) f.delete();
            dirs[i].delete();
        }
    }

    private static long newest(File dir) {
        long when = dir.lastModified();
        File[] inside = dir.listFiles();
        if (inside != null) for (File f : inside) when = Math.max(when, f.lastModified());
        return when;
    }

    /** One copy per book and version of it. */
    private static String key(File file) {
        long hash = 1125899906842597L;
        String id = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        for (int i = 0; i < id.length(); i++) hash = 31 * hash + id.charAt(i);
        return Long.toHexString(hash);
    }
}
