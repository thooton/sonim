package com.mail.xp3;

import com.mail.xp3.net.Address;
import com.mail.xp3.net.Attachment;
import com.mail.xp3.net.Change;
import com.mail.xp3.net.Conversation;
import com.mail.xp3.net.Folder;
import com.mail.xp3.net.Gmail;
import com.mail.xp3.net.Html;
import com.mail.xp3.net.Me;
import com.mail.xp3.net.Message;
import com.mail.xp3.net.Mime;
import com.mail.xp3.net.Outgoing;
import com.mail.xp3.net.People;
import com.xp3.common.Check;
import com.xp3.common.Json;
import com.xp3.common.TestServer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/** Everything in :core, checked on a desktop JVM. */
public final class SelfTest {

    static final TimeZone PHOENIX = TimeZone.getTimeZone("America/Phoenix");
    /** 3:04 PM on Tuesday 13 October 2026 in Phoenix. */
    static final long TUESDAY = 1791929040000L;

    static final Me ME = new Me("me@gmail.com",
        Arrays.asList(new Address("Sam Smith", "me@gmail.com"), new Address("Sam", "sam@work.com")));

    public static void main(String[] args) throws Exception {
        final Check check = new Check();
        check.run("addresses", () -> {
            List<Address> list = Address.parse("\"Doe, Jane\" <jane@x.com>, bob@y.org, =?UTF-8?Q?Ren=C3=A9?= <rene@z.fr>, undisclosed-recipients:;");
            check.same("count", 3, list.size());
            check.same("quoted comma", "Doe, Jane", list.get(0).name);
            check.same("bare", "bob@y.org", list.get(1).display());
            check.same("encoded name", "René", list.get(2).name);
            check.same("brief", "Doe", list.get(0).brief());
            check.same("brief of bare", "bob", list.get(1).brief());
            check.same("comment name", "Bob", Address.parse("bob@y.org (Bob)").get(0).name);
            check.same("field round trip", 3, Address.typed(Address.field(list)).size());
            check.same("header quoting", "\"Doe, Jane\" <jane@x.com>", list.get(0).header());
            check.same("header encoding", "=?UTF-8?B?UmVuw6k=?= <rene@z.fr>", list.get(2).header());
            check.fails("typo", "Not an address: bob@", () -> Address.typed("jane@x.com, bob@"));
            check.same("typed allows a trailing comma", 1, Address.typed("jane@x.com, ").size());
        });
        check.run("mime", () -> {
            check.same("q words", "Café au lait", Mime.words("=?utf-8?q?Caf=C3=A9_au?= =?utf-8?q?_lait?="));
            check.same("b word", "€5 off", Mime.words("=?UTF-8?B?4oKsNSBvZmY=?="));
            check.same("latin-1 word", "Grüße", Mime.words("=?ISO-8859-1?Q?Gr=FC=DFe?="));
            check.same("plain", "Hello", Mime.words("Hello"));
            check.same("cp1252", "“quoted”", Mime.text(new byte[] {(byte) 0x93, 'q', 'u', 'o', 't', 'e', 'd', (byte) 0x94}, "iso-8859-1"));
            check.same("unknown charset is utf-8", "é", Mime.text("é".getBytes(Charset.forName("UTF-8")), "x-nonsense"));
            check.same("base64url unpadded", "hi?", new String(Mime.decode(Base64.getUrlEncoder().withoutPadding().encodeToString("hi?".getBytes()))));
            check.same("param", "utf-8", Mime.param("text/plain; charset=\"utf-8\"; format=flowed", "charset"));
            check.same("value", "attachment", Mime.value("Attachment; filename=a.pdf"));
            check.same("encode ascii", "Hi", Mime.encodeWord("Hi"));
            check.same("encode round trip", "Ünïcödé subject that goes on and on and on for a while",
                Mime.words(Mime.encodeWord("Ünïcödé subject that goes on and on and on for a while")));
            check.same("bare subject", "Lunch", Mime.bare("Re: Fwd: RE: Lunch"));
            check.same("quote", "> one\n>\n>> two", Mime.quote("one\n\n> two\n"));
            check.same("unquoted", "Sounds good.",
                Mime.unquoted("Sounds good.\n\nOn Tue, Oct 13, 2026 at 3:04 PM Jane <j@x.com>\nwrote:\n> Lunch?\n> \n"));
            check.same("outlook", "Yes",
                Mime.unquoted("Yes\n\nFrom: Jane\nSent: Tuesday\nTo: me\n"));
            check.same("nothing quoted", null, Mime.unquoted("Just text"));
            check.same("safe name", "a_b.pdf", Mime.safeName("../../a:b.pdf"));
            check.same("size", "120 KB", Mime.size(123000));
            check.same("links", "https://x.com/a?b=1", Mime.links("See https://x.com/a?b=1. Thanks").get(0)[1]);
        });
        check.run("html", () -> {
            String html = "<html><head><style>p{color:red}</style><title>T</title></head><body>"
                + "<p>Hello&nbsp;<b>Jane</b>,</p><p>Your   order\n shipped.<br>Track it <a href=\"https://t.co/1?a=1&amp;b=2\">here</a>.</p>"
                + "<ul><li>One</li><li>Two</li></ul><table><tr><td>Total</td><td>$5</td></tr></table>"
                + "<!-- hidden --><script>x()</script>&#8217;&rsquo;&zwnj;͏</body></html>";
            check.same("text", "Hello Jane,\n\nYour order shipped.\nTrack it here.\n\n• One\n• Two\n\nTotal $5\n\n’’", Html.text(html));
            List<String[]> links = Html.links(html + "<a href='mailto:a@b.c'></a><a href=\"javascript:x\">no</a>");
            check.same("links", 2, links.size());
            check.same("link text", "here", links.get(0)[0]);
            check.same("link address", "https://t.co/1?a=1&b=2", links.get(0)[1]);
            check.same("bare link", "mailto:a@b.c", links.get(1)[0]);
            check.same("escape", "a &lt;b&gt;<br>c", Html.escape("a <b>\nc"));
            check.same("body", "<p>x</p>", Html.body("<html><body class=a><p>x</p></body></html>"));
        });
        check.run("messages", () -> {
            Message plain = message("m1", "t1", Arrays.asList("INBOX", "UNREAD"), "Jane <jane@x.com>", "me@gmail.com",
                "=?UTF-8?Q?Caf=C3=A9?=", TUESDAY, part("text/plain; charset=utf-8", "Coffee at 3?\r\nJane"));
            check.same("subject", "Café", plain.subject());
            check.same("sender", "Jane", plain.sender());
            check.same("body", "Coffee at 3?\nJane", plain.content().body());
            check.ok("unread", plain.unread());
            check.same("when", "Tue, Oct 13, 2026 at 3:04 PM", plain.when(PHOENIX));
            check.same("attribution", "On Tue, Oct 13, 2026 at 3:04 PM Jane <jane@x.com> wrote:", plain.attribution(PHOENIX));

            Map<String, Object> mixed = multipart("multipart/mixed",
                multipart("multipart/alternative", part("text/plain", "View in browser"),
                    part("text/html; charset=\"windows-1252\"", "<p>Big <b>sale</b></p><a href=\"https://shop.example\">Shop</a>")),
                file("report.pdf", "application/pdf", "ANGjdJ8", 120000, null),
                file("", "image/png", null, 30, "img1"));
            Message rich = message("m2", "t1", Arrays.asList("INBOX"), "Shop <deals@shop.example>", "me@gmail.com", "Sale", TUESDAY, mixed);
            Message.Content content = rich.content();
            check.same("html when the text is a stub", "Big sale\n\nShop", content.body());
            check.same("files", 2, content.attachments.size());
            check.same("file name", "report.pdf, 117 KB", content.attachments.get(0).label());
            check.same("file id", "ANGjdJ8", content.attachments.get(0).id);
            check.same("inline image", "img1", content.attachments.get(1).contentId);
            check.same("links", "https://shop.example", content.links().get(0)[1]);
        });
        check.run("conversations", () -> {
            Conversation c = conversation("t1",
                message("a", "t1", Arrays.asList("INBOX"), "Jane Doe <jane@x.com>", "me@gmail.com", "Lunch", TUESDAY, part("text/plain", "Lunch?")),
                message("b", "t1", Arrays.asList("SENT"), "Sam <me@gmail.com>", "jane@x.com", "Re: Lunch", TUESDAY + 60000, part("text/plain", "Yes")),
                message("c", "t1", Arrays.asList("INBOX", "UNREAD"), "Jane Doe <jane@x.com>", "me@gmail.com", "Re: Lunch", TUESDAY + 120000, part("text/plain", "Noon")),
                message("d", "t1", Arrays.asList("DRAFT"), "me@gmail.com", "jane@x.com", "Re: Lunch", TUESDAY + 180000, part("text/plain", "Can")));
            check.same("subject is the first", "Lunch", c.subject());
            check.same("latest skips drafts", "c", c.latest().id);
            check.same("people", "Jane, me, Draft (4)", c.people(ME, false));
            check.same("sent shows whom", "To: jane@x.com", c.people(ME, true));
            check.ok("unread", c.unread());
            check.ok("has a draft", c.draft() != null && !c.onlyDrafts());
            check.ok("in the inbox", Folder.INBOX.holds(c));
            check.same("offered", Arrays.asList(Change.ARCHIVE, Change.TRASH, Change.READ, Change.STAR, Change.SPAM), Change.offered(c));
            Change.ARCHIVE.apply(c);
            check.ok("archived leaves the inbox", !Folder.INBOX.holds(c) && Folder.ALL.holds(c));
            check.same("then offers inbox", Change.INBOX, Change.offered(c).get(0));
            Change.TRASH.apply(c);
            check.ok("trashed", Folder.TRASH.holds(c) && !Folder.ALL.holds(c));
            check.same("then offers restore", Change.UNTRASH, Change.offered(c).get(0));
            Change.READ.apply(c);
            check.ok("read", !c.unread());
            check.ok("a search keeps it", Folder.search("lunch").holds(c));
            check.same("search name", "“lunch”", Folder.search(" lunch ").name);
        });
        check.run("replying", () -> {
            Message original = message("m1", "t9", Arrays.asList("INBOX"), "Jane <jane@x.com>",
                "Sam <sam@work.com>, bob@y.org", "Plans", TUESDAY,
                part("text/plain", "Dinner?\nJane"), "Cc", "carol@z.org", "Message-ID", "<abc@x.com>",
                "References", "<root@x.com>");
            Outgoing reply = Outgoing.reply(original, ME, false, PHOENIX);
            check.same("to the sender", "Jane <jane@x.com>", reply.to);
            check.same("no cc", "", reply.cc);
            check.same("re", "Re: Plans", reply.subject);
            check.same("from the address it was sent to", "\"Sam\" <sam@work.com>", reply.from);
            check.same("thread", "t9", reply.threadId);
            check.same("in reply to", "<abc@x.com>", reply.inReplyTo);
            check.same("references", "<root@x.com> <abc@x.com>", reply.references);
            reply.text = "Yes!  \n";
            check.same("quoted below", "Yes!\n\nOn Tue, Oct 13, 2026 at 3:04 PM Jane <jane@x.com> wrote:\n> Dinner?\n> Jane", reply.body());
            Outgoing all = Outgoing.reply(original, ME, true, PHOENIX);
            check.same("reply all copies the rest, not me", "bob@y.org, carol@z.org", all.cc);

            Message mine = message("m2", "t9", Arrays.asList("SENT"), "Sam Smith <me@gmail.com>", "jane@x.com", "Re: Plans", TUESDAY, part("text/plain", "Hi"));
            check.same("my own message goes to its recipients", "jane@x.com", Outgoing.reply(mine, ME, false, PHOENIX).to);
            check.same("re once", "Re: Plans", Outgoing.reply(mine, ME, false, PHOENIX).subject);

            Outgoing forward = Outgoing.forward(original, ME, PHOENIX);
            check.same("fwd", "Fwd: Plans", forward.subject);
            check.ok("forwarded block", forward.tail.startsWith("---------- Forwarded message ---------\nFrom: Jane <jane@x.com>\nDate: Tue, Oct 13, 2026 at 3:04 PM\nSubject: Plans\nTo: Sam <sam@work.com>, bob@y.org\nCc: carol@z.org\n\nDinner?"));
            check.same("no html to carry", "", forward.tailHtml);
        });
        check.run("raw", () -> {
            Outgoing out = Outgoing.compose("\"Doe, Jane\" <jane@x.com>");
            out.cc = "bob@y.org";
            out.subject = "Café";
            out.text = "Line one\nLine two";
            out.attachments.add(new Attachment("m1", "", Base64.getUrlEncoder().encodeToString("PDF!".getBytes()), "résumé.pdf",
                "application/pdf", 4, "", false));
            String raw = out.raw(new Address("Sam Smith", "me@gmail.com"), TUESDAY, a -> Mime.decode(a.data));
            check.ok("ascii", Mime.ascii(raw.replace("\r\n", "")));
            check.ok("from", raw.contains("\r\nFrom: \"Sam Smith\" <me@gmail.com>\r\n"));
            check.ok("to", raw.contains("\r\nTo: \"Doe, Jane\" <jane@x.com>\r\n"));
            check.ok("cc", raw.contains("\r\nCc: bob@y.org\r\n"));
            check.ok("subject", raw.contains("\r\nSubject: =?UTF-8?B?Q2Fmw6k=?=\r\n"));
            check.ok("mixed", raw.contains("Content-Type: multipart/mixed; boundary="));
            check.ok("text", raw.contains(Mime.base64("Line one\r\nLine two".getBytes(Charset.forName("UTF-8")))));
            check.ok("file", raw.contains("Content-Disposition: attachment; filename=\"=?UTF-8?B?csOpc3Vtw6kucGRm?=\"")
                && raw.contains(Mime.base64("PDF!".getBytes())));
            check.ok("no bcc header", !raw.contains("Bcc:"));
            check.fails("nobody", "Who is it to?", () -> Outgoing.compose("").recipients());

            Outgoing back = Outgoing.parse(out.toJson());
            check.same("kept to", out.to, back.to);
            check.same("kept text", out.text, back.text);
            check.same("kept file", "résumé.pdf", back.attachments.get(0).name);
            check.same("junk", null, Outgoing.parse("{"));
            Outgoing start = Outgoing.compose("");
            Outgoing typed = Outgoing.compose("");
            check.ok("untouched", typed.untouched(start));
            typed.text = "x";
            check.ok("touched", !typed.untouched(start));
        });
        check.run("people", () -> {
            People people = new People();
            people.seen(Arrays.asList(new Address("", "jane@x.com"), new Address("Sam", "me@gmail.com"),
                new Address("", "no-reply@shop.com"), new Address("Bob", "bob@y.org")), ME);
            check.same("me and robots left out", 2, people.all().size());
            people.seen(Arrays.asList(new Address("Jane Doe", "JANE@x.com")), ME);
            check.same("a name fills in", "Jane Doe", people.all().get(0).name);
            people.wrote(Arrays.asList(new Address("", "bob@y.org")));
            check.same("written to first", "bob@y.org", people.all().get(0).email);
            check.same("keeps the name", "Bob", people.all().get(0).name);
            check.same("matching", "jane@x.com", people.matching("doe").get(0).email);
            check.same("round trip", 2, People.parse(people.toJson()).all().size());
        });
        check.run("gmail", () -> gmail(check));
        if (!check.report()) System.exit(1);
    }

