package com.authenticator.xp3;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The accounts in what a two-factor QR code holds:
 *
 * - otpauth://totp/Issuer:account?secret=...&issuer=...&algorithm=...&digits=...&period=...
 *   (Google's Key Uri Format, which nearly every service uses), and
 * - otpauth-migration://offline?data=... (Google Authenticator's "Transfer
 *   accounts" export, a protocol buffer of several accounts).
 *
 * Only time-based codes are taken; counter-based (HOTP) ones are refused, as
 * almost no service still uses them.
 */
public final class OtpLink {

    private OtpLink() {}

    /** The accounts in `text`; IllegalArgumentException, phrased for a person, when there are none. */
    public static List<Account> parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.startsWith("otpauth://")) {
            List<Account> one = new ArrayList<>();
            one.add(otpauth(trimmed));
            return one;
        }
        if (lower.startsWith("otpauth-migration://")) return migration(trimmed);
        throw new IllegalArgumentException("That QR code isn't for two-factor codes");
    }

    private static Account otpauth(String uri) {
        String rest = uri.substring("otpauth://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("That two-factor link is incomplete");
        String type = rest.substring(0, slash).toLowerCase(Locale.US);
        if (type.equals("hotp")) throw new IllegalArgumentException("Only time-based codes are supported");
        if (!type.equals("totp")) throw new IllegalArgumentException("That two-factor link is of an unknown kind");
        rest = rest.substring(slash + 1);
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);
        int question = rest.indexOf('?');
        String label = decode(question < 0 ? rest : rest.substring(0, question), true);
        Map<String, String> query = query(question < 0 ? "" : rest.substring(question + 1));
        String issuer = query.get("issuer");
        String account = label;
        int colon = label.indexOf(':');
        if (colon >= 0) {
            String prefix = label.substring(0, colon).trim();
            account = label.substring(colon + 1).trim();
            if (issuer == null || issuer.trim().length() == 0) issuer = prefix;
        }
        String secret = query.get("secret");
        if (secret == null || secret.trim().length() == 0) throw new IllegalArgumentException("That two-factor link has no secret key");
        return new Account(null, issuer, account, secret, query.get("algorithm"),
            number(query.get("digits")), number(query.get("period")));
    }

    private static int number(String value) {
        if (value == null || value.trim().length() == 0) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("That two-factor link has a malformed setting");
        }
    }

    private static Map<String, String> query(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.length() == 0) continue;
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals), true).toLowerCase(Locale.US);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            // Base64 data keeps its plus signs; anywhere else a plus is a space.
            out.put(name, decode(value, !name.equals("data")));
        }
        return out;
    }

    /** Percent-decoding as UTF-8. */
    static String decode(String text, boolean plusIsSpace) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length()) {
                int high = hex(text.charAt(i + 1));
                int low = hex(text.charAt(i + 2));
                if (high >= 0 && low >= 0) {
                    out.write(high * 16 + low);
                    i += 2;
                    continue;
                }
            }
            if (c == '+' && plusIsSpace) {
                out.write(' ');
                continue;
            }
            int length = Character.charCount(text.codePointAt(i));
            byte[] bytes = text.substring(i, i + length).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            i += length - 1;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static List<Account> migration(String uri) {
        int question = uri.indexOf('?');
        String data = question < 0 ? null : query(uri.substring(question + 1)).get("data");
        if (data == null || data.length() == 0) throw new IllegalArgumentException("That export has no accounts in it");
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(data.replace('-', '+').replace('_', '/').trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That export is damaged");
        }
        List<Account> accounts = new ArrayList<>();
        int skippedHotp = 0;
        Proto message = new Proto(payload);
        while (message.more()) {
            int field = message.tag();
            if (field == 1 && message.wire == 2) {
                Account account = parameters(new Proto(message.bytes()));
                if (account == null) skippedHotp++;
                else accounts.add(account);
            } else {
                message.skip();
            }
        }
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException(skippedHotp > 0 ? "Only time-based codes are supported" : "That export has no accounts in it");
        }
        return accounts;
    }

    /** One OtpParameters message; null for a counter-based account. */
    private static Account parameters(Proto message) {
        byte[] secret = null;
        String name = "";
        String issuer = "";
        long algorithm = 0;
        long digits = 0;
        long type = 0;
        while (message.more()) {
            int field = message.tag();
            if (field == 1 && message.wire == 2) secret = message.bytes();
            else if (field == 2 && message.wire == 2) name = new String(message.bytes(), StandardCharsets.UTF_8);
            else if (field == 3 && message.wire == 2) issuer = new String(message.bytes(), StandardCharsets.UTF_8);
            else if (field == 4 && message.wire == 0) algorithm = message.varint();
            else if (field == 5 && message.wire == 0) digits = message.varint();
            else if (field == 6 && message.wire == 0) type = message.varint();
            else message.skip();
        }
        if (type == 1) return null;
        if (secret == null || secret.length == 0) throw new IllegalArgumentException("That export is damaged");
        String account = name;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            String prefix = name.substring(0, colon).trim();
            account = name.substring(colon + 1).trim();
            if (issuer.trim().length() == 0) issuer = prefix;
        }
        String hash = algorithm == 2 ? "SHA256" : algorithm == 3 ? "SHA512" : algorithm == 4 ? "MD5" : "SHA1";
        return new Account(null, issuer, account, Base32.encode(secret), hash, digits == 2 ? 8 : 6, 30);
    }

    /** Just enough of the protocol buffer wire format to read the export. */
    private static final class Proto {
        private final byte[] bytes;
        private int position;
        int wire;

        Proto(byte[] bytes) {
            this.bytes = bytes;
        }

        boolean more() {
            return position < bytes.length;
        }

        int tag() {
            long key = varint();
            wire = (int) (key & 7);
            return (int) (key >>> 3);
        }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position >= bytes.length) throw new IllegalArgumentException("That export is damaged");
                int b = bytes[position++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("That export is damaged");
        }

        byte[] bytes() {
            long length = varint();
            if (length < 0 || position + length > bytes.length) throw new IllegalArgumentException("That export is damaged");
            byte[] out = new byte[(int) length];
            System.arraycopy(bytes, position, out, 0, out.length);
            position += out.length;
            return out;
        }

        void skip() {
            if (wire == 0) varint();
            else if (wire == 1) position += 8;
            else if (wire == 2) bytes();
            else if (wire == 5) position += 4;
            else throw new IllegalArgumentException("That export is damaged");
        }
    }
}
