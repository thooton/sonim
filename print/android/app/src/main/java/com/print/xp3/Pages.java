package com.print.xp3;

import android.content.ContentProviderClient;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
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
 * main thread. A file is first opened by {@link PdfCheck}, in its own
 * process, and only drawn here once it opened there.
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

    private static Context context;

    /** Before anything else is asked: from the activity and the service. */
    static void start(Context c) {
        context = c.getApplicationContext();
    }

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
        int count = check(file);
        keep(file, count);
        return count;
    }

    /** Asks {@link PdfCheck}, starting it afresh when it ended after a bad file. */
    private static int check(File file) {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (attempt > 0) android.os.SystemClock.sleep(50);
            ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(PdfCheck.URI);
            if (client == null) break;
            try {
                Bundle answer = client.call(PdfCheck.PAGES, file.getPath(), null);
                if (answer != null) return answer.getInt(PdfCheck.PAGES, BROKEN);
            } catch (RemoteException | RuntimeException e) {
                // That process ended: the next attempt starts another.
            } finally {
                client.release();
            }
        }
        return BROKEN;
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
        // Only a file that opened in PdfCheck is opened here, so one bad file can't spoil the rest.
        if (index >= countNow(file)) return null;
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
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
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
