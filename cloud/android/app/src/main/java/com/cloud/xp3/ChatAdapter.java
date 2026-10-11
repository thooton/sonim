package com.cloud.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cloud.xp3.data.Entry;

import java.util.ArrayList;
import java.util.List;

/**
 * The transcript as a list rather than a column of views in a ScrollView.
 *
 * A ScrollView scrolls but its contents cannot be stepped through, and on a
 * phone with no touchscreen that means a message can be read and never
 * reached: no way to put the cursor on one, and so no way to copy it. A
 * ListView gives every entry a position, which is what the d-pad moves through
 * and what the copy menu opens on.
 *
 * While a turn runs, one extra note row at the end says so; it is not an
 * entry and is never saved.
 *
 * A message's pictures are drawn from the files saved when it was sent from
 * this phone. One sent from somewhere else, or from before a reinstall, has
 * no file here and shows as a placeholder line instead.
 */
final class ChatAdapter extends SelectableAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final Markdown markdown;
    private final Pictures pictures;
    private final List<Entry> entries = new ArrayList<>();
    private int saved;

    ChatAdapter(Context context, Pictures pictures) {
        this.context = context;
        this.pictures = pictures;
        inflater = LayoutInflater.from(context);
        markdown = new Markdown(context, 0, 14);
    }

    /** The entries, plus a working line when `working` is not null. */
    void replace(List<Entry> next, String working) {
        entries.clear();
        if (next != null) entries.addAll(next);
        saved = entries.size();
        if (working != null) entries.add(new Entry(Entry.NOTE, working, null));
        notifyDataSetChanged();
    }

    /** Whether the row is a real entry rather than the working line. */
    boolean isEntry(int position) {
        return position >= 0 && position < saved;
    }

    @Override public int getCount() { return entries.size(); }
    @Override public Entry getItem(int position) { return entries.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public boolean hasStableIds() { return true; }
    @Override public int getViewTypeCount() { return 4; }
    @Override public int getItemViewType(int position) { return entries.get(position).kind; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Entry entry = entries.get(position);
        View view = convertView;
        if (view == null) view = inflater.inflate(layout(entry.kind), parent, false);
        view.setSelected(isSelected(position));
        if (entry.kind == Entry.YOU) {
            TextView text = view.findViewById(R.id.bubble_text);
            text.setText(entry.text);
            text.setVisibility(entry.text.length() == 0 ? View.GONE : View.VISIBLE);
            bindPictures((LinearLayout) view.findViewById(R.id.bubble_pictures), entry);
        } else if (entry.kind == Entry.CLAUDE) {
            LinearLayout message = view.findViewById(R.id.bubble_text);
            // Recycled rows keep their old blocks; rebuild them every time.
            message.removeAllViews();
            markdown.into(message, entry.text);
        } else if (entry.kind == Entry.TOOL) {
            ((TextView) view.findViewById(R.id.tool_text)).setText("› " + entry.text);
        } else {
            ((TextView) view.findViewById(R.id.note_text)).setText(entry.text);
        }
        return view;
    }

    /** Thumbnails a little narrower than the screen, so the bubble's edge still shows. */
    private void bindPictures(LinearLayout holder, Entry entry) {
        holder.removeAllViews();
        holder.setVisibility(entry.pictures == 0 ? View.GONE : View.VISIBLE);
        int width = context.getResources().getDisplayMetrics().widthPixels * 2 / 3;
        int gap = dp(4);
        for (int i = 0; i < entry.pictures; i++) {
            Bitmap bitmap = entry.id == null ? null : pictures.thumbnail(pictures.sent(entry.id, i), width);
            View shown;
            if (bitmap != null) {
                ImageView image = new ImageView(context);
                image.setImageBitmap(bitmap);
                image.setAdjustViewBounds(true);
                image.setMaxWidth(width);
                image.setMaxHeight(dp(140));
                image.setScaleType(ImageView.ScaleType.FIT_START);
                shown = image;
            } else {
                TextView placeholder = new TextView(context);
                placeholder.setText("[Picture]");
                placeholder.setTextSize(12);
                placeholder.setTextColor(context.getResources().getColor(R.color.text_muted));
                shown = placeholder;
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = gap;
            holder.addView(shown, params);
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int layout(int kind) {
        if (kind == Entry.YOU) return R.layout.row_chat_you;
        if (kind == Entry.CLAUDE) return R.layout.row_chat_agent;
        if (kind == Entry.TOOL) return R.layout.row_chat_tool;
        return R.layout.row_chat_note;
    }
}
