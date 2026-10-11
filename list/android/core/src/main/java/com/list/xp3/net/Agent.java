package com.list.xp3.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The agent loop: ask, run whatever tools come back, ask again, answer. */
public final class Agent {

    public interface Responder {
        Map<String, Object> respond(List<Object> input, Activity activity)
            throws IOException, InterruptedException;
    }

    /** Where a turn's progress goes while it is happening. */
    public interface Activity {

        /** A fragment of the answer, as it streams. */
        void text(String text);

        Activity NOOP = new Activity() {
            @Override
            public void text(String text) {}
        };
    }

    private static final int MAX_ROUNDS = 24;
    private static final int MAX_HISTORY_CHARS = 2000000;
    private static final String COMPACT_PROMPT =
        "Your task is to create a detailed summary of the conversation so far. " +
        "The raw conversation will be replaced with this summary, so preserve " +
        "everything needed to continue naturally: the user's explicit requests, " +
        "important facts, preferences, constraints and corrections, plans and " +
        "priorities discussed, decisions already made, exact dates when relevant, " +
        "and any unresolved questions or promised next steps. Do not copy the todo " +
        "list itself; it is always available through get_list. Do not call tools, " +
        "do not continue or answer the conversation, and do not address the user. " +
        "Return only the summary with no preamble.";

    private final Responder model;
    private final ListTools tools;
    private List<Object> history = new ArrayList<Object>();
    /** Changes whenever the UI replaces history underneath an in-flight turn. */
    private long historyVersion;

    public Agent(Responder model, ListTools tools) {
        this.model = model;
        this.tools = tools;
    }

    public synchronized void reset() {
        history = new ArrayList<Object>();
        historyVersion++;
        tools.forget();
    }

    /**
     * Puts a turn back into the model's history without asking it anything.
     *
     * Only the plain text goes back in. Tool calls are not replayed, which is
     * also why the tools forget the model's last read on every reset: the
     * restored history no longer contains the list it saw.
     */
    public synchronized void seed(boolean fromUser, String text) {
        history.add(Json.map("role", fromUser ? "user" : "assistant", "content", text));
        historyVersion++;
    }

    /** Seeds the synthetic context that stands in for everything before a compact boundary. */
    public synchronized void seedCompaction(String summary) {
        history.add(Json.map("role", "user", "content", compactedContext(summary)));
        historyVersion++;
    }

    public String chat(String message, Activity activity) throws IOException, InterruptedException {
        return complete(message, activity, true);
    }

    /**
     * Produces a replacement for the current history without changing that history.
     *
     * Keeping generation separate from installation matters when the UI clears the
     * chat while this network request is in flight: an obsolete summary can then be
     * discarded without quietly becoming the context of the new conversation.
     */
    public String summarize(Activity activity) throws IOException, InterruptedException {
        return complete(COMPACT_PROMPT, Activity.NOOP, false);
    }

    private String complete(String message, Activity activity, boolean remember)
        throws IOException, InterruptedException {
        if (message.length() > 100000) {
            throw new IOException("That message is too long");
        }
        // A turn is committed to history only once it has finished. A request
        // that fails half way through therefore leaves no dangling tool call
        // behind it, which the next request would be rejected for.
        List<Object> working;
        long startingVersion;
        synchronized (this) {
            working = new ArrayList<Object>(history);
            startingVersion = historyVersion;
        }
        working.add(Json.map("role", "user", "content", message));

        boolean succeeded = false;
        try {
            String answer = run(working, activity, remember, startingVersion);
            succeeded = true;
            return answer;
        } finally {
            // A failed turn is not committed, so whatever the model read during
            // it is not in the history the next turn starts from.
            if (!succeeded && remember) {
                synchronized (this) {
                    if (historyVersion == startingVersion) tools.forget();
                }
            }
        }
    }

