package com.mail.xp3;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mail.xp3.net.Address;
import com.mail.xp3.net.Attachment;
import com.mail.xp3.net.Change;
import com.mail.xp3.net.Conversation;
import com.mail.xp3.net.Eml;
import com.mail.xp3.net.Folder;
import com.mail.xp3.net.Gmail;
import com.mail.xp3.net.Me;
import com.mail.xp3.net.Message;
import com.mail.xp3.net.Mime;
import com.mail.xp3.net.Outgoing;
import com.mail.xp3.net.People;
import com.xp3.common.Login;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Card;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Thunderbird for Android, as a flip phone drives it: one screen showing a
 * folder's conversations, one conversation, or a message being written, with
 * the folder pane sliding over it from the left.
 *
 * The list: Up and Down pick (Down past the last loads more), OK or Right
 * opens, Left opens the folder pane. A conversation: Up and Down scroll, Left
 * and Right go to the conversation before or after it, OK is the menu, Back
 * returns to the list. Writing: the fields as usual, OK starts a new line in
 * the message, Menu sends, Back offers to save a draft. The keypad's digits,
 * * and # are shortcuts, shown beside each item in the menu (1 reply, 4
 * archive, 9 delete and so on).
 */
public final class MainActivity extends BaseActivity {

    private static final String PREFS = "mail";
    private static final int LIST = 0;
    private static final int READ = 1;
    private static final int WRITE = 2;
    private static final int ASK_FOLDER = 1;
    private static final int READ_STORAGE = 2;
    /** A long message is cut here on screen; its whole text still goes into a reply. */
    private static final int MAX_SHOWN = 20000;
    /** The list is fetched again on coming back to the app after this long. */
    private static final long STALE_MS = 120000;
    /** The folder pane's counts are fetched again when it opens after this long. */
    private static final long COUNTS_MS = 60000;
    /** People offered under To or Cc at once: as many as fit above the message. */
    private static final int SUGGESTIONS = 4;
    /** How long typing pauses before the organisation is searched. */
    private static final long FIND_AFTER_MS = 300;
    /** The menu's key for opening the folder pane: Left, not a keypad key. */
    private static final char LEFT = '◀';

    /** Work for the background thread: returns what to do with its result on the main thread. */
    private interface Work {
        Runnable call() throws Exception;
    }

    private ImageView barIcon;
    private TextView titleView;
    private TextView subtitleView;
    private TextView countView;
    private ImageView barAction;
    private ProgressBar progress;
    private TextView statusView;
    private ListView listView;
    private View emptyView;
    private ImageView emptyIcon;
    private TextView emptyText;
    private ScrollView reader;
    private LinearLayout readerBody;
    private View readerLoading;
    private ProgressBar readerSpinner;
    private TextView readerFailed;
    private View compose;
    private View fromRow;
    private TextView fromView;
    private View ccRow;
    private EditText toField;
    private EditText ccField;
    private EditText subjectField;
    private TextView filesView;
    private EditText bodyField;
    private LinearLayout suggestView;
    private View scrim;
    private View drawer;
    private ListView foldersView;
    private TextView accountName;
    private TextView accountEmail;
    private final ConversationAdapter adapter = new ConversationAdapter();
    private final FolderAdapter folderAdapter = new FolderAdapter();
    private final ExecutorService worker = Ui.worker("mail");
    /** Archive, delete and the rest go one at a time, in order, beside whatever is loading. */
    private final ExecutorService changes = Ui.worker("mail-changes");
    /** The folder pane's counts, fetched beside the rest. */
    private final ExecutorService counter = Ui.worker("mail-counts");
    /** Searches of the user's organisation for whom to write to, beside the rest. */
    private final ExecutorService finder = Ui.worker("mail-people");

    private Login login = Login.blank();
    private Gmail gmail;
    private Me me;
    private People people = new People();
    private List<Folder> labels = new ArrayList<Folder>();
    private boolean labelsLoaded;

    private int screen = LIST;

    // The list.
    private Folder folder = Folder.INBOX;
    private final List<Conversation> items = new ArrayList<Conversation>();
    private String next;
    private int picked;
    /** Whether this folder has been listed since it was opened, so an empty list means an empty folder. */
    private boolean listed;
    private long loadedAt;

    // The folder pane: what it lists, which one the d-pad is on, and Gmail's counts by label id.
    private boolean drawerOpen;
    private final List<Entry> entries = new ArrayList<Entry>();
    private int drawerPick;
    private final Map<String, Integer> counts = new HashMap<String, Integer>();
    private long countsAt;
    private boolean counting;

    // The conversation open, whole, and the view of each of its messages.
    private Conversation reading;
    private final Map<String, Conversation> whole = new HashMap<String, Conversation>();
    private final List<View> messageViews = new ArrayList<View>();
    private boolean showQuoted;
    private boolean expandAll;

    // The message being written, as it began (to tell whether anything was typed), and where it was begun.
    private Outgoing writing;
    private Outgoing writingStart;
    private int writeFrom = LIST;
    private boolean sending;

    // People offered as an address is typed: under which field, for what, who, and which the d-pad is on (-1: none).
    private EditText suggestFor;
    private String suggestTyped = "";
    private final List<Address> suggestions = new ArrayList<Address>();
    private int suggestPick = -1;
    /** What the organisation's search found, by what was typed, while the app runs. */
    private final Map<String, List<Address>> found = new HashMap<String, List<Address>>();
    private final Runnable findPeople = this::findPeople;

    private Attachment pendingSave;
    private Future<?> job;
    private String doing = "";
    private int generation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        barIcon = (ImageView) findViewById(R.id.bar_icon);
        titleView = (TextView) findViewById(R.id.title);
        subtitleView = (TextView) findViewById(R.id.subtitle);
        countView = (TextView) findViewById(R.id.count);
        barAction = (ImageView) findViewById(R.id.bar_action);
        progress = (ProgressBar) findViewById(R.id.progress);
        statusView = (TextView) findViewById(R.id.status);
        listView = (ListView) findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        emptyIcon = (ImageView) findViewById(R.id.empty_icon);
        emptyText = (TextView) findViewById(R.id.empty_text);
        reader = (ScrollView) findViewById(R.id.reader);
        readerBody = (LinearLayout) findViewById(R.id.reader_body);
        readerLoading = findViewById(R.id.reader_loading);
        readerSpinner = (ProgressBar) findViewById(R.id.reader_spinner);
        readerFailed = (TextView) findViewById(R.id.reader_failed);
        compose = findViewById(R.id.compose);
        fromRow = findViewById(R.id.from_row);
        fromView = (TextView) findViewById(R.id.from);
        ccRow = findViewById(R.id.cc_row);
        toField = (EditText) findViewById(R.id.to);
        ccField = (EditText) findViewById(R.id.cc);
        subjectField = (EditText) findViewById(R.id.subject);
        filesView = (TextView) findViewById(R.id.files);
        bodyField = (EditText) findViewById(R.id.body);
        suggestView = (LinearLayout) findViewById(R.id.suggestions);
        scrim = findViewById(R.id.scrim);
        drawer = findViewById(R.id.drawer);
        foldersView = (ListView) findViewById(R.id.folders);
        accountName = (TextView) findViewById(R.id.account_name);
        accountEmail = (TextView) findViewById(R.id.account_email);

        listView.setAdapter(adapter);
        Family.list(listView);
        // Thunderbird's rows are ruled the whole width, white and grey alike.
        listView.setDivider(new ColorDrawable(color(R.color.divider)));
        listView.setDividerHeight(1);
        foldersView.setAdapter(folderAdapter);
        filesView.setCompoundDrawablesRelative(small(R.drawable.ic_attachment), null, null, null);
        Pills.attach(toField, color(R.color.pill_wash), color(R.color.focus_rule), color(R.color.draft), dp(4));
        Pills.attach(ccField, color(R.color.pill_wash), color(R.color.focus_rule), color(R.color.draft), dp(4));
        offerPeople(toField);
        offerPeople(ccField);

        SharedPreferences prefs = prefs();
        login = loadLogin(prefs);
        people = People.parse(prefs.getString("people", ""));

