package com.canvas.xp3.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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

        /** A fragment of the model's thinking, when a provider sends it. */
        void reasoning(String text);

        /** One line about what the turn is doing now, for the waiting screen. */
        void note(String line);

        Activity NOOP = new Activity() {
            @Override
            public void text(String text) {}

            @Override
            public void reasoning(String text) {}

            @Override
            public void note(String line) {}
        };
    }

    private static final int MAX_ROUNDS = 64;
    private static final int MAX_CALLS = 256;
    private static final int MAX_HISTORY_CHARS = 2000000;
    private static final String COMPACT_PROMPT =
        "Your task is to create a detailed summary of the conversation so far. " +
        "The raw conversation will be replaced with this summary, so preserve " +
        "everything needed to continue naturally: the user's explicit requests, " +
        "important facts, preferences, constraints and corrections, the assistant's " +
        "useful answers and actions, decisions already made, exact dates, course and " +
        "assignment names, grades, deadlines and calculations when relevant, and any " +
        "unresolved questions or promised next steps. Distinguish facts from estimates. " +
        "Do not call tools, do not continue or answer the conversation, and do not " +
        "address the user. Return only the summary with no preamble.";

    private final Responder model;
    private final Tools tools;
    private final ExecutorService pool;
    private List<Object> history = new ArrayList<Object>();
    /** Changes whenever the UI replaces history underneath an in-flight turn. */
    private long historyVersion;

    public Agent(Responder model, Tools tools) {
        this(model, tools, 2);
    }

    Agent(Responder model, Tools tools, int threads) {
        this.model = model;
        this.tools = tools;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "canvas-tool");
                // Daemon, so a turn still in flight never keeps the process up
                // after the last screen has gone.
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public synchronized void reset() {
        history = new ArrayList<Object>();
        historyVersion++;
    }

    /** Lets go of the tool threads when this agent is being replaced. */
    public void shutdown() {
        pool.shutdownNow();
    }

    /**
     * Puts a turn back into the model's history without asking it anything.
     *
     * The transcript on screen survives the app being closed, and this is what
     * stops the conversation behind it from silently not surviving: without it,
     * the first question after a relaunch would be answered against a blank
     * history while the screen above it still showed the exchange it was
     * following up on. Only the plain text goes back in. Tool calls and
     * reasoning blocks are not replayed; they belong to a turn that is over.
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
    public String summarize(final Activity activity) throws IOException, InterruptedException {
        Activity quiet = new Activity() {
            @Override public void text(String text) {}
            @Override public void reasoning(String text) {}
            @Override public void note(String line) { activity.note(line); }
        };
        return complete(COMPACT_PROMPT, quiet, false);
    }

    private String complete(String message, Activity activity, boolean remember)
        throws IOException, InterruptedException {
        if (message.length() > 100000) {
            throw new IOException("That message is too long");
        }
        // A turn is committed to history only once it has finished. A request
        // that fails half way through therefore leaves no dangling function
        // call behind it, which the next request would be rejected for.
        List<Object> working;
        long startingVersion;
        synchronized (this) {
            working = new ArrayList<Object>(history);
            startingVersion = historyVersion;
        }
        working.add(Json.map("role", "user", "content", message));

        int calls = 0;
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

            // A no-argument function call is represented as an empty JSON
            // object. Some provider streams omit `arguments` or send an empty
            // string instead, so normalize that valid shorthand before the
            // call is executed or copied into the next model request.
            for (Object item : output) {
                Map<String, Object> entry = Json.object(item);
                if ("function_call".equals(entry.get("type"))) {
                    entry.put("arguments", toolArguments(entry));
                }
            }
            // Reasoning and encrypted_content are carried forward untouched:
            // a stateless Responses turn needs them back to continue thinking.
            working.addAll(output);

            List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>();
            for (Object item : output) {
                Map<String, Object> entry = Json.object(item);
                if ("function_call".equals(entry.get("type"))) pending.add(entry);
            }
            if (pending.isEmpty()) {
                String answer = finalAnswerFrom(output);
                if (answer != null) {
                    if (remember) {
                        synchronized (this) {
                            // Clear, sign-out, or rewind may have installed a
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
                // Commentary is nonterminal model progress. Keep it (and
                // reasoning) in the manually replayed state and let the model
                // carry on until it emits a final answer.
                if (hasProgress(output)) {
                    activity.note("Still thinking");
                    continue;
                }
                throw new IOException("The model sent no answer; ask again");
            }

            for (int i = 0; i < pending.size(); i++) {
                String name = Json.string(pending.get(i), "name");
                activity.note(readable(name));
            }
            List<Map<String, Object>> executed = runTools(pending, calls);
            calls += pending.size();
            for (Map<String, Object> call : executed) {
                working.add(Json.map("type", "function_call_output",
                    "call_id", Json.string(call, "call_id"),
                    "output", Json.string(call, "result")));
            }
        }
        throw new IOException("That took too many steps; ask something narrower");
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

    private static String finalAnswerFrom(List<Object> output) {
        String answer = messageTextFrom(output, false);
        if (answer == null) return null;
        answer = withoutLeadingWhitespace(answer);
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
                // Each response after a tool call or commentary round is a
                // new user-visible message. Providers commonly trim its first
                // delta, so supply the Markdown paragraph break ourselves.
                if (previousMessage[0]) visible = "\n\n" + visible;
                previousMessage[0] = true;
                activity.text(visible);
            }

            @Override
            public void reasoning(String text) {
                activity.reasoning(text);
            }

            @Override
            public void note(String line) {
                activity.note(line);
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

    private static String messageTextFrom(List<Object> output, boolean commentary) {
        StringBuilder text = new StringBuilder();
        for (Object item : output) {
            Map<String, Object> entry = Json.object(item);
            if (!"message".equals(entry.get("type"))) continue;
            // Newer Codex models mark preambles and progress reports as
            // commentary. An absent phase is the legacy final-answer shape.
            if (commentary != "commentary".equals(entry.get("phase"))) continue;
            for (Object part : Json.children(entry, "content")) {
                Map<String, Object> content = Json.object(part);
                Object value = content.get("output_text".equals(content.get("type")) ? "text" : "refusal");
                if (value instanceof String) {
                    if (text.length() > 0) text.append('\n');
                    text.append((String) value);
                }
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    private static boolean hasProgress(List<Object> output) {
        for (Object item : output) {
            Map<String, Object> entry = Json.object(item);
            if (
                "reasoning".equals(entry.get("type")) ||
                (
                    "message".equals(entry.get("type")) &&
                    "commentary".equals(entry.get("phase"))
                )
            ) return true;
        }
        return false;
    }

    /**
     * This round's tool calls, run together.
     *
     * They are independent read-only lookups and the phone spends the whole of
     * one waiting on a network it is not otherwise using, so several at once is
     * most of the difference between a turn that answers and one that is
     * abandoned. The results are put back in the order the model asked for
     * them, which is the order it pairs its outputs by.
     */
    private List<Map<String, Object>> runTools(List<Map<String, Object>> pending, int callsSoFar)
        throws IOException, InterruptedException {
        if (callsSoFar + pending.size() > MAX_CALLS) {
            throw new IOException("That needed too many lookups; ask something narrower");
        }
        final List<Map<String, Object>> executed =
            Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        List<CompletableFuture<?>> futures = new ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < pending.size(); i++) {
            final Map<String, Object> call = pending.get(i);
            futures.add(CompletableFuture.runAsync(new Runnable() {
                @Override
                public void run() {
                    String result;
                    try {
                        result = tools.call(Json.string(call, "name"), Json.string(call, "arguments"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    Map<String, Object> done = new LinkedHashMap<String, Object>();
                    done.put("call_id", Json.string(call, "call_id"));
                    done.put("name", Json.string(call, "name"));
                    done.put("arguments", Json.string(call, "arguments"));
                    done.put("result", result);
                    executed.add(done);
                }
            }, pool));
        }
        for (CompletableFuture<?> future : futures) {
            future.join();
        }

        List<Map<String, Object>> ordered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> call : pending) {
            String id = Json.string(call, "call_id");
            for (Map<String, Object> done : executed) {
                if (id.equals(done.get("call_id"))) {
                    ordered.add(done);
                    break;
                }
            }
        }
        if (ordered.size() != pending.size()) throw new InterruptedException();
        return ordered;
    }

    /** A tool name as something worth reading while you wait for it. */
    private static String readable(String tool) {
        if (tool.equals("due_assignments")) return "Checking due dates";
        if (tool.equals("list_courses")) return "Reading your courses";
        if (tool.equals("get_course")) return "Reading a course";
        if (tool.equals("list_assignments")) return "Reading assignments";
        if (tool.equals("get_assignment")) return "Reading an assignment";
        if (tool.equals("get_grading") || tool.equals("get_grading_standard")) return "Reading grading rules";
        if (tool.equals("get_grades")) return "Reading your grades";
        if (tool.equals("list_posts") || tool.equals("get_post")) return "Reading announcements";
        if (tool.equals("list_post_entries") || tool.equals("list_post_replies")) return "Reading replies";
        if (tool.equals("list_pages") || tool.equals("get_page")) return "Reading course pages";
        if (tool.equals("calculate_decimal")) return "Working it out";
        return "Looking something up";
    }
}
