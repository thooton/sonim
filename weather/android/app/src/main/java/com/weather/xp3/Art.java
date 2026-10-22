package com.weather.xp3;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

/**
 * The pictures: icons, the weather over the sky and the scenery along its
 * foot, drawn in CARROT's manner and colours. This is the mockups' drawing
 * code (weather/mockups/index.html) line for line, in a 240-wide space, so a
 * change to one is a change to the other.
 */
final class Art {

    private Art() {}

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    static {
        FILL.setStyle(Paint.Style.FILL);
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
    }
    private static final RectF RECT = new RectF();
    private static final Map<String, Path> PATHS = new HashMap<>();

    /** SVG path data as a Path, read once. */
    static Path path(String d) {
        Path p = PATHS.get(d);
        if (p != null) return p;
        p = new Path();
        for (float[] op : PathData.parse(d).ops) {
            switch ((int) op[0]) {
                case PathData.MOVE: p.moveTo(op[1], op[2]); break;
                case PathData.LINE: p.lineTo(op[1], op[2]); break;
                case PathData.CUBIC: p.cubicTo(op[1], op[2], op[3], op[4], op[5], op[6]); break;
                default: p.close();
            }
        }
        PATHS.put(d, p);
        return p;
    }

    static void fill(Canvas c, String d, int color) {
        fill(c, d, color, 1);
    }

    static void fill(Canvas c, String d, int color, float alpha) {
        FILL.setColor(color);
        FILL.setAlpha(Math.round(alpha * 255));
        c.drawPath(path(d), FILL);
    }

    static void stroke(Canvas c, String d, int color, float width, float alpha) {
        STROKE.setColor(color);
        STROKE.setAlpha(Math.round(alpha * 255));
        STROKE.setStrokeWidth(width);
        c.drawPath(path(d), STROKE);
    }

    static void rect(Canvas c, float x, float y, float w, float h, float r, int color, float alpha) {
        FILL.setColor(color);
        FILL.setAlpha(Math.round(alpha * 255));
        RECT.set(x, y, x + w, y + h);
        c.drawRoundRect(RECT, r, r, FILL);
    }

    static void circle(Canvas c, float x, float y, float r, int color, float alpha) {
        FILL.setColor(color);
        FILL.setAlpha(Math.round(alpha * 255));
        c.drawCircle(x, y, r, FILL);
    }

    // ------------------------------------------------------------------
    // Icons, each in a 32×32 box.
    // ------------------------------------------------------------------

    static final String CLOUD = "M8.5,27 L24.5,27 A6.5,6.5 0 0 0 25.6,14.1 A9,9 0 0 0 8.4,15.4 A5.8,5.8 0 0 0 8.5,27 Z";
    static final String MOON = "M17.5,4.2 A12,12 0 1 0 28.8,20.6 A9.6,9.6 0 0 1 17.5,4.2 Z";
    static final String DROP = "M16,3 C16,3 6.5,14.6 6.5,20.3 A9.5,9.5 0 0 0 25.5,20.3 C25.5,14.6 16,3 16,3 Z";
    static final String BOLT = "M19.5,2.5 L7.5,18.2 L15,18.2 L12.2,29.5 L24.8,12.8 L17.2,12.8 Z";

    private static final int ORANGE = 0xFFF79402, YELLOW = 0xFFFFED00, SKY_CLOUD = 0xFF00CFFF,
        NIGHT_CLOUD = 0xFF9013FE, MOON_PINK = 0xFFD445FF, RAIN_BLUE = 0xFF007AFF;

    /** An eight-point burst (two rounded squares) round a yellow disc. */
    private static void sun(Canvas c, float cx, float cy, float r) {
        float s = r * 1.42f;
        c.save();
        c.translate(cx, cy);
        rect(c, -s / 2, -s / 2, s, s, r * .16f, ORANGE, 1);
        c.rotate(45);
        rect(c, -s / 2, -s / 2, s, s, r * .16f, ORANGE, 1);
        c.restore();
        circle(c, cx, cy, r * .56f, YELLOW, 1);
    }

