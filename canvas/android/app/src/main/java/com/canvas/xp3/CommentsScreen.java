package com.canvas.xp3;

import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Detail;

import java.util.ArrayList;
import java.util.List;

/**
 * Private comments (G2): one thread per assignment, read like a text
 * conversation with the teacher. The lock line says exactly who can read it.
 */
final class CommentsScreen extends Screen {

    private final Assignment work;
    private Detail detail;
    private EditText field;
    private String typed = "";
    private boolean sending;

    CommentsScreen(MainActivity app, Assignment work, Detail detail) {
        super(app);
        this.work = work;
        this.detail = detail;
    }

    @Override
    String left() {
        return "Options";
    }

    @Override
    String center() {
        return sending ? "" : "Send";
    }

    @Override
    String right() {
        return "Clear";
    }

    @Override
    void onRight() {
        if (field != null) field.setText("");
    }

    @Override
    boolean globalOptions() {
        return false;
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        out.add(new Sheet.Choice("Refresh", R.drawable.ic_refresh, null, this::reload));
        return out;
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        Course course = app.course(work.courseId);
        LinearLayout root = v.column();
        root.addView(parts.nav(work.title, (String) null), Views.wide());
        TextView heading = v.text("Private comments", 14, look.heavy, look.ink);
        v.pad(heading, 12, 0, 12, 0);
        root.addView(heading, Views.wide());
        LinearLayout lock = v.row();
        v.pad(lock, 12, 2, 12, 2);
        lock.addView(v.icon(R.drawable.ic_lock, look.muted, 9));
        String teacher = course == null || course.teacher() == null ? "your teacher" : course.teacher();
        lock.addView(v.text("Only you and " + teacher, 9, look.medium, look.muted), v.margins(Views.wrap(), 4, 0, 0, 0));
        root.addView(lock, Views.wide());

        LinearLayout thread = parts.page(this, root);
        v.pad(thread, 10, 6, 10, 6);
        long now = System.currentTimeMillis();
        if (detail == null || detail.comments.isEmpty()) {
            TextView none = v.text("No comments yet. Ask a question about this assignment and only your teacher "
                + "will see it.", 10, look.regular, look.muted);
            none.setLineSpacing(0, 1.3f);
            thread.addView(none, Views.wide());
        } else {
            for (Detail.Comment said : detail.comments) {
                thread.addView(Talk.bubble(v, said, now), v.margins(Views.wide(), 0, 0, 0, 5));
            }
        }
        field = Talk.compose(app, v, root, sending ? "Sending…" : "Write to " + teacher);
        field.setText(typed);
        field.setSelection(typed.length());
        return root;
    }

    @Override
    void shown() {
        field.requestFocus();
        if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        field.requestFocus();
        app.refreshKeys();
    }

    @Override
    boolean onKey(KeyEvent event) {
        if (com.xp3.common.ui.Ui.isOk(event.getKeyCode()) && app.getCurrentFocus() == field) {
            if (event.getAction() == KeyEvent.ACTION_UP) send();
            return true;
        }
        int code = event.getKeyCode();
        if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) && scroll != null
            && app.getCurrentFocus() == field && field.getLineCount() <= 1) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) scroll.smoothScrollBy(0, (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * v.dp(60));
            return true;
        }
        return false;
    }

    private void send() {
        final String text = field.getText().toString().trim();
        if (text.length() == 0 || sending) return;
        sending = true;
        typed = text;
        refresh();
        app.background(() -> {
            app.backend.comment(work, text);
            return app.backend.detail(work);
        }, (fresh, problem) -> {
            sending = false;
            if (fresh != null) {
                detail = fresh;
                typed = "";
            } else {
                app.say(problem == null ? "Couldn't send." : problem);
            }
            refresh();
        });
    }

    private void reload() {
        app.background(() -> app.backend.detail(work), (fresh, problem) -> {
            if (fresh != null) detail = fresh;
            else app.say(problem);
            refresh();
        });
    }

    @Override
    void refresh() {
        if (field != null) typed = field.getText().toString();
        super.refresh();
    }

    /** The page underneath shows the new comments too. */
    @Override
    boolean onBack() {
        app.finishWith(detail);
        return true;
    }
}
