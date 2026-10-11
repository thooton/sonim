package com.canvas.xp3.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    /** Swaps in what a sync, or the cache read at startup, just produced. */
    public void replace(List<Assignment> next) {
        work.clear();
        if (next != null) work.addAll(next);
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
        List<Assignment> matching = new ArrayList<>();
        for (int i = 0; i < work.size(); i++) {
            if (work.get(i).state(now) == state) matching.add(work.get(i));
        }
        return matching;
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
            Section noDate = new Section(Section.NO_DUE_DATE);
            Section early = new Section(Section.DONE_EARLY);
            Section thisWeek = new Section(Section.THIS_WEEK);
            Section lastWeek = new Section(Section.LAST_WEEK);
            Section earlier = new Section(Section.EARLIER);
            for (Assignment item : inState(state, now)) {
                if (item.due == null) noDate.items.add(item);
                // Turned in on something that has not come due yet. It is
                // grouped by that rather than by its week, because "done
                // early" is the fact you would want back from this screen.
                else if (item.early(now)) early.items.add(item);
                else {
                    int week = Dates.weeksBetween(now, item.due.longValue());
                    if (week >= 0) thisWeek.items.add(item);
                    else if (week == -1) lastWeek.items.add(item);
                    else earlier.items.add(item);
                }
            }
            sortByDue(early, true);
            sortByDue(thisWeek, false);
            sortByDue(lastWeek, false);
            sortByDue(earlier, false);
            collect(sections, noDate, early, thisWeek, lastWeek, earlier);
        }
        return sections;
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
            new Comparator<Assignment>() {
                @Override
                public int compare(Assignment left, Assignment right) {
                    long a = left.due == null ? 0L : left.due.longValue();
                    long b = right.due == null ? 0L : right.due.longValue();
                    int order = a < b ? -1 : a > b ? 1 : 0;
                    return ascending ? order : -order;
                }
            }
        );
    }
}
