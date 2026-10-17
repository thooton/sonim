package com.canvas.xp3.data;

import com.canvas.xp3.net.Config;
import com.xp3.common.Disk;
import com.xp3.common.Json;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the app writes down, in its own private directory: the settings and
 * preferences, the last sync, what each opened page fetched, and the work
 * the student has saved but not turned in.
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
    private static final String CACHE = "assignments.json";
    private static final String PREFS = "prefs.json";
    private static final String NOTICES = "notices.json";
    private static final String DETAILS = "details";
    private static final String CLASSES = "classes";
    private static final String DRAFTS = "drafts";
    private static final String DOWNLOADS = "downloads";

    /**
     * What older versions kept for the chat: the conversation, and the marker
     * for a bundled Claude login. Nothing reads them now.
     */
    private static final String[] RETIRED = {
        "chat.json", "chat.json.tmp", "bundled-setup.txt", "bundled-setup.txt.tmp",
    };
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

    /**
     * Removes what older versions kept for the chat: the conversation, and the
     * model provider's login inside the settings file. The login is a live
     * credential that nothing uses any more, so it should not stay on the
     * phone. The rest of the settings file is written back untouched, and a
     * file that cannot be read is left alone rather than replaced.
     */
    public void forgetChat() {
        for (String name : RETIRED) new File(directory, name).delete();
        String json = read(SETTINGS);
        if (json == null) return;
        try {
            Map<String, Object> root = Json.object(Json.parse(json));
            if (!root.containsKey("openai")) return;
            root.remove("openai");
            write(SETTINGS, Json.write(root), true);
        } catch (IOException e) {
            // Tried again on the next launch.
        } catch (RuntimeException e) {
            // Not a settings file this version can read; settings() already
            // treats it as blank.
        }
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    public Prefs prefs() {
        String json = read(PREFS);
        return json == null ? Prefs.defaults() : Prefs.parse(json);
    }

    public void savePrefs(Prefs prefs) throws IOException {
        write(PREFS, prefs.toJson(), false);
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
            List<Course> courses = new ArrayList<>();
            for (Object entry : Json.children(root, "courses")) {
                Course course = course(Json.object(entry));
                if (course != null) courses.add(course);
            }
            List<String> unavailable = new ArrayList<>();
            for (Object entry : Json.children(root, "unavailable")) {
                if (entry instanceof String) unavailable.add((String) entry);
            }
            Map<String, String> overrides = new HashMap<>();
            Set<String> checked = new HashSet<>();
            for (Object entry : Json.children(root, "overrides")) {
                Map<String, Object> o = Json.object(entry);
                String assignment = Json.text(o, "assignment");
                String id = Json.text(o, "id");
                if (assignment == null || id == null) continue;
                overrides.put(assignment, id);
                if (Json.flag(o, "done")) checked.add(assignment);
            }
            Double at = Json.number(root, "fetchedAt");
            return new Sync.Result(work, courses, unavailable, at == null ? 0L : (long) at.doubleValue(),
                overrides, checked);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void saveCache(Sync.Result result) throws IOException {
        List<Object> work = new ArrayList<>();
        for (Assignment item : result.work) work.add(fields(item));
        List<Object> courses = new ArrayList<>();
        for (Course course : result.courses) courses.add(fields(course));
        List<Object> overrides = new ArrayList<>();
        for (Map.Entry<String, String> o : result.overrides.entrySet()) {
            overrides.add(Json.map("assignment", o.getKey(), "id", o.getValue(),
                "done", Boolean.valueOf(result.checked.contains(o.getKey()))));
        }
        write(CACHE, Json.write(Json.map(
            "fetchedAt", Long.valueOf(result.fetchedAt),
            "unavailable", new ArrayList<Object>(result.unavailable),
            "courses", courses,
            "overrides", overrides,
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
        out.put("kinds", new ArrayList<Object>(item.kinds));
        out.put("quizId", item.quizId);
        out.put("topicId", item.topicId);
        out.put("returned", Boolean.valueOf(item.returned));
        out.put("gradedAt", item.gradedAt);
        out.put("grade", item.grade);
        out.put("attemptsAllowed", Integer.valueOf(item.attemptsAllowed));
        out.put("attempts", Integer.valueOf(item.attempts));
        out.put("locked", Boolean.valueOf(item.locked));
        return out;
    }

    private static Assignment assignment(Map<String, Object> record) {
        String courseId = Json.text(record, "courseId");
        String assignmentId = Json.text(record, "assignmentId");
        String title = Json.text(record, "title");
        if (courseId == null || assignmentId == null || title == null) return null;
        Double points = Json.number(record, "points");
        Double turnedIn = Json.number(record, "turnedIn");
        String course = Json.text(record, "course");
        Double score = Json.number(record, "score");
        List<String> kinds = new ArrayList<>();
        for (Object kind : Json.children(record, "kinds")) {
            if (kind instanceof String) kinds.add((String) kind);
        }
        Double allowed = Json.number(record, "attemptsAllowed");
        Double attempts = Json.number(record, "attempts");
        // A cache from before this version has no "returned"; a score was
        // the same fact then.
        boolean returned = record.containsKey("returned") ? Json.flag(record, "returned") : score != null;
        return new Assignment(courseId, assignmentId, title,
            course == null ? "" : course,
            millis(record, "due"),
            points == null ? 0 : points.doubleValue(),
            Json.flag(record, "missing"), Json.flag(record, "done"),
            turnedIn == null ? null : Long.valueOf((long) turnedIn.doubleValue()),
            score, Json.flag(record, "excused"),
            kinds, Json.text(record, "quizId"), Json.text(record, "topicId"), returned,
            millis(record, "gradedAt"), Json.text(record, "grade"),
            allowed == null ? -1 : (int) allowed.doubleValue(),
            attempts == null ? 0 : (int) attempts.doubleValue(),
            Json.flag(record, "locked"));
    }

    private static Long millis(Map<String, Object> record, String key) {
        Double value = Json.number(record, key);
        return value == null ? null : Long.valueOf((long) value.doubleValue());
    }

    private static Map<String, Object> fields(Course course) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", course.id);
        out.put("title", course.title);
        out.put("code", course.code);
        out.put("term", course.term);
        out.put("teachers", new ArrayList<Object>(course.teachers));
        out.put("color", Integer.valueOf(course.color));
        out.put("score", course.score);
        out.put("grade", course.grade);
        return out;
    }

    private static Course course(Map<String, Object> record) {
        String id = Json.text(record, "id");
        String title = Json.text(record, "title");
        String code = Json.text(record, "code");
        if (id == null || title == null || code == null) return null;
        List<String> teachers = new ArrayList<>();
        for (Object name : Json.children(record, "teachers")) {
            if (name instanceof String) teachers.add((String) name);
        }
        Double color = Json.number(record, "color");
        return new Course(id, title, code, Json.text(record, "term"), teachers,
            color == null ? 0 : Math.abs((int) color.doubleValue()) % Course.COLORS,
            Json.number(record, "score"), Json.text(record, "grade"));
    }

    // ------------------------------------------------------------------
    // What is fetched when a page is opened
    // ------------------------------------------------------------------

    public Detail detail(Assignment item) {
        String json = read(DETAILS + "/" + item.courseId + "-" + item.assignmentId + ".json");
        if (json == null) return null;
        try {
            return Detail.fromMap(Json.object(Json.parse(json)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void saveDetail(Assignment item, Detail detail) throws IOException {
        write(DETAILS + "/" + item.courseId + "-" + item.assignmentId + ".json", Json.write(detail.toMap()), false);
    }

    public ClassData classData(String courseId) {
        String json = read(CLASSES + "/" + courseId + ".json");
        if (json == null) return null;
        try {
            return ClassData.fromMap(Json.object(Json.parse(json)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void saveClassData(String courseId, ClassData data) throws IOException {
        write(CLASSES + "/" + courseId + ".json", Json.write(data.toMap()), false);
    }

    public List<Notice> notices() {
        List<Notice> out = new ArrayList<>();
        String json = read(NOTICES);
        if (json == null) return out;
        try {
            for (Object entry : Json.array(Json.parse(json))) {
                Notice notice = Notice.fromMap(Json.object(entry));
                if (notice != null) out.add(notice);
            }
        } catch (RuntimeException e) {
            // An unreadable list is an empty one until the next fetch.
        }
        return out;
    }

    public void saveNotices(List<Notice> notices) throws IOException {
        List<Object> out = new ArrayList<>();
        for (Notice notice : notices) out.add(notice.toMap());
        write(NOTICES, Json.write(out), false);
    }

    // ------------------------------------------------------------------
    // Work saved on the phone
    // ------------------------------------------------------------------

    public Draft draft(String key) {
        String json = read(DRAFTS + "/" + Draft.empty(key).folder() + ".json");
        if (json == null) return Draft.empty(key);
        try {
            Draft draft = Draft.fromMap(Json.object(Json.parse(json)));
            return draft == null || !draft.key.equals(key) ? Draft.empty(key) : draft;
        } catch (RuntimeException e) {
            return Draft.empty(key);
        }
    }

    public void saveDraft(Draft draft) throws IOException {
        write(DRAFTS + "/" + draft.folder() + ".json", Json.write(draft.toMap()), false);
    }

    /** The folder a draft's photos, recordings and files are kept in. */
    public File draftFolder(Draft draft) {
        File folder = new File(new File(directory, DRAFTS), draft.folder());
        folder.mkdirs();
        return folder;
    }

    /** Forgets a draft once it is turned in, files and all. */
    public void dropDraft(String key) {
        Draft draft = Draft.empty(key);
        new File(new File(directory, DRAFTS), draft.folder() + ".json").delete();
        File folder = new File(new File(directory, DRAFTS), draft.folder());
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) file.delete();
        }
        folder.delete();
    }

    /** Every draft waiting for a signal to be turned in. */
    public List<Draft> queued() {
        List<Draft> out = new ArrayList<>();
        File[] files = new File(directory, DRAFTS).listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".json")) continue;
            String json = read(DRAFTS + "/" + file.getName());
            if (json == null) continue;
            try {
                Draft draft = Draft.fromMap(Json.object(Json.parse(json)));
                if (draft != null && draft.queued) out.add(draft);
            } catch (RuntimeException e) {
                // Not a draft this version can read.
            }
        }
        return out;
    }

    /** Where a teacher's file is kept once downloaded. */
    public File download(String fileId, String name) {
        File folder = new File(directory, DOWNLOADS);
        folder.mkdirs();
        String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
        return new File(folder, fileId + "-" + safe);
    }

    /** Everything written about this account, for Settings' sign-out. */
    public void clear() {
        new File(directory, SETTINGS).delete();
        new File(directory, CACHE).delete();
        new File(directory, NOTICES).delete();
        String[] folders = { DETAILS, CLASSES, DRAFTS, DOWNLOADS };
        for (String name : folders) delete(new File(directory, name));
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        file.delete();
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
        return Disk.read(new File(directory, name), MAX_FILE);
    }

    private synchronized void write(String name, String content, boolean secret) throws IOException {
        Disk.write(new File(directory, name), content, secret);
    }
}
