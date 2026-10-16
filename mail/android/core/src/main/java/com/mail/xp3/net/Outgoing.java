package com.mail.xp3.net;

import com.xp3.common.Json;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * A message being written: a new one, a reply, a forward, or a draft opened
 * again. What the user types is `text`; a reply's quote or a forward's copy
 * of the original is `tail`, added under it when it is sent, so the composer
 * on a 240px screen holds only what is theirs to write.
 */
public final class Outgoing {

    public static final String NEW = "new";
    public static final String REPLY = "reply";
    public static final String FORWARD = "forward";
    public static final String DRAFT = "draft";

    public String kind = NEW;
    /** Whom it is from, when not the default address: one of Me's, as a header. */
    public String from = "";
    public String to = "";
    public String cc = "";
    public String bcc = "";
    public String subject = "";
    public String text = "";
    public String tail = "";
    /** The forwarded original's HTML, when it had some; sent beside the text. */
    public String tailHtml = "";
    public String inReplyTo = "";
    public String references = "";
    public String threadId = "";
    /** Gmail's id for this draft once saved, so saving again replaces it. */
    public String draftId = "";
    /** Files carried over from the original (a forward) or the draft. */
    public final List<Attachment> attachments = new ArrayList<Attachment>();

    public static Outgoing compose(String to) {
        Outgoing out = new Outgoing();
        out.to = to == null ? "" : to;
        return out;
    }

    /**
     * A reply addressed as Gmail does: to the Reply-To or the sender, or for
     * the user's own message to its recipients again; with `all`, everyone
     * else on it is copied.
     */
    public static Outgoing reply(Message original, Me me, boolean all, TimeZone zone) {
        Outgoing out = new Outgoing();
        out.kind = REPLY;
        out.from = me.from(original).header();
        List<Address> sender = original.from();
        List<Address> to;
        List<Address> cc = new ArrayList<Address>();
        if (!sender.isEmpty() && me.is(sender.get(0).email)) {
            to = original.to();
            if (all) cc.addAll(original.cc());
        } else {
            to = original.replyTo();
            if (to.isEmpty()) to = sender;
            if (all) {
                cc.addAll(original.to());
                cc.addAll(original.cc());
            }
            to = dedupe(to, me, new ArrayList<Address>());
        }
        cc = dedupe(cc, me, to);
        out.to = Address.field(to);
        out.cc = Address.field(cc);
        String subject = original.subject();
        out.subject = Mime.isReply(subject) ? subject : "Re: " + subject;
        out.joins(original);
        out.tail = original.attribution(zone) + "\n" + Mime.quote(original.content().body());
        return out;
    }

    /** A forward: the original's text and HTML under a "Forwarded message" block, with its files. */
    public static Outgoing forward(Message original, Me me, TimeZone zone) {
        Outgoing out = new Outgoing();
        out.kind = FORWARD;
        out.from = me.from(original).header();
        String subject = original.subject();
        out.subject = Mime.isForward(subject) ? subject : "Fwd: " + subject;
        out.joins(original);
        String[][] fields = {
            {"From", Address.display(original.from())},
            {"Date", original.when(zone)},
            {"Subject", subject},
            {"To", Address.display(original.to())},
            {"Cc", Address.display(original.cc())},
        };
        StringBuilder text = new StringBuilder("---------- Forwarded message ---------\n");
        StringBuilder head = new StringBuilder();
        for (String[] field : fields) {
            if (field[1].length() == 0 && field[0].equals("Cc")) continue;
            text.append(field[0]).append(": ").append(field[1]).append('\n');
            head.append(field[0]).append(": ").append(Html.escape(field[1])).append("<br>");
        }
        Message.Content content = original.content();
        text.append('\n').append(content.body());
        out.tail = text.toString();
        if (content.html.length() > 0) {
            out.tailHtml = "<div class=\"gmail_quote\"><div class=\"gmail_attr\">---------- Forwarded message ---------<br>"
                + head + "</div><br><br>" + Html.body(content.html) + "</div>";
        }
        out.attachments.addAll(content.attachments);
        return out;
    }

