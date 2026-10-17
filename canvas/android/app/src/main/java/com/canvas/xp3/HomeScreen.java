package com.canvas.xp3;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Section;

import java.util.ArrayList;
import java.util.List;

/**
 * To-do (A1 to A6): Assigned, Missing and Done.
 *
 * Assigned is built around the week. The strip across the top is the week's
 * seven days; under it, the week's work as a timeline grouped by day, the due
 * time in a column on the left. Students think "what's due Tuesday?", so the
 * days are the navigation: Left and Right always move along time, and the
 * keys 1 to 7 jump to a day, 0 back to today.
 *
 * Three zones stack top to bottom (tabs, strip, list), and Up and Down move
 * between them. The day in view stays ink wherever the focus is, so the
 * student always knows which day they are looking at.
 *
 * Missing and Done are quiet tabs beside it, with no counts or dots.
 */
final class HomeScreen extends Screen {

    private static final String TABS = "tabs";
    private static final String STRIP = "strip";

    private int tab = Assignment.ASSIGNED;
    private long weekStart;
    private long selected;
    private boolean chosen;
    private WeekStrip strip;
    private final List<View> headers = new ArrayList<>();
    private final List<Long> headerDays = new ArrayList<>();
    private boolean scrollToDay = true;
    private final Runnable prebuilder = this::prebuild;

    HomeScreen(MainActivity app) {
        super(app);
    }

    @Override
    String right() {
        return "Classes";
    }

    @Override
    void onRight() {
        app.push(new ClassesScreen(app));
    }

    @Override
    boolean globalOptions() {
        return true;
    }

    @Override
    String firstFocus() {
        if (tab != Assignment.ASSIGNED) return null;
        List<Assignment> due = app.todos.dueOn(selected, System.currentTimeMillis());
        return due.isEmpty() ? STRIP : "w:" + due.get(0).id;
    }

    /** One tab's built screen, kept until what it shows changes. */
    private static final class Built {
        String key;
        View root;
        android.widget.ScrollView scroll;
        WeekStrip strip;
        final List<View> headers = new ArrayList<>();
        final List<Long> headerDays = new ArrayList<>();
    }

    /**
     * Each tab's screen, built once and reused. Switching tabs on this phone
     * is the most common thing the app does, and building a term's worth of
     * rows each time is what makes a tab slow to appear; a tab is rebuilt
     * only when the work, the day, the theme or the day in view changes.
     */
    private final Built[] built = new Built[3];

    private String key(int which, long now) {
        StringBuilder key = new StringBuilder();
        key.append(app.version).append('|').append(System.identityHashCode(look)).append('|')
            .append(Dates.dayOf(now)).append('|').append(app.backend.canvasReady()).append('|')
            .append(app.snapshot == null ? app.syncError : "ok");
        if (which == Assignment.ASSIGNED) key.append('|').append(weekStart).append('|').append(selected);
        return key.toString();
    }

    @Override
    View build() {
        long now = System.currentTimeMillis();
        if (!chosen) {
            selected = app.todos.openingDay(now);
            weekStart = Dates.weekOf(selected);
            chosen = app.snapshot != null;
        }
        // First launch, nothing saved yet: the whole screen waits.
        if (app.backend.canvasReady() && app.snapshot == null && app.syncing) return new Parts(app).spinner();
        Built tabView = built[tab];
        String key = key(tab, now);
        if (tabView == null || !key.equals(tabView.key)) {
            tabView = make(tab, now);
            tabView.key = key;
            built[tab] = tabView;
        }
        use(tabView);
        return tabView.root;
    }

