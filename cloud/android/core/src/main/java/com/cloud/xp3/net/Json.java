package com.cloud.xp3.net;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON codec: maps, lists, strings, BigDecimal, booleans and
 * null, and nothing else.
 *
 * Strict is the point. Everything this app parses arrives from somewhere else
 * (a model, a login endpoint) and is treated as data rather than as
 * something to be guessed at, so a duplicate key, a trailing comma or a control
 * character in a string is an error rather than a repair. It is also the only
 * JSON in the app, which is why the settings file, the list and the transcript
 * are written with it too.
 */
public final class Json {

    private Json() {}

    public static Object parse(String source) {
        if (source.length() > 8000000) {
            throw new IllegalArgumentException("JSON exceeds size limit");
        }
        Parser parser = new Parser(source);
        Object result = parser.value(0);
        parser.space();
        if (parser.i != source.length()) throw parser.error();
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>) value;
    }

    /** A non-empty string at `key`, or an error. */
    public static String string(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String) || ((String) value).trim().length() == 0) {
            throw new IllegalArgumentException("Missing or invalid " + key);
        }
        return (String) value;
    }

    /** The string at `key`, or null when it is absent, null or not a string. */
    public static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** The number at `key` as a Double, or null when it is absent or not a number. */
    public static Double number(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
    }

    /** True only when `key` holds the boolean true. Absent and null are false. */
    public static boolean flag(Map<String, Object> source, String key) {
        return Boolean.TRUE.equals(source.get(key));
    }

    /** The map at `key`, or an empty map when there is not one there. */
    public static Map<String, Object> child(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map ? object(value) : new LinkedHashMap<String, Object>();
    }

    /** The list at `key`, or an empty list when there is not one there. */
    public static List<Object> children(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof List ? array(value) : new ArrayList<Object>();
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    public static List<Object> list(Object... items) {
        List<Object> result = new ArrayList<Object>();
        for (int i = 0; i < items.length; i++) result.add(items[i]);
        return result;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            appendString(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            if (value instanceof Double && !isFinite(((Double) value).doubleValue())
                || value instanceof Float && !isFinite(((Float) value).doubleValue())) {
                throw new IllegalArgumentException("Non-finite JSON number");
            }
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{');
            boolean comma = false;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (comma) out.append(',');
                comma = true;
                appendString(out, (String) entry.getKey());
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            List<?> items = (List<?>) value;
            out.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) out.append(',');
                append(out, items.get(i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value");
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 32 || Character.isSurrogate(c)) {
                out.append(String.format("\\u%04x", Integer.valueOf(c)));
            } else out.append(c);
        }
        out.append('"');
    }

    private static final class Parser {

        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error() {
            return new IllegalArgumentException("Invalid JSON at character " + i);
        }

        void space() {
            while (i < s.length() && " \r\n\t".indexOf(s.charAt(i)) >= 0) i++;
        }

        boolean take(char c) {
            space();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        Object value(int depth) {
            if (depth > 100) throw error();
            space();
            if (i >= s.length()) throw error();
            if (s.charAt(i) == '"') return text();
            if (take('{')) {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                if (take('}')) return map;
                do {
                    space();
                    if (i >= s.length() || s.charAt(i) != '"') throw error();
                    String key = text();
                    if (!take(':') || map.containsKey(key)) throw error();
                    map.put(key, value(depth + 1));
                } while (take(','));
                if (!take('}')) throw error();
                return map;
            }
            if (take('[')) {
                List<Object> list = new ArrayList<Object>();
                if (take(']')) return list;
                do {
                    list.add(value(depth + 1));
                } while (take(','));
                if (!take(']')) throw error();
                return list;
            }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Object number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            if (i < s.length() && s.charAt(i) == '0') i++;
            else {
                int digits = i;
                while (digit()) i++;
                if (i == digits) throw error();
            }
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int digits = i;
                while (digit()) i++;
                if (i == digits) throw error();
            }
            if (i < s.length() && "eE".indexOf(s.charAt(i)) >= 0) {
                i++;
                if (i < s.length() && "+-".indexOf(s.charAt(i)) >= 0) i++;
                int digits = i;
                while (digit()) i++;
                if (i == digits) throw error();
            }
            try {
                return new BigDecimal(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error();
            }
        }

        boolean digit() {
            return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
        }

        String text() {
            i++;
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c < 32) throw error();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i == s.length()) throw error();
                char escape = s.charAt(i++);
                if (escape == '"' || escape == '\\' || escape == '/') out.append(escape);
                else if (escape == 'b') out.append('\b');
                else if (escape == 'f') out.append('\f');
                else if (escape == 'n') out.append('\n');
                else if (escape == 'r') out.append('\r');
                else if (escape == 't') out.append('\t');
                else if (escape == 'u') {
                    if (i + 4 > s.length()) throw error();
                    for (int j = i; j < i + 4; j++) {
                        if ("0123456789abcdefABCDEF".indexOf(s.charAt(j)) < 0) throw error();
                    }
                    out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                } else throw error();
            }
            throw error();
        }
    }
}
