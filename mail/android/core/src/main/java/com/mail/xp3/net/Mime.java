package com.mail.xp3.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The small parts of MIME a mail client needs: encodings, charsets, quoting. */
public final class Mime {

    private Mime() {}

    /** Gmail's body data: base64url, padded or not. */
    public static byte[] decode(String data) {
        if (data == null || data.length() == 0) return new byte[0];
        String clean = data.replace('+', '-').replace('/', '_').replace("=", "").replaceAll("\\s", "");
        return Base64.getUrlDecoder().decode(clean);
    }

    /** Bytes in a message's charset, as text. Plain ASCII and Latin-1 are read as Windows-1252, their superset. */
    public static String text(byte[] data, String charset) {
        String name = charset == null ? "" : charset.trim().replace("\"", "").toLowerCase(Locale.US);
        if (name.equals("us-ascii") || name.equals("ascii") || name.startsWith("iso-8859-1") || name.equals("latin1")) {
            name = "windows-1252";
        }
        Charset found;
        try {
            found = name.length() == 0 ? Charset.forName("UTF-8") : Charset.forName(name);
        } catch (RuntimeException e) {
            found = Charset.forName("UTF-8");
        }
        return new String(data, found);
    }

    private static final Pattern WORD = Pattern.compile("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?\\s]*)\\?=");

