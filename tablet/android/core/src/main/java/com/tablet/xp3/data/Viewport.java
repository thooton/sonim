package com.tablet.xp3.data;

/**
 * Where the preview is looking: a zoom level and the page point at the
 * screen's top left, in page units. A page is 300 wide and as tall as its
 * shape makes it: 400 for the tablet's own 3:4, 388 for Letter, more for a
 * notebook page scrolled down.
 *
 * At 100% the page's width fills the screen's; the levels go up to 300%.
 * Panning and zooming keep the view on the page, and zooming keeps the
 * screen's centre where it was.
 */
public final class Viewport {

    public static final float PAGE_W = 300;
    /** The tablet's own page, and the shape every cover on the shelf is drawn in. */
    public static final float PAGE_H = 400;
    public static final float[] ZOOMS = {1f, 1.5f, 2f, 3f};

    private float screenW;
    private float screenH;
    public int zoom;
    public float x;
    public float y;
    /** This page's height, in page units. */
    public float pageH = PAGE_H;

    public Viewport(float screenW, float screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
    }

    /** The screen changed size; the zoom and the point in view are kept. */
    public void resize(float w, float h) {
        screenW = w;
        screenH = h;
        clamp();
    }

    /** A page `width` by `height` in any units: its height in page units follows. */
    public void page(float width, float height) {
        pageH = width > 0 && height > 0 ? PAGE_W * height / width : PAGE_H;
        clamp();
    }

    /** Screen pixels per page unit. */
    public float scale() {
        return screenW / PAGE_W * ZOOMS[zoom];
    }

    public int percent() {
        return Math.round(ZOOMS[zoom] * 100);
    }

    /** How much of the page is on screen, in page units. */
    public float viewW() {
        return screenW / scale();
    }

    public float viewH() {
        return screenH / scale();
    }

    public float maxX() {
        return Math.max(0, PAGE_W - viewW());
    }

    public float maxY() {
        return Math.max(0, pageH - viewH());
    }

    public void clamp() {
        x = Math.min(Math.max(x, 0), maxX());
        y = Math.min(Math.max(y, 0), maxY());
    }

    /** One press of the d-pad: a quarter of the screen's width. */
    public void pan(int dx, int dy) {
        float step = screenW / 4 / scale();
        x += dx * step;
        y += dy * step;
        clamp();
    }

    public boolean zoomIn() {
        return zoomTo(zoom + 1);
    }

    public boolean zoomOut() {
        return zoomTo(zoom - 1);
    }

    public boolean zoomTo(int level) {
        if (level < 0 || level >= ZOOMS.length || level == zoom) return false;
        float cx = x + viewW() / 2;
        float cy = y + viewH() / 2;
        zoom = level;
        x = cx - viewW() / 2;
        y = cy - viewH() / 2;
        clamp();
        return true;
    }

    /** The whole width at 100%, from the top. */
    public void fit() {
        zoom = 0;
        x = 0;
        y = 0;
    }

    public boolean canLeft() {
        return x > 1;
    }

    public boolean canRight() {
        return x < maxX() - 1;
    }

    public boolean canUp() {
        return y > 1;
    }

    public boolean canDown() {
        return y < maxY() - 1;
    }
}
