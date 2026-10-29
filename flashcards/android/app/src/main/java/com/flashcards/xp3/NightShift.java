package com.flashcards.xp3;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.View;

/**
 * Night Shift: the whole screen warmed toward yellow, as Books does it.
 *
 * The window's root view is drawn through a colour filter that keeps red,
 * takes some green and most of the blue away, so every pane goes through it
 * without a second set of colours. Platform dialogs are windows of their own
 * and are not warmed.
 */
final class NightShift {

    private static final float GREEN = 0.84f;
    private static final float BLUE = 0.52f;

    private NightShift() {}

    static void apply(View root, boolean on) {
        if (!on) {
            root.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }
        ColorMatrix matrix = new ColorMatrix();
        matrix.setScale(1f, GREEN, BLUE, 1f);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        root.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
    }
}
