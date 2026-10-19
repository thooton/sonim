package com.print.xp3.raster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * PostScript (Level 2): each page one image filling the paper, run-length
 * coded and carried in ASCII85 so that nothing in it can upset a printer
 * reading from a plain socket. Paper, copies and sides are asked for in
 * ways a printer that can't do them will skip rather than fail on.
 */
public final class PostScript extends Format {

    private final ByteArrayOutputStream packed = new ByteArrayOutputStream();
    private Ascii85 text;

    @Override
    public void start(OutputStream out, Frame frame) throws IOException {
        super.start(out, frame);
        String w = number(frame.widthPoints);
        String h = number(frame.heightPoints);
        StringBuilder head = new StringBuilder();
        head.append("%!PS-Adobe-3.0\n")
            .append("%%Creator: Print\n")
            .append("%%Pages: ").append(frame.pages).append('\n')
            .append("%%BoundingBox: 0 0 ").append(Math.round(frame.widthPoints)).append(' ')
            .append(Math.round(frame.heightPoints)).append('\n')
            .append("%%EndComments\n")
            .append("%%BeginSetup\n")
            .append(optional("/PageSize [" + w + " " + h + "]"));
        if (frame.copies > 1) head.append(optional("/NumCopies " + frame.copies));
        if (frame.duplex != Frame.ONE_SIDED) {
            head.append(optional("/Duplex true /Tumble " + (frame.duplex == Frame.SHORT_EDGE)));
        }
        head.append("%%EndSetup\n");
        out.write(ascii(head.toString()));
    }

    private static String optional(String entries) {
        return "[{ << " + entries + " >> setpagedevice } stopped cleartomark\n";
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public void startPage(int index, Back back) throws IOException {
        String decode = frame.color ? "[0 1 0 1 0 1]" : "[0 1]";
        String page = "%%Page: " + (index + 1) + " " + (index + 1) + "\n"
            + "save\n"
            + number(frame.widthPoints) + " " + number(frame.heightPoints) + " scale\n"
            + (frame.color ? "/DeviceRGB" : "/DeviceGray") + " setcolorspace\n"
            + "<< /ImageType 1 /Width " + frame.width + " /Height " + frame.height
            + " /BitsPerComponent 8 /Decode " + decode
            + " /ImageMatrix [" + frame.width + " 0 0 -" + frame.height + " 0 " + frame.height + "]"
            + " /DataSource currentfile /ASCII85Decode filter /RunLengthDecode filter >> image\n";
        out.write(ascii(page));
        text = new Ascii85(out);
    }

    @Override
    public void rows(byte[] data, int offset, int count) throws IOException {
        packed.reset();
        PackBits.encode(data, offset, count * frame.bytesPerLine(), packed);
        text.write(packed.toByteArray());
    }

    @Override
    public void endPage() throws IOException {
        text.write(new byte[] {(byte) 128});
        text.close();
        out.write(ascii("\nrestore\nshowpage\n"));
    }

    @Override
    public void finish() throws IOException {
        out.write(ascii("%%EOF\n"));
        super.finish();
    }

    /** ASCII85 with lines of at most 76 characters, closed by "~>". */
    static final class Ascii85 {
        private final OutputStream out;
        private final byte[] group = new byte[4];
        private int filled;
        private int column;

        Ascii85(OutputStream out) {
            this.out = out;
        }

        void write(byte[] data) throws IOException {
            for (byte b : data) {
                group[filled++] = b;
                if (filled == 4) {
                    emit(4);
                    filled = 0;
                }
            }
        }

        private void emit(int count) throws IOException {
            long value = 0;
            for (int i = 0; i < 4; i++) value = (value << 8) | (i < count ? group[i] & 0xFF : 0);
            if (count == 4 && value == 0) {
                put('z');
                return;
            }
            char[] digits = new char[5];
            for (int i = 4; i >= 0; i--) {
                digits[i] = (char) ('!' + value % 85);
                value /= 85;
            }
            for (int i = 0; i < count + 1; i++) put(digits[i]);
        }

        private void put(char c) throws IOException {
            out.write(c);
            if (++column == 76) {
                out.write('\n');
                column = 0;
            }
        }

        void close() throws IOException {
            if (filled > 0) emit(filled);
            filled = 0;
            out.write('~');
            out.write('>');
        }
    }
}
