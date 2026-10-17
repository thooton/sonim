package com.list.xp3;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;

import com.list.xp3.data.Item;
import com.xp3.common.ui.SelectableAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * The list laid out as Google Tasks lays it out: two white cards.
 *
 * The first is the list itself: its heading, then the open tasks in order
 * (or a word on why there are none). The second, only when something is
 * done, is "Completed (n)", which folds the finished tasks away or shows
 * them. Both cards are drawn by their rows, each painting its own slice
 * with the card's corners where it starts and ends, so the whole thing is
 * still one ListView that the d-pad walks through.
 *
 * Positions here are rows, not items: a row knows the index of the item it
 * shows. Headings, the gap and the empty note are not selectable.
 */
final class ItemAdapter extends SelectableAdapter {

    static final int HEADER = 0;
    static final int TASK = 1;
    static final int EMPTY = 2;
    static final int GAP = 3;
    static final int COMPLETED = 4;
    static final int DONE = 5;

    private static final int TOP = 1;
    private static final int BOTTOM = 2;

    static final class Row {
        final int type;
        /** The item's index in the checklist, or -1 for a row with none. */
        final int index;
        final Item item;
        int corners;

        Row(int type, int index, Item item) {
            this.type = type;
            this.index = index;
            this.item = item;
        }
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final int textColor;
    private final int doneColor;
    private final int cardColor;
    private final int focusColor;
    private final float radius;
    private final int gap;
    private final List<Row> rows = new ArrayList<>();
    private int doneCount;
    private boolean expanded;

    ItemAdapter(Context context) {
        this.context = context;
        inflater = LayoutInflater.from(context);
        textColor = context.getColor(R.color.text);
        doneColor = context.getColor(R.color.done_text);
        cardColor = context.getColor(R.color.card);
        focusColor = context.getColor(R.color.focus_fill);
        radius = context.getResources().getDimension(R.dimen.card_radius);
        gap = context.getResources().getDimensionPixelSize(R.dimen.card_gap);
    }

    /** Lays the rows out again from `items`, with the finished ones shown or folded away. */
    void replace(List<Item> items, boolean showCompleted) {
        rows.clear();
        expanded = showCompleted;
        doneCount = 0;

        rows.add(new Row(HEADER, -1, null));
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.done) doneCount++;
            else rows.add(new Row(TASK, i, item));
        }
        if (rows.size() == 1) rows.add(new Row(EMPTY, -1, null));
        rows.get(0).corners |= TOP;
        rows.get(rows.size() - 1).corners |= BOTTOM;

