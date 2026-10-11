package com.canvas.xp3.data;

import com.canvas.xp3.net.Config;
import com.canvas.xp3.net.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three things the app writes down, in its own private directory.
 *
 * A phone that has been in a pocket all morning should open on the work it knew
 * about this morning, not on a spinner: the network on a handset like this is
 * slow when it is there at all, and a list of assignments that is four hours
 * stale is worth immeasurably more than an empty screen that is current. So the
 * last sync is kept, shown at once, and replaced when a fresh one lands.
 *
 * Every read is defensive. A file that is missing, truncated by a battery pull
 * or written by an older version is treated as absent rather than as an error:
 * losing a cache is a slow launch, and refusing to start over one would be a
 * worse answer than the problem.
 *
 * Writes go to a temporary file and are renamed into place, so an interrupted
 * write leaves the previous good copy rather than half of a new one.
 */
public final class Store {

    private static final String SETTINGS = "settings.json";
    private static final String BUNDLED_REVISION = "bundled-setup.txt";
    private static final String CACHE = "assignments.json";
    private static final String TRANSCRIPT = "chat.json";

    /** Enough conversation to be worth keeping, little enough to load instantly. */
    private static final int MAX_TURNS = 200;
    private static final long MAX_FILE = 4000000;

    private final File directory;

