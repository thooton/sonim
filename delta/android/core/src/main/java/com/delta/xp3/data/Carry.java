package com.delta.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A conversation carried over to a new session on another cloud account.
 *
 * A session runs on one account, and when that account reaches its usage
 * limit the session can't go on until the limit lifts. The phone can then
 * start a new session on an account that still has room. The whole
 * conversation so far, as the agent had it, is written out (`build`) from
 * the old session's full event history: every message, every tool call with
 * its complete input, every tool result in full. Nothing is shortened. It is
 * stored on the command center, and the new session's first message opens
 * with a block (`block`) naming it for `delta resume <id> <file>`, with the
 * pictures that were sent attached. Like the setup block it is fenced in a
 * tag, so the transcript shows it as one note instead.
 */
public final class Carry {

    static final String OPEN = "<delta-continued>";
    static final String CLOSE = "</delta-continued>";

    /** How the block reads on the phone, here and in a replay. */
    public static final String NOTE = "Continued from an earlier session whose account reached its usage limit.";

    /** The written-out conversation, and the pictures it refers to, in order, to go with it. */
    public static final class Built {
        public final String text;
        public final List<byte[]> pictures;

        Built(String text, List<byte[]> pictures) {
            this.text = text;
            this.pictures = pictures;
        }
    }

    private Carry() {}

    /**
     * The whole conversation of the session titled `title`, whose event
     * payloads, oldest first, are `events` (Cloud.history), as the text
     * `delta resume` saves. Its first line is "# " and the title.
     */
    public static Built build(String title, List<Map<String, Object>> events) {
        StringBuilder out = new StringBuilder();
        List<byte[]> pictures = new ArrayList<byte[]>();
        String name = title == null || title.trim().length() == 0 ? "Untitled" : title.trim();
        out.append("# ").append(name.replace('\n', ' ')).append("\n\n");
        out.append("The complete conversation of an earlier Delta session, which stopped because the account it ");
        out.append("ran on reached its usage limit: every message, every tool call with its full input, and every ");
        out.append("tool result in full, oldest first. \"You\" is the agent. Pictures the user sent are attached to ");
        out.append("the first message of the session that continues it, in the order they are mentioned.\n\n");
        out.append(conversation(events, pictures));
        out.append("[End of the conversation. Carry on from here.]\n");
        return new Built(out.toString(), pictures);
    }

    /**
     * The block that opens the first message of the new session: why, and
     * the id the conversation is stored under on the command center.
     */
    public static String block(String title, String id) {
        StringBuilder out = new StringBuilder();
        out.append(OPEN).append('\n');
        out.append("This conversation continues an earlier session");
        if (title != null && title.trim().length() > 0) out.append(" (\"").append(clean(title.trim())).append("\")");
        out.append(" that stopped because the account it ran on reached its usage limit; this session runs on ");
        out.append("another account. The whole conversation so far, every message, tool call and tool result in ");
        out.append("full, is on the command center. Once the Delta CLI is set up, and before anything else, run\n\n");
        out.append("    delta resume ").append(id).append(" conversation.md\n\n");
        out.append("which saves it to conversation.md, and read ALL of that file, from the first line to the last, ");
        out.append("in parts if need be. ");
        out.append("Pictures the user sent earlier are attached to this message, in the order the conversation ");
        out.append("mentions them. The old session's sandbox did not come along: clone repositories again and redo ");
        out.append("any work that was not pushed or saved elsewhere. Then pick up exactly where it left off. ");
        out.append("If the user wrote anything new, it follows this block.\n");
        out.append(CLOSE);
        return out.toString();
    }

    /** The main thread of the conversation, as text, collecting the user's pictures into `pictures`. */
    static String conversation(List<Map<String, Object>> events, List<byte[]> pictures) {
        StringBuilder out = new StringBuilder();
        Map<String, String> tools = new HashMap<String, String>();
        Set<String> seen = new HashSet<String>();
        for (Map<String, Object> payload : events) {
            String type = Json.text(payload, "type");
            // A subagent's own turns are its working; what it found comes
            // back as the result of the call that started it.
            if (payload.get("parent_tool_use_id") != null) continue;
            String uuid = Json.text(payload, "uuid");
            if (uuid != null && !seen.add(uuid)) continue;
            if ("user".equals(type)) {
                if (Json.flag(payload, "isMeta")) continue;
                user(out, Json.child(payload, "message").get("content"), tools, pictures);
            } else if ("assistant".equals(type)) {
                assistant(out, Json.children(Json.child(payload, "message"), "content"), tools);
            }
        }
        return out.toString();
    }

