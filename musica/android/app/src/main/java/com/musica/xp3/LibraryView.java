package com.musica.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Apple Music's Songs list (the mockup's b-library): the title, the Play and
 * Shuffle capsules, a row per song A to Z, and the mini player floating at
 * the foot. The d-pad moves a grey "pressed" row; Up from the first song
 * reaches the capsules. Drawn whole, as one view, so it matches the mockup
 * to the pixel and the focus never strays.
 */
final class LibraryView extends View {

    /** The focus on the capsules: Play, Shuffle. Songs are 0 and up. */
    static final int PLAY = -2;
    static final int SHUFFLE = -1;

    private static final float ROW = 44;
    private static final float LIST_TOP = 98;

    enum State { LOADING, NO_ACCESS, EMPTY, SONGS }

    private final Look look;
    private final Art art;
    private final Player player;
    private final TextPaint title;
    private final TextPaint capsule;
    private final TextPaint name;
    private final TextPaint meta;
    private final TextPaint miniName;
    private final TextPaint miniArtist;
    private final TextPaint emptyTitle;
    private final TextPaint emptyText;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();

    private List<Song> songs = new ArrayList<>();
    private State state = State.LOADING;
    private int focus = 0;
    private float scroll;
    private float scrollFrom;
    private float scrollTo;
    private long scrollStart;

    LibraryView(Context context) {
        super(context);
        look = Look.get(context);
        art = Art.get(context);
        player = Player.get(context);
        title = look.paint(look.w800, 21, 0xFF000000, -0.02f);
        capsule = look.paint(look.w650, 11.5f, Look.RED, 0);
        name = look.paint(look.w500, 12.5f, 0xFF000000, -0.01f);
        meta = look.paint(look.w400, 10.5f, Look.GREY, 0);
        miniName = look.paint(look.w500, 11.5f, 0xFF000000, 0);
        miniArtist = look.paint(look.w400, 10f, Look.GREY, 0);
        emptyTitle = look.paint(look.w750, 15.5f, 0xFF000000, -0.01f);
        emptyText = look.paint(look.w400, 11.5f, Look.GREY, 0);
        // Shadows on shapes need the software pipeline on Android 8.1.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
    }

    void setSongs(List<Song> list, State s) {
        Song focused = focusedSong();
        songs = list;
        state = s;
        int at = -1;
        if (focused != null) {
            for (int i = 0; i < songs.size(); i++) if (songs.get(i).path.equals(focused.path)) at = i;
        }
        if (at < 0 && focus >= 0) {
            at = playingIndex();
            if (at < 0) at = Math.min(focus, songs.size() - 1);
        }
        if (focus >= 0 || songs.isEmpty()) focus = songs.isEmpty() ? 0 : Math.max(0, at);
        if (songs.isEmpty()) focus = 0;
        follow(false);
        invalidate();
    }

    private int playingIndex() {
        Song s = player.song();
        if (s == null) return -1;
        for (int i = 0; i < songs.size(); i++) if (songs.get(i).path.equals(s.path)) return i;
        return -1;
    }

    Song focusedSong() {
        return focus >= 0 && focus < songs.size() ? songs.get(focus) : null;
    }

    int focus() {
        return focus;
    }

    /** Puts the focus on the song playing, if it is in the list. */
    void focusPlaying() {
        int at = playingIndex();
        if (at >= 0) {
            focus = at;
            follow(false);
            invalidate();
        }
    }

    /** Moves the focus: Up and Down by a song, Left and Right between the capsules. */
    boolean move(int dy, int dx) {
        if (state != State.SONGS) return false;
        int before = focus;
        if (dx != 0 && focus < 0) focus = dx < 0 ? PLAY : SHUFFLE;
        if (dy < 0) focus = focus == 0 ? PLAY : focus < 0 ? focus : focus - 1;
        if (dy > 0) focus = focus < 0 ? 0 : Math.min(songs.size() - 1, focus + 1);
        if (focus != before) {
            follow(true);
            invalidate();
        }
        return true;
    }

    // ---- Scrolling: the header scrolls away with the list, as Apple's does.

    private float bottomSpace() {
        return look.px(player.song() != null ? 56 : 8);
    }

    private float contentHeight() {
        return look.px(LIST_TOP + ROW * songs.size()) + bottomSpace();
    }

