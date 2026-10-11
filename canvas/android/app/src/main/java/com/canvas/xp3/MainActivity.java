package com.canvas.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.ChatHistory;
import com.canvas.xp3.data.ChatTurn;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Section;
import com.canvas.xp3.data.Sync;
import com.canvas.xp3.data.Todos;
import com.canvas.xp3.net.Agent;
import com.canvas.xp3.net.ClaudeAuth;
import com.canvas.xp3.net.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Four lists, a detail page, a settings page, and the keys that move between
 * them.
 *
 * Views are built in code rather than inflated per screen because each screen
 * is a handful of rows whose shape depends on what is in it, and a layout file
 * per state would be more XML than the states are worth. The things that
 * repeat, an assignment row, a section band, a bubble and a setting, are the
 * things that do have layouts.
 *
 * Nothing on this side of the app touches a network. Everything that does is
 * behind Backend, runs on one of the two executors below, and comes back
 * through the main thread's handler. There are two executors rather than one
 * because a chat turn can legitimately take half a minute of tool calls, and a
 * list refresh queued behind it would be a list that does not refresh.
 */
public final class MainActivity extends Activity {

    /**
     * The first three line up with Assignment's own state constants on
     * purpose: a tab is a state, so the tab index is what gets handed to
     * Todos.sections rather than a translation between two numberings that
     * would only ever be the identity.
     */
    private static final int ASSIGNED = 0;
    private static final int MISSING = 1;
    private static final int DONE = 2;
    private static final int CHAT = 3;
    private static final int TABS = 4;

    /** Where a redraw of the Chat tab should leave the cursor. */
    private static final int CHAT_FOCUS_NONE = 0;
    private static final int CHAT_FOCUS_LIST = 1;
    private static final int CHAT_FOCUS_INPUT = 2;

    private static final String PASTE = "Paste into the box";
    private static final String COMPACT = "Compact this chat";
    private static final String CLEAR = "Clear this chat";
    private static final String REFRESH = "Refresh from Canvas";
    private static final String SETTINGS = "Settings";
    private static final String ABOUT = "About";
    private static final String CLAUDE_LOGIN = "Sign in to Claude";

    /**
     * How stale the list has to be before opening the app fetches a new one.
     *
     * Ten minutes rather than every launch: this app is opened to check one
     * thing and closed again, often several times in a row, and a network round
     * trip on a handset like this is slow enough to be the whole of that
     * interaction. Assignments do not change minute to minute, and Refresh is
     * on the menu for the times when you know something has.
     */
    private static final long STALE_MS = 10L * 60L * 1000L;

    /** When to start saying out loud that the list is old. */
    private static final long OLD_MS = 2L * 60L * 60L * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Todos todos = new Todos();
    private Backend backend;
    private FrameLayout content;
    private View tabBar;
    private Button[] tabs;
    private int currentTab = ASSIGNED;

    /**
     * Two threads for work, one for chat.
     *
     * Two rather than one because opening an assignment fetches its
     * instructions, and a sync across a term's courses can easily run for
     * twenty seconds: queued behind it, a page opened in the meantime would sit
     * on "Fetching the instructions" for the whole of that sync for no reason
     * other than the shape of the queue.
     */
    private final ExecutorService work = pool("canvas-work", 2);
    private final ExecutorService chatWork = pool("canvas-chat", 1);

