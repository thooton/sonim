package com.musica.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Covers: the MP3's own picture, or one drawn from the title in colours
 * taken from it, as the mockup's are. Each song's picture is cut square and
 * kept in the cache folder at the player's size; smaller copies and the
 * blurred backdrops are made from that and held in memory.
 */
final class Art {

    interface Ready {
        void ready();
    }

    /** A blurred cover for the player's background, and how much to darken it. */
    static final class Backdrop {
        final Bitmap blurred;
        final int veil;

        Backdrop(Bitmap blurred, int veil) {
            this.blurred = blurred;
            this.veil = veil;
        }
    }

    private static Art shared;

    static Art get(Context context) {
        if (shared == null) shared = new Art(context.getApplicationContext());
        return shared;
    }

    /** The largest a cover is drawn: the Now Playing one. */
    static final int BIG = 168;

    private final Context context;
    private final Look look;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> memory;
    private final LruCache<String, Backdrop> backdrops = new LruCache<>(4);
    private final Set<String> pending = new HashSet<>();

    private Art(Context context) {
        this.context = context;
        look = Look.get(context);
        dir = new File(context.getCacheDir(), "covers");
        int bytes = (int) Math.min(12L << 20, Runtime.getRuntime().maxMemory() / 6);
        memory = new LruCache<String, Bitmap>(bytes) {
            @Override protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
    }

    /**
     * The song's cover at `size` dp, or null while it is being made, in
     * which case `ready` runs on the main thread once it is.
     */
    Bitmap cover(Song song, int size, Ready ready) {
        String key = song.path + "@" + size + (song.art ? "+art" : "");
        Bitmap b = memory.get(key);
        if (b != null) return b;
        if (!song.art) {
            // Drawing one is quick enough to do here.
            b = drawn(song, Math.round(look.px(size)), size >= 34);
            memory.put(key, b);
            return b;
        }
        if (pending.add(key)) {
            worker.execute(() -> {
                Bitmap made = scaled(big(song), Math.round(look.px(size)));
                main.post(() -> {
                    pending.remove(key);
                    if (made != null) memory.put(key, made);
                    if (ready != null) ready.ready();
                });
            });
        }
        return null;
    }

    /** The blurred backdrop for the player, or null while it is made. */
    Backdrop backdrop(Song song, Ready ready) {
        Backdrop b = backdrops.get(song.path + song.art);
        if (b != null) return b;
        String key = "backdrop:" + song.path + song.art;
        if (pending.add(key)) {
            worker.execute(() -> {
                Bitmap src = song.art ? big(song) : null;
                if (src == null) src = drawn(song, Math.round(look.px(BIG)), true);
                Backdrop made = blur(src);
                main.post(() -> {
                    pending.remove(key);
                    backdrops.put(song.path + song.art, made);
                    if (ready != null) ready.ready();
                });
            });
        }
        return null;
    }

    /** The picture at BIG, cut square: from the cache folder, or the MP3 (then cached). */
    private Bitmap big(Song song) {
        int size = Math.round(look.px(BIG));
        File cached = new File(dir, Integer.toHexString(song.path.hashCode()) + "-" + song.size + ".jpg");
        if (cached.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(cached.getPath());
            if (b != null) return b;
        }
        byte[] picture = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try (ParcelFileDescriptor fd = Files.open(context, new File(song.path))) {
            r.setDataSource(fd.getFileDescriptor());
            picture = r.getEmbeddedPicture();
        } catch (IOException | RuntimeException e) {
            picture = null;
        } finally {
            close(r);
        }
        if (picture == null) return drawn(song, size, true);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        int small = Math.min(bounds.outWidth, bounds.outHeight);
        while (small / (opts.inSampleSize * 2) >= size) opts.inSampleSize *= 2;
        Bitmap raw = BitmapFactory.decodeByteArray(picture, 0, picture.length, opts);
        if (raw == null) return drawn(song, size, true);
        Bitmap square = scaled(raw, size);
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(cached)) {
            square.compress(Bitmap.CompressFormat.JPEG, 92, out);
        } catch (IOException ignored) {
            // Made again next time.
        }
        return square;
    }

