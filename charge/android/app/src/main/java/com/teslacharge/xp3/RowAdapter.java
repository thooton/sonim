package com.teslacharge.xp3;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A keypad-selectable list of compact rows sharing {@code row_site}. */
abstract class RowAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<JSONObject> rows = new ArrayList<>();
    private int selectedPosition = AdapterView.INVALID_POSITION;

    RowAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<JSONObject> next) {
        rows.clear();
        if (next != null) rows.addAll(next);
        selectedPosition = AdapterView.INVALID_POSITION;
        notifyDataSetChanged();
    }

    /** Grows the list under the selection. {@link #replace} is for a different
     *  list and drops the highlight; this is for more of the same one, where
     *  the row someone is reading has not moved. */
    void append(List<JSONObject> more) {
        if (more == null || more.isEmpty()) return;
        rows.addAll(more);
        notifyDataSetChanged();
    }

    void setSelectedPosition(int position) {
        if (position == selectedPosition) return;
        selectedPosition = position;
        notifyDataSetChanged();
    }

    int getSelectedPosition() { return selectedPosition; }

    @Override public int getCount() { return rows.size(); }
    @Override public JSONObject getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    /** For subclasses that put a row of a different shape at the end of the
     *  list and so have to inflate it themselves. */
    LayoutInflater inflater() { return inflater; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView == null ? inflater.inflate(R.layout.row_site, parent, false) : convertView;
        // ListView's selector is normally drawn underneath its child. These
        // rows have an opaque background, so mirror selection onto the row
        // itself to make the active entry unmistakable on the XP3.
        row.setSelected(position == selectedPosition);
        bind(row, getItem(position));
        return row;
    }

    abstract void bind(View row, JSONObject item);
}
