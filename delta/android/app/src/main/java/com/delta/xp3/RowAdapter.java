package com.delta.xp3;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Chats, Files and Accounts lists: a name, a quiet line under it (two on
 * Accounts), and a short value on the right ("Working", "On phone"). Rows are named by a key rather than
 * found by position, so a refresh that reorders the list can put the
 * highlight back on the row someone was on.
 */
final class RowAdapter extends SelectableAdapter {

    static final class Row {
        final String key;
        final String title;
        final String sub;
        final String value;
        /** A second quiet line, or empty for none. */
        final String extra;

        Row(String key, String title, String sub, String value) {
            this(key, title, sub, value, null);
        }

        Row(String key, String title, String sub, String value, String extra) {
            this.key = key;
            this.title = title;
            this.sub = sub == null ? "" : sub;
            this.value = value == null ? "" : value;
            this.extra = extra == null ? "" : extra;
        }
    }

    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();

    RowAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<Row> next) {
        rows.clear();
        if (next != null) rows.addAll(next);
        notifyDataSetChanged();
    }

    /** The row's position, or -1. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).key)) return i;
        }
        return -1;
    }

    /** The highlighted row, or null. */
    Row selected() {
        int position = getSelectedPosition();
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView == null ? inflater.inflate(R.layout.row_item, parent, false) : convertView;
        // ListView's selector is drawn underneath its children, and these rows
        // are opaque, so the row paints its own highlight.
        view.setSelected(isSelected(position));
        Row row = rows.get(position);
        ((TextView) view.findViewById(R.id.row_title)).setText(row.title);
        TextView sub = view.findViewById(R.id.row_sub);
        sub.setText(row.sub);
        sub.setVisibility(row.sub.length() == 0 ? View.GONE : View.VISIBLE);
        TextView extra = view.findViewById(R.id.row_extra);
        extra.setText(row.extra);
        extra.setVisibility(row.extra.length() == 0 ? View.GONE : View.VISIBLE);
        TextView value = view.findViewById(R.id.row_value);
        value.setText(row.value);
        value.setVisibility(row.value.length() == 0 ? View.GONE : View.VISIBLE);
        return view;
    }
}
