package com.musica.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.MaskFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * Now Playing and its lyrics (the mockup's b-now, b-lyrics and b-break), as
 * one view so that opening the lyrics can shrink the cover into the corner
 * as Apple's does. The background is the cover, blurred to colour alone.
 *
 * In the lyrics only the line being sung is bright; the others fade, and
 * blur two or more lines away. The page follows the song unless a line has
 * been picked with Up or Down, which shows its time until OK plays from it or
 * a few seconds pass.
 */
final class PlayerView extends View {

    /** How long a picked line waits for OK before the lyrics follow the song again. */
    static final long PICK_WAIT = 5000;

    private final Look look;
    private final Art art;
    private final Player player;

    private final TextPaint title;
    private final TextPaint artist;
    private final TextPaint times;
    private final TextPaint lyric;
    private final TextPaint chip;
    private final TextPaint emptyTitle;
    private final TextPaint emptyText;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final MaskFilter soft;

    /** 0 is Now Playing, 1 the lyrics; in between while one turns into the other. */
    private float t;
    private float tFrom;
    private float tTo;
    private long tStart;

    private String backdropFor;
    private Bitmap backdrop;
    private Bitmap oldBackdrop;
    private long backdropStart;

    private Lyrics laidOut;
    private int laidWidth;
    private StaticLayout[] layouts = new StaticLayout[0];
    private float[] tops = new float[0];
    private float[] glow = new float[0];
    private long lastFrame;
    private float scroll;
    private float scrollFrom;
    private float scrollTo;
    private long scrollStart;

    private int pick = -1;
    private long pickedAt;
    /** Where a held Left or Right has seeked to; -1 when not held. */
    private long scrub = -1;

    PlayerView(Context context) {
        super(context);
        look = Look.get(context);
        art = Art.get(context);
        player = Player.get(context);
        title = look.paint(look.w650, 14.5f, 0xFFFFFFFF, -0.01f);
        artist = look.paint(look.w400, 12f, 0x9EFFFFFF, 0);
        times = look.paint(look.w500, 8.5f, 0x8CFFFFFF, 0);
        times.setFontFeatureSettings("tnum");
        lyric = look.paint(look.w750, 17f, 0xFFFFFFFF, -0.015f);
        chip = look.paint(look.w750, 9f, 0xFF000000, 0);
        chip.setFontFeatureSettings("tnum");
        emptyTitle = look.paint(look.w650, 13f, 0xFFFFFFFF, 0);
        emptyText = look.paint(look.w400, 10.5f, 0x9EFFFFFF, 0);
        soft = new BlurMaskFilter(look.px(1.4f), BlurMaskFilter.Blur.NORMAL);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        // Blurred lines and the cover's shadow need the software pipeline on 8.1.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
    }

    // ---- Modes

    boolean lyricsShown() {
        return tTo == 1;
    }

    void showLyrics(boolean on, boolean animate) {
        float to = on ? 1 : 0;
        pick = -1;
        if (!animate) {
            t = tFrom = tTo = to;
            tStart = 0;
        } else if (to != tTo) {
            tFrom = t;
            tTo = to;
            tStart = SystemClock.uptimeMillis();
        }
        snapLyrics();
        invalidate();
    }

    /** Jumps the lyrics to where the song is, without scrolling there. */
    void snapLyrics() {
        layout();
        int focus = focusRow();
        scroll = scrollTo = focus >= 0 && focus < tops.length ? tops[focus] : 0;
        scrollStart = 0;
        int cur = player.lyrics().at(position());
        for (int i = 0; i < glow.length; i++) glow[i] = i == cur ? 1 : 0;
    }

    long position() {
        return scrub >= 0 ? scrub : player.position();
    }

    void setScrub(long at) {
        scrub = at;
        invalidate();
    }

    // ---- Picking a line

    boolean picking() {
        if (pick >= 0 && SystemClock.uptimeMillis() - pickedAt > PICK_WAIT) pick = -1;
        return pick >= 0;
    }

    /** Moves the picked line up or down a line (starting from the one being sung). */
    void movePick(int by) {
        Lyrics l = player.lyrics();
        if (l.isEmpty()) return;
        boolean was = picking();
        int from = was ? pick : l.at(position());
        int to = by < 0 ? l.lineAtOrBefore(from - 1) : l.lineAfter(from);
        if (to < 0) to = was ? pick : l.lineAtOrBefore(Math.max(0, from));
        if (to < 0) to = l.lineAfter(-1);
        if (to < 0) return;
        pick = to;
        pickedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** The picked line's start, clearing the pick; -1 when none is picked. */
    long takePick() {
        if (!picking()) return -1;
        long at = player.lyrics().rows.get(pick).start;
        pick = -1;
        return at;
    }

    void clearPick() {
        pick = -1;
        invalidate();
    }

    private int focusRow() {
        if (picking()) return pick;
        return Math.max(0, player.lyrics().at(position()));
    }

    // ---- Drawing

    private void layout() {
        Lyrics l = player.lyrics();
        int width = (int) (getWidth() - look.px(28));
        if (l == laidOut && width == laidWidth) return;
        laidOut = l;
        laidWidth = width;
        int n = l.rows.size();
        layouts = new StaticLayout[n];
        tops = new float[n];
        glow = new float[n];
        pick = -1;
        float y = 0;
        for (int i = 0; i < n; i++) {
            tops[i] = y;
            Lyrics.Row row = l.rows.get(i);
            float h;
            if (row.isBreak()) {
                h = look.px(18);
            } else {
                layouts[i] = new StaticLayout(row.text, lyric, Math.max(1, width), Layout.Alignment.ALIGN_NORMAL, 1f, 0, false);
                h = layouts[i].getHeight();
            }
            y += h + look.px(11);
        }
        // A new song's lyrics start where it is, not scrolled in from the top.
        int cur = l.at(position());
        int focus = Math.max(0, cur);
        scroll = scrollTo = focus < n ? tops[focus] : 0;
        scrollStart = 0;
        for (int i = 0; i < n; i++) glow[i] = i == cur ? 1 : 0;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        backdropFor = null;
        laidOut = null;
        snapLyrics();
    }

    @Override protected void onDraw(Canvas c) {
        Song song = player.song();
        int w = getWidth();
        int h = getHeight();
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        if (tStart != 0) {
            float k = Math.min(1, (now - tStart) / 320f);
            float e = k < 0.5f ? 4 * k * k * k : 1 - (float) Math.pow(-2 * k + 2, 3) / 2;
            t = Look.lerp(tFrom, tTo, e);
            if (k >= 1) tStart = 0;
            else animating = true;
        }
        if (drawBackdrop(c, song, w, h, now)) animating = true;
        if (song == null) return;
        float d = look.d;
        float dyb = h - 320 * d;

        // The grab handle, Now Playing's only.
        if (t < 1) {
            fill.setColor(Look.alpha(0x59FFFFFF, 1 - t));
            rect.set(w / 2f - 16 * d, 27 * d, w / 2f + 16 * d, 31 * d);
            c.drawRoundRect(rect, 2 * d, 2 * d, fill);
        }

        // The lyrics, under the header so the cover can shrink over them.
        if (t > 0) {
            if (drawLyrics(c, w, 64 * d, 258 * d + dyb, now)) animating = true;
        }

        // The cover, from the middle to the corner.
        float size = Look.lerp(168, 30, t) * d;
        float cx = Look.lerp(36, 14, t) * d;
        float cy = Look.lerp(40, 28, t) * d;
        rect.set(cx, cy, cx + size, cy + size);
        float radius = Look.lerp(8, 4, t) * d;
        if (t < 1) {
            fill.setColor(0xFF000000);
            fill.setShadowLayer(14 * d, 0, 8 * d, Look.alpha(0x66000000, 1 - t));
            c.drawRoundRect(rect, radius, radius, fill);
            fill.clearShadowLayer();
        }
        Bitmap cover = art.cover(song, size <= 36 * d ? 30 : Art.BIG, this::invalidate);
        if (cover == null) cover = art.cover(song, size <= 36 * d ? Art.BIG : 30, null);
        if (cover != null) Art.rounded(c, cover, rect, radius, image);

        // Title and artist, beside the more button.
        float titleSize = Look.lerp(14.5f, 11.5f, t);
        float artistSize = Look.lerp(12f, 10f, t);
        float block = (titleSize + artistSize) * 1.2f;
        float centre = Look.lerp(218 + 31.8f / 2, 43, t) * d;
        float tx = Look.lerp(16, 52, t) * d;
        float moreX = w - Look.lerp(26, 22, t) * d;
        float textRight = moreX - 10 * d - Look.lerp(7, 8, t) * d;
        title.setTextSize(titleSize * d);
        artist.setTextSize(artistSize * d);
        float top = centre - block * d / 2;
        look.line(c, song.title, title, tx, top, titleSize * 1.2f, textRight - tx);
        look.line(c, song.artist.isEmpty() ? "Unknown artist" : song.artist, artist, tx,
            top + titleSize * 1.2f * d, artistSize * 1.2f, textRight - tx);
        fill.setColor(0x26FFFFFF);
        c.drawCircle(moreX, centre, 10 * d, fill);
        look.icon(c, R.drawable.ic_moreh, moreX - 5.5f * d, centre - 5.5f * d, 11, 0xFFFFFFFF);

        // Progress and times.
        long pos = position();
        long dur = player.duration();
        float py = Look.lerp(257, 266, t) * d + dyb;
        rect.set(16 * d, py, w - 16 * d, py + 4 * d);
        fill.setColor(0x40FFFFFF);
        c.drawRoundRect(rect, 2 * d, 2 * d, fill);
        float f = dur > 0 ? Math.max(0, Math.min(1, pos / (float) dur)) : 0;
        if (f > 0) {
            rect.right = rect.left + Math.max(4 * d, rect.width() * f);
            fill.setColor(0xCCFFFFFF);
            c.drawRoundRect(rect, 2 * d, 2 * d, fill);
        }
        float timesTop = py + 7 * d;
        c.drawText(Pixels.clock(pos), 16 * d, look.baseline(times, timesTop, 10.3f), times);
        String left = "-" + Pixels.clock(Math.max(0, dur - pos));
        c.drawText(left, w - 16 * d - times.measureText(left), look.baseline(times, timesTop, 10.3f), times);

        // Back, play or pause, ahead.
        float my = Look.lerp(288, 298, t) * d + dyb;
        float side = Look.lerp(24, 20, t);
        float mid = Look.lerp(28, 24, t);
        float total = (side * 2 + mid + 60) * d;
        float x = (w - total) / 2;
        look.icon(c, R.drawable.ic_rew, x, my - side * d / 2, side, 0xFFFFFFFF);
        look.icon(c, player.playing() ? R.drawable.ic_pause : R.drawable.ic_play,
            x + (side + 30) * d, my - mid * d / 2, mid, 0xFFFFFFFF);
        look.icon(c, R.drawable.ic_ff, x + (side + 60 + mid) * d, my - side * d / 2, side, 0xFFFFFFFF);

        // Lyrics and the library, at the foot of Now Playing.
        if (t < 1) {
            int icon = Look.alpha(0xBFFFFFFF, 1 - t);
            float iy = 300 * d + dyb + t * 10 * d;
            look.icon(c, R.drawable.ic_quote, 40 * d, iy, 15, icon);
            look.icon(c, R.drawable.ic_list, w - 55 * d, iy, 15, icon);
        }

        if (animating) postInvalidateOnAnimation();
    }

    /** The cover, blurred to its colours and darkened enough for white text; true while fading in. */
    private boolean drawBackdrop(Canvas c, Song song, int w, int h, long now) {
        if (song != null && !song.path.equals(backdropFor)) {
            Art.Backdrop b = art.backdrop(song, this::invalidate);
            if (b != null) {
                oldBackdrop = backdrop;
                backdrop = render(b, w, h);
                backdropFor = song.path;
                backdropStart = oldBackdrop == null ? 0 : now;
            }
        }
        if (backdrop == null) {
            c.drawColor(0xFF3A3A3C);
            return false;
        }
        if (backdropStart != 0 && oldBackdrop != null) {
            float k = Math.min(1, (now - backdropStart) / 450f);
            c.drawBitmap(oldBackdrop, 0, 0, null);
            image.setAlpha(Math.round(255 * k));
            c.drawBitmap(backdrop, 0, 0, image);
            image.setAlpha(255);
            if (k < 1) return true;
            backdropStart = 0;
            oldBackdrop = null;
            return false;
        }
        c.drawBitmap(backdrop, 0, 0, null);
        return false;
    }

    /** Draws the blurred cover once at the view's size, so each frame is a single copy. */
    private Bitmap render(Art.Backdrop b, int w, int h) {
        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float side = Math.max(w, h) + look.px(80);
        RectF dst = new RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(1.3f);
        p.setColorFilter(new ColorMatrixColorFilter(saturate));
        c.drawBitmap(b.blurred, null, dst, p);
        c.drawColor(b.veil << 24);
        // A little more at the foot, under the controls.
        Paint foot = new Paint();
        foot.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0x40000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, foot);
        return out;
    }

    /** The lyrics between `top` and `bottom`; true while anything in them moves. */
    private boolean drawLyrics(Canvas c, int w, float top, float bottom, long now) {
        float d = look.d;
        Lyrics l = player.lyrics();
        float slide = (1 - t) * 30 * d;
        int layer = c.saveLayerAlpha(0, top, w, bottom, Math.round(255 * t));
        c.translate(0, slide);
        if (l.isEmpty()) {
            drawNoLyrics(c, w, top, bottom - slide);
            c.restoreToCount(layer);
            return false;
        }
        layout();
        boolean moving = false;
        long pos = position();
        int cur = l.at(pos);
        boolean picked = picking();
        int focus = picked ? pick : Math.max(0, cur);

        // Follow the focused row.
        float target = tops[focus];
        if (target != scrollTo) {
            scrollFrom = scroll;
            scrollTo = target;
            scrollStart = now;
        }
        if (scrollStart != 0) {
            float k = Math.min(1, (now - scrollStart) / 520f);
            float e = 1 - (float) Math.pow(1 - k, 3);
            scroll = Look.lerp(scrollFrom, scrollTo, e);
            if (k >= 1) scrollStart = 0;
            else moving = true;
        }
        // Lines brighten and dim smoothly.
        float dt = lastFrame == 0 ? 1000 : Math.min(200, now - lastFrame);
        lastFrame = now;
        float ease = 1 - (float) Math.exp(-dt / 110f);
        for (int i = 0; i < glow.length; i++) {
            float goal = i == cur ? 1 : 0;
            if (Math.abs(goal - glow[i]) > 0.003f) {
                glow[i] += (goal - glow[i]) * ease;
                moving = true;
            } else {
                glow[i] = goal;
            }
        }

        float anchor = top + 34 * d;
        float x = 14 * d;
        for (int i = 0; i < layouts.length; i++) {
            float y = anchor + tops[i] - scroll;
            float h = (i + 1 < tops.length ? tops[i + 1] - 11 * d : tops[i] + rowHeight(i)) - tops[i];
            if (y + h < top - 40 * d) continue;
            if (y > bottom + 20 * d) break;
            boolean far = Math.abs(i - focus) >= 2;
            float base = far ? 0.22f : 0.3f;
            float alpha = Look.lerp(base, 1, glow[i]);
            if (picked && i == pick) {
                alpha = 1;
                fill.setColor(0x24FFFFFF);
                rect.set(x - 8 * d, y - 3 * d, w - 6 * d, y + h + 3 * d);
                c.drawRoundRect(rect, 8 * d, 8 * d, fill);
            }
            Lyrics.Row row = l.rows.get(i);
            if (row.isBreak()) {
                if (drawDots(c, x, y, row, i == cur, alpha, pos, now)) moving = true;
            } else {
                lyric.setColor(Look.alpha(0xFFFFFFFF, alpha));
                lyric.setMaskFilter(far && glow[i] < 0.5f && !(picked && i == pick) ? soft : null);
                c.save();
                c.translate(x, y);
                layouts[i].draw(c);
                c.restore();
                lyric.setMaskFilter(null);
            }
            if (picked && i == pick) {
                String time = Pixels.clock(row.start);
                float cw = chip.measureText(time) + 10 * d;
                float ch = 13 * d;
                float right = w - 10 * d;
                float midY = y + h / 2;
                rect.set(right - cw, midY - ch / 2, right, midY + ch / 2);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(rect, 7 * d, 7 * d, fill);
                c.drawText(time, right - cw + 5 * d, look.baseline(chip, midY - ch / 2, 13), chip);
            }
        }

        // The page fades out at its top and bottom edges.
        float fadeTop = (bottom - top) * 0.14f;
        float fadeBottom = (bottom - top) * 0.18f;
        c.translate(0, -slide);
        cut.setShader(new LinearGradient(0, top, 0, top + fadeTop, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, top, w, top + fadeTop, cut);
        cut.setShader(new LinearGradient(0, bottom - fadeBottom, 0, bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(0, bottom - fadeBottom, w, bottom, cut);
        cut.setShader(null);
        c.restoreToCount(layer);
        return moving;
    }

    private float rowHeight(int i) {
        return layouts[i] != null ? layouts[i].getHeight() : look.px(18);
    }

    /** Apple's three dots for a break: they fill in as it passes, and breathe. */
    private boolean drawDots(Canvas c, float x, float y, Lyrics.Row row, boolean current, float alpha, long pos, long now) {
        float d = look.d;
        float p = current && row.end > row.start ? Math.max(0, Math.min(1, (pos - row.start) / (float) (row.end - row.start))) : 0;
        float breathe = current ? 1 + 0.08f * (float) Math.sin(now / 1600.0 * 2 * Math.PI) : 1;
        for (int k = 0; k < 3; k++) {
            float a = current ? Math.max(0.35f, Math.min(1, 0.35f + (p * 3 - k) * 0.65f)) : alpha;
            float scale = breathe * (current ? Look.lerp(0.85f, 1.15f, Math.max(0, Math.min(1, p * 3 - k))) : 1);
            fill.setColor(Look.alpha(0xFFFFFFFF, a));
            c.drawCircle(x + (4 + 15 * k) * d, y + 10 * d, 4 * d * scale, fill);
        }
        return current && player.playing();
    }

    private void drawNoLyrics(Canvas c, int w, float top, float bottom) {
        float d = look.d;
        Song song = player.song();
        float mid = (top + bottom) / 2;
        look.icon(c, R.drawable.ic_mic, w / 2f - 13 * d, mid - 44 * d, 26, 0xB3FFFFFF);
        String head = "No lyrics for this song";
        c.drawText(head, (w - emptyTitle.measureText(head)) / 2, look.baseline(emptyTitle, mid - 10 * d, 16), emptyTitle);
        String detail = (song == null ? "" : song.base()) + ".srt isn't in Musica";
        StaticLayout layout = new StaticLayout(detail, emptyText, (int) (w - 40 * d), Layout.Alignment.ALIGN_CENTER, 1.1f, 0, false);
        c.save();
        c.translate(20 * d, mid + 10 * d);
        layout.draw(c);
        c.restore();
    }
}
