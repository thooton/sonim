package com.tablet.xp3;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.xp3.common.ui.CardGrant;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Puts downloads onto the SD card through Android's grant for it. Android
 * 8.1 refuses plain file writes on a removable card outside an app's own
 * folder ("Permission denied"), so a download is fetched into the cache and
 * copied onto the card through `DocumentsContract`, as Source and Books do.
 */
final class CardSaver {

    private final ContentResolver resolver;
    private final Uri tree;
    private final File card;

    private CardSaver(Context context, Uri tree, File card) {
        this.resolver = context.getContentResolver();
        this.tree = tree;
        this.card = card;
    }

    /** Writing onto `card`, if the user has let Tablet; null if not. */
    static CardSaver open(Context context, File card) {
        Uri tree = CardGrant.tree(context, card);
        return tree == null ? null : new CardSaver(context, tree, card);
    }

    /** Makes `dir` on the card, and the folders above it, if they aren't there. */
    void mkdirs(File dir) throws IOException {
        if (dir.isDirectory()) return;
        File parent = dir.getParentFile();
        if (dir.equals(card) || parent == null) throw new IOException("Can't open the SD card");
        mkdirs(parent);
        Uri made = make(parent, DocumentsContract.Document.MIME_TYPE_DIR, dir);
        if (made == null || !dir.isDirectory()) throw new IOException("Couldn't make the " + dir.getName() + " folder");
    }

    /** Copies `from` to `dest` on the card; nothing is left there if it fails. */
    void copy(File from, File dest) throws IOException {
        String failed = "Couldn't save " + dest.getName();
        // Octet-stream keeps the name as it is.
        Uri doc = make(dest.getParentFile(), "application/octet-stream", dest);
        if (doc == null) throw new IOException(failed);
        boolean done = false;
        try {
            // The card changed the name; nothing is written under another one.
            if (!dest.equals(CardGrant.file(card, doc))) throw new IOException(failed);
            InputStream in = new FileInputStream(from);
            try {
                OutputStream out = resolver.openOutputStream(doc);
                if (out == null) throw new IOException(failed);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = in.read(buffer)) != -1; ) out.write(buffer, 0, count);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            done = true;
        } catch (RuntimeException e) {
            throw new IOException(failed);
        } finally {
            if (!done) remove(doc);
        }
    }

    private Uri make(File parent, String type, File file) {
        try {
            return DocumentsContract.createDocument(resolver, CardGrant.document(tree, card, parent), type, file.getName());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void remove(Uri doc) {
        try {
            DocumentsContract.deleteDocument(resolver, doc);
        } catch (IOException | RuntimeException e) {
            // Left behind: an empty or partial file.
        }
    }
}
