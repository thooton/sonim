package com.newyorker.xp3;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.xp3.common.ui.Ui;

import java.util.concurrent.ExecutorService;

/**
 * Pictures fetched in the background and kept in memory while the app runs.
 *
 * A view asks with its address; the view is tagged with it, so a list row
 * reused for another story by the time the picture arrives is left alone.
 */
final class Pictures {

    private static final ExecutorService WORKERS = Ui.workers("pictures", 3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** About six megabytes of pictures: the front page's, and then some. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Pictures() {}

    /**
     * Shows the picture in `view` once it is here; `whenShown`, if any, runs
     * then, or with false if it never comes.
     */
    static void show(final ImageView view, final String url, final int width, final Shown whenShown) {
        view.setTag(url);
        Bitmap cached = url.isEmpty() ? null : CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (whenShown != null) whenShown.shown(true);
            return;
        }
        view.setImageDrawable(null);
        if (url.isEmpty()) {
            if (whenShown != null) whenShown.shown(false);
            return;
        }
        WORKERS.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = CACHE.get(url);
                if (bitmap == null) {
                    bitmap = Net.picture(url, width);
                    CACHE.put(url, bitmap);
                }
            } catch (Exception | OutOfMemoryError e) {
                bitmap = null;
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> {
                if (!url.equals(view.getTag())) return;
                if (result != null) view.setImageBitmap(result);
                if (whenShown != null) whenShown.shown(result != null);
            });
        });
    }

    interface Shown {
        void shown(boolean ok);
    }

    /** Hides `view` until its picture arrives, and for good if none does. */
    static void showOrHide(final ImageView view, String url, int width) {
        if (url.isEmpty()) {
            view.setTag(url);
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        show(view, url, width, ok -> view.setVisibility(ok ? View.VISIBLE : View.GONE));
    }
}
