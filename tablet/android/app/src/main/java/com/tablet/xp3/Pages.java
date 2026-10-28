package com.tablet.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;

import com.tablet.xp3.data.Viewport;
import com.tablet.xp3.net.Center;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * The tablet's pages, as the command center draws them (GET
 * /remarkable/{id}/pages/{n}, one page as a PDF), drawn to bitmaps with
 * Android's own PdfRenderer.
 *
 * Each page's PDF is kept on disk under the document's id and when it was
 * last edited, so a page already seen opens without the network, offline
 * too, and an edit on the tablet makes a new one. Bitmaps are kept in
 * memory, a few screens' worth. One background thread does the fetching
 * and drawing, newest request first: the page just asked for matters more
 * than the covers that slid past on the way to it.
 */
final class Pages {

    /** A page asked for has arrived, or there isn't one; on the main thread. */
    interface Ready {
        void ready(Page page, int pages, String problem);
    }

    /** Cap on the disk cache; the oldest pages go first. */
    private static final long DISK = 80L * 1000 * 1000;
    /** No page is drawn taller than this many times its width (a long-scrolled notebook page). */
    private static final int TALLEST = 5;

    private final Context context;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Ui.worker("tablet-pages");
    private final LruCache<String, Bitmap> memory;
    private final Map<String, Integer> counts = new HashMap<>();
    /** Documents the command center said have no pages at all (an ePub nothing is written on). */
    private final Set<String> empty = new HashSet<>();
    /** What couldn't be had, by key, so a cover drawn every frame isn't asked for every frame. */
    private final Map<String, String> missing = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final LinkedList<Runnable> queue = new LinkedList<>();
    private final Map<String, List<Ready>> waiting = new HashMap<>();
    private volatile Center tablet;
    /** Told when a cover arrives, to draw the shelf again. */
    private Runnable changed = () -> {};

    Pages(Context context) {
        this.context = context;
        dir = new File(context.getCacheDir(), "pages");
        dir.mkdirs();
        int budget = (int) Math.min(Runtime.getRuntime().maxMemory() / 4, 24L * 1024 * 1024);
        memory = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    void signIn(Center tablet) {
        this.tablet = tablet;
        missing.clear();
    }

    void onChange(Runnable changed) {
        this.changed = changed;
    }

    /** Asks again for what couldn't be had, after a refresh. */
    void retry() {
        missing.clear();
    }

    void shutdown() {
        worker.shutdownNow();
    }

    /** The width covers are drawn at: enough for the shelf's largest and the Transform sheet. */
    int coverWidth() {
        return Math.round(240 * context.getResources().getDisplayMetrics().density);
    }

    /** The width a preview page is drawn at: sharp at 300%. */
    int pageWidth(int screen) {
        return Math.min(1200, screen * 3);
    }

    /** How many pages a document has, once one of them has been fetched; 0 until then. */
    int count(String id, long stamp) {
        Integer n = counts.get(id + "@" + stamp);
        return n == null ? 0 : n;
    }

    /** Whether the command center said the document has no pages at all, not just that one couldn't come. */
    boolean empty(String id, long stamp) {
        return empty.contains(id + "@" + stamp);
    }

    /**
     * Page 1 of a document, small, for its cover; a Blank one with its name
     * until it arrives (when `changed` is told), or for good when there is
     * none to show.
     */
    Page cover(String id, long stamp, String name) {
        String key = key(id, stamp, 1, coverWidth());
        Bitmap bitmap = memory.get(key);
        if (bitmap != null) return new Shown(bitmap);
        if (!missing.containsKey(key)) get(id, stamp, 1, coverWidth(), (page, pages, problem) -> changed.run());
        return new Blank(name, "");
    }

    /** Page n, drawn `width` pixels wide; `ready` hears straight away when it is in memory. */
    void page(String id, long stamp, int n, int width, Ready ready) {
        String key = key(id, stamp, n, width);
        Bitmap bitmap = memory.get(key);
        if (bitmap != null) {
            ready.ready(new Shown(bitmap), count(id, stamp), null);
            return;
        }
        missing.remove(key);
        get(id, stamp, n, width, ready);
    }

    private static String key(String id, long stamp, int n, int width) {
        return id + "@" + stamp + "#" + n + "w" + width;
    }

    private void get(String id, long stamp, int n, int width, Ready ready) {
        String key = key(id, stamp, n, width);
        List<Ready> told = waiting.get(key);
        if (told == null) {
            told = new ArrayList<>();
            waiting.put(key, told);
        }
        told.add(ready);
        if (!pending.add(key)) return;
        final Center signedIn = tablet;
        synchronized (queue) {
            queue.addFirst(() -> fetch(signedIn, id, stamp, n, width, key));
        }
        worker.execute(() -> {
            Runnable next;
            synchronized (queue) {
                next = queue.pollFirst();
            }
            if (next != null) next.run();
        });
    }

    /** On the worker: the page from disk, or from the command center to disk, then drawn. */
    private void fetch(Center signedIn, String id, long stamp, int n, int width, String key) {
        Bitmap bitmap = null;
        int pages = 0;
        boolean none = false;
        String problem = null;
        File file = new File(dir, safe(id) + "-" + stamp + "-" + n + ".pdf");
        try {
            if (!file.isFile()) {
                if (signedIn == null) throw new IOException("Not signed in");
                pages = signedIn.page(id, n, file);
                trim();
            }
            bitmap = draw(file, width);
            file.setLastModified(System.currentTimeMillis());
        } catch (Center.NoPage e) {
            pages = e.pages;
            none = e.pages == 0;
            problem = e.getMessage();
        } catch (IOException | RuntimeException e) {
            problem = e.getMessage() == null ? "Couldn't show the page." : e.getMessage();
            file.delete(); // a PDF the renderer refused is fetched again next time
        } catch (InterruptedException e) {
            return;
        }
        final Bitmap drawn = bitmap;
        final int count = pages;
        final String why = problem;
        final boolean nothing = none;
        main.post(() -> {
            pending.remove(key);
            if (count > 0) counts.put(id + "@" + stamp, count);
            if (nothing) empty.add(id + "@" + stamp);
            if (drawn != null) memory.put(key, drawn);
            else missing.put(key, why);
            List<Ready> told = waiting.remove(key);
            if (told == null) return;
            Page page = drawn != null ? new Shown(drawn) : null;
            for (Ready r : told) r.ready(page, count(id, stamp), why);
        });
    }

    private static Bitmap draw(File file, int width) throws IOException {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer opened;
        try {
            opened = new PdfRenderer(fd);
        } catch (IOException | RuntimeException e) {
            fd.close(); // the renderer owns it only once it opens
            throw e;
        }
        try (PdfRenderer renderer = opened) {
            if (renderer.getPageCount() < 1) throw new IOException("That page came back empty.");
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                float shape = page.getHeight() / (float) Math.max(1, page.getWidth());
                int height = Math.round(width * Math.min(shape, TALLEST));
                Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, height), Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                // Drawn to the bitmap's own size: a page longer than TALLEST is squeezed rather than cut.
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        }
    }

