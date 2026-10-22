package com.books.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A d-pad list drawn in the style of an iOS grouped sheet: the reader's
 * settings ("Themes & Settings" in Books) and its table of contents.
 *
 * It is drawn rather than built from widgets so that it looks the same on
 * every firmware, and it takes no focus: the activity routes keys to it, so
 * there is exactly one place deciding what a key does.
 */
final class ListPanel extends View {

    static final int PLAIN = 0;
    static final int HEADER = 1;
    /** An on/off switch; Left, Right and OK flip it. */
    static final int TOGGLE = 2;
    /** A value stepped with Left and Right. */
    static final int STEPPER = 3;
    /** One of a set, ticked when chosen. */
    static final int CHOICE = 4;
    /** Opens something else; drawn with a chevron. */
    static final int LINK = 5;

    static final class Row {
        final int id;
        final int kind;
        String title;
        String value;
        boolean on;
        Typeface face;
        int depth;
        /** Drawn in the accent colour: the contents entry being read. */
        boolean current;
        /** Drawn in red: an action that cannot be undone. */
        boolean destructive;

        Row(int id, int kind, String title) {
            this.id = id;
            this.kind = kind;
            this.title = title;
        }
    }

    interface Listener {
        /** OK on a row. */
        void onActivate(Row row);

        /** Left (-1) or Right (+1) on a toggle or stepper. */
        void onAdjust(Row row, int delta);
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Typeface regular;
    private final Typeface bold;
    private Listener listener;
    private String title = "";
    /** A sheet rises from the bottom with rounded corners; otherwise it fills the screen. */
    private boolean sheet;
    private int focus;
    private int scroll;

    private final int rowHeight;
    private final int headerHeight;
    private final int titleHeight;
    private final int colorSurface;
    private final int colorRaised;
    private final int colorSeparator;
    private final int colorLabel;
    private final int colorSecondary;
    private final int colorTertiary;
    private final int colorAccent;
    private final int colorDestructive;
    private final int colorSwitchOff;

    ListPanel(Context context) {
        super(context);
        regular = Fonts.ui(context.getAssets(), false);
        bold = Fonts.ui(context.getAssets(), true);
        rowHeight = dp(29);
        headerHeight = dp(22);
        titleHeight = dp(30);
        colorSurface = color(R.color.surface);
        colorRaised = color(R.color.surface_raised);
        colorSeparator = color(R.color.separator);
        colorLabel = color(R.color.label);
        colorSecondary = color(R.color.label_secondary);
        colorTertiary = color(R.color.label_tertiary);
        colorAccent = color(R.color.accent);
        colorDestructive = color(R.color.destructive);
        colorSwitchOff = color(R.color.switch_off);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void show(String title, boolean sheet, List<Row> rows, int focus) {
        this.title = title;
        this.sheet = sheet;
        this.rows.clear();
        this.rows.addAll(rows);
        this.focus = -1;
        this.scroll = 0;
        moveTo(focus, 1);
        invalidate();
    }

    List<Row> rows() {
        return rows;
    }

    Row focused() {
        return focus >= 0 && focus < rows.size() ? rows.get(focus) : null;
    }

    /** The height a sheet of these rows wants, at most `limit`. */
    int sheetHeight(int limit) {
        int height = titleHeight + dp(6);
        for (Row row : rows) height += row.kind == HEADER ? headerHeight : rowHeight;
        return Math.min(limit, height);
    }

    // ------------------------------------------------------------------
    // Keys, as routed by the activity
    // ------------------------------------------------------------------

    void move(int delta) {
        int at = focus + delta;
        while (at >= 0 && at < rows.size() && rows.get(at).kind == HEADER) at += delta;
        if (at < 0 || at >= rows.size()) {
            // At an end: show the header above the first row, if any.
            if (delta < 0) scroll = 0;
            invalidate();
            return;
        }
        focus = at;
        reveal();
        invalidate();
    }

    void page(int delta) {
        int visible = Math.max(1, (getHeight() - titleHeight) / rowHeight - 1);
        int target = Math.max(0, Math.min(rows.size() - 1, focus + delta * visible));
        moveTo(target, delta);
        invalidate();
    }

    void activate() {
        Row row = focused();
        if (row != null && listener != null) listener.onActivate(row);
    }

    void adjust(int delta) {
        Row row = focused();
        if (row != null && listener != null && (row.kind == TOGGLE || row.kind == STEPPER)) {
            listener.onAdjust(row, delta);
        }
    }

    void refresh() {
        invalidate();
    }

    private void moveTo(int target, int direction) {
        int at = Math.max(0, Math.min(rows.size() - 1, target));
        while (at >= 0 && at < rows.size() && rows.get(at).kind == HEADER) at += direction >= 0 ? 1 : -1;
        if (at < 0 || at >= rows.size()) at = Math.max(0, Math.min(rows.size() - 1, target));
        focus = rows.isEmpty() ? -1 : at;
        reveal();
    }

    /** Scrolls so the focused row is in view, with a row of context where there is one. */
    private void reveal() {
        if (focus < 0 || getHeight() == 0) return;
        int top = top(focus);
        int bottom = top + rowHeight;
        int viewport = getHeight() - titleHeight;
        if (top - rowHeight / 2 < scroll) scroll = Math.max(0, top - rowHeight / 2);
        if (bottom + rowHeight / 2 > scroll + viewport) scroll = bottom + rowHeight / 2 - viewport;
        int max = Math.max(0, top(rows.size()) - viewport + dp(4));
        scroll = Math.max(0, Math.min(scroll, max));
        if (focus == firstSelectable()) scroll = 0;
    }

    private int firstSelectable() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind != HEADER) return i;
        return -1;
    }

