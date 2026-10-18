package com.tablet.xp3;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.tablet.xp3.data.Doc;
import com.tablet.xp3.data.Viewport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Library's shelf: the selected file's first page in the middle, the
 * files either side of it smaller, greyed and fading out toward the edges,
 * and under them a row of dots saying where on the shelf you are.
 *
 * Moving along slides every cover from one slot to the next rather than
 * jumping, so the eye can follow which way it went.
 */
final class Shelf extends View {

    /**
     * The slots for covers two either side of the middle, and one beyond
     * each end to slide out to, in dp on the 240dp-wide screen. With the
     * actions moved into the OK menu the shelf has most of the screen, so
     * the middle cover is large enough to make out the page; it sits a
     * clear gap below the header, which it once crowded.
     */
    private static final float[] SCALE = {0.22f, 0.27f, 0.32f, 0.40f, 0.32f, 0.27f, 0.22f};
    private static final float[] LEFT = {-110, -66, -14, 60, 158, 225, 290};
    private static final float[] TOP = {92, 82, 72, 52, 72, 82, 92};
    private static final float[] SHOWN = {0, 1, 1, 1, 1, 1, 0};
    /** The middle of the row of dots, in dp from the top of the shelf. */
    static final float NAV_MID = 225;
    /** The most files shown as dots. */
    private static final int DOTS = 12;

    /** Where covers come from: page 1 of each document, or a stand-in while it is on its way. */
    interface Covers {
        Page cover(Doc doc);
    }

    private final List<Doc> docs = new ArrayList<>();
    private Covers covers;
    private final List<Integer> order = new ArrayList<>();
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layer = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorMatrix grey = new ColorMatrix();
    private final RectF rect = new RectF();
    private final Drawable left;
    private final Drawable right;
    private final float density;
    /** Screen pixels per proposal pixel, for the outline and the dots. */
    private final float unit;
    private float position;
    private int selected;
    private ValueAnimator slide;

    Shelf(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        unit = Look.unit(context);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f * unit);
        outline.setColor(Look.INK);
        left = context.getDrawable(R.drawable.ic_chev_left).mutate();
        right = context.getDrawable(R.drawable.ic_chev_right).mutate();
    }

    /** A new listing, on the document at `index`, without sliding to it. */
    void setDocs(List<Doc> list, int index, Covers from) {
        docs.clear();
        docs.addAll(list);
        covers = from;
        if (slide != null) slide.cancel();
        selected = index;
        position = index;
        invalidate();
    }

    /** Slides to the file at `index`. */
    void select(int index) {
        if (index == selected) return;
        selected = index;
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofFloat(position, index);
        slide.setDuration(180);
        slide.setInterpolator(new DecelerateInterpolator());
        slide.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        slide.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Farthest first, so nearer covers lie over them.
        order.clear();
        for (int i = 0; i < docs.size(); i++) if (Math.abs(i - position) < 3) order.add(i);
        Collections.sort(order, (a, b) -> Float.compare(Math.abs(b - position), Math.abs(a - position)));
        for (int i : order) drawCover(canvas, i, i - position);
        drawNav(canvas);
    }

    private static float slot(float[] values, float d) {
        float at = d + 3;
        int low = (int) Math.floor(at);
        if (low < 0) return values[0];
        if (low >= values.length - 1) return values[values.length - 1];
        float t = at - low;
        return values[low] + (values[low + 1] - values[low]) * t;
    }

    private void drawCover(Canvas canvas, int index, float d) {
        float shown = slot(SHOWN, d);
        if (shown <= 0.01f) return;
        float scale = slot(SCALE, d) * density;
        float x = slot(LEFT, d) * density;
        float y = slot(TOP, d) * density;
        float w = 300 * scale;
        float h = 400 * scale;
        float dim = Math.min(1, Math.abs(d));

        // A soft shadow under the page, as paper lifts off the shelf.
        for (int i = 3; i >= 1; i--) {
            shade.setColor(Look.alpha(0xFF1E1E19, 0.06f * shown * (1 - dim * 0.45f)));
            float spread = i * density;
            canvas.drawRect(x - spread * 0.6f, y + spread * 0.6f, x + w + spread * 0.6f, y + h + spread * 1.4f, shade);
        }

        grey.setSaturation(1 - dim);
        layer.setColorFilter(new ColorMatrixColorFilter(grey));
        layer.setAlpha(Math.round(255 * shown * (1 - 0.45f * dim)));
        rect.set(x, y, x + w, y + h);
        canvas.saveLayer(rect, layer);
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        // The top of the page in the tablet's 3:4, on white where a page is shorter.
        canvas.clipRect(0, 0, Viewport.PAGE_W, Viewport.PAGE_H);
        canvas.drawColor(Look.PAGE);
        covers.cover(docs.get(index)).draw(canvas);
        canvas.restore();

        if (dim < 0.5f) {
            outline.setAlpha(Math.round(255 * (1 - dim * 2)));
            float gap = 3 * unit;
            rect.set(x - gap, y - gap, x + w + gap, y + h + gap);
            canvas.drawRoundRect(rect, 3 * unit, 3 * unit, outline);
        }
    }

    /**
     * ‹ dots › centred under the covers, the selected dot a dash; past
     * DOTS files, which wouldn't fit across, "3 of 40" in their place.
     */
    private void drawNav(Canvas canvas) {
        int n = docs.size();
        if (n == 0) return;
        float gap = 3 * unit;
        float icon = 10 * unit;
        float chev = icon + 8 * unit;
        float mid = NAV_MID * density;
        String count = (selected + 1) + " of " + n;
        float dots = 0;
        if (n > DOTS) {
            label.setTypeface(Look.ui);
            label.setTextSize(9 * unit);
            dots = label.measureText(count);
        } else {
            for (int i = 0; i < n; i++) dots += (i == selected ? 9 : 3) * unit;
        }
        float total = chev * 2 + dots + gap * (n > DOTS ? 2 : n + 1);
        float x = (getWidth() - total) / 2;

        drawChevron(canvas, left, x + 4 * unit, mid, icon, selected > 0);
        x += chev + gap;
        if (n > DOTS) {
            label.setColor(Look.GRAPHITE);
            canvas.drawText(count, x, mid + 3.2f * unit, label);
            x += dots + gap;
        } else {
            for (int i = 0; i < n; i++) {
                float w = (i == selected ? 9 : 3) * unit;
                dot.setColor(i == selected ? Look.INK : Look.RULE);
                rect.set(x, mid - 1.5f * unit, x + w, mid + 1.5f * unit);
                canvas.drawRoundRect(rect, 1.5f * unit, 1.5f * unit, dot);
                x += w + gap;
            }
        }
        drawChevron(canvas, right, x + 4 * unit, mid, icon, selected < n - 1);
    }

    private static void drawChevron(Canvas canvas, Drawable icon, float x, float mid, float size, boolean on) {
        icon.setTint(Look.alpha(Look.GRAPHITE, on ? 1f : 0.25f));
        icon.setBounds(Math.round(x), Math.round(mid - size / 2), Math.round(x + size), Math.round(mid + size / 2));
        icon.draw(canvas);
    }
}
