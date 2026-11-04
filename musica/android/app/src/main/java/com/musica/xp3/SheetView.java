package com.musica.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import java.util.Locale;

/**
 * The song's options, on Menu: lyrics, speed, repeat, shuffle and the sleep
 * timer, in a sheet that rises from the foot (light over the library, dark
 * over the player, as Apple's menus follow what is under them). Up and Down
 * pick, OK and Right step a setting on, Left back.
 */
final class SheetView extends View {

    interface Host {
        boolean lyricsShown();
        void showLyrics(boolean on);
        void closeSheet();
    }

    private static final int LYRICS = 0;
    private static final int SPEED = 1;
    private static final int REPEAT = 2;
    private static final int SHUFFLE = 3;
    private static final int SLEEP = 4;
    private static final String[] LABELS = {"Lyrics", "Playback speed", "Repeat", "Shuffle", "Sleep timer"};
    private static final int[] ICONS = {R.drawable.ic_quote, R.drawable.ic_speed, R.drawable.ic_repeat,
        R.drawable.ic_shuffle, R.drawable.ic_timer};

    private final Look look;
    private final Art art;
    private final Player player;
    private final Host host;
    private final TextPaint label;
    private final TextPaint value;
    private final TextPaint head;
    private final TextPaint sub;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();

    private boolean dark;
    private Song fallback;
    private int focus;
    private long openedAt;
    private boolean closing;

    SheetView(Context context, Host host) {
        super(context);
        this.host = host;
        look = Look.get(context);
        art = Art.get(context);
        player = Player.get(context);
        label = look.paint(look.w650, 12f, 0xFF000000, 0);
        value = look.paint(look.w650, 11f, Look.GREY, 0);
        head = look.paint(look.w650, 12.5f, 0xFF000000, 0);
        sub = look.paint(look.w400, 10.5f, Look.GREY, 0);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
    }

    /** Opens over the player (dark) or the library (light, about `song` if nothing plays). */
    void open(boolean overPlayer, Song song) {
        dark = overPlayer;
        fallback = song;
        focus = player.song() == null ? SPEED : LYRICS;
        openedAt = SystemClock.uptimeMillis();
        closing = false;
        setVisibility(VISIBLE);
        invalidate();
    }

    void close() {
        if (closing || getVisibility() != VISIBLE) return;
        closing = true;
        openedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE && !closing;
    }

    void move(int by) {
        int next = focus + by;
        if (next == LYRICS && player.song() == null) next += by;
        if (next >= 0 && next < LABELS.length) focus = next;
        invalidate();
    }

    /** Steps the focused setting on (1) or back (-1). */
    void step(int dir) {
        switch (focus) {
            case LYRICS:
                if (player.song() != null) {
                    host.showLyrics(!host.lyricsShown());
                    host.closeSheet();
                }
                return;
            case SPEED: {
                int i = indexOf(Player.SPEEDS, player.speed());
                player.setSpeed(Player.SPEEDS[wrap(i + dir, Player.SPEEDS.length)]);
                break;
            }
            case REPEAT: {
                Queue.Repeat[] all = Queue.Repeat.values();
                player.setRepeat(all[wrap(player.repeat().ordinal() + dir, all.length)]);
                break;
            }
            case SHUFFLE:
                player.setShuffled(!player.shuffled());
                break;
            case SLEEP: {
                int i = 0;
                for (int k = 0; k < Player.SLEEP.length; k++) if (Player.SLEEP[k] == player.sleep()) i = k;
                player.setSleep(Player.SLEEP[wrap(i + dir, Player.SLEEP.length)]);
                break;
            }
            default:
                break;
        }
        invalidate();
    }

    private static int wrap(int i, int n) {
        return ((i % n) + n) % n;
    }

