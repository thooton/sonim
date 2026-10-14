package com.delta.xp3.data;

import com.delta.xp3.Check;
import com.delta.xp3.net.Config;
import com.delta.xp3.net.Json;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class DataTests {

    private DataTests() {}

    public static void run(final Check check) {
        check.run("transcript keeps the conversation", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                check.ok("log while idle is not a change", !t.apply("client_event", "1", frame(1, "worker",
                    Json.map("type", "env_manager_log", "data", Json.map("content", "Allocating sandbox"),
                        "uuid", "a"))));
                check.ok("client prompt shown", t.apply("client_event", "2", frame(2, "client", user("u1", "Hello"))));
                check.ok("prompt starts a turn", t.working());
                t.apply("client_event", "3", frame(3, "worker",
                    Json.map("type", "env_manager_log", "data", Json.map("content", "Starting Claude Code"))));
                check.same("start-up line is the status, white-labelled", "Starting Delta", t.status());
                t.apply("client_event", "4", frame(4, "worker", assistant(null,
                    Json.map("type", "tool_use", "name", "Bash", "input", Json.map("command", "ls\n-la"))
                )));
                t.apply("client_event", "5", frame(5, "worker", Json.map("type", "user", "message",
                    Json.map("role", "user", "content", Json.list(Json.map("type", "tool_result", "content", "x"))))));
                t.apply("client_event", "6", frame(6, "worker", assistant("toolu_1",
                    Json.map("type", "text", "text", "subagent chatter"))));
                t.apply("client_event", "7", frame(7, "worker", assistant(null,
                    Json.map("type", "thinking", "thinking", "hmm"),
                    Json.map("type", "text", "text", "  Done.  "))));
                check.ok("still working before the result", t.working());
                t.apply("client_event", "8", frame(8, "worker", Json.map("type", "result", "subtype", "success",
                    "is_error", Boolean.FALSE, "result", "Done.")));
                check.ok("result ends the turn", !t.working());
                List<Entry> entries = t.entries();
                check.same("you, tool, agent", 3, entries.size());
                check.same("prompt", "Hello", entries.get(0).text);
                check.same("tool line flattened", "Bash: ls -la", entries.get(1).text);
                check.same("tool kind", Entry.TOOL, entries.get(1).kind);
                check.same("text trimmed", "Done.", entries.get(2).text);
                check.same("sequence", 8L, t.sequence());

                check.ok("replay ignored", !t.apply("client_event", "7", frame(7, "worker", assistant(null,
                    Json.map("type", "text", "text", "again")))));
                check.same("nothing added by replay", 3, t.size());
            }
        });

        check.run("own messages are not shown twice", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                t.sent("u-1", "Fix it");
                check.ok("sending starts a turn", t.working());
                check.ok("echo is not a change", !t.apply("client_event", "1", frame(1, "client", user("u-1", "Fix it"))));
                check.same("one message", 1, t.size());
                t.apply("client_event", "2", frame(2, "client", user("u-2", "From the web")));
                check.same("another client's message shown", 2, t.size());
                t.apply("client_event", "3", frame(3, "client",
                    Json.map("type", "user", "uuid", "u-3", "isMeta", Boolean.TRUE,
                        "message", Json.map("role", "user", "content", "meta"))));
                check.same("meta messages hidden", 2, t.size());
                t.apply("client_event", "4", frame(4, "client", Json.map("type", "user", "uuid", "u-4",
                    "message", Json.map("role", "user", "content", Json.list(
                        Json.map("type", "text", "text", "one"), Json.map("type", "image"),
                        Json.map("type", "text", "text", "two"))))));
                check.same("text blocks joined", "one\n\ntwo", t.entries().get(2).text);
                check.same("picture counted", 1, t.entries().get(2).pictures);
                check.ok("picture alone is a message", t.apply("client_event", "5", frame(5, "client",
                    Json.map("type", "user", "uuid", "u-5", "message", Json.map("role", "user",
                        "content", Json.list(Json.map("type", "image"), Json.map("type", "image")))))));
                check.same("no text", "", t.entries().get(3).text);
                check.same("two pictures", 2, t.entries().get(3).pictures);
                t.sent("u-6", "", 1);
                check.ok("own picture echo skipped", !t.apply("client_event", "6", frame(6, "client",
                    Json.map("type", "user", "uuid", "u-6", "message", Json.map("role", "user",
                        "content", Json.list(Json.map("type", "image")))))));
                check.same("still one copy", 5, t.size());
            }
        });

        check.run("results that end badly leave a note", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                t.sent("u", "go");
                t.apply("client_event", "1", frame(1, "worker", Json.map("type", "result",
                    "subtype", "error_during_execution", "is_error", Boolean.TRUE)));
                check.same("interrupt note", "Stopped.", t.entries().get(1).text);
                check.ok("idle", !t.working());
                t.apply("client_event", "2", frame(2, "worker", Json.map("type", "result",
                    "subtype", "success", "is_error", Boolean.TRUE, "result", "API Error:\n 529 overloaded")));
                check.same("error text flattened", "API Error: 529 overloaded", t.entries().get(2).text);
                t.sent("u2", "again");
                t.apply("client_event", "3", frame(3, "worker", assistant(null,
                    Json.map("type", "text", "text", "You've hit your session limit"))));
                t.apply("client_event", "4", frame(4, "worker", Json.map("type", "result",
                    "subtype", "success", "is_error", Boolean.TRUE, "result", "You've hit your session limit")));
                check.same("a limit the agent already said is not said twice", 5, t.size());
                check.ok("still ends the turn", !t.working());
            }
        });

        check.run("permission requests", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                check.ok("client requests ignored", !t.apply("client_event", "1", frame(1, "client",
                    Json.map("type", "control_request", "request_id", "c1",
                        "request", Json.map("subtype", "set_permission_mode", "mode", "auto")))));
                t.apply("client_event", "2", frame(2, "worker", Json.map("type", "control_request",
                    "request_id", "p1", "request", Json.map("subtype", "can_use_tool", "tool_name", "Bash",
                        "input", Json.map("command", "rm -rf build")))));
                Transcript.Permission pending = t.pending();
                check.ok("pending", pending != null);
                check.same("request id", "p1", pending.requestId);
                check.same("input kept", "rm -rf build", pending.input.get("command"));
                check.same("note", "Delta asks to use Bash: rm -rf build.",
                    t.entries().get(0).text);
                check.ok("other responses leave it", !t.apply("client_event", "3", frame(3, "worker",
                    Json.map("type", "control_response", "response", Json.map("request_id", "c1")))));
                check.ok("still pending", t.pending() != null);
                check.ok("answered elsewhere", t.apply("client_event", "4", frame(4, "client",
                    Json.map("type", "control_response", "response", Json.map("request_id", "p1")))));
                check.ok("cleared", t.pending() == null);
            }
        });

        check.run("malformed frames are skipped but counted", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                check.ok("bad json", !t.apply("client_event", "5", "{nope"));
                check.same("sequence still advances", 5L, t.sequence());
                check.ok("other events", !t.apply("session_update", "", "{\"connection_status\":\"connected\"}"));
                check.ok("truncation noted", t.apply("catch_up_truncated", "", "{}"));
                check.same("sequence from payload when no id", 9L, sequenceAfter("", 9));
            }
        });

        check.run("transcript is capped", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                for (int i = 0; i < Transcript.MAX_ENTRIES + 5; i++) t.note("n" + i);
                check.same("size", Transcript.MAX_ENTRIES, t.size());
                check.same("oldest dropped", "n5", t.entries().get(0).text);
            }
        });

        check.run("session round trip", new Check.Body() {
            @Override
            public void run() throws Exception {
                File directory = Files.createTempDirectory("delta-store").toFile();
                Store store = new Store(directory);
                Store.Saved unknown = store.session("session_01abc");
                check.ok("nothing saved yet", unknown.title == null && unknown.transcript.size() == 0);
                check.ok("no open session", store.openSession() == null);

                Transcript t = new Transcript();
                t.sent("u-1", "Hi", 2);
                t.apply("client_event", "12", frame(12, "worker", assistant(null,
                    Json.map("type", "text", "text", "Hello **there**"))));
                store.saveSession("session_01abc", "Hi", t);
                store.saveOpenSession("session_01abc");

                Store.Saved back = store.session("session_01abc");
                check.same("id", "session_01abc", back.id);
                check.same("title", "Hi", back.title);
                check.same("sequence", 12L, back.transcript.sequence());
                check.ok("working", back.transcript.working());
                check.same("entries", 2, back.transcript.size());
                check.same("own id kept", "u-1", back.transcript.entries().get(0).id);
                check.same("pictures kept", 2, back.transcript.entries().get(0).pictures);
                check.same("none on the agent's", 0, back.transcript.entries().get(1).pictures);
                check.ok("echo still recognised after restore", !back.transcript.apply("client_event", "13",
                    frame(13, "client", user("u-1", "Hi"))));
                check.same("open session", "session_01abc", store.openSession());

                store.saveOpenSession(null);
                check.ok("new session open", store.openSession() == null);

                store.saveSession(null, "x", new Transcript());
                check.ok("an unstarted session writes nothing", new File(directory, "sessions").list().length == 1);
                store.saveSession("../evil", "x", new Transcript());
                check.ok("a path is never a session", !new File(directory, "evil.json").exists());

                Files.write(new File(directory, "open.txt").toPath(), "../../x".getBytes("UTF-8"));
                check.ok("odd open id refused", store.openSession() == null);
                Files.write(new File(directory, "sessions/session_02.json").toPath(),
                    "{\"id\":\"session_03\",\"entries\":[{\"kind\":0,\"text\":\"x\"}]}".getBytes("UTF-8"));
                check.same("a transcript filed under the wrong id is ignored", 0,
                    store.session("session_02").transcript.size());
            }
        });

        check.run("old transcripts are pruned", new Check.Body() {
            @Override
            public void run() throws Exception {
                File directory = Files.createTempDirectory("delta-prune").toFile();
                Store store = new Store(directory);
                for (int i = 0; i < 5; i++) {
                    Transcript t = new Transcript();
                    t.sent("u-" + i, "m", i % 2);
                    store.saveSession("session_0" + i, "t", t);
                    new File(directory, "sessions/session_0" + i + ".json").setLastModified(1000000L * (i + 1));
                }
                java.util.Set<String> pictured = store.prune(2, "session_00");
                check.ok("newest kept", store.session("session_04").transcript.size() == 1);
                check.ok("next newest kept", store.session("session_03").transcript.size() == 1);
                check.ok("older dropped", store.session("session_02").transcript.size() == 0);
                check.ok("open one kept however old", store.session("session_00").transcript.size() == 1);
                check.ok("pictures of a kept message", pictured.contains("u-3"));
                check.ok("pictures of a dropped message", !pictured.contains("u-1"));
                check.ok("no pictures, not listed", !pictured.contains("u-4"));
            }
        });

        check.run("chats and downloads round trip", new Check.Body() {
            @Override
            public void run() throws Exception {
                File directory = Files.createTempDirectory("delta-lists").toFile();
                Store store = new Store(directory);
                check.same("no chats yet", 0, store.chats().size());
                store.saveChats(java.util.Arrays.asList(
                    new Chat("session_01", "Fix the list app", Chat.WORKING, 1700000000000L),
                    new Chat("session_02", " ", null, 0)));
                List<Chat> chats = store.chats();
                check.same("two", 2, chats.size());
                check.same("title", "Fix the list app", chats.get(0).title);
                check.same("status", Chat.WORKING, chats.get(0).status);
                check.same("time", 1700000000000L, chats.get(0).updated);
                check.same("blank title named", "Untitled", chats.get(1).title);
                check.same("missing status is idle", Chat.IDLE, chats.get(1).status);

                Map<String, RemoteFile> downloads = new java.util.LinkedHashMap<String, RemoteFile>();
                downloads.put("list.apk", new RemoteFile("list.apk", 1234, 1700000000123L));
                store.saveDownloads(downloads);
                RemoteFile back = store.downloads().get("list.apk");
                check.ok("same upload", back != null && back.sameAs(new RemoteFile("list.apk", 1234, 1700000000123L)));
                check.ok("a new push of the name is not", !back.sameAs(new RemoteFile("list.apk", 1234, 1700000009999L)));
                check.ok("apk", back.isApk());

                Files.write(new File(directory, "downloads.json").toPath(),
                    "[{\"name\":\"../x\",\"size\":1,\"modified\":1}]".getBytes("UTF-8"));
                check.same("a path is never a download", 0, store.downloads().size());
            }
        });

        check.run("names and ids", new Check.Body() {
            @Override
            public void run() {
                check.ok("session id", Chat.validId("session_01AbC-x_9"));
                check.ok("cse is not stored", !Chat.validId("cse_01"));
                check.ok("no slash", !Chat.validId("session_01/../x"));
                check.ok("no empty", !Chat.validId("session_"));
                check.same("cse converted", "session_01", Chat.sessionId("cse_01"));
                check.ok("file name", RemoteFile.validName("Flashcards debug.apk"));
                check.ok("no hidden", !RemoteFile.validName(".upload-1"));
                check.ok("no path", !RemoteFile.validName("a/b"));
                check.ok("no backslash", !RemoteFile.validName("a\\b"));
                check.ok("no control", !RemoteFile.validName("a\nb"));
            }
        });

        check.run("the setup block", new Check.Body() {
            @Override
            public void run() {
                String wrapped = Setup.wrap("  Fix the list app  ", "https://cc.example.com", "delta_tok");
                check.ok("set-auth line", wrapped.contains("delta set-auth https://cc.example.com delta_tok\n"));
                check.ok("install script", wrapped.contains("curl -fsSL " + Setup.INSTALL_URL + " | sh"));
                check.ok("agent is Delta", wrapped.contains("you are Delta"));
                check.ok("help", wrapped.contains("delta help"));
                check.ok("message last", wrapped.endsWith("\n\nFix the list app"));
                check.same("stripped", "Fix the list app", Setup.strip(wrapped));
                String bare = Setup.wrap("hi", null, null);
                check.ok("no set-auth without a command center", !bare.contains("set-auth"));
                check.same("picture-only first message", "", Setup.strip(Setup.wrap("", "https://x", "t")));
                check.same("ordinary text untouched", "Use <delta-setup> tags", Setup.strip("Use <delta-setup> tags"));

                Transcript t = new Transcript();
                t.apply("client_event", "1", frame(1, "client", user("u-1", wrapped)));
                check.same("replayed first message reads as typed", "Fix the list app", t.entries().get(0).text);
                t.apply("client_event", "2", frame(2, "client", Json.map("type", "user", "uuid", "u-2",
                    "message", Json.map("role", "user", "content", Json.list(
                        Json.map("type", "image"),
                        Json.map("type", "text", "text", Setup.wrap("", "https://x", "t")))))));
                check.same("picture-only first message", 1, t.entries().get(1).pictures);
                check.same("its token is not shown", "", t.entries().get(1).text);
            }
        });

        check.run("attached files", new Check.Body() {
            @Override
            public void run() {
                List<String> names = Arrays.asList("notes.pdf", "Song (2).mp3");
                String wrapped = Attached.wrap("  Read these  ", names);
                check.ok("typed first", wrapped.startsWith("Read these\n\n<delta-files>\n"));
                check.ok("tells the agent how", wrapped.contains("`delta pull <name>`"));
                check.ok("one name a line", wrapped.contains("\n- notes.pdf\n- Song (2).mp3\n</delta-files>"));
                check.same("names read back", names, Attached.names(wrapped));
                check.same("shown as one line", "Read these\n\nAttached: notes.pdf, Song (2).mp3", Attached.show(wrapped));
                check.same("nothing attached is just the text", "hi", Attached.wrap(" hi ", new ArrayList<String>()));
                check.same("files alone", "Attached: notes.pdf",
                    Attached.show(Attached.wrap("", Arrays.asList("notes.pdf"))));
                check.ok("one file is it", Attached.wrap("", Arrays.asList("a")).contains("fetch it with"));
                check.same("ordinary text untouched", "Use <delta-files> tags", Attached.show("Use <delta-files> tags"));
                check.same("numbered", "a (2).mp3", RemoteFile.numbered("a.mp3", 2));
                check.same("first is the name", "a.mp3", RemoteFile.numbered("a.mp3", 1));
                check.same("hidden-looking stem kept whole", "x (3)", RemoteFile.numbered("x", 3));

                Transcript t = new Transcript();
                String first = Setup.wrap(Attached.wrap("Fix this", Arrays.asList("log.txt")), "https://x", "t");
                t.apply("client_event", "1", frame(1, "client", user("u-1", first)));
                check.same("replayed message reads as typed, with its files",
                    "Fix this\n\nAttached: log.txt", t.entries().get(0).text);
            }
        });

        check.run("config", new Check.Body() {
            @Override
            public void run() throws Exception {
                Config both = Config.of("a", "r", 5).withCenter("cc.example.com/", "delta_t");
                check.same("https assumed, slash dropped", "https://cc.example.com", both.server());
                Config back = Config.parse(both.toJson());
                check.same("server", "https://cc.example.com", back.server());
                check.same("token", "delta_t", back.token());
                check.same("cloud kept", "r", back.refreshToken());
                check.ok("center ready", back.centerReady());
                check.ok("cloud only", !Config.of("a", null, 0).centerReady());
                check.same("prefix kept", "https://example.com/delta",
                    Config.blank().withCenter("https://example.com/delta", "t").server());
                check.fails("plain http refused", "https", new Check.Body() {
                    @Override
                    public void run() {
                        Config.blank().withCenter("http://cc.example.com", "t");
                    }
                });
                check.fails("token needed", new Check.Body() {
                    @Override
                    public void run() {
                        Config.blank().withCenter("https://cc.example.com", " ");
                    }
                });
                check.fails("no spaces in a token", new Check.Body() {
                    @Override
                    public void run() {
                        Config.blank().withCenter("https://cc.example.com", "a b");
                    }
                });
                Config bad = Config.parse("{\"claude\":{\"refreshToken\":\"r\"},"
                    + "\"center\":{\"server\":\"http://x\",\"token\":\"t\"}}");
                check.ok("a refused center keeps the cloud login", bad.ready() && !bad.centerReady());
                Config rotated = both.withCloud("a2", "r2", 9);
                check.same("rotation keeps the center", "delta_t", rotated.token());
                check.same("center of another", "delta_t", Config.of("x", "y", 1).withCenterOf(both).token());
                check.same("cloud of another", "r", Config.blank().withCloudOf(both).refreshToken());
                check.same("legacy login key still read", "r",
                    Config.parse("{\"claude\":{\"refreshToken\":\"r\"}}").refreshToken());
                check.ok("written under the new key", both.toJson().contains("\"cloud\""));
            }
        });

        check.run("folders to move a file into", new Check.Body() {
            @Override
            public void run() throws Exception {
                check.same("music first for a song", Folders.MUSIC, Folders.forName("Artist - Song.FLAC").get(0));
                check.same("ringtones next", Folders.RINGTONES, Folders.forName("a.mp3").get(1));
                check.same("pictures first for a photo", Folders.PICTURES, Folders.forName("x.jpg").get(0));
                check.same("movies first for a video", Folders.MOVIES, Folders.forName("x.mp4").get(0));
                check.same("documents first for a book", Folders.DOCUMENTS, Folders.forName("A - B.epub").get(0));
                check.same("download first for anything else", Folders.DOWNLOAD, Folders.forName("app.apk").get(0));
                check.same("download first without an extension", Folders.DOWNLOAD, Folders.forName("README").get(0));
                List<String> all = Folders.forName("x.mp3");
                check.same("every folder offered once", 9, all.size());
                check.same("no repeats", 9, new java.util.HashSet<String>(all).size());

                File folder = Files.createTempDirectory("delta-folders").toFile();
                check.same("free name kept", "a.mp3", Folders.unused(folder, "a.mp3").getName());
                new File(folder, "a.mp3").createNewFile();
                check.same("taken name numbered", "a (2).mp3", Folders.unused(folder, "a.mp3").getName());
                new File(folder, "a (2).mp3").createNewFile();
                check.same("next number", "a (3).mp3", Folders.unused(folder, "a.mp3").getName());
                new File(folder, "notes").createNewFile();
                check.same("no extension", "notes (2)", Folders.unused(folder, "notes").getName());
                check.same("same folder", folder, Folders.unused(folder, "notes").getParentFile());

                new File(folder, "beta").mkdir();
                new File(folder, "Alpha").mkdir();
                new File(folder, ".thumbnails").mkdir();
                check.same("subfolders in order, files and hidden ones left out",
                    Arrays.asList("Alpha", "beta"), Folders.subfolders(folder));
                check.same("files in order, folders and hidden ones left out",
                    Arrays.asList("a (2).mp3", "a.mp3", "notes"), Folders.files(folder));
                new File(folder, ".nomedia").createNewFile();
                check.same("hidden file left out", 3, Folders.files(folder).size());
                check.same("nothing inside a missing folder", 0,
                    Folders.subfolders(new File(folder, "missing")).size());
            }
        });
    }

    private static long sequenceAfter(String id, int sequence) {
        Transcript t = new Transcript();
        t.apply("client_event", id, frame(sequence, "worker", Json.map("type", "system")));
        return t.sequence();
    }

    static String frame(int sequence, String source, Map<String, Object> payload) {
        return Json.write(Json.map(
            "event_id", "e" + sequence,
            "sequence_num", String.valueOf(sequence),
            "source", source,
            "payload", payload
        ));
    }

    static Map<String, Object> user(String uuid, String text) {
        return Json.map("type", "user", "uuid", uuid, "parent_tool_use_id", null,
            "message", Json.map("role", "user", "content", text));
    }

    static Map<String, Object> assistant(String parent, Object... blocks) {
        return Json.map("type", "assistant", "parent_tool_use_id", parent,
            "message", Json.map("role", "assistant", "content", Json.list(blocks)));
    }
}
