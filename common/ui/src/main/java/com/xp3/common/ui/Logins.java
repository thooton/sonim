package com.xp3.common.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.xp3.common.Login;

import java.util.function.Consumer;

/**
 * The command center login as the apps keep it: under "login" in their
 * preferences, with the build's bundled one adopted first, and the Command
 * center dialog for typing one in.
 */
public final class Logins {

    private Logins() {}

    /**
     * The bundled login (the app's generated `Bundled` constants) is adopted
     * once per build that carries a new one; otherwise whatever was saved,
     * perhaps typed in, is kept.
     */
    public static Login load(SharedPreferences prefs, String revision, String server, String token) {
        if (revision.length() > 0 && !revision.equals(prefs.getString("bundled", ""))) {
            Login bundled;
            try {
                bundled = Login.of(server, token);
            } catch (IllegalArgumentException e) {
                bundled = Login.blank();
            }
            if (bundled.ready()) {
                prefs.edit().putString("login", bundled.toJson()).putString("bundled", revision).apply();
                return bundled;
            }
        }
        return Login.parse(prefs.getString("login", ""));
    }

    public static void save(SharedPreferences prefs, Login login) {
        prefs.edit().putString("login", login.toJson()).apply();
    }

    /**
     * The Command center dialog, the fallback for a build without a bundled
     * login: the address and the token, typed. A blank token keeps the saved
     * one. Save checks both and passes the new login to `saved` (which
     * stores it); what is wrong with them goes to `refused`.
     */
    public static AlertDialog ask(Activity activity, final Login login, final Consumer<Login> saved,
                                  final Consumer<String> refused) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(activity, 16);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText server = new EditText(activity);
        server.setHint("https://…");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(login.server() == null ? "" : login.server());
        final EditText token = new EditText(activity);
        token.setHint(login.ready() ? "••••••••" : "Token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(server);
        box.addView(token);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", (shown, which) -> {
                String typed = token.getText().toString();
                if (typed.trim().length() == 0 && login.ready()) typed = login.token();
                Login next;
                try {
                    next = Login.of(server.getText().toString(), typed);
                    if (!next.ready()) throw new IllegalArgumentException("Both the address and the token are needed");
                } catch (IllegalArgumentException e) {
                    refused.accept(e.getMessage());
                    return;
                }
                saved.accept(next);
            })
            .setNegativeButton("Cancel", null)
            .show();
        // A single-line field keeps Up and Down to itself; on a keys-only
        // phone the d-pad is how one goes from field to field and on to Save.
        View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        upDown(server, null, token);
        upDown(token, server, save);
        upDown(save, token, null);
        upDown(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), token, null);
        return dialog;
    }

    /** Up and Down move the focus from `view` to `up` and `down` (null: as Android has it). */
    private static void upDown(View view, final View up, final View down) {
        if (view == null) return;
        view.setOnKeyListener((v, keyCode, event) -> {
            View to = keyCode == KeyEvent.KEYCODE_DPAD_UP ? up
                : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? down : null;
            if (to == null) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) to.requestFocus();
            return true;
        });
    }
}
