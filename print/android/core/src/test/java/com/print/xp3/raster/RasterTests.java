package com.print.xp3.raster;

import com.xp3.common.Check;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public final class RasterTests {

    private RasterTests() {}

    /** A page `frame` describes: a dark bar across the top third, a grey ramp, a speckled band. */
    public static byte[] page(Frame frame, int seed) {
        int line = frame.bytesPerLine();
        byte[] out = new byte[line * frame.height];
        Arrays.fill(out, (byte) 0xFF);
        Random random = new Random(seed);
        for (int y = 0; y < frame.height; y++) {
            for (int x = 0; x < frame.width; x++) {
                int value;
                if (y < frame.height / 3) value = x < frame.width / 2 ? 0 : 255;
                else if (y < 2 * frame.height / 3) value = x * 255 / Math.max(1, frame.width - 1);
                else value = random.nextInt(4) == 0 ? random.nextInt(256) : 255;
                for (int c = 0; c < frame.channels(); c++) out[y * line + x * frame.channels() + c] = (byte) (value ^ (c * 40));
            }
        }
        return out;
    }

    public static void run(final Check check) {
        check.run("packbits", () -> {
            byte[] data = {1, 1, 1, 1, 2, 3, 4, 4, 5, 6, 6, 6};
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PackBits.encode(data, 0, data.length, out);
            check.same("round trip", Arrays.toString(data), Arrays.toString(unpack(out.toByteArray())));
            byte[] long1 = new byte[1000];
            new Random(1).nextBytes(long1);
            for (int i = 300; i < 700; i++) long1[i] = 9;
            out.reset();
            PackBits.encode(long1, 0, long1.length, out);
            check.same("long round trip", Arrays.toString(long1), Arrays.toString(unpack(out.toByteArray())));
        });

        check.run("pwg raster", () -> {
            for (boolean color : new boolean[] {false, true}) {
                Frame frame = new Frame(72, 100, 100, color, "na_letter_8.5x11in", Frame.LONG_EDGE, 2, 1);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                PwgRaster format = new PwgRaster();
                format.start(out, frame);
                byte[][] pages = {page(frame, 1), page(frame, 2)};
                for (int p = 0; p < 2; p++) {
                    format.startPage(p, p == 1 ? Back.ROTATE : Back.NONE);
                    // In uneven bites, as the renderer sends strips.
                    int y = 0;
                    while (y < frame.height) {
                        int n = Math.min(17, frame.height - y);
                        format.rows(pages[p], y * frame.bytesPerLine(), n);
                        y += n;
                    }
                    format.endPage();
                }
                format.finish();
                byte[] raw = out.toByteArray();
                check.same("sync", "RaS2", new String(raw, 0, 4, "US-ASCII"));
                int[] at = {4};
                for (int p = 0; p < 2; p++) {
                    ByteBuffer h = ByteBuffer.wrap(raw, at[0], PwgRaster.HEADER).slice();
                    check.same("width", frame.width, h.getInt(372));
                    check.same("height", frame.height, h.getInt(376));
                    check.same("bits per pixel", color ? 24 : 8, h.getInt(388));
                    check.same("colour space", color ? 19 : 18, h.getInt(400));
                    check.same("feed transform", p == 1 ? -1 : 1, h.getInt(460));
                    at[0] += PwgRaster.HEADER;
                    byte[] decoded = LineCoder.decodePage(raw, at, frame.bytesPerLine(), frame.height, frame.channels());
                    check.ok("page " + p + (color ? " colour" : " grey") + " decodes", Arrays.equals(pages[p], decoded));
                }
                check.same("nothing left", raw.length, at[0]);
            }
        });

        check.run("apple raster", () -> {
            Frame frame = new Frame(50, 60, 72, true, "", Frame.ONE_SIDED, 1, 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AppleRaster format = new AppleRaster();
            format.start(out, frame);
            byte[] page = page(frame, 3);
            format.startPage(0, Back.NONE);
            format.rows(page, 0, frame.height);
            format.endPage();
            format.finish();
            byte[] raw = out.toByteArray();
            check.same("magic", "UNIRAST", new String(raw, 0, 7, "US-ASCII"));
            check.same("pages", 1, ByteBuffer.wrap(raw, 8, 4).getInt());
            check.same("bpp", 24, raw[12] & 0xFF);
            check.same("width", frame.width, ByteBuffer.wrap(raw, 24, 4).getInt());
            int[] at = {12 + 32};
            check.ok("decodes", Arrays.equals(page, LineCoder.decodePage(raw, at, frame.bytesPerLine(), frame.height, 3)));
        });

        check.run("ascii85", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PostScript.Ascii85 text = new PostScript.Ascii85(out);
            text.write("Man is distinguished".getBytes("US-ASCII"));
            text.write(new byte[] {0, 0, 0, 0, 1});
            text.close();
            check.same("known", "9jqo^BlbD-BleB1DJ+*+F(f,q", out.toString("US-ASCII").substring(0, 25));
            check.ok("zero group", out.toString("US-ASCII").contains("z!<~>"));
        });

        check.run("back sides", () -> {
            check.same("normal", Back.NONE, Back.of("normal", Frame.LONG_EDGE));
            check.same("rotated long", Back.ROTATE, Back.of("rotated", Frame.LONG_EDGE));
            check.same("rotated short", Back.NONE, Back.of("rotated", Frame.SHORT_EDGE));
            check.same("flipped long", Back.FLIP_VERTICAL, Back.of("flipped", Frame.LONG_EDGE));
            check.same("one-sided", Back.NONE, Back.of("rotated", Frame.ONE_SIDED));
            Frame frame = new Frame(612, 792, 300, false, "na_letter_8.5x11in", Frame.ONE_SIDED, 1, 1);
            check.same("letter at 300", 2550, frame.width);
            check.same("letter at 300 tall", 3300, frame.height);
            check.same("pcl letter", 2, Pcl.paper(frame));
        });
    }

    static byte[] unpack(byte[] coded) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < coded.length) {
            int n = coded[i++] & 0xFF;
            if (n < 128) {
                out.write(coded, i, n + 1);
                i += n + 1;
            } else if (n > 128) {
                for (int k = 0; k < 257 - n; k++) out.write(coded[i]);
                i++;
            }
        }
        return out.toByteArray();
    }
}
