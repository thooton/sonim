package com.delta.xp3.data;

import com.delta.xp3.net.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One session's conversation, as built from its event stream.
 *
 * The stream carries everything the cloud worker and every attached client do:
 * sandbox start-up logs, MCP handshakes, context-usage polls, rate-limit
 * notices. Almost none of it is conversation. What is kept is what a person
 * would want on a 240px screen: what was said to the agent, what it said,
 * a line per tool call, and the odd note when a turn ends badly or the agent is
 * waiting for permission.
 *
 * Events are applied in sequence order and an event at or below the last
 * sequence number seen is ignored, so a reconnect that replays a little too
 * much changes nothing. Main thread only.
 */
public final class Transcript {

    public static final int MAX_ENTRIES = 300;
    private static final int MAX_SUMMARY = 160;

    /** A tool call the worker will not run until someone allows it. */
    public static final class Permission {
        public final String requestId;
        public final String tool;
        public final String summary;
        public final Map<String, Object> input;

        Permission(String requestId, String tool, String summary, Map<String, Object> input) {
            this.requestId = requestId;
            this.tool = tool;
            this.summary = summary;
            this.input = input;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private long sequence;
    private boolean working;
    /** The latest sandbox start-up line, shown while a turn is getting going. */
    private String status;
    private Permission pending;
    /**
     * Until when the session's account is out of usage, in epoch
     * milliseconds, as the latest rate-limit event said; 0 when it has room.
     */
    private long limitedUntil;

    public Transcript() {}

    public Transcript(List<Entry> saved, long sequence, boolean working) {
        if (saved != null) entries.addAll(saved);
        trim();
        this.sequence = Math.max(0, sequence);
        this.working = working;
    }

    public List<Entry> entries() {
        return new ArrayList<Entry>(entries);
    }

    public int size() {
        return entries.size();
    }

    /** The highest event sequence number applied: where a reconnect resumes. */
    public long sequence() {
        return sequence;
    }

    /** Whether a turn is under way: from a message until its result. */
    public boolean working() {
        return working;
    }

    /** What to say while working. */
    public String status() {
        return status == null ? "Working…" : status;
    }

    public Permission pending() {
        return pending;
    }

    /**
     * When the session's account reaches its usage limit lifts, in epoch
     * milliseconds, if the stream has said so since this was built; 0
     * otherwise. A replayed rejection from long ago is in the past by then.
     */
    public long limitedUntil() {
        return limitedUntil;
    }

    // ------------------------------------------------------------------
    // Local changes
    // ------------------------------------------------------------------

    /** A message sent from here, shown before the cloud has echoed it back. */
    public void sent(String uuid, String text) {
        sent(uuid, text, 0);
    }

    public void sent(String uuid, String text, int pictures) {
        add(new Entry(Entry.YOU, text, uuid, pictures));
        working = true;
        status = null;
    }

    public void note(String text) {
        add(new Entry(Entry.NOTE, text, null));
    }

    /**
     * A message written here that waits on the phone for a usage limit to
     * lift: shown, but no turn starts until it is actually sent.
     */
    public void queued(String uuid, String text, int pictures) {
        add(new Entry(Entry.YOU, text, uuid, pictures));
    }

    /** A message held back earlier has gone out, so a turn is coming. */
    public void resumed() {
        working = true;
        status = null;
    }

    /**
     * The start of a session that carries an earlier conversation over: its
     * note, under the uuid of the message that carries it, so the echo of
     * that message adds nothing.
     */
    public void carried(String uuid) {
        add(new Entry(Entry.NOTE, Carry.NOTE, uuid));
        working = true;
        status = null;
    }

    /** A send failed, so no turn is coming. */
    public void idle() {
        working = false;
        status = null;
    }

    /** The pending permission request was answered from here. */
    public void answered() {
        pending = null;
    }

    // ------------------------------------------------------------------
    // The stream
    // ------------------------------------------------------------------

    /**
     * Applies one stream frame.
     *
     * @return whether anything on screen changed
     */
    public boolean apply(String event, String id, String data) {
        if ("catch_up_truncated".equals(event)) {
            note("Some earlier activity was too long to replay.");
            return true;
        }
        if (!"client_event".equals(event)) return false;
        Map<String, Object> frame;
        try {
            frame = Json.object(Json.parse(data));
        } catch (RuntimeException e) {
            advance(sequenceOf(id, null));
            return false;
        }
        long at = sequenceOf(id, frame);
        if (at > 0 && at <= sequence) return false;
        advance(at);
        try {
            return payload(Json.child(frame, "payload"), Json.text(frame, "source"));
        } catch (RuntimeException e) {
            // One malformed event is skipped rather than costing the session.
            return false;
        }
    }

    private void advance(long at) {
        if (at > sequence) sequence = at;
    }

    /** The frame's SSE id, which is its sequence number; the payload's own copy if that is missing. */
    private static long sequenceOf(String id, Map<String, Object> frame) {
        String value = id;
        if ((value == null || value.length() == 0) && frame != null) value = Json.text(frame, "sequence_num");
        if (value == null) return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean payload(Map<String, Object> payload, String source) {
        String type = Json.text(payload, "type");
        if ("user".equals(type)) return user(payload, source);
        if ("assistant".equals(type)) return assistant(payload);
        if ("result".equals(type)) return result(payload);
        if ("control_request".equals(type)) return controlRequest(payload, source);
        if ("control_response".equals(type)) return settled(Json.text(Json.child(payload, "response"), "request_id"));
        if ("control_cancel_request".equals(type)) return settled(Json.text(payload, "request_id"));
        if ("rate_limit_event".equals(type)) return rateLimit(Json.child(payload, "rate_limit_info"));
        if ("env_manager_log".equals(type)) {
            String line = Json.text(Json.child(payload, "data"), "content");
            if (line == null || line.trim().length() == 0) return false;
            status = oneLine(rebrand(line));
            return working;
        }
        return false;
    }

    /**
     * A message to the agent. Only the client's own are shown: user frames from
     * the worker are tool results and interrupt markers, which are its
     * bookkeeping rather than anything anybody said.
     */
    private boolean user(Map<String, Object> payload, String source) {
        if (!"client".equals(source) || Json.flag(payload, "isMeta")) return false;
        Object content = Json.child(payload, "message").get("content");
        // The first message of a session started here carries the Delta setup
        // ahead of what was typed; only what was typed is conversation. A
        // conversation carried over from another account is one note, and
        // files sent with a message are named in a block shown as one line.
        String typed = Setup.strip(text(content));
        boolean carried = Carry.present(typed);
        String text = Attached.show(Carry.strip(typed));
        // A carried conversation's pictures ride in the message that carries
        // it; they were shown with the messages they came with.
        int pictures = carried ? 0 : pictures(content);
        if (text.trim().length() == 0 && pictures == 0 && !carried) return false;
        String uuid = Json.text(payload, "uuid");
        if (uuid != null) {
            for (Entry entry : entries) {
                if (uuid.equals(entry.id)) return false;
            }
        }
        if (carried) add(new Entry(Entry.NOTE, Carry.NOTE, uuid));
        if (text.trim().length() > 0 || pictures > 0) add(new Entry(Entry.YOU, text.trim(), uuid, pictures));
        working = true;
        status = null;
        return true;
    }

    private boolean assistant(Map<String, Object> payload) {
        // A subagent's inner turns would bury the main thread on this screen.
        if (payload.get("parent_tool_use_id") != null) return false;
        boolean changed = !working || status != null;
        working = true;
        status = null;
        for (Object block : Json.children(Json.child(payload, "message"), "content")) {
            if (!(block instanceof Map)) continue;
            Map<String, Object> part = Json.object(block);
            String type = Json.text(part, "type");
            if ("text".equals(type)) {
                String text = Json.text(part, "text");
                if (text == null || text.trim().length() == 0) continue;
                add(new Entry(Entry.AGENT, text.trim(), null));
                changed = true;
            } else if ("tool_use".equals(type)) {
                add(new Entry(Entry.TOOL, tool(Json.text(part, "name"), Json.child(part, "input")), null));
                changed = true;
            }
        }
        return changed;
    }

    private boolean result(Map<String, Object> payload) {
        working = false;
        status = null;
        pending = null;
        String subtype = Json.text(payload, "subtype");
        if ("error_during_execution".equals(subtype)) {
            note("Stopped.");
        } else if ("error_max_turns".equals(subtype)) {
            note("Stopped at the turn limit.");
        } else if (Json.flag(payload, "is_error") || (subtype != null && !"success".equals(subtype))) {
            String said = Json.text(payload, "result");
            // A usage limit arrives as the agent's own words and then again as
            // the result; once on a 240px screen is enough.
            if (said != null && repeatsLastReply(said)) return true;
            note(said != null && said.trim().length() > 0 ? oneLine(rebrand(said)) : "The turn ended with an error.");
        }
        return true;
    }

    private boolean repeatsLastReply(String said) {
        if (entries.isEmpty()) return false;
        Entry last = entries.get(entries.size() - 1);
        return last.kind == Entry.AGENT && last.text.equals(said.trim());
    }

    /** The worker asking permission for a tool call. Requests to the worker are not ours to show. */
    private boolean controlRequest(Map<String, Object> payload, String source) {
        if (!"worker".equals(source)) return false;
        Map<String, Object> request = Json.child(payload, "request");
        if (!"can_use_tool".equals(Json.text(request, "subtype"))) return false;
        String requestId = Json.text(payload, "request_id");
        String name = Json.text(request, "tool_name");
        if (requestId == null || name == null) return false;
        Map<String, Object> input = Json.child(request, "input");
        String summary = summary(name, input);
        pending = new Permission(requestId, name, summary, new LinkedHashMap<String, Object>(input));
        note("Delta asks to use " + tool(name, input) + ".");
        return true;
    }

    /**
     * The worker passing on what the account's usage limits say. Only a
     * rejection with a reset time counts: the agent's own reply already says
     * the limit was hit, so nothing new goes on screen.
     */
    private boolean rateLimit(Map<String, Object> info) {
        String status = Json.text(info, "status");
        if ("rejected".equals(status)) {
            Double resets = Json.number(info, "resetsAt");
            if (resets != null && resets.doubleValue() > 0) limitedUntil = (long) (resets.doubleValue() * 1000);
        } else if (status != null) {
            limitedUntil = 0;
        }
        return false;
    }

    /** A request answered or withdrawn elsewhere: the web app, the CLI, or a timeout. */
    private boolean settled(String requestId) {
        if (pending == null || requestId == null || !requestId.equals(pending.requestId)) return false;
        pending = null;
        return true;
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** A message's content: a plain string, or the text blocks of a block list. */
    private static String text(Object content) {
        if (content instanceof String) return (String) content;
        if (!(content instanceof List)) return "";
        StringBuilder out = new StringBuilder();
        for (Object block : Json.array(content)) {
            if (!(block instanceof Map)) continue;
            Map<String, Object> part = Json.object(block);
            if (!"text".equals(Json.text(part, "type"))) continue;
            String text = Json.text(part, "text");
            if (text == null) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(text);
        }
        return out.toString();
    }

    private static int pictures(Object content) {
        if (!(content instanceof List)) return 0;
        int count = 0;
        for (Object block : Json.array(content)) {
            if (block instanceof Map && "image".equals(Json.text(Json.object(block), "type"))) count++;
        }
        return count;
    }

    static String tool(String name, Map<String, Object> input) {
        String label = name == null ? "a tool" : name.startsWith("mcp__") ? name.substring(5) : name;
        String summary = summary(name, input);
        return summary.length() == 0 ? label : label + ": " + summary;
    }

    /** The one input field that says what a call is doing. */
    static String summary(String name, Map<String, Object> input) {
        String[] keys = {"command", "file_path", "notebook_path", "pattern", "url", "query", "description", "prompt"};
        if ("TodoWrite".equals(name)) return "update the to-do list";
        for (String key : keys) {
            String value = Json.text(input, key);
            if (value != null && value.trim().length() > 0) return oneLine(value);
        }
        for (Object value : input.values()) {
            if (value instanceof String && ((String) value).trim().length() > 0) return oneLine((String) value);
        }
        return "";
    }

    /**
     * The app's own status and note lines are white-labelled: the worker's
     * start-up log and error results name the provider's product, and on
     * this phone the agent is Delta. The agent's own words are left alone.
     */
    static String rebrand(String text) {
        return text.replaceAll("\\bClaude(?: Code| AI)?\\b", "Delta");
    }

    private static String oneLine(String text) {
        String flat = text.trim().replaceAll("\\s+", " ");
        return flat.length() <= MAX_SUMMARY ? flat : flat.substring(0, MAX_SUMMARY - 1) + "…";
    }

    private void add(Entry entry) {
        entries.add(entry);
        trim();
    }

    private void trim() {
        if (entries.size() > MAX_ENTRIES) entries.subList(0, entries.size() - MAX_ENTRIES).clear();
    }
}
