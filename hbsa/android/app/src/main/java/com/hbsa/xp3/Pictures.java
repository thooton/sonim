package com.hbsa.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.xp3.common.Http;
import com.xp3.common.ui.Ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.ExecutorService;

/**
 * The posts' pictures: fetched in the background, kept on disk at the
 * screen's size and in memory while the app runs.
 *
 * Instagram's picture addresses are signed and stop working after a few
 * days, so a picture is kept under its post and place in the carousel
 * rather than its address: what was seen once still shows offline.
 */
final class Pictures {

    /** Pictures are kept at most this wide, a little over the screen for sharpness. */
    static final int WIDTH = 480;

    private static final ExecutorService WORKERS = Ui.workers("pictures", 3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Http HTTP = new Http();
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Pictures() {}

    /** Shows the picture `index` of `post` in `view`, decoded no wider than `width`. */
    static void show(Context context, final ImageView view, Post post, int index, final int width) {
        final String name = post.shortcode + "-" + index;
        final String key = name + "@" + width;
        final String url = index < post.images.size() ? post.images.get(index) : "";
        final File file = new File(new File(context.getCacheDir(), "pictures"), name + ".jpg");
        view.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        WORKERS.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (!file.exists() && !url.isEmpty()) fetch(url, file);
                if (file.exists()) bitmap = decode(file, width);
                if (bitmap != null) CACHE.put(key, bitmap);
            } catch (Exception | OutOfMemoryError e) {
                bitmap = null;
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> {
                if (result != null && key.equals(view.getTag())) view.setImageBitmap(result);
            });
        });
    }

    /** Fetches a picture and keeps it on disk, scaled down to WIDTH. */
    private static void fetch(String url, File file) throws IOException, InterruptedException {
        Http.Stream stream = HTTP.stream(Http.Request.to(URI.create(url))
            .header("User-Agent", Instagram.HEADERS[0][1])
            .timeout(45000));
        byte[] bytes;
        try {
            if (stream.status() != 200) throw new IOException("Picture " + stream.status());
            bytes = read(stream.stream());
        } finally {
            stream.close();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= WIDTH) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("Not a picture");
        if (bitmap.getWidth() > WIDTH) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, WIDTH, bitmap.getHeight() * WIDTH / bitmap.getWidth(), true);
            bitmap.recycle();
            bitmap = scaled;
        }
        file.getParentFile().mkdirs();
        File part = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
        }
        bitmap.recycle();
        if (!part.renameTo(file)) throw new IOException("Couldn't keep the picture");
    }

    private static Bitmap decode(File file, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private static byte[] read(InputStream in) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int count; (count = in.read(buffer)) >= 0; ) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            out.write(buffer, 0, count);
            if (out.size() > 6000000) throw new IOException("Picture too large");
        }
        return out.toByteArray();
    }

    /** Drops pictures of posts no longer among the latest. */
    static void prune(Context context, java.util.List<Post> posts) {
        File[] files = new File(context.getCacheDir(), "pictures").listFiles();
        if (files == null) return;
        java.util.Set<String> keep = new java.util.HashSet<String>();
        for (Post post : posts) keep.add(post.shortcode);
        for (File file : files) {
            String name = file.getName();
            int cut = name.lastIndexOf('-');
            if (cut > 0 && !keep.contains(name.substring(0, cut))) file.delete();
        }
    }
}
