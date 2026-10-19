package com.print.xp3.ipp;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * IPP messages (RFC 8010): a request or response is a version, an operation
 * or status code, a request id and groups of attributes. Values are kept as
 * plain Java: Integer (integer, enum), Boolean, String (every text-like
 * tag), int[2] (rangeOfInteger), int[3] (resolution: x, y, units),
 * List&lt;Attribute&gt; (a collection's members) and byte[] (the rest).
 */
public final class Ipp {

    private Ipp() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // Group delimiters.
    public static final int OPERATION = 0x01;
    public static final int JOB = 0x02;
    public static final int END = 0x03;
    public static final int PRINTER = 0x04;
    public static final int UNSUPPORTED = 0x05;

    // Value tags.
    public static final int NO_VALUE = 0x13;
    public static final int INTEGER = 0x21;
    public static final int BOOLEAN = 0x22;
    public static final int ENUM = 0x23;
    public static final int OCTETS = 0x30;
    public static final int DATE = 0x31;
    public static final int RESOLUTION = 0x32;
    public static final int RANGE = 0x33;
    public static final int BEGIN_COLLECTION = 0x34;
    public static final int TEXT_LANG = 0x35;
    public static final int NAME_LANG = 0x36;
    public static final int END_COLLECTION = 0x37;
    public static final int TEXT = 0x41;
    public static final int NAME = 0x42;
    public static final int KEYWORD = 0x44;
    public static final int URI = 0x45;
    public static final int CHARSET = 0x47;
    public static final int LANGUAGE = 0x48;
    public static final int MIME = 0x49;
    public static final int MEMBER = 0x4A;

    // Operations.
    public static final int PRINT_JOB = 0x0002;
    public static final int CANCEL_JOB = 0x0008;
    public static final int GET_JOB_ATTRIBUTES = 0x0009;
    public static final int GET_PRINTER_ATTRIBUTES = 0x000B;
    public static final int CUPS_GET_PRINTERS = 0x4002;

    // Statuses worth naming.
    public static final int OK = 0x0000;
    public static final int BAD_REQUEST = 0x0400;
    public static final int FORBIDDEN = 0x0401;
    public static final int NOT_AUTHENTICATED = 0x0402;
    public static final int NOT_FOUND = 0x0406;
    public static final int FORMAT_NOT_SUPPORTED = 0x040A;
    public static final int ATTRIBUTES_NOT_SUPPORTED = 0x040B;
    public static final int VERSION_NOT_SUPPORTED = 0x0503;
    public static final int BUSY = 0x0507;

    /** One named attribute and its values, all of one tag. */
    public static final class Attribute {
        public final String name;
        public final int tag;
        public final List<Object> values = new ArrayList<Object>();

        public Attribute(String name, int tag, Object... values) {
            this.name = name;
            this.tag = tag;
            this.values.addAll(Arrays.asList(values));
        }

        /** The first value as a string, or "". */
        public String string() {
            return values.isEmpty() || !(values.get(0) instanceof String) ? "" : (String) values.get(0);
        }

        /** Every value that is a string. */
        public List<String> strings() {
            List<String> out = new ArrayList<String>();
            for (Object value : values) if (value instanceof String) out.add((String) value);
            return out;
        }

        /** The first value as an integer, or `otherwise`. */
        public int integer(int otherwise) {
            return values.isEmpty() || !(values.get(0) instanceof Integer) ? otherwise : (Integer) values.get(0);
        }

        public boolean flag() {
            return !values.isEmpty() && Boolean.TRUE.equals(values.get(0));
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(name).append('=');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) out.append(',');
                Object value = values.get(i);
                out.append(value instanceof int[] ? Arrays.toString((int[]) value) : String.valueOf(value));
            }
            return out.toString();
        }
    }

    /** A group: its delimiter tag and its attributes, in order. */
    public static final class Group {
        public final int tag;
        public final List<Attribute> attributes = new ArrayList<Attribute>();

        public Group(int tag) {
            this.tag = tag;
        }

        public Group add(Attribute attribute) {
            attributes.add(attribute);
            return this;
        }

        public Group add(String name, int tag, Object... values) {
            return add(new Attribute(name, tag, values));
        }

        public Attribute get(String name) {
            return find(attributes, name);
        }
    }

    public static Attribute find(List<Attribute> attributes, String name) {
        for (Attribute attribute : attributes) if (attribute.name.equals(name)) return attribute;
        return null;
    }

    /** A request or a response. `code` is the operation or the status. */
    public static final class Message {
        public int major = 2;
        public int minor = 0;
        public int code;
        public int id;
        public final List<Group> groups = new ArrayList<Group>();

        public Message(int code, int id) {
            this.code = code;
            this.id = id;
        }

        public Group group(int tag) {
            for (Group group : groups) if (group.tag == tag) return group;
            return null;
        }

        /** Every group of this tag: CUPS-Get-Printers answers one per printer. */
        public List<Group> all(int tag) {
            List<Group> out = new ArrayList<Group>();
            for (Group group : groups) if (group.tag == tag) out.add(group);
            return out;
        }

        public Group add(int tag) {
            Group group = new Group(tag);
            groups.add(group);
            return group;
        }

        /** The named attribute from the first group that has it. */
        public Attribute get(String name) {
            for (Group group : groups) {
                Attribute found = group.get(name);
                if (found != null) return found;
            }
            return null;
        }

        public boolean ok() {
            return code < 0x0100;
        }
    }

    /**
     * A request with the operation attributes every request starts with, in
     * the order the standard asks for.
     */
    public static Message request(int operation, int id, String printerUri) {
        Message message = new Message(operation, id);
        Group group = message.add(OPERATION);
        group.add("attributes-charset", CHARSET, "utf-8");
        group.add("attributes-natural-language", LANGUAGE, "en");
        if (printerUri != null) group.add("printer-uri", URI, printerUri);
        return message;
    }

    // Encoding.

    public static byte[] encode(Message message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(message.major);
            out.writeByte(message.minor);
            out.writeShort(message.code);
            out.writeInt(message.id);
            for (Group group : message.groups) {
                out.writeByte(group.tag);
                for (Attribute attribute : group.attributes) write(out, attribute.name, attribute);
            }
            out.writeByte(END);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(DataOutputStream out, String name, Attribute attribute) throws IOException {
        if (attribute.values.isEmpty()) {
            out.writeByte(NO_VALUE);
            string(out, name);
            out.writeShort(0);
            return;
        }
        for (int i = 0; i < attribute.values.size(); i++) {
            String named = i == 0 ? name : "";
            Object value = attribute.values.get(i);
            if (attribute.tag == BEGIN_COLLECTION) {
                out.writeByte(BEGIN_COLLECTION);
                string(out, named);
                out.writeShort(0);
                @SuppressWarnings("unchecked")
                List<Attribute> members = (List<Attribute>) value;
                for (Attribute member : members) {
                    out.writeByte(MEMBER);
                    out.writeShort(0);
                    string(out, member.name);
                    write(out, "", member);
                }
                out.writeByte(END_COLLECTION);
                out.writeShort(0);
                out.writeShort(0);
                continue;
            }
            out.writeByte(attribute.tag);
            string(out, named);
            if (value instanceof Integer) {
                if (attribute.tag == BOOLEAN) {
                    out.writeShort(1);
                    out.writeByte((Integer) value);
                } else {
                    out.writeShort(4);
                    out.writeInt((Integer) value);
                }
            } else if (value instanceof Boolean) {
                out.writeShort(1);
                out.writeByte((Boolean) value ? 1 : 0);
            } else if (value instanceof int[]) {
                int[] numbers = (int[]) value;
                if (attribute.tag == RESOLUTION) {
                    out.writeShort(9);
                    out.writeInt(numbers[0]);
                    out.writeInt(numbers[1]);
                    out.writeByte(numbers[2]);
                } else {
                    out.writeShort(8);
                    out.writeInt(numbers[0]);
                    out.writeInt(numbers[1]);
                }
            } else if (value instanceof byte[]) {
                byte[] raw = (byte[]) value;
                out.writeShort(raw.length);
                out.write(raw);
            } else {
                string(out, String.valueOf(value));
            }
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(UTF8);
        out.writeShort(raw.length);
        out.write(raw);
    }

    // Decoding.

    /** Reads a whole message, up to its END tag. */
    public static Message decode(byte[] data) throws IOException {
        Reader in = new Reader(data);
        Message message = new Message(0, 0);
        message.major = in.u8();
        message.minor = in.u8();
        message.code = in.u16();
        message.id = in.s32();
        Group group = null;
        Attribute last = null;
        while (true) {
            int tag = in.u8();
            if (tag == END) break;
            if (tag < 0x10) {
                group = message.add(tag);
                last = null;
                continue;
            }
            if (group == null) throw new IOException("IPP value outside a group");
            String name = in.string();
            if (tag == BEGIN_COLLECTION) {
                in.skip(in.u16());
                List<Attribute> members = collection(in);
                if (name.isEmpty() && last != null) {
                    last.values.add(members);
                } else {
                    last = new Attribute(name, tag, members);
                    group.add(last);
                }
                continue;
            }
            Object value = value(in, tag);
            if (name.isEmpty() && last != null) {
                last.values.add(value);
            } else {
                last = new Attribute(name, tag);
                if (tag != NO_VALUE && value != null) last.values.add(value);
                group.add(last);
            }
        }
        return message;
    }

    private static List<Attribute> collection(Reader in) throws IOException {
        List<Attribute> members = new ArrayList<Attribute>();
        Attribute member = null;
        while (true) {
            int tag = in.u8();
            String name = in.string();
            if (tag == END_COLLECTION) {
                in.skip(in.u16());
                return members;
            }
            if (tag == MEMBER) {
                String memberName = new String(in.bytes(in.u16()), UTF8);
                member = null;
                // The member's first value comes next, with an empty name.
                int valueTag = in.u8();
                in.string();
                if (valueTag == BEGIN_COLLECTION) {
                    in.skip(in.u16());
                    member = new Attribute(memberName, valueTag, collection(in));
                } else {
                    member = new Attribute(memberName, valueTag);
                    Object value = value(in, valueTag);
                    if (value != null && valueTag != NO_VALUE) member.values.add(value);
                }
                members.add(member);
                continue;
            }
            if (member == null) throw new IOException("IPP collection value without a member name");
            if (tag == BEGIN_COLLECTION) {
                in.skip(in.u16());
                member.values.add(collection(in));
            } else {
                Object value = value(in, tag);
                if (value != null) member.values.add(value);
            }
            if (!name.isEmpty()) throw new IOException("IPP named value inside a collection");
        }
    }

    private static Object value(Reader in, int tag) throws IOException {
        int length = in.u16();
        switch (tag) {
            case INTEGER:
            case ENUM:
                if (length != 4) break;
                return in.s32();
            case BOOLEAN:
                if (length != 1) break;
                return in.u8() != 0;
            case RANGE:
                if (length != 8) break;
                return new int[] {in.s32(), in.s32()};
            case RESOLUTION:
                if (length != 9) break;
                return new int[] {in.s32(), in.s32(), in.u8()};
            case TEXT_LANG:
            case NAME_LANG: {
                byte[] raw = in.bytes(length);
                Reader inner = new Reader(raw);
                inner.string();
                return inner.string();
            }
            default:
                if (tag >= 0x40 && tag < 0x60) return new String(in.bytes(length), UTF8);
                return in.bytes(length);
        }
        return in.bytes(length);
    }

    private static final class Reader {
        private final byte[] data;
        private int at;

        Reader(byte[] data) {
            this.data = data;
        }

        private void need(int count) throws IOException {
            if (count < 0 || at + count > data.length) throw new IOException("IPP message cut short");
        }

        int u8() throws IOException {
            need(1);
            return data[at++] & 0xFF;
        }

        int u16() throws IOException {
            return (u8() << 8) | u8();
        }

        int s32() throws IOException {
            return (u16() << 16) | u16();
        }

        byte[] bytes(int count) throws IOException {
            need(count);
            byte[] out = Arrays.copyOfRange(data, at, at + count);
            at += count;
            return out;
        }

        void skip(int count) throws IOException {
            need(count);
            at += count;
        }

        String string() throws IOException {
            return new String(bytes(u16()), UTF8);
        }
    }
}

