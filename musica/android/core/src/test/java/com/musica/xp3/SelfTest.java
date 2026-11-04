package com.musica.xp3;

import com.xp3.common.Check;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Everything in :core, checked on a desktop JVM. */
public final class SelfTest {

    public static void main(String[] args) {
        Check check = new Check();
        check.run("srt", () -> srt(check));
        check.run("rows", () -> rows(check));
        check.run("decode", () -> decode(check));
        check.run("names", () -> names(check));
        check.run("scan", () -> scan(check));
        check.run("catalog", () -> catalog(check));
        check.run("queue", () -> queue(check));
        check.run("pixels", () -> pixels(check));
        if (!check.report()) System.exit(1);
    }

    static final String CIELITO =
        "1\r\n00:00:12,000 --> 00:00:15,000\r\nDe la Sierra Morena,\r\n\r\n"
        + "2\r\n00:00:15,500 --> 00:00:18,900\r\ncielito lindo, vienen bajando\r\n\r\n"
        + "3\r\n00:00:19,000 --> 00:00:22,000\r\n<i>un par de ojitos negros,</i>\r\n"
        + "cielito lindo, de contrabando.\r\n\r\n"
        + "4\r\n00:00:37,000 --> 00:00:41,000\r\n{\\an8}¡Ay, ay, ay, ay!\r\n\r\n";

    static void srt(Check c) {
        Lyrics l = Lyrics.parse(CIELITO);
        // A break from 0 to 12 s, three lines, a break from 22 to 37 s, one line.
        c.same("rows", 6, l.rows.size());
        c.ok("leading break", l.rows.get(0).isBreak());
        c.same("leading break ends at first line", 12000L, l.rows.get(0).end);
        c.same("first line", "De la Sierra Morena,", l.rows.get(1).text);
        c.same("two lines stay one cue", "un par de ojitos negros,\ncielito lindo, de contrabando.", l.rows.get(3).text);
        c.ok("gap of 15 s is a break", l.rows.get(4).isBreak());
        c.same("break starts at end of the cue before", 22000L, l.rows.get(4).start);
        c.same("tags stripped", "¡Ay, ay, ay, ay!", l.rows.get(5).text);

        Lyrics tight = Lyrics.parse("1\n00:00:01,000 --> 00:00:02,000\nUno\n\n2\n00:00:07,999 --> 00:00:09,000\nDos\n");
        c.same("gap under 6 s isn't a break", 2, tight.rows.size());
        Lyrics wide = Lyrics.parse("1\n00:00:01,000 --> 00:00:02,000\nUno\n\n2\n00:00:08,000 --> 00:00:09,000\nDos\n");
        c.same("gap of exactly 6 s is", 3, wide.rows.size());

        Lyrics run = Lyrics.parse("1\n00:00:01,000 --> 00:00:02,000\nUno\n2\n00:00:02,000 --> 00:00:03,000\nDos");
        c.same("cues without blank lines between", 2, run.rows.size());
        c.same("index of the next cue isn't text", "Uno", run.rows.get(0).text);
        c.same("last cue without a blank line", "Dos", run.rows.get(1).text);

        Lyrics dots = Lyrics.parse("00:01:02.5 --> 00:01:04.25\nPunto");
        c.same("dot separator and short fractions", 62500L, dots.rows.get(1).start);
        c.same("short fraction end", 64250L, dots.rows.get(1).end);

        Lyrics unordered = Lyrics.parse("2\n00:00:05,000 --> 00:00:06,000\nB\n\n1\n00:00:01,000 --> 00:00:02,000\nA\n");
        c.same("sorted by time", "A", unordered.rows.get(0).text);

        Lyrics empty = Lyrics.parse("1\n00:00:01,000 --> 00:00:02,000\n\n\n2\n00:00:03,000 --> 00:00:04,000\n<i></i>\n");
        c.ok("cues with no words are dropped", empty.isEmpty());
        c.ok("garbage", Lyrics.parse("not a subtitle file\nat all").isEmpty());
        c.ok("hours", Lyrics.parse("1:02:03,004 --> 1:02:05,000\nX").rows.get(1).start == 3723004L);
    }

    static void rows(Check c) {
        Lyrics l = Lyrics.parse(CIELITO);
        c.same("before anything", 0, l.at(0));
        c.same("in the leading break", 0, l.at(11999));
        c.same("first line starts", 1, l.at(12000));
        c.same("held between close lines", 1, l.at(15400));
        c.same("next line", 2, l.at(15500));
        c.same("in the break", 4, l.at(30000));
        c.same("last line, and after the end", 5, l.at(999999));
        Lyrics noBreak = Lyrics.parse("00:00:01,000 --> 00:00:02,000\nX");
        c.same("before the first line", -1, noBreak.at(500));
        c.same("line at or before a break", 3, l.lineAtOrBefore(4));
        c.same("no line before the leading break", -1, l.lineAtOrBefore(0));
        c.same("line after a break", 5, l.lineAfter(4));
        c.same("line after the leading break", 1, l.lineAfter(0));
        c.same("first line from nowhere", 1, l.lineAfter(-1));
        c.same("nothing after the last", -1, l.lineAfter(5));
    }

