package com.serve.xp3.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** URL paths to folder names and back. */
public final class Paths {

    private static final String HEX = "0123456789ABCDEF";

    private Paths() {}

    /**
     * The names a request path walks through under the root, decoded: "." is
     * dropped and ".." climbs, never above the root. Null for a path that
     * cannot name a file (bad %-escapes, a NUL, a slash hidden in an escape).
     */
    public static List<String> segments(String path) {
        List<String> names = new ArrayList<>();
        for (String raw : path.split("/")) {
            String name = decode(raw);
            if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
                return null;
            }
            if (name.length() == 0 || name.equals(".")) continue;
            if (name.equals("..")) {
                if (!names.isEmpty()) names.remove(names.size() - 1);
                continue;
            }
            names.add(name);
        }
        return names;
    }

    /** "/a/b/" for a folder, "/a/b" for a file, "/" for the root. */
    public static String join(List<String> names, boolean folder) {
        StringBuilder path = new StringBuilder();
        for (String name : names) path.append('/').append(name);
        if (folder || names.isEmpty()) path.append('/');
        return path.toString();
    }

    /** A decoded path made safe for an href or a Location: each name %-escaped, the slashes kept. */
    public static String href(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "/-._~!$&'()*+,;=:@".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 15));
            }
        }
        return out.toString();
    }

    /** %-decoding as UTF-8; unlike URLDecoder, "+" stays a plus, as it does in a path. */
    static String decode(String raw) {
        if (raw.indexOf('%') < 0) return raw;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 3 > raw.length()) return null;
                int high = Character.digit(raw.charAt(i + 1), 16);
                int low = Character.digit(raw.charAt(i + 2), 16);
                if (high < 0 || low < 0) return null;
                bytes.write(high * 16 + low);
                i += 3;
            } else {
                int next = raw.offsetByCodePoints(i, 1);
                byte[] encoded = raw.substring(i, next).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                i = next;
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
