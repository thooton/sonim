package com.canvas.xp3;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Course;

/**
 * Classes (B1), for when a student thinks by subject. Each class keeps its
 * colour as a soft tile with its code, so it is recognisable in every list.
 */
final class ClassesScreen extends Screen {

    ClassesScreen(MainActivity app) {
        super(app);
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        LinearLayout head = v.row();
        head.setGravity(Gravity.BOTTOM);
        v.pad(head, 12, 4, 12, 6);
        head.addView(v.screenTitle("Classes"), Views.fill());
        String term = null;
        if (app.snapshot != null) {
            for (Course course : app.snapshot.courses) if (term == null) term = course.term;
        }
        if (term != null) head.addView(v.text(term, 9, look.semibold, look.muted), v.margins(Views.wrap(), 0, 0, 0, 2));
        root.addView(head, Views.wide());
        if ((app.snapshot == null || app.snapshot.courses.isEmpty()) && app.syncing) return parts.spinner();
        LinearLayout list = parts.page(this, root);
        if (app.snapshot == null || app.snapshot.courses.isEmpty()) {
            list.addView(parts.empty(0, "No classes this semester", null),
                v.margins(Views.wide(), 0, 50, 0, 0));
            return root;
        }
        long now = System.currentTimeMillis();
        for (final Course course : app.snapshot.courses) {
            int due = 0;
            for (Assignment item : app.todos.inState(Assignment.ASSIGNED, now)) {
                if (item.courseId.equals(course.id) && item.due != null) due++;
            }
            Item row = new Item(app, "class:" + course.id, "Open", () -> app.push(new ClassScreen(app, course)));
            LinearLayout line = v.row();
            v.pad(line, 12, 6, 12, 6);
            line.addView(tile(course));
            LinearLayout text = v.column();
            MarkText name = v.mark(course.title, 11.5f, look.semibold, look.ink);
            name.setLineSpacing(0, 1.1f);
            text.addView(name, Views.wide());
            if (course.teacher() != null) text.addView(v.meta(course.teacher()), v.margins(Views.wide(), 0, 2, 0, 0));
            line.addView(text, v.margins(Views.fill(), 9, 0, 0, 0));
            if (due > 0) line.addView(v.text(due + " due", 9.5f, look.semibold, look.muted), v.margins(Views.wrap(), 8, 0, 0, 0));
            row.addView(line, Views.wide());
            row.marks(name, look);
            list.addView(row, Views.wide());
        }
        return root;
    }

    /** A soft square in the course's colour with its code: "THF" over "101". */
    private View tile(Course course) {
        LinearLayout tile = v.column();
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(v.round(look.courseWash(course.color), 10, 0, 0));
        int color = look.course(course.color);
        TextView letters = v.text(course.codeLetters(), course.codeLetters().length() > 4 ? 8 : 10, look.heavy, color);
        letters.setSingleLine(true);
        tile.addView(letters);
        if (course.codeNumber().length() > 0) tile.addView(v.text(course.codeNumber(), 8.5f, look.bold, color));
        tile.setLayoutParams(new LinearLayout.LayoutParams(v.dp(34), v.dp(34)));
        return tile;
    }
}
