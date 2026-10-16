package com.mail.xp3.net;

import com.xp3.common.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A Gmail thread: its messages oldest first, as the list and the reader show it. */
public final class Conversation {

    public final String id;
    public final List<Message> messages;

    public Conversation(Map<String, Object> json) {
        id = Json.string(json, "id");
        List<Message> found = new ArrayList<Message>();
        for (Object item : Json.children(json, "messages")) {
            if (item instanceof Map) found.add(new Message(Json.object(item)));
        }
        messages = Collections.unmodifiableList(found);
    }

    /** The latest message that isn't a draft, or the latest draft when that is all there is. */
    public Message latest() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!messages.get(i).draft()) return messages.get(i);
        }
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public String subject() {
        String subject = messages.isEmpty() ? "" : messages.get(0).subject();
        return subject.length() == 0 ? "(no subject)" : subject;
    }

    public String snippet() {
        Message latest = latest();
        return latest == null ? "" : latest.snippet();
    }

    public long time() {
        Message latest = latest();
        return latest == null ? 0 : latest.time();
    }

    /** Whether any message has the label. */
    public boolean has(String label) {
        for (Message message : messages) if (message.has(label)) return true;
        return false;
    }

    public boolean unread() {
        return has("UNREAD");
    }

    public boolean starred() {
        return has("STARRED");
    }

    public boolean onlyDrafts() {
        if (messages.isEmpty()) return false;
        for (Message message : messages) if (!message.draft()) return false;
        return true;
    }

    /** The newest draft in it, or null. */
    public Message draft() {
        for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i).draft()) return messages.get(i);
        return null;
    }

    /** Every label on any of its messages. */
    public List<String> labels() {
        List<String> all = new ArrayList<String>();
        for (Message message : messages) {
            for (String label : message.labels) if (!all.contains(label)) all.add(label);
        }
        return all;
    }

    /**
     * Who is in it, as Gmail's list says: the senders' first names (or the
     * one sender in full), "me" for the user, "Draft" for an unsent one, and
     * how many messages when there are several. In Sent it is who it went to.
     */
    public String people(Me me, boolean sent) {
        if (sent) {
            Message latest = null;
            for (Message message : messages) if (message.has("SENT")) latest = message;
            List<Address> to = latest == null ? new ArrayList<Address>() : latest.to();
            if (!to.isEmpty()) return "To: " + (to.size() == 1 ? to.get(0).display() : briefs(to, me));
        }
        List<Address> senders = new ArrayList<Address>();
        boolean drafts = false;
        for (Message message : messages) {
            if (message.draft()) {
                drafts = true;
                continue;
            }
            List<Address> from = message.from();
            if (!from.isEmpty() && !contains(senders, from.get(0))) senders.add(from.get(0));
        }
        String names = senders.isEmpty() ? ""
            : senders.size() == 1 ? (me != null && me.is(senders.get(0).email) ? "me" : senders.get(0).display())
            : briefs(senders, me);
        if (drafts) names = names.length() == 0 ? "Draft" : names + ", Draft";
        int count = messages.size();
        return count > 1 ? names + " (" + count + ")" : names;
    }

    private static String briefs(List<Address> addresses, Me me) {
        StringBuilder out = new StringBuilder();
        for (Address address : addresses) {
            if (out.length() > 0) out.append(", ");
            out.append(me != null && me.is(address.email) ? "me" : address.brief());
        }
        return out.toString();
    }

    private static boolean contains(List<Address> list, Address address) {
        for (Address each : list) if (each.is(address.email)) return true;
        return false;
    }

    /** Changes the labels on every message here, as Gmail's thread modify does. */
    public void relabel(List<String> add, List<String> remove) {
        for (Message message : messages) {
            for (String label : remove) message.labels.remove(label);
            for (String label : add) if (!message.labels.contains(label)) message.labels.add(label);
        }
    }

    /** Every address seen on it, for suggesting whom to write to. */
    public List<Address> addresses() {
        List<Address> out = new ArrayList<Address>();
        for (Message message : messages) {
            out.addAll(message.from());
            out.addAll(message.to());
            out.addAll(message.cc());
        }
        return out;
    }
}
