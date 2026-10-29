package com.print.xp3.raster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * PCLm, the Wi-Fi Alliance's raster format for printers that only take
 * that: a strict PDF in which each page is a column of strips, each strip
 * a deflated RGB image `strip` rows tall, drawn from the top down.
 */
public final class Pclm extends Format {

    private final int strip;
    private final List<Long> offsets = new ArrayList<>();
    private Counting counted;
    private byte[] buffer;
    private int filled;
    private int page;
    private int stripIndex;
    private int strips;
    private Deflater deflater;

    public Pclm(int strip) {
        this.strip = Math.max(1, strip);
    }

    @Override
    public void start(OutputStream out, Frame frame) throws IOException {
        counted = new Counting(out);
        super.start(counted, frame);
        strips = (frame.height + strip - 1) / strip;
        buffer = new byte[frame.width * 3 * strip];
        deflater = new Deflater(6);
        counted.write(ascii("%PDF-1.7\n%PCLm 1.0\n"));
        object(1, "<</Type /Catalog /Pages 2 0 R>>");
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < frame.pages; p++) kids.append(pageObject(p)).append(" 0 R ");
        object(2, "<</Type /Pages /Kids [ " + kids + "] /Count " + frame.pages + ">>");
    }

    private int pageObject(int p) {
        return 3 + p * (2 + strips);
    }

    private void object(int number, String body) throws IOException {
        mark(number);
        counted.write(ascii(number + " 0 obj\n" + body + "\nendobj\n"));
    }

    private void mark(int number) {
        while (offsets.size() < number) offsets.add(0L);
        offsets.set(number - 1, counted.count);
    }

    @Override
    public void startPage(int index, Back back) throws IOException {
        page = index;
        stripIndex = 0;
        filled = 0;
        int first = pageObject(page);
        String width = points(frame.widthPoints);
        String height = points(frame.heightPoints);
        StringBuilder images = new StringBuilder();
        for (int s = 0; s < strips; s++) images.append("/Image").append(s).append(' ').append(first + 2 + s).append(" 0 R ");
        object(first, "<</Type /Page /Parent 2 0 R /Resources <</XObject <<" + images + ">> >> /MediaBox [0 0 "
            + width + " " + height + "] /Contents [ " + (first + 1) + " 0 R ]>>");

        StringBuilder content = new StringBuilder();
        String scale = String.format(Locale.ROOT, "%.6f", 72.0 / frame.dpi);
        content.append(scale).append(" 0 0 ").append(scale).append(" 0 0 cm\n");
        for (int s = 0; s < strips; s++) {
            int y = frame.height - (s + 1) * strip;
            content.append("/P <</MCID 0>> BDC q\n")
                .append(frame.width).append(" 0 0 ").append(strip).append(" 0 ").append(y).append(" cm\n")
                .append("/Image").append(s).append(" Do Q\n");
        }
        byte[] stream = ascii(content.toString());
        mark(first + 1);
        counted.write(ascii((first + 1) + " 0 obj\n<</Length " + stream.length + ">>\nstream\n"));
        counted.write(stream);
        counted.write(ascii("\nendstream\nendobj\n"));
    }

    private static String points(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public void rows(byte[] data, int offset, int count) throws IOException {
        int line = frame.bytesPerLine();
        for (int i = 0; i < count; i++) {
            int at = filled * frame.width * 3;
            int from = offset + i * line;
            if (frame.color) {
                System.arraycopy(data, from, buffer, at, line);
            } else {
                for (int x = 0; x < frame.width; x++) {
                    byte grey = data[from + x];
                    buffer[at++] = grey;
                    buffer[at++] = grey;
                    buffer[at++] = grey;
                }
            }
            if (++filled == strip) writeStrip();
        }
    }

    private void writeStrip() throws IOException {
        // The last strip is filled out with white below the page's foot.
        for (int i = filled * frame.width * 3; i < buffer.length; i++) buffer[i] = (byte) 0xFF;
        ByteArrayOutputStream packed = new ByteArrayOutputStream(buffer.length / 4);
        deflater.reset();
        DeflaterOutputStream zip = new DeflaterOutputStream(packed, deflater, 64 * 1024);
        zip.write(buffer);
        zip.finish();
        int number = pageObject(page) + 2 + stripIndex;
        mark(number);
        counted.write(ascii(number + " 0 obj\n<</Width " + frame.width + " /ColorSpace /DeviceRGB /Height " + strip
            + " /Filter /FlateDecode /Subtype /Image /Length " + packed.size()
            + " /Type /XObject /BitsPerComponent 8>>\nstream\n"));
        packed.writeTo(counted);
        counted.write(ascii("\nendstream\nendobj\n"));
        stripIndex++;
        filled = 0;
    }

    @Override
    public void endPage() throws IOException {
        if (filled > 0) writeStrip();
        while (stripIndex < strips) writeStrip();
    }

    @Override
    public void finish() throws IOException {
        long xref = counted.count;
        StringBuilder table = new StringBuilder();
        table.append("xref\n0 ").append(offsets.size() + 1).append('\n');
        table.append("0000000000 65535 f \n");
        for (long at : offsets) table.append(String.format(Locale.ROOT, "%010d 00000 n \n", at));
        table.append("trailer\n<</Size ").append(offsets.size() + 1).append(" /Root 1 0 R>>\n");
        table.append("startxref\n").append(xref).append("\n%%EOF\n");
        counted.write(ascii(table.toString()));
        deflater.end();
        super.finish();
    }

    /** Counts what goes through, for the cross-reference table. */
    private static final class Counting extends OutputStream {
        private final OutputStream out;
        long count;

        Counting(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
