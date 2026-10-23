package com.canvas.xp3;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.ClassData;
import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Detail;

import java.util.ArrayList;
import java.util.List;

/**
 * One class (B2 to B4): Stream, Classwork, People and Grades, as Classroom
 * has them. Left and Right switch tabs.
 *
 * Classwork is the course's modules, which is how most Canvas courses lay
 * out their work, grouped under the teacher's own topic names.
 */
final class ClassScreen extends Screen {

    private static final String[] TABS = { "Stream", "Classwork", "People", "Grades" };

    private final Course course;
    private int tab;
    private ClassData data;
    private boolean loading;
    private String problem;

    ClassScreen(MainActivity app, Course course) {
        super(app);
        this.course = course;
        data = app.backend.cachedClass(course.id);
        fetch();
    }

    private void fetch() {
        if (!app.backend.canvasReady()) return;
        loading = true;
        app.background(() -> app.backend.classData(course.id), (fresh, failure) -> {
            loading = false;
            if (fresh != null) data = fresh;
            else problem = failure;
            refresh();
        });
    }

    /** Jumps to the next topic heading in Classwork. */
    private void nextTopic() {
        if (tab != 1 || scroll == null) return;
        View focus = app.getCurrentFocus();
        List<Item> rows = items(scroll);
        int at = rows.indexOf(focus);
        String topic = at >= 0 ? rows.get(at).key.split(":")[1] : "-1";
        for (int i = at + 1; i < rows.size(); i++) {
            if (!rows.get(i).key.split(":")[1].equals(topic)) {
                rows.get(i).requestFocus();
                return;
            }
        }
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        if (tab == 1 && data != null && data.topics.size() > 1) {
            out.add(new Sheet.Choice("Next topic", R.drawable.ic_folder, null, this::nextTopic));
        }
        final boolean quiet = app.prefs.quiet.contains(course.id);
        out.add(new Sheet.Choice(quiet ? "Turn reminders on" : "Turn reminders off", R.drawable.ic_bell,
            "This class", () -> app.savePrefs(app.prefs.withQuiet(course.id, !quiet))));
        return out;
    }

