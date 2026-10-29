package com.canvas.xp3;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Draft;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Check the photo (D4): the page as it was cropped, with the session's
 * pages in a row under it. Keep makes them one item; Add page goes back for
 * another; Retake throws this one away.
 */
final class ReviewScreen extends Screen {

    private final CameraScreen camera;
    private int index;
    private Bitmap shown;

    ReviewScreen(MainActivity app, CameraScreen camera, int index) {
        super(app);
        this.camera = camera;
        this.index = index;
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        out.add(new Sheet.Choice("Add page", R.drawable.ic_camera, null, app::pop));
        out.add(new Sheet.Choice("Retake", R.drawable.ic_undo, null, this::retake));
        return out;
    }

    @Override
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (Ui.isOk(code)) {
            if (up) keep();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!up) {
                int next = index + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (next >= 0 && next < camera.pages.size()) {
                    index = next;
                    app.redraw();
                }
            }
            return true;
        }
        return false;
    }

    private String name() {
        Draft draft = app.backend.draft(camera.work.id);
        int sets = 0;
        for (Draft.Piece piece : draft.pieces) if (Draft.PHOTOS.equals(piece.kind)) sets++;
        return sets == 0 ? "Notes on paper" : "Notes on paper " + (sets + 1);
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        root.addView(parts.nav("Camera", name()), Views.wide());
        LinearLayout area = v.column();
        area.setBackgroundColor(look.paper);
        area.setGravity(Gravity.CENTER_HORIZONTAL);
        v.pad(area, 0, 8, 0, 6);
        ImageView image = new ImageView(app);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (shown != null) shown.recycle();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        shown = BitmapFactory.decodeFile(camera.pages.get(index).getPath(), options);
        image.setImageBitmap(shown);
        image.setElevation(v.dp(3));
        area.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout thumbs = v.row();
        thumbs.setGravity(Gravity.CENTER);
        for (int i = 0; i < camera.pages.size(); i++) {
            TextView thumb = v.text(Integer.toString(i + 1), 9, look.heavy, i == index ? look.ink : look.muted);
            thumb.setGravity(Gravity.CENTER);
            thumb.setBackground(v.round(0xFFFBFAF5, 3, i == index ? look.ink : look.line, 1.5f));
            thumbs.addView(thumb, v.margins(new LinearLayout.LayoutParams(v.dp(22), v.dp(28)), 2, 0, 3, 0));
        }
        TextView add = v.text("+", 9, look.heavy, look.muted);
        add.setGravity(Gravity.CENTER);
        add.setBackground(v.dashed(0, 3, look.line));
        thumbs.addView(add, v.margins(new LinearLayout.LayoutParams(v.dp(22), v.dp(28)), 2, 0, 2, 0));
        area.addView(thumbs, v.margins(Views.wrap(), 0, 6, 0, 0));
        TextView note = v.text("Page " + (index + 1) + " of " + camera.pages.size(),
            9, look.regular, look.muted);
        area.addView(note, v.margins(Views.wrap(), 0, 6, 0, 0));
        root.addView(area, Ui.weighted());
        return root;
    }

    private void retake() {
        File page = camera.pages.remove(index);
        page.delete();
        app.pop();
    }

    private void keep() {
        final String name = name();
        app.background(() -> camera.finish(name), (piece, problem) -> {
            if (piece == null) {
                app.say("The pages couldn't be saved.");
                return;
            }
            app.pop();
            app.finishWith(piece);
        });
    }

    @Override
    void closed() {
        if (shown != null) shown.recycle();
        shown = null;
    }

    @Override
    boolean globalOptions() {
        return false;
    }
}
