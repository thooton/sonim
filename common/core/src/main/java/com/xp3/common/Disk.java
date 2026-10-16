package com.xp3.common;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Whole-file text reads and writes, for the small files the apps keep. */
public final class Disk {

    private Disk() {}

    /**
     * The file as UTF-8 text, or null when it is missing, empty, larger than
     * `limit` bytes or unreadable.
     */
    public static String read(File file, long limit) {
        if (!file.isFile() || file.length() == 0 || file.length() > limit) return null;
        try {
            InputStream in = new FileInputStream(file);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = in.read(buffer)) >= 0; ) out.write(buffer, 0, count);
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Replaces the file whole or not at all: the text is written and synced to
     * a temporary file beside it, which is then renamed over it, so a crash or
     * a full disk leaves the old file rather than half of the new one. Callers
     * writing one file from several threads synchronize, since they share the
     * temporary name.
     *
     * A `secret` file is made readable by its owner only. The apps' own
     * directories are private already, but a file holding a login should stay
     * unreadable even if a backup or a restore ever loosens the directory.
     */
    public static void write(File file, String text, boolean secret) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not open the app's storage");
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temporary);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (secret) {
            temporary.setReadable(false, false);
            temporary.setReadable(true, true);
            temporary.setWritable(false, false);
            temporary.setWritable(true, true);
        }
        if (!temporary.renameTo(file)) {
            // Some filesystems will not replace an existing file on rename.
            if (!file.delete() || !temporary.renameTo(file)) {
                temporary.delete();
                throw new IOException("Could not save to the app's storage");
            }
        }
    }
}
