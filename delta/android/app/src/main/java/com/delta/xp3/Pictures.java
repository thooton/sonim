package com.delta.xp3;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The pictures a message carries, from the camera or the gallery to the cloud.
 *
 * A picture is shrunk to what the model can use before it goes anywhere: no more
 * than MAX_EDGE on its long side (larger is scaled down by the model anyway,
 * after being paid for in upload time), turned upright from its EXIF
 * orientation, and saved as a JPEG. The XP3's camera writes several megabytes
 * per shot; what is sent is a few hundred kilobytes.
 *
 * Pictures waiting for the next message live in draft/, so they survive the
 * activity being destroyed while the camera is open. Sending moves them to
 * sent/, named after the message's uuid, which is how the transcript finds
 * them again to draw.
 */
final class Pictures {

    /** At most this many pictures per message. */
    static final int MAX_DRAFT = 4;
    private static final int MAX_EDGE = 1568;
    private static final int QUALITY = 85;

    /** Something that can be opened more than once: bounds, orientation and pixels are separate reads. */
    interface Source {
        InputStream open() throws IOException;
    }

    private final File draft;
    private final File sent;
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    Pictures(File directory) {
        draft = new File(directory, "draft");
        sent = new File(directory, "sent");
    }

    /** Where the camera writes its shot; served to it by CaptureProvider. */
    static File capture(File directory) {
        return new File(new File(directory, "capture"), "capture.jpg");
    }

    // ------------------------------------------------------------------
    // The draft
    // ------------------------------------------------------------------

    /** The pictures waiting for the next message, oldest first. */
    synchronized List<File> draft() {
        File[] files = draft.listFiles();
        List<File> pictures = new ArrayList<File>();
        if (files == null) return pictures;
        Arrays.sort(files);
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jpg")) pictures.add(file);
        }
        return pictures;
    }

    /** Adds a prepared JPEG to the draft. Refused once the draft is full. */
    synchronized void addToDraft(byte[] jpeg) throws IOException {
        if (draft().size() >= MAX_DRAFT) throw new IOException("A message can carry " + MAX_DRAFT + " pictures");
        if (!draft.isDirectory() && !draft.mkdirs()) throw new IOException("Could not open the app's storage");
        String name = String.format(java.util.Locale.US, "%015d", System.currentTimeMillis());
        File temporary = new File(draft, name + ".tmp");
        write(temporary, jpeg);
        File target = new File(draft, name + ".jpg");
        for (int i = 1; target.exists(); i++) target = new File(draft, name + "-" + i + ".jpg");
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Could not save the picture");
        }
    }

    synchronized void clearDraft() {
        for (File file : draft()) file.delete();
    }

    /** Moves the draft to the sent pictures of message `uuid`, and returns them in order. */
    synchronized List<File> commit(String uuid) {
        List<File> moved = new ArrayList<File>();
        List<File> waiting = draft();
        if (waiting.isEmpty()) return moved;
        sent.mkdirs();
        for (File file : waiting) {
            File target = sent(uuid, moved.size());
            if (file.renameTo(target)) moved.add(target);
        }
        return moved;
    }

    /** The `index`th picture of message `uuid`, whether or not it exists. */
    File sent(String uuid, int index) {
        return new File(sent, uuid + "-" + index + ".jpg");
    }

    /**
     * Throws away sent pictures whose message is not in any transcript still
     * kept, given the ids of the messages that are. Anything from the last
     * day is spared, so a message sent while this runs keeps its pictures
     * even though its transcript has not been saved yet.
     */
    synchronized void retain(Set<String> messages) {
        File[] files = sent.listFiles();
        if (files == null) return;
        long recent = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File file : files) {
            String name = file.getName();
            int dash = name.lastIndexOf('-');
            String message = dash < 0 ? name : name.substring(0, dash);
            if (!messages.contains(message) && file.lastModified() < recent) file.delete();
        }
        thumbnails.evictAll();
    }

    static byte[] read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void write(File file, byte[] bytes) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
            ((FileOutputStream) out).getFD().sync();
        } finally {
            out.close();
        }
    }

    // ------------------------------------------------------------------
    // Preparing
    // ------------------------------------------------------------------

    /** Shrinks, turns upright and re-encodes a picture. Worker thread. */
    static byte[] prepare(Source source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = source.open();
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            in.close();
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("That is not a picture this phone can read");
        }

        // Decode at the largest power-of-two reduction that still leaves at
        // least MAX_EDGE, so a camera shot never has to fit in memory whole.
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / (sample * 2) >= MAX_EDGE) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try {
            in = source.open();
            try {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            } finally {
                in.close();
            }
            if (bitmap == null) throw new IOException("That is not a picture this phone can read");
            return encode(bitmap, orientation(source));
        } catch (OutOfMemoryError e) {
            throw new IOException("That picture is too large for this phone");
        }
    }

    /** A bitmap handed back directly, as a camera that ignores EXTRA_OUTPUT does. */
    static byte[] prepare(Bitmap bitmap) throws IOException {
        try {
            return encode(bitmap, 0);
        } catch (OutOfMemoryError e) {
            throw new IOException("That picture is too large for this phone");
        }
    }

    private static byte[] encode(Bitmap bitmap, int degrees) {
        Matrix matrix = new Matrix();
        float scale = MAX_EDGE / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (scale < 1f) matrix.postScale(scale, scale);
        if (degrees != 0) matrix.postRotate(degrees);
        Bitmap upright = matrix.isIdentity()
            ? bitmap
            : Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
        if (upright != bitmap) upright.recycle();
        bitmap.recycle();
        return out.toByteArray();
    }

    /** The clockwise turn the EXIF data asks for. Gallery pictures without any are taken as they are. */
    private static int orientation(Source source) {
        try {
            InputStream in = source.open();
            try {
                int tag = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (tag == ExifInterface.ORIENTATION_ROTATE_90) return 90;
                if (tag == ExifInterface.ORIENTATION_ROTATE_180) return 180;
                if (tag == ExifInterface.ORIENTATION_ROTATE_270) return 270;
                return 0;
            } finally {
                in.close();
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * A small copy for the screen, about `width` pixels across, or null when
     * the file is missing. Cached, because the transcript rebinds its rows on
     * every event that arrives.
     */
    Bitmap thumbnail(File file, int width) {
        String key = file.getPath() + "@" + width;
        Bitmap cached = thumbnails.get(key);
        if (cached != null) return cached;
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap != null) thumbnails.put(key, bitmap);
            return bitmap;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
