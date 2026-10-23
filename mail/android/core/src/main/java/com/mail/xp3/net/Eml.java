package com.mail.xp3.net;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An email file (.eml): a message written somewhere else, such as one the
 * user wrote by hand on the tablet that Transform typed up, which Tablet
 * saves into Documents on the SD card. Opened, it is a new message, to check
 * and send: its To, Cc, Bcc, Subject and text, and nothing else (Mail sends
 * it from the user's own address).
 */
public final class Eml {

    /** Bigger than any typed-up email; a bigger file isn't one to open. */
    public static final long MAX = 1 << 20;

    private Eml() {}

    /** The .eml files in `folders`, newest first. A folder that isn't there is skipped. */
    public static List<File> find(List<File> folders) {
        List<File> out = new ArrayList<File>();
        for (File folder : folders) {
            File[] files = folder == null ? null : folder.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".eml") && file.length() <= MAX) out.add(file);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** The message in an .eml, as a new message to send. */
    public static Outgoing open(byte[] data) {
        Part message = new Part(data);
        Outgoing out = Outgoing.compose("");
        out.to = Address.field(Address.parse(message.header("To")));
        out.cc = Address.field(Address.parse(message.header("Cc")));
        out.bcc = Address.field(Address.parse(message.header("Bcc")));
        out.subject = Mime.words(message.header("Subject")).replaceAll("\\s+", " ").trim();
        out.text = message.text().replace("\r\n", "\n").replace('\r', '\n').replaceAll("\\s+$", "");
        return out;
    }

    /** One part of a message: its headers, by lower-case name, and its body as it is in the file. */
    private static final class Part {
        final Map<String, String> headers = new HashMap<String, String>();
        final byte[] body;

        Part(byte[] data) {
            // A part with no headers starts with its blank line.
            int skip = data.length > 0 && data[0] == '\n' ? 1 : data.length > 1 && data[0] == '\r' && data[1] == '\n' ? 2 : 0;
            int end = skip > 0 ? 0 : headersEnd(data);
            String head = new String(data, 0, end, Charset.forName("UTF-8"));
            String name = null;
            StringBuilder value = new StringBuilder();
            for (String line : head.split("\r?\n")) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && name != null) {
                    value.append(line); // folded onto the line before
                    continue;
                }
                if (name != null && !headers.containsKey(name)) headers.put(name, value.toString().trim());
                int colon = line.indexOf(':');
                name = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.US) : null;
                value.setLength(0);
                if (name != null) value.append(line.substring(colon + 1));
            }
            if (name != null && !headers.containsKey(name)) headers.put(name, value.toString().trim());
            int start = skip > 0 ? skip : Math.min(data.length, end + (end < data.length && data[end] == '\r' ? 4 : 2));
            body = new byte[data.length - start];
            System.arraycopy(data, start, body, 0, body.length);
        }

        /** Where the headers end: at the first blank line, or the end when there is none. */
        private static int headersEnd(byte[] data) {
            for (int i = 0; i + 1 < data.length; i++) {
                if (data[i] != '\n' && data[i] != '\r') continue;
                if (data[i] == '\n' && data[i + 1] == '\n') return i;
                if (data[i] == '\r' && i + 3 < data.length && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') return i;
            }
            return data.length;
        }

        String header(String name) {
            String value = headers.get(name.toLowerCase(Locale.US));
            return value == null ? "" : value;
        }

        /** The text: a plain part's, or an HTML part's as text when there is no plain one. */
        String text() {
            String type = header("Content-Type");
            String kind = type.length() == 0 ? "text/plain" : Mime.value(type);
            if (kind.startsWith("multipart/")) {
                String html = "";
                for (Part part : parts(Mime.param(type, "boundary"))) {
                    String partKind = Mime.value(part.header("Content-Type"));
                    if (Mime.value(part.header("Content-Disposition")).equals("attachment")) continue;
                    if (partKind.length() == 0 || partKind.equals("text/plain") || partKind.startsWith("multipart/")) {
                        String text = part.text();
                        if (text.trim().length() > 0) return text;
                    } else if (partKind.equals("text/html") && html.length() == 0) {
                        html = part.text();
                    }
                }
                return html;
            }
            String text = Mime.text(decoded(), Mime.param(type, "charset"));
            return kind.equals("text/html") ? Html.text(text) : kind.startsWith("text/") ? text : "";
        }

        /** The body without its transfer encoding. */
        private byte[] decoded() {
            String encoding = Mime.value(header("Content-Transfer-Encoding"));
            String ascii = new String(body, Charset.forName("ISO-8859-1"));
            try {
                if (encoding.equals("base64")) return Base64.getMimeDecoder().decode(ascii.replaceAll("[^A-Za-z0-9+/=]", ""));
                if (encoding.equals("quoted-printable")) return Mime.quoted(ascii.replaceAll("=\r?\n", ""));
            } catch (RuntimeException e) {
                // Not what it says it is: as it is.
            }
            return body;
        }

        /** A multipart body's parts. */
        private List<Part> parts(String boundary) {
            List<Part> out = new ArrayList<Part>();
            if (boundary.length() == 0) return out;
            String all = new String(body, Charset.forName("ISO-8859-1"));
            String[] pieces = all.split("(?md)^--" + Pattern.quote(boundary) + "(--)?[ \\t]*\\r?(\\n|\\z)", -1);
            // Before the first boundary is the preamble, after the last the epilogue.
            for (int i = 1; i < pieces.length - 1; i++) {
                String piece = pieces[i].replaceAll("\r?\n$", "");
                out.add(new Part(piece.getBytes(Charset.forName("ISO-8859-1"))));
            }
            return out;
        }
    }
}
