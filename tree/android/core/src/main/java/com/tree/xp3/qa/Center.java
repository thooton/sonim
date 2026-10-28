package com.tree.xp3.qa;

import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.Login;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The nightly QA on the command center (thooton/delta's /qa routes), signed
 * in with the delta token as Tablet and Authenticator are. Every failure is
 * an IOException whose message can go straight on screen: the command
 * center's own {"error": ...} when it sent one.
 */
public final class Center {

    /** A phone screenshot is tens of kilobytes; a tablet's, a few hundred. */
    static final long MAX_SHOT = 8000000;
    private static final int QUICK = 30000;

    private final String server;
    private final String token;
    private final Http http = new Http();

    public Center(Login login) {
        this(login.server(), login.token());
        if (!login.ready()) throw new IllegalArgumentException("Not signed in");
    }

    /** Any address, for the tests' plain-http server; the app goes through Login, which takes https only. */
    public Center(String server, String token) {
        this.server = server;
        this.token = token;
    }

    /** GET /qa; `fresh` has the command center list the apps from GitHub again. */
    public Qa qa(boolean fresh) throws IOException, InterruptedException {
        return Qa.parse(qaJson(fresh));
    }

    /** The same, as the JSON it came in, for keeping. */
    public String qaJson(boolean fresh) throws IOException, InterruptedException {
        String body = send(request(fresh ? "/qa?fresh=1" : "/qa"), "read the QA");
        try {
            Qa.parse(body);
        } catch (RuntimeException e) {
            throw new IOException("The command center sent something unreadable");
        }
        return body;
    }

    /** Turns the nightly run on or off. */
    public Qa.Settings nightly(boolean enabled) throws IOException, InterruptedException {
        return settings(Json.map("enabled", Boolean.valueOf(enabled)));
    }

    /** Moves the nightly run to `hour` (0 to 23, in the command center's time zone). */
    public Qa.Settings hour(int hour) throws IOException, InterruptedException {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("An hour is 0 to 23");
        return settings(Json.map("hour", Integer.valueOf(hour)));
    }

    private Qa.Settings settings(Map<String, Object> body) throws IOException, InterruptedException {
        // The command center sits behind a proxy and can't tell its own
        // address, so every change to the schedule says it.
        body.put("server", server);
        String sent = send(request("/qa/settings").method("PUT").post(Json.write(body)), "change the schedule");
        return read(sent, s -> Qa.Settings.from(Json.object(Json.parse(s))));
    }

    /** Lets the app grow features, or stops it; answers what the command center now has. */
    public boolean grow(Qa.App app, boolean grow) throws IOException, InterruptedException {
        String path = "/qa/apps/" + segment(app.repo) + "/" + segment(app.dir);
        String sent = send(request(path).method("PUT").post(Json.write(Json.map("grow", Boolean.valueOf(grow)))), "change it");
        return read(sent, s -> {
            Map<String, Object> root = Json.object(Json.parse(s));
            return root.get("grow") instanceof Boolean ? Json.flag(root, "grow") : grow;
        });
    }

    /**
     * Starts a run of every app (none named) or of the apps named
     * ("sonim/list"), saying the command center's address as this app uses it.
     */
    public Qa.Run start(List<String> apps) throws IOException, InterruptedException {
        Map<String, Object> body = Json.map();
        if (apps != null && !apps.isEmpty()) body.put("apps", new ArrayList<Object>(apps));
        // Where the run's jobs reach the command center: it can't tell its
        // own address from behind its proxy.
        body.put("server", server);
        String sent = send(request("/qa/runs").post(Json.write(body)), "start a run");
        return read(sent, Qa.Run::parse);
    }

    /** One run whole, with its apps and jobs. */
    public Qa.Run run(String id) throws IOException, InterruptedException {
        return read(send(request("/qa/runs/" + segment(id)), "read the run"), Qa.Run::parse);
    }

    public Qa.Run cancel(String id) throws IOException, InterruptedException {
        return read(send(request("/qa/runs/" + segment(id)).method("DELETE"), "cancel the run"), Qa.Run::parse);
    }

    /** The app's findings, newest first. */
    public List<Finding> findings(String app, int limit) throws IOException, InterruptedException {
        String query = "/qa/findings?app=" + URLEncoder.encode(app, "UTF-8") + "&limit=" + limit;
        return read(send(request(query), "read the findings"), Finding::list);
    }

    /** Gives a grown feature a verdict: "up", "down", or "" to clear it. */
    public Finding verdict(String id, String verdict) throws IOException, InterruptedException {
        if (!("up".equals(verdict) || "down".equals(verdict) || "".equals(verdict))) {
            throw new IllegalArgumentException("A verdict is up, down or none");
        }
        String path = "/qa/findings/" + segment(id) + "/verdict";
        return read(send(request(path).method("PUT").post(Json.write(Json.map("verdict", verdict))), "give a verdict"),
            Finding::parse);
    }

    /** Saves screenshot `name` to `dest`, through a part file so a broken download leaves nothing. */
    public void shot(String name, File dest) throws IOException, InterruptedException {
        if (!Words.safeName(name)) throw new IOException("Not a screenshot");
        Http.Stream stream = http.stream(request("/qa/shots/" + segment(name)).header("Accept", "image/png"));
        try {
            int status = stream.status();
            if (status < 200 || status >= 300) fail(status, small(stream.stream()), "show the screenshot");
            File part = new File(dest.getPath() + ".part");
            boolean saved = false;
            OutputStream out = new FileOutputStream(part);
            try {
                InputStream in = stream.stream();
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                for (int count; (count = in.read(buffer)) != -1; ) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    total += count;
                    if (total > MAX_SHOT) throw new IOException("Screenshot too big");
                    out.write(buffer, 0, count);
                }
                out.close();
                saved = part.renameTo(dest);
                if (!saved) throw new IOException("Couldn't keep the screenshot");
            } finally {
                out.close();
                if (!saved) part.delete();
            }
        } finally {
            stream.close();
        }
    }

    private interface Reader<T> {
        T read(String body);
    }

    private static <T> T read(String body, Reader<T> reader) throws IOException {
        try {
            return reader.read(body);
        } catch (RuntimeException e) {
            throw new IOException("The command center sent something unreadable");
        }
    }

    private Http.Request request(String path) {
        return Http.Request.to(URI.create(server + path))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(QUICK);
    }

    private String send(Http.Request request, String what) throws IOException, InterruptedException {
        Http.Response response = http.send(request);
        if (response.status() < 200 || response.status() >= 300) fail(response.status(), response.body(), what);
        return response.body();
    }

    /**
     * The command center's own words for what went wrong ({"error": ...}),
     * such as "a run is already going"; they never carry a credential.
     */
    static void fail(int status, String body, String what) throws IOException {
        if (status == 401) throw new IOException("Token refused");
        String said = null;
        try {
            said = Json.text(Json.object(Json.parse(body)), "error");
        } catch (RuntimeException ignored) {
            // Not the command center's JSON: a proxy's page, or nothing.
        }
        if (said != null && said.trim().length() > 0) {
            String text = said.trim();
            text = text.substring(0, 1).toUpperCase(Locale.US) + text.substring(1);
            throw new IOException(text.endsWith(".") ? text : text + ".");
        }
        if (status == 404) throw new IOException("Couldn't " + what + ": the command center has no QA, or it is gone.");
        Http.requireSuccess(status, "The command center");
    }

    private static String small(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; out.size() < 65536 && (count = in.read(buffer)) != -1; ) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }

    /** One path segment, percent-encoded. */
    static String segment(String value) {
        if (value.equals(".") || value.equals("..")) return value.replace(".", "%2E");
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.') {
                out.append((char) c);
            } else {
                out.append(String.format(Locale.US, "%%%02X", c));
            }
        }
        return out.toString();
    }
}
