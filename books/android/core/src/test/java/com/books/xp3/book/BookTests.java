package com.books.xp3.book;

import com.xp3.common.Check;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BookTests {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void run(final Check check) {
        check.run("markup", () -> markup(check));
        check.run("html", () -> html(check));
        check.run("sections", () -> sections(check));
        check.run("epub", () -> epub(check));
        check.run("plain text", () -> plainText(check));
    }

    private static void markup(Check check) {
        String named = "a &amp; b &mdash; &eacute;";
        check.same("named", "a & b \u2014 \u00E9", Markup.decode(named, 0, named.length()));
        check.same("numeric", "\u2019A", Markup.decode("&#8217;&#x41;", 0, 13));
        check.same("unknown kept", "&bogus; & x", Markup.decode("&bogus; & x", 0, 11));

        Markup markup = new Markup("<?xml version='1.0'?><!-- hi --><dc:Title id=\"t\" class=x>A &lt; B</dc:Title><br/><p>x</p>");
        check.ok("start", markup.next() && markup.type == Markup.START);
        check.same("name lowered, prefix gone", "title", markup.name);
        check.same("quoted attr", "t", markup.attr("id"));
        check.same("bare attr", "x", markup.attr("class"));
        check.ok("text", markup.next() && markup.type == Markup.TEXT);
        check.same("text decoded", "A < B", markup.text);
        check.ok("end", markup.next() && markup.type == Markup.END && "title".equals(markup.name));
        check.ok("self closing", markup.next() && markup.selfClosing && "br".equals(markup.name));

        Markup script = new Markup("<script>if (a < b) x();</script><p>t</p>");
        script.next();
        check.ok("script start", script.type == Markup.START && "script".equals(script.name));
        script.next();
        check.ok("script body skipped", script.type == Markup.END && "script".equals(script.name));
    }

    private static Chapter html(String body) {
        Chapter chapter = new Chapter();
        Html.read("<html><head><title>Not shown</title><style>p{}</style></head><body>" + body + "</body></html>", chapter);
        return chapter;
    }

    private static void html(Check check) {
        Chapter c = html("<h1 id='top'>Chapter\n One</h1><p>It was a <i>dark</i>   and\n<b>stormy</b> night.</p><p>Second.</p>");
        check.same("text", "Chapter One\nIt was a dark and stormy night.\nSecond.", c.text.toString());
        check.same("paragraphs", 3, c.paragraphs.size());
        check.same("heading", Paragraph.HEADING, c.paragraphs.get(0).kind);
        check.same("heading level", 1, c.paragraphs.get(0).level);
        check.ok("heading centred", c.paragraphs.get(0).centered);
        check.ok("first after heading is flush", c.paragraphs.get(1).opening);
        check.ok("next is indented", !c.paragraphs.get(2).opening);
        check.same("runs", 2, c.runs.size());
        Run italic = c.runs.get(0);
        check.same("italic text", "dark", c.text.substring(italic.start, italic.end));
        check.same("italic style", Run.ITALIC, italic.style);
        check.same("bold style", Run.BOLD, c.runs.get(1).style);
        check.same("anchor", Integer.valueOf(0), c.anchors.get("top"));
        check.same("first heading", "Chapter One", c.firstHeading());

        Chapter gap = html("<p>One.</p><p>&#160;</p><p>Two.</p><hr/><p>Three.</p>");
        check.same("spacer dropped", "One.\nTwo.\n* * *\nThree.", gap.text.toString());
        check.ok("after spacer: flush", gap.paragraphs.get(1).opening);
        check.ok("after spacer: spaced", gap.paragraphs.get(1).spaced);
        check.same("break kind", Paragraph.BREAK, gap.paragraphs.get(2).kind);
        check.ok("after break: flush", gap.paragraphs.get(3).opening);

        Chapter headed = html("<h2>Title</h2><p><span> </span></p><p>Body.</p>");
        check.ok("spacer after a heading adds nothing", !headed.paragraphs.get(1).spaced);

        Chapter verse = html("<p class='poem'>Line one<br/>Line two<br/><br/>Line three</p>");
        check.same("verse lines", 3, verse.paragraphs.size());
        check.ok("verse continuation not spaced", !verse.paragraphs.get(1).spaced);
        check.ok("double break spaced", verse.paragraphs.get(2).spaced);

        Chapter list = html("<ol start='3'><li>Three</li><li>Four</li></ol><ul><li> Dot</li></ul>");
        check.same("list", "3.\u00A0Three\n4.\u00A0Four\n\u2022\u00A0Dot", list.text.toString());
        check.same("item kind", Paragraph.ITEM, list.paragraphs.get(0).kind);

        Chapter hidden = html("<div style='display: none'>secret</div><svg><text>x</text></svg><p style='text-align:center'>Fin</p>");
        check.same("hidden content skipped", "Fin", hidden.text.toString());
        check.ok("inline centring", hidden.paragraphs.get(0).centered);

        Chapter sloppy = html("<p>Unclosed <em>emphasis<p>Next & last</div></p>");
        check.same("sloppy text", "Unclosed emphasis\nNext & last", sloppy.text.toString());

        Chapter quote = html("<blockquote><p>Quoted <span class='italic'>words</span></p></blockquote>");
        check.same("quote kind", Paragraph.QUOTE, quote.paragraphs.get(0).kind);
        check.same("class italic", Run.ITALIC, quote.runs.get(0).style);

        Chapter pre = html("<pre>  first\n    second</pre>");
        check.same("pre lines", "first\nsecond", pre.text.toString());
    }

    private static void sections(Check check) {
        Chapter chapter = new Chapter();
        StringBuilder sentence = new StringBuilder();
        for (int i = 0; i < 20; i++) sentence.append("Word word word word word. ");
        String para = sentence.toString().trim(); // ~520 characters
        int count = 120;
        for (int i = 0; i < count; i++) {
            if (i == 40) chapter.sceneBreak();
            chapter.append(para, i % 7 == 0 ? Run.ITALIC : 0, Paragraph.BODY, 0, false);
            chapter.end();
        }
        chapter.anchor("end");
        List<Chapter> chapters = new ArrayList<Chapter>();
        Chapter intro = new Chapter();
        intro.append("Intro", 0, Paragraph.HEADING, 1, true);
        intro.end();
        chapters.add(intro);
        chapters.add(new Chapter()); // An empty spine document, such as a cover page.
        chapters.add(chapter);
        List<Book.Link> links = new ArrayList<Book.Link>();
        links.add(new Book.Link("Intro", 0, 0, null));
        links.add(new Book.Link("Cover", 0, 1, null));
        links.add(new Book.Link("Main", 0, 2, null));
        links.add(new Book.Link("Last", 1, 2, "end"));
        Book book = Book.assemble("T", "A", chapters, links);

        check.ok("several sections", book.sections.size() >= 4);
        long total = 0;
        boolean sizes = true;
        for (int i = 0; i < book.sections.size(); i++) {
            Section s = book.sections.get(i);
            check.same("section " + i + " start", Long.valueOf(total), Long.valueOf(s.start));
            total += s.length();
            if (s.chapter == 2 && i < book.sections.size() - 1 && s.length() > Book.SECTION_MAX + para.length()) sizes = false;
            for (Run run : s.runs) {
                if (run.start < 0 || run.end > s.length() || run.start >= run.end) sizes = false;
            }
            Paragraph last = s.paragraphs.get(s.paragraphs.size() - 1);
            check.same("section " + i + " ends with its last paragraph", s.length(), last.end);
        }
        check.ok("sizes and runs in range", sizes);
        check.same("length", Long.valueOf(total), Long.valueOf(book.length));
        // The scene break at paragraph 40 (~21,000 characters in) is a pause
        // inside the window, so the first cut of chapter 2 is there.
        Section second = book.sections.get(2);
        check.same("cut at the scene break", Paragraph.BREAK, second.paragraphs.get(0).kind);

        check.same("contents", 4, book.contents.size());
        check.same("cover points at the next text", Long.valueOf(book.sections.get(1).start), Long.valueOf(book.contents.get(1).offset));
        check.same("anchor at the end", Long.valueOf(book.length), Long.valueOf(book.contents.get(3).offset));
        check.same("sectionAt start", 0, book.sectionAt(0));
        check.same("sectionAt end", book.sections.size() - 1, book.sectionAt(book.length + 5));
        check.same("sectionAt boundary", 2, book.sectionAt(book.sections.get(2).start));
        check.same("contentsAt", 2, book.contentsAt(book.sections.get(2).start + 10));
        check.same("next contents", Long.valueOf(book.length), Long.valueOf(book.nextContentsOffset(book.sections.get(2).start)));

        List<Book.Link> thin = new ArrayList<Book.Link>();
        thin.add(new Book.Link("Start", 0, 0, null));
        List<Chapter> headed = new ArrayList<Chapter>();
        for (int i = 1; i <= 3; i++) {
            Chapter c = new Chapter();
            c.append("Part " + i, 0, Paragraph.HEADING, 1, true);
            c.end();
            c.append("Text.", 0, Paragraph.BODY, 0, false);
            c.end();
            headed.add(c);
        }
        Book byHeadings = Book.assemble("H", null, headed, thin);
        check.same("one-line contents replaced by headings", 3, byHeadings.contents.size());
        check.same("heading entry", "Part 2", byHeadings.contents.get(1).title);

        Book none = Book.assemble("Empty", null, new ArrayList<Chapter>(), null);
        check.same("empty book still has a page", 1, none.sections.size());
    }

    private static void epub(Check check) throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "books-selftest-" + System.nanoTime());
        dir.mkdirs();
        try {
            File three = new File(dir, "three.epub");
            zip(three, new String[] {
                "mimetype", "application/epub+zip",
                "META-INF/container.xml", "<?xml version='1.0'?><container><rootfiles><rootfile full-path='OEBPS/content.opf' media-type='application/oebps-package+xml'/></rootfiles></container>",
                "OEBPS/content.opf", "<?xml version='1.0' encoding='UTF-8'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                    + "<dc:title>  The   Test Book </dc:title><dc:creator>Ann Author</dc:creator><dc:creator>Second</dc:creator></metadata>"
                    + "<manifest><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>"
                    + "<item id='c1' href='Text/one.xhtml' media-type='application/xhtml+xml'/>"
                    + "<item id='c2' href='Text/two%20words.xhtml' media-type='application/xhtml+xml'/>"
                    + "<item id='skip' href='Text/notes.xhtml' media-type='application/xhtml+xml'/>"
                    + "<item id='img' href='Images/cover.jpg' media-type='image/jpeg' properties='cover-image'/></manifest>"
                    + "<spine><itemref idref='c1'/><itemref idref='c2'/><itemref idref='skip' linear='no'/><itemref idref='missing'/></spine></package>",
                "OEBPS/nav.xhtml", "<html><body><nav epub:type='landmarks'><ol><li><a href='Text/one.xhtml'>Landmark</a></li></ol></nav>"
                    + "<nav epub:type='toc'><ol><li><a href='Text/one.xhtml'>One</a></li><li><a href='Text/two%20words.xhtml'>Two</a>"
                    + "<ol><li><a href='Text/two%20words.xhtml#part'><span>Two,</span> part B</a></li></ol></li></ol></nav></body></html>",
                "OEBPS/Text/one.xhtml", "<html><body><h1>One</h1><p>First chapter.</p></body></html>",
                "OEBPS/Text/two words.xhtml", "<html><body><h1>Two</h1><p>Opening.</p><p id='part'>Part B.</p></body></html>",
                "OEBPS/Text/notes.xhtml", "<html><body><p>Not in the reading order.</p></body></html>",
                "OEBPS/Images/cover.jpg", "JPEGDATA",
            });
            Epub.Info info = Epub.info(three);
            check.same("title", "The Test Book", info.title.replaceAll("\\s+", " ").trim());
            check.same("first author", "Ann Author", info.author);
            check.same("cover bytes", "JPEGDATA", new String(Epub.cover(three), UTF8));
            Book book = Epub.read(three);
            check.same("chapters", 2, book.sections.size());
            check.same("text", "One\nFirst chapter.", book.sections.get(0).text);
            check.same("nav contents", 3, book.contents.size());
            check.same("nav label", "Two, part B", book.contents.get(2).title);
            check.same("nav depth", 1, book.contents.get(2).depth);
            long partB = book.sections.get(1).start + book.sections.get(1).text.indexOf("Part B.");
            check.same("anchor offset", Long.valueOf(partB), Long.valueOf(book.contents.get(2).offset));

            File two = new File(dir, "two.epub");
            zip(two, new String[] {
                "META-INF/container.xml", "<container><rootfiles><rootfile full-path='wrong/path.opf'/></rootfiles></container>",
                "content.opf", "<package><metadata><title>NCX book</title><meta name='cover' content='cov'/></metadata><manifest>"
                    + "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/><item id='a' href='a.html' media-type='application/xhtml+xml'/>"
                    + "<item id='cov' href='c.png' media-type='image/png'/></manifest><spine toc='ncx'><itemref idref='a'/></spine></package>",
                "toc.ncx", "<ncx><docTitle><text>Ignored</text></docTitle><navMap><navPoint><navLabel><text>Start</text></navLabel><content src='a.html'/>"
                    + "<navPoint><navLabel><text>Inner</text></navLabel><content src='A.HTML#x'/></navPoint></navPoint></navMap></ncx>",
                "a.html", "<?xml version='1.0' encoding='ISO-8859-1'?><html><body><p>Caf\u00E9</p><p id='x'>Inner text &hellip;</p></body></html>",
                "c.png", "PNG",
            }, "ISO-8859-1");
            Book ncx = Epub.read(two);
            check.same("opf found without container", "NCX book", ncx.title);
            check.same("declared encoding", "Caf\u00E9\nInner text \u2026", ncx.sections.get(0).text);
            check.same("ncx contents", 2, ncx.contents.size());
            check.same("ncx depth", 1, ncx.contents.get(1).depth);
            check.same("ncx anchor, other case", Long.valueOf(5), Long.valueOf(ncx.contents.get(1).offset));
            check.same("meta cover", "PNG", new String(Epub.cover(two), UTF8));

            final File junk = new File(dir, "junk.epub");
            FileOutputStream out = new FileOutputStream(junk);
            out.write("not a zip".getBytes(UTF8));
            out.close();
            check.fails("not a zip", "Not an EPUB", () -> Epub.read(junk));
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
        check.same("resolve", "OEBPS/Images/a b.jpg", Epub.resolve("OEBPS/Text/", "../Images/a%20b.jpg"));
        check.same("resolve absolute", "x/y.html", Epub.resolve("OEBPS/", "/x/./y.html"));
    }

    private static void plainText(Check check) {
        String gutenberg = "The Project Gutenberg eBook of Tiny\r\n\r\nTitle: Tiny Tales\r\nAuthor: Some One\r\n\r\n"
            + "*** START OF THE PROJECT GUTENBERG EBOOK TINY ***\r\n\r\nCONTENTS\r\n\r\nCHAPTER I. Begin\r\n\r\nCHAPTER II. End\r\n\r\n"
            + "CHAPTER I. Begin\r\n\r\nIt was a hard-wrapped\r\nparagraph of text.\r\n\r\nAnother one.\r\n\r\n* * *\r\n\r\nAfter the break.\r\n\r\n"
            + "CHAPTER II. End\r\n\r\nThe end.\r\n\r\n*** END OF THE PROJECT GUTENBERG EBOOK TINY ***\r\nLicence text.\r\n";
        Book book = PlainText.parse(PlainText.decode(gutenberg.getBytes(UTF8)), "fallback");
        check.same("gutenberg title", "Tiny Tales", book.title);
        check.same("gutenberg author", "Some One", book.author);
        check.same("chapters", 3, book.sections.size());
        check.same("contents block kept together", "CONTENTS\nCHAPTER I. Begin\nCHAPTER II. End", book.sections.get(0).text);
        check.same("joined lines", "CHAPTER I. Begin\nIt was a hard-wrapped paragraph of text.\nAnother one.\n* * *\nAfter the break.",
            book.sections.get(1).text);
        check.same("scene break", Paragraph.BREAK, book.sections.get(1).paragraphs.get(3).kind);
        check.ok("licence left out", book.sections.get(2).text.indexOf("Licence") < 0);
        check.same("contents from headings", 3, book.contents.size());
        check.same("heading title", "CHAPTER II. End", book.contents.get(2).title);

        Book lines = PlainText.parse("one line\nanother line\nthird line\n", "Lines");
        check.same("fallback title", "Lines", lines.title);
        check.same("a paragraph per line", 3, lines.sections.get(0).paragraphs.size());

        byte[] cp1252 = {(byte) 'C', (byte) 'a', (byte) 'f', (byte) 0xE9, (byte) ' ', (byte) 0x93, (byte) 'q', (byte) 0x94};
        check.same("windows-1252", "Caf\u00E9 \u201Cq\u201D", PlainText.decode(cp1252));
        check.same("utf-8 bom", "x", PlainText.decode(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'x'}));
    }

    static void zip(File file, String[] entries) throws IOException {
        zip(file, entries, "UTF-8");
    }

    static void zip(File file, String[] entries, String charset) throws IOException {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file));
        try {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i]));
                out.write(entries[i + 1].getBytes(Charset.forName(charset)));
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }
}
