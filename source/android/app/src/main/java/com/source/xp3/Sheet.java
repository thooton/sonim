package com.source.xp3;

import android.app.Dialog;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.xp3.common.ui.Ui;
import java.util.ArrayList;
import java.util.List;

/**
 * The options sheet, opened with Menu: rows of icon, name and the value it
 * is set to, over a scrim at the foot of the screen. Up and Down move, OK
 * does the row (and closes the sheet), Back or Menu closes it.
 */
final class Sheet {

    private static final class Row {
        final int icon;
        final String label;
        final String value;
        final Runnable action;

        Row(int icon, String label, String value, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.value = value;
            this.action = action;
        }
    }

    private final Tonal activity;
    private final List<Row> rows = new ArrayList<Row>();
    private final List<View> views = new ArrayList<View>();
    private int picked;

    Sheet(Tonal activity) {
        this.activity = activity;
    }

    /** A row; `value`, what it is set to, may be null. */
    Sheet add(int icon, String label, String value, Runnable action) {
        rows.add(new Row(icon, label, value, action));
        return this;
    }

    void show() {
        if (rows.isEmpty()) return;
        final Dialog dialog = new Dialog(activity, R.style.Sheet);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.sheet);
        View handle = new View(activity);
        handle.setBackgroundResource(R.drawable.sheet_handle);
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(Ui.dp(activity, 28), Ui.dp(activity, 3));
        handleSize.gravity = Gravity.CENTER_HORIZONTAL;
        handleSize.topMargin = Ui.dp(activity, 6);
        handleSize.bottomMargin = Ui.dp(activity, 4);
        sheet.addView(handle, handleSize);

        final ScrollView scroll = new ScrollView(activity);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, Ui.dp(activity, 4));
        for (Row row : rows) {
            View view = rowView(row);
            views.add(view);
            list.addView(view);
        }
        scroll.addView(list);
        // Every row is one line at its least height; at most most of the
        // screen, so the scrim shows what the sheet is over.
        int most = activity.getResources().getDisplayMetrics().heightPixels * 2 / 3;
        int tall = Math.min(most, rows.size() * Ui.dp(activity, 30) + Ui.dp(activity, 4));
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tall));

        dialog.setContentView(sheet, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Window window = dialog.getWindow();
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);

        dialog.setOnKeyListener((shown, key, event) -> {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (Ui.isOk(key)) {
                if (down && event.getRepeatCount() == 0) {
                    dialog.dismiss();
                    rows.get(picked).action.run();
                }
                return true;
            }
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) {
                        int step = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                        pick((picked + step + rows.size()) % rows.size(), scroll);
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    if (!down) dialog.dismiss();
                    return true;
                default:
                    return false;
            }
        });
        pick(0, scroll);
        dialog.show();
    }

    private void pick(int index, final ScrollView scroll) {
        picked = index;
        for (int i = 0; i < views.size(); i++) {
            View view = views.get(i);
            view.setActivated(i == index);
            ((TextView) view.findViewById(android.R.id.text1)).setTextColor(
                activity.getColor(i == index ? R.color.on_accent_container : R.color.text));
        }
        final View row = views.get(index);
        scroll.post(() -> {
            int top = row.getTop();
            int bottom = row.getBottom();
            if (top < scroll.getScrollY()) scroll.smoothScrollTo(0, top);
            else if (bottom > scroll.getScrollY() + scroll.getHeight()) scroll.smoothScrollTo(0, bottom - scroll.getHeight());
        });
    }

    private View rowView(Row row) {
        LinearLayout view = activity.row(Gravity.CENTER_VERTICAL);
        view.setBackgroundResource(R.drawable.row_pick);
        int pad = Ui.dp(activity, 16);
        view.setPadding(pad, 0, pad, 0);
        view.setMinimumHeight(Ui.dp(activity, 30));

        TextView label = Tonal.sized(activity, R.dimen.text_row, R.color.text);
        label.setId(android.R.id.text1);
        label.setText(row.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setCompoundDrawablePadding(Ui.dp(activity, 10));
        label.setCompoundDrawablesRelative(Tonal.icon(activity, row.icon, R.color.text_muted, 16), null, null, null);
        view.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (row.value != null && row.value.length() > 0) {
            TextView value = Tonal.sized(activity, R.dimen.text_meta, R.color.text_muted);
            value.setText(row.value);
            value.setSingleLine(true);
            value.setPadding(Ui.dp(activity, 6), 0, 0, 0);
            view.addView(value);
        }
        return view;
    }
}