    private static void user(StringBuilder out, Object content, Map<String, String> tools, List<byte[]> pictures) {
        if (content instanceof String) {
            said(out, "User", Setup.strip((String) content));
            return;
        }
        StringBuilder words = new StringBuilder();
        for (Object value : Json.array(content == null ? new ArrayList<Object>() : content)) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> block = Json.object(value);
            String type = Json.text(block, "type");
            if ("tool_result".equals(type)) {
                String name = tools.get(Json.text(block, "tool_use_id"));
                String heading = "Result of " + (name == null ? "a tool call" : name)
                    + (Json.flag(block, "is_error") ? " (error)" : "");
                said(out, heading, result(block.get("content")));
            } else if ("text".equals(type)) {
                String text = Json.text(block, "text");
                if (text == null) continue;
                if (words.length() > 0) words.append("\n\n");
                // The setup sits at the start of the text, after any pictures.
                words.append(Setup.strip(text));
            } else if ("image".equals(type)) {
                if (words.length() > 0) words.append("\n\n");
                words.append(picture(Json.child(block, "source"), pictures));
            }
        }
        if (words.length() > 0) said(out, "User", words.toString());
    }

    private static void assistant(StringBuilder out, List<Object> content, Map<String, String> tools) {
        for (Object value : content) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> block = Json.object(value);
            String type = Json.text(block, "type");
            if ("text".equals(type)) {
                said(out, "You", Json.text(block, "text"));
            } else if ("thinking".equals(type)) {
                said(out, "You (thinking)", Json.text(block, "thinking"));
            } else if ("tool_use".equals(type)) {
                String name = Json.text(block, "name");
                String id = Json.text(block, "id");
                if (id != null && name != null) tools.put(id, name);
                said(out, "You called " + (name == null ? "a tool" : name), Json.write(Json.child(block, "input")));
            }
        }
    }

    /** A tool result's content: a string, or blocks of text and images. */
    private static String result(Object content) {
        if (content == null) return "(no output)";
        if (content instanceof String) return ((String) content).length() == 0 ? "(no output)" : (String) content;
        if (!(content instanceof List)) return Json.write(content);
        StringBuilder out = new StringBuilder();
        for (Object value : Json.array(content)) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> block = Json.object(value);
            if (out.length() > 0) out.append('\n');
            if ("text".equals(Json.text(block, "type"))) {
                String text = Json.text(block, "text");
                out.append(text == null ? "" : text);
            } else if ("image".equals(Json.text(block, "type"))) {
                out.append("[an image, which can't be carried over; produce it again if you need it]");
            } else {
                out.append(Json.write(block));
            }
        }
        return out.length() == 0 ? "(no output)" : out.toString();
    }

    /** A picture the user sent: attached to the new message when it is a JPEG, as phone pictures are. */
    private static String picture(Map<String, Object> source, List<byte[]> pictures) {
        String data = Json.text(source, "data");
        if (!"base64".equals(Json.text(source, "type")) || data == null
            || !"image/jpeg".equals(Json.text(source, "media_type"))) {
            return "[a picture, which can't be carried over]";
        }
        try {
            pictures.add(Base64.getDecoder().decode(data));
        } catch (IllegalArgumentException e) {
            return "[a picture, which can't be carried over]";
        }
        return "[picture " + pictures.size() + ", attached to the first message]";
    }

    private static void said(StringBuilder out, String who, String text) {
        if (text == null || text.trim().length() == 0) return;
        out.append("[").append(who).append("]\n").append(clean(text.trim())).append("\n\n");
    }

    /** Nothing quoted may close the block early. */
    private static String clean(String text) {
        return text.replace(CLOSE, "</delta-continued >");
    }

    /** Whether a message (with any setup block already taken out) opens with the block. */
    public static boolean present(String text) {
        return text != null && text.trim().startsWith(OPEN) && text.contains(CLOSE);
    }

    /** A message with any leading block taken back out. */
    public static String strip(String text) {
        if (!present(text)) return text == null ? "" : text;
        String trimmed = text.trim();
        return trimmed.substring(trimmed.indexOf(CLOSE) + CLOSE.length()).trim();
    }
}
