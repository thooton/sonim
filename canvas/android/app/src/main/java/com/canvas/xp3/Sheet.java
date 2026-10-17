package com.canvas.xp3;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xp3.common.ui.Ui;

import java.util.List;

/**
 * What slides over a screen: the numbered Options menu, a sheet of choices,
 * and a short question with two buttons.
 *
 * Each is drawn in the page itself over a dimmed screen, not as a system
 * dialog, so it keeps the type, the highlighter and the softkey bar. A menu's
 * numbers are keys: pressing one picks that row.
 */
final class Sheet {

    /** One row of a menu or one option of a choice. */
    static final class Choice {
        final String label;
        final int icon;
        final String note;
        final Runnable action;
        /** For a choice sheet: whether this is the current value. */
        final boolean chosen;

        Choice(String label, int icon, String note, Runnable action) {
            this(label, icon, note, action, false);
        }

        Choice(String label, int icon, String note, Runnable action, boolean chosen) {
            this.label = label;
            this.icon = icon;
            this.note = note;
            this.action = action;
            this.chosen = chosen;
        }
    }

    /** What the activity needs to run an open overlay. */
    static final class Open {
        final View view;
        final String left;
        final String center;
        final String right;
        final List<Choice> numbered;
        final Runnable onRight;

        Open(View view, String left, String center, String right, List<Choice> numbered, Runnable onRight) {
            this.view = view;
            this.left = left;
            this.center = center;
            this.right = right;
            this.numbered = numbered;
            this.onRight = onRight;
        }

        /** Digits pick a numbered row. */
        boolean key(MainActivity app, KeyEvent event) {
            if (numbered == null || event.getAction() != KeyEvent.ACTION_UP) return false;
            int code = event.getKeyCode();
            if (code < KeyEvent.KEYCODE_1 || code > KeyEvent.KEYCODE_9) return false;
            int index = code - KeyEvent.KEYCODE_1;
            if (index >= numbered.size()) return false;
            app.closeOverlay();
            numbered.get(index).action.run();
            return true;
        }
    }

    private Sheet() {}

    /** The Options menu (A5) and Add your work (D1): numbered rows over the screen. */
    static void menu(final MainActivity app, String title, final List<Choice> choices) {
        Views v = app.views();
        Look look = v.look;
        LinearLayout sheet = sheet(v, title);
        for (int i = 0; i < choices.size(); i++) {
            final Choice choice = choices.get(i);
            Item row = new Item(app, "menu:" + i, "Select", () -> {
                app.closeOverlay();
                choice.action.run();
            });
            LinearLayout line = v.row();
            v.pad(line, 12, 5, 12, 5);
            TextView badge = v.key(Integer.toString(i + 1));
            line.addView(badge);
            View tile = v.tile(choice.icon, look.paper, look.ink, 26, 14, 8);
            line.addView(tile, v.margins(new LinearLayout.LayoutParams(v.dp(26), v.dp(26)), 9, 0, 9, 0));
            MarkText name = v.mark(choice.label, 11.5f, look.semibold, look.ink);
            line.addView(name, Views.fill());
            if (choice.note != null) line.addView(v.text(choice.note, 9.5f, look.semibold, look.muted));
            row.addView(line, Views.wide());
            row.keyed(badge, name, v);
            sheet.addView(row, Views.wide());
        }
        app.openOverlay(new Open(wrap(app, sheet, true), "Close", "Select", null, choices, null));
    }

    /** A choice among values (H3): large rows, the current one filled with an ink dot. */
    static void pick(final MainActivity app, String title, final List<Choice> choices, String note) {
        Views v = app.views();
        Look look = v.look;
        LinearLayout sheet = sheet(v, title);
        int start = 0;
        for (int i = 0; i < choices.size(); i++) {
            final Choice choice = choices.get(i);
            if (choice.chosen) start = i;
            Item option = option(app, v, "pick:" + i, choice.label, choice.chosen, () -> {
                app.closeOverlay();
                choice.action.run();
            });
            sheet.addView(option, v.margins(Views.wide(), 12, i == 0 ? 2 : 5, 12, 0));
        }
        if (note != null) {
            TextView help = v.text(note, 9, look.regular, look.muted);
            help.setLineSpacing(0, 1.2f);
            v.pad(help, 12, 6, 12, 2);
            sheet.addView(help, Views.wide());
        }
        View wrapped = wrap(app, sheet, true);
        app.openOverlay(new Open(wrapped, "Cancel", "Select", null, null, null));
        Item first = Screen.find(wrapped, "pick:" + start);
        if (first != null) first.requestFocus();
    }

    /**
     * A large option row: a radio, then the label. Chosen is an ink border and
     * dot; focus is the yellow fill. Used by choice sheets and quiz questions.
     */
    static Item option(MainActivity app, final Views v, String key, String label, boolean chosen, Runnable action) {
        final Look look = v.look;
        Item option = new Item(app, key, "Select", action);
        LinearLayout line = v.row();
        v.pad(line, 9, 7, 9, 7);
        View radio = radio(v, chosen, false);
        line.addView(radio);
        TextView name = v.text(label, 11, look.semibold, look.ink);
        name.setLineSpacing(0, 1.15f);
        line.addView(name, v.margins(Views.fill(), 8, 0, 0, 0));
        option.addView(line, Views.wide());
        final FrameLayout holder = (FrameLayout) radio;
        option.fills(v.round(look.bg, 10, chosen ? look.ink : look.line, 1.5f),
            v.round(look.mark, 10, look.mark, 1.5f), new TextView[] { name }, new int[] { look.ink });
        option.also(has -> {
            holder.removeAllViews();
            fillRadio(v, holder, chosen, has);
        });
        return option;
    }

