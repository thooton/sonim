package com.mail.xp3.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What can be done to a whole conversation, as `delta gmail` and the Gmail
 * app do it: labels added and removed, or the trash. Nothing deletes mail for
 * good; the login's scope doesn't allow it.
 */
public enum Change {
    ARCHIVE("Archive", "Archived", null, "INBOX"),
    INBOX("Move to inbox", "Moved to the inbox", "INBOX", null),
    TRASH("Delete", "Moved to the trash", null, null),
    UNTRASH("Restore", "Restored", null, null),
    READ("Mark read", "Marked read", null, "UNREAD"),
    UNREAD("Mark unread", "Marked unread", "UNREAD", null),
    STAR("Star", "Starred", "STARRED", null),
    UNSTAR("Unstar", "Unstarred", null, "STARRED"),
    SPAM("Report spam", "Reported as spam", "SPAM", "INBOX"),
    NOT_SPAM("Not spam", "Moved to the inbox", "INBOX", "SPAM");

    public final String label;
    public final String done;
    public final List<String> add;
    public final List<String> remove;

    Change(String label, String done, String add, String remove) {
        this.label = label;
        this.done = done;
        this.add = add == null ? Collections.<String>emptyList() : Arrays.asList(add);
        this.remove = remove == null ? Collections.<String>emptyList() : Arrays.asList(remove);
    }

    /** What happens on the phone's copy, so it shows at once. */
    public void apply(Conversation conversation) {
        if (this == TRASH) conversation.relabel(Arrays.asList("TRASH"), Collections.<String>emptyList());
        else if (this == UNTRASH) conversation.relabel(Collections.<String>emptyList(), Arrays.asList("TRASH"));
        else conversation.relabel(add, remove);
    }

    /** The changes that make sense for a conversation, in the order the menu offers them. */
    public static List<Change> offered(Conversation conversation) {
        List<Change> out = new ArrayList<Change>();
        boolean trash = conversation.has("TRASH");
        boolean spam = conversation.has("SPAM");
        if (conversation.has("INBOX") && !trash && !spam) out.add(ARCHIVE);
        else if (!trash && !spam) out.add(INBOX);
        out.add(trash ? UNTRASH : TRASH);
        out.add(conversation.unread() ? READ : UNREAD);
        out.add(conversation.starred() ? UNSTAR : STAR);
        if (spam) out.add(NOT_SPAM);
        else if (!trash) out.add(SPAM);
        return out;
    }
}
