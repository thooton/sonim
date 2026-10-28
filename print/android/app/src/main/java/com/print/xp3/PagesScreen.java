package com.print.xp3;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.xp3.common.ui.Ui;
import android.widget.TextView;

/**
 * Pages (screen 08): all of them, a range typed into two boxes, or only the
 * page in the preview, with a strip of pages showing what will print. In
 * the range, number keys type into the box in focus and Left and Right
 * move between the boxes; an end before the start is swapped. Done saves.
 */
final class PagesScreen extends Screen {

    private static final int ALL = 0;
    private static final int RANGE = 1;
    private static final int CURRENT = 2;

    private final Draft draft;
    private int choice;
    private int from;
    private int to;
    /** Which box takes the keys: 0 the start, 1 the end. */
    private int box;
    /** Whether the next digit starts the box afresh. */
    private boolean fresh = true;

    private Parts.Row[] rows;
    private Parts.Radio[] radios;
    private TextView fromBox;
    private TextView toBox;
    private LinearLayout strip;
    private TextView foot;

    PagesScreen(Draft draft) {
        this.draft = draft;
        from = draft.options.from;
        to = draft.options.to;
        choice = draft.allPages() ? ALL : from == to && from == draft.page + 1 ? CURRENT : RANGE;
    }

    @Override
    View build() {
        Context c = app;
        LinearLayout body = Parts.column(c);
        rows = new Parts.Row[3];
        radios = new Parts.Radio[3];
        String[] titles = {"All pages", "Page range", "Current page only"};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            Parts.Row row = new Parts.Row(c, i == RANGE ? 58 : 40);
            Parts.Radio radio = new Parts.Radio(c, false);
            row.lead(radio, Ui.dp(c, 14), Ui.dp(c, 14));
            row.titled(titles[i], i == ALL ? (draft.pages == 1 ? "Page 1" : "Pages 1–" + draft.pages)
                : i == CURRENT ? "Page " + (draft.page + 1) : null);
            if (i == RANGE) {
                LinearLayout range = Parts.row(c);
                range.addView(Look.text(c, "From", 11, Look.REGULAR, Look.INK));
                fromBox = numberBox(c);
                range.addView(fromBox, boxAt(c));
                range.addView(Look.text(c, "to", 11, Look.REGULAR, Look.INK));
                toBox = numberBox(c);
                range.addView(toBox, boxAt(c));
                LinearLayout.LayoutParams under = Parts.line(Parts.WRAP, Parts.WRAP);
                under.topMargin = Ui.dp(c, 2);
                row.text.addView(range, under);
            }
            row.setOnClickListener(v -> {
                choice = which;
                refresh();
            });
            row.setOnFocusChangeListener((v, has) -> {
                if (which == RANGE && has) fresh = true;
                refresh();
            });
            rows[i] = row;
            radios[i] = radio;
            body.addView(row);
        }
        strip = Parts.row(c);
        strip.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), 0);
        body.addView(strip);
        foot = Parts.foot(c, "");
        body.addView(foot);
        body.addView(Parts.buttons(c, Parts.button(c, "Done", Parts.PRIMARY, this::done)));
        refresh();
        return Parts.page(c, Parts.nav(c, "Pages", true, null), body);
    }

    private static TextView numberBox(Context c) {
        TextView box = Look.text(c, "", 11.5f, Look.BOLD, Look.INK);
        box.setGravity(Gravity.CENTER);
        box.setMinWidth(Ui.dp(c, 30));
        box.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
        return box;
    }

    private static LinearLayout.LayoutParams boxAt(Context c) {
        LinearLayout.LayoutParams lp = Parts.line(Parts.WRAP, Ui.dp(c, 24));
        lp.leftMargin = lp.rightMargin = Ui.dp(c, 3);
        return lp;
    }

    @Override
    View first() {
        return rows[choice];
    }

    private int[] chosen() {
        if (choice == ALL) return new int[] {1, draft.pages};
        if (choice == CURRENT) return new int[] {draft.page + 1, draft.page + 1};
        int a = clamp(from);
        int b = clamp(to);
        return new int[] {Math.min(a, b), Math.max(a, b)};
    }

    private int clamp(int page) {
        return Math.max(1, Math.min(draft.pages, page));
    }

    private void refresh() {
        for (int i = 0; i < 3; i++) radios[i].set(i == choice);
        boolean typing = rows[RANGE].isFocused();
        fromBox.setText(from > 0 ? String.valueOf(from) : "");
        toBox.setText(to > 0 ? String.valueOf(to) : "");
        fromBox.setBackground(Look.box(app, typing && box == 0));
        toBox.setBackground(Look.box(app, typing && box == 1));
        int[] range = chosen();
        int count = range[1] - range[0] + 1;
        foot.setText(count + " of " + draft.pages + (draft.pages == 1 ? " page" : " pages") + " selected");
        drawStrip(range);
    }

    /** Up to six pages around the range, those that print outlined in blue. */
    private void drawStrip(int[] range) {
        Context c = app;
        strip.removeAllViews();
        int first = Math.max(1, Math.min(range[0] - 1, draft.pages - 5));
        int last = Math.min(draft.pages, first + 5);
        for (int p = first; p <= last; p++) {
            boolean in = p >= range[0] && p <= range[1];
            LinearLayout cell = Parts.column(c);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            final ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumb.setBackground(in ? Look.outlined(Look.WHITE, 0, 1.5f, Look.BLUE, c) : Parts.fill(0xFFDCDEE3));
            int pad = in ? Ui.dp(c, 1.5f) : 1;
            thumb.setPadding(pad, pad, pad, pad);
            thumb.setAlpha(in ? 1f : 0.4f);
            cell.addView(thumb, Parts.line(Ui.dp(c, 28), Ui.dp(c, 36)));
            TextView number = Look.text(c, String.valueOf(p), 8.5f, Look.SEMIBOLD, in ? Look.BLUE : Look.MUTE);
            LinearLayout.LayoutParams under = Parts.line(Parts.WRAP, Parts.WRAP);
            under.topMargin = Ui.dp(c, 2);
            cell.addView(number, under);
            strip.addView(cell, new LinearLayout.LayoutParams(0, Parts.WRAP, 1));
            Pages.draw(draft.file, p - 1, Ui.dp(c, 26), Ui.dp(c, 34), bitmap -> {
                if (bitmap != null) thumb.setImageBitmap(bitmap);
            });
        }
    }

    private void done() {
        int[] range = chosen();
        draft.options.from = range[0];
        draft.options.to = range[1];
        app.close();
    }

    @Override
    boolean key(KeyEvent event) {
        if (!rows[RANGE].isFocused()) return false;
        int code = event.getKeyCode();
        boolean down = down(event);
        int digit = digit(event);
        if (digit >= 0) {
            if (down) {
                choice = RANGE;
                int value = fresh ? digit : (box == 0 ? from : to) * 10 + digit;
                if (value > 9999) value = digit;
                if (box == 0) from = value;
                else to = value;
                fresh = false;
                refresh();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_STAR) {
            if (down) {
                box = code == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 1 - box;
                fresh = true;
                tidy();
                refresh();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_CLEAR) {
            if (down) {
                if (box == 0) from /= 10;
                else to /= 10;
                fresh = false;
                refresh();
            }
            return true;
        }
        if (down && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) tidy();
        return false;
    }

    /** Pages that exist, the start before the end. */
    private void tidy() {
        if (from <= 0) from = 1;
        if (to <= 0) to = from;
        from = clamp(from);
        to = clamp(to);
        if (to < from) {
            int swap = from;
            from = to;
            to = swap;
        }
    }
}
