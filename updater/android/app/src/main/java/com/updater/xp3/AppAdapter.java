package com.updater.xp3;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.updater.xp3.core.Catalog;
import com.updater.xp3.core.Notes;
import com.updater.xp3.core.Status;
import com.xp3.common.ui.SelectableAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * The app rows, as the App Store's Updates page draws them: icon, name, date
 * and a capsule button, with what's new underneath, grouped under Available,
 * Not Installed and Installed (the order MainActivity sorts them in).
 *
 * As in List, the highlight is painted by the rows (SelectableAdapter).
 */
final class AppAdapter extends SelectableAdapter {

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

        /** Whether Update All would queue it. */
        boolean updatable() {
            return status == Status.UPDATE && activity == null;
        }

        /** Which heading it is under: 0 Available, 1 Not Installed, 2 Installed. */
        int group() {
            if (status == Status.UPDATE) return 0;
            if (status == Status.INSTALL) return 1;
            return 2;
        }
    }

    private static final String[] GROUPS = {"Available", "Not Installed", "Installed"};

    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();

    AppAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<Row> next) {
        rows.clear();
        rows.addAll(next);
        notifyDataSetChanged();
    }

    List<Row> rows() {
        return rows;
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_app, parent, false);
        Row row = rows.get(position);
        boolean selected = isSelected(position);
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
        pill.setSelected(selected);

        TextView notes = view.findViewById(R.id.app_notes);
        notes.setVisibility(row.notes.isEmpty() ? View.GONE : View.VISIBLE);
        if (!row.notes.isEmpty()) notes.setText(row.notes.get(0).text);

        view.setContentDescription(row.label + ": " + row.describe());
        return view;
    }
}