    private void use(Built b) {
        if (b.root.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) b.root.getParent()).removeView(b.root);
        }
        scroll = b.scroll;
        strip = b.strip;
        headers.clear();
        headers.addAll(b.headers);
        headerDays.clear();
        headerDays.addAll(b.headerDays);
    }

    /** Builds the other tabs ahead, one per pass of the main thread, so switching to them is instant. */
    private void prebuild() {
        if (app.top() != this || app.overlayOpen()) return;
        if (app.snapshot == null) return;
        long now = System.currentTimeMillis();
        for (int t = 0; t < 3; t++) {
            if (t == tab) continue;
            String key = key(t, now);
            if (built[t] != null && key.equals(built[t].key)) continue;
            int was = tab;
            android.widget.ScrollView keepScroll = scroll;
            WeekStrip keepStrip = strip;
            List<View> keepHeaders = new ArrayList<>(headers);
            List<Long> keepDays = new ArrayList<>(headerDays);
            tab = t;
            Built b = make(t, now);
            b.key = key;
            built[t] = b;
            tab = was;
            scroll = keepScroll;
            strip = keepStrip;
            headers.clear();
            headers.addAll(keepHeaders);
            headerDays.clear();
            headerDays.addAll(keepDays);
            app.main.post(this::prebuild);
            return;
        }
    }

    private Built make(int which, long now) {
        Parts parts = new Parts(app);
        Built b = new Built();
        scroll = null;
        strip = null;
        headers.clear();
        headerDays.clear();

        LinearLayout root = v.column();
        b.root = root;
        root.addView(tabs(), Views.wide());

        List<Assignment> assigned = app.todos.inState(Assignment.ASSIGNED, now);
        if (which == Assignment.ASSIGNED) {
            Item holder = new Item(app, STRIP, "View day", this::enterDay);
            final WeekStrip days = new WeekStrip(app, look);
            strip = days;
            holder.addView(days, Views.wide());
            holder.painter = days::focus;
            int todayColumn = Dates.weekOf(now) == weekStart ? Dates.column(now) : -1;
            days.week(weekStart, Dates.column(selected), todayColumn);
            boolean weekEmpty = true;
            for (int i = 0; i < 7; i++) {
                List<Integer> colors = new ArrayList<>();
                for (Assignment item : app.todos.dueOn(Dates.addDays(weekStart, i), now)) {
                    colors.add(Integer.valueOf(look.course(color(item))));
                    weekEmpty = false;
                }
                days.dots(i, colors);
            }
            if (weekEmpty && undatedHere(now).isEmpty()) holder.ok = "Today";
            root.addView(holder, Views.wide());
        }
        root.addView(v.hairline());

        if (!app.backend.canvasReady()) {
            root.addView(parts.empty(0, "Canvas isn't set up",
                "Open Options, then Settings, then Account, to sign in."), Views.tall());
        } else if (app.snapshot == null && app.syncError != null) {
            root.addView(parts.empty(0, "Couldn't reach Canvas", app.syncError + " Press Options, then Refresh."),
                Views.tall());
        } else {
            LinearLayout list = parts.page(this, root);
            if (which == Assignment.ASSIGNED) assigned(parts, list, now, root);
            else if (which == Assignment.MISSING) missing(parts, list, now);
            else done(parts, list, now);
        }
        b.scroll = scroll;
        b.strip = strip;
        b.headers.addAll(headers);
        b.headerDays.addAll(headerDays);
        return b;
    }

    private int color(Assignment item) {
        Course course = app.course(item.courseId);
        return course == null ? 0 : course.color;
    }

    private String code(Assignment item) {
        Course course = app.course(item.courseId);
        return course == null ? item.course : course.code;
    }

    // ------------------------------------------------------------------
    // The tabs
    // ------------------------------------------------------------------

    private View tabs() {
        Item row = new Item(app, TABS, "Select", () -> step(1));
        LinearLayout line = v.row();
        line.setGravity(Gravity.BOTTOM);
        v.pad(line, 12, 4, 10, 5);
        String[] names = { "Assigned", "Missing", "Done" };
        final MarkText[] marks = new MarkText[3];
        for (int i = 0; i < 3; i++) {
            boolean on = i == tab;
            MarkText name = v.mark(names[i], on ? 18 : 10.5f, on ? look.heavy : look.semibold,
                on ? look.ink : look.muted);
            if (on) name.setLetterSpacing(-0.02f);
            name.setSingleLine(true);
            marks[i] = name;
            line.addView(name, v.margins(Views.wrap(), 0, 0, 8, on ? 0 : 2));
        }
        View gap = new View(app);
        line.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        if (tab == Assignment.ASSIGNED) {
            TextView range = v.text(Dates.weekRange(weekStart), 8.5f, look.semibold, look.muted);
            range.setSingleLine(true);
            line.addView(range, v.margins(Views.wrap(), 0, 0, 0, 2));
        }
        row.addView(line, Views.wide());
        final int current = tab;
        row.painter = has -> marks[current].mark(has, look.markStroke);
        return row;
    }

    private void step(int direction) {
        tab = (tab + direction + 3) % 3;
        scrollToDay = true;
        app.redraw();
        View tabs = Screen.find(app.findViewById(android.R.id.content), TABS);
        if (tabs != null) tabs.requestFocus();
    }

    // ------------------------------------------------------------------
    // Assigned: the week as a timeline
    // ------------------------------------------------------------------

    private List<Assignment> undatedHere(long now) {
        return Dates.weekOf(now) == weekStart ? app.todos.undated(now) : new ArrayList<Assignment>();
    }

    private void assigned(Parts parts, LinearLayout list, long now, LinearLayout root) {
        boolean any = false;
        for (int i = 0; i < 7; i++) {
            long day = Dates.addDays(weekStart, i);
            List<Assignment> due = app.todos.dueOn(day, now);
            if (due.isEmpty() && day != selected) continue;
            View header = parts.dayHeader(Dates.dayHeading(day), due.isEmpty() ? null : due.size() + " due");
            headers.add(header);
            headerDays.add(Long.valueOf(day));
            list.addView(header, Views.wide());
            if (due.isEmpty()) {
                TextView none = v.text("Nothing due", 10, look.regular, look.muted);
                v.pad(none, 12, 2, 12, 4);
                list.addView(none, Views.wide());
                continue;
            }
            any = true;
            for (Assignment item : due) list.addView(timeline(item, day), Views.wide());
        }
        List<Assignment> undated = undatedHere(now);
        if (!undated.isEmpty()) {
            any = true;
            list.addView(parts.dayHeader("No due date", undated.size() + ""), Views.wide());
            for (Assignment item : undated) list.addView(timeline(item, 0), Views.wide());
        }
        if (!any) {
            // The empty week (A6): a drawing and the next step, in place of the list.
            root.removeView((View) list.getParent());
            scroll = null;
            boolean thisWeek = Dates.weekOf(now) == weekStart;
            root.addView(parts.empty(R.drawable.art_plane,
                thisWeek ? "Nothing due this week" : "Nothing due this week",
                thisWeek ? "Press Right to look at the week after."
                    : "Press Right to look at the week after, or 0 to go back to today."), Views.tall());
        }
    }

    /** One row of the timeline: due time, a dot on the day's line, the title and its class. */
    private Item timeline(final Assignment item, long day) {
        final Item row = new Item(app, "w:" + item.id, "Open", () -> open(item));
        row.day = day;
        LinearLayout line = v.row();
        line.setGravity(Gravity.TOP);
        v.pad(line, 12, 3, 12, 5);

        LinearLayout time = v.column();
        if (item.due != null) {
            TextView clock = v.text(Dates.clock(item.due.longValue()), 10.5f, look.bold, look.ink);
            Views.tabular(clock);
            time.addView(clock, v.margins(Views.wrap(), 0, 1, 0, 0));
            String half = Dates.meridiem(item.due.longValue());
            if (half != null) time.addView(v.text(half, 8, look.semibold, look.muted));
        }
        line.addView(time, new LinearLayout.LayoutParams(v.dp(30), -2));

        FrameLayout rail = new FrameLayout(app);
        View dot = v.dot(look.course(color(item)), 8);
        FrameLayout.LayoutParams dotAt = new FrameLayout.LayoutParams(v.dp(8), v.dp(8));
        dotAt.setMargins(v.dp(1), v.dp(4), 0, 0);
        rail.addView(dot, dotAt);
        View track = new View(app);
        track.setBackgroundColor(look.line);
        FrameLayout.LayoutParams trackAt = new FrameLayout.LayoutParams(Math.max(1, v.dp(1)), -1);
        trackAt.setMargins(v.dp(4.5f), v.dp(15), 0, 0);
        rail.addView(track, trackAt);
        line.addView(rail, v.margins(new LinearLayout.LayoutParams(v.dp(10), -1), 6, 0, 6, 0));

        LinearLayout text = v.column();
        MarkText title = v.itemTitle(item.title);
        text.addView(title, Views.wide());
        Course course = app.course(item.courseId);
        String meta = course == null ? item.course : course.code + " · " + course.title;
        text.addView(v.meta(meta), v.margins(Views.wide(), 0, 2, 0, 0));
        line.addView(text, Views.fill());

        row.addView(line, Views.wide());
        row.marks(title, look);
        final long rowDay = day;
        row.also(has -> {
            // The day in view follows the focus down the list.
            if (has && rowDay != 0 && rowDay != selected) {
                selected = rowDay;
                if (strip != null) strip.select(Dates.column(rowDay));
            }
        });
        return row;
    }

    private void open(Assignment item) {
        app.push(new AssignmentScreen(app, item, "Assigned"));
    }

    /** OK or Down from the strip: into the list at the day in view. */
    private void enterDay() {
        long now = System.currentTimeMillis();
        List<Assignment> due = app.todos.dueOn(selected, now);
        if (due.isEmpty()) {
            boolean weekEmpty = true;
            for (int i = 0; i < 7; i++) weekEmpty &= app.todos.dueOn(Dates.addDays(weekStart, i), now).isEmpty();
            if (weekEmpty && undatedHere(now).isEmpty()) jump(now, true);
            return;
        }
        Item first = find(app.findViewById(android.R.id.content), "w:" + due.get(0).id);
        if (first != null) first.requestFocus();
    }

    /** Moves the day in view, crossing into another week if need be, and redraws. */
    private void jump(long day, boolean focusStrip) {
        long midnight = Dates.dayOf(day);
        selected = midnight;
        long week = Dates.weekOf(midnight);
        weekStart = week;
        scrollToDay = true;
        app.redraw();
        if (focusStrip) {
            View holder = find(app.findViewById(android.R.id.content), STRIP);
            if (holder != null) holder.requestFocus();
        }
    }

    @Override
    void shown() {
        app.main.removeCallbacks(prebuilder);
        app.main.postDelayed(prebuilder, 300);
        if (tab != Assignment.ASSIGNED && scrollToDay && scroll != null) {
            scrollToDay = false;
            final android.widget.ScrollView page = scroll;
            page.post(() -> page.scrollTo(0, 0));
            return;
        }
        if (!scrollToDay || scroll == null) return;
        scrollToDay = false;
        for (int i = 0; i < headers.size(); i++) {
            if (headerDays.get(i).longValue() != selected) continue;
            final View header = headers.get(i);
            scroll.post(() -> {
                // Room under the last day, so any day can sit at the top of
                // the list rather than stopping short at the end of the week.
                View list = scroll.getChildAt(0);
                int below = list.getHeight() - list.getPaddingBottom() - header.getTop();
                int room = Math.max(v.dp(10), scroll.getHeight() - below);
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), room);
                scroll.post(() -> scroll.scrollTo(0, header.getTop()));
            });
        }
    }

    // ------------------------------------------------------------------
    // Missing and Done
    // ------------------------------------------------------------------

    private void missing(Parts parts, LinearLayout list, long now) {
        TextView calm = v.text("Work that is past its due date but still open. "
            + "You can turn it in whenever you're ready.", 10, look.regular, look.muted);
        calm.setLineSpacing(0, 1.3f);
        v.pad(calm, 12, 2, 12, 0);
        list.addView(calm, Views.wide());
        List<Section> sections = app.todos.sections(Assignment.MISSING, now);
        if (sections.isEmpty()) {
            list.addView(parts.empty(0, "Nothing missing", null), v.margins(Views.wide(), 0, 40, 0, 0));
            return;
        }
        for (Section section : sections) {
            list.addView(parts.section(section.label, Integer.toString(section.items.size())), Views.wide());
            for (final Assignment item : section.items) {
                String meta = code(item) + (item.due == null ? "" : " · Was due " + Dates.shortDay(item.due.longValue()));
                list.addView(parts.work("w:" + item.id, item, meta, null,
                    () -> app.push(new AssignmentScreen(app, item, "Missing"))), Views.wide());
            }
        }
    }

    private void done(Parts parts, LinearLayout list, long now) {
        List<Section> sections = app.todos.sections(Assignment.DONE, now);
        if (sections.isEmpty()) {
            list.addView(parts.empty(0, "Nothing turned in yet", null), v.margins(Views.wide(), 0, 60, 0, 0));
            return;
        }
        for (Section section : sections) {
            list.addView(parts.section(section.label, Integer.toString(section.items.size())), Views.wide());
            for (final Assignment item : section.items) {
                String meta;
                View right;
                if (Section.RETURNED.equals(section.label)) {
                    Long at = item.gradedAt != null ? item.gradedAt : item.turnedIn;
                    meta = code(item) + (at == null ? "" : " · Returned " + Dates.shortDay(at.longValue()));
                    String chip = item.gradeChip();
                    right = chip == null ? null : v.gradeChip(chip);
                } else if (app.todos.checked(item) && !item.done) {
                    meta = code(item) + " · Marked done";
                    right = null;
                } else {
                    meta = code(item) + (item.turnedIn == null ? "" : " · Turned in "
                        + Dates.shortDay(item.turnedIn.longValue()).split(", ")[1]);
                    right = v.text(item.points > 0 ? "Not graded" : "", 9.5f, look.semibold, look.muted);
                }
                list.addView(parts.work("w:" + item.id, item, meta, right,
                    () -> app.push(new AssignmentScreen(app, item, "Done"))), Views.wide());
            }
        }
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Override
    boolean onKey(KeyEvent event) {
        View focus = app.getCurrentFocus();
        String key = focus instanceof Item ? ((Item) focus).key : null;
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        long now = System.currentTimeMillis();

        if (tab == Assignment.ASSIGNED && isDigit(code)) {
            if (down) return true;
            if (code == KeyEvent.KEYCODE_0) jump(now, STRIP.equals(key));
            else if (code <= KeyEvent.KEYCODE_7) {
                jump(Dates.addDays(weekStart, code - KeyEvent.KEYCODE_1), STRIP.equals(key) || key == null
                    || TABS.equals(key));
                if (!STRIP.equals(key) && !TABS.equals(key)) enterDayIfAny();
            }
            return true;
        }
        if (TABS.equals(key)) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down && event.getRepeatCount() == 0) step(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            return false;
        }
        if (STRIP.equals(key)) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) jump(Dates.addDays(selected, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1), true);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) {
                    List<Assignment> due = app.todos.dueOn(selected, now);
                    if (!due.isEmpty()) enterDay();
                    else {
                        List<Item> rows = items(app.findViewById(android.R.id.content));
                        for (Item row : rows) {
                            if (row.key.startsWith("w:")) {
                                row.requestFocus();
                                break;
                            }
                        }
                    }
                }
                return true;
            }
            return false;
        }
        if (tab == Assignment.ASSIGNED && key != null && key.startsWith("w:")
            && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (!down) return true;
            int direction = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            Long next = app.todos.dayWithWork(selected, direction, now);
            long target;
            if (next != null && Dates.weekOf(next.longValue()) == weekStart) target = next.longValue();
            else target = direction > 0 ? Dates.addDays(weekStart, 7) : Dates.addDays(weekStart, -1);
            if (next != null && Dates.weekOf(next.longValue()) == Dates.weekOf(target)) target = next.longValue();
            jump(target, false);
            enterDayIfAny();
            return true;
        }
        if (tab == Assignment.ASSIGNED && key != null && key.startsWith("w:") && code == KeyEvent.KEYCODE_DPAD_UP) {
            List<Item> rows = items(app.findViewById(android.R.id.content));
            for (Item row : rows) {
                if (!row.key.startsWith("w:")) continue;
                if (row == focus) {
                    if (down) {
                        View holder = find(app.findViewById(android.R.id.content), STRIP);
                        if (holder != null) holder.requestFocus();
                        if (scroll != null) scroll.smoothScrollTo(0, 0);
                    }
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private void enterDayIfAny() {
        List<Assignment> due = app.todos.dueOn(selected, System.currentTimeMillis());
        View root = app.findViewById(android.R.id.content);
        if (!due.isEmpty()) {
            Item first = find(root, "w:" + due.get(0).id);
            if (first != null) first.requestFocus();
        } else {
            View holder = find(root, STRIP);
            if (holder != null) holder.requestFocus();
        }
    }
}