    // ---- The client, against a server standing in for the command center, Google and Gmail ----

    static void gmail(Check check) throws Exception {
        final AtomicInteger tokens = new AtomicInteger();
        final AtomicInteger refuse = new AtomicInteger();
        try (TestServer server = new TestServer(request -> {
            String path = request.path;
            if (path.equals("/center/gmail-login")) {
                if (!request.header("Authorization").equals("Bearer delta-token")) return TestServer.Reply.of(401, "application/json", "{}");
                return TestServer.Reply.json("{\"client_id\":\"cid\",\"client_secret\":\"sec\",\"refresh_token\":\"r/1\",\"email\":\"me@gmail.com\"}");
            }
            if (path.equals("/token")) {
                if (!request.body.contains("refresh_token=r%2F1") || !request.body.contains("client_id=cid")) {
                    return TestServer.Reply.of(400, "application/json", "{\"error\":\"invalid_grant\"}");
                }
                return TestServer.Reply.json("{\"access_token\":\"at" + tokens.incrementAndGet() + "\",\"expires_in\":3599}");
            }
            if (!request.header("Authorization").startsWith("Bearer at")) return TestServer.Reply.of(401, "application/json", "{}");
            if (refuse.getAndDecrement() > 0) {
                return TestServer.Reply.of(401, "application/json", "{\"error\":{\"message\":\"Invalid Credentials\"}}");
            }
            String me = "/gmail/v1/users/me";
            if (path.equals(me + "/settings/sendAs")) {
                return TestServer.Reply.json("{\"sendAs\":[{\"sendAsEmail\":\"me@gmail.com\",\"displayName\":\"Sam Smith\",\"isDefault\":true}]}");
            }
            if (path.startsWith(me + "/threads?")) {
                if (path.contains("pageToken=p2")) return TestServer.Reply.json("{\"threads\":[{\"id\":\"t3\"}]}");
                return TestServer.Reply.json("{\"threads\":[{\"id\":\"t1\"},{\"id\":\"gone\"}],\"nextPageToken\":\"p2\"}");
            }
            if (path.startsWith(me + "/threads/gone")) return TestServer.Reply.of(404, "application/json", "{\"error\":{\"message\":\"Not Found\"}}");
            if (path.startsWith(me + "/threads/t1?") || path.startsWith(me + "/threads/t3?")) {
                String id = path.substring((me + "/threads/").length(), path.indexOf('?'));
                return TestServer.Reply.json(Json.write(Json.map("id", id, "messages", Json.list(
                    raw("m-" + id, id, "Jane <jane@x.com>", "Hello", path.contains("format=full") ? "Hi there" : null)))));
            }
            if (path.equals(me + "/threads/t1/modify")) return TestServer.Reply.json("{}");
            if (path.equals(me + "/threads/t1/trash")) return TestServer.Reply.json("{}");
            if (path.equals(me + "/labels/INBOX")) return TestServer.Reply.json("{\"threadsUnread\":7}");
            if (path.equals(me + "/labels")) {
                return TestServer.Reply.json("{\"labels\":[{\"id\":\"INBOX\",\"name\":\"INBOX\",\"type\":\"system\"},"
                    + "{\"id\":\"Label_2\",\"name\":\"work\",\"type\":\"user\"},{\"id\":\"Label_1\",\"name\":\"Bills\",\"type\":\"user\"}]}");
            }
            if (path.equals(me + "/messages/m1/attachments/A1")) {
                return TestServer.Reply.json("{\"data\":\"" + Base64.getUrlEncoder().encodeToString("file!".getBytes()) + "\"}");
            }
            if (path.equals("/upload/gmail/v1/users/me/messages/send?uploadType=multipart")) {
                return TestServer.Reply.json("{\"id\":\"sent1\"}");
            }
            if (path.equals("/upload/gmail/v1/users/me/drafts?uploadType=multipart")) return TestServer.Reply.json("{\"id\":\"d1\"}");
            if (path.equals("/upload/gmail/v1/users/me/drafts/d1?uploadType=multipart")) return TestServer.Reply.json("{\"id\":\"d1\"}");
            if (path.equals(me + "/drafts/send")) return TestServer.Reply.json("{\"id\":\"sent2\"}");
            if (path.startsWith(me + "/drafts?")) return TestServer.Reply.json("{\"drafts\":[{\"id\":\"d9\",\"message\":{\"id\":\"m9\"}}]}");
            if (path.equals(me + "/drafts/d1")) return TestServer.Reply.json("");
            return TestServer.Reply.of(404, "application/json", "{\"error\":{\"message\":\"no route " + path + "\"}}");
        })) {
            String base = server.uri("").toString();
            Gmail gmail = new Gmail(base + "/center", "delta-token", base, base + "/token");

            Gmail.Page page = gmail.list(Folder.INBOX, null);
            check.same("a deleted thread is skipped", 1, page.conversations.size());
            check.same("subject", "Hello", page.conversations.get(0).subject());
            check.same("next", "p2", page.next);
            check.ok("query sent", seen(server, "/threads?maxResults=20&q=in%3Ainbox"));
            check.same("one token for all of it", 1, tokens.get());
            check.same("next page", "t3", gmail.list(Folder.INBOX, "p2").conversations.get(0).id);
            check.ok("trash asks for trash", gmail.list(Folder.TRASH, null) != null && seen(server, "includeSpamTrash=true"));

            Conversation full = gmail.conversation("t1");
            check.same("full text", "Hi there", full.messages.get(0).content().body());
            check.same("unread", 7, gmail.unread());
            List<Folder> labels = gmail.labels();
            check.same("user labels, sorted", "Bills", labels.get(0).name);
            check.same("label id", "Label_1", labels.get(0).label);
            check.same("me", "Sam Smith", gmail.me().addresses.get(0).name);

            gmail.change("t1", Change.ARCHIVE);
            check.ok("archive", lastBody(server, "/threads/t1/modify").equals("{\"addLabelIds\":[],\"removeLabelIds\":[\"INBOX\"]}"));
            gmail.change("t1", Change.TRASH);
            check.ok("trash", seen(server, "/threads/t1/trash"));

            refuse.set(1);
            gmail.change("t1", Change.STAR);
            check.same("a refused token is renewed once", 2, tokens.get());
            refuse.set(2);
            check.fails("but only once", "rejected the login", () -> gmail.change("t1", Change.STAR));
            refuse.set(0);

            byte[] file = gmail.attachment(new Attachment("m1", "A1", "", "a.txt", "text/plain", 5, "", false));
            check.same("attachment", "file!", new String(file));
            check.fails("too big", "about 6 MB", () -> gmail.attachment(new Attachment("m1", "A1", "", "big.zip", "", 9000000, "", false)));
            check.fails("an id that isn't one", "not a Gmail id", () -> gmail.conversation("../x"));

            Outgoing out = Outgoing.compose("jane@x.com");
            out.subject = "Hi";
            out.text = "Hello";
            out.threadId = "t1";
            check.same("sent", "sent1", gmail.send(out));
            String upload = lastBody(server, "/messages/send");
            check.ok("upload carries the thread", upload.contains("{\"threadId\":\"t1\"}"));
            check.ok("upload carries the message", upload.contains("Content-Type: message/rfc822") && upload.contains("To: jane@x.com"));
            check.ok("from the default", upload.contains("From: \"Sam Smith\" <me@gmail.com>"));
            check.fails("a typo isn't sent", "Not an address", () -> gmail.send(Outgoing.compose("jane@")));

            Outgoing draft = Outgoing.compose("jane@x.com");
            check.same("saved", "d1", gmail.save(draft));
            gmail.save(draft);
            check.ok("saved again in place", seen(server, "/drafts/d1?uploadType=multipart"));
            check.same("sending a draft sends it", "sent2", gmail.send(draft));
            check.ok("as itself", lastBody(server, "/drafts/send").equals("{\"id\":\"d1\"}"));
            check.same("draft lookup", "d9", gmail.draftId("m9"));
            check.same("no draft", null, gmail.draftId("m0"));
            gmail.discard("d1");
            check.ok("discard", seen(server, "DELETE", "/drafts/d1"));
            gmail.close();

            Gmail stranger = new Gmail(base + "/center", "wrong", base, base + "/token");
            check.fails("command center refuses", "Command center HTTP 401", () -> stranger.unread());
            stranger.close();
        }
        try (TestServer none = new TestServer(request -> TestServer.Reply.of(404, "application/json", "{\"error\":\"none\"}"))) {
            String base = none.uri("").toString();
            Gmail gmail = new Gmail(base, "t", base, base + "/token");
            check.fails("not set up", "delta gmail login", () -> gmail.unread());
            gmail.close();
        }
    }

