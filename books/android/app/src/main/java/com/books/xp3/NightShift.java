package com.books.xp3;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.View;

/**
 * Night Shift: the whole screen warmed toward yellow, as iOS does it.
 *
 * Rather than a second set of colours, each screen's root view is drawn
 * through a colour filter that keeps red, takes some green and most of the
 * blue away. Everything the app draws goes through it (text, the sheets,
 * covers), and black stays black, so the page is still dark. Text that is
 * soft white by day comes out a warm yellow.
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
