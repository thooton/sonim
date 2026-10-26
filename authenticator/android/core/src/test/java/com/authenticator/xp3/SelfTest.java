package com.authenticator.xp3;

import com.authenticator.xp3.qr.QrChecks;
import com.authenticator.xp3.qr.QrReader;
import com.xp3.common.Check;
import com.xp3.common.TestServer;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Everything in :core, checked on a desktop JVM. The QR pictures in
 * resources/qr are made by fixtures.py.
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        final Check check = new Check();

        check.run("base32", () -> {
            check.same("tidy", "JBSWY3DPEHPK3PXP", Base32.tidy(" jbsw-y3dp ehpk 3pxp=="));
            check.same("round trip", "Hello!Þ", new String(Base32.decode(Base32.encode("Hello!Þ".getBytes(StandardCharsets.ISO_8859_1))), StandardCharsets.ISO_8859_1));
            check.same("known value", "JBSWY3DPEE", Base32.encode("Hello!".getBytes(StandardCharsets.US_ASCII)));
            check.fails("not base32", "letters and the digits 2 to 7", () -> Base32.decode("ABC1"));
            check.fails("empty", () -> Base32.decode(" "));
        });

        check.run("codes (RFC 6238's test vectors)", () -> {
            Account sha1 = new Account(null, "Test", "", Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII)), "SHA1", 8, 30);
            Account sha256 = new Account(null, "Test", "", Base32.encode("12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII)), "SHA256", 8, 30);
            Account sha512 = new Account(null, "Test", "", Base32.encode(
                "1234567890123456789012345678901234567890123456789012345678901234".getBytes(StandardCharsets.US_ASCII)), "SHA512", 8, 30);
            long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
            String[] one = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
            String[] two = {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"};
            String[] five = {"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"};
            for (int i = 0; i < times.length; i++) {
                check.same("SHA1 at " + times[i], one[i], sha1.code(times[i] * 1000));
                check.same("SHA256 at " + times[i], two[i], sha256.code(times[i] * 1000));
                check.same("SHA512 at " + times[i], five[i], sha512.code(times[i] * 1000));
            }
            Account six = Account.of("GitHub", "sam", "JBSWY3DPEHPK3PXP");
            check.same("six digits (as Python's hmac works it out)", "324550", six.code(1700000000000L));
            check.same("same code through the period", six.code(1700000010000L), six.code(1700000019999L));
            check.same("spaced, six", "482 963", Account.spaced("482963"));
            check.same("spaced, eight", "1234 5678", Account.spaced("12345678"));
            check.close("a full period left at its start", 1.0, six.left(1700000010000L), 1e-6);
            check.close("half left halfway", 0.5, six.left(1700000025000L), 1e-6);
            check.fails("MD5 refused", "SHA1, SHA256 and SHA512", () -> new Account(null, "A", "", "JBSWY3DPEHPK3PXP", "MD5", 6, 30));
            check.fails("nameless refused", "needs a name", () -> Account.of(" ", "", "JBSWY3DPEHPK3PXP"));
            check.same("title and detail", "GitHub|sam", six.title() + "|" + six.detail());
            Account bare = Account.of("", "sam@example.com", "JBSWY3DPEHPK3PXP");
            check.same("an account alone leads", "sam@example.com|", bare.title() + "|" + bare.detail());
        });

        check.run("otpauth links", () -> {
            List<Account> parsed = OtpLink.parse("otpauth://totp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60");
            Account a = parsed.get(0);
            check.same("issuer", "ACME Co", a.issuer);
            check.same("account", "john.doe@email.com", a.account);
            check.same("secret", "HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", a.secret);
            check.same("settings", "SHA256 8 60", a.algorithm + " " + a.digits + " " + a.period);
            Account b = OtpLink.parse("otpauth://totp/Example:alice@google.com?secret=JBSWY3DPEHPK3PXP").get(0);
            check.same("issuer from the label", "Example|alice@google.com", b.issuer + "|" + b.account);
            check.same("defaults", "SHA1 6 30", b.algorithm + " " + b.digits + " " + b.period);
            Account c = OtpLink.parse("OTPAUTH://TOTP/alice?secret=jbswy3dpehpk3pxp&issuer=My+Bank").get(0);
            check.same("plus as space, any case", "My Bank|alice|JBSWY3DPEHPK3PXP", c.issuer + "|" + c.account + "|" + c.secret);
            Account d = OtpLink.parse("otpauth://totp/%E2%9C%93Service:%C3%BC?secret=JBSWY3DPEHPK3PXP").get(0);
            check.same("UTF-8 label", "✓Service|ü", d.issuer + "|" + d.account);
            check.fails("counter based", "time-based", () -> OtpLink.parse("otpauth://hotp/A:b?secret=JBSWY3DPEHPK3PXP&counter=1"));
            check.fails("no secret", "no secret", () -> OtpLink.parse("otpauth://totp/A:b?issuer=A"));
            check.fails("not two-factor", "isn't for two-factor", () -> OtpLink.parse("https://example.com"));
        });

        check.run("services", () -> {
            Services few = Services.parse("Amazon\tamazon.com\thttps://amazon.com/icon.png\n"
                + "Amazon Web Services\taws.amazon.com\t\n"
                + "X (Twitter)\tx.com,twitter.com\tasset:twitter.png\n"
                + "Epic Games\tepicgames.com\thttps://epicgames.com/i.png\n"
                + "bad line\n");
            check.same("rows", 4, few.size());
            check.same("by name", "Amazon", few.find(Account.of("amazon", "sam", "JBSWY3DPEHPK3PXP")).name);
            check.same("by label", "Amazon Web Services", few.find(Account.of("AWS", "root", "JBSWY3DPEHPK3PXP")).name);
            check.same("by a name in brackets", "X (Twitter)", few.find(Account.of("Twitter", "", "JBSWY3DPEHPK3PXP")).name);
            check.same("by the name before brackets", "X (Twitter)", few.find(Account.of("X", "", "JBSWY3DPEHPK3PXP")).name);
            check.same("by another domain", "X (Twitter)", few.find(Account.of("www.twitter.com", "", "JBSWY3DPEHPK3PXP")).name);
            check.same("squashed", "Epic Games", few.find(Account.of("EpicGames", "", "JBSWY3DPEHPK3PXP")).name);
            check.same("what follows a colon", "Epic Games", few.find(Account.of("Epic Games: Fortnite", "", "JBSWY3DPEHPK3PXP")).name);
            check.same("the first word", "Amazon", few.find(Account.of("Amazon Seller", "", "JBSWY3DPEHPK3PXP")).name);
            check.same("a domain the list lacks", "example.org", few.find(Account.of("Example.org", "", "JBSWY3DPEHPK3PXP")).domain);
            check.same("nothing fits", null, few.find(Account.of("My Bank", "", "JBSWY3DPEHPK3PXP")));
            check.same("an email alone isn't its provider", null, few.find(Account.of("", "sam@amazon.com", "JBSWY3DPEHPK3PXP")));
            check.same("the site's logo, then Google's", "https://amazon.com/icon.png|" + Services.favicon("amazon.com"),
                String.join("|", few.find(Account.of("Amazon", "", "JBSWY3DPEHPK3PXP")).logos()));
            check.same("no logo of its own", 1, few.find(Account.of("AWS", "", "JBSWY3DPEHPK3PXP")).logos().size());
            check.same("a bundled logo alone, with no fallback", "asset:twitter.png",
                String.join("|", few.find(Account.of("Twitter", "", "JBSWY3DPEHPK3PXP")).logos()));

            Services all = Services.parse(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("../app/src/main/assets/services.tsv")), StandardCharsets.UTF_8));
            check.ok("the app's list is large (" + all.size() + ")", all.size() > 2000);
            String[][] known = {
                {"GitHub", "github.com"}, {"Google", "google.com"}, {"Microsoft", "microsoft.com"}, {"Discord", "discord.com"},
                {"AWS", "aws.amazon.com"}, {"Dropbox", "dropbox.com"}, {"Twitter", "x.com"}, {"Coinbase", "coinbase.com"},
                {"PayPal", "paypal.com"}, {"Proton", "proton.me"}, {"Anthropic", "anthropic.com"},
                {"Tutanota", "tuta.com"}, {"Tuta", "tuta.com"}, {"SpigotMC", "spigotmc.org"}, {"Spigot", "spigotmc.org"},
            };
            for (String[] pair : known) {
                Services.Service found = all.find(Account.of(pair[0], "sam", "JBSWY3DPEHPK3PXP"));
                check.same(pair[0], pair[1], found == null ? null : found.domain);
            }
            String[][] bundled = { {"Twitter", "twitter.png"}, {"Tutanota", "tutanota.png"}, {"SpigotMC", "spigotmc.png"} };
            for (String[] pair : bundled) {
                Services.Service found = all.find(Account.of(pair[0], "sam", "JBSWY3DPEHPK3PXP"));
                check.same(pair[0] + " carries its own logo", "asset:" + pair[1], found == null ? null : found.logo);
                check.ok(pair[0] + "'s logo is in the app",
                    new java.io.File("../app/src/main/assets/logos/" + pair[1]).length() > 0);
            }
        });

        List<String[]> fixtures = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            SelfTest.class.getResourceAsStream("/qr/expected.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.length() > 0) fixtures.add(line.split("\t", 2));
            }
        }

        check.run("google authenticator export", () -> {
            String link = null;
            for (String[] fixture : fixtures) if (fixture[0].equals("migration.png")) link = fixture[1];
            List<Account> accounts = OtpLink.parse(link);
            check.same("the counter-based one skipped", 2, accounts.size());
            Account github = accounts.get(0);
            check.same("first", "GitHub|sam@example.com|SHA1|6", github.issuer + "|" + github.account + "|" + github.algorithm + "|" + github.digits);
            check.same("its secret", Base32.encode("Hello!Þ­¾ï".getBytes(StandardCharsets.ISO_8859_1)), github.secret);
            Account aws = accounts.get(1);
            check.same("second", "AWS|root|SHA256|8", aws.issuer + "|" + aws.account + "|" + aws.algorithm + "|" + aws.digits);
            check.fails("damaged", "damaged", () -> OtpLink.parse("otpauth-migration://offline?data=CgQK"));
        });

        check.run("qr pictures", () -> {
            for (String[] fixture : fixtures) {
                BufferedImage image;
                try (InputStream in = SelfTest.class.getResourceAsStream("/qr/" + fixture[0])) {
                    image = ImageIO.read(in);
                }
                int width = image.getWidth();
                int height = image.getHeight();
                int[] luminance = new int[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int rgb = image.getRGB(x, y);
                        luminance[y * width + x] = (((rgb >> 16) & 0xFF) * 306 + ((rgb >> 8) & 0xFF) * 601 + (rgb & 0xFF) * 117) >> 10;
                    }
                }
                long started = System.nanoTime();
                String text = QrReader.read(luminance, width, height);
                long millis = (System.nanoTime() - started) / 1000000;
                check.same(fixture[0], fixture[1].equals("NONE") ? null : fixture[1], text);
                check.ok(fixture[0] + " in under 3 s (" + millis + " ms)", millis < 3000);
            }
        });

        QrChecks.run(check);

        check.run("command center", () -> {
            final List<String> stored = Collections.synchronizedList(new ArrayList<String>());
            stored.add("{\"id\":\"a1\",\"issuer\":\"GitHub\",\"account\":\"sam\",\"secret\":\"JBSWY3DPEHPK3PXP\",\"algorithm\":\"SHA1\",\"digits\":6,\"period\":30,\"added\":\"2026-10-25T00:00:00Z\"}");
            try (TestServer server = new TestServer(request -> {
                if (!request.header("Authorization").equals("Bearer tok")) return TestServer.Reply.of(401, "application/json", "{\"error\":\"no\"}");
                if (request.method.equals("GET") && request.path.equals("/otp")) return TestServer.Reply.json("[" + String.join(",", stored) + "]");
                if (request.method.equals("POST") && request.path.equals("/otp")) {
                    if (request.body.contains("BADBAD")) return TestServer.Reply.of(400, "application/json", "{\"error\":\"the secret key isn't valid Base32\"}");
                    stored.add("{\"id\":\"b2\",\"issuer\":\"AWS\",\"account\":\"root\",\"secret\":\"GEZDGNBVGY3TQOJQ\",\"algorithm\":\"SHA1\",\"digits\":6,\"period\":30}");
                    return TestServer.Reply.of(201, "application/json", "[" + String.join(",", stored) + "]");
                }
                if (request.method.equals("PUT") && request.path.equals("/otp/a1")) {
                    stored.set(0, request.body);
                    return TestServer.Reply.json("[" + String.join(",", stored) + "]");
                }
                if (request.method.equals("DELETE") && request.path.equals("/otp/b2")) {
                    stored.remove(1);
                    return TestServer.Reply.json("[" + String.join(",", stored) + "]");
                }
                if (request.method.equals("DELETE")) return TestServer.Reply.of(404, "application/json", "{\"error\":\"no such account\"}");
                return TestServer.Reply.of(404, "text/plain", "404 page not found");
            })) {
                String base = server.uri("").toString().replaceAll("/$", "");
                Vault vault = new Vault(base, "tok");
                List<Account> listed = vault.list();
                check.same("listed", "a1 GitHub sam", listed.get(0).id + " " + listed.get(0).issuer + " " + listed.get(0).account);
                List<Account> added = vault.add(Collections.singletonList(Account.of("AWS", "root", "GEZDGNBVGY3TQOJQ")));
                check.same("added", 2, added.size());
                String sent = server.seen.get(server.seen.size() - 1).body;
                check.ok("sent as a list with the settings", sent.startsWith("[{") && sent.contains("\"secret\":\"GEZDGNBVGY3TQOJQ\"") && sent.contains("\"digits\":6"));
                check.fails("the command center's reason shown", "The secret key isn't valid Base32",
                    () -> vault.add(Collections.singletonList(Account.of("X", "", "BADBAD"))));
                Account changed = new Account("a1", "GitHub Enterprise", "sammy", "JBSWY3DPEHPK3PXP", "SHA256", 8, 60);
                List<Account> updated = vault.update(changed);
                check.same("updated", "a1 GitHub Enterprise sammy SHA256 8 60", updated.get(0).id + " " + updated.get(0).issuer + " "
                    + updated.get(0).account + " " + updated.get(0).algorithm + " " + updated.get(0).digits + " " + updated.get(0).period);
                check.same("removed", 1, vault.remove("b2").size());
                check.same("removing one already gone lists again", 1, vault.remove("zz").size());
                check.fails("wrong token", "HTTP 401", () -> new Vault(base, "nope").list());
                check.same("cache round trip", "GitHub", Vault.read(Vault.write(listed)).get(0).issuer);
            }
        });

        if (!check.report()) System.exit(1);
    }
}
