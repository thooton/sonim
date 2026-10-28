package com.tree.xp3;

import android.content.Context;
import android.content.SharedPreferences;

import com.tree.xp3.qa.Center;
import com.tree.xp3.qa.Qa;
import com.xp3.common.Disk;
import com.xp3.common.Login;

import java.io.File;
import java.io.IOException;

/**
 * What every screen shares while the app runs: the command center login and
 * the last `GET /qa` it answered, which is also kept in `files/qa.json` so
 * the home screen has something to show at once.
 */
final class Tree {

    private Tree() {}

    private static final String PREFS = "tree";
    private static final String CACHE = "qa.json";

    static Login login = Login.blank();
    /** The last QA the command center sent, or null before any. */
    static Qa qa;
    /** When qa was last fetched (0: only read from the file). */
    static long fetched;
    private static boolean loaded;

    /** Reads the login and the kept QA, once per process. */
    static void init(Context context) {
        if (loaded) return;
        loaded = true;
        login = loadLogin(prefs(context));
        String json = Disk.read(new File(context.getFilesDir(), CACHE), 4 << 20);
        if (json != null) {
            try {
                qa = Qa.parse(json);
            } catch (RuntimeException e) {
                qa = null;
            }
        }
    }

    /** The client, or null without a login. */
    static Center center() {
        return login.ready() ? new Center(login) : null;
    }

    /** Fetches the QA (on a worker thread) and keeps it. */
    static Qa fetch(Context context, boolean fresh) throws IOException, InterruptedException {
        Center center = center();
        if (center == null) throw new IOException("No command center");
        String json = center.qaJson(fresh);
        Qa got = Qa.parse(json);
        synchronized (Tree.class) {
            Disk.write(new File(context.getFilesDir(), CACHE), json, true);
        }
        return got;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void saveLogin(Context context, Login next) {
        login = next;
        prefs(context).edit().putString("login", next.toJson()).apply();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (typed in from the menu) is kept.
     */
    private static Login loadLogin(SharedPreferences prefs) {
        String revision = Bundled.REVISION;
        if (revision.length() > 0 && !revision.equals(prefs.getString("bundled", ""))) {
            Login bundled;
            try {
                bundled = Login.of(Bundled.SERVER, Bundled.TOKEN);
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

    /** A failure, for the screen. */
    static String message(Throwable e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) return "Offline";
        if (e instanceof java.net.SocketTimeoutException) return "The command center didn't answer";
        return e.getMessage() == null ? "Something went wrong" : e.getMessage();
    }
}
