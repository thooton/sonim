package com.weather.xp3;

import java.util.ArrayList;
import java.util.List;

/**
 * SVG path data, as the mockups draw the art, read into moves, lines and
 * cubic curves that android.graphics.Path takes as they are. Arcs become
 * cubics here, so the shapes on the phone are the mockups' own.
 *
 * Takes M L H V C S Q A Z in either case; Q becomes a cubic too.
 */
public final class PathData {

    public static final int MOVE = 0, LINE = 1, CUBIC = 2, CLOSE = 3;

    /** Each op: its kind, then its points (one for a move or line, three for a cubic). */
    public final List<float[]> ops = new ArrayList<>();

    private float x, y, startX, startY, lastCx, lastCy;
    private char lastCommand;

    public static PathData parse(String d) {
        PathData p = new PathData();
        p.read(d);
        return p;
    }

    private void read(String d) {
        int i = 0, n = d.length();
        char command = 0;
        List<Float> args = new ArrayList<>();
        while (i < n) {
            char c = d.charAt(i);
            if (Character.isLetter(c) && c != 'e' && c != 'E') {
                if (command != 0) run(command, args);
                command = c;
                args.clear();
                i++;
            } else if (c == '-' || c == '+' || c == '.' || Character.isDigit(c)) {
                int start = i;
                i++;
                boolean dot = c == '.', exp = false;
                while (i < n) {
                    char k = d.charAt(i);
                    if (Character.isDigit(k)) { i++; continue; }
                    if (k == '.' && !dot && !exp) { dot = true; i++; continue; }
                    if ((k == 'e' || k == 'E') && !exp) {
                        exp = true; i++;
                        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
                        continue;
                    }
                    break;
                }
                args.add(Float.parseFloat(d.substring(start, i)));
                // An arc's flags may be written run together: "0 01 5,5".
                if (Character.toUpperCase(command) == 'A') {
                    int at = args.size() % 7;
                    if ((at == 4 || at == 5) && i - start > 1) {
                        char first = d.charAt(start);
                        if (first == '0' || first == '1') {
                            args.set(args.size() - 1, (float) (first - '0'));
                            i = start + 1;
                        }
                    }
                }
            } else {
                i++;
            }
        }
        if (command != 0) run(command, args);
    }

    private void run(char command, List<Float> a) {
        boolean rel = Character.isLowerCase(command);
        char c = Character.toUpperCase(command);
        if (c == 'Z') {
            ops.add(new float[] {CLOSE});
            x = startX;
            y = startY;
            lastCommand = 'Z';
            return;
        }
        int size = c == 'M' || c == 'L' ? 2 : c == 'H' || c == 'V' ? 1 : c == 'C' ? 6 : c == 'S' || c == 'Q' ? 4 : 7;
        for (int k = 0; k + size <= a.size(); k += size) {
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (c) {
                case 'M':
                    if (k == 0) {
                        x = ox + a.get(k); y = oy + a.get(k + 1);
                        startX = x; startY = y;
                        ops.add(new float[] {MOVE, x, y});
                    } else {
                        line(ox + a.get(k), oy + a.get(k + 1));
                    }
                    break;
                case 'L': line(ox + a.get(k), oy + a.get(k + 1)); break;
                case 'H': line(ox + a.get(k), y); break;
                case 'V': line(x, oy + a.get(k)); break;
                case 'C':
                    cubic(ox + a.get(k), oy + a.get(k + 1), ox + a.get(k + 2), oy + a.get(k + 3), ox + a.get(k + 4), oy + a.get(k + 5));
                    break;
                case 'S': {
                    boolean after = lastCommand == 'C' || lastCommand == 'S';
                    float c1x = after ? 2 * x - lastCx : x, c1y = after ? 2 * y - lastCy : y;
                    cubic(c1x, c1y, ox + a.get(k), oy + a.get(k + 1), ox + a.get(k + 2), oy + a.get(k + 3));
                    break;
                }
                case 'Q': {
                    float qx = ox + a.get(k), qy = oy + a.get(k + 1), ex = ox + a.get(k + 2), ey = oy + a.get(k + 3);
                    cubic(x + 2f / 3 * (qx - x), y + 2f / 3 * (qy - y), ex + 2f / 3 * (qx - ex), ey + 2f / 3 * (qy - ey), ex, ey);
                    break;
                }
                default:
                    arc(a.get(k), a.get(k + 1), a.get(k + 2), a.get(k + 3) != 0, a.get(k + 4) != 0, ox + a.get(k + 5), oy + a.get(k + 6));
            }
            lastCommand = c;
        }
    }

    private void line(float nx, float ny) {
        x = nx; y = ny;
        ops.add(new float[] {LINE, x, y});
    }

    private void cubic(float x1, float y1, float x2, float y2, float nx, float ny) {
        ops.add(new float[] {CUBIC, x1, y1, x2, y2, nx, ny});
        lastCx = x2; lastCy = y2;
        x = nx; y = ny;
    }

    /** SVG's endpoint arc, as cubics of a quarter turn or less (SVG 1.1, F.6.5). */
    private void arc(double rx, double ry, double angle, boolean large, boolean sweep, float ex, float ey) {
        double x1 = x, y1 = y;
        if (x1 == ex && y1 == ey) return;
        rx = Math.abs(rx); ry = Math.abs(ry);
        if (rx == 0 || ry == 0) { line(ex, ey); return; }
        double phi = Math.toRadians(angle), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - ex) / 2, dy = (y1 - ey) / 2;
        double px = cos * dx + sin * dy, py = -sin * dx + cos * dy;
        double lambda = px * px / (rx * rx) + py * py / (ry * ry);
        if (lambda > 1) { rx *= Math.sqrt(lambda); ry *= Math.sqrt(lambda); }
        double num = rx * rx * ry * ry - rx * rx * py * py - ry * ry * px * px;
        double den = rx * rx * py * py + ry * ry * px * px;
        double co = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = co * rx * py / ry, cyp = -co * ry * px / rx;
        double cx = cos * cxp - sin * cyp + (x1 + ex) / 2, cy = sin * cxp + cos * cyp + (y1 + ey) / 2;
        double t1 = Math.atan2((py - cyp) / ry, (px - cxp) / rx);
        double t2 = Math.atan2((-py - cyp) / ry, (-px - cxp) / rx);
        double dt = t2 - t1;
        if (sweep && dt < 0) dt += 2 * Math.PI;
        if (!sweep && dt > 0) dt -= 2 * Math.PI;
        int segments = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2) - 1e-9);
        double step = dt / segments, k = 4.0 / 3 * Math.tan(step / 4);
        double t = t1;
        for (int s = 0; s < segments; s++) {
            double ta = t, tb = t + step;
            double ax = Math.cos(ta), ay = Math.sin(ta), bx = Math.cos(tb), by = Math.sin(tb);
            double[] p1 = point(cx, cy, rx, ry, cos, sin, ax - k * ay, ay + k * ax);
            double[] p2 = point(cx, cy, rx, ry, cos, sin, bx + k * by, by - k * bx);
            double[] p3 = s == segments - 1 ? new double[] {ex, ey} : point(cx, cy, rx, ry, cos, sin, bx, by);
            cubic((float) p1[0], (float) p1[1], (float) p2[0], (float) p2[1], (float) p3[0], (float) p3[1]);
            t = tb;
        }
    }

    private static double[] point(double cx, double cy, double rx, double ry, double cos, double sin, double ux, double uy) {
        double px = rx * ux, py = ry * uy;
        return new double[] {cx + cos * px - sin * py, cy + sin * px + cos * py};
    }
}
