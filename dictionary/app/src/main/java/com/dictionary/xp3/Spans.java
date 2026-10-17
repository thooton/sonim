package com.dictionary.xp3;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;

/** The spans an entry is drawn with, as Dictionary.com sets its pages. */
final class Spans {

    private Spans() {}

    /** A typeface of our own (TypefaceSpan only takes one from API 28). */
    static final class Font extends MetricAffectingSpan {
        private final Typeface typeface;

        Font(Typeface typeface) {
            this.typeface = typeface;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setTypeface(typeface);
        }

        @Override
        public void updateMeasureState(TextPaint paint) {
            paint.setTypeface(typeface);
        }
    }

    /** A sense: its number in muted grey, hanging in the margin. */
    static final class Number implements LeadingMarginSpan {
        private final String number;
        private final int margin;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Number(int number, int margin, Typeface typeface, float size, int color) {
            this.number = String.valueOf(number);
            this.margin = margin;
            paint.setTypeface(typeface);
            paint.setTextSize(size);
            paint.setColor(color);
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return margin;
        }

        @Override
        public void drawLeadingMargin(Canvas canvas, Paint p, int x, int dir, int top, int baseline, int bottom,
                CharSequence text, int start, int end, boolean first, Layout layout) {
            if (first && ((Spanned) text).getSpanStart(this) == start) {
                canvas.drawText(number, x, baseline, paint);
            }
        }
    }

    /** An example: indented under its sense behind a pale grey rule. */
    static final class Example implements LeadingMarginSpan {
        private final int indent;
        private final int rule;
        private final int gap;
        private final Paint paint = new Paint();

        Example(int indent, int rule, int gap, int color) {
            this.indent = indent;
            this.rule = rule;
            this.gap = gap;
            paint.setColor(color);
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return indent + rule + gap;
        }

        @Override
        public void drawLeadingMargin(Canvas canvas, Paint p, int x, int dir, int top, int baseline, int bottom,
                CharSequence text, int start, int end, boolean first, Layout layout) {
            // Each example has a rule of its own, parted from the one above.
            canvas.drawRect(x + indent, first ? top + gap / 2 : top, x + indent + rule, bottom, paint);
        }
    }

    /** A synonym, as Thesaurus.com shows one: the word over a thick green rule. */
    static final class Synonym extends ReplacementSpan {
        private final int rule;
        private final int drop;
        private final int space;
        private final Paint line = new Paint();

        Synonym(int rule, int drop, int space, int color) {
            this.rule = rule;
            this.drop = drop;
            this.space = space;
            line.setColor(color);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                paint.getFontMetricsInt(fm);
                fm.descent = Math.max(fm.descent, (int) Math.ceil(paint.descent()) + drop + rule);
                fm.bottom = Math.max(fm.bottom, fm.descent);
            }
            return Math.round(paint.measureText(text, start, end)) + space;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                Paint paint) {
            canvas.drawText(text, start, end, x, y, paint);
            float width = paint.measureText(text, start, end);
            float under = y + paint.descent() + drop;
            canvas.drawRect(x, under, x + width, under + rule, line);
        }
    }
}
