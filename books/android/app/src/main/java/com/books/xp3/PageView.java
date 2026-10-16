package com.books.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * The page: the book's text, edge to edge on black, and nothing else.
 *
 * OK brings up the controls Books shows when the page is tapped, drawn over
 * the text rather than pushing it about: the chapter's name at the top, and
 * at the bottom how far through the chapter and the book the page is.
 */
final class PageView extends View {

    interface SizeListener {
        void onPageSize(int width, int height);
    }

    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint();
    private final Typeface regular;
    private final Typeface bold;
    private SizeListener sizeListener;

    private Pages pages;
    private int page;
    private int textColor;
    private int dimColor;
    private String message;

    private boolean chrome;
    private String chapter = "";
    private String detail = "";
    private String percent = "";
    private float progress;
    private int accent;
    private int track;

    PageView(Context context) {
        super(context);
        regular = Fonts.ui(context.getAssets(), false);
        bold = Fonts.ui(context.getAssets(), true);
        accent = getResources().getColor(R.color.accent);
        track = getResources().getColor(R.color.separator);
        setBackgroundColor(getResources().getColor(R.color.page));
    }

    void setSizeListener(SizeListener listener) {
        sizeListener = listener;
    }

    void setColors(int text, int dim) {
        textColor = text;
        dimColor = dim;
        invalidate();
    }

    void showPage(Pages pages, int page) {
        this.pages = pages;
        this.page = page;
        this.message = null;
        invalidate();
    }

    /** A line in the middle of an empty page: "Opening..." or what went wrong. */
    void showMessage(String message) {
        this.pages = null;
        this.message = message;
        invalidate();
    }

    void setChrome(boolean visible) {
        chrome = visible;
        invalidate();
    }

    boolean chrome() {
        return chrome;
    }

    void setStatus(String chapter, String detail, String percent, float progress) {
        this.chapter = chapter == null ? "" : chapter;
        this.detail = detail == null ? "" : detail;
        this.percent = percent == null ? "" : percent;
        this.progress = progress;
        if (chrome) invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (sizeListener != null && w > 0 && h > 0) sizeListener.onPageSize(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (pages != null) {
            pages.draw(canvas, page, textColor, dimColor);
        } else if (message != null) {
            label.setTypeface(regular);
            label.setTextSize(dp(13));
            label.setColor(dimColor);
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(ellipsize(message, width - dp(20)), width / 2f, height / 2f, label);
            label.setTextAlign(Paint.Align.LEFT);
        }
        if (chrome) drawChrome(canvas, width, height);
    }

    private void drawChrome(Canvas canvas, int width, int height) {
        int bar = dp(24);
        int fade = dp(14);
        int black = 0xFF000000;

        // Top: the chapter, over a band that fades into the page.
        fill.setShader(null);
        fill.setColor(black);
        canvas.drawRect(0, 0, width, bar, fill);
        fill.setShader(new LinearGradient(0, bar, 0, bar + fade, black, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, bar, width, bar + fade, fill);

        label.setTypeface(bold);
        label.setTextSize(dp(11));
        label.setColor(textColor);
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(ellipsize(chapter, width - dp(24)), width / 2f, dp(16), label);

        // Bottom: progress through the book, then pages left in the chapter.
        int bottomBar = dp(30);
        fill.setShader(new LinearGradient(0, height - bottomBar - fade, 0, height - bottomBar, 0x00000000, black,
            Shader.TileMode.CLAMP));
        canvas.drawRect(0, height - bottomBar - fade, width, height - bottomBar, fill);
        fill.setShader(null);
        fill.setColor(black);
        canvas.drawRect(0, height - bottomBar, width, height, fill);

        int lineY = height - bottomBar + dp(6);
        int left = dp(12);
        int right = width - dp(12);
        fill.setColor(track);
        canvas.drawRect(left, lineY, right, lineY + dp(2), fill);
        fill.setColor(accent);
        float at = left + (right - left) * Math.max(0f, Math.min(1f, progress));
        canvas.drawRect(left, lineY, at, lineY + dp(2), fill);
        canvas.drawCircle(at, lineY + dp(1), dp(3), fill);

        label.setTypeface(regular);
        label.setTextSize(dp(10));
        label.setColor(dimColor);
        float baseline = height - dp(7);
        label.setTextAlign(Paint.Align.LEFT);
        float percentWidth = label.measureText(percent);
        canvas.drawText(ellipsize(detail, right - left - percentWidth - dp(8)), left, baseline, label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(percent, right, baseline, label);
        label.setTextAlign(Paint.Align.LEFT);
    }

    private String ellipsize(String value, float width) {
        return TextUtils.ellipsize(value, label, Math.max(0, width), TextUtils.TruncateAt.END).toString();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
