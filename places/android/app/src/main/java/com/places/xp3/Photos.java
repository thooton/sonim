package com.places.xp3;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import com.places.xp3.net.Places;
import com.xp3.common.ui.Ui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * A place's photo, at the top of its page. Each one is billed when fetched,
 * so only the first of a place's photos is shown, fetched small, once, and
 * kept in memory for as long as the app runs. One that fails is left as its grey frame.
 */
final class Photos {

    /** What hands over the client to fetch with; it changes with the login. */
    interface Client {
        Places places();
    }

    /** Big enough for the largest frame, on Google's side, so little crosses the air. */
    private static final int SIZE = 240;

    private final Client client;
    private final ExecutorService pool = Ui.workers("photos", 2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override
        protected int sizeOf(String name, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    /** The frames waiting on each photo being fetched. */
    private final Map<String, List<ImageView>> waiting = new HashMap<String, List<ImageView>>();
    private final Set<String> failed = new HashSet<String>();

    Photos(Client client) {
        this.client = client;
    }

    /** Puts photo `name` in `frame` now if it is known, or once it has come. */
    void show(ImageView frame, String name) {
        frame.setTag(name);
        Bitmap known = cache.get(name);
        frame.setImageBitmap(known);
        if (known != null || failed.contains(name)) return;
        List<ImageView> frames = waiting.get(name);
        if (frames != null) {
            if (!frames.contains(frame)) frames.add(frame);
            return;
        }
        frames = new ArrayList<ImageView>();
        frames.add(frame);
        waiting.put(name, frames);
        final Places places = client.places();
        pool.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = places.photo(name, SIZE);
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception e) {
                // Left grey.
            }
            final Bitmap got = bitmap;
            main.post(() -> {
                if (got != null) cache.put(name, got);
                else failed.add(name);
                List<ImageView> those = waiting.remove(name);
                if (got == null || those == null) return;
                for (ImageView view : those) {
                    if (name.equals(view.getTag())) view.setImageBitmap(got);
                }
            });
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }
}
