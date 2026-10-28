package com.print.xp3;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import com.xp3.common.ui.Ui;
import java.util.Locale;

/**
 * Recent (screen 15): what was printed, newest first, grouped by day. OK
 * opens the preview with the settings used last time. A file that has been
 * moved or deleted says so and can't be opened. Menu takes a file off the
 * list, or clears it.
 */
final class RecentScreen extends Screen {

    private LinearLayout list;
    private View firstRow;

    @Override
    View build() {
        Context c = app;
        list = Parts.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.addView(list);
        fill(c);
        return Parts.page(c, Parts.nav(c, "Recent", true, null), scroll);
    }

    @Override
    View first() {
        return firstRow;
    }

    private void fill(Context c) {
        List<Store.Print> history = Store.get(app).history();
        if (history.isEmpty()) {
            list.addView(Parts.status(c, Parts.badge(c, R.drawable.ic_clock, Look.ICON_FILL, Look.BLUE), 56,
                "Nothing printed yet", "PDFs you print show up here, ready to print again.", null, null, 40));
            return;
        }
        long midnight = Queue.midnight();
        long day = 24L * 60 * 60 * 1000;
        String group = null;
        Parts.Row last = null;
        for (final Store.Print print : history) {
            String heading = print.when >= midnight ? "Today" : print.when >= midnight - day ? "Yesterday"
                : print.when >= midnight - 6 * day ? "This week" : "Earlier";
            if (!heading.equals(group)) {
                if (last != null) last.noDivider();
                list.addView(Parts.header(c, heading));
                group = heading;
            }
            boolean there = print.exists();
            Parts.Row row = new Parts.Row(c, 42);
            row.setTag(print.path);
            final ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumb.setBackgroundColor(0xFFDCDEE3);
            thumb.setPadding(1, 1, 1, 1);
            row.lead(thumb, Ui.dp(c, 24), Ui.dp(c, 31));
            row.titled(print.title, there ? "Printed " + time(print.when, midnight) + " · " + shortName(print.printerName)
                : "Moved or deleted");
            if (there) {
                row.chevron();
                row.setOnClickListener(v -> app.open(new PreviewScreen(new File(print.path), print)));
                Pages.draw(new File(print.path), 0, Ui.dp(c, 22), Ui.dp(c, 29), bitmap -> {
                    if (bitmap != null) thumb.setImageBitmap(bitmap);
                });
            } else {
                // Still focusable, so it can be taken off the list.
                row.title.setTextColor(Look.MUTE);
            }
            list.addView(row, Parts.line(Parts.FILL, Parts.WRAP));
            if (firstRow == null) firstRow = row;
            last = row;
        }
        if (last != null) last.noDivider();
    }

    /** "2:48 PM" today, "7:05 AM" yesterday, "Tue" this week, else "Oct 3". */
    private static String time(long when, long midnight) {
        long day = 24L * 60 * 60 * 1000;
        Date date = new Date(when);
        if (when >= midnight - day) return new SimpleDateFormat("h:mm a", Locale.US).format(date);
        if (when >= midnight - 6 * day) return new SimpleDateFormat("EEE", Locale.US).format(date);
        Calendar then = Calendar.getInstance();
        then.setTime(date);
        return new SimpleDateFormat("MMM d", Locale.US).format(date);
    }

    /** "OfficeJet" from "OfficeJet Pro 9015e": enough to know it by. */
    static String shortName(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length <= 2) return name;
        if (words[0].equalsIgnoreCase("HP") || words[0].equalsIgnoreCase("Canon") || words[0].equalsIgnoreCase("Epson")
            || words[0].equalsIgnoreCase("Brother")) return words[0] + " " + words[1];
        return words[0];
    }

    @Override
    void options(List<Option> out) {
        if (Store.get(app).history().isEmpty()) return;
        View focused = list.findFocus();
        final Object path = focused == null ? null : focused.getTag();
        if (path instanceof String) {
            out.add(new Option("Remove from Recent", () -> {
                Store.get(app).unlist((String) path);
                refill();
            }));
        }
        out.add(new Option("Clear Recent", () -> app.ask("Clear Recent?", "Your PDFs stay where they are.",
            "Clear", Parts.DANGER, () -> {
                Store.get(app).clearHistory();
                refill();
            }, "Keep")));
    }

    private void refill() {
        list.removeAllViews();
        firstRow = null;
        fill(app);
        if (firstRow != null) firstRow.requestFocus();
    }
}
