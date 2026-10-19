package com.print.xp3.raster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * PWG Raster (PWG 5102.4), what IPP Everywhere printers all take: "RaS2",
 * then for each page a 1796-byte header and its lines, run-length coded.
 */
public final class PwgRaster extends Format {

    static final int HEADER = 1796;
    private static final int SGRAY = 18;
    private static final int SRGB = 19;

    private LineCoder coder;

    @Override
    public void start(java.io.OutputStream out, Frame frame) throws IOException {
        super.start(out, frame);
        out.write(ascii("RaS2"));
        coder = new LineCoder(frame.bytesPerLine(), frame.channels());
    }

    @Override
    public void startPage(int index, Back back) throws IOException {
        out.write(header(frame, back));
    }

    static byte[] header(Frame frame, Back back) {
        ByteBuffer h = ByteBuffer.allocate(HEADER);
        put(h, 0, "PwgRaster");
        put(h, 128, ""); // MediaType
        put(h, 192, ""); // OutputType
        h.putInt(272, frame.duplex == Frame.ONE_SIDED ? 0 : 1);
        h.putInt(276, frame.dpi);
        h.putInt(280, frame.dpi);
        h.putInt(340, frame.copies);
        h.putInt(352, (int) Math.round(frame.widthPoints));
        h.putInt(356, (int) Math.round(frame.heightPoints));
        h.putInt(368, frame.duplex == Frame.SHORT_EDGE ? 1 : 0);
        h.putInt(372, frame.width);
        h.putInt(376, frame.height);
        h.putInt(384, 8);
        h.putInt(388, 8 * frame.channels());
        h.putInt(392, frame.bytesPerLine());
        h.putInt(396, 0); // chunky
        h.putInt(400, frame.color ? SRGB : SGRAY);
        h.putInt(420, frame.channels());
        // cupsInteger: TotalPageCount, CrossFeedTransform, FeedTransform,
        // then the image box, which is the whole page.
        h.putInt(452, frame.pages);
        h.putInt(456, back.crossFeed);
        h.putInt(460, back.feed);
        h.putInt(464, 0);
        h.putInt(468, 0);
        h.putInt(472, frame.width);
        h.putInt(476, frame.height);
        put(h, 1668, ""); // RenderingIntent
        put(h, 1732, frame.media);
        return h.array();
    }

    private static void put(ByteBuffer buffer, int at, String text) {
        byte[] raw = text.getBytes(Charset.forName("US-ASCII"));
        int length = Math.min(raw.length, 63);
        for (int i = 0; i < length; i++) buffer.put(at + i, raw[i]);
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
