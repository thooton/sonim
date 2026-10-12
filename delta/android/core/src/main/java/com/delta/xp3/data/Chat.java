package com.delta.xp3.data;

/**
 * One cloud session as the Chats tab lists it: enough to name it and say
 * whether anything is happening in it, not its conversation.
 */
public final class Chat {

    /** The worker is running a turn. */
    public static final String WORKING = "running";
    /** The worker is waiting on a permission answer. */
    public static final String WAITING = "requires_action";
    /** Nothing running; the session can take a message. */
    public static final String IDLE = "idle";

    /** Always the session_… form, which is also what the web app's links use. */
    public final String id;
    public final String title;
    public final String status;
    /** When anything last happened in it, in epoch milliseconds; 0 if unknown. */
    public final long updated;

    public Chat(String id, String title, String status, long updated) {
        if (!validId(id)) throw new IllegalArgumentException("Not a session id");
        this.id = id;
        this.title = title == null || title.trim().length() == 0 ? "Untitled" : title.trim();
        this.status = status == null ? IDLE : status;
        this.updated = updated;
    }

    /**
     * A session id the app will use as a file name and put in a URL path, so
     * nothing beyond the characters the sessions API actually uses gets in.
     */
    public static boolean validId(String id) {
        if (id == null || !id.startsWith("session_")) return false;
        int length = id.length() - "session_".length();
        if (length < 1 || length > 120) return false;
        for (int i = "session_".length(); i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** The session_… form of an id, which the code-sessions API may give as cse_…. */
    public static String sessionId(String id) {
        if (id == null) return null;
        return id.startsWith("cse_") ? "session_" + id.substring("cse_".length()) : id;
    }

    public Chat titled(String next) {
        return new Chat(id, next, status, updated);
    }
}
