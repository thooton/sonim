package com.delta.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.delta.xp3.data.Chat;
import com.delta.xp3.data.Entry;
import com.delta.xp3.data.RemoteFile;
import com.delta.xp3.data.Store;
import com.delta.xp3.data.Transcript;
import com.delta.xp3.net.Center;
import com.delta.xp3.net.Cloud;
import com.delta.xp3.net.Sse;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Delta on the phone: three tabs over the cloud sessions and the command
 * center.
 *
 * - Chats lists the account's agent sessions in the cloud. OK opens
 *   one in Session; the first row starts a new one.
 * - Session is one conversation, and a box to add to it. The first message
 *   of a new session creates it, carrying the Delta setup ahead of what was
 *   typed. What is on screen is built from the session's event stream, which
 *   stays open while the app is visible, whichever tab is showing.
 * - Files lists what agents have pushed to the command center. OK downloads a
 *   file, then installs it if it is an APK or opens it if not.
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
public final class MainActivity extends Activity {

    private static final int CHATS = 0;
    private static final int SESSION = 1;
    private static final int FILES = 2;
    private static final int TABS = 3;

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

    /** Activity results. */
    private static final int TAKE_PICTURE = 1;
    private static final int PICK_PICTURE = 2;
    private static final int INSTALL = 3;

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
    private final ExecutorService saves = pool("delta-save");
    private final ExecutorService requests = pool("delta-request");
    private final ExecutorService lists = pool("delta-list");
    private final ExecutorService downloads = pool("delta-download");
    private final ExecutorService picturesWork = pool("delta-pictures");

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

    private Backend backend;
    private Pictures pictures;
    /** Pictures still being shrunk; a message waits for them. */
    private int preparing;
    private FrameLayout content;
    private Button[] tabs;
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

    // ------------------------------------------------------------------
    // The session screen
    // ------------------------------------------------------------------

