package com.mail.xp3.net;

import com.xp3.common.Json;
import java.util.Map;

/**
 * A file attached to a message, or an inline image. Gmail keeps a big one
 * apart (fetched by `id`) and puts a small one's base64url `data` in the
 * message itself.
 */
public final class Attachment {

    public final String message;
    public final String id;
    public final String data;
    public final String name;
    public final String type;
    public final long size;
    public final String contentId;
    public final boolean inline;

    public Attachment(String message, String id, String data, String name, String type, long size,
                      String contentId, boolean inline) {
        this.message = message;
        this.id = id == null ? "" : id;
        this.data = data == null ? "" : data;
        this.name = name;
        this.type = type == null || type.length() == 0 ? "application/octet-stream" : type;
        this.size = size;
        this.contentId = contentId == null ? "" : contentId;
        this.inline = inline;
    }

    /** "report.pdf, 120 KB". */
    public String label() {
        return name + ", " + Mime.size(size);
    }

    public Map<String, Object> toJson() {
        return Json.map("message", message, "id", id, "data", data, "name", name, "type", type,
            "size", Long.valueOf(size), "cid", contentId, "inline", Boolean.valueOf(inline));
    }

    public static Attachment parse(Map<String, Object> json) {
        Double size = Json.number(json, "size");
        return new Attachment(Json.string(json, "message"), Json.text(json, "id"), Json.text(json, "data"),
            Json.string(json, "name"), Json.text(json, "type"), size == null ? 0 : size.longValue(),
            Json.text(json, "cid"), Json.flag(json, "inline"));
    }
}
