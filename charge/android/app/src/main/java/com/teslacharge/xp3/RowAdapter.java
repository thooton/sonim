package com.teslacharge.xp3;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.xp3.common.ui.SelectableAdapter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A keypad-selectable list of compact rows sharing {@code row_site}. */
abstract class RowAdapter extends SelectableAdapter {
    private final LayoutInflater inflater;
    private final List<JSONObject> rows = new ArrayList<>();

    RowAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<JSONObject> next) {
        rows.clear();
        if (next != null) rows.addAll(next);
        trackSelectedPosition(AdapterView.INVALID_POSITION);
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

    @Override public int getCount() { return rows.size(); }
    @Override public JSONObject getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }

    /** For subclasses that put a row of a different shape at the end of the
     *  list and so have to inflate it themselves. */
    LayoutInflater inflater() { return inflater; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView == null ? inflater.inflate(R.layout.row_site, parent, false) : convertView;
        row.setSelected(isSelected(position));
        bind(row, getItem(position));
        return row;
    }

    abstract void bind(View row, JSONObject item);
}
