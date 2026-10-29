package com.authenticator.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.xp3.common.Http;
import com.xp3.common.ui.Ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * The services' logos: which service an account is (`Services`, from the
 * bundled services.tsv), its logo fetched from where the list says, the
 * service's own site first and then Google's copy of its icon, or read from
 * the app's own assets for the few the list names as `asset:` (Twitter's
 * bird, Tuta's and SpigotMC's), and kept in files/logos until no account is
 * that service any more (`keep`). Until a logo is here, and for a service
 * with none, the monogram shows.
 */
final class Logos {

    private static final int SIZE = 96;
    private static final int MAX_BYTES = 1 << 20;

    private final Context context;
    private final Runnable changed;
    private final ExecutorService workers = Ui.workers("logos", 3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Http http = new Http();
    private final File dir;

    /** Touched on the main thread only. */
    private Services services;
    private boolean loading;
    private List<Account> kept;
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final Map<String, Integer> tints = new HashMap<>();
    /** Logos being fetched, and ones that couldn't be had this time the app ran. */
    private final Set<String> pending = new HashSet<>();
    private final Set<String> missing = new HashSet<>();

    /** `changed` runs on the main thread when the list or a logo arrives. */
    Logos(Context context, Runnable changed) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        this.dir = new File(context.getFilesDir(), "logos");
    }

    /** The account's logo, or null while there is none (yet). */
    Bitmap get(Account account) {
        String key = key(account);
        if (key == null) return null;
        Bitmap bitmap = bitmaps.get(key);
        if (bitmap != null || missing.contains(key) || pending.contains(key)) return bitmap;
        pending.add(key);
        final Services.Service service = services.find(account);
        workers.execute(() -> {
            Bitmap got = load(key, service);
            Integer tint = got == null ? null : tint(got);
            main.post(() -> {
                pending.remove(key);
                if (got == null) {
                    missing.add(key);
                    return;
                }
                bitmaps.put(key, got);
                if (tint != null) tints.put(key, tint);
                changed.run();
            });
        });
        return null;
    }

    /** The colour the logo is mostly, for its ring, or null when it has none to speak of. */
    Integer tint(Account account) {
        String key = key(account);
        return key == null ? null : tints.get(key);
    }

    /** Forgets, on the phone too, the logos of services no account in `accounts` is. */
    void keep(List<Account> accounts) {
        if (services == null) {
            // Done once the list is read.
            kept = accounts;
            return;
        }
        final Set<String> wanted = new HashSet<>();
        for (Account account : accounts) {
            String key = key(account);
            if (key != null) wanted.add(key);
        }
        bitmaps.keySet().retainAll(wanted);
        tints.keySet().retainAll(wanted);
        missing.retainAll(wanted);
        // One being fetched is written after this, so it is spared too.
        final Set<String> spared = new HashSet<>(wanted);
        spared.addAll(pending);
        workers.execute(() -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File file : files) {
                String name = file.getName();
                String key = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
                if (!spared.contains(key)) file.delete();
            }
        });
    }

    /** The file name the account's service's logo is kept under, or null when it has none. */
    private String key(Account account) {
        if (services == null) {
            loadServices();
            return null;
        }
        Services.Service service = services.find(account);
        return service == null ? null : hash(service.domain + "\n" + service.logo);
    }

    private void loadServices() {
        if (loading) return;
        loading = true;
        workers.execute(() -> {
            Services read;
            try (InputStream in = context.getAssets().open("services.tsv")) {
                read = Services.parse(new String(read(in, Integer.MAX_VALUE), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                read = Services.parse("");
            }
            final Services done = read;
            main.post(() -> {
                services = done;
                if (kept != null) keep(kept);
                kept = null;
                changed.run();
            });
        });
    }

    /** From the phone if it was had before, else fetched or, bundled, read from the app, and kept. */
    private Bitmap load(String key, Services.Service service) {
        File file = new File(dir, key + ".png");
        if (file.length() > 0) {
            Bitmap kept = BitmapFactory.decodeFile(file.getPath());
            if (kept != null) return kept;
        }
        Bitmap logo = service.bundled() ? bundled(service.asset()) : fetched(service);
        if (logo == null) return null;
        dir.mkdirs();
        File part = new File(dir, key + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            logo.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            part.delete();
            return logo;
        }
        if (!part.renameTo(file)) part.delete();
        return logo;
    }

    /** The first of the service's addresses that answers with a picture. */
    private Bitmap fetched(Services.Service service) {
        for (String url : service.logos()) {
            Bitmap got = fetch(url);
            if (got != null) return got;
        }
        return null;
    }

    /** A logo the app carries itself, from its assets under logos/. */
    private Bitmap bundled(String name) {
        try (InputStream in = context.getAssets().open("logos/" + name)) {
            byte[] bytes = read(in, MAX_BYTES);
            return bytes == null ? null : decode(bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The picture at `url`, at most SIZE pixels a side. Redirects are
     * followed by hand, to https only: nothing secret is sent for a logo.
     */
    private Bitmap fetch(String url) {
        try {
            for (int hop = 0; hop < 4; hop++) {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
                try (Http.Stream stream = http.stream(Http.Request.to(uri)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 8.1.0) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36")
                    .header("Accept", "image/*")
                    .timeout(20000))) {
                    int status = stream.status();
                    if (status >= 300 && status < 400 && stream.header("Location").length() > 0) {
                        url = uri.resolve(stream.header("Location")).toString();
                        continue;
                    }
                    if (status != 200) return null;
                    byte[] bytes = read(stream.stream(), MAX_BYTES);
                    return bytes == null ? null : decode(bytes);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException | OutOfMemoryError e) {
            // No logo then; the monogram stays.
        }
        return null;
    }

    /** All of `in`, or null when it is more than `limit` bytes. */
    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            if (out.size() > limit) return null;
        }
        return out.toByteArray();
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Ui.sampleSize(Math.min(bounds.outWidth, bounds.outHeight), SIZE);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) return null;
        int longer = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longer <= SIZE) return bitmap;
        float scale = SIZE / (float) longer;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
            Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    /** The average of the logo's opaque, coloured pixels; null for one in greys. */
    private static Integer tint(Bitmap bitmap) {
        long r = 0, g = 0, b = 0, n = 0;
        float[] hsv = new float[3];
        int step = Math.max(1, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 24);
        for (int y = 0; y < bitmap.getHeight(); y += step) {
            for (int x = 0; x < bitmap.getWidth(); x += step) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 200) continue;
                Color.colorToHSV(pixel, hsv);
                if (hsv[1] < 0.3f || hsv[2] < 0.2f) continue;
                r += Color.red(pixel);
                g += Color.green(pixel);
                b += Color.blue(pixel);
                n++;
            }
        }
        if (n < 4) return null;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 10; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
