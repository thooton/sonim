package com.print.xp3.raster;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes page images as one document in a printer's language. Called as
 * start, then for each page startPage, rows until the page's height is
 * reached, and endPage, then finish.
 */
public abstract class Format {

    protected OutputStream out;
    protected Frame frame;

    public void start(OutputStream out, Frame frame) throws IOException {
        this.out = out;
        this.frame = frame;
    }

    /** `back` is how the page's image was turned, for formats that record it. */
    public abstract void startPage(int index, Back back) throws IOException;

    /** `count` rows of `frame.bytesPerLine()` bytes each, from `offset`. */
    public abstract void rows(byte[] data, int offset, int count) throws IOException;

    public abstract void endPage() throws IOException;

    public void finish() throws IOException {
        out.flush();
    }

    protected static void int32(OutputStream out, long value) throws IOException {
        out.write((int) (value >>> 24) & 0xFF);
        out.write((int) (value >>> 16) & 0xFF);
        out.write((int) (value >>> 8) & 0xFF);
        out.write((int) value & 0xFF);
    }

    protected static byte[] ascii(String text) {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < out.length; i++) out[i] = (byte) text.charAt(i);
        return out;
    }
}
