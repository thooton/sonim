package com.mail.xp3;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.widget.EditText;
import com.mail.xp3.net.Address;
import java.util.List;

/**
 * Thunderbird's address pills: in To and Cc, each address finished with a
 * comma is drawn as a rounded pill holding the person's name (or address),
 * and one that isn't an address is drawn in red. The text itself is
 * untouched; only how it looks changes. {@link Pill} is also how the reader
 * draws a conversation's labels.
 */
final class Pills implements TextWatcher {

    private final int fill;
    private final int ink;
    private final int bad;
    private final float pad;

    private Pills(int fill, int ink, int bad, float pad) {
        this.fill = fill;
        this.ink = ink;
        this.bad = bad;
        this.pad = pad;
    }

    static void attach(EditText field, int fill, int ink, int bad, float pad) {
        Pills pills = new Pills(fill, ink, bad, pad);
        field.addTextChangedListener(pills);
        pills.afterTextChanged(field.getText());
    }

    @Override
    public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence text, int start, int before, int count) {}

    /** Every "address," gets a pill; what is still being typed after the last comma does not. */
    @Override
    public void afterTextChanged(Editable text) {
        for (Pill pill : text.getSpans(0, text.length(), Pill.class)) text.removeSpan(pill);
        int from = 0;
        String all = text.toString();
        while (true) {
            int comma = all.indexOf(',', from);
            if (comma < 0) break;
            int start = from;
            while (start < comma && Character.isWhitespace(all.charAt(start))) start++;
            if (start < comma) {
                String typed = all.substring(start, comma);
                String label = typed.trim();
                boolean ok = true;
                try {
                    List<Address> found = Address.typed(typed);
                    if (found.size() == 1) label = found.get(0).display();
                } catch (IllegalArgumentException e) {
                    ok = false;
                }
                text.setSpan(new Pill(label, ok ? fill : 0x00000000, ok ? ink : bad, ok ? 0 : bad, pad),
                    start, comma + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            from = comma + 1;
        }
    }

    /** Text drawn as a rounded pill in place of what it covers. */
    static final class Pill extends ReplacementSpan {

        private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final String label;
        private final int fill;
        private final int ink;
        private final int ring;
        private final float pad;

        /** `ring` is an outline colour, or 0 for none. */
        Pill(String label, int fill, int ink, int ring, float pad) {
            this.label = label;
            this.fill = fill;
            this.ink = ink;
            this.ring = ring;
            this.pad = pad;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            float size = paint.getTextSize();
            Typeface face = paint.getTypeface();
            paint.setTextSize(size * 0.9f);
            paint.setTypeface(MEDIUM);
            float width = paint.measureText(label);
            paint.setTextSize(size);
            paint.setTypeface(face);
            return Math.round(width + pad * 3);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            float size = paint.getTextSize();
            Typeface face = paint.getTypeface();
            int color = paint.getColor();
            Paint.Style style = paint.getStyle();
            paint.setTextSize(size * 0.9f);
            paint.setTypeface(MEDIUM);
            float width = paint.measureText(label);
            Paint.FontMetrics m = paint.getFontMetrics();
            float textTop = y + m.ascent;
            float textBottom = y + m.descent;
            RectF box = new RectF(x, textTop - pad / 2, x + width + pad * 2, textBottom + pad / 2);
            float radius = box.height() / 2;
            paint.setAntiAlias(true);
            if (Color.alpha(fill) > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fill);
                canvas.drawRoundRect(box, radius, radius, paint);
            }
            if (ring != 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(pad / 4);
                paint.setColor(ring);
                canvas.drawRoundRect(box, radius, radius, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ink);
            canvas.drawText(label, x + pad, y, paint);
            paint.setTextSize(size);
            paint.setTypeface(face);
            paint.setColor(color);
            paint.setStyle(style);
        }
    }
}
