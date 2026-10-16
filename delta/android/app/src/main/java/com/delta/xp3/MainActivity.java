package com.delta.xp3;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.Manifest;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.delta.xp3.data.Attached;
import com.delta.xp3.data.Queued;
import com.delta.xp3.data.Chat;
import com.delta.xp3.data.Entry;
import com.delta.xp3.data.Folders;
import com.delta.xp3.data.RemoteFile;
import com.delta.xp3.data.Store;
import com.delta.xp3.data.Transcript;
import com.delta.xp3.net.Center;
import com.delta.xp3.net.Cloud;
import com.delta.xp3.net.Sse;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Tabs;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Delta on the phone: four tabs over the cloud sessions and the command
 * center.
 *
 * - Chats lists the agent sessions of every cloud account the command
 *   center keeps. OK opens one in Session; the first row starts a new one.
 * - Session is one conversation, and a box to add to it. The first message
 *   of a new session creates it, carrying the Delta setup ahead of what was
 *   typed. What is on screen is built from the session's event stream, which
 *   stays open while the app is visible, whichever tab is showing. When the
 *   session's account runs out of usage, a note and a button at the foot of
 *   the transcript offer to move the conversation to a new session on
 *   another account; a message sent instead waits until the limit lifts.
 * - Files lists what agents have pushed to the command center. OK downloads a
 *   file, then installs it if it is an APK or opens it if not. "Move to"
 *   puts the phone's copy in a shared folder such as Music.
 * - Accounts lists the cloud accounts the command center keeps, in the order
 *   new sessions try them, with how much of each one's 5-hour and weekly
 *   limits is used and when each resets.
 *
 * Nothing on this side of the app touches a network. Session requests run in
 * order on one worker, list fetches on another, downloads on a third, the
 * stream on its link's thread, and all of them come back through the main
 * thread's handler. Generation numbers stop anything that was in flight for a
 * session or a list that has since been left from landing in what replaced it.
 *
 * Pictures come in from the menu the way the phone's Messages app takes
 * them: the stock camera writing through a content:// address this app
 * serves (CaptureProvider), or the stock Gallery's picker. They wait above the
 * box, shrunk and saved, until the next message carries them.
 */
public final class MainActivity extends BaseActivity {

    private static final int CHATS = 0;
    private static final int SESSION = 1;
    private static final int FILES = 2;
    private static final int ACCOUNTS = 3;

    private static final int FOCUS_NONE = 0;
    private static final int FOCUS_LIST = 1;
    private static final int FOCUS_INPUT = 2;

    /** Menu item ids. The menu is rebuilt on every opening. */
    private static final int MENU_COPY = 1;
    private static final int MENU_ALLOW = 2;
    private static final int MENU_DENY = 3;
    private static final int MENU_INTERRUPT = 4;
    private static final int MENU_PASTE = 5;
    private static final int MENU_LINK = 6;
    private static final int MENU_NEW = 7;
    private static final int MENU_TAKE = 8;
    private static final int MENU_PICK = 9;
    private static final int MENU_REMOVE = 10;
    private static final int MENU_REFRESH = 11;
    private static final int MENU_ARCHIVE = 12;
    private static final int MENU_DOWNLOAD = 13;
    private static final int MENU_INSTALL = 14;
    private static final int MENU_OPEN = 15;
    private static final int MENU_FORGET = 16;
    private static final int MENU_DELETE = 17;
    private static final int MENU_CANCEL = 18;
    private static final int MENU_CENTER = 19;
    private static final int MENU_MOVE = 20;
    private static final int MENU_UPLOAD = 21;
    private static final int MENU_ATTACH = 22;
    private static final int MENU_SEND_FILE = 23;
    private static final int MENU_UNATTACH = 24;
    private static final int MENU_CANCEL_UPLOAD = 25;
    private static final int MENU_NEW_BACKUP = 26;

    /** Activity results. */
    private static final int TAKE_PICTURE = 1;
    private static final int PICK_PICTURE = 2;
    private static final int INSTALL = 3;

    /** Permission requests. */
    private static final int STORAGE = 1;
    private static final int STORAGE_UPLOAD = 2;

    /** What a file picked from the phone is for, while the storage permission is asked. */
    private static final int PICK_NONE = 0;
    private static final int PICK_UPLOAD = 1;
    private static final int PICK_ATTACH = 2;

    /** Files named in one message at most. */
    private static final int MAX_ATTACHED = 10;

    /**
     * The Installer helper (../installer). The XP3's own installer screen
     * refuses anything not signed by Sonim, so when the helper is on the phone
     * the APK goes to it instead; it installs as the device owner.
     */
    private static final ComponentName HELPER =
        new ComponentName("com.installer.xp3", "com.installer.xp3.InstallActivity");
    private static final String HELPER_MESSAGE = "message";

    private static final int MAX_TITLE = 60;
    private static final long SAVE_DELAY_MS = 1000;
    /** The first row of Chats, which is not a session. */

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService saves = Ui.worker("delta-save");
    private final ExecutorService requests = Ui.worker("delta-request");
    private final ExecutorService lists = Ui.worker("delta-list");
    private final ExecutorService downloads = Ui.worker("delta-download");
    private final ExecutorService picturesWork = Ui.worker("delta-pictures");
    private final ExecutorService moves = Ui.worker("delta-move");
    private final ExecutorService uploads = Ui.worker("delta-upload");

    private Backend backend;
    private Pictures pictures;
    /** Pictures still being shrunk; a message waits for them. */
    private int preparing;
    private FrameLayout content;
    private Tabs tabs;
    private int currentTab = CHATS;

    // ------------------------------------------------------------------
    // The open session
    // ------------------------------------------------------------------

    /** Null until the first message has created a session. */
    private String sessionId;
    private String title;
    private Transcript transcript;
    /** The first message is on its way and the session does not exist yet. */
    private boolean creating;
    /** Whether the new, unsent session is to be a backup one (▲) rather than a cloud one (●). */
    private boolean newBackup;
    /** Sends the waiting messages that are due, and redraws the foot of the transcript when a limit lifts. */
    private final Runnable tick = () -> sendDue();
    private Link link;
    private boolean online;
    /** Bumped whenever a different session is opened; anything older that comes back is dropped. */
    private int generation;
    private boolean savePending;

    // ------------------------------------------------------------------
    // Chats
    // ------------------------------------------------------------------

    private List<Chat> chats = new ArrayList<>();
    private boolean chatsLoading;
    private String chatsProblem;
    private int chatsGeneration;
    private ListView rowList;
    private RowAdapter rowAdapter;
    /** The row to put the highlight back on when a list is drawn again. */
    private String chatsKey;
    private String filesKey;

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private List<RemoteFile> files = new ArrayList<>();
    private boolean filesLoaded;
    private boolean filesLoading;
    private String filesProblem;
    private int filesGeneration;
    /** What is on the phone, by name, as it was on the command center when fetched. */
    private Map<String, RemoteFile> local = new LinkedHashMap<>();
    /** The download in flight, or null. */
    private Download running;
    /** The file handed to the installer, by name, and what the install was called. */
    private String installing;
    private String installLabel;
    private boolean installUpdate;
    /** The file being moved into a shared folder, by name, or null. */
    private String moving;
    /** The file whose Move to is waiting on the storage permission, by name. */
    private String pendingMove;
    /** The upload in flight, or null. */
    private Upload uploading;
    /** A PICK_ value while a pick from the phone waits on the storage permission. */
    private int pendingPick;
    /** Shows the upload's progress on whichever tab is drawn, or null. */
    private TextView uploadView;

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    private List<Backend.AccountUsage> accountUsage = new ArrayList<>();
    private boolean accountsLoaded;
    private boolean accountsLoading;
    private String accountsProblem;
    private int accountsGeneration;
    private String accountsKey;

    // ------------------------------------------------------------------
    // The session screen
    // ------------------------------------------------------------------

    private String draft = "";
    /** Names on the command center that go with the next message. */
    private ArrayList<String> attachedFiles = new ArrayList<>();
    private ChatListView chatList;
    private ChatAdapter chatAdapter;
    private TextView titleView;
    private TextView stateView;
    /** -1 means no restore; MAX_VALUE means the transcript was at its end. */
    private int savedScrollPosition = AdapterView.INVALID_POSITION;

    private Entry menuEntry;
    private RowAdapter.Row menuRow;
    private boolean okHeld;
    /** Whether the OK press now being released began on this screen. */
    private boolean okDown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        backend = new Backend(getFilesDir(), ShareProvider.folder(this));
        newBackup = defaultBackup();
        pictures = new Pictures(getFilesDir());
        int tab = CHATS;
        // The camera and the installer can cost this activity its life on a
        // phone this small.
        if (state != null) {
            draft = state.getString("draft", "");
            tab = state.getInt("tab", CHATS);
            installing = state.getString("installing");
            installLabel = state.getString("installLabel");
            installUpdate = state.getBoolean("installUpdate");
            pendingMove = state.getString("pendingMove");
            pendingPick = state.getInt("pendingPick", PICK_NONE);
            ArrayList<String> attached = state.getStringArrayList("attachedFiles");
            if (attached != null) attachedFiles = attached;
        }
        openSaved(backend.openSession());
        chats = backend.savedChats();
        local = backend.downloads();