        if (doneCount > 0) {
            rows.add(new Row(GAP, -1, null));
            Row heading = new Row(COMPLETED, -1, null);
            heading.corners = TOP;
            rows.add(heading);
            if (showCompleted) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).done) rows.add(new Row(DONE, i, items.get(i)));
                }
            }
            rows.get(rows.size() - 1).corners |= BOTTOM;
        }
        notifyDataSetChanged();
    }

    /** The row showing the item at checklist `index`, or -1. */
    int positionOf(int index) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).index == index) return i;
        }
        return -1;
    }

    /** The selectable row at `position`, else the nearest after it, else before it; or -1. */
    int nearestSelectable(int position) {
        int start = Math.max(0, Math.min(position, rows.size() - 1));
        for (int i = start; i < rows.size(); i++) if (isEnabled(i)) return i;
        for (int i = start - 1; i >= 0; i--) if (isEnabled(i)) return i;
        return AdapterView.INVALID_POSITION;
    }

    /** The next or previous row of the same kind (open or done) as `position`, or -1. */
    int neighbour(int position, int step) {
        int type = rows.get(position).type;
        int next = position + step;
        if (next < 0 || next >= rows.size() || rows.get(next).type != type) return -1;
        return next;
    }

    @Override public int getCount() { return rows.size(); }
    @Override public Row getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 6; }
    @Override public int getItemViewType(int position) { return rows.get(position).type; }
    @Override public boolean areAllItemsEnabled() { return false; }

    @Override
    public boolean isEnabled(int position) {
        int type = rows.get(position).type;
        return type == TASK || type == DONE || type == COMPLETED;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Row row = rows.get(position);
        View view = convertView;
        switch (row.type) {
            case HEADER:
                if (view == null) view = inflater.inflate(R.layout.row_header, parent, false);
                break;
            case EMPTY:
                if (view == null) view = inflater.inflate(R.layout.row_empty, parent, false);
                ((TextView) view).setText(doneCount > 0
                    ? emptyText("All tasks complete", "Nice work!")
                    : emptyText("No tasks yet", "Add your to-dos and keep track of them here"));
                break;
            case GAP:
                if (view == null) {
                    view = new View(context);
                    view.setLayoutParams(new AdapterView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, gap));
                }
                return view;
            case COMPLETED: {
                if (view == null) view = inflater.inflate(R.layout.row_completed, parent, false);
                ((TextView) view.findViewById(R.id.completed_text))
                    .setText("Completed (" + doneCount + ")");
                ((ImageView) view.findViewById(R.id.completed_chevron))
                    .setImageResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
                view.setContentDescription("Completed, " + doneCount + (expanded ? ", shown" : ", hidden"));
                break;
            }
            default:
                if (view == null) view = inflater.inflate(R.layout.row_item, parent, false);
                bindTask(view, row.item);
                break;
        }
        // A background with insets brings them in as padding; the row keeps its own.
        int left = view.getPaddingLeft(), top = view.getPaddingTop();
        int right = view.getPaddingRight(), bottom = view.getPaddingBottom();
        view.setBackground(isEnabled(position) ? selectableCard(row.corners) : card(row.corners, false));
        view.setPadding(left, top, right, bottom);
        // ListView moves the selected state between rows as the d-pad moves;
        // this sets it right for a row it has just been handed.
        view.setSelected(isSelected(position));
        return view;
    }

    private void bindTask(View view, Item item) {
        ImageView check = view.findViewById(R.id.item_check);
        check.setImageResource(item.done ? R.drawable.check_done : R.drawable.check_empty);

        TextView text = view.findViewById(R.id.item_text);
        text.setText(item.text);
        text.setTextColor(item.done ? doneColor : textColor);
        int flags = text.getPaintFlags();
        text.setPaintFlags(item.done
            ? flags | Paint.STRIKE_THRU_TEXT_FLAG
            : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);

        // A finished task has no star in Google Tasks.
        view.findViewById(R.id.item_star).setVisibility(item.done ? View.INVISIBLE : View.VISIBLE);
        view.setContentDescription((item.done ? "Done: " : "To do: ") + item.text);
    }

    /** A card slice that shows the d-pad's pill while its row is selected. */
    private Drawable selectableCard(int corners) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_selected}, card(corners, true));
        states.addState(new int[] {}, card(corners, false));
        return states;
    }

    /**
     * A row's slice of its card: white, rounded where the card starts or
     * ends, with the pale blue pill of the d-pad's row inset inside it.
     */
    private Drawable card(int corners, boolean focused) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(cardColor);
        float top = (corners & TOP) != 0 ? radius : 0;
        float bottom = (corners & BOTTOM) != 0 ? radius : 0;
        fill.setCornerRadii(new float[] {top, top, top, top, bottom, bottom, bottom, bottom});
        if (!focused) return fill;

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(focusColor);
        pill.setCornerRadius(radius - dp(3));
        int inset = dp(3);
        return new LayerDrawable(new Drawable[] {fill, new InsetDrawable(pill, inset, dp(1), inset, dp(1))});
    }

    private CharSequence emptyText(String title, String hint) {
        SpannableStringBuilder out = new SpannableStringBuilder(title);
        int end = out.length();
        out.setSpan(new AbsoluteSizeSpan(15, true), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(textColor), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
        int start = out.length();
        out.append(hint);
        out.setSpan(new TypefaceSpan("sans-serif"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new AbsoluteSizeSpan(12, true), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(context.getColor(R.color.text_muted)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