    @Override
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                tab = (tab + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) + TABS.length) % TABS.length;
                app.redraw();
            }
            return true;
        }
        return false;
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        LinearLayout head = v.column();
        v.pad(head, 12, 3, 12, 0);
        LinearLayout name = v.row();
        View square = new View(app);
        square.setBackground(v.round(look.course(course.color), 3, 0, 0));
        name.addView(square, new LinearLayout.LayoutParams(v.dp(9), v.dp(9)));
        TextView title = v.text(course.title, 15, look.heavy, look.ink);
        title.setLetterSpacing(-0.01f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        name.addView(title, v.margins(Views.fill(), 6, 0, 0, 0));
        head.addView(name, Views.wide());
        StringBuilder meta = new StringBuilder(course.code);
        if (course.teacher() != null) meta.append(" · ").append(course.teacher());
        if (course.term != null) meta.append(" · ").append(course.term);
        head.addView(v.meta(meta.toString()), v.margins(Views.wide(), 0, 1, 0, 0));
        root.addView(head, Views.wide());

        LinearLayout tabs = v.row();
        v.pad(tabs, 12, 7, 12, 0);
        for (int i = 0; i < TABS.length; i++) {
            boolean on = i == tab;
            TextView label = v.text(TABS[i], 10, on ? look.heavy : look.semibold, on ? look.ink : look.muted);
            label.setSingleLine(true);
            v.pad(label, 0, 0, 0, 5);
            if (on) {
                // The open tab's ink rule, drawn under its own text only.
                android.graphics.drawable.GradientDrawable rule = new android.graphics.drawable.GradientDrawable();
                rule.setColor(look.ink);
                android.graphics.drawable.LayerDrawable under = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[] { rule });
                under.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
                under.setLayerHeight(0, v.dp(2));
                label.setBackground(under);
            }
            tabs.addView(label, v.margins(Views.wrap(), 0, 0, 12, 0));
        }
        root.addView(tabs, Views.wide());
        root.addView(v.hairline());

        LinearLayout page = parts.page(this, root);
        if (data == null) {
            if (loading) return parts.spinner();
            page.addView(parts.empty(0, "Couldn't open this class", problem), v.margins(Views.wide(), 0, 40, 0, 0));
            return root;
        }
        if (tab == 0) stream(parts, page);
        else if (tab == 1) classwork(parts, page);
        else if (tab == 2) people(parts, page);
        else grades(parts, page);
        return root;
    }

    // ------------------------------------------------------------------

    private void stream(Parts parts, LinearLayout page) {
        long now = System.currentTimeMillis();
        Assignment next = null;
        for (Assignment item : app.todos.inState(Assignment.ASSIGNED, now)) {
            if (!item.courseId.equals(course.id) || item.due == null) continue;
            if (next == null || item.due.longValue() < next.due.longValue()) next = item;
        }
        if (next != null) {
            final Assignment upcoming = next;
            Item card = new Item(app, "upcoming", "Open", () -> app.push(new AssignmentScreen(app, upcoming, "Stream")));
            LinearLayout inside = v.column();
            v.pad(inside, 9, 6, 9, 6);
            inside.addView(v.label("Upcoming"));
            MarkText title = v.mark(upcoming.title, 11, look.semibold, look.ink);
            inside.addView(title, v.margins(Views.wide(), 0, 1, 0, 0));
            inside.addView(v.meta("Due " + Dates.shortDay(upcoming.due.longValue())), v.margins(Views.wide(), 0, 1, 0, 0));
            card.addView(inside, Views.wide());
            card.setBackground(v.round(look.paper, 10, 0, 0));
            card.marks(title, look);
            page.addView(card, v.margins(Views.wide(), 10, 8, 10, 0));
        }
        if (data.posts.isEmpty()) {
            TextView none = v.text("No announcements yet.", 10, look.regular, look.muted);
            v.pad(none, 12, 12, 12, 0);
            page.addView(none, Views.wide());
        }
        for (final ClassData.Post post : data.posts) {
            page.addView(post(parts, post, true), v.margins(Views.wide(), 10, 8, 10, 0));
        }
    }

    /** An announcement card: who, when, what, and a way into its comments. */
    View post(Parts parts, final ClassData.Post post, boolean clip) {
        LinearLayout card = v.column();
        card.setBackground(v.round(look.bg, 12, look.line, 1));
        v.pad(card, 9, 7, 9, 7);
        card.addView(parts.author(post.author, post.at, look.course(course.color)), Views.wide());
        TextView title = v.text(post.title, 10.5f, look.bold, look.ink);
        v.pad(title, 0, 5, 0, 0);
        card.addView(title, Views.wide());
        String body = post.body.replaceAll("[*_#>`]", "").trim();
        TextView text = v.bodyText(body);
        if (clip) {
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
        }
        v.pad(text, 0, 3, 0, 0);
        card.addView(text, Views.wide());
        Item foot = new Item(app, "post:" + post.id, "Open", () -> app.push(new PostScreen(app, course, post)));
        LinearLayout line = v.row();
        v.pad(line, 0, 5, 0, 0);
        line.addView(v.icon(R.drawable.ic_comment, look.ink, 11));
        MarkText label = v.mark(post.replies == 0 ? "Class comments" : post.replies
            + (post.replies == 1 ? " class comment" : " class comments"), 9.5f, look.semibold, look.ink);
        line.addView(label, v.margins(Views.wrap(), 5, 0, 0, 0));
        foot.addView(v.hairline());
        foot.addView(line, v.margins(Views.wide(), 0, 0, 0, 0));
        foot.marks(label, look);
        card.addView(foot, v.margins(Views.wide(), 0, 6, 0, 0));
        return card;
    }

    private void classwork(Parts parts, LinearLayout page) {
        if (data.topics.isEmpty()) {
            page.addView(parts.empty(0, "No classwork posted", null), v.margins(Views.wide(), 0, 40, 0, 0));
            return;
        }
        for (int t = 0; t < data.topics.size(); t++) {
            ClassData.Topic topic = data.topics.get(t);
            int count = 0;
            for (ClassData.Item item : topic.items) if (!ClassData.Item.HEADER.equals(item.type)) count++;
            LinearLayout heading = v.row();
            v.pad(heading, 12, 8, 12, 2);
            TextView name = v.text(topic.name, 10.5f, look.heavy, look.ink);
            heading.addView(name, Views.fill());
            heading.addView(v.text(count + (count == 1 ? " item" : " items"), 8.5f, look.semibold, look.muted));
            page.addView(heading, Views.wide());
            for (int i = 0; i < topic.items.size(); i++) {
                final ClassData.Item item = topic.items.get(i);
                if (ClassData.Item.HEADER.equals(item.type)) {
                    TextView sub = v.label(item.title);
                    v.pad(sub, 12, 6, 12, 2);
                    page.addView(sub, Views.wide());
                    continue;
                }
                page.addView(classworkRow(parts, "cw:" + t + ":" + i, item), Views.wide());
            }
        }
    }

    private Item classworkRow(Parts parts, String key, final ClassData.Item item) {
        int icon;
        String kind;
        if (ClassData.Item.ASSIGNMENT.equals(item.type)) {
            icon = R.drawable.ic_assignment;
            kind = "Assignment";
        } else if (ClassData.Item.QUIZ.equals(item.type)) {
            icon = R.drawable.ic_help;
            kind = "Quiz";
        } else if (ClassData.Item.DISCUSSION.equals(item.type)) {
            icon = R.drawable.ic_forum;
            kind = "Discussion";
        } else if (ClassData.Item.FILE.equals(item.type)) {
            icon = R.drawable.ic_doc;
            kind = "File";
        } else if (ClassData.Item.LINK.equals(item.type) || ClassData.Item.TOOL.equals(item.type)) {
            icon = R.drawable.ic_link;
            kind = "Link";
        } else {
            icon = R.drawable.ic_book;
            kind = "Material";
        }
        Assignment work = assignmentFor(item);
        String meta = kind;
        View right = null;
        if (work != null && work.returned && work.gradeChip() != null) {
            meta += " · Returned";
            right = v.gradeChip(work.gradeChip());
        } else if (work != null && work.done) {
            meta += " · Turned in";
        } else if (item.due != null) {
            meta += " · Due " + Dates.shortDay(item.due.longValue());
        }
        int tint = look.course(course.color);
        return parts.tiled(key, "Open", icon, look.courseWash(course.color), tint, item.title, meta, right,
            () -> open(item));
    }

    /** The assignment a classwork item stands for, if the work list has it. */
    private Assignment assignmentFor(ClassData.Item item) {
        if (item.contentId == null) return null;
        for (Assignment work : app.todos.all()) {
            if (!work.courseId.equals(course.id)) continue;
            if (ClassData.Item.ASSIGNMENT.equals(item.type) && work.assignmentId.equals(item.contentId)) return work;
            if (ClassData.Item.QUIZ.equals(item.type) && item.contentId.equals(work.quizId)) return work;
            if (ClassData.Item.DISCUSSION.equals(item.type) && item.contentId.equals(work.topicId)) return work;
        }
        return null;
    }

    private void open(ClassData.Item item) {
        Assignment work = assignmentFor(item);
        if (work != null) {
            app.push(new AssignmentScreen(app, work, "Classwork"));
        } else if (ClassData.Item.PAGE.equals(item.type) && item.contentId != null) {
            app.push(new PageScreen(app, course, item.title, item.contentId));
        } else if (ClassData.Item.FILE.equals(item.type) && item.contentId != null) {
            app.push(new ViewerScreen(app, new Detail.FileRef(item.contentId, item.title, -1, null), course.title));
        } else if (ClassData.Item.DISCUSSION.equals(item.type) && item.contentId != null) {
            app.push(new PostScreen(app, course, new ClassData.Post(item.contentId, item.title, "", 0, "", 0, true)));
        } else if (item.url != null) {
            Sheet.ask(app, item.title, item.url, "Open in browser", "Close", () -> {
                try {
                    app.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(item.url)));
                } catch (RuntimeException e) {
                    app.say("This phone has no browser for that.");
                }
            });
        } else {
            app.say("This one opens on a computer.");
        }
    }

    private void people(Parts parts, LinearLayout page) {
        String last = null;
        for (int i = 0; i < data.people.size(); i++) {
            ClassData.Person person = data.people.get(i);
            String group = "Student".equals(person.role) ? "Classmates" : "Teachers";
            if (!group.equals(last)) {
                page.addView(parts.section(group, null), Views.wide());
                last = group;
            }
            Item row = new Item(app, "person:" + i, null, null);
            LinearLayout line = v.row();
            v.pad(line, 12, 5, 12, 5);
            line.addView(parts.avatar(person.name, "Student".equals(person.role) ? look.faint
                : look.course(course.color), 22));
            MarkText name = v.mark(person.name, 11, look.semibold, look.ink);
            line.addView(name, v.margins(Views.fill(), 9, 0, 0, 0));
            if ("TA".equals(person.role)) line.addView(v.text("TA", 9, look.semibold, look.muted));
            row.addView(line, Views.wide());
            row.marks(name, look);
            page.addView(row, Views.wide());
        }
        if (data.people.isEmpty()) {
            page.addView(parts.empty(0, "This class doesn't show its people", null), v.margins(Views.wide(), 0, 40, 0, 0));
        }
    }

    private void grades(Parts parts, LinearLayout page) {
        if (course.score != null) {
            LinearLayout overall = v.row();
            v.pad(overall, 12, 10, 12, 0);
            overall.addView(v.text("Overall", 12, look.bold, look.ink), Views.fill());
            TextView score = v.text(Assignment.number(Math.round(course.score.doubleValue() * 10) / 10.0) + "%", 18,
                look.heavy, look.ink);
            Views.tabular(score);
            overall.addView(score);
            page.addView(overall, Views.wide());
        }
        page.addView(parts.section("Graded work", null), Views.wide());
        List<Assignment> returned = new ArrayList<>();
        List<Assignment> waiting = new ArrayList<>();
        for (Assignment work : app.todos.all()) {
            if (!work.courseId.equals(course.id)) continue;
            if (work.returned || work.excused) returned.add(work);
            else if (work.done && work.points > 0) waiting.add(work);
        }
        java.util.Collections.sort(returned, (a, b) -> Long.compare(at(b), at(a)));
        for (final Assignment work : returned) {
            Long when = work.gradedAt != null ? work.gradedAt : work.turnedIn;
            String chip = work.gradeChip();
            page.addView(row(work, when == null ? "Returned" : "Returned " + Dates.shortDay(when.longValue()),
                chip == null ? null : v.gradeChip(chip)), Views.wide());
        }
        for (final Assignment work : waiting) {
            page.addView(row(work, work.turnedIn == null ? "Turned in"
                : "Turned in " + Dates.shortDay(work.turnedIn.longValue()),
                v.text("Not graded yet", 9.5f, look.semibold, look.muted)), Views.wide());
        }
        if (returned.isEmpty() && waiting.isEmpty()) {
            TextView none = v.text("Nothing graded yet.", 10, look.regular, look.muted);
            v.pad(none, 12, 0, 12, 0);
            page.addView(none, Views.wide());
        }
    }

    private static long at(Assignment work) {
        Long when = work.gradedAt != null ? work.gradedAt : work.turnedIn;
        return when == null ? 0 : when.longValue();
    }

    private Item row(final Assignment work, String meta, View right) {
        Item row = new Item(app, "g:" + work.id, "Open", () -> app.push(new AssignmentScreen(app, work, "Grades")));
        LinearLayout line = v.row();
        v.pad(line, 12, 6, 12, 6);
        LinearLayout text = v.column();
        MarkText name = v.mark(work.title, 11.5f, look.semibold, look.ink);
        text.addView(name, Views.wide());
        text.addView(v.meta(meta), v.margins(Views.wide(), 0, 2, 0, 0));
        line.addView(text, Views.fill());
        if (right != null) line.addView(right, v.margins(Views.wrap(), 8, 0, 0, 0));
        row.addView(line, Views.wide());
        row.marks(name, look);
        return row;
    }
}
