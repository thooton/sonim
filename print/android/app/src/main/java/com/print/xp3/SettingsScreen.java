package com.print.xp3;

import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings (screen 18): the defaults new jobs start with, and the app's own
 * options. OK flips a switch or opens a choice. Jobs already in the queue
 * keep what they were sent with.
 */
final class SettingsScreen extends Screen {

    private Parts.SettingRow printer;
    private Parts.SettingRow color;
    private Parts.Row twoSidedRow;
    private Parts.Switch twoSided;
    private Parts.SettingRow paper;
    private Parts.Switch vibrate;

    @Override
    View build() {
        Context c = app;
        final Store store = Store.get(app);
        LinearLayout list = Parts.column(c);
        list.addView(Parts.header(c, "Printing defaults"));

        printer = new Parts.SettingRow(c, "Default printer", 30, true);
        printer.row.setOnClickListener(v -> app.open(new PrintersScreen(null)));
        list.addView(printer.row);

        color = new Parts.SettingRow(c, "Color", 30, true);
        color.row.setOnClickListener(v -> {
            List<MainActivity.Choice> choices = new ArrayList<MainActivity.Choice>();
            choices.add(new MainActivity.Choice("Color", null));
            choices.add(new MainActivity.Choice("Black & white", null));
            app.choose("Color", choices, store.color() ? 0 : 1, null, which -> {
                store.setColor(which == 0);
                refresh();
            });
        });
        list.addView(color.row);

        twoSidedRow = new Parts.Row(c, 30);
        twoSidedRow.titled("Two-sided", null);
        twoSided = new Parts.Switch(c, store.twoSided());
        twoSidedRow.trailing(twoSided);
        twoSidedRow.setOnClickListener(v -> {
            store.setTwoSided(!store.twoSided());
            refresh();
        });
        list.addView(twoSidedRow);

        paper = new Parts.SettingRow(c, "Paper size", 30, true);
        paper.row.setOnClickListener(v -> {
            final List<String> offered = Choices.media(store.current());
            List<MainActivity.Choice> choices = new ArrayList<MainActivity.Choice>();
            for (String media : offered) choices.add(new MainActivity.Choice(Media.shown(media), Media.dimensions(media)));
            int selected = offered.indexOf(store.media());
            if (selected < 0) {
                for (int i = 0; i < offered.size(); i++) if (Media.shown(offered.get(i)).equals(Media.shown(store.media()))) selected = i;
            }
            app.choose("Paper size", choices, Math.max(0, selected), null, which -> {
                store.setMedia(offered.get(which));
                refresh();
            });
        });
        paper.row.noDivider();
        list.addView(paper.row);

        list.addView(Parts.header(c, "App"));
        Parts.Row vibrateRow = new Parts.Row(c, 30);
        vibrateRow.titled("Vibrate when done", null);
        vibrate = new Parts.Switch(c, store.vibrate());
        vibrateRow.trailing(vibrate);
        vibrateRow.setOnClickListener(v -> {
            store.setVibrate(!store.vibrate());
            refresh();
        });
        list.addView(vibrateRow);

        Parts.Row help = new Parts.Row(c, 30);
        help.titled("Help", null);
        help.chevron();
        help.setOnClickListener(v -> app.open(new HelpScreen()));
        list.addView(help);

        Parts.Row about = new Parts.Row(c, 30);
        about.titled("About", null);
        about.trailing(Look.line(c, "Version " + version(c), 11.5f, Look.REGULAR, Look.MUTE));
        about.setFocusable(false);
        about.noDivider();
        list.addView(about);

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.addView(list);
        refresh();
        return Parts.page(c, Parts.nav(c, "Settings", true, null), scroll);
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @Override
    View first() {
        return printer.row;
    }

    @Override
    void shown() {
        refresh();
    }

    private void refresh() {
        Store store = Store.get(app);
        Printer current = store.current();
        printer.value.setText(current == null ? "None" : RecentScreen.shortName(current.shown()));
        color.value.setText(Choices.colorName(store.color()));
        twoSided.set(store.twoSided());
        paper.value.setText(Media.shown(store.media()));
        vibrate.set(store.vibrate());
    }

    /** Help: the keys, in brief. */
    static final class HelpScreen extends Screen {
        @Override
        View build() {
            Context c = app;
            LinearLayout list = Parts.column(c);
            list.setFocusable(true);
            list.setDefaultFocusHighlightEnabled(false);
            String[][] keys = {
                {"1 to 6", "Open a tile on Home"},
                {"0", "Print the last PDF again"},
                {"Number keys", "Search a list of files: each key stands for its letters"},
                {"#", "Change a file list's order"},
                {"◀ ▶", "Turn pages in the preview; change copies; switch tabs"},
                {"*", "Next box, when typing an address"},
                {"Back", "Go back a screen. On Printing, printing carries on"},
            };
            list.addView(Parts.header(c, "Keys"));
            for (String[] key : keys) {
                LinearLayout row = Parts.row(c);
                row.setPadding(Look.dp(c, 11), Look.dp(c, 5), Look.dp(c, 10), Look.dp(c, 5));
                TextView name = Look.text(c, key[0], 11.5f, Look.BOLD, Look.BLUE);
                row.addView(name, Parts.line(Look.dp(c, 70), Parts.WRAP));
                row.addView(Look.text(c, key[1], 11, Look.REGULAR, Look.INK), Parts.weighted());
                list.addView(row);
            }
            list.addView(Parts.header(c, "Printers"));
            TextView about = Look.text(c, "Print finds printers on the same Wi-Fi by itself. It sends the PDF as it is to a "
                + "printer that reads PDFs, and otherwise draws the pages on the phone in a language the printer takes. "
                + "A printer it doesn't find can be added by its IP address, which the printer's own menu shows.",
                10.5f, Look.REGULAR, Look.MUTE);
            about.setPadding(Look.dp(c, 11), 0, Look.dp(c, 11), Look.dp(c, 10));
            about.setLineSpacing(0, 1.15f);
            list.addView(about);
            ScrollView scroll = new ScrollView(c);
            scroll.setDefaultFocusHighlightEnabled(false);
            scroll.addView(list);
            return Parts.page(c, Parts.nav(c, "Help", true, null), scroll);
        }

        @Override
        View first() {
            return ((ScrollView) ((LinearLayout) view).getChildAt(2)).getChildAt(0);
        }
    }
}
