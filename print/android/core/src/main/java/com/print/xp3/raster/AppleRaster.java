package com.print.xp3.raster;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Apple Raster ("URF"), what AirPrint printers take: "UNIRAST", a page
 * count, then for each page a 32-byte header and its lines, coded as PWG
 * Raster's are.
 */
public final class AppleRaster extends Format {

    private static final int SRGB = 1;
    private static final int SGRAY = 4;

    private LineCoder coder;

    @Override
    public void start(OutputStream out, Frame frame) throws IOException {
        super.start(out, frame);
        out.write(ascii("UNIRAST"));
        out.write(0);
        int32(out, frame.pages);
        coder = new LineCoder(frame.bytesPerLine(), frame.channels());
    }

    @Override
    public void startPage(int index, Back back) throws IOException {
        byte[] h = new byte[32];
        h[0] = (byte) (8 * frame.channels());
        h[1] = (byte) (frame.color ? SRGB : SGRAY);
        h[2] = (byte) (frame.duplex == Frame.ONE_SIDED ? 1 : frame.duplex == Frame.SHORT_EDGE ? 2 : 3);
        h[3] = 4; // normal quality
        put(h, 12, frame.width);
        put(h, 16, frame.height);
        put(h, 20, frame.dpi);
        out.write(h);
    }

    private static void put(byte[] to, int at, int value) {
        to[at] = (byte) (value >>> 24);
        to[at + 1] = (byte) (value >>> 16);
        to[at + 2] = (byte) (value >>> 8);
        to[at + 3] = (byte) value;
    }

    @Override
    public void rows(byte[] data, int offset, int count) throws IOException {
        int line = frame.bytesPerLine();
        for (int i = 0; i < count; i++) coder.row(out, data, offset + i * line);
    }

    @Override
    public void endPage() throws IOException {
        coder.flush(out);
    }
}