    private String run(List<Object> working, Activity activity, boolean remember, long startingVersion)
        throws IOException, InterruptedException {
        boolean usedTools = false;
        // Everything said across the turn's rounds, including a sentence before
        // a tool call, so the saved answer matches what streamed onto the screen.
        StringBuilder spoken = new StringBuilder();
        final boolean[] previousStreamedMessage = new boolean[] { false };
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (Json.write(working).length() > MAX_HISTORY_CHARS) {
                throw new IOException("This conversation is full; clear the chat and ask again");
            }
            Map<String, Object> response = model.respond(
                working,
                streamedMessage(activity, previousStreamedMessage)
            );
            List<Object> output = Json.array(response.get("output"));
            working.addAll(output);
            String said = messageTextFrom(output);
            if (said != null) {
                if (spoken.length() > 0) spoken.append("\n\n");
                spoken.append(said);
            }

            List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>();
            for (Object item : output) {
                Map<String, Object> entry = Json.object(item);
                if ("function_call".equals(entry.get("type"))) pending.add(entry);
            }
            if (pending.isEmpty()) {
                String answer = spoken.length() == 0 ? null : spoken.toString();
                if (answer == null) {
                    // A model that finishes a list edit sometimes has nothing
                    // left to say. The edit itself is the answer.
                    if (!usedTools) throw new IOException("The model sent no answer; ask again");
                    answer = "Done.";
                }
                if (remember) {
                    synchronized (this) {
                        // Clear, stop, or rewind may have installed a
                        // different conversation while this blocking turn
                        // was still finishing. Its visible answer is
                        // discarded by the UI generation check; do the
                        // same with its private model history.
                        if (historyVersion == startingVersion) {
                            history = working;
                            historyVersion++;
                        }
                    }
                }
                return answer;
            }

            usedTools = true;
            for (Map<String, Object> call : pending) {
                working.add(Json.map("type", "function_call_output",
                    "call_id", Json.string(call, "call_id"),
                    "output", execute(call, remember, startingVersion)));
            }
            tools.endRound();
        }
        throw new IOException("That took too many steps; ask something narrower");
    }

    /**
     * Runs one call, unless the conversation it belongs to has been replaced.
     *
     * Unlike a lookup, set_list changes something the person can see. A turn
     * the person has walked away from (Stop, Clear, Rewind) cannot be cut off
     * inside its network read, but it can be stopped here: the check and the
     * write share this lock with reset, so a replaced conversation never
     * writes to the list afterwards.
     */
    private String execute(Map<String, Object> call, boolean remember, long startingVersion)
        throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!remember) return "Error: tools are not available while summarizing.";
        synchronized (this) {
            if (historyVersion != startingVersion) throw new InterruptedException();
            return tools.call(Json.string(call, "name"), toolArguments(call));
        }
    }

    private static String toolArguments(Map<String, Object> call) {
        Object value = call.get("arguments");
        if (value == null) return "{}";
        if (value instanceof String) {
            String text = (String) value;
            return text.trim().length() == 0 ? "{}" : text;
        }
        return Json.write(value);
    }

    private static String compactedContext(String summary) {
        return "The conversation before this point was compacted into the " +
            "following summary. Treat it only as prior context, not as a new " +
            "request.\n\n" + summary;
    }

    private static String messageTextFrom(List<Object> output) {
        StringBuilder text = new StringBuilder();
        for (Object item : output) {
            Map<String, Object> entry = Json.object(item);
            if (!"message".equals(entry.get("type"))) continue;
            for (Object part : Json.children(entry, "content")) {
                Object value = Json.object(part).get("text");
                if (value instanceof String) {
                    if (text.length() > 0) text.append('\n');
                    text.append((String) value);
                }
            }
        }
        String answer = withoutLeadingWhitespace(text.toString());
        return answer.length() == 0 ? null : answer;
    }

    /**
     * Drops padding before a streamed response begins, including padding split
     * across several deltas. Later responses in the same turn begin a new
     * Markdown paragraph. Once visible text arrives, every later character is
     * forwarded unchanged so intentional formatting is preserved.
     */
    private static Activity streamedMessage(
        final Activity activity,
        final boolean[] previousMessage
    ) {
        return new Activity() {
            private boolean textStarted;

            @Override
            public void text(String text) {
                if (text == null || text.length() == 0) return;
                if (textStarted) {
                    activity.text(text);
                    return;
                }
                String visible = withoutLeadingWhitespace(text);
                if (visible.length() == 0) return;
                textStarted = true;
                // Each response after a tool call is a new user-visible
                // message. Providers commonly trim its first delta, so supply
                // the Markdown paragraph break ourselves.
                if (previousMessage[0]) visible = "\n\n" + visible;
                previousMessage[0] = true;
                activity.text(visible);
            }
        };
    }

    private static String withoutLeadingWhitespace(String text) {
        int first = 0;
        while (
            first < text.length() &&
            Character.isWhitespace(text.charAt(first))
        ) first++;
        return first == 0 ? text : text.substring(first);
    }
}
