package com.canvas.xp3.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every piece of work the last sync found, held for as long as the app is open.
 *
 * The three lists are one list read three ways. Nothing moves between them:
 * what Canvas says about a submission decides what the work is, and where it
 * appears follows from that, which is what keeps the Done tab from ever
 * disagreeing with the Assigned one.
 */
public final class Todos {

    private final List<Assignment> work = new ArrayList<>();

    /**
     * Assignment ids the student has ticked off in the planner: work with
     * nothing to turn in, marked done by hand. They count as done here and
     * nowhere else, since Canvas's own record of them has not changed.
     */
    private final java.util.Set<String> checked = new java.util.HashSet<>();

    /**
     * Each list's work, worked out once per minute rather than on every call:
     * drawing the week asks for Assigned work once per day of it, and the
     * answer only changes when the list or the minute does.
     */
    private final java.util.Map<Integer, List<Assignment>> memo = new java.util.HashMap<>();
    private long memoMinute = Long.MIN_VALUE;

    /** Swaps in what a sync, or the cache read at startup, just produced. */
    public void replace(List<Assignment> next) {
        work.clear();
        if (next != null) work.addAll(next);
        memo.clear();
    }

    public void check(java.util.Collection<String> assignmentIds) {
        checked.clear();
        if (assignmentIds != null) checked.addAll(assignmentIds);
        memo.clear();
    }

    public boolean checked(Assignment item) {
        return checked.contains(item.assignmentId);
    }

    public java.util.Set<String> checkedIds() {
        return new java.util.HashSet<>(checked);
    }

    /** The item's list, counting a planner check mark as done. */
    public int state(Assignment item, long now) {
        if (!item.done && checked.contains(item.assignmentId)) return Assignment.DONE;
        return item.state(now);
    }

    public boolean isEmpty() {
        return work.isEmpty();
    }

    public List<Assignment> all() {
        return work;
    }

    public Assignment byId(String id) {
        for (int i = 0; i < work.size(); i++) {
            if (work.get(i).id.equals(id)) return work.get(i);
        }
        return null;
    }

    public List<Assignment> inState(int state, long now) {
        long minute = now / 60000L;
        if (minute != memoMinute) {
            memo.clear();
            memoMinute = minute;
        }
        List<Assignment> known = memo.get(Integer.valueOf(state));
        if (known != null) return known;
        List<Assignment> matching = new ArrayList<>();
        for (int i = 0; i < work.size(); i++) {
            if (state(work.get(i), now) == state) matching.add(work.get(i));
        }
        known = Collections.unmodifiableList(matching);
        memo.put(Integer.valueOf(state), known);
        return known;
    }

    /**
     * One list's sections, in the order they are shown, with empty sections
     * left out except for Assigned's This week section.
     *
     * A heading over nothing is a row spent saying "no". The count beside a
     * heading is the whole point of showing it, and a section that would read
     * "0" has usually already been answered by not being there. This week is
     * kept in Assigned because it is the list's useful anchor even when there
     * is nothing due in it.
     */
    public List<Section> sections(int state, long now) {
        List<Section> sections = new ArrayList<>();
        if (state == Assignment.ASSIGNED) {
            Section noDate = new Section(Section.NO_DUE_DATE);
            Section thisWeek = new Section(Section.THIS_WEEK);
            Section nextWeek = new Section(Section.NEXT_WEEK);
            Section later = new Section(Section.LATER);
            for (Assignment item : inState(state, now)) {
                if (item.due == null) noDate.items.add(item);
                else {
                    int week = Dates.weeksBetween(now, item.due.longValue());
                    if (week <= 0) thisWeek.items.add(item);
                    else if (week == 1) nextWeek.items.add(item);
                    else later.items.add(item);
                }
            }
            // Soonest first: the top of this list is what to do tonight.
            sortByDue(thisWeek, true);
            sortByDue(nextWeek, true);
            sortByDue(later, true);
            collect(sections, noDate);
            sections.add(thisWeek);
            collect(sections, nextWeek, later);
        } else if (state == Assignment.MISSING) {
            Section noDate = new Section(Section.NO_DUE_DATE);
            Section thisWeek = new Section(Section.THIS_WEEK);
            Section lastWeek = new Section(Section.LAST_WEEK);
            Section earlier = new Section(Section.EARLIER);
            for (Assignment item : inState(state, now)) {
                if (item.due == null) noDate.items.add(item);
                else {
                    int week = Dates.weeksBetween(now, item.due.longValue());
                    if (week >= 0) thisWeek.items.add(item);
                    else if (week == -1) lastWeek.items.add(item);
                    else earlier.items.add(item);
                }
            }
            // Most recent first, in both of the backward-looking lists. Work
            // that went missing yesterday is still catchable; work from three
            // weeks ago mostly is not, so it sinks.
            sortByDue(thisWeek, false);
            sortByDue(lastWeek, false);
            sortByDue(earlier, false);
            collect(sections, noDate, thisWeek, lastWeek, earlier);
        } else {
            // Returned work first, newest first: a grade is the news this
            // tab exists to give. Then what is in and waiting to be graded.
            Section returned = new Section(Section.RETURNED);
            Section waiting = new Section(Section.TURNED_IN);
            for (Assignment item : inState(state, now)) {
                if (item.returned || item.excused) returned.items.add(item);
                else waiting.items.add(item);
            }
            sortBy(returned, item -> item.gradedAt != null ? item.gradedAt : item.turnedIn);
            sortBy(waiting, item -> item.turnedIn != null ? item.turnedIn : item.due);
            collect(sections, returned, waiting);
        }
        return sections;
    }

