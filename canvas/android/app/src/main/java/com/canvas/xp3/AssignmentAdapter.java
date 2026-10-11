package com.canvas.xp3;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Dates;

import java.util.ArrayList;
import java.util.List;

/**
 * The sectioned list: headers and work in one flat run of rows.
 *
 * Collapsing is done by rebuilding this list rather than by hiding views,
 * because the rows a keypad steps through and the rows on screen have to be
 * the same rows. A hidden view still holds a position, and the selection
 * would walk into it.
 */
final class AssignmentAdapter extends SelectableAdapter {

    private static final int HEADER = 0;
    private static final int WORK = 1;
    private static final int EMPTY = 2;

    static final class Row {
        /** Non-null on a section header, null on a piece of work. */
        final String label;
        final int count;
        final boolean expanded;
        final Assignment item;

        private Row(String label, int count, boolean expanded, Assignment item) {
            this.label = label;
            this.count = count;
            this.expanded = expanded;
            this.item = item;
        }

        static Row header(String label, int count, boolean expanded) {
            return new Row(label, count, expanded, null);
        }

        static Row work(Assignment item) {
            return new Row(null, 0, false, item);
        }

        static Row empty() {
            return new Row(null, 0, false, null);
        }

        boolean isHeader() {
            return label != null;
        }

        boolean isEmpty() {
            return label == null && item == null;
        }
    }

    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();
    /** Which list this is, which decides what the right-hand column says. */
    private int state;
    private long now;

    AssignmentAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<Row> next, int state, long now) {
        this.state = state;
        this.now = now;
        rows.clear();
        if (next != null) rows.addAll(next);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 3; }

    @Override public boolean areAllItemsEnabled() { return false; }

    @Override public int getItemViewType(int position) {
        Row row = rows.get(position);
        return row.isHeader() ? HEADER : row.isEmpty() ? EMPTY : WORK;
    }

    @Override public boolean isEnabled(int position) {
        return !rows.get(position).isEmpty();
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Row row = rows.get(position);
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(
                row.isHeader() ? R.layout.row_section : R.layout.row_assignment,
                parent,
                false
            );
        }
        view.setSelected(isSelected(position));
        if (row.isHeader()) bindHeader(view, row);
        else if (row.isEmpty()) bindEmpty(view);
        else bindWork(view, row.item);
        return view;
    }

    private void bindEmpty(View view) {
        TextView title = view.findViewById(R.id.assignment_title);
        title.setText("Nothing to do this week");
        title.setTextColor(color(view, R.color.text_muted));
        view.findViewById(R.id.assignment_course).setVisibility(View.GONE);
        view.findViewById(R.id.assignment_when).setVisibility(View.GONE);
        view.findViewById(R.id.assignment_when_sub).setVisibility(View.GONE);
    }

    private void bindHeader(View view, Row row) {
        // Solid triangles rather than chevrons: at 9sp a chevron is three grey
        // pixels and reads as dirt on the screen.
        ((TextView) view.findViewById(R.id.section_caret)).setText(
            row.expanded ? "▼" : "▶"
        );
        ((TextView) view.findViewById(R.id.section_label)).setText(row.label);
        ((TextView) view.findViewById(R.id.section_count)).setText(
            String.valueOf(row.count)
        );
    }

    private void bindWork(View view, Assignment item) {
        ((TextView) view.findViewById(R.id.assignment_title)).setText(item.title);
        ((TextView) view.findViewById(R.id.assignment_course)).setText(item.course);

        TextView when = view.findViewById(R.id.assignment_when);
        TextView sub = view.findViewById(R.id.assignment_when_sub);

        if (state == Assignment.DONE) {
            // Done work is read for what it scored, so the score takes the top
            // line and the date drops beneath it. The section header above has
            // already said which week this is.
            String score = item.scoreLabel();
            when.setText(score == null ? "Done" : score);
            when.setTextColor(color(view, R.color.text));
            sub.setText(
                item.turnedIn == null
                    ? ""
                    : Dates.day(item.turnedIn.longValue(), now)
            );
        } else if (item.due == null) {
            // Nothing to count down to. A dash holds the column so the row
            // keeps its shape instead of reading as a different kind of row.
            when.setText("-");
            when.setTextColor(color(view, R.color.text_muted));
            sub.setText("");
        } else {
            when.setText(Dates.day(item.due.longValue(), now));
            when.setTextColor(
                color(
                    view,
                    state == Assignment.MISSING
                        ? R.color.overdue
                        : Dates.daysBetween(now, item.due.longValue()) <= 1
                          ? R.color.due_soon
                          : R.color.text
                )
            );
            sub.setText(Dates.time(item.due.longValue()));
        }
        sub.setVisibility(sub.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    private int color(View view, int resId) {
        return view.getResources().getColor(resId);
    }
}