    private static View radio(Views v, boolean chosen, boolean focused) {
        FrameLayout ring = new FrameLayout(v.context);
        ring.setLayoutParams(new LinearLayout.LayoutParams(v.dp(13), v.dp(13)));
        fillRadio(v, ring, chosen, focused);
        return ring;
    }

    private static void fillRadio(Views v, FrameLayout ring, boolean chosen, boolean focused) {
        Look look = v.look;
        int edge = focused ? Look.ON_MARK : chosen ? look.ink : look.faint;
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setStroke(Math.max(1, v.dp(1.5f)), edge);
        ring.setBackground(circle);
        if (chosen) {
            View dot = v.dot(focused ? Look.ON_MARK : look.ink, 7);
            ring.addView(dot, new FrameLayout.LayoutParams(v.dp(7), v.dp(7), Gravity.CENTER));
        }
    }

    /**
     * A question with two answers (F1, F3). The first button has focus, so
     * OK twice from the screen underneath is all it takes.
     */
    static void ask(final MainActivity app, String title, String message, String yes, String no,
                    final Runnable onYes) {
        Views v = app.views();
        Look look = v.look;
        LinearLayout box = v.column();
        box.setBackground(v.round(look.bg, 14, 0, 0));
        v.pad(box, 0, 12, 0, 10);
        TextView heading = v.text(title, 14, look.heavy, look.ink);
        heading.setLetterSpacing(-0.01f);
        v.pad(heading, 12, 0, 12, 0);
        box.addView(heading, Views.wide());
        TextView said = v.text(message, 10, look.regular, look.body);
        said.setLineSpacing(0, 1.3f);
        v.pad(said, 12, 4, 12, 0);
        box.addView(said, Views.wide());
        Item primary = button(app, v, "ask:yes", yes, true, () -> {
            app.closeOverlay();
            onYes.run();
        });
        box.addView(primary, v.margins(new LinearLayout.LayoutParams(-1, v.dp(28)), 12, 10, 12, 0));
        Item secondary = button(app, v, "ask:no", no, false, app::closeOverlay);
        box.addView(secondary, v.margins(new LinearLayout.LayoutParams(-1, v.dp(28)), 12, 8, 12, 0));

        FrameLayout layer = new FrameLayout(app);
        View shade = new View(app);
        shade.setBackgroundColor(look.dim);
        layer.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        at.setMargins(v.dp(12), 0, v.dp(12), 0);
        layer.addView(box, at);
        app.openOverlay(new Open(layer, "Cancel", yes, null, null, null));
        primary.requestFocus();
    }

    /**
     * A full-width button: ink outline, or ink fill for the main action;
     * yellow while it has focus.
     */
    static Item button(MainActivity app, Views v, String key, String label, boolean primary, Runnable action) {
        Look look = v.look;
        Item button = new Item(app, key, label, action);
        button.setGravity(Gravity.CENTER);
        TextView name = v.text(label, 11.5f, look.heavy, primary ? look.bg : look.ink);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        button.addView(name, Views.wrap());
        button.fills(primary ? v.round(look.ink, 9, look.ink, 1.5f) : v.round(0, 9, look.ink, 1.5f),
            v.round(look.mark, 9, look.mark, 1.5f), new TextView[] { name },
            new int[] { primary ? look.bg : look.ink });
        return button;
    }

    private static LinearLayout sheet(Views v, String title) {
        Look look = v.look;
        LinearLayout sheet = v.column();
        android.graphics.drawable.GradientDrawable top = new android.graphics.drawable.GradientDrawable();
        top.setColor(look.bg);
        float r = v.dp(14);
        top.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        sheet.setBackground(top);
        v.pad(sheet, 0, 5, 0, 4);
        View grab = new View(v.context);
        grab.setBackground(v.round(look.line, 2, 0, 0));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(v.dp(28), v.dp(3));
        at.gravity = Gravity.CENTER_HORIZONTAL;
        at.bottomMargin = v.dp(4);
        sheet.addView(grab, at);
        TextView heading = v.text(title, 12, look.heavy, look.ink);
        v.pad(heading, 12, 2, 12, 3);
        sheet.addView(heading, Views.wide());
        return sheet;
    }

    private static View wrap(MainActivity app, LinearLayout sheet, boolean bottom) {
        Views v = app.views();
        FrameLayout layer = new FrameLayout(app);
        View shade = new View(app);
        shade.setBackgroundColor(v.look.dim);
        layer.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(app);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(sheet, new FrameLayout.LayoutParams(-1, -2));
        layer.addView(scroll, new FrameLayout.LayoutParams(-1, -2, bottom ? Gravity.BOTTOM : Gravity.CENTER));
        return layer;
    }

    static boolean isOk(int code) {
        return Ui.isOk(code);
    }
}
