package com.print.xp3;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.print.xp3.find.Finder;
import com.print.xp3.find.Sweep;
import com.print.xp3.ipp.Capabilities;
import com.xp3.common.ui.Ui;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Printers (screens 12, 13 and 14). Nearby lists what is found on the
 * Wi-Fi, as it is found, with the printers kept from before; By IP address
 * reaches one that isn't found. Left and Right switch tabs. OK on a printer
 * makes it the one in use (or, when a job is asking, the one for that job)
 * and goes back.
 */
final class PrintersScreen extends Screen {

    interface Picked {
        void picked(Printer printer);
    }

    /** How long a search goes on before the list is called empty. */
    private static final long SEARCH_MILLIS = 15000;

    private final Picked picked;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Parts.Tabs tabs;
    private int tab;
    private FrameLayout content;

    // Nearby.
    private LinearLayout nearby;
    private LinearLayout list;
    private ScrollView listScroll;
    /** The "No printers found" or "Wi-Fi is off" page in the list's place, or null. */
    private View empty;
    private boolean emptyWifi;
    private TextView searching;
    private View spinner;
    private final Map<String, Parts.Row> rows = new HashMap<>();
    private final Map<String, Readiness> states = new HashMap<>();
    private Finder finder;
    private boolean searchOver;
    private WifiManager.MulticastLock multicast;

    // By IP address.
    private LinearLayout byAddress;
    private LinearLayout addressRow;
    private final TextView[] boxes = new TextView[4];
    private final String[] parts = {"", "", "", ""};
    private int box;
    private boolean fresh = true;
    private LinearLayout result;
    private TextView add;
    private Printer found;
    private int checkCount;

    /** `picked` null: OK makes the printer the one in use. */
    PrintersScreen(Picked picked) {
        this.picked = picked;
    }

