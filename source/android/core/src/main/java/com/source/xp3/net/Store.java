package com.source.xp3.net;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * How downloads are written: folders made, files created, renamed and
 * removed. On the phone they go through Android's grant for the SD card,
 * since Android 8.1 refuses plain writes there; `PLAIN` is the file system
 * as it is, for the tests. Reading is plain files either way.
 */
public interface Store {

    /** Makes `dir` and any folder above it that is missing. */
    void mkdirs(File dir) throws IOException;

    /** A new, empty `file`, to write; its folder is there. */
    OutputStream create(File file) throws IOException;

    /** Renames `from` to `to`, in the same folder; where it landed, which may differ if the card changed the name. */
    File rename(File from, File to) throws IOException;

    /** Removes `file`, if it can. */
    void delete(File file);

    Store PLAIN = new Store() {
        @Override
        public void mkdirs(File dir) throws IOException {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't make the folder " + dir.getName() + ".");
        }

        @Override
        public OutputStream create(File file) throws IOException {
            return new FileOutputStream(file);
        }

        @Override
        public File rename(File from, File to) throws IOException {
            if (!from.renameTo(to)) throw new IOException("Can't save " + to.getName() + ".");
            return to;
        }

        @Override
        public void delete(File file) {
            file.delete();
        }
    };
}
