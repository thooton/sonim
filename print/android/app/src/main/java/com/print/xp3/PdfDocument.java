package com.print.xp3;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.print.xp3.raster.Back;
import com.print.xp3.raster.Format;
import com.print.xp3.raster.Frame;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * A PDF's pages drawn for the printer by the phone's own renderer, a strip
 * at a time, so a page at 300 dpi (35 MB as one image) never has to be held
 * whole. Each page is scaled to fit the paper, centred, and turned a
 * quarter when its shape is the other way round from the paper's.
 */
final class PdfDocument implements Job.Document, Closeable {

    /** Rows drawn at once: a strip of a Letter page at 300 dpi is about 1.3 MB. */
    private static final int STRIP = 128;

    private final ParcelFileDescriptor fd;
    private final PdfRenderer renderer;

    PdfDocument(File file) throws IOException {
        fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            renderer = new PdfRenderer(fd);
        } catch (SecurityException e) {
            fd.close();
            throw new IOException("This PDF is protected with a password");
        } catch (IOException | RuntimeException e) {
            fd.close();
            throw new IOException("This PDF can't be opened");
        }
    }

    @Override
    public int pages() {
        return renderer.getPageCount();
    }

    @Override
    public void draw(int index, Frame frame, Back back, Format format) throws IOException {
        int line = frame.bytesPerLine();
        byte[] rows = new byte[line * STRIP];
        if (index < 0) {
            Arrays.fill(rows, (byte) 0xFF);
            for (int y = 0; y < frame.height; y += STRIP) format.rows(rows, 0, Math.min(STRIP, frame.height - y));
            return;
        }
        Bitmap strip;
        try {
            strip = Bitmap.createBitmap(frame.width, STRIP, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            throw new IOException("Not enough memory to draw the page");
        }
        int[] pixels = new int[frame.width * STRIP];
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            Matrix place = placement(page.getWidth(), page.getHeight(), frame, back);
            Matrix shifted = new Matrix();
            for (int y = 0; y < frame.height; y += STRIP) {
                int count = Math.min(STRIP, frame.height - y);
                strip.eraseColor(Color.WHITE);
                shifted.set(place);
                shifted.postTranslate(0, -y);
                page.render(strip, null, shifted, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                strip.getPixels(pixels, 0, frame.width, 0, 0, frame.width, count);
                convert(pixels, frame.width * count, frame.color, rows);
                format.rows(rows, 0, count);
            }
        } finally {
            strip.recycle();
        }
    }

    /** Page points to the paper's pixels: fitted, centred, turned to the paper's shape, and turned again for a back side. */
    static Matrix placement(float pageWidth, float pageHeight, Frame frame, Back back) {
        Matrix m = new Matrix();
        boolean pageWide = pageWidth > pageHeight;
        boolean paperWide = frame.width > frame.height;
        boolean turn = pageWide != paperWide;
        float w = turn ? pageHeight : pageWidth;
        float h = turn ? pageWidth : pageHeight;
        if (turn) {
            // A quarter turn clockwise: the page's left edge goes to the paper's top.
            m.postRotate(90);
            m.postTranslate(pageHeight, 0);
        }
        float scale = Math.min(frame.width / w, frame.height / h);
        m.postScale(scale, scale);
        m.postTranslate((frame.width - w * scale) / 2f, (frame.height - h * scale) / 2f);
        switch (back) {
            case ROTATE:
                m.postRotate(180, frame.width / 2f, frame.height / 2f);
                break;
            case FLIP_VERTICAL:
                m.postScale(1, -1, 0, frame.height / 2f);
                break;
            case MIRROR:
                m.postScale(-1, 1, frame.width / 2f, 0);
                break;
            default:
                break;
        }
        return m;
    }

    /** ARGB pixels to 8-bit grey or RGB bytes. */
    private static void convert(int[] pixels, int count, boolean color, byte[] out) {
        if (color) {
            for (int i = 0, o = 0; i < count; i++) {
                int p = pixels[i];
                out[o++] = (byte) (p >> 16);
                out[o++] = (byte) (p >> 8);
                out[o++] = (byte) p;
            }
        } else {
            for (int i = 0; i < count; i++) {
                int p = pixels[i];
                out[i] = (byte) ((((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8);
            }
        }
    }

    @Override
    public void close() {
        renderer.close();
        try {
            fd.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
    }
}
