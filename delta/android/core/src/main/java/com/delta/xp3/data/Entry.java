package com.delta.xp3.data;

/** One row of the transcript. */
public final class Entry {

    /** Something you sent, from this phone or anywhere else. */
    public static final int YOU = 0;
    /** Claude's own words, as Markdown. */
    public static final int CLAUDE = 1;
    /** A one-line record of a tool call: "Bash: ls -la". */
    public static final int TOOL = 2;
    /** A quiet line from the app itself: a failure, a stop, a question. */
    public static final int NOTE = 3;

    public final int kind;
    public final String text;
    /** The event uuid for a message you sent, so its echo is not shown twice. Otherwise null. */
    public final String id;
    /**
     * How many pictures a message of yours carried. The pictures themselves
     * are not kept here: the app saves the ones sent from this phone as files
     * named after `id`, and shows a placeholder for any it does not have.
     */
    public final int pictures;

    public Entry(int kind, String text, String id) {
        this(kind, text, id, 0);
    }

    public Entry(int kind, String text, String id, int pictures) {
        if (kind < YOU || kind > NOTE) throw new IllegalArgumentException("Unknown entry kind");
        if (text == null) throw new IllegalArgumentException("Entry has no text");
        if (pictures < 0) throw new IllegalArgumentException("Negative picture count");
        this.kind = kind;
        this.text = text;
        this.id = id;
        this.pictures = pictures;
    }

    public boolean isMessage() {
        return kind == YOU || kind == CLAUDE;
    }
}
