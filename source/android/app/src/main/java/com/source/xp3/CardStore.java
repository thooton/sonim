package com.source.xp3;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import com.source.xp3.net.Store;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes onto the SD card through Android's grant for it. Android 8.1
 * refuses plain file writes on a removable card outside an app's own folder
 * ("Permission denied"), so the first download asks for the whole card with
 * `StorageVolume.createAccessIntent`, the grant is kept (`card`: `tree`, one
 * card's), and every folder and file is made through `DocumentsContract`.
 * A file's document id is the card's id, a colon, and its path on the card:
 * "0403-0201:Music/Artist/Song.mp3".
 */
final class CardStore implements Store {

    private static final String PREFS = "card";
    private static final String TREE = "tree";

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
        Uri tree = granted(context, card);
        return tree == null ? null : new CardStore(context, tree, card);
    }

    /** Whether Source may write onto the card that is in, or there is no card to ask about. */
    static boolean ready(Context context) {
        File card = Files.card(context);
        return card == null || granted(context, card) != null;
    }

    private static Uri granted(Context context, File card) {
        String kept = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE, "");
        if (kept.length() == 0) return null;
        Uri tree = Uri.parse(kept);
        // A grant is for one card: another card in the slot has another id.
        if (!DocumentsContract.getTreeDocumentId(tree).equals(card.getName() + ":")) return null;
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (grant.getUri().equals(tree) && grant.isWritePermission()) return tree;
        }
        return null;
    }

    /** Android's "Allow Source to access SD card?"; null when it can't be asked. */
    static Intent ask(Context context) {
        File card = Files.card(context);
        if (card == null) return null;
        StorageVolume volume = context.getSystemService(StorageManager.class).getStorageVolume(card);
        // No folder name: the whole card, since music and books go in folders of their own.
        return volume == null ? null : volume.createAccessIntent(null);
    }

    /** Keeps what the user allowed, from the answer to ask(); false when it was refused. */
    static boolean answered(Context context, int result, Intent data) {
        Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (tree == null) return false;
        context.getContentResolver().takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TREE, tree.toString()).apply();
        return true;
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
        String root = card.getAbsolutePath();
        String path = file.getAbsolutePath();
        String rel = path.equals(root) ? "" : path.substring(root.length() + 1);
        return DocumentsContract.buildDocumentUriUsingTree(tree, card.getName() + ":" + rel);
    }

    private File fileOf(Uri doc) {
        String id = DocumentsContract.getDocumentId(doc);
        return new File(card, id.substring(id.indexOf(':') + 1));
    }
}
