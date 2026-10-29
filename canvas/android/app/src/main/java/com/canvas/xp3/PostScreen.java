package com.canvas.xp3;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.ClassData;
import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Detail;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Class comments (G3) under a stream post, or the replies to a discussion.
 *
 * Everyone in the class can read these, and the compose box says so before
 * anything is typed: "Add a class comment". If the teacher has turned
 * replies off, the box is replaced by one line saying so.
 */
final class PostScreen extends Screen {

    private final Course course;
    private final ClassData.Post post;
    private List<Detail.Comment> replies;
    private String problem;
    private EditText field;
    private View rest;
    private String typed = "";
    private boolean sending;

    PostScreen(MainActivity app, Course course, ClassData.Post post) {
        super(app);
        this.course = course;
        this.post = post;
        load();
    }

    private void load() {
        app.background(() -> app.backend.replies(course.id, post.id), (found, failure) -> {
            if (found != null) replies = found;
            else problem = failure;
            refresh();
        });
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        if (replies == null && problem == null) return parts.spinner();
        LinearLayout root = v.column();
        root.addView(parts.nav("Stream", course), Views.wide());
        LinearLayout page = parts.page(this, root);

        LinearLayout card = v.column();
        card.setBackground(v.round(look.bg, 12, look.line, 1));
        v.pad(card, 9, 7, 9, 7);
        if (post.author.length() > 0) {
            card.addView(parts.author(post.author, post.at, look.course(course.color)), Views.wide());
        }
        TextView title = v.text(post.title, 11, look.bold, look.ink);
        v.pad(title, 0, 5, 0, 0);
        card.addView(title, Views.wide());
        if (post.body.trim().length() > 0) {
            LinearLayout body = v.column();
            parts.markdown(body, post.body);
            for (int i = 0; i < body.getChildCount(); i++) body.getChildAt(i).setPadding(0,
                body.getChildAt(i).getPaddingTop(), 0, body.getChildAt(i).getPaddingBottom());
            card.addView(body, Views.wide());
        }
        page.addView(card, v.margins(Views.wide(), 10, 2, 10, 0));

        page.addView(parts.section("Class comments", replies == null ? null : Integer.toString(replies.size())),
            Views.wide());
        if (replies == null) {
            TextView wait = v.text("Comments couldn't be loaded. " + problem, 10, look.regular, look.muted);
            v.pad(wait, 12, 0, 12, 0);
            page.addView(wait, Views.wide());
        } else {
            long now = System.currentTimeMillis();
            for (Detail.Comment said : replies) {
                LinearLayout row = v.row();
                row.setGravity(Gravity.TOP);
                v.pad(row, 12, 5, 12, 5);
                row.addView(parts.avatar(said.author, look.faint, 20));
                LinearLayout words = v.column();
                TextView who = v.text(Course.shortName(said.author) + "  " + Talk.when(said.at, now), 9.5f, look.bold,
                    look.ink);
                words.addView(who);
                TextView text = v.text(said.text, 10, look.regular, look.body);
                text.setLineSpacing(0, 1.25f);
                words.addView(text, v.margins(Views.wide(), 0, 1, 0, 0));
                row.addView(words, v.margins(Views.fill(), 7, 0, 0, 0));
                page.addView(row, Views.wide());
            }
        }
        if (post.open) {
            field = Talk.compose(app, v, root, sending ? "Sending…" : "Add a class comment");
            field.setText(typed);
        } else {
            field = null;
            TextView closed = v.text("Comments are turned off for this post.", 9.5f, look.regular, look.muted);
            v.pad(closed, 12, 6, 12, 6);
            root.addView(closed, Views.wide());
        }
        root.setFocusable(true);
        rest = root;
        return root;
    }

    @Override
    void shown() {
        if (field != null) field.requestFocus();
    }

    @Override
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (field != null && app.getCurrentFocus() == field) {
            if (Ui.isOk(code)) {
                if (event.getAction() == KeyEvent.ACTION_UP) send();
                return true;
            }
            if (field.getLineCount() <= 1) return read(event, field);
        } else if (rest != null) {
            return read(event, field != null ? field : rest);
        }
        return false;
    }

    private void send() {
        final String text = field.getText().toString().trim();
        if (text.length() == 0 || sending) return;
        Sheet.ask(app, "Post a class comment?", "Everyone in " + course.code + " can read it.", "Post", "Cancel",
            () -> {
                sending = true;
                typed = text;
                refresh();
                app.background(() -> {
                    app.backend.reply(course.id, post.id, text);
                    return app.backend.replies(course.id, post.id);
                }, (found, failure) -> {
                    sending = false;
                    if (found != null) {
                        replies = found;
                        typed = "";
                    } else {
                        app.say(failure);
                    }
                    refresh();
                });
            });
    }

    @Override
    void refresh() {
        if (field != null) typed = field.getText().toString();
        super.refresh();
    }

    @Override
    boolean globalOptions() {
        return false;
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        if (post.open && field != null) {
            out.add(new Sheet.Choice("Clear", R.drawable.ic_undo, null, () -> field.setText("")));
        }
        out.add(new Sheet.Choice("Refresh", R.drawable.ic_refresh, null, this::load));
        return out;
    }
}
