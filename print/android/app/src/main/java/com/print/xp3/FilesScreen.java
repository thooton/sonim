package com.print.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The PDFs in one place (screen 03), newest first, or one folder of the
 * phone's storage. Number keys search at once (screen 04): each key stands
 * for its letters, as in a phone's contact search, and the matching letters
 * are highlighted. Menu changes the order.
 */
final class FilesScreen extends Screen {

    /** Rows built at most; search finds the rest. */
    private static final int MOST = 300;

    private final String title;
    private final List<Library.Pdf> pdfs;
    private final File folder;
    private List<File> entries;

    private ScrollView scroll;
    private LinearLayout list;
    private LinearLayout field;
    private TextView fieldText;
    private TextView mode;
    private TextView meta;
    private final StringBuilder query = new StringBuilder();
    private final List<Line> lines = new ArrayList<Line>();
    private int sort;

    /** One row and what it shows. */
    private static final class Line {
        final Parts.Row row;
        final File file;
        final Library.Pdf pdf;
        final ImageView thumb;
        boolean asked;

        Line(Parts.Row row, File file, Library.Pdf pdf, ImageView thumb) {
            this.row = row;
            this.file = file;
            this.pdf = pdf;
            this.thumb = thumb;
        }
    }

    /** The PDFs of a location, as scanned. */
    FilesScreen(Library.Location location, List<Library.Pdf> pdfs) {
        this.title = location.name;
        this.pdfs = pdfs == null ? new ArrayList<Library.Pdf>() : new ArrayList<Library.Pdf>(pdfs);
        this.folder = null;
    }

    /** One folder, browsed. */
    FilesScreen(String title, File folder) {
        this.title = title;
        this.pdfs = null;
        this.folder = folder;
    }

    @Override
    View build() {
        Context c = app;
        sort = Store.get(app).sort();
        LinearLayout body = Parts.column(c);

        field = Parts.row(c);
        field.setPadding(Look.dp(c, 8), 0, Look.dp(c, 8), 0);
        field.setDefaultFocusHighlightEnabled(false);
        field.addView(Parts.image(c, R.drawable.ic_search, Look.MUTE, 12));
        fieldText = Look.line(c, "Type to search", 11, Look.REGULAR, Look.MUTE);
        LinearLayout.LayoutParams lp = Parts.weighted();
        lp.leftMargin = Look.dp(c, 6);
        field.addView(fieldText, lp);
        mode = Look.line(c, "T9", 9, Look.BOLD, Look.BLUE);
        mode.setBackground(Look.rounded(Look.ICON_FILL, 3, c));
        mode.setPadding(Look.dp(c, 4), Look.dp(c, 1), Look.dp(c, 4), Look.dp(c, 1));
        field.addView(mode);
        LinearLayout.LayoutParams at = Parts.line(Parts.FILL, Look.dp(c, 26));
        at.setMargins(Look.dp(c, 9), Look.dp(c, 6), Look.dp(c, 9), Look.dp(c, 4));
        body.addView(field, at);
        fieldLook(false);

        meta = Look.line(c, "", 9.5f, Look.REGULAR, Look.MUTE);
        meta.setPadding(Look.dp(c, 11), Look.dp(c, 3), Look.dp(c, 11), Look.dp(c, 4));
        body.addView(meta);

        list = Parts.column(c);
        scroll = new ScrollView(c);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        scroll.addView(list);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> loadVisible());
        body.addView(scroll, new LinearLayout.LayoutParams(Parts.FILL, 0, 1));

