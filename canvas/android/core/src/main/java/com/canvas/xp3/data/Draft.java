package com.canvas.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The student's work on one assignment, saved on the phone until it is
 * turned in: typed answers, photographed pages, recordings, files and links.
 *
 * Everything is written here first and sent afterwards, so nothing a student
 * makes depends on the signal holding while they make it. A draft that has
 * been turned in with no signal is `queued`, and the uploader finishes it
 * when the phone is back online.
 */
public final class Draft {

    public static final String TEXT = "text";
    public static final String PHOTOS = "photos";
    public static final String AUDIO = "audio";
    public static final String FILE = "file";
    public static final String LINK = "link";

    /** One thing added under Your work. */
    public static final class Piece {
        public final String id;
        public final String kind;
        public final String name;
        /** The typed answer, for TEXT. */
        public final String text;
        /** The address, for LINK. */
        public final String url;
        /** The saved file's name inside the draft's folder, for the rest. */
        public final String file;
        public final String mime;
        public final long size;
        /** Pages for PHOTOS, milliseconds for AUDIO. */
        public final long amount;
        /** Canvas's file id once uploaded, so a retry does not send it twice. */
        public final String uploaded;

        public Piece(String id, String kind, String name, String text, String url, String file, String mime,
                     long size, long amount, String uploaded) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.text = text;
            this.url = url;
            this.file = file;
            this.mime = mime;
            this.size = size;
            this.amount = amount;
            this.uploaded = uploaded;
        }

        public static Piece text(String id, String name, String text) {
            return new Piece(id, TEXT, name, text, null, null, "text/plain", 0, 0, null);
        }

        public static Piece link(String id, String name, String url) {
            return new Piece(id, LINK, name, null, url, null, null, 0, 0, null);
        }

        public static Piece file(String id, String kind, String name, String file, String mime, long size,
                                 long amount) {
            return new Piece(id, kind, name, null, null, file, mime, size, amount, null);
        }

        public Piece renamed(String value) {
            return new Piece(id, kind, value, text, url, file, mime, size, amount, uploaded);
        }

        public Piece withText(String value) {
            return new Piece(id, kind, name, value, url, file, mime, size, amount, null);
        }

        public Piece withUpload(String canvasId) {
            return new Piece(id, kind, name, text, url, file, mime, size, amount, canvasId);
        }

        public boolean isFile() {
            return PHOTOS.equals(kind) || AUDIO.equals(kind) || FILE.equals(kind);
        }

        /** Words in a typed answer, as the editor counts them. */
        public int words() {
            return Draft.words(text);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("id", id);
            out.put("kind", kind);
            out.put("name", name);
            out.put("text", text);
            out.put("url", url);
            out.put("file", file);
            out.put("mime", mime);
            out.put("size", Long.valueOf(size));
            out.put("amount", Long.valueOf(amount));
            out.put("uploaded", uploaded);
            return out;
        }

