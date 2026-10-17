package com.mail.xp3.net;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Somewhere to list conversations from: one of Gmail's own folders, a label
 * of the user's, or a search. Each is a Gmail query, a label id, or both.
 */
public final class Folder {

    public static final Folder INBOX = new Folder("inbox", "Inbox", "in:inbox", null);
    public static final Folder STARRED = new Folder("starred", "Starred", "is:starred", null);
    public static final Folder SENT = new Folder("sent", "Sent", "in:sent", null);
    public static final Folder DRAFTS = new Folder("drafts", "Drafts", "in:drafts", null);
    public static final Folder ALL = new Folder("all", "All mail", "", null);
    public static final Folder SPAM = new Folder("spam", "Spam", "in:spam", null);
    public static final Folder TRASH = new Folder("trash", "Trash", "in:trash", null);

    /** Gmail's own, in the order Left and Right go through them. */
    public static final List<Folder> SYSTEM =
        Collections.unmodifiableList(Arrays.asList(INBOX, STARRED, SENT, DRAFTS, ALL, SPAM, TRASH));

    public final String kind;
    public final String name;
    public final String query;
    public final String label;

    private Folder(String kind, String name, String query, String label) {
        this.kind = kind;
        this.name = name;
        this.query = query;
        this.label = label;
    }

    public static Folder label(String id, String name) {
        return new Folder("label", name, "", id);
    }

    public static Folder search(String query) {
        return new Folder("search", "“" + query.trim() + "”", query.trim(), null);
    }

    public boolean is(Folder other) {
        return other != null && kind.equals(other.kind) && name.equals(other.name)
            && (label == null ? other.label == null : label.equals(other.label));
    }

    /**
     * The label whose count the folder pane shows beside this folder, as
     * Gmail does: the inbox's, spam's and each label's unread, and how many
     * drafts there are. Null for the rest.
     */
    public String countedLabel() {
        if (kind.equals("inbox")) return "INBOX";
        if (kind.equals("drafts")) return "DRAFT";
        if (kind.equals("spam")) return "SPAM";
        if (kind.equals("label")) return label;
        return null;
    }

    /** Drafts are counted whole; everything else by what is unread. */
    public boolean countsAll() {
        return kind.equals("drafts");
    }

    /** Spam and Trash are left out of every list but their own. */
    public boolean includesSpamAndTrash() {
        return this == SPAM || this == TRASH;
    }

    /**
     * Whether a conversation still belongs here after a change made on the
     * phone, so an archived one leaves the inbox at once rather than at the
     * next refresh. A search keeps what it found.
     */
    public boolean holds(Conversation conversation) {
        boolean binned = conversation.has("TRASH") || conversation.has("SPAM");
        if (kind.equals("inbox")) return conversation.has("INBOX") && !binned;
        if (kind.equals("starred")) return conversation.starred() && !binned;
        if (kind.equals("sent")) return conversation.has("SENT") && !conversation.has("TRASH");
        if (kind.equals("drafts")) return conversation.has("DRAFT");
        if (kind.equals("all")) return !binned;
        if (kind.equals("spam")) return conversation.has("SPAM");
        if (kind.equals("trash")) return conversation.has("TRASH");
        if (kind.equals("label")) return conversation.has(label) && !binned;
        return true;
    }
}
