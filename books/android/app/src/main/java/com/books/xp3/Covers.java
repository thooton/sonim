package com.books.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.books.xp3.shelf.Shelf;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Cover thumbnails for the library, cut from each EPUB's cover image once and
 * kept as small PNGs in the cache directory. A book with no cover (and every
 * plain text file) gets a marker file instead, so it is not looked at again;
 * the library draws a cover for it.
 *
 * load() reads files and decodes images: call it off the main thread.
 */
final class Covers {

    private final File directory;
    private final Shelf.Reader reader;

    Covers(Context context, Shelf.Reader reader) {
        directory = new File(context.getCacheDir(), "covers");
        this.reader = reader;
    }

    /** The thumbnail at exactly `width` x `height`, or null if the book has no cover. */
    Bitmap load(Shelf.Entry entry, int width, int height) {
        String name = key(entry) + "-" + width + "x" + height;
        File png = new File(directory, name + ".png");
        File none = new File(directory, name + ".none");
        if (none.exists()) return null;
        if (png.exists()) {
            Bitmap cached = BitmapFactory.decodeFile(png.getPath());
            if (cached != null) return cached;
        }
        Bitmap thumb = null;
        try {
            byte[] bytes = Shelf.cover(reader.readable(entry.file()));
            if (bytes != null) thumb = thumbnail(bytes, width, height);
        } catch (FileNotFoundException e) {
            // Unreadable for now (an SD card not yet allowed): no marker, so it is tried again.
            return null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            thumb = null;
        }
        directory.mkdirs();
        try {
            if (thumb == null) {
                new FileOutputStream(none).close();
                return null;
            }
            OutputStream out = new FileOutputStream(png);
            try {
                thumb.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            // Not cached; it will be cut again next time.
        }
        return thumb;
    }

    /** The image scaled to fill `width` x `height`, its overflow cropped evenly. */
    private static Bitmap thumbnail(byte[] bytes, int width, int height) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width * 2 && bounds.outHeight / (sample * 2) >= height * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (image == null) return null;

        float scale = Math.max(width / (float) image.getWidth(), height / (float) image.getHeight());
        float cropW = width / scale;
        float cropH = height / scale;
        int x = Math.round((image.getWidth() - cropW) / 2);
        int y = Math.round((image.getHeight() - cropH) / 2);
        Rect from = new Rect(x, y, Math.round(x + cropW), Math.round(y + cropH));
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        new Canvas(out).drawBitmap(image, from, new Rect(0, 0, width, height), paint);
        image.recycle();
        return out;
    }

    /** A file name for the book that changes when the book does. */
    private static String key(Shelf.Entry entry) {
        long hash = 1125899906842597L;
        // "2": markers from before 1.1.1 were also left for books that couldn't be read.
        String id = "2|" + entry.path + "|" + entry.size + "|" + entry.modified;
        for (int i = 0; i < id.length(); i++) hash = 31 * hash + id.charAt(i);
        return Long.toHexString(hash);
    }
}