    public Store(File directory) {
        this.directory = directory;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /**
     * Whether this phone has settings of its own yet.
     *
     * The difference between "no file" and "a file that says nothing is
     * configured" is what stops a bundled setup from reappearing after somebody
     * has deliberately signed out of it.
     */
    public boolean hasSettings() {
        return new File(directory, SETTINGS).isFile();
    }

    public Config settings() {
        String json = read(SETTINGS);
        if (json == null) return Config.blank();
        try {
            return Config.parse(json);
        } catch (RuntimeException e) {
            // A settings file this version cannot read is not a reason to
            // refuse to start; the Settings screen will show it as unset.
            return Config.blank();
        }
    }

    public void saveSettings(Config config) throws IOException {
        write(SETTINGS, config.toJson(), true);
    }

    /** The local OAuth bundle already adopted by this installation, if any. */
    public String bundledRevision() {
        String revision = read(BUNDLED_REVISION);
        return revision == null ? "" : revision.trim();
    }

    public void saveBundledRevision(String revision) throws IOException {
        write(BUNDLED_REVISION, revision == null ? "" : revision.trim(), false);
    }

    // ------------------------------------------------------------------
    // The last sync
    // ------------------------------------------------------------------

    public Sync.Result cache() {
        String json = read(CACHE);
        if (json == null) return null;
        try {
            Map<String, Object> root = Json.object(Json.parse(json));
            List<Assignment> work = new ArrayList<>();
            for (Object entry : Json.children(root, "work")) {
                Assignment item = assignment(Json.object(entry));
                if (item != null) work.add(item);
            }
            List<String> unavailable = new ArrayList<>();
            for (Object entry : Json.children(root, "unavailable")) {
                if (entry instanceof String) unavailable.add((String) entry);
            }
            Double at = Json.number(root, "fetchedAt");
            return new Sync.Result(work, unavailable, at == null ? 0L : (long) at.doubleValue());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void saveCache(Sync.Result result) throws IOException {
        List<Object> work = new ArrayList<>();
        for (Assignment item : result.work) work.add(fields(item));
        write(CACHE, Json.write(Json.map(
            "fetchedAt", Long.valueOf(result.fetchedAt),
            "unavailable", new ArrayList<Object>(result.unavailable),
            "work", work)), false);
    }

    private static Map<String, Object> fields(Assignment item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("courseId", item.courseId);
        out.put("assignmentId", item.assignmentId);
        out.put("title", item.title);
        out.put("course", item.course);
        out.put("due", item.due);
        out.put("points", Double.valueOf(item.points));
        out.put("missing", Boolean.valueOf(item.flaggedMissing));
        out.put("done", Boolean.valueOf(item.done));
        out.put("turnedIn", item.turnedIn);
        out.put("score", item.score);
        out.put("excused", Boolean.valueOf(item.excused));
        // Kept so an assignment read once can be read again with no signal.
        out.put("details", item.details());
        return out;
    }

    private static Assignment assignment(Map<String, Object> record) {
        String courseId = Json.text(record, "courseId");
        String assignmentId = Json.text(record, "assignmentId");
        String title = Json.text(record, "title");
        if (courseId == null || assignmentId == null || title == null) return null;
        Double points = Json.number(record, "points");
        Double due = Json.number(record, "due");
        Double turnedIn = Json.number(record, "turnedIn");
        String course = Json.text(record, "course");
        Assignment item = new Assignment(courseId, assignmentId, title,
            course == null ? "" : course,
            due == null ? null : Long.valueOf((long) due.doubleValue()),
            points == null ? 0 : points.doubleValue(),
            Json.flag(record, "missing"), Json.flag(record, "done"),
            turnedIn == null ? null : Long.valueOf((long) turnedIn.doubleValue()),
            Json.number(record, "score"), Json.flag(record, "excused"));
        item.setDetails(Json.text(record, "details"));
        return item;
    }

    // ------------------------------------------------------------------
    // The transcript
    // ------------------------------------------------------------------

    public List<ChatTurn> transcript() {
        List<ChatTurn> turns = new ArrayList<>();
        String json = read(TRANSCRIPT);
        if (json == null) return turns;
        try {
            for (Object entry : Json.children(Json.object(Json.parse(json)), "turns")) {
                Map<String, Object> turn = Json.object(entry);
                String text = Json.text(turn, "text");
                Double at = Json.number(turn, "at");
                if (text == null || at == null) continue;
                turns.add(
                    Json.flag(turn, "compacted")
                        ? ChatTurn.compaction(text, (long) at.doubleValue())
                        : new ChatTurn(Json.flag(turn, "me"), text, (long) at.doubleValue())
                );
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        return turns;
    }

    public void saveTranscript(List<ChatTurn> turns) throws IOException {
        List<Object> saved = new ArrayList<>();
        int from = Math.max(0, turns.size() - MAX_TURNS);
        for (int i = from; i < turns.size(); i++) {
            ChatTurn turn = turns.get(i);
            saved.add(Json.map(
                "me", Boolean.valueOf(turn.fromUser),
                "text", turn.text,
                "at", Long.valueOf(turn.when),
                "compacted", Boolean.valueOf(turn.isCompaction())
            ));
        }
        write(TRANSCRIPT, Json.write(Json.map("turns", saved)), false);
    }

    /** Everything written about this account, for Settings' sign-out. */
    public void clear() {
        new File(directory, SETTINGS).delete();
        new File(directory, CACHE).delete();
        new File(directory, TRANSCRIPT).delete();
        // Keep BUNDLED_REVISION: signing out must not silently restore the
        // credentials from this same APK on the next launch.
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /*
     * The two file methods are synchronized because more than one thread writes
     * here: a sync finishing and an assignment's instructions arriving both
     * save the work list, and they can land at the same moment. They share a
     * temporary file name, so without this one write's rename could pick up the
     * other's half-written bytes.
     */

    private synchronized String read(String name) {
        File file = new File(directory, name);
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_FILE) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return new String(out.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            close(in);
        }
    }

    private synchronized void write(String name, String content, boolean secret) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not open the app's storage");
        }
        File target = new File(directory, name);
        File temporary = new File(directory, name + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(temporary);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
        } finally {
            close(out);
        }
        if (secret) {
            // Belt and braces: the app's own directory is already private, but a
            // file holding a session token should not be readable even if the
            // directory's mode is ever loosened by a backup or a restore.
            temporary.setReadable(false, false);
            temporary.setReadable(true, true);
            temporary.setWritable(false, false);
            temporary.setWritable(true, true);
        }
        if (!temporary.renameTo(target)) {
            // Some filesystems will not replace an existing file on rename.
            if (!target.delete() || !temporary.renameTo(target)) {
                temporary.delete();
                throw new IOException("Could not save to the app's storage");
            }
        }
    }

    private static void close(java.io.Closeable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // A close that fails on a stream already written is not a new failure.
        }
    }
}
