package com.mail.xp3.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One mailbox: "Jane Doe <jane@example.com>", or just the address. */
public final class Address {

    public final String name;
    public final String email;

    public Address(String name, String email) {
        this.name = name == null ? "" : name.trim();
        this.email = email.trim();
    }

    /**
     * Every address in a header, skipping what isn't one ("undisclosed
     * recipients:;"). Commas inside quotes and angle brackets don't split.
     */
    public static List<Address> parse(String header) {
        List<Address> out = new ArrayList<Address>();
        if (header == null) return out;
        for (String piece : split(Mime.words(header))) {
            Address address = one(piece);
            if (address != null) out.add(address);
        }
        return out;
    }

    /**
     * What was typed in To or Cc: every piece must be an address, so a typo
     * is caught before sending rather than bounced after.
     */
    public static List<Address> typed(String text) {
        List<Address> out = new ArrayList<Address>();
        for (String piece : split(text == null ? "" : text)) {
            if (piece.trim().length() == 0) continue;
            Address address = one(piece);
            if (address == null || !address.email.matches("[^\\s@<>(),;:\"]+@[^\\s@<>(),;:\"]+\\.[^\\s@<>(),;:\"]+")) {
                throw new IllegalArgumentException("Not an address: " + piece.trim());
            }
            out.add(address);
        }
        return out;
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<String>();
        StringBuilder piece = new StringBuilder();
        boolean quoted = false;
        boolean angled = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && quoted && i + 1 < text.length()) {
                piece.append(c).append(text.charAt(++i));
                continue;
            }
            if (c == '"') quoted = !quoted;
            else if (c == '<' && !quoted) angled = true;
            else if (c == '>' && !quoted) angled = false;
            if ((c == ',' || c == ';') && !quoted && !angled) {
                out.add(piece.toString());
                piece.setLength(0);
            } else {
                piece.append(c);
            }
        }
        out.add(piece.toString());
        return out;
    }

    private static Address one(String piece) {
        String text = piece.trim();
        // A group's name ("Friends: a@x, b@y;") is not an address.
        int colon = text.indexOf(':');
        if (colon >= 0 && text.indexOf('"') < 0 && (text.indexOf('<') < 0 || colon < text.indexOf('<'))) {
            text = text.substring(colon + 1).trim();
        }
        int open = text.lastIndexOf('<');
        int close = text.lastIndexOf('>');
        String name;
        String email;
        if (open >= 0 && close > open) {
            email = text.substring(open + 1, close).trim();
            name = text.substring(0, open).trim();
        } else {
            // bare@address (Comment)
            email = text.replaceAll("\\(.*?\\)", "").trim();
            int paren = text.indexOf('(');
            name = paren >= 0 && text.indexOf(')', paren) > paren ? text.substring(paren + 1, text.indexOf(')', paren)) : "";
        }
        if (email.indexOf('@') <= 0) return null;
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (name.equalsIgnoreCase(email)) name = "";
        return new Address(name, email);
    }

    /** The name if there is one, else the address. */
    public String display() {
        return name.length() > 0 ? name : email;
    }

    /** How a list of several senders names this one: a first name, or the address's local part. */
    public String brief() {
        if (name.length() > 0) {
            String first = name.split("[\\s,]+")[0];
            return first.length() > 0 ? first : name;
        }
        return email.substring(0, email.indexOf('@'));
    }

    public boolean is(String other) {
        return other != null && email.equalsIgnoreCase(other.trim());
    }

    /** For a header: the name quoted (and encoded when it isn't ASCII), then the address. */
    public String header() {
        if (name.length() == 0) return email;
        String shown = Mime.ascii(name)
            ? "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            : Mime.encodeWord(name);
        return shown + " <" + email + ">";
    }

    public static String header(List<Address> addresses) {
        StringBuilder out = new StringBuilder();
        for (Address address : addresses) {
            if (out.length() > 0) out.append(", ");
            out.append(address.header());
        }
        return out.toString();
    }

    /** "Jane Doe <jane@x.com>, bob@y.org", for reading. */
    public static String display(List<Address> addresses) {
        StringBuilder out = new StringBuilder();
        for (Address address : addresses) {
            if (out.length() > 0) out.append(", ");
            out.append(address.name.length() > 0 ? address.name + " <" + address.email + ">" : address.email);
        }
        return out.toString();
    }

    /** For a To field: "Jane Doe <jane@x.com>, bob@y.org", names quoted where a comma would split them. */
    public static String field(List<Address> addresses) {
        StringBuilder out = new StringBuilder();
        for (Address address : addresses) {
            if (out.length() > 0) out.append(", ");
            if (address.name.length() == 0) {
                out.append(address.email);
                continue;
            }
            boolean special = address.name.matches(".*[,;<>\"@:()].*");
            out.append(special ? "\"" + address.name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : address.name)
                .append(" <").append(address.email).append('>');
        }
        return out.toString();
    }

    /** Just the addresses, comma separated. */
    public static String emails(List<Address> addresses) {
        StringBuilder out = new StringBuilder();
        for (Address address : addresses) {
            if (out.length() > 0) out.append(", ");
            out.append(address.email);
        }
        return out.toString();
    }

    public static boolean contains(List<Address> addresses, String email) {
        for (Address address : addresses) if (address.is(email)) return true;
        return false;
    }

    public String key() {
        return email.toLowerCase(Locale.US);
    }

    @Override
    public String toString() {
        return header();
    }
}