    static boolean seen(TestServer server, String fragment) {
        for (TestServer.Request request : server.seen) if (request.path.contains(fragment)) return true;
        return false;
    }

    static boolean seen(TestServer server, String method, String fragment) {
        for (TestServer.Request request : server.seen) if (request.method.equals(method) && request.path.contains(fragment)) return true;
        return false;
    }

    static String lastBody(TestServer server, String fragment) {
        String body = "";
        for (TestServer.Request request : server.seen) if (request.path.contains(fragment)) body = request.body;
        return body;
    }

    // ---- Gmail's JSON, built by hand ----

    static Map<String, Object> raw(String id, String thread, String from, String subject, String text) {
        Map<String, Object> payload = text == null
            ? Json.map("mimeType", "text/plain", "headers", Json.list())
            : part("text/plain", text);
        List<Object> headers = Json.children(payload, "headers");
        headers.add(Json.map("name", "From", "value", from));
        headers.add(Json.map("name", "Subject", "value", subject));
        payload.put("headers", headers);
        return Json.map("id", id, "threadId", thread, "labelIds", Json.list("INBOX"), "snippet", "Hi &amp; bye",
            "internalDate", String.valueOf(TUESDAY), "payload", payload);
    }

    static Message message(String id, String thread, List<String> labels, String from, String to, String subject,
                           long time, Map<String, Object> payload, String... more) {
        List<Object> headers = Json.list(Json.map("name", "From", "value", from), Json.map("name", "To", "value", to),
            Json.map("name", "Subject", "value", subject));
        for (int i = 0; i < more.length; i += 2) headers.add(Json.map("name", more[i], "value", more[i + 1]));
        headers.addAll(Json.children(payload, "headers"));
        payload.put("headers", headers);
        return new Message(Json.map("id", id, "threadId", thread, "labelIds", new java.util.ArrayList<Object>(labels),
            "internalDate", String.valueOf(time), "payload", payload));
    }

