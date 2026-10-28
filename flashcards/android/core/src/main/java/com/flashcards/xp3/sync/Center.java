package com.flashcards.xp3.sync;

import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.Login;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * The command center's flashcards (POST /flashcards/sync, with the delta
 * token or a paired device's): one round trip sends what changed here and
 * brings back what changed elsewhere. See
 * {@link com.flashcards.xp3.data.Collection}.
 *
 * Pairing gets a token without typing one: {@link #startPairing} asks for a
 * code, the user types it on the website's Tablet page, and {@link #poll}
 * then answers a token that reaches only the flashcards.
 */
public final class Center {

    /** The user's command center; its address is no secret, the token is. */
    public static final String DEFAULT_SERVER = "https://edit.mimilearn.org/api/delta";

    /** A code waiting to be typed on the website. */
    public static final class Pairing {
        public final String code;
        final String secret;

        Pairing(String code, String secret) {
            this.code = code;
            this.secret = secret;
        }
    }

    private static final Http HTTP = new Http();

    /**
     * What a failed request says on the phone's narrow screen: "No
     * connection" for the network's own failures (a POST body's send passes
     * Java's text, a hostname and resolver jargon, straight through).
     */
    public static String reason(IOException e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketException
            || e instanceof java.net.SocketTimeoutException || e.getMessage() == null) {
            return "No connection";
        }
        return e.getMessage();
    }

    public static Pairing startPairing(String server) throws IOException, InterruptedException {
        Http.Response response = HTTP.send(Http.Request.to(URI.create(server + "/flashcards/pair"))
            .post("{\"name\":\"Phone\"}").header("Content-Type", "application/json").timeout(30000));
        if (response.status() == 404 || response.status() == 405) {
            throw new IOException("The command center needs updating for flashcards");
        }
        Http.requireSuccess(response.status(), "Command center");
        try {
            Map<String, Object> root = Json.object(Json.parse(response.body()));
            return new Pairing(Json.string(root, "code"), Json.string(root, "secret"));
        } catch (IllegalArgumentException e) {
            throw new IOException("The command center's answer was unreadable");
        }
    }

    /** The token once the code was typed, null while it waits; throws once it expired. */
    public static String poll(String server, Pairing pairing) throws IOException, InterruptedException {
        Http.Response response = HTTP.send(Http.Request.to(URI.create(server + "/flashcards/pair/" + pairing.secret))
            .timeout(30000));
        if (response.status() == 202) return null;
        if (response.status() == 404) throw new IOException("The code expired");
        Http.requireSuccess(response.status(), "Command center");
        try {
            return Json.string(Json.object(Json.parse(response.body())), "token");
        } catch (IllegalArgumentException e) {
            throw new IOException("The command center's answer was unreadable");
        }
    }

    private final Http http = new Http();
    private final Login login;

    public Center(Login login) {
        if (!login.ready()) throw new IllegalArgumentException("There is no command center login");
        this.login = login;
    }

    /** Sends a sync's body and answers the command center's reply. */
    public String sync(String body) throws IOException, InterruptedException {
        Http.Response response = http.send(
            Http.Request.to(URI.create(login.server() + "/flashcards/sync"))
                .header("Authorization", "Bearer " + login.token())
                .header("Content-Type", "application/json")
                .post(body)
                .timeout(120000));
        if (response.status() == 401) throw new Unpaired();
        if (response.status() == 404 || response.status() == 405) {
            throw new IOException("The command center needs updating for flashcards");
        }
        if (response.status() == 400) {
            String why = null;
            try {
                why = Json.text(Json.object(Json.parse(response.body())), "error");
            } catch (RuntimeException ignored) {
                // Fall through to the generic message.
            }
            if (why != null && why.length() > 0) throw new IOException(why);
        }
        Http.requireSuccess(response.status(), "Command center");
        return response.body();
    }

    /** The token was refused: unpaired on the website, or a delta token replaced. */
    public static final class Unpaired extends IOException {
        public Unpaired() {
            super("The command center refused the token");
        }
    }
}
