package com.updater.xp3;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.updater.xp3.core.Catalog;
import com.updater.xp3.core.Notes;
import com.updater.xp3.core.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * The app rows, as the App Store's Updates page draws them: icon, name, date
 * and a capsule button, with what's new underneath, grouped under Available,
 * Installed and Not Installed (the order MainActivity sorts them in).
 *
 * As in List, the highlight is painted by the rows (their background is
 * opaque, so ListView's selector would be hidden), which means the adapter
 * mirrors ListView's selection. ListView still owns all d-pad movement; Right
 * and Left only move between the row's button and its "more".
 */
final class AppAdapter extends BaseAdapter {

    /** One app in the release, as it stands on this phone. */
    static final class Row {
        final Catalog.Entry entry;
        final String label;
        /** The phone's versionName, or null when it has none or no app. */
        final String installed;
        final Status status;
        /** What is being done to it ("Downloading 40%"), or null. */
        final String activity;
        /** How far a download has got, 0 to 100, or -1 when there is no telling. */
        final int percent;
        final Drawable icon;
        /** What's new in it, newest first; empty when unknown. */
        final List<Notes.Note> notes;

        Row(Catalog.Entry entry, String label, String installed, Status status,
            String activity, int percent, Drawable icon, List<Notes.Note> notes) {
            this.entry = entry;
            this.label = label;
            this.installed = installed;
            this.status = status;
            this.activity = activity;
            this.percent = percent;
            this.icon = icon;
            this.notes = notes;
        }

        /** The capsule's word, or null while the app is being worked on. */
        String action() {
            if (activity != null) return null;
            if (status == Status.UPDATE) return "UPDATE";
            if (status == Status.INSTALL) return "GET";
            return "OPEN";
        }

        /** The same in full, for a screen reader, which hears no heading. */
        String describe() {
            if (activity != null) return activity;
            String latest = entry.version;
            switch (status) {
                case INSTALL:
                    return "Not installed · " + latest;
                case UPDATE:
                    return (installed != null ? installed : "?") + " → " + latest;
                case NEWER:
                    return installed + ", newer than " + latest;
                default:
                    return "Up to date · " + latest;
            }
        }

        /** Which heading it is under: 0 Available, 1 Installed, 2 Not Installed. */
        int group() {
            if (status == Status.UPDATE) return 0;
            if (status == Status.INSTALL) return 2;
            return 1;
        }
    }

    private static final String[] GROUPS = {"Available", "Installed", "Not Installed"};

    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();
    private int selectedPosition = AdapterView.INVALID_POSITION;
    /** Whether the d-pad is on the selected row's "more" rather than its button. */
    private boolean moreFocused;

    AppAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<Row> next) {
        rows.clear();
        rows.addAll(next);
        if (moreFocused && !hasMore(selectedPosition)) moreFocused = false;
        notifyDataSetChanged();
    }

    void setSelectedPosition(int position) {
        selectedPosition = position;
        moreFocused = false;
        notifyDataSetChanged();
    }

    /** Records ListView's native selection without causing a second layout. */
    void trackSelectedPosition(int position) {
        if (position != selectedPosition && moreFocused) {
            moreFocused = false;
            selectedPosition = position;
            notifyDataSetChanged();
            return;
        }
        selectedPosition = position;
    }

    int getSelectedPosition() {
        return selectedPosition;
    }

    /** Moves the d-pad onto the selected row's "more" (true) or back to its button. */
    void setMoreFocused(boolean more) {
        boolean next = more && hasMore(selectedPosition);
        if (next == moreFocused) return;
        moreFocused = next;
        notifyDataSetChanged();
    }

    boolean isMoreFocused() {
        return moreFocused;
    }

    private boolean hasMore(int position) {
        return position >= 0 && position < rows.size() && !rows.get(position).notes.isEmpty();
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_app, parent, false);
        Row row = rows.get(position);
        boolean selected = position == selectedPosition;
        view.findViewById(R.id.app_body).setSelected(selected);

        int group = row.group();
        boolean first = position == 0 || rows.get(position - 1).group() != group;
        TextView section = view.findViewById(R.id.app_section);
        section.setVisibility(first ? View.VISIBLE : View.GONE);
        if (first) section.setText(GROUPS[group]);
        view.findViewById(R.id.app_rule).setVisibility(position == rows.size() - 1 ? View.INVISIBLE : View.VISIBLE);

        ((ImageView) view.findViewById(R.id.app_icon)).setImageDrawable(row.icon);
        ((TextView) view.findViewById(R.id.app_name)).setText(row.label);
        TextView date = view.findViewById(R.id.app_date);
        date.setText(row.notes.isEmpty()
            ? "Version " + row.entry.version
            : Notes.when(row.notes.get(0).time, System.currentTimeMillis(), TimeZone.getDefault()));

        TextView pill = view.findViewById(R.id.app_pill);
        ProgressRing ring = view.findViewById(R.id.app_ring);
        String action = row.action();
        pill.setVisibility(action != null ? View.VISIBLE : View.GONE);
        ring.setVisibility(action != null ? View.GONE : View.VISIBLE);
        if (action != null) pill.setText(action);
        else ring.setProgress(row.percent);
        pill.setSelected(selected && !moreFocused);

        View notesLine = view.findViewById(R.id.app_notes_line);
        notesLine.setVisibility(row.notes.isEmpty() ? View.GONE : View.VISIBLE);
        if (!row.notes.isEmpty()) {
            ((TextView) view.findViewById(R.id.app_notes)).setText(row.notes.get(0).text);
            view.findViewById(R.id.app_more).setSelected(selected && moreFocused);
        }

        view.setContentDescription(row.label + ": " + row.describe());
        return view;
    }
}
