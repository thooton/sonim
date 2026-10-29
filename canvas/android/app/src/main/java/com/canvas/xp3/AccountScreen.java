package com.canvas.xp3;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.net.Config;

import java.io.IOException;

/**
 * The Canvas account: the school's Canvas address, the cookie service that
 * holds the login, its token, and the time zone. Sign out removes them and
 * everything fetched with them.
 *
 * The app never sees a password. Canvas's login lives with the cookie
 * service; the phone keeps the session cookie in memory for a quarter of an
 * hour and never writes it down.
 */
final class AccountScreen extends Screen {

    AccountScreen(MainActivity app) {
        super(app);
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        Config config = app.backend.config();
        LinearLayout root = v.column();
        root.addView(parts.nav("Settings"), Views.wide());
        TextView heading = v.screenTitle("Account");
        v.pad(heading, 12, 0, 12, 2);
        root.addView(heading, Views.wide());
        TextView state = v.meta(app.backend.canvasReady() ? "Signed in to Canvas" : "Not set up");
        v.pad(state, 12, 0, 12, 6);
        root.addView(state, Views.wide());
        LinearLayout list = parts.page(this, root);
        list.addView(v.hairline());
        list.addView(field(config, Config.CANVAS_URL, "Canvas address", false, true), Views.wide());
        list.addView(field(config, Config.COOKIE_URL, "Cookie service", false, true), Views.wide());
        list.addView(field(config, Config.COOKIE_TOKEN, "Service token", true, false), Views.wide());
        list.addView(field(config, Config.TIMEZONE, "Time zone", false, false), Views.wide());
        if (app.backend.hasBundledSetup()) {
            list.addView(action("restore", "Use the bundled setup", this::restore), Views.wide());
        }
        list.addView(action("signout", "Sign out", this::signOut), Views.wide());
        return root;
    }

    private Item field(Config config, final String key, final String label, boolean secret, final boolean address) {
        String value = config.get(key);
        String shown = value.length() == 0 ? "Not set" : secret ? "••••••••" : value.replaceFirst("^https://", "");
        Item row = new Item(app, key, () -> edit(key, label, address));
        LinearLayout line = v.row();
        v.pad(line, 12, 7, 12, 7);
        MarkText name = v.mark(label, 11, look.semibold, look.ink);
        line.addView(name, v.margins(Views.wrap(), 0, 0, 8, 0));
        TextView current = v.text(shown, 9.5f, look.medium, look.muted);
        current.setSingleLine(true);
        current.setEllipsize(TextUtils.TruncateAt.START);
        current.setGravity(Gravity.RIGHT);
        line.addView(current, Views.fill());
        row.addView(line, Views.wide());
        row.addView(v.hairline());
        row.marks(name, look);
        return row;
    }

    private Item action(String key, String label, Runnable run) {
        Item row = new Item(app, key, run);
        LinearLayout line = v.row();
        v.pad(line, 12, 7, 12, 7);
        MarkText name = v.mark(label, 11, look.semibold, look.ink);
        line.addView(name, Views.fill());
        row.addView(line, Views.wide());
        row.addView(v.hairline());
        row.marks(name, look);
        return row;
    }

    private void edit(String key, String label, boolean address) {
        edit(key, label, address, app.backend.config().get(key));
    }

    private void edit(final String key, final String label, final boolean address, String start) {
        String help = Config.TIMEZONE.equals(key) ? "Leave empty to use the phone's own, or type one like "
            + "America/Phoenix." : Config.COOKIE_TOKEN.equals(key) ? "Copy it into a message to the phone, then "
            + "paste it here from Options." : null;
        FieldScreen screen = new FieldScreen(app, label, start, label,
            value -> apply(key, label, address, value)).exact(address);
        if (help != null) screen.help(help);
        app.push(screen);
    }

    private void apply(String key, String label, boolean address, String value) {
        Config next;
        try {
            next = app.backend.config().with(key, value);
        } catch (IllegalArgumentException e) {
            // Back to the field with the text as typed: retyping a whole
            // address on a keypad for one wrong letter is the real cost.
            edit(key, label, address, value);
            app.tell("Not saved", e.getMessage() == null ? "That value wasn't accepted." : e.getMessage());
            return;
        }
        try {
            app.backend.saveSettings(next);
        } catch (IOException e) {
            app.tell("Not saved", "Couldn't write to this phone's storage.");
            return;
        }
        // A different Canvas account means the work on screen belongs to
        // somebody else; drop it rather than showing it until a sync replaces it.
        if (!Config.TIMEZONE.equals(key)) app.forgetWork();
        app.refreshIfStale();
        refresh();
    }

    private void restore() {
        try {
            app.backend.restoreBundledSetup();
        } catch (IOException | RuntimeException e) {
            app.tell("Not restored", "This build's setup couldn't be used.");
            return;
        }
        app.forgetWork();
        app.refreshIfStale();
        refresh();
    }

    private void signOut() {
        Sheet.ask(app, "Sign out?", "The Canvas address, the token, your saved work and everything fetched are "
            + "removed from this phone.", "Sign out", "Cancel", () -> {
                app.backend.signOut();
                app.forgetWork();
                refresh();
            });
    }

    @Override
    boolean globalOptions() {
        return false;
    }
}