    static Conversation conversation(String id, Message... messages) {
        // Through JSON again, as Gmail would send it.
        List<Object> list = Json.list();
        for (Message message : messages) list.add(Json.map("id", message.id, "threadId", id,
            "labelIds", labelsOf(message), "internalDate", String.valueOf(message.time()),
            "payload", Json.map("mimeType", "text/plain", "headers", Json.list(
                Json.map("name", "From", "value", message.header("From")),
                Json.map("name", "To", "value", message.header("To")),
                Json.map("name", "Subject", "value", message.header("Subject"))))));
        return new Conversation(Json.map("id", id, "messages", list));
    }

    static List<Object> labelsOf(Message message) {
        List<Object> out = Json.list();
        for (String label : Arrays.asList("INBOX", "SENT", "UNREAD", "DRAFT", "STARRED")) if (message.has(label)) out.add(label);
        return out;
    }

    static Map<String, Object> part(String type, String text) {
        String mime = type.contains(";") ? type.substring(0, type.indexOf(';')).trim() : type;
        String charset = Mime.param(type, "charset");
        byte[] bytes = text.getBytes(Charset.forName(charset.length() == 0 ? "UTF-8" : charset));
        return Json.map("mimeType", mime, "headers", Json.list(Json.map("name", "Content-Type", "value", type)),
            "body", Json.map("size", Integer.valueOf(bytes.length), "data", Base64.getUrlEncoder().encodeToString(bytes)));
    }

    @SafeVarargs
    static Map<String, Object> multipart(String type, Map<String, Object>... parts) {
        return Json.map("mimeType", type, "headers", Json.list(), "parts", new java.util.ArrayList<Object>(Arrays.asList((Object[]) parts)),
            "body", Json.map("size", Integer.valueOf(0)));
    }

    static Map<String, Object> file(String name, String type, String id, long size, String contentId) {
        List<Object> headers = Json.list();
        if (contentId != null) headers.add(Json.map("name", "Content-ID", "value", "<" + contentId + ">"));
        Map<String, Object> body = Json.map("size", Long.valueOf(size));
        if (id != null) body.put("attachmentId", id);
        return Json.map("mimeType", type, "filename", name, "headers", headers, "body", body);
    }

    static List<String> list(String... items) {
        return Collections.unmodifiableList(Arrays.asList(items));
    }
}
