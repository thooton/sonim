package com.source.xp3;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import com.source.xp3.net.Store;
import com.xp3.common.ui.CardGrant;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes onto the SD card through Android's grant for it. Android 8.1
 * refuses plain file writes on a removable card outside an app's own folder
 * ("Permission denied"), so the first download asks for the whole card with
 * `StorageVolume.createAccessIntent`, the grant is kept (`CardGrant`, shared
 * with Books), and every folder and file is made through `DocumentsContract`.
 */
final class CardStore implements Store {

    private final ContentResolver resolver;
    private final Uri tree;
    private final File card;

    private CardStore(Context context, Uri tree, File card) {
        this.resolver = context.getContentResolver();
        this.tree = tree;
        this.card = card;
    }

    /** Writing onto `card`, if the user has let Source; null if not. */
    static CardStore open(Context context, File card) {
        Uri tree = CardGrant.tree(context, card);
        return tree == null ? null : new CardStore(context, tree, card);
    }

    /** Whether Source may write onto the card that is in, or there is no card to ask about. */
    static boolean ready(Context context) {
        return CardGrant.ready(context);
    }

    /** Android's "Allow Source to access SD card?"; null when it can't be asked. */
    static Intent ask(Context context) {
        // The whole card, since music and books go in folders of their own.
        return CardGrant.ask(context);
    }

    /** Keeps what the user allowed, from the answer to ask(); false when it was refused. */
    static boolean answered(Context context, int result, Intent data) {
        return CardGrant.answered(context, result, data);
    }

    @Override
    public void mkdirs(File dir) throws IOException {
        if (dir.isDirectory()) return;
        if (dir.equals(card)) throw new IOException("Can't open the SD card.");
        File parent = dir.getParentFile();
        if (parent == null) throw new IOException("Can't make the folder " + dir.getName() + ".");
        mkdirs(parent);
        Uri made = make(parent, DocumentsContract.Document.MIME_TYPE_DIR, dir);
        if (made == null || !dir.isDirectory()) throw new IOException("Can't make the folder " + dir.getName() + ".");
    }

    @Override
    public OutputStream create(File file) throws IOException {
        // Octet-stream keeps the name as it is.
        Uri doc = make(file.getParentFile(), "application/octet-stream", file);
        if (doc == null) throw new IOException("Can't write " + file.getName() + " on the SD card.");
        if (!file.equals(fileOf(doc))) {
            // The card changed the name; nothing here is written under another one.
            remove(doc);
            throw new IOException("Can't write " + file.getName() + " on the SD card.");
        }
        try {
            OutputStream out = resolver.openOutputStream(doc);
            if (out == null) throw new FileNotFoundException();
            return out;
        } catch (IOException | RuntimeException e) {
            remove(doc);
            throw new IOException("Can't write " + file.getName() + " on the SD card.");
        }
    }

    @Override
    public File rename(File from, File to) throws IOException {
        try {
            Uri moved = DocumentsContract.renameDocument(resolver, uri(from), to.getName());
            // Null when the name was already the one asked for.
            return moved == null ? from : fileOf(moved);
        } catch (IOException | RuntimeException e) {
            throw new IOException("Can't save " + to.getName() + ".");
        }
    }

    @Override
    public void delete(File file) {
        if (file.exists()) remove(uri(file));
    }

    private Uri make(File parent, String type, File file) throws IOException {
        try {
            return DocumentsContract.createDocument(resolver, uri(parent), type, file.getName());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void remove(Uri doc) {
        try {
            DocumentsContract.deleteDocument(resolver, doc);
        } catch (IOException | RuntimeException e) {
            // Left behind: a part file or an empty one, shown nowhere.
        }
    }

    /** The document for a file or folder on the card. */
    private Uri uri(File file) {
        return CardGrant.document(tree, card, file);
    }

    private File fileOf(Uri doc) {
        return CardGrant.file(card, doc);
    }
}
