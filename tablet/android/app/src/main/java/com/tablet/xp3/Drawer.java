package com.tablet.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The menu drawer, slid in from the left by the Menu key: the tablet and
 * when it was last read, the app's places (the one you are on under the
 * highlighter, Transforms with how its running job is going), and the
 * command center the tablet is read through.
 */
final class Drawer extends LinearLayout {

    static final int LIBRARY = 0;
    static final int TRANSFORMS = 1;
    static final int UPLOAD = 2;
    static final int SETTINGS = 3;

    private final Parts.Action[] items = new Parts.Action[4];
    private final TextView files;
    private final TextView progress;
    private final TextView count;
    private final TextView synced;
    private final TextView host;

    Drawer(Context c) {
        super(c);
        setOrientation(VERTICAL);
        // The sheet with an ink edge on its right.
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(Look.INK);
        GradientDrawable paper = new GradientDrawable();
        paper.setColor(Look.SHEET);
        LayerDrawable bg = new LayerDrawable(new android.graphics.drawable.Drawable[] {edge, new InsetDrawable(paper, 0, 0, Look.dp(c, 1.5f), 0)});
        setBackground(bg);
        setElevation(Look.px(c, 8));
        setPadding(Look.dp(c, 8), Look.dp(c, 10), Look.dp(c, 9.5f), Look.dp(c, 8));

        LinearLayout device = Parts.column(c);
        device.setPadding(Look.dp(c, 6), 0, Look.dp(c, 6), Look.dp(c, 10));
        device.addView(Look.text(c, "Tablet", Look.serifBold, 13, Look.INK));
        synced = Look.text(c, "", Look.ui, 9, Look.GRAPHITE);
        device.addView(synced, top(c, 3));
        addView(device);
        addView(Parts.rule(c), Parts.line(Parts.FILL, 1));

        files = Look.text(c, "", Look.ui, 9, Look.GRAPHITE);
        progress = Look.text(c, "", Look.ui, 9, Look.GRAPHITE);
        count = Look.text(c, "", Look.uiBold, 9, Look.SHEET);
        count.setGravity(Gravity.CENTER);
        count.setPadding(Look.dp(c, 5), Look.dp(c, 2), Look.dp(c, 5), Look.dp(c, 2));
        count.setBackground(Look.round(Look.INK, 8, c));

        items[LIBRARY] = item(c, R.drawable.ic_book, "Library", files, null);
        items[TRANSFORMS] = item(c, R.drawable.ic_sparkle, "Transforms", progress, count);
        items[UPLOAD] = item(c, R.drawable.ic_upload, "Upload", null, null);
        items[SETTINGS] = item(c, R.drawable.ic_gear, "Settings", null, null);

        View spring = new View(c);
        LayoutParams grow = new LayoutParams(Parts.FILL, 0);
        grow.weight = 1;
        addView(spring, grow);
        addView(Parts.rule(c), Parts.line(Parts.FILL, 1));
        LinearLayout foot = Parts.column(c);
        foot.setPadding(Look.dp(c, 6), Look.dp(c, 8), Look.dp(c, 6), 0);
        foot.addView(Look.cap(c, "Command center", Look.GRAPHITE));
        host = Look.text(c, "", Look.ui, 9, Look.GRAPHITE);
        host.setSingleLine();
        host.setEllipsize(android.text.TextUtils.TruncateAt.END);
        foot.addView(host, top(c, 3));
        addView(foot);
    }

    private static LayoutParams top(Context c, float margin) {
        LayoutParams lp = new LayoutParams(Parts.WRAP, Parts.WRAP);
        lp.topMargin = Look.dp(c, margin);
        return lp;
    }

    private Parts.Action item(Context c, int icon, String name, TextView sub, TextView badge) {
        LinearLayout box = Parts.row(c);
        LinearLayout text = Parts.column(c);
        Parts.Action action = new Parts.Action(c, icon, name);
        Look.style(action.label, Look.ui, 12, Look.INK);
        action.setPadding(0, 0, 0, 0);
        text.addView(action, Parts.line(Parts.FILL, Look.dp(c, 19)));
        if (sub != null) {
            sub.setSingleLine();
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LayoutParams lp = top(c, 1);
            lp.leftMargin = Look.dp(c, 29);
            text.addView(sub, lp);
        }
        LayoutParams grow = new LayoutParams(0, Parts.WRAP);
        grow.weight = 1;
        box.addView(text, grow);
        if (badge != null) box.addView(badge, new LayoutParams(Parts.WRAP, Parts.WRAP));
        box.setPadding(0, Look.dp(c, 4), 0, Look.dp(c, 4));
        LayoutParams lp = new LayoutParams(Parts.FILL, Parts.WRAP);
        lp.topMargin = Look.dp(c, 8);
        addView(box, lp);
        return action;
    }

    /** `picked` is the highlighted place; the rest is how things stand. */
    void show(int picked, int fileCount, String running, int active, String updated, String through) {
        synced.setText(updated);
        host.setText(through);
        for (int i = 0; i < items.length; i++) {
            items[i].on(i == picked);
            Look.style(items[i].label, i == picked ? Look.uiBold : Look.ui, 12, Look.INK);
        }
        files.setText(fileCount + (fileCount == 1 ? " file" : " files"));
        progress.setText(running);
        count.setText(String.valueOf(active));
        count.setVisibility(active > 0 ? VISIBLE : GONE);
    }
}
