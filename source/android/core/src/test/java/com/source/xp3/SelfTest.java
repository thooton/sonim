package com.source.xp3;

import com.source.xp3.data.History;
import com.source.xp3.data.Monogram;
import com.source.xp3.data.Recents;
import com.source.xp3.net.Books;
import com.xp3.common.Login;
import com.source.xp3.net.Music;
import com.source.xp3.net.Names;
import com.source.xp3.net.Tar;
import com.xp3.common.Check;
import com.xp3.common.TestServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Everything in :core, checked on a desktop JVM. */
public final class SelfTest {

    public static void main(String[] args) {
        final Check check = new Check();
        check.run("login", () -> {
            Login login = Login.of("cc.example.org/", " delta_abc ");
            check.same("https added, slash dropped", "https://cc.example.org", login.server());
            check.same("token trimmed", "delta_abc", login.token());
            check.same("host", "cc.example.org", login.host());
            check.ok("ready", login.ready());
            check.fails("plain http refused", "https", () -> {
                Login.of("http://cc.example.org", "t");
            });
            check.fails("token with a space refused", () -> {
                Login.of("cc.example.org", "a b");
            });
            Login back = Login.parse(login.toJson());
            check.same("round trip", login.token(), back.token());
            check.ok("junk is blank", !Login.parse("{nope").ready());
        });
        check.run("names", () -> {
            check.same("plain", "[Artist - Album, 01 Song.flac]", Names.parts("Artist - Album/01 Song.flac").toString());
            check.same("no way out", "[etc, passwd]", Names.parts("/../etc/./passwd").toString());
            check.same("FAT characters", "[AC_DC - What_.mp3]", Names.parts("AC/DC - What?.mp3".replace("/", ":")).toString());
            check.same("trailing dots", "[Vol, a.mp3]", Names.parts("Vol.../a.mp3").toString());
            check.same("numbered", "Song (2).mp3", Names.numbered("Song.mp3", 2));
            check.same("numbered folder", "Album (3)", Names.numbered("Album", 3));
        });
        check.run("history", () -> {
            History history = new History();
            history.add(new History.Entry("slsk://a", Music.SONG, "Song", "m / Music", java.util.Arrays.asList("/sd/Music/a.mp3"), 7, 1000));
            history.add(new History.Entry("book:1/x", "book", "Book", "Author", java.util.Arrays.asList("/sd/Books/b.epub"), 9, 3000000000000L));
            history.add(new History.Entry("slsk://a", Music.SONG, "Song again", "m / Music", java.util.Arrays.asList("/sd/Music/a (2).mp3"), 7, 5000));
            check.same("newest first, once each", "Song again", history.entries().get(0).title);
            check.same("count", 2, history.entries().size());
            History back = History.parse(history.toJson());
            History.Entry book = back.find("book:1/x");
            check.ok("found", book != null);
            check.same("big times survive", 3000000000000L, book.time);
            check.same("files", "[/sd/Books/b.epub]", book.files.toString());
            check.same("bytes", 9L, book.bytes);
            check.ok("missing", back.find("nope") == null);
            check.same("junk is empty", 0, History.parse("{junk").entries().size());
            for (int i = 0; i < History.LIMIT + 5; i++) {
                history.add(new History.Entry("k" + i, Music.SONG, "", "", new ArrayList<String>(), 0, i));
            }
            check.same("limited", History.LIMIT, history.entries().size());
            history.remove("k" + (History.LIMIT + 4));
            check.same("removed", "k" + (History.LIMIT + 3), history.entries().get(0).key);
        });
        check.run("recents", () -> {
            Recents recents = new Recents();
            recents.add(Music.SONG, " Artist - Song ");
            recents.add("book", "Dune");
            recents.add(Music.SONG, "artist - song");
            recents.add(Music.ALBUM, "   ");
            check.same("newest first, once each", "artist - song", recents.searches().get(0).query);
            check.same("count", 2, recents.searches().size());
            for (int i = 0; i < 9; i++) recents.add(Music.ALBUM, "Album " + i);
            check.same("limited", Recents.LIMIT, recents.searches().size());
            Recents back = Recents.parse(recents.toJson());
            check.same("round trip", "Album 8", back.searches().get(0).query);
            check.same("mode kept", Music.ALBUM, back.searches().get(0).mode);
            check.same("junk is empty", 0, Recents.parse("nope").searches().size());
        });
        check.run("monogram", () -> {
            check.same("two words", "SO", Monogram.initials("Slow Orbit"));
            check.same("track number passed over", "TC", Monogram.initials("01 - the cure"));
            check.same("one word", "U", Monogram.initials("Untitled"));
            check.same("digits alone", "12", Monogram.initials("1999 - 2001"));
            check.same("nothing", "", Monogram.initials(" - "));
            check.same("accents kept", "ÉP", Monogram.initials("élan (pt. 2)"));
            int tone = Monogram.tone("Slow Orbit", 6);
            check.ok("tone in range", tone >= 0 && tone < 6);
            check.same("tone steady", tone, Monogram.tone("Slow Orbit", 6));
            check.ok("negative hashes in range", Monogram.tone("polygenelubricants", 6) >= 0);
        });
        check.run("tar", () -> {
            String longName = "Some Artist - A Very Long Album Title That Goes On And On (Deluxe Edition)/"
                + "01 - An Equally Long Song Title That Needs PAX Headers.flac";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            entry(out, "cover.jpg", '0', "JPEG".getBytes("UTF-8"));
            byte[] pax = record("path", longName);
            entry(out, "PaxHeaders/x", 'x', pax);
            entry(out, "truncated-name", '0', new byte[700]);
            out.write(new byte[1024]);
            Tar tar = new Tar(new ByteArrayInputStream(out.toByteArray()));
            Tar.Entry first = tar.next();
            check.same("first name", "cover.jpg", first.name);
            check.same("first body", "JPEG", new String(readAll(tar.body()), "UTF-8"));
            Tar.Entry second = tar.next();
            check.same("pax name", longName, second.name);
            check.same("pax size", 700L, second.size);
            check.ok("end", tar.next() == null);

            ByteArrayOutputStream cut = new ByteArrayOutputStream();
            entry(cut, "a.mp3", '0', new byte[2000]);
            final byte[] half = java.util.Arrays.copyOf(cut.toByteArray(), 1000);
            check.fails("truncated", "part way", () -> {
                Tar t = new Tar(new ByteArrayInputStream(half));
                t.next();
                readAll(t.body());
            });
        });
        check.run("music", () -> {
            final ByteArrayOutputStream archive = new ByteArrayOutputStream();
            entry(archive, "Artist - Song.mp3", '0', "ID3data".getBytes("UTF-8"));
            archive.write(new byte[1024]);
            TestServer server = new TestServer(request -> {
                if (!"Bearer tok".equals(request.header("authorization"))) {
                    return TestServer.Reply.of(401, "application/json", "{\"error\":\"invalid or missing auth token\"}");
                }
                if (request.method.equals("POST") && request.path.equals("/music-search")) {
                    if (request.body.contains("Nothing")) {
                        return TestServer.Reply.of(200, "application/x-ndjson",
                            "{\"done\":{\"error\":\"Nothing on Soulseek met the config's conditions\",\"results\":[]}}\n");
                    }
                    return TestServer.Reply.of(200, "application/x-ndjson",
                        "{\"stdout\":\"[002] SongJob: searching\"}\n{}\n"
                        + "{\"done\":{\"results\":["
                        + "{\"link\":\"slsk://bob/@@m/Music/Artist - Song.mp3\",\"user\":\"bob\",\"speed\":1.25,\"free\":true,"
                        + "\"files\":[{\"path\":\"@@m\\\\Music\\\\Artist - Song.mp3\",\"size\":8500000,\"length\":252,\"bitrate\":320}]},"
                        + "{\"link\":\"slsk://carol/m/Artist/Album (2001)/\",\"user\":\"carol\",\"speed\":0.04,\"free\":false,"
                        + "\"folder\":\"m\\\\Artist\\\\Album (2001)\",\"files\":["
                        + "{\"path\":\"m\\\\Artist\\\\Album (2001)\\\\01.flac\",\"size\":30000000,\"length\":3000,\"samplerate\":44100,\"bitdepth\":16},"
                        + "{\"path\":\"m\\\\Artist\\\\Album (2001)\\\\02.flac\",\"size\":20000000,\"length\":725,\"samplerate\":44100,\"bitdepth\":16}]},"
                        + "{\"user\":\"nolink\",\"files\":[]}]}}\n");
                }
                if (request.method.equals("POST") && request.path.equals("/music")) {
                    return TestServer.Reply.of(200, "application/x-ndjson",
                        "{\"stdout\":\"Downloading\"}\n{}\nnot json\n{\"stderr\":\"slow\"}\n"
                        + "{\"done\":{\"job\":\"j1\",\"files\":[{\"path\":\"Artist - Song.mp3\",\"size\":7}]}}\n");
                }
                if (request.method.equals("GET") && request.path.equals("/music-jobs/j1")) {
                    return TestServer.Reply.of(200, "application/x-tar", new String(archive.toByteArray(), "UTF-8"));
                }
                if (request.method.equals("DELETE") && request.path.equals("/music-jobs/j1")) {
                    return TestServer.Reply.json("{}");
                }
                return TestServer.Reply.of(404, "application/json", "{\"error\":\"no such music job\"}");
            });
            try {
                // The test server is plain http; Login only builds https
                // addresses, so swap the scheme after validation.
                String base = server.uri("").toString().replace("http://", "");
                Login login = Login.of(base, "tok");
                Music music = new Music(new PlainLogin(login).get());
                final List<String> lines = new ArrayList<String>();
                Music.Found found = music.search(Music.SONG, " Artist - Song ", (text, error) -> {
                    lines.add((error ? "! " : "") + text);
                });
                check.same("search log", "[[002] SongJob: searching]", lines.toString());
                check.ok("query sent trimmed", server.seen.get(0).body.contains("\"query\":\"Artist - Song\""));
                check.ok("search found", found.error == null);
                check.same("results without a link dropped", 2, found.hits.size());
                Music.Hit song = found.hits.get(0);
                check.same("song title", "Artist - Song.mp3", song.title());
                check.same("song meta", "MP3 · 320 kbps · 4:12 · 8.1 MB", song.meta());
                check.same("song source", "bob · 1.3 MB/s", song.source());
                check.same("song where", "@@m\\Music", song.where());
                check.same("song name", "Artist - Song", song.name());
                check.same("song folders", "@@m / Music", song.folders());
                check.same("song formats", "MP3", song.formats());
                check.same("song specs", "320 kbps · 4:12 · 8.1 MB", song.specs());
                check.same("song bytes", 8500000L, song.bytes());
                check.same("song brief", "320 · 8.1 MB", song.brief());
                Music.Hit album = found.hits.get(1);
                check.ok("album", album.album() && !song.album());
                check.same("album title", "Artist / Album (2001)", album.title());
                check.same("album meta", "2 files · FLAC · 16/44.1 kHz · 1:02:05 · 47.7 MB", album.meta());
                check.same("album source", "carol · 0.0 MB/s · queued", album.source());
                check.same("album name", "Album (2001)", album.name());
                check.same("album folders", "m / Artist", album.folders());
                check.same("album specs", "2 files · 16/44.1 kHz · 1:02:05 · 47.7 MB", album.specs());
                check.same("album brief", "2 files · 16/44.1 · 47.7 MB", album.brief());
                check.same("track title", "01", album.tracks.get(0).title());
                Music.Found none = music.search(Music.ALBUM, "Nothing", (text, error) -> {});
                check.ok("nothing found", none.hits.isEmpty() && none.error.startsWith("Nothing on Soulseek"));

                lines.clear();
                Music.Result result = music.download(song, (text, error) -> {
                    lines.add((error ? "! " : "") + text);
                });
                check.same("log", "[Downloading, ! slow]", lines.toString());
                check.same("job", "j1", result.job);
                check.ok("no error", result.error == null);
                check.same("bytes", 7L, result.bytes);
                check.same("link sent", "{\"link\":\"slsk://bob/@@m/Music/Artist - Song.mp3\"}", server.seen.get(2).body);
                final List<String> got = new ArrayList<String>();
                int count = music.fetch("j1", (path, size, body) -> {
                    got.add(path + "=" + new String(readAll(body), "UTF-8"));
                });
                check.same("fetched", 1, count);
                check.same("file", "[Artist - Song.mp3=ID3data]", got.toString());
                music.delete("j1");
                music.delete("gone");
                final Music refused = new Music(new PlainLogin(Login.of(base, "wrong")).get());
                check.fails("bad token", "refused the token", () -> {
                    refused.search(Music.ALBUM, "x", (text, error) -> {});
                });
            } finally {
                server.close();
            }
        });
        check.run("books", () -> {
            final int[] logins = {0};
            final boolean[] limit = {false};
            final boolean[] noAccount = {false};
            TestServer server = new TestServer(request -> {
                String cookie = request.header("cookie");
                String path = request.path;
                if (path.equals("/books-login")) {
                    if (!"Bearer tok".equals(request.header("authorization"))) {
                        return TestServer.Reply.of(401, "application/json", "{\"error\":\"invalid or missing auth token\"}");
                    }
                    if (noAccount[0]) return TestServer.Reply.of(404, "application/json", "{\"error\":\"no book login is set\"}");
                    return TestServer.Reply.json("{\"email\":\"me@example.org\",\"password\":\"pw\"}");
                }
                if (path.equals("/eapi/info/domains")) {
                    return TestServer.Reply.json("{\"domains\":[{\"domain\":\"elsewhere.invalid\"},{\"domain\":\"" + request.header("host") + "\"}]}");
                }
                if (path.equals("/eapi/user/login")) {
                    logins[0]++;
                    if (!request.body.equals("email=me%40example.org&password=pw")) {
                        return TestServer.Reply.json("{\"success\":0,\"error\":\"Incorrect email or password\"}");
                    }
                    return TestServer.Reply.json("{\"success\":1,\"user\":{\"id\":42,\"remix_userkey\":\"key\"}}");
                }
                boolean session = cookie.contains("remix_userid=42") && cookie.contains("remix_userkey=key");
                if (path.equals("/eapi/book/search")) {
                    if (!session) return TestServer.Reply.json("{\"success\":0,\"error\":\"Please login\"}");
                    if (request.body.contains("message=formats")) {
                        return TestServer.Reply.json("{\"success\":1,\"books\":["
                            + "{\"id\":1,\"hash\":\"a\",\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"extension\":\"epub\"},"
                            + "{\"id\":2,\"hash\":\"b\",\"title\":\"Dune Messiah\",\"author\":\"Frank Herbert\",\"extension\":\"epub\"},"
                            + "{\"id\":3,\"hash\":\"c\",\"title\":\" dune\",\"author\":\"Frank Herbert \",\"extension\":\"pdf\"}],"
                            + "\"pagination\":{\"total_pages\":1}}");
                    }
                    return TestServer.Reply.json("{\"success\":1,\"books\":[{\"id\":7,\"hash\":\"ab\",\"title\":\"The Hobbit: Or There and Back Again\","
                        + "\"author\":\"J.R.R. Tolkien\",\"year\":\"1937\",\"language\":\"english\",\"extension\":\"EPUB\",\"filesize\":\"2048\"}],"
                        + "\"pagination\":{\"total_pages\":3}}");
                }
                if (path.equals("/eapi/book/7/ab/file")) {
                    if (!session) return TestServer.Reply.json("{\"success\":0,\"error\":\"Please login\"}");
                    return TestServer.Reply.json("{\"success\":1,\"file\":{\"downloadLink\":\"/dl/7\"}}");
                }
                if (path.equals("/dl/7")) {
                    return TestServer.Reply.of(302, null, "").header("Location: /files/7").header("Set-Cookie: hop=yes; Path=/");
                }
                if (path.equals("/files/7")) {
                    if (limit[0]) return TestServer.Reply.of(200, "text/html", "<html>limit</html>");
                    if (!session || !cookie.contains("hop=yes") || !request.header("user-agent").startsWith("python-httpx")) {
                        return TestServer.Reply.of(403, "text/plain", "no");
                    }
                    return TestServer.Reply.of(200, "application/epub+zip", "EPUBDATA");
                }
                return TestServer.Reply.of(404, "text/plain", "");
            });
            File folder = java.nio.file.Files.createTempDirectory("source-books").toFile();
            try {
                String host = server.uri("").getAuthority();
                String base = server.uri("").toString().replace("http://", "");
                Login center = new PlainLogin(Login.of(base, "tok")).get();
                // The first domain is dead, so the probe moves on to the second.
                List<String> domains = java.util.Arrays.asList("127.0.0.1:1", host);
                final Books.Session[] kept = {null};
                Books.Keeper keeper = session -> kept[0] = session;
                Books books = new Books(center, Books.Session.none(), keeper, "http", domains);
                Books.Page page = books.search(" hobbit ", 1, "epub");
                check.same("logged in once", 1, logins[0]);
                check.ok("session kept", kept[0] != null && kept[0].ready());
                check.same("session's account", "me@example.org", kept[0].email());
                check.same("pages", 3, page.pages);
                check.same("results", 1, page.books.size());
                Books.Book hobbit = page.books.get(0);
                check.same("meta", "J.R.R. Tolkien · 1937 · english · EPUB · 2 KB", hobbit.meta());
                TestServer.Request search = null;
                for (TestServer.Request r : server.seen) if (r.path.equals("/eapi/book/search")) search = r;
                check.same("search form", "message=hobbit&limit=20&page=1&extensions%5B%5D=EPUB", search.body);

                Books.Page dunes = books.search("formats", 1, null);
                List<Books.Book> dune = Books.formats(dunes, dunes.books.get(0));
                check.same("same work, other formats", 2, dune.size());
                check.same("its other format", "pdf", dune.get(1).extension);
                check.same("a work alone", 1, Books.formats(dunes, dunes.books.get(1)).size());

                Books.Progress quiet = (done, total) -> {};
                Books.Saved saved = books.download(hobbit, folder, quiet);
                check.same("named", "J.R.R. Tolkien - The Hobbit - Or There and Back Again.epub", saved.file.getName());
                check.same("content", "EPUBDATA", new String(readAll(new java.io.FileInputStream(saved.file)), "UTF-8"));
                check.ok("new", !saved.kept);
                Books.Saved again = books.download(hobbit, folder, quiet);
                check.ok("same book kept", again.kept && again.file.equals(saved.file));
                check.same("no part files left", 1, folder.list().length);

                // A stored session that has expired logs in again, once.
                Books.Session stale = Books.Session.parse(kept[0].toJson().replace("\"key\"", "\"old\""));
                check.ok("stale session reads back", stale.ready());
                Books later = new Books(center, stale, keeper, "http", domains);
                later.search("hobbit", 2, null);
                check.same("logged in again", 2, logins[0]);

                limit[0] = true;
                final Books.Book book = hobbit;
                final File into = new File(folder, "more");
                final Books limited = new Books(center, kept[0], keeper, "http", domains);
                check.fails("daily limit", "daily download limit", () -> {
                    limited.download(book, into, (done, total) -> {});
                });
                check.same("nothing left behind", 0, into.list().length);

                noAccount[0] = true;
                final Books unset = new Books(center, Books.Session.none(), keeper, "http", domains);
                check.fails("no account", "delta books login set", () -> {
                    unset.search("x", 1, null);
                });
            } finally {
                server.close();
                for (File f : folder.listFiles()) {
                    if (f.isDirectory()) for (File g : f.listFiles()) g.delete();
                    f.delete();
                }
                folder.delete();
            }
        });
        if (!check.report()) System.exit(1);
    }

