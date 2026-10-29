package com.print.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.print.xp3.ipp.Capabilities;
import com.xp3.common.ui.Ui;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ink Levels (screen 17): the supplies of the printer in use, as tall bars
 * in their own colours, with a note when one is low. Left and Right go
 * through the other known printers. Nothing here takes the focus.
 */
final class InkScreen extends Screen {

    private final List<Printer> printers = new ArrayList<>();
    private int at;
    private LinearLayout body;

    @Override
    View build() {
        Context c = app;
        Store store = Store.get(app);
        Printer current = store.current();
        if (current != null) printers.add(current);
        for (Printer printer : store.printers()) if (!printers.contains(printer)) printers.add(printer);
        body = Parts.column(c);
        // Keys need somewhere to land, and nothing on the screen acts.
        body.setFocusable(true);
        body.setDefaultFocusHighlightEnabled(false);
        show();
        return Parts.page(c, Parts.nav(c, "Ink Levels", true, null), body);
    }

    @Override
    View first() {
        return body;
    }

    private void show() {
        Context c = app;
        body.removeAllViews();
        if (printers.isEmpty()) {
            body.addView(Parts.status(c, Parts.badge(c, R.drawable.ic_drop, Look.ICON_FILL, Look.BLUE), 56,
                "No printer chosen", "Choose a printer in Printers to see its ink.", null, null, 40));
            return;
        }
        final Printer printer = printers.get(at);
        Parts.Row head = new Parts.Row(c, 42);
        head.setFocusable(false);
        ImageView picture = new ImageView(c);
        picture.setImageResource(Readiness.picture(printer));
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        head.lead(picture, Ui.dp(c, 38), Ui.dp(c, 27));
        head.titled(printer.shown(), "Checking…");
        if (printers.size() > 1) {
            head.trailing(Look.text(c, (at + 1) + " of " + printers.size(), 9.5f, Look.SEMIBOLD, Look.MUTE));
        }
        body.addView(head);
        final LinearLayout levels = Parts.column(c);
        body.addView(levels);
        final int shownAt = at;
        Readiness.check(printer, readiness -> {
            if (shownAt != at || app == null) return;
            head.sub.setText(readiness.text + paperLoaded(printer));
            head.sub.setTextColor(readiness.kind == Readiness.READY || readiness.kind == Readiness.BUSY ? Look.OK
                : Readiness.color(readiness));
            levels(levels, printer, readiness);
        });
    }

    /** " · Letter paper loaded", when the printer says which. */
    private static String paperLoaded(Printer printer) {
        Capabilities caps = printer.ipp;
        if (caps == null || caps.mediaDefault.isEmpty() || Media.points(caps.mediaDefault) == null) return "";
        return " · " + Media.shown(caps.mediaDefault) + " paper loaded";
    }

    private void levels(LinearLayout into, Printer printer, Readiness readiness) {
        Context c = app;
        into.removeAllViews();
        Capabilities caps = printer.ipp;
        List<Capabilities.Marker> markers = caps == null ? Collections.emptyList() : caps.markers;
        if (readiness.kind == Readiness.UNREACHABLE) {
            into.addView(Parts.status(c, null, 0, "Can't reach the printer",
                "Check that it is on and connected to " + Wifi.name(c) + ".", null, null, 24));
            return;
        }
        if (markers.isEmpty()) {
            into.addView(Parts.status(c, null, 0, "No ink levels",
                "This printer doesn't say how much ink or toner it has left.", null, null, 24));
            return;
        }
        LinearLayout row = Parts.row(c);
        row.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), 0);
        Capabilities.Marker lowest = null;
        for (Capabilities.Marker marker : markers) {
            LinearLayout one = Parts.column(c);
            one.setGravity(Gravity.CENTER_HORIZONTAL);
            boolean low = marker.isLow();
            if (low && (lowest == null || marker.level < lowest.level)) lowest = marker;
            one.addView(Look.text(c, marker.level < 0 ? "?" : marker.level + "%", 12, Look.BOLD, low ? Look.WARN : Look.INK));
            LinearLayout.LayoutParams tubeAt = Parts.line(Ui.dp(c, 24), Ui.dp(c, markers.size() > 4 ? 80 : 96));
            tubeAt.topMargin = tubeAt.bottomMargin = Ui.dp(c, 3);
            one.addView(new Tube(c, marker.level, color(marker)), tubeAt);
            String name = Readiness.colorName(marker);
            one.addView(Look.line(c, name.isEmpty() ? "Ink" : name, 9.5f, Look.SEMIBOLD, Look.MUTE));
            row.addView(one, new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
        }
        into.addView(row);
        if (lowest != null) {
            LinearLayout note = Parts.row(c);
            note.setGravity(Gravity.TOP);
            note.setBackground(Look.rounded(0xFFFFF5E1, 6, c));
            note.setPadding(Ui.dp(c, 9), Ui.dp(c, 7), Ui.dp(c, 9), Ui.dp(c, 7));
            ImageView sign = Parts.image(c, R.drawable.ic_alert, Look.WARN, 13);
            ((LinearLayout.LayoutParams) sign.getLayoutParams()).rightMargin = Ui.dp(c, 6);
            note.addView(sign);
            String what = Readiness.supply(lowest);
            TextView text = Look.text(c, what + (lowest.level == 0 ? " is empty. Replace it to print in color." : " is low. Have a new one ready."),
                10, Look.REGULAR, 0xFF7A4A00);
            note.addView(text, Parts.weighted());
            LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Parts.WRAP);
            lp.setMargins(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), 0);
            into.addView(note, lp);
        }
        TextView updated = Parts.foot(c, "Updated just now");
        updated.setPadding(0, Ui.dp(c, 6), 0, 0);
        into.addView(updated);
    }

    /** The bar's colour: the printer's own for the supply, the proposal's for the four inks. */
    private static int color(Capabilities.Marker marker) {
        String name = Readiness.colorName(marker).toLowerCase(Locale.ROOT);
        if (name.contains("cyan")) return 0xFF14B4E8;
        if (name.contains("magenta")) return 0xFFE8338A;
        if (name.contains("yellow")) return 0xFFF7CC1A;
        if (name.contains("black")) return 0xFF24262D;
        String hex = marker.color;
        if (hex.length() >= 7 && hex.startsWith("#")) {
            try {
                return 0xFF000000 | Integer.parseInt(hex.substring(1, 7), 16);
            } catch (NumberFormatException ignored) {
                // Fall through to grey.
            }
        }
        return 0xFF8A8F9C;
    }

    /** A tall rounded bar, filled from the bottom. */
    private static final class Tube extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int level;
        private final int color;

        Tube(Context c, int level, int color) {
            super(c);
            this.level = level;
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = 5 * getResources().getDisplayMetrics().density;
            paint.setColor(0xFFECEEF2);
            canvas.drawRoundRect(0, 0, w, h, r, r, paint);
            if (level <= 0) return;
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setColor(color);
            canvas.drawRect(0, h - h * level / 100f, w, h, paint);
            canvas.restore();
        }
    }

    @Override
    boolean key(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (down(event) && printers.size() > 1) {
            at = (at + (code == KeyEvent.KEYCODE_DPAD_LEFT ? printers.size() - 1 : 1)) % printers.size();
            show();
        }
        return true;
    }
}