    private interface When {
        Long of(Assignment item);
    }

    /** Newest first, with work that has no date at the end. */
    private void sortBy(Section section, final When when) {
        Collections.sort(section.items, (left, right) -> {
            Long a = when.of(left);
            Long b = when.of(right);
            if (a == null) return b == null ? 0 : 1;
            if (b == null) return -1;
            return Long.compare(b.longValue(), a.longValue());
        });
    }

    // ------------------------------------------------------------------
    // The week strip
    // ------------------------------------------------------------------

    /** Assigned work due on the day starting at `midnight`, earliest first. */
    public List<Assignment> dueOn(long midnight, long now) {
        List<Assignment> due = new ArrayList<>();
        long end = Dates.addDays(midnight, 1);
        for (Assignment item : inState(Assignment.ASSIGNED, now)) {
            if (item.due != null && item.due.longValue() >= midnight && item.due.longValue() < end) due.add(item);
        }
        Collections.sort(due, (a, b) -> Long.compare(a.due.longValue(), b.due.longValue()));
        return due;
    }

    /** Assigned work with no due date, which sits under every week. */
    public List<Assignment> undated(long now) {
        List<Assignment> found = new ArrayList<>();
        for (Assignment item : inState(Assignment.ASSIGNED, now)) {
            if (item.due == null) found.add(item);
        }
        return found;
    }

    /**
     * The day the home screen opens on: today, unless nothing is left this
     * week, in which case the first day with work due, wherever it falls.
     * Opening on an empty week with work waiting the next is a screen that
     * says "nothing" while being wrong.
     */
    public long openingDay(long now) {
        long today = Dates.dayOf(now);
        Long first = null;
        for (Assignment item : inState(Assignment.ASSIGNED, now)) {
            if (item.due == null) continue;
            if (first == null || item.due.longValue() < first.longValue()) first = item.due;
        }
        if (first == null) return today;
        if (Dates.weeksBetween(now, first.longValue()) <= 0) return today;
        return Dates.dayOf(first.longValue());
    }

    /** The next or previous day with assigned work, or null. */
    public Long dayWithWork(long midnight, int direction, long now) {
        Long best = null;
        for (Assignment item : inState(Assignment.ASSIGNED, now)) {
            if (item.due == null) continue;
            long day = Dates.dayOf(item.due.longValue());
            if (direction > 0 ? day <= midnight : day >= midnight) continue;
            if (best == null || (direction > 0 ? day < best.longValue() : day > best.longValue())) {
                best = Long.valueOf(day);
            }
        }
        return best;
    }

    private void collect(List<Section> into, Section... candidates) {
        for (Section section : candidates) {
            if (!section.items.isEmpty()) into.add(section);
        }
    }

    /**
     * Sections are grouped by due date on every tab, including Done, where the
     * date something was turned in would have been the other candidate. Due
     * date wins because it is the date printed on the work itself: the same
     * assignment sits in the same week whichever tab you find it on, and
     * marking something done does not make it jump weeks.
     */
    private void sortByDue(Section section, final boolean ascending) {
        Collections.sort(
            section.items,
            (left, right) -> {
                long a = left.due == null ? 0L : left.due.longValue();
                long b = right.due == null ? 0L : right.due.longValue();
                int order = a < b ? -1 : a > b ? 1 : 0;
                return ascending ? order : -order;
            }
        );
    }
}
