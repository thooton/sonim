package com.authenticator.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.authenticator.xp3.qr.QrReader;

import java.io.IOException;
import java.io.InputStream;

/** The QR code in a photo or a picture, read off the main thread. */
final class Pictures {

    private Pictures() {}

    /**
     * The text of the QR code in the picture at `uri`, or null when none
     * could be read. A camera photo is far bigger than a code needs, and the
     * phone's memory small, so the picture is decoded at most `SIDES[0]`
     * pixels on its longer side; a code that can't be read there is tried
     * again smaller, which smooths away a noisy sensor's grain.
     */
    static String read(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(context, uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("That isn't a picture");
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int lastSample = 0;
        for (int side : SIDES) {
            int sample = 1;
            while (longest / (sample * 2) >= side) sample *= 2;
            if (sample == lastSample) continue;
            lastSample = sample;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap;
            try (InputStream in = open(context, uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            } catch (OutOfMemoryError e) {
                continue;
            }
            if (bitmap == null) throw new IOException("That picture can't be opened");
            String text = read(bitmap);
            bitmap.recycle();
            if (text != null) return text;
        }
        return null;
    }

    private static final int[] SIDES = {1600, 800, 400};

    private static InputStream open(Context context, Uri uri) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("That picture can't be opened");
        return in;
    }

    static String read(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            int alpha = (argb >>> 24) & 0xFF;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            int luminance = (r * 306 + g * 601 + b * 117) >> 10;
            // A transparent picture (a code saved as a PNG with no
            // background) is read as on white.
            pixels[i] = (luminance * alpha + 255 * (255 - alpha)) / 255;
        }
        return QrReader.read(pixels, width, height);
    }
}
