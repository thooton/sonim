package com.print.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The Keypad Grid proposal's visual system (see AGENTS.md): its palette, its
 * one typeface in three weights, its type scale, and the focus styles it
 * draws for rows, buttons, tiles and fields. Sizes are the proposal's pixels,
 * which are dp on the XP3's mdpi screen.
 */
final class Look {

    private Look() {}

    // Colour.
    static final int BLUE = 0xFF1A57D6;
    static final int NAVY = 0xFF11298A;
    static final int BANNER_FROM = 0xFF0F2584;
    static final int BANNER_TO = 0xFF1637A8;
    static final int INK = 0xFF1A1C22;
    static final int MUTE = 0xFF6F7380;
    static final int LINE = 0xFFE3E4E8;
    static final int SEL = 0xFFE3EDFD;
    static final int GREY = 0xFFF3F4F6;
    static final int PREVIEW = 0xFFE7E8EB;
    static final int BAR = 0xFFFAFAFB;
    static final int OK = 0xFF1B9E5A;
    static final int WARN = 0xFFD98200;
    static final int WARN_TEXT = 0xFF9A5B00;
    static final int ERR = 0xFFD23B3B;
    static final int WHITE = 0xFFFFFFFF;
    static final int CHEVRON = 0xFFA0A4AE;
    static final int ICON_FILL = 0xFFEEF2FD;
    static final int READY_DOT = 0xFF4ADE80;

    /** The six home tiles, top and bottom of each one's gradient, stepping from indigo (1) to teal (6). */
    static final int[][] TILES = {
        {0xFF5A62CF, 0xFF4F5CC8}, {0xFF4C6AD3, 0xFF4570CF}, {0xFF3F7AD8, 0xFF3882D5},
        {0xFF348AD6, 0xFF2E92D0}, {0xFF2D98CC, 0xFF29A0C2}, {0xFF28A2BF, 0xFF24A9B0},
    };

    private static Typeface regular;
    private static Typeface semibold;
    private static Typeface bold;

    /** Source Sans 3, bundled (OFL, ../OFL.txt). */
    static void load(Context context) {
        if (regular != null) return;
        regular = context.getResources().getFont(R.font.source_sans_regular);
        semibold = context.getResources().getFont(R.font.source_sans_semibold);
        bold = context.getResources().getFont(R.font.source_sans_bold);
    }

    static final int REGULAR = 0;
    static final int SEMIBOLD = 1;
    static final int BOLD = 2;

    static Typeface face(int weight) {
        return weight == BOLD ? bold : weight == SEMIBOLD ? semibold : regular;
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, CharSequence value, float size, int weight, int color) {
        TextView view = new TextView(context);
        style(view, size, weight, color);
        view.setText(value);
        return view;
    }

    static void style(TextView view, float size, int weight, int color) {
        view.setTypeface(face(weight));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
    }

