package com.tablet.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;

import com.tablet.xp3.data.Viewport;

/**
 * The preview: one page, up close, with everything that says where you are
 * drawn over it. A bar along the top gives the file and the page (with the 4
 * and 6 keys either side), round arrows at the edges show which ways there
 * is more to pan to, a pill at the bottom left the zoom (with its * # 0
 * keys), and a minimap at the bottom right the whole page with the part on
 * screen marked by the highlighter.
 *
 * The page is laid out in dp by the Viewport, so at 100% it fills the
 * screen's width; the chrome over it is the proposal's pixels at Look.SCALE.
 */
final class PageView extends View {

    final Viewport viewport = new Viewport(240, 296);
    private Page page;
    private String title = "";
    private int number = 1;
    private int pages = 1;
    /** Shows "Page n of m" in the middle for a moment after a page turn. */
    private boolean flash;

    private final float density;
    /** Screen pixels per proposal pixel, for everything drawn over the page. */
    private final float unit;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint type = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final Drawable back;
    private final Drawable[] chevrons;
    private final Runnable unflash = () -> {
        flash = false;
        invalidate();
    };

    PageView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        unit = Look.unit(context);
        stroke.setStyle(Paint.Style.STROKE);
        back = context.getDrawable(R.drawable.ic_chev_left).mutate();
        chevrons = new Drawable[] {
            context.getDrawable(R.drawable.ic_chev_left).mutate(),
            context.getDrawable(R.drawable.ic_chev_right).mutate(),
            context.getDrawable(R.drawable.ic_chev_up).mutate(),
            context.getDrawable(R.drawable.ic_chev_down).mutate(),
        };
    }

    /** `pages` is 0 while the count isn't known yet. */
    void show(Page page, String title, int number, int pages) {
        this.page = page;
        this.title = title;
        this.number = number;
        this.pages = pages;
        viewport.page(Viewport.PAGE_W, page.height());
        invalidate();
    }

    void turned() {
        flash = true;
        removeCallbacks(unflash);
        postDelayed(unflash, 700);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w > 0 && h > 0) viewport.resize(w / density, h / density);
    }

    private float dp(float value) {
        return value * unit;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Look.PAPER);
        if (page == null) return;
        Viewport v = viewport;
        float s = v.scale() * density;
        float left = -v.x * s;
        float top = -v.y * s;
        float w = Viewport.PAGE_W * s;
        float h = v.pageH * s;

        for (int i = 3; i >= 1; i--) {
            fill.setColor(Look.alpha(0xFF000000, 0.05f));
            float spread = dp(i * 1.6f);
            canvas.drawRect(left - spread, top - spread + dp(2), left + w + spread, top + h + spread + dp(2), fill);
        }
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(s, s);
        canvas.clipRect(0, 0, Viewport.PAGE_W, v.pageH);
        canvas.drawColor(Look.PAGE);
        page.draw(canvas);
        canvas.restore();

        drawTop(canvas);
        drawEdges(canvas);
        drawZoom(canvas);
        drawMinimap(canvas);
        if (flash) drawFlash(canvas);
    }

    private void drawTop(Canvas canvas) {
        float width = getWidth();
        float bar = dp(24);
        fill.setColor(Look.alpha(Look.SHEET, 0.94f));
        canvas.drawRect(0, 0, width, bar, fill);
        fill.setColor(Look.RULE);
        canvas.drawRect(0, bar - dp(1), width, bar, fill);
        float mid = bar / 2;

        // The pager, right-aligned: "4 ‹ Page 1 of 12 › 6".
        type.setTextSize(dp(9.5f));
        float x = width - dp(9);
        float base = mid + dp(3.3f);
        if (pages != 1) {
            String at = pages > 1 ? " Page " + number + " of " + pages + " " : " Page " + number + " ";
            x = drawRight(canvas, "› 6", x, base, Look.uiBold, pages == 0 || number < pages ? Look.INK : Look.alpha(Look.INK, 0.3f));
            x = drawRight(canvas, at, x, base, Look.ui, Look.GRAPHITE);
            x = drawRight(canvas, "4 ‹", x, base, Look.uiBold, number > 1 ? Look.INK : Look.alpha(Look.INK, 0.3f));
        } else {
            x = drawRight(canvas, "Page 1 of 1", x, base, Look.ui, Look.GRAPHITE);
        }

        float icon = dp(11.5f);
        back.setTint(Look.INK);
        back.setBounds(Math.round(dp(9)), Math.round(mid - icon / 2), Math.round(dp(9) + icon), Math.round(mid + icon / 2));
        back.draw(canvas);
        type.setTypeface(Look.serifBold);
        type.setTextSize(dp(11.5f));
        type.setColor(Look.INK);
        type.setTextAlign(Paint.Align.LEFT);
        float start = dp(9) + icon + dp(3);
        float room = x - dp(8) - start;
        CharSequence shown = TextUtils.ellipsize(title, new android.text.TextPaint(type), room, TextUtils.TruncateAt.END);
        canvas.drawText(shown.toString(), start, mid + dp(4), type);
    }

    private float drawRight(Canvas canvas, String text, float right, float base, Typeface face, int color) {
        type.setTypeface(face);
        type.setColor(color);
        type.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(text, right, base, type);
        float width = type.measureText(text);
        type.setTextAlign(Paint.Align.LEFT);
        return right - width;
    }

    /** Round arrows on the sides there is more page beyond. */
    private void drawEdges(Canvas canvas) {
        Viewport v = viewport;
        float width = getWidth();
        float height = getHeight();
        float side = height / 2 - dp(8);
        if (v.canLeft()) edge(canvas, chevrons[0], dp(5), side);
        if (v.canRight()) edge(canvas, chevrons[1], width - dp(21), side);
        if (v.canUp()) edge(canvas, chevrons[2], width / 2 - dp(8), dp(30));
        if (v.canDown()) edge(canvas, chevrons[3], width / 2 - dp(8), height - dp(50));
    }

    private void edge(Canvas canvas, Drawable icon, float x, float y) {
        fill.setColor(Look.alpha(Look.INK, 0.75f));
        canvas.drawCircle(x + dp(8), y + dp(8), dp(8), fill);
        icon.setTint(Look.SHEET);
        int side = Math.round(dp(11));
        int l = Math.round(x + dp(8) - side / 2f);
        int t = Math.round(y + dp(8) - side / 2f);
        icon.setBounds(l, t, l + side, t + side);
        icon.draw(canvas);
    }

    /** "200%" on the highlighter, then "* out  # in  0 fit". */
    private void drawZoom(Canvas canvas) {
        String pct = viewport.percent() + "%";
        type.setTypeface(Look.serifBold);
        type.setTextSize(dp(12));
        float pctW = type.measureText(pct) + dp(12);
        String[][] keys = {{"*", " out"}, {"#", " in"}, {"0", " fit"}};
        float keysW = 0;
        type.setTextSize(dp(9));
        for (String[] k : keys) {
            type.setTypeface(Look.uiBold);
            keysW += type.measureText(k[0]);
            type.setTypeface(Look.ui);
            keysW += type.measureText(k[1]);
        }
        keysW += dp(6) * (keys.length - 1);

        float height = dp(26);
        float left = dp(8);
        float bottom = getHeight() - dp(8);
        float top = bottom - height;
        rect.set(left, top, left + dp(5) + pctW + dp(6) + keysW + dp(9), bottom);
        fill.setColor(Look.INK);
        canvas.drawRoundRect(rect, dp(14), dp(14), fill);

        rect.set(left + dp(5), top + dp(4), left + dp(5) + pctW, bottom - dp(4));
        fill.setColor(Look.MARK);
        canvas.drawRoundRect(rect, dp(10), dp(10), fill);
        type.setTypeface(Look.serifBold);
        type.setTextSize(dp(12));
        type.setColor(Look.INK);
        type.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(pct, rect.centerX(), rect.centerY() + dp(4.2f), type);
        type.setTextAlign(Paint.Align.LEFT);

        float x = rect.right + dp(6);
        float base = top + height / 2 + dp(3);
        type.setTextSize(dp(9));
        for (String[] k : keys) {
            type.setTypeface(Look.uiBold);
            type.setColor(Look.MARK);
            canvas.drawText(k[0], x, base, type);
            x += type.measureText(k[0]);
            type.setTypeface(Look.ui);
            type.setColor(Look.alpha(Look.SHEET, 0.85f));
            canvas.drawText(k[1], x, base, type);
            x += type.measureText(k[1]) + dp(6);
        }
    }

    /** The whole page small, with what is on screen washed in the highlighter. */
    private void drawMinimap(Canvas canvas) {
        Viewport v = viewport;
        // A long page's minimap is no taller than the tablet's own page's.
        float mini = 0.15f * Math.min(1, Viewport.PAGE_H / v.pageH);
        float pw = Viewport.PAGE_W * mini * unit;
        float ph = v.pageH * mini * unit;
        float right = getWidth() - dp(8);
        float bottom = getHeight() - dp(8);
        float pad = dp(3);
        float left = right - pw - pad * 2;
        float top = bottom - ph - pad * 2;

        rect.set(left, top, right, bottom);
        fill.setColor(Look.SHEET);
        canvas.drawRoundRect(rect, dp(3), dp(3), fill);
        stroke.setColor(Look.INK);
        stroke.setStrokeWidth(dp(1.5f));
        canvas.drawRoundRect(rect, dp(3), dp(3), stroke);

        float px = left + pad;
        float py = top + pad;
        canvas.save();
        canvas.translate(px, py);
        canvas.scale(mini * unit, mini * unit);
        canvas.clipRect(0, 0, Viewport.PAGE_W, v.pageH);
        canvas.drawColor(Look.PAGE);
        page.draw(canvas);
        canvas.restore();

        float vx = px + v.x * mini * unit;
        float vy = py + v.y * mini * unit;
        float vw = Math.min(pw, v.viewW() * mini * unit);
        float vh = Math.min(ph, v.viewH() * mini * unit);
        rect.set(vx, vy, vx + vw, vy + vh);
        fill.setColor(Look.alpha(Look.MARK, 0.55f));
        canvas.drawRect(rect, fill);
        canvas.drawRect(rect, stroke);
    }

    private void drawFlash(Canvas canvas) {
        String text = pages > 0 ? "Page " + number + " of " + pages : "Page " + number;
        type.setTypeface(Look.serifBold);
        type.setTextSize(dp(13));
        float w = type.measureText(text) + dp(24);
        float h = dp(27);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        fill.setColor(Look.INK);
        canvas.drawRoundRect(rect, dp(14), dp(14), fill);
        type.setColor(Look.SHEET);
        type.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, cx, cy + dp(4.5f), type);
        type.setTextAlign(Paint.Align.LEFT);
    }
}
