package com.weather.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The one screen: the sky (place, icon, temperature, scenery) edge to edge,
 * then the hourly and daily forecasts straight on the page. Drawn in a
 * 240-wide space, the mockups' own, scaled to the view.
 */
final class WeatherView extends View {

    static final float W = 240, SKY = 148, M = 8, HOURLY_H = 88, DAY_H = 19;
    static final int COLS = 6;
    static final float COLW = (W - 2 * M) / COLS;

    /** The page's colours under the sky, CARROT's dark and light. */
    static final class Theme {
        final boolean dark;
        final int bg, text, muted, faint, rule, track, sheet, field;

        Theme(boolean dark) {
            this.dark = dark;
            bg = dark ? 0xFF000000 : 0xFFFFFFFF;
            text = dark ? 0xFFFFFFFF : 0xFF000000;
            muted = 0xFF8E8E93;
            faint = dark ? 0xFF636366 : 0xFFAEAEB2;
            rule = dark ? 0xFF2C2C2E : 0xFFF0F0F0;
            track = dark ? 0xFF2C2C2E : 0xFFE5E5EA;
            sheet = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
            field = dark ? 0xFF2C2C2E : 0xFFEFEFF2;
        }
    }

    /** Told when the colour behind the status bar changes. */
    interface Chrome {
        void statusBar(int color, boolean lightIcons);
    }

    Forecast forecast;
    Metric metric = Metric.TEMP;
    Units units = Units.US;
    Theme theme = new Theme(true);
    boolean clock24;
    boolean loading;
    /** First hour shown, as an index into the hours from now. */
    int offset;
    String error;
    Sheet sheet;
    Chrome chrome;
    /** While something lies over the page, it says the status bar's colour, not the page. */
    boolean covered;

