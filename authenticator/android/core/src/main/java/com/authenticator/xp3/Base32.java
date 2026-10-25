package com.authenticator.xp3;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** RFC 4648 Base32, the alphabet two-factor secret keys are written in. */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {}

    /**
     * A key as people write it, tidied: upper case, without the spaces,
     * hyphens and padding that services add to make it readable.
     */
    public static String tidy(String key) {
        if (key == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : key.toUpperCase(Locale.US).toCharArray()) {
            if (c == ' ' || c == '-' || c == '=' || c == '\t' || c == '\n' || c == '\r') continue;
            out.append(c);
        }
        return out.toString();
    }

    /** The bytes of a tidied key; IllegalArgumentException when it isn't Base32. */
    public static byte[] decode(String key) {
        String tidied = tidy(key);
        if (tidied.length() == 0) throw new IllegalArgumentException("The secret key is empty");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < tidied.length(); i++) {
            int value = ALPHABET.indexOf(tidied.charAt(i));
            if (value < 0) throw new IllegalArgumentException("The secret key can only have letters and the digits 2 to 7");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        if (out.size() == 0) throw new IllegalArgumentException("The secret key is too short");
        return out.toByteArray();
    }

    public static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }
}
