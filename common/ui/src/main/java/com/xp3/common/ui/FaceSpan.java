package com.xp3.common.ui;

import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

/** A run of text in another typeface: TypefaceSpan only takes a Typeface from API 28. */
public final class FaceSpan extends MetricAffectingSpan {
    private final Typeface face;

    public FaceSpan(Typeface face) {
        this.face = face;
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        paint.setTypeface(face);
    }

    @Override
    public void updateDrawState(TextPaint paint) {
        paint.setTypeface(face);
    }
}