    /** One line, cut with an ellipsis at the end. */
    static TextView line(Context context, CharSequence value, float size, int weight, int color) {
        TextView view = text(context, value, size, weight, color);
        view.setSingleLine();
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    static Drawable icon(Context context, int id, int color) {
        Drawable icon = context.getDrawable(id).mutate();
        icon.setTint(color);
        return icon;
    }

    static GradientDrawable rounded(int fill, float radius, Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    static GradientDrawable outlined(int fill, float radius, float stroke, int strokeColor, Context context) {
        GradientDrawable shape = rounded(fill, radius, context);
        shape.setStroke(Math.max(1, dp(context, stroke)), strokeColor);
        return shape;
    }

    /**
     * A drawable lit while its view is focused or pressed, which it keeps in
     * `on`. Opaque unless it says otherwise.
     */
    abstract static class Face extends Drawable {
        boolean on;

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean focused = false;
            for (int each : state) {
                if (each == android.R.attr.state_focused || each == android.R.attr.state_pressed) focused = true;
            }
            if (focused == on) return false;
            on = focused;
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter filter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /**
     * A list row: white at rest; focused, the focus fill with a 3dp brand
     * blue edge on the left.
     */
    static final class RowFocus extends Face {
        private final Paint paint = new Paint();
        private final int edge;
        private final int rest;

        RowFocus(Context context, int rest) {
            this.edge = dp(context, 3);
            this.rest = rest;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setColor(on ? SEL : rest);
            canvas.drawRect(b, paint);
            if (on) {
                paint.setColor(BLUE);
                canvas.drawRect(b.left, b.top, b.left + edge, b.bottom, paint);
            }
        }
    }

    /**
     * A button. Its body is inset 3.5dp from its bounds to leave room for
     * the focus ring, which the proposal draws outside the body: 2dp of white,
     * then 1.5dp of blue.
     */
    static final class ButtonFace extends Face {
        static final int PRIMARY = 0;
        static final int SECONDARY = 1;
        static final int DANGER = 2;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;
        private final float ring;
        private final float radius;
        private final float density;
        private boolean enabled = true;

        ButtonFace(Context context, int kind) {
            this.kind = kind;
            this.density = context.getResources().getDisplayMetrics().density;
            this.ring = 3.5f * density;
            this.radius = 4 * density;
            stroke.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean focused = false;
            boolean isEnabled = false;
            for (int each : state) {
                if (each == android.R.attr.state_focused || each == android.R.attr.state_pressed) focused = true;
                if (each == android.R.attr.state_enabled) isEnabled = true;
            }
            if (focused == on && isEnabled == enabled) return false;
            on = focused;
            enabled = isEnabled;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            RectF body = new RectF(b.left + ring, b.top + ring, b.right - ring, b.bottom - ring);
            if (on && enabled) {
                stroke.setColor(BLUE);
                stroke.setStrokeWidth(1.5f * density);
                float out = 2.75f * density;
                canvas.drawRoundRect(new RectF(body.left - out, body.top - out, body.right + out, body.bottom + out),
                    radius + out, radius + out, stroke);
            }
            if (kind == PRIMARY && enabled) {
                fill.setColor(BLUE);
                canvas.drawRoundRect(body, radius, radius, fill);
                return;
            }
            fill.setColor(WHITE);
            canvas.drawRoundRect(body, radius, radius, fill);
            stroke.setStrokeWidth(density);
            stroke.setColor(!enabled ? LINE : kind == DANGER ? 0xFFE8B9B9 : 0xFFB9BDC8);
            float half = density / 2;
            canvas.drawRoundRect(new RectF(body.left + half, body.top + half, body.right - half, body.bottom - half),
                radius, radius, stroke);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * A home tile: a top-to-bottom gradient; focused, slightly brighter, with
     * a 2dp navy ring at its edge and a 2dp white ring inside that.
     */
    static final class TileFace extends Face {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int top;
        private final int bottom;
        private final float width;

        TileFace(Context context, int top, int bottom) {
            this.top = top;
            this.bottom = bottom;
            this.width = 2 * context.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(width);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            int from = on ? brighter(top) : top;
            int to = on ? brighter(bottom) : bottom;
            paint.setShader(new LinearGradient(0, b.top, 0, b.bottom, from, to, Shader.TileMode.CLAMP));
            canvas.drawRect(b, paint);
            if (!on) return;
            float half = width / 2;
            ring.setColor(NAVY);
            canvas.drawRect(b.left + half, b.top + half, b.right - half, b.bottom - half, ring);
            ring.setColor(WHITE);
            canvas.drawRect(b.left + width + half, b.top + width + half, b.right - width - half, b.bottom - width - half, ring);
        }

        private static int brighter(int color) {
            int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * 1.12f));
            int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * 1.12f));
            int b = Math.min(255, Math.round((color & 0xFF) * 1.12f));
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    /** The printer banner: a navy gradient left to right; focused, a white inner ring. */
    static final class BannerFace extends Face {
        private final Paint paint = new Paint();
        private final Paint ring = new Paint();
        private final float width;

        BannerFace(Context context) {
            width = 2 * context.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(width);
            ring.setColor(WHITE);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setShader(new LinearGradient(b.left, 0, b.right, 0, BANNER_FROM, BANNER_TO, Shader.TileMode.CLAMP));
            canvas.drawRect(b, paint);
            if (on) {
                float half = width / 2;
                canvas.drawRect(b.left + half, b.top + half, b.right - half, b.bottom - half, ring);
            }
        }
    }

    /** A number box (page numbers, address parts): grey edge; focused, a blue edge and a soft halo. */
    static Drawable box(Context context, boolean on) {
        // The halo sits in a 2dp margin every box keeps, so focus doesn't move the text.
        GradientDrawable halo = on ? outlined(0xFFDCE7FD, 5, 0, 0, context) : rounded(0, 5, context);
        GradientDrawable edge = on ? outlined(WHITE, 3, 1.5f, BLUE, context) : outlined(WHITE, 3, 1, 0xFFB9BDC8, context);
        android.graphics.drawable.LayerDrawable both = new android.graphics.drawable.LayerDrawable(new Drawable[] {halo, edge});
        int in = dp(context, 2);
        both.setLayerInset(1, in, in, in, in);
        return both;
    }
}