    private static int indexOf(float[] values, float v) {
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - v) < 0.001f) return i;
        return 0;
    }

    private String valueOf(int item) {
        switch (item) {
            case LYRICS:
                return host.lyricsShown() ? "On" : "Off";
            case SPEED: {
                float s = player.speed();
                String n = s == (int) s ? String.valueOf((int) s) : String.format(Locale.ROOT, "%s", s);
                return n + "×";
            }
            case REPEAT:
                return player.repeat() == Queue.Repeat.ONE ? "This song" : player.repeat() == Queue.Repeat.ALL ? "All" : "Off";
            case SHUFFLE:
                return player.shuffled() ? "On" : "Off";
            case SLEEP:
                return player.sleep() == -1 ? "End of song" : player.sleep() > 0 ? player.sleepLeft() + " min" : "Off";
            default:
                return "";
        }
    }

    private boolean accented(int item) {
        switch (item) {
            case LYRICS: return host.lyricsShown();
            case SPEED: return player.speed() != 1f;
            case REPEAT: return player.repeat() != Queue.Repeat.OFF;
            case SHUFFLE: return player.shuffled();
            case SLEEP: return player.sleep() != 0;
            default: return false;
        }
    }

    @Override protected void onDraw(Canvas c) {
        float d = look.d;
        int w = getWidth();
        int h = getHeight();
        float k = Math.min(1, (SystemClock.uptimeMillis() - openedAt) / 200f);
        float e = 1 - (1 - k) * (1 - k);
        float shown = closing ? 1 - e : e;
        if (closing && k >= 1) {
            setVisibility(GONE);
            closing = false;
            return;
        }
        int bg = dark ? 0xFF2C2C2E : 0xFFFFFFFF;
        int text = dark ? 0xFFFFFFFF : 0xFF000000;
        int muted = dark ? 0xFFAEAEB2 : Look.GREY;
        int pressed = dark ? 0xFF3A3A3C : Look.PRESSED;
        int hair = dark ? 0x1FFFFFFF : 0xFFE0E0E4;

        c.drawColor(Look.alpha(0x8C000000, shown));
        float height = (6 + 3 + 8 + 36 + 9 + 1 + 4 + LABELS.length * 32 + 4) * d;
        float top = h - height * shown;
        fill.setColor(bg);
        rect.set(0, top, w, h + 12 * d);
        c.drawRoundRect(rect, 12 * d, 12 * d, fill);

        fill.setColor(dark ? 0x40FFFFFF : 0x33000000);
        rect.set(w / 2f - 14 * d, top + 6 * d, w / 2f + 14 * d, top + 9 * d);
        c.drawRoundRect(rect, 2 * d, 2 * d, fill);

        Song song = player.song() != null ? player.song() : fallback;
        float y = top + 17 * d;
        if (song != null) {
            Bitmap cover = art.cover(song, 36, this::invalidate);
            rect.set(12 * d, y, 48 * d, y + 36 * d);
            if (cover != null) Art.rounded(c, cover, rect, 5 * d, image);
            head.setColor(text);
            sub.setColor(muted);
            float block = (12.5f + 10.5f) * 1.25f;
            float tt = y + (36 - block) / 2 * d;
            look.line(c, song.title, head, 57 * d, tt, 12.5f * 1.25f, w - 69 * d);
            look.line(c, song.artist.isEmpty() ? "Unknown artist" : song.artist, sub, 57 * d, tt + 12.5f * 1.25f * d, 10.5f * 1.25f, w - 69 * d);
        }
        y += 45 * d;
        fill.setColor(hair);
        c.drawRect(0, y, w, y + Math.max(1, d), fill);
        y += 1 * d + 4 * d;
        for (int i = 0; i < LABELS.length; i++) {
            boolean off = i == LYRICS && player.song() == null;
            float rowTop = y + i * 32 * d + 2 * d;
            if (i == focus) {
                fill.setColor(pressed);
                rect.set(4 * d, rowTop, w - 4 * d, rowTop + 30 * d);
                c.drawRoundRect(rect, 6 * d, 6 * d, fill);
            }
            int ink = off ? Look.alpha(muted, 0.5f) : text;
            look.icon(c, ICONS[i], 12 * d, rowTop + 6.5f * d, 17, off ? ink : muted);
            label.setColor(ink);
            look.line(c, LABELS[i], label, 41 * d, rowTop, 30, w - 120 * d);
            if (!off) {
                String v = valueOf(i);
                value.setColor(accented(i) ? (dark ? 0xFFFF4F64 : Look.RED) : muted);
                float vw = value.measureText(v);
                c.drawText(v, w - 12 * d - vw, look.baseline(value, rowTop, 30), value);
            }
        }
        if (k < 1) postInvalidateOnAnimation();
    }
}
