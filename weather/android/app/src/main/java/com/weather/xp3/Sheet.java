package com.weather.xp3;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * A menu: a sheet up from the foot of the screen over a dimmed forecast,
 * with CARROT's blue on the row under the d-pad. Up and Down move, OK picks.
 */
final class Sheet {

    enum Glyph { NONE, PIN, CHART, UNITS, THEME, REFRESH }

    static final class Row {
        final Glyph glyph;
        final String label, value;
        final boolean check;
        final Runnable pick;

        Row(Glyph glyph, String label, String value, boolean check, Runnable pick) {
            this.glyph = glyph;
            this.label = label;
            this.value = value;
            this.check = check;
            this.pick = pick;
        }
    }

    final String title;
    final List<Row> rows = new ArrayList<>();
    final float rowH;
    int selected;
    /** The first row in view, when there are more than fit. */
    private int first;

    Sheet(String title, float rowH) {
        this.title = title;
        this.rowH = rowH;
    }

    Sheet add(Glyph glyph, String label, String value, Runnable pick) {
        rows.add(new Row(glyph, label, value, false, pick));
        return this;
    }

    Sheet option(String label, boolean check, Runnable pick) {
        rows.add(new Row(Glyph.NONE, label, null, check, pick));
        if (check) selected = rows.size() - 1;
        return this;
    }

    void move(int by) {
        selected = Math.max(0, Math.min(rows.size() - 1, selected + by));
    }

    void pick() {
        rows.get(selected).pick.run();
    }

    void draw(Canvas c, WeatherView v, float height) {
        WeatherView.Theme th = v.theme;
        float W = WeatherView.W;
        int fits = (int) ((height - 40 - 24) / rowH);
        int shown = Math.min(rows.size(), fits);
        if (selected < first) first = selected;
        if (selected >= first + shown) first = selected - shown + 1;
        float h = 34 + shown * rowH + 6;
        float y0 = height - h;
        Art.rect(c, 0, 0, W, height, 0, 0xFF000000, .45f);
        Art.rect(c, 0, y0, W, h + 12, 12, th.sheet, 1);
        Art.rect(c, 108, y0 + 6, 24, 3, 1.5f, th.faint, 1);
        v.label(c, 14, y0 + 27, v.fit(title, W - 28, 13, WeatherView.medium()), 13, WeatherView.medium(), th.text, Paint.Align.LEFT);
        for (int k = 0; k < shown; k++) {
            int i = first + k;
            Row r = rows.get(i);
            float y = y0 + 34 + k * rowH;
            boolean on = i == selected;
            if (on) Art.rect(c, 6, y, 228, rowH, 8, Colors.BLUE, 1);
            int fg = on ? 0xFFFFFFFF : th.text;
            int sub = on ? 0xCCFFFFFF : th.muted;
            float x = 14;
            if (r.glyph != Glyph.NONE) {
                c.save();
                c.translate(14, y + rowH / 2 - 8);
                glyph(c, r.glyph, on ? 0xFFFFFFFF : Colors.BLUE);
                c.restore();
                x = 38;
            }
            v.label(c, x, y + rowH / 2 + 4.5f, r.label, 12.5f, WeatherView.regular(), fg, Paint.Align.LEFT);
            if (r.value != null) v.label(c, 226, y + rowH / 2 + 4.5f, r.value, 11.5f, WeatherView.regular(), sub, Paint.Align.RIGHT);
            if (r.check) Art.stroke(c, "M214," + (y + rowH / 2) + " l4,4 l8,-9", on ? 0xFFFFFFFF : Colors.BLUE, 2, 1);
        }
        // More below or above: a fade says so.
        if (first > 0) Art.rect(c, 108, y0 + 30, 24, 2, 1, th.faint, 1);
        if (first + shown < rows.size()) Art.rect(c, 108, height - 4, 24, 2, 1, th.faint, 1);
    }

    /** The menu's small icons, 16 square. */
    static void glyph(Canvas c, Glyph g, int color) {
        switch (g) {
            case PIN:
                Art.fill(c, "M8,1.5 A5.5,5.5 0 0 0 2.5,7 C2.5,11 8,15 8,15 C8,15 13.5,11 13.5,7 A5.5,5.5 0 0 0 8,1.5 Z", color);
                Art.circle(c, 8, 7, 2, 0xFFFFFFFF, .9f);
                break;
            case CHART:
                Art.stroke(c, "M1.5,12 L5.5,7 L9,9.5 L14.5,3", color, 2, 1);
                Art.rect(c, 1, 13.6f, 14, 1.6f, .8f, color, 1);
                break;
            case UNITS:
                Art.stroke(c, "M8,1.5 A6.5,6.5 0 1 1 7.99,1.5", color, 1.6f, 1);
                Art.stroke(c, "M6.6,5.6 A1.6,1.6 0 1 1 6.59,5.6", color, 1.4f, 1);
                Art.stroke(c, "M10.2,7.5 V11.2", color, 1.4f, 1);
                break;
            case THEME:
                Art.stroke(c, "M8,1.5 A6.5,6.5 0 1 1 7.99,1.5", color, 1.6f, 1);
                Art.fill(c, "M8,1.5 A6.5,6.5 0 0 1 8,14.5 Z", color);
                break;
            case REFRESH:
                Art.stroke(c, "M13,8 A5,5 0 1 1 11.3,4.2", color, 1.8f, 1);
                Art.fill(c, "M9.6,1.6 L13.6,3.8 L10.4,6.8 Z", color);
                break;
            default:
        }
    }
}
