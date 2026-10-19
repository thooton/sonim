package com.tablet.xp3;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tablet.xp3.data.Doc;

import java.util.List;

/**
 * The Library: the shelf of covers, and the selected file's title under the
 * highlighter with what it is beneath. Left and Right pick the file; OK
 * opens its menu (Sheet.actions): Open, Transform, Rename, Move, Details.
 *
 * The proposal kept those actions listed under the shelf all the time; on
 * the phone that left every cover and line too small to read, so they wait
 * in the menu and the shelf has the screen.
 */
final class LibraryScreen extends FrameLayout {

    /** The file's menu, in order. */
    static final int OPEN = 0;
    static final int TRANSFORM = 1;
    static final int DOWNLOAD = 2;
    static final int RENAME = 3;
    static final int MOVE = 4;
    static final int DETAILS = 5;
    static final int DELETE = 6;
    static final String[] LABELS = {"Open", "Transform", "Download PDF", "Rename", "Move to folder…", "Details", "Delete"};
    static final int[] ICONS = {R.drawable.ic_eye, R.drawable.ic_sparkle, R.drawable.ic_download, R.drawable.ic_pen, R.drawable.ic_move,
        R.drawable.ic_info, R.drawable.ic_trash};

    /** Where the title sits, in dp from the top: just under the shelf's dots. */
    private static final float CAPTION_TOP = 237;
    /** With no file to show, the shelf is empty and what is so sits mid-screen, with room for a few lines. */
    private static final float EMPTY_TOP = 112;

    final Shelf shelf;
    private final TextView synced = new TextView(getContext());
    private final Mark title;
    private final TextView meta;
    private final LinearLayout caption;

    LibraryScreen(Context c) {
        super(c);
        setBackgroundColor(Look.PAPER);
        float density = c.getResources().getDisplayMetrics().density;
        addView(Parts.header(c, "Library", R.drawable.ic_tablet, synced), Parts.headerAt(c));

        shelf = new Shelf(c);
        addView(shelf, new LayoutParams(Parts.FILL, Parts.FILL));

        caption = Parts.column(c);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        title = new Mark(c);
        Look.style(title, Look.serifBold, 13, Look.INK);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Look.dp(c, 3), Look.dp(c, 1), Look.dp(c, 3), Look.dp(c, 1));
        title.setMaxWidth(Math.round(224 * density));
        caption.addView(title, Parts.line(Parts.WRAP, Parts.WRAP));
        meta = Look.text(c, "", Look.ui, 9.5f, Look.GRAPHITE);
        meta.setGravity(Gravity.CENTER_HORIZONTAL);
        meta.setLineSpacing(0, 1.25f);
        meta.setSingleLine();
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = Parts.line(Parts.WRAP, Parts.WRAP);
        lp.topMargin = Look.dp(c, 3);
        caption.addView(meta, lp);
        LayoutParams at = new LayoutParams(Parts.FILL, Parts.WRAP);
        at.topMargin = Math.round(CAPTION_TOP * density);
        at.leftMargin = Math.round(8 * density);
        at.rightMargin = Math.round(8 * density);
        addView(caption, at);
    }

    void setDocs(List<Doc> docs, int file, Shelf.Covers covers) {
        shelf.setDocs(docs, file, covers);
    }

    /**
     * The caption under the shelf: the selected file's name under the
     * highlighter and what it is, or with nothing to select (`marked`
     * false), what is so and what to do about it.
     */
    void show(int file, String status, String name, String about, boolean marked) {
        shelf.select(file);
        synced.setText(status);
        title.setText(name);
        title.mark(marked);
        meta.setText(about);
        meta.setSingleLine(marked);
        meta.setMaxLines(marked ? 1 : 6);
        LayoutParams at = (LayoutParams) caption.getLayoutParams();
        int top = Math.round((marked ? CAPTION_TOP : EMPTY_TOP) * getResources().getDisplayMetrics().density);
        if (at.topMargin != top) {
            at.topMargin = top;
            caption.setLayoutParams(at);
        }
    }
}
