package com.canvas.xp3;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.canvas.xp3.data.Detail;
import com.xp3.common.Disk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reading a file (C3): a teacher's PDF, picture or text in a reading view
 * sized to the screen. Up and Down scroll, OK turns the page, and Zoom is
 * in Options.
 *
 * A teacher's file is downloaded once and kept, so an assignment's handouts
 * read again with no signal. Word and PowerPoint files have no reader on the
 * phone; for those the page says so plainly.
 */
final class ViewerScreen extends Screen {

    private final Detail.FileRef ref;
    private final String name;
    private final String back;
    private File file;
    private String problem;
    private PdfRenderer pdf;
    private ParcelFileDescriptor descriptor;
    private int page;
    private boolean zoomed;
    private Bitmap rendered;
    private ScrollView pane;
    private HorizontalScrollView wide;

    ViewerScreen(MainActivity app, Detail.FileRef ref, String back) {
        super(app);
        this.ref = ref;
        this.name = ref.name;
        this.back = back;
        File kept = app.backend.fileOnPhone(ref.id, ref.name);
        if (kept.isFile() && kept.length() > 0) file = kept;
        else download();
    }

    ViewerScreen(MainActivity app, File local, String name, String back) {
        super(app);
        this.ref = null;
        this.name = name;
        this.back = back;
        this.file = local;
    }

    private void download() {
        app.background(() -> app.backend.download(ref.id, null), (got, failure) -> {
            if (got != null) file = got;
            else problem = failure;
            refresh();
        });
    }

    private String kind() {
        String lower = (file != null ? file.getName() : name).toLowerCase(Locale.US);
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.matches(".*\\.(jpe?g|png|gif|webp|bmp)$")) return "image";
        if (lower.matches(".*\\.(txt|md|csv|text)$")) return "text";
        return "other";
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        if (file != null && problem == null && ("pdf".equals(kind()) || "image".equals(kind()))) {
            out.add(new Sheet.Choice(zoomed ? "Fit" : "Zoom", R.drawable.ic_page, null, () -> {
                zoomed = !zoomed;
                app.redraw();
            }));
        }
        return out;
    }

    @Override
    String center() {
        return pdf != null && pdf.getPageCount() > 1 ? "Next page" : "";
    }

    @Override
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (com.xp3.common.ui.Ui.isOk(code)) {
            if (up && pdf != null && pdf.getPageCount() > 1) turn(1);
            return true;
        }
        if (pane != null && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (!up) pane.smoothScrollBy(0, (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * v.dp(60));
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!up) {
                int direction = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (zoomed && wide != null) wide.smoothScrollBy(direction * v.dp(80), 0);
                else if (pdf != null) turn(direction);
            }
            return true;
        }
        return false;
    }

    private void turn(int direction) {
        int count = pdf.getPageCount();
        page = (page + direction + count) % count;
        app.redraw();
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        String where = pdf != null ? "Page " + (page + 1) + " of " + pdf.getPageCount() : null;
        if (file != null && "pdf".equals(kind()) && pdf == null) {
            try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                pdf = new PdfRenderer(descriptor);
                where = "Page 1 of " + pdf.getPageCount();
            } catch (IOException | RuntimeException e) {
                problem = "This PDF couldn't be opened.";
            }
        }
        root.addView(parts.nav(name, where), Views.wide());
        FrameLayout area = new FrameLayout(app);
        area.setBackgroundColor(look.paper);
        root.addView(area, Views.tall());
        pane = null;
        wide = null;

        if (file == null) {
            if (problem == null) return parts.spinner();
            area.addView(parts.empty(0, "Couldn't download", problem), new FrameLayout.LayoutParams(-1, -1));
            return root;
        }
        String kind = kind();
        if ("other".equals(kind) || problem != null) {
            area.addView(parts.empty(0, problem != null ? problem : "This file can't be shown on the phone",
                null),
                new FrameLayout.LayoutParams(-1, -1));
            return root;
        }

        pane = new ScrollView(app);
        pane.setFocusable(true);
        pane.setDefaultFocusHighlightEnabled(false);
        LinearLayout sheetHolder = v.column();
        v.pad(sheetHolder, 10, 8, 10, 8);
        int width = app.getResources().getDisplayMetrics().widthPixels - v.dp(20);
        if ("text".equals(kind)) {
            String text = Disk.read(file, 2000000);
            TextView body = v.text(text == null ? "" : text, 9, look.regular, 0xFF1D2939);
            body.setLineSpacing(0, 1.4f);
            body.setBackgroundColor(Color.WHITE);
            v.pad(body, 12, 12, 12, 12);
            sheetHolder.addView(body, Views.wide());
        } else {
            int target = zoomed ? width * 2 : width;
            if (rendered != null) rendered.recycle();
            rendered = "pdf".equals(kind) ? render(target) : picture(target);
            ImageView image = new ImageView(app);
            image.setImageBitmap(rendered);
            image.setAdjustViewBounds(true);
            image.setBackgroundColor(Color.WHITE);
            image.setElevation(v.dp(2));
            if (zoomed) {
                wide = new HorizontalScrollView(app);
                wide.setHorizontalScrollBarEnabled(false);
                wide.addView(image, new FrameLayout.LayoutParams(target, -2));
                sheetHolder.addView(wide, Views.wide());
            } else {
                sheetHolder.addView(image, Views.wide());
            }
        }
        pane.addView(sheetHolder, new FrameLayout.LayoutParams(-1, -2));
        area.addView(pane, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    @Override
    void shown() {
        if (pane != null) pane.requestFocus();
        app.refreshKeys();
    }

    private Bitmap render(int width) {
        PdfRenderer.Page open = pdf.openPage(page);
        try {
            int height = Math.round((float) width * open.getHeight() / open.getWidth());
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            open.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } finally {
            open.close();
        }
    }

    private Bitmap picture(int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, bounds.outWidth / Math.max(1, width));
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    @Override
    void closed() {
        if (pdf != null) pdf.close();
        try {
            if (descriptor != null) descriptor.close();
        } catch (IOException e) {
            // Nothing to keep.
        }
        pdf = null;
    }

    @Override
    boolean globalOptions() {
        return false;
    }
}