    @Override
    View build() {
        Context c = app;
        LinearLayout body = Parts.column(c);
        LinearLayout tabBar = Parts.column(c);
        tabBar.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 6));
        tabs = new Parts.Tabs(c, "Nearby", "By IP address");
        tabBar.addView(tabs);
        body.addView(tabBar);
        body.addView(Parts.rule(c), Parts.line(Parts.FILL, 1));
        content = new FrameLayout(c);
        body.addView(content, new LinearLayout.LayoutParams(Parts.FILL, 0, 1));
        nearby = buildNearby(c);
        byAddress = buildAddress(c);
        select(0);
        return Parts.page(c, Parts.nav(c, "Printers", true, null), body);
    }

    @Override
    void shown() {
        if (finder == null && !searchOver) app.askLocation(this::search);
        else refreshRows();
    }

    @Override
    void closed() {
        stopSearch();
    }

    private void select(int which) {
        tab = which;
        tabs.select(which);
        content.removeAllViews();
        content.addView(which == 0 ? nearby : byAddress);
        if (which == 0) {
            View focus = focusRow();
            if (focus != null) focus.requestFocus();
        } else {
            addressRow.requestFocus();
            paintBoxes();
        }
    }

    // Nearby.

    private LinearLayout buildNearby(Context c) {
        LinearLayout page = Parts.column(c);
        LinearLayout header = Parts.row(c);
        header.setPadding(Ui.dp(c, 11), Ui.dp(c, 7), Ui.dp(c, 11), Ui.dp(c, 4));
        spinner = spinner(c);
        header.addView(spinner, Parts.line(Ui.dp(c, 9), Ui.dp(c, 9)));
        searching = Look.line(c, "", 9.5f, Look.SEMIBOLD, Look.MUTE);
        searching.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams lp = Parts.weighted();
        lp.leftMargin = Ui.dp(c, 5);
        header.addView(searching, lp);
        page.addView(header);
        list = Parts.column(c);
        listScroll = Parts.scroll(c, list);
        page.addView(listScroll, new LinearLayout.LayoutParams(Parts.FILL, 0, 1));
        return page;
    }

    /** The small turning ring by "Searching". */
    private static View spinner(Context c) {
        View ring = new View(c) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                float stroke = 1.5f * getResources().getDisplayMetrics().density;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(Look.BLUE);
                canvas.drawArc(stroke, stroke, getWidth() - stroke, getHeight() - stroke, -45, 270, false, paint);
            }
        };
        RotateAnimation turn = new RotateAnimation(0, 360, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        turn.setDuration(900);
        turn.setRepeatCount(RotateAnimation.INFINITE);
        turn.setInterpolator(new LinearInterpolator());
        ring.startAnimation(turn);
        return ring;
    }

    private void search() {
        if (app == null) return;
        stopSearch();
        searchOver = false;
        Store store = Store.get(app);
        for (Printer printer : store.printers()) {
            addRow(printer);
            check(printer);
        }
        header();
        if (!Wifi.on(app)) {
            searchOver = true;
            header();
            refreshRows();
            return;
        }
        WifiManager wifi = (WifiManager) app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicast = wifi.createMulticastLock("print:search");
            multicast.setReferenceCounted(false);
            multicast.acquire();
        }
        final Finder search = new Finder(printer -> main.post(() -> found(printer)));
        finder = search;
        background("search", () -> {
            search.run(SEARCH_MILLIS);
            main.postDelayed(() -> {
                if (finder != search) return;
                searchOver = true;
                if (multicast != null && multicast.isHeld()) multicast.release();
                header();
                refreshRows();
            }, 1500);
        });
        refreshRows();
    }

    /** Its own thread, which doesn't keep the app alive: a search and an address check can run at once. */
    private static void background(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void stopSearch() {
        if (finder != null) finder.stop();
        finder = null;
        if (multicast != null && multicast.isHeld()) multicast.release();
    }

    private void header() {
        String name = Wifi.ssid(app);
        boolean on = !searchOver;
        spinner.setVisibility(on ? View.VISIBLE : View.GONE);
        if (spinner.getAnimation() != null && !on) spinner.clearAnimation();
        String network = name == null ? "Wi-Fi" : "Wi-Fi “" + name + "”";
        searching.setText((on ? "Searching " + network : network).toUpperCase(Locale.ROOT));
    }

    private void found(Printer printer) {
        if (app == null || finder == null) return;
        Printer known = Store.get(app).remember(printer);
        addRow(known);
        states.put(known.key(), Readiness.of(known));
        synchronized (known) {
            if (known.ipp == null && known.ippPort > 0) states.put(known.key(), Readiness.CHECKING_NOW);
        }
        refreshRows();
    }

    private void check(final Printer printer) {
        states.put(printer.key(), Readiness.CHECKING_NOW);
        Readiness.check(printer, readiness -> {
            states.put(printer.key(), readiness);
            refreshRows();
        });
    }

    private void addRow(final Printer printer) {
        if (rows.containsKey(printer.key())) return;
        Context c = app;
        Parts.Row row = new Parts.Row(c, 44);
        ImageView picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.lead(picture, Ui.dp(c, 38), Ui.dp(c, 27));
        row.titled(printer.shown(), " ");
        ImageView tick = Parts.image(c, R.drawable.ic_check, Look.BLUE, 14);
        row.trailing(tick);
        row.setTag(new Object[] {printer, picture, tick});
        row.setOnClickListener(v -> pick(printer));
        rows.put(printer.key(), row);
        list.addView(row, Parts.line(Parts.FILL, Parts.WRAP));
    }

    private void refreshRows() {
        if (app == null) return;
        Printer current = Store.get(app).current();
        boolean hadFocus = list.findFocus() != null;
        for (Parts.Row row : rows.values()) {
            Object[] tag = (Object[]) row.getTag();
            Printer printer = (Printer) tag[0];
            ((ImageView) tag[1]).setImageResource(Readiness.picture(printer));
            row.title.setText(printer.shown());
            Readiness readiness = states.get(printer.key());
            if (readiness != null && readiness.kind == Readiness.CHECKING) {
                synchronized (printer) {
                    if (printer.ipp != null) readiness = Readiness.of(printer);
                }
            }
            row.sub.setText(Readiness.line(printer, readiness));
            row.sub.setTextColor(Readiness.color(readiness));
            row.sub.setVisibility(View.VISIBLE);
            ((View) tag[2]).setVisibility(current != null && current.key().equals(printer.key()) ? View.VISIBLE : View.INVISIBLE);
        }
        // Kept printers can't be reached with the Wi-Fi off: that page says so instead.
        boolean wifiOn = Wifi.on(app);
        if (searchOver && (rows.isEmpty() || !wifiOn)) {
            if (empty != null && emptyWifi != wifiOn) hideEmpty();
            if (empty == null) showEmpty();
        } else {
            hideEmpty();
        }
        if (tab == 0 && !hadFocus && app.top() == this) {
            View focus = focusRow();
            if (focus != null) focus.requestFocus();
        }
    }

    /** The printer in use, else the first. */
    private View focusRow() {
        if (empty != null || rows.isEmpty()) {
            return empty == null ? null : empty.findFocus() != null ? empty.findFocus() : empty.findViewWithTag("primary");
        }
        Printer current = Store.get(app).current();
        if (current != null && rows.containsKey(current.key())) return rows.get(current.key());
        return list.getChildAt(0);
    }

    /** Screen 14: nothing found (or Wi-Fi off). */
    private void showEmpty() {
        Context c = app;
        boolean wifiOn = Wifi.on(c);
        View rings = rings(c);
        TextView primary;
        if (wifiOn) {
            primary = Parts.button(c, "Search again", Parts.PRIMARY, () -> {
                hideEmpty();
                search();
            });
        } else {
            primary = Parts.button(c, "Open Wi-Fi settings", Parts.PRIMARY, () -> {
                searchOver = false;
                app.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            });
        }
        primary.setTag("primary");
        TextView other = Parts.button(c, "Add by IP address", Parts.SECONDARY, () -> select(1));
        String message = wifiOn
            ? "Check that the printer is on and connected to " + Wifi.name(c) + (Wifi.ssid(c) == null ? "." : ", the same Wi-Fi as this phone.")
            : "Turn on Wi-Fi to find printers on your network.";
        LinearLayout page = Parts.status(c, rings, 84, wifiOn ? "No printers found" : "Wi-Fi is off", message, null,
            Parts.buttons(c, primary, other), 8);
        // In the list's place, and scrolling, so the buttons are reached on a short screen.
        ScrollView holder = Parts.scroll(c, page);
        holder.setFillViewport(true);
        listScroll.setVisibility(View.GONE);
        nearby.addView(holder, new LinearLayout.LayoutParams(Parts.FILL, 0, 1));
        empty = holder;
        emptyWifi = wifiOn;
        if (tab == 0) primary.requestFocus();
    }

    private void hideEmpty() {
        if (empty == null) return;
        nearby.removeView(empty);
        empty = null;
        listScroll.setVisibility(View.VISIBLE);
    }

    /** The Wi-Fi sign in soft rings. */
    private static View rings(Context c) {
        FrameLayout art = new FrameLayout(c);
        art.setBackground(Look.oval(0xFFF4F7FE));
        View middle = new View(c);
        middle.setBackground(Look.oval(0xFFEEF3FE));
        art.addView(middle, new FrameLayout.LayoutParams(Ui.dp(c, 68), Ui.dp(c, 68), Gravity.CENTER));
        View inner = new View(c);
        inner.setBackground(Look.oval(Look.WHITE));
        art.addView(inner, new FrameLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44), Gravity.CENTER));
        ImageView sign = Parts.image(c, R.drawable.ic_wifi, Look.BLUE, 22);
        art.addView(sign, new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER));
        return art;
    }

    private void pick(Printer printer) {
        Store store = Store.get(app);
        stopSearch();
        if (picked == null) {
            store.setCurrent(printer);
            app.close();
        } else {
            Printer kept = store.keep(printer);
            // The first printer ever chosen becomes the one in use, too.
            if (store.current() == null) store.setCurrent(kept);
            app.close();
            picked.picked(kept);
        }
    }

    // By IP address.

    private LinearLayout buildAddress(Context c) {
        LinearLayout page = Parts.column(c);
        page.setPadding(0, Ui.dp(c, 9), 0, 0);
        TextView label = Look.text(c, "Printer IP address", 10, Look.SEMIBOLD, Look.MUTE);
        label.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        page.addView(label);
        addressRow = Parts.row(c);
        addressRow.setFocusable(true);
        addressRow.setDefaultFocusHighlightEnabled(false);
        addressRow.setPadding(Ui.dp(c, 10), Ui.dp(c, 3), Ui.dp(c, 10), 0);
        addressRow.setOnFocusChangeListener((v, has) -> paintBoxes());
        for (int i = 0; i < 4; i++) {
            if (i > 0) addressRow.addView(Look.text(c, ".", 12, Look.BOLD, Look.INK));
            TextView box = Look.text(c, "", 11.5f, Look.BOLD, Look.INK);
            box.setGravity(Gravity.CENTER);
            box.setMinWidth(Ui.dp(c, 34));
            box.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
            LinearLayout.LayoutParams lp = Parts.line(Parts.WRAP, Ui.dp(c, 24));
            lp.leftMargin = lp.rightMargin = Ui.dp(c, 1);
            addressRow.addView(box, lp);
            boxes[i] = box;
        }
        page.addView(addressRow);
        TextView hint = Look.text(c, "Type with the number keys. * moves to the next box.", 9.5f, Look.REGULAR, Look.MUTE);
        hint.setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), 0);
        page.addView(hint);
        result = Parts.row(c);
        result.setBackground(Look.outlined(Look.WHITE, 6, 1, Look.LINE, c));
        result.setPadding(Ui.dp(c, 8), Ui.dp(c, 7), Ui.dp(c, 8), Ui.dp(c, 7));
        result.setVisibility(View.GONE);
        LinearLayout.LayoutParams resultAt = Parts.line(Parts.FILL, Parts.WRAP);
        resultAt.setMargins(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), 0);
        page.addView(result, resultAt);
        add = Parts.button(c, "Add printer", Parts.PRIMARY, () -> {
            if (found != null) pick(found);
        });
        add.setEnabled(false);
        LinearLayout.LayoutParams addAt = Parts.line(Parts.FILL, Parts.WRAP);
        addAt.topMargin = Ui.dp(c, 9);
        page.addView(Parts.buttons(c, add), addAt);

        // The phone's own network is the likely one: its first three parts are filled in.
        int[] self = Sweep.local();
        if (self != null) {
            String[] mine = Sweep.dotted(self[0]).split("\\.");
            for (int i = 0; i < 3; i++) parts[i] = mine[i];
            box = 3;
        }
        return page;
    }

    private void paintBoxes() {
        boolean typing = addressRow.isFocused();
        for (int i = 0; i < 4; i++) {
            boxes[i].setText(parts[i]);
            boxes[i].setBackground(Look.box(app, typing && i == box));
        }
    }

    private boolean complete() {
        for (String part : parts) {
            if (part.isEmpty() || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private void typed() {
        paintBoxes();
        found = null;
        add.setEnabled(false);
        result.setVisibility(View.GONE);
        if (!complete()) return;
        final int asked = ++checkCount;
        // Checked as soon as the address is whole, after a moment for a last digit.
        main.postDelayed(() -> {
            if (asked == checkCount) checkAddress();
        }, parts[3].length() == 3 ? 0 : 900);
    }

    private void checkAddress() {
        final int asked = checkCount;
        final String address = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
        showResult(null, "Looking for a printer…", null);
        final Finder one = new Finder(printer -> { });
        background("address", () -> {
            Printer printer = null;
            String problem = null;
            try {
                List<Printer> all = one.at(address);
                printer = all.get(0);
            } catch (IOException e) {
                problem = e.getMessage();
            }
            final Printer answer = printer;
            final String why = problem;
            main.post(() -> {
                one.stop();
                if (asked != checkCount || app == null) return;
                if (answer == null) {
                    showResult(null, "No printer found", why == null ? "Nothing answers at " + address : why);
                    return;
                }
                found = answer;
                add.setEnabled(true);
                showResult(answer, "Found printer", answer.shown() + " · " + abilities(answer));
            });
        });
    }

    /** "PDF, color, 2-sided". */
    private static String abilities(Printer printer) {
        List<String> out = new ArrayList<>();
        Capabilities caps = printer.ipp;
        if (caps != null) {
            if (caps.takes("application/pdf")) out.add("PDF");
            out.add(caps.color ? "color" : "black & white");
            if (caps.twoSided()) out.add("2-sided");
        } else if (printer.pjl != null && !printer.pjl.languages.isEmpty()) {
            out.add(String.join(", ", printer.pjl.languages));
        } else {
            out.add("prints what it is sent");
        }
        return String.join(", ", out);
    }

    private void showResult(Printer printer, String title, String detail) {
        Context c = app;
        result.removeAllViews();
        result.setVisibility(View.VISIBLE);
        if (printer != null) {
            ImageView picture = new ImageView(c);
            picture.setImageResource(Readiness.picture(printer));
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = Parts.line(Ui.dp(c, 40), Ui.dp(c, 28));
            lp.rightMargin = Ui.dp(c, 8);
            result.addView(picture, lp);
        }
        LinearLayout text = Parts.column(c);
        LinearLayout heading = Parts.row(c);
        if (printer != null) {
            ImageView tick = Parts.image(c, R.drawable.ic_check, Look.OK, 12);
            ((LinearLayout.LayoutParams) tick.getLayoutParams()).rightMargin = Ui.dp(c, 4);
            heading.addView(tick);
        }
        heading.addView(Look.line(c, title, 11.5f, Look.BOLD, printer == null && detail != null ? Look.ERR : Look.INK));
        text.addView(heading);
        // A found printer's details fit one line; why none was found is advice, read whole.
        if (detail != null) text.addView(printer != null ? Look.line(c, detail, 9.5f, Look.REGULAR, Look.MUTE)
            : Look.text(c, detail, 9.5f, Look.REGULAR, Look.MUTE));
        result.addView(text, Parts.weighted());
    }

    // Keys.

    @Override
    boolean key(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = down(event);
        if (tab == 1 && addressRow.isFocused()) {
            int digit = digit(event);
            if (digit >= 0) {
                if (down) typeDigit(digit);
                return true;
            }
            if (code == KeyEvent.KEYCODE_STAR || (code == KeyEvent.KEYCODE_DPAD_RIGHT && box < 3)) {
                if (down) {
                    box = Math.min(3, box + 1);
                    fresh = true;
                    paintBoxes();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && box > 0) {
                if (down) {
                    box--;
                    fresh = true;
                    paintBoxes();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_CLEAR) {
                if (down) {
                    if (!parts[box].isEmpty()) parts[box] = parts[box].substring(0, parts[box].length() - 1);
                    else if (box > 0) box--;
                    fresh = false;
                    typed();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (!down && complete()) checkAddress();
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int want = code == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
            boolean inPair = content.findFocus() != null && content.findFocus().getParent() instanceof LinearLayout
                && ((LinearLayout) content.findFocus().getParent()).getOrientation() == LinearLayout.HORIZONTAL
                && content.findFocus() instanceof TextView;
            if (inPair) return false;
            if (down && want != tab) select(want);
            return true;
        }
        return false;
    }

    private void typeDigit(int digit) {
        String part = fresh || parts[box].length() >= 3 ? "" : parts[box];
        part = part + digit;
        if (part.length() > 1 && part.startsWith("0")) part = part.substring(1);
        parts[box] = part;
        fresh = false;
        // A box that can't take another digit is done: on to the next.
        if (box < 3 && (part.length() == 3 || Integer.parseInt(part) * 10 > 255)) {
            box++;
            fresh = true;
        }
        typed();
    }

    /** Forget the focused printer, if it is one kept; search again, when a search has found some. */
    @Override
    void options(List<Option> out) {
        if (tab != 0) return;
        View focused = list.findFocus();
        Object tag = focused == null ? null : focused.getTag();
        if (tag instanceof Object[]) {
            final Printer printer = (Printer) ((Object[]) tag)[0];
            if (Store.get(app).kept(printer)) out.add(new Option("Forget printer", () -> forget(printer)));
        }
        if (searchOver && !rows.isEmpty() && Wifi.on(app)) out.add(new Option("Search again", this::search));
    }

    private void forget(Printer printer) {
        Store.get(app).forget(printer);
        Parts.Row row = rows.remove(printer.key());
        states.remove(printer.key());
        if (row != null) list.removeView(row);
        refreshRows();
        View focus = focusRow();
        if (focus != null) focus.requestFocus();
    }
}
