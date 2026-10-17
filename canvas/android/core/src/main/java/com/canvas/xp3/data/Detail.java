package com.canvas.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an assignment's page shows beyond its row in a list: the instructions,
 * the teacher's files, the rubric, and the student's own submission with the
 * teacher's comments on it.
 *
 * Canvas sends none of this with a list of assignments, so it is fetched when
 * the page is opened and written down beside the list, which is what lets an
 * assignment read once be read again with no signal.
 */
public final class Detail {

    /** A file named by Canvas: the teacher's, attached to a submission, or on a comment. */
    public static final class FileRef {
        public final String id;
        public final String name;
        /** Bytes, or -1 when Canvas has not said yet. */
        public final long size;
        /** The MIME type, or null. */
        public final String type;

        public FileRef(String id, String name, long size, String type) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.type = type;
        }

        /** "PDF", "DOCX", "JPG": the extension, which is what a person calls a file's kind. */
        public String kind() {
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) return "File";
            return name.substring(dot + 1).toUpperCase(java.util.Locale.US);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("name", name);
            out.put("size", Long.valueOf(size));
            out.put("type", type);
            return out;
        }

        static FileRef fromMap(Map<String, Object> map) {
            String id = Json.text(map, "id");
            String name = Json.text(map, "name");
            if (id == null || name == null) return null;
            Double size = Json.number(map, "size");
            return new FileRef(id, name, size == null ? -1 : (long) size.doubleValue(), Json.text(map, "type"));
        }

        /** A Canvas file or attachment record. */
        static FileRef fromCanvas(Map<String, Object> record) {
            Object id = record.get("id");
            if (!(id instanceof Number)) return null;
            String name = Json.text(record, "display_name");
            if (name == null) name = Json.text(record, "filename");
            if (name == null) name = "File";
            Double size = Json.number(record, "size");
            return new FileRef(Long.toString(((Number) id).longValue()), name,
                size == null ? -1 : (long) size.doubleValue(), Json.text(record, "content-type"));
        }
    }

    /** A private comment on the submission, from the student or the teacher. */
    public static final class Comment {
        public final String author;
        public final boolean mine;
        public final String text;
        public final long at;

        public Comment(String author, boolean mine, String text, long at) {
            this.author = author;
            this.mine = mine;
            this.text = text;
            this.at = at;
        }
    }

    /** One rubric row: what is assessed, the level reached, and its points. */
    public static final class Criterion {
        public final String name;
        public final double points;
        /** The rating the teacher picked, or null before it is marked. */
        public final String level;
        public final Double earned;

        public Criterion(String name, double points, String level, Double earned) {
            this.name = name;
            this.points = points;
            this.level = level;
            this.earned = earned;
        }
    }

    public final String body;
    public final List<FileRef> files;
    public final List<Criterion> rubric;
    public final List<String> extensions;
    /** What was last turned in: its type, typed text, link and files. */
    public final String submittedType;
    public final String submittedText;
    public final String submittedUrl;
    public final List<FileRef> submittedFiles;
    public final List<Comment> comments;
    /** False when the submission could not be read, so the page can say so. */
    public final boolean submissionKnown;
    public final long fetchedAt;

    public Detail(String body, List<FileRef> files, List<Criterion> rubric, List<String> extensions,
                  String submittedType, String submittedText, String submittedUrl, List<FileRef> submittedFiles,
                  List<Comment> comments, boolean submissionKnown, long fetchedAt) {
        this.body = body;
        this.files = files;
        this.rubric = rubric;
        this.extensions = extensions;
        this.submittedType = submittedType;
        this.submittedText = submittedText;
        this.submittedUrl = submittedUrl;
        this.submittedFiles = submittedFiles;
        this.comments = comments;
        this.submissionKnown = submissionKnown;
        this.fetchedAt = fetchedAt;
    }

    private static final Pattern LINK =
        Pattern.compile("<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FILE = Pattern.compile("/files/(\\d{1,18})(?:[/?#]|$)");

    /**
     * The teacher's files: every link in the instructions that points at a
     * Canvas file, once each, named by the link's own text. That is how
     * Canvas's editor attaches a file to an assignment, and the link text is
     * the file's name unless the teacher changed it.
     */
    static List<FileRef> linkedFiles(String html) {
        List<FileRef> found = new ArrayList<>();
        if (html == null) return found;
        Matcher link = LINK.matcher(html);
        while (link.find()) {
            Matcher file = FILE.matcher(link.group(1));
            if (!file.find()) continue;
            String id = file.group(1);
            boolean seen = false;
            for (FileRef ref : found) seen |= ref.id.equals(id);
            if (seen) continue;
            String label = Html.toMarkdown(link.group(2)).replaceAll("[*_`\\\\]", "").trim();
            if (label.length() == 0 || label.length() > 120) label = "File";
            found.add(new FileRef(id, label, -1, null));
        }
        return found;
    }

    static Detail from(Map<String, Object> assignment, Map<String, Object> submission, long now) {
        String description = Json.text(assignment, "description");
        String body = Html.toMarkdown(description);
        String locked = Json.text(assignment, "lock_explanation");
        if (locked != null && Json.flag(assignment, "locked_for_user")) {
            body = body.trim().length() == 0 ? Html.toMarkdown(locked) : Html.toMarkdown(locked) + "\n\n" + body;
        }

        List<String> extensions = new ArrayList<>();
        for (Object kind : Json.children(assignment, "allowed_extensions")) {
            if (kind instanceof String) extensions.add(((String) kind).toLowerCase(java.util.Locale.US));
        }

        Map<String, Object> marks = submission == null
            ? new LinkedHashMap<String, Object>()
            : Json.child(submission, "rubric_assessment");
        List<Criterion> rubric = new ArrayList<>();
        for (Object entry : Json.children(assignment, "rubric")) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> criterion = Json.object(entry);
            String name = Json.text(criterion, "description");
            Double points = Json.number(criterion, "points");
            Map<String, Object> mark = Json.child(marks, String.valueOf(criterion.get("id")));
            String level = null;
            String rating = Json.text(mark, "rating_id");
            for (Object r : Json.children(criterion, "ratings")) {
                if (r instanceof Map && rating != null && rating.equals(Json.text(Json.object(r), "id"))) {
                    level = Json.text(Json.object(r), "description");
                }
            }
            rubric.add(new Criterion(name == null ? "Criterion" : name,
                points == null ? 0 : points.doubleValue(), level, Json.number(mark, "points")));
        }

        String type = null;
        String text = null;
        String url = null;
        List<FileRef> attached = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();
        if (submission != null) {
            if (submission.get("submitted_at") instanceof String) {
                type = Json.text(submission, "submission_type");
                String html = Json.text(submission, "body");
                if (html != null && html.trim().length() > 0) text = Html.toMarkdown(html);
                url = Json.text(submission, "url");
                for (Object entry : Json.children(submission, "attachments")) {
                    if (!(entry instanceof Map)) continue;
                    FileRef ref = FileRef.fromCanvas(Json.object(entry));
                    if (ref != null) attached.add(ref);
                }
            }
            Object self = submission.get("user_id");
            for (Object entry : Json.children(submission, "submission_comments")) {
                if (!(entry instanceof Map)) continue;
                Map<String, Object> comment = Json.object(entry);
                String said = Json.text(comment, "comment");
                Long at = Sync.time(Json.text(comment, "created_at"));
                if (said == null || at == null) continue;
                Object author = comment.get("author_id");
                boolean mine = self instanceof Number && author instanceof Number
                    && ((Number) self).longValue() == ((Number) author).longValue();
                String name = Json.text(comment, "author_name");
                comments.add(new Comment(name == null ? "Teacher" : name, mine, said.trim(), at.longValue()));
            }
        }
        return new Detail(body, linkedFiles(description), rubric, extensions, type, text, url, attached,
            comments, submission != null, now);
    }

    // ------------------------------------------------------------------
    // Written down beside the list
    // ------------------------------------------------------------------

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("body", body);
        out.put("files", refs(files));
        List<Object> criteria = new ArrayList<>();
        for (Criterion c : rubric) {
            criteria.add(Json.map("name", c.name, "points", Double.valueOf(c.points), "level", c.level,
                "earned", c.earned));
        }
        out.put("rubric", criteria);
        out.put("extensions", new ArrayList<Object>(extensions));
        out.put("submittedType", submittedType);
        out.put("submittedText", submittedText);
        out.put("submittedUrl", submittedUrl);
        out.put("submittedFiles", refs(submittedFiles));
        List<Object> said = new ArrayList<>();
        for (Comment c : comments) {
            said.add(Json.map("author", c.author, "mine", Boolean.valueOf(c.mine), "text", c.text,
                "at", Long.valueOf(c.at)));
        }
        out.put("comments", said);
        out.put("submissionKnown", Boolean.valueOf(submissionKnown));
        out.put("fetchedAt", Long.valueOf(fetchedAt));
        return out;
    }

    private static List<Object> refs(List<FileRef> files) {
        List<Object> out = new ArrayList<>();
        for (FileRef ref : files) out.add(ref.toMap());
        return out;
    }

    private static List<FileRef> refs(Map<String, Object> map, String key) {
        List<FileRef> out = new ArrayList<>();
        for (Object entry : Json.children(map, key)) {
            if (!(entry instanceof Map)) continue;
            FileRef ref = FileRef.fromMap(Json.object(entry));
            if (ref != null) out.add(ref);
        }
        return out;
    }

    /** Null for anything this version cannot read, which then reads as not fetched yet. */
    static Detail fromMap(Map<String, Object> map) {
        String body = Json.text(map, "body");
        if (body == null) return null;
        List<Criterion> rubric = new ArrayList<>();
        for (Object entry : Json.children(map, "rubric")) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> c = Json.object(entry);
            Double points = Json.number(c, "points");
            rubric.add(new Criterion(String.valueOf(Json.text(c, "name")), points == null ? 0 : points.doubleValue(),
                Json.text(c, "level"), Json.number(c, "earned")));
        }
        List<String> extensions = new ArrayList<>();
        for (Object entry : Json.children(map, "extensions")) {
            if (entry instanceof String) extensions.add((String) entry);
        }
        List<Comment> comments = new ArrayList<>();
        for (Object entry : Json.children(map, "comments")) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> c = Json.object(entry);
            Double at = Json.number(c, "at");
            String text = Json.text(c, "text");
            if (at == null || text == null) continue;
            comments.add(new Comment(String.valueOf(Json.text(c, "author")), Json.flag(c, "mine"), text,
                (long) at.doubleValue()));
        }
        Double at = Json.number(map, "fetchedAt");
        return new Detail(body, refs(map, "files"), rubric, extensions, Json.text(map, "submittedType"),
            Json.text(map, "submittedText"), Json.text(map, "submittedUrl"), refs(map, "submittedFiles"), comments,
            Json.flag(map, "submissionKnown"), at == null ? 0 : (long) at.doubleValue());
    }
}
