package com.serve.xp3.net;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Content types by extension, for what is likely to be on a phone. */
public final class Mime {

    private static final Map<String, String> TYPES = new HashMap<String, String>();
    static {
        String[][] pairs = {
            {"html", "text/html"}, {"htm", "text/html"}, {"css", "text/css"},
            {"js", "text/javascript"}, {"mjs", "text/javascript"}, {"json", "application/json"},
            {"txt", "text/plain"}, {"md", "text/markdown"}, {"csv", "text/csv"}, {"log", "text/plain"},
            {"xml", "application/xml"}, {"vcf", "text/vcard"}, {"ics", "text/calendar"},
            {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"gif", "image/gif"},
            {"webp", "image/webp"}, {"bmp", "image/bmp"}, {"svg", "image/svg+xml"}, {"ico", "image/x-icon"},
            {"heic", "image/heic"},
            {"mp3", "audio/mpeg"}, {"m4a", "audio/mp4"}, {"aac", "audio/aac"}, {"ogg", "audio/ogg"},
            {"oga", "audio/ogg"}, {"opus", "audio/ogg"}, {"flac", "audio/flac"}, {"wav", "audio/wav"},
            {"amr", "audio/amr"}, {"mid", "audio/midi"},
            {"mp4", "video/mp4"}, {"m4v", "video/mp4"}, {"webm", "video/webm"}, {"3gp", "video/3gpp"},
            {"mkv", "video/x-matroska"}, {"mov", "video/quicktime"}, {"avi", "video/x-msvideo"},
            {"pdf", "application/pdf"}, {"epub", "application/epub+zip"}, {"zip", "application/zip"},
            {"gz", "application/gzip"}, {"tar", "application/x-tar"},
            {"apk", "application/vnd.android.package-archive"},
            {"woff", "font/woff"}, {"woff2", "font/woff2"}, {"ttf", "font/ttf"}, {"otf", "font/otf"},
            {"wasm", "application/wasm"},
        };
        for (String[] pair : pairs) TYPES.put(pair[0], pair[1]);
    }

    private Mime() {}

    public static String of(String name) {
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.US));
        if (type == null) return "application/octet-stream";
        if (type.startsWith("text/") || type.equals("application/json") || type.equals("application/xml")
                || type.equals("image/svg+xml")) {
            return type + "; charset=utf-8";
        }
        return type;
    }
}
