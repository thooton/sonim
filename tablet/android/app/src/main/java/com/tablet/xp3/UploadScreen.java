package com.tablet.xp3;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Upload: the phone's storage, one line per folder or file, to pick a PDF or
 * ePub to put on the tablet. Up and Down move the highlighter, OK opens a
 * folder or picks a file, Back goes up a folder. Laid out as Transforms is.
 */
final class UploadScreen extends RowsScreen {

    /** One line: a place, a folder or a file. */
    static final class Line {
        final String name;
        final String about;
        final boolean folder;

        Line(String name, String about, boolean folder) {
            this.name = name;
            this.about = about;
            this.folder = folder;
        }
    }

    private final TextView where;

    UploadScreen(Context c) {
        this(c, new TextView(c));
    }

    private UploadScreen(Context c, TextView where) {
        super(c, "Upload", where);
        this.where = where;
        where.setEllipsize(TextUtils.TruncateAt.START);
        where.setMaxWidth(Look.dp(c, 110));
    }

    /** `selected` is the highlighted line, or -1 while the drawer or a sheet is over the list. */
    void show(String path, List<Line> lines, int selected, String empty) {
        Context c = getContext();
        where.setText(path);
        if (lines.isEmpty()) {
            showEmpty(empty, 1f);
            return;
        }
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            boolean on = i == selected;
            ImageView glyph = new ImageView(c);
            glyph.setImageDrawable(Look.icon(c, line.folder ? R.drawable.ic_folder : R.drawable.ic_file, Look.INK, 14));
            glyph.setScaleType(ImageView.ScaleType.CENTER);
            rows.add(row(c, glyph, 18, 5, on ? Look.uiBold : Look.ui, line.name,
                line.about.length() > 0 ? line.about : null, on, i < lines.size() - 1));
        }
        showRows(rows, selected);
    }
}