        View right = null;
        if (pdfs != null) {
            right = Parts.image(c, R.drawable.ic_sort, Look.BLUE, 13);
        } else {
            entries = Library.folder(folder);
        }
        rows();
        return Parts.page(c, Parts.nav(c, title, true, right), body);
    }

    @Override
    View first() {
        return lines.isEmpty() ? null : lines.get(0).row;
    }

    private void fieldLook(boolean on) {
        field.setBackground(on ? Look.outlined(Look.WHITE, 5, 1.5f, Look.BLUE, app) : Look.rounded(Look.GREY, 5, app));
    }

    /** Rebuilds the list for the order and the search. */
    private void rows() {
        Context c = app;
        list.removeAllViews();
        lines.clear();
        String typed = query.toString();
        int total = 0;
        String shownMatch = null;
        if (pdfs != null) {
            Library.sort(pdfs, sort);
            for (Library.Pdf pdf : pdfs) {
                int[] match = typed.isEmpty() ? null : T9.match(pdf.name, typed);
                if (!typed.isEmpty() && match == null) continue;
                total++;
                if (shownMatch == null && match != null) shownMatch = pdf.name.substring(match[0], match[1]);
                if (lines.size() < MOST) addPdf(c, pdf.file, pdf, match);
            }
        } else {
            for (File entry : entries) {
                int[] match = typed.isEmpty() ? null : T9.match(display(entry), typed);
                if (!typed.isEmpty() && match == null) continue;
                total++;
                if (shownMatch == null && match != null) shownMatch = display(entry).substring(match[0], match[1]);
                if (lines.size() >= MOST) continue;
                if (entry.isDirectory()) addFolder(c, entry, match);
                else addPdf(c, entry, new Library.Pdf(entry), match);
            }
        }
        if (!lines.isEmpty()) lines.get(lines.size() - 1).row.noDivider();

        boolean searching = typed.length() > 0;
        field.setFocusable(searching);
        mode.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (searching) {
            String shown = shownMatch != null ? shownMatch.toLowerCase(Locale.getDefault()) : typed;
            fieldText.setText(shown);
            fieldText.setTextColor(Look.INK);
            meta.setText(total == 0 ? "No PDFs match “" + shown + "”"
                : total + (total == 1 ? " result" : " results") + " for “" + shown + "”");
        } else {
            fieldText.setText("Type to search");
            fieldText.setTextColor(Look.MUTE);
            if (pdfs != null) meta.setText(total + (total == 1 ? " PDF" : " PDFs") + " · " + Library.SORTS[sort]);
            else meta.setText(total == 0 ? "No folders or PDFs here" : folderSummary());
        }
        if (total > lines.size()) {
            list.addView(Parts.foot(c, "Showing " + lines.size() + " of " + total + ". Type to find the others."));
        }
        scroll.post(this::loadVisible);
    }

    private String folderSummary() {
        int folders = 0;
        int files = 0;
        for (File entry : entries) {
            if (entry.isDirectory()) folders++;
            else files++;
        }
        StringBuilder out = new StringBuilder();
        if (folders > 0) out.append(folders).append(folders == 1 ? " folder" : " folders");
        if (files > 0) out.append(out.length() > 0 ? " · " : "").append(files).append(files == 1 ? " PDF" : " PDFs");
        return out.toString();
    }

    private static String display(File entry) {
        return entry.isDirectory() ? entry.getName() : Library.title(entry);
    }

    private CharSequence marked(String name, int[] match) {
        if (match == null) return name;
        SpannableStringBuilder out = new SpannableStringBuilder(name);
        out.setSpan(new BackgroundColorSpan(0xFFFFE58A), match[0], match[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    private void addPdf(Context c, final File file, Library.Pdf pdf, int[] match) {
        Parts.Row row = new Parts.Row(c, 44);
        ImageView thumb = new ImageView(c);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setBackgroundColor(0xFFDCDEE3);
        thumb.setPadding(1, 1, 1, 1);
        row.lead(thumb, Look.dp(c, 24), Look.dp(c, 31));
        row.titled(marked(pdf.name, match), about(pdf));
        row.setOnClickListener(v -> app.open(new PreviewScreen(file, null)));
        list.addView(row, Parts.line(Parts.FILL, Parts.WRAP));
        lines.add(new Line(row, file, pdf, thumb));
    }

    private void addFolder(Context c, final File dir, int[] match) {
        Parts.Row row = new Parts.Row(c, 40);
        row.lead(Parts.square(c, R.drawable.ic_folder), Look.dp(c, 26), Look.dp(c, 26));
        row.titled(marked(dir.getName(), match), "Folder");
        row.chevron();
        row.setOnClickListener(v -> app.open(new FilesScreen(dir.getName(), dir)));
        list.addView(row, Parts.line(Parts.FILL, Parts.WRAP));
        lines.add(new Line(row, dir, null, null));
    }

    /** "6 pages · 412 KB · Today". */
    private static String about(Library.Pdf pdf) {
        int pages = Pages.known(pdf.file);
        String count = pages == Pages.LOCKED ? "Protected" : pages == Pages.BROKEN ? "Can't be read"
            : pages > 0 ? pages + (pages == 1 ? " page" : " pages") : null;
        return (count == null ? "" : count + " · ") + Library.size(pdf.size) + " · " + when(pdf.modified);
    }

    /** "Today", "Yesterday", "Oct 14", or "Oct 14, 2024" for another year. */
    static String when(long time) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        long midnight = Queue.midnight();
        if (time >= midnight) return "Today";
        if (time >= midnight - 24L * 60 * 60 * 1000) return "Yesterday";
        boolean thisYear = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
        return new SimpleDateFormat(thisYear ? "MMM d" : "MMM d, yyyy", Locale.US).format(new Date(time));
    }

    /** Draws the thumbnails of the rows in sight (and a few past them), each once. */
    private void loadVisible() {
        int top = scroll.getScrollY() - Look.dp(app, 100);
        int bottom = scroll.getScrollY() + scroll.getHeight() + Look.dp(app, 200);
        for (final Line line : lines) {
            if (line.asked || line.pdf == null) continue;
            if (line.row.getBottom() < top || (line.row.getTop() > bottom && line.row.getTop() > 0)) continue;
            line.asked = true;
            Pages.draw(line.file, 0, Look.dp(app, 22), Look.dp(app, 29), (Bitmap bitmap) -> {
                if (bitmap != null) line.thumb.setImageBitmap(bitmap);
                else if (Pages.known(line.file) == Pages.LOCKED) line.thumb.setImageDrawable(Look.icon(app, R.drawable.ic_lock, Look.MUTE));
                line.row.sub.setText(about(line.pdf));
            });
        }
    }

    @Override
    boolean key(KeyEvent event) {
        int code = event.getKeyCode();
        int digit = digit(event);
        boolean down = down(event);
        if (digit >= 0) {
            if (down) {
                query.append((char) ('0' + digit));
                rows();
                field.requestFocus();
                fieldLook(true);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_CLEAR) {
            if (down && query.length() > 0) {
                query.setLength(query.length() - 1);
                rows();
                if (query.length() > 0) field.requestFocus();
                else focusFirst();
            }
            return query.length() > 0 || !down;
        }
        if (field.isFocused() && code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down && !lines.isEmpty()) {
                fieldLook(false);
                lines.get(0).row.requestFocus();
            }
            return true;
        }
        if (field.isFocused() && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT
            || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (!down && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && !lines.isEmpty()) {
                lines.get(0).row.performClick();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && down && !lines.isEmpty() && lines.get(0).row.isFocused() && query.length() > 0) {
            field.requestFocus();
            fieldLook(true);
            return true;
        }
        return false;
    }

    private void focusFirst() {
        fieldLook(false);
        if (!lines.isEmpty()) lines.get(0).row.requestFocus();
    }

    /** Back clears a search before it leaves. */
    @Override
    boolean back() {
        if (query.length() == 0) return false;
        query.setLength(0);
        rows();
        focusFirst();
        return true;
    }

    @Override
    void shown() {
        // A file opened and looked at may now have a known page count.
        for (Line line : lines) if (line.pdf != null) line.row.sub.setText(about(line.pdf));
    }

    @Override
    void options(List<Option> out) {
        if (pdfs == null) return;
        out.add(new Option("Sort", () -> {
            List<MainActivity.Choice> choices = new ArrayList<MainActivity.Choice>();
            for (String each : Library.SORTS) choices.add(new MainActivity.Choice(each, null));
            app.choose("Sort", choices, sort, null, which -> {
                sort = which;
                Store.get(app).setSort(sort);
                rows();
                focusFirst();
            });
        }));
    }
}
