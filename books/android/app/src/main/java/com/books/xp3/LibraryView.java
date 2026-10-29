package com.books.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import com.books.xp3.book.Run;
import com.books.xp3.shelf.Shelf;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The library, as Books lists it: a large "Library" title, then each book
 * with its cover, title, author and how far into it you are.
 *
 * Drawn rather than built from a ListView so covers, badges and focus look
 * the same on every firmware; the activity routes the d-pad to it.
 */
final class LibraryView extends View {

    /** Muted cloth colours for books that have no cover of their own. */
    private static final int[] CLOTH = {
        0xFF2E4A62, 0xFF6B2E3A, 0xFF2F5D50, 0xFF7A5A2E, 0xFF4A3B6B, 0xFF4F5B63, 0xFF8C4A2F,
    };

    private final List<Shelf.Entry> books = new ArrayList<Shelf.Entry>();
    private final Map<String, Bitmap> covers = new HashMap<String, Bitmap>();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Rect dest = new Rect();
    private final Typeface regular;
    private final Typeface bold;
    private final Typeface coverFace;

    private int focus;
    private int scroll;
    private String message;
    /** A line in the header, in place of the book count: what the last action did. */
    private String notice;

    final int coverWidth;
    final int coverHeight;
    private final int rowHeight;
    private final int headerHeight;
    private final int colorRaised;
    private final int colorLabel;
    private final int colorSecondary;
    private final int colorTertiary;
    private final int colorAccent;
    private final int colorHairline;

    LibraryView(Context context) {
        super(context);
        regular = Fonts.ui(context.getAssets(), false);
        bold = Fonts.ui(context.getAssets(), true);
        coverFace = Fonts.face(context.getAssets(), Fonts.CHARTER, Run.BOLD);
        coverWidth = dp(38);
        coverHeight = dp(57);
        rowHeight = dp(66);
        headerHeight = dp(40);
        colorRaised = color(R.color.surface_raised);
        colorLabel = color(R.color.label);
        colorSecondary = color(R.color.label_secondary);
        colorTertiary = color(R.color.label_tertiary);
        colorAccent = color(R.color.accent);
        colorHairline = color(R.color.hairline);
        setBackgroundColor(color(R.color.page));
    }

    /**
     * Replaces the list, keeping the highlight on the same book if it is
     * still there (a book just read has moved to the top), else on the row
     * where it was.
     */
    void setBooks(List<Shelf.Entry> list) {
        String kept = focused() != null ? focused().path : null;
        books.clear();
        books.addAll(list);
        focus = Math.max(0, Math.min(focus, books.size() - 1));
        if (kept != null) {
            for (int i = 0; i < books.size(); i++) {
                if (books.get(i).path.equals(kept)) focus = i;
            }
        }
        message = null;
        reveal();
        invalidate();
    }

    /** A message in place of the list, when there is no list to show. */
    void setMessage(String message) {
        this.message = message;
        invalidate();
    }

    /** Shows `notice` in the header until it is replaced or cleared with null. */
    void setNotice(String notice) {
        this.notice = notice;
        invalidate();
    }

    boolean hasBooks() {
        return !books.isEmpty() && message == null;
    }

    void setCover(String path, Bitmap bitmap) {
        covers.put(path, bitmap);
        invalidate();
    }

    boolean hasCover(String path) {
        return covers.containsKey(path);
    }

    Shelf.Entry focused() {
        return focus >= 0 && focus < books.size() ? books.get(focus) : null;
    }

    void move(int delta) {
        if (books.isEmpty()) return;
        focus = Math.max(0, Math.min(books.size() - 1, focus + delta));
        reveal();
        invalidate();
    }

