package com.weather.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Choosing a place: the field at the top (an EditText lies over it), the
 * places found under it, or the phone's own position and recent places
 * before anything is typed.
 */
final class LocationView extends View {

    static final float FIELD_X = 8, FIELD_Y = 8, FIELD_W = 224, FIELD_H = 30, ROW_H = 40, TOP = 46;

    static final class Row {
        final String name, detail;
        final boolean here;
        final Place place;

        Row(String name, String detail, boolean here, Place place) {
            this.name = name;
            this.detail = detail;
            this.here = here;
            this.place = place;
        }
    }

    final List<Row> rows = new ArrayList<>();
    /** -1 while the field has the d-pad. */
    int selected = -1;
    /** Said in place of rows: "Searching", "No places found". */
    String message;
    WeatherView.Theme theme = new WeatherView.Theme(true);
    private final WeatherView painter;

    LocationView(Context context, WeatherView painter) {
        super(context);
        this.painter = painter;
    }

    int fits() {
        return (int) ((getHeight() / (getWidth() / WeatherView.W) - TOP) / ROW_H);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = getWidth() / WeatherView.W;
        c.save();
        c.scale(s, s);
        WeatherView.Theme th = theme;
        c.drawColor(th.bg);
        Art.rect(c, FIELD_X, FIELD_Y, FIELD_W, FIELD_H, FIELD_H / 2, th.field, 1);
        if (selected == -1) {
            Art.stroke(c, "M" + (FIELD_X + 15) + "," + FIELD_Y + " H" + (FIELD_X + FIELD_W - 15)
                + " A15,15 0 0 1 " + (FIELD_X + FIELD_W - 15) + "," + (FIELD_Y + FIELD_H)
                + " H" + (FIELD_X + 15) + " A15,15 0 0 1 " + (FIELD_X + 15) + "," + FIELD_Y + " Z", Colors.BLUE, 1.5f, 1);
        }
        c.save();
        c.translate(18, FIELD_Y + 7);
        c.scale(.95f, .95f);
        Art.stroke(c, "M6.5,1.7 A4.8,4.8 0 1 1 6.49,1.7", th.muted, 1.8f, 1);
        Art.stroke(c, "M10,10 L14,14", th.muted, 1.8f, 1);
        c.restore();
        if (rows.isEmpty() && message != null) {
            painter.label(c, WeatherView.W / 2, TOP + 26, message, 12, WeatherView.regular(), th.muted, Paint.Align.CENTER);
        }
        int first = Math.max(0, selected - fits() + 1);
        for (int k = 0; k + first < rows.size() && k <= fits(); k++) {
            int i = k + first;
            Row r = rows.get(i);
            float y = TOP + k * ROW_H;
            boolean on = i == selected;
            if (on) Art.rect(c, 6, y, 228, 38, 8, Colors.BLUE, 1);
            c.save();
            c.translate(14, y + 11);
            Sheet.glyph(c, Sheet.Glyph.PIN, on ? 0xFFFFFFFF : r.here ? Colors.BLUE : th.faint);
            c.restore();
            painter.label(c, 38, y + 16, painter.fit(r.name, 190, 13, WeatherView.medium()), 13, WeatherView.medium(), on ? 0xFFFFFFFF : th.text, Paint.Align.LEFT);
            painter.label(c, 38, y + 30, painter.fit(r.detail, 190, 10.5f, WeatherView.regular()), 10.5f, WeatherView.regular(), on ? 0xCCFFFFFF : th.muted, Paint.Align.LEFT);
            if (!on && i + 1 != selected && i < rows.size() - 1) Art.rect(c, 38, y + 39, 194, 1, 0, th.rule, 1);
        }
        c.restore();
    }
}
