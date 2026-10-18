package com.source.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.xp3.common.ui.Ui;
import java.io.File;

/**
 * How full the SD card is, in the style of Files by Google: what is free
 * of how much, a bar split into Music, Books and everything else, and what
 * Music and Books hold. On the list screen it is compact: what is free,
 * the bar. The folders are measured off the main thread, at most
 * once a minute; until then the bar shows only used and free.
 */
final class StorageCard extends LinearLayout {

    private static long music = -1;
    private static long books = -1;
    private static long measured;
    private static boolean measuring;

    private final boolean compact;
    private final TextView free;
    private final TextView legend;
    private final Bar bar;

    StorageCard(Context context, boolean compact) {
        super(context);
        this.compact = compact;
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.card_pick);
        int pad = Ui.dp(context, 12);
        setPadding(pad, Ui.dp(context, compact ? 9 : 8), pad, Ui.dp(context, 9));

        free = new TextView(context);
        free.setTextColor(context.getColor(compact ? R.color.text_muted : R.color.text));
        free.setIncludeFontPadding(false);
        free.setSingleLine(true);
        if (compact) {
            free.setTextSize(10.5f);
            free.setPadding(0, Ui.dp(context, 2), 0, 0);
        }
        addView(free);

        bar = new Bar(context);
        LayoutParams barSize = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 6));
        barSize.topMargin = Ui.dp(context, compact ? 5 : 7);
        addView(bar, barSize);

        legend = new TextView(context);
        legend.setTextColor(context.getColor(R.color.text_muted));
        legend.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_meta));
        legend.setIncludeFontPadding(false);
        legend.setPadding(0, Ui.dp(context, 6), 0, 0);
        legend.setGravity(Gravity.START);
        if (!compact) addView(legend);
        refresh();
    }

    /** Shows what is known now, and measures the folders again when that is stale. */
    void refresh() {
        long[] space = Files.space(getContext());
        Context context = getContext();
        boolean none = space[1] <= 0;
        bar.set(space[1], space[1] - space[0], Math.max(0, music), Math.max(0, books));
        if (compact) {
            free.setText(none ? "No SD card in the phone"
                : Download.human(space[0]) + " free of " + Download.human(space[1]));
        } else {
            showFree(context, space);
        }
        measure();
    }

    private void showFree(Context context, long[] space) {
        if (space[1] <= 0) {
            free.setText("No SD card in the phone");
            legend.setVisibility(GONE);
            return;
        }
        SpannableStringBuilder line = new SpannableStringBuilder(Download.human(space[0]));
        line.setSpan(new AbsoluteSizeSpan(18, true), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new Tonal.Face(Tonal.BOLD), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = line.length();
        line.append(" free of ").append(Download.human(space[1]));
        line.setSpan(new AbsoluteSizeSpan(11, true), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new ForegroundColorSpan(context.getColor(R.color.text_muted)), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        free.setText(line);

        SpannableStringBuilder key = new SpannableStringBuilder();
        if (music >= 0) {
            dot(key, R.color.accent);
            key.append("Music ").append(Download.human(music)).append("   ");
            dot(key, R.color.tertiary);
            key.append("Books ").append(Download.human(books));
        }
        legend.setText(key);
        legend.setVisibility(key.length() == 0 ? GONE : VISIBLE);
    }

    private void measure() {
        if (measuring || System.currentTimeMillis() - measured < 60000) return;
        final Context context = getContext();
        measuring = true;
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File card = Files.card(context);
            final long m = card == null ? -1 : Files.size(new File(card, Download.MUSIC_FOLDER));
            final long b = card == null ? -1 : Files.size(new File(card, Download.BOOKS_FOLDER));
            main.post(() -> {
                music = m;
                books = b;
                measured = System.currentTimeMillis();
                measuring = false;
                if (isAttachedToWindow()) refresh();
            });
        }, "storage").start();
    }

    private void dot(SpannableStringBuilder out, int color) {
        int start = out.length();
        out.append("● ");
        out.setSpan(new ForegroundColorSpan(getContext().getColor(color)), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** The bar: Music, Books, the rest of what is used, then what is free. */
    private static final class Bar extends View {
        private long total;
        private long used;
        private long music;
        private long books;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        Bar(Context context) {
            super(context);
        }

        void set(long total, long used, long music, long books) {
            this.total = total;
            this.used = used;
            this.music = music;
            this.books = books;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float radius = h / 2;
            rect.set(0, 0, w, h);
            paint.setColor(getContext().getColor(R.color.track));
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (total <= 0) return;
            long rest = Math.max(0, used - music - books);
            long[] parts = {music, books, rest};
            int[] colors = {R.color.accent, R.color.tertiary, R.color.outline};
            canvas.save();
            clip.reset();
            clip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            float x = 0;
            float gap = Math.max(1, getResources().getDisplayMetrics().density);
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] <= 0) continue;
                float width = w * parts[i] / total;
                paint.setColor(getContext().getColor(colors[i]));
                canvas.drawRect(x, 0, Math.min(w, x + width), h, paint);
                x += width + gap;
            }
            canvas.restore();
        }
    }
}
