package com.updater.xp3;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.updater.xp3.core.Catalog;
import com.updater.xp3.core.Notes;
import com.updater.xp3.core.Releases;
import com.updater.xp3.core.Status;
import com.updater.xp3.core.Version;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

/**
 * Every app in the latest release, against the copy on this phone.
 *
 * Drawn as the App Store's Updates page (DESIGN.md). Opening the app reads
 * the release's versions.txt and packages.txt and lists each app under
 * Available (an older versionName here), Installed or Not Installed, with
 * what's new in it from the repository's commits. OK presses the row's
 * button: UPDATE, GET or OPEN; on "more" (Right) it shows what's new. Update
 * All sits in the bar, Up from the top row. Menu, or holding OK, has the rest.
 * Chosen apps go one at a time: download into the app's own files, check the
 * APK is the app it claims to be, hand it to Installer, then the next.
 */
public final class MainActivity extends BaseActivity {

    /** Menu item ids. The menu is rebuilt on every opening. */
    private static final int MENU_UPDATE = 1;
    private static final int MENU_INSTALL = 2;
    private static final int MENU_REINSTALL = 3;
    private static final int MENU_UPDATE_ALL = 4;
    private static final int MENU_CHECK = 5;
    private static final int MENU_OPEN = 6;
    private static final int MENU_NOTES = 7;

    private static final int INSTALL = 1;

    /**
     * The Installer helper (../installer). The XP3's own installer screen
     * refuses anything not signed by Sonim, so when the helper is on the phone
     * the APK goes to it instead; it installs as the device owner.
     */
    private static final ComponentName HELPER =
        new ComponentName("com.installer.xp3", "com.installer.xp3.InstallActivity");
    private static final String HELPER_MESSAGE = "message";
    private static final String HELPER_PACKAGE = "package";

    /**
     * How long to give Android to finish replacing an app when the installer
     * ended without a word. That is what happens when Installer updates
     * itself: Android stops it to replace it before it can answer.
     */
    private static final long SETTLE_MS = 2500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService checks = Ui.worker("updater-check");
    private final ExecutorService downloads = Ui.worker("updater-download");
    private final Releases releases = new Releases();

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private FrameLayout content;
    private TextView updateAll;
    private ProgressRing spinner;
    private Icons icons;
    private ListView listView;
    private AppAdapter adapter;
    /** The message shown instead of a list, which then holds focus. */
    private LinearLayout messageView;
    private ProgressRing messageSpinner;
    private TextView messageTitle;
    private TextView messageDetail;
    /** The highlighted app, by its release name, kept across redraws. */
    private String selectedName;

    /** The release's apps; null until the first check succeeds. */
    private List<Catalog.Entry> catalog;
    private boolean checking;
    private String checkError;
    /** What's new in each app, by release name; empty until fetched. */
    private Map<String, List<Notes.Note>> notes = new HashMap<>();

    /** One app being updated, installed or reinstalled. */
    private static final class Job {
        final Catalog.Entry entry;
        final int action;
        final String label;
        /** The phone's copy's lastUpdateTime when the install began; 0 if none. */
        long before;
        /** Only touched on the download thread. */
        int percent = -1;
        /** What the ring shows, on the main thread: 0 to 100, or -1 to spin. */
        int shown = -1;
        String activity = "Waiting";

        Job(Catalog.Entry entry, int action, String label) {
            this.entry = entry;
            this.action = action;
            this.label = label;
        }
    }

    /** Apps waiting their turn, in the order they will go. */
    private final List<Job> queue = new ArrayList<>();
    private Job job;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        content = findViewById(R.id.content);
        updateAll = findViewById(R.id.update_all);
        spinner = findViewById(R.id.spinner);
        spinner.setShowStop(false);
        icons = new Icons(this, 40);
        updateAll.setOnClickListener(v -> updateAll());
        // Every context menu is answered here; see showMenu.
        registerForContextMenu(content);

