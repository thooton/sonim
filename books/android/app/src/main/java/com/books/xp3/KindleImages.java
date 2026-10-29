package com.books.xp3;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.books.xp3.book.Mobi;
import com.xp3.common.ui.Ui;

import java.io.ByteArrayOutputStream;

/**
 * A book's images readied for a Kindle while converting to MOBI.
 *
 * JPEGs and GIFs small enough are kept as they are. Anything else (PNG,
 * WebP, a photo of several megabytes) is redrawn as a JPEG on white, no
 * longer than a Kindle's screen on its long side, at the best quality that
 * stays under the size Mobipocket allows an image.
 */
final class KindleImages implements Mobi.Images {

    private static final int MAX_BYTES = 127 * 1024;
    private static final int MAX_SIDE = 1200;

    @Override
    public byte[] prepare(byte[] data) {
        if ((Mobi.isJpeg(data) || Mobi.isGif(data)) && data.length <= MAX_BYTES) return data;
        Bitmap image = null;
        Bitmap out = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            // Decoded at no more than twice the size wanted, so a huge image
            // never has to fit in memory whole.
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Ui.sampleSize(Math.max(bounds.outWidth, bounds.outHeight), MAX_SIDE * 2);
            image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (image == null) return null;

            float scale = Math.min(1f, MAX_SIDE / (float) Math.max(image.getWidth(), image.getHeight()));
            int width = Math.max(1, Math.round(image.getWidth() * scale));
            int height = Math.max(1, Math.round(image.getHeight() * scale));
            out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            // A JPEG has no transparency: what was see-through lands on white, as on the page.
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(image, null, new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
            image.recycle();
            image = null;

            ByteArrayOutputStream bytes = new ByteArrayOutputStream(MAX_BYTES);
            for (int quality = 85; ; quality -= 15) {
                bytes.reset();
                out.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
                if (bytes.size() <= MAX_BYTES || quality <= 40) return bytes.toByteArray();
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            // An image that can't be read or won't fit is left out; the text matters more.
            return null;
        } finally {
            if (image != null) image.recycle();
            if (out != null) out.recycle();
        }
    }
}
