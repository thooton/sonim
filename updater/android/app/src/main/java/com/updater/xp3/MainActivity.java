package com.updater.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.updater.xp3.core.Catalog;
import com.updater.xp3.core.Releases;
import com.updater.xp3.core.Status;
import com.updater.xp3.core.Version;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Every app in the latest release, against the copy on this phone.
 *
 * Opening the app reads the release's versions.txt and packages.txt and lists
 * each app as up to date, out of date (older versionName here) or not
 * installed. Menu (or OK) on an app offers Update or Install, and Reinstall
 * for one that is already current. Chosen apps go one at a time: download
 * into the app's own files, check the APK is the app it claims to be, hand it
 * to Installer, then the next.
 */
public final class MainActivity extends Activity {

    /** Menu item ids. The menu is rebuilt on every opening. */
    private static final int MENU_UPDATE = 1;
    private static final int MENU_INSTALL = 2;
    private static final int MENU_REINSTALL = 3;
    private static final int MENU_UPDATE_ALL = 4;
    private static final int MENU_CHECK = 5;

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
    private final ExecutorService checks = pool("updater-check");
    private final ExecutorService downloads = pool("updater-download");
    private final Releases releases = new Releases();

    private static ExecutorService pool(final String name) {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private FrameLayout content;
    private TextView summary;
    private ListView listView;
    private AppAdapter adapter;
    /** The message shown instead of a list, which then holds focus. */
    private TextView messageView;
    /** The highlighted app, by its release name, kept across redraws. */
    private String selectedName;

    /** The release's apps; null until the first check succeeds. */
    private List<Catalog.Entry> catalog;
    private boolean checking;
    private String checkError;

    /** One app being updated, installed or reinstalled. */
    private static final class Job {
        final Catalog.Entry entry;
        final int action;
        final String label;
        /** The phone's copy's lastUpdateTime when the install began; 0 if none. */
        long before;
        /** Only touched on the download thread. */
        int percent = -1;
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
        summary = findViewById(R.id.summary);
        // Every context menu is answered here; see showMenu.
        registerForContextMenu(content);

        // APKs a previous run left behind. Queued first on the download
        // thread, so it is done before any download starts.
        downloads.execute(new Runnable() {
            @Override
            public void run() {
                File[] stale = ApkProvider.folder(MainActivity.this).listFiles();
                if (stale != null) for (File file : stale) file.delete();
            }
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
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!isDestroyed()) runnable.run();
            }
        });
    }

    // ------------------------------------------------------------------
    // Checking the release
    // ------------------------------------------------------------------

    private void check() {
        if (checking) return;
        checking = true;
        render();
        checks.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Catalog.Entry> found = releases.catalog();
                    post(new Runnable() {
                        @Override
                        public void run() {
                            checking = false;
                            checkError = null;
                            catalog = found;
                            render();
                        }
                    });
                } catch (final IOException e) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            checking = false;
                            checkError = message(e);
                            // With a list already showing, keep it and say so.
                            if (catalog != null) toast("Couldn't check: " + checkError);
                            render();
                        }
                    });
                }
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
            rows.add(new AppAdapter.Row(entry, label, here, status, activity(entry)));
        }
        Collections.sort(rows, new Comparator<AppAdapter.Row>() {
            @Override
            public int compare(AppAdapter.Row a, AppAdapter.Row b) {
                int c = rank(a.status) - rank(b.status);
                return c != 0 ? c : a.label.compareToIgnoreCase(b.label);
            }
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
        if (job != null && job.entry.name.equals(entry.name)) return job.activity;
        for (Job waiting : queue) {
            if (waiting.entry.name.equals(entry.name)) return waiting.activity;
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
        summary.setText(summaryText(rows));
        if (rows.isEmpty()) {
            showMessage();
            return;
        }
        boolean focus = listView == null || listView.hasFocus() || getCurrentFocus() == null;
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

    private String summaryText(List<AppAdapter.Row> rows) {
        if (checking) return "Checking…";
        if (catalog == null) return checkError != null ? "Offline" : "";
        if (job != null) return "Working…";
        int updates = 0;
        for (AppAdapter.Row row : rows) if (row.status == Status.UPDATE) updates++;
        if (updates == 0) return "Up to date";
        return updates == 1 ? "1 update" : updates + " updates";
    }

    private void buildList() {
        adapter = new AppAdapter(this);
        final ListView list = new ListView(this);
        list.setAdapter(adapter);
        // Rows paint their own focus state edge to edge; a transparent
        // selector stops the platform drawing a second highlight beneath.
        list.setSelector(new ColorDrawable(0x00000000));
        list.setDivider(new ColorDrawable(getResources().getColor(R.color.divider)));
        list.setDividerHeight(dp(1));
        list.setBackgroundColor(getResources().getColor(R.color.page));
        list.setCacheColorHint(0x00000000);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                adapter.trackSelectedPosition(position);
                selectedName = adapter.getItem(position).entry.name;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                adapter.setSelectedPosition(position);
                selectedName = adapter.getItem(position).entry.name;
                showMenu();
            }
        });
        // Sonim firmware reports OK as DPAD_CENTER or ENTER. Its release, or
        // holding it, opens the menu; arrows stay with ListView.
        list.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                return isOk(keyCode) && okOpensMenu(event);
            }
        });
        messageView = null;
        listView = list;
        replaceContent(list);
    }

    /** Checking, the reason a check failed, or an empty release. */
    private void showMessage() {
        String text;
        if (checking) text = "Checking for updates…";
        else if (checkError != null) text = "Couldn't check for updates.\n\n" + checkError + "\n\nMenu: Check again";
        else text = "The release lists no apps.\n\nMenu: Check again";
        if (messageView == null) {
            TextView view = new TextView(this);
            view.setTextSize(13);
            view.setTextColor(getResources().getColor(R.color.text_muted));
            view.setGravity(Gravity.CENTER);
            view.setLineSpacing(dp(3), 1f);
            view.setPadding(dp(16), dp(16), dp(16), dp(16));
            // Focusable so keys land somewhere and the menu has an anchor.
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    return isOk(keyCode) && okOpensMenu(event);
                }
            });
            listView = null;
            adapter = null;
            messageView = view;
            replaceContent(view);
        }
        messageView.setText(text);
        messageView.requestFocus();
    }

    private void replaceContent(View view) {
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------
    // Keys and the menu
    // ------------------------------------------------------------------

    private boolean okDown;
    private boolean okHeld;

    /**
     * OK opens the menu on its release, or as soon as it is held. Every OK
     * event is claimed so ListView's own long press, which would open the
     * menu as a centred dialog, never runs, and the release that ends a hold
     * doesn't open it a second time. A release whose press went elsewhere
     * (the menu's own choice, a dialog's button) opens nothing.
     */
    private boolean okOpensMenu(KeyEvent event) {
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
            boolean open = okDown && !okHeld && !event.isCanceled();
            okDown = false;
            okHeld = false;
            if (open) showMenu();
        }
        return true;
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
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
            if (!checking) add(MENU_CHECK, "Check again");
            return messageView != null ? messageView : content;
        }
        int position = Math.max(0, Math.min(adapter.getSelectedPosition(), adapter.getCount() - 1));
        AppAdapter.Row row = adapter.getItem(position);
        menuRow = row;
        if (row.activity == null) {
            if (row.status == Status.INSTALL) add(MENU_INSTALL, "Install");
            else if (row.status == Status.UPDATE) add(MENU_UPDATE, "Update");
            else add(MENU_REINSTALL, "Reinstall");
        }
        int updates = 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            AppAdapter.Row other = adapter.getItem(i);
            if (other.status == Status.UPDATE && other.activity == null) updates++;
        }
        if (updates > 1) add(MENU_UPDATE_ALL, "Update all (" + updates + ")");
        if (!checking) add(MENU_CHECK, "Check again");
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
            for (AppAdapter.Row row : rows()) {
                if (row.status == Status.UPDATE && row.activity == null) enqueue(row, MENU_UPDATE);
            }
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
        downloads.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    apk.getParentFile().mkdirs();
                    releases.download(next.entry.apk(), apk, new Releases.Progress() {
                        @Override
                        public void update(long done, long total) {
                            if (total <= 0) return;
                            final int percent = (int) (done * 100 / total);
                            if (percent == next.percent) return;
                            next.percent = percent;
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    if (job != next) return;
                                    next.activity = "Downloading " + percent + "%";
                                    render();
                                }
                            });
                        }
                    });
                    post(new Runnable() {
                        @Override
                        public void run() {
                            downloaded(next, apk);
                        }
                    });
                } catch (final IOException e) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            done(next);
                            alert(failure(next), message(e));
                        }
                    });
                }
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
        render();

        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(ApkProvider.uri(done.entry), ApkProvider.APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            .putExtra(HELPER_PACKAGE, done.entry.packageName);
        if (hasHelper()) intent.setComponent(HELPER);
        if (done.entry.packageName.equals(getPackageName())) {
            toast("The Updater closes to update itself. Open it again after.");
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
            alert(failure(done), "Installer wouldn't take it. Reinstall the Updater "
                + "(with adb) now that Installer is on the phone.");
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
            return "The phone's copy is signed with a different key, so Android won't "
                + "replace it. Uninstall it first, then install it from here.";
        }
        if (versionCode(info) < versionCode(here)) {
            return "The release's " + version(info) + " is older than the phone's "
                + version(here) + ", and Android won't go back a version.";
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
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isDestroyed() || job != done) return;
                    if (replaced(done)) {
                        succeeded(done);
                    } else {
                        done(done);
                        toast(failure(done));
                    }
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

    private void alert(String title, String message) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
