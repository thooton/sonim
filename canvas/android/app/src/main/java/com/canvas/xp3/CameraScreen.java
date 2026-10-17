package com.canvas.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.hardware.Camera;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Draft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Take photos (D3), for work done on paper.
 *
 * Corner guides show where to hold the page, and each picture is cropped to
 * them, so what the teacher gets is the page rather than the desk around it.
 * Each press of OK adds a page, and every page of one session becomes one
 * PDF, a single item under Your work instead of a pile of images.
 */
@SuppressWarnings("deprecation")
final class CameraScreen extends Screen {

    /** The guides, as fractions of the preview: where the page should sit. */
    static final float LEFT = 0.11f;
    static final float RIGHT = 0.89f;
    static final float TOP = 0.06f;
    static final float BOTTOM = 0.84f;

    final Assignment work;
    final List<File> pages = new ArrayList<>();
    private Camera camera;
    private int rotation;
    private boolean busy;
    private SurfaceView preview;
    private final File folder;

    CameraScreen(MainActivity app, Assignment work) {
        super(app);
        this.work = work;
        folder = app.backend.draftFolder(app.backend.draft(work.id));
    }

    @Override
    String left() {
        return "Gallery";
    }

    @Override
    String center() {
        return busy ? "" : "Capture";
    }

    @Override
    String right() {
        return pages.isEmpty() ? null : "Done";
    }

    @Override
    void onRight() {
        if (!pages.isEmpty()) app.push(new ReviewScreen(app, this, pages.size() - 1));
    }