    /** A row's top, in content coordinates below the title. */
    private int top(int index) {
        int y = dp(3);
        for (int i = 0; i < index && i < rows.size(); i++) y += rows.get(i).kind == HEADER ? headerHeight : rowHeight;
        return y;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reveal();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        float radius = dp(10);

        fill.setColor(colorSurface);
        if (sheet) {
            rect.set(0, 0, width, height + radius);
            canvas.drawRoundRect(rect, radius, radius, fill);
            // The grabber that says the sheet can be put away.
            fill.setColor(colorTertiary);
            rect.set(width / 2f - dp(14), dp(4), width / 2f + dp(14), dp(4) + dp(3));
            canvas.drawRoundRect(rect, dp(2), dp(2), fill);
        } else {
            canvas.drawRect(0, 0, width, height, fill);
        }

        text.setTypeface(bold);
        text.setTextSize(dp(13));
        text.setColor(colorLabel);
        text.setTextAlign(Paint.Align.CENTER);
        String heading = ellipsize(title, width - dp(24));
        canvas.drawText(heading, width / 2f, titleHeight - dp(sheet ? 9 : 10), text);
        text.setTextAlign(Paint.Align.LEFT);
        fill.setColor(colorSeparator);
        canvas.drawRect(0, titleHeight - 1, width, titleHeight, fill);

        canvas.save();
        canvas.clipRect(0, titleHeight, width, height);
        canvas.translate(0, titleHeight - scroll);
        int y = dp(3);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int h = row.kind == HEADER ? headerHeight : rowHeight;
            if (y + h >= scroll && y <= scroll + height) drawRow(canvas, row, i == focus, y, h, width);
            y += h;
        }
        canvas.restore();

