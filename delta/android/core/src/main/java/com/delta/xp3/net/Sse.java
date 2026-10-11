package com.delta.xp3.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A server-sent events reader: `event:`, `id:` and `data:` lines, a blank line
 * to end each frame, and `:` comments (the session stream's keepalives)
 * skipped.
 *
 * Lines are split on bytes rather than read through a Reader, so a multi-byte
 * character can never be cut in half at a buffer boundary.
 */
public final class Sse {

    /** One dispatched frame. Fields absent from the frame are empty strings. */
    public static final class Frame {
        public final String event;
        public final String id;
        public final String data;

        Frame(String event, String id, String data) {
            this.event = event;
            this.id = id;
            this.data = data;
        }
    }

    /** A replayed tool result can be long; anything past this is not a frame worth holding. */
    private static final int MAX_LINE = 8000000;

    private final InputStream in;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    public Sse(InputStream in) {
        // Lines are read a byte at a time, so the socket must not be.
        this.in = new BufferedInputStream(in, 16384);
    }

    /** The next frame, or null when the stream has ended. */
    public Frame next() throws IOException {
        String event = "";
        String id = "";
        StringBuilder data = null;
        while (true) {
            String text = readLine();
            if (text == null) return null;
            if (text.length() == 0) {
                if (data != null) return new Frame(event.length() == 0 ? "message" : event, id, data.toString());
                event = "";
                continue;
            }
            if (text.charAt(0) == ':') continue;
            int colon = text.indexOf(':');
            String field = colon < 0 ? text : text.substring(0, colon);
            String value = colon < 0 ? "" : text.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (field.equals("event")) {
                event = value;
            } else if (field.equals("id")) {
                id = value;
            } else if (field.equals("data")) {
                if (data == null) data = new StringBuilder(value);
                else data.append('\n').append(value);
            }
        }
    }

    private String readLine() throws IOException {
        line.reset();
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (line.size() == 0) return null;
                break;
            }
            if (c == '\n') break;
            if (line.size() >= MAX_LINE) throw new IOException("The session sent an event too large to read");
            line.write(c);
        }
        String text = line.toString("UTF-8");
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }
}
