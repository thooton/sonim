package com.canvas.xp3;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Detail;
import com.canvas.xp3.data.Draft;

import java.util.ArrayList;
import java.util.List;

/**
 * The assignment page (C1, C2, D8, E1, F2, F4, G1): one page scrolled with
 * the d-pad, top to bottom.
 *
 * Title; the due date as a small tear-off calendar page and a sentence, never
 * a countdown; status and points as quiet pills; the instructions; the
 * teacher's files; the student's own work; private comments. Returned work
 * leads with its grade instead, set large and plain, with the teacher's
 * comment right under it.
 *
 * Every path through the app ends here, and leaves through turning in.
 */
final class AssignmentScreen extends Screen {

    final Assignment item;
    private final String back;
    private Detail detail;
    private boolean loading;
    private String problem;
    private Draft draft;
    private EditText answer;

    AssignmentScreen(MainActivity app, Assignment item, String back) {
        super(app);
        this.item = item;
        this.back = back;
        detail = app.backend.cachedDetail(item);
        draft = app.backend.draft(item.id);
        fetch();
    }

    private void fetch() {
        if (!app.backend.canvasReady()) return;
        loading = true;
        app.background(() -> app.backend.detail(current()), (fresh, failure) -> {
            loading = false;
            if (fresh != null) detail = fresh;
            else problem = failure;
            refresh();
        });
    }

    /** The newest record for this work: a sync may have replaced it since the page opened. */
    private Assignment current() {
        Assignment fresh = app.todos.byId(item.id);
        return fresh == null ? item : fresh;
    }

    @Override
    String right() {
        Assignment work = current();
        if (canAdd(work)) return "Add work";
        return "Comment";
    }

    @Override
    void onRight() {
        if (canAdd(current())) addWork();
        else comments();
    }

    private boolean canAdd(Assignment work) {
        boolean waiting = draft.queued || app.sending(work);
        return work.takesWork() && !work.locked && !waiting && (!work.done || work.attemptsLeft())
            && !work.accepts(Assignment.DISCUSSION);
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        View focus = app.getCurrentFocus();
        final String key = focus instanceof Item ? ((Item) focus).key : null;
        if (key != null && key.startsWith("piece:") && !draft.queued) {
            final Draft.Piece piece = draft.piece(key.substring(6));
            if (piece != null) {
                out.add(new Sheet.Choice("Rename", R.drawable.ic_edit, null, () -> rename(piece)));
                out.add(new Sheet.Choice("Remove", R.drawable.ic_undo, null, () -> remove(piece)));
            }
        }
        if (canAdd(current())) out.add(new Sheet.Choice("Add work", R.drawable.ic_add, null, this::addWork));
        out.add(new Sheet.Choice("Private comments", R.drawable.ic_comment, null, this::comments));
        final Course course = app.course(item.courseId);
        if (course != null) {
            final boolean quiet = app.prefs.quiet.contains(course.id);
            out.add(new Sheet.Choice(quiet ? "Remind me about " + course.code : "No reminders for " + course.code,
                R.drawable.ic_bell, null, () -> app.savePrefs(app.prefs.withQuiet(course.id, !quiet))));
        }
        return out;
    }

    // ------------------------------------------------------------------