    private float scroll;
    private int lastBar;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    WeatherView(Context context) {
        super(context);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    // ------------------------------------------------------------------
    // Scrolling.
    // ------------------------------------------------------------------

    private float scale() {
        return getWidth() / W;
    }

    /** The page's height, in the 240-wide space. */
    private float pageHeight() {
        int days = forecast == null ? 0 : Math.min(10, forecast.daysFrom(System.currentTimeMillis()).size());
        return SKY + 2 + HOURLY_H + 2 + 21 + days * DAY_H + 6;
    }

    /** Far enough down that the last day sits at the foot of the screen, and no further. */
    private float maxScroll() {
        return Math.max(0, pageHeight() - getHeight() / scale());
    }

    /** How far one press of Up or Down moves the page: two days' rows. */
    static final float STEP = DAY_H * 2;

    boolean scrollDown() {
        float max = maxScroll();
        if (scroll >= max - .5f) return false;
        scrollTo(Math.min(max, scroll + STEP));
        return true;
    }

    boolean scrollUp() {
        if (scroll <= .5f) return false;
        scrollTo(Math.max(0, scroll - STEP));
        return true;
    }

    void scrollToTop() {
        scroll = 0;
        invalidate();
    }

    /** Straight there: no easing, so each press lands at once. */
    private void scrollTo(float to) {
        scroll = to;
        invalidate();
    }

    /** Right: on six hours. */
    boolean hoursOn(int by) {
        if (forecast == null) return false;
        int count = forecast.hoursFrom(System.currentTimeMillis()).size();
        int to = Math.max(0, Math.min(Math.max(0, count - COLS), offset + by));
        if (to == offset) return false;
        offset = to;
        invalidate();
        return true;
    }

    /** `color` under the sheet's scrim (black at .45) while a sheet is open, as the page is. */
    private int dimmed(int color) {
        if (sheet == null) return color;
        int r = Math.round(((color >> 16) & 0xFF) * .55f), g = Math.round(((color >> 8) & 0xFF) * .55f), b = Math.round((color & 0xFF) * .55f);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Says the status bar's colour again, after something else took it. */
    void forceStatusBar() {
        lastBar = 0;
        invalidate();
    }

    // ------------------------------------------------------------------
    // Drawing.
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        float s = scale();
        c.save();
        c.scale(s, s);
        c.drawColor(theme.bg);
        if (forecast == null) {
            drawEmpty(c);
        } else {
            float max = maxScroll();
            if (scroll > max) scroll = max;
            c.save();
            c.translate(0, -scroll);
            String cond = forecast.now.condition;
            drawSky(c, cond);
            drawHourly(c, SKY + 2);
            drawDaily(c, SKY + 2 + HOURLY_H + 2);
            c.restore();
            int bar = dimmed(scroll > SKY - 10 ? theme.bg : Condition.card(cond));
            if (bar != lastBar && chrome != null && !covered) {
                lastBar = bar;
                chrome.statusBar(bar, scroll > SKY - 10 && !theme.dark && sheet == null);
            }
        }
        if (loading) {
            Art.rect(c, 0, 0, W, 2, 0, 0xFFFFFFFF, .25f);
            float t = (System.currentTimeMillis() % 1200) / 1200f;
            Art.rect(c, -80 + t * 320, 0, 80, 2, 0, forecast == null || (scroll > SKY - 10 && !theme.dark) ? Colors.BLUE : 0xFFFFFFFF, 1);
            postInvalidateOnAnimation();
        }
        if (sheet != null) sheet.draw(c, this, getHeight() / s);
        c.restore();
    }

    private void drawEmpty(Canvas c) {
        int bar = dimmed(Colors.CARD_DAY);
        if (bar != lastBar && chrome != null && !covered) {
            lastBar = bar;
            chrome.statusBar(bar, false);
        }
        Art.rect(c, 0, 0, W, SKY, 0, Colors.CARD_DAY, 1);
        Art.scenery(c, Condition.Scenery.DAY, SKY - 55);
        label(c, W / 2, 70, error != null ? "No forecast" : "Getting the forecast", 14, MEDIUM, 0xFFFFFFFF, Paint.Align.CENTER);
        if (error != null) wrapped(c, error, W / 2, SKY + 30, 12, theme.muted);
    }

    private void drawSky(Canvas c, String cond) {
        Art.rect(c, 0, 0, W, SKY, 0, Condition.card(cond), 1);
        c.save();
        c.clipRect(0, 0, W, SKY);
        Art.effect(c, Condition.effect(cond), W, SKY - 36);
        label(c, W / 2, 19, fit(forecast.place, 220, 14, MEDIUM), 14, MEDIUM, 0xFFFFFFFF, Paint.Align.CENTER);
        String temp = units.degrees(forecast.now.temp);
        text.setTypeface(LIGHT);
        text.setTextSize(46);
        float tw = text.measureText(temp);
        float iw = 50, gap = 12, total = iw + gap + tw - 4;
        float ix = (W - total) / 2;
        Art.icon(c, cond, ix, 30, iw, true);
        label(c, ix + iw + gap - 2, 70, temp, 46, LIGHT, 0xFFFFFFFF, Paint.Align.LEFT);
        text.setAlpha(255);
        label(c, ix + iw + gap, 86, "Feels " + units.degrees(forecast.now.feels), 11, REGULAR, 0xD9FFFFFF, Paint.Align.LEFT);
        Art.scenery(c, Condition.scenery(cond), SKY - 55);
        c.restore();
    }

    private void drawHourly(Canvas c, float y0) {
        long now = System.currentTimeMillis();
        List<Forecast.Hour> all = forecast.hoursFrom(now);
        if (all.isEmpty()) return;
        offset = Math.min(offset, Math.max(0, all.size() - COLS));
        List<Forecast.Hour> hs = all.subList(offset, Math.min(all.size(), offset + COLS));
        Metric m = metric;
        c.save();
        c.translate(0, y0);
        header(c, "HOURLY FORECAST");
        // Which data, on the right, in CARROT's blue.
        text.setTypeface(MEDIUM);
        text.setTextSize(9.5f);
        text.setLetterSpacing(0);
        String chip = m.chip(units);
        float lw = width(chip, 9.5f, MEDIUM) + 14;
        Art.rect(c, W - M - lw, 5, lw, 14, 7, Colors.BLUE, 1);
        label(c, W - M - lw / 2, 15.2f, chip, 9.5f, MEDIUM, 0xFFFFFFFF, Paint.Align.CENTER);
        // Times and icons.
        for (int i = 0; i < hs.size(); i++) {
            Forecast.Hour h = hs.get(i);
            float cx = M + COLW * i + COLW / 2;
            String[] l = forecast.hourLabel(h.time, clock24);
            text.setTypeface(REGULAR);
            text.setTextSize(10.5f);
            float a = text.measureText(l[0]);
            text.setTextSize(7.5f);
            float b = l[1].isEmpty() ? 0 : text.measureText(l[1]) + .5f;
            float x = cx - (a + b) / 2;
            label(c, x, 34, l[0], 10.5f, REGULAR, theme.text, Paint.Align.LEFT);
            if (b > 0) label(c, x + a + .5f, 34, l[1], 7.5f, REGULAR, theme.text, Paint.Align.LEFT);
            Art.icon(c, h.condition, cx - 8, 39, 16, false);
        }
        float top = 62, bottom = 84;
        if (m.kind == Metric.Kind.LINE) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (Forecast.Hour h : all) {
                double v = m.plotted(m.value(h), units);
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            final double flo = lo, fhi = hi;
            // Half a column past each edge, as the iOS chart runs off it.
            int first = Math.max(0, offset - 1), last = Math.min(all.size() - 1, offset + COLS);
            List<float[]> pts = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (int i = first; i <= last; i++) {
                double raw = m.value(all.get(i));
                pts.add(new float[] {M + COLW * (i - offset) + COLW / 2, yOf(m.plotted(raw, units), flo, fhi, top, bottom)});
                colors.add(lineColor(m, raw));
            }
            Path p = new Path();
            for (int k = 0; k < pts.size(); k++) {
                float[] pt = pts.get(k);
                if (k == 0) p.moveTo(pt[0], pt[1]);
                else {
                    float[] q = pts.get(k - 1);
                    float mx = (q[0] + pt[0]) / 2;
                    p.cubicTo(mx, q[1], mx, pt[1], pt[0], pt[1]);
                }
            }
            int[] cs = new int[colors.size()];
            float[] stops = new float[colors.size()];
            for (int k = 0; k < cs.length; k++) {
                cs[k] = colors.get(k);
                stops[k] = cs.length == 1 ? 0 : (pts.get(k)[0] - pts.get(0)[0]) / (pts.get(pts.size() - 1)[0] - pts.get(0)[0]);
            }
            line.setStrokeWidth(2.2f);
            line.setColor(0xFFFFFFFF);
            if (cs.length > 1) {
                line.setShader(new LinearGradient(pts.get(0)[0], 0, pts.get(pts.size() - 1)[0], 0, cs, stops, Shader.TileMode.CLAMP));
            } else {
                line.setColor(cs[0]);
            }
            c.drawPath(p, line);
            line.setShader(null);
            for (int i = 0; i < hs.size(); i++) {
                Forecast.Hour h = hs.get(i);
                float cx = M + COLW * i + COLW / 2;
                double raw = m.value(h);
                float y = yOf(m.plotted(raw, units), flo, fhi, top, bottom);
                Art.circle(c, cx, y, 2.4f + .8f, lineColor(m, raw), 1);
                Art.circle(c, cx, y, 2.4f - .8f, theme.bg, 1);
                label(c, cx, y - 5, m.format(raw, units), 10.5f, MEDIUM, theme.text, Paint.Align.CENTER);
            }
        } else if (m.kind == Metric.Kind.BAR) {
            for (int i = 0; i < hs.size(); i++) {
                Forecast.Hour h = hs.get(i);
                float cx = M + COLW * i + COLW / 2;
                double v = m.value(h);
                float bh = (float) Math.max(2, Math.min(1, v / m.max()) * 16);
                Art.rect(c, cx - 7, bottom - 16, 14, 16, 2, theme.track, 1);
                Art.rect(c, cx - 7, bottom - bh, 14, bh, 2, lineColor(m, v), 1);
                label(c, cx, bottom - 20, m.format(v, units), 10.5f, MEDIUM, theme.text, Paint.Align.CENTER);
            }
        } else {
            for (int i = 0; i < hs.size(); i++) {
                Forecast.Hour h = hs.get(i);
                float cx = M + COLW * i + COLW / 2;
                label(c, cx, 70, m.format(h.wind, units), 12, MEDIUM, theme.text, Paint.Align.CENTER);
                c.save();
                c.translate(cx, 79);
                c.rotate((float) h.direction + 180);
                Art.fill(c, "M0,-5 L3.6,4 L0,2.2 L-3.6,4 Z", 0xFF21D3A0);
                c.restore();
            }
        }
        c.restore();
    }

    private static float yOf(double v, double lo, double hi, float top, float bottom) {
        if (hi == lo) return (top + bottom) / 2 + 4;
        return (float) (bottom - (v - lo) / (hi - lo) * (bottom - top - 4));
    }

    private static int lineColor(Metric m, double v) {
        switch (m) {
            case TEMP: case FEELS: case DEW: return Colors.temp(v);
            case POP: case PRECIP: return Colors.BLUE;
            case HUMIDITY: return 0xFF30D5C8;
            case UV: return Colors.uv(v);
            case CLOUD: return 0xFF8E9AAF;
            case PRESSURE: return 0xFFBF5AF2;
            default: return 0xFF64D2FF;
        }
    }

    private void drawDaily(Canvas c, float y0) {
        long now = System.currentTimeMillis();
        List<Forecast.Day> all = forecast.daysFrom(now);
        if (all.size() > 10) all = all.subList(0, 10);
        if (all.isEmpty()) return;
        double wlo = Double.MAX_VALUE, whi = -Double.MAX_VALUE;
        for (Forecast.Day d : all) {
            wlo = Math.min(wlo, d.low);
            whi = Math.max(whi, d.high);
        }
        if (whi == wlo) whi = wlo + 1;
        c.save();
        c.translate(0, y0);
        Paint rule = new Paint();
        rule.setColor(theme.rule);
        c.drawRect(M, 0, W - M, 1, rule);
        header(c, "DAILY FORECAST");
        float bx0 = 122, bx1 = 196;
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int k = 0; k < all.size(); k++) {
            Forecast.Day d = all.get(k);
            float y = 21 + k * DAY_H, mid = y + DAY_H / 2;
            text.setLetterSpacing(.2f / 10.5f);
            label(c, M, mid + 4, forecast.dayLabel(d, now), 10.5f, MEDIUM, theme.text, Paint.Align.LEFT);
            text.setLetterSpacing(0);
            Art.icon(c, Condition.daytime(d.condition), 52, mid - 8, 16, false);
            if (d.pop >= 20) label(c, 71, mid + 3.5f, Math.round(d.pop) + "%", 9, MEDIUM, Colors.BLUE, Paint.Align.LEFT);
            label(c, bx0 - 5, mid + 4, units.degrees(d.low), 11, REGULAR, theme.muted, Paint.Align.RIGHT);
            Art.rect(c, bx0, mid - 2, bx1 - bx0, 4, 2, theme.track, 1);
            float a = (float) (bx0 + (d.low - wlo) / (whi - wlo) * (bx1 - bx0));
            float b = (float) (bx0 + (d.high - wlo) / (whi - wlo) * (bx1 - bx0));
            if (b - a < 4) b = a + 4;
            bar.setShader(new LinearGradient(a, 0, b, 0, Colors.temp(d.low), Colors.temp(d.high), Shader.TileMode.CLAMP));
            c.drawRoundRect(a, mid - 2, b, mid + 2, 2, 2, bar);
            label(c, W - M, mid + 4, units.degrees(d.high), 11, MEDIUM, theme.text, Paint.Align.RIGHT);
        }
        c.restore();
    }

