package com.canvas.xp3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Draft;
import com.canvas.xp3.data.Notice;
import com.canvas.xp3.data.Prefs;
import com.canvas.xp3.data.Sync;
import com.canvas.xp3.data.Todos;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The one activity: a stack of screens, the softkey bar under them, and
 * whatever is sliding over them.
 *
 * Every screen names its three keys in the bar at the bottom, so nothing has
 * to be guessed. The left softkey opens Options, the right one is the
 * screen's single shortcut, and OK does what the middle of the bar says.
 * Back goes back one screen. The XP3's own softkeys arrive as Menu and Back
 * on some firmware and as the soft-left and soft-right codes on others, so
 * every shortcut on the right is in Options too: nothing depends on a key the
 * phone might not send.
 *
 * The work list lives here, above the screens, because nearly every screen
 * reads it: the home timeline, a class's grades, the assignment page.
 * Nothing on this side touches the network; Backend does, on the workers,
 * and results come back through the main thread's handler.
 */
public final class MainActivity extends BaseActivity {

    /**
     * How stale the list has to be before opening the app fetches a new one.
     * This app is opened to check one thing and closed again, often several
     * times in a row, and a round trip on this network is slow enough to be
     * the whole of that.
     */
    private static final long STALE_MS = 10L * 60L * 1000L;

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService work = Ui.workers("canvas-work", 2);
    Backend backend;
    Prefs prefs;
    private Look look;
    private Views views;

    final Todos todos = new Todos();
    Sync.Result snapshot;
    boolean syncing;
    String syncError;
    /** Pieces of work being turned in right now, and how far each has got. */
    final Map<String, long[]> progress = new HashMap<>();

