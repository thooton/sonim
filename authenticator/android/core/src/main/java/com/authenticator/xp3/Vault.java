package com.authenticator.xp3;

import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.Login;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The accounts on the command center (GET /otp, POST /otp, PUT /otp/{id},
 * DELETE /otp/{id}, with the delta token). Every call answers with the whole list as it now
 * stands there, which the app keeps as its copy for when it is offline, in the
 * same JSON (`write`, `read`).
 */
public final class Vault {

    private final Http http = new Http();
    private final String center;
    private final String token;

    public Vault(Login login) {
        this(login.server(), login.token());
    }

    public Vault(String center, String token) {
        if (center == null || token == null) throw new IllegalArgumentException("There is no command center login");
        this.center = center;
        this.token = token;
    }

    public List<Account> list() throws IOException, InterruptedException {
        return send(request("/otp"));
    }

    /** Adds the accounts; one whose secret is already there is left as it was. */
    public List<Account> add(List<Account> accounts) throws IOException, InterruptedException {
        List<Object> body = new ArrayList<>();
        for (Account account : accounts) body.add(account.toJson());
        return send(request("/otp").post(Json.write(body)).header("Content-Type", "application/json"));
    }

    /** Changes the account with `changed`'s id to what `changed` says. */
    public List<Account> update(Account changed) throws IOException, InterruptedException {
        return send(request("/otp/" + changed.id).method("PUT").post(Json.write(changed.toJson()))
            .header("Content-Type", "application/json"));
    }

    public List<Account> remove(String id) throws IOException, InterruptedException {
        return send(request("/otp/" + id).method("DELETE"));
    }

    private Http.Request request(String path) {
        return Http.Request.to(URI.create(center + path))
            .header("Authorization", "Bearer " + token)
            .timeout(30000);
    }

    private List<Account> send(Http.Request request) throws IOException, InterruptedException {
        Http.Response response = http.send(request);
        if (response.status() == 400) {
            // The command center's own words about what was wrong with an
            // account, written for the person who typed it.
            String why = null;
            try {
                why = Json.text(Json.object(Json.parse(response.body())), "error");
            } catch (RuntimeException ignored) {
                // Fall through to the generic message.
            }
            if (why != null && why.length() > 0) {
                throw new IOException(Character.toUpperCase(why.charAt(0)) + why.substring(1));
            }
        }
        // Removed already, from another phone or an earlier try that timed out.
        if (response.status() == 404 && response.body().contains("no such account")) return list();
        if (response.status() == 404 || response.status() == 405) throw new IOException("The command center needs updating for two-factor accounts");
        Http.requireSuccess(response.status(), "Command center");
        try {
            return read(response.body());
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable list");
        }
    }

    /** The accounts in the command center's JSON. */
    public static List<Account> read(String json) {
        List<Account> accounts = new ArrayList<>();
        for (Object item : Json.array(Json.parse(json))) {
            Map<String, Object> map = Json.object(item);
            try {
                accounts.add(Account.fromJson(map));
            } catch (IllegalArgumentException e) {
                // One account this app can't show doesn't hide the rest.
            }
        }
        return accounts;
    }

    public static String write(List<Account> accounts) {
        List<Object> list = new ArrayList<>();
        for (Account account : accounts) list.add(account.toJson());
        return Json.write(list);
    }
}
