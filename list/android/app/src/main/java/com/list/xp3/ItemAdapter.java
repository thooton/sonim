package com.list.xp3;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.list.xp3.data.Item;

import java.util.ArrayList;
import java.util.List;

/** The checklist rows: a box and a task, struck through once it is done. */
final class ItemAdapter extends SelectableAdapter {

    private final LayoutInflater inflater;
    private final int textColor;
    private final int doneColor;
    private final List<Item> items = new ArrayList<>();

    ItemAdapter(Context context) {
        inflater = LayoutInflater.from(context);
        textColor = context.getResources().getColor(R.color.text);
        doneColor = context.getResources().getColor(R.color.done_text);
    }

    void replace(List<Item> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public Item getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) view = inflater.inflate(R.layout.row_item, parent, false);
        Item item = items.get(position);
        view.setSelected(isSelected(position));

        ImageView check = view.findViewById(R.id.item_check);
        check.setImageResource(item.done ? R.drawable.check_done : R.drawable.check_empty);

        TextView text = view.findViewById(R.id.item_text);
        text.setText(item.text);
        text.setTextColor(item.done ? doneColor : textColor);
        int flags = text.getPaintFlags();
        text.setPaintFlags(item.done
            ? flags | Paint.STRIKE_THRU_TEXT_FLAG
            : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
        view.setContentDescription((item.done ? "Done: " : "To do: ") + item.text);
        return view;
    }
}
