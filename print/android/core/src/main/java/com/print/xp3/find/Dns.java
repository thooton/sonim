package com.print.xp3.find;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The parts of DNS messages (RFC 1035) that multicast DNS service discovery uses. */
public final class Dns {

    private Dns() {}

    public static final int A = 1;
    public static final int PTR = 12;
    public static final int TXT = 16;
    public static final int SRV = 33;

    private static final Charset UTF8 = StandardCharsets.UTF_8;

    /** One answer. Names are compared in lower case; `name` keeps its case for showing. */
    public static final class Record {
        public String name = "";
        public int type;
        public long ttl;
        /** PTR's target, SRV's host, A's address as dotted digits. */
        public String target = "";
        public int port;
        public final Map<String, String> txt = new LinkedHashMap<>();
    }

    public static byte[] query(int id, List<String> names, List<Integer> types) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        short16(out, id);
        short16(out, 0);
        short16(out, names.size());
        short16(out, 0);
        short16(out, 0);
        short16(out, 0);
        for (int i = 0; i < names.size(); i++) {
            name(out, names.get(i));
            short16(out, types.get(i));
            short16(out, 1);
        }
        return out.toByteArray();
    }

    private static void short16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void name(ByteArrayOutputStream out, String name) {
        for (String label : labels(name)) {
            byte[] raw = label.getBytes(UTF8);
            out.write(Math.min(raw.length, 63));
            out.write(raw, 0, Math.min(raw.length, 63));
        }
        out.write(0);
    }

    /**
     * Splits a name at its dots. An instance name may itself hold dots
     * ("Office Printer v2.1._ipp._tcp.local"), so the labels before the
     * service's "_" are kept as one.
     */
    static List<String> labels(String name) {
        List<String> out = new ArrayList<>();
        int service = name.indexOf("._");
        String head = service > 0 ? name.substring(0, service) : null;
        String rest = service > 0 ? name.substring(service + 1) : name;
        if (head != null) out.add(head);
        for (String label : rest.split("\\.")) if (!label.isEmpty()) out.add(label);
        return out;
    }

    /** Every answer, authority and additional record; null for a broken message. */
    public static List<Record> parse(byte[] data, int length) {
        try {
            Reader in = new Reader(data, length);
            in.at = 4;
            int questions = in.u16();
            int answers = in.u16() + in.u16() + in.u16();
            for (int i = 0; i < questions; i++) {
                in.name();
                in.at += 4;
            }
            List<Record> out = new ArrayList<>();
            for (int i = 0; i < answers; i++) {
                Record record = new Record();
                record.name = in.name();
                record.type = in.u16();
                in.u16();
                record.ttl = ((long) in.u16() << 16) | in.u16();
                int size = in.u16();
                int end = in.at + size;
                if (end > length) return null;
                switch (record.type) {
                    case PTR:
                        record.target = in.name();
                        break;
                    case SRV:
                        in.u16();
                        in.u16();
                        record.port = in.u16();
                        record.target = in.name();
                        break;
                    case A:
                        if (size == 4) {
                            record.target = (data[in.at] & 0xFF) + "." + (data[in.at + 1] & 0xFF) + "."
                                + (data[in.at + 2] & 0xFF) + "." + (data[in.at + 3] & 0xFF);
                        }
                        break;
                    case TXT:
                        while (in.at < end) {
                            int n = data[in.at++] & 0xFF;
                            if (in.at + n > end) break;
                            String entry = new String(data, in.at, n, UTF8);
                            in.at += n;
                            int eq = entry.indexOf('=');
                            if (eq > 0) record.txt.put(entry.substring(0, eq).toLowerCase(Locale.ROOT), entry.substring(eq + 1));
                            else if (!entry.isEmpty()) record.txt.put(entry.toLowerCase(Locale.ROOT), "");
                        }
                        break;
                    default:
                        break;
                }
                in.at = end;
                out.add(record);
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Reader {
        final byte[] data;
        final int length;
        int at;

        Reader(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }

        int u16() {
            if (at + 2 > length) throw new IndexOutOfBoundsException();
            int value = ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
            at += 2;
            return value;
        }

        String name() {
            StringBuilder out = new StringBuilder();
            int position = at;
            boolean jumped = false;
            int hops = 0;
            while (true) {
                if (position >= length) throw new IndexOutOfBoundsException();
                int n = data[position] & 0xFF;
                if (n == 0) {
                    position++;
                    break;
                }
                if ((n & 0xC0) == 0xC0) {
                    if (position + 1 >= length || ++hops > 32) throw new IndexOutOfBoundsException();
                    int pointer = ((n & 0x3F) << 8) | (data[position + 1] & 0xFF);
                    if (!jumped) at = position + 2;
                    jumped = true;
                    position = pointer;
                    continue;
                }
                if (position + 1 + n > length) throw new IndexOutOfBoundsException();
                if (out.length() > 0) out.append('.');
                out.append(new String(data, position + 1, n, UTF8));
                position += 1 + n;
            }
            if (!jumped) at = position;
            return out.toString();
        }
    }
}
