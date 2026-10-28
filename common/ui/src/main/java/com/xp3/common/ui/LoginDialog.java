package com.xp3.common.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.xp3.common.Login;

/**
 * The command center login as the apps keep it, and the "Command center"
 * dialog that types one in: the fallback for a build without a bundled
 * login, and for moving to another command center.
 */
public final class LoginDialog {

    public interface Saved {
        void saved(Login login);
    }

    private LoginDialog() {}

    /**
     * The bundled login (gradle/bundled.gradle's `Bundled` class), adopted
     * once per build that carries a new one; otherwise whatever was saved.
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
     * Asks for the address and the token, keeping `current`'s token when that
     * field is left empty. Save closes the dialog only once the login is whole
     * and saved into `prefs`, so a slip keeps what was typed for another try.
     * Up and Down go between the fields and the buttons; OK in the address
     * goes on to the token, and OK in the token saves.
     */
    public static AlertDialog show(Context context, SharedPreferences prefs, final Login current, final Saved saved) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(context, 16);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText server = field(context, "https://…", InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT);
        server.setText(current.server() == null ? "" : current.server());
        final EditText token = field(context, current.ready() ? "••••••••" : "Token",
            InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        box.addView(server);
        box.addView(token);
        final AlertDialog ask = new AlertDialog.Builder(context)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        final Runnable next = () -> {
            token.requestFocus();
            token.setSelection(token.length());
        };
        final Runnable save = () -> {
            String typed = token.getText().toString();
            if (typed.trim().length() == 0 && current.ready()) typed = current.token();
            Login login;
            try {
                login = Login.of(server.getText().toString(), typed);
            } catch (IllegalArgumentException e) {
                Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
                (addressFault(server) ? server : token).requestFocus();
                return;
            }
            if (!login.ready()) {
                Toast.makeText(context, "Both the address and the token are needed", Toast.LENGTH_SHORT).show();
                (server.getText().toString().trim().length() == 0 ? server : token).requestFocus();
                return;
            }
            save(prefs, login);
            ask.dismiss();
            saved.saved(login);
        };
        ask.setOnKeyListener((shown, code, event) -> {
            View at = ask.getCurrentFocus();
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (Ui.isOk(code) && (at == server || at == token)) {
                if (down && event.getRepeatCount() == 0) (at == server ? next : save).run();
                return true;
            }
            if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (!down) return true;
            View to = at == null ? null : at.focusSearch(code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
            if (to != null) to.requestFocus();
            return true;
        });
        server.setOnEditorActionListener((view, action, event) -> {
            if (event != null) return false;
            next.run();
            return true;
        });
        token.setOnEditorActionListener((view, action, event) -> {
            if (event != null) return false;
            save.run();
            return true;
        });
        ask.show();
        ask.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> save.run());
        return ask;
    }

    private static EditText field(Context context, String hint, int variation, int action) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        field.setImeOptions(action);
        return field;
    }

    /** Whether it is the typed address that Login refuses. */
    private static boolean addressFault(EditText server) {
        try {
            Login.of(server.getText().toString(), null);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
