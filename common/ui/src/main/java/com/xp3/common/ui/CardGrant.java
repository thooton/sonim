package com.xp3.common.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import java.io.File;

/**
 * Android's grant to an app for the whole SD card. Android 8.1 refuses plain
 * file writes on a removable card outside an app's own folder ("Permission
 * denied"), and can refuse plain reads of files other apps put there, so an
 * app asks once with `StorageVolume.createAccessIntent`, keeps the grant
 * (prefs `card`: `tree`, one card's), and goes through `DocumentsContract`.
 * A file's document id is the card's id, a colon, and its path on the card:
 * "0403-0201:Music/Artist/Song.mp3".
 */
public final class CardGrant {

    private static final String PREFS = "card";
    private static final String TREE = "tree";

    private CardGrant() {}

    /** The tree the app was let use on `card`; null if it wasn't. */
    public static Uri tree(Context context, File card) {
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

    /** Whether the app may use the card that is in, or there is no card to ask about. */
    public static boolean ready(Context context) {
        File card = Card.root(context);
        return card == null || tree(context, card) != null;
    }

    /** Android's "Allow <app> to access SD card?"; null when it can't be asked. */
    public static Intent ask(Context context) {
        File card = Card.root(context);
        if (card == null) return null;
        StorageVolume volume = context.getSystemService(StorageManager.class).getStorageVolume(card);
        // No folder name: the whole card.
        return volume == null ? null : volume.createAccessIntent(null);
    }

    /** Keeps what the user allowed, from the answer to ask(); false when it was refused. */
    public static boolean answered(Context context, int result, Intent data) {
        Uri tree = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (tree == null) return false;
        context.getContentResolver().takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TREE, tree.toString()).apply();
        return true;
    }

    /** Whether `file` is on `card`, or is the card. */
    public static boolean on(File card, File file) {
        String root = card.getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.equals(root) || path.startsWith(root + "/");
    }

    /** The document for a file or folder on the card. */
    public static Uri document(Uri tree, File card, File file) {
        String root = card.getAbsolutePath();
        String path = file.getAbsolutePath();
        String rel = path.equals(root) ? "" : path.substring(root.length() + 1);
        return DocumentsContract.buildDocumentUriUsingTree(tree, card.getName() + ":" + rel);
    }

    /** The file on the card a document is. */
    public static File file(File card, Uri doc) {
        String id = DocumentsContract.getDocumentId(doc);
        return new File(card, id.substring(id.indexOf(':') + 1));
    }
}