    @Override
    View build() {
        Parts parts = new Parts(app);
        // Nothing fetched yet: the page waits whole rather than drawing its
        // top half and filling in the instructions afterwards.
        if (detail == null && loading) return parts.spinner();
        Assignment work = current();
        draft = app.backend.draft(work.id);
        Course course = app.course(work.courseId);
        long now = System.currentTimeMillis();
        int state = app.todos.state(work, now);

        LinearLayout root = v.column();
        root.addView(parts.nav(back, course), Views.wide());
        boolean waiting = draft.queued && !app.onlineNow();
        if (draft.queued) {
            root.addView(parts.offline(app.onlineNow() ? "Turning in." : "You're offline.",
                "It will turn in when you reconnect."), Views.wide());
        }
        LinearLayout page = parts.page(this, root);

        // The title takes the d-pad first, so Down reads down the page before
        // it lands on anything: the instructions come before the buttons.
        MarkText title = v.pageTitle(work.title);
        Item top = new Item(app, "title", null, null);
        top.addView(title, Views.wide());
        top.marks(title, look);
        v.pad(top, 12, 0, 12, 0);
        page.addView(top, Views.wide());

        Long stamped = app.turnedIn.get(work.id);
        boolean returned = work.returned || work.excused;
        if (returned) {
            page.addView(grade(work), Views.wide());
            Detail.Comment said = teacherSaid();
            if (said != null) {
                page.addView(parts.section("From your teacher", null), Views.wide());
                page.addView(bubble(said), v.margins(Views.wide(), 10, 0, 40, 0));
            }
        } else {
            page.addView(due(work), Views.wide());
            if (work.done && (work.turnedIn != null || stamped != null)) {
                page.addView(stamp(work, stamped), Views.wide());
            } else if (stamped != null) {
                page.addView(stamp(work, stamped), Views.wide());
            }
            LinearLayout pills = v.row();
            v.pad(pills, 12, 8, 12, 0);
            pills.addView(v.pill(status(work, state), true));
            if (work.points > 0) {
                pills.addView(v.pill(Assignment.number(work.points) + " points", false),
                    v.margins(Views.wrap(), 5, 0, 0, 0));
            }
            if (work.attemptsAllowed > 0) {
                pills.addView(v.pill(work.attempts + " of " + work.attemptsAllowed + " tries", false),
                    v.margins(Views.wrap(), 5, 0, 0, 0));
            }
            page.addView(pills, Views.wide());
        }

        // Instructions
        if (detail != null && detail.body.trim().length() > 0 && !returned) {
            page.addView(parts.section("Instructions", null), Views.wide());
            LinearLayout body = v.column();
            parts.markdown(body, detail.body);
            page.addView(body, Views.wide());
        } else if (detail == null) {
            page.addView(parts.section("Instructions", null), Views.wide());
            TextView wait = v.bodyText(problem != null ? "The instructions couldn't be loaded. " + problem
                : "The instructions haven't downloaded yet.");
            wait.setTextColor(look.muted);
            v.pad(wait, 12, 0, 12, 0);
            page.addView(wait, Views.wide());
        }

        // The teacher's files
        if (detail != null && !detail.files.isEmpty() && !returned) {
            page.addView(parts.section("From your teacher", null), Views.wide());
            LinearLayout stack = stack();
            for (final Detail.FileRef file : detail.files) {
                stack.addView(parts.box("file:" + file.id, "Open", parts.fileTile(file.kind()), file.name,
                    parts.fileMeta(file), () -> app.push(new ViewerScreen(app, file, work.title))), boxGap());
            }
            page.addView(stack, Views.wide());
        }

        // Your work
        yourWork(parts, page, work, state, waiting);

        // Rubric
        if (detail != null && !detail.rubric.isEmpty()) {
            page.addView(parts.section("Rubric", null), Views.wide());
            for (Detail.Criterion c : detail.rubric) page.addView(criterion(c), Views.wide());
        }

        // Private comments
        page.addView(parts.section("Private comments", detail != null && !detail.comments.isEmpty()
            ? detail.comments.size() + "" : null), Views.wide());
        LinearLayout stack = stack();
        Item comment = parts.box("comments", "Comment", v.tile(R.drawable.ic_lock, look.paper, look.ink, 24, 13, 6),
            detail != null && !detail.comments.isEmpty() ? "Private comments" : "Add a private comment",
            null, this::comments);
        stack.addView(comment, boxGap());
        page.addView(stack, Views.wide());
        return root;
    }

