package com.canvas.xp3;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.ClassData;
import com.canvas.xp3.data.Detail;
import com.canvas.xp3.data.Draft;
import com.canvas.xp3.data.Notice;
import com.canvas.xp3.data.Prefs;
import com.canvas.xp3.data.Quiz;
import com.canvas.xp3.data.Store;
import com.canvas.xp3.data.Sync;
import com.canvas.xp3.net.Canvas;
import com.canvas.xp3.net.Config;
import com.xp3.common.Http;
import com.xp3.common.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything with a network or a disk behind it, in one place.
 *
 * The screens above this are drawn on the main thread and nothing here that
 * throws IOException may be called from it; the cached reads, which do not,
 * are the ones a screen may call directly to draw what it knew last time.
 *
 * The client is rebuilt when the settings change. That is the simplest
 * correct answer to a Canvas address or a token being edited half way through
 * a session, and it is cheap: it holds nothing but a cookie that should be
 * dropped anyway when the account it belongs to has been changed underneath it.
 */
final class Backend {

    /** Teacher files larger than this are not downloaded to the phone. */
    static final long MAX_DOWNLOAD = 25L * 1024 * 1024;

    private final Store store;
    private final Http http = new Http();

    // Written on the main thread when the settings change, read on the workers.
    // Each background method takes its own copy first, so a save landing part
    // way through a fetch means that fetch finishes against the client it
    // started with rather than against half of each.
    private volatile Config config;
    private volatile Canvas canvas;
    private volatile Sync sync;

    Backend(File directory) {
        store = new Store(directory);
        // Versions with the chat kept a conversation and a model login here;
        // neither is used any more, and the login should not linger.
        store.forgetChat();
        config = store.settings();
        // Adopt a bundled setup on first launch only, so signing out of it
        // stays signed out.
        if (hasBundledSetup() && !store.hasSettings()) {
            try {
                Config bundled = Config.parse(Bundled.SETTINGS);
                store.saveSettings(bundled);
                config = bundled;
            } catch (IOException e) {
                // Could not write it down; the values are still good for this
                // run, and the next launch will simply try again.
                config = safeDefaults();
            } catch (RuntimeException e) {
                // A bundled setup this version cannot read is not a reason to
                // refuse to start.
                config = Config.blank();
            }
        }
        build();
    }

    private static Config safeDefaults() {
        try {
            return Config.parse(Bundled.SETTINGS);
        } catch (RuntimeException e) {
            return Config.blank();
        }
    }

    boolean hasBundledSetup() {
        return Bundled.SETTINGS.length() > 0;
    }

    void restoreBundledSetup() throws IOException {
        if (!hasBundledSetup()) return;
        saveSettings(Config.parse(Bundled.SETTINGS));
    }

    private void build() {
        if (!config.canvasReady()) {
            canvas = null;
            sync = null;
            return;
        }
        canvas = new Canvas(config, http);
        sync = new Sync(canvas);
    }

    Config config() {
        return config;
    }

    boolean canvasReady() {
        return config.canvasReady();
    }

    void saveSettings(Config next) throws IOException {
        store.saveSettings(next);
        config = next;
        build();
    }

    /**
     * Forgets the account. A blank settings file is written rather than none,
     * so a build with a setup baked into it stays signed out.
     */
    void signOut() {
        store.clear();
        config = Config.blank();
        try {
            store.saveSettings(config);
        } catch (IOException e) {
            // The credentials are gone from memory and the files are deleted,
            // which is what was actually asked for.
        }
        build();
    }