    private void follow(boolean animate) {
        float h = getHeight();
        if (h == 0) return;
        float target;
        if (focus < 0) {
            target = 0;
        } else {
            float top = look.px(LIST_TOP + ROW * focus);
            float bottom = top + look.px(ROW);
            target = scrollTo;
            float viewTop = look.px(24);
            float viewBottom = h - bottomSpace();
            if (top - target < viewTop) target = top - viewTop - look.px(focus == 0 ? LIST_TOP - 24 : 0);
            if (bottom - target > viewBottom) target = bottom - viewBottom;
        }
        target = Math.max(0, Math.min(target, Math.max(0, contentHeight() - h)));
        if (!animate) {
            scroll = scrollTo = target;
            scrollStart = 0;
            return;
        }
        if (target == scrollTo) return;
        scrollFrom = scroll;
        scrollTo = target;
        scrollStart = SystemClock.uptimeMillis();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        follow(false);
    }

    // ---- Drawing

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        if (scrollStart != 0) {
            float t = Math.min(1, (now - scrollStart) / 160f);
            float e = 1 - (1 - t) * (1 - t);
            scroll = Look.lerp(scrollFrom, scrollTo, e);
            if (t >= 1) scrollStart = 0;
            else animating = true;
        }
        int w = getWidth();
        int h = getHeight();
        c.drawColor(0xFFFFFFFF);
        c.save();
        c.translate(0, -scroll);
        float d = look.d;

        look.line(c, "Songs", title, 12 * d, 28 * d, 25.4f, w - 50 * d);
        look.icon(c, R.drawable.ic_moreh, w - 28 * d, 33 * d, 18, Look.RED);

        if (state == State.SONGS) {
            float half = (w - 32 * d) / 2;
            drawCapsule(c, 12 * d, half, R.drawable.ic_play, 12, "Play", focus == PLAY);
            drawCapsule(c, 20 * d + half, half, R.drawable.ic_shuffle, 13, "Shuffle", focus == SHUFFLE);
            int first = Math.max(0, (int) ((scroll - LIST_TOP * d) / (ROW * d)) - 1);
            int last = Math.min(songs.size() - 1, (int) ((scroll + h - LIST_TOP * d) / (ROW * d)) + 1);
            Song playing = player.song();
            for (int i = first; i <= last; i++) {
                boolean isPlaying = playing != null && playing.path.equals(songs.get(i).path);
                if (drawRow(c, i, isPlaying, w, now)) animating = true;
            }
        } else if (state != State.LOADING) {
            drawEmpty(c, w, h);
        }
        c.restore();