    private String status(Assignment work, int state) {
        if (work.excused) return "Excused";
        if (app.todos.checked(work) && !work.done) return "Marked done";
        if (work.done) return work.returned ? "Returned" : "Turned in";
        if (state == Assignment.MISSING) return "Missing";
        return "Assigned";
    }

    private LinearLayout stack() {
        LinearLayout stack = v.column();
        v.pad(stack, 10, 0, 10, 0);
        return stack;
    }

    private LinearLayout.LayoutParams boxGap() {
        return v.margins(Views.wide(), 0, 0, 0, 5);
    }

    /** The due date as a tear-off calendar page and a sentence. */
    private View due(Assignment work) {
        LinearLayout row = v.row();
        v.pad(row, 12, 9, 12, 0);
        if (work.due == null) {
            TextView none = v.text("No due date", 13, look.bold, look.ink);
            row.addView(none);
            return row;
        }
        long when = work.due.longValue();
        LinearLayout cal = v.column();
        cal.setBackground(v.round(look.bg, 7, look.ink, 1.5f));
        cal.setClipToOutline(true);
        TextView tab = v.text(Dates.weekdayTab(when), 7.5f, look.heavy, look.bg);
        tab.setGravity(Gravity.CENTER);
        tab.setLetterSpacing(0.1f);
        tab.setBackground(topRounded(look.ink, 6));
        v.pad(tab, 0, 2, 0, 2);
        cal.addView(tab, Views.wide());
        TextView day = v.text(Dates.dayOfMonth(when), 16, look.heavy, look.ink);
        day.setGravity(Gravity.CENTER);
        Views.tabular(day);
        v.pad(day, 0, 1, 0, 3);
        cal.addView(day, Views.wide());
        row.addView(cal, new LinearLayout.LayoutParams(v.dp(38), -2));
        LinearLayout words = v.column();
        TextView label = v.label("Due");
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8 * look.scale);
        words.addView(label);
        TextView date = v.text(Dates.longDay(when), 13, look.bold, look.ink);
        words.addView(date, v.margins(Views.wrap(), 0, 2, 0, 0));
        TextView time = v.text("at " + Dates.time(when), 10, look.regular, look.muted);
        words.addView(time, v.margins(Views.wrap(), 0, 1, 0, 0));
        row.addView(words, v.margins(Views.fill(), 10, 0, 0, 0));
        return row;
    }

    private android.graphics.drawable.GradientDrawable topRounded(int color, float radius) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        float r = v.dp(radius);
        shape.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        return shape;
    }

    /** The green stamp: turned in, when, and how much. */
    private View stamp(Assignment work, Long stamped) {
        LinearLayout box = v.row();
        box.setBackground(v.round(look.okBg, 12, 0, 0));
        v.pad(box, 10, 8, 10, 8);
        FrameLayout check = v.tile(R.drawable.ic_check, look.okText, look.okBg, 24, 14, 12);
        box.addView(check);
        LinearLayout words = v.column();
        words.addView(v.text("Turned in", 13, look.heavy, look.okText));
        long at = stamped != null ? stamped.longValue() : work.turnedIn.longValue();
        int count = submittedCount();
        String line = Dates.shortDay(at) + " at " + Dates.time(at) + (count > 0 ? " · " + count
            + (count == 1 ? " item" : " items") : "");
        words.addView(v.text(line, 9, look.semibold, look.okText), v.margins(Views.wrap(), 0, 2, 0, 0));
        box.addView(words, v.margins(Views.fill(), 9, 0, 0, 0));
        LinearLayout holder = v.column();
        holder.addView(box, v.margins(Views.wide(), 10, 9, 10, 0));
        if (work.due != null) {
            TextView due = v.text("Due " + Dates.longDay(work.due.longValue()) + " at " + Dates.time(work.due.longValue()),
                9, look.medium, look.muted);
            v.pad(due, 12, 6, 12, 0);
            holder.addView(due, Views.wide());
        }
        return holder;
    }

    private int submittedCount() {
        if (detail == null || detail.submittedType == null) return 0;
        int count = detail.submittedFiles.size();
        if (detail.submittedText != null) count++;
        if (detail.submittedUrl != null) count++;
        return count;
    }

    /** Returned work: the grade, large and in ink whatever it is. */
    private View grade(Assignment work) {
        LinearLayout row = v.row();
        row.setGravity(Gravity.BOTTOM);
        row.setBaselineAligned(false);
        v.pad(row, 12, 10, 12, 0);
        if (work.excused) {
            row.addView(v.text("Excused", 26, look.heavy, look.ink));
        } else if (work.score != null) {
            String chip = work.gradeChip();
            boolean points = chip != null && chip.contains("/");
            TextView big = v.text(points ? Assignment.number(work.score.doubleValue()) : String.valueOf(chip),
                32, look.heavy, look.ink);
            big.setLetterSpacing(-0.03f);
            Views.tabular(big);
            row.addView(big);
            if (points) row.addView(v.text("/" + Assignment.number(work.points), 14, look.bold, look.muted),
                v.margins(Views.wrap(), 2, 0, 0, 2));
        }
        LinearLayout side = v.column();
        side.setGravity(Gravity.RIGHT);
        TextView pill = v.greenPill("Returned");
        pill.setSingleLine(true);
        side.addView(pill, Views.wrap());
        Long at = work.gradedAt;
        if (at != null) side.addView(v.text(Dates.shortDay(at.longValue()), 9, look.regular, look.muted),
            v.margins(Views.wrap(), 0, 3, 0, 0));
        View gap = new View(app);
        row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(side, Views.wrap());
        return row;
    }

    private Detail.Comment teacherSaid() {
        if (detail == null) return null;
        for (int i = detail.comments.size() - 1; i >= 0; i--) {
            if (!detail.comments.get(i).mine) return detail.comments.get(i);
        }
        return null;
    }

    private View bubble(Detail.Comment said) {
        LinearLayout box = v.column();
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(look.paper);
        float r = v.dp(12);
        float small = v.dp(3);
        shape.setCornerRadii(new float[] { r, r, r, r, r, r, small, small });
        box.setBackground(shape);
        v.pad(box, 8, 5, 8, 5);
        TextView who = v.text(Course.shortName(said.author) + " · private", 8, look.bold, look.muted);
        box.addView(who);
        TextView text = v.text(said.text, 10, look.regular, look.ink);
        text.setLineSpacing(0, 1.25f);
        box.addView(text, v.margins(Views.wrap(), 0, 1, 0, 0));
        return box;
    }

    private View criterion(Detail.Criterion c) {
        LinearLayout row = v.row();
        v.pad(row, 12, 5, 12, 5);
        LinearLayout words = v.column();
        TextView name = v.text(c.name, 10.5f, look.semibold, look.ink);
        words.addView(name);
        if (c.level != null) words.addView(v.meta(c.level), v.margins(Views.wrap(), 0, 2, 0, 0));
        row.addView(words, Views.fill());
        String points = (c.earned == null ? "–" : Assignment.number(c.earned.doubleValue())) + "/"
            + Assignment.number(c.points);
        TextView right = v.text(points, 10, look.bold, look.muted);
        Views.tabular(right);
        row.addView(right, v.margins(Views.wrap(), 8, 0, 0, 0));
        return row;
    }

    // ------------------------------------------------------------------
    // Your work
    // ------------------------------------------------------------------

    private void yourWork(Parts parts, LinearLayout page, final Assignment work, int state, boolean waiting) {
        boolean submitted = detail != null && detail.submittedType != null;
        boolean sending = app.sending(work);
        String note;
        if (draft.queued) note = "Waiting to turn in";
        else if (sending) note = "Turning in";
        else if (!draft.isEmpty()) note = draft.pieces.size() + (draft.pieces.size() == 1 ? " item" : " items");
        else if (submitted) note = submittedCount() == 1 ? "1 item" : submittedCount() + " items";
        else note = status(work, state);
        page.addView(parts.section("Your work", note), Views.wide());

        if (work.accepts(Assignment.DISCUSSION) && work.topicId != null) {
            discussion(page, work);
            return;
        }
        if (work.accepts(Assignment.QUIZ) && work.quizId != null) {
            LinearLayout stack = stack();
            if (work.done) {
                TextView done = v.bodyText("You've taken this quiz.");
                stack.addView(done, boxGap());
            }
            if (!work.done || work.attemptsLeft()) {
                stack.addView(Sheet.button(app, v, "quiz", work.done ? "Take it again" : "Start quiz", true,
                    () -> app.push(new QuizScreen(app, work))), new LinearLayout.LayoutParams(-1, v.dp(28)));
            }
            page.addView(stack, Views.wide());
            return;
        }

        LinearLayout stack = stack();
        // What was turned in, while nothing new is being added.
        if (submitted && draft.isEmpty()) {
            if (detail.submittedText != null) {
                stack.addView(submittedRow("sub:text", "text", "Typed answer",
                    words(detail.submittedText) + " words", null), Views.wide());
            }
            if (detail.submittedUrl != null) {
                stack.addView(submittedRow("sub:url", "link", detail.submittedUrl, "Link", null), Views.wide());
            }
            for (final Detail.FileRef file : detail.submittedFiles) {
                stack.addView(submittedRow("sub:" + file.id, file.kind(), file.name, parts.fileMeta(file),
                    () -> app.push(new ViewerScreen(app, file, work.title))), Views.wide());
            }
        }

        for (final Draft.Piece piece : draft.pieces) {
            stack.addView(piece(parts, piece, waiting), boxGap());
        }

        boolean can = canAdd(work);
        if (can) {
            if (draft.isEmpty() && !submitted) {
                Item add = addBox();
                stack.addView(add, boxGap());
            } else {
                Item more = parts.tiled("add", "Add", R.drawable.ic_add, look.paper, look.ink,
                    submitted && draft.isEmpty() ? "Turn in again" : "Add more work",
                    null, null,
                    this::addWork);
                stack.addView(more, Views.wide());
            }
        }

        if (draft.queued) {
            stack.addView(Sheet.button(app, v, "cancel", "Cancel turn-in", false, () -> app.cancelQueued(draft)),
                v.margins(new LinearLayout.LayoutParams(-1, v.dp(28)), 0, 4, 0, 0));
        } else if (!draft.isEmpty()) {
            Item button = Sheet.button(app, v, "turnin", sending ? "Turning in…" : "Turn in", true,
                () -> confirmTurnIn(work));
            stack.addView(button, v.margins(new LinearLayout.LayoutParams(-1, v.dp(28)), 0, 4, 0, 0));
        } else if (work.nothingToSend() && !work.done) {
            final boolean checked = app.todos.checked(work);
            stack.addView(Sheet.button(app, v, "markdone", checked ? "Mark as not done" : "Mark as done", false,
                () -> markDone(work, !checked)), v.margins(new LinearLayout.LayoutParams(-1, v.dp(28)), 0, 4, 0, 0));
        } else if (!work.takesWork() && !work.done) {
            TextView note2 = v.bodyText(work.locked ? "This assignment is locked."
                : "This one is turned in on a computer.");
            note2.setTextColor(look.muted);
            stack.addView(note2, Views.wide());
        }
        page.addView(stack, Views.wide());
    }

    private static int words(String text) {
        String trimmed = text.trim();
        return trimmed.length() == 0 ? 0 : trimmed.split("\\s+").length;
    }

    /** A plain row for something already turned in (F2). */
    private View submittedRow(String key, String kind, String title, String meta, Runnable open) {
        Parts parts = new Parts(app);
        Item row = new Item(app, key, open == null ? null : "Open", open);
        LinearLayout line = v.row();
        v.pad(line, 2, 4, 2, 4);
        line.addView(parts.fileTile(kind));
        LinearLayout words = v.column();
        MarkText name = v.mark(title, 11, look.semibold, look.ink);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(name, Views.wide());
        words.addView(v.meta(meta), v.margins(Views.wide(), 0, 1, 0, 0));
        line.addView(words, v.margins(Views.fill(), 9, 0, 0, 0));
        row.addView(line, Views.wide());
        row.marks(name, look);
        if (open == null) row.setFocusable(false);
        return row;
    }

    /** The dashed "Add work" box, solid ink under the d-pad. */
    private Item addBox() {
        Item add = new Item(app, "add", "Add", this::addWork);
        LinearLayout line = v.row();
        v.pad(line, 7, 7, 7, 7);
        line.addView(v.tile(R.drawable.ic_add, look.paper, look.ink, 24, 13, 6));
        LinearLayout words = v.column();
        MarkText name = v.mark("Add work", 11.5f, look.semibold, look.ink);
        words.addView(name);
        words.addView(v.meta(addHint()), v.margins(Views.wrap(), 0, 2, 0, 0));
        line.addView(words, v.margins(Views.fill(), 8, 0, 0, 0));
        add.addView(line, Views.wide());
        add.boxed(v.dashed(0, 10, look.faint), v.round(0, 10, look.ink, 1.5f));
        add.also(has -> name.mark(has, look.markStroke));
        return add;
    }

    private String addHint() {
        Assignment work = current();
        List<String> ways = new ArrayList<>();
        if (work.accepts(Assignment.TEXT) || work.accepts(Assignment.UPLOAD)) ways.add("write");
        if (work.accepts(Assignment.UPLOAD)) {
            ways.add("take photos");
            ways.add("record");
            ways.add("attach");
        }
        if (work.accepts(Assignment.URL)) ways.add("add a link");
        if (ways.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ways.size(); i++) {
            if (i > 0) out.append(i == ways.size() - 1 ? " or " : ", ");
            out.append(ways.get(i));
        }
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    /** One piece of the student's work, with its upload bar while it goes. */
    private Item piece(Parts parts, final Draft.Piece piece, boolean waiting) {
        String meta;
        String kind;
        if (Draft.TEXT.equals(piece.kind)) {
            kind = "text";
            meta = "Typed answer · " + piece.words() + " words";
        } else if (Draft.PHOTOS.equals(piece.kind)) {
            kind = "photos";
            meta = piece.amount + (piece.amount == 1 ? " photo · " : " photos · ") + Parts.size(piece.size);
        } else if (Draft.AUDIO.equals(piece.kind)) {
            kind = "audio";
            meta = "Audio · " + RecorderScreen.clock(piece.amount);
        } else if (Draft.LINK.equals(piece.kind)) {
            kind = "link";
            meta = piece.url;
        } else {
            kind = Draft.TEXT.equals(piece.kind) ? "text" : extension(piece.file);
            meta = extension(piece.file).toUpperCase(java.util.Locale.US) + " · " + Parts.size(piece.size);
        }
        if (draft.queued) meta = waiting ? (piece.isFile() ? "Saved on phone · will upload" : "Saved on phone") : meta;
        long[] sent = app.progress.get(piece.id);
        if (sent != null && sent[1] > 0) meta += " · Uploading " + (int) (100 * sent[0] / sent[1]) + "%";
        Item box = parts.box("piece:" + piece.id, "Open", parts.fileTile(kind), piece.name, meta, () -> openPiece(piece));
        if (sent != null && sent[1] > 0) {
            FrameLayout track = new FrameLayout(app);
            track.setBackground(v.round(look.line, 2, 0, 0));
            View fill = new View(app);
            fill.setBackground(v.round(look.ink, 2, 0, 0));
            track.addView(fill, new FrameLayout.LayoutParams(0, -1));
            final View bar = fill;
            final float fraction = (float) sent[0] / (float) sent[1];
            track.post(() -> {
                FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) bar.getLayoutParams();
                at.width = Math.round(track.getWidth() * fraction);
                bar.setLayoutParams(at);
            });
            box.addView(track, v.margins(new LinearLayout.LayoutParams(-1, v.dp(2)), 36, 0, 8, 3));
        }
        return box;
    }

    private static String extension(String file) {
        if (file == null) return "file";
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "file" : file.substring(dot + 1);
    }

    /** An upload moved on; only the bar redraws, not the page. */
    void progress(String pieceId) {
        refresh();
    }

    private void openPiece(Draft.Piece piece) {
        if (draft.queued) return;
        if (Draft.TEXT.equals(piece.kind)) {
            app.push(new EditorScreen(app, current(), piece));
        } else if (Draft.LINK.equals(piece.kind)) {
            app.push(new LinkScreen(app, current(), piece));
        } else if (Draft.AUDIO.equals(piece.kind)) {
            RecorderScreen.play(app, new java.io.File(app.backend.draftFolder(draft), piece.file));
        } else {
            java.io.File file = new java.io.File(app.backend.draftFolder(draft), piece.file);
            app.push(new ViewerScreen(app, file, piece.name, current().title));
        }
    }

    private void rename(final Draft.Piece piece) {
        app.push(new FieldScreen(app, "Rename", piece.name, "Name", value -> {
            if (value.trim().length() == 0) return;
            save(draft.with(piece.renamed(value.trim()), System.currentTimeMillis()));
        }));
    }

    private void remove(final Draft.Piece piece) {
        Sheet.ask(app, "Remove " + piece.name + "?", "It's deleted from this phone.", "Remove", "Keep it", () -> {
            if (piece.file != null) new java.io.File(app.backend.draftFolder(draft), piece.file).delete();
            save(draft.without(piece.id, System.currentTimeMillis()));
        });
    }

    private void save(Draft next) {
        try {
            app.backend.saveDraft(next);
        } catch (java.io.IOException e) {
            app.say("Couldn't save on the phone.");
        }
        draft = next;
        refresh();
    }

    /** A step finished: a typed answer, photos, a recording, a file or a link joins the draft. */
    @Override
    void result(Object value) {
        if (value instanceof Detail) detail = (Detail) value;
        if (value instanceof Draft.Piece) {
            draft = app.backend.draft(item.id);
            save(draft.with((Draft.Piece) value, System.currentTimeMillis()));
            savedFocus = "piece:" + ((Draft.Piece) value).id;
        }
    }

    // ------------------------------------------------------------------
    // Adding and turning in
    // ------------------------------------------------------------------

    /** Add your work (D1): a numbered sheet, one key per method. */
    private void addWork() {
        final Assignment work = current();
        List<Sheet.Choice> ways = new ArrayList<>();
        if (work.accepts(Assignment.TEXT) || work.accepts(Assignment.UPLOAD)) {
            ways.add(new Sheet.Choice("Write an answer", R.drawable.ic_edit, null,
                () -> app.push(new EditorScreen(app, work, null))));
        }
        if (work.accepts(Assignment.UPLOAD)) {
            ways.add(new Sheet.Choice("Take photos", R.drawable.ic_camera, null,
                () -> app.withPermission(MainActivity.CAMERA, () -> app.push(new CameraScreen(app, work)))));
            ways.add(new Sheet.Choice("Record audio", R.drawable.ic_mic, null,
                () -> app.withPermission(MainActivity.MICROPHONE, () -> app.push(new RecorderScreen(app, work)))));
            ways.add(new Sheet.Choice("Choose a file", R.drawable.ic_folder, null,
                () -> app.withPermission(MainActivity.STORAGE, () -> app.push(new PickerScreen(app, work)))));
        }
        if (work.accepts(Assignment.URL)) {
            ways.add(new Sheet.Choice("Add a link", R.drawable.ic_link, null,
                () -> app.push(new LinkScreen(app, work, null))));
        }
        if (ways.isEmpty()) return;
        Sheet.menu(app, "Add your work", ways);
    }

    /** Turn in your work? (F1): says exactly what will be sent, with Turn in already focused. */
    private void confirmTurnIn(final Assignment work) {
        if (app.sending(work)) return;
        List<String> extensions = detail == null ? new ArrayList<String>() : detail.extensions;
        Draft.Plan plan = draft.plan(work.kinds, extensions);
        if (!plan.ok()) {
            Sheet.ask(app, "Can't turn in yet", plan.problem, "OK", "Close", () -> { });
            return;
        }
        int count = draft.pieces.size();
        final List<String> allowed = extensions;
        Sheet.ask(app, "Turn in your work?", count + (count == 1 ? " item" : " items")
            + " will be turned in for " + work.title + ".", "Turn in", "Cancel",
            () -> app.turnIn(work, draft, allowed));
    }

    private void markDone(final Assignment work, final boolean done) {
        final String override = app.snapshot == null ? null : app.snapshot.overrides.get(work.assignmentId);
        app.background(() -> {
            app.backend.markDone(work, override, done);
            return Boolean.TRUE;
        }, (ok, failure) -> {
            if (ok == null) {
                app.say(failure);
                return;
            }
            java.util.Set<String> checked = app.todos.checkedIds();
            if (done) checked.add(work.assignmentId);
            else checked.remove(work.assignmentId);
            app.todos.check(checked);
            app.saveSnapshot();
            // The override's id comes back with the next sync, which then
            // updates that record rather than adding a second.
            app.refresh(false);
            refresh();
        });
    }

    private void comments() {
        app.push(new CommentsScreen(app, current(), detail));
    }

    // ------------------------------------------------------------------
    // A discussion: the answer box on the page (E1)
    // ------------------------------------------------------------------

    private void discussion(LinearLayout page, final Assignment work) {
        if (work.done) {
            TextView done = v.bodyText("You've posted your answer.");
            v.pad(done, 12, 0, 12, 4);
            page.addView(done, Views.wide());
        }
        TextView label = v.label("Your answer");
        v.pad(label, 12, 2, 12, 3);
        page.addView(label, Views.wide());
        answer = Fields.box(app, v, draft.pieces.isEmpty() ? "" : String.valueOf(draft.pieces.get(0).text),
            "Write your answer", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        answer.setMinLines(3);
        answer.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                Draft.Piece piece = Draft.Piece.text("answer", "Answer", s.toString());
                draft = draft.with(piece, System.currentTimeMillis());
                try {
                    app.backend.saveDraft(draft);
                } catch (java.io.IOException e) {
                    // Kept on screen; the next keystroke tries again.
                }
            }
        });
        page.addView(answer, v.margins(Views.wide(), 12, 0, 12, 0));
        Item post = Sheet.button(app, v, "post", "Post", true, () -> postAnswer(work));
        page.addView(post, v.margins(new LinearLayout.LayoutParams(-1, v.dp(28)), 10, 8, 10, 0));
    }

    private void postAnswer(final Assignment work) {
        final String text = answer == null ? "" : answer.getText().toString().trim();
        if (text.length() == 0) {
            app.say("Write your answer first.");
            return;
        }
        Sheet.ask(app, "Post your answer?", "Everyone in " + (app.course(work.courseId) == null ? "the class"
            : app.course(work.courseId).code) + " can read it.", "Post", "Cancel", () -> app.background(() -> {
                app.backend.reply(work.courseId, work.topicId, text);
                return Boolean.TRUE;
            }, (ok, failure) -> {
                if (ok == null) {
                    app.tell("Couldn't post", failure + " Your answer is saved on the phone.");
                    return;
                }
                app.backend.dropDraft(work.id);
                app.turnedIn.put(work.id, Long.valueOf(System.currentTimeMillis()));
                app.refresh(false);
                refresh();
            }));
    }

    @Override
    String firstFocus() {
        return draft.queued ? "cancel" : "title";
    }
}
