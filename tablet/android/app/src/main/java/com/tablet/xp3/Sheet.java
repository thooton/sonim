package com.tablet.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tablet.xp3.data.Doc;
import com.tablet.xp3.data.Formats;
import com.tablet.xp3.data.Job;
import com.tablet.xp3.data.Library;

import java.util.ArrayList;
import java.util.List;

/**
 * A sheet risen over the screen: for one file, its menu (what OK on a
 * cover opens), then the formats a transform makes, its details, renaming,
 * moving or deleting it; a job's menu, and its files to download; the
 * folder an upload goes into; or the settings. The page behind is
 * dimmed; Up and Down pick a choice, OK takes it, Back closes the sheet.
 */
final class Sheet extends FrameLayout {

    static final int ACTIONS = 0;
    static final int TRANSFORM = 1;
    static final int DETAILS = 2;
    static final int RENAME = 3;
    static final int MOVE = 4;
    static final int SETTINGS = 5;
    static final int JOB = 6;
    static final int DELETE = 7;
    static final int UPLOAD = 8;
    static final int FORMATS = 9;
    static final int FILES = 10;

    /** What the choices on a job's sheet do. */
    static final int KEEP = 0;
    static final int CANCEL_JOB = 1;
    static final int VIEW = 2;
    static final int RETRY = 3;
    static final int REMOVE = 4;
    static final int DOWNLOAD = 5;
    /** On the Download sheet, every file; the other choices are a format's index in Formats.ALL. */
    static final int ALL_FILES = -1;

    final int kind;
    private final LinearLayout body;
    private final List<Parts.Action> choices = new ArrayList<>();
    /** The name being typed, on the Rename sheet. */
    EditText field;
    /** Which formats are ticked, on the Formats sheet, by their index in Formats.ALL. */
    private boolean[] ticked;
    /** What each choice does, on a job's sheet. */
    private final List<Integer> does = new ArrayList<>();
    int picked;

    private Sheet(Context c, int kind) {
        super(c);
        this.kind = kind;
        View scrim = new View(c);
        scrim.setBackgroundColor(Look.SCRIM);
        addView(scrim, new LayoutParams(Parts.FILL, Parts.FILL));
        body = Parts.column(c);
        body.setBackground(Parts.sheet(c));
        body.setElevation(Look.px(c, 6));
        body.setPadding(Look.dp(c, 8), Look.dp(c, 10), Look.dp(c, 8), Look.dp(c, 8));
        LayoutParams lp = new LayoutParams(Parts.FILL, Parts.WRAP);
        lp.gravity = Gravity.BOTTOM;
        addView(body, lp);
    }

    int count() {
        return choices.size();
    }

    void pick(int index) {
        picked = Math.max(0, Math.min(choices.size() - 1, index));
        for (int i = 0; i < choices.size(); i++) choices.get(i).on(i == picked);
    }

    private void head(String cap, String title) {
        Context c = getContext();
        TextView label = Look.cap(c, cap, Look.GRAPHITE);
        label.setPadding(Look.dp(c, 9), 0, Look.dp(c, 9), 0);
        body.addView(label);
        TextView name = Look.text(c, title, Look.serifBold, 13, Look.INK);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(Look.dp(c, 9), Look.dp(c, 5), Look.dp(c, 9), 0);
        body.addView(name);
    }

