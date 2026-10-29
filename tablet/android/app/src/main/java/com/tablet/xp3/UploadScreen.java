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
 * folder or picks a file, Back goes up a folder. Laid out as Transforms is,
 * so the list never moves under the eye.
 */
final class UploadScreen extends ListScreen {

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

    UploadScreen(Context c) {
        super(c, "Upload");
        side.setEllipsize(TextUtils.TruncateAt.START);
        side.setMaxWidth(Look.dp(c, 110));
    }

    /** `selected` is the highlighted line, or -1 while the drawer or a sheet is over the list. */
    void show(String path, List<Line> lines, int selected, String empty) {
        Context c = getContext();
        side.setText(path);
        if (lines.isEmpty()) {
            TextView none = Look.text(c, empty, Look.ui, 10.5f, Look.GRAPHITE);
            none.setPadding(Look.dp(c, 4), Look.dp(c, 4), Look.dp(c, 4), Look.dp(c, 4));
            showEmpty(none, Parts.line(Parts.FILL, Parts.WRAP));
            return;
        }
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            boolean on = i == selected;
            ImageView glyph = new ImageView(c);
            glyph.setImageDrawable(Look.icon(c, line.folder ? R.drawable.ic_folder : R.drawable.ic_file, Look.INK, 14));
            glyph.setScaleType(ImageView.ScaleType.CENTER);
            rows.add(row(c, glyph, 18, 5, on ? Look.uiBold : Look.ui, line.name, on,
                line.about.length() > 0 ? line.about : null));
        }
        showRows(rows, selected);
    }
}
