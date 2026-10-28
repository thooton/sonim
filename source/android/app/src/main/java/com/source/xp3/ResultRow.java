package com.source.xp3;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.source.xp3.data.History;
import com.source.xp3.net.Books;
import com.source.xp3.net.Music;
import com.xp3.common.ui.Ui;
import java.text.DateFormat;
import java.util.Date;

/**
 * A row of results or downloads, at the proposal's sizes: the drawn cover,
 * the lines that tell one result from another, and the state button (on a
 * result, only once it is being downloaded: it is got from its page). A music
 * result shows its name, the folders it sits in (from the deepest up, which
 * say the most), then a format tag with its bitrate and size and who shares
 * it (its length, and how fast it comes, are on its page). A book shows its title, then a format
 * tag with its author and size. The row under the d-pad is a filled block of
 * primaryContainer.
 */
final class ResultRow extends LinearLayout {

    private final Cover cover;
    private final TextView title;
    private final TextView folders;
    private final TextView tag;
    private final TextView meta;
    private final TextView extra;
    private final StateButton button;

    ResultRow(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.row_pick);
        setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6));
        setMinimumHeight(Ui.dp(context, 44));

        cover = new Cover(context);
        addView(cover, new LayoutParams(Ui.dp(context, 32), Ui.dp(context, 34)));

        LinearLayout lines = new LinearLayout(context);
        lines.setOrientation(VERTICAL);
        LayoutParams linesSize = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        linesSize.leftMargin = Ui.dp(context, 9);
        linesSize.rightMargin = Ui.dp(context, 9);
        addView(lines, linesSize);

        title = line(context, R.dimen.text_row, R.color.text);
        // So a screen that paints the d-pad's row itself can recolour it.
        title.setId(android.R.id.text1);
        title.setTypeface(Tonal.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, 0, 0, 0);
        lines.addView(title);

        folders = line(context, R.dimen.text_meta, R.color.text_muted);
        folders.setSingleLine(true);
        folders.setCompoundDrawablePadding(Ui.dp(context, 3));
        lines.addView(folders);

        LinearLayout tagLine = new LinearLayout(context);
        tagLine.setOrientation(HORIZONTAL);
        tagLine.setGravity(Gravity.CENTER_VERTICAL);
        tag = new TextView(context);
        tag.setBackgroundResource(R.drawable.tag);
        tag.setTextColor(context.getColor(R.color.on_tag));
        tag.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.text_tag));
        tag.setTypeface(Tonal.BOLD);
        tag.setIncludeFontPadding(false);
        tag.setSingleLine(true);
        tag.setLetterSpacing(0.04f);
        tag.setPadding(Ui.dp(context, 4), Ui.dp(context, 1), Ui.dp(context, 4), Ui.dp(context, 1));
        LayoutParams tagSize = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tagSize.rightMargin = Ui.dp(context, 3);
        tagLine.addView(tag, tagSize);
        meta = line(context, R.dimen.text_meta, R.color.text_muted);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, 0, 0, 0);
        tagLine.addView(meta);
        tagLine.setPadding(0, Ui.dp(context, 2), 0, 0);
        lines.addView(tagLine);

        extra = line(context, R.dimen.text_meta, R.color.text_muted);
        extra.setSingleLine(true);
        extra.setEllipsize(TextUtils.TruncateAt.END);
        lines.addView(extra);

        // Kept for a download's progress, and gone on a result's row.
        extra.setVisibility(GONE);

        button = new StateButton(context);
        addView(button, new LayoutParams(Ui.dp(context, 26), Ui.dp(context, 26)));
        lines.setGravity(Gravity.CENTER_VERTICAL);
    }

    private TextView line(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextColor(context.getColor(color));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(size));
        view.setIncludeFontPadding(false);
        view.setPadding(0, Ui.dp(context, 2), 0, 0);
        return view;
    }

    private void show(TextView view, CharSequence text) {
        view.setText(text);
        view.setVisibility(text == null || text.length() == 0 ? GONE : VISIBLE);
    }

    private void picked(boolean picked) {
        setActivated(picked);
        title.setTextColor(getContext().getColor(picked ? R.color.on_accent_container : R.color.text));
    }

    /**
     * Whether the second line holds folders: then it is cut from the start,
     * so the folders nearest the file stay in view; a book's author keeps
     * its start.
     */
    private void folderIcon(boolean on) {
        folders.setEllipsize(on ? TextUtils.TruncateAt.START : TextUtils.TruncateAt.END);
        if (!on) {
            folders.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        android.graphics.drawable.Drawable icon = getContext().getDrawable(R.drawable.ic_folder).mutate();
        icon.setTint(getContext().getColor(R.color.text_muted));
        int size = Ui.dp(getContext(), 10);
        icon.setBounds(0, 0, size, size);
        folders.setCompoundDrawablesRelative(icon, null, null, null);
    }

    void hit(Music.Hit hit, int state, float progress, boolean picked) {
        cover.set(hit.album() ? Music.ALBUM : Music.SONG, hit.name(), state == Download.FAILED, Download.IDLE);
        title.setText(hit.name().length() > 0 ? hit.name() : hit.title());
        show(folders, hit.folders());
        folderIcon(true);
        show(tag, hit.formats());
        meta.setText(join(hit.brief(), hit.free ? hit.user : hit.user + " (queued)"));
        ((View) meta.getParent()).setVisibility(VISIBLE);
        show(extra, null);
        state(state, progress, picked);
        picked(picked);
    }

    void book(Books.Book book, int state, float progress, boolean picked) {
        String name = book.title.length() > 0 ? book.title : "Untitled";
        cover.set(Download.BOOK, name, state == Download.FAILED, Download.IDLE);
        title.setText(name);
        show(folders, null);
        show(tag, book.extension.toUpperCase(java.util.Locale.US));
        meta.setText(join(book.author, book.size));
        ((View) meta.getParent()).setVisibility(VISIBLE);
        show(extra, null);
        state(state, progress, picked);
        picked(picked);
    }

    /** A result's state, or nothing while it hasn't been asked for. */
    private void state(int state, float progress, boolean picked) {
        button.set(state, progress, picked);
        button.setVisibility(state == Download.IDLE ? GONE : VISIBLE);
    }

    /** A download of this session, running, queued or failed. */
    void job(Download.Job job, boolean picked) {
        cover.set(job.kind, job.title, job.state == Download.FAILED, Download.IDLE);
        title.setText(job.title);
        show(folders, job.music() ? job.folders : null);
        folderIcon(job.music());
        ((View) meta.getParent()).setVisibility(GONE);
        show(extra, progressLine(job));
        button.set(job.state, job.progress(), picked);
        button.setVisibility(VISIBLE);
        picked(picked);
    }

    /** A saved download, from the history: ticked while its first file is still there. */
    void entry(History.Entry entry, boolean picked) {
        boolean there = Download.saved(entry.key) != null;
        cover.set(entry.kind, entry.title, false, Download.IDLE);
        title.setText(entry.title);
        boolean music = !Download.BOOK.equals(entry.kind);
        show(folders, entry.folders);
        folderIcon(music);
        ((View) meta.getParent()).setVisibility(GONE);
        String when = entry.time > 0 ? DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(entry.time)) : "";
        show(extra, there ? join(entry.bytes > 0 ? Download.human(entry.bytes) : "", when) : join("No longer on the phone", when));
        button.set(Download.SAVED, -1, picked);
        button.setVisibility(there ? VISIBLE : GONE);
        picked(picked);
    }

    /** "Downloading from bob", "3.5 of 9.1 MB", "Queued", or why it failed. */
    static String progressLine(Download.Job job) {
        switch (job.state) {
            case Download.QUEUED:
                return "Queued";
            case Download.FAILED:
                return job.error == null ? "Failed" : job.error;
            case Download.SAVED:
                return job.note;
            default:
                if (job.total > 0) return Download.human(job.done) + " of " + Download.human(job.total);
                return job.phase;
        }
    }

    static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.length() == 0) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(part);
        }
        return out.toString();
    }
}
