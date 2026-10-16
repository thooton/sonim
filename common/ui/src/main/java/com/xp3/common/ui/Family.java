package com.xp3.common.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

/**
 * The family look in code, for the parts that are built rather than inflated
 * (see DESIGN.md at the root, and the family_* resources beside this).
 */
public final class Family {

    private Family() {}

    /** Roboto Medium: the family's one emphasis weight. */
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /**
     * A list in the family look: rows paint their own focus card, so the
     * platform selector is turned off, and the hairlines between rows are
     * inset to the text edge.
     */
    public static void list(ListView list) {
        list.setSelector(new ColorDrawable(0x00000000));
        list.setDivider(list.getContext().getDrawable(R.drawable.family_divider));
        list.setDividerHeight(Ui.dp(list.getContext(), 1));
        list.setBackgroundColor(list.getContext().getColor(R.color.row));
        list.setCacheColorHint(0x00000000);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
    }

    /**
     * What an empty screen says: a short title, and under it, smaller and
     * muted, what to do about it (which key to press). `hint` may be null.
     */
    public static CharSequence emptyText(Context context, String title, String hint) {
        SpannableStringBuilder out = new SpannableStringBuilder(title);
        int end = out.length();
        out.setSpan(new TypefaceSpan("sans-serif-medium"), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new AbsoluteSizeSpan(14, true), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(context.getColor(R.color.text)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (hint != null && hint.length() > 0) {
            out.append('\n');
            int start = out.length();
            out.append(hint);
            out.setSpan(new AbsoluteSizeSpan(12, true), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(context.getColor(R.color.text_muted)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** A centred empty state, as {@link #emptyText}, filling whatever it is given. */
    public static TextView empty(Context context, String title, String hint) {
        TextView view = new TextView(context);
        view.setText(emptyText(context, title, hint));
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(Ui.dp(context, 5), 1f);
        int pad = Ui.dp(context, 20);
        view.setPadding(pad, pad, pad, pad);
        view.setBackgroundColor(context.getColor(R.color.row));
        // It takes the focus so keys land somewhere; nothing on it changes.
        view.setDefaultFocusHighlightEnabled(false);
        return view;
    }
}