    private Canvas client() throws IOException {
        Canvas current = canvas;
        if (current == null) throw new IOException("Canvas is not set up yet");
        return current;
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    Prefs prefs() {
        return store.prefs();
    }

    void savePrefs(Prefs prefs) {
        try {
            store.savePrefs(prefs);
        } catch (IOException e) {
            // Kept for this session; written next time something is saved.
        }
    }

    // ------------------------------------------------------------------
    // The work list
    // ------------------------------------------------------------------

    Sync.Result cached() {
        return store.cache();
    }

    Sync.Result refresh() throws IOException, InterruptedException {
        Sync current = sync;
        if (current == null) throw new IOException("Canvas is not set up yet");
        Sync.Result result = current.run();
        save(result);
        return result;
    }

    void save(Sync.Result result) {
        try {
            store.saveCache(result);
        } catch (IOException e) {
            // A cache that could not be written is a slower launch next time.
        }
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    Detail cachedDetail(Assignment item) {
        return store.detail(item);
    }

    Detail detail(Assignment item) throws IOException, InterruptedException {
        Sync current = sync;
        if (current == null) throw new IOException("Canvas is not set up yet");
        Detail detail = current.details(item);
        store.saveDetail(item, detail);
        return detail;
    }

    ClassData cachedClass(String courseId) {
        return store.classData(courseId);
    }

    /**
     * A class's stream, classwork and people. Each part fails on its own:
     * a course with its People page hidden still shows its classwork.
     */
    ClassData classData(String courseId) throws IOException, InterruptedException {
        Canvas client = client();
        IOException failure = null;
        List<ClassData.Post> posts = new ArrayList<>();
        List<ClassData.Topic> topics = new ArrayList<>();
        List<ClassData.Person> people = new ArrayList<>();
        ClassData old = store.classData(courseId);
        try {
            posts = ClassData.posts(client.announcements(courseId));
        } catch (IOException e) {
            failure = e;
            if (old != null) posts = old.posts;
        }
        try {
            topics = ClassData.topics(client.modules(courseId));
        } catch (IOException e) {
            failure = e;
            if (old != null) topics = old.topics;
        }
        try {
            people = ClassData.people(client.people(courseId));
        } catch (IOException e) {
            if (old != null) people = old.people;
        }
        if (failure != null && posts.isEmpty() && topics.isEmpty()) throw failure;
        ClassData data = new ClassData(posts, topics, people, System.currentTimeMillis());
        store.saveClassData(courseId, data);
        return data;
    }

    List<Detail.Comment> replies(String courseId, String topicId) throws IOException, InterruptedException {
        Object entries = client().entries(courseId, topicId);
        return ClassData.replies(Json.array(entries));
    }

    Map<String, Object> page(String courseId, String pageId) throws IOException, InterruptedException {
        return client().pageById(courseId, pageId);
    }

    List<Notice> cachedNotices() {
        return store.notices();
    }

    List<Notice> notices(Sync.Result known) throws IOException, InterruptedException {
        List<Notice> notices = Notice.from(client().activity(),
            known == null ? new ArrayList<com.canvas.xp3.data.Course>() : known.courses);
        store.saveNotices(notices);
        return notices;
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    Map<String, Object> fileInfo(String fileId) throws IOException, InterruptedException {
        return client().file(fileId);
    }

    /** Where a teacher's file is or will be kept on the phone. */
    File fileOnPhone(String fileId, String name) {
        return store.download(fileId, name);
    }

    /** Downloads a teacher's file to the phone, keeping it for reading offline. */
    File download(String fileId, Http.Sending progress) throws IOException, InterruptedException {
        Canvas client = client();
        Map<String, Object> info = client.file(fileId);
        String name = Json.text(info, "display_name");
        if (name == null) name = "file";
        File target = store.download(fileId, name);
        if (target.isFile() && target.length() > 0) return target;
        File partial = new File(target.getPath() + ".part");
        OutputStream out = new FileOutputStream(partial);
        try {
            client.download(info, out, MAX_DOWNLOAD, progress);
        } finally {
            out.close();
        }
        if (!partial.renameTo(target)) throw new IOException("Could not save the file on the phone.");
        return target;
    }

    // ------------------------------------------------------------------
    // Your work
    // ------------------------------------------------------------------

    Draft draft(String key) {
        return store.draft(key);
    }

    void saveDraft(Draft draft) throws IOException {
        store.saveDraft(draft);
    }

    File draftFolder(Draft draft) {
        return store.draftFolder(draft);
    }

    void dropDraft(String key) {
        store.dropDraft(key);
    }

    List<Draft> queued() {
        return store.queued();
    }

    /** Told how far a turn-in has got, for the thin bar under an item. */
    interface Progress {
        void at(String pieceId, long done, long total);
    }

    /**
     * Turns a draft in as one Canvas submission.
     *
     * Files go up one at a time and each upload's Canvas id is written into
     * the draft as soon as it arrives, so a turn-in cut off half way picks up
     * after the last file that made it instead of sending them all again.
     * The draft is forgotten only once Canvas has the submission.
     */
    void turnIn(Draft draft, Assignment item, List<String> extensions, Progress progress)
        throws IOException, InterruptedException {
        Canvas client = client();
        Draft.Plan plan = draft.plan(item.kinds, extensions);
        if (!plan.ok()) throw new IOException(plan.problem);
        File folder = store.draftFolder(draft);
        Draft current = draft;
        if (Assignment.TEXT.equals(plan.kind)) {
            client.submitText(item.courseId, item.assignmentId, current.html(), null);
        } else if (Assignment.URL.equals(plan.kind)) {
            client.submitUrl(item.courseId, item.assignmentId, current.pieces.get(0).url, null);
        } else {
            List<String> ids = new ArrayList<>();
            for (Draft.Piece piece : draft.pieces) {
                if (piece.uploaded != null) {
                    ids.add(piece.uploaded);
                    continue;
                }
                final String pieceId = piece.id;
                String uploaded;
                if (Draft.TEXT.equals(piece.kind)) {
                    byte[] bytes = String.valueOf(piece.text).getBytes("UTF-8");
                    uploaded = client.upload(item.courseId, item.assignmentId, safeName(piece.name) + ".txt",
                        bytes.length, "text/plain", new java.io.ByteArrayInputStream(bytes),
                        (done, total) -> progress.at(pieceId, done, total));
                } else {
                    File file = new File(folder, piece.file);
                    InputStream in = new FileInputStream(file);
                    try {
                        uploaded = client.upload(item.courseId, item.assignmentId, uploadName(piece), file.length(),
                            piece.mime == null ? "application/octet-stream" : piece.mime, in,
                            (done, total) -> progress.at(pieceId, done, total));
                    } finally {
                        in.close();
                    }
                }
                ids.add(uploaded);
                current = current.with(piece.withUpload(uploaded), System.currentTimeMillis());
                store.saveDraft(current);
            }
            client.submitFiles(item.courseId, item.assignmentId, ids, null);
        }
        store.dropDraft(draft.key);
    }

    private static String uploadName(Draft.Piece piece) {
        String extension = piece.file == null ? "" : piece.file.substring(piece.file.lastIndexOf('.') + 1);
        String name = safeName(piece.name);
        return name.toLowerCase(java.util.Locale.US).endsWith("." + extension.toLowerCase(java.util.Locale.US))
            ? name : name + "." + extension;
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.length() == 0 ? "Work" : cleaned;
    }

    void comment(Assignment item, String text) throws IOException, InterruptedException {
        client().comment(item.courseId, item.assignmentId, text);
    }

    void reply(String courseId, String topicId, String text) throws IOException, InterruptedException {
        client().reply(courseId, topicId, "<p>" + text.trim().replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\n", "<br>") + "</p>");
    }

    void markDone(Assignment item, String override, boolean done) throws IOException, InterruptedException {
        client().markDone(item.assignmentId, override, done);
    }

    // ------------------------------------------------------------------
    // Quizzes
    // ------------------------------------------------------------------

    Map<String, Object> quiz(Assignment item) throws IOException, InterruptedException {
        return client().quiz(item.courseId, item.quizId);
    }

    Quiz.Attempt openAttempt(Assignment item) throws IOException, InterruptedException {
        return Quiz.open(client().quizAttempts(item.courseId, item.quizId));
    }

    Quiz.Attempt startQuiz(Assignment item) throws IOException, InterruptedException {
        Quiz.Attempt attempt = Quiz.Attempt.from(client().startQuiz(item.courseId, item.quizId));
        if (attempt == null) throw new IOException("Canvas did not start the quiz");
        return attempt;
    }

    List<Quiz.Question> questions(Quiz.Attempt attempt) throws IOException, InterruptedException {
        return Quiz.questions(client().quizQuestions(attempt.id));
    }

    void answer(Quiz.Attempt attempt, Quiz.Question question) throws IOException, InterruptedException {
        client().answer(attempt.id, attempt.number, attempt.token, question.id, question.answer());
    }

    void finishQuiz(Assignment item, Quiz.Attempt attempt) throws IOException, InterruptedException {
        client().finishQuiz(item.courseId, item.quizId, attempt.id, attempt.number, attempt.token);
    }
}