        Outgoing kept = Outgoing.parse(prefs.getString("compose", ""));
        if (kept != null) {
            write(kept, LIST, "Your unfinished message was kept.");
            writingStart = Outgoing.compose("");
        } else {
            refresh();
        }
        if (kept == null) load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (screen == LIST && !busy() && loadedAt > 0 && System.currentTimeMillis() - loadedAt > STALE_MS) load();
    }

    @Override
    protected void onPause() {
        // What is being written survives the app being stopped; a message left as it began is dropped, as Back drops it.
        if (screen == WRITE && writing != null && !sending) {
            collect();
            if (writing.untouched(writingStart)) prefs().edit().remove("compose").apply();
            else prefs().edit().putString("compose", writing.toJson()).apply();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        counter.shutdownNow();
        finder.shutdownNow();
        changes.shutdown();
        if (gmail != null) gmail.close();
        super.onDestroy();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from the menu) is kept.
     */
    private Login loadLogin(SharedPreferences prefs) {
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

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** Whether there is a login to reach Gmail with; if not, says so and asks for one. */
    private boolean signedIn() {
        if (login.ready()) return true;
        say("Mail isn't set up");
        askLogin();
        return false;
    }

    /** One client per login, so its access token is reused. */
    private Gmail gmail() {
        if (gmail == null) gmail = new Gmail(login);
        return gmail;
    }

    // ---- Background work ----

    /**
     * Runs `work` off the main thread, in place of anything already running.
     * Meanwhile a bar runs along the foot of the app bar and `doing` stands
     * under the title, as Thunderbird's status line does.
     */
    private void start(String doing, final Work work) {
        stop();
        final int mine = ++generation;
        this.doing = doing;
        job = worker.submit(() -> {
            Runnable done;
            try {
                done = work.call();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                final String message = message(e);
                done = () -> say(message);
            }
            final Runnable then = done;
            runOnUiThread(() -> {
                if (mine != generation) return;
                job = null;
                then.run();
                refresh();
            });
        });
        refresh();
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private boolean busy() {
        return job != null;
    }

    /** Abandons what is running; whatever it was doing is never shown. */
    private void stop() {
        if (job == null) return;
        job.cancel(true);
        job = null;
        generation++;
        bar();
    }

    // ---- The list ----

    /** The folder's first page, keeping the pick where it was. */
    private void load() {
        if (!login.ready()) {
            askLogin();
            return;
        }
        final Gmail client = gmail();
        final Folder from = folder;
        final boolean wantLabels = !labelsLoaded;
        start(items.isEmpty() ? "Loading " + from.name + "…" : "Checking for mail…", () -> {
            final Me user = client.me();
            final List<Folder> found = wantLabels ? client.labels() : null;
            final Gmail.Page page = client.list(from, null);
            final int count = from == Folder.INBOX ? client.unread() : -1;
            return () -> {
                me = user;
                if (found != null) {
                    labels = found;
                    labelsLoaded = true;
                }
                items.clear();
                items.addAll(page.conversations);
                next = page.next;
                if (count >= 0) counts.put("INBOX", count);
                picked = Math.max(0, Math.min(picked, items.size() - 1));
                listed = true;
                loadedAt = System.currentTimeMillis();
                learn(page.conversations);
                say("");
                if (System.currentTimeMillis() - countsAt > COUNTS_MS) loadCounts();
            };
        });
    }

    /** The next page, after the ones already shown. */
    private void more() {
        if (busy() || next == null) return;
        final Gmail client = gmail();
        final Folder from = folder;
        final String after = next;
        start("Loading more…", () -> {
            final Gmail.Page page = client.list(from, after);
            return () -> {
                boolean wasLast = picked == items.size() - 1;
                items.addAll(page.conversations);
                next = page.next;
                if (wasLast && picked + 1 < items.size()) picked++;
                learn(page.conversations);
                say("");
            };
        });
    }

    private void setFolder(Folder to) {
        stop();
        folder = to;
        items.clear();
        next = null;
        picked = 0;
        listed = false;
        screen = LIST;
        say("");
        refresh();
        load();
    }

    private void movePick(int by) {
        if (items.isEmpty()) return;
        int to = picked + by;
        if (to < 0) return;
        if (to >= items.size()) {
            more();
            return;
        }
        picked = to;
        refresh();
    }

    /** Remembers the people in these conversations, to offer when writing. */
    private void learn(List<Conversation> conversations) {
        boolean changed = false;
        for (Conversation conversation : conversations) changed |= people.seen(conversation.addresses(), me);
        if (changed) prefs().edit().putString("people", people.toJson()).apply();
    }

    private Conversation current() {
        return items.isEmpty() ? null : items.get(Math.min(picked, items.size() - 1));
    }

    // ---- The folder pane ----

    /** A line of the folder pane: a folder, a heading, or something to do. */
    private static final class Entry {
        final Folder folder;
        final int icon;
        final String name;
        final Runnable action;

        Entry(Folder folder, int icon, String name, Runnable action) {
            this.folder = folder;
            this.icon = icon;
            this.name = name;
            this.action = action;
        }

        boolean heading() {
            return folder == null && action == null;
        }
    }

    /** Thunderbird's icon for each kind of folder. */
    private static int icon(Folder folder) {
        switch (folder.kind) {
            case "inbox": return R.drawable.ic_inbox;
            case "starred": return R.drawable.ic_star;
            case "sent": return R.drawable.ic_send;
            case "drafts": return R.drawable.ic_draft;
            case "all": return R.drawable.ic_archive;
            case "spam": return R.drawable.ic_report;
            case "trash": return R.drawable.ic_delete;
            case "search": return R.drawable.ic_search;
            default: return R.drawable.ic_folder;
        }
    }

    /** Gmail's folders, a search if one is open, the labels, then the command center. */
    private void buildEntries() {
        entries.clear();
        for (Folder each : Folder.SYSTEM) entries.add(new Entry(each, icon(each), each.name, null));
        if (folder.kind.equals("search")) entries.add(new Entry(folder, icon(folder), folder.name, null));
        if (!labels.isEmpty()) {
            entries.add(new Entry(null, 0, getString(R.string.labels), null));
            for (Folder label : labels) entries.add(new Entry(label, icon(label), label.name, null));
        }
        entries.add(new Entry(null, 0, "Account", null));
        entries.add(new Entry(null, R.drawable.ic_settings, "Command center", this::askLogin));
    }

    private void openDrawer() {
        if (drawerOpen) return;
        buildEntries();
        drawerPick = 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).folder != null && entries.get(i).folder.is(folder)) drawerPick = i;
        drawerOpen = true;
        String name = me == null ? "" : me.addresses.get(0).name;
        String email = me == null ? (login.ready() ? "Gmail" : "Not set up") : me.email;
        accountName.setText(name.length() > 0 ? name : "Gmail");
        accountEmail.setText(email);
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-dp(40));
        drawer.setAlpha(0.6f);
        drawer.animate().translationX(0).alpha(1).setDuration(140).start();
        folderAdapter.notifyDataSetChanged();
        foldersView.setSelection(Math.max(0, drawerPick - 3));
        if (System.currentTimeMillis() - countsAt > COUNTS_MS) loadCounts();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.animate().cancel();
        scrim.setVisibility(View.GONE);
        drawer.setVisibility(View.GONE);
    }

    private void moveDrawer(int by) {
        int to = drawerPick;
        do {
            to += by;
        } while (to >= 0 && to < entries.size() && entries.get(to).heading());
        if (to < 0 || to >= entries.size()) return;
        drawerPick = to;
        folderAdapter.notifyDataSetChanged();
        foldersView.smoothScrollToPosition(to);
    }

    private void chooseEntry() {
        if (drawerPick < 0 || drawerPick >= entries.size()) return;
        Entry entry = entries.get(drawerPick);
        closeDrawer();
        if (entry.action != null) entry.action.run();
        else if (entry.folder != null && !entry.folder.is(folder)) setFolder(entry.folder);
    }

    /** The counts beside each folder, as Gmail shows them; a failure leaves the old ones. */
    private void loadCounts() {
        if (!login.ready() || counting) return;
        counting = true;
        final Gmail client = gmail();
        final List<Folder> all = new ArrayList<Folder>(Folder.SYSTEM);
        all.addAll(labels);
        counter.submit(() -> {
            Map<String, Integer> found = null;
            try {
                found = client.counts(all);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // The counts are a nicety; the list says when Gmail fails.
            }
            final Map<String, Integer> got = found;
            runOnUiThread(() -> {
                counting = false;
                if (got == null || client != gmail) return;
                counts.clear();
                counts.putAll(got);
                countsAt = System.currentTimeMillis();
                folderAdapter.notifyDataSetChanged();
                bar();
            });
        });
    }

    private int count(Folder of) {
        String label = of.countedLabel();
        Integer count = label == null ? null : counts.get(label);
        return count == null ? 0 : count;
    }

    // ---- Showing ----

    /** Puts the screen in step with the state. */
    private void refresh() {
        boolean list = screen == LIST;
        listView.setVisibility(list && !items.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(list && items.isEmpty() ? View.VISIBLE : View.GONE);
        boolean opening = opening();
        reader.setVisibility(screen == READ && !opening ? View.VISIBLE : View.GONE);
        readerLoading.setVisibility(opening ? View.VISIBLE : View.GONE);
        if (opening) {
            readerSpinner.setVisibility(busy() ? View.VISIBLE : View.GONE);
            readerFailed.setVisibility(busy() ? View.GONE : View.VISIBLE);
            if (!busy()) readerFailed.setText(Family.emptyText(this, "This conversation didn't load", null));
        }
        compose.setVisibility(screen == WRITE ? View.VISIBLE : View.GONE);
        bar();
        if (!list) return;
        if (items.isEmpty()) {
            empty();
            return;
        }
        adapter.notifyDataSetChanged();
        keepInView();
    }

    /**
     * Scrolls the list just far enough to show the picked row whole, at
     * once. A smooth scroll started on every press, while the rows were
     * being bound again, would sometimes run from the top back down to it.
     */
    private void keepInView() {
        listView.post(() -> {
            if (items.isEmpty()) return;
            int first = listView.getFirstVisiblePosition();
            View row = listView.getChildAt(picked - first);
            int height = listView.getHeight();
            if (row != null && row.getTop() >= 0 && row.getBottom() <= height) return;
            if (picked <= first) {
                listView.setSelectionFromTop(picked, 0);
            } else {
                View any = row != null ? row : listView.getChildAt(0);
                int rowHeight = any == null ? 0 : any.getHeight();
                listView.setSelectionFromTop(picked, Math.max(0, height - rowHeight));
            }
        });
    }

    /**
     * The app bar: what Left or Back does (the folder pane, back, or close),
     * the folder or what is being written, the account (or what is under
     * way) under it, and on the right the folder's unread count or which
     * conversation of the list is open.
     */
    private void bar() {
        progress.setVisibility(busy() ? View.VISIBLE : View.INVISIBLE);
        String account = me != null ? me.email : login.ready() ? "Gmail" : "Not set up";
        String under = busy() ? doing : account;
        countView.setVisibility(View.GONE);
        barAction.setVisibility(View.GONE);
        if (screen == WRITE) {
            String kind = writing == null ? Outgoing.NEW : writing.kind;
            barIcon.setImageResource(R.drawable.ic_close);
            titleView.setText(kind.equals(Outgoing.REPLY) ? "Reply" : kind.equals(Outgoing.FORWARD) ? "Forward"
                : kind.equals(Outgoing.DRAFT) ? "Draft" : "New message");
            under = busy() ? doing : "";
            barAction.setImageResource(R.drawable.ic_send);
            barAction.setImageTintList(ColorStateList.valueOf(color(R.color.focus_rule)));
            barAction.setVisibility(View.VISIBLE);
        } else if (screen == READ) {
            barIcon.setImageResource(R.drawable.ic_arrow_back);
            titleView.setText(folder.name);
        } else {
            barIcon.setImageResource(R.drawable.ic_menu);
            titleView.setText(folder.name);
            int unread = folder.countsAll() ? 0 : count(folder);
            if (unread > 0) {
                countView.setText(unread > 999 ? "999+" : String.valueOf(unread));
                countView.setVisibility(View.VISIBLE);
            }
        }
        subtitleView.setText(under);
        subtitleView.setVisibility(under.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** What an empty list says: that it is loading, that it failed, or that the folder is empty. */
    private void empty() {
        emptyIcon.setImageResource(icon(folder));
        String title;
        if (!login.ready()) title = "Mail isn't set up";
        else if (busy()) title = doing;
        else if (!listed) title = folder.name + " didn't load";
        else title = "Nothing in " + folder.name;
        emptyText.setText(Family.emptyText(this, title, null));
    }

    /** The note under the bar; it takes no room while there is nothing to say. */
    private void say(String text) {
        statusView.setText(text);
        statusView.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private int indexOf(String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
        return -1;
    }

    // ---- Reading ----

    private void open() {
        Conversation listed = current();
        if (listed == null) return;
        if (listed.onlyDrafts()) editDraft(listed);
        else show(listed);
    }

    /**
     * A conversation, whole: at once when it was opened before, else a
     * spinner until all of it has loaded, never a part of it.
     */
    private void show(Conversation listed) {
        stop();
        screen = READ;
        showQuoted = false;
        expandAll = false;
        final String id = listed.id;
        reading = whole.get(id);
        if (reading != null) {
            render(reading);
            refresh();
            say("");
            markRead(reading);
            return;
        }
        reading = listed;
        readerBody.removeAllViews();
        messageViews.clear();
        final Gmail client = gmail();
        start("Opening…", () -> {
            final Conversation full = client.conversation(id);
            return () -> {
                whole.put(id, full);
                if (screen != READ || reading == null || !reading.id.equals(id)) return;
                reading = full;
                say("");
                render(full);
                markRead(full);
            };
        });
    }

    /** Whether the conversation open is still loading (or failed to), so only the spinner shows. */
    private boolean opening() {
        return screen == READ && reading != null && !whole.containsKey(reading.id);
    }

    /** Opening an unread conversation reads it, as in Gmail. */
    private void markRead(Conversation conversation) {
        if (conversation.unread()) change(conversation, Change.READ, true);
    }

    private void closeReader() {
        stop();
        screen = LIST;
        reading = null;
        say("");
        refresh();
    }

    /** Left and Right in a conversation: the one before or after it in the list, as Thunderbird's swipe. */
    private void stepConversation(int by) {
        int at = reading == null ? -1 : indexOf(reading.id);
        if (at < 0) return;
        int to = at + by;
        if (to < 0) {
            toast("This is the first in " + folder.name);
            return;
        }
        if (to >= items.size()) {
            toast(next != null ? "Last one loaded" : "This is the last in " + folder.name);
            return;
        }
        picked = to;
        show(items.get(to));
    }

    /**
     * The subject and its tags, then each message under a header of its
     * own, as Thunderbird's conversation view: read messages before the last
     * are folded to their header and first words, unless all are opened.
     */
    private void render(Conversation conversation) {
        readerBody.removeAllViews();
        messageViews.clear();
        readerBody.addView(subjectView(conversation));
        List<Message> messages = conversation.messages;
        int first = -1;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (first < 0 && message.unread()) first = i;
            boolean folded = !expandAll && i < messages.size() - 1 && !message.unread() && !message.draft();
            View view = messageView(message, i, folded);
            readerBody.addView(view);
            messageViews.add(view);
        }
        // The first unread message, or the last, as Gmail opens a conversation.
        final int at = first >= 0 ? first : messages.size() - 1;
        reader.scrollTo(0, 0);
        if (at > 0) scrollOnceLaidOut(at);
    }

    /** Scrolls to a message as soon as the views just added have been measured. */
    private void scrollOnceLaidOut(final int index) {
        readerBody.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                view.removeOnLayoutChangeListener(this);
                view.post(() -> scrollToMessage(index));
            }
        });
    }

    /** The subject, large, and under it a star and the user's labels as Thunderbird's tag pills. */
    private View subjectView(Conversation conversation) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        span(out, conversation.subject(), new TypefaceSpan("sans-serif-medium"), new AbsoluteSizeSpan(16, true),
            new ForegroundColorSpan(color(R.color.text)));
        List<String> tags = new ArrayList<String>();
        List<String> on = conversation.labels();
        for (Folder label : labels) if (on.contains(label.label)) tags.add(label.name);
        boolean starred = conversation.starred();
        if (starred || !tags.isEmpty()) out.append('\n');
        if (starred) span(out, "★ Starred", new Pills.Pill("★ Starred", color(R.color.row_read), color(R.color.star), color(R.color.star), dp(5)));
        for (String tag : tags) {
            if (out.charAt(out.length() - 1) != '\n') out.append(' ');
            span(out, tag, new Pills.Pill(tag, color(R.color.pill_wash), color(R.color.pill), 0, dp(5)));
        }
        TextView view = new TextView(this);
        view.setText(out);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setLineSpacing(dp(6), 1f);
        view.setTextColor(color(R.color.text));
        int pad = getResources().getDimensionPixelSize(R.dimen.pad_h);
        view.setPadding(pad, dp(10), pad, dp(10));
        return view;
    }

    /** One message: its header, then (unless folded) its text, a note where a quote was cut, and its files. */
    private View messageView(Message message, int index, boolean folded) {
        View view = getLayoutInflater().inflate(R.layout.reader_message, readerBody, false);
        String sender = message.sender();
        ((TextView) view.findViewById(R.id.avatar)).setText(Avatar.letter(sender));
        SpannableStringBuilder name = new SpannableStringBuilder(sender.length() == 0 ? "(nobody)" : sender);
        if (message.draft()) span(name, "  Draft", new ForegroundColorSpan(color(R.color.draft)));
        ((TextView) view.findViewById(R.id.name)).setText(name);
        ((TextView) view.findViewById(R.id.date)).setText(shortDate(message.time()));
        TextView detail = (TextView) view.findViewById(R.id.detail);
        String line = folded ? message.snippet() : join(recipients(message), timeUnlessToday(message.time()));
        detail.setText(line);
        detail.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);

        TextView body = (TextView) view.findViewById(R.id.body);
        TextView quoted = (TextView) view.findViewById(R.id.quoted);
        LinearLayout files = (LinearLayout) view.findViewById(R.id.files);
        if (folded) {
            body.setVisibility(View.GONE);
            quoted.setVisibility(View.GONE);
            files.setVisibility(View.GONE);
            return view;
        }
        Message.Content content = message.content();
        String text = content.body();
        boolean hidden = false;
        if (index > 0 && !showQuoted) {
            String cut = Mime.unquoted(text);
            if (cut != null) {
                text = cut;
                hidden = true;
            }
        }
        if (text.length() > MAX_SHOWN) text = text.substring(0, MAX_SHOWN) + "…";
        if (text.trim().length() == 0) {
            SpannableStringBuilder none = new SpannableStringBuilder();
            span(none, "(no text)", new ForegroundColorSpan(color(R.color.text_muted)));
            body.setText(none);
        } else {
            body.setText(linked(text.trim()));
        }
        quoted.setText("Quoted text hidden");
        quoted.setVisibility(hidden ? View.VISIBLE : View.GONE);

        List<Attachment> attachments = content.attachments;
        if (attachments.isEmpty()) {
            files.setVisibility(View.GONE);
            return view;
        }
        long size = 0;
        for (Attachment attachment : attachments) size += attachment.size;
        TextView header = fileLine(R.drawable.ic_attachment,
            files(attachments.size()) + ", " + Mime.size(size), R.color.text_read, true);
        header.setPadding(0, 0, 0, dp(5));
        files.addView(header);
        for (Attachment attachment : attachments) {
            SpannableStringBuilder label = new SpannableStringBuilder();
            span(label, attachment.name, new TypefaceSpan("sans-serif-medium"), new ForegroundColorSpan(color(R.color.text)));
            label.append('\n');
            span(label, Mime.size(attachment.size) + "  ·  " + attachment.type,
                new ForegroundColorSpan(color(R.color.text_muted)), new AbsoluteSizeSpan(11, true));
            TextView card = fileLine(R.drawable.ic_description, "", R.color.text, false);
            card.setText(label);
            card.setSingleLine(false);
            card.setMaxLines(2);
            card.setBackgroundResource(R.drawable.file_card);
            card.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(4);
            files.addView(card, params);
        }
        return view;
    }

    private TextView fileLine(int icon, String text, int textColor, boolean medium) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color(textColor));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (medium) view.setTypeface(Family.MEDIUM);
        view.setCompoundDrawablesRelative(small(icon), null, null, null);
        view.setCompoundDrawablePadding(dp(6));
        return view;
    }

    /** An icon at the size of a line of small text. */
    private Drawable small(int icon) {
        Drawable drawable = getDrawable(icon).mutate();
        drawable.setBounds(0, 0, dp(16), dp(16));
        return drawable;
    }

    /** A message's text with its web addresses in Thunderbird's link blue; * opens them. */
    private CharSequence linked(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder(text);
        int from = 0;
        for (String[] link : Mime.links(text)) {
            int at = text.indexOf(link[0], from);
            if (at < 0) at = text.indexOf(link[0]);
            if (at < 0) continue;
            int end = at + link[0].length();
            out.setSpan(new ForegroundColorSpan(color(R.color.focus_rule)), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new UnderlineSpan(), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = end;
        }
        return out;
    }

    /** "to me, Jane, Bob +2". */
    private String recipients(Message message) {
        List<Address> to = new ArrayList<Address>(message.to());
        to.addAll(message.cc());
        if (to.isEmpty()) return "";
        StringBuilder out = new StringBuilder("to ");
        int shown = Math.min(2, to.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(", ");
            out.append(me != null && me.is(to.get(i).email) ? "me" : to.get(i).brief());
        }
        if (to.size() > shown) out.append(" +").append(to.size() - shown);
        return out.toString();
    }

    private void scrollToMessage(int index) {
        if (index < 0 || index >= messageViews.size()) return;
        reader.scrollTo(0, messageViews.get(index).getTop());
    }

    /**
     * The message being read: the one at the top of the screen, or, once the
     * conversation is scrolled to its end, the last one in sight.
     */
    private int messageInView() {
        if (messageViews.isEmpty()) return 0;
        int top = reader.getScrollY();
        if (!reader.canScrollVertically(1)) {
            int bottom = top + reader.getHeight();
            for (int i = messageViews.size() - 1; i >= 0; i--) {
                if (messageViews.get(i).getTop() + dp(24) < bottom) return i;
            }
        }
        int found = 0;
        for (int i = 0; i < messageViews.size(); i++) if (messageViews.get(i).getTop() <= top + dp(8)) found = i;
        return found;
    }

    /** Whom a reply answers: the message in view, or the latest when that is a draft. */
    private Message replyTarget() {
        if (reading == null || reading.messages.isEmpty()) return null;
        int at = Math.min(messageInView(), reading.messages.size() - 1);
        Message message = reading.messages.get(at);
        return message.draft() ? reading.latest() : message;
    }

    /** Draws it again as it is now, keeping the message in view at the top. */
    private void rerender() {
        if (reading == null || !whole.containsKey(reading.id)) return;
        final int at = messageInView();
        render(reading);
        scrollOnceLaidOut(at);
    }

    private static void span(SpannableStringBuilder out, String text, Object... spans) {
        int start = out.length();
        out.append(text);
        for (Object span : spans) out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** For the list: "3:04 PM" today, "Oct 13" this year, else "10/13/25". */
    private static String shortDate(long time) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        String pattern = now.get(Calendar.YEAR) != then.get(Calendar.YEAR) ? "M/d/yy"
            : now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ? "h:mm a" : "MMM d";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(time));
    }

    /** For an open message, whose date beside the name says only the day: "3:04 PM", or "" today, when it already did. */
    private static String timeUnlessToday(long time) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return today ? "" : new SimpleDateFormat("h:mm a", Locale.US).format(new Date(time));
    }

    private static String join(String first, String second) {
        return first.length() == 0 ? second : second.length() == 0 ? first : first + "  ·  " + second;
    }

    // ---- Changing conversations ----

    /** The phone's copies of a conversation: the list's and the whole one. */
    private List<Conversation> copies(String id) {
        List<Conversation> out = new ArrayList<Conversation>();
        for (Conversation item : items) if (item.id.equals(id)) out.add(item);
        Conversation full = whole.get(id);
        if (full != null && !out.contains(full)) out.add(full);
        if (reading != null && reading.id.equals(id) && !out.contains(reading)) out.add(reading);
        return out;
    }

    /**
     * Makes a change at once on the phone and then in Gmail. One that takes
     * the conversation out of this folder (archive in the inbox, say) takes it
     * off the list, and out of the reader back to the list, with the pick on
     * the one after it.
     */
    private void change(Conversation conversation, final Change change, boolean quiet) {
        final String id = conversation.id;
        boolean wasUnread = conversation.unread();
        boolean wasStarred = conversation.starred();
        for (Conversation copy : copies(id)) change.apply(copy);
        Integer inbox = counts.get("INBOX");
        if (inbox != null && conversation.has("INBOX")) {
            if (change == Change.READ && wasUnread) counts.put("INBOX", Math.max(0, inbox - 1));
            if (change == Change.UNREAD && !wasUnread) counts.put("INBOX", inbox + 1);
        }
        countsAt = 0;
        boolean gone = !folder.holds(conversation);
        if (gone) removeFromList(id);
        if (!quiet) toast(change.done);
        if (screen == READ && (gone || change == Change.UNREAD)) closeReader();
        else if (screen == READ && conversation.starred() != wasStarred) rerender();
        else refresh();
        final Gmail client = gmail();
        changes.submit(() -> {
            try {
                client.change(id, change);
            } catch (Exception e) {
                final String message = message(e);
                runOnUiThread(() -> {
                    say("Couldn't do that: " + message);
                    whole.remove(id);
                    if (screen == LIST) load();
                });
            }
        });
    }

    private void removeFromList(String id) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!items.get(i).id.equals(id)) continue;
            items.remove(i);
            if (picked > i) picked--;
        }
        picked = Math.max(0, Math.min(picked, items.size() - 1));
    }

    /** Ticks the user's labels on and off a conversation. */
    private void chooseLabels(final Conversation conversation) {
        if (labels.isEmpty()) {
            toast("There are no labels of your own.");
            return;
        }
        final String[] names = new String[labels.size()];
        final boolean[] on = new boolean[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            names[i] = labels.get(i).name;
            on[i] = conversation.has(labels.get(i).label);
        }
        final boolean[] was = on.clone();
        new AlertDialog.Builder(this)
            .setTitle("Labels")
            .setMultiChoiceItems(names, on, (dialog, which, checked) -> on[which] = checked)
            .setPositiveButton("OK", (dialog, which) -> {
                final List<String> add = new ArrayList<String>();
                final List<String> remove = new ArrayList<String>();
                for (int i = 0; i < labels.size(); i++) {
                    if (on[i] && !was[i]) add.add(labels.get(i).label);
                    if (!on[i] && was[i]) remove.add(labels.get(i).label);
                }
                if (add.isEmpty() && remove.isEmpty()) return;
                final String id = conversation.id;
                for (Conversation copy : copies(id)) copy.relabel(add, remove);
                countsAt = 0;
                if (!folder.holds(conversation)) {
                    removeFromList(id);
                    if (screen == READ) closeReader();
                } else if (screen == READ) {
                    rerender();
                }
                refresh();
                toast("Labels changed");
                final Gmail client = gmail();
                changes.submit(() -> {
                    try {
                        client.relabel(id, add, remove);
                    } catch (Exception e) {
                        final String message = message(e);
                        runOnUiThread(() -> say("Couldn't change the labels: " + message));
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ---- Writing ----

    private void write(Outgoing out, int from, String status) {
        stop();
        closeDrawer();
        writing = out;
        writingStart = Outgoing.parse(out.toJson());
        writeFrom = from;
        screen = WRITE;
        sending = false;
        toField.setText(pilled(out.to));
        ccField.setText(pilled(out.cc));
        subjectField.setText(out.subject);
        bodyField.setText(out.text);
        ccRow.setVisibility(out.cc.length() > 0 ? View.VISIBLE : View.GONE);
        showFrom();
        if (out.attachments.isEmpty()) {
            filesView.setVisibility(View.GONE);
        } else {
            long size = 0;
            for (Attachment attachment : out.attachments) size += attachment.size;
            filesView.setText(files(out.attachments.size()) + ", " + Mime.size(size));
            filesView.setVisibility(View.VISIBLE);
        }
        EditText focus = out.to.length() == 0 ? toField : out.subject.length() == 0 ? subjectField : bodyField;
        focus.requestFocus();
        focus.setSelection(focus == bodyField && !out.kind.equals(Outgoing.DRAFT) ? 0 : focus.length());
        refresh();
        say(status);
    }

    /** Whom it is sent as, as Thunderbird's From line: the name, then the address in grey. */
    private void showFrom() {
        List<Address> chosen = Address.parse(writing.from);
        Address from = chosen.isEmpty() || me != null && !me.is(chosen.get(0).email) ? null : chosen.get(0);
        if (from == null && me != null) from = me.addresses.get(0);
        if (from == null) {
            fromRow.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder line = new SpannableStringBuilder();
        if (from.name.length() > 0) {
            span(line, from.name, new ForegroundColorSpan(color(R.color.text)));
            line.append("  ");
        }
        span(line, from.email, new ForegroundColorSpan(color(R.color.text_muted)));
        fromView.setText(line);
        fromRow.setVisibility(View.VISIBLE);
    }

    private void compose(String to) {
        write(Outgoing.compose(to), screen, "");
    }

    private void reply(boolean all) {
        Message target = replyTarget();
        if (target == null || me == null) return;
        write(Outgoing.reply(target, me, all, TimeZone.getDefault()), READ, "");
    }

    private void forward() {
        Message target = replyTarget();
        if (target == null || me == null) return;
        write(Outgoing.forward(target, me, TimeZone.getDefault()), READ, "");
    }

    private static String files(int count) {
        return count == 1 ? "1 attachment" : count + " attachments";
    }

    /** A draft, opened to carry on with: Gmail's id for it is looked up first, so sending sends it. */
    private void editDraft(Conversation conversation) {
        final String id = conversation.id;
        final Gmail client = gmail();
        final int from = screen;
        final Conversation known = whole.get(id);
        start("Opening the draft…", () -> {
            Conversation full = known != null ? known : client.conversation(id);
            final Conversation loaded = full;
            final Message draft = loaded.draft();
            if (draft == null) throw new IOException("That draft was sent or deleted.");
            final String draftId = client.draftId(draft.id);
            if (draftId == null) throw new IOException("That draft was sent or deleted.");
            return () -> {
                whole.put(id, loaded);
                write(Outgoing.draft(draft, draftId), from, "");
            };
        });
    }

    /** Addresses as a field shows them: each finished with a comma, so each is drawn as a pill. */
    private static String pilled(String addresses) {
        String trimmed = addresses.trim();
        return trimmed.length() == 0 || trimmed.endsWith(",") ? trimmed : trimmed + ", ";
    }

    /** A field's addresses as kept: without the comma after the last. */
    private static String unpilled(EditText field) {
        return field.getText().toString().trim().replaceAll("[\\s,]+$", "");
    }

    /** The fields into the message being written. */
    private void collect() {
        writing.to = unpilled(toField);
        writing.cc = unpilled(ccField);
        writing.subject = subjectField.getText().toString();
        writing.text = bodyField.getText().toString();
    }

    private void send() {
        if (sending) return;
        collect();
        final List<Address> to;
        try {
            to = writing.recipients();
        } catch (IllegalArgumentException e) {
            say(e.getMessage());
            return;
        }
        if (!signedIn()) return;
        final Outgoing out = writing;
        final Gmail client = gmail();
        sending = true;
        start("Sending…", () -> {
            try {
                client.send(out);
            } catch (IOException | RuntimeException e) {
                final String message = message(e);
                return () -> {
                    sending = false;
                    say(message);
                };
            }
            return () -> {
                sending = false;
                people.wrote(to);
                prefs().edit().putString("people", people.toJson()).apply();
                toast("Sent");
                finishWriting(true);
            };
        });
    }

    private void saveDraft() {
        if (sending) return;
        collect();
        if (!signedIn()) return;
        final Outgoing out = writing;
        final Gmail client = gmail();
        sending = true;
        start("Saving the draft…", () -> {
            try {
                client.save(out);
            } catch (IOException | RuntimeException e) {
                final String message = message(e);
                return () -> {
                    sending = false;
                    say(message);
                };
            }
            return () -> {
                sending = false;
                toast("Saved to Drafts");
                finishWriting(true);
            };
        });
    }

    /** Throws it away; a draft saved in Gmail goes too, once confirmed. */
    private void discard() {
        if (sending) return;
        final String draftId = writing.draftId;
        if (draftId.length() == 0) {
            toast("Discarded");
            finishWriting(false);
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage("Delete this draft from Gmail too?")
            .setPositiveButton("Delete", (dialog, which) -> {
                final Gmail client = gmail();
                changes.submit(() -> {
                    try {
                        client.discard(draftId);
                    } catch (Exception e) {
                        final String message = message(e);
                        runOnUiThread(() -> say("Couldn't delete the draft: " + message));
                    }
                });
                toast("Draft deleted");
                finishWriting(true);
            })
            .setNegativeButton("Keep it", (dialog, which) -> finishWriting(false))
            .show();
    }

    /** Back while writing: straight out when nothing was typed, else save, discard or carry on. */
    private void leaveWriting() {
        if (sending) return;
        collect();
        if (writing.untouched(writingStart)) {
            finishWriting(false);
            return;
        }
        new Sheet()
            .add(R.drawable.ic_save, "Save draft", this::saveDraft)
            .add(R.drawable.ic_delete, "Discard", this::discard)
            .add(R.drawable.ic_edit, "Keep writing", () -> {})
            .show(this);
    }

    /** Back to where writing began; what it changed (a reply, a draft) is fetched again. */
    private void finishWriting(boolean changedMail) {
        prefs().edit().remove("compose").apply();
        hideSuggestions();
        String thread = writing.threadId;
        View focus = getCurrentFocus();
        if (focus != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
        if (changedMail && thread.length() > 0) whole.remove(thread);
        if (writeFrom == READ && reading != null) {
            writing = null;
            if (changedMail) {
                // Open it again, whole, with the reply in it.
                whole.remove(reading.id);
                show(reading);
            } else {
                screen = READ;
                say("");
                refresh();
            }
            return;
        }
        screen = LIST;
        writing = null;
        say("");
        refresh();
        if (changedMail || items.isEmpty()) load();
    }

    // ---- Whom to write to, offered as it is typed ----

    /**
     * As Gmail does: typing in To or Cc offers the people whose name or
     * address starts with what is typed after the last comma, those known
     * from mail first, then the user's organisation and the people Gmail
     * saved, found once typing pauses.
     */
    private void offerPeople(final EditText field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                suggest(field);
            }
        });
        field.setOnFocusChangeListener((view, focused) -> {
            if (!focused && suggestFor == field) hideSuggestions();
        });
    }

    /** What is being typed in the field: after its last comma. */
    private static String typedAddress(EditText field) {
        String text = field.getText().toString();
        List<Integer> commas = Address.commas(text);
        return (commas.isEmpty() ? text : text.substring(commas.get(commas.size() - 1) + 1)).trim();
    }

    private void suggest(EditText field) {
        String typed = typedAddress(field);
        if (screen != WRITE || !field.hasFocus() || typed.length() == 0) {
            hideSuggestions();
            return;
        }
        boolean same = field == suggestFor && typed.equalsIgnoreCase(suggestTyped);
        Address was = suggestPick >= 0 && same ? suggestions.get(suggestPick) : null;
        suggestFor = field;
        suggestTyped = typed;
        suggestions.clear();
        suggestions.addAll(people.suggest(typed, foundFor(typed), SUGGESTIONS));
        suggestPick = -1;
        for (int i = 0; was != null && i < suggestions.size(); i++) if (suggestions.get(i).is(was.email)) suggestPick = i;
        showSuggestions();
        toField.removeCallbacks(findPeople);
        if (typed.length() >= 2 && !found.containsKey(typed.toLowerCase(Locale.US)) && login.ready()) {
            toField.postDelayed(findPeople, FIND_AFTER_MS);
        }
    }

    /** The organisation's people for what is typed: as found for it, or for the longest start of it, narrowed. */
    private List<Address> foundFor(String typed) {
        String key = typed.toLowerCase(Locale.US);
        for (int end = key.length(); end >= 2; end--) {
            List<Address> known = found.get(key.substring(0, end));
            if (known != null) return end == key.length() ? known : People.matching(known, typed);
        }
        return new ArrayList<Address>();
    }

    private void findPeople() {
        if (suggestFor == null) return;
        final Gmail client = gmail();
        if (!client.searchesPeople()) return;
        final String typed = suggestTyped;
        final String key = typed.toLowerCase(Locale.US);
        if (found.containsKey(key)) return;
        finder.submit(() -> {
            final List<Address> people;
            try {
                people = client.people(typed);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // Offline, or Google failed: what is known from mail is still offered.
                return;
            }
            runOnUiThread(() -> {
                if (found.size() > 100) found.clear();
                found.put(key, people);
                if (suggestFor != null && suggestTyped.equalsIgnoreCase(typed)) suggest(suggestFor);
            });
        });
    }

    /** The people offered, right under the field being typed in. */
    private void showSuggestions() {
        if (suggestions.isEmpty() || suggestFor == null) {
            suggestView.setVisibility(View.GONE);
            return;
        }
        ViewGroup form = (ViewGroup) compose;
        int under = form.indexOfChild((View) suggestFor.getParent()) + 1;
        if (form.indexOfChild(suggestView) != under) {
            form.removeView(suggestView);
            form.addView(suggestView, form.indexOfChild((View) suggestFor.getParent()) + 1);
        }
        suggestView.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < suggestions.size(); i++) {
            Address address = suggestions.get(i);
            View row = inflater.inflate(R.layout.row_suggestion, suggestView, false);
            ((TextView) row.findViewById(R.id.avatar)).setText(Avatar.letter(address.display()));
            ((TextView) row.findViewById(R.id.name)).setText(address.display());
            TextView email = (TextView) row.findViewById(R.id.email);
            email.setText(address.email);
            email.setVisibility(address.name.length() > 0 ? View.VISIBLE : View.GONE);
            row.setBackgroundColor(color(i == suggestPick ? R.color.focus_fill : R.color.row));
            suggestView.addView(row);
        }
        suggestView.setVisibility(View.VISIBLE);
    }

    private boolean suggesting() {
        return suggestFor != null && !suggestions.isEmpty() && suggestFor.hasFocus();
    }

    private void hideSuggestions() {
        toField.removeCallbacks(findPeople);
        suggestFor = null;
        suggestTyped = "";
        suggestions.clear();
        suggestPick = -1;
        suggestView.setVisibility(View.GONE);
    }

    /** The person in place of what was typed, as a pill, ready for the next. */
    private void pickSuggestion(int which) {
        EditText field = suggestFor;
        Address address = suggestions.get(which);
        String text = field.getText().toString();
        List<Integer> commas = Address.commas(text);
        String before = commas.isEmpty() ? "" : text.substring(0, commas.get(commas.size() - 1) + 1).trim() + " ";
        hideSuggestions();
        field.setText(before + Address.field(Collections.singletonList(address)) + ", ");
        field.setSelection(field.length());
    }

    /**
     * Keys while people are offered: Down and Up go through them (Up past the
     * first back to the field), OK takes the one picked, or the first when
     * what is typed isn't an address yet, and Back puts them away.
     */
    private boolean suggestionKey(KeyEvent event, int key, boolean down, boolean first) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    suggestPick = Math.min(suggestions.size() - 1, suggestPick + 1);
                    showSuggestions();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (suggestPick < 0) return false;
                if (down) {
                    suggestPick--;
                    showSuggestions();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down && !event.isCanceled()) hideSuggestions();
                return true;
            default:
                if (!Ui.isOk(key)) return false;
                if (suggestPick < 0 && isAddress(suggestTyped)) return false;
                if (first) pickSuggestion(Math.max(0, suggestPick));
                return true;
        }
    }

    private static boolean isAddress(String typed) {
        try {
            return Address.typed(typed).size() == 1;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Someone known, into To or Cc (whichever has the cursor), matching what is typed after the last comma. */
    private void addRecipient() {
        final EditText field = ccField.hasFocus() ? ccField : toField;
        final String text = field.getText().toString();
        int comma = text.lastIndexOf(',');
        final String head = comma < 0 ? "" : text.substring(0, comma + 1) + " ";
        String typed = comma < 0 ? text : text.substring(comma + 1);
        if (typed.contains("@") && typed.trim().matches("\\S+@\\S+\\.\\S+")) typed = "";
        List<Address> found = people.matching(typed);
        if (found.isEmpty() && typed.trim().length() > 0) {
            toast("Nobody matches \"" + typed.trim() + "\"");
            found = people.matching("");
        }
        if (found.isEmpty()) {
            toast("No addresses yet");
            return;
        }
        final List<Address> choices = found.size() > 100 ? found.subList(0, 100) : found;
        final String keep = typed.length() == 0 ? text.trim() : head.trim();
        List<String> shown = new ArrayList<String>();
        for (Address address : choices) shown.add(address.name.length() > 0 ? address.name + " <" + address.email + ">" : address.email);
        listDialog(field == ccField ? "Copy to" : "To", shown, which -> {
            String before = keep.length() == 0 ? "" : keep.endsWith(",") ? keep + " " : keep + ", ";
            field.setText(before + choices.get(which).email + ", ");
            field.requestFocus();
            field.setSelection(field.length());
        }, null);
    }

    // ---- Files and links ----

    private void chooseAttachment() {
        if (reading == null) return;
        final List<Attachment> all = new ArrayList<Attachment>();
        for (Message message : reading.messages) all.addAll(message.content().attachments);
        if (all.isEmpty()) {
            toast("No attachments here.");
            return;
        }
        if (all.size() == 1) {
            saveAttachment(all.get(0));
            return;
        }
        List<String> shown = new ArrayList<String>();
        for (Attachment attachment : all) shown.add(attachment.label());
        listDialog("Save to SD card", shown, which -> saveAttachment(all.get(which)), null);
    }

    /**
     * Saves to the SD card, never the phone. Android lets an app write in the
     * card's Download folder only once the user has allowed it, so that is
     * asked for on the first save; refused, the file goes to Mail's own
     * folder on the card instead.
     */
    private void saveAttachment(final Attachment attachment) {
        final File card = Card.root(this);
        if (card == null) {
            toast("No SD card: put one in to save files");
            return;
        }
        final Uri tree = cardFolder(card);
        if (tree == null) {
            askForCardFolder(attachment, card);
            return;
        }
        final Gmail client = gmail();
        final ContentResolver resolver = getContentResolver();
        start("Saving " + attachment.name + "…", () -> {
            byte[] data = client.attachment(attachment);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            // Octet-stream keeps the name as it is; the provider adds " (1)" and so on when it is taken.
            Uri doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", Mime.safeName(attachment.name));
            if (doc == null) throw new IOException("Couldn't write to the SD card's Download folder.");
            try {
                OutputStream out = resolver.openOutputStream(doc);
                if (out == null) throw new IOException("Couldn't write to the SD card's Download folder.");
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
            } catch (IOException | RuntimeException e) {
                DocumentsContract.deleteDocument(resolver, doc);
                throw e;
            }
            // "0403-0201:Download/test (1).pdf": the path on the card follows the colon.
            String id = DocumentsContract.getDocumentId(doc);
            final File file = new File(card, id.substring(id.indexOf(':') + 1));
            MediaScannerConnection.scanFile(this, new String[] {file.getPath()}, null, null);
            return () -> {
                say("");
                toast("Saved to SD card/Download/" + file.getName());
            };
        });
    }

    /** The card's Download folder, if the user has let Mail write there; null if not. */
    private Uri cardFolder(File card) {
        String kept = prefs().getString("card_folder", "");
        if (kept.length() == 0) return null;
        Uri tree = Uri.parse(kept);
        // A grant is for one card: another card in the slot has another id.
        if (!DocumentsContract.getTreeDocumentId(tree).startsWith(card.getName() + ":")) return null;
        for (UriPermission grant : getContentResolver().getPersistedUriPermissions()) {
            if (grant.getUri().equals(tree) && grant.isWritePermission()) return tree;
        }
        return null;
    }

    /** Android's "Allow Mail to access Download on SD card?", once; the save carries on after. */
    private void askForCardFolder(Attachment attachment, File card) {
        StorageVolume volume = getSystemService(StorageManager.class).getStorageVolume(card);
        Intent ask = volume == null ? null : volume.createAccessIntent(Environment.DIRECTORY_DOWNLOADS);
        if (ask != null) {
            try {
                pendingSave = attachment;
                startActivityForResult(ask, ASK_FOLDER);
                return;
            } catch (ActivityNotFoundException e) {
                pendingSave = null;
            }
        }
        saveToOwnFolder(attachment, card);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != ASK_FOLDER) return;
        Attachment attachment = pendingSave;
        pendingSave = null;
        if (attachment == null) return;
        Uri tree = result == RESULT_OK && data != null ? data.getData() : null;
        if (tree != null) {
            getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs().edit().putString("card_folder", tree.toString()).apply();
            saveAttachment(attachment);
            return;
        }
        File card = Card.root(this);
        if (card == null) toast("No SD card: put one in to save files");
        else saveToOwnFolder(attachment, card);
    }

    /** Mail's own folder on the card, which needs no one's leave: for when the Download folder is refused. */
    private void saveToOwnFolder(final Attachment attachment, File card) {
        File own = null;
        for (File dir : getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS)) {
            if (dir != null && dir.getPath().startsWith(card.getPath() + "/")) own = dir;
        }
        if (own == null) {
            toast("Couldn't open Mail's folder on the SD card");
            return;
        }
        final File folder = own;
        final String shown = "SD card" + folder.getPath().substring(card.getPath().length());
        final Gmail client = gmail();
        start("Saving " + attachment.name + "…", () -> {
            byte[] data = client.attachment(attachment);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Couldn't open Mail's folder on the SD card.");
            final File file = unused(folder, Mime.safeName(attachment.name));
            OutputStream out = new FileOutputStream(file);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            MediaScannerConnection.scanFile(this, new String[] {file.getPath()}, null, null);
            return () -> {
                say("");
                toast("Saved to " + shown + "/" + file.getName());
            };
        });
    }

    /** `name`, or "name (2).ext" and so on when that is taken. */
    private static File unused(File folder, String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File file = new File(folder, name);
        for (int i = 2; file.exists(); i++) file = new File(folder, stem + " (" + i + ")" + ext);
        return file;
    }

    // ---- Email files ----

    /**
     * An .eml from Documents or Download, on the SD card or the phone
     * (Tablet saves Transform's emails into the card's Documents), opened
     * as a new message. Reading them asks for storage first, once.
     */
    private void chooseEmailFile() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, READ_STORAGE);
            return;
        }
        List<File> folders = new ArrayList<File>();
        File card = Card.root(this);
        File phone = Environment.getExternalStorageDirectory();
        for (File root : card == null ? new File[] {phone} : new File[] {card, phone}) {
            folders.add(new File(root, Environment.DIRECTORY_DOCUMENTS));
            folders.add(new File(root, Environment.DIRECTORY_DOWNLOADS));
        }
        final List<File> found = Eml.find(folders);
        if (found.isEmpty()) {
            toast("No email files in Documents or Download");
            return;
        }
        List<String> shown = new ArrayList<String>();
        for (File file : found) shown.add(file.getName().replaceAll("(?i)\\.eml$", ""));
        listDialog("Open email file", shown, which -> openEmailFile(found.get(which)), null);
    }

    private void openEmailFile(final File file) {
        final int from = screen;
        start("Opening " + file.getName() + "…", () -> {
            final Outgoing out = Eml.open(Files.readAllBytes(file.toPath()));
            return () -> write(out, from, "");
        });
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != READ_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) chooseEmailFile();
        else toast("Storage not allowed");
    }

    /** The message in view's links: a web one opens in the browser, an address starts a message to it. */
    private void chooseLink() {
        Message target = replyTarget();
        if (target == null) return;
        final List<String[]> links = target.content().links();
        if (links.isEmpty()) {
            toast("No links in this message.");
            return;
        }
        List<String> shown = new ArrayList<String>();
        for (String[] link : links) shown.add(link[0].length() > 80 ? link[0].substring(0, 80) + "…" : link[0]);
        listDialog("Links", shown, which -> {
            String url = links.get(which)[1];
            if (url.toLowerCase(Locale.US).startsWith("mailto:")) {
                String address = Uri.decode(url.substring(7));
                int query = address.indexOf('?');
                compose(query >= 0 ? address.substring(0, query) : address);
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException e) {
                toast("Nothing on this phone can open that.");
            }
        }, null);
    }

    // ---- Keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        if (key == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                closeDrawer();
                actions().show(this);
            }
            return true;
        }
        if (drawerOpen) return drawerKey(key, down, first);
        if (screen == WRITE) return writeKey(event, key, down, first);
        char shortcut = shortcut(key);
        if (shortcut != 0) {
            if (first) actions().press(shortcut);
            return true;
        }
        if (screen == READ) return readKey(event, key, down, first);
        return listKey(event, key, down, first);
    }

    /** The keypad key as the menu shows it, or 0. */
    private static char shortcut(int key) {
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) return (char) ('0' + key - KeyEvent.KEYCODE_0);
        if (key >= KeyEvent.KEYCODE_NUMPAD_0 && key <= KeyEvent.KEYCODE_NUMPAD_9) return (char) ('0' + key - KeyEvent.KEYCODE_NUMPAD_0);
        if (key == KeyEvent.KEYCODE_STAR || key == KeyEvent.KEYCODE_NUMPAD_MULTIPLY) return '*';
        if (key == KeyEvent.KEYCODE_POUND) return '#';
        return 0;
    }

    private boolean drawerKey(int key, boolean down, boolean first) {
        if (Ui.isOk(key) || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (first) chooseEntry();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveDrawer(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (first) closeDrawer();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) closeDrawer();
                return true;
            default:
                return true;
        }
    }

    private boolean listKey(KeyEvent event, int key, boolean down, boolean first) {
        if (Ui.isOk(key)) {
            if (first) open();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) movePick(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (first) openDrawer();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first) open();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (busy()) {
                    if (!down) {
                        stop();
                        refresh();
                    }
                    return true;
                }
                if (!folder.is(Folder.INBOX)) {
                    if (!down) setFolder(Folder.INBOX);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private boolean readKey(KeyEvent event, int key, boolean down, boolean first) {
        if (Ui.isOk(key)) {
            if (first) actions().show(this);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) reader.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * dp(60));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first) stepConversation(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) closeReader();
                return true;
            default:
                return event.isPrintingKey() || super.dispatchKeyEvent(event);
        }
    }

    /**
     * OK moves on from To, Cc and the subject, and starts a new line in the
     * message. It acts on the press: with T9 composing a word, the keyboard
     * takes the first release to finish it.
     */
    private boolean writeKey(KeyEvent event, int key, boolean down, boolean first) {
        if (suggesting() && suggestionKey(event, key, down, first)) return true;
        if (key == KeyEvent.KEYCODE_BACK) {
            if (!down && !event.isCanceled()) leaveWriting();
            return true;
        }
        if (Ui.isOk(key)) {
            if (!down) return true;
            View focus = getCurrentFocus();
            if (focus == bodyField) {
                int start = Math.max(0, bodyField.getSelectionStart());
                int end = Math.max(0, bodyField.getSelectionEnd());
                bodyField.getText().replace(Math.min(start, end), Math.max(start, end), "\n");
            } else if (first) {
                EditText to = focus == toField ? (ccRow.getVisibility() == View.VISIBLE ? ccField : subjectField)
                    : focus == ccField ? subjectField : bodyField;
                to.requestFocus();
                to.setSelection(to == bodyField ? 0 : to.length());
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Menus ----

    /**
     * What can be done here, in the menu's order, each with its keypad
     * shortcut: the digits as Thunderbird's single-key shortcuts are grouped
     * (1 to 3 answer, 4 to 9 file away), 0 writes, * and # the rest.
     */
    private Sheet actions() {
        Sheet sheet = new Sheet();
        if (screen == WRITE) {
            if (sending) return sheet;
            sheet.add(R.drawable.ic_send, "Send", this::send);
            sheet.add(R.drawable.ic_person_add, "Add recipient…", this::addRecipient);
            if (ccRow.getVisibility() != View.VISIBLE) {
                sheet.add(R.drawable.ic_add, "Add Cc", () -> {
                    ccRow.setVisibility(View.VISIBLE);
                    ccField.requestFocus();
                });
            }
            sheet.add(R.drawable.ic_save, "Save draft", this::saveDraft);
            sheet.add(R.drawable.ic_delete, "Discard", () -> {
                collect();
                discard();
            });
            return sheet;
        }
        if (busy()) {
            sheet.add(R.drawable.ic_close, "Stop", () -> {
                stop();
                refresh();
            });
        }
        if (screen == READ) {
            final Conversation conversation = reading != null ? reading : current();
            boolean loaded = reading != null && whole.containsKey(reading.id);
            if (loaded && me != null) {
                sheet.add(R.drawable.ic_reply, "Reply", '1', () -> reply(false));
                Message target = replyTarget();
                if (target != null && target.to().size() + target.cc().size() > 1) {
                    sheet.add(R.drawable.ic_reply_all, "Reply all", '2', () -> reply(true));
                }
                sheet.add(R.drawable.ic_forward, "Forward", '3', this::forward);
                if (reading.draft() != null) sheet.add(R.drawable.ic_edit, "Edit draft", () -> editDraft(reading));
            }
            if (conversation != null) conversationActions(sheet, conversation);
            if (loaded) {
                boolean files = false;
                for (Message message : reading.messages) files |= !message.content().attachments.isEmpty();
                if (files) sheet.add(R.drawable.ic_download, "Save attachment…", '#', this::chooseAttachment);
                sheet.add(R.drawable.ic_link, "Open link…", '*', this::chooseLink);
                if (reading.messages.size() > 1) {
                    sheet.add(expandAll ? R.drawable.ic_expand_less : R.drawable.ic_expand_more,
                        expandAll ? "Fold read messages" : "Expand all messages", () -> {
                            expandAll = !expandAll;
                            rerender();
                        });
                }
                sheet.add(R.drawable.ic_description, showQuoted ? "Hide quoted text" : "Show quoted text", () -> {
                    showQuoted = !showQuoted;
                    rerender();
                });
            }
            sheet.add(R.drawable.ic_edit, "Write", '0', () -> compose(""));
            sheet.add(R.drawable.ic_arrow_back, "Back to " + folder.name, this::closeReader);
            return sheet;
        }
        sheet.add(R.drawable.ic_edit, "Write", '0', () -> compose(""));
        sheet.add(R.drawable.ic_draft, "Open email file…", this::chooseEmailFile);
        Conversation conversation = current();
        if (conversation != null) conversationActions(sheet, conversation);
        sheet.add(R.drawable.ic_search, "Search…", '*', this::askSearch);
        sheet.add(R.drawable.ic_menu, "Folders", LEFT, this::openDrawer);
        sheet.add(R.drawable.ic_refresh, "Refresh", '#', this::load);
        if (next != null) sheet.add(R.drawable.ic_expand_more, "Load more", this::more);
        sheet.add(R.drawable.ic_settings, "Command center…", this::askLogin);
        return sheet;
    }

    /** Archive, delete and the rest that make sense for it, then its labels. */
    private void conversationActions(Sheet sheet, final Conversation conversation) {
        for (final Change change : Change.offered(conversation)) {
            sheet.add(icon(change), change.label, key(change), () -> change(conversation, change, false));
        }
        if (!labels.isEmpty()) sheet.add(R.drawable.ic_folder, "Labels…", '8', () -> chooseLabels(conversation));
    }

    private static int icon(Change change) {
        switch (change) {
            case ARCHIVE: return R.drawable.ic_archive;
            case TRASH: return R.drawable.ic_delete;
            case READ: return R.drawable.ic_mark_email_read;
            case UNREAD: return R.drawable.ic_mark_email_unread;
            case STAR: return R.drawable.ic_star;
            case UNSTAR: return R.drawable.ic_star_filled;
            case SPAM: return R.drawable.ic_report;
            default: return R.drawable.ic_inbox;
        }
    }

    /** 4 files it away (or back), 5 stars, 6 marks, 7 is spam, 9 deletes (or restores). */
    private static char key(Change change) {
        switch (change) {
            case ARCHIVE:
            case INBOX:
                return '4';
            case STAR:
            case UNSTAR:
                return '5';
            case READ:
            case UNREAD:
                return '6';
            case SPAM:
            case NOT_SPAM:
                return '7';
            default:
                return '9';
        }
    }

    private void askSearch() {
        LinearLayout box = vertical();
        int pad = dp(12);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        if (folder.kind.equals("search")) {
            field.setText(folder.query);
            field.setSelection(field.length());
        }
        box.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Search mail")
            .setView(box)
            .setPositiveButton("Search", (dialog, which) -> {
                String query = field.getText().toString().trim();
                if (query.length() > 0) setFolder(Folder.search(query));
            })
            .setNegativeButton("Cancel", null)
            .show();
        onOk(field, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    }

    /** The fallback for a build without a bundled login. */
    private void askLogin() {
        LinearLayout box = vertical();
        int pad = dp(12);
        box.setPadding(pad, pad / 2, pad, 0);
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
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", (dialog, which) -> {
                String typed = token.getText().toString();
                if (typed.trim().length() == 0 && login.ready()) typed = login.token();
                try {
                    Login next = Login.of(server.getText().toString(), typed);
                    if (!next.ready()) throw new IllegalArgumentException("Both the address and the token are needed");
                    login = next;
                    if (gmail != null) gmail.close();
                    gmail = null;
                    me = null;
                    labelsLoaded = false;
                    whole.clear();
                    counts.clear();
                    countsAt = 0;
                    prefs().edit().putString("login", next.toJson()).apply();
                    say("Saved: " + next.host());
                    if (screen == LIST) load();
                } catch (IllegalArgumentException e) {
                    say(e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
        onOk(server, token::requestFocus);
        onOk(token, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    }

    /**
     * OK (or the keyboard's Done) in a dialog's one-line field does `then`, as
     * OK commits a typed field in the other apps. It acts on the key's release,
     * so the release doesn't reach whatever is under the dialog.
     */
    private static void onOk(EditText field, Runnable then) {
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            then.run();
            return true;
        });
        // Only a press that began here: the release of the OK that opened the dialog is not one.
        final boolean[] pressed = {false};
        field.setOnKeyListener((view, keyCode, event) -> {
            if (!Ui.isOk(keyCode)) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                pressed[0] = true;
            } else if (event.getAction() == KeyEvent.ACTION_UP && pressed[0]) {
                pressed[0] = false;
                if (!event.isCanceled()) then.run();
            }
            return true;
        });
    }

    // ---- Adapters ----

    /**
     * A conversation, as Thunderbird's message item: unread ones white, with
     * the dot, Medium type and a blue time; read ones on grey in a softer
     * ink. A star, a paperclip and the message count show when they apply.
     */
    private final class ConversationAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View row = convert != null ? convert : getLayoutInflater().inflate(R.layout.row_conversation, parent, false);
            Conversation conversation = items.get(position);
            boolean unread = conversation.unread();
            boolean sent = folder == Folder.SENT;
            int ink = color(unread ? R.color.text : R.color.text_read);
            Typeface face = unread ? Family.MEDIUM : Typeface.DEFAULT;
            row.setBackgroundResource(unread ? R.drawable.row_unread : R.drawable.row_read);
            row.setActivated(position == picked);
            row.findViewById(R.id.dot).setVisibility(unread ? View.VISIBLE : View.INVISIBLE);

            String names = conversation.names(me, sent);
            ((TextView) row.findViewById(R.id.avatar)).setText(Avatar.letter(person(conversation, names, sent)));

            TextView who = (TextView) row.findViewById(R.id.who);
            who.setText(names.length() == 0 ? "(nobody)" : names);
            who.setTypeface(face);
            who.setTextColor(conversation.onlyDrafts() ? color(R.color.draft) : ink);

            row.findViewById(R.id.star).setVisibility(conversation.starred() ? View.VISIBLE : View.GONE);
            TextView when = (TextView) row.findViewById(R.id.when);
            when.setText(shortDate(conversation.time()));
            when.setTextColor(color(unread ? R.color.focus_rule : R.color.text_muted));
            when.setTypeface(face);

            TextView subject = (TextView) row.findViewById(R.id.subject);
            subject.setText(conversation.subject());
            subject.setTypeface(face);
            subject.setTextColor(ink);
            row.findViewById(R.id.clip).setVisibility(conversation.hasFiles() ? View.VISIBLE : View.GONE);
            TextView thread = (TextView) row.findViewById(R.id.thread);
            int count = conversation.messages.size();
            thread.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
            thread.setText(count > 99 ? "99+" : String.valueOf(count));
            int badge = color(unread ? R.color.focus_rule : R.color.text_muted);
            thread.setTextColor(badge);
            thread.setBackgroundTintList(ColorStateList.valueOf(badge));

            TextView snippet = (TextView) row.findViewById(R.id.snippet);
            snippet.setText(conversation.snippet());
            snippet.setTextColor(color(R.color.text_muted));
            snippet.setVisibility(conversation.snippet().length() == 0 ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    /**
     * Whom a conversation's monogram stands for: in Sent whom it went to,
     * else the latest sender who isn't the user, so a thread the user
     * answered last still shows who it is with.
     */
    private String person(Conversation conversation, String names, boolean sent) {
        if (sent && names.startsWith("To: ")) return names.substring(4);
        for (int i = conversation.messages.size() - 1; i >= 0; i--) {
            Message message = conversation.messages.get(i);
            if (message.draft()) continue;
            List<Address> from = message.from();
            if (me == null || from.isEmpty() || !me.is(from.get(0).email)) return message.sender();
        }
        Message latest = conversation.latest();
        return latest == null ? names : latest.sender();
    }

    /**
     * The folder pane's lines: a folder (icon, name, count) on a pill that
     * fills under the d-pad and is washed for the folder open now, or a
     * heading.
     */
    private final class FolderAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return entries.get(position).heading() ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int position) {
            return !entries.get(position).heading();
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Entry entry = entries.get(position);
            if (entry.heading()) {
                View row = convert != null ? convert : getLayoutInflater().inflate(R.layout.row_section, parent, false);
                ((TextView) row.findViewById(R.id.name)).setText(entry.name);
                return row;
            }
            View row = convert != null ? convert : getLayoutInflater().inflate(R.layout.row_folder, parent, false);
            boolean picked = position == drawerPick;
            boolean open = entry.folder != null && entry.folder.is(folder);
            // The wash is the row's own background: the ListView sets and clears rows' selected state itself.
            int left = row.getPaddingLeft(), right = row.getPaddingRight();
            row.setBackgroundResource(open ? R.drawable.drawer_open : R.drawable.drawer_item);
            row.setPadding(left, 0, right, 0);
            row.setActivated(picked);
            int ink = picked ? color(R.color.on_primary) : open ? color(R.color.focus_rule) : color(R.color.text);
            ImageView icon = (ImageView) row.findViewById(R.id.icon);
            icon.setImageResource(entry.icon);
            icon.setImageTintList(ColorStateList.valueOf(picked || open ? ink : color(R.color.icon)));
            TextView name = (TextView) row.findViewById(R.id.name);
            name.setText(entry.name);
            name.setTextColor(ink);
            name.setTypeface(picked || open ? Family.MEDIUM : Typeface.DEFAULT);
            TextView count = (TextView) row.findViewById(R.id.count);
            int n = entry.folder == null ? 0 : count(entry.folder);
            count.setText(n > 999 ? "999+" : String.valueOf(n));
            count.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            count.setTextColor(picked ? color(R.color.on_primary)
                : entry.folder != null && entry.folder.countsAll() ? color(R.color.text_muted) : color(R.color.focus_rule));
            return row;
        }
    }
}
