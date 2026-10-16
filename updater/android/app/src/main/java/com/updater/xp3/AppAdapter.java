package com.updater.xp3;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.updater.xp3.core.Catalog;
import com.updater.xp3.core.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * The app rows: a name, and under it where the phone's copy stands or what is
 * being done to it.
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
    }

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
        ((TextView) view.findViewById(R.id.app_name)).setText(row.label);
        TextView status = view.findViewById(R.id.app_status);
        status.setText(row.describe());
        status.setTextColor(row.wanted() ? wanted : textMuted);
        view.setContentDescription(row.label + ": " + row.describe());
        return view;
    }
}