    private void reveal() {
        if (getHeight() == 0) return;
        int top = focus * rowHeight;
        int viewport = getHeight() - headerHeight;
        if (top < scroll) scroll = top;
        if (top + rowHeight > scroll + viewport) scroll = top + rowHeight - viewport;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, books.size() * rowHeight - viewport)));
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
        int pad = dp(10);

        text.setTypeface(bold);
        text.setTextSize(dp(22));
        text.setColor(colorLabel);
        canvas.drawText("Library", pad, dp(30), text);
        // On the right: what the last action did, else how many books there are.
        String line = notice != null ? notice : !hasBooks() ? null : books.size() == 1 ? "1 book" : books.size() + " books";
        if (line != null) {
            float left = pad + text.measureText("Library") + dp(12);
            text.setTypeface(regular);
            text.setTextSize(dp(11));
            text.setColor(notice != null ? colorAccent : colorSecondary);
            line = ellipsize(line, width - pad - left);
            canvas.drawText(line, width - pad - text.measureText(line), dp(29), text);
        }

        if (!hasBooks()) {
            drawMessage(canvas, width, height);
            return;
        }

        canvas.save();
        canvas.clipRect(0, headerHeight, width, height);
        canvas.translate(0, headerHeight - scroll);
        int first = Math.max(0, scroll / rowHeight);
        int last = Math.min(books.size() - 1, (scroll + height) / rowHeight);
        for (int i = first; i <= last; i++) drawRow(canvas, books.get(i), i == focus, i * rowHeight, width);
        canvas.restore();
    }

    private void drawRow(Canvas canvas, Shelf.Entry book, boolean focused, int y, int width) {
        int pad = dp(10);
        if (focused) {
            fill.setColor(colorRaised);
            rect.set(dp(4), y + dp(1), width - dp(4), y + rowHeight - dp(1));
            canvas.drawRoundRect(rect, dp(8), dp(8), fill);
        } else {
            fill.setColor(colorHairline);
            canvas.drawRect(pad + coverWidth + dp(10), y + rowHeight - 1, width, y + rowHeight, fill);
        }

        int coverTop = y + (rowHeight - coverHeight) / 2;
        dest.set(pad, coverTop, pad + coverWidth, coverTop + coverHeight);
        Bitmap cover = covers.get(book.path);
        if (cover != null) canvas.drawBitmap(cover, null, dest, fill);
        else drawCloth(canvas, book, dest);

        int x = dest.right + dp(10);
        int room = width - x - dp(10);
        text.setTypeface(bold);
        text.setTextSize(dp(13));
        text.setColor(colorLabel);
        String title = book.displayTitle();
        int fits = text.breakText(title, true, room, null);
        float baseline = coverTop + dp(12);
        if (fits >= title.length()) {
            canvas.drawText(title, x, baseline, text);
        } else {
            // Two lines at most, broken at a space where there is one.
            int cut = title.lastIndexOf(' ', fits);
            if (cut <= 0) cut = fits;
            canvas.drawText(title.substring(0, cut), x, baseline, text);
            baseline += dp(15);
            canvas.drawText(ellipsize(title.substring(cut).trim(), room), x, baseline, text);
        }

        text.setTypeface(regular);
        text.setTextSize(dp(11));
        text.setColor(colorSecondary);
        if (book.author != null) {
            baseline += dp(14);
            canvas.drawText(ellipsize(book.author, room), x, baseline, text);
        }

        float statusLine = coverTop + coverHeight - dp(2);
        if (book.isNew()) {
            drawBadge(canvas, "NEW", x, statusLine);
        } else if (book.finished()) {
            text.setTextSize(dp(10));
            text.setColor(colorTertiary);
            canvas.drawText("FINISHED", x, statusLine, text);
        } else {
            // A thin bar with the percentage beside it.
            int barWidth = Math.min(dp(60), room - dp(36));
            float barY = statusLine - dp(4);
            fill.setColor(colorTertiary);
            rect.set(x, barY - dp(1), x + barWidth, barY + dp(1));
            canvas.drawRoundRect(rect, dp(1), dp(1), fill);
            fill.setColor(colorAccent);
            rect.set(x, barY - dp(1), x + Math.max(dp(2), barWidth * book.percent() / 100f), barY + dp(1));
            canvas.drawRoundRect(rect, dp(1), dp(1), fill);
            text.setTextSize(dp(10));
            text.setColor(colorSecondary);
            canvas.drawText(book.percent() + "%", x + barWidth + dp(6), statusLine, text);
        }
    }

    private void drawBadge(Canvas canvas, String label, float x, float baseline) {
        text.setTypeface(bold);
        text.setTextSize(dp(9));
        text.setLetterSpacing(0.08f);
        float w = text.measureText(label);
        fill.setColor(0x33FF9F0A);
        rect.set(x, baseline - dp(10), x + w + dp(10), baseline + dp(3));
        canvas.drawRoundRect(rect, dp(7), dp(7), fill);
        text.setColor(colorAccent);
        canvas.drawText(label, x + dp(5), baseline, text);
        text.setLetterSpacing(0f);
    }

    /** A plain cloth binding: a colour from the title, its initial, and a rule. */
    private void drawCloth(Canvas canvas, Shelf.Entry book, Rect at) {
        String title = book.displayTitle();
        fill.setColor(CLOTH[(title.hashCode() & 0x7FFFFFFF) % CLOTH.length]);
        canvas.drawRect(at, fill);
        fill.setColor(0x26000000);
        canvas.drawRect(at.left, at.top, at.left + dp(3), at.bottom, fill);
        String initial = title.length() == 0 ? "?" : title.substring(0, title.offsetByCodePoints(0, 1)).toUpperCase();
        text.setTypeface(coverFace);
        text.setTextSize(dp(22));
        text.setColor(0xE6FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        float cx = at.left + dp(2) + (at.width() - dp(2)) / 2f;
        canvas.drawText(initial, cx, at.top + at.height() * 0.52f, text);
        text.setTextAlign(Paint.Align.LEFT);
        fill.setColor(0x80FFFFFF);
        canvas.drawRect(cx - dp(8), at.top + at.height() * 0.66f, cx + dp(8), at.top + at.height() * 0.66f + 1, fill);
    }

    private void drawMessage(Canvas canvas, int width, int height) {
        if (message == null) return;
        int pad = dp(16);
        text.setTypeface(bold);
        text.setTextSize(dp(15));
        text.setColor(colorLabel);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(ellipsize(message, width - 2 * pad), width / 2f, height * 0.42f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private String ellipsize(String value, float width) {
        return TextUtils.ellipsize(value, text, Math.max(0, width), TextUtils.TruncateAt.END).toString();
    }

    private int color(int id) {
        return getContext().getColor(id);
    }

    private int dp(float value) {
        return Ui.dp(getContext(), value);
    }
}
