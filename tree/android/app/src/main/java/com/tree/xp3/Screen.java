package com.tree.xp3;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tree.xp3.qa.Center;
import com.tree.xp3.qa.Qa;
import com.xp3.common.Login;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * What every screen here has: the family's dark 36dp header saying where you
 * are or how things stand, a thin bar under it while something is being
 * fetched, the content below, and the soft keys (left is Menu, right is
 * Back).
 */
abstract class Screen extends BaseActivity {

    /** One worker for the whole app, so requests go in the order they were made. */
    static final ExecutorService WORKER = Ui.worker("tree");

    private TextView title;
    private TextView meta;
    private ProgressBar working;
    private int busy;
    protected FrameLayout content;

    interface Done<T> {
        void got(T value);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Tree.init(this);
        LinearLayout root = vertical();
        root.setBackgroundColor(color(R.color.page));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(color(R.color.primary));
        bar.setPadding(dp(10), 0, dp(10), 0);
        title = text("", 15, color(R.color.on_primary));
        title.setTypeface(Family.MEDIUM);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        meta = text("", 12, color(R.color.on_primary_dim));
        meta.setSingleLine(true);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        right.leftMargin = dp(8);
        bar.addView(meta, right);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        FrameLayout body = new FrameLayout(this);
        content = new FrameLayout(this);
        body.addView(content);
        working = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        working.setIndeterminate(true);
        working.setIndeterminateTintList(getColorStateList(R.color.focus_rule));
        working.setVisibility(View.GONE);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6), Gravity.TOP);
        top.topMargin = -dp(3);
        body.addView(working, top);
        root.addView(body, Ui.weighted());
        setContentView(root);
    }

    protected final void header(String left, String right) {
        title.setText(left);
        meta.setText(right == null ? "" : right);
        meta.setVisibility(right == null || right.length() == 0 ? View.GONE : View.VISIBLE);
    }

    protected final boolean busy() {
        return busy > 0;
    }

    /**
     * Runs `task` on the worker and hands its result to `done` on the main
     * thread; a failure is shown as a toast, or handed to `failed` when there
     * is one. Nothing is delivered to a screen that has gone.
     */
    protected final <T> void work(final Callable<T> task, final Done<T> done, final Done<String> failed) {
        busy++;
        working.setVisibility(View.VISIBLE);
        WORKER.execute(() -> {
            T value = null;
            String failure = null;
            try {
                value = task.call();
            } catch (Exception e) {
                failure = Tree.message(e);
            }
            final T got = value;
            final String why = failure;
            runOnUiThread(() -> {
                busy--;
                if (busy == 0) working.setVisibility(View.GONE);
                if (isDestroyed()) return;
                if (why == null) done.got(got);
                else if (failed != null) failed.got(why);
                else toast(why);
            });
        });
    }

    /** The client, or null after asking for a login. */
    protected final Center center() {
        Center center = Tree.center();
        if (center == null) askLogin();
        return center;
    }

    /** Called when something here changed the QA (a run, a setting). */
    protected void changed() {}

    /** Starts a run of the apps named, or of every app; then refreshes. */
    protected final void startRun(final List<String> apps) {
        final Center center = center();
        if (center == null) return;
        work(() -> center.start(apps), run -> {
            if (Tree.qa != null) Tree.qa = Tree.qa.withRunning(run);
            toast(apps == null || apps.isEmpty() ? "Running QA on every app" : "Running QA");
            changed();
        }, null);
    }

    /** Asks first, then cancels the run. */
    protected final void cancelRun(final String id) {
        new AlertDialog.Builder(this)
            .setTitle("Cancel this run?")
            .setPositiveButton("Cancel run", (dialog, which) -> {
                final Center center = center();
                if (center == null) return;
                work(() -> center.cancel(id), run -> {
                    if (Tree.qa != null) Tree.qa = Tree.qa.withRunning(run);
                    toast("Cancelled");
                    changed();
                }, null);
            })
            .setNegativeButton("Keep going", null)
            .show();
    }

    /** The run going now, if Tree knows of one. */
    protected static Qa.Run running() {
        return Tree.qa == null ? null : Tree.qa.running;
    }

    /** A popup of choices under the header's right end. */
    protected final void popup(String[] labels, final Ui.Selected chose) {
        // Hung from the content, not the header: on API 27 the popup
        // overlaps its anchor, and from the header it covered it.
        PopupMenu menu = new PopupMenu(this, content, Gravity.END);
        for (int i = 0; i < labels.length; i++) menu.getMenu().add(0, i, i, labels[i]);
        menu.setOnMenuItemClickListener(item -> {
            chose.at(item.getItemId());
            return true;
        });
        menu.show();
    }

    /** What Menu (or the left soft key) opens. */
    protected abstract void menu();

    static boolean isMenu(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SOFT_LEFT;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isMenu(keyCode) || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isMenu(keyCode)) {
            if (!event.isCanceled()) menu();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            if (!event.isCanceled()) onBackPressed();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // Pieces of a grouped page (DESIGN.md rule 9).

    /** A white card, 6dp from the edges, its text 10dp in. */
    protected final LinearLayout card() {
        LinearLayout card = vertical();
        card.setBackgroundResource(com.xp3.common.ui.R.drawable.family_card);
        card.setPadding(dp(10), dp(8), dp(10), dp(9));
        return card;
    }

    protected final LinearLayout.LayoutParams cardSpacing() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(6), dp(6), dp(6), 0);
        return params;
    }

    /** BaseActivity's text, at a size between whole sp (13.5, 11.5). */
    protected final TextView text(CharSequence value, float sp, int color) {
        TextView view = text(value, 12, color);
        view.setTextSize(sp);
        return view;
    }

    /** A muted label over a value. */
    protected final TextView label(String value) {
        TextView view = text(value, 11.5f, color(R.color.text_muted));
        view.setPadding(0, 0, 0, dp(3));
        return view;
    }

    /** Text that leads, at 13.5sp, wrapping. */
    protected final TextView lead(CharSequence value) {
        TextView view = text(value, 13.5f, color(R.color.text));
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    protected final TextView medium(CharSequence value, float sp, int color) {
        TextView view = text(value, sp, color);
        view.setTypeface(Family.MEDIUM);
        return view;
    }

    /** The fallback for a build without a bundled login: the address and the token, typed. */
    protected final void askLogin() {
        LinearLayout box = vertical();
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, 0);
        final Login login = Tree.login;
        final EditText server = new EditText(this);
        server.setHint("https://…");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(login.server() == null ? "" : login.server());
        final EditText token = new EditText(this);
        token.setHint(login.ready() ? "••••••••" : "Token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(server);
        box.addView(token);
        final AlertDialog ask = new AlertDialog.Builder(this)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        // Up and Down never left the address field here, so the d-pad moves
        // between the fields and the buttons itself.
        ask.setOnKeyListener((shown, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            View at = ask.getCurrentFocus();
            View next = at == null ? null : at.focusSearch(code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
            if (next != null) next.requestFocus();
            return true;
        });
        ask.show();
        // Save closes the dialog only once the login is taken, so a mistake
        // keeps what was typed for correcting.
        ask.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String typed = token.getText().toString();
            if (typed.trim().length() == 0 && login.ready()) typed = login.token();
            try {
                Login next = Login.of(server.getText().toString(), typed);
                if (!next.ready()) throw new IllegalArgumentException("Both the address and the token are needed");
                Tree.saveLogin(this, next);
                ask.dismiss();
                toast("Saved: " + next.host());
                loggedIn();
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
            }
        });
    }

    /** After a login is typed in. */
    protected void loggedIn() {}
}
