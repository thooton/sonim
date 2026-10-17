package com.canvas.xp3;

import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Detail;

/**
 * The pieces of a conversation: message bubbles and the compose box at the
 * bottom, shared by private comments (G2) and class comments (G3).
 */
final class Talk {

    private Talk() {}

    /** A message: the student's in ink on the right, everyone else's on paper on the left. */
    static View bubble(Views v, Detail.Comment said, long now) {
        Look look = v.look;
        LinearLayout box = v.column();
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(said.mine ? look.ink : look.paper);
        float r = v.dp(12);
        float small = v.dp(3);
        shape.setCornerRadii(said.mine
            ? new float[] { r, r, r, r, small, small, r, r }
            : new float[] { r, r, r, r, r, r, small, small });
        box.setBackground(shape);
        v.pad(box, 8, 5, 8, 5);
        int text = said.mine ? look.bg : look.ink;
        TextView who = v.text((said.mine ? "You" : Course.shortName(said.author)) + " · " + when(said.at, now), 8,
            look.bold, said.mine ? (look.bg & 0x00FFFFFF) | 0x99000000 : look.muted);
        box.addView(who);
        TextView body = v.text(said.text, 10, look.regular, text);
        body.setLineSpacing(0, 1.25f);
        box.addView(body, v.margins(Views.wrap(), 0, 1, 0, 0));
        LinearLayout row = v.row();
        row.setGravity(said.mine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams at = Views.wrap();
        row.addView(box, at);
        box.post(() -> {
            // At most four fifths of the width, as a text thread looks.
            int limit = Math.round(row.getWidth() * 0.82f);
            if (box.getWidth() > limit) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) box.getLayoutParams();
                p.width = limit;
                box.setLayoutParams(p);
            }
        });
        return row;
    }

    /** "Fri", "Oct 9", or a time for today. */
    static String when(long at, long now) {
        int days = Dates.daysBetween(at, now);
        if (days == 0) return Dates.time(at);
        if (days < 7) return Dates.shortDay(at).split(",")[0];
        return Dates.shortDay(at).split(", ")[1];
    }

    /** The compose box and its round send mark. Send is on OK. */
    static EditText compose(MainActivity app, Views v, LinearLayout into, String hint) {
        Look look = v.look;
        LinearLayout row = v.row();
        row.setGravity(Gravity.BOTTOM);
        v.pad(row, 10, 6, 10, 6);
        EditText field = Fields.box(app, v, "", hint, InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMaxLines(4);
        row.addView(field, Views.fill());
        View send = v.tile(R.drawable.ic_send, look.ink, look.bg, 24, 12, 12);
        row.addView(send, v.margins(new LinearLayout.LayoutParams(v.dp(24), v.dp(24)), 6, 0, 0, 0));
        into.addView(row, Views.wide());
        return field;
    }
}