    /** Facts as label and value, one to a line, ruled. A null value leaves its line out. */
    private void rows(String[][] rows) {
        Context c = getContext();
        LinearLayout table = Parts.column(c);
        for (String[] row : rows) {
            if (row[1] == null || row[1].length() == 0) continue;
            LinearLayout line = Parts.row(c);
            line.setPadding(Look.dp(c, 9), Look.dp(c, 4), Look.dp(c, 9), Look.dp(c, 4));
            TextView key = Look.text(c, row[0], Look.ui, 10.5f, Look.GRAPHITE);
            line.addView(key, Parts.line(Parts.WRAP, Parts.WRAP));
            TextView value = Look.text(c, row[1], Look.ui, 10.5f, Look.INK);
            value.setSingleLine();
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setGravity(Gravity.END);
            LinearLayout.LayoutParams grow = Parts.line(0, Parts.WRAP);
            grow.weight = 1;
            grow.leftMargin = Look.dp(c, 12);
            line.addView(value, grow);
            table.addView(line);
            table.addView(Parts.rule(c), Parts.line(Parts.FILL, 1));
        }
        LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Parts.WRAP);
        lp.topMargin = Look.dp(c, 6);
        body.addView(table, lp);
    }

    private void note(String text, float top) {
        Context c = getContext();
        TextView p = Look.text(c, text, Look.ui, 9.5f, Look.GRAPHITE);
        p.setLineSpacing(0, 1.3f);
        p.setPadding(Look.dp(c, 9), Look.dp(c, top), Look.dp(c, 9), Look.dp(c, 8));
        body.addView(p);
    }

    private Parts.Action choice(int icon, String label) {
        Context c = getContext();
        Parts.Action action = new Parts.Action(c, icon, label);
        LinearLayout.LayoutParams lp = Parts.actionLine(c);
        if (choices.isEmpty()) lp.topMargin = Look.dp(c, 4);
        body.addView(action, lp);
        choices.add(action);
        return action;
    }

    /** What can be done with a file, Open first so OK, OK opens it. */
    static Sheet actions(Context c, Doc doc) {
        Sheet s = new Sheet(c, ACTIONS);
        s.head(doc.kind(), doc.name);
        for (int i = 0; i < LibraryScreen.LABELS.length; i++) {
            s.choice(LibraryScreen.ICONS[i], LibraryScreen.LABELS[i]);
        }
        s.pick(LibraryScreen.OPEN);
        return s;
    }

    /** A transform already running on the file: how it is going. */
    static Sheet transform(Context c, Doc doc, Job running) {
        Sheet s = new Sheet(c, TRANSFORM);
        s.head("Transform", doc.name);
        s.rows(new String[][] {{"Status", running.step()}, {"Progress", running.percent + "%"}});
        s.choice(R.drawable.ic_check, "OK");
        s.pick(0);
        return s;
    }

    /**
     * What a transform makes, as boxes OK ticks: the PDF always, the rest
     * unticked every time. Start is under them, and picked, so OK, OK makes
     * a PDF.
     */
    static Sheet formats(Context c, Doc doc) {
        Sheet s = new Sheet(c, FORMATS);
        s.head("Transform", doc.name);
        s.ticked = new boolean[Formats.ALL.length];
        s.ticked[0] = true;
        for (int i = 0; i < Formats.ALL.length; i++) {
            Parts.Action a = s.choice(i == 0 ? R.drawable.ic_square_check : R.drawable.ic_square, Formats.LABELS[i]);
            a.note.setText(i == 0 ? "Always" : "." + Formats.ALL[i]);
        }
        s.choice(R.drawable.ic_play, "Start");
        s.pick(Formats.ALL.length);
        return s;
    }

    /** On the Formats sheet: whether Start is picked. */
    boolean onStart() {
        return picked == Formats.ALL.length;
    }

    /** On the Formats sheet: ticks or unticks the picked format; the PDF stays ticked. */
    void toggle() {
        if (picked <= 0 || picked >= ticked.length) return;
        ticked[picked] = !ticked[picked];
        choices.get(picked).icon(ticked[picked] ? R.drawable.ic_square_check : R.drawable.ic_square);
    }

    /** On the Formats sheet: the formats ticked. */
    List<String> chosen() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ticked.length; i++) if (ticked[i]) out.add(Formats.ALL[i]);
        return out;
    }

    static Sheet details(Context c, Doc doc, String edited) {
        Sheet s = new Sheet(c, DETAILS);
        s.head("Details", doc.name);
        s.rows(new String[][] {{"Type", doc.kind()}, {"Pages", doc.pages > 0 ? String.valueOf(doc.pages) : "—"},
            {"Folder", doc.folder}, {"Size", doc.sizeText()}, {"Edited", edited}});
        s.choice(R.drawable.ic_check, "Done");
        s.pick(0);
        return s;
    }

    /** The file's name in a field the keypad types into, with Save and Cancel under it. */
    static Sheet rename(Context c, Doc doc) {
        Sheet s = new Sheet(c, RENAME);
        s.head("Rename", doc.name);
        EditText field = new EditText(c);
        Look.style(field, Look.ui, 12, Look.INK);
        field.setText(doc.name);
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        field.setPadding(Look.dp(c, 7), Look.dp(c, 5), Look.dp(c, 7), Look.dp(c, 5));
        GradientDrawable box = Look.round(0xFFFFFFFF, 4, c);
        box.setStroke(Look.dp(c, 1.5f), Look.INK);
        field.setBackground(box);
        field.setHighlightColor(Look.alpha(Look.MARK, 0.7f));
        LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Parts.WRAP);
        lp.setMargins(Look.dp(c, 9), Look.dp(c, 9), Look.dp(c, 9), Look.dp(c, 6));
        s.body.addView(field, lp);
        s.field = field;
        s.choice(R.drawable.ic_check, "Save");
        s.choice(R.drawable.ic_x, "Cancel");
        s.pick(0);
        return s;
    }

    /** The tablet's folders, the file's own marked as current. */
    static Sheet move(Context c, Doc doc, List<Library.Folder> folders) {
        Sheet s = new Sheet(c, MOVE);
        s.head("Move to folder", doc.name);
        int current = 0;
        for (int i = 0; i < folders.size(); i++) {
            Parts.Action a = s.choice(R.drawable.ic_folder, folders.get(i).path);
            // The end of a long path is the folder itself.
            a.label.setEllipsize(TextUtils.TruncateAt.START);
            if (folders.get(i).id.equals(doc.parent)) {
                a.note.setText("Current");
                current = i;
            }
        }
        s.pick(current);
        return s;
    }

    /**
     * What can be done with a job, under how it stands. A running job's
     * sheet opens on keeping it, so a second OK never cancels it by accident.
     */
    static Sheet job(Context c, Job job, String finished) {
        Sheet s = new Sheet(c, JOB);
        s.head(job.stateLabel(), job.heading());
        switch (job.status) {
            case Job.RUNNING:
                s.rows(new String[][] {{"From", job.from()}, {"Status", job.step()}, {"Progress", job.percent + "%"}});
                s.jobChoice(R.drawable.ic_check, "Keep running", KEEP);
                s.jobChoice(R.drawable.ic_x, "Cancel job", CANCEL_JOB);
                break;
            case Job.DONE:
                s.rows(new String[][] {{"From", job.from()}, {"Saved as", job.result}, {"Finished", finished}});
                if (job.tablet.length() > 0) s.jobChoice(R.drawable.ic_eye, "View", VIEW);
                if (!job.files.isEmpty()) s.jobChoice(R.drawable.ic_download, "Download…", DOWNLOAD);
                s.jobChoice(R.drawable.ic_x, "Remove", REMOVE);
                break;
            default:
                s.rows(new String[][] {{"From", job.from()}, {"Failed", finished}});
                s.note(job.failure(), 6);
                s.jobChoice(R.drawable.ic_play, "Try again", RETRY);
                s.jobChoice(R.drawable.ic_x, "Remove", REMOVE);
        }
        s.pick(0);
        return s;
    }

    private Parts.Action jobChoice(int icon, String label, int what) {
        Parts.Action a = choice(icon, label);
        does.add(what);
        return a;
    }

    /** What the picked choice on a job's sheet does. */
    int doing() {
        return does.get(picked);
    }

    /** A finished job's files: all of them, when it made more than one format, then each format. */
    static Sheet files(Context c, Job job) {
        Sheet s = new Sheet(c, FILES);
        s.head("Download", job.heading());
        List<String> made = job.made();
        if (made.size() > 1) s.jobChoice(R.drawable.ic_download, "All", ALL_FILES).note.setText(job.files.size() + " files");
        for (String format : made) {
            int n = job.files(format).size();
            s.jobChoice(R.drawable.ic_download, Formats.LABELS[Formats.index(format)], Formats.index(format))
                .note.setText(n > 1 ? n + " ." + format : "." + format);
        }
        s.pick(0);
        return s;
    }

    /** Which of the tablet's folders a file from the phone goes into; the top first. */
    static Sheet uploadTo(Context c, String name, List<Library.Folder> folders) {
        Sheet s = new Sheet(c, UPLOAD);
        s.head("Upload to", name);
        for (Library.Folder folder : folders) {
            Parts.Action a = s.choice(R.drawable.ic_folder, folder.path);
            a.label.setEllipsize(TextUtils.TruncateAt.START);
        }
        s.pick(0);
        return s;
    }

    /** Before a file goes to the tablet's trash. Opens on Cancel. */
    static Sheet delete(Context c, Doc doc) {
        Sheet s = new Sheet(c, DELETE);
        s.head("Delete", doc.name);
        s.choice(R.drawable.ic_x, "Cancel");
        s.choice(R.drawable.ic_trash, "Delete");
        s.pick(0);
        return s;
    }

    /** The settings: where the tablet is reached, and reading it again. */
    static final int REFRESH = 0;
    static final int SIGN_IN = 1;

    static Sheet settings(Context c, String host) {
        Sheet s = new Sheet(c, SETTINGS);
        s.head("Settings", "Command center");
        s.rows(new String[][] {{"Server", host.length() > 0 ? host : "Not signed in"}});
        s.choice(R.drawable.ic_sync, "Refresh");
        s.choice(R.drawable.ic_tablet, host.length() > 0 ? "Change server…" : "Sign in…");
        s.choice(R.drawable.ic_check, "Done");
        s.pick(host.length() > 0 ? REFRESH : SIGN_IN);
        return s;
    }
}
