package com.canvas.xp3;

import android.Manifest;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Detail;
import com.canvas.xp3.data.Link;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What OK on a link in a teacher's text offers: read it here, or save it to
 * the phone's Download folder. An assignment link simply opens it.
 */
final class LinkMenu {

    private LinkMenu() {}

    static void show(final MainActivity app, final Link link, final String label) {
        if (link.kind == Link.ASSIGNMENT) {
            Assignment work = app.assignment(link.course, link.id);
            if (work != null) app.push(new AssignmentScreen(app, work, "Back"));
            else app.say("That assignment isn't in this semester's classes.");
            return;
        }
        if (link.kind == Link.CANVAS) {
            app.say("That Canvas page opens on a computer.");
            return;
        }
        String named = label.length() > 0 ? label : link.site();
        final String title = named.length() > 60 ? named.substring(0, 59).trim() + "…" : named;
        List<Sheet.Choice> choices = new ArrayList<>();
        choices.add(new Sheet.Choice("Open", R.drawable.ic_open, link.kind == Link.WEB ? link.site() : null,
            () -> open(app, link, title)));
        choices.add(new Sheet.Choice("Save to Download", R.drawable.ic_folder, null,
            () -> app.withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, () -> save(app, link, title))));
        Sheet.menu(app, title, choices);
    }

    private static void open(MainActivity app, Link link, String title) {
        if (link.kind == Link.FILE) app.push(new ViewerScreen(app, new Detail.FileRef(link.id, title, -1, null), title));
        else app.push(new WebScreen(app, link, title));
    }

    private static void save(final MainActivity app, final Link link, final String title) {
        app.say("Downloading…");
        app.background(() -> {
            File kept = link.kind == Link.FILE ? app.backend.download(link.id, null) : app.backend.fetchLink(link, title);
            File saved = app.backend.saveToDownloads(kept);
            android.media.MediaScannerConnection.scanFile(app, new String[] { saved.getPath() }, null, null);
            return saved;
        }, (saved, failure) -> app.say(saved != null ? "Saved to Download/" + saved.getName() : failure));
    }
}
