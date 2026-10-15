package com.delta.xp3.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a new session is given before the person's first message.
 *
 * The cloud environment does the setup: its setup script (`SCRIPT`)
 * installs the `delta` CLI, and its variables (`variables`) sign it in to
 * the command center with the address and token this phone holds. The first
 * message then only tells the agent who it is and to read `delta help`,
 * which explains the rest: the phone, the repositories, and how a rebuilt
 * APK reaches the Files tab. The agent is never pointed at the Delta
 * repository, which would only fill its context.
 *
 * The command center's backup sessions are set up the same way, and their
 * first message opens with the same block, word for word (`agentSetup` in
 * thooton/delta's cmd/delta-cc/agents.go). Keep the two in step.
 *
 * The block is fenced in a tag so the transcript can take it back out: on
 * the phone the first message reads as what was typed.
 */
public final class Setup {

    static final String OPEN = "<delta-setup>";
    static final String CLOSE = "</delta-setup>";

    public static final String INSTALL_URL =
        "https://github.com/thooton/delta/releases/download/latest/install.sh";

    /**
     * The cloud environment's setup script. It runs as root before the agent
     * starts, and its result is kept for later sessions, so a failed install
     * must fail rather than be kept: hence `pipefail`.
     */
    public static final String SCRIPT =
        "#!/bin/bash\n"
        + "set -eo pipefail\n"
        + "curl -fsSL " + INSTALL_URL + " | sh\n";

    static final String BLOCK = OPEN + "\n"
        + "You were started from the Delta app on the user's Sonim XP3 flip phone. In this app you are Delta: "
        + "refer to yourself as Delta, and do not bring up the underlying model, its maker or the tool you run in "
        + "unless the user asks.\n\n"
        + "Before you start on their message, run `delta help` and read all of its output: it explains Delta "
        + "and how to get files, such as rebuilt APKs, onto the user's phone. Do this quietly: do not announce, "
        + "narrate or summarize it, and do not mention it unless it fails in a way that affects their request. "
        + "Your first words to the user should be about their message.\n"
        + CLOSE;

    private Setup() {}

    /** The first message of a new session: the setup block, then what was typed. */
    public static String wrap(String message) {
        String typed = message == null ? "" : message.trim();
        return typed.length() > 0 ? BLOCK + "\n\n" + typed : BLOCK;
    }

    /**
     * The cloud environment's variables: what the `delta` CLI signs in with.
     * Empty when the phone has no command center.
     */
    public static Map<String, Object> variables(String server, String token) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (server != null && token != null) {
            out.put("DELTA_SERVER", server);
            out.put("DELTA_TOKEN", token);
        }
        return out;
    }

    /**
     * The name of the cloud environment set up for this command center. An
     * environment can't be changed from here, so a new one is made whenever
     * the script, the address or the token changes, and the name tells them
     * apart: a short hash of all three, which gives none of them away.
     */
    public static String environmentName(String server, String token) {
        String all = SCRIPT + "\n" + server + "\n" + token;
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(all.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("Delta ");
            for (int i = 0; i < 4; i++) out.append(String.format("%02x", sum[i] & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A message with any leading setup block taken back out. */
    public static String strip(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (!trimmed.startsWith(OPEN)) return text;
        int end = trimmed.indexOf(CLOSE);
        if (end < 0) return text;
        return trimmed.substring(end + CLOSE.length()).trim();
    }
}
