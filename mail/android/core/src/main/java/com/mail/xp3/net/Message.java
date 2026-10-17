package com.mail.xp3.net;

import com.xp3.common.Json;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** One of Gmail's messages, read from its JSON as asked for. */
public final class Message {

    public final String id;
    public final String threadId;
    /** Changed in place when the phone relabels its conversation. */
    final List<String> labels = new ArrayList<String>();
    private final Map<String, Object> payload;
    private final long time;
    private final String snippet;
    private Content content;

    public Message(Map<String, Object> json) {
        id = Json.string(json, "id");
        threadId = Json.text(json, "threadId") == null ? id : Json.text(json, "threadId");
        for (Object label : Json.children(json, "labelIds")) {
            if (label instanceof String) labels.add((String) label);
        }
        payload = Json.child(json, "payload");
        long at = 0;
        try {
            at = Long.parseLong(String.valueOf(json.get("internalDate")));
        } catch (RuntimeException e) {
            // Unknown: sorted first.
        }
        time = at;
        String text = Json.text(json, "snippet");
        snippet = text == null ? "" : Html.entities(text).trim();
    }

    /** The first header with this name, as it arrived. */
    public String header(String name) {
        return header(payload, name);
    }

    static String header(Map<String, Object> part, String name) {
        for (Object item : Json.children(part, "headers")) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> header = Json.object(item);
            if (name.equalsIgnoreCase(Json.text(header, "name"))) {
                String value = Json.text(header, "value");
                return value == null ? "" : value;
            }
        }
        return "";
    }

    /**
     * Whether it carries a file, as far as its MIME tree says: a named part,
     * or a multipart/mixed body, which is what a message with attachments is
     * sent as. A list's metadata has only the top of the tree, so there it
     * is the second.
     */
    public boolean hasFiles() {
        return hasFiles(payload);
    }

    private static boolean hasFiles(Map<String, Object> part) {
        if (part == null) return false;
        String type = Json.text(part, "mimeType");
        if (type != null && type.equalsIgnoreCase("multipart/mixed")) return true;
        String name = Json.text(part, "filename");
        if (name != null && name.length() > 0) return true;
        for (Object child : Json.children(part, "parts")) {
            if (child instanceof Map && hasFiles(Json.object(child))) return true;
        }
        return false;
    }

    public String subject() {
        return Mime.words(header("Subject")).replaceAll("\\s+", " ").trim();
    }

    public List<Address> from() {
        return Address.parse(header("From"));
    }

    public List<Address> to() {
        return Address.parse(header("To"));
    }

    public List<Address> cc() {
        return Address.parse(header("Cc"));
    }

    public List<Address> bcc() {
        return Address.parse(header("Bcc"));
    }

    public List<Address> replyTo() {
        return Address.parse(header("Reply-To"));
    }

    /** The sender's name, or address. */
    public String sender() {
        List<Address> from = from();
        return from.isEmpty() ? Mime.words(header("From")).trim() : from.get(0).display();
    }

    /** When Gmail received (or sent) it, in epoch milliseconds. */
    public long time() {
        return time;
    }

    public String snippet() {
        return snippet;
    }

    public boolean has(String label) {
        return labels.contains(label);
    }

    public boolean unread() {
        return has("UNREAD");
    }

    public boolean draft() {
        return has("DRAFT");
    }

    /** The Message-ID header, which a reply names to join the conversation. */
    public String messageId() {
        String value = header("Message-ID").trim();
        return value.length() > 0 ? value : header("Message-Id").trim();
    }

    /** "On Tue, Oct 13, 2026 at 3:04 PM Jane Doe <jane@x.com> wrote:", as Gmail puts it above a quote. */
    public String attribution(TimeZone zone) {
        return "On " + when(zone) + " " + Address.display(from()) + " wrote:";
    }

    /** "Tue, Oct 13, 2026 at 3:04 PM". */
    public String when(TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, MMM d, yyyy 'at' h:mm a", Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(time));
    }

    public Content content() {
        if (content == null) {
            Content walked = new Content();
            walked.walk(id, payload);
            content = walked;
        }
        return content;
    }

    /** A message's text and HTML, and its files. */
    public static final class Content {

        public String text = "";
        public String html = "";
        public final List<Attachment> attachments = new ArrayList<Attachment>();

        /**
         * The text to show: the plain text, or the HTML as text when there is
         * no real plain text (some senders put only "View in browser" there).
         */
        public String body() {
            String plain = text.trim();
            if (html.length() > 0 && plain.length() < 40) {
                String converted = Html.text(html);
                if (converted.trim().length() > 0) return converted;
            }
            return plain;
        }

        /** The links to offer: the HTML's, or the plain text's addresses. */
        public List<String[]> links() {
            return html.length() > 0 ? Html.links(html) : Mime.links(text);
        }

        void walk(String message, Map<String, Object> part) {
            String type = String.valueOf(Json.text(part, "mimeType")).toLowerCase(Locale.US);
            String filename = Json.text(part, "filename");
            List<Object> parts = Json.children(part, "parts");
            String disposition = Mime.value(header(part, "Content-Disposition"));
            String contentId = header(part, "Content-ID").replaceAll("[<>\\s]", "");
            Map<String, Object> body = Json.child(part, "body");
            boolean file = filename != null && filename.length() > 0
                || parts.isEmpty() && !type.startsWith("multipart/")
                && (disposition.equals("attachment") || contentId.length() > 0 && !type.startsWith("text/"));
            if (file) {
                String name = filename != null && filename.length() > 0 ? Mime.words(filename) : "attachment";
                Double size = Json.number(body, "size");
                attachments.add(new Attachment(message, Json.text(body, "attachmentId"), Json.text(body, "data"),
                    name, type, size == null ? 0 : size.longValue(), contentId, disposition.equals("inline")));
                return;
            }
            if (type.startsWith("multipart/")) {
                for (Object child : parts) if (child instanceof Map) walk(message, Json.object(child));
                return;
            }
            if (!type.equals("text/plain") && !type.equals("text/html")) return;
            String data = Json.text(body, "data");
            if (data == null || data.length() == 0) return;
            String decoded;
            try {
                decoded = Mime.text(Mime.decode(data), Mime.param(header(part, "Content-Type"), "charset"));
            } catch (RuntimeException e) {
                return;
            }
            // The first of each; a later one is usually a forwarded copy.
            if (type.equals("text/plain") && text.length() == 0) text = decoded.replace("\r\n", "\n");
            else if (type.equals("text/html") && html.length() == 0) html = decoded;
        }
    }
}