    private void header(Canvas c, String s) {
        text.setLetterSpacing(.3f / 9.5f);
        label(c, M, 15, s, 9.5f, MEDIUM, theme.muted, Paint.Align.LEFT);
        text.setLetterSpacing(0);
    }

    void label(Canvas c, float x, float y, String s, float size, Typeface face, int color, Paint.Align align) {
        text.setTypeface(face);
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(align);
        if (size < 11 && s.indexOf(' ') >= 0) {
            // Small text: hinting at mdpi all but closes the gap between
            // words, so each word is placed by hand.
            String[] words = s.split(" ");
            float gap = size * .28f, total = -gap;
            text.setTextAlign(Paint.Align.LEFT);
            for (String w : words) total += text.measureText(w) + gap;
            float at = align == Paint.Align.CENTER ? x - total / 2 : align == Paint.Align.RIGHT ? x - total : x;
            for (String w : words) {
                c.drawText(w, at, y, text);
                at += text.measureText(w) + gap;
            }
        } else {
            c.drawText(s, x, y, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** How wide `label` draws `s`. */
    float width(String s, float size, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(size);
        if (size >= 11 || s.indexOf(' ') < 0) return text.measureText(s);
        float gap = size * .28f, total = -gap;
        for (String w : s.split(" ")) total += text.measureText(w) + gap;
        return total;
    }

    /** `s`, shortened with an ellipsis to fit `width`. */
    String fit(String s, float width, float size, Typeface face) {
        text.setTypeface(face);
        text.setTextSize(size);
        if (text.measureText(s) <= width) return s;
        int n = s.length();
        while (n > 1 && text.measureText(s.substring(0, n) + "…") > width) n--;
        return s.substring(0, n).trim() + "…";
    }

    void wrapped(Canvas c, String s, float cx, float y, float size, int color) {
        text.setTypeface(REGULAR);
        text.setTextSize(size);
        StringBuilder lineText = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = lineText.length() == 0 ? word : lineText + " " + word;
            if (text.measureText(next) > W - 32 && lineText.length() > 0) {
                label(c, cx, y, lineText.toString(), size, REGULAR, color, Paint.Align.CENTER);
                y += size * 1.4f;
                lineText = new StringBuilder(word);
            } else {
                lineText = new StringBuilder(next);
            }
        }
        if (lineText.length() > 0) label(c, cx, y, lineText.toString(), size, REGULAR, color, Paint.Align.CENTER);
    }

    static Typeface medium() {
        return MEDIUM;
    }

    static Typeface regular() {
        return REGULAR;
    }
}