    /** `src` cut to its middle square and scaled to `size` px, halving first for smoothness. */
    private static Bitmap scaled(Bitmap src, int size) {
        if (src == null) return null;
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        while (b.getWidth() / 2 >= size) b = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
        if (b.getWidth() != size) b = Bitmap.createScaledBitmap(b, size, size, true);
        return b;
    }

    private static Backdrop blur(Bitmap src) {
        int n = 36;
        Bitmap small = Bitmap.createScaledBitmap(scaled(src, Math.max(n, src.getWidth() / 4)), n, n, true);
        int[] px = new int[n * n];
        small.getPixels(px, 0, n, 0, 0, n, n);
        Pixels.blur(px, n, n, 3);
        Bitmap out = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        return new Backdrop(out, Pixels.veilAlpha(Pixels.meanLuma(px)));
    }

    /**
     * A cover drawn from the title, as the mockup's are: a gradient in the
     * title's colours, one of three simple shapes, and the title written in
     * the corner when there's room.
     */
    Bitmap drawn(Song song, int s, boolean titled) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int[] colors = Pixels.coverColors(song.title);
        int light = colors[0];
        int deep = colors[1];
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, s * 0.35f, s, light, deep, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        p.setShader(null);
        int kind = (Names.fold(song.title).hashCode() >>> 3) % 3;
        if (kind == 0) {
            // A sun on a horizon.
            p.setColor(blend(deep, 0xFF000000, 0.55f));
            c.drawRect(0, s * 0.76f, s, s, p);
            p.setShader(new RadialGradient(s * 0.5f, s * 0.66f, s * 0.21f,
                new int[] {0xFFFFD25A, 0xFFFFD25A, 0xFFFFB347, 0x00FFB347},
                new float[] {0f, 0.86f, 0.95f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(s * 0.5f, s * 0.66f, s * 0.21f, p);
            p.setShader(null);
        } else if (kind == 1) {
            // A band across, and a moon.
            p.setColor(blend(light, 0xFFFFFFFF, 0.35f));
            android.graphics.Path band = new android.graphics.Path();
            band.moveTo(s * 0.62f, 0);
            band.lineTo(s * 0.80f, 0);
            band.lineTo(s * 0.38f, s);
            band.lineTo(s * 0.20f, s);
            band.close();
            c.drawPath(band, p);
            p.setColor(0xF0FFFFFF);
            c.drawCircle(s * 0.76f, s * 0.32f, s * 0.09f, p);
        } else {
            // Two circles overlapping.
            p.setColor(blend(light, 0xFFFFFFFF, 0.25f));
            c.drawCircle(s * 0.42f, s * 0.58f, s * 0.2f, p);
            p.setColor(blend(deep, 0xFF000000, 0.15f));
            p.setAlpha(225);
            c.drawCircle(s * 0.58f, s * 0.58f, s * 0.2f, p);
        }
        if (titled) {
            TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(look.w750);
            t.setTextSize(Math.max(look.px(6), s * 0.13f));
            t.setColor(blend(light, 0xFFFFFFFF, 0.8f));
            int width = Math.round(s * 0.84f);
            StaticLayout layout = new StaticLayout(song.title, t, width, Layout.Alignment.ALIGN_NORMAL, 0.95f, 0, false);
            int lines = Math.min(2, layout.getLineCount());
            c.save();
            c.translate(s * 0.08f, s * 0.07f);
            c.clipRect(0, 0, width, layout.getLineBottom(lines - 1));
            layout.draw(c);
            c.restore();
        }
        return b;
    }

    static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Draws `b` into `dst` with its corners rounded by `radius` px. */
    static void rounded(Canvas c, Bitmap b, RectF dst, float radius, Paint p) {
        p.setShader(null);
        Shader shader = new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setRectToRect(new RectF(0, 0, b.getWidth(), b.getHeight()), dst, android.graphics.Matrix.ScaleToFit.FILL);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        p.setFilterBitmap(true);
        c.drawRoundRect(dst, radius, radius, p);
        p.setShader(null);
    }


    /** release() is declared to throw on newer SDKs, though not on the phone's. */
    static void close(MediaMetadataRetriever r) {
        try {
            r.release();
        } catch (Exception ignored) {
            // Nothing more to free.
        }
    }
}