    /** Rebuilds a validated login over plain http, for the loopback server only. */
    private static final class PlainLogin {
        private final Login login;

        PlainLogin(Login login) {
            this.login = login;
        }

        Login get() throws Exception {
            java.lang.reflect.Constructor<Login> make = Login.class.getDeclaredConstructor(String.class, String.class);
            make.setAccessible(true);
            return make.newInstance(login.server().replace("https://", "http://"), login.token());
        }
    }

    static byte[] record(String key, String value) throws IOException {
        String body = " " + key + "=" + value + "\n";
        int length = body.getBytes("UTF-8").length;
        int digits = String.valueOf(length).length();
        int total = length + digits;
        if (String.valueOf(total).length() != digits) total++;
        return (total + body).getBytes("UTF-8");
    }

    static void entry(ByteArrayOutputStream out, String name, char type, byte[] data) throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes("UTF-8");
        System.arraycopy(nameBytes, 0, header, 0, Math.min(100, nameBytes.length));
        put(header, 100, "0000644");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", data.length));
        put(header, 136, "00000000000");
        header[156] = (byte) type;
        put(header, 257, "ustar");
        header[262] = '0';
        header[263] = '0';
        for (int i = 148; i < 156; i++) header[i] = ' ';
        long sum = 0;
        for (byte b : header) sum += b & 0xff;
        put(header, 148, String.format("%06o", sum));
        header[154] = 0;
        out.write(header);
        out.write(data);
        int pad = (512 - data.length % 512) % 512;
        out.write(new byte[pad]);
    }

    private static void put(byte[] header, int offset, String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8");
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = in.read(buffer)) >= 0; ) out.write(buffer, 0, count);
        return out.toByteArray();
    }
}
