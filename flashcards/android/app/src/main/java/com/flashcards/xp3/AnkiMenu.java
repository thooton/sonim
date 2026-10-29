package com.flashcards.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Anki's pop-up menu, as its desktop windows show one: a bordered panel with
 * a check column on the left, the label, a shortcut on the right (here the
 * digit that picks the item), hairlines between groups and a blue bar under
 * the highlighted item.
 *
 * It is a view laid over the window, not a platform dialog, so Night Shift
 * and Dark mode reach it. It takes no focus: the activity hands it the keys.
 */
final class AnkiMenu extends FrameLayout {

    /** One entry; `action` runs once the menu has closed. */
    static final class Item {
        final String label;
        final Runnable action;
        boolean checked;
        boolean checkable;
        boolean separatorBefore;

        Item(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }

        Item checked(boolean on) {
            checkable = true;
            checked = on;
            return this;
        }

        Item group() {
            separatorBefore = true;
            return this;
        }
    }

    private final List<Item> items;
    private final Runnable close;
    private final List<View> rows = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private final List<TextView> marks = new ArrayList<>();
    private final List<TextView> hints = new ArrayList<>();
    private final ScrollView scroll;
    private int at;

    AnkiMenu(Context context, List<Item> items, Runnable close) {
        super(context);
        this.items = items;
        this.close = close;
        setClickable(true);
        setOnClickListener(v -> close.run());

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable face = new GradientDrawable();
        face.setColor(context.getColor(R.color.elevated));
        face.setCornerRadius(Ui.dp(context, 5));
        face.setStroke(Ui.dp(context, 1), context.getColor(R.color.border));
        panel.setBackground(face);
        int inset = Ui.dp(context, 3);
        panel.setPadding(0, inset, 0, inset);
        panel.setClickable(true);

        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.separatorBefore && i > 0) {
                View rule = new View(context);
                rule.setBackgroundColor(context.getColor(R.color.border_subtle));
                LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 1));
                line.setMargins(Ui.dp(context, 6), inset, Ui.dp(context, 6), inset);
                panel.addView(rule, line);
            }
            panel.addView(row(context, i, item), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 28)));
        }
        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        LayoutParams place = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int margin = Ui.dp(context, 6);
        place.setMargins(margin, margin, margin, margin);
        addView(scroll, place);
        paint();
    }

    private View row(Context context, int index, Item item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 10), 0);

        TextView mark = new TextView(context);
        mark.setText(item.checked ? "✓" : "");
        mark.setTextSize(12);
        mark.setGravity(Gravity.CENTER);
        mark.setIncludeFontPadding(false);
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(context, 20), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(context);
        label.setText(item.label);
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = new TextView(context);
        hint.setText(index < 9 ? Integer.toString(index + 1) : "");
        hint.setTextSize(11);
        hint.setIncludeFontPadding(false);
        LinearLayout.LayoutParams after = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        after.leftMargin = Ui.dp(context, 10);
        row.addView(hint, after);

        row.setOnClickListener(v -> {
            at = index;
            choose();
        });
        rows.add(row);
        marks.add(mark);
        labels.add(label);
        hints.add(hint);
        return row;
    }

    /** The highlighted item gets Anki's blue bar and white text; the rest are plain. */
    private void paint() {
        Context context = getContext();
        for (int i = 0; i < rows.size(); i++) {
            boolean on = i == at;
            if (on) {
                GradientDrawable bar = new GradientDrawable();
                bar.setColor(context.getColor(R.color.focus));
                bar.setCornerRadius(Ui.dp(context, 3));
                rows.get(i).setBackground(bar);
            } else {
                rows.get(i).setBackground(null);
            }
            int text = context.getColor(on ? android.R.color.white : R.color.text);
            int quiet = context.getColor(on ? android.R.color.white : R.color.text_subtle);
            labels.get(i).setTextColor(text);
            marks.get(i).setTextColor(text);
            hints.get(i).setTextColor(quiet);
        }
        final View row = rows.get(at);
        scroll.post(() -> {
            int top = row.getTop();
            int bottom = row.getBottom();
            if (top < scroll.getScrollY()) scroll.scrollTo(0, top);
            else if (bottom > scroll.getScrollY() + scroll.getHeight()) scroll.scrollTo(0, bottom - scroll.getHeight());
        });
    }

    private void choose() {
        Runnable action = items.get(at).action;
        close.run();
        action.run();
    }

    /** Routes one key event; true when the menu used it. Everything is consumed while it is open. */
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled();
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) {
                int step = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                at = (at + step + items.size()) % items.size();
                paint();
            }
        } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (up) choose();
        } else if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (up) close.run();
        } else if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9) {
            int picked = code - KeyEvent.KEYCODE_1;
            if (up && picked < items.size()) {
                at = picked;
                choose();
            }
        }
        return true;
    }
}
