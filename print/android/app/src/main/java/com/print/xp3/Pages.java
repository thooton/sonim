package com.print.xp3;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Reads PDFs with the phone's own renderer: how many pages a file has, its
 * pages drawn small (list thumbnails, the page strip) or to fit the
 * preview. One worker does it all, in the order asked, and answers on the
 * main thread.
 */
final class Pages {

    private Pages() {}

    /** Told on the main thread; `bitmap` is null when the file can't be read. */
    interface Drawn {
        void drawn(Bitmap bitmap);
    }

    interface Counted {
        /** The page count, or {@link #LOCKED} or {@link #BROKEN}. */
        void counted(int pages);
    }

    static final int LOCKED = -2;
    static final int BROKEN = -3;

    private static final ExecutorService WORKER = com.xp3.common.ui.Ui.worker("pages");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Integer> COUNTS = new HashMap<String, Integer>();
    private static final LruCache<String, Bitmap> SMALL = new LruCache<String, Bitmap>(4 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static String key(File file) {
        return file.getPath() + "@" + file.lastModified() + ":" + file.length();
    }

    /** The count if already known, else -1. */
    static synchronized int known(File file) {
        Integer count = COUNTS.get(key(file));
        return count == null ? -1 : count;
    }

    private static synchronized void keep(File file, int count) {
        COUNTS.put(key(file), count);
    }

    static void count(final File file, final Counted answer) {
        int known = known(file);
        if (known != -1) {
            answer.counted(known);
            return;
        }
        WORKER.execute(() -> {
            int count = countNow(file);
            MAIN.post(() -> answer.counted(count));
        });
    }

    /** Off the main thread. */
    static int countNow(File file) {
        int known = known(file);
        if (known != -1) return known;
        int count;
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            count = renderer.getPageCount();
        } catch (SecurityException e) {
            count = LOCKED;
        } catch (IOException | RuntimeException e) {
            count = BROKEN;
        }
        keep(file, count);
        return count;
    }

    /** Page `index` of `file`, to fit `width` x `height` pixels (kept small ones are reused). */
    static void draw(final File file, final int index, final int width, final int height, final Drawn answer) {
        final String key = key(file) + "#" + index + "@" + width + "x" + height;
        Bitmap kept = SMALL.get(key);
        if (kept != null) {
            answer.drawn(kept);
            return;
        }
        WORKER.execute(() -> {
            Bitmap bitmap = drawNow(file, index, width, height);
            if (bitmap != null && width * height <= 64 * 64) SMALL.put(key, bitmap);
            MAIN.post(() -> answer.drawn(bitmap));
        });
    }

    private static Bitmap drawNow(File file, int index, int width, int height) {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            keep(file, renderer.getPageCount());
            if (index >= renderer.getPageCount()) return null;
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                float scale = Math.min(width / (float) page.getWidth(), height / (float) page.getHeight());
                int w = Math.max(1, Math.round(page.getWidth() * scale));
                int h = Math.max(1, Math.round(page.getHeight() * scale));
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                Matrix matrix = new Matrix();
                matrix.setScale(w / (float) page.getWidth(), h / (float) page.getHeight());
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        } catch (SecurityException e) {
            keep(file, LOCKED);
            return null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