        static Piece fromMap(Map<String, Object> map) {
            String id = Json.text(map, "id");
            String kind = Json.text(map, "kind");
            String name = Json.text(map, "name");
            if (id == null || kind == null || name == null) return null;
            Double size = Json.number(map, "size");
            Double amount = Json.number(map, "amount");
            return new Piece(id, kind, name, Json.text(map, "text"), Json.text(map, "url"), Json.text(map, "file"),
                Json.text(map, "mime"), size == null ? 0 : (long) size.doubleValue(),
                amount == null ? 0 : (long) amount.doubleValue(), Json.text(map, "uploaded"));
        }
    }

    /** "142:9013", the assignment this is for. */
    public final String key;
    public final List<Piece> pieces;
    /** Turned in while offline, and waiting for a signal to finish. */
    public final boolean queued;
    public final long savedAt;

    public Draft(String key, List<Piece> pieces, boolean queued, long savedAt) {
        this.key = key;
        this.pieces = pieces;
        this.queued = queued;
        this.savedAt = savedAt;
    }

    public static Draft empty(String key) {
        return new Draft(key, new ArrayList<Piece>(), false, 0);
    }

    public boolean isEmpty() {
        return pieces.isEmpty();
    }

    public Piece piece(String id) {
        for (Piece piece : pieces) {
            if (piece.id.equals(id)) return piece;
        }
        return null;
    }

    public Draft with(Piece piece, long now) {
        List<Piece> next = new ArrayList<>();
        boolean replaced = false;
        for (Piece existing : pieces) {
            if (existing.id.equals(piece.id)) {
                next.add(piece);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) next.add(piece);
        return new Draft(key, next, queued, now);
    }

    public Draft without(String id, long now) {
        List<Piece> next = new ArrayList<>();
        for (Piece existing : pieces) {
            if (!existing.id.equals(id)) next.add(existing);
        }
        return new Draft(key, next, queued, now);
    }

    public Draft queued(boolean value, long now) {
        return new Draft(key, pieces, value, now);
    }

    /** The folder name the draft's files live in, safe on any filesystem. */
    public String folder() {
        return key.replace(':', '-');
    }

    static int words(String text) {
        if (text == null) return 0;
        String trimmed = text.trim();
        return trimmed.length() == 0 ? 0 : trimmed.split("\\s+").length;
    }

    // ------------------------------------------------------------------
    // How a draft becomes one Canvas submission
    // ------------------------------------------------------------------

    /**
     * What turning this draft in will send. Canvas takes one kind of
     * submission per attempt, so a draft has to fit one of them:
     *
     *   typed answers only     online_text_entry, one document with a heading each
     *   one link only          online_url
     *   anything with a file   online_upload, typed answers sent as .txt files
     *
     * A draft that cannot fit what the assignment accepts gets the reason,
     * phrased as the thing to change, rather than a refusal from Canvas.
     */
    public static final class Plan {
        public final String kind;
        public final String problem;

        Plan(String kind, String problem) {
            this.kind = kind;
            this.problem = problem;
        }

        public boolean ok() {
            return problem == null;
        }
    }

    public Plan plan(List<String> accepts, List<String> extensions) {
        if (pieces.isEmpty()) return new Plan(null, "Add your work first.");
        int texts = 0;
        int links = 0;
        int files = 0;
        for (Piece piece : pieces) {
            if (TEXT.equals(piece.kind)) texts++;
            else if (LINK.equals(piece.kind)) links++;
            else files++;
        }
        if (links > 0 && (texts > 0 || files > 0 || links > 1)) {
            return new Plan(null, "A link has to be turned in on its own. Remove the other items.");
        }
        if (links == 1) {
            return accepts.contains(Assignment.URL)
                ? new Plan(Assignment.URL, null)
                : new Plan(null, "This assignment doesn't take links.");
        }
        if (files == 0) {
            if (accepts.contains(Assignment.TEXT)) return new Plan(Assignment.TEXT, null);
            if (accepts.contains(Assignment.UPLOAD) && allowed(extensions, "txt")) {
                return new Plan(Assignment.UPLOAD, null);
            }
            return new Plan(null, "This assignment doesn't take typed answers.");
        }
        if (!accepts.contains(Assignment.UPLOAD)) {
            return new Plan(null, "This assignment doesn't take files. Remove them to turn in.");
        }
        for (Piece piece : pieces) {
            String extension = TEXT.equals(piece.kind) ? "txt" : extension(piece.file);
            if (!allowed(extensions, extension)) {
                return new Plan(null, "This assignment doesn't take ." + extension + " files.");
            }
        }
        return new Plan(Assignment.UPLOAD, null);
    }

    private static boolean allowed(List<String> extensions, String extension) {
        return extensions == null || extensions.isEmpty() || extensions.contains(extension);
    }

    static String extension(String file) {
        if (file == null) return "";
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "" : file.substring(dot + 1).toLowerCase(java.util.Locale.US);
    }

    /**
     * Typed answers as the one HTML document a text submission is. Each
     * answer's name is its heading when there is more than one, and every
     * line break the student typed is kept.
     */
    public String html() {
        StringBuilder out = new StringBuilder();
        int texts = 0;
        for (Piece piece : pieces) {
            if (TEXT.equals(piece.kind)) texts++;
        }
        for (Piece piece : pieces) {
            if (!TEXT.equals(piece.kind)) continue;
            if (texts > 1) out.append("<h3>").append(escape(piece.name)).append("</h3>");
            for (String paragraph : String.valueOf(piece.text).split("\n\\s*\n")) {
                if (paragraph.trim().length() == 0) continue;
                out.append("<p>").append(escape(paragraph.trim()).replace("\n", "<br>")).append("</p>");
            }
        }
        return out.toString();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------

    Map<String, Object> toMap() {
        List<Object> list = new ArrayList<>();
        for (Piece piece : pieces) list.add(piece.toMap());
        return Json.map("key", key, "pieces", list, "queued", Boolean.valueOf(queued),
            "savedAt", Long.valueOf(savedAt));
    }

    static Draft fromMap(Map<String, Object> map) {
        String key = Json.text(map, "key");
        if (key == null) return null;
        List<Piece> pieces = new ArrayList<>();
        for (Object entry : Json.children(map, "pieces")) {
            if (!(entry instanceof Map)) continue;
            Piece piece = Piece.fromMap(Json.object(entry));
            if (piece != null) pieces.add(piece);
        }
        Double at = Json.number(map, "savedAt");
        return new Draft(key, pieces, Json.flag(map, "queued"), at == null ? 0 : (long) at.doubleValue());
    }
}
