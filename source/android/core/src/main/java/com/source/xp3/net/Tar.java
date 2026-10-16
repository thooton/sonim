package com.source.xp3.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Just enough of a tar reader for the command center's music jobs, which Go's
 * archive/tar writes: regular files, with a PAX header ('x') in front of any
 * name too long or too non-ASCII for the 100-byte field. GNU long names ('L')
 * and the ustar prefix are read too, and every other kind of entry is skipped.
 *
 * `next()` returns each file in turn; read its bytes from `body()` before
 * asking for the next one.
 */
public final class Tar {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** One file in the archive. */
    public static final class Entry {
        public final String name;
        public final long size;

        Entry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private final InputStream in;
    private final byte[] header = new byte[512];
    private long remaining;
    private long padding;
    private final InputStream body = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (count < 0) throw new EOFException("The download stopped part way.");
            remaining -= count;
            return count;
        }
    };

    public Tar(InputStream in) {
        this.in = in;
    }

    /** The next regular file, or null at the end of the archive. */
    public Entry next() throws IOException {
        String longName = null;
        while (true) {
            skip(remaining + padding);
            remaining = 0;
            padding = 0;
            if (!fill(header)) return null;
            if (zero(header)) return null;
            if (!checksumOk(header)) throw new IOException("The download arrived damaged.");
            char type = (char) header[156];
            long size = octal(header, 124, 12);
            if (size < 0) throw new IOException("The download arrived damaged.");
            remaining = size;
            padding = (512 - size % 512) % 512;
            if (type == 'x' || type == 'L') {
                if (size > 1 << 20) throw new IOException("The download arrived damaged.");
                byte[] data = new byte[(int) size];
                if (!fill(data)) throw new EOFException("The download stopped part way.");
                remaining = 0;
                String found = type == 'L' ? cString(data, 0, data.length) : paxPath(data);
                if (found != null) longName = found;
                continue;
            }
            if (type != '0' && type != '\0' && type != '7') {
                longName = null;
                continue;
            }
            String name = longName;
            if (name == null) {
                name = cString(header, 0, 100);
                if (ustar(header)) {
                    String prefix = cString(header, 345, 155);
                    if (prefix.length() > 0) name = prefix + "/" + name;
                }
            }
            return new Entry(name, size);
        }
    }

    /** The current file's bytes; ends at the end of the file. */
    public InputStream body() {
        return body;
    }

    private static String paxPath(byte[] data) {
        // Records are "<length> <key>=<value>\n", the length counting the
        // whole record in bytes.
        int at = 0;
        String path = null;
        while (at < data.length) {
            int space = at;
            while (space < data.length && data[space] != ' ') space++;
            if (space >= data.length) break;
            int length;
            try {
                length = Integer.parseInt(new String(data, at, space - at, UTF8));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || at + length > data.length) break;
            String record = new String(data, space + 1, at + length - space - 2, UTF8);
            int equals = record.indexOf('=');
            if (equals > 0 && record.substring(0, equals).equals("path")) path = record.substring(equals + 1);
            at += length;
        }
        return path;
    }

    private boolean fill(byte[] buffer) throws IOException {
        int done = 0;
        while (done < buffer.length) {
            int count = in.read(buffer, done, buffer.length - done);
            if (count < 0) {
                if (done == 0) return false;
                throw new EOFException("The download stopped part way.");
            }
            done += count;
        }
        return true;
    }

    private void skip(long count) throws IOException {
        byte[] scratch = new byte[8192];
        while (count > 0) {
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, count));
            if (read < 0) throw new EOFException("The download stopped part way.");
            count -= read;
        }
    }

    private static boolean zero(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static boolean ustar(byte[] block) {
        return block[257] == 'u' && block[258] == 's' && block[259] == 't'
            && block[260] == 'a' && block[261] == 'r';
    }

    private static boolean checksumOk(byte[] block) {
        long want = octal(block, 148, 8);
        long sum = 0;
        for (int i = 0; i < 512; i++) sum += (i >= 148 && i < 156) ? ' ' : block[i] & 0xff;
        return want == sum;
    }

    private static long octal(byte[] block, int offset, int length) {
        long value = 0;
        int i = offset;
        int end = offset + length;
        while (i < end && (block[i] == ' ' || block[i] == 0)) i++;
        boolean any = false;
        for (; i < end; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') break;
            if (b < '0' || b > '7') return -1;
            value = value * 8 + (b - '0');
            any = true;
        }
        return any ? value : 0;
    }

    private static String cString(byte[] block, int offset, int length) {
        int end = offset;
        while (end < offset + length && block[end] != 0) end++;
        return new String(block, offset, end - offset, UTF8);
    }
}
