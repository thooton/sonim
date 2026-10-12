package com.delta.xp3.data;

/**
 * What a new session is told about Delta before the person's first message.
 *
 * The agent is never pointed at the Delta repository, which would only fill
 * its context. It is told to fetch the `delta` CLI, sign it in to the command
 * center with the address and token this phone holds, and read `delta help`,
 * which explains the rest: the phone, the repositories, and how a rebuilt APK
 * reaches the Files tab.
 *
 * It rides in the first message rather than anywhere else because that is the
 * one channel a cloud session is guaranteed to read. It is fenced in a tag so
 * the transcript can take it back out: on the phone the first message reads
 * as what was typed, and the token is not put on screen.
 */
public final class Setup {

    static final String OPEN = "<delta-setup>";
    static final String CLOSE = "</delta-setup>";

    public static final String INSTALL_URL =
        "https://github.com/thooton/delta/releases/download/latest/install.sh";

    private Setup() {}

    /**
     * The first message of a new session: the setup block, then what was
     * typed. `server` and `token` are null when the phone has no command
     * center, and the agent is told so rather than given a command to fail.
     */
    public static String wrap(String message, String server, String token) {
        StringBuilder out = new StringBuilder();
        out.append(OPEN).append('\n');
        out.append("You were started from the Delta app on the user's Sonim XP3 flip phone. ");
        out.append("In this app you are Delta: refer to yourself as Delta, and do not bring up the underlying ");
        out.append("model, its maker or the tool you run in unless the user asks. ");
        out.append("Before you start on their message, install the Delta CLI");
        if (server != null && token != null) out.append(" and connect it to their command center");
        out.append(":\n\n");
        out.append("    curl -fsSL ").append(INSTALL_URL).append(" | sh\n");
        if (server != null && token != null) {
            out.append("    delta set-auth ").append(server).append(' ').append(token).append('\n');
        }
        out.append("    delta help\n\n");
        out.append("Read all of `delta help`: it explains Delta and how to get files, such as rebuilt ");
        out.append("APKs, onto the user's phone.");
        if (server != null && token != null) {
            out.append(" The token is a password; never repeat it to the user.");
        } else {
            out.append(" The phone has no command center set up yet, so files cannot reach it until the user adds one.");
        }
        out.append(" This setup is routine and happens in every session, so do it quietly: do not announce, ");
        out.append("narrate or summarize it, and do not mention it to the user at all unless a step fails in a way ");
        out.append("that affects their request. Your first words to the user should be about their message.");
        out.append('\n').append(CLOSE);
        String typed = message == null ? "" : message.trim();
        if (typed.length() > 0) out.append("\n\n").append(typed);
        return out.toString();
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
