package com.musica.xp3;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.xp3.common.ui.Card;
import com.xp3.common.ui.CardGrant;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reading songs and lyrics off the card. Android 8.1 can refuse a plain read
 * of a file another app put on the SD card even though listing it works;
 * then the read goes through the app's grant for the card, and with no grant
 * yet it says so, for MainActivity to ask.
 */
final class Files {

    private Files() {}

    /** A read was refused and the app has no grant for the card to try instead. */
    static final class NeedsGrant extends IOException {
        NeedsGrant(File file) {
            super("No access to " + file.getName());
        }
    }

    static ParcelFileDescriptor open(Context context, File file) throws IOException {
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException | SecurityException e) {
            File card = Card.root(context);
            if (card == null || !CardGrant.on(card, file)) throw new IOException(e);
            Uri tree = CardGrant.tree(context, card);
            if (tree == null) throw new NeedsGrant(file);
            try {
                ParcelFileDescriptor fd = context.getContentResolver()
                    .openFileDescriptor(CardGrant.document(tree, card, file), "r");
                if (fd == null) throw new IOException("Can't open " + file.getName());
                return fd;
            } catch (SecurityException | IllegalArgumentException refused) {
                throw new IOException(refused);
            }
        }
    }

    /** A small file's bytes (lyrics), at most `limit` of them. */
    static byte[] read(Context context, File file, int limit) throws IOException {
        try (ParcelFileDescriptor fd = open(context, file);
             InputStream in = new FileInputStream(fd.getFileDescriptor())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, n);
                if (out.size() > limit) throw new IOException(file.getName() + " is too large");
            }
            return out.toByteArray();
        }
    }
}