    private static void snowflake(Canvas c, int color) {
        STROKE.setColor(color);
        STROKE.setAlpha(255);
        STROKE.setStrokeWidth(2.6f);
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 - Math.PI / 2;
            float x = (float) (16 + Math.cos(a) * 10.5), y = (float) (16 + Math.sin(a) * 10.5);
            c.drawLine(16, 16, x, y, STROKE);
            circle(c, x, y, 2.6f, color, 1);
        }
        circle(c, 16, 16, 3.4f, color, 1);
    }

    private static void scaled(Canvas c, float tx, float ty, float s, String d, int color) {
        c.save();
        c.translate(tx, ty);
        c.scale(s, s);
        fill(c, d, color);
        c.restore();
    }

    /** `name`'s icon at (x, y), `size` square. On the sky, snow is white. */
    static void icon(Canvas c, String name, float x, float y, float size, boolean onSky) {
        c.save();
        c.translate(x, y);
        c.scale(size / 32, size / 32);
        switch (name) {
            case Condition.CLEAR_DAY:
                sun(c, 16, 16, 12.5f);
                break;
            case Condition.CLEAR_NIGHT:
                fill(c, MOON, MOON_PINK);
                stroke(c, "M25,4.5v6M22,7.5h6", 0xFF00FFCC, 1.8f, 1);
                stroke(c, "M28.5,12.2v3.6M26.7,14h3.6", SKY_CLOUD, 1.5f, 1);
                break;
            case Condition.PARTLY_CLOUDY_DAY:
                sun(c, 20.5f, 11.5f, 9.2f);
                scaled(c, -3.2f, 3, .86f, CLOUD, SKY_CLOUD);
                break;
            case Condition.PARTLY_CLOUDY_NIGHT:
                scaled(c, 9, -1.5f, .62f, MOON, MOON_PINK);
                scaled(c, -3.2f, 3, .86f, CLOUD, NIGHT_CLOUD);
                break;
            case Condition.CLOUDY_DAY:
                sun(c, 23.5f, 9, 6.6f);
                scaled(c, -1, 0, 1, CLOUD, SKY_CLOUD);
                break;
            case Condition.CLOUDY_NIGHT:
                fill(c, CLOUD, NIGHT_CLOUD);
                break;
            case Condition.SNOW:
                snowflake(c, onSky ? 0xFFFFFFFF : RAIN_BLUE);
                break;
            case Condition.SLEET:
                scaled(c, -4, 2, .72f, DROP, RAIN_BLUE);
                c.save();
                c.translate(13, 9);
                c.scale(.58f, .58f);
                snowflake(c, onSky ? 0xFFFFFFFF : RAIN_BLUE);
                c.restore();
                break;
            case Condition.THUNDERSTORM:
                fill(c, BOLT, 0xFFF7B500);
                break;
            case Condition.FOG:
                int grey = 0xFFD0D0D0;
                rect(c, 3, 7, 15, 4.2f, 2.1f, grey, 1);
                rect(c, 21, 7, 7, 4.2f, 2.1f, grey, 1);
                rect(c, 7, 14, 22, 4.2f, 2.1f, grey, 1);
                rect(c, 3, 21, 9, 4.2f, 2.1f, grey, 1);
                rect(c, 15, 21, 12, 4.2f, 2.1f, grey, 1);
                break;
            case Condition.WIND:
                int green = 0xFF21D3A0;
                stroke(c, "M3,12.5 H20.5 A4,4 0 1 0 16.5,8.5", green, 2.6f, 1);
                stroke(c, "M3,18.5 H25 A3.6,3.6 0 1 1 21.4,22.1", green, 2.6f, 1);
                stroke(c, "M7,24.5 H14", green, 2.6f, 1);
                break;
            default:
                fill(c, DROP, RAIN_BLUE);
        }
        c.restore();
    }

    // ------------------------------------------------------------------
    // The weather over the sky.
    // ------------------------------------------------------------------

    /** The mockups' random numbers (a Park-Miller generator), so the skies match. */
    private static final class Rnd {
        private double s;

        Rnd(int seed) {
            s = seed;
        }

        double next() {
            s = (s * 16807) % 2147483647;
            return s / 2147483647;
        }
    }

    static void effect(Canvas c, Condition.Effect kind, float w, float h) {
        Rnd r = new Rnd(7);
        switch (kind) {
            case CLOUDS_DAY:
            case CLOUDS_NIGHT: {
                // CARROT's partly cloudy: rows of rounded dashes.
                int col = kind == Condition.Effect.CLOUDS_DAY ? 0xFF0A93FF : 0xFF5E10CF;
                int[][] rows = {{8, 7}, {22, 5}, {38, 6}, {54, 4}};
                for (int[] row : rows) {
                    double x = -10 + r.next() * 30;
                    for (int i = 0; i < row[1] && x < w; i++) {
                        double len = 10 + r.next() * 28;
                        rect(c, (float) x, row[0], (float) len, 5, 2.5f, col, 1);
                        if (r.next() > .55) rect(c, (float) (x + len + 4), row[0], 5, 5, 2.5f, col, 1);
                        x += len + 18 + r.next() * 34;
                    }
                }
                break;
            }
            case OVERCAST_DAY:
            case OVERCAST_NIGHT: {
                // A scalloped deck of cloud along the top.
                int col = kind == Condition.Effect.OVERCAST_DAY ? 0xFF0A93FF : 0xFF5E0FCF;
                StringBuilder d = new StringBuilder("M0,0 H" + w + " V12");
                for (float x = w; x > 0; x -= 24) d.append(" A13,9 0 0 1 ").append(x - 24).append(",12");
                fill(c, d.append(" Z").toString(), col, .55f);
                d = new StringBuilder("M0,0 H" + w + " V5");
                for (float x = w + 8; x > -20; x -= 30) d.append(" A16,10 0 0 1 ").append(x - 30).append(",5");
                fill(c, d.append(" V0 Z").toString(), col);
                break;
            }
            case RAIN:
                for (int i = 0; i < 46; i++) {
                    double x = r.next() * w, y = r.next() * h - 10, len = 10 + r.next() * 16, a = .1 + r.next() * .22;
                    rect(c, (float) x, (float) y, 1.2f, (float) len, .6f, 0xFF00AEFD, (float) a);
                }
                break;
            case SNOW:
                for (int i = 0; i < 38; i++) {
                    double x = r.next() * w, y = r.next() * h, s = .9 + r.next() * 1.8;
                    circle(c, (float) x, (float) y, (float) s, 0xFFD7E4FB, (float) (.45 + r.next() * .55));
                }
                break;
            case STARS:
                for (int i = 0; i < 40; i++) {
                    double x = r.next() * w, y = r.next() * h * .8, s = .4 + r.next() * .9;
                    circle(c, (float) x, (float) y, (float) s, 0xFFF5F2F8, (float) (.4 + r.next() * .6));
                }
                stroke(c, "M206,18v5M203.5,20.5h5M30,46v4M28,48h4", 0xFFFF00FE, 1.2f, .8f);
                break;
            case WIND:
                for (int i = 0; i < 9; i++) {
                    double x = r.next() * w - 30, y = 6 + r.next() * (h - 50), len = 30 + r.next() * 60;
                    float a = (float) (.12 + r.next() * .2);
                    stroke(c, String.format(java.util.Locale.US, "M%.1f,%.1f h%.1f q8,0 8,-6", x, y, len), 0xFFFFFFFF, 1.4f, a);
                }
                break;
            default:
        }
    }

    // ------------------------------------------------------------------
    // The scenery: hills, a lighthouse, an observatory, a tree and a bush.
    // ------------------------------------------------------------------

    /** One scene's colours, in CARROT's layer order. */
    static final class Palette {
        final int far, mid, house, lamp, low, front, crown, trunk, spot, dot, dot2, smoke, bars;
        final float farAlpha;

        Palette(int far, float farAlpha, int mid, int house, int lamp, int low, int front, int crown,
                int trunk, int spot, int dot, int dot2, int smoke, int bars) {
            this.far = far; this.farAlpha = farAlpha; this.mid = mid; this.house = house; this.lamp = lamp;
            this.low = low; this.front = front; this.crown = crown; this.trunk = trunk; this.spot = spot;
            this.dot = dot; this.dot2 = dot2; this.smoke = smoke; this.bars = bars;
        }
    }

    static Palette palette(Condition.Scenery s) {
        switch (s) {
            case NIGHT: return new Palette(0xFF1B54AE, 1, 0xFF9013FE, 0xFFFFFEE3, 0xFF9013FE, 0xFF03A1BD, 0xFF0BE0C4, 0xFF9013FE, 0xFF0B3E84, 0xFFFF00FE, 0xFFFFFFFF, 0xFFFFFA00, 0xFF9013FE, 0);
            case RAIN: return new Palette(0xFF00CFFF, .3f, 0xFF0090F2, 0xFFFFFEE3, 0xFF0090F2, 0xFF02B1C1, 0xFF63FEE8, 0xFF1C68A4, 0xFF63FEE8, 0xFF63FEE8, 0xFFFFFFFF, 0xFFFFFA00, 0xFF0090F2, 0);
            case SNOW: return new Palette(0xFFB8D7F6, .3f, 0xFF326AC7, 0xFFFFFEE3, 0xFF326AC7, 0xFFB8D7F6, 0xFFE4EEFF, 0xFF00ADFF, 0xFF0649B9, 0xFF0649B9, 0xFFB8D7F6, 0xFFB8D7F6, 0xFF326AC7, 0);
            case FOG: return new Palette(0xFF2A9ECC, .3f, 0xFF23A0D2, 0xFF308CC7, 0xFF308CC7, 0xFF308CC7, 0xFF308CC7, 0xFF308CC7, 0xFF308CC7, 0xFF308CC7, 0, 0, 0xFF308CC7, 0xFF85DCF2);
            default: return new Palette(0xFF00CFFF, .3f, 0xFF2CA2FF, 0xFFFFFEE3, 0xFF2CA2FF, 0xFF02BBCC, 0xFF3FE0B3, 0xFF1CA3A4, 0xFF3FE0B3, 0xFF3FE0B3, 0xFFFFFFFF, 0xFFFFFA00, 0xFF2CA2FF, 0);
        }
    }

    static final float SCENERY_H = 44;
    private static final float[][] PINES = {{74, 24}, {80, 25.5f}, {172, 22.5f}, {178, 24}, {167, 25}};
    private static final float[][] DOTS = {
        {30, 21, 1}, {50, 26, 0}, {27, 25, 2}, {54, 31, 1}, {34, 30, 1}, {39, 31.6f, 1}, {44, 33, 2},
        {176, 38, 1}, {181, 37.4f, 2}, {186, 38.6f, 1}, {200, 34, 1}, {206, 33, 2}, {212, 27, 1}, {214, 31, 1},
    };
    private static final float[][] MIST = {
        {6, 22, 52}, {72, 22, 22}, {104, 22, 74}, {196, 22, 40}, {0, 29, 18}, {40, 29, 46}, {126, 29, 58},
        {214, 29, 26}, {16, 36, 40}, {70, 36, 66}, {168, 36, 50},
    };

    /**
     * Drawn 240 wide and 44 high, then scaled up by a quarter about the
     * middle so the lighthouse fills more of the screen: 55 high at y0.
     */
    static void scenery(Canvas c, Condition.Scenery scene, float y0) {
        Palette p = palette(scene);
        float H = SCENERY_H;
        c.save();
        c.translate(-30, y0);
        c.scale(1.25f, 1.25f);
        // Far hills.
        fill(c, "M0,14 C30,6 58,8 84,15 C104,20 122,19 142,13 C168,6 200,4 240,12 L240," + H + " L0," + H + " Z", p.far, p.farAlpha);
        // Middle hills, with little pines on them.
        fill(c, "M0,24 C22,19 44,19 66,24 C82,28 96,27 110,22 C132,15 152,17 172,23 C196,30 218,26 240,20 L240," + H + " L0," + H + " Z", p.mid, .7f);
        for (float[] t : PINES) {
            fill(c, "M" + t[0] + "," + (t[1] - 5) + " L" + (t[0] + 2.6f) + "," + t[1] + " L" + (t[0] - 2.6f) + "," + t[1] + " Z", p.mid);
        }
        // The observatory and its mast.
        rect(c, 95, 17, 2, 15, 0, p.house, 1);
        for (int y : new int[] {20, 24, 28}) rect(c, 94, y, 4, 1.3f, 0, p.mid, 1);
        fill(c, "M101,31 A6.5,6.5 0 0 1 114,31 Z", p.house);
        rect(c, 99.5f, 30.5f, 16, 2.6f, 1, p.house, 1);
        c.save();
        c.rotate(-30, 107.7f, 27);
        rect(c, 107, 25.4f, 1.4f, 3.2f, 0, p.mid, 1);
        c.restore();
        rect(c, 119, 30.2f, 5, 3, 1.2f, p.house, 1);
        // The lighthouse.
        fill(c, "M128.8,33.5 L131.8,19.6 L138.2,19.6 L141.2,33.5 Z", p.house);
        rect(c, 130.6f, 23.4f, 8.8f, 1.6f, 0, p.mid, .9f);
        rect(c, 130.8f, 17.4f, 8.4f, 2.6f, .8f, p.house, 1);
        fill(c, "M131.8,17.6 A3.2,3.2 0 0 1 138.2,17.6 Z", p.lamp);
        circle(c, 137.4f, 10.6f, 1.5f, p.smoke, 1);
        circle(c, 140.2f, 6.2f, 1, p.smoke, .8f);
        // Lower hill, then the near hills either side.
        fill(c, "M0,36 C40,33 80,32 120,33.5 C160,35 200,34 240,31 L240," + H + " L0," + H + " Z", p.low);
        fill(c, "M0,8 C16,9 30,16 44,24 C60,33 78,38 104,40 C120,41 150,41 172,37 C194,33 206,22 222,16 C230,13 236,12 240,12 L240," + H + " L0," + H + " Z", p.front);
        // Tree on the left hill.
        rect(c, 38.4f, 17, 1.6f, 12, .8f, p.trunk, 1);
        stroke(c, "M39,24 L36,21.5", p.trunk, 1.3f, 1);
        circle(c, 39.2f, 14.4f, 6.2f, p.crown, 1);
        circle(c, 38, 12.6f, 1, p.spot, 1);
        circle(c, 41.4f, 15, .9f, p.spot, 1);
        // Bush on the right hill.
        fill(c, "M188,29 A4.2,4.2 0 0 1 193,23.2 A5.6,5.6 0 0 1 203.4,23.6 A4.4,4.4 0 0 1 208.6,29 Z", p.crown);
        circle(c, 194.4f, 26, .9f, p.spot, 1);
        circle(c, 199.4f, 23.6f, .9f, p.spot, 1);
        circle(c, 202.6f, 27, .9f, p.spot, 1);
        // Flowers in the grass.
        if (p.dot != 0) {
            for (float[] d : DOTS) circle(c, d[0], d[1], .95f, d[2] == 2 ? p.dot2 : p.dot, 1);
        }
        // Fog: banks of mist across the scene.
        if (p.bars != 0) {
            for (float[] m : MIST) rect(c, m[0], m[1], m[2], 3.4f, 1.7f, p.bars, .45f);
        }
        c.restore();
    }
}