        content = findViewById(R.id.content);
        tabs = new Tabs(this, which -> showTab(which, true), R.id.tab_chats, R.id.tab_session, R.id.tab_files, R.id.tab_accounts);
        // Every context menu is answered here; see showMenu.
        registerForContextMenu(content);
        showTab(tab, true);
        prune();
    }

    @Override
    protected void onStart() {
        super.onStart();
        connect();
        // Whether a session can move to another account is decided on the
        // main thread, from the accounts already listed.
        if (backend.ready()) {
            lists.execute(() -> {
                backend.warm();
                main.post(() -> updateChat());
            });
        }
        sendDue();
        // Coming back is the moment to ask again: a phone like this is put
        // away, not quit, and the agent has usually been busy meanwhile.
        if (currentTab == CHATS) refreshChats();
        else if (currentTab == FILES) refreshFiles();
        else if (currentTab == ACCOUNTS) refreshAccounts();
    }

    @Override
    protected void onStop() {
        disconnect();
        saveNow();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("draft", draft);
        state.putInt("tab", currentTab);
        state.putString("installing", installing);
        state.putString("installLabel", installLabel);
        state.putBoolean("installUpdate", installUpdate);
        state.putString("pendingMove", pendingMove);
        state.putInt("pendingPick", pendingPick);
        state.putStringArrayList("attachedFiles", attachedFiles);
    }

    @Override
    protected void onPause() {
        if (currentTab == SESSION) saveScrollPosition();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(tick);
        disconnect();
        if (running != null) running.cancelled = true;
        if (uploading != null) uploading.cancelled = true;
        // Let the last save land; a request in flight is not worth holding
        // the process for once the screen is gone.
        saves.shutdown();
        picturesWork.shutdown();
        moves.shutdown();
        requests.shutdownNow();
        lists.shutdownNow();
        downloads.shutdownNow();
        uploads.shutdownNow();
    }

    /** Forgets old transcripts, and the pictures only they showed. */
    private void prune() {
        final String open = sessionId;
        saves.execute(() -> {
            final Set<String> shown = backend.prune(open);
            picturesWork.execute(() -> pictures.retain(shown));
        });
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private void showTab(int tab, boolean focusContent) {
        if (currentTab == SESSION && tab != SESSION) saveScrollPosition();
        leaveScreens();
        currentTab = tab;
        tabs.mark(tab);
        if (tab == CHATS) {
            renderChats(focusContent);
            refreshChats();
        } else if (tab == SESSION) {
            render(focusContent ? FOCUS_INPUT : FOCUS_NONE);
        } else if (tab == FILES) {
            renderFiles(focusContent);
            if (!filesLoaded) refreshFiles();
        } else {
            renderAccounts(focusContent);
            refreshAccounts();
        }
    }

    /** Drops the views of whichever tab is going, so nothing updates them off screen. */
    private void leaveScreens() {
        if (rowAdapter != null && rowList != null && rowList.hasFocus()) {
            RowAdapter.Row row = rowAdapter.selected();
            if (row != null && currentTab == CHATS) chatsKey = row.key;
            if (row != null && currentTab == FILES) filesKey = row.key;
            if (row != null && currentTab == ACCOUNTS) accountsKey = row.key;
        }
        chatList = null;
        chatAdapter = null;
        titleView = null;
        stateView = null;
        rowList = null;
        rowAdapter = null;
        uploadView = null;
    }

    // ------------------------------------------------------------------
    // Chats
    // ------------------------------------------------------------------

    private void refreshChats() {
        if (!anySessions() || chatsLoading) return;
        chatsLoading = true;
        final int forGeneration = ++chatsGeneration;
        if (currentTab == CHATS) updateChatRows();
        lists.execute(() -> {
            List<Chat> fetched = null;
            String problem = null;
            try {
                fetched = backend.sessions();
                String left = backend.listProblem();
                if (left != null) problem = "Some sessions are missing. " + left;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                problem = "Couldn't refresh: " + reason(e);
            }
            final List<Chat> result = fetched;
            final String said = problem;
            main.post(() -> chatsFetched(result, said, forGeneration));
        });
    }

    private void chatsFetched(List<Chat> fetched, String problem, int forGeneration) {
        if (forGeneration != chatsGeneration || isFinishing()) return;
        chatsLoading = false;
        chatsProblem = problem;
        if (fetched != null) {
            chats = fetched;
            final List<Chat> snapshot = new ArrayList<>(fetched);
            saves.execute(() -> backend.saveChats(snapshot));
            // A session is titled by the cloud after its first turn, or renamed
            // on the web; the open one follows.
            for (Chat chat : fetched) {
                if (chat.id.equals(sessionId) && !chat.title.equals(title)) {
                    title = chat.title;
                    scheduleSave();
                    updateHeader();
                }
            }
        }
        if (currentTab == CHATS) updateChatRows();
    }

    private void renderChats(boolean focusContent) {
        if (!anySessions()) {
            Ui.fill(content, emptyMessage(Backend.NO_LOGIN));
            return;
        }
        LinearLayout root = vertical();
        TextView notice = notice();
        root.addView(notice);
        final RowAdapter adapter = new RowAdapter(this);
        final ListView list = listView(adapter);
        root.addView(list, Ui.weighted());
        rowList = list;
        rowAdapter = adapter;
        wireRows(list, adapter);
        Ui.fill(content, root);
        updateChatRows();
        int position = adapter.positionOf(chatsKey != null ? chatsKey : sessionId);
        select(list, adapter, position < 0 ? 0 : position);
        if (focusContent) list.requestFocus();
    }

    /** Redraws the rows in place, keeping the highlight on the same session. */
    private void updateChatRows() {
        if (currentTab != CHATS || rowAdapter == null) return;
        RowAdapter.Row was = rowAdapter.selected();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Chat chat : chats) {
            String when = when(chat.updated);
            boolean open = chat.id.equals(sessionId);
            String sub = open ? (when.length() == 0 ? "Open" : "Open · " + when) : when;
            rows.add(new RowAdapter.Row(chat.id, chat.label(), sub, chatState(chat)));
        }
        rowAdapter.replace(rows);
        if (was != null) {
            int position = rowAdapter.positionOf(was.key);
            if (position >= 0 && position != rowAdapter.getSelectedPosition()) select(rowList, rowAdapter, position);
        }
        View parent = (View) rowList.getParent();
        TextView notice = parent == null ? null : (TextView) ((ViewGroup) parent).getChildAt(0);
        if (notice != null) {
            String said = chatsProblem != null ? chatsProblem : chatsLoading && chats.isEmpty() ? "Loading…"
                : chats.isEmpty() ? "No sessions yet." : null;
            notice.setText(said);
            notice.setVisibility(said == null ? View.GONE : View.VISIBLE);
        }
    }

    /** What a session is doing, in a word; the open one by what its stream says. */
    private String chatState(Chat chat) {
        if (chat.id.equals(sessionId) && online) {
            if (transcript.pending() != null) return "Waiting";
            return transcript.working() ? "Working" : null;
        }
        if (Chat.WORKING.equals(chat.status)) return "Working";
        if (Chat.WAITING.equals(chat.status)) return "Waiting";
        return null;
    }

    private void activateChat(RowAdapter.Row row) {
        for (Chat chat : chats) {
            if (chat.id.equals(row.key)) {
                openChat(chat);
                return;
            }
        }
    }

    /** Opens a session from the list in Session, where it carries on from what is saved of it. */
    private void openChat(Chat chat) {
        if (!chat.id.equals(sessionId)) {
            if (creating) {
                toast("Still starting the session");
                return;
            }
            saveNow();
            switchTo(chat.id);
            if (title == null) title = chat.title;
            saveNow();
            connect();
        }
        showTab(SESSION, true);
    }

    /** A cloud session is archived; a backup one is deleted, with its computer and files. */
    private void confirmArchive(final Chat chat) {
        boolean backup = Chat.backup(chat.id);
        String verb = backup ? "Delete" : "Archive";
        String message = backup
            ? "Delete \"" + chat.title + "\"? Its computer and everything on it go too."
            : "Archive \"" + chat.title + "\"?";
        new AlertDialog.Builder(this)
            .setTitle(verb)
            .setMessage(message)
            .setPositiveButton(verb, (dialog, which) -> archive(chat))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void archive(final Chat chat) {
        lists.execute(() -> {
            String problem = null;
            try {
                backend.archive(chat.id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                problem = reason(e);
            }
            final String said = problem;
            main.post(() -> {
                if (isFinishing()) return;
                boolean backup = Chat.backup(chat.id);
                if (said != null) {
                    toast((backup ? "Not deleted: " : "Not archived: ") + said);
                    return;
                }
                toast(backup ? "Deleted" : "Archived");
                List<Chat> left = new ArrayList<>();
                for (Chat other : chats) if (!other.id.equals(chat.id)) left.add(other);
                chats = left;
                if (chat.id.equals(sessionId)) newSession(defaultBackup());
                if (currentTab == CHATS) updateChatRows();
            });
        });
    }

    private Chat chatFor(RowAdapter.Row row) {
        if (row == null) return null;
        for (Chat chat : chats) if (chat.id.equals(row.key)) return chat;
        return null;
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    /** Asks the command center for every account's usage again. */
    private void refreshAccounts() {
        if (!backend.ready() || accountsLoading) return;
        accountsLoading = true;
        final int forGeneration = ++accountsGeneration;
        if (currentTab == ACCOUNTS) updateAccountRows();
        lists.execute(() -> {
            List<Backend.AccountUsage> fetched = null;
            String problem = null;
            try {
                fetched = backend.usage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                problem = "Couldn't refresh: " + reason(e);
            }
            final List<Backend.AccountUsage> result = fetched;
            final String said = problem;
            main.post(() -> {
                if (forGeneration != accountsGeneration || isFinishing()) return;
                accountsLoading = false;
                accountsProblem = said;
                if (result != null) {
                    accountUsage = result;
                    accountsLoaded = true;
                }
                if (currentTab == ACCOUNTS) updateAccountRows();
                // What was learnt may change what a session can do.
                updateChat();
                scheduleTick();
            });
        });
    }

    private void renderAccounts(boolean focusContent) {
        if (!backend.ready()) {
            Ui.fill(content, emptyMessage(Backend.NO_LOGIN));
            return;
        }
        LinearLayout root = vertical();
        root.addView(notice());
        final RowAdapter adapter = new RowAdapter(this);
        final ListView list = listView(adapter);
        root.addView(list, Ui.weighted());
        rowList = list;
        rowAdapter = adapter;
        wireRows(list, adapter);
        Ui.fill(content, root);
        updateAccountRows();
        int position = adapter.positionOf(accountsKey);
        select(list, adapter, position < 0 ? 0 : position);
        if (focusContent) list.requestFocus();
    }

    /**
     * One row per account, in the order new sessions try them: its address,
     * its 5-hour and weekly use with when each resets, and on the right its
     * plan, or "Out" while it has no room.
     */
    private void updateAccountRows() {
        if (currentTab != ACCOUNTS || rowAdapter == null) return;
        RowAdapter.Row was = rowAdapter.selected();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Backend.AccountUsage entry : accountUsage) {
            Center.Account account = entry.account;
            String sub;
            String extra;
            if (entry.usage == null) {
                sub = "Usage unavailable";
                extra = entry.problem;
            } else {
                sub = window("5-hour", entry.usage.fiveHour, entry.usage.fiveHourResets);
                extra = window("Weekly", entry.usage.weekly, entry.usage.weeklyResets);
            }
            boolean out = backend.accountLimit(account.id) > 0
                || (entry.usage != null && entry.usage.limitedUntil() > System.currentTimeMillis());
            rows.add(new RowAdapter.Row(account.id, account.email, sub, out ? "Out" : plan(account.plan), extra));
        }
        rowAdapter.replace(rows);
        if (was != null) {
            int position = rowAdapter.positionOf(was.key);
            if (position >= 0 && position != rowAdapter.getSelectedPosition()) select(rowList, rowAdapter, position);
        }
        View parent = (View) rowList.getParent();
        TextView notice = parent == null ? null : (TextView) ((ViewGroup) parent).getChildAt(0);
        if (notice != null) {
            String said = accountsProblem != null ? accountsProblem
                : accountsLoading && !accountsLoaded ? "Loading…"
                : accountsLoaded && accountUsage.isEmpty() ? Backend.NO_ACCOUNTS : null;
            notice.setText(said);
            notice.setVisibility(said == null ? View.GONE : View.VISIBLE);
        }
    }

    /** "5-hour: 42% · resets 3:05 PM". */
    private static String window(String name, Double used, long resets) {
        if (used == null) return name + ": no data";
        String said = name + ": " + Math.round(used.doubleValue()) + "%";
        if (resets > 0) said += " · resets " + Backend.clock(resets);
        return said;
    }

    /** "claude_pro" as "Pro". */
    private static String plan(String plan) {
        if (plan == null) return "";
        String name = plan.startsWith("claude_") ? plan.substring("claude_".length()) : plan;
        if (name.length() == 0) return "";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ------------------------------------------------------------------
    // Switching sessions
    // ------------------------------------------------------------------

    /** Loads a saved session, or a new one for null, without touching the screen. */
    private void openSaved(String id) {
        Store.Saved saved = backend.session(id);
        sessionId = saved.id;
        title = saved.title;
        transcript = saved.transcript;
    }

    /** Leaves the open session for another, or for a new one when `id` is null. */
    private void switchTo(String id) {
        generation++;
        disconnect();
        creating = false;
        savedScrollPosition = AdapterView.INVALID_POSITION;
        openSaved(id);
    }

    // ------------------------------------------------------------------
    // The stream
    // ------------------------------------------------------------------

    private void connect() {
        if (link != null || sessionId == null) return;
        final int forGeneration = generation;
        link = backend.link(sessionId, transcript.sequence(), new Link.Listener() {
            @Override
            public void frame(final Sse.Frame frame) {
                main.post(() -> received(frame, forGeneration));
            }

            @Override
            public void online(final boolean up) {
                main.post(() -> {
                    if (forGeneration != generation) return;
                    online = up;
                    updateHeader();
                    updateChatRows();
                });
            }
        });
        if (link != null) link.start();
        updateHeader();
        scheduleTick();
    }

    private void disconnect() {
        if (link == null) return;
        link.stop();
        link = null;
        online = false;
        updateHeader();
    }

    private void received(Sse.Frame frame, int forGeneration) {
        if (forGeneration != generation || isFinishing()) return;
        long before = transcript.sequence();
        boolean wasWorking = transcript.working();
        boolean changed = transcript.apply(frame.event, frame.id, frame.data);
        if (backend.limited(sessionId, transcript.limitedUntil())) {
            saves.execute(() -> backend.saveLimits());
            // The foot of the transcript now says so, and offers the move.
            changed = true;
            scheduleTick();
        }
        if (changed) {
            updateChat();
            updateHeader();
            if (wasWorking != transcript.working()) updateChatRows();
        }
        if (transcript.sequence() != before) scheduleSave();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    private void send(String text) {
        final String message = text.trim();
        if (message.length() == 0 && pictures.draft().isEmpty() && attachedFiles.isEmpty()) return;
        if (preparing > 0) {
            toast("Still adding the picture");
            return;
        }
        if (uploading != null && uploading.attach) {
            toast("Still sending " + uploading.file.getName());
            return;
        }
        final boolean backup = backupNow();
        if (backup ? !backend.centerReady() : !backend.ready()) {
            transcript.note(backup ? Backend.NO_CENTER : Backend.NO_LOGIN);
            render(FOCUS_INPUT);
            return;
        }
        if (creating) {
            toast("Still starting the session");
            return;
        }
        final String uuid = UUID.randomUUID().toString();
        final int forGeneration = generation;
        final List<File> attached = pictures.commit(uuid);
        final List<String> sentFiles = new ArrayList<>(attachedFiles);
        draft = "";
        attachedFiles.clear();
        // Sent to a session whose account is out of usage, it would only run
        // into the same limit; it waits on the phone until the limit lifts.
        if (sessionId != null && backend.limitedUntil(sessionId) > 0) {
            transcript.queued(uuid, Attached.line(message, sentFiles), attached.size());
            backend.enqueue(sessionId, uuid, message, sentFiles, attached.size());
            saves.execute(() -> backend.saveQueue());
            scheduleTick();
            saveNow();
            render(FOCUS_INPUT);
            return;
        }
        transcript.sent(uuid, Attached.line(message, sentFiles), attached.size());
        if (sessionId == null) {
            creating = true;
            title = message.length() > 0 ? titleFor(message)
                : !attached.isEmpty() ? (attached.size() == 1 ? "Picture" : "Pictures")
                : titleFor(sentFiles.get(0));
            final String sessionTitle = title;
            requests.execute(() -> {
                try {
                    final String id = backend.create(backup, sessionTitle, uuid, message, attached, sentFiles);
                    main.post(() -> created(id, forGeneration));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failed("Could not start the session: " + reason(e), forGeneration);
                }
            });
        } else {
            final String session = sessionId;
            requests.execute(() -> {
                try {
                    backend.send(session, uuid, message, attached, sentFiles);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failed("Not sent: " + reason(e), forGeneration);
                }
            });
        }
        saveNow();
        render(FOCUS_INPUT);
    }

    private void created(String id, int forGeneration) {
        if (forGeneration != generation || isFinishing()) return;
        creating = false;
        sessionId = id;
        saveNow();
        connect();
        // In the list straight away, before the cloud has been asked again.
        List<Chat> next = new ArrayList<>();
        next.add(new Chat(id, title, Chat.WORKING, System.currentTimeMillis()));
        for (Chat chat : chats) if (!chat.id.equals(id)) next.add(chat);
        chats = next;
        updateChatRows();
    }

    /** Called on the request worker. */
    private void failed(final String problem, final int forGeneration) {
        main.post(() -> {
            if (forGeneration != generation || isFinishing()) return;
            if (creating) {
                creating = false;
                title = null;
            }
            transcript.note(problem);
            transcript.idle();
            saveNow();
            updateChat();
            updateHeader();
        });
    }

    // ------------------------------------------------------------------
    // Usage limits
    // ------------------------------------------------------------------

    /**
     * Moves the open session's conversation, whose account is out of usage,
     * to a new session on an account with room: the whole conversation, from
     * the cloud's own history of it, and any messages that were waiting for
     * the limit to lift. The old session stays in Chats. If the move fails,
     * the old session comes back with its messages still waiting.
     */
    private void moveSession() {
        if (sessionId == null || creating) return;
        if (!backend.canMove(sessionId)) {
            toast("No other account has room right now");
            return;
        }
        final String from = sessionId;
        final String was = title;
        final List<Queued> waiting = backend.takeQueued(from);
        saves.execute(() -> backend.saveQueue());
        StringBuilder typed = new StringBuilder();
        final List<String> names = new ArrayList<>();
        final List<File> sentPictures = new ArrayList<>();
        for (Queued message : waiting) {
            if (message.text.trim().length() > 0) {
                if (typed.length() > 0) typed.append("\n\n");
                typed.append(message.text.trim());
            }
            names.addAll(message.files);
            for (int i = 0; i < message.pictures; i++) {
                File picture = pictures.sent(message.uuid, i);
                if (picture.isFile()) sentPictures.add(picture);
            }
        }
        final String text = typed.toString();
        saveNow();
        switchTo(null);
        title = was;
        creating = true;
        final String uuid = UUID.randomUUID().toString();
        final int forGeneration = generation;
        transcript.carried(uuid);
        if (text.length() > 0 || !names.isEmpty()) transcript.queued(uuid, Attached.line(text, names), 0);
        render(FOCUS_INPUT);
        requests.execute(() -> {
            try {
                final String id = backend.move(from, was, uuid, text, sentPictures, names);
                main.post(() -> created(id, forGeneration));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                final String problem = reason(e);
                main.post(() -> notMoved(from, waiting, problem, forGeneration));
            }
        });
    }

    private void notMoved(String from, List<Queued> waiting, String problem, int forGeneration) {
        backend.requeue(waiting);
        saves.execute(() -> backend.saveQueue());
        if (forGeneration != generation || isFinishing()) return;
        switchTo(from);
        connect();
        toast("Not moved: " + problem);
        if (currentTab == SESSION) render(FOCUS_LIST);
    }

    /**
     * Sends the waiting messages that are due and arranges the next tick. A
     * send that fails is tried again a minute later, up to ten times.
     */
    private void sendDue() {
        main.removeCallbacks(tick);
        if (isFinishing()) return;
        final List<Queued> ready = backend.due();
        if (!ready.isEmpty()) {
            saves.execute(() -> backend.saveQueue());
        }
        for (final Queued message : ready) {
            if (message.session.equals(sessionId)) transcript.resumed();
            requests.execute(() -> {
                List<File> attached = new ArrayList<>();
                for (int i = 0; i < message.pictures; i++) {
                    File picture = pictures.sent(message.uuid, i);
                    if (picture.isFile()) attached.add(picture);
                }
                String problem = null;
                try {
                    backend.send(message.session, message.uuid, message.text, attached, message.files);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    problem = reason(e);
                }
                final String said = problem;
                main.post(() -> {
                    if (said == null) return;
                    boolean again = message.tries < 9;
                    if (again) {
                        backend.retry(message);
                        saves.execute(() -> backend.saveQueue());
                        scheduleTick();
                    }
                    if (message.session.equals(sessionId)) {
                        transcript.idle();
                        transcript.note((again ? "Not sent yet, trying again in a minute: " : "Not sent: ") + said);
                        scheduleSave();
                        updateChat();
                        updateHeader();
                    }
                });
            });
        }
        updateChat();
        updateHeader();
        scheduleTick();
    }

    /** Wakes up for the next waiting message, or when the open session's limit lifts, whichever is first. */
    private void scheduleTick() {
        main.removeCallbacks(tick);
        long next = backend.nextDue();
        long lifts = sessionId == null ? 0 : backend.limitedUntil(sessionId);
        if (lifts > 0 && (next == 0 || lifts < next)) next = lifts;
        if (next == 0) return;
        main.postDelayed(tick, Math.max(1000L, next - System.currentTimeMillis() + 500L));
    }

    /** What the foot of the transcript says about the open session's limit, or null. */
    private String limitNote() {
        if (sessionId == null) return null;
        long until = backend.limitedUntil(sessionId);
        if (until <= 0) return null;
        String when = Backend.when(until).replaceFirst("^at ", "until ").replaceFirst("^on ", "until ");
        String said = "This account is out of usage " + when + ".";
        int waiting = backend.queuedFor(sessionId);
        if (waiting > 0) {
            said += waiting == 1 ? " Your message will be sent then." : " Your messages will be sent then.";
        } else {
            said += " A message sent now waits until then.";
        }
        if (backend.canMove(sessionId)) said += " Or move the conversation to another account:";
        return said;
    }

    /** The button at the foot of the transcript, or null. */
    private String limitAction() {
        if (sessionId == null || creating || !backend.canMove(sessionId)) return null;
        return "Move to a new session";
    }

    /** The first line of the first message, which is what the cloud titles a session with too. */
    private static String titleFor(String message) {
        String line = message.split("\n", 2)[0].trim();
        return line.length() <= MAX_TITLE ? line : line.substring(0, MAX_TITLE - 1) + "…";
    }

    private void interrupt() {
        if (sessionId == null) return;
        final String session = sessionId;
        requests.execute(() -> {
            String problem = null;
            try {
                backend.interrupt(session);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                problem = reason(e);
            }
            final String said = problem == null ? "Interrupt sent" : "Interrupt not sent: " + problem;
            main.post(() -> toast(said));
        });
    }

    /**
     * Answers the permission request the agent is waiting on. Auto mode settles
     * most calls itself; this is for the ones it still asks a person about.
     */
    private void answer(final boolean allow) {
        final Transcript.Permission pending = transcript.pending();
        if (pending == null || sessionId == null) return;
        final String session = sessionId;
        final int forGeneration = generation;
        transcript.answered();
        transcript.note((allow ? "Allowed " : "Denied ") + pending.tool + ".");
        scheduleSave();
        updateChat();
        updateHeader();
        requests.execute(() -> {
            try {
                backend.answer(session, pending.requestId, allow, pending.input);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                failed("Answer not sent: " + reason(e), forGeneration);
            }
        });
    }

    /**
     * A new session, not yet started, of the kind asked for: a backup one
     * (▲) or a cloud one (●). The one left stays in Chats.
     */
    private void newSession(boolean backup) {
        if (sessionId == null && !creating) {
            newBackup = backup;
            updateHeader();
            return;
        }
        saveNow();
        switchTo(null);
        newBackup = backup;
        saveNow();
        if (currentTab == SESSION) render(FOCUS_INPUT);
    }

    /** Whether there are sessions to list: cloud ones, backup ones, or both. */
    private boolean anySessions() {
        return backend.ready() || backend.centerReady();
    }

    /** A new session is a cloud one, unless this build can only start backup ones. */
    private boolean defaultBackup() {
        return !backend.ready() && backend.centerReady();
    }

    /** Whether the open session (or the new one about to start) is a backup one. */
    private boolean backupNow() {
        return sessionId != null ? Chat.backup(sessionId) : newBackup;
    }

    /** The menu's "New session" items, for each kind this build can start. */
    private void addNewSessionItems() {
        if (backend.ready()) add(MENU_NEW, "New session " + Chat.MARK);
        if (backend.centerReady()) add(MENU_NEW_BACKUP, "New session " + Chat.BACKUP_MARK);
    }

    // ------------------------------------------------------------------
    // Pictures
    // ------------------------------------------------------------------

    /**
     * The stock camera, told to write its full-size shot to CaptureProvider,
     * as Messages' "Capture picture" does. The grant is for this one intent.
     */
    private void takePicture() {
        File target = Pictures.capture(getFilesDir());
        target.delete();
        Uri output = CaptureProvider.uri();
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, output);
        intent.setClipData(ClipData.newRawUri("", output));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launch(intent, TAKE_PICTURE, "No camera app on this phone");
    }

    /**
     * The stock Gallery's picker, as Messages' "Attach photos/videos" opens
     * it, or whatever picks images when that Gallery is not installed.
     */
    private void pickPicture() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        Intent gallery = new Intent(intent)
            .setComponent(new ComponentName("com.android.gallery3d", "com.android.gallery3d.app.Gallery"))
            .putExtra("multipick", true);
        if (gallery.resolveActivity(getPackageManager()) != null) {
            launch(gallery, PICK_PICTURE, "No gallery on this phone");
        } else {
            launch(Intent.createChooser(intent, "Attach picture"), PICK_PICTURE, "No gallery on this phone");
        }
    }

    private void launch(Intent intent, int request, String missing) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(missing);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL) {
            installed(result == RESULT_OK,
                data == null ? null : data.getStringExtra(HELPER_MESSAGE));
            return;
        }
        if (result != RESULT_OK) return;
        if (request == TAKE_PICTURE) {
            final File shot = Pictures.capture(getFilesDir());
            if (shot.isFile() && shot.length() > 0) {
                prepare(() -> new FileInputStream(shot), shot);
                return;
            }
            // A camera that ignored EXTRA_OUTPUT hands back only a thumbnail.
            Object thumbnail = data == null || data.getExtras() == null ? null : data.getExtras().get("data");
            if (thumbnail instanceof Bitmap) prepareBitmap((Bitmap) thumbnail);
            else toast("The camera did not return a picture");
        } else if (request == PICK_PICTURE && data != null) {
            List<Uri> chosen = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    if (clip.getItemAt(i).getUri() != null) chosen.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                chosen.add(data.getData());
            }
            int room = Pictures.MAX_DRAFT - pictures.draft().size() - preparing;
            if (chosen.size() > room) toast("Only " + Pictures.MAX_DRAFT + " pictures fit in one message");
            for (int i = 0; i < chosen.size() && i < room; i++) {
                final Uri uri = chosen.get(i);
                prepare(() -> {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("That picture could not be opened");
                    return in;
                }, null);
            }
        }
    }

    /** Shrinks a picture off the main thread and adds it to the draft. `consumed` is deleted after. */
    private void prepare(final Pictures.Source source, final File consumed) {
        preparing++;
        picturesWork.execute(() -> {
            String problem = null;
            try {
                pictures.addToDraft(Pictures.prepare(source));
            } catch (IOException e) {
                problem = reason(e);
            } catch (SecurityException e) {
                problem = "That picture could not be opened";
            } finally {
                if (consumed != null) consumed.delete();
            }
            prepared(problem);
        });
        toast("Adding picture…");
    }

    private void prepareBitmap(final Bitmap bitmap) {
        preparing++;
        picturesWork.execute(() -> {
            String problem = null;
            try {
                pictures.addToDraft(Pictures.prepare(bitmap));
            } catch (IOException e) {
                problem = reason(e);
            }
            prepared(problem);
        });
    }

    /** Called on the pictures worker. */
    private void prepared(final String problem) {
        main.post(() -> {
            preparing--;
            if (isFinishing()) return;
            if (problem != null) toast(problem);
            if (currentTab != SESSION) return;
            saveScrollPosition();
            render(FOCUS_INPUT);
        });
    }

    private void removePictures() {
        preparing++;
        picturesWork.execute(() -> {
            pictures.clearDraft();
            prepared(null);
        });
    }

    /**
     * The pictures waiting for the next message, as a row of small thumbnails
     * above the box. Not focusable: Menu is how they are removed.
     */
    private View draftStrip() {
        List<File> waiting = pictures.draft();
        if (waiting.isEmpty()) return null;
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(6), dp(4), dp(6), 0);
        strip.setBackgroundColor(color(R.color.chat_page));
        for (File file : waiting) {
            Bitmap bitmap = pictures.thumbnail(file, dp(64));
            if (bitmap == null) continue;
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(36));
            params.rightMargin = dp(4);
            strip.addView(image, params);
        }
        TextView label = new TextView(this);
        label.setText(waiting.size() == 1 ? "1 picture" : waiting.size() + " pictures");
        label.setTextSize(11);
        label.setTextColor(color(R.color.text_muted));
        label.setPadding(dp(2), 0, 0, 0);
        strip.addView(label);
        return strip;
    }

    // ------------------------------------------------------------------
    // Files sent with a message
    // ------------------------------------------------------------------

    /**
     * The files going with the next message, and the one still being sent
     * to the command center for it, as a line above the box. Not focusable:
     * Menu is how they are removed.
     */
    private View filesStrip() {
        boolean sending = uploading != null && uploading.attach;
        if (attachedFiles.isEmpty() && !sending) return null;
        LinearLayout strip = vertical();
        strip.setPadding(dp(6), dp(4), dp(6), 0);
        strip.setBackgroundColor(color(R.color.chat_page));
        if (!attachedFiles.isEmpty()) {
            TextView names = stripLine();
            names.setText(Attached.line("", attachedFiles));
            strip.addView(names);
        }
        if (sending) {
            TextView progress = stripLine();
            progress.setText(uploadLine());
            strip.addView(progress);
            uploadView = progress;
        }
        return strip;
    }

    private TextView stripLine() {
        TextView line = new TextView(this);
        line.setTextSize(11);
        line.setTextColor(color(R.color.text_muted));
        line.setMaxLines(2);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return line;
    }

    /** A file on the command center goes with the next message. */
    private void attach(String name) {
        if (!attachedFiles.contains(name)) {
            if (attachedFiles.size() >= MAX_ATTACHED) {
                toast("Only " + MAX_ATTACHED + " files fit in one message");
                return;
            }
            attachedFiles.add(name);
        }
        toast(name + " goes with your next message");
    }

    private void removeFiles() {
        attachedFiles.clear();
        if (currentTab != SESSION) return;
        saveScrollPosition();
        render(FOCUS_INPUT);
    }

    /**
     * Picks a file from the phone's shared storage and sends it to the
     * command center; with PICK_ATTACH it then goes with the next message.
     * Storage access comes first, as for Move to.
     */
    private void chooseUpload(int purpose) {
        if (!backend.centerReady()) {
            toast(Backend.NO_CENTER);
            return;
        }
        if (uploading != null) {
            toast("Wait for " + uploading.file.getName() + " first");
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPick = purpose;
            requestPermissions(new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_UPLOAD);
            return;
        }
        pickFile(purpose, "");
    }

    /**
     * One folder of shared storage, by its path there ("" is the top): the
     * folders inside it, then its files. Picking a file sends it. Back goes
     * up a level, and from the top closes.
     */
    private void pickFile(final int purpose, final String path) {
        File folder = path.length() == 0 ? Environment.getExternalStorageDirectory() : shared(path);
        final List<String> inside = Folders.subfolders(folder);
        final List<String> found = Folders.files(folder);
        List<String> items = new ArrayList<>();
        for (String each : inside) items.add(each + "/");
        for (String each : found) items.add(each);
        if (items.isEmpty()) items.add("(Nothing here)");
        String title = path.length() == 0 ? (purpose == PICK_ATTACH ? "Attach file" : "Upload") : path;
        listDialog(title, items, which -> {
            if (which < inside.size()) {
                String next = inside.get(which);
                pickFile(purpose, path.length() == 0 ? next : path + "/" + next);
            } else if (which - inside.size() < found.size()) {
                upload(new File(folder, found.get(which - inside.size())), purpose == PICK_ATTACH);
            } else {
                pickFile(purpose, path);
            }
        }, () -> {
            int slash = path.lastIndexOf('/');
            if (path.length() > 0) pickFile(purpose, slash < 0 ? "" : path.substring(0, slash));
        });
    }

    private void upload(File file, boolean attach) {
        if (uploading != null) {
            toast("Wait for " + uploading.file.getName() + " first");
            return;
        }
        if (attach && attachedFiles.size() >= MAX_ATTACHED) {
            toast("Only " + MAX_ATTACHED + " files fit in one message");
            return;
        }
        uploading = new Upload(file, attach);
        keepAwake();
        uploads.execute(uploading);
        redrawForUpload();
    }

    /** Called on the main thread once `upload` has ended, however it ended. */
    private void uploaded(Upload upload, String name, String problem) {
        if (upload != uploading) return;
        uploading = null;
        keepAwake();
        if (problem != null) {
            toast("Not sent: " + problem);
        } else {
            List<RemoteFile> next = new ArrayList<>();
            next.add(new RemoteFile(name, upload.file.length(), System.currentTimeMillis()));
            for (RemoteFile other : files) if (!other.name.equals(name)) next.add(other);
            files = next;
            filesLoaded = true;
            if (upload.attach) {
                attach(name);
            } else {
                toast(name.equals(upload.file.getName()) ? "Sent " + name : "Sent as " + name);
            }
            refreshFiles();
        }
        redrawForUpload();
    }

    private void cancelUpload() {
        if (uploading != null) uploading.cancelled = true;
    }

    /** Draws the tab again, so an upload's line appears or goes. */
    private void redrawForUpload() {
        if (isFinishing()) return;
        if (currentTab == FILES) {
            renderFilesKeepingFocus();
        } else if (currentTab == SESSION) {
            boolean listFocused = chatList != null && chatList.hasFocus();
            saveScrollPosition();
            render(listFocused ? FOCUS_LIST : FOCUS_INPUT);
        }
    }

    private String uploadLine() {
        if (uploading == null) return "";
        String said = "Sending " + uploading.file.getName() + "…";
        if (uploading.total > 0) said += " " + (int) (uploading.done * 100 / uploading.total) + "%";
        return said;
    }

    /** A long transfer outlasts the screen timeout; the screen stays on while one runs. */
    private void keepAwake() {
        if (running != null || uploading != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** One upload, on the uploads worker; reports back through the main handler. */
    private final class Upload implements Runnable, Center.Progress {
        final File file;
        /** Whether it goes with the next message once it is there. */
        final boolean attach;
        volatile boolean cancelled;
        /** Read on the main thread only. */
        long done;
        long total = -1;
        private long lastUpdate;

        Upload(File file, boolean attach) {
            this.file = file;
            this.attach = attach;
        }

        @Override
        public void run() {
            String name = null;
            String problem = null;
            try {
                name = backend.upload(file, this);
            } catch (Center.Cancelled e) {
                problem = "Cancelled";
            } catch (InterruptedException e) {
                problem = "Cancelled";
            } catch (IOException e) {
                problem = reason(e);
            } catch (RuntimeException e) {
                problem = "The upload failed.";
            }
            final String sentAs = name;
            final String said = problem;
            main.post(() -> uploaded(Upload.this, sentAs, said));
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void progress(final long now, final long size) {
            long at = SystemClock.uptimeMillis();
            if (now > 0 && now < size && at - lastUpdate < 250) return;
            lastUpdate = at;
            main.post(() -> {
                if (uploading != Upload.this) return;
                done = now;
                total = size;
                if (uploadView != null) uploadView.setText(uploadLine());
            });
        }
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /**
     * Writes the session down after a burst of frames has settled, rather than
     * once per frame: a reconnect can replay hundreds of them at once.
     */
    private void scheduleSave() {
        if (savePending) return;
        savePending = true;
        main.postDelayed(() -> {
            if (savePending) saveNow();
        }, SAVE_DELAY_MS);
    }

    /** Saves a snapshot, in order, off the main thread. */
    private void saveNow() {
        savePending = false;
        final String id = sessionId;
        final String name = title;
        final Transcript snapshot = new Transcript(transcript.entries(), transcript.sequence(), transcript.working());
        saves.execute(() -> backend.save(id, name, snapshot));
    }

    // ------------------------------------------------------------------
    // The session screen
    // ------------------------------------------------------------------

    private void updateHeader() {
        if (titleView == null || stateView == null) return;
        titleView.setText(Chat.label(backupNow(), title != null ? title : getString(R.string.new_session)));
        String state;
        if (sessionId == null) state = creating ? "Starting" : "";
        else if (!online) state = "Offline";
        else if (transcript.pending() != null) state = "Waiting";
        else if (transcript.working()) state = "Working";
        else state = "Ready";
        stateView.setText(state);
    }

    /** The working line under the transcript, or null when nothing is running. */
    private String workingLine() {
        if (creating) return "Starting the session…";
        if (!transcript.working() || transcript.pending() != null) return null;
        return transcript.status();
    }

    /**
     * Repaints the rows without rebuilding the screen, so the cursor, the
     * draft and the T9 keyboard's half-finished word survive a new event.
     */
    private void updateChat() {
        if (currentTab != SESSION || chatAdapter == null || chatList == null) return;
        boolean follow = chatList.isPinnedToBottom() || isAtBottom(chatList, chatAdapter);
        chatAdapter.replace(transcript.entries(), workingLine(), limitNote(), limitAction());
        if (follow) chatList.pinToBottomOnce();
    }

    /** The session's name and state, in a slim strip over the transcript. */
    private View header() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackgroundColor(color(R.color.section_fill));
        int pad = getResources().getDimensionPixelSize(R.dimen.row_pad_h);
        strip.setPadding(pad, 0, pad, 0);
        TextView name = new TextView(this);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setIncludeFontPadding(false);
        name.setTextColor(color(R.color.text));
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.header_text));
        strip.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = new TextView(this);
        state.setSingleLine(true);
        state.setIncludeFontPadding(false);
        state.setTextColor(color(R.color.accent_text));
        state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.state_text));
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateParams.leftMargin = dp(6);
        strip.addView(state, stateParams);
        titleView = name;
        stateView = state;
        strip.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, getResources().getDimensionPixelSize(R.dimen.header_height)));
        return strip;
    }

    private void render(int focus) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(header());
        updateHeader();

        final ChatAdapter adapter = new ChatAdapter(this, pictures);
        adapter.replace(transcript.entries(), workingLine(), limitNote(), limitAction());
        final ChatListView list = new ChatListView(this);
        list.setAdapter(adapter);
        // Rows paint their own focus state edge to edge; a transparent
        // selector stops the platform drawing a second highlight under them.
        list.setSelector(new ColorDrawable(0x00000000));
        list.setCacheColorHint(0x00000000);
        list.setItemsCanFocus(false);
        list.setBackgroundColor(color(R.color.chat_page));
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        list.setSmoothScrollbarEnabled(false);
        list.setVerticalFadingEdgeEnabled(false);
        list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        list.setPadding(0, 0, 0, dp(8));
        list.setClipToPadding(false);
        chatAdapter = adapter;
        chatList = list;
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        /* A compact three-line compose field leaves the transcript most of the
         * screen. OK sends, so the 240px-wide layout needs no separate button. */
        final EditText input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(color(R.color.input_text));
        input.setHintTextColor(color(R.color.input_hint));
        input.setBackgroundResource(R.drawable.edittext_border);
        input.setPadding(dp(8), dp(8), dp(8), dp(8));
        input.setHint(getString(R.string.chat_hint));
        input.setText(draft);
        input.setSelection(draft.length());
        input.setMaxLines(3);
        // Without AUTO_CORRECT the T9 keyboard drops back to multitap with no
        // suggestions; MULTI_LINE lets a long message wrap instead of
        // scrolling sideways through a 240px slot.
        input.setInputType(
            InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT |
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        // The draft is kept here rather than read off the view, because a
        // redraw rebuilds this screen and the view holding it goes with it.
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                draft = text.toString();
                if (input.hasFocus()) list.pinToBottomOnce();
            }
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null &&
                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN)
            ) {
                send(view.getText().toString());
                return true;
            }
            return false;
        });
        int listId = View.generateViewId();
        int inputId = View.generateViewId();
        list.setId(listId);
        input.setId(inputId);
        list.setNextFocusDownId(inputId);
        input.setNextFocusUpId(listId);
        input.setOnFocusChangeListener((view, hasFocus) -> {
            // Reaching the composer proves the transcript was read to its
            // end. Keep the last row's bottom fixed while typing.
            list.setPinnedToBottom(hasFocus);
        });
        /*
         * OK sends, on the press rather than the release: with the T9 keyboard
         * up, the first ENTER release never reaches the app because the
         * keyboard takes it to finish the word it is composing. Both halves
         * are swallowed so ENTER cannot also drop a newline into the field.
         */
        input.setOnKeyListener((view, keyCode, event) -> {
            // Up out of the box once there is no line of your own above
            // the cursor, without the platform's extra dead press.
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (!onFirstLine((EditText) view)) return false;
                if (list.getCount() == 0) return false;
                list.requestFocus();
                return true;
            }
            if (!Ui.isOk(keyCode)) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                send(((EditText) view).getText().toString());
            }
            return true;
        });

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(dp(4), dp(2), dp(4), dp(4));
        View strip = draftStrip();
        if (strip != null) root.addView(strip);
        View named = filesStrip();
        if (named != null) root.addView(named);
        root.addView(input, inputParams);

        wireTranscript(list, adapter);
        Ui.fill(content, root);
        restoreScrollPosition(list, adapter);
        if (focus == FOCUS_INPUT) input.requestFocus();
        else if (focus == FOCUS_LIST && adapter.getCount() > 0) list.requestFocus();
        else if (focus == FOCUS_LIST) input.requestFocus();
    }

    /**
     * Stepping through the transcript. Up out of the box puts the highlight
     * on the newest row; down off the newest one puts it back in the box.
     * The highlight is dropped whenever the list loses focus.
     */
    private void wireTranscript(final ChatListView list, final ChatAdapter adapter) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnFocusChangeListener((view, hasFocus) -> {
            if (adapter.getCount() == 0) return;
            if (hasFocus) {
                list.setPinnedToBottom(false);
                if (adapter.getSelectedPosition() == AdapterView.INVALID_POSITION) {
                    int selected = list.getSelectedItemPosition();
                    if (selected == AdapterView.INVALID_POSITION) selected = list.getLastVisiblePosition();
                    adapter.trackSelectedPosition(selected);
                    View selectedView = list.getSelectedView();
                    if (selectedView != null) selectedView.setSelected(true);
                }
            } else {
                View selectedView = list.getSelectedView();
                if (selectedView != null) selectedView.setSelected(false);
                adapter.trackSelectedPosition(AdapterView.INVALID_POSITION);
            }
        });
        Ui.onSelect(list, position -> {
            if (list.hasFocus()) adapter.trackSelectedPosition(position);
        });
        list.setOnKeyListener((view, keyCode, event) -> {
            // OK on a message does nothing and holding it opens the menu;
            // OK on the button at the foot presses it.
            if (!Ui.isOk(keyCode)) return false;
            if (okOnRow(event, null) && adapter.isAction(adapter.getSelectedPosition())) moveSession();
            return true;
        });
    }

    /**
     * Records intent rather than a pixel offset. MAX_VALUE means "the end", so
     * returning after the transcript grows resolves against its new count.
     */
    private void saveScrollPosition() {
        if (chatList == null || chatAdapter == null || chatAdapter.getCount() == 0) return;
        savedScrollPosition = chatList.getLastVisiblePosition() >= chatAdapter.getCount() - 1
            ? Integer.MAX_VALUE
            : chatList.getFirstVisiblePosition();
    }

    private void restoreScrollPosition(ChatListView list, ChatAdapter adapter) {
        int count = adapter.getCount();
        int saved = savedScrollPosition;
        savedScrollPosition = AdapterView.INVALID_POSITION;
        if (count == 0) return;
        int position = saved >= 0 && saved != Integer.MAX_VALUE ? Math.min(saved, count - 1) : count - 1;
        list.setSelection(position);
        if (saved == Integer.MAX_VALUE || saved == AdapterView.INVALID_POSITION) list.pinToBottomOnce();
    }

    /** Whether the final row is fully visible inside the padded viewport. */
    private static boolean isAtBottom(ListView list, ChatAdapter adapter) {
        int target = adapter.getCount() - 1;
        int last = list.getLastVisiblePosition();
        if (target < 0 || last != target) return false;
        View row = list.getChildAt(last - list.getFirstVisiblePosition());
        return row != null && row.getBottom() <= list.getHeight() - list.getPaddingBottom();
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private void refreshFiles() {
        if (!backend.centerReady() || filesLoading) return;
        filesLoading = true;
        final int forGeneration = ++filesGeneration;
        if (currentTab == FILES) renderFilesKeepingFocus();
        lists.execute(() -> {
            List<RemoteFile> fetched = null;
            String problem = null;
            try {
                fetched = backend.files();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                problem = "Couldn't refresh: " + reason(e);
            }
            final List<RemoteFile> result = fetched;
            final String said = problem;
            main.post(() -> {
                if (forGeneration != filesGeneration || isFinishing()) return;
                filesLoading = false;
                filesProblem = said;
                if (result != null) {
                    files = result;
                    filesLoaded = true;
                }
                if (currentTab == FILES) renderFilesKeepingFocus();
            });
        });
    }

    private void renderFilesKeepingFocus() {
        if (rowAdapter != null) {
            RowAdapter.Row row = rowAdapter.selected();
            if (row != null) filesKey = row.key;
        }
        renderFiles(true);
    }

    private void renderFiles(boolean focusContent) {
        if (!backend.centerReady()) {
            Ui.fill(content, emptyMessage(Backend.NO_CENTER));
            return;
        }
        if (files.isEmpty()) {
            String said = uploading != null ? uploadLine()
                : filesProblem != null ? filesProblem
                : filesLoading || !filesLoaded ? "Loading…"
                : "No files yet.";
            View empty = emptyMessage(said);
            if (uploading != null) uploadView = (TextView) empty;
            Ui.fill(content, empty);
            return;
        }
        LinearLayout root = vertical();
        TextView notice = notice();
        notice.setText(filesProblem);
        notice.setVisibility(filesProblem == null ? View.GONE : View.VISIBLE);
        root.addView(notice);
        if (uploading != null) {
            TextView progress = notice();
            progress.setText(uploadLine());
            progress.setVisibility(View.VISIBLE);
            root.addView(progress);
            uploadView = progress;
        }
        final RowAdapter adapter = new RowAdapter(this);
        final ListView list = listView(adapter);
        root.addView(list, Ui.weighted());
        rowList = list;
        rowAdapter = adapter;
        wireRows(list, adapter);
        Ui.fill(content, root);
        updateFileRows();
        int position = adapter.positionOf(filesKey);
        select(list, adapter, position < 0 ? 0 : position);
        if (focusContent) list.requestFocus();
    }

    private void updateFileRows() {
        if (currentTab != FILES || rowAdapter == null) return;
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (RemoteFile file : files) {
            String sub = Formatter.formatShortFileSize(this, file.size);
            String when = when(file.modified);
            if (when.length() > 0) sub += " · " + when;
            rows.add(new RowAdapter.Row(file.name, file.name, sub, fileState(file)));
        }
        int selected = rowAdapter.getSelectedPosition();
        rowAdapter.replace(rows);
        if (selected >= 0 && selected < rows.size()) rowAdapter.setSelectedPosition(selected);
    }

    private static final int ABSENT = 0;
    private static final int CURRENT = 1;
    private static final int CHANGED = 2;

    /** Whether the phone has this file, and whether it is still the latest push of it. */
    private int copyOf(RemoteFile file) {
        RemoteFile copy = local.get(file.name);
        if (copy == null || !backend.downloaded(file.name).isFile()) return ABSENT;
        return copy.sameAs(file) ? CURRENT : CHANGED;
    }

    private String fileState(RemoteFile file) {
        if (running != null && running.file.name.equals(file.name)) {
            return running.total > 0 ? (int) (running.done * 100 / running.total) + "%" : "Getting";
        }
        if (file.name.equals(moving)) return "Moving";
        int copy = copyOf(file);
        if (copy == CURRENT) return file.isApk() ? "Install" : "On phone";
        if (copy == CHANGED) return "Changed";
        return null;
    }

    private RemoteFile fileFor(RowAdapter.Row row) {
        if (row == null) return null;
        for (RemoteFile file : files) if (file.name.equals(row.key)) return file;
        return null;
    }

    /** OK on a file: fetch it, or once it is here, install or open it. */
    private void activateFile(RowAdapter.Row row) {
        RemoteFile file = fileFor(row);
        if (file == null) return;
        if (running != null && running.file.name.equals(file.name)) {
            toast("Downloading…");
            return;
        }
        if (file.name.equals(moving)) {
            toast("Moving…");
            return;
        }
        if (copyOf(file) == CURRENT) {
            if (file.isApk()) install(file);
            else open(file);
        } else {
            download(file);
        }
    }

    private void download(RemoteFile file) {
        download(file, null);
    }

    /** Fetches a file, then moves it into `folder` when that is not null. */
    private void download(RemoteFile file, String folder) {
        if (running != null) {
            toast("Wait for " + running.file.name + " first");
            return;
        }
        running = new Download(file, folder);
        // A large APK on a slow line outlasts the screen timeout.
        keepAwake();
        downloads.execute(running);
        updateFileRows();
    }

    /** Called on the main thread once `download` has ended, however it ended. */
    private void downloaded(Download download, String problem) {
        if (download != running) return;
        running = null;
        keepAwake();
        if (problem != null) {
            toast(problem);
        } else {
            local.put(download.file.name, download.file);
            saveDownloads();
            if (download.folder != null) move(download.file, download.folder);
            else toast("Downloaded " + download.file.name);
        }
        updateFileRows();
    }

    /**
     * Move to: the storage permission first, since the folders are listed as
     * well as written to, then the shared folders.
     */
    private void chooseFolder(RemoteFile file) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingMove = file.name;
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE);
            return;
        }
        showFolders(file.name);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == STORAGE_UPLOAD) {
            int purpose = pendingPick;
            pendingPick = PICK_NONE;
            if (purpose == PICK_NONE) return;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) pickFile(purpose, "");
            else toast("Delta needs storage access to send files");
            return;
        }
        if (request != STORAGE) return;
        String name = pendingMove;
        pendingMove = null;
        if (name == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) showFolders(name);
        else toast("Delta needs storage access to move files");
    }

    /** The top level: the shared folders, the likeliest for this file first. */
    private void showFolders(final String name) {
        final List<String> folders = Folders.forName(name);
        new AlertDialog.Builder(this)
            .setTitle("Move to")
            .setItems(folders.toArray(new String[0]), (dialog, which) -> browse(name, folders.get(which)))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * One folder, by its path in shared storage ("Music/Some Album"): move
     * the file here, make a folder in it, or open one of the folders inside.
     * Back goes up a level, and from a shared folder to the list of them.
     */
    private void browse(final String name, final String path) {
        final List<String> inside = Folders.subfolders(shared(path));
        List<String> items = new ArrayList<>();
        items.add("Move here");
        items.add("New folder…");
        for (String folder : inside) items.add(folder + "/");
        listDialog(path, items, which -> {
            if (which == 0) moveTo(name, path);
            else if (which == 1) newFolder(name, path);
            else browse(name, path + "/" + inside.get(which - 2));
        }, () -> {
            int slash = path.lastIndexOf('/');
            if (slash < 0) showFolders(name);
            else browse(name, path.substring(0, slash));
        });
    }

    /** Makes a folder inside `path` and opens it; giving up goes back to `path`. */
    private void newFolder(final String name, final String path) {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        // AUTO_CORRECT keeps T9's word suggestions, as in the composer.
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS
            | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        field.setHint("Folder name");
        LinearLayout form = vertical();
        form.setPadding(dp(16), dp(8), dp(16), 0);
        form.addView(field);
        final DialogInterface.OnClickListener back = (dialog, which) -> browse(name, path);
        new AlertDialog.Builder(this)
            .setTitle("New folder in " + path)
            .setView(form)
            .setPositiveButton("Create", (dialog, which) -> {
                String folder = field.getText().toString().trim();
                File made = new File(shared(path), folder);
                if (!RemoteFile.validName(folder)) {
                    toast("Not a folder name");
                    browse(name, path);
                } else if (!made.isDirectory() && !made.mkdirs()) {
                    toast("Couldn't make the folder");
                    browse(name, path);
                } else {
                    browse(name, path + "/" + folder);
                }
            })
            .setNegativeButton("Cancel", back)
            .setOnCancelListener(dialog -> browse(name, path))
            .show();
    }

    /** A folder in shared storage, by its path there. */
    private static File shared(String path) {
        return new File(Environment.getExternalStorageDirectory(), path);
    }

    /**
     * Moves the phone's copy of a file into a folder in shared storage,
     * fetching it first when the phone has none or an older one.
     */
    private void moveTo(String name, String folder) {
        RemoteFile file = null;
        for (RemoteFile each : files) if (each.name.equals(name)) file = each;
        if (file == null) return;
        if (copyOf(file) == CURRENT) move(file, folder);
        else download(file, folder);
    }

    private void move(final RemoteFile file, final String folder) {
        if (moving != null) {
            toast("Wait for " + moving + " first");
            return;
        }
        moving = file.name;
        final File into = shared(folder);
        moves.execute(() -> {
            File placed = null;
            String problem = null;
            try {
                placed = backend.moveOut(file.name, into);
            } catch (IOException e) {
                problem = reason(e);
            }
            final File where = placed;
            final String said = problem;
            main.post(() -> moved(file.name, folder, where, said));
        });
        updateFileRows();
    }

    /**
     * The phone's copy now lives in the shared folder, so the app forgets it;
     * the media scanner is told, so the music player and the gallery see it.
     */
    private void moved(String name, String folder, File placed, String problem) {
        moving = null;
        if (problem != null) {
            toast("Not moved: " + problem);
        } else {
            local.remove(name);
            saveDownloads();
            MediaScannerConnection.scanFile(getApplicationContext(), new String[] {placed.getPath()}, null, null);
            toast(placed.getName().equals(name) ? "Moved to " + folder : "Moved to " + folder + " as " + placed.getName());
        }
        updateFileRows();
    }

    private void cancelDownload() {
        if (running != null) running.cancelled = true;
    }

    private void install(RemoteFile file) {
        File apk = backend.downloaded(file.name);
        Apks.Verdict verdict = Apks.inspect(this, apk);
        if (verdict.problem != null) {
            new AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(verdict.problem)
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        installing = file.name;
        installLabel = verdict.label;
        installUpdate = verdict.update;
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(ShareProvider.uri(file.name), ShareProvider.APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        if (hasHelper()) intent.setComponent(HELPER);
        try {
            startActivityForResult(intent, INSTALL);
        } catch (ActivityNotFoundException e) {
            installing = null;
            toast("No installer on this phone");
        }
    }

    private boolean hasHelper() {
        try {
            getPackageManager().getActivityInfo(HELPER, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The installer's answer, with the helper's reason when it gave one. An
     * installed APK has done its job, so its copy goes.
     */
    private void installed(boolean ok, String why) {
        String name = installing;
        installing = null;
        if (name == null) return;
        String outcome = installLabel + (installUpdate ? " was not updated" : " was not installed");
        if (ok) {
            toast((installUpdate ? "Updated " : "Installed ") + installLabel);
            forget(name);
        } else if (why != null) {
            new AlertDialog.Builder(this)
                .setTitle(outcome)
                .setMessage(why)
                .setPositiveButton("OK", null)
                .show();
        } else {
            toast(outcome);
        }
    }

    private void open(RemoteFile file) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(ShareProvider.uri(file.name), ShareProvider.mime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("No app on this phone opens " + file.name);
        }
    }

    /** Removes the phone's copy of a file; the command center's stays. */
    private void forget(String name) {
        backend.downloaded(name).delete();
        local.remove(name);
        saveDownloads();
        updateFileRows();
    }

    private void saveDownloads() {
        final Map<String, RemoteFile> snapshot = new LinkedHashMap<>(local);
        saves.execute(() -> backend.saveDownloads(snapshot));
    }

    private void confirmDelete(final RemoteFile file) {
        new AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete " + file.name + " from the command center?")
            .setPositiveButton("Delete", (dialog, which) -> delete(file))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void delete(final RemoteFile file) {
        if (running != null && running.file.name.equals(file.name)) running.cancelled = true;
        lists.execute(() -> {
            String problem = null;
            try {
                backend.deleteFile(file.name);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                problem = reason(e);
            }
            final String said = problem;
            main.post(() -> {
                if (isFinishing()) return;
                if (said != null) {
                    toast("Not deleted: " + said);
                    return;
                }
                toast("Deleted " + file.name);
                List<RemoteFile> left = new ArrayList<>();
                for (RemoteFile other : files) if (!other.name.equals(file.name)) left.add(other);
                files = left;
                forget(file.name);
                if (currentTab == FILES) renderFilesKeepingFocus();
            });
        });
    }

    /**
     * The command center's address and token, for a build that came without
     * them. The token is checked against the server before it is kept, as
     * `delta set-auth` checks it.
     */
    private void editCenter() {
        LinearLayout form = vertical();
        form.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("cc.example.com");
        String server = backend.server();
        if (server != null) {
            address.setText(server);
            address.setSelection(server.length());
        }
        form.addView(address);
        final EditText token = new EditText(this);
        token.setSingleLine(true);
        // Visible, not hidden: it is typed on a keypad and has to be checked
        // by eye, and T9 must not try to correct it into words.
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        token.setHint(backend.centerReady() ? "Token (blank keeps it)" : "Token");
        form.addView(token);
        new AlertDialog.Builder(this)
            .setTitle("Command center")
            .setView(form)
            .setPositiveButton("Connect", (dialog, which) -> {
                connectCenter(address.getText().toString(), token.getText().toString());
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void connectCenter(final String server, final String token) {
        toast("Checking the command center…");
        lists.execute(() -> {
            String problem = null;
            try {
                backend.connectCenter(server, token);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
            } catch (Exception e) {
                problem = reason(e);
            }
            final String said = problem;
            main.post(() -> {
                if (isFinishing()) return;
                if (said != null) {
                    toast("Not connected: " + said);
                    return;
                }
                toast("Connected to the command center");
                files = new ArrayList<>();
                filesLoaded = false;
                filesProblem = null;
                filesGeneration++;
                filesLoading = false;
                if (currentTab == FILES) renderFiles(true);
                refreshFiles();
            });
        });
    }

    /** One download, on the downloads worker; reports back through the main handler. */
    private final class Download implements Runnable, Center.Progress {
        final RemoteFile file;
        /** The folder in shared storage to move it into once it is here, or null. */
        final String folder;
        volatile boolean cancelled;
        /** Read on the main thread only. */
        long done;
        long total = -1;
        private long lastUpdate;

        Download(RemoteFile file, String folder) {
            this.file = file;
            this.folder = folder;
        }

        @Override
        public void run() {
            String problem = null;
            try {
                backend.download(file, this);
            } catch (Center.Cancelled e) {
                problem = "Cancelled";
            } catch (InterruptedException e) {
                problem = "Cancelled";
            } catch (IOException e) {
                problem = reason(e);
            } catch (RuntimeException e) {
                problem = "The download failed.";
            }
            final String said = problem;
            main.post(() -> {
                downloaded(Download.this, said);
            });
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void progress(final long now, final long size) {
            long at = SystemClock.uptimeMillis();
            if (now > 0 && at - lastUpdate < 250) return;
            lastUpdate = at;
            main.post(() -> {
                if (running != Download.this) return;
                done = now;
                total = size;
                updateFileRows();
            });
        }
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    /**
     * Chats and Files: up and down are ListView's own, OK on release acts on
     * the row, and holding OK opens the menu, as a long press does in the
     * phone's own lists.
     */
    private void wireRows(final ListView list, final RowAdapter adapter) {
        Ui.onSelect(list, position -> adapter.setSelectedPosition(position));
        list.setOnItemClickListener((parent, view, position, id) -> {
            adapter.setSelectedPosition(position);
            activateRow(adapter.getItem(position));
        });
        list.setOnKeyListener((view, keyCode, event) -> {
            if (!Ui.isOk(keyCode)) return false;
            okOnRow(event, adapter.selected());
            return true;
        });
    }

    private void activateRow(RowAdapter.Row row) {
        if (row == null) return;
        if (currentTab == CHATS) activateChat(row);
        else if (currentTab == FILES) activateFile(row);
    }

    private ListView listView(RowAdapter adapter) {
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setSelector(new ColorDrawable(0x00000000));
        list.setCacheColorHint(0x00000000);
        list.setItemsCanFocus(false);
        list.setDivider(new ColorDrawable(color(R.color.divider)));
        list.setDividerHeight(getResources().getDimensionPixelSize(R.dimen.divider_height));
        list.setVerticalFadingEdgeEnabled(false);
        list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        return list;
    }

    private static void select(ListView list, RowAdapter adapter, int position) {
        if (position < 0 || position >= adapter.getCount()) return;
        adapter.setSelectedPosition(position);
        list.setSelection(position);
    }

    /** A quiet line over a list: loading, or why the list is not fresh. */
    private TextView notice() {
        TextView notice = new TextView(this);
        notice.setTextColor(color(R.color.text_muted));
        notice.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_notice));
        notice.setBackgroundColor(color(R.color.section_fill));
        int pad = getResources().getDimensionPixelSize(R.dimen.row_pad_h);
        notice.setPadding(pad, dp(4), pad, dp(4));
        notice.setMaxLines(3);
        notice.setVisibility(View.GONE);
        return notice;
    }

    /** A whole tab saying one thing. Focusable, so Menu still has somewhere to open. */
    private View emptyMessage(String said) {
        TextView message = new TextView(this);
        message.setText(said);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(color(R.color.text_muted));
        message.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_empty));
        message.setPadding(dp(16), dp(16), dp(16), dp(16));
        message.setFocusable(true);
        message.setFocusableInTouchMode(true);
        return message;
    }

    /** "5 min. ago", or nothing for an unknown time. */
    private static String when(long time) {
        if (time <= 0) return "";
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /**
     * OK on a row. Holding it opens the menu; releasing it without having
     * held it acts on `row`, if there is one, and returns true. Every OK press is claimed, so
     * ListView's own long press, which would open a menu as a centred dialog,
     * never runs. A release whose press began somewhere else (a dialog, the
     * previous tab) does nothing.
     */
    private boolean okOnRow(KeyEvent event, RowAdapter.Row row) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                okHeld = false;
                okDown = true;
            } else if (!okHeld && okDown) {
                okHeld = true;
                showMenu();
            }
            return false;
        }
        boolean act = okDown && !okHeld && !event.isCanceled();
        okHeld = false;
        okDown = false;
        if (act) activateRow(row);
        return act;
    }

    /** Whether the cursor sits on the first line of what has been typed. */
    private static boolean onFirstLine(EditText field) {
        Layout laid = field.getLayout();
        if (laid == null) return true;
        return laid.getLineForOffset(field.getSelectionStart()) == 0;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // First press only. Auto-repeat from a held d-pad would cycle the tabs
        // as fast as the key fires and redraw every one of them on the way.
        // Inside typed text, left and right belong to the cursor instead.
        int next = tabs.step(event, getCurrentFocus(), currentTab);
        if (next >= 0) {
            showTab(next, true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /*
     * Menu opens on its release, as the platform's own menus do. Opened on
     * the press, the release would arrive at the popup once it had taken
     * focus, and the popup closes on a Menu release.
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

    @Override
    public void onBackPressed() {
        // Back cancels a download being watched, as Downloader's did.
        if (currentTab == FILES && running != null) {
            cancelDownload();
            return;
        }
        if (currentTab == FILES && uploading != null) {
            cancelUpload();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // The Menu key
    // ------------------------------------------------------------------

    /*
     * Menu, or holding OK, opens the platform's own context menu at the thing
     * it acts on: the highlighted row, or the foot of the list. Opening it at
     * a point inside the list (never on the row, whose parent would be
     * scrolled to fit it) is what makes Android draw the stock popup beside
     * that point rather than a dialog in the middle of the screen.
     */

    private final List<Integer> menuIds = new ArrayList<>();
    private final List<String> menuTitles = new ArrayList<>();

    private void showMenu() {
        menuEntry = null;
        menuRow = null;
        menuIds.clear();
        menuTitles.clear();
        View anchor;
        float y;
        if (currentTab == SESSION && chatList != null) {
            anchor = chatList;
            y = sessionMenu();
        } else if (currentTab != SESSION && rowList != null && rowAdapter != null) {
            anchor = rowList;
            y = rowList.getHeight();
            if (rowList.hasFocus()) {
                menuRow = rowAdapter.selected();
                int position = rowAdapter.getSelectedPosition();
                View row = rowList.getChildAt(position - rowList.getFirstVisiblePosition());
                if (row != null) y = Math.max(0, Math.min(row.getBottom(), rowList.getHeight()));
            }
            if (currentTab == CHATS) chatsMenu();
            else if (currentTab == FILES) filesMenu();
            else accountsMenu();
        } else {
            anchor = content.getChildCount() > 0 ? content.getChildAt(0) : content;
            y = anchor.getHeight() / 2f;
            if (currentTab == FILES) filesMenu();
            else if (currentTab == CHATS && anySessions()) chatsMenu();
            else if (currentTab == ACCOUNTS && backend.ready()) accountsMenu();
        }
        if (menuIds.isEmpty()) return;
        anchor.showContextMenu(Math.min(dp(32), anchor.getWidth() / 2f), y);
    }

    /** The Session tab's items; returns where to open them. */
    private float sessionMenu() {
        float y = chatList.getHeight();
        if (chatList.hasFocus()) {
            int position = chatAdapter.getSelectedPosition();
            if (chatAdapter.isEntry(position)) {
                menuEntry = chatAdapter.getItem(position);
                add(MENU_COPY, "Copy text");
                View row = chatList.getChildAt(position - chatList.getFirstVisiblePosition());
                if (row != null) y = Math.max(0, Math.min(row.getBottom(), chatList.getHeight()));
            }
        }
        Transcript.Permission pending = transcript.pending();
        if (pending != null && sessionId != null) {
            add(MENU_ALLOW, "Allow " + pending.tool);
            add(MENU_DENY, "Deny " + pending.tool);
        }
        int attached = pictures.draft().size() + preparing;
        if (attached < Pictures.MAX_DRAFT) {
            add(MENU_TAKE, "Take picture");
            add(MENU_PICK, "Attach picture");
        }
        if (attached > 0) add(MENU_REMOVE, attached == 1 ? "Remove picture" : "Remove pictures");
        if (backend.centerReady() && uploading == null && attachedFiles.size() < MAX_ATTACHED) {
            add(MENU_ATTACH, "Attach file");
        }
        if (uploading != null && uploading.attach) add(MENU_CANCEL_UPLOAD, "Cancel upload");
        if (!attachedFiles.isEmpty()) add(MENU_UNATTACH, attachedFiles.size() == 1 ? "Remove file" : "Remove files");
        if (sessionId != null && transcript.working()) add(MENU_INTERRUPT, "Interrupt");
        if (clipboardText() != null) add(MENU_PASTE, "Paste");
        // A backup session has no page on the web to link to.
        if (sessionId != null && !Chat.backup(sessionId)) add(MENU_LINK, "Copy session link");
        addNewSessionItems();
        return y;
    }

    private void accountsMenu() {
        add(MENU_REFRESH, "Refresh");
    }

    private void chatsMenu() {
        Chat chat = chatFor(menuRow);
        add(MENU_REFRESH, "Refresh");
        addNewSessionItems();
        if (chat != null) add(MENU_ARCHIVE, Chat.backup(chat.id) ? "Delete" : "Archive");
    }

    private void filesMenu() {
        RemoteFile file = fileFor(menuRow);
        if (file != null) {
            boolean fetching = running != null && running.file.name.equals(file.name);
            boolean busy = fetching || file.name.equals(moving);
            int copy = copyOf(file);
            if (fetching) add(MENU_CANCEL, "Cancel download");
            else if (!busy && copy == CURRENT && file.isApk()) add(MENU_INSTALL, "Install");
            else if (!busy && copy == CURRENT) add(MENU_OPEN, "Open");
            add(MENU_SEND_FILE, "Send to agent");
            if (!busy) add(MENU_MOVE, "Move to…");
            if (!busy) add(MENU_DOWNLOAD, copy == ABSENT ? "Download" : "Download again");
            if (copy != ABSENT && !busy) add(MENU_FORGET, "Remove from phone");
            add(MENU_DELETE, "Delete from command center");
        }
        if (uploading != null) add(MENU_CANCEL_UPLOAD, "Cancel upload");
        else if (backend.centerReady()) add(MENU_UPLOAD, "Upload from phone…");
        if (backend.centerReady()) add(MENU_REFRESH, "Refresh");
        add(MENU_CENTER, "Command center");
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
        Chat chat = currentTab == CHATS ? chatFor(menuRow) : null;
        RemoteFile file = currentTab == FILES ? fileFor(menuRow) : null;
        switch (item.getItemId()) {
            case MENU_COPY:
                if (menuEntry != null) copy(menuEntry.text, "Copied");
                break;
            case MENU_ALLOW:
                answer(true);
                break;
            case MENU_DENY:
                answer(false);
                break;
            case MENU_INTERRUPT:
                interrupt();
                break;
            case MENU_PASTE:
                paste();
                break;
            case MENU_LINK:
                String id = chat != null ? chat.id : sessionId;
                if (id != null) copy(Cloud.link(id), "Link copied");
                break;
            case MENU_NEW:
            case MENU_NEW_BACKUP:
                newSession(item.getItemId() == MENU_NEW_BACKUP);
                if (currentTab != SESSION) showTab(SESSION, true);
                break;
            case MENU_TAKE:
                takePicture();
                break;
            case MENU_PICK:
                pickPicture();
                break;
            case MENU_REMOVE:
                removePictures();
                break;
            case MENU_REFRESH:
                if (currentTab == CHATS) refreshChats();
                else if (currentTab == FILES) refreshFiles();
                else refreshAccounts();
                break;
            case MENU_ARCHIVE:
                if (chat != null) confirmArchive(chat);
                break;
            case MENU_DOWNLOAD:
                if (file != null) download(file);
                break;
            case MENU_INSTALL:
                if (file != null) install(file);
                break;
            case MENU_OPEN:
                if (file != null) open(file);
                break;
            case MENU_MOVE:
                if (file != null) chooseFolder(file);
                break;
            case MENU_FORGET:
                if (file != null) forget(file.name);
                break;
            case MENU_DELETE:
                if (file != null) confirmDelete(file);
                break;
            case MENU_CANCEL:
                cancelDownload();
                break;
            case MENU_CENTER:
                editCenter();
                break;
            case MENU_UPLOAD:
                chooseUpload(PICK_UPLOAD);
                break;
            case MENU_ATTACH:
                chooseUpload(PICK_ATTACH);
                break;
            case MENU_SEND_FILE:
                if (file != null) {
                    attach(file.name);
                    showTab(SESSION, true);
                }
                break;
            case MENU_UNATTACH:
                removeFiles();
                break;
            case MENU_CANCEL_UPLOAD:
                cancelUpload();
                break;
            default:
                break;
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
    // Helpers
    // ------------------------------------------------------------------

    private void copy(String text, String said) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Delta", text));
        toast(said);
    }

    /** What is on the clipboard as text, or null when there is nothing usable. */
    private CharSequence clipboardText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()
            || clipboard.getPrimaryClip().getItemCount() == 0) return null;
        CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        return pasted == null || pasted.length() == 0 ? null : pasted;
    }

    private void paste() {
        CharSequence pasted = clipboardText();
        if (pasted == null) return;
        draft = draft + pasted.toString();
        saveScrollPosition();
        render(FOCUS_INPUT);
    }

    /** An exception as a sentence, never empty. */
    private static String reason(Exception e) {
        if (e instanceof RuntimeException) return "the answer could not be read.";
        String message = e.getMessage();
        return message == null || message.length() == 0 ? "the network could not be reached." : message;
    }
}
