package com.print.xp3.send;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PJL, HP's job language, which most printers with a raw port (9100) speak
 * too: asked who the printer is and which languages it reads, and used to
 * open and close a job in one of them.
 */
public final class Pjl {

    private Pjl() {}

    static final String UEL = "\u001b%-12345X";
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** What a printer said about itself. */
    public static final class Info {
        public String model = "";
        /** Upper case, as the printer says them: PCL, POSTSCRIPT, PDF, ... */
        public final List<String> languages = new ArrayList<String>();

        public boolean speaks(String language) {
            for (String each : languages) if (each.startsWith(language)) return true;
            return false;
        }
    }

    /** Asks over the raw port; null when the printer says nothing back. */
    public static Info query(String host, int port, int millis) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), millis);
            socket.setSoTimeout(millis);
            OutputStream out = socket.getOutputStream();
            out.write((UEL + "@PJL\r\n@PJL INFO ID\r\n@PJL INFO CONFIG\r\n" + UEL).getBytes(ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream answer = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long until = System.currentTimeMillis() + millis * 2L;
            try {
                int read;
                while (System.currentTimeMillis() < until && (read = in.read(buffer)) > 0) {
                    answer.write(buffer, 0, read);
                    if (answer.size() > 65536) break;
                    // CONFIG answers last; once its languages are in, stop waiting.
                    String so = new String(answer.toByteArray(), ASCII);
                    if (so.contains("INFO CONFIG") && so.indexOf('\f', so.indexOf("INFO CONFIG")) > 0) break;
                }
            } catch (SocketTimeoutException quiet) {
                // It said all it was going to.
            }
            if (answer.size() == 0) return null;
            return parse(new String(answer.toByteArray(), ASCII));
        } catch (IOException e) {
            return null;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }

    public static Info parse(String answer) {
        Info info = new Info();
        String[] lines = answer.replace("\r", "").split("[\n\f]");
        boolean inId = false;
        boolean inLanguages = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("@PJL INFO ID")) {
                inId = true;
                inLanguages = false;
                continue;
            }
            if (upper.startsWith("@PJL")) {
                inId = false;
                inLanguages = false;
                continue;
            }
            if (inId) {
                info.model = line.replace("\"", "").trim();
                inId = false;
                continue;
            }
            if (upper.startsWith("LANGUAGES")) {
                inLanguages = true;
                continue;
            }
            if (inLanguages) {
                if (raw.startsWith("\t") || raw.startsWith(" ")) {
                    info.languages.add(upper);
                    continue;
                }
                inLanguages = false;
            }
        }
        return info;
    }

    /** What goes before a job in `language` (PCL, POSTSCRIPT, PDF). */
    public static String begin(String name, String language, int copies, int duplex) {
        StringBuilder out = new StringBuilder(UEL).append("@PJL\r\n");
        out.append("@PJL JOB NAME=\"").append(clean(name)).append("\"\r\n");
        if (language.equals("PDF")) {
            if (copies > 1) out.append("@PJL SET COPIES=").append(copies).append("\r\n");
            if (duplex != 0) {
                out.append("@PJL SET DUPLEX=ON\r\n@PJL SET BINDING=")
                    .append(duplex == 2 ? "SHORTEDGE" : "LONGEDGE").append("\r\n");
            }
        }
        out.append("@PJL ENTER LANGUAGE=").append(language).append("\r\n");
        return out.toString();
    }

    public static String end(String name) {
        return UEL + "@PJL EOJ NAME=\"" + clean(name) + "\"\r\n" + UEL;
    }

    private static String clean(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c >= 32 && c < 127 && c != '"') out.append(c);
            if (out.length() == 60) break;
        }
        return out.toString();
    }
}
