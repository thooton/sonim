package com.print.xp3;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.xp3.common.ui.Ui;
import java.io.File;

/**
 * Preview (screen 05): the page as it will print, its paper size and a page
 * counter, a line saying where and how it will print, then Settings and
 * Print, with Print focused so OK prints. Left and Right turn pages from
 * the ends of the button row, or wherever the preview has the focus.
 */
final class PreviewScreen extends Screen {

    private final File file;
    private final Store.Print from;
    private Draft draft;

    private FrameLayout frame;
    private FrameLayout area;
    private ImageView pageView;
    private TextView sizeChip;
    private TextView counter;
    private View left;
    private View right;
    private TextView summaryName;
    private TextView summaryRest;
    /** "60 pages, may take several minutes", under the summary, when that many will print. */
    private TextView slow;
    private TextView settings;
    private TextView print;
    private int drawn = -1;

    /** `from` is a past print whose settings to start with (Recent, Print again), or null. */
    PreviewScreen(File file, Store.Print from) {
        this.file = file;
        this.from = from;
    }

    @Override
    View build() {
        Context c = app;
        frame = new FrameLayout(c);
        frame.setBackgroundColor(Look.WHITE);
        View loading = Parts.status(c, null, 0, "Opening…", null, null, null, 60);
        frame.addView(loading);
        Pages.count(file, this::opened);
        return Parts.page(c, Parts.nav(c, Library.title(file), true, null), frame);
    }

    @Override
    View first() {
        return print;
    }

    private void opened(int pages) {
        Context c = app;
        frame.removeAllViews();
        if (pages == Pages.LOCKED || pages <= 0) {
            boolean locked = pages == Pages.LOCKED;
            TextView back = Parts.button(c, "Back to files", Parts.PRIMARY, app::close);
            frame.addView(Parts.status(c, Parts.badge(c, locked ? R.drawable.ic_lock : R.drawable.ic_alert, 0xFFFFF1D6, Look.WARN), 56,
                "Can't open this file",
                locked ? "This PDF is protected with a password, which this phone's PDF reader can't open."
                    : "The PDF may be damaged or incomplete. Try downloading it again.",
                null, Parts.buttons(c, back), 34));
            back.requestFocus();
            return;
        }
        Store store = Store.get(app);
        Printer printer = store.current();
        Options options;
        if (from != null) {
            Printer before = store.printer(from.printer);
            if (before != null) printer = before;
            options = Store.copy(from.options);
            options.from = Math.min(Math.max(1, options.from), pages);
            options.to = Math.min(Math.max(options.from, options.to), pages);
            Choices.fit(options, printer);
        } else {
            options = store.defaults(printer, pages);
        }
        draft = new Draft(file, pages, printer, options);
        frame.addView(layout(c));
        refresh();
        print.requestFocus();
    }