        // A hint of more below, as a scroll bar would give.
        int content = y + dp(4);
        int viewport = height - titleHeight;
        if (content > viewport) {
            float span = viewport * (float) viewport / content;
            float at = titleHeight + (viewport - span) * scroll / (float) Math.max(1, content - viewport);
            fill.setColor(colorTertiary);
            rect.set(width - dp(3), at + dp(2), width - dp(1), at + span - dp(2));
            canvas.drawRoundRect(rect, dp(1), dp(1), fill);
        }
    }

    private void drawRow(Canvas canvas, Row row, boolean focused, int y, int h, int width) {
        int pad = dp(12);
        if (row.kind == HEADER) {
            text.setTypeface(regular);
            text.setTextSize(dp(10));
            text.setColor(colorSecondary);
            text.setLetterSpacing(0.06f);
            canvas.drawText(row.title.toUpperCase(), pad, y + h - dp(6), text);
            text.setLetterSpacing(0f);
            return;
        }
        if (focused) {
            fill.setColor(colorRaised);
            rect.set(dp(4), y + 1, width - dp(4), y + h - 1);
            canvas.drawRoundRect(rect, dp(7), dp(7), fill);
        }
        float baseline = y + h / 2f + dp(5) - 0.5f;

        // The right-hand side first, so the title knows how much room it has.
        float right = width - pad;
        switch (row.kind) {
            case TOGGLE:
                right = drawSwitch(canvas, row.on, right, y + h / 2f);
                break;
            case STEPPER:
                right = drawStepper(canvas, row.value, focused, right, baseline);
                break;
            case CHOICE:
                if (row.on) drawCheck(canvas, right - dp(10), y + h / 2f);
                right -= dp(16);
                break;
            case LINK:
                drawChevron(canvas, right - dp(3), y + h / 2f, focused ? colorLabel : colorTertiary);
                right -= dp(12);
                if (row.value != null) right = drawValue(canvas, row.value, right, baseline);
                break;
            default:
                if (row.value != null) right = drawValue(canvas, row.value, right, baseline);
                break;
        }

        int left = pad + row.depth * dp(10);
        text.setTypeface(row.face != null ? row.face : regular);
        text.setTextSize(dp(row.face != null ? 14 : 13));
        text.setColor(row.current ? colorAccent : row.destructive ? colorDestructive : colorLabel);
        canvas.drawText(ellipsize(row.title, right - left - dp(6)), left, baseline, text);
    }

    private float drawValue(Canvas canvas, String value, float right, float baseline) {
        text.setTypeface(regular);
        text.setTextSize(dp(12));
        text.setColor(colorSecondary);
        float w = text.measureText(value);
        canvas.drawText(value, right - w, baseline, text);
        return right - w - dp(6);
    }

    /** An iOS switch: a pill, accent when on, with a white knob. */
    private float drawSwitch(Canvas canvas, boolean on, float right, float middle) {
        float w = dp(30);
        float h = dp(18);
        rect.set(right - w, middle - h / 2, right, middle + h / 2);
        fill.setColor(on ? colorAccent : colorSwitchOff);
        canvas.drawRoundRect(rect, h / 2, h / 2, fill);
        fill.setColor(0xFFFFFFFF);
        float knob = h / 2 - dp(2);
        float cx = on ? right - h / 2 : right - w + h / 2;
        canvas.drawCircle(cx, middle, knob, fill);
        return right - w - dp(8);
    }

    /** The value between two chevrons, which brighten when the row is focused. */
    private float drawStepper(Canvas canvas, String value, boolean focused, float right, float baseline) {
        text.setTypeface(regular);
        text.setTextSize(dp(12));
        float w = Math.max(text.measureText(value), dp(14));
        float middle = baseline - dp(4);
        int chevron = focused ? colorLabel : colorTertiary;
        drawChevron(canvas, right - dp(3), middle, chevron);
        float textRight = right - dp(12);
        text.setColor(focused ? colorLabel : colorSecondary);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, textRight - w / 2, baseline, text);
        text.setTextAlign(Paint.Align.LEFT);
        float leftEdge = textRight - w - dp(12);
        drawChevronLeft(canvas, leftEdge + dp(3), middle, chevron);
        return leftEdge - dp(4);
    }

    private void drawChevron(Canvas canvas, float x, float y, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(2) * 0.8f);
        path.reset();
        path.moveTo(x - dp(4), y - dp(5));
        path.lineTo(x, y);
        path.lineTo(x - dp(4), y + dp(5));
        canvas.drawPath(path, stroke);
    }

    private void drawChevronLeft(Canvas canvas, float x, float y, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(2) * 0.8f);
        path.reset();
        path.moveTo(x + dp(4), y - dp(5));
        path.lineTo(x, y);
        path.lineTo(x + dp(4), y + dp(5));
        canvas.drawPath(path, stroke);
    }

    private void drawCheck(Canvas canvas, float x, float y) {
        stroke.setColor(colorAccent);
        stroke.setStrokeWidth(dp(2));
        path.reset();
        path.moveTo(x - dp(5), y);
        path.lineTo(x - dp(1.5f), y + dp(4));
        path.lineTo(x + dp(5), y - dp(4.5f));
        canvas.drawPath(path, stroke);
    }

    private String ellipsize(String value, float width) {
        if (value == null) return "";
        return TextUtils.ellipsize(value, text, Math.max(0, width), TextUtils.TruncateAt.END).toString();
    }

    private int color(int id) {
        return getResources().getColor(id);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