    private final List<Screen> stack = new ArrayList<>();
    private FrameLayout content;
    private FrameLayout layer;
    private TextView keyLeft;
    private TextView keyCenter;
    private TextView keyRight;
    private View bar;
    private Sheet.Open overlay;
    private View focusBeforeOverlay;
    private Runnable afterPermission;
    private ConnectivityManager.NetworkCallback online;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Dates.setTwentyFourHour(DateFormat.is24HourFormat(this));
        backend = new Backend(getFilesDir());
        prefs = backend.prefs();
        applyWeekStart();
        snapshot = backend.cached();
        if (snapshot != null) {
            todos.replace(snapshot.work);
            todos.check(snapshot.checked);
        }
        theme();
        push(new HomeScreen(this));
        refreshIfStale();
        listenForSignal();
        Alarms.schedule(this, backend);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A phone like this is closed by being put in a pocket, so the screen
        // you come back to is often yesterday's; what is missing is derived
        // from the clock on every draw, and Canvas is worth asking again.
        if (started) redraw();
        started = true;
        refreshIfStale();
        flushQueue();
    }

    private boolean started;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (online != null) {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            try {
                manager.unregisterNetworkCallback(online);
            } catch (RuntimeException e) {
                // Already gone.
            }
        }
        for (Screen screen : stack) screen.closed();
        work.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Look
    // ------------------------------------------------------------------

    Look look() {
        return look;
    }

    Views views() {
        return views;
    }

    /** Builds the frame in the current theme. Called again when the theme or text size changes. */
    void theme() {
        look = Look.of(this, prefs);
        views = new Views(this, look);
        getWindow().setStatusBarColor(look.bg);
        getWindow().setNavigationBarColor(look.bg);
        getWindow().getDecorView().setSystemUiVisibility(look.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(look.bg));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(look.bg);
        LinearLayout column = views.column();
        content = new FrameLayout(this);
        column.addView(content, Views.tall());
        column.addView(softkeys(), Views.wide());
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        layer = new FrameLayout(this);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        for (Screen screen : stack) {
            screen.look = look;
            screen.v = views;
        }
        if (!stack.isEmpty()) show(top(), null, 0);
    }

    private View softkeys() {
        LinearLayout row = views.row();
        row.setBackgroundColor(look.bg);
        views.pad(row, 8, 0, 8, 0);
        keyLeft = views.text("", 9.5f, look.semibold, look.muted);
        keyCenter = views.text("", 9.5f, look.heavy, look.ink);
        keyCenter.setGravity(Gravity.CENTER);
        keyRight = views.text("", 9.5f, look.semibold, look.muted);
        keyRight.setGravity(Gravity.RIGHT);
        row.addView(keyLeft, Views.fill());
        row.addView(keyCenter, Views.fill());
        row.addView(keyRight, Views.fill());
        LinearLayout holder = views.column();
        holder.addView(views.hairline());
        holder.addView(row, new LinearLayout.LayoutParams(-1, views.dp(18)));
        bar = holder;
        return holder;
    }

    void applyWeekStart() {
        if (Prefs.SUNDAY.equals(prefs.weekStart)) Dates.setWeekStart(Calendar.SUNDAY);
        else if (Prefs.MONDAY.equals(prefs.weekStart)) Dates.setWeekStart(Calendar.MONDAY);
        else Dates.setWeekStart(Calendar.getInstance().getFirstDayOfWeek());
    }

    void savePrefs(Prefs next) {
        boolean restyle = !next.theme.equals(prefs.theme) || !next.textSize.equals(prefs.textSize);
        prefs = next;
        backend.savePrefs(next);
        applyWeekStart();
        Alarms.schedule(this, backend);
        if (restyle) theme();
        else redraw();
    }

    /** A passing line at the bottom of the screen. */
    void say(String text) {
        toast(text);
    }

    /** Something that went wrong, said once in the app's own dialog: what happened and what to do. */
    void tell(String title, String message) {
        Sheet.ask(this, title, message, "OK", "Close", () -> { });
    }

    /** A preference that changes nothing on screen, such as when Notifications was last read. */
    void savePrefsQuietly(Prefs next) {
        prefs = next;
        backend.savePrefs(next);
    }

    /** Drops the work on screen, which belonged to the setup just replaced. */
    void forgetWork() {
        snapshot = null;
        syncError = null;
        todos.replace(new ArrayList<Assignment>());
        todos.check(null);
    }

    // ------------------------------------------------------------------
    // The stack
    // ------------------------------------------------------------------

    Screen top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    void push(Screen screen) {
        screen.look = look;
        screen.v = views;
        stack.add(screen);
        show(screen, null, 0);
    }

    /** Replaces the top screen, for a step that leads straight into another. */
    void replace(Screen screen) {
        Screen old = stack.remove(stack.size() - 1);
        old.closed();
        push(screen);
    }

    void pop() {
        if (stack.size() <= 1) {
            finish();
            return;
        }
        Screen old = stack.remove(stack.size() - 1);
        old.closed();
        Screen under = top();
        show(under, under.savedFocus, under.savedScroll);
    }

    /** Closes the top screen and hands its result to the one underneath. */
    void finishWith(Object value) {
        Screen old = stack.remove(stack.size() - 1);
        old.closed();
        Screen under = top();
        under.result(value);
        show(under, under.savedFocus, under.savedScroll);
    }

    /** Rebuilds the top screen in place, keeping the d-pad and the scroll where they were. */
    void redraw() {
        Screen screen = top();
        if (screen == null) return;
        View focused = getCurrentFocus();
        String key = focused instanceof Item ? ((Item) focused).key : null;
        int y = screen.scroll == null ? 0 : screen.scroll.getScrollY();
        if (overlay != null) return;
        show(screen, key, y);
    }

    private void show(final Screen screen, String key, final int y) {
        // What the screen underneath had in focus comes back when it does.
        Screen previous = stack.size() > 1 && stack.get(stack.size() - 1) == screen
            ? stack.get(stack.size() - 2) : null;
        if (previous != null && previous.savedFocus == null) {
            View focused = getCurrentFocus();
            if (focused instanceof Item) previous.savedFocus = ((Item) focused).key;
            if (previous.scroll != null) previous.savedScroll = previous.scroll.getScrollY();
        }
        screen.scroll = null;
        View view = screen.build();
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        Item target = Screen.find(view, key);
        if (target == null) target = Screen.find(view, screen.firstFocus());
        if (target == null) {
            List<Item> all = Screen.items(view);
            if (!all.isEmpty()) target = all.get(0);
        }
        if (screen.scroll != null) {
            final android.widget.ScrollView scroller = screen.scroll;
            final Item focusAfter = target;
            scroller.post(() -> {
                scroller.scrollTo(0, y);
                // The scroll comes back first, then the d-pad's item is
                // brought into view if the old position left it off screen.
                if (focusAfter != null && focusAfter.isFocused()) {
                    focusAfter.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, focusAfter.getWidth(), focusAfter.getHeight()), true);
                }
            });
        }
        if (target != null) {
            target.requestFocus();
            focusMoved(target);
        } else {
            view.setFocusable(true);
            view.setDefaultFocusHighlightEnabled(false);
            view.requestFocus();
            updateKeys(null);
        }
        screen.savedFocus = null;
        screen.shown();
    }

    // ------------------------------------------------------------------
    // The softkey bar
    // ------------------------------------------------------------------

    void focusMoved(Item item) {
        if (overlay != null) {
            keyCenter.setText(item.ok == null ? overlay.center : item.ok);
            return;
        }
        updateKeys(item);
    }

    void updateKeys(Item item) {
        Screen screen = top();
        if (screen == null) return;
        keyLeft.setText(screen.left() == null ? "" : screen.left());
        String center = item != null && item.ok != null ? item.ok : screen.center();
        View focused = getCurrentFocus();
        if (focused instanceof EditText) center = screen.center();
        keyCenter.setText(center == null ? "" : center);
        keyRight.setText(screen.right() == null ? "" : screen.right());
    }

    void refreshKeys() {
        View focused = getCurrentFocus();
        updateKeys(focused instanceof Item ? (Item) focused : null);
    }

    // ------------------------------------------------------------------
    // Overlays
    // ------------------------------------------------------------------

    void openOverlay(Sheet.Open open) {
        if (overlay != null) closeOverlay();
        focusBeforeOverlay = getCurrentFocus();
        overlay = open;
        ((ViewGroup) content).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        layer.addView(open.view, new FrameLayout.LayoutParams(-1, -1));
        keyLeft.setText(open.left == null ? "" : open.left);
        keyCenter.setText(open.center == null ? "" : open.center);
        keyRight.setText(open.right == null ? "" : open.right);
        List<Item> items = Screen.items(open.view);
        if (!items.isEmpty()) items.get(0).requestFocus();
    }

    void closeOverlay() {
        if (overlay == null) return;
        layer.removeAllViews();
        overlay = null;
        ((ViewGroup) content).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (focusBeforeOverlay != null && focusBeforeOverlay.isAttachedToWindow()) {
            focusBeforeOverlay.requestFocus();
        }
        focusBeforeOverlay = null;
        refreshKeys();
    }

    boolean overlayOpen() {
        return overlay != null;
    }

    /** Options: the screen's own entries, then the app-wide ones. */
    void showOptions() {
        Screen screen = top();
        List<Sheet.Choice> choices = new ArrayList<>(screen.options());
        if (screen.globalOptions()) {
            if (!(screen instanceof ClassesScreen)) {
                choices.add(new Sheet.Choice("Classes", R.drawable.ic_apps, null,
                    () -> push(new ClassesScreen(this))));
            }
            if (!(screen instanceof NoticesScreen)) {
                int fresh = 0;
                for (Notice notice : backend.cachedNotices()) {
                    if (notice.at > prefs.noticesSeen) fresh++;
                }
                choices.add(new Sheet.Choice("Notifications", R.drawable.ic_bell,
                    fresh > 0 ? fresh + " new" : null, () -> push(new NoticesScreen(this))));
            }
            choices.add(new Sheet.Choice("Refresh", R.drawable.ic_refresh, null, () -> refresh(true)));
            if (!(screen instanceof SettingsScreen)) {
                choices.add(new Sheet.Choice("Settings", R.drawable.ic_tune, null,
                    () -> push(new SettingsScreen(this))));
            }
        }
        if (choices.isEmpty()) return;
        Sheet.menu(this, "Menu", choices);
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    private static boolean isLeftSoft(int code) {
        return code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SOFT_LEFT;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (overlay != null) {
            if (isLeftSoft(code) || code == KeyEvent.KEYCODE_BACK) {
                if (up) closeOverlay();
                return true;
            }
            if (code == KeyEvent.KEYCODE_SOFT_RIGHT) {
                if (up && overlay.onRight != null) overlay.onRight.run();
                return true;
            }
            if (overlay.key(this, event)) return true;
            return super.dispatchKeyEvent(event);
        }
        Screen screen = top();
        if (screen != null && screen.onKey(event)) return true;
        if (isLeftSoft(code)) {
            if (up && screen != null && screen.left() != null) showOptions();
            return true;
        }
        if (code == KeyEvent.KEYCODE_SOFT_RIGHT) {
            if (up && screen != null && screen.right() != null) screen.onRight();
            return true;
        }
        // Clear backs out like Back, except in a text field, where it is how
        // a character is deleted, as on every other screen of the phone.
        if ((code == KeyEvent.KEYCODE_CLEAR || code == KeyEvent.KEYCODE_DEL)
            && !(getCurrentFocus() instanceof EditText)) {
            if (up) onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) {
            closeOverlay();
            return;
        }
        Screen screen = top();
        if (screen != null && screen.onBack()) return;
        pop();
    }

    // ------------------------------------------------------------------
    // Permissions and other activities
    // ------------------------------------------------------------------

    /** Runs `then` once the permission is granted, asking for it first if need be. */
    void withPermission(String permission, Runnable then) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            then.run();
            return;
        }
        afterPermission = then;
        requestPermissions(new String[] { permission }, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        Runnable then = afterPermission;
        afterPermission = null;
        if (then != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            then.run();
        } else if (then != null) {
            toast("The phone didn't allow it.");
        }
    }

    private ActivityResult pendingResult;

    interface ActivityResult {
        void done(int code, Intent data);
    }

    void startForResult(Intent intent, ActivityResult then) {
        pendingResult = then;
        try {
            startActivityForResult(intent, 2);
        } catch (RuntimeException e) {
            pendingResult = null;
            toast("This phone has no app for that.");
        }
    }

    @Override
    protected void onActivityResult(int request, int code, Intent data) {
        ActivityResult then = pendingResult;
        pendingResult = null;
        if (then != null) then.done(code, data);
    }

    // ------------------------------------------------------------------
    // Background work
    // ------------------------------------------------------------------

    interface Task<T> {
        T run() throws Exception;
    }

    interface Done<T> {
        void done(T value, String problem);
    }

    /** Runs `task` on a worker and hands its result, or a sentence about its failure, back here. */
    <T> void background(final Task<T> task, final Done<T> done) {
        work.execute(() -> {
            T value = null;
            String problem = null;
            try {
                value = task.run();
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.io.IOException e) {
                problem = reason(e);
            } catch (RuntimeException e) {
                problem = "Canvas sent something this app couldn't read.";
            } catch (Exception e) {
                problem = reason(e);
            }
            final T result = value;
            final String failure = problem;
            main.post(() -> {
                if (!isFinishing()) done.done(result, failure);
            });
        });
    }

    static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.length() == 0 ? "The network couldn't be reached." : message;
    }

    // ------------------------------------------------------------------
    // Syncing
    // ------------------------------------------------------------------

    void refreshIfStale() {
        if (!backend.canvasReady() || syncing) return;
        long age = snapshot == null ? Long.MAX_VALUE : System.currentTimeMillis() - snapshot.fetchedAt;
        if (age >= STALE_MS) refresh(false);
    }

    void refresh(final boolean announce) {
        if (syncing) return;
        if (!backend.canvasReady()) {
            if (announce) toast("Canvas isn't set up. See Settings, then Account.");
            return;
        }
        syncing = true;
        syncError = null;
        redraw();
        background(() -> backend.refresh(), (result, problem) -> {
            syncing = false;
            if (result != null) {
                snapshot = result;
                todos.replace(result.work);
                todos.check(result.checked);
                Alarms.schedule(this, backend);
                if (announce) toast(result.complete() ? "Up to date" : "Updated. Some classes couldn't be read.");
                refreshNotices();
            } else {
                syncError = problem;
                if (!todos.isEmpty() || announce) toast(problem);
            }
            redraw();
        });
    }

    private void refreshNotices() {
        final Sync.Result known = snapshot;
        background(() -> backend.notices(known), (notices, problem) -> {
            Screen screen = top();
            if (screen instanceof NoticesScreen) screen.refresh();
        });
    }

    Course course(String id) {
        return snapshot == null ? null : snapshot.course(id);
    }

    Assignment assignment(String courseId, String assignmentId) {
        return todos.byId(courseId + ":" + assignmentId);
    }

    /** A record changed by something done here (a check mark), written back so it survives. */
    void saveSnapshot() {
        if (snapshot == null) return;
        final Sync.Result copy = new Sync.Result(new ArrayList<>(todos.all()), snapshot.courses,
            snapshot.unavailable, snapshot.fetchedAt, snapshot.overrides, todos.checkedIds());
        snapshot = copy;
        work.execute(() -> backend.save(copy));
    }

    // ------------------------------------------------------------------
    // Turning in
    // ------------------------------------------------------------------

    boolean onlineNow() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /**
     * Sends a draft. With no signal it is queued instead, and finishes on its
     * own: here when the network comes back while the app is open, and from
     * a job the phone runs on reconnecting when it is not.
     */
    void turnIn(final Assignment item, final Draft draft, final List<String> extensions) {
        turnIn(item, draft, extensions, false);
    }

    /**
     * `quiet` is for a queued turn-in retried on its own: if it fails it
     * simply stays queued for the next try, without a dialog each time the
     * signal comes and goes.
     */
    private void turnIn(final Assignment item, final Draft draft, final List<String> extensions,
                        final boolean quiet) {
        if (!onlineNow()) {
            queue(draft);
            return;
        }
        if (!TurnInJob.claim(item.id)) return;
        redraw();
        background(() -> {
            backend.turnIn(draft, item, extensions, (piece, done, total) -> main.post(() -> {
                progress.put(piece, new long[] { done, total });
                Screen screen = top();
                if (screen instanceof AssignmentScreen) ((AssignmentScreen) screen).progress(piece);
            }));
            return Boolean.TRUE;
        }, (ok, problem) -> {
            TurnInJob.release(item.id);
            progress.clear();
            if (ok != null) {
                turnedIn.put(item.id, Long.valueOf(System.currentTimeMillis()));
                refresh(false);
            } else if (!onlineNow() || quiet) {
                queue(backend.draft(draft.key));
                return;
            } else {
                tell("Couldn't turn in", problem + " Your work is saved on the phone. Press OK, then try again.");
            }
            redraw();
        });
    }

    boolean sending(Assignment item) {
        return TurnInJob.active(item.id);
    }

    /** When each turn-in made from this phone went through, for the stamp until the next sync. */
    final Map<String, Long> turnedIn = new HashMap<>();

    private void queue(Draft draft) {
        try {
            backend.saveDraft(draft.queued(true, System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            toast("Couldn't save on the phone.");
            return;
        }
        TurnInJob.schedule(this);
        redraw();
    }

    void cancelQueued(Draft draft) {
        try {
            backend.saveDraft(draft.queued(false, System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            toast("Couldn't save on the phone.");
        }
        redraw();
    }

    /** Sends whatever was turned in with no signal, now that there may be one. */
    void flushQueue() {
        if (!onlineNow() || !backend.canvasReady()) return;
        for (Draft draft : backend.queued()) {
            int colon = draft.key.indexOf(':');
            if (colon < 0) continue;
            Assignment item = assignment(draft.key.substring(0, colon), draft.key.substring(colon + 1));
            if (item == null || TurnInJob.active(item.id)) continue;
            List<String> extensions = new ArrayList<>();
            com.canvas.xp3.data.Detail detail = backend.cachedDetail(item);
            if (detail != null) extensions = detail.extensions;
            turnIn(item, draft.queued(false, draft.savedAt), extensions, true);
        }
    }

    private void listenForSignal() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return;
        online = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                main.post(() -> {
                    flushQueue();
                    Screen screen = top();
                    if (screen != null) screen.refresh();
                });
            }

            @Override
            public void onLost(Network network) {
                main.post(() -> {
                    Screen screen = top();
                    if (screen != null) screen.refresh();
                });
            }
        };
        try {
            manager.registerDefaultNetworkCallback(online);
        } catch (RuntimeException e) {
            online = null;
        }
    }

    static final String CAMERA = Manifest.permission.CAMERA;
    static final String MICROPHONE = Manifest.permission.RECORD_AUDIO;
    static final String STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE;
}