    private static ExecutorService pool(final String name, int threads) {
        return Executors.newFixedThreadPool(threads, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** What the last sync produced, from the network or read back off disk. */
    private Sync.Result snapshot;
    private boolean syncing;
    /** The last sync failure, kept so the list can say why it is showing old work. */
    private String syncError;

    /** True while the settings page is filling the screen. */
    private boolean settingsOpen;

    /**
     * The assignment filling the screen, or null when a list is showing.
     *
     * A detail page hides the tab bar rather than dimming it. On a 240px panel
     * the 36dp it gives back is most of a row, and navigation you can see but
     * cannot reach is a worse answer than navigation that has stepped out of
     * the way and says so.
     */
    private Assignment openWork;

    /**
     * Which sections are expanded, keyed by tab and heading. Absent means the
     * default, which is that a tab opens on This week and nothing else: it is
     * the section anyone opening a homework list came to read, and the others
     * are one press away.
     */
    private final Map<String, Boolean> sectionOpen = new HashMap<>();

    /** Tabs that have been drawn once, so the opening-section rule is a
     *  decision taken on arrival rather than one re-imposed on every draw. */
    private final Set<Integer> defaulted = new HashSet<>();

    private final List<ChatTurn> transcript = new ArrayList<>();
    private String chatDraft = "";
    private boolean agentThinking;
    /** True when the in-flight model turn is producing a hidden context summary. */
    private boolean compacting;
    /** The assistant turn currently arriving; it becomes a saved turn on completion. */
    private final StringBuilder streamingAnswer = new StringBuilder();
    private long streamingAnswerWhen;

    /**
     * Which conversation a turn belongs to.
     *
     * Clearing the chat or signing out while an answer is on its way has to
     * stop that answer landing in the transcript that replaced it. The turn
     * cannot be cancelled mid-flight, so it is allowed to finish and then
     * discarded on arrival by comparing this.
     */
    private int chatGeneration;

    /** The live chat views, so a streamed word can redraw rows without rebuilding the screen. */
    private ChatListView chatList;
    private ChatAdapter chatAdapter;
    /** -1 means no restore; MAX_VALUE means the transcript was at its end. */
    private int savedChatScrollPosition = AdapterView.INVALID_POSITION;

    /** The live work list, so a sync landing underneath can keep the highlight. */
    private ListView listView;
    private AssignmentAdapter listAdapter;
    private SettingsAdapter settingsAdapter;

    /** Instructions being fetched for the open assignment, and why it failed. */
    private boolean detailLoading;
    private String detailError;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        // Every clock time in the app follows the phone's own setting, so a
        // due time does not read 11:59 PM on a handset whose every other
        // screen says 23:59.
        Dates.setTwentyFourHour(DateFormat.is24HourFormat(this));
        backend = new Backend(getFilesDir());

        // The list on disk goes up before anything is asked of the network, so
        // the app opens on the work it knew about last time rather than on a
        // blank screen with a spinner. A refresh then replaces it underneath.
        snapshot = backend.cached();
        if (snapshot != null) todos.replace(snapshot.work);
        transcript.addAll(backend.transcript());
        backend.restore(transcript);

        content = findViewById(R.id.content);
        tabBar = findViewById(R.id.tab_bar);
        tabs = new Button[] {
            findViewById(R.id.tab_assigned),
            findViewById(R.id.tab_missing),
            findViewById(R.id.tab_done),
            findViewById(R.id.tab_chat),
        };
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            tabs[i].setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showTab(tab, true);
                    }
                }
            );
        }
        showTab(ASSIGNED, false);
        refreshIfStale();
    }

    /**
     * Comes back to a list that may have been left open for hours.
     *
     * A phone like this is closed by being put in a pocket, not by being quit,
     * so the screen you return to is often yesterday's. The list itself is
     * still correct, because what is missing is derived from the clock every
     * time it is drawn, but it is worth asking Canvas again.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // The redraw is for the dates: a list left open overnight still says
        // "Tomorrow" against work that is now due today. Not on the first
        // resume, which arrives moments after onCreate has just drawn it.
        if (started && openWork == null && !settingsOpen) redrawCurrent();
        started = true;
        refreshIfStale();
    }

    @Override
    protected void onPause() {
        if (currentTab == CHAT && openWork == null && !settingsOpen) {
            saveChatScrollPosition();
        }
        super.onPause();
    }

    private boolean started;

    private String greeting() {
        if (!backend.canvasReady()) {
            return "Add your Canvas details in Menu, Settings, and I can answer questions about your work.";
        }
        if (!backend.chatReady()) {
            return "Your work is syncing, but I have no model to think with yet. Open Menu, Settings, then choose a model provider and model.";
        }
        return "Ask me about your courses, deadlines or grades. I read them from Canvas when you ask.";
    }

    private void showTab(int tab, boolean focusContent) {
        if (currentTab == CHAT && tab != CHAT) saveChatScrollPosition();
        currentTab = tab;
        openWork = null;
        settingsOpen = false;
        tabBar.setVisibility(View.VISIBLE);
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == tab);
        // The first time Chat is drawn, leave it neutral just like the opening
        // screen: the first down press then enters the transcript at its end.
        // On later visits, keep the ordinary tab-to-tab focus behaviour.
        if (tab == CHAT) {
            boolean firstChat = chatList == null;
            renderChat(
                firstChat
                    ? CHAT_FOCUS_NONE
                    : focusContent ? CHAT_FOCUS_LIST : CHAT_FOCUS_NONE
            );
        }
        else renderList(tab, focusContent, null);
    }

    // ------------------------------------------------------------------
    // The three lists
    // ------------------------------------------------------------------

    /**
     * Draws one of the work lists.
     *
     * `key` is the row to leave the highlight on, named rather than numbered:
     * "h:This week" for a heading, "w:142:9013" for a piece of work. That is
     * how expanding a section keeps the heading you just pressed under the
     * cursor instead of throwing the selection back to the top of a list that
     * has just grown, and it is also what survives a sync landing underneath
     * somebody in the middle of the list, where a row index would not. Pass
     * null to start on the first piece of work.
     */
    private void renderList(final int state, boolean focusContent, String key) {
        final long stamp = System.currentTimeMillis();
        List<Section> sections = todos.sections(state, stamp);

        LinearLayout root = vertical();
        View status = statusStrip();
        if (status != null) root.addView(status);

        if (sections.isEmpty() || shouldShowWholeTabEmpty(state)) {
            listAdapter = null;
            listView = null;
            root.addView(emptyMessage(emptyText(state)), weighted());
            replaceContent(root);
            return;
        }

        openSomethingFirstTime(state, sections, stamp);
        final AssignmentAdapter adapter = new AssignmentAdapter(this);
        adapter.replace(rows(state, sections), state, stamp);
        ListView list = listView(adapter);
        root.addView(list, weighted());
        wireList(list, adapter, state);
        replaceContent(root);
        listAdapter = adapter;
        listView = list;

        int start = indexOf(adapter, key);
        if (start < 0) start = firstWork(adapter);
        adapter.setSelectedPosition(start);
        list.setSelection(start);
        if (focusContent) list.requestFocus();
    }

    /**
     * Before the first successful fetch, the full-page setup, loading or error
     * message is more useful than Assigned's deliberately empty week section.
     */
    private boolean shouldShowWholeTabEmpty(int state) {
        return state == Assignment.ASSIGNED
            && todos.isEmpty()
            && (!backend.canvasReady() || snapshot == null);
    }

    /** Names the highlighted row so it can be found again after a redraw. */
    private String selectedKey() {
        if (listAdapter == null) return null;
        int at = listAdapter.getSelectedPosition();
        if (at < 0 || at >= listAdapter.getCount()) return null;
        return keyOf(listAdapter.getItem(at));
    }

    private static String keyOf(AssignmentAdapter.Row row) {
        if (row.isHeader()) return "h:" + row.label;
        return row.isEmpty() ? "e:This week" : "w:" + row.item.id;
    }

    private static int indexOf(AssignmentAdapter adapter, String key) {
        if (key == null) return -1;
        for (int i = 0; i < adapter.getCount(); i++) {
            if (key.equals(keyOf(adapter.getItem(i)))) return i;
        }
        return -1;
    }

    /**
     * The line above the list, when there is something worth spending a row on.
     *
     * Usually there is not: a list that synced two minutes ago and got
     * everything says so by being a list of work, and a permanent "Last updated"
     * strip would cost a row of a screen that holds five to tell you nothing on
     * almost every launch. It appears while updating, when an update failed,
     * when a course could not be read, and when what is on screen is old enough
     * that you should know before you trust it.
     */
    private View statusStrip() {
        String message = statusText();
        if (message == null) return null;
        TextView view = text(message, 10, color(R.color.text_muted));
        view.setBackgroundColor(color(R.color.section_fill));
        view.setPadding(dp(10), dp(3), dp(10), dp(3));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private String statusText() {
        // With nothing set up, the empty page in the middle of the screen says
        // it at length; a strip above it would say it twice.
        if (!backend.canvasReady()) return null;
        // So would a strip saying "Updating" over a page saying "Fetching your
        // work from Canvas". The strip is for a list that has something in it.
        if (todos.isEmpty() && (syncing || syncError != null || snapshot == null)) return null;
        if (syncing) return "Updating…";
        if (syncError != null) return "Could not update. Menu to retry.";
        if (snapshot == null) return null;
        if (!snapshot.complete()) {
            int count = snapshot.unavailable.size();
            return count + (count == 1 ? " course could not be read" : " courses could not be read");
        }
        long stamp = System.currentTimeMillis();
        if (stamp - snapshot.fetchedAt > OLD_MS) {
            return "As of " + Dates.dayAndTime(snapshot.fetchedAt, stamp);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Syncing
    // ------------------------------------------------------------------

    /** Fetches only if what is on screen has had time to go wrong. */
    private void refreshIfStale() {
        if (!backend.canvasReady() || syncing) return;
        long age = snapshot == null
            ? Long.MAX_VALUE
            : System.currentTimeMillis() - snapshot.fetchedAt;
        if (age >= STALE_MS) refresh(false);
    }

    /**
     * Fetches the work list. `announce` is for a refresh somebody asked for by
     * pressing Menu, which should say something even when it succeeds; the
     * automatic one on opening the app should not.
     */
    private void refresh(final boolean announce) {
        if (syncing) return;
        if (!backend.canvasReady()) {
            if (announce) toast("Add your Canvas details in Settings first");
            return;
        }
        syncing = true;
        syncError = null;
        redrawIfListing();
        work.execute(
            new Runnable() {
                @Override
                public void run() {
                    Sync.Result fetched = null;
                    String failure = null;
                    try {
                        fetched = backend.refresh();
                    } catch (InterruptedException stopping) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (java.io.IOException e) {
                        failure = reason(e);
                    } catch (RuntimeException e) {
                        failure = "Canvas sent something this app could not read.";
                    }
                    final Sync.Result result = fetched;
                    final String problem = failure;
                    main.post(
                        new Runnable() {
                            @Override
                            public void run() {
                                synced(result, problem, announce);
                            }
                        }
                    );
                }
            }
        );
    }

    private void synced(Sync.Result result, String problem, boolean announce) {
        if (isFinishing()) return;
        syncing = false;
        if (result != null) {
            carryDetailsForward(result.work);
            snapshot = result;
            syncError = null;
            todos.replace(result.work);
            saveWork();
            if (announce) {
                toast(result.complete() ? "Up to date" : "Updated, some courses unavailable");
            }
        } else {
            syncError = problem;
            // With work already on screen the strip only has room to say that
            // something went wrong, so the reason goes past once as a toast.
            if (!todos.isEmpty()) toast(problem);
        }
        redrawIfListing();
    }

    /**
     * Moves instructions already fetched onto the records that replace them.
     *
     * A sync builds new Assignment objects from what Canvas sent, and Canvas
     * does not send bodies with a list, so without this every assignment read
     * once would go back to needing a signal the moment anything refreshed. The
     * text is keyed by course and assignment id, which is what it was fetched
     * against, so it cannot land on the wrong piece of work.
     */
    private void carryDetailsForward(List<Assignment> next) {
        if (todos.isEmpty()) return;
        Map<String, Assignment> known = new HashMap<>();
        for (Assignment item : todos.all()) {
            if (item.hasDetails()) known.put(item.id, item);
        }
        if (known.isEmpty()) return;
        for (Assignment item : next) {
            Assignment previous = known.get(item.id);
            if (previous != null && !item.hasDetails()) item.setDetails(previous.details());
        }
    }

    /** Writes the list down as it now stands, off the main thread. */
    private void saveWork() {
        final List<Assignment> all = new ArrayList<>(todos.all());
        final Sync.Result context = snapshot;
        work.execute(
            new Runnable() {
                @Override
                public void run() {
                    backend.saveWork(all, context);
                }
            }
        );
    }

    /**
     * Redraws a work list, and only a work list.
     *
     * A sync finishing changes nothing on the Chat tab, and redrawing that tab
     * rebuilds its compose field: doing it because a background fetch happened
     * to land would take the cursor and the keyboard's half-finished word away
     * from somebody in the middle of typing a question.
     */
    private void redrawIfListing() {
        if (openWork != null || settingsOpen || currentTab == CHAT) return;
        renderList(currentTab, listView != null && listView.hasFocus(), selectedKey());
    }

    /** An exception as a sentence, never empty. */
    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.length() == 0 ? "The network could not be reached." : message;
    }

    /** Redraws whatever is on screen, keeping the highlight where it was. */
    private void redrawCurrent() {
        if (openWork != null) {
            renderDetail();
        } else if (settingsOpen) {
            renderSettings(settingsAdapter == null ? 0 : settingsAdapter.getSelectedPosition());
        } else if (currentTab == CHAT) {
            renderChat(chatFocus());
        } else {
            renderList(currentTab, listView != null && listView.hasFocus(), selectedKey());
        }
    }

    private int chatFocus() {
        if (chatList == null) return CHAT_FOCUS_NONE;
        if (getCurrentFocus() instanceof EditText) return CHAT_FOCUS_INPUT;
        return chatList.hasFocus() ? CHAT_FOCUS_LIST : CHAT_FOCUS_NONE;
    }

    private List<AssignmentAdapter.Row> rows(int state, List<Section> sections) {
        List<AssignmentAdapter.Row> rows = new ArrayList<>();
        for (Section section : sections) {
            boolean expanded = isOpen(state, section.label);
            rows.add(
                AssignmentAdapter.Row.header(
                    section.label,
                    section.items.size(),
                    expanded
                )
            );
            if (!expanded) continue;
            if (Section.THIS_WEEK.equals(section.label) && section.items.isEmpty()) {
                rows.add(AssignmentAdapter.Row.empty());
            }
            for (Assignment item : section.items) {
                rows.add(AssignmentAdapter.Row.work(item));
            }
        }
        return rows;
    }

    private boolean isOpen(int state, String label) {
        Boolean stored = sectionOpen.get(state + ":" + label);
        return stored == null ? Section.THIS_WEEK.equals(label) : stored.booleanValue();
    }

    /**
     * Guarantees a tab opens on something, the first time it is drawn.
     *
     * This week is the default. Assigned makes one useful exception: on a
     * Sunday with nothing left this week and work due tomorrow, Next week is
     * opened so the imminent work is visible immediately. Other tabs can have
     * no This week section, in which case their first section takes over.
     *
     * Once only, though. This is a decision about how a tab opens, not a rule
     * about how it must always look, and applying it on every draw would take
     * Collapse all away from anyone who chose it by opening a section back up
     * underneath them.
     */
    private void openSomethingFirstTime(int state, List<Section> sections, long now) {
        if (!defaulted.add(Integer.valueOf(state))) return;
        if (state == Assignment.ASSIGNED && nextWeekStartsTomorrow(sections, now)) {
            sectionOpen.put(state + ":" + Section.THIS_WEEK, Boolean.FALSE);
            sectionOpen.put(state + ":" + Section.NEXT_WEEK, Boolean.TRUE);
            return;
        }
        for (Section section : sections) {
            if (isOpen(state, section.label)) return;
        }
        sectionOpen.put(state + ":" + sections.get(0).label, Boolean.TRUE);
    }

    private boolean nextWeekStartsTomorrow(List<Section> sections, long now) {
        Section thisWeek = null;
        Section nextWeek = null;
        for (Section section : sections) {
            if (Section.THIS_WEEK.equals(section.label)) thisWeek = section;
            if (Section.NEXT_WEEK.equals(section.label)) nextWeek = section;
        }
        if (thisWeek == null || !thisWeek.items.isEmpty() || nextWeek == null) return false;
        for (Assignment item : nextWeek.items) {
            if (item.due != null && Dates.daysBetween(now, item.due.longValue()) == 1) {
                return true;
            }
        }
        return false;
    }

    private int firstWork(AssignmentAdapter adapter) {
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i).item != null) return i;
        }
        return 0;
    }

    private void wireList(
        ListView list,
        final AssignmentAdapter adapter,
        final int state
    ) {
        list.setOnItemClickListener(
            new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    activate(adapter, state, position);
                }
            }
        );
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnFocusChangeListener(
            new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    if (
                        hasFocus &&
                        adapter.getCount() > 0 &&
                        adapter.getSelectedPosition() == AdapterView.INVALID_POSITION
                    ) {
                        int start = firstWork(adapter);
                        adapter.setSelectedPosition(start);
                        ((ListView) view).setSelection(start);
                    }
                }
            }
        );
        list.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    adapter.trackSelectedPosition(position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            }
        );
        // Sonim firmware variants report the physical OK key as either
        // DPAD_CENTER or ENTER. Handle activation only; arrows stay with
        // ListView's native selection and scrolling implementation.
        list.setOnKeyListener(
            new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (
                        event.getAction() != KeyEvent.ACTION_UP ||
                        (keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                            keyCode != KeyEvent.KEYCODE_ENTER &&
                            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
                    ) return false;

                    int position = adapter.getSelectedPosition();
                    if (position >= 0 && position < adapter.getCount()) {
                        activate(adapter, state, position);
                        return true;
                    }
                    return false;
                }
            }
        );
    }

    private void activate(AssignmentAdapter adapter, int state, int position) {
        AssignmentAdapter.Row row = adapter.getItem(position);
        if (row.isHeader()) {
            sectionOpen.put(state + ":" + row.label, Boolean.valueOf(!row.expanded));
            // The heading you just pressed stays under the cursor: it is named
            // rather than numbered, so it is found again wherever it has ended
            // up in the list that has just grown or shrunk beneath it.
            renderList(state, true, keyOf(row));
        } else if (!row.isEmpty()) {
            openDetail(row.item);
        }
    }

    /**
     * What a screen with nothing on it says.
     *
     * The three cheerful answers at the bottom are only true once the app knows
     * they are: an empty Missing tab means "you are caught up" if a sync
     * succeeded and "I have not looked yet" if one has not, and telling
     * somebody with an unsent assignment that they have nothing missing is the
     * worst thing this app could do.
     */
    private String emptyText(int state) {
        if (!backend.canvasReady()) {
            return "Not set up yet.\n\nPress Menu, then Settings, to add your Canvas address and service token.";
        }
        if (todos.isEmpty()) {
            if (syncing) return "Fetching your work from Canvas…";
            if (syncError != null) return syncError + "\n\nPress Menu, then Refresh, to try again.";
            if (snapshot == null) return "Nothing fetched yet.\n\nPress Menu, then Refresh.";
        }
        if (state == ASSIGNED) {
            return "Nothing assigned.\n\nWork your teachers post shows up here, soonest first.";
        }
        if (state == MISSING) {
            return "Nothing missing.\n\nEverything with a date on it has been turned in.";
        }
        return "Nothing turned in yet.\n\nWork you hand in on Canvas shows up here.";
    }

    // ------------------------------------------------------------------
    // One assignment, full screen
    // ------------------------------------------------------------------

    /**
     * Opens one assignment, and fetches its instructions if this is the first
     * time it has been opened.
     *
     * Canvas does not send an assignment's body with a list of assignments, so
     * the alternative to fetching here is a request per row at sync time, which
     * on a term's worth of work is a hundred requests to draw a screen of
     * titles. The page therefore opens on what the list already knew, with the
     * instructions arriving underneath a moment later. Once fetched they are
     * kept, so the second reading needs no signal at all.
     */
    private void openDetail(final Assignment item) {
        openWork = item;
        tabBar.setVisibility(View.GONE);
        detailError = null;
        detailLoading = !item.hasDetails();
        renderDetail();
        if (!detailLoading) return;
        work.execute(
            new Runnable() {
                @Override
                public void run() {
                    String failure = null;
                    try {
                        backend.details(item);
                    } catch (InterruptedException stopping) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (java.io.IOException e) {
                        failure = reason(e);
                    } catch (RuntimeException e) {
                        failure = "Canvas sent instructions this app could not read.";
                    }
                    final String problem = failure;
                    main.post(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
                                detailLoading = false;
                                detailError = problem;
                                // Written down so the page reads again with no
                                // signal, from the list as it stands now rather
                                // than as it stood when the fetch started.
                                if (problem == null) saveWork();
                                // Redrawn only if it is still the thing on
                                // screen: the text is kept either way, so
                                // backing out and opening it again is instant.
                                if (openWork == item) renderDetail();
                            }
                        }
                    );
                }
            }
        );
    }

    private void renderDetail() {
        if (openWork == null) return;
        Assignment item = openWork;
        long stamp = System.currentTimeMillis();
        int state = item.state(stamp);

        ScrollView scroll = new ScrollView(this);
        // The page scrolls with up and down before focus ever reaches the
        // button at the foot of it, which is what lets a long assignment be
        // read on a keypad without tabbing through it.
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        LinearLayout root = vertical();

        root.addView(heading(item.title));
        root.addView(subheading(item.course));
        root.addView(divider());

        root.addView(
            metric(
                "Due",
                item.due == null
                    ? "No due date"
                    : Dates.dayAndTime(item.due.longValue(), stamp),
                state == MISSING ? color(R.color.overdue) : color(R.color.text)
            )
        );
        root.addView(
            metric(
                "Status",
                statusLabel(item, stamp),
                state == MISSING
                    ? color(R.color.overdue)
                    : state == DONE ? color(R.color.due_soon) : color(R.color.text)
            )
        );
        if (item.done && item.turnedIn != null) {
            root.addView(
                metric(
                    "Turned in",
                    Dates.dayAndTime(item.turnedIn.longValue(), stamp),
                    color(R.color.text)
                )
            );
        }
        // A score is shown whenever there is one, not only on work that counts
        // as done: a zero entered against something never handed in is filed
        // under Missing, and it is exactly the number you opened the page for.
        if (item.points > 0 || item.score != null) {
            String score = item.score != null
                ? Assignment.number(item.score.doubleValue())
                    + (item.points > 0 ? " of " + Assignment.number(item.points) : "")
                : Assignment.number(item.points) + " points";
            root.addView(metric("Score", score, color(R.color.text)));
        }
        root.addView(divider());

        if (item.hasDetails()) {
            new Markdown(this).into(root, item.details());
        } else {
            root.addView(
                emptyMessage(
                    detailLoading
                        ? "Fetching the instructions…"
                        : detailError != null
                            ? detailError
                            : "No instructions were posted."
                )
            );
        }

        scroll.addView(
            root,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );
        replaceContent(scroll);
        scroll.requestFocus();
    }

    private String statusLabel(Assignment item, long now) {
        // Excused counts as done everywhere else, but on the one screen with
        // room to say why, "Turned in" would be wrong: you did not turn it in.
        if (item.excused) return "Excused";
        if (item.done) return item.early(now) ? "Done early" : "Turned in";
        if (item.state(now) == MISSING) return "Missing";
        return "Assigned";
    }

    private void closeDetail() {
        openWork = null;
        showTab(currentTab, true);
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /**
     * The transcript, plus the answer being composed.
     *
     * The pause while an answer is worked out is a turn like any other, so it
     * queues at the bottom and scrolls with the rest instead of being a
     * separate thing pinned somewhere. The assistant gets one area for its
     * whole response. It begins as an ellipsis, then fills with streamed text
     * as soon as any arrives.
     *
     * It is built rather than stored: this bubble is not a turn yet. It must
     * not be saved, must not be copied from, and is thrown away whole if the
     * turn fails.
     */
    private List<ChatTurn> chatTurns() {
        List<ChatTurn> turns = new ArrayList<>(transcript);
        if (!agentThinking) return turns;
        if (compacting) {
            turns.add(ChatTurn.compacting(streamingAnswerWhen));
            return turns;
        }
        turns.add(new ChatTurn(
            false,
            streamingAnswer.length() == 0 ? "…" : streamingAnswer.toString(),
            streamingAnswerWhen
        ));
        return turns;
    }

    /**
     * Repaints the rows without rebuilding the screen.
     *
     * Rebuilding the screen for every streamed fragment would take the cursor,
     * the draft and the T9 keyboard's half-finished word away. Only the adapter
     * changes here, so the field below the list is untouched.
     */
    private void updateChat() {
        if (chatAdapter == null || chatList == null) return;
        if (currentTab != CHAT || openWork != null || settingsOpen) return;
        // Composer focus is an unconditional bottom pin. Otherwise, preserve
        // scrollback unless the final row was fully visible before this change.
        boolean follow =
            chatList.isPinnedToBottom() || chatIsAtBottom(chatList, chatAdapter);
        chatAdapter.replace(chatTurns());
        if (follow) chatList.pinToBottomOnce();
    }

    private void renderChat(int focus) {
        LinearLayout root = vertical();

        final ChatAdapter adapter = new ChatAdapter(this);
        adapter.replace(chatTurns());
        final ChatListView list = chatListView(adapter);
        chatAdapter = adapter;
        chatList = list;
        list.setBackgroundColor(color(R.color.chat_page));
        // Bubbles carry their own spacing. A rule between them would be a
        // second way of saying where one message ends.
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        list.setSmoothScrollbarEnabled(false);
        list.setVerticalFadingEdgeEnabled(false);
        list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        list.setPadding(0, 0, 0, dp(8));
        list.setClipToPadding(false);
        root.addView(list, weighted());

        /* A compact three-line compose field leaves the transcript most of the
         * screen. OK sends, so the 240px-wide layout needs no separate button. */
        final EditText input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(color(R.color.input_text));
        input.setHintTextColor(color(R.color.input_hint));
        input.setBackgroundResource(R.drawable.edittext_border);
        input.setPadding(dp(8), dp(8), dp(8), dp(8));
        input.setHint(getString(R.string.chat_hint));
        input.setText(chatDraft);
        input.setSelection(chatDraft.length());
        input.setMaxLines(3);
        /*
         * The T9 candidate strip along the bottom of the screen is not
         * automatic: the keyboard decides whether to predict from what the
         * field asks for, and without AUTO_CORRECT it drops back to multitap
         * with no suggestions at all. SHORT_MESSAGE says what is being written
         * here, and MULTI_LINE lets a long question wrap over three lines
         * instead of scrolling sideways through a 240px slot.
         */
        input.setInputType(
            InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT |
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        input.setImeOptions(
            EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION
        );
        // The draft is kept here rather than read off the view, because every
        // reply rebuilds this screen and the view holding it goes with it.
        input.addTextChangedListener(
            new TextWatcher() {
                @Override
                public void beforeTextChanged(
                    CharSequence text,
                    int start,
                    int count,
                    int after
                ) {}

                @Override
                public void onTextChanged(
                    CharSequence text,
                    int start,
                    int before,
                    int count
                ) {}

                @Override
                public void afterTextChanged(Editable text) {
                    chatDraft = text.toString();
                    if (input.hasFocus()) list.pinToBottomOnce();
                }
            }
        );
        input.setOnEditorActionListener(
            new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(
                    TextView view,
                    int actionId,
                    KeyEvent event
                ) {
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
            }
        );
        /*
         * OK sends. This handset's keypad maps OK to ENTER (`key 352 ENTER` in
         * its key layout), not to DPAD_CENTER, so both are handled.
         *
         * It sends on the press rather than the release, which is not the
         * usual way round and is load bearing here. With the T9 keyboard up,
         * the first ENTER release never reaches the app at all: the keyboard
         * takes it to finish the word it is composing, and only the press
         * arrives. Sending on the release therefore cost two presses, or three
         * with a word still in the air. The press is delivered every time.
         *
         * Both halves are swallowed regardless, so that ENTER cannot also drop
         * a newline into a field that is set to accept them.
         */
        final ListView transcript = list;
        int transcriptId = View.generateViewId();
        int inputId = View.generateViewId();
        list.setId(transcriptId);
        input.setId(inputId);
        list.setNextFocusDownId(inputId);
        input.setNextFocusUpId(transcriptId);
        input.setOnFocusChangeListener(
            new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    // Reaching the composer proves the transcript was read to
                    // its end. Keep the last row's bottom fixed while typing,
                    // including keyboard, line-wrap and streaming relayouts.
                    list.setPinnedToBottom(hasFocus);
                }
            }
        );
        input.setOnKeyListener(
            new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    /*
                     * Up out of the box, once there is no line of your own
                     * above the cursor to go to.
                     *
                     * The field holds three lines, and the platform spends a
                     * press on each of them plus one more that does nothing at
                     * all: on the first line it answers up by moving the cursor
                     * to the start of the text, silently, and only the press
                     * after that gives up the focus. Reaching the transcript
                     * over a two line question therefore took three presses,
                     * one of which changed nothing on screen. Handled here, it
                     * takes one press per line of what you have written and no
                     * dead ones.
                     */
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (event.getAction() != KeyEvent.ACTION_DOWN) {
                            return false;
                        }
                        if (!onFirstLine((EditText) view)) return false;
                        transcript.requestFocus();
                        return true;
                    }

                    if (
                        keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                        keyCode != KeyEvent.KEYCODE_ENTER &&
                        keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
                    ) return false;
                    if (
                        event.getAction() == KeyEvent.ACTION_DOWN &&
                        event.getRepeatCount() == 0
                    ) {
                        send(((EditText) view).getText().toString());
                    }
                    return true;
                }
            }
        );

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(dp(4), dp(2), dp(4), dp(4));
        root.addView(input, inputParams);

        wireChatList(list, adapter);
        replaceContent(root);
        restoreChatScrollPosition(list, adapter);
        if (focus == CHAT_FOCUS_INPUT) input.requestFocus();
        else if (focus == CHAT_FOCUS_LIST) list.requestFocus();
    }

    /**
     * Records intent rather than a pixel offset. MAX_VALUE means "the end", so
     * returning after the transcript grows resolves against its new count.
     */
    private void saveChatScrollPosition() {
        if (chatList == null || chatAdapter == null || chatAdapter.getCount() == 0) {
            return;
        }
        savedChatScrollPosition =
            chatList.getLastVisiblePosition() >= chatAdapter.getCount() - 1
                ? Integer.MAX_VALUE
                : chatList.getFirstVisiblePosition();
    }

    /** Applies a saved position immediately after the adapter has its data. */
    private void restoreChatScrollPosition(ChatListView list, ChatAdapter adapter) {
        int count = adapter.getCount();
        if (count == 0) {
            savedChatScrollPosition = AdapterView.INVALID_POSITION;
            return;
        }
        int saved = savedChatScrollPosition;
        savedChatScrollPosition = AdapterView.INVALID_POSITION;
        int position = saved == Integer.MAX_VALUE
            ? count - 1
            : saved >= 0 ? Math.min(saved, count - 1) : count - 1;
        list.setSelection(position);
        if (saved == Integer.MAX_VALUE || saved == AdapterView.INVALID_POSITION) {
            list.pinToBottomOnce();
        }
    }

    /** Whether the final row is fully visible inside the padded viewport. */
    private boolean chatIsAtBottom(ListView list, ChatAdapter adapter) {
        int target = adapter.getCount() - 1;
        int last = list.getLastVisiblePosition();
        if (target < 0 || last != target) return false;
        View row = list.getChildAt(last - list.getFirstVisiblePosition());
        return row != null &&
            row.getBottom() <= list.getHeight() - list.getPaddingBottom();
    }

    /**
     * Stepping through the transcript.
     *
     * Up out of the box puts the highlight on the newest message and walks
     * back from there; down off the newest one puts it back in the box. The
     * highlight is dropped whenever the list loses focus, because a message
     * left looking current while you are typing is claiming a keypress it
     * would not get.
     */
    private void wireChatList(
        final ChatListView list,
        final ChatAdapter adapter
    ) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnFocusChangeListener(
            new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    if (adapter.getCount() == 0) return;
                    if (hasFocus) {
                        list.setPinnedToBottom(false);
                        if (
                            adapter.getSelectedPosition() ==
                            AdapterView.INVALID_POSITION
                        ) {
                            int selected = ((ListView) view).getSelectedItemPosition();
                            if (selected == AdapterView.INVALID_POSITION) {
                                selected = ((ListView) view).getLastVisiblePosition();
                            }
                            adapter.trackSelectedPosition(selected);
                            View selectedView = ((ListView) view).getSelectedView();
                            if (selectedView != null) selectedView.setSelected(true);
                        }
                    } else {
                        View selectedView = ((ListView) view).getSelectedView();
                        if (selectedView != null) selectedView.setSelected(false);
                        adapter.trackSelectedPosition(AdapterView.INVALID_POSITION);
                    }
                }
            }
        );
        list.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    if (parent.hasFocus()) adapter.trackSelectedPosition(position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            }
        );
        list.setOnItemClickListener(
            new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    showMessageMenu(adapter.getItem(position), position);
                }
            }
        );
        list.setOnKeyListener(
            new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (
                        event.getAction() != KeyEvent.ACTION_UP ||
                        (keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                            keyCode != KeyEvent.KEYCODE_ENTER &&
                            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
                    ) return false;

                    int position = adapter.getSelectedPosition();
                    if (position >= 0 && position < adapter.getCount()) {
                        showMessageMenu(adapter.getItem(position), position);
                        return true;
                    }
                    return false;
                }
            }
        );
    }

    /**
     * What can be done with one saved message.
     *
     * A press of OK opens the action for the selected area, which is the only
     * way to get at the text of an answer without typing it out again. The
     * temporary streaming row can be copied, but it is not yet a point the
     * saved conversation can rewind to.
     */
    private void showMessageMenu(final ChatTurn turn, final int position) {
        if (turn == null || !turn.isMessage()) return;
        List<String> items = new ArrayList<>();
        items.add("Copy text");
        if (
            position >= 0 &&
            position < transcript.size() &&
            transcript.get(position) == turn
        ) items.add("Rewind to here");
        final String[] labels = items.toArray(new String[items.size()]);
        new AlertDialog.Builder(this)
            .setTitle(turn.fromUser ? "Your message" : "Message")
            .setItems(
                labels,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (labels[which].equals("Copy text")) {
                            copyToClipboard(turn.text);
                        } else {
                            rewindChat(position);
                        }
                    }
                }
            )
            .show();
    }

    /**
     * Makes the selected saved message the end of the conversation.
     *
     * Rebuilding the agent from the shortened transcript is what removes a
     * compact summary when its boundary was in the discarded tail. A user
     * message is removed too and moved back into the composer; an assistant
     * message remains selected at the new end of the transcript.
     */
    private void rewindChat(int position) {
        if (
            position < 0 ||
            position >= transcript.size() ||
            !transcript.get(position).isMessage()
        ) return;

        chatGeneration++;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        String draft = ChatHistory.rewind(transcript, position);
        if (draft != null) chatDraft = draft;
        backend.restore(transcript);
        backend.saveTranscript(transcript);
        renderChat(draft == null ? CHAT_FOCUS_LIST : CHAT_FOCUS_INPUT);
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(
            CLIPBOARD_SERVICE
        );
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Message", text));
        toast("Copied");
    }

    private boolean clipboardHasText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(
            CLIPBOARD_SERVICE
        );
        return (
            clipboard != null &&
            clipboard.hasPrimaryClip() &&
            clipboard.getPrimaryClip().getItemCount() > 0
        );
    }

    /**
     * Paste, from the menu rather than from a long press.
     *
     * It goes through the draft rather than through the field, because the
     * field is rebuilt on every redraw and the draft is what survives that.
     * Text lands at the end, which is where a cursor in an empty or
     * half-written question already is.
     */
    private void pasteIntoInput() {
        CharSequence pasted = clipboardText();
        if (pasted == null) return;
        chatDraft = chatDraft + pasted.toString();
        renderChat(CHAT_FOCUS_INPUT);
    }

    /** What is on the clipboard as text, or null when there is nothing usable. */
    private CharSequence clipboardText() {
        if (!clipboardHasText()) return null;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        return pasted == null || pasted.length() == 0 ? null : pasted;
    }

    private void send(String text) {
        final String question = text.trim();
        if (question.length() == 0) return;
        if (agentThinking) {
            // One turn at a time. A second question sent into a turn already
            // running would interleave two answers into one transcript.
            toast("Still answering");
            return;
        }
        transcript.add(new ChatTurn(true, question, System.currentTimeMillis()));
        chatDraft = "";

        if (!backend.chatReady()) {
            transcript.add(new ChatTurn(false, greeting(), System.currentTimeMillis()));
            backend.saveTranscript(transcript);
            renderChat(CHAT_FOCUS_INPUT);
            return;
        }

        agentThinking = true;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = System.currentTimeMillis();
        backend.saveTranscript(transcript);
        renderChat(CHAT_FOCUS_INPUT);
        ask(question, chatGeneration);
    }

    /**
     * Runs one turn.
     *
     * Everything the agent reports comes back on a background thread, so each
     * callback hops to the main thread before it touches anything the screen
     * is drawn from.
     */
    private void ask(final String question, final int generation) {
        final Agent.Activity activity = new Agent.Activity() {
            @Override
            public void text(final String delta) {
                if (delta == null || delta.length() == 0) return;
                main.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (!agentThinking || generation != chatGeneration) return;
                            streamingAnswer.append(delta);
                            updateChat();
                        }
                    }
                );
            }

            @Override
            public void reasoning(String delta) {
                // Private reasoning is not part of the conversation transcript.
            }

            @Override
            public void note(String line) {}
        };

        chatWork.execute(
            new Runnable() {
                @Override
                public void run() {
                    String reply = null;
                    String failure = null;
                    try {
                        reply = backend.ask(question, activity);
                    } catch (InterruptedException stopping) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (java.io.IOException e) {
                        failure = reason(e);
                    } catch (RuntimeException e) {
                        failure = "That answer came back in a form this app could not read.";
                    }
                    final String answer = reply;
                    final String problem = failure;
                    main.post(
                        new Runnable() {
                            @Override
                            public void run() {
                                answered(answer, problem, generation);
                            }
                        }
                    );
                }
            }
        );
    }

    private void answered(String answer, String problem, int generation) {
        // The conversation this belonged to has been cleared out from under it.
        if (isFinishing() || generation != chatGeneration) return;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        if (problem != null) {
            transcript.add(new ChatTurn(false, problem, System.currentTimeMillis()));
        } else if (answer != null && answer.length() > 0) {
            transcript.add(new ChatTurn(false, answer, System.currentTimeMillis()));
        }
        backend.saveTranscript(transcript);
        // Completion replaces the live row with the saved answer; a failure
        // replaces it with an explanation. Keep this in the same adapter and
        // bottom-policy path as streaming updates so scrollback never jumps.
        if (currentTab == CHAT && openWork == null && !settingsOpen) {
            updateChat();
        }
    }

    // ------------------------------------------------------------------
    // Keys and the menu
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // First press only. Auto-repeat from a held d-pad would cycle the tabs
        // as fast as the key fires and redraw every one of them on the way.
        //
        // A detail page takes no part in this: with the tab bar hidden, left
        // and right sliding to another tab underneath it would be navigation
        // by accident. Neither does a text field, where the same keys are how
        // you move through what you have typed.
        if (
            event.getAction() == KeyEvent.ACTION_DOWN &&
            event.getRepeatCount() == 0 &&
            openWork == null &&
            !settingsOpen &&
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
     * Whether left or right belongs to a text cursor rather than to the tabs.
     *
     * Inside typed text it belongs to the cursor, which is what anyone who has
     * just written a question expects. At either end of it, it goes back to
     * being a tab key: pressing right when the cursor is already as far right
     * as it goes cannot mean "further right", so it can safely mean the next
     * tab. Without that, a box with something in it is a room with no door,
     * because the only way out would be to press up first and nothing on the
     * screen says so.
     *
     * A selection counts as inside the text. So does an empty field in
     * neither direction, since there is no cursor in it worth moving.
     */
    /**
     * Whether the cursor sits on the first line of what has been typed, which
     * is the point at which up stops being a text key and becomes a way out of
     * the field. An empty field is always on its first line.
     */
    private boolean onFirstLine(EditText field) {
        Layout laid = field.getLayout();
        if (laid == null) return true;
        return laid.getLineForOffset(field.getSelectionStart()) == 0;
    }

    private boolean cursorOwns(int keyCode) {
        View focus = getCurrentFocus();
        if (!(focus instanceof EditText)) return false;
        EditText field = (EditText) focus;
        int length = field.getText().length();
        if (length == 0) return false;
        int start = field.getSelectionStart();
        int end = field.getSelectionEnd();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return !(start == 0 && end == 0);
        }
        return !(start == length && end == length);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        // The XP3's clear key is the one people reach for to back out, and it
        // arrives as either of these depending on the firmware.
        if (
            (keyCode == KeyEvent.KEYCODE_CLEAR || keyCode == KeyEvent.KEYCODE_DEL) &&
            (openWork != null || settingsOpen) &&
            !(getCurrentFocus() instanceof EditText)
        ) {
            if (openWork != null) closeDetail();
            else closeSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (openWork != null) closeDetail();
        else if (settingsOpen) closeSettings();
        else super.onBackPressed();
    }

    /**
     * Stops the background threads when the screen goes for good.
     *
     * They are daemon threads, so the process would exit regardless; this is so
     * that a turn still running against the network is not left holding a
     * socket open behind an app somebody has closed.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
        chatWork.shutdownNow();
    }

    private static final String STOP = "Stop waiting";
    private static final String EXPAND = "Expand all sections";
    private static final String COLLAPSE = "Collapse all sections";
    private static final String SIGN_OUT = "Sign out";
    private static final String RESTORE = "Use the bundled setup";

    private void showMenu() {
        if (openWork != null || settingsOpen) return;
        // Paste only appears when there is something to paste, which is the
        // difference between a menu that answers a question and one that
        // offers a greyed-out row as an answer.
        List<String> items = new ArrayList<>();
        if (currentTab == CHAT) {
            if (agentThinking && !compacting) items.add(STOP);
            if (clipboardHasText()) items.add(PASTE);
            items.add(COMPACT);
            items.add(CLEAR);
        } else {
            items.add(REFRESH);
            items.add(EXPAND);
            items.add(COLLAPSE);
        }
        items.add(SETTINGS);
        items.add(ABOUT);
        final String[] labels = items.toArray(new String[items.size()]);
        new AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(
                labels,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        chose(labels[which]);
                    }
                }
            )
            .show();
    }

    /** Dispatch by label rather than by index: the menu differs per tab. */
    private void chose(String label) {
        if (label.equals(PASTE)) pasteIntoInput();
        else if (label.equals(STOP)) stopWaiting();
        else if (label.equals(COMPACT)) compactChat();
        else if (label.equals(CLEAR)) clearChat();
        else if (label.equals(REFRESH)) refresh(true);
        else if (label.equals(EXPAND)) setAllSections(true);
        else if (label.equals(COLLAPSE)) setAllSections(false);
        else if (label.equals(SETTINGS)) openSettings();
        else showAbout();
    }

    /**
     * Gives up on an answer that is taking too long.
     *
     * A turn holds the tab against a second question until it finishes, and one
     * that is reading six courses over a bad signal can hold it for minutes.
     * The request itself cannot be taken back — it is inside a blocking read on
     * a worker — so this stops waiting for it rather than stopping it: the
     * conversation moves on, and the answer is discarded on arrival.
     */
    private void stopWaiting() {
        if (!agentThinking) return;
        chatGeneration++;
        agentThinking = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        transcript.add(
            new ChatTurn(false, "Stopped waiting for that one.", System.currentTimeMillis())
        );
        backend.saveTranscript(transcript);
        renderChat(CHAT_FOCUS_INPUT);
    }

    /**
     * Replaces the model's working history with a summary while leaving the
     * visible transcript intact. The saved compact turn holds that summary,
     * but the adapter renders it as a boundary rather than as somebody's words.
     */
    private void compactChat() {
        if (agentThinking) {
            toast("Still answering");
            return;
        }
        if (transcript.isEmpty()) {
            toast("Nothing to compact");
            return;
        }
        if (!backend.chatReady()) {
            toast("Set up a model first");
            return;
        }

        agentThinking = true;
        compacting = true;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = System.currentTimeMillis();
        renderChat(CHAT_FOCUS_INPUT);
        final int generation = chatGeneration;

        chatWork.execute(new Runnable() {
            @Override
            public void run() {
                String reply = null;
                String failure = null;
                try {
                    reply = backend.summarize(Agent.Activity.NOOP);
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.io.IOException e) {
                    failure = reason(e);
                } catch (RuntimeException e) {
                    failure = "That summary came back in a form this app could not read.";
                }
                final String summary = reply;
                final String problem = failure;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        compacted(summary, problem, generation);
                    }
                });
            }
        });
    }

    private void compacted(String summary, String problem, int generation) {
        // Summarization never mutates model history, so an obsolete result can
        // simply disappear after Clear this chat or signing out.
        if (isFinishing() || generation != chatGeneration) return;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        if (problem != null) {
            updateChat();
            alert("Could not compact chat", problem);
            return;
        }
        transcript.add(ChatTurn.compaction(summary, System.currentTimeMillis()));
        backend.restore(transcript);
        backend.saveTranscript(transcript);
        updateChat();
    }

    private void clearChat() {
        // Anything still in flight belongs to the conversation being thrown
        // away, so its answer must not land in the new one.
        chatGeneration++;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        transcript.clear();
        backend.resetChat();
        backend.saveTranscript(transcript);
        renderChat(CHAT_FOCUS_INPUT);
    }

    private void setAllSections(boolean expanded) {
        for (Section section : todos.sections(currentTab, System.currentTimeMillis())) {
            sectionOpen.put(
                currentTab + ":" + section.label,
                Boolean.valueOf(expanded)
            );
        }
        renderList(currentTab, true, null);
    }

    private void showAbout() {
        String updated = snapshot == null
            ? "Nothing has been fetched yet."
            : "Work list fetched "
                + Dates.dayAndTime(snapshot.fetchedAt, System.currentTimeMillis()) + ".";
        new AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage(
                "Canvas, for the Sonim XP3.\n\n" +
                "Your assignments are read from Canvas and kept on this phone " +
                "so they can be read without a signal. Nothing is ever written " +
                "back to Canvas: this app cannot turn work in or change a grade.\n\n" +
                updated + "\n\n" +
                "Chat sends your question and what it reads from Canvas to the " +
                "model provider set in Settings.\n\n" +
                "Left and right move between tabs. OK opens a section or an " +
                "assignment. Back leaves a page. Menu has refresh and settings."
            )
            .setPositiveButton("OK", null)
            .show();
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private void openSettings() {
        if (currentTab == CHAT) saveChatScrollPosition();
        settingsOpen = true;
        openWork = null;
        // Same reasoning as a detail page: the tab bar is 36dp of a 260dp
        // screen, and this page is a list that wants every row it can get.
        tabBar.setVisibility(View.GONE);
        renderSettings(0);
    }

    private void closeSettings() {
        settingsOpen = false;
        settingsAdapter = null;
        showTab(currentTab, true);
        // Details changed here are the reason a fetch would now work.
        refreshIfStale();
    }

    private List<SettingsAdapter.Row> settingRows() {
        Config config = backend.config();
        List<SettingsAdapter.Row> rows = new ArrayList<>();
        rows.add(row(config, Config.CANVAS_URL, "Canvas address", false));
        rows.add(row(config, Config.COOKIE_URL, "Cookie service", false));
        rows.add(row(config, Config.COOKIE_TOKEN, "Service token", true));
        rows.add(
            new SettingsAdapter.Row(
                Config.PROVIDER,
                "Model provider",
                config.claude() ? "Claude Pro/Max login"
                    : config.codex() ? "ChatGPT (Codex) login" : "OpenAI API key",
                false
            )
        );
        rows.add(row(config, Config.MODEL, "Model", false));
        if (config.claude()) {
            rows.add(new SettingsAdapter.Row(
                null,
                CLAUDE_LOGIN,
                config.chatReady() ? "Signed in" : "Press OK to begin",
                false
            ));
        } else if (config.codex()) {
            // The refresh token leads, because it is the one that matters: the
            // access token below it is renewed from this every ten days or so.
            rows.add(row(config, Config.REFRESH_TOKEN, "Refresh token", true));
            rows.add(row(config, Config.ACCESS_TOKEN, "Access token", true));
            rows.add(row(config, Config.ACCOUNT_ID, "Account ID", true));
        } else {
            rows.add(row(config, Config.API_KEY, "API key", true));
        }
        rows.add(row(config, Config.TIMEZONE, "Time zone", false));
        if (backend.hasBundledSetup()) {
            rows.add(new SettingsAdapter.Row(null, RESTORE, "Put this build's own setup back", false));
        }
        rows.add(new SettingsAdapter.Row(null, SIGN_OUT, "Remove everything saved here", false));
        return rows;
    }

    private static SettingsAdapter.Row row(Config config, String field, String label, boolean secret) {
        return new SettingsAdapter.Row(field, label, config.get(field), secret);
    }

    private void renderSettings(int select) {
        final SettingsAdapter adapter = new SettingsAdapter(this);
        adapter.replace(settingRows());
        final ListView list = listView(adapter);

        LinearLayout root = vertical();
        root.addView(heading("Settings"));
        root.addView(
            subheading(
                backend.chatReady()
                    ? "Work and chat are set up"
                    : backend.canvasReady() ? "Work is set up; chat needs a model" : "Not set up yet"
            )
        );
        root.addView(divider());
        root.addView(list, weighted());
        wireSettings(list, adapter);
        replaceContent(root);
        settingsAdapter = adapter;

        int start = select >= 0 && select < adapter.getCount() ? select : 0;
        adapter.setSelectedPosition(start);
        list.setSelection(start);
        list.requestFocus();
    }

    private void wireSettings(final ListView list, final SettingsAdapter adapter) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    adapter.trackSelectedPosition(position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            }
        );
        list.setOnItemClickListener(
            new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    editSetting(adapter.getItem(position), position);
                }
            }
        );
        list.setOnKeyListener(
            new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (
                        event.getAction() != KeyEvent.ACTION_UP ||
                        (keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                            keyCode != KeyEvent.KEYCODE_ENTER &&
                            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
                    ) return false;
                    int position = adapter.getSelectedPosition();
                    if (position < 0 || position >= adapter.getCount()) return false;
                    editSetting(adapter.getItem(position), position);
                    return true;
                }
            }
        );
    }

    /**
     * Editing one setting.
     *
     * Paste is a button on this dialog rather than a menu item, because these
     * are the fields nobody is going to type: a service token is 40 random
     * characters and an API key is longer, and entering either on a keypad is
     * the difference between an app somebody sets up and one they give up on.
     * The intended route is a message or an email to the phone, then copy and
     * paste. Typing still works for the short ones.
     */
    private void editSetting(final SettingsAdapter.Row row, final int position) {
        if (row.field == null) {
            if (SIGN_OUT.equals(row.label)) confirmSignOut();
            else if (CLAUDE_LOGIN.equals(row.label)) startClaudeLogin(position);
            else restoreBundledSetup();
            return;
        }
        if (Config.PROVIDER.equals(row.field)) {
            chooseProvider(position);
            return;
        }

        final EditText field = new EditText(this);
        field.setTextSize(14);
        field.setSingleLine(true);
        boolean address = Config.CANVAS_URL.equals(row.field) || Config.COOKIE_URL.equals(row.field);
        // Never CAP_SENTENCES or AUTO_CORRECT here: a key with its first letter
        // capitalised or a hostname the keyboard has helpfully corrected is a
        // credential that silently does not work.
        field.setInputType(
            InputType.TYPE_CLASS_TEXT |
            (address
                ? InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        );
        field.setText(backend.config().get(row.field));
        field.setSelection(field.getText().length());

        // A bare EditText in a dialog sits flush against its edges, which at
        // this width leaves the text touching the frame on both sides.
        LinearLayout holder = vertical();
        holder.setPadding(dp(12), dp(6), dp(12), dp(2));
        holder.addView(
            field,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(row.label)
            .setView(holder)
            .setPositiveButton(
                "Save",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applySetting(row.field, field.getText().toString(), position);
                    }
                }
            )
            .setNegativeButton("Cancel", null);
        if (clipboardHasText()) builder.setNeutralButton("Paste", null);

        final AlertDialog dialog = builder.create();
        dialog.show();

        /*
         * OK saves, without going near the Save button.
         *
         * With a keyboard up on a 320px screen the dialog's buttons are pushed
         * off the bottom, so reaching Save means dismissing the keyboard first
         * and then stepping down onto it: three deliberate presses to confirm a
         * value that has already been typed.
         *
         * Unlike the chat compose field, this acts on the release. That field
         * has to use the press because the T9 keyboard swallows the first
         * ENTER release to finish the word it is composing — but these fields
         * turn prediction off, so there is never a word in the air and the
         * release always arrives. It matters which way round: dismissing on the
         * press leaves the release to be delivered to the settings list
         * underneath, which reads it as OK and opens this dialog straight back
         * up. Both halves are swallowed either way, so ENTER cannot also reach
         * the field itself.
         */
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        field.setOnKeyListener(
            new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (
                        keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                        keyCode != KeyEvent.KEYCODE_ENTER &&
                        keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
                    ) return false;
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        dialog.dismiss();
                        applySetting(row.field, field.getText().toString(), position);
                    }
                    return true;
                }
            }
        );
        field.setOnEditorActionListener(
            new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                    if (actionId != EditorInfo.IME_ACTION_DONE && actionId != EditorInfo.IME_ACTION_GO) {
                        return false;
                    }
                    dialog.dismiss();
                    applySetting(row.field, view.getText().toString(), position);
                    return true;
                }
            }
        );

        Button paste = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (paste != null) {
            // Wired after show() so that pasting does not also dismiss the
            // dialog, which is what a plain button listener would do.
            paste.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        CharSequence text = clipboardText();
                        if (text == null) return;
                        field.setText(text.toString().trim());
                        field.setSelection(field.getText().length());
                    }
                }
            );
        }
    }

    private void chooseProvider(final int position) {
        final String[] values = {
            Config.PROVIDER_API, Config.PROVIDER_CODEX, Config.PROVIDER_CLAUDE
        };
        new AlertDialog.Builder(this)
            .setTitle("Model provider")
            .setItems(
                new String[] {
                    "OpenAI API key", "ChatGPT (Codex) login", "Claude Pro/Max login"
                },
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applySetting(Config.PROVIDER, values[which], position);
                    }
                }
            )
            .show();
    }

    /** Opens Pi's manual-callback Claude OAuth flow in the phone's browser. */
    private void startClaudeLogin(final int position) {
        final ClaudeAuth.Login login;
        try {
            login = backend.beginClaudeLogin();
        } catch (RuntimeException e) {
            alert("Could not sign in", reason(e));
            return;
        }

        final EditText code = new EditText(this);
        code.setTextSize(13);
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        code.setHint("Final callback URL or code");

        LinearLayout holder = vertical();
        holder.setPadding(dp(12), dp(4), dp(12), dp(2));
        TextView help = new TextView(this);
        help.setTextSize(12);
        help.setText("Finish in the browser, copy its final localhost URL, then return and paste it here.");
        holder.addView(help);
        holder.addView(code, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle("Claude Pro/Max sign-in")
            .setView(holder)
            .setPositiveButton("Finish", null)
            .setNegativeButton("Cancel", null)
            // The callback is copied after this dialog is created and the
            // browser opens, so Paste must exist even if the clipboard is
            // empty at the beginning of the flow.
            .setNeutralButton("Paste", null);
        final AlertDialog dialog = builder.create();
        dialog.show();

        Button finish = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        finish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String input = code.getText().toString().trim();
                if (input.length() == 0) {
                    toast("Paste the callback URL first");
                    return;
                }
                dialog.dismiss();
                toast("Finishing Claude sign-in");
                work.execute(new Runnable() {
                    @Override
                    public void run() {
                        String failure = null;
                        try {
                            backend.finishClaudeLogin(login, input);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (java.io.IOException e) {
                            failure = reason(e);
                        } catch (RuntimeException e) {
                            failure = "The Claude login response could not be read.";
                        }
                        final String problem = failure;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (problem != null) {
                                    alert("Could not sign in", problem);
                                    return;
                                }
                                backend.restore(transcript);
                                toast("Signed in to Claude");
                                if (settingsOpen) renderSettings(position);
                            }
                        });
                    }
                });
            }
        });
        Button paste = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (paste != null) paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                CharSequence text = clipboardText();
                if (text == null) return;
                code.setText(text.toString().trim());
                code.setSelection(code.getText().length());
            }
        });

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(login.url())));
        } catch (RuntimeException e) {
            dialog.dismiss();
            alert("No browser", "This phone could not open the Claude sign-in page.");
        }
    }

    private void applySetting(String field, String value, int position) {
        Config next;
        try {
            next = backend.config().with(field, value);
            // Codex and Claude both use rotating access/refresh pairs, but
            // they are not interchangeable. Never let a provider switch make
            // one service's token look like a saved login to the other.
            if (Config.PROVIDER.equals(field)
                && !backend.config().get(Config.PROVIDER).equals(value)) {
                next = next
                    .with(Config.MODEL, "")
                    .with(Config.ACCESS_TOKEN, "")
                    .with(Config.REFRESH_TOKEN, "")
                    .with(Config.ACCOUNT_ID, "")
                    .with(Config.EXPIRES_AT, "");
            }
        } catch (IllegalArgumentException e) {
            alert("Not saved", e.getMessage());
            return;
        }
        try {
            backend.saveSettings(next);
        } catch (java.io.IOException e) {
            alert("Not saved", "Could not write to this phone's storage.");
            return;
        }
        // Saving rebuilds the agent, so the conversation on screen has to be
        // put back into it or the next question is answered out of context.
        backend.restore(transcript);

        // A different Canvas account means the work on screen belongs to
        // somebody else. Drop it rather than showing it until a sync replaces
        // it row by row.
        if (Config.CANVAS_URL.equals(field) || Config.COOKIE_URL.equals(field)
            || Config.COOKIE_TOKEN.equals(field)) {
            snapshot = null;
            syncError = null;
            todos.replace(new ArrayList<Assignment>());
            defaulted.clear();
        }
        renderSettings(position);
    }

    /** Puts the build's own baked-in setup back. */
    private void restoreBundledSetup() {
        try {
            backend.restoreBundledSetup();
        } catch (java.io.IOException e) {
            alert("Not restored", "Could not write to this phone's storage.");
            return;
        } catch (RuntimeException e) {
            alert("Not restored", "This build's bundled setup could not be read.");
            return;
        }
        backend.restore(transcript);
        snapshot = null;
        syncError = null;
        todos.replace(new ArrayList<Assignment>());
        defaulted.clear();
        toast("Bundled setup restored");
        renderSettings(0);
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
            .setTitle(SIGN_OUT)
            .setMessage(
                "Remove the Canvas address, the tokens, the saved work list " +
                "and this chat from this phone?"
            )
            .setPositiveButton(
                SIGN_OUT,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        chatGeneration++;
                        agentThinking = false;
                        compacting = false;
                        streamingAnswer.setLength(0);
                        streamingAnswerWhen = 0L;
                        backend.signOut();
                        snapshot = null;
                        syncError = null;
                        todos.replace(new ArrayList<Assignment>());
                        defaulted.clear();
                        transcript.clear();
                        renderSettings(0);
                    }
                }
            )
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void alert(String title, String message) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message == null ? "That value was not accepted." : message)
            .setPositiveButton("OK", null)
            .show();
    }

    // ------------------------------------------------------------------
    // View helpers
    // ------------------------------------------------------------------

    private void replaceContent(View view) {
        content.removeAllViews();
        content.addView(
            view,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        );
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        );
    }

    private ListView listView(ListAdapter adapter) {
        return configureListView(new ListView(this), adapter);
    }

    private ChatListView chatListView(ListAdapter adapter) {
        return configureListView(new ChatListView(this), adapter);
    }

    private <T extends ListView> T configureListView(T list, ListAdapter adapter) {
        list.setAdapter(adapter);
        // Rows paint their own focus state edge to edge. A transparent
        // selector stops the platform highlight from drawing a second,
        // differently shaped indicator underneath them.
        list.setSelector(new ColorDrawable(0x00000000));
        list.setDivider(new ColorDrawable(color(R.color.divider)));
        list.setDividerHeight(dp(1));
        list.setBackgroundColor(color(R.color.page));
        list.setCacheColorHint(0x00000000);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list.setItemsCanFocus(false);
        return list;
    }

    /** Title of a detail screen, sitting on the row surface like a first row. */
    private TextView heading(String value) {
        TextView view = text(value, 16, color(R.color.text));
        view.setTypeface(null, Typeface.BOLD);
        view.setBackgroundColor(color(R.color.row));
        view.setPadding(dp(10), dp(9), dp(10), dp(3));
        return view;
    }

    private TextView subheading(String value) {
        TextView view = text(value, 12, color(R.color.text_muted));
        view.setBackgroundColor(color(R.color.row));
        view.setPadding(dp(10), dp(1), dp(10), dp(9));
        return view;
    }

    /** Centred guidance for a screen that has nothing in it. */
    private TextView emptyMessage(String message) {
        TextView view = text(message, 13, color(R.color.text_muted));
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        return view;
    }

    /** Label left, value right: the shape every fact on a detail page takes. */
    private View metric(String label, String value, int tint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(color(R.color.row));
        row.setMinimumHeight(dp(28));
        row.setPadding(dp(10), dp(5), dp(10), dp(5));

        TextView name = text(label, 12, color(R.color.text_muted));
        name.setSingleLine(true);
        row.addView(
            name,
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
        );

        // Label and value are separated by colour, not weight: a page of bold
        // values has no emphasis left to give.
        TextView amount = text(value, 12, tint);
        amount.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        right.leftMargin = dp(8);
        row.addView(amount, right);
        return row;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(color(R.color.divider));
        line.setLayoutParams(
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        );
        return line;
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
