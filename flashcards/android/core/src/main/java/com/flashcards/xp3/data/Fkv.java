package com.flashcards.xp3.data;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FKV (Flashcards Key-Value), the flashcards Transform makes and Menu >
 * Import reads: UTF-8 text, one card per line, key||value.
 *
 * The key and the value are written as the body of a string literal: a
 * backslash starts an escape (\n, \r, \t, \\, \|, \0, \a, \b, \f, \v, \',
 * \", and a code point: \x and 2 hex digits, or a backslash, u or U, and 4 or
 * 8), and anything else stands for itself, quotes included. The first unescaped "||" splits the line; another one in the
 * line is an error, so a literal || is written \|\|. Blank lines are
 * skipped, a \r before a line's end is dropped, and so is a leading BOM. A
 * card needs a key and a value.
 *
 * The command center checks what Transform submits with the same rules
 * (internal/fkv in thooton/delta); change them together.
 */
public final class Fkv {

    private Fkv() {}

    /** Why a file isn't FKV, naming the line. */
    public static final class Malformed extends Exception {
        Malformed(String why) {
            super(why);
        }
    }

    /** One card: its front and its back. */
    public static final class Card {
        public final String key;
        public final String value;

        Card(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    /** The cards in an FKV file, in order. */
    public static List<Card> parse(byte[] data) throws Malformed {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new Malformed("It isn't UTF-8 text");
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        List<Card> cards = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.trim().isEmpty()) continue;
            try {
                cards.add(line(line));
            } catch (Malformed e) {
                throw new Malformed("Line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return cards;
    }

    /** One line, key||value. */
    static Card line(String line) throws Malformed {
        StringBuilder[] parts = {new StringBuilder(), new StringBuilder()};
        int part = 0;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i = unescape(line, i, parts[part]);
            } else if (c == '|' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                if (part == 1) throw new Malformed("a second || (write a literal one as \\|\\|)");
                part = 1;
                i += 2;
            } else {
                parts[part].append(c);
                i++;
            }
        }
        if (part == 0) throw new Malformed("no || between the key and the value");
        if (parts[0].length() == 0) throw new Malformed("the key is empty");
        if (parts[1].length() == 0) throw new Malformed("the value is empty");
        return new Card(parts[0].toString(), parts[1].toString());
    }

    /** Appends the escape at `at` to `out`; returns where it ends. */
    private static int unescape(String s, int at, StringBuilder out) throws Malformed {
        if (at + 1 >= s.length()) throw new Malformed("a lone \\ at the end of the line (write a literal one as \\\\)");
        char e = s.charAt(at + 1);
        String simple = "nrt\\|0abfv'\"";
        String means = "\n\r\t\\|\u0000\u0007\b\f\u000B'\"";
        int k = simple.indexOf(e);
        if (k >= 0) {
            out.append(means.charAt(k));
            return at + 2;
        }
        int digits = e == 'x' ? 2 : e == 'u' ? 4 : e == 'U' ? 8 : 0;
        if (digits == 0) {
            throw new Malformed("\\" + new String(Character.toChars(s.codePointAt(at + 1)))
                + " isn't an escape (write a literal backslash as \\\\)");
        }
        if (at + 2 + digits > s.length()) throw new Malformed("\\" + e + " needs " + digits + " hex digits");
        String hex = s.substring(at + 2, at + 2 + digits);
        long n = -1;
        if (hex.matches("[0-9A-Fa-f]+")) n = Long.parseLong(hex, 16);
        if (n < 0 || n > Character.MAX_CODE_POINT || (n >= 0xD800 && n <= 0xDFFF)) {
            throw new Malformed("\\" + e + hex + " isn't a character");
        }
        out.appendCodePoint((int) n);
        return at + 2 + digits;
    }
}
