package com.updater.xp3;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * App icons as the App Store draws them: a rounded square (corners at about
 * 22% of the side) with a faint hairline round it.
 *
 * An installed app's own icon is cropped to what it actually paints first,
 * since the launcher icons here leave a transparent margin round a square
 * tile. An app that isn't on the phone gets its initial on a tile in one of
 * iOS's system colours, picked by its name so it is the same every time.
 */
final class Icons {

    private static final int[] TILES = {
        0xFF007AFF, 0xFF34C759, 0xFFFF9500, 0xFFFF2D55,
        0xFF5856D6, 0xFF5AC8FA, 0xFFAF52DE, 0xFFFF3B30,
    };

    private final Context context;
    private final int size;
    private final Map<String, Drawable> cache = new HashMap<>();

    Icons(Context context, int sizeDp) {
        this.context = context;
        // Drawn at twice the size so the corners stay smooth.
        this.size = Math.round(sizeDp * context.getResources().getDisplayMetrics().density * 2);
    }

    /** The icon of the app `info` describes, or its initial's tile when it is null. */
    Drawable of(String name, String label, PackageInfo info) {
        String key = info != null ? info.packageName + "@" + info.lastUpdateTime : "tile:" + name;
        Drawable cached = cache.get(key);
        if (cached != null) return cached;
        Bitmap art = null;
        if (info != null) {
            try {
                art = crop(render(context.getPackageManager().getApplicationIcon(info.applicationInfo)));
            } catch (RuntimeException e) {
                art = null;
            }
        }
        if (art == null) art = tile(name, label);
        Drawable icon = new BitmapDrawable(context.getResources(), round(art));
        cache.put(key, icon);
        return icon;
    }

    private Bitmap render(Drawable drawable) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return out;
    }

    /** The part of `art` that isn't see-through, or `art` itself if that is all of it or none. */
    private static Bitmap crop(Bitmap art) {
        int w = art.getWidth();
        int h = art.getHeight();
        int[] pixels = new int[w * h];
        art.getPixels(pixels, 0, w, 0, 0, w, h);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((pixels[y * w + x] >>> 24) < 32) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right < 0 || (left == 0 && top == 0 && right == w - 1 && bottom == h - 1)) return art;
        return Bitmap.createBitmap(art, left, top, right - left + 1, bottom - top + 1);
    }

    private Bitmap tile(String name, String label) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(TILES[(name.hashCode() & 0x7FFFFFFF) % TILES.length]);
        String text = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(size * 0.5f);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(text, size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
        return out;
    }

    /** `art` scaled to fill the rounded square, with the hairline on top. */
    private Bitmap round(Bitmap art) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        BitmapShader shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix scale = new Matrix();
        scale.setScale(size / (float) art.getWidth(), size / (float) art.getHeight());
        shader.setLocalMatrix(scale);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        fill.setShader(shader);
        float corner = size * 0.225f;
        RectF bounds = new RectF(0, 0, size, size);
        canvas.drawRoundRect(bounds, corner, corner, fill);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(size / 40f);
        line.setColor(0x1F000000);
        float half = line.getStrokeWidth() / 2f;
        bounds.inset(half, half);
        canvas.drawRoundRect(bounds, corner - half, corner - half, line);
        return out;
    }
}