        // APKs a previous run left behind. Queued first on the download
        // thread, so it is done before any download starts.
        downloads.execute(() -> {
            File[] stale = ApkProvider.folder(MainActivity.this).listFiles();
            if (stale != null) for (File file : stale) file.delete();
        });
        render();
        check();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // An app may have been installed or removed while this was away.
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        checks.shutdown();
        downloads.shutdown();
    }

    /** Runs on the main thread, unless the activity has gone by then. */
    private void post(final Runnable runnable) {
        main.post(() -> {
            if (!isDestroyed()) runnable.run();
        });
    }

    // ------------------------------------------------------------------
    // Checking the release
    // ------------------------------------------------------------------

    private void check() {
        if (checking) return;
        checking = true;
        render();
        checks.execute(() -> {
            try {
                final List<Catalog.Entry> found = releases.catalog();
                post(() -> {
                    checking = false;
                    checkError = null;
                    catalog = found;
                    render();
                });
                // What's new is a nicety: without it the rows show versions.
                List<String> names = new ArrayList<>();
                for (Catalog.Entry entry : found) names.add(entry.name);
                try {
                    final Map<String, List<Notes.Note>> news = releases.notes(names);
                    post(() -> {
                        notes = news;
                        render();
                    });
                } catch (IOException e) {
                    // Kept as they were; GitHub's API allows 60 an hour.
                }
            } catch (final IOException e) {
                post(() -> {
                    checking = false;
                    checkError = message(e);
                    // With a list already showing, keep it and say so.
                    if (catalog != null) toast("Couldn't check: " + checkError);
                    render();
                });
            }
        });
    }

    /** The release's apps as they stand here: updates first, missing apps last. */
    private List<AppAdapter.Row> rows() {
        List<AppAdapter.Row> rows = new ArrayList<>();
        if (catalog == null) return rows;
        PackageManager pm = getPackageManager();
        for (Catalog.Entry entry : catalog) {
            PackageInfo info = installed(entry.packageName, 0);
            String label = info != null
                ? pm.getApplicationLabel(info.applicationInfo).toString()
                : title(entry.name);
            String here = info != null ? info.versionName : null;
            Status status = Status.of(info != null, here, entry.version);
            List<Notes.Note> news = notes.get(entry.name);
            Job working = job(entry);
            rows.add(new AppAdapter.Row(entry, label, here, status,
                working != null ? working.activity : null,
                working != null ? working.shown : -1,
                icons.of(entry.name, label, info),
                news != null ? news : Collections.<Notes.Note>emptyList()));
        }
        Collections.sort(rows, (a, b) -> {
            int c = rank(a.status) - rank(b.status);
            return c != 0 ? c : a.label.compareToIgnoreCase(b.label);
        });
        return rows;
    }

    private static int rank(Status status) {
        if (status == Status.UPDATE) return 0;
        if (status == Status.INSTALL) return 2;
        return 1;
    }

    /** What is being done to an app, or null. */
    private String activity(Catalog.Entry entry) {
        Job working = job(entry);
        return working != null ? working.activity : null;
    }

    /** The app's job, running or waiting, or null. */
    private Job job(Catalog.Entry entry) {
        if (job != null && job.entry.name.equals(entry.name)) return job;
        for (Job waiting : queue) {
            if (waiting.entry.name.equals(entry.name)) return waiting;
        }
        return null;
    }

    private static String title(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ------------------------------------------------------------------
    // The screen
    // ------------------------------------------------------------------

    private void render() {
        List<AppAdapter.Row> rows = rows();
        int updates = 0;
        for (AppAdapter.Row row : rows) {
            if (row.status == Status.UPDATE && row.activity == null) updates++;
        }
        // Update All while there is anything left to update; the spinner
        // while a check runs over a list already showing.
        boolean hadBar = updateAll.hasFocus();
        updateAll.setVisibility(updates > 0 ? View.VISIBLE : View.GONE);
        spinner.setVisibility(checking && !rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (rows.isEmpty()) {
            showMessage();
            return;
        }
        boolean focus = listView == null || listView.hasFocus() || getCurrentFocus() == null
            || (hadBar && updates == 0);
        if (listView == null) buildList();
        int at = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).entry.name.equals(selectedName)) at = i;
        }
        selectedName = rows.get(at).entry.name;
        boolean moved = adapter.getSelectedPosition() != at;
        adapter.replace(rows);
        if (moved) {
            adapter.setSelectedPosition(at);
            listView.setSelection(at);
        }
        if (focus) listView.requestFocus();
    }

    private void buildList() {
        adapter = new AppAdapter(this);
        final ListView list = new ListView(this);
        list.setAdapter(adapter);
        // Rows paint their own selection and their own hairlines (see
        // row_app), so the list draws neither a selector nor dividers.
        Family.list(list);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setDefaultFocusHighlightEnabled(false);
        Ui.onSelect(list, position -> {
            adapter.trackSelectedPosition(position);
            selectedName = adapter.getItem(position).entry.name;
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            adapter.setSelectedPosition(position);
            selectedName = adapter.getItem(position).entry.name;
            press();
        });
        // Sonim firmware reports OK as DPAD_CENTER or ENTER. Its release
        // presses the row's button, holding it opens the menu; Right and
        // Left move between the button and "more"; Up and Down stay with
        // ListView, which hands Up from the top row on to Update All.
        list.setOnKeyListener((view, keyCode, event) -> {
            if (Ui.isOk(keyCode)) return okKey(event, this::press);
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    adapter.setMoreFocused(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
                }
                return true;
            }
            return false;
        });
        messageView = null;
        listView = list;
        Ui.fill(content, list);
    }

    /** Checking (a spinner, as the App Store shows), the reason a check failed, or an empty release. */
    private void showMessage() {
        if (messageView == null) {
            LinearLayout view = new LinearLayout(this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(20), dp(20), dp(20), dp(20));
            view.setBackgroundColor(color(R.color.page));
            messageSpinner = new ProgressRing(this);
            messageSpinner.setShowStop(false);
            view.addView(messageSpinner, new LinearLayout.LayoutParams(dp(26), dp(26)));
            messageTitle = text("", 16, color(R.color.text));
            messageTitle.setTypeface(Family.MEDIUM, android.graphics.Typeface.BOLD);
            messageTitle.setGravity(Gravity.CENTER);
            view.addView(messageTitle);
            messageDetail = text("", 13, color(R.color.text_muted));
            messageDetail.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            below.topMargin = dp(6);
            view.addView(messageDetail, below);
            // Focusable so keys land somewhere and the menu has an anchor.
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setDefaultFocusHighlightEnabled(false);
            view.setOnKeyListener((v, keyCode, event) -> Ui.isOk(keyCode) && okKey(event, this::check));
            listView = null;
            adapter = null;
            messageView = view;
            Ui.fill(content, view);
        }
        String title = checking ? null : checkError != null ? "Cannot Check for Updates" : "No Apps";
        String detail = checking ? null : checkError;
        messageSpinner.setVisibility(checking ? View.VISIBLE : View.GONE);
        messageTitle.setVisibility(title != null ? View.VISIBLE : View.GONE);
        messageTitle.setText(title);
        messageDetail.setVisibility(detail != null ? View.VISIBLE : View.GONE);
        messageDetail.setText(detail);
        messageView.requestFocus();
    }

    // ------------------------------------------------------------------
    // Keys and the menu
    // ------------------------------------------------------------------

    private boolean okDown;
    private boolean okHeld;

    /**
     * OK does `press` on its release, and opens the menu as soon as it is
     * held. Every OK event is claimed so ListView's own long press, which
     * would open the menu as a centred dialog, never runs, and the release
     * that ends a hold doesn't press as well. A release whose press went
     * elsewhere (the menu's own choice, a dialog's button) does nothing.
     */
    private boolean okKey(KeyEvent event, Runnable press) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                okDown = true;
                okHeld = false;
            } else if (okDown && !okHeld) {
                okHeld = true;
                showMenu();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean pressed = okDown && !okHeld && !event.isCanceled();
            okDown = false;
            okHeld = false;
            if (pressed) press.run();
        }
        return true;
    }

    /**
     * The highlighted row's button, as a tap on it in the App Store: UPDATE
     * and GET queue the app, OPEN opens it, and the ring of an app still
     * waiting its turn takes it out of the line. On "more", what's new.
     */
    private void press() {
        AppAdapter.Row row = selectedRow();
        if (row == null) return;
        if (adapter.isMoreFocused()) {
            whatsNew(row);
        } else if (row.activity != null) {
            Job waiting = job(row.entry);
            if (waiting != null && waiting != job) {
                queue.remove(waiting);
                render();
            }
        } else if (row.status == Status.UPDATE) {
            enqueue(row, MENU_UPDATE);
        } else if (row.status == Status.INSTALL) {
            enqueue(row, MENU_INSTALL);
        } else {
            open(row);
        }
    }

    private AppAdapter.Row selectedRow() {
        if (listView == null || adapter == null || adapter.getCount() == 0) return null;
        int position = Math.max(0, Math.min(adapter.getSelectedPosition(), adapter.getCount() - 1));
        return adapter.getItem(position);
    }

    private void updateAll() {
        for (AppAdapter.Row row : rows()) {
            if (row.status == Status.UPDATE && row.activity == null) enqueue(row, MENU_UPDATE);
        }
        if (listView != null) listView.requestFocus();
    }

    private void open(AppAdapter.Row row) {
        if (row.entry.packageName.equals(getPackageName())) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(row.entry.packageName);
        if (launch == null) {
            toast("Can't open " + row.label);
            return;
        }
        try {
            startActivity(launch);
        } catch (ActivityNotFoundException e) {
            toast("Can't open " + row.label);
        }
    }

    /**
     * The App Store's What's New: the app with its icon, the release's
     * version and date, then each change with the day it landed.
     */
    private void whatsNew(AppAdapter.Row row) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(14), dp(16), dp(4));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(row.icon);
        head.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = vertical();
        names.setPadding(dp(8), 0, 0, 0);
        TextView name = text(row.label, 15, color(R.color.text));
        name.setTypeface(Family.MEDIUM);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        String here = row.installed != null ? "This phone has " + row.installed
            : row.status == Status.INSTALL ? "Not installed" : null;
        if (here != null) names.addView(text(here, 12, color(R.color.text_muted)));
        head.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        page.addView(head);

        TextView title = text("What's New", 17, color(R.color.text));
        title.setTypeface(Family.MEDIUM, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(4));
        page.addView(title);

        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        LinearLayout version = new LinearLayout(this);
        TextView number = text("Version " + row.entry.version, 12, color(R.color.text_muted));
        version.addView(number, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!row.notes.isEmpty()) {
            version.addView(text(Notes.when(row.notes.get(0).time, now, zone), 12, color(R.color.text_muted)));
        }
        page.addView(version);

        for (int i = 0; i < row.notes.size() && i < 10; i++) {
            Notes.Note note = row.notes.get(i);
            if (i > 0) {
                TextView when = text(Notes.when(note.time, now, zone), 11, color(R.color.text_muted));
                when.setPadding(0, dp(8), 0, 0);
                page.addView(when);
            }
            TextView body = text(note.text, 13, color(R.color.text));
            body.setPadding(0, dp(i == 0 ? 6 : 2), 0, 0);
            body.setLineSpacing(dp(2), 1f);
            page.addView(body);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.addView(page);
        new AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show();
    }

    /*
     * Menu opens on its release, as the platform's own menus do. Opened on
     * the press, the release would arrive at the popup and close it.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.isTracking() && !event.isCanceled()) showMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** Leaving mid-update keeps it going: the task only goes to the back. */
    @Override
    public void onBackPressed() {
        if (job != null || !queue.isEmpty()) {
            toast("Updating in the background");
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    /*
     * As in List: the platform's own context menu, opened at a point inside
     * the view it is about (View.showContextMenu(x, y)), which is what makes
     * Android draw it as a popup beside the row rather than a centred dialog.
     */

    private final List<Integer> menuIds = new ArrayList<>();
    private final List<String> menuTitles = new ArrayList<>();
    /** The app the open menu is about, captured when it opened. */
    private AppAdapter.Row menuRow;

    private void showMenu() {
        menuIds.clear();
        menuTitles.clear();
        menuRow = null;
        View anchor = fillMenu();
        if (menuIds.isEmpty() || anchor == null) return;

        // Just under the row, a little in from the left. A row is opened
        // from its list, since the platform scrolls the anchor's parent to
        // fit the menu and a list scrolled under an open menu recycles rows.
        float x = Math.min(dp(32), anchor.getWidth() / 2f);
        float y = anchor.getHeight();
        if (anchor == messageView) {
            x = anchor.getWidth() / 4f;
            y = anchor.getHeight() / 2f;
        } else if (anchor.getParent() instanceof ListView) {
            ListView list = (ListView) anchor.getParent();
            y = Math.max(0, Math.min(anchor.getBottom(), list.getHeight()));
            anchor = list;
        }
        anchor.showContextMenu(x, y);
    }

    /** Fills the menu for the highlighted app and returns the view it belongs beside. */
    private View fillMenu() {
        if (listView == null || adapter == null || adapter.getCount() == 0) {
            if (!checking) add(MENU_CHECK, "Check Again");
            return messageView != null ? messageView : content;
        }
        int position = Math.max(0, Math.min(adapter.getSelectedPosition(), adapter.getCount() - 1));
        AppAdapter.Row row = adapter.getItem(position);
        menuRow = row;
        boolean self = row.entry.packageName.equals(getPackageName());
        if (row.activity == null) {
            if (row.status == Status.INSTALL) add(MENU_INSTALL, "Get");
            else if (row.status == Status.UPDATE) add(MENU_UPDATE, "Update");
        }
        if (row.status != Status.INSTALL && !self) add(MENU_OPEN, "Open");
        if (row.activity == null && (row.status == Status.CURRENT || row.status == Status.NEWER)) {
            add(MENU_REINSTALL, "Reinstall");
        }
        add(MENU_NOTES, "What's New");
        int updates = 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            AppAdapter.Row other = adapter.getItem(i);
            if (other.status == Status.UPDATE && other.activity == null) updates++;
        }
        if (updates > 1) add(MENU_UPDATE_ALL, "Update All (" + updates + ")");
        if (!checking) add(MENU_CHECK, "Check Again");
        View view = listView.getChildAt(position - listView.getFirstVisiblePosition());
        return view != null ? view : listView;
    }

    private void add(int id, String title) {
        menuIds.add(Integer.valueOf(id));
        menuTitles.add(title);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo info) {
        for (int i = 0; i < menuIds.size(); i++) {
            menu.add(Menu.NONE, menuIds.get(i).intValue(), i, menuTitles.get(i));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == MENU_CHECK) {
            check();
        } else if (id == MENU_UPDATE_ALL) {
            updateAll();
        } else if (id == MENU_OPEN) {
            if (menuRow != null) open(menuRow);
        } else if (id == MENU_NOTES) {
            if (menuRow != null) whatsNew(menuRow);
        } else if (menuRow != null) {
            enqueue(menuRow, id);
        }
        return true;
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // A long press the platform handles itself gets no menu.
        menuIds.clear();
        menuTitles.clear();
    }

    // ------------------------------------------------------------------
    // Updating
    // ------------------------------------------------------------------

    /**
     * Puts an app in the line. Installer goes after the others and the
     * Updater itself last: Android stops an app to replace it, and the
     * Updater can't carry on once it has been stopped.
     */
    private void enqueue(AppAdapter.Row row, int action) {
        if (activity(row.entry) != null) return;
        Job added = new Job(row.entry, action, row.label);
        int order = order(row.entry);
        int at = queue.size();
        for (int i = 0; i < queue.size(); i++) {
            if (order(queue.get(i).entry) > order) {
                at = i;
                break;
            }
        }
        queue.add(at, added);
        pump();
        render();
    }

    private int order(Catalog.Entry entry) {
        if (entry.packageName.equals(getPackageName())) return 2;
        if (entry.packageName.equals(HELPER.getPackageName())) return 1;
        return 0;
    }

    /** Starts the next app in the line, if nothing is running. */
    private void pump() {
        if (job != null || queue.isEmpty()) return;
        final Job next = queue.remove(0);
        job = next;
        next.activity = "Downloading…";
        final File apk = ApkProvider.file(this, next.entry);
        downloads.execute(() -> {
            try {
                apk.getParentFile().mkdirs();
                releases.download(next.entry.apk(), apk, (done, total) -> {
                    if (total <= 0) return;
                    final int percent = (int) (done * 100 / total);
                    if (percent == next.percent) return;
                    next.percent = percent;
                    post(() -> {
                        if (job != next) return;
                        next.activity = "Downloading " + percent + "%";
                        next.shown = percent;
                        render();
                    });
                });
                post(() -> {
                    downloaded(next, apk);
                });
            } catch (final IOException e) {
                post(() -> {
                    done(next);
                    alert(failure(next), message(e));
                });
            }
        });
    }

    private void downloaded(Job done, File apk) {
        if (job != done) return;
        String problem = inspect(done, apk);
        if (problem != null) {
            done(done);
            alert(failure(done), problem);
            return;
        }
        PackageInfo here = installed(done.entry.packageName, 0);
        done.before = here != null ? here.lastUpdateTime : 0;
        done.activity = "Installing…";
        done.shown = -1;
        render();

        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(ApkProvider.uri(done.entry), ApkProvider.APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            .putExtra(HELPER_PACKAGE, done.entry.packageName);
        if (hasHelper()) intent.setComponent(HELPER);
        if (done.entry.packageName.equals(getPackageName())) {
            toast("The Updater closes to update itself");
        }
        try {
            startActivityForResult(intent, INSTALL);
        } catch (ActivityNotFoundException e) {
            done(done);
            toast("No installer on this phone");
        } catch (SecurityException e) {
            // Signature permissions are granted when an app is installed, so
            // an Updater installed before Installer doesn't have it.
            done(done);
            alert(failure(done), "Installer wouldn't take it. Reinstall the Updater with adb.");
        }
    }

    /**
     * What the installer would refuse, said first and in words: an APK for a
     * different app than the release says, one signed with a different key
     * from the phone's copy, or one older than it. Null when it can go.
     */
    private String inspect(Job job, File apk) {
        PackageManager pm = getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (info == null) return "The download isn't an app this phone can read.";
        if (!job.entry.packageName.equals(info.packageName)) {
            return "The download is " + info.packageName + ", not " + job.entry.packageName + ".";
        }
        PackageInfo here = installed(info.packageName, PackageManager.GET_SIGNATURES);
        if (here == null) return null;
        if (!Arrays.equals(here.signatures, info.signatures)) {
            return "The phone's copy is signed with a different key.";
        }
        if (versionCode(info) < versionCode(here)) {
            return "The release's " + version(info) + " is older than the phone's " + version(here) + ".";
        }
        return null;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != INSTALL || job == null) return;
        final Job done = job;
        String why = data == null ? null : data.getStringExtra(HELPER_MESSAGE);
        if (result == RESULT_OK) {
            succeeded(done);
        } else if (why != null) {
            done(done);
            alert(failure(done), why);
        } else {
            // No word: the system installer's Cancel, or Installer replacing
            // itself (Android stops it before it can answer). The phone's
            // copy says which, once Android has finished with it.
            main.postDelayed(() -> {
                if (isDestroyed() || job != done) return;
                if (replaced(done)) {
                    succeeded(done);
                } else {
                    done(done);
                    toast(failure(done));
                }
            }, SETTLE_MS);
        }
    }

    /** Whether the phone now has the release's version, put there since the install began. */
    private boolean replaced(Job job) {
        PackageInfo now = installed(job.entry.packageName, 0);
        return now != null && now.lastUpdateTime != job.before
            && Version.compare(now.versionName, job.entry.version) == 0;
    }

    private void succeeded(Job job) {
        PackageInfo now = installed(job.entry.packageName, 0);
        String label = now != null
            ? getPackageManager().getApplicationLabel(now.applicationInfo).toString()
            : job.label;
        String version = now != null ? version(now) : job.entry.version;
        if (job.action == MENU_INSTALL) toast("Installed " + label + " " + version);
        else if (job.action == MENU_REINSTALL) toast("Reinstalled " + label + " " + version);
        else toast("Updated " + label + " to " + version);
        done(job);
    }

    /** Ends a job however it went, and starts the next. */
    private void done(Job finished) {
        if (job != finished) return;
        job = null;
        ApkProvider.file(this, finished.entry).delete();
        pump();
        render();
    }

    private static String failure(Job job) {
        if (job.action == MENU_INSTALL) return job.label + " wasn't installed";
        if (job.action == MENU_REINSTALL) return job.label + " wasn't reinstalled";
        return job.label + " wasn't updated";
    }

    private boolean hasHelper() {
        try {
            getPackageManager().getActivityInfo(HELPER, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The copy of `packageName` on the phone, or null. */
    private PackageInfo installed(String packageName, int flags) {
        try {
            return getPackageManager().getPackageInfo(packageName, flags);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // getLongVersionCode arrives in API 28; the XP3 is 27.
    @SuppressWarnings("deprecation")
    private static int versionCode(PackageInfo info) {
        return info.versionCode;
    }

    private static String version(PackageInfo info) {
        return info.versionName != null ? info.versionName : "version " + versionCode(info);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String message(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
