package com.print.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The proposal's components (section 4), built in code. */
final class Parts {

    private Parts() {}

    static final int FILL = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static LinearLayout.LayoutParams line(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, WRAP, 1);
    }

    static LinearLayout column(Context c) {
        LinearLayout view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    static LinearLayout row(Context c) {
        LinearLayout view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static ImageView image(Context c, int id, int color, float size) {
        ImageView view = new ImageView(c);
        view.setImageDrawable(Look.icon(c, id, color));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int px = Look.dp(c, size);
        view.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return view;
    }

    /**
     * The 28dp nav bar: "Back" in brand blue on the left as a reminder of
     * the Back key (never focusable), the title centred, and an optional
     * indicator on the right. `back` false leaves the left empty.
     */
    static FrameLayout nav(Context c, String title, boolean back, View right) {
        FrameLayout bar = new FrameLayout(c);
        bar.setBackgroundColor(Look.BAR);
        int side = Look.dp(c, 58);
        TextView name = Look.line(c, title, 13, Look.BOLD, Look.INK);
        name.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams middle = new FrameLayout.LayoutParams(FILL, FILL);
        middle.leftMargin = side;
        middle.rightMargin = side;
        bar.addView(name, middle);
        if (back) {
            LinearLayout hint = row(c);
            hint.addView(image(c, R.drawable.ic_chev_left, Look.BLUE, 12));
            hint.addView(Look.text(c, "Back", 12, Look.SEMIBOLD, Look.BLUE));
            FrameLayout.LayoutParams left = new FrameLayout.LayoutParams(WRAP, FILL, Gravity.LEFT);
            left.leftMargin = Look.dp(c, 7);
            bar.addView(hint, left);
        }
        if (right != null) {
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(WRAP, FILL, Gravity.RIGHT);
            at.rightMargin = Look.dp(c, 8);
            bar.addView(right, at);
        }
        return bar;
    }

    /** A screen: the nav bar, a hairline, then `body` filling the rest. */
    static LinearLayout page(Context c, View nav, View body) {
        LinearLayout page = column(c);
        page.setBackgroundColor(Look.WHITE);
        page.addView(nav, line(FILL, Look.dp(c, 28)));
        page.addView(rule(c), line(FILL, 1));
        page.addView(body, new LinearLayout.LayoutParams(FILL, 0, 1));
        return page;
    }

    static View rule(Context c) {
        View rule = new View(c);
        rule.setBackgroundColor(Look.LINE);
        return rule;
    }

    /** "LOCATIONS", "IN PROGRESS": a list's section header. */
    static TextView header(Context c, String text) {
        TextView view = Look.line(c, text.toUpperCase(java.util.Locale.ROOT), 9.5f, Look.SEMIBOLD, Look.MUTE);
        view.setLetterSpacing(0.05f);
        view.setPadding(Look.dp(c, 11), Look.dp(c, 7), Look.dp(c, 11), Look.dp(c, 4));
        return view;
    }

    /** A centred muted note under a list or above a button. */
    static TextView foot(Context c, String text) {
        TextView view = Look.text(c, text, 9.5f, Look.REGULAR, Look.MUTE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Look.dp(c, 10), Look.dp(c, 8), Look.dp(c, 10), Look.dp(c, 7));
        return view;
    }

    /** A focusable list row of `height` dp with a hairline under it; children go in `content`. */
    static final class Row extends LinearLayout {
        final TextView title;
        final TextView sub;
        final LinearLayout text;
        private ImageView chevron;
        private boolean divider = true;
        private final Paint paint = new Paint();

        Row(Context c, float height) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Look.dp(c, height));
            setPadding(Look.dp(c, 11), 0, Look.dp(c, 10), 0);
            setFocusable(true);
            setBackground(new Look.RowFocus(c, Look.WHITE));
            setDefaultFocusHighlightEnabled(false);
            setWillNotDraw(false);
            paint.setColor(Look.LINE);
            text = column(c);
            title = Look.line(c, "", 12.5f, Look.SEMIBOLD, Look.INK);
            sub = Look.line(c, "", 9.5f, Look.REGULAR, Look.MUTE);
            text.addView(title, line(FILL, WRAP));
            LinearLayout.LayoutParams under = line(FILL, WRAP);
            under.topMargin = Look.dp(c, 2);
            text.addView(sub, under);
            sub.setVisibility(GONE);
        }

        /** Adds what leads the row (an icon square, a thumbnail), 9dp before the text. */
        Row lead(View view, int width, int height) {
            LinearLayout.LayoutParams lp = line(width, height);
            lp.rightMargin = Look.dp(getContext(), 9);
            addView(view, lp);
            return this;
        }

        Row titled(CharSequence title, CharSequence sub) {
            this.title.setText(title);
            this.sub.setText(sub == null ? "" : sub);
            this.sub.setVisibility(sub == null || sub.length() == 0 ? GONE : VISIBLE);
            if (text.getParent() == null) addView(text, weighted());
            return this;
        }

        /** The grey chevron that turns blue with focus: OK opens something. */
        Row chevron() {
            chevron = image(getContext(), R.drawable.ic_chev_right, Look.CHEVRON, 12);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chevron.getLayoutParams();
            lp.leftMargin = Look.dp(getContext(), 6);
            addView(chevron);
            return this;
        }

        Row trailing(View view) {
            LinearLayout.LayoutParams lp = line(WRAP, WRAP);
            lp.leftMargin = Look.dp(getContext(), 6);
            addView(view, lp);
            return this;
        }

        Row noDivider() {
            divider = false;
            invalidate();
            return this;
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (chevron != null) chevron.setImageDrawable(Look.icon(getContext(), R.drawable.ic_chev_right, isFocused() ? Look.BLUE : Look.CHEVRON));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (divider) canvas.drawRect(0, getHeight() - 1, getWidth(), getHeight(), paint);
        }
    }

    /** A setting: its label on the left and its value in brand blue on the right, with a chevron when OK opens choices. */
    static final class SettingRow {
        final Row row;
        final TextView value;

        SettingRow(Context c, String label, float height, boolean opens) {
            row = new Row(c, height);
            row.titled(label, null);
            value = Look.line(c, "", 11.5f, Look.SEMIBOLD, Look.BLUE);
            value.setMaxWidth(Look.dp(c, 120));
            row.trailing(value);
            if (opens) row.chevron();
        }
    }

    /** The icon square that leads a location row. */
    static View square(Context c, int icon) {
        FrameLayout square = new FrameLayout(c);
        square.setBackground(Look.rounded(Look.ICON_FILL, 6, c));
        ImageView glyph = image(c, icon, Look.BLUE, 14);
        square.addView(glyph, new FrameLayout.LayoutParams(Look.dp(c, 14), Look.dp(c, 14), Gravity.CENTER));
        return square;
    }

    static final int PRIMARY = Look.ButtonFace.PRIMARY;
    static final int SECONDARY = Look.ButtonFace.SECONDARY;
    static final int DANGER = Look.ButtonFace.DANGER;

    /** A button, 28dp tall plus room for its focus ring. */
    static TextView button(Context c, String label, int kind, final Runnable action) {
        TextView view = new TextView(c) {
            @Override
            protected void drawableStateChanged() {
                super.drawableStateChanged();
                if (kind == PRIMARY) setTextColor(isEnabled() ? Look.WHITE : Look.MUTE);
            }
        };
        Look.style(view, 12, Look.BOLD, kind == PRIMARY ? Look.WHITE : kind == DANGER ? Look.ERR : Look.INK);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine();
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setBackground(new Look.ButtonFace(c, kind));
        int ring = Look.dp(c, 3.5f);
        view.setPadding(ring + Look.dp(c, 4), ring, ring + Look.dp(c, 4), ring);
        view.setMinHeight(Look.dp(c, 35));
        view.setFocusable(true);
        view.setDefaultFocusHighlightEnabled(false);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /** Buttons stacked, 7dp apart, 12dp from the sides (the ring takes 3.5 of it). */
    static LinearLayout buttons(Context c, View... buttons) {
        LinearLayout column = column(c);
        int side = Look.dp(c, 12 - 3.5f);
        column.setPadding(side, 0, side, 0);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = line(FILL, Look.dp(c, 35));
            if (i > 0) lp.topMargin = Look.dp(c, 0);
            column.addView(buttons[i], lp);
        }
        return column;
    }

    /** Two buttons side by side, sharing the width. */
    static LinearLayout pair(Context c, View left, View right) {
        LinearLayout row = row(c);
        int side = Look.dp(c, 12 - 3.5f);
        row.setPadding(side, 0, side, 0);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, Look.dp(c, 35), 1);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, Look.dp(c, 35), 1);
        row.addView(left, a);
        row.addView(right, b);
        return row;
    }

    /** On is brand blue, off grey. */
    static final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean on;

        Switch(Context c, boolean on) {
            super(c);
            this.on = on;
            setLayoutParams(new LinearLayout.LayoutParams(Look.dp(c, 26), Look.dp(c, 15)));
        }

        void set(boolean value) {
            on = value;
            invalidate();
        }

        /** Its own size, 26 x 15 dp, whatever layout params a row gives it. */
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize(Look.dp(getContext(), 26), widthSpec), resolveSize(Look.dp(getContext(), 15), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float w = getWidth();
            paint.setColor(on ? Look.BLUE : 0xFFC9CCD4);
            canvas.drawRoundRect(0, 0, w, h, h / 2, h / 2, paint);
            paint.setColor(Look.WHITE);
            float r = h / 2 - Look.dp(getContext(), 2);
            canvas.drawCircle(on ? w - h / 2 : h / 2, h / 2, r, paint);
        }
    }

    /** A radio: a grey ring, or a blue ring with a blue dot for the chosen value. */
    static final class Radio extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean on;

        Radio(Context c, boolean on) {
            super(c);
            this.on = on;
            paint.setStrokeWidth(1.5f * c.getResources().getDisplayMetrics().density);
        }

        void set(boolean value) {
            on = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight());
            float stroke = paint.getStrokeWidth();
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(on ? Look.BLUE : 0xFFA9AEBA);
            canvas.drawCircle(s / 2, s / 2, s / 2 - stroke / 2, paint);
            if (on) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(s / 2, s / 2, s / 2 - stroke - Look.dp(getContext(), 1.5f), paint);
            }
        }
    }

    /** Segmented tabs: two halves in a blue outline, the selected one filled. Not focusable: Left and Right switch them. */
    static final class Tabs extends LinearLayout {
        private final TextView[] tabs;

        Tabs(Context c, String... labels) {
            super(c);
            setOrientation(HORIZONTAL);
            setBackground(Look.outlined(Look.WHITE, 5, 1, Look.BLUE, c));
            int in = Look.dp(c, 1);
            setPadding(in, in, in, in);
            tabs = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                TextView tab = Look.line(c, labels[i], 10.5f, Look.SEMIBOLD, Look.BLUE);
                tab.setGravity(Gravity.CENTER);
                tab.setPadding(0, Look.dp(c, 3), 0, Look.dp(c, 4));
                addView(tab, new LinearLayout.LayoutParams(0, WRAP, 1));
                tabs[i] = tab;
            }
        }

        void select(int index) {
            for (int i = 0; i < tabs.length; i++) {
                boolean on = i == index;
                tabs[i].setTextColor(on ? Look.WHITE : Look.BLUE);
                tabs[i].setBackground(on ? Look.rounded(Look.BLUE, 4, getContext()) : null);
            }
        }
    }

    /** A large icon in a tinted circle, for status pages. */
    static View badge(Context c, int icon, int tint, int color) {
        FrameLayout badge = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(tint);
        badge.setBackground(circle);
        ImageView glyph = image(c, icon, color, 28);
        badge.addView(glyph, new FrameLayout.LayoutParams(Look.dp(c, 28), Look.dp(c, 28), Gravity.CENTER));
        return badge;
    }

    /** A rounded pill of bold small text: "Paused · page 4 of 6", "Next". */
    static TextView pill(Context c, String text, int fill, int color) {
        TextView view = Look.line(c, text, 9.5f, Look.BOLD, color);
        view.setBackground(Look.rounded(fill, 10, c));
        view.setPadding(Look.dp(c, 8), Look.dp(c, 2), Look.dp(c, 8), Look.dp(c, 3));
        return view;
    }

    /**
     * A status page: a large icon (or picture), a headline, one or two
     * sentences, an optional pill, then its buttons.
     */
    static LinearLayout status(Context c, View art, int artSize, String headline, CharSequence message, View pill,
                               View buttons, int top) {
        LinearLayout page = column(c);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(0, Look.dp(c, top), 0, 0);
        if (art != null) page.addView(art, line(Look.dp(c, artSize), Look.dp(c, artSize)));
        TextView title = Look.text(c, headline, 15, Look.BOLD, Look.INK);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = line(FILL, WRAP);
        lp.topMargin = Look.dp(c, 10);
        lp.leftMargin = lp.rightMargin = Look.dp(c, 16);
        page.addView(title, lp);
        if (message != null && message.length() > 0) {
            TextView body = Look.text(c, message, 10.5f, Look.REGULAR, Look.MUTE);
            body.setGravity(Gravity.CENTER);
            body.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams under = line(FILL, WRAP);
            under.topMargin = Look.dp(c, 4);
            under.leftMargin = under.rightMargin = Look.dp(c, 16);
            page.addView(body, under);
        }
        if (pill != null) {
            LinearLayout.LayoutParams under = line(WRAP, WRAP);
            under.topMargin = Look.dp(c, 8);
            page.addView(pill, under);
        }
        if (buttons != null) {
            LinearLayout.LayoutParams under = line(FILL, WRAP);
            under.topMargin = Look.dp(c, 14);
            page.addView(buttons, under);
        }
        return page;
    }

    /** A thin progress bar in brand blue on grey, 0 to 100 (or -1 for a slow sweep). */
    static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int percent;

        Bar(Context c) {
            super(c);
        }

        void set(int value) {
            percent = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float w = getWidth();
            paint.setColor(0xFFE3E6EC);
            canvas.drawRoundRect(0, 0, w, h, h / 2, h / 2, paint);
            paint.setColor(Look.BLUE);
            if (percent >= 0) {
                canvas.drawRoundRect(0, 0, w * Math.min(100, percent) / 100f, h, h / 2, h / 2, paint);
                return;
            }
            float t = (System.currentTimeMillis() % 1600) / 1600f;
            float x = (w * 1.3f) * t - w * 0.3f;
            canvas.drawRoundRect(Math.max(0, x), 0, Math.min(w, x + w * 0.3f), h, h / 2, h / 2, paint);
            postInvalidateOnAnimation();
        }
    }

    /** A drawable that fills with one colour, for pieces drawn by hand. */
    static Drawable fill(int color) {
        return new android.graphics.drawable.ColorDrawable(color);
    }
}