    /** A header with its RFC 2047 encoded words ("=?UTF-8?Q?Caf=C3=A9?=") decoded. */
    public static String words(String header) {
        if (header == null) return "";
        if (header.indexOf("=?") < 0) return header;
        // Space between two encoded words is folding, not text.
        String joined = header.replaceAll("(\\?=)\\s+(=\\?)", "$1$2");
        Matcher m = WORD.matcher(joined);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(joined, last, m.start());
            String charset = m.group(1);
            int star = charset.indexOf('*'); // RFC 2231 language
            if (star >= 0) charset = charset.substring(0, star);
            try {
                byte[] bytes = m.group(2).equalsIgnoreCase("B")
                    ? Base64.getMimeDecoder().decode(m.group(3))
                    : quoted(m.group(3).replace('_', ' '));
                out.append(text(bytes, charset));
            } catch (RuntimeException e) {
                out.append(m.group());
            }
            last = m.end();
        }
        out.append(joined.substring(last));
        return out.toString();
    }

    static byte[] quoted(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' && i + 2 < text.length()) {
                try {
                    out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (RuntimeException e) {
                    // Not an escape: keep the "=".
                }
            }
            out.write(c);
        }
        return out.toByteArray();
    }

    public static boolean ascii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    /** Text for a header, as one or more encoded words when it isn't plain ASCII. */
    public static String encodeWord(String text) {
        if (ascii(text)) return text;
        StringBuilder out = new StringBuilder();
        // At most 45 bytes per word keeps each under 75 characters, and a word
        // never splits a character.
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int bytes = 0;
            while (end < text.length()) {
                int point = text.codePointAt(end);
                int size = new String(Character.toChars(point)).getBytes(Charset.forName("UTF-8")).length;
                if (bytes + size > 45 && end > start) break;
                bytes += size;
                end += Character.charCount(point);
            }
            if (out.length() > 0) out.append("\r\n ");
            out.append("=?UTF-8?B?")
                .append(Base64.getEncoder().encodeToString(text.substring(start, end).getBytes(Charset.forName("UTF-8"))))
                .append("?=");
            start = end;
        }
        return out.toString();
    }

    /** A parameter of a header such as Content-Type: `charset` of `text/plain; charset="utf-8"`. */
    public static String param(String header, String name) {
        if (header == null) return "";
        Matcher m = Pattern.compile("(?i);\\s*" + Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|([^;\\s]*))").matcher(header);
        if (!m.find()) return "";
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    /** The value before any parameters, lower case: `attachment` of `attachment; filename=x`. */
    public static String value(String header) {
        if (header == null) return "";
        int semi = header.indexOf(';');
        return (semi < 0 ? header : header.substring(0, semi)).trim().toLowerCase(Locale.US);
    }

    /** Base64 in lines of 76, as a MIME body. */
    public static String base64(byte[] data) {
        String encoded = Base64.getEncoder().encodeToString(data);
        StringBuilder out = new StringBuilder(encoded.length() + encoded.length() / 38 + 2);
        for (int i = 0; i < encoded.length(); i += 76) {
            out.append(encoded, i, Math.min(encoded.length(), i + 76)).append("\r\n");
        }
        return out.toString();
    }

    private static final Pattern QUOTE_INTRO =
        Pattern.compile("(?i)^(on\\b.*\\bwrote|le\\b.*\\ba écrit|am\\b.*\\bschrieb|el\\b.*\\bescribió)\\s*:?\\s*$");
    private static final Pattern OUTLOOK_QUOTE = Pattern.compile("^(-{3,}\\s*Original Message\\s*-{3,}|_{10,})\\s*$");
    private static final Pattern OUTLOOK_HEADER = Pattern.compile("^\\*?From:\\*? ");

    /**
     * A reply without the quoted copy of earlier messages it ends with ("On
     * ... wrote:" and ">" lines, or Outlook's "From: ... Sent: ..."), as
     * `delta gmail read` hides it; null when there is none.
     */
    public static String unquoted(String text) {
        String[] lines = text.split("\n", -1);
        int cut = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            if (!line.startsWith(">")) break;
            cut = i;
        }
        if (cut >= 0) {
            int j = cut - 1;
            while (j >= 0 && lines[j].trim().length() == 0) j--;
            if (j >= 0) {
                if (QUOTE_INTRO.matcher(lines[j].trim()).matches()) cut = j;
                else if (j > 0 && QUOTE_INTRO.matcher(lines[j - 1].trim() + " " + lines[j].trim()).matches()) cut = j - 1;
            }
        } else {
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (OUTLOOK_QUOTE.matcher(line).matches() || QUOTE_INTRO.matcher(line).matches() && i + 1 < lines.length) {
                    cut = i;
                    break;
                }
                if (OUTLOOK_HEADER.matcher(line).find() && i + 1 < lines.length
                    && lines[i + 1].trim().replaceFirst("^\\*+", "").startsWith("Sent:")) {
                    cut = i;
                    break;
                }
            }
        }
        if (cut <= 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cut; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString().replaceAll("[ \n]+$", "");
    }

    /** Each line with "> " in front, as a reply quotes what it answers. */
    public static String quote(String text) {
        String[] lines = text.replaceAll("\n+$", "").split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String line = lines[i];
            out.append(line.startsWith(">") ? ">" + line : line.length() == 0 ? ">" : "> " + line);
        }
        return out.toString();
    }

    /** A subject without its "Re:" and "Fwd:" prefixes. */
    public static String bare(String subject) {
        String s = subject.trim();
        while (true) {
            String next = s.replaceFirst("(?i)^(re|aw|sv|fwd?|wg|tr)\\s*:\\s*", "");
            if (next.equals(s)) return s;
            s = next;
        }
    }

    public static boolean isReply(String subject) {
        return subject.matches("(?is)^\\s*(re|aw|sv)\\s*:.*");
    }

    public static boolean isForward(String subject) {
        return subject.matches("(?is)^\\s*(fwd?|wg|tr)\\s*:.*");
    }

    /** A file name every file system takes. */
    public static String safeName(String name) {
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            out.append(c < ' ' || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        String clean = out.toString().trim().replaceFirst("^\\.+", "");
        return clean.length() == 0 ? "attachment" : clean;
    }

    /** "12 KB". */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static final Pattern URL = Pattern.compile("(?i)\\b(https?://[^\\s<>\"]+[^\\s<>\".,;:!?)\\]'])");

    /** The web addresses in plain text, each once. */
    public static List<String[]> links(String text) {
        List<String[]> out = new ArrayList<String[]>();
        List<String> seen = new ArrayList<String>();
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String url = m.group(1);
            if (seen.contains(url)) continue;
            seen.add(url);
            out.add(new String[] {url, url});
        }
        return out;
    }
}
