package com.list.xp3;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.list.xp3.data.ChatTurn;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The transcript as a list rather than a column of views in a ScrollView.
 *
 * A ScrollView scrolls but its contents cannot be stepped through, and on a
 * phone with no touchscreen that means a message can be read and never
 * reached: no way to put the cursor on one, and so no way to copy it. A
 * ListView gives every turn a position, which is what the d-pad moves through
 * and what the copy menu opens on.
 */
final class ChatAdapter extends SelectableAdapter {

    private static final int AGENT = 0;
    private static final int YOU = 1;
    private static final int COMPACTION = 2;

    private static final long MINUTE_MS = 60L * 1000L;
    private static final long HOUR_MS = 60L * MINUTE_MS;
    private static final long WEEK_MS = 7L * 24L * HOUR_MS;

    private final Context context;
    private final LayoutInflater inflater;
    private final Markdown markdown;
    private final List<ChatTurn> turns = new ArrayList<>();

    ChatAdapter(Context context) {
        this.context = context;
        inflater = LayoutInflater.from(context);
        markdown = new Markdown(context, 0, 14);
    }

    void replace(List<ChatTurn> next) {
        turns.clear();
        if (next != null) turns.addAll(next);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return turns.size(); }
    @Override public ChatTurn getItem(int position) { return turns.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public boolean hasStableIds() { return true; }
    @Override public int getViewTypeCount() { return 3; }

    @Override public int getItemViewType(int position) {
        ChatTurn turn = turns.get(position);
        if (!turn.isMessage()) return COMPACTION;
        return turn.fromUser ? YOU : AGENT;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        ChatTurn turn = turns.get(position);
        View view = convertView;
        if (view == null) {
            int layout = !turn.isMessage()
                ? R.layout.row_chat_compaction
                : turn.fromUser ? R.layout.row_chat_you : R.layout.row_chat_agent;
            view = inflater.inflate(layout, parent, false);
        }
        view.setSelected(isSelected(position));
        if (!turn.isMessage()) {
            ((TextView) view.findViewById(R.id.compaction_text)).setText(
                turn.isCompaction() ? "Chat compacted" : "Compacting chat…"
            );
            return view;
        } else if (turn.fromUser) {
            ((TextView) view.findViewById(R.id.bubble_text)).setText(turn.text);
        } else {
            LinearLayout message = view.findViewById(R.id.bubble_text);
            // The same row is rebound as tokens stream in and when ListView
            // recycles it. Rebuild its Markdown blocks so stale views never
            // survive from the previous version of the response.
            message.removeAllViews();
            markdown.into(message, turn.text);
        }
        ((TextView) view.findViewById(R.id.bubble_time)).setText(stamp(turn.when));
        return view;
    }

    /**
     * The timestamp ladder the phone's Messages app uses, read out of its own
     * code rather than guessed at: Now, then minutes, then a clock time, then
     * a weekday and a clock time, then dates.
     *
     * Almost every message in this app lands in the first two rungs, which is
     * the point of copying it. A chat you are having right now wants "Now" and
     * "3 mins", not the same clock time printed over and over down the screen.
     *
     * The formatting itself goes through DateUtils with the flags that app
     * passes, so 12 or 24 hour, the abbreviations and the locale all come from
     * the phone instead of from a pattern written here.
     */
    private String stamp(long when) {
        long age = System.currentTimeMillis() - when;
        if (age < MINUTE_MS) return context.getString(R.string.posted_now);
        if (age < HOUR_MS) {
            int minutes = (int) (age / MINUTE_MS);
            return context
                .getResources()
                .getQuantityString(R.plurals.minutes_ago, minutes, minutes);
        }
        int flags =
            DateUtils.FORMAT_ABBREV_ALL |
            DateUtils.FORMAT_NO_NOON |
            DateUtils.FORMAT_NO_MIDNIGHT |
            DateUtils.FORMAT_CAP_AMPM;
        long stampedNow = System.currentTimeMillis();
        if (sameDay(stampedNow, when)) {
            flags |= DateUtils.FORMAT_SHOW_TIME;
        } else if (age < WEEK_MS) {
            flags |= DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY;
        } else if (sameYear(stampedNow, when)) {
            flags |= DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE;
        } else {
            flags |= DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR;
        }
        return DateUtils.formatDateTime(context, when, flags);
    }

    private static boolean sameDay(long a, long b) {
        Calendar first = calendar(a);
        Calendar second = calendar(b);
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
            && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    private static boolean sameYear(long a, long b) {
        return calendar(a).get(Calendar.YEAR) == calendar(b).get(Calendar.YEAR);
    }

    private static Calendar calendar(long when) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(when);
        return calendar;
    }
}