    static void decode(Check c) {
        String s = "Corazón partío, ¿qué?";
        c.same("utf-8", s, Lyrics.decode(s.getBytes(StandardCharsets.UTF_8)));
        c.same("windows-1252", s, Lyrics.decode(s.getBytes(Charset.forName("windows-1252"))));
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[utf8.length + 3];
        bom[0] = (byte) 0xEF; bom[1] = (byte) 0xBB; bom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, bom, 3, utf8.length);
        c.same("utf-8 with BOM", s, Lyrics.decode(bom));
        byte[] le = s.getBytes(StandardCharsets.UTF_16LE);
        byte[] lebom = new byte[le.length + 2];
        lebom[0] = (byte) 0xFF; lebom[1] = (byte) 0xFE;
        System.arraycopy(le, 0, lebom, 2, le.length);
        c.same("utf-16le", s, Lyrics.decode(lebom));
        Lyrics l = Lyrics.parse(("\uFEFF1\n00:00:01,000 --> 00:00:02,000\nAñoranza\n").getBytes(StandardCharsets.UTF_8));
        c.same("BOM before the first cue", "Añoranza", l.rows.get(0).text);
    }

    static void names(Check c) {
        c.same("artist - title", Arrays.asList("Clandestino", "Manu Chao"), Arrays.asList(Names.split("Manu Chao - Clandestino")));
        c.same("title alone", Arrays.asList("Cielito lindo", ""), Arrays.asList(Names.split("Cielito lindo")));
        c.same("track number", Arrays.asList("Bailando", "Enrique Iglesias"), Arrays.asList(Names.split("01 - Enrique Iglesias - Bailando")));
        c.same("track number with a dot", Arrays.asList("Despacito", ""), Arrays.asList(Names.split("3. Despacito")));
        c.same("a number that is the title", Arrays.asList("99 Luftballons", ""), Arrays.asList(Names.split("99 Luftballons")));
        c.same("underscores", Arrays.asList("La Bamba", "Ritchie Valens"), Arrays.asList(Names.split("Ritchie_Valens_-_La_Bamba")));
        c.same("base", "Cielito lindo", Names.base("Cielito lindo.mp3"));
        c.same("extension", "mp3", Names.extension("A.MP3"));
        c.same("fold", "corazon partio", Names.fold("Corazón Partío"));
        List<String> titles = new ArrayList<>(Arrays.asList("Vivir Mi Vida", "¡Ay!", "La Bicicleta", "Ángel", "bailando", "Clandestino"));
        titles.sort(Names::compare);
        c.same("A to Z, Spanish style", Arrays.asList("Ángel", "¡Ay!", "bailando", "La Bicicleta", "Clandestino", "Vivir Mi Vida"), titles);
    }

    static File temp() throws IOException {
        return Files.createTempDirectory("musica").toFile();
    }

    static File touch(File dir, String name) throws IOException {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(name.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    static void scan(Check c) throws IOException {
        File root = temp();
        c.same("no Musica", null, Scan.musica(root));
        // A JVM whose file names aren't UTF-8 (no locale set) can't make "Ú".
        boolean utf8 = "UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding"));
        File musica = new File(root, utf8 ? "MÚSICA" : "MUSICA");
        musica.mkdirs();
        c.same("Musica in any case, with or without its accent", musica, Scan.musica(root));
        touch(musica, "Cielito lindo.mp3");
        touch(musica, "cielito LINDO.srt");
        touch(musica, "Corazon Partio.mp3");
        touch(musica, "Despacito.mp3");
        touch(musica, "Despacito.en.srt");
        touch(musica, "Despacito.es.srt");
        touch(musica, "notes.txt");
        touch(musica, ".hidden.mp3");
        touch(musica, "Manu Chao/Clandestino.MP3");
        touch(musica, "Manu Chao/Clandestino.srt");
        touch(musica, "a/b/c/d/e/too deep.mp3");
        List<Scan.Found> found = Scan.songs(musica);
        Map<String, Scan.Found> by = new HashMap<>();
        for (Scan.Found f : found) by.put(f.audio.getName(), f);
        c.same("songs", 4, found.size());
        c.same("srt paired ignoring case", "cielito LINDO.srt", by.get("Cielito lindo.mp3").srt.getName());
        c.same("no srt", null, by.get("Corazon Partio.mp3").srt);
        c.same("tagged srt, Spanish first", "Despacito.es.srt", by.get("Despacito.mp3").srt.getName());
        c.same("subfolder", "Manu Chao", by.get("Clandestino.MP3").folder);
        c.ok("subfolder's srt", by.get("Clandestino.MP3").srt != null);
        c.ok("hidden skipped", !by.containsKey(".hidden.mp3"));
    }

    static void catalog(Check c) throws IOException {
        File musica = temp();
        touch(musica, "Pedro Infante - Cielito lindo.mp3");
        touch(musica, "Tagged.mp3");
        touch(musica, "Banda/Untagged.mp3");
        int[] reads = {0};
        Song.Tagger tagger = audio -> {
            reads[0]++;
            Song.Tags t = new Song.Tags();
            if (audio.getName().equals("Tagged.mp3")) {
                t.title = "Bailando";
                t.artist = "Enrique Iglesias";
                t.album = "Sex and Love";
                t.duration = 243000;
                t.art = true;
            }
            return t;
        };
        List<Song> songs = Song.catalog(Scan.songs(musica), new HashMap<String, Song>(), tagger);
        c.same("all read", 3, reads[0]);
        c.same("A to Z", "Bailando", songs.get(0).title);
        c.same("name split when no tags", "Pedro Infante", songs.get(1).artist);
        c.same("title from name", "Cielito lindo", songs.get(1).title);
        c.same("album from folder", "Banda", songs.get(2).album);
        c.ok("art", songs.get(0).art);

        Map<String, Song> cache = Song.read(Song.write(songs));
        c.same("cache round trip", 3, cache.size());
        c.same("cached duration", 243000L, cache.get(songs.get(0).path).duration);
        reads[0] = 0;
        List<Song> again = Song.catalog(Scan.songs(musica), cache, tagger);
        c.same("cache spares reading", 0, reads[0]);
        c.same("cached title", "Bailando", again.get(0).title);
        File changed = new File(musica, "Tagged.mp3");
        try (FileOutputStream out = new FileOutputStream(changed, true)) {
            out.write(1);
        }
        Song.catalog(Scan.songs(musica), cache, tagger);
        c.same("a changed file is read again", 1, reads[0]);
        c.same("bad cache", 0, Song.read("{nope").size());

        Song.Tagger broken = audio -> { throw new IllegalStateException("bad file"); };
        c.same("a file the tagger can't read is still listed", 3,
            Song.catalog(Scan.songs(musica), new HashMap<String, Song>(), broken).size());
    }

    static void queue(Check c) {
        Queue q = new Queue(new Random(4));
        q.setLibrary(Arrays.asList("a", "b", "c", "d"));
        c.same("nothing yet", null, q.current());
        q.play("b");
        c.same("next", "c", q.next(Queue.Repeat.OFF, false));
        c.same("next", "d", q.next(Queue.Repeat.OFF, false));
        c.same("end", null, q.next(Queue.Repeat.OFF, false));
        c.same("user's next wraps", "a", q.next(Queue.Repeat.OFF, true));
        c.same("start", null, q.previous(Queue.Repeat.OFF));
        c.same("previous wraps when repeating", "d", q.previous(Queue.Repeat.ALL));
        c.same("repeat all wraps", "a", q.next(Queue.Repeat.ALL, false));
        c.same("repeat one", "a", q.next(Queue.Repeat.ONE, false));
        c.same("repeat one, user skips", "b", q.next(Queue.Repeat.ONE, true));

        q.setShuffled(true);
        c.same("shuffling keeps the current one", "b", q.current());
        List<String> seen = new ArrayList<>();
        seen.add(q.current());
        for (int i = 0; i < 3; i++) seen.add(q.next(Queue.Repeat.OFF, false));
        seen.sort(String::compareTo);
        c.same("shuffle plays each once", Arrays.asList("a", "b", "c", "d"), seen);
        c.same("then stops", null, q.next(Queue.Repeat.OFF, false));
        q.setShuffled(false);
        q.play("c");
        q.setLibrary(Arrays.asList("a", "c", "e"));
        c.same("reload keeps the current one", "c", q.current());
        c.same("and the new order", "e", q.next(Queue.Repeat.OFF, false));
    }

    static void pixels(Check c) {
        int w = 9, h = 5;
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFF000000);
        px[2 * w + 4] = 0xFFFFFFFF;
        Pixels.blur(px, w, h, 1);
        int centre = px[2 * w + 4] & 0xFF;
        int near = px[2 * w + 5] & 0xFF;
        c.ok("spread out", centre > 0 && centre < 255 && near > 0 && near <= centre);
        c.same("stays opaque", 0xFF, px[0] >>> 24);
        int[] flat = new int[16];
        Arrays.fill(flat, 0xFF336699);
        Pixels.blur(flat, 4, 4, 2);
        c.same("flat stays flat", 0xFF336699, flat[5]);
        c.close("white", 1.0, Pixels.luma(0xFFFFFFFF), 1e-9);
        c.ok("pale covers get a darker veil", Pixels.veilAlpha(0.9) > Pixels.veilAlpha(0.2));
        c.same("dark covers get Apple's 15%", 38, Pixels.veilAlpha(0.2));
        c.same("same title, same colours", Pixels.coverColors("Cielito lindo")[1], Pixels.coverColors("cielito lindo")[1]);
        c.same("hsv red", 0xFFFF0000, Pixels.hsv(0, 1, 1));
        c.same("clock", "2:48", Pixels.clock(168900));
        c.same("clock hours", "1:00:05", Pixels.clock(3605000));
    }
}
