package com.books.xp3.book;

import com.xp3.common.Check;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MobiTests {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void run(final Check check) {
        check.run("palmdoc", () -> palmDoc(check));
        check.run("mobi", () -> mobi(check));
    }

    private static void palmDoc(Check check) {
        String prose = "It was the best of times, it was the worst of times, it was the age of wisdom, "
            + "it was the age of foolishness. Été — café.\u0001\u0008 ~ @A ";
        StringBuilder long_ = new StringBuilder();
        while (long_.length() < 4096) long_.append(prose);
        byte[] text = long_.substring(0, 4096).getBytes(UTF8);
        byte[] packed = PalmDoc.compress(text, 0, text.length);
        check.ok("text compresses", packed.length < text.length / 2);
        check.ok("text round trip", java.util.Arrays.equals(text, PalmDoc.decompress(packed, 0, packed.length)));

        Random random = new Random(7);
        for (int round = 0; round < 50; round++) {
            byte[] data = new byte[1 + random.nextInt(4096)];
            // Small alphabets make matches; the full range makes literal runs.
            int range = round % 2 == 0 ? 256 : 4;
            for (int i = 0; i < data.length; i++) data[i] = (byte) (round % 4 == 1 ? ' ' + random.nextInt(range) : random.nextInt(range));
            byte[] out = PalmDoc.decompress(PalmDoc.compress(data, 0, data.length), 0, PalmDoc.compress(data, 0, data.length).length);
            if (!java.util.Arrays.equals(data, out)) {
                check.ok("random round trip " + round, false);
                return;
            }
        }
        check.ok("random round trips", true);

        byte[] offset = "xxxxabcabcabcabcyyyy".getBytes(UTF8);
        byte[] middle = PalmDoc.compress(offset, 4, 12);
        check.same("part of a buffer", "abcabcabcabc", new String(PalmDoc.decompress(middle, 0, middle.length), UTF8));

        byte[] split = "abé—".getBytes(UTF8);  // a b C3 A9 E2 80 94
        check.same("ends on a whole character", 0, Mobi.overlap(split, 0, 4));
        check.same("ends inside a two-byte one", 1, Mobi.overlap(split, 0, 3));
        check.same("ends inside a three-byte one", 2, Mobi.overlap(split, 0, 5));
        check.same("one byte left of it", 1, Mobi.overlap(split, 0, 6));
        check.same("at the very end", 0, Mobi.overlap(split, 0, 7));
    }

    private static void mobi(Check check) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "books-mobitest-" + System.nanoTime());
        dir.mkdirs();
        try {
            StringBuilder filler = new StringBuilder();
            for (int i = 0; i < 400; i++) filler.append("<p>Filler paragraph number ").append(i).append(" &mdash; caf&eacute;.</p>");
            File epub = new File(dir, "book.epub");
            String jpeg = "ÿØÿàJPEGCOVER";
            BookTests.zip(epub, new String[] {
                "mimetype", "application/epub+zip",
                "META-INF/container.xml", "<container><rootfiles><rootfile full-path='OEBPS/content.opf'/></rootfiles></container>",
                "OEBPS/content.opf", "<package version='3.0'><metadata><dc:title>A Converted Book</dc:title>"
                    + "<dc:creator>Ann Author</dc:creator><dc:language>en-GB</dc:language></metadata><manifest>"
                    + "<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>"
                    + "<item id='c1' href='Text/one.xhtml' media-type='application/xhtml+xml'/>"
                    + "<item id='c2' href='Text/two.xhtml' media-type='application/xhtml+xml'/>"
                    + "<item id='cov' href='Images/cover.jpg' media-type='image/jpeg' properties='cover-image'/>"
                    + "<item id='png' href='Images/fig.png' media-type='image/png'/></manifest>"
                    + "<spine><itemref idref='c1'/><itemref idref='c2'/></spine></package>",
                "OEBPS/nav.xhtml", "<html><body><nav epub:type='toc'><ol><li><a href='Text/one.xhtml'>One</a></li>"
                    + "<li><a href='Text/two.xhtml#later'>Two &amp; more</a></li></ol></nav></body></html>",
                "OEBPS/Text/one.xhtml", "<html><head><title>Hidden title</title><style>p { color: red }</style></head>"
                    + "<body><svg><image xlink:href='../Images/cover.jpg'/></svg>"
                    + "<h1 class='chapter'>One</h1><p class='first' style='text-align: center'>Some <span class='italic'>slanted</span>"
                    + " and <em>stressed</em> text, a <a href='two.xhtml#later'>link</a> and <a href='https://example.com/?a=1&amp;b=2'>a site</a>.</p>"
                    + "<div style='display:none'>Not shown</div><p>Unclosed <b>bold<p>next</p><img src='../Images/fig.png'/>"
                    + "<script>var x = 1 < 2;</script></body></html>",
                "OEBPS/Text/two.xhtml", "<html><body><h2>Two</h2>" + filler + "<p id='later'>Later on.</p></body></html>",
                "OEBPS/Images/cover.jpg", jpeg,
                "OEBPS/Images/fig.png", "\u0089PNG",
            }, "ISO-8859-1");

            File target = new File(dir, "book.mobi");
            Mobi.convert(epub, target, Mobi.AS_THEY_ARE);
            byte[] file = Files.readAllBytes(target.toPath());
            check.ok("no part file left", !new File(dir, ".book.mobi.part").exists());

            check.same("type and creator", "BOOKMOBI", new String(file, 60, 8, UTF8));
            int count = u16(file, 76);
            int[] offsets = new int[count + 1];
            for (int i = 0; i < count; i++) offsets[i] = u32(file, 78 + 8 * i);
            offsets[count] = file.length;
            int r0 = offsets[0];
            check.same("record 0 after the list", 78 + 8 * count + 2, r0);
            check.same("palmdoc compression", 2, u16(file, r0));
            int textLength = u32(file, r0 + 4);
            int textRecords = u16(file, r0 + 8);
            check.same("record size", 4096, u16(file, r0 + 10));
            check.same("mobi magic", "MOBI", new String(file, r0 + 16, 4, UTF8));
            check.same("mobi header length", 0xE8, u32(file, r0 + 20));
            check.same("utf-8", 65001, u32(file, r0 + 28));
            check.same("version 6", 6, u32(file, r0 + 36));
            check.same("first non-book record", textRecords + 1, u32(file, r0 + 80));
            check.same("english", 9, u32(file, r0 + 92));
            int firstImage = u32(file, r0 + 108);
            check.same("first image follows the text", textRecords + 1, firstImage);
            check.same("exth flag", 0x50, u32(file, r0 + 128));
            check.same("last content is the image", firstImage, u16(file, r0 + 194));
            check.same("multibyte trailing entries", 1, u32(file, r0 + 240));
            int nameOffset = u32(file, r0 + 84);
            int nameLength = u32(file, r0 + 88);
            check.same("full name", "A Converted Book", new String(file, r0 + nameOffset, nameLength, UTF8));

            check.same("exth magic", "EXTH", new String(file, r0 + 248, 4, UTF8));
            int exthCount = u32(file, r0 + 256);
            String author = null;
            String language = null;
            int coverOffset = -1;
            for (int i = 0, at = r0 + 260; i < exthCount; i++) {
                int type = u32(file, at);
                int length = u32(file, at + 4);
                if (type == 100) author = new String(file, at + 8, length - 8, UTF8);
                if (type == 524) language = new String(file, at + 8, length - 8, UTF8);
                if (type == 201) coverOffset = u32(file, at + 8);
                at += length;
            }
            check.same("author", "Ann Author", author);
            check.same("language", "en-GB", language);
            check.same("cover is the first image", 0, coverOffset);
            String image = new String(file, offsets[firstImage], offsets[firstImage + 1] - offsets[firstImage], "ISO-8859-1");
            check.same("cover record", jpeg, image);
            check.same("png left out: one image", "FLIS", new String(file, offsets[firstImage + 1], 4, UTF8));
            check.same("fcis", "FCIS", new String(file, offsets[firstImage + 2], 4, UTF8));
            check.same("text length in fcis", textLength, u32(file, offsets[firstImage + 2] + 16));

            check.ok("several text records", textRecords > 2);
            java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
            for (int i = 1; i <= textRecords; i++) {
                int start = offsets[i];
                int size = offsets[i + 1] - start;
                int trailing = (file[start + size - 1] & 3) + 1;
                byte[] record = PalmDoc.decompress(file, start, size - trailing);
                if (i < textRecords && record.length != 4096) check.same("record " + i + " is full", 4096, record.length);
                text.write(record, 0, record.length);
            }
            byte[] bytes = text.toByteArray();
            check.same("text length", textLength, bytes.length);
            String html = new String(bytes, UTF8);
            check.ok("multibyte text intact", html.contains("Filler paragraph number 399 — café."));
            check.ok("guide", html.startsWith("<html><head><guide><reference title=\"Table of Contents\" type=\"toc\" filepos="));
            check.ok("head left out", !html.contains("Hidden title") && !html.contains("color: red"));
            check.ok("script left out", !html.contains("var x"));
            check.ok("hidden left out", !html.contains("Not shown"));
            check.ok("svg cover kept", html.contains("<img recindex=\"00001\" />"));
            check.ok("unusable image left out", !html.contains("recindex=\"00002\""));
            check.ok("class italic", html.contains("<i>slanted</i>"));
            check.ok("em kept", html.contains("<em>stressed</em>"));
            check.ok("centred", html.contains("<p align=\"center\">Some"));
            check.ok("heading", html.contains("<h1>One</h1>"));
            check.ok("external link", html.contains("<a href=\"https://example.com/?a=1&amp;b=2\">a site</a>"));
            check.ok("unclosed bold closed", html.contains("<p>Unclosed <b>bold<p>next</p></b></p>"));
            check.ok("page breaks", html.contains("<mbp:pagebreak />"));
            check.ok("contents label escaped", html.contains(">Two &amp; more</a>"));
            check.ok("ends cleanly", html.endsWith("</body></html>"));

            // Every filepos lands on what it names.
            Matcher m = Pattern.compile("filepos=(\\d{10})").matcher(html);
            List<Integer> positions = new ArrayList<Integer>();
            while (m.find()) positions.add(Integer.valueOf(m.group(1)));
            check.same("filepos count", 5, positions.size());
            check.ok("toc reference", at(bytes, positions.get(0)).startsWith("<h2>Contents</h2>"));
            check.ok("text reference", at(bytes, positions.get(1)).startsWith("<img recindex"));
            check.ok("contents to chapter", at(bytes, positions.get(2)).startsWith("<img recindex"));
            check.ok("contents to anchor", at(bytes, positions.get(3)).startsWith("<p>Later on."));
            check.ok("link to anchor", at(bytes, positions.get(4)).startsWith("<p>Later on."));

            File bare = new File(dir, "bare.epub");
            BookTests.zip(bare, new String[] {
                "content.opf", "<package><metadata><title>Bare</title></metadata><manifest>"
                    + "<item id='a' href='a.html' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>",
                "a.html", "<p>No body, no contents.</p>",
            });
            String plain = new String(textOf(Mobi.convert(bare, Mobi.AS_THEY_ARE)), UTF8);
            check.ok("no contents page", !plain.contains("Contents") && !plain.contains("type=\"toc\""));
            check.ok("bodyless document", plain.contains("<p>No body, no contents.</p>"));

            File empty = new File(dir, "empty.epub");
            BookTests.zip(empty, new String[] {"content.opf", "<package><manifest/><spine/></package>"});
            check.fails("nothing to convert", "no text", () -> Mobi.convert(empty, Mobi.AS_THEY_ARE));
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
    }

    private static byte[] textOf(byte[] file) {
        int count = u16(file, 76);
        int r0 = u32(file, 78);
        int records = u16(file, r0 + 8);
        java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
        for (int i = 1; i <= records; i++) {
            int start = u32(file, 78 + 8 * i);
            int end = i + 1 < count ? u32(file, 78 + 8 * (i + 1)) : file.length;
            int trailing = (file[end - 1] & 3) + 1;
            byte[] record = PalmDoc.decompress(file, start, end - start - trailing);
            text.write(record, 0, record.length);
        }
        return text.toByteArray();
    }

    private static String at(byte[] text, int position) {
        return new String(text, position, Math.min(40, text.length - position), UTF8);
    }

    private static int u16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static int u32(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }
}
