package com.canvas.xp3;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
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

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Section;
import com.canvas.xp3.data.Sync;
import com.canvas.xp3.data.Todos;
import com.canvas.xp3.net.Config;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Markdown;
import com.xp3.common.ui.Tabs;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Three lists, a detail page, a settings page, and the keys that move between
 * them.
 *
 * Views are built in code rather than inflated per screen because each screen
 * is a handful of rows whose shape depends on what is in it, and a layout file
 * per state would be more XML than the states are worth. The things that
 * repeat, an assignment row, a section band and a setting, are the
 * things that do have layouts.
 *
 * Nothing on this side of the app touches a network. Everything that does is
 * behind Backend, runs on the work executor below, and comes back through the
 * main thread's handler.
 */
public final class MainActivity extends BaseActivity {

    /**
     * The first three line up with Assignment's own state constants on
     * purpose: a tab is a state, so the tab index is what gets handed to
     * Todos.sections rather than a translation between two numberings that
     * would only ever be the identity.
     */
    private static final int ASSIGNED = 0;
    private static final int MISSING = 1;
    private static final int DONE = 2;

    private static final String REFRESH = "Refresh from Canvas";
    private static final String SETTINGS = "Settings";
    private static final String ABOUT = "About";

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
    private Tabs tabs;
    private int currentTab = ASSIGNED;

    /**
     * Two threads for work.
     *
     * Two rather than one because opening an assignment fetches its
     * instructions, and a sync across a term's courses can easily run for
     * twenty seconds: queued behind it, a page opened in the meantime would sit
     * on "Fetching the instructions" for the whole of that sync for no reason
     * other than the shape of the queue.
     */
    private final ExecutorService work = Ui.workers("canvas-work", 2);

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

        content = findViewById(R.id.content);
        tabBar = findViewById(R.id.tab_bar);
        tabs = new Tabs(this, tab -> showTab(tab, true), R.id.tab_assigned, R.id.tab_missing, R.id.tab_done);
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

    private boolean started;

    private void showTab(int tab, boolean focusContent) {
        currentTab = tab;
        openWork = null;
        settingsOpen = false;
        tabBar.setVisibility(View.VISIBLE);
        tabs.mark(tab);
        renderList(tab, focusContent, null);
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
            root.addView(emptyMessage(emptyText(state)), Ui.weighted());
            Ui.fill(content, root);
            return;
        }