    /** A saved draft, opened to carry on with: all of its text is the user's to edit. */
    public static Outgoing draft(Message draft, String draftId) {
        Outgoing out = new Outgoing();
        out.kind = DRAFT;
        out.from = draft.header("From");
        out.to = Address.field(draft.to());
        out.cc = Address.field(draft.cc());
        out.bcc = Address.field(draft.bcc());
        out.subject = draft.subject();
        Message.Content content = draft.content();
        out.text = content.body();
        out.inReplyTo = draft.header("In-Reply-To").trim();
        out.references = draft.header("References").trim();
        out.threadId = draft.threadId;
        out.draftId = draftId;
        out.attachments.addAll(content.attachments);
        return out;
    }

    /** In-Reply-To and References, so the message joins the original's conversation for everyone on it. */
    private void joins(Message original) {
        threadId = original.threadId;
        String id = original.messageId();
        if (id.length() == 0) return;
        inReplyTo = id;
        references = (original.header("References") + " " + id).trim().replaceAll("\\s+", " ");
    }

    private static List<Address> dedupe(List<Address> addresses, Me me, List<Address> skip) {
        List<Address> out = new ArrayList<Address>();
        for (Address address : addresses) {
            if (me.is(address.email) || Address.contains(skip, address.email) || Address.contains(out, address.email)) continue;
            out.add(address);
        }
        return out;
    }

    /** Whether nothing has been typed that leaving would lose. */
    public boolean untouched(Outgoing start) {
        return to.equals(start.to) && cc.equals(start.cc) && subject.equals(start.subject) && text.equals(start.text);
    }

    /** Everyone it goes to; throws, phrased for a person, if one isn't an address or there is nobody. */
    public List<Address> recipients() {
        List<Address> all = new ArrayList<Address>(Address.typed(to));
        all.addAll(Address.typed(cc));
        all.addAll(Address.typed(bcc));
        if (all.isEmpty()) throw new IllegalArgumentException("Who is it to? Fill in To.");
        return all;
    }

    /** The text as sent: what was typed, then the quote or the forwarded message. */
    public String body() {
        String typed = text.replace("\r\n", "\n").replaceAll("[ \n]+$", "");
        if (tail.length() == 0) return typed;
        return typed.length() == 0 ? tail : typed + "\n\n" + tail;
    }

    /** Fetches a carried-over file's bytes. */
    public interface Files {
        byte[] get(Attachment attachment) throws IOException, InterruptedException;
    }

    /** The message in RFC 5322 form, as Gmail's send and draft uploads take it. ASCII throughout. */
    public String raw(Address from, long now, Files files) throws IOException, InterruptedException {
        StringBuilder out = new StringBuilder();
        SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        date.setTimeZone(TimeZone.getDefault());
        header(out, "Date", date.format(new Date(now)));
        header(out, "From", from.header());
        header(out, "To", Address.header(Address.typed(to)));
        header(out, "Cc", Address.header(Address.typed(cc)));
        header(out, "Bcc", Address.header(Address.typed(bcc)));
        header(out, "Subject", Mime.encodeWord(subject.trim()));
        header(out, "In-Reply-To", inReplyTo);
        header(out, "References", references);
        header(out, "MIME-Version", "1.0");

        String plain = body();
        String entity = textPart("text/plain", plain);
        if (tailHtml.length() > 0) {
            String typed = text.replaceAll("[ \n]+$", "");
            String html = (typed.length() == 0 ? "" : "<div>" + Html.escape(typed) + "</div><br>") + tailHtml;
            entity = multipart("alternative", entity, textPart("text/html", html));
        }
        if (!attachments.isEmpty()) {
            String[] parts = new String[attachments.size() + 1];
            parts[0] = entity;
            for (int i = 0; i < attachments.size(); i++) parts[i + 1] = filePart(attachments.get(i), files.get(attachments.get(i)));
            entity = multipart("mixed", parts);
        }
        return out.append(entity).toString();
    }

