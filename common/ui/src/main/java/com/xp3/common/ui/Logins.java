package com.xp3.common.ui;

import android.content.SharedPreferences;

import com.xp3.common.Login;

/**
 * The command center login an app keeps in its preferences, under "login".
 *
 * A build can carry a login of its own (gradle/bundled.gradle). That one is
 * adopted once per build that carries a new one, recorded under "bundled" by
 * its revision, so a login typed in later is not overwritten on every start.
 */
public final class Logins {

    private Logins() {}

    /** The bundled login when this build brings a new one, else what was saved. */
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
}
