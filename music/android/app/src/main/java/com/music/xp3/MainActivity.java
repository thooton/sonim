package com.music.xp3;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.music.xp3.net.Login;
import com.music.xp3.net.Music;
import java.util.ArrayList;
import java.util.List;

/**
 * One screen: type "Artist - Title" (or "Artist - Album"), press OK, and the
 * command center finds and downloads it while its log scrolls by below. The
 * files land in the phone's Music folder, albums in a folder of their own.
 *
 * Keys: OK downloads; Left and Right switch between Song and Album while the
 * box is empty; Up and Down scroll the log; Back cancels a download, or
 * leaves; Menu has the rest.
 */
public final class MainActivity extends Activity implements Download.Listener {

    private static final int ASK_STORAGE = 1;
    private static final String PREFS = "music";

    private EditText query;
    private TextView modeView;
    private TextView statusView;
    private TextView logView;
    private ScrollView scroll;
    private String mode = Music.SONG;
    private Login login = Login.blank();
    private boolean startWhenAllowed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        query = (EditText) findViewById(R.id.query);
        modeView = (TextView) findViewById(R.id.mode);
        statusView = (TextView) findViewById(R.id.status);
        logView = (TextView) findViewById(R.id.log);
        scroll = (ScrollView) findViewById(R.id.scroll);

        SharedPreferences prefs = prefs();
        mode = Music.ALBUM.equals(prefs.getString("mode", Music.SONG)) ? Music.ALBUM : Music.SONG;
        login = loadLogin(prefs);

        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    begin();
                    return true;
                }
                return false;
            }
        });
        showMode();
        query.requestFocus();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from the menu) is kept.
     */
    private Login loadLogin(SharedPreferences prefs) {
        String revision = Defaults.revision();
        if (revision.length() > 0 && !revision.equals(prefs.getString("bundled", ""))) {
            Login bundled = Defaults.login();
            if (bundled.ready()) {
                prefs.edit().putString("login", bundled.toJson()).putString("bundled", revision).apply();
                return bundled;
            }
        }
        return Login.parse(prefs.getString("login", ""));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Download.listen(this);
        changed();
    }

    @Override
    protected void onStop() {
        Download.listen(null);
        super.onStop();
    }

    @Override
    public void changed() {
        boolean running = Download.running();
        String status = Download.status();
        if (status.length() == 0) {
            status = login.ready() ? "OK downloads into Music." : "No command center: Menu → Command center.";
        }
        statusView.setText(status);
        StringBuilder text = new StringBuilder();
        for (String line : Download.log()) {
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        logView.setText(text);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
                query.requestFocus();
            }
        });
        query.setEnabled(!running);
    }

    private void showMode() {
        boolean album = Music.ALBUM.equals(mode);
        modeView.setText(album ? "◂ Album ▸" : "◂ Song ▸");
        query.setHint(album ? R.string.hint_album : R.string.hint_song);
    }

    private void toggleMode() {
        mode = Music.ALBUM.equals(mode) ? Music.SONG : Music.ALBUM;
        prefs().edit().putString("mode", mode).apply();
        showMode();
    }

    private void begin() {
        if (Download.running()) return;
        String text = query.getText().toString().trim();
        if (text.length() == 0) return;
        if (text.startsWith("-")) {
            statusView.setText("A name can't start with \"-\".");
            return;
        }
        if (!login.ready()) {
            askLogin();
            return;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            startWhenAllowed = true;
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
            return;
        }
        Download.start(this, login, mode, text);
        query.setText("");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_STORAGE) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && startWhenAllowed) {
            startWhenAllowed = false;
            begin();
        } else if (!granted) {
            statusView.setText("Music needs storage access to save into the Music folder.");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean empty = query.getText().length() == 0;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // On the press: T9 eats the first release.
                if (down && event.getRepeatCount() == 0) begin();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (empty || Download.running()) {
                    if (down && event.getRepeatCount() == 0 && !Download.running()) toggleMode();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int step = (int) (40 * getResources().getDisplayMetrics().density);
                    scroll.smoothScrollBy(0, key == KeyEvent.KEYCODE_DPAD_UP ? -step : step);
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!down) menu();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (Download.running()) {
                    if (!down) Download.cancel();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    private void menu() {
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        if (Download.running()) {
            labels.add("Cancel download");
            actions.add(new Runnable() { public void run() { Download.cancel(); } });
        } else {
            labels.add(Music.ALBUM.equals(mode) ? "Download a song instead" : "Download an album instead");
            actions.add(new Runnable() { public void run() { toggleMode(); } });
            labels.add("Clear log");
            actions.add(new Runnable() { public void run() { Download.clearLog(); } });
            labels.add("Reconnect music account");
            actions.add(new Runnable() { public void run() { confirmRelogin(); } });
        }
        labels.add("Command center…");
        actions.add(new Runnable() { public void run() { askLogin(); } });
        new AlertDialog.Builder(this)
            .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    actions.get(which).run();
                }
            })
            .show();
    }

    /** As `delta music re-login`: only when downloads keep failing to connect. */
    private void confirmRelogin() {
        if (!login.ready()) {
            askLogin();
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage("Only if downloads keep saying they couldn't connect: too many new logins get the command center throttled. Reconnect?")
            .setPositiveButton("Reconnect", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    relogin();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void relogin() {
        statusView.setText("Reconnecting…");
        final Login using = login;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    new Music(using).relogin();
                    result = "Reconnected. Try the download again.";
                } catch (Exception e) {
                    result = "Couldn't reconnect: " + e.getMessage();
                }
                final String shown = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusView.setText(shown);
                    }
                });
            }
        }).start();
    }

    /** The fallback for a build without a bundled login. */
    private void askLogin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText server = new EditText(this);
        server.setHint("https://…");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(login.server() == null ? "" : login.server());
        final EditText token = new EditText(this);
        token.setHint(login.ready() ? "Token (blank keeps it)" : "Delta token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(server);
        box.addView(token);
        new AlertDialog.Builder(this)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String typed = token.getText().toString();
                    if (typed.trim().length() == 0 && login.ready()) typed = login.token();
                    try {
                        Login next = Login.of(server.getText().toString(), typed);
                        if (!next.ready()) throw new IllegalArgumentException("Both the address and the token are needed");
                        login = next;
                        prefs().edit().putString("login", next.toJson()).apply();
                        statusView.setText("Saved: " + next.host());
                    } catch (IllegalArgumentException e) {
                        statusView.setText(e.getMessage());
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