    /** Keeps the disk cache under DISK, dropping the pages looked at longest ago. */
    private void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** A page the command center drew. */
    static final class Shown implements Page {
        private final Bitmap bitmap;
        private final RectF box = new RectF();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        Shown(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public float height() {
            return Viewport.PAGE_W * bitmap.getHeight() / bitmap.getWidth();
        }

        @Override
        public void draw(Canvas canvas) {
            box.set(0, 0, Viewport.PAGE_W, height());
            canvas.drawBitmap(bitmap, null, box, paint);
        }
    }

    /**
     * A plain page with the document's name set on it, as a book's title
     * page, and a note under it: what shows while a page is on its way, and
     * an ePub's cover, which the tablet lays out itself.
     */
    static final class Blank implements Page {
        private static final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private static final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final String name;
        private final String says;
        private StaticLayout nameLayout;
        private StaticLayout noteLayout;

        Blank(String name, String says) {
            this.name = name;
            this.says = says;
        }

        @Override
        public float height() {
            return Viewport.PAGE_H;
        }

        private static StaticLayout layout(String text, TextPaint paint, float spacing, int lines) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, 240)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0, spacing)
                .setIncludePad(false)
                .setMaxLines(lines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build();
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawColor(Look.PAGE);
            // With a note (loading, or why the page can't come) the name is
            // smaller and higher, so the note stays above the preview's pill
            // and minimap at 100%.
            boolean noted = says.length() > 0;
            if (nameLayout == null) {
                title.setTypeface(Look.serifBold);
                title.setTextSize(noted ? 20 : 26);
                title.setColor(Look.INK);
                nameLayout = layout(name, title, 1.1f, noted ? 2 : Integer.MAX_VALUE);
                note.setTypeface(Look.ui);
                note.setTextSize(15);
                note.setColor(Look.GRAPHITE);
                noteLayout = layout(says, note, 1.25f, 4);
            }
            canvas.save();
            canvas.translate(30, noted ? 70 : 110);
            nameLayout.draw(canvas);
            canvas.translate(0, nameLayout.getHeight() + 22);
            noteLayout.draw(canvas);
            canvas.restore();
        }
    }
}