    @Override
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SOFT_LEFT) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                app.withPermission(MainActivity.STORAGE, () -> app.push(PickerScreen.images(app, work)));
            }
            return true;
        }
        if (com.xp3.common.ui.Ui.isOk(code) || code == KeyEvent.KEYCODE_CAMERA) {
            if (event.getAction() == KeyEvent.ACTION_UP) capture();
            return true;
        }
        return false;
    }

    @Override
    View build() {
        FrameLayout root = new FrameLayout(app);
        root.setBackgroundColor(0xFF0E1116);
        root.setFocusable(true);
        preview = new SurfaceView(app);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new Guides(app, v, pages.size() + 1), new FrameLayout.LayoutParams(-1, -1));
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                open(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                close();
            }
        });
        return root;
    }

    private void open(SurfaceHolder holder) {
        if (camera != null) return;
        try {
            camera = Camera.open();
            if (camera == null) throw new RuntimeException();
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);
            rotation = info.orientation;
            camera.setDisplayOrientation(rotation);
            Camera.Parameters params = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size size : params.getSupportedPictureSizes()) {
                int longest = Math.max(size.width, size.height);
                if (longest > 2100) continue;
                if (best == null || size.width * size.height > best.width * best.height) best = size;
            }
            if (best != null) params.setPictureSize(best.width, best.height);
            params.setJpegQuality(88);
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            camera.setParameters(params);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (Exception e) {
            close();
            app.say("The camera couldn't be opened.");
        }
    }

    private void close() {
        if (camera == null) return;
        try {
            camera.stopPreview();
        } catch (RuntimeException e) {
            // Already stopped.
        }
        camera.release();
        camera = null;
    }

    private void capture() {
        if (camera == null || busy) return;
        busy = true;
        app.refreshKeys();
        try {
            camera.takePicture(null, null, (data, cam) -> {
                final byte[] jpeg = data;
                app.background(() -> save(jpeg), (file, problem) -> {
                    busy = false;
                    if (file == null) {
                        app.say("The photo couldn't be saved.");
                        restart();
                        return;
                    }
                    pages.add(file);
                    app.push(new ReviewScreen(app, this, pages.size() - 1));
                });
            });
        } catch (RuntimeException e) {
            busy = false;
            app.say("The photo couldn't be taken.");
        }
    }

    private void restart() {
        if (camera == null) return;
        try {
            camera.startPreview();
        } catch (RuntimeException e) {
            // Reopened on the next surface.
        }
    }

    /** Turns the picture upright and crops it to the guides. */
    private File save(byte[] jpeg) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 1800);
        Bitmap raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (raw == null) throw new IOException("Unreadable photo");
        return page(upright(raw, rotation), true);
    }

    static Bitmap upright(Bitmap raw, int degrees) {
        if (degrees == 0) return raw;
        Matrix turn = new Matrix();
        turn.postRotate(degrees);
        Bitmap turned = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), turn, true);
        if (turned != raw) raw.recycle();
        return turned;
    }

    /** Saves a page, cropped to the guides when it came from the camera. */
    File page(Bitmap upright, boolean crop) throws IOException {
        Bitmap page = upright;
        if (crop) {
            int x = Math.round(upright.getWidth() * LEFT);
            int y = Math.round(upright.getHeight() * TOP);
            int w = Math.round(upright.getWidth() * (RIGHT - LEFT));
            int h = Math.round(upright.getHeight() * (BOTTOM - TOP));
            page = Bitmap.createBitmap(upright, x, y, w, h);
            if (page != upright) upright.recycle();
        }
        File file = new File(folder, "page-" + System.currentTimeMillis() + ".jpg");
        FileOutputStream out = new FileOutputStream(file);
        try {
            page.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } finally {
            out.close();
            page.recycle();
        }
        return file;
    }

    /** A picture chosen from the gallery joins the pages as it is. */
    @Override
    void result(Object value) {
        if (!(value instanceof File)) return;
        final File chosen = (File) value;
        app.background(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(chosen.getPath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 1800);
            Bitmap raw = BitmapFactory.decodeFile(chosen.getPath(), options);
            if (raw == null) throw new IOException("That isn't a picture.");
            return page(raw, false);
        }, (file, problem) -> {
            if (file == null) {
                app.say(problem);
                return;
            }
            pages.add(file);
            app.push(new ReviewScreen(app, this, pages.size() - 1));
        });
    }

    /**
     * Every page as one PDF, each page sized to its photo. Runs on a worker;
     * the photos themselves are deleted once they are inside it.
     */
    Draft.Piece finish(String name) throws IOException {
        PdfDocument pdf = new PdfDocument();
        try {
            for (int i = 0; i < pages.size(); i++) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                Bitmap bitmap = BitmapFactory.decodeFile(pages.get(i).getPath(), options);
                if (bitmap == null) continue;
                int width = 612;
                int height = Math.round(612f * bitmap.getHeight() / bitmap.getWidth());
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(width, height, i + 1).create());
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                page.getCanvas().drawBitmap(bitmap, null, new RectF(0, 0, width, height), paint);
                pdf.finishPage(page);
                bitmap.recycle();
            }
            File file = new File(folder, "photos-" + System.currentTimeMillis() + ".pdf");
            FileOutputStream out = new FileOutputStream(file);
            try {
                pdf.writeTo(out);
            } finally {
                out.close();
            }
            int count = pages.size();
            for (File page : pages) page.delete();
            pages.clear();
            return Draft.Piece.file("p" + System.currentTimeMillis(), Draft.PHOTOS, name, file.getName(),
                "application/pdf", file.length(), count);
        } finally {
            pdf.close();
        }
    }

    @Override
    void shown() {
        app.refreshKeys();
    }

    @Override
    void closed() {
        close();
        for (File page : pages) page.delete();
    }

    @Override
    boolean globalOptions() {
        return false;
    }

    /** The corner guides, the page chip and the one-line hint, over the preview. */
    private static final class Guides extends View {
        private final Views v;
        private final int page;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Guides(Context context, Views v, int page) {
            super(context);
            this.v = v;
            this.page = page;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float left = w * LEFT;
            float right = w * RIGHT;
            float top = h * TOP;
            float bottom = h * BOTTOM;
            float arm = v.dp(16);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(v.dp(2));
            paint.setColor(0xE6FFFFFF);
            canvas.drawLine(left, top, left + arm, top, paint);
            canvas.drawLine(left, top, left, top + arm, paint);
            canvas.drawLine(right, top, right - arm, top, paint);
            canvas.drawLine(right, top, right, top + arm, paint);
            canvas.drawLine(left, bottom, left + arm, bottom, paint);
            canvas.drawLine(left, bottom, left, bottom - arm, paint);
            canvas.drawLine(right, bottom, right - arm, bottom, paint);
            canvas.drawLine(right, bottom, right, bottom - arm, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(v.look.bold);
            paint.setTextSize(v.dp(9));
            paint.setTextAlign(Paint.Align.CENTER);
            String chip = "Page " + page;
            float chipWidth = paint.measureText(chip) + v.dp(16);
            paint.setColor(0x29FFFFFF);
            canvas.drawRoundRect(w / 2 - chipWidth / 2, v.dp(6), w / 2 + chipWidth / 2, v.dp(20), v.dp(10), v.dp(10),
                paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawText(chip, w / 2, v.dp(16.5f), paint);
            paint.setTypeface(v.look.semibold);
            paint.setTextSize(v.dp(9.5f));
            paint.setColor(0xE6FFFFFF);
            canvas.drawText("Fit the page inside the corners", w / 2, h - v.dp(12), paint);
        }
    }
}