        openSomethingFirstTime(state, sections, stamp);
        final AssignmentAdapter adapter = new AssignmentAdapter(this);
        adapter.replace(rows(state, sections), state, stamp);
        ListView list = listView(adapter);
        root.addView(list, Ui.weighted());
        wireList(list, adapter, state);
        Ui.fill(content, root);
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
        TextView view = text(message, 11, color(R.color.text_muted));
        view.setBackgroundColor(color(R.color.section_fill));
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
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
            () -> {
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
                    () -> synced(result, problem, announce)
                );
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
            () -> backend.saveWork(all, context)
        );
    }

    /** Redraws a work list, and only a work list. */
    private void redrawIfListing() {
        if (openWork != null || settingsOpen) return;
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
        } else {
            renderList(currentTab, listView != null && listView.hasFocus(), selectedKey());
        }
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
            (parent, view, position, id) -> activate(adapter, state, position)
        );
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnFocusChangeListener(
            (view, hasFocus) -> {
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
        );
        Ui.onSelect(list, position -> adapter.trackSelectedPosition(position));
        // Sonim firmware variants report the physical OK key as either
        // DPAD_CENTER or ENTER. Handle activation only; arrows stay with
        // ListView's native selection and scrolling implementation.
        list.setOnKeyListener(
            (view, keyCode, event) -> {
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
            () -> {
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
                    () -> {
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
                );
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
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setBackgroundColor(color(R.color.grouped_page));
        LinearLayout root = vertical();
        root.setPadding(0, 0, 0, dp(8));

        root.addView(heading(item.title));
        root.addView(subheading(item.course));

        // The facts on one card, the instructions on another.
        LinearLayout facts = card();
        root.addView(facts);
        facts.addView(
            metric(
                "Due",
                item.due == null
                    ? "No due date"
                    : Dates.dayAndTime(item.due.longValue(), stamp),
                state == MISSING ? color(R.color.overdue) : color(R.color.text)
            )
        );
        facts.addView(divider());
        facts.addView(
            metric(
                "Status",
                statusLabel(item, stamp),
                state == MISSING
                    ? color(R.color.overdue)
                    : state == DONE ? color(R.color.due_soon) : color(R.color.text)
            )
        );
        if (item.done && item.turnedIn != null) {
            facts.addView(divider());
            facts.addView(
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
            facts.addView(divider());
            facts.addView(metric("Score", score, color(R.color.text)));
        }

        LinearLayout body = card();
        body.setPadding(0, dp(2), 0, dp(4));
        root.addView(body);
        if (item.hasDetails()) {
            new Markdown(this).into(body, item.details());
        } else {
            body.addView(
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
        Ui.fill(content, scroll);
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

    /** What is on the clipboard as text, or null when there is nothing usable. */
    private CharSequence clipboardText() {
        if (!clipboardHasText()) return null;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        return pasted == null || pasted.length() == 0 ? null : pasted;
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
        int next = openWork != null || settingsOpen ? -1 : tabs.step(event, getCurrentFocus(), currentTab);
        if (next >= 0) {
            showTab(next, true);
            return true;
        }
        return super.dispatchKeyEvent(event);
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
     * that a sync still running against the network is not left holding a
     * socket open behind an app somebody has closed.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
    }

    private static final String EXPAND = "Expand all sections";
    private static final String COLLAPSE = "Collapse all sections";
    private static final String SIGN_OUT = "Sign out";
    private static final String RESTORE = "Use the bundled setup";

    private void showMenu() {
        if (openWork != null || settingsOpen) return;
        List<String> items = new ArrayList<>();
        items.add(REFRESH);
        items.add(EXPAND);
        items.add(COLLAPSE);
        items.add(SETTINGS);
        items.add(ABOUT);
        final String[] labels = items.toArray(new String[items.size()]);
        new AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(
                labels,
                (dialog, which) -> chose(labels[which])
            )
            .show();
    }

    /** Dispatch by label rather than by index. */
    private void chose(String label) {
        if (label.equals(REFRESH)) refresh(true);
        else if (label.equals(EXPAND)) setAllSections(true);
        else if (label.equals(COLLAPSE)) setAllSections(false);
        else if (label.equals(SETTINGS)) openSettings();
        else showAbout();
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
        root.addView(subheading(backend.canvasReady() ? "Work is set up" : "Not set up yet"));
        root.addView(divider());
        root.addView(list, Ui.weighted());
        wireSettings(list, adapter);
        Ui.fill(content, root);
        settingsAdapter = adapter;

        int start = select >= 0 && select < adapter.getCount() ? select : 0;
        adapter.setSelectedPosition(start);
        list.setSelection(start);
        list.requestFocus();
    }

    private void wireSettings(final ListView list, final SettingsAdapter adapter) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        Ui.onSelect(list, position -> adapter.trackSelectedPosition(position));
        list.setOnItemClickListener(
            (parent, view, position, id) -> editSetting(adapter.getItem(position), position)
        );
        list.setOnKeyListener(
            (view, keyCode, event) -> {
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
            else restoreBundledSetup();
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
                (dialog, which) -> applySetting(row.field, field.getText().toString(), position)
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
         * This acts on the release. A field with T9 prediction would have to
         * use the press, because the keyboard swallows the first ENTER release
         * to finish the word it is composing — but these fields turn
         * prediction off, so there is never a word in the air and the release
         * always arrives. It matters which way round: dismissing on the
         * press leaves the release to be delivered to the settings list
         * underneath, which reads it as OK and opens this dialog straight back
         * up. Both halves are swallowed either way, so ENTER cannot also reach
         * the field itself.
         */
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        field.setOnKeyListener(
            (view, keyCode, event) -> {
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
        );
        field.setOnEditorActionListener(
            (view, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_DONE && actionId != EditorInfo.IME_ACTION_GO) {
                    return false;
                }
                dialog.dismiss();
                applySetting(row.field, view.getText().toString(), position);
                return true;
            }
        );

        Button paste = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (paste != null) {
            // Wired after show() so that pasting does not also dismiss the
            // dialog, which is what a plain button listener would do.
            paste.setOnClickListener(
                view -> {
                    CharSequence text = clipboardText();
                    if (text == null) return;
                    field.setText(text.toString().trim());
                    field.setSelection(field.getText().length());
                }
            );
        }
    }

    private void applySetting(String field, String value, int position) {
        Config next;
        try {
            next = backend.config().with(field, value);
        } catch (IllegalArgumentException e) {
            alert("Not saved", e.getMessage() == null ? "That value was not accepted." : e.getMessage());
            return;
        }
        try {
            backend.saveSettings(next);
        } catch (java.io.IOException e) {
            alert("Not saved", "Could not write to this phone's storage.");
            return;
        }
        // A different Canvas account means the work on screen belongs to
        // somebody else. Drop it rather than showing it until a sync replaces
        // it row by row.
        if (Config.CANVAS_URL.equals(field) || Config.COOKIE_URL.equals(field)
            || Config.COOKIE_TOKEN.equals(field)) {
            forgetWork();
        }
        renderSettings(position);
    }

    /** Drops the work on screen, which belonged to the setup just replaced. */
    private void forgetWork() {
        snapshot = null;
        syncError = null;
        todos.replace(new ArrayList<Assignment>());
        defaulted.clear();
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
        forgetWork();
        toast("Bundled setup restored");
        renderSettings(0);
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
            .setTitle(SIGN_OUT)
            .setMessage(
                "Remove the Canvas address, the token and the saved work " +
                "list from this phone?"
            )
            .setPositiveButton(
                SIGN_OUT,
                (dialog, which) -> {
                    backend.signOut();
                    forgetWork();
                    renderSettings(0);
                }
            )
            .setNegativeButton("Cancel", null)
            .show();
    }

    private ListView listView(ListAdapter adapter) {
        return configureListView(new ListView(this), adapter);
    }

    private <T extends ListView> T configureListView(T list, ListAdapter adapter) {
        list.setAdapter(adapter);
        // Rows paint their own focus card, so Family turns the platform
        // selector off and insets the hairlines to the text edge.
        Family.list(list);
        list.setItemsCanFocus(false);
        return list;
    }

    /** Title of a detail screen, on the grouped page above its cards. */
    private TextView heading(String value) {
        TextView view = text(value, 16, color(R.color.text));
        view.setTypeface(Family.MEDIUM);
        view.setPadding(dp(10), dp(10), dp(10), dp(2));
        return view;
    }

    private TextView subheading(String value) {
        TextView view = text(value, 12, color(R.color.text_muted));
        view.setPadding(dp(10), dp(1), dp(10), dp(4));
        return view;
    }

    /** A white card on the grouped page, holding one group of a page's facts. */
    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setBackgroundResource(com.xp3.common.ui.R.drawable.family_card);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        at.setMargins(dp(6), dp(6), dp(6), 0);
        card.setLayoutParams(at);
        return card;
    }

    /**
     * Centred guidance for a screen that has nothing in it: the first
     * paragraph is what is (or isn't) here, set as a title, and the rest
     * says what to do about it.
     */
    private TextView emptyMessage(String message) {
        int cut = message.indexOf("\n\n");
        String title = cut < 0 ? message : message.substring(0, cut);
        String hint = cut < 0 ? null : message.substring(cut + 2);
        if (title.endsWith(".")) title = title.substring(0, title.length() - 1);
        TextView view = Family.empty(this, title, hint);
        view.setBackgroundColor(0x00000000);
        return view;
    }

    /** Label left, value right: the shape every fact on a detail page takes. */
    private View metric(String label, String value, int tint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(30));
        row.setPadding(dp(10), dp(6), dp(10), dp(6));

        TextView name = text(label, 12, color(R.color.text_muted));
        name.setSingleLine(true);
        row.addView(
            name,
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
        );

        // Label and value are separated by colour, not weight: a page of bold
        // values has no emphasis left to give.
        TextView amount = text(value, 13, tint);
        amount.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        right.leftMargin = dp(8);
        row.addView(amount, right);
        return row;
    }

    /** A hairline between the facts on a card, inset to the text edge. */
    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(color(R.color.divider));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        );
        at.leftMargin = dp(10);
        line.setLayoutParams(at);
        return line;
    }
}
