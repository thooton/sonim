package com.tablet.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import com.tablet.xp3.data.Job;
import com.tablet.xp3.data.Library;
import com.tablet.xp3.data.Viewport;

import java.util.ArrayList;
import java.util.List;

/**
 * A sheet risen over the screen: for one file, its menu (what OK on a
 * cover opens), then starting a transform, its details, renaming it or
 * moving it; or the settings. The page behind is dimmed; Up and Down pick a choice, OK takes
 * it, Back closes the sheet.
 */
final class Sheet extends FrameLayout {

    static final int ACTIONS = 0;
    static final int TRANSFORM = 1;
    static final int DETAILS = 2;
    static final int RENAME = 3;
    static final int MOVE = 4;
    static final int SETTINGS = 5;
    static final int JOB = 6;

    /** What the choices on a job's sheet do. */
    static final int KEEP = 0;
    static final int CANCEL_JOB = 1;
    static final int VIEW = 2;
    static final int RETRY = 3;
    static final int REMOVE = 4;

    final int kind;
    private final LinearLayout body;
    private final List<Parts.Action> choices = new ArrayList<>();
    /** The name being typed, on the Rename sheet. */
    EditText field;
    /** The top of page 1, on the Transform sheet. */
    private TopOfPage top;
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

    /**
     * Before a transform starts: the top of page 1, where the instruction is
     * written, so it can be checked before anything runs; or, with a job
     * already running on the file, how that one is going.
     */
    static Sheet transform(Context c, Doc doc, Page first, Job running) {
        Sheet s = new Sheet(c, TRANSFORM);
        s.head("Transform", doc.name);
        if (running != null) {
            s.note("Already being transformed: " + running.step() + ", " + running.percent + "%. Follow it in Menu → Transforms.", 8);
            s.choice(R.drawable.ic_check, "OK");
        } else {
            s.top = new TopOfPage(c, first);
            LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Look.dp(c, 54));
            lp.setMargins(Look.dp(c, 9), Look.dp(c, 8), Look.dp(c, 9), Look.dp(c, 4));
            s.body.addView(s.top, lp);
            s.note("An agent reads what you wrote at the top of page 1 and makes what it asks for. The result goes on your tablet beside the original.", 3);
            s.choice(R.drawable.ic_play, "Start transform");
            s.choice(R.drawable.ic_x, "Cancel");
        }
        s.pick(0);
        return s;
    }

    /** Page 1 has arrived, for the Transform sheet. */
    void first(Page page) {
        if (top != null) top.show(page);
    }

    /** The top of a page, full width, behind an ink rule as the proposal sets a quote. */
    private static final class TopOfPage extends View {
        private Page page;
        private final Paint rule = new Paint();

        TopOfPage(Context c, Page page) {
            super(c);
            this.page = page;
            rule.setColor(Look.INK);
        }

        void show(Page next) {
            page = next;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float bar = Look.px(getContext(), 2);
            float w = getWidth() - bar * 3;
            canvas.drawRect(0, 0, bar, getHeight(), rule);
            canvas.save();
            canvas.translate(bar * 3, 0);
            canvas.clipRect(0, 0, w, getHeight());
            canvas.drawColor(Look.PAGE);
            float scale = w / Viewport.PAGE_W;
            canvas.scale(scale, scale);
            page.draw(canvas);
            canvas.restore();
        }
    }

    static Sheet details(Context c, Doc doc, String edited) {
        Sheet s = new Sheet(c, DETAILS);
        s.head("Details", doc.name);
        String[][] rows = {{"Type", doc.kind()}, {"Pages", doc.pages > 0 ? String.valueOf(doc.pages) : "Not known"},
            {"Folder", doc.folder}, {"Size", doc.sizeText()}, {"Edited", edited}};
        LinearLayout table = Parts.column(c);
        for (String[] row : rows) {
            LinearLayout line = Parts.row(c);
            line.setPadding(Look.dp(c, 9), Look.dp(c, 4), Look.dp(c, 9), Look.dp(c, 4));
            TextView key = Look.text(c, row[0], Look.ui, 10.5f, Look.GRAPHITE);
            LinearLayout.LayoutParams grow = Parts.line(0, Parts.WRAP);
            grow.weight = 1;
            line.addView(key, grow);
            TextView value = Look.text(c, row[1], Look.ui, 10.5f, Look.INK);
            value.setSingleLine();
            value.setEllipsize(TextUtils.TruncateAt.START);
            line.addView(value, Parts.line(Parts.WRAP, Parts.WRAP));
            table.addView(line);
            table.addView(Parts.rule(c), Parts.line(Parts.FILL, 1));
        }
        LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Parts.WRAP);
        lp.topMargin = Look.dp(c, 6);
        s.body.addView(table, lp);
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
    static Sheet job(Context c, Job job, String summary) {
        Sheet s = new Sheet(c, JOB);
        s.head(job.stateLabel(), job.heading());
        String from = job.from();
        String detail = job.status == Job.DONE && job.result.length() > 0 ? "Saved as " + job.result + ". " + summary + "."
            : job.status == Job.RUNNING ? job.step() + ", " + job.percent + "%." : summary;
        s.note(from.length() > 0 ? from + ". " + detail : detail, 4);
        switch (job.status) {
            case Job.RUNNING:
                s.jobChoice(R.drawable.ic_check, "Keep it running", KEEP);
                s.jobChoice(R.drawable.ic_x, "Cancel job", CANCEL_JOB);
                break;
            case Job.DONE:
                if (job.tablet.length() > 0) s.jobChoice(R.drawable.ic_eye, "View result", VIEW);
                s.jobChoice(R.drawable.ic_x, "Remove from list", REMOVE);
                break;
            default:
                s.jobChoice(R.drawable.ic_play, "Try again", RETRY);
                s.jobChoice(R.drawable.ic_x, "Remove from list", REMOVE);
        }
        s.pick(0);
        return s;
    }

    private void jobChoice(int icon, String label, int what) {
        choice(icon, label);
        does.add(what);
    }

    /** What the picked choice on a job's sheet does. */
    int doing() {
        return does.get(picked);
    }

    /** The settings: where the tablet is reached, and reading it again. */
    static final int REFRESH = 0;
    static final int SIGN_IN = 1;

    static Sheet settings(Context c, String host) {
        Sheet s = new Sheet(c, SETTINGS);
        s.head("Settings", "Command center");
        s.note(host.length() > 0
            ? "The tablet is read through " + host + ", which it syncs with."
            : "Not signed in. Sign in with the command center's address and the delta token.", 4);
        s.choice(R.drawable.ic_sync, "Refresh now");
        s.choice(R.drawable.ic_tablet, host.length() > 0 ? "Sign in again…" : "Sign in…");
        s.choice(R.drawable.ic_check, "Done");
        s.pick(host.length() > 0 ? REFRESH : SIGN_IN);
        return s;
    }
}
