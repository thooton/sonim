package com.print.xp3;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The one activity: a stack of {@link Screen}s, Home at the bottom, and a
 * dialog drawn over the top one when a setting's choices are open. Back
 * goes back a screen; on Home it leaves. Menu opens what the screen offers
 * that no button does (forget a printer, sort files), when it offers any.
 */
public final class MainActivity extends Activity {

    static final String EXTRA_JOB = "job";
    private static final int ASK_STORAGE = 1;
    private static final int ASK_LOCATION = 2;

    private FrameLayout root;
    private final List<Screen> stack = new ArrayList<Screen>();
    private View overlay;
    private Runnable overlayClosed;
    private Runnable whenAllowed;
    private Runnable whenAsked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Wifi.bind(this);
        Look.load(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Look.WHITE);
        setContentView(root);
        open(new HomeScreen());
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Screen top = top();
        if (top != null) top.shown();
    }

    @Override
    protected void onPause() {
        Screen top = top();
        if (top != null) top.hidden();
        super.onPause();
    }

    /** A PDF opened from another app, or a job's notification. */
    private void handle(Intent intent) {
        if (intent == null) return;
        int job = intent.getIntExtra(EXTRA_JOB, 0);
        if (job > 0) {
            Queue.Entry entry = Queue.get(job);
            if (entry != null) {
                home();
                open(new PrintingScreen(entry));
            }
            intent.removeExtra(EXTRA_JOB);
            return;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            final Uri uri = intent.getData();
            setIntent(new Intent(this, MainActivity.class));
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                final File file = new File(uri.getPath());
                allowed(() -> open(new PreviewScreen(file, null)));
                return;
            }
            File copy = received(uri);
            if (copy == null) toast("Can't open this file");
            else open(new PreviewScreen(copy, null));
        }
    }

    /** A shared document copied in, since the link to it lasts only as long as this intent. */
    private File received(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "Document";
        name = name.replaceAll(".*[/:]", "").replaceAll("[^A-Za-z0-9 ._()-]", "_");
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) name += ".pdf";
        File dir = new File(getCacheDir(), "opened");
        dir.mkdirs();
        File out = new File(dir, name);
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream write = new FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) write.write(buffer, 0, read);
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // The stack.

    Screen top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    void open(Screen screen) {
        closeOverlay();
        Screen below = top();
        if (below != null) {
            below.remember();
            below.hidden();
        }
        screen.app = this;
        screen.view = screen.build();
        stack.add(screen);
        show(screen, screen.first());
    }

    /** Back a screen. */
    void close() {
        closeOverlay();
        if (stack.size() <= 1) {
            finish();
            return;
        }
        Screen gone = stack.remove(stack.size() - 1);
        gone.hidden();
        gone.closed();
        Screen back = top();
        show(back, back.restore());
    }

    /** Closes the top screen and opens `screen` in its place. */
    void replace(Screen screen) {
        closeOverlay();
        if (stack.size() > 1) {
            Screen gone = stack.remove(stack.size() - 1);
            gone.hidden();
            gone.closed();
        }
        open(screen);
    }

    /** Back to Home. */
    void home() {
        closeOverlay();
        while (stack.size() > 1) {
            Screen gone = stack.remove(stack.size() - 1);
            gone.hidden();
            gone.closed();
        }
        Screen home = top();
        show(home, home.restore());
    }

    private void show(Screen screen, View focus) {
        root.removeAllViews();
        root.addView(screen.view, new FrameLayout.LayoutParams(Parts.FILL, Parts.FILL));
        screen.shown();
        View target = focus != null ? focus : screen.first();
        if (target != null && target.isShown()) target.requestFocus();
        else if (target != null) target.post(target::requestFocus);
    }

    // Keys.

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (up && !event.isCanceled()) back();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SOFT_LEFT) {
            if (up && !event.isCanceled() && event.getRepeatCount() == 0) menu();
            return true;
        }
        if (overlay != null) return super.dispatchKeyEvent(event);
        Screen top = top();
        if (top != null && top.key(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean menuOpen;

    /** Menu: the top screen's options in a list, or nothing when it has none; Menu again closes it. */
    private void menu() {
        if (overlay != null) {
            if (menuOpen) closeOverlay();
            return;
        }
        Screen top = top();
        if (top == null) return;
        final List<Screen.Option> options = new ArrayList<Screen.Option>();
        top.options(options);
        if (options.isEmpty()) return;
        List<Choice> choices = new ArrayList<Choice>();
        for (Screen.Option option : options) choices.add(new Choice(option.title, null));
        choose("Options", choices, -1, null, which -> options.get(which).action.run());
        menuOpen = true;
    }

    private void back() {
        if (overlay != null) {
            closeOverlay();
            return;
        }
        Screen top = top();
        if (top != null && top.back()) return;
        close();
    }

    // Dialogs.

    /** One choice in a dialog: a title, and a smaller line under it (or null). */
    static final class Choice {
        final String title;
        final String sub;

        Choice(String title, String sub) {
            this.title = title;
            this.sub = sub;
        }
    }

    interface Chosen {
        void chose(int index);
    }

    /**
     * The choice dialog (screen 07): a white card over the dimmed screen,
     * a radio list with the saved value marked and focused, and one line of
     * help. OK picks; Back closes without changing anything.
     */
    void choose(String title, List<Choice> choices, int selected, String hint, final Chosen chosen) {
        final LinearLayout card = Parts.column(this);
        card.setBackground(Look.rounded(Look.WHITE, 8, this));
        card.setElevation(Look.dp(this, 10));
        card.setClipToOutline(true);
        TextView heading = Look.line(this, title, 13, Look.BOLD, Look.INK);
        heading.setPadding(Look.dp(this, 12), Look.dp(this, 10), Look.dp(this, 12), Look.dp(this, 6));
        card.addView(heading);
        LinearLayout list = Parts.column(this);
        View focus = null;
        for (int i = 0; i < choices.size(); i++) {
            final int index = i;
            Choice choice = choices.get(i);
            card.addView(Parts.rule(this), Parts.line(Parts.FILL, 1));
            Parts.Row row = new Parts.Row(this, 34);
            row.noDivider();
            // A list of actions (selected -1) has no radios: nothing is chosen.
            if (selected >= 0) row.lead(new Parts.Radio(this, i == selected), Look.dp(this, 14), Look.dp(this, 14));
            row.titled(choice.title, choice.sub);
            row.setOnClickListener(v -> {
                closeOverlay();
                chosen.chose(index);
            });
            list.addView(row, Parts.line(Parts.FILL, Parts.WRAP));
            if (i == selected || focus == null) focus = row;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        card.addView(scroll, new LinearLayout.LayoutParams(Parts.FILL, Parts.WRAP, 1));
        if (hint != null) {
            card.addView(Parts.rule(this), Parts.line(Parts.FILL, 1));
            TextView help = Look.text(this, hint, 9, Look.REGULAR, Look.MUTE);
            help.setPadding(Look.dp(this, 12), Look.dp(this, 6), Look.dp(this, 12), Look.dp(this, 8));
            card.addView(help);
        }
        showOverlay(card, focus, null);
    }

    /** A question with its answer as a button, and a way out ("Cancel this print job?"). */
    void ask(String title, String message, String yes, int kind, final Runnable then, String no) {
        LinearLayout card = Parts.column(this);
        card.setBackground(Look.rounded(Look.WHITE, 8, this));
        card.setElevation(Look.dp(this, 10));
        card.setPadding(0, Look.dp(this, 12), 0, Look.dp(this, 8));
        TextView heading = Look.text(this, title, 13, Look.BOLD, Look.INK);
        heading.setPadding(Look.dp(this, 12), 0, Look.dp(this, 12), 0);
        card.addView(heading);
        if (message != null) {
            TextView body = Look.text(this, message, 10.5f, Look.REGULAR, Look.MUTE);
            body.setPadding(Look.dp(this, 12), Look.dp(this, 4), Look.dp(this, 12), 0);
            card.addView(body);
        }
        TextView keep = Parts.button(this, no, Parts.SECONDARY, this::closeOverlay);
        TextView act = Parts.button(this, yes, kind, () -> {
            closeOverlay();
            then.run();
        });
        LinearLayout pair = Parts.pair(this, keep, act);
        LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Parts.WRAP);
        lp.topMargin = Look.dp(this, 10);
        card.addView(pair, lp);
        // The safe answer is focused, so OK by accident changes nothing.
        showOverlay(card, keep, null);
    }

    private void showOverlay(View card, View focus, Runnable closed) {
        closeOverlay();
        Screen top = top();
        if (top != null) {
            top.remember();
            if (top.view instanceof ViewGroup) {
                ((ViewGroup) top.view).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
        }
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x8C0C1020);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(Parts.FILL, Parts.WRAP, Gravity.TOP);
        at.leftMargin = at.rightMargin = Look.dp(this, 14);
        at.topMargin = Look.dp(this, 50);
        at.bottomMargin = Look.dp(this, 14);
        layer.addView(card, at);
        root.addView(layer, new FrameLayout.LayoutParams(Parts.FILL, Parts.FILL));
        overlay = layer;
        overlayClosed = closed;
        if (focus != null) focus.post(focus::requestFocus);
    }

    void closeOverlay() {
        if (overlay == null) return;
        root.removeView(overlay);
        overlay = null;
        menuOpen = false;
        Screen top = top();
        if (top != null) {
            if (top.view instanceof ViewGroup) {
                ((ViewGroup) top.view).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
            View back = top.restore();
            if (back != null) back.requestFocus();
        }
        Runnable closed = overlayClosed;
        overlayClosed = null;
        if (closed != null) closed.run();
    }

    // Permissions.

    /** Runs `then` once storage can be read, asking the first time. */
    void allowed(Runnable then) {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            then.run();
            return;
        }
        whenAllowed = then;
        requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
    }

    /**
     * Asks once for the location permission, without which Android 8.1
     * won't tell an app the Wi-Fi network's name; `then` runs either way.
     */
    void askLocation(Runnable then) {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || getSharedPreferences("print", MODE_PRIVATE).getBoolean("askedLocation", false)) {
            then.run();
            return;
        }
        getSharedPreferences("print", MODE_PRIVATE).edit().putBoolean("askedLocation", true).apply();
        whenAsked = then;
        requestPermissions(new String[] {Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == ASK_LOCATION) {
            Runnable then = whenAsked;
            whenAsked = null;
            if (then != null) then.run();
            return;
        }
        if (code != ASK_STORAGE) return;
        Runnable then = whenAllowed;
        whenAllowed = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (then != null) then.run();
        } else {
            toast("Print needs to read storage to find PDFs");
        }
    }

    void toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }
}
