package com.canvas.xp3.data;

/** One turn in the chat transcript. */
public final class ChatTurn {

    public static final int MESSAGE = 0;
    public static final int COMPACTION = 1;
    public static final int COMPACTING = 2;

    public final boolean fromUser;
    public final String text;
    public final long when;
    public final int kind;

    public ChatTurn(boolean fromUser, String text, long when) {
        this(fromUser, text, when, MESSAGE);
    }

    private ChatTurn(boolean fromUser, String text, long when, int kind) {
        this.fromUser = fromUser;
        this.text = text;
        this.when = when;
        this.kind = kind;
    }

    /** A hidden model summary represented on screen by a compact divider. */
    public static ChatTurn compaction(String summary, long when) {
        return new ChatTurn(false, summary, when, COMPACTION);
    }

    /** The temporary divider shown while the summary request is running. */
    public static ChatTurn compacting(long when) {
        return new ChatTurn(false, "", when, COMPACTING);
    }

    public boolean isMessage() {
        return kind == MESSAGE;
    }

    public boolean isCompaction() {
        return kind == COMPACTION;
    }
}
