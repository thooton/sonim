package com.canvas.xp3;

import android.os.Environment;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Draft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Choose a file (D6) from the phone's storage or its SD card, newest first,
 * since the file wanted is usually the one just made.
 *
 * The chosen file is copied into the draft, so moving or deleting the
 * original afterwards cannot take it out of work the student has queued.
 */
final class PickerScreen extends Screen {

    private final Assignment work;
    private final boolean imagesOnly;
    private File folder;
    private boolean byName;

    PickerScreen(MainActivity app, Assignment work) {
        this(app, work, false);
    }

    private PickerScreen(MainActivity app, Assignment work, boolean imagesOnly) {
        super(app);
        this.work = work;
        this.imagesOnly = imagesOnly;
    }

    /** The camera's Gallery: pictures only, handed back as a file. */
    static PickerScreen images(MainActivity app, Assignment work) {
        return new PickerScreen(app, work, true);
    }

    @Override
    String left() {
        return "Back";
    }

    @Override
    String right() {
        return byName ? "Newest" : "A to Z";
    }

    @Override
    void onRight() {
        byName = !byName;
        app.redraw();
    }

    @Override
    boolean onKey(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        if (code == android.view.KeyEvent.KEYCODE_MENU || code == android.view.KeyEvent.KEYCODE_SOFT_LEFT) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) onBackPressedHere();
            return true;
        }
        return false;
    }

    private void onBackPressedHere() {
        if (!onBack()) app.pop();
    }

    @Override
    boolean onBack() {
        if (folder == null) return false;
        File parent = folder.getParentFile();
        File top = null;
        for (File root : roots()) {
            if (folder.equals(root)) top = root;
        }
        folder = top != null || parent == null ? null : parent;
        app.redraw();
        return true;
    }

    /** The phone's shared storage, then any SD card. */
    private static List<File> roots() {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStorageDirectory());
        File[] mounted = new File("/storage").listFiles();
        if (mounted != null) {
            for (File card : mounted) {
                String name = card.getName();
                if (name.equals("emulated") || name.equals("self") || !card.canRead()) continue;
                out.add(card);
            }
        }
        return out;
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        root.addView(parts.nav(folder == null ? "Add work" : "Back", (String) null), Views.wide());
        TextView heading = v.screenTitle(imagesOnly ? "Choose a picture" : "Choose a file");
        heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16 * look.scale);
        v.pad(heading, 12, 0, 12, 0);
        root.addView(heading, Views.wide());
        TextView where = v.meta(path());
        v.pad(where, 12, 1, 12, 4);
        root.addView(where, Views.wide());
        LinearLayout list = parts.page(this, root);

        if (folder == null) {
            List<File> places = roots();
            for (int i = 0; i < places.size(); i++) {
                final File place = places.get(i);
                String name = i == 0 ? "Phone" : "SD card";
                list.addView(parts.tiled("dir:" + place.getPath(), "Open", R.drawable.ic_folder, look.paper, look.ink,
                    name, count(place), null, () -> {
                        folder = place;
                        app.redraw();
                    }), Views.wide());
            }
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads.isDirectory()) {
                list.addView(parts.tiled("dir:" + downloads.getPath(), "Open", R.drawable.ic_folder, look.paper,
                    look.ink, "Downloads", count(downloads), null, () -> {
                        folder = downloads;
                        app.redraw();
                    }), Views.wide());
            }
            return root;
        }

        File[] found = folder.listFiles();
        List<File> folders = new ArrayList<>();
        List<File> files = new ArrayList<>();
        if (found != null) {
            for (File file : found) {
                if (file.getName().startsWith(".")) continue;
                if (file.isDirectory()) folders.add(file);
                else if (!imagesOnly || isImage(file)) files.add(file);
            }
        }
        java.util.Comparator<File> order = byName
            ? (a, b) -> a.getName().compareToIgnoreCase(b.getName())
            : (a, b) -> Long.compare(b.lastModified(), a.lastModified());
        java.util.Collections.sort(folders, order);
        java.util.Collections.sort(files, order);
        for (final File dir : folders) {
            list.addView(parts.tiled("dir:" + dir.getPath(), "Open", R.drawable.ic_folder, look.paper, look.ink,
                dir.getName(), count(dir), null, () -> {
                    folder = dir;
                    app.redraw();
                }), Views.wide());
        }
        for (final File file : files) {
            String meta = Parts.size(file.length()) + " · " + Dates.shortDay(file.lastModified()).split(", ")[1];
            Item row = parts.tiled("file:" + file.getPath(), "Select", R.drawable.ic_doc, look.paper, look.ink,
                file.getName(), meta, null, () -> choose(file));
            list.addView(row, Views.wide());
        }
        if (folders.isEmpty() && files.isEmpty()) {
            list.addView(parts.empty(0, imagesOnly ? "No pictures here" : "Nothing here", null),
                v.margins(Views.wide(), 0, 30, 0, 0));
        }
        return root;
    }

    private String path() {
        if (folder == null) return "Phone storage";
        String base = Environment.getExternalStorageDirectory().getPath();
        String path = folder.getPath();
        if (path.startsWith(base)) path = "Phone" + path.substring(base.length());
        else if (path.startsWith("/storage/")) path = "SD card" + path.substring(path.indexOf('/', 9) < 0
            ? path.length() : path.indexOf('/', 9));
        return path.replace("/", " › ");
    }

    private static String count(File dir) {
        String[] names = dir.list();
        int n = names == null ? 0 : names.length;
        return n + (n == 1 ? " item" : " items");
    }

    private static boolean isImage(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp");
    }

    private void choose(final File file) {
        if (imagesOnly) {
            app.finishWith(file);
            return;
        }
        if (file.length() > 50L * 1024 * 1024) {
            app.say("That file is too large to send from the phone.");
            return;
        }
        app.background(() -> copy(file), (piece, problem) -> {
            if (piece == null) {
                app.say("The file couldn't be copied.");
                return;
            }
            app.finishWith(piece);
        });
    }

    private Draft.Piece copy(File source) throws IOException {
        Draft draft = app.backend.draft(work.id);
        File folder = app.backend.draftFolder(draft);
        String name = source.getName();
        File target = new File(folder, "file-" + System.currentTimeMillis() + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        InputStream in = new FileInputStream(source);
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return Draft.Piece.file("f" + System.currentTimeMillis(), Draft.FILE, name, target.getName(),
            mime == null ? "application/octet-stream" : mime, target.length(), 0);
    }

    @Override
    boolean globalOptions() {
        return false;
    }
}