    private String draft = "";
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
        }
        openSaved(backend.openSession());
        chats = backend.savedChats();
        local = backend.downloads();

        content = findViewById(R.id.content);
        tabs = new Button[] {
            findViewById(R.id.tab_chats),
            findViewById(R.id.tab_session),
            findViewById(R.id.tab_files),
        };
        for (int i = 0; i < tabs.length; i++) {
            final int which = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showTab(which, true);
                }
            });
        }
        // Every context menu is answered here; see showMenu.
        registerForContextMenu(content);
        showTab(tab, true);
        prune();
    }

    @Override
    protected void onStart() {
        super.onStart();
        connect();
        // Coming back is the moment to ask again: a phone like this is put
        // away, not quit, and the agent has usually been busy meanwhile.
        if (currentTab == CHATS) refreshChats();
        else if (currentTab == FILES) refreshFiles();
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
    }

    @Override
    protected void onPause() {
        if (currentTab == SESSION) saveScrollPosition();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        if (running != null) running.cancelled = true;
        // Let the last save land; a request in flight is not worth holding
        // the process for once the screen is gone.
        saves.shutdown();
        picturesWork.shutdown();
        requests.shutdownNow();
        lists.shutdownNow();
        downloads.shutdownNow();
    }

    /** Forgets old transcripts, and the pictures only they showed. */
    private void prune() {
        final String open = sessionId;
        saves.execute(new Runnable() {
            @Override
            public void run() {
                final Set<String> shown = backend.prune(open);
                picturesWork.execute(new Runnable() {
                    @Override
                    public void run() {
                        pictures.retain(shown);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private void showTab(int tab, boolean focusContent) {
        if (currentTab == SESSION && tab != SESSION) saveScrollPosition();
        leaveScreens();
        currentTab = tab;
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == tab);
        if (tab == CHATS) {
            renderChats(focusContent);
            refreshChats();
        } else if (tab == SESSION) {
            render(focusContent ? FOCUS_INPUT : FOCUS_NONE);
        } else {
            renderFiles(focusContent);
            if (!filesLoaded) refreshFiles();
        }
    }

    /** Drops the views of whichever tab is going, so nothing updates them off screen. */
    private void leaveScreens() {
        if (rowAdapter != null && rowList != null && rowList.hasFocus()) {
            RowAdapter.Row row = rowAdapter.selected();
            if (row != null && currentTab == CHATS) chatsKey = row.key;
            if (row != null && currentTab == FILES) filesKey = row.key;
        }
        chatList = null;
        chatAdapter = null;
        titleView = null;
        stateView = null;
        rowList = null;
        rowAdapter = null;
    }

    private void replaceContent(View view) {
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------
    // Chats
    // ------------------------------------------------------------------

    private void refreshChats() {
        if (!backend.ready() || chatsLoading) return;
        chatsLoading = true;
        final int forGeneration = ++chatsGeneration;
        if (currentTab == CHATS) updateChatRows();
        lists.execute(new Runnable() {
            @Override
            public void run() {
                List<Chat> fetched = null;
                String problem = null;
                try {
                    fetched = backend.sessions();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    problem = "Couldn't refresh: " + reason(e);
                }
                final List<Chat> result = fetched;
                final String said = problem;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        chatsFetched(result, said, forGeneration);
                    }
                });
            }
        });
    }

    private void chatsFetched(List<Chat> fetched, String problem, int forGeneration) {
        if (forGeneration != chatsGeneration || isFinishing()) return;
        chatsLoading = false;
        chatsProblem = problem;
        if (fetched != null) {
            chats = fetched;
            final List<Chat> snapshot = new ArrayList<>(fetched);
            saves.execute(new Runnable() {
                @Override
                public void run() {
                    backend.saveChats(snapshot);
                }
            });
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
        if (!backend.ready()) {
            replaceContent(emptyMessage(Backend.NO_LOGIN));
            return;
        }
        LinearLayout root = vertical();
        TextView notice = notice();
        root.addView(notice);
        final RowAdapter adapter = new RowAdapter(this);
        final ListView list = listView(adapter);
        root.addView(list, weighted());
        rowList = list;
        rowAdapter = adapter;
        wireRows(list, adapter);
        replaceContent(root);
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
            rows.add(new RowAdapter.Row(chat.id, chat.title, sub, chatState(chat)));
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
                : chats.isEmpty() ? "No cloud sessions yet." : null;
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

    private void confirmArchive(final Chat chat) {
        new AlertDialog.Builder(this)
            .setTitle("Archive")
            .setMessage("Archive \"" + chat.title + "\"?")
            .setPositiveButton("Archive", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    archive(chat);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void archive(final Chat chat) {
        lists.execute(new Runnable() {
            @Override
            public void run() {
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
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (said != null) {
                            toast("Not archived: " + said);
                            return;
                        }
                        toast("Archived");
                        List<Chat> left = new ArrayList<>();
                        for (Chat other : chats) if (!other.id.equals(chat.id)) left.add(other);
                        chats = left;
                        if (chat.id.equals(sessionId)) newSession();
                        if (currentTab == CHATS) updateChatRows();
                    }
                });
            }
        });
    }

    private Chat chatFor(RowAdapter.Row row) {
        if (row == null) return null;
        for (Chat chat : chats) if (chat.id.equals(row.key)) return chat;
        return null;
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
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        received(frame, forGeneration);
                    }
                });
            }

            @Override
            public void online(final boolean up) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (forGeneration != generation) return;
                        online = up;
                        updateHeader();
                        updateChatRows();
                    }
                });
            }
        });
        if (link != null) link.start();
        updateHeader();
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
        if (transcript.apply(frame.event, frame.id, frame.data)) {
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
        if (message.length() == 0 && pictures.draft().isEmpty()) return;
        if (preparing > 0) {
            toast("Still adding the picture");
            return;
        }
        if (!backend.ready()) {
            transcript.note(Backend.NO_LOGIN);
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
        transcript.sent(uuid, message, attached.size());
        draft = "";
        if (sessionId == null) {
            creating = true;
            title = message.length() > 0 ? titleFor(message) : attached.size() == 1 ? "Picture" : "Pictures";
            final String sessionTitle = title;
            requests.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final String id = backend.create(sessionTitle, uuid, message, attached);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                created(id, forGeneration);
                            }
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        failed("Could not start the session: " + reason(e), forGeneration);
                    }
                }
            });
        } else {
            final String session = sessionId;
            requests.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        backend.send(session, uuid, message, attached);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        failed("Not sent: " + reason(e), forGeneration);
                    }
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
        main.post(new Runnable() {
            @Override
            public void run() {
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
            }
        });
    }

    /** The first line of the first message, which is what the cloud titles a session with too. */
    private static String titleFor(String message) {
        String line = message.split("\n", 2)[0].trim();
        return line.length() <= MAX_TITLE ? line : line.substring(0, MAX_TITLE - 1) + "…";
    }

    private void interrupt() {
        if (sessionId == null) return;
        final String session = sessionId;
        requests.execute(new Runnable() {
            @Override
            public void run() {
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
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(said);
                    }
                });
            }
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
        requests.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    backend.answer(session, pending.requestId, allow, pending.input);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failed("Answer not sent: " + reason(e), forGeneration);
                }
            }
        });
    }

    /** A new session, not yet started. The one left stays in Chats. */
    private void newSession() {
        if (sessionId == null && !creating) return;
        saveNow();
        switchTo(null);
        saveNow();
        if (currentTab == SESSION) render(FOCUS_INPUT);
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
                prepare(new Pictures.Source() {
                    @Override
                    public InputStream open() throws IOException {
                        return new FileInputStream(shot);
                    }
                }, shot);
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
                prepare(new Pictures.Source() {
                    @Override
                    public InputStream open() throws IOException {
                        InputStream in = getContentResolver().openInputStream(uri);
                        if (in == null) throw new IOException("That picture could not be opened");
                        return in;
                    }
                }, null);
            }
        }
    }

    /** Shrinks a picture off the main thread and adds it to the draft. `consumed` is deleted after. */
    private void prepare(final Pictures.Source source, final File consumed) {
        preparing++;
        picturesWork.execute(new Runnable() {
            @Override
            public void run() {
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
            }
        });
        toast("Adding picture…");
    }

    private void prepareBitmap(final Bitmap bitmap) {
        preparing++;
        picturesWork.execute(new Runnable() {
            @Override
            public void run() {
                String problem = null;
                try {
                    pictures.addToDraft(Pictures.prepare(bitmap));
                } catch (IOException e) {
                    problem = reason(e);
                }
                prepared(problem);
            }
        });
    }

    /** Called on the pictures worker. */
    private void prepared(final String problem) {
        main.post(new Runnable() {
            @Override
            public void run() {
                preparing--;
                if (isFinishing()) return;
                if (problem != null) toast(problem);
                if (currentTab != SESSION) return;
                saveScrollPosition();
                render(FOCUS_INPUT);
            }
        });
    }

    private void removePictures() {
        preparing++;
        picturesWork.execute(new Runnable() {
            @Override
            public void run() {
                pictures.clearDraft();
                prepared(null);
            }
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
    // Saving
    // ------------------------------------------------------------------

    /**
     * Writes the session down after a burst of frames has settled, rather than
     * once per frame: a reconnect can replay hundreds of them at once.
     */
    private void scheduleSave() {
        if (savePending) return;
        savePending = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (savePending) saveNow();
            }
        }, SAVE_DELAY_MS);
    }

    /** Saves a snapshot, in order, off the main thread. */
    private void saveNow() {
        savePending = false;
        final String id = sessionId;
        final String name = title;
        final Transcript snapshot = new Transcript(transcript.entries(), transcript.sequence(), transcript.working());
        saves.execute(new Runnable() {
            @Override
            public void run() {
                backend.save(id, name, snapshot);
            }
        });
    }

    // ------------------------------------------------------------------
    // The session screen
    // ------------------------------------------------------------------

    private void updateHeader() {
        if (titleView == null || stateView == null) return;
        titleView.setText(title != null ? title : getString(R.string.new_session));
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
        chatAdapter.replace(transcript.entries(), workingLine());
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
        adapter.replace(transcript.entries(), workingLine());
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
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
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
            }
        });
        int listId = View.generateViewId();
        int inputId = View.generateViewId();
        list.setId(listId);
        input.setId(inputId);
        list.setNextFocusDownId(inputId);
        input.setNextFocusUpId(listId);
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                // Reaching the composer proves the transcript was read to its
                // end. Keep the last row's bottom fixed while typing.
                list.setPinnedToBottom(hasFocus);
            }
        });
        /*
         * OK sends, on the press rather than the release: with the T9 keyboard
         * up, the first ENTER release never reaches the app because the
         * keyboard takes it to finish the word it is composing. Both halves
         * are swallowed so ENTER cannot also drop a newline into the field.
         */
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                // Up out of the box once there is no line of your own above
                // the cursor, without the platform's extra dead press.
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (!onFirstLine((EditText) view)) return false;
                    if (list.getCount() == 0) return false;
                    list.requestFocus();
                    return true;
                }
                if (!isOk(keyCode)) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    send(((EditText) view).getText().toString());
                }
                return true;
            }
        });

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(dp(4), dp(2), dp(4), dp(4));
        View strip = draftStrip();
        if (strip != null) root.addView(strip);
        root.addView(input, inputParams);

        wireTranscript(list, adapter);
        replaceContent(root);
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
        list.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
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
            }
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent.hasFocus()) adapter.trackSelectedPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        list.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                // OK on a row does nothing; holding it opens the menu.
                if (!isOk(keyCode)) return false;
                okOnRow(event, null);
                return true;
            }
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
        lists.execute(new Runnable() {
            @Override
            public void run() {
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
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (forGeneration != filesGeneration || isFinishing()) return;
                        filesLoading = false;
                        filesProblem = said;
                        if (result != null) {
                            files = result;
                            filesLoaded = true;
                        }
                        if (currentTab == FILES) renderFilesKeepingFocus();
                    }
                });
            }
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
            replaceContent(emptyMessage(Backend.NO_CENTER));
            return;
        }
        if (files.isEmpty()) {
            String said = filesProblem != null ? filesProblem
                : filesLoading || !filesLoaded ? "Loading…"
                : "No files yet.";
            replaceContent(emptyMessage(said));
            return;
        }
        LinearLayout root = vertical();
        TextView notice = notice();
        notice.setText(filesProblem);
        notice.setVisibility(filesProblem == null ? View.GONE : View.VISIBLE);
        root.addView(notice);
        final RowAdapter adapter = new RowAdapter(this);
        final ListView list = listView(adapter);
        root.addView(list, weighted());
        rowList = list;
        rowAdapter = adapter;
        wireRows(list, adapter);
        replaceContent(root);
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
        if (copyOf(file) == CURRENT) {
            if (file.isApk()) install(file);
            else open(file);
        } else {
            download(file);
        }
    }

    private void download(RemoteFile file) {
        if (running != null) {
            toast("Wait for " + running.file.name + " first");
            return;
        }
        running = new Download(file);
        // A large APK on a slow line outlasts the screen timeout.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        downloads.execute(running);
        updateFileRows();
    }

    /** Called on the main thread once `download` has ended, however it ended. */
    private void downloaded(Download download, String problem) {
        if (download != running) return;
        running = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (problem != null) {
            toast(problem);
        } else {
            local.put(download.file.name, download.file);
            saveDownloads();
            toast("Downloaded " + download.file.name);
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
        saves.execute(new Runnable() {
            @Override
            public void run() {
                backend.saveDownloads(snapshot);
            }
        });
    }

    private void confirmDelete(final RemoteFile file) {
        new AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete " + file.name + " from the command center?")
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    delete(file);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void delete(final RemoteFile file) {
        if (running != null && running.file.name.equals(file.name)) running.cancelled = true;
        lists.execute(new Runnable() {
            @Override
            public void run() {
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
                main.post(new Runnable() {
                    @Override
                    public void run() {
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
                    }
                });
            }
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
            .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    connectCenter(address.getText().toString(), token.getText().toString());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void connectCenter(final String server, final String token) {
        toast("Checking the command center…");
        lists.execute(new Runnable() {
            @Override
            public void run() {
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
                main.post(new Runnable() {
                    @Override
                    public void run() {
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
                    }
                });
            }
        });
    }

    /** One download, on the downloads worker; reports back through the main handler. */
    private final class Download implements Runnable, Center.Progress {
        final RemoteFile file;
        volatile boolean cancelled;
        /** Read on the main thread only. */
        long done;
        long total = -1;
        private long lastUpdate;

        Download(RemoteFile file) {
            this.file = file;
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
            main.post(new Runnable() {
                @Override
                public void run() {
                    downloaded(Download.this, said);
                }
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
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (running != Download.this) return;
                    done = now;
                    total = size;
                    updateFileRows();
                }
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
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                adapter.setSelectedPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                adapter.setSelectedPosition(position);
                activateRow(adapter.getItem(position));
            }
        });
        list.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (!isOk(keyCode)) return false;
                okOnRow(event, adapter.selected());
                return true;
            }
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

    private LinearLayout vertical() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        return root;
    }

    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
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
     * held it acts on `row`, if there is one. Every OK press is claimed, so
     * ListView's own long press, which would open a menu as a centred dialog,
     * never runs. A release whose press began somewhere else (a dialog, the
     * previous tab) does nothing.
     */
    private void okOnRow(KeyEvent event, RowAdapter.Row row) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                okHeld = false;
                okDown = true;
            } else if (!okHeld && okDown) {
                okHeld = true;
                showMenu();
            }
            return;
        }
        boolean act = okDown && !okHeld && !event.isCanceled();
        okHeld = false;
        okDown = false;
        if (act) activateRow(row);
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
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
        if (
            event.getAction() == KeyEvent.ACTION_DOWN &&
            event.getRepeatCount() == 0 &&
            !cursorOwns(event.getKeyCode())
        ) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                showTab((currentTab + TABS - 1) % TABS, true);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                showTab((currentTab + 1) % TABS, true);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Whether left or right belongs to the text cursor rather than to the tabs.
     *
     * Inside typed text it belongs to the cursor. At either end of it, it goes
     * back to being a tab key: pressing right when the cursor is already as
     * far right as it goes cannot mean "further right", so it can safely mean
     * the next tab. Without that, a box with something in it is a room with no
     * door. A selection counts as inside the text; an empty box never owns them.
     */
    private boolean cursorOwns(int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        View focus = getCurrentFocus();
        if (!(focus instanceof EditText)) return false;
        EditText field = (EditText) focus;
        int length = field.getText().length();
        if (length == 0) return false;
        int start = field.getSelectionStart();
        int end = field.getSelectionEnd();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return !(start == 0 && end == 0);
        return !(start == length && end == length);
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
            else filesMenu();
        } else {
            anchor = content.getChildCount() > 0 ? content.getChildAt(0) : content;
            y = anchor.getHeight() / 2f;
            if (currentTab == FILES) filesMenu();
            else if (currentTab == CHATS && backend.ready()) chatsMenu();
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
        if (sessionId != null && transcript.working()) add(MENU_INTERRUPT, "Interrupt");
        if (clipboardText() != null) add(MENU_PASTE, "Paste");
        if (sessionId != null) add(MENU_LINK, "Copy session link");
        if (sessionId != null) add(MENU_NEW, "New session");
        return y;
    }

    private void chatsMenu() {
        Chat chat = chatFor(menuRow);
        add(MENU_REFRESH, "Refresh");
        add(MENU_NEW, "New session");
        if (chat != null) add(MENU_ARCHIVE, "Archive");
    }

    private void filesMenu() {
        RemoteFile file = fileFor(menuRow);
        if (file != null) {
            boolean busy = running != null && running.file.name.equals(file.name);
            int copy = copyOf(file);
            if (busy) add(MENU_CANCEL, "Cancel download");
            else if (copy == CURRENT && file.isApk()) add(MENU_INSTALL, "Install");
            else if (copy == CURRENT) add(MENU_OPEN, "Open");
            if (!busy) add(MENU_DOWNLOAD, copy == ABSENT ? "Download" : "Download again");
            if (copy != ABSENT && !busy) add(MENU_FORGET, "Remove from phone");
            add(MENU_DELETE, "Delete from command center");
        }
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
                newSession();
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
                else refreshFiles();
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

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int color(int resId) {
        return getResources().getColor(resId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
