package com.canvas.xp3;

import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Html;
import com.canvas.xp3.data.Link;
import com.xp3.common.Disk;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A link from outside Canvas, fetched. A file (a PDF, a picture) goes on to
 * the reader; a web page is shown as its text, the way a Canvas page is,
 * with its own links in reach in turn.
 */
final class WebScreen extends Screen {

    private final Link link;
    private final String title;
    private String body;
    private String problem;
    private View rest;

    WebScreen(MainActivity app, Link link, String title) {
        super(app);
        this.link = link;
        this.title = title;
        app.background(() -> app.backend.fetchLink(link, title), (File got, String failure) -> {
            if (got == null) {
                problem = failure;
            } else if (got.getName().endsWith(".html")) {
                body = page(got);
            } else if (app.top() == this) {
                app.replace(new ViewerScreen(app, got, title, title));
                return;
            }
            refresh();
        });
    }

    /** The page as Markdown, in the character set it was sent in when that was recorded in its own head. */
    private static String page(File file) {
        String text = Disk.read(file, (int) Backend.MAX_DOWNLOAD);
        if (text == null) return "";
        Matcher declared = Pattern
            .compile("(?i)<meta[^>]+charset=[\"']?([A-Za-z0-9._-]+)").matcher(text.length() > 4096
                ? text.substring(0, 4096) : text);
        if (declared.find() && !declared.group(1).equalsIgnoreCase("utf-8")) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                text = new String(bytes, Charset.forName(declared.group(1)));
            } catch (Exception e) {
                // An unknown or unreadable charset: the UTF-8 reading stands.
            }
        }
        return Html.toMarkdown(text);
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
        root.addView(parts.nav("Back", link.site()), Views.wide());
        LinearLayout page = parts.page(this, root);
        MarkText heading = v.pageTitle(title);
        v.pad(heading, 12, 0, 12, 6);
        page.addView(heading, Views.wide());
        if (body != null) {
            parts.markdown(page, body.trim().length() == 0 ? "This page has no text to show." : body, link.fetch);
        } else {
            TextView wait = v.bodyText("Couldn't open the link. " + problem);
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
