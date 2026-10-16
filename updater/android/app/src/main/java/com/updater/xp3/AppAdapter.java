package com.updater.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.updater.xp3.core.Catalog;
import com.updater.xp3.core.Status;
import com.xp3.common.ui.Family;

import java.util.ArrayList;
import java.util.List;

/**
 * The app rows: a name, and under it where the phone's copy stands or what is
 * being done to it, grouped under Updates, Up to date and Not installed (the
 * order MainActivity sorts them in).
 *
 * As in List, the highlight is painted by the rows (their background is
 * opaque, so ListView's selector would be hidden), which means the adapter
 * mirrors ListView's selection. ListView still owns all d-pad movement.
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

        Row(Catalog.Entry entry, String label, String installed, Status status, String activity) {
            this.entry = entry;
            this.label = label;
            this.installed = installed;
            this.status = status;
            this.activity = activity;
        }

        /** The line under the name; the group's heading says the rest. */
        String detail() {
            if (activity != null) return activity;
            switch (status) {
                case UPDATE:
                    return (installed != null ? installed : "?") + " → " + entry.version;
                case NEWER:
                    return installed + ", newer than " + entry.version;
                default:
                    return entry.version;
            }
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

        boolean wanted() {
            return activity != null || status == Status.UPDATE;
        }

        /** Which heading it is under: 0 Updates, 1 Up to date, 2 Not installed. */
        int group() {
            if (status == Status.UPDATE) return 0;
            if (status == Status.INSTALL) return 2;
            return 1;
        }
    }

    private static final String[] GROUPS = {"Updates", "Up to date", "Not installed"};

    private final LayoutInflater inflater;
    private final int textMuted;
    private final int wanted;
    private final List<Row> rows = new ArrayList<>();
    private int selectedPosition = AdapterView.INVALID_POSITION;

    AppAdapter(Context context) {
        inflater = LayoutInflater.from(context);
        textMuted = context.getResources().getColor(R.color.text_muted);
        wanted = context.getResources().getColor(R.color.wanted);
    }

    void replace(List<Row> next) {
        rows.clear();
        rows.addAll(next);
        notifyDataSetChanged();
    }

    void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    /** Records ListView's native selection without causing a second layout. */
    void trackSelectedPosition(int position) {
        selectedPosition = position;
    }

    int getSelectedPosition() {
        return selectedPosition;
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_app, parent, false);
        Row row = rows.get(position);
        view.setSelected(position == selectedPosition);

        int group = row.group();
        boolean first = position == 0 || rows.get(position - 1).group() != group;
        boolean last = position == rows.size() - 1 || rows.get(position + 1).group() != group;
        view.findViewById(R.id.app_section).setVisibility(first ? View.VISIBLE : View.GONE);
        if (first) {
            int count = 0;
            for (Row other : rows) if (other.group() == group) count++;
            ((TextView) view.findViewById(R.id.app_section_name)).setText(GROUPS[group]);
            ((TextView) view.findViewById(R.id.app_section_count)).setText(String.valueOf(count));
        }
        view.findViewById(R.id.app_rule).setVisibility(last ? View.INVISIBLE : View.VISIBLE);

        TextView name = view.findViewById(R.id.app_name);
        name.setText(row.label);
        // Medium for the apps that want something done, so they lead.
        name.setTypeface(group == 1 ? Typeface.DEFAULT : Family.MEDIUM);
        TextView status = view.findViewById(R.id.app_status);
        status.setText(row.detail());
        status.setTextColor(row.wanted() ? wanted : textMuted);

        TextView pill = view.findViewById(R.id.app_pill);
        String action = row.activity != null ? null
            : row.status == Status.UPDATE ? "Update"
            : row.status == Status.INSTALL ? "Install"
            : null;
        pill.setVisibility(action != null ? View.VISIBLE : View.GONE);
        if (action != null) pill.setText(action);

        view.setContentDescription(row.label + ": " + row.describe());
        return view;
    }
}
