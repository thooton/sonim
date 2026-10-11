package com.canvas.xp3;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The settings list: a label, what it is currently set to, and nothing else. */
final class SettingsAdapter extends SelectableAdapter {

    static final class Row {

        /** The Config field this edits, or null for an action like signing out. */
        final String field;
        final String label;
        final String value;
        /** Whether the value is a credential, and so shown as Set rather than shown. */
        final boolean secret;

        Row(String field, String label, String value, boolean secret) {
            this.field = field;
            this.label = label;
            this.value = value;
            this.secret = secret;
        }
    }

    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();

    SettingsAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<Row> next) {
        rows.clear();
        if (next != null) rows.addAll(next);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Row row = rows.get(position);
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_setting, parent, false);
        view.setSelected(isSelected(position));
        ((TextView) view.findViewById(R.id.setting_label)).setText(row.label);
        ((TextView) view.findViewById(R.id.setting_value)).setText(shown(row));
        return view;
    }

    /**
     * A token is shown as "Set" rather than as itself.
     *
     * Not because somebody reading over your shoulder is the threat, but
     * because there is nothing to do with sixty characters of key on a 240px
     * screen except wrap them over four lines and push everything else off the
     * bottom. Whether it is there is the only question this screen is asked.
     */
    private static String shown(Row row) {
        if (row.field == null) return row.value;
        if (row.value == null || row.value.length() == 0) return "Not set";
        return row.secret ? "Set" : row.value;
    }
}