        // The status bar's strip stays white over what scrolls under it.
        fill.setColor(0xFFFFFFFF);
        c.drawRect(0, 0, w, 24 * d, fill);
        if (player.song() != null) drawMini(c, w, h);
        if (animating) postInvalidateOnAnimation();
    }

    private void drawCapsule(Canvas c, float x, float width, int icon, float iconSize, String label, boolean focused) {
        float d = look.d;
        rect.set(x, 62 * d, x + width, 90 * d);
        fill.setColor(focused ? Look.RED : Look.CAPSULE);
        c.drawRoundRect(rect, 8 * d, 8 * d, fill);
        int color = focused ? 0xFFFFFFFF : Look.RED;
        capsule.setColor(color);
        float textWidth = capsule.measureText(label);
        float total = iconSize * d + 5 * d + textWidth;
        float left = x + (width - total) / 2;
        look.icon(c, icon, left, 76 * d - iconSize * d / 2, iconSize, color);
        c.drawText(label, left + iconSize * d + 5 * d, look.baseline(capsule, 62 * d, 28), capsule);
    }

    /** One song's row; true while its playing bars move. */
    private boolean drawRow(Canvas c, int i, boolean isPlaying, int w, long now) {
        float d = look.d;
        Song s = songs.get(i);
        float top = (LIST_TOP + ROW * i) * d;
        boolean focused = i == focus;
        if (focused) {
            fill.setColor(Look.PRESSED);
            c.drawRect(0, top, w, top + ROW * d, fill);
        }
        rect.set(12 * d, top + 4 * d, 48 * d, top + 40 * d);
        Bitmap cover = art.cover(s, 36, this::invalidate);
        if (cover != null) {
            Art.rounded(c, cover, rect, 5 * d, image);
        } else {
            fill.setColor(Look.CAPSULE);
            c.drawRoundRect(rect, 5 * d, 5 * d, fill);
        }
        boolean moving = false;
        if (isPlaying) {
            fill.setColor(0x4D000000);
            c.drawRoundRect(rect, 5 * d, 5 * d, fill);
            moving = player.playing();
            drawBars(c, rect.centerX(), rect.centerY(), now, moving);
        }
        float x = 58 * d;
        float width = w - x - 10 * d;
        float block = 12.5f * 1.2f + 10.5f * 1.35f;
        float textTop = top + (ROW * d - block * d) / 2;
        name.setColor(isPlaying ? Look.RED : 0xFF000000);
        look.line(c, s.title, name, x, textTop, 15, width);
        String sub = s.artist.isEmpty() ? "Unknown artist" : s.artist;
        if (s.srt == null) sub += " · No lyrics";
        look.line(c, sub, meta, x, textTop + 15 * d, 14.2f, width);
        // The hairline under the text, but not by the pressed row.
        if (!focused && i + 1 != focus && i + 1 < songs.size()) {
            fill.setColor(Look.HAIRLINE);
            c.drawRect(x, top + ROW * d - Math.max(1, 0.5f * d), w - 10 * d, top + ROW * d, fill);
        }
        return moving;
    }

    /** Apple's three playing bars, still when paused. */
    private void drawBars(Canvas c, float cx, float cy, long now, boolean moving) {
        float d = look.d;
        float[] heights = {5, 9, 6};
        float[] phase = {-0.3f, -0.6f, 0f};
        float barW = 2 * d;
        float gap = 1.5f * d;
        float left = cx - (3 * barW + 2 * gap) / 2;
        float bottom = cy + 4.5f * d;
        fill.setColor(0xFFFFFFFF);
        for (int k = 0; k < 3; k++) {
            float scale = 1;
            if (moving) {
                // An eased back and forth every 0.9 s, as the mockup's CSS.
                float t = ((now / 900f) - phase[k]) % 2f;
                if (t < 0) t += 2;
                float u = t < 1 ? t : 2 - t;
                u = u * u * (3 - 2 * u);
                scale = 0.35f + 0.65f * u;
            }
            float bh = heights[k] * d * scale;
            float x = left + k * (barW + gap);
            rect.set(x, bottom - bh, x + barW, bottom);
            c.drawRoundRect(rect, d, d, fill);
        }
    }

    private void drawMini(Canvas c, int w, int h) {
        float d = look.d;
        Song s = player.song();
        rect.set(8 * d, h - 48 * d, w - 8 * d, h - 8 * d);
        fill.setColor(0xF2F6F6F8);
        fill.setShadowLayer(5 * d, 0, 2 * d, 0x2A000000);
        c.drawRoundRect(rect, 12 * d, 12 * d, fill);
        fill.clearShadowLayer();
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Math.max(1, 0.5f * d));
        fill.setColor(0x1A000000);
        c.drawRoundRect(rect, 12 * d, 12 * d, fill);
        fill.setStyle(Paint.Style.FILL);
        float top = h - 48 * d;
        RectF cov = new RectF(14 * d, top + 6 * d, 42 * d, top + 34 * d);
        Bitmap cover = art.cover(s, 28, this::invalidate);
        if (cover != null) Art.rounded(c, cover, cov, 5 * d, image);
        float x = 50 * d;
        float right = w - 18 * d - 20 * d - 6 * d - 18 * d - 8 * d;
        float block = 11.5f * 1.2f + 10f * 1.3f;
        float textTop = top + (40 * d - block * d) / 2;
        look.line(c, s.title, miniName, x, textTop, 13.8f, right - x);
        look.line(c, s.artist.isEmpty() ? "Unknown artist" : s.artist, miniArtist, x, textTop + 13.8f * d, 13, right - x);
        float iy = top + 20 * d;
        look.icon(c, player.playing() ? R.drawable.ic_pause : R.drawable.ic_play,
            w - 18 * d - 20 * d - 6 * d - 18 * d, iy - 9 * d, 18, 0xFF000000);
        look.icon(c, R.drawable.ic_ff, w - 18 * d - 20 * d, iy - 10 * d, 20, 0xFF000000);
    }

    private void drawEmpty(Canvas c, int w, int h) {
        float d = look.d;
        String head;
        String detail;
        int icon;
        if (state == State.NO_ACCESS) {
            head = "No access to storage";
            detail = "Música needs it to play the songs in Musica.";
            icon = R.drawable.ic_lock;
        } else {
            head = "No songs yet";
            detail = "Songs go in Musica on the SD card, each MP3 with its SRT.";
            icon = R.drawable.ic_folder;
        }
        float cy = (58 * d + h) / 2 - 28 * d;
        fill.setColor(Look.CAPSULE);
        c.drawCircle(w / 2f, cy, 29 * d, fill);
        look.icon(c, icon, w / 2f - 14 * d, cy - 14 * d, 28, Look.RED);
        float y = cy + 29 * d + 14 * d;
        float tw = emptyTitle.measureText(head);
        c.drawText(head, (w - tw) / 2, look.baseline(emptyTitle, y, 19), emptyTitle);
        int width = (int) (w - 44 * d);
        StaticLayout layout = new StaticLayout(detail, emptyText, width, Layout.Alignment.ALIGN_CENTER, 1.15f, 0, false);
        c.save();
        c.translate(22 * d, y + 25 * d);
        layout.draw(c);
        c.restore();
    }
}
