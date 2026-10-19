package com.print.xp3.raster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * PCL 5 raster graphics in black and white, which nearly every laser
 * printer understands, whatever else it does. Grey is dithered
 * (Floyd-Steinberg) to dots, and each line packed with compression mode 2.
 *
 * PCL draws from its logical page, which starts a quarter inch (Letter) or
 * 71 dots (A4) in from the paper's left edge: the image's columns left of
 * that are dropped, since the printer could not reach them anyway.
 */
public final class Pcl extends Format {

    private static final byte ESC = 27;

    private int skip;
    private int width;
    private int y;
    private int[] errors;
    private int[] nextErrors;
    private byte[] bits;
    private final ByteArrayOutputStream packed = new ByteArrayOutputStream();

    @Override
    public void start(OutputStream out, Frame frame) throws IOException {
        super.start(out, frame);
        skip = (int) Math.round(frame.dpi * (frame.media.startsWith("iso_") ? 71 / 300.0 : 0.25));
        width = Math.max(1, frame.width - skip);
        errors = new int[width + 2];
        nextErrors = new int[width + 2];
        bits = new byte[(width + 7) / 8];
        command("E");
        command("&l" + frame.copies + "X");
        command("&l" + paper(frame) + "A");
        command("&l0O");
        command("&l" + frame.duplex + "S");
        command("&l0E");
        command("&a0L");
    }

    /** The paper's PCL size code. */
    static int paper(Frame frame) {
        String media = frame.media;
        if (media.startsWith("na_legal")) return 3;
        if (media.startsWith("iso_a4")) return 26;
        if (media.startsWith("iso_a5")) return 25;
        if (media.startsWith("iso_a3")) return 27;
        if (media.startsWith("na_executive")) return 1;
        if (media.startsWith("na_ledger")) return 6;
        if (media.startsWith("na_letter")) return 2;
        return frame.widthPoints < 600 ? 26 : 2;
    }

    private void command(String text) throws IOException {
        out.write(ESC);
        out.write(ascii(text));
    }

    @Override
    public void startPage(int index, Back back) throws IOException {
        y = 0;
        java.util.Arrays.fill(errors, 0);
        command("*t" + frame.dpi + "R");
        command("*p0x0Y");
        command("*r0F");
        command("*r" + width + "S");
        command("*r" + frame.height + "T");
        command("*b2M");
        command("*r1A");
    }

    @Override
    public void rows(byte[] data, int offset, int count) throws IOException {
        int line = frame.bytesPerLine();
        int channels = frame.channels();
        for (int r = 0; r < count; r++) {
            int from = offset + r * line;
            java.util.Arrays.fill(bits, (byte) 0);
            java.util.Arrays.fill(nextErrors, 0);
            boolean ink = false;
            for (int x = 0; x < width; x++) {
                int at = from + (x + skip) * channels;
                int grey = channels == 1 ? data[at] & 0xFF
                    : ((data[at] & 0xFF) * 77 + (data[at + 1] & 0xFF) * 150 + (data[at + 2] & 0xFF) * 29) >> 8;
                int value = grey + errors[x + 1] / 16;
                int error;
                if (value < 128) {
                    bits[x >> 3] |= (byte) (0x80 >>> (x & 7));
                    ink = true;
                    error = value;
                } else {
                    error = value - 255;
                }
                errors[x + 2] += error * 7;
                nextErrors[x] += error * 3;
                nextErrors[x + 1] += error * 5;
                nextErrors[x + 2] += error;
            }
            int[] swap = errors;
            errors = nextErrors;
            nextErrors = swap;
            y++;
            if (!ink) {
                command("*b0W");
                continue;
            }
            int used = bits.length;
            while (used > 0 && bits[used - 1] == 0) used--;
            packed.reset();
            int length = PackBits.encode(bits, 0, used, packed);
            command("*b" + length + "W");
            packed.writeTo(out);
        }
    }

    @Override
    public void endPage() throws IOException {
        command("*rB");
        out.write(12);
    }

    @Override
    public void finish() throws IOException {
        command("E");
        super.finish();
    }
}