    private static void header(StringBuilder out, String name, String value) {
        if (value != null && value.length() > 0) out.append(name).append(": ").append(value).append("\r\n");
    }

    private static String textPart(String type, String text) {
        byte[] bytes = text.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(Charset.forName("UTF-8"));
        return "Content-Type: " + type + "; charset=\"UTF-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" + Mime.base64(bytes);
    }

    private static String filePart(Attachment attachment, byte[] data) {
        String name = quotedName(attachment.name);
        String type = attachment.type.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+") ? attachment.type : "application/octet-stream";
        StringBuilder out = new StringBuilder();
        out.append("Content-Type: ").append(type).append("; name=").append(name).append("\r\n");
        out.append("Content-Disposition: ").append(attachment.inline && attachment.contentId.length() > 0 ? "inline" : "attachment")
            .append("; filename=").append(name).append("\r\n");
        out.append("Content-Transfer-Encoding: base64\r\n");
        if (attachment.contentId.length() > 0) out.append("Content-ID: <").append(attachment.contentId).append(">\r\n");
        return out.append("\r\n").append(Mime.base64(data)).toString();
    }

    private static String quotedName(String name) {
        String safe = Mime.ascii(name) ? name.replace("\\", "\\\\").replace("\"", "\\\"") : Mime.encodeWord(name).replace("\r\n ", " ");
        return "\"" + safe + "\"";
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    static String boundary() {
        StringBuilder out = new StringBuilder("=_xp3_");
        for (int i = 0; i < 24; i++) out.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        return out.toString();
    }

    private static String multipart(String subtype, String... parts) {
        String boundary = boundary();
        StringBuilder out = new StringBuilder("Content-Type: multipart/").append(subtype)
            .append("; boundary=\"").append(boundary).append("\"\r\n\r\n");
        for (String part : parts) out.append("--").append(boundary).append("\r\n").append(part).append("\r\n");
        return out.append("--").append(boundary).append("--\r\n").toString();
    }

    // ---- Kept on the phone while it is being written ----

    public String toJson() {
        List<Object> files = new ArrayList<Object>();
        for (Attachment attachment : attachments) files.add(attachment.toJson());
        return Json.write(Json.map("kind", kind, "from", from, "to", to, "cc", cc, "bcc", bcc, "subject", subject, "text", text,
            "tail", tail, "tailHtml", tailHtml, "inReplyTo", inReplyTo, "references", references,
            "threadId", threadId, "draftId", draftId, "attachments", files));
    }

    /** What toJson wrote, or null when it is unreadable. */
    public static Outgoing parse(String json) {
        if (json == null || json.length() == 0) return null;
        try {
            Map<String, Object> root = Json.object(Json.parse(json));
            Outgoing out = new Outgoing();
            out.kind = or(Json.text(root, "kind"), NEW);
            out.from = or(Json.text(root, "from"), "");
            out.to = or(Json.text(root, "to"), "");
            out.cc = or(Json.text(root, "cc"), "");
            out.bcc = or(Json.text(root, "bcc"), "");
            out.subject = or(Json.text(root, "subject"), "");
            out.text = or(Json.text(root, "text"), "");
            out.tail = or(Json.text(root, "tail"), "");
            out.tailHtml = or(Json.text(root, "tailHtml"), "");
            out.inReplyTo = or(Json.text(root, "inReplyTo"), "");
            out.references = or(Json.text(root, "references"), "");
            out.threadId = or(Json.text(root, "threadId"), "");
            out.draftId = or(Json.text(root, "draftId"), "");
            for (Object item : Json.children(root, "attachments")) out.attachments.add(Attachment.parse(Json.object(item)));
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String or(String value, String otherwise) {
        return value == null ? otherwise : value;
    }
}
