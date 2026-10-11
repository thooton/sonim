package com.canvas.xp3.data;

import java.util.List;

/** Operations that change the shape of a saved chat transcript. */
public final class ChatHistory {

    private ChatHistory() {}

    /**
     * Rewinds a transcript to one of its messages.
     *
     * An assistant message remains as the last turn. A user message becomes a
     * draft instead, so the saved conversation still ends before that request
     * and sending the draft cannot create two adjacent user turns. Any compact
     * boundary after the selected message is removed along with the rest of
     * the tail; rebuilding model history from the result therefore restores
     * the un-compacted messages that precede it.
     *
     * @return the text to put in the composer, or null for an assistant turn
     */
    public static String rewind(List<ChatTurn> turns, int position) {
        if (turns == null || position < 0 || position >= turns.size()) {
            throw new IllegalArgumentException("No message at that position");
        }
        ChatTurn selected = turns.get(position);
        if (!selected.isMessage()) {
            throw new IllegalArgumentException("A compact boundary is not a message");
        }

        int keep = selected.fromUser ? position : position + 1;
        turns.subList(keep, turns.size()).clear();
        return selected.fromUser ? selected.text : null;
    }
}
