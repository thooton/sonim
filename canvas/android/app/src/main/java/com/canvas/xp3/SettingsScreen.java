package com.canvas.xp3;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings (H2): one line each, with the current value on the right. OK
 * opens a sheet of choices (H3), where every option, Off included, is an
 * ordinary choice.
 */
final class SettingsScreen extends Screen {

    SettingsScreen(MainActivity app) {
        super(app);
    }

    @Override
    String left() {
        return "Back";
    }

    @Override
    boolean onKey(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        if (code == android.view.KeyEvent.KEYCODE_MENU || code == android.view.KeyEvent.KEYCODE_SOFT_LEFT) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) app.pop();
            return true;
        }
        return false;
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        Prefs prefs = app.prefs;
        LinearLayout root = v.column();
        TextView heading = v.screenTitle("Settings");
        v.pad(heading, 12, 4, 12, 6);
        root.addView(heading, Views.wide());
        LinearLayout list = parts.page(this, root);
        list.addView(v.hairline());
        list.addView(setting("theme", "Theme", themeLabel(prefs.theme), this::theme), Views.wide());
        list.addView(setting("text", "Text size", Prefs.LARGE.equals(prefs.textSize) ? "Large" : "Default",
            this::textSize), Views.wide());
        list.addView(setting("reminders", "Reminders", Prefs.reminderLabel(prefs.reminders), this::reminders),
            Views.wide());
        list.addView(setting("week", "Week starts on", weekLabel(prefs.weekStart), this::week), Views.wide());
        list.addView(setting("downloads", "Download files", downloadLabel(prefs.downloads), this::downloads),
            Views.wide());
        String account = app.backend.canvasReady() ? app.backend.config().canvasBase().getHost() : "Not set up";
        list.addView(setting("account", "Account", account, () -> app.push(new AccountScreen(app))), Views.wide());
        return root;
    }

    private Item setting(String key, String name, String value, Runnable change) {
        Item row = new Item(app, key, "Change", change);
        LinearLayout line = v.row();
        v.pad(line, 12, 7, 12, 7);
        MarkText label = v.mark(name, 11, look.semibold, look.ink);
        line.addView(label, Views.fill());
        TextView current = v.text(value, 9.5f, look.medium, look.muted);
        current.setSingleLine(true);
        current.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(current, v.margins(Views.wrap(), 8, 0, 0, 0));
        row.addView(line, Views.wide());
        row.addView(v.hairline());
        row.marks(label, look);
        return row;
    }

    private static String themeLabel(String value) {
        return Prefs.LIGHT.equals(value) ? "Light" : Prefs.DARK.equals(value) ? "Dark" : "Follow phone";
    }

    private static String weekLabel(String value) {
        if (Prefs.MONDAY.equals(value)) return "Monday";
        if (Prefs.SUNDAY.equals(value)) return "Sunday";
        return java.util.Calendar.getInstance().getFirstDayOfWeek() == java.util.Calendar.SUNDAY
            ? "Sunday (region)" : "Monday (region)";
    }

    private static String downloadLabel(String value) {
        return Prefs.ALWAYS.equals(value) ? "Always" : Prefs.NEVER.equals(value) ? "Never" : "On Wi-Fi only";
    }

    private void choose(String title, String[] values, String[] labels, String current, String note,
                        final Chosen chosen) {
        List<Sheet.Choice> choices = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            choices.add(new Sheet.Choice(labels[i], 0, null, () -> chosen.value(value), value.equals(current)));
        }
        Sheet.pick(app, title, choices, note);
    }

    private interface Chosen {
        void value(String value);
    }

    private void theme() {
        choose("Theme", new String[] { Prefs.LIGHT, Prefs.DARK, Prefs.PHONE },
            new String[] { "Light", "Dark", "Follow phone" }, app.prefs.theme, null,
            value -> app.savePrefs(app.prefs.withTheme(value)));
    }

    private void textSize() {
        choose("Text size", new String[] { Prefs.NORMAL, Prefs.LARGE }, new String[] { "Default", "Large" },
            app.prefs.textSize, null, value -> app.savePrefs(app.prefs.withTextSize(value)));
    }

    private void reminders() {
        List<String> values = Prefs.reminderChoices();
        String[] labels = new String[values.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Prefs.reminderLabel(values.get(i));
        choose("Remind me about due work", values.toArray(new String[0]), labels, app.prefs.reminders,
            "You can turn reminders off for one class from its menu.",
            value -> app.savePrefs(app.prefs.withReminders(value)));
    }

    private void week() {
        String current = app.prefs.weekStart == null ? "" : app.prefs.weekStart;
        choose("Week starts on", new String[] { Prefs.MONDAY, Prefs.SUNDAY }, new String[] { "Monday", "Sunday" },
            current, null, value -> app.savePrefs(app.prefs.withWeekStart(value)));
    }

    private void downloads() {
        choose("Download teacher files", new String[] { Prefs.ALWAYS, Prefs.WIFI, Prefs.NEVER },
            new String[] { "Always", "On Wi-Fi only", "Never" }, app.prefs.downloads,
            "Files are kept on the phone so they open with no signal. A file you open is always downloaded.",
            value -> app.savePrefs(app.prefs.withDownloads(value)));
    }

    @Override
    boolean globalOptions() {
        return false;
    }
}
