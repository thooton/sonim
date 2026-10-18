package com.canvas.xp3;

import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Html;
import com.xp3.common.Json;

import java.util.Map;

/** A Canvas page from a class's Classwork: its title and its text, read with Up and Down. */
final class PageScreen extends Screen {

    private final Course course;
    private final String title;
    private final String pageId;
    private String body;
    private String problem;
    private View rest;

    PageScreen(MainActivity app, Course course, String title, String pageId) {
        super(app);
        this.course = course;
        this.title = title;
        this.pageId = pageId;
        if (pageId.matches("\\d+")) {
            app.background(() -> app.backend.page(course.id, pageId), (Map<String, Object> page, String failure) -> {
                if (page != null) body = Html.toMarkdown(Json.text(page, "body"));
                else problem = failure;
                refresh();
            });
        } else {
            problem = "This page opens on a computer.";
        }
    }

    @Override
    String right() {
        return "Close";
    }

    @Override
    void onRight() {
        app.pop();
    }

    @Override
    boolean onKey(KeyEvent event) {
        return rest != null && read(event, rest);
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        if (body == null && problem == null) return parts.spinner();
        LinearLayout root = v.column();
        root.addView(parts.nav("Classwork", course), Views.wide());
        LinearLayout page = parts.page(this, root);
        MarkText heading = v.pageTitle(title);
        v.pad(heading, 12, 0, 12, 6);
        page.addView(heading, Views.wide());
        if (body != null) {
            parts.markdown(page, body.trim().length() == 0 ? "This page is empty." : body);
        } else {
            TextView wait = v.bodyText(problem);
            wait.setTextColor(look.muted);
            v.pad(wait, 12, 0, 12, 0);
            page.addView(wait, Views.wide());
        }
        root.setFocusable(true);
        rest = root;
        return root;
    }

    @Override
    boolean globalOptions() {
        return false;
    }
}
