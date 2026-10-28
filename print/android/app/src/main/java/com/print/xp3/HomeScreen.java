package com.print.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.xp3.common.ui.Ui;
import android.widget.TextView;

/**
 * Home (screen 01): the printer banner, six tiles that match keys 1 to 6,
 * and Print again on 0. Number keys work wherever the focus is.
 */
final class HomeScreen extends Screen implements Queue.Listener {

    private TextView printerName;
    private TextView printerState;
    private View dot;
    private TextView badge;
    private View tileFirst;
    private LinearLayout again;
    private TextView againSub;
    private ImageView againThumb;
    private Store.Print last;
    private Printer checking;

    @Override
    View build() {
        Context c = app;
        LinearLayout body = Parts.column(c);

        View banner = banner(c);
        body.addView(banner, Parts.line(Parts.FILL, Ui.dp(c, 42)));

        LinearLayout grid = Parts.column(c);
        int gap = Ui.dp(c, 2);
        grid.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), 0);
        String[] labels = {"Print PDF", "Recent", "Printers", "Print Queue", "Ink Levels", "Settings"};
        int[] icons = {R.drawable.ic_doc, R.drawable.ic_clock, R.drawable.ic_printer, R.drawable.ic_list,
            R.drawable.ic_drop, R.drawable.ic_gear};
        for (int r = 0; r < 2; r++) {
            LinearLayout line = Parts.row(c);
            for (int i = 0; i < 3; i++) {
                final int n = r * 3 + i + 1;
                View tile = tile(c, n, labels[n - 1], icons[n - 1]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 78), 1);
                if (i > 0) lp.leftMargin = gap;
                line.addView(tile, lp);
                if (n == 1) tileFirst = tile;
            }
            LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Parts.WRAP);
            if (r > 0) lp.topMargin = gap;
            grid.addView(line, lp);
        }
        body.addView(grid);

        again = againRow(c);
        LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Ui.dp(c, 48));
        lp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), 0);
        body.addView(again, lp);

        return Parts.page(c, Parts.nav(c, "Print", false, null), body);
    }

    @Override
    View first() {
        return tileFirst;
    }

    private View banner(Context c) {
        LinearLayout banner = Parts.row(c);
        banner.setBackground(new Look.BannerFace(c));
        banner.setPadding(Ui.dp(c, 9), 0, Ui.dp(c, 9), 0);
        banner.setFocusable(true);
        banner.setDefaultFocusHighlightEnabled(false);
        banner.setOnClickListener(v -> app.open(new PrintersScreen(null)));
        banner.addView(Parts.image(c, R.drawable.ic_printer, Look.WHITE, 20));
        LinearLayout text = Parts.column(c);
        printerName = Look.line(c, "", 13, Look.BOLD, Look.WHITE);
        text.addView(printerName);
        LinearLayout state = Parts.row(c);
        dot = new View(c);
        LinearLayout.LayoutParams dotAt = Parts.line(Ui.dp(c, 6), Ui.dp(c, 6));
        dotAt.rightMargin = Ui.dp(c, 3);
        state.addView(dot, dotAt);
        printerState = Look.line(c, "", 9.5f, Look.REGULAR, 0xE6FFFFFF);
        state.addView(printerState);
        LinearLayout.LayoutParams under = Parts.line(Parts.WRAP, Parts.WRAP);
        under.topMargin = Ui.dp(c, 2);
        text.addView(state, under);
        LinearLayout.LayoutParams lp = Parts.weighted();
        lp.leftMargin = Ui.dp(c, 8);
        banner.addView(text, lp);
        banner.addView(Parts.image(c, R.drawable.ic_chev_right, Look.WHITE, 13));
        return banner;
    }

    private void dot(int color) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        dot.setBackground(circle);
    }

    private View tile(Context c, final int n, String label, int icon) {
        FrameLayout tile = new FrameLayout(c);
        tile.setBackground(new Look.TileFace(c, Look.TILES[n - 1][0], Look.TILES[n - 1][1]));
        tile.setFocusable(true);
        tile.setDefaultFocusHighlightEnabled(false);
        tile.setOnClickListener(v -> act(n));
        TextView key = Look.text(c, String.valueOf(n), 9, Look.BOLD, 0xCCFFFFFF);
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(Parts.WRAP, Parts.WRAP, Gravity.LEFT | Gravity.TOP);
        corner.leftMargin = Ui.dp(c, 5);
        corner.topMargin = Ui.dp(c, 4);
        tile.addView(key, corner);
        if (n == 4) {
            badge = Look.text(c, "", 9, Look.BOLD, Look.NAVY);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Look.rounded(Look.WHITE, 7, c));
            badge.setMinWidth(Ui.dp(c, 14));
            badge.setPadding(Ui.dp(c, 3), 0, Ui.dp(c, 3), 0);
            FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(Parts.WRAP, Ui.dp(c, 14), Gravity.RIGHT | Gravity.TOP);
            right.rightMargin = Ui.dp(c, 5);
            right.topMargin = Ui.dp(c, 4);
            tile.addView(badge, right);
        }
        LinearLayout middle = Parts.column(c);
        middle.setGravity(Gravity.CENTER_HORIZONTAL);
        middle.addView(Parts.image(c, icon, Look.WHITE, 22));
        TextView name = Look.text(c, label, 11, Look.SEMIBOLD, Look.WHITE);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams under = Parts.line(Parts.WRAP, Parts.WRAP);
        under.topMargin = Ui.dp(c, 6);
        middle.addView(name, under);
        tile.addView(middle, new FrameLayout.LayoutParams(Parts.FILL, Parts.WRAP, Gravity.CENTER));
        return tile;
    }

    private LinearLayout againRow(Context c) {
        LinearLayout row = Parts.row(c);
        android.graphics.drawable.StateListDrawable face = new android.graphics.drawable.StateListDrawable();
        face.addState(new int[] {android.R.attr.state_focused}, Look.outlined(Look.SEL, 6, 1.5f, Look.BLUE, c));
        face.addState(new int[] {}, Look.rounded(Look.GREY, 6, c));
        row.setBackground(face);
        row.setPadding(Ui.dp(c, 9), 0, Ui.dp(c, 9), 0);
        row.setFocusable(true);
        row.setDefaultFocusHighlightEnabled(false);
        row.setOnClickListener(v -> act(0));
        TextView key = Look.text(c, "0", 10, Look.BOLD, Look.NAVY);
        key.setGravity(Gravity.CENTER);
        key.setBackground(Look.outlined(Look.WHITE, 4, 1, 0xFFD3D6DE, c));
        row.addView(key, Parts.line(Ui.dp(c, 18), Ui.dp(c, 18)));
        againThumb = new ImageView(c);
        againThumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        againThumb.setBackgroundColor(0xFFDCDEE3);
        againThumb.setPadding(1, 1, 1, 1);
        LinearLayout.LayoutParams thumb = Parts.line(Ui.dp(c, 22), Ui.dp(c, 28));
        thumb.leftMargin = Ui.dp(c, 8);
        row.addView(againThumb, thumb);
        LinearLayout text = Parts.column(c);
        text.addView(Look.line(c, "Print again", 11.5f, Look.BOLD, Look.INK));
        againSub = Look.line(c, "", 9.5f, Look.REGULAR, Look.MUTE);
        LinearLayout.LayoutParams under = Parts.line(Parts.FILL, Parts.WRAP);
        under.topMargin = Ui.dp(c, 1);
        text.addView(againSub, under);
        LinearLayout.LayoutParams lp = Parts.weighted();
        lp.leftMargin = Ui.dp(c, 8);
        row.addView(text, lp);
        row.addView(Parts.image(c, R.drawable.ic_chev_right, Look.CHEVRON, 12));
        return row;
    }

    @Override
    void shown() {
        Queue.listen(this);
        refresh();
    }

    @Override
    void hidden() {
        Queue.unlisten(this);
    }

    @Override
    public void changed(Queue.Entry entry) {
        int active = Queue.activeCount();
        badge.setText(String.valueOf(active));
        badge.setVisibility(active > 0 ? View.VISIBLE : View.GONE);
    }

    private void refresh() {
        changed(null);
        Store store = Store.get(app);
        final Printer printer = store.current();
        if (printer == null) {
            printerName.setText("Choose a printer");
            printerState.setText("No printer chosen yet");
            dot(0x99FFFFFF);
        } else {
            printerName.setText(printer.shown());
            if (checking != printer) {
                printerState.setText("Checking…");
                dot(0x99FFFFFF);
            }
            checking = printer;
            Readiness.check(printer, readiness -> {
                if (Store.get(app).current() != printer) return;
                printerName.setText(printer.shown());
                boolean ready = readiness.kind == Readiness.READY || readiness.kind == Readiness.BUSY;
                printerState.setText(ready ? (readiness.kind == Readiness.BUSY ? "Printer is busy" : "Printer is ready") : readiness.text);
                dot(ready ? Look.READY_DOT : readiness.kind == Readiness.WARNING ? 0xFFFFC14D : 0xFFFF7B7B);
            });
        }
        last = store.last();
        if (last == null) {
            again.setVisibility(View.GONE);
            return;
        }
        again.setVisibility(View.VISIBLE);
        againSub.setText(last.title + " · " + last.pages + (last.pages == 1 ? " page" : " pages"));
        againThumb.setImageDrawable(null);
        if (last.exists()) {
            Pages.draw(new java.io.File(last.path), 0, Ui.dp(app, 20), Ui.dp(app, 26), bitmap -> {
                if (bitmap != null) againThumb.setImageBitmap(bitmap);
            });
        }
    }

    private void act(int n) {
        switch (n) {
            case 0:
                if (last == null) return;
                if (!last.exists()) {
                    app.toast("That file has been moved or deleted");
                    return;
                }
                final Store.Print print = last;
                app.allowed(() -> app.open(new PreviewScreen(new java.io.File(print.path), print)));
                break;
            case 1:
                app.allowed(() -> app.open(new LocationsScreen()));
                break;
            case 2:
                app.open(new RecentScreen());
                break;
            case 3:
                app.open(new PrintersScreen(null));
                break;
            case 4:
                app.open(new QueueScreen());
                break;
            case 5:
                app.open(new InkScreen());
                break;
            case 6:
                app.open(new SettingsScreen());
                break;
            default:
                break;
        }
    }

    @Override
    boolean key(KeyEvent event) {
        int digit = digit(event);
        if (digit < 0 || digit > 6) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) act(digit);
        return true;
    }
}