    private View layout(Context c) {
        LinearLayout column = Parts.column(c);
        area = new FrameLayout(c);
        area.setBackgroundColor(Look.PREVIEW);
        area.setFocusable(true);
        area.setDefaultFocusHighlightEnabled(false);
        pageView = new ImageView(c);
        pageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pageView.setElevation(Ui.dp(c, 2));
        pageView.setBackgroundColor(Look.WHITE);
        area.addView(pageView, new FrameLayout.LayoutParams(Parts.WRAP, Parts.WRAP, Gravity.CENTER));
        sizeChip = Look.line(c, "", 9, Look.SEMIBOLD, Look.BLUE);
        sizeChip.setBackground(Look.rounded(Look.WHITE, 3, c));
        sizeChip.setPadding(Ui.dp(c, 5), Ui.dp(c, 2), Ui.dp(c, 5), Ui.dp(c, 2));
        FrameLayout.LayoutParams chipAt = new FrameLayout.LayoutParams(Parts.WRAP, Parts.WRAP, Gravity.RIGHT | Gravity.TOP);
        chipAt.setMargins(0, Ui.dp(c, 6), Ui.dp(c, 6), 0);
        area.addView(sizeChip, chipAt);
        left = arrow(c, R.drawable.ic_chev_left);
        right = arrow(c, R.drawable.ic_chev_right);
        FrameLayout.LayoutParams leftAt = new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        leftAt.leftMargin = Ui.dp(c, 8);
        area.addView(left, leftAt);
        FrameLayout.LayoutParams rightAt = new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rightAt.rightMargin = Ui.dp(c, 8);
        area.addView(right, rightAt);
        counter = Look.line(c, "", 9, Look.SEMIBOLD, Look.WHITE);
        counter.setBackground(Look.rounded(0xBF1A1C22, 9, c));
        counter.setPadding(Ui.dp(c, 7), Ui.dp(c, 2), Ui.dp(c, 7), Ui.dp(c, 2));
        FrameLayout.LayoutParams counterAt = new FrameLayout.LayoutParams(Parts.WRAP, Parts.WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        counterAt.bottomMargin = Ui.dp(c, 6);
        area.addView(counter, counterAt);
        column.addView(area, new LinearLayout.LayoutParams(Parts.FILL, 0, 1));

        column.addView(Parts.rule(c), Parts.line(Parts.FILL, 1));
        LinearLayout foot = Parts.column(c);
        foot.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 6));
        LinearLayout summary = Parts.row(c);
        summary.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 5));
        summary.addView(Parts.image(c, R.drawable.ic_printer, Look.MUTE, 11));
        summaryName = Look.line(c, "", 10, Look.SEMIBOLD, Look.INK);
        LinearLayout.LayoutParams nameAt = Parts.line(Parts.WRAP, Parts.WRAP);
        nameAt.leftMargin = Ui.dp(c, 5);
        summary.addView(summaryName, nameAt);
        summaryName.setMaxWidth(Ui.dp(c, 110));
        summaryRest = Look.line(c, "", 10, Look.REGULAR, Look.MUTE);
        summary.addView(summaryRest, Parts.weighted());
        foot.addView(summary);
        slow = Look.line(c, "", 10, Look.REGULAR, Look.MUTE);
        slow.setPadding(Ui.dp(c, 28), 0, Ui.dp(c, 12), Ui.dp(c, 5));
        slow.setVisibility(View.GONE);
        foot.addView(slow);
        settings = Parts.button(c, "Settings", Parts.SECONDARY, () -> app.open(new PrintSettingsScreen(draft, this::print)));
        settings.setCompoundDrawablesRelativeWithIntrinsicBounds(scaled(c, R.drawable.ic_gear, 13), null, null, null);
        settings.setCompoundDrawablePadding(Ui.dp(c, 5));
        print = Parts.button(c, "Print", Parts.PRIMARY, this::print);
        foot.addView(Parts.pair(c, settings, print));
        column.addView(foot);
        return column;
    }

    private static Drawable scaled(Context c, int id, float size) {
        Drawable icon = Look.icon(c, id, Look.INK);
        icon.setBounds(0, 0, Ui.dp(c, size), Ui.dp(c, size));
        return icon;
    }

    private static View arrow(Context c, int icon) {
        FrameLayout arrow = new FrameLayout(c);
        arrow.setBackground(Look.oval(Look.WHITE));
        arrow.setElevation(Ui.dp(c, 1.5f));
        arrow.addView(Parts.image(c, icon, Look.INK, 12), new FrameLayout.LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12), Gravity.CENTER));
        return arrow;
    }

    /** Shows the draft as it now stands. */
    private void refresh() {
        if (draft == null) return;
        String media = draft.options.media;
        sizeChip.setText(Media.shown(media) + " " + Media.dimensions(media).replace(" × ", " x "));
        counter.setText("Page " + (draft.page + 1) + " of " + draft.pages);
        left.setAlpha(draft.page > 0 ? 1f : 0.4f);
        right.setAlpha(draft.page < draft.pages - 1 ? 1f : 0.4f);
        if (draft.printer == null) {
            summaryName.setText("No printer chosen");
            summaryRest.setText("");
        } else {
            summaryName.setText(draft.printer.shown());
            summaryRest.setText(" · " + draft.summary());
        }
        // The pages that will print, not the document's: two pages of a long manual are quick.
        int printing = draft.options.to - draft.options.from + 1;
        slow.setText(printing + " pages, may take several minutes");
        slow.setVisibility(draft.printer != null && printing >= 50 ? View.VISIBLE : View.GONE);
        draw();
    }

    private void draw() {
        if (drawn == draft.page) return;
        drawn = draft.page;
        final int page = draft.page;
        area.post(() -> {
            int w = area.getWidth() - Ui.dp(app, 64);
            int h = area.getHeight() - Ui.dp(app, 36);
            if (w <= 0 || h <= 0) {
                drawn = -1;
                return;
            }
            Pages.draw(file, page, w, h, bitmap -> {
                if (draft.page != page) return;
                pageView.setImageBitmap(bitmap);
            });
        });
    }

    private void turn(int by) {
        if (draft == null) return;
        int next = Math.max(0, Math.min(draft.pages - 1, draft.page + by));
        if (next == draft.page) return;
        draft.page = next;
        refresh();
    }

    private void print() {
        if (draft.printer == null) {
            app.open(new PrintersScreen(chosen -> {
                draft.use(chosen);
                refresh();
            }));
            return;
        }
        Queue.Entry entry = Queue.add(app, file, draft.title, draft.printer, draft.options, draft.pages);
        app.home();
        app.open(new PrintingScreen(entry));
    }

    @Override
    void shown() {
        refresh();
    }

    @Override
    boolean key(KeyEvent event) {
        if (draft == null) return false;
        int code = event.getKeyCode();
        boolean down = down(event);
        boolean onArea = area.isFocused();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && (onArea || settings.isFocused())) {
            if (down) turn(-1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && (onArea || print.isFocused())) {
            if (down) turn(1);
            return true;
        }
        if (onArea && code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) print.requestFocus();
            return true;
        }
        return false;
    }
}
