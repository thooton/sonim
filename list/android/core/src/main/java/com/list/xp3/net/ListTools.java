package com.list.xp3.net;

import com.list.xp3.data.Checklist;
import com.list.xp3.data.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The complete set of actions available to the assistant: read the list, and
 * replace the list.
 *
 * `set_list` replaces every item at once, so a write made against an old copy
 * would silently undo whatever the person did in the meantime. The rule that
 * prevents that is enforced here rather than asked for in the prompt:
 *
 * - `set_list` is refused until the model has seen a `get_list` result in the
 *   current conversation;
 * - it is refused again whenever the list has changed since that read, which
 *   in practice means the person ticked, edited or added something while the
 *   model was thinking.
 *
 * Both refusals tell the model to call `get_list`, which is all it needs to
 * recover. A successful `set_list` counts as a read of the list it wrote: the
 * model knows exactly what is there, because it just put it there.
 */
public final class ListTools {

    public static final String GET_LIST = "get_list";
    public static final String SET_LIST = "set_list";

    static final String NEVER_READ =
        "Error: you must call get_list before calling set_list. Call get_list now " +
        "to read the current list, then call set_list with the complete updated list.";
    static final String STALE =
        "Error: the list has changed since you last called get_list (the user edited " +
        "it). Your changes were not applied. Call get_list again to read the current " +
        "version, then call set_list with the complete updated list.";

    public static final List<Object> DEFINITIONS = definitions();

    private final Checklist list;
    private final Runnable changed;

    /** The version the model last saw, or -1 when it has not seen the list. */
    private long seen = -1;
    /** A read made this round, which the model has not been shown yet. */
    private long pending = -1;

    /** `changed` runs on the calling thread after the assistant rewrites the list. */
    public ListTools(Checklist list, Runnable changed) {
        this.list = list;
        this.changed = changed;
    }

    private static List<Object> definitions() {
        Map<String, Object> item = Json.map(
            "type", "object",
            "properties", Json.map(
                "text", Json.map(
                    "type", "string",
                    "description", "The task, as one short line."
                ),
                "done", Json.map(
                    "type", "boolean",
                    "description", "Whether the task is completed (checked off)."
                )
            ),
            "required", Json.list("text", "done"),
            "additionalProperties", Boolean.FALSE
        );
        List<Object> all = new ArrayList<Object>();
        all.add(Json.map(
            "name", GET_LIST,
            "description",
                "Read the user's current todo list, in order from top to bottom. " +
                "Call this before set_list, and again whenever set_list says the " +
                "list has changed.",
            "parameters", Json.map(
                "type", "object",
                "properties", Json.map(),
                "additionalProperties", Boolean.FALSE
            )
        ));
        all.add(Json.map(
            "name", SET_LIST,
            "description",
                "Replace the user's entire todo list with these items, in this order " +
                "from top to bottom. Include every item that should remain, with its " +
                "done state; anything left out is deleted. Only works after get_list, " +
                "and only if the user has not changed the list since.",
            "parameters", Json.map(
                "type", "object",
                "properties", Json.map(
                    "items", Json.map(
                        "type", "array",
                        "description", "The complete new list.",
                        "items", item
                    )
                ),
                "required", Json.list("items"),
                "additionalProperties", Boolean.FALSE
            )
        ));
        return all;
    }

    /**
     * Forgets that the model has read the list.
     *
     * Called whenever the model's history is replaced: a cleared, rewound or
     * restored conversation no longer contains the get_list result that made a
     * write safe, so the next write has to read again first.
     */
    public synchronized void forget() {
        seen = -1;
        pending = -1;
    }

    /**
     * Marks the end of a round of tool calls.
     *
     * A get_list result only reaches the model with the next request, so a
     * set_list issued in the same breath as the read was written without it
     * and must not count as informed.
     */
    public synchronized void endRound() {
        if (pending >= 0) seen = pending;
        pending = -1;
    }

    /** Runs one tool. Never throws: every problem becomes text for the model. */
    public String call(String name, String arguments) {
        Map<String, Object> args;
        try {
            if (arguments == null || arguments.length() > 200000) {
                return "Error: the tool arguments are missing or too large.";
            }
            args = Json.object(Json.parse(arguments));
        } catch (RuntimeException e) {
            return "Error: the tool arguments were not a JSON object.";
        }
        if (GET_LIST.equals(name)) return getList();
        if (SET_LIST.equals(name)) return setList(args);
        return "Error: there is no tool called " + name + ". Use get_list or set_list.";
    }

    private synchronized String getList() {
        Checklist.Snapshot snapshot = list.snapshot();
        pending = snapshot.version;
        List<Object> items = new ArrayList<Object>();
        for (Item item : snapshot.items) {
            items.add(Json.map("text", item.text, "done", Boolean.valueOf(item.done)));
        }
        return Json.write(Json.map("items", items));
    }

    private String setList(Map<String, Object> args) {
        List<Item> next;
        try {
            next = items(args);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage() + ". Nothing was changed.";
        }
        synchronized (this) {
            if (seen < 0) return NEVER_READ;
            long written = list.replaceIf(seen, next);
            if (written < 0) return STALE;
            seen = written;
        }
        if (changed != null) changed.run();
        int open = 0;
        for (Item item : next) if (!item.done) open++;
        return "OK. The list now has " + next.size() + (next.size() == 1 ? " item" : " items")
            + " (" + open + " not done).";
    }

    private static List<Item> items(Map<String, Object> args) {
        Object raw = args.get("items");
        if (!(raw instanceof List)) throw new IllegalArgumentException("items must be an array");
        List<Object> entries = Json.array(raw);
        if (entries.size() > Checklist.MAX_ITEMS) {
            throw new IllegalArgumentException("a list can hold at most " + Checklist.MAX_ITEMS + " items");
        }
        List<Item> result = new ArrayList<Item>();
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("item " + (i + 1) + " must be an object with text and done");
            }
            Map<String, Object> fields = Json.object(entry);
            Object text = fields.get("text");
            Object done = fields.get("done");
            if (!(text instanceof String) || ((String) text).trim().length() == 0) {
                throw new IllegalArgumentException("item " + (i + 1) + " needs non-empty text");
            }
            if (done != null && !(done instanceof Boolean)) {
                throw new IllegalArgumentException("item " + (i + 1) + " has a done value that is not true or false");
            }
            try {
                result.add(new Item((String) text, Boolean.TRUE.equals(done)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("item " + (i + 1) + ": " + e.getMessage());
            }
        }
        return result;
    }
}
