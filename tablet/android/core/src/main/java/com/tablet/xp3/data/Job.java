package com.tablet.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * One transform job, as the command center lists it (GET /transforms): an
 * agent reading what the user wrote on a document and making the document
 * it asks for, which goes back onto the tablet.
 *
 * A job runs until the agent submits its result (done) or gives up, runs out
 * of time or can't start (failed). What it is making (`title`), what it is
 * doing (`progress`) and how far along it is (`percent`) are what the agent
 * last reported; until it reports, they are empty.
 */
public final class Job {

    public static final int RUNNING = 0;
    public static final int DONE = 1;
    public static final int FAILED = 2;

    public final String id;
    /** The tablet document it started from, and that document's name then. */
    public final String document;
    public final String name;
    public final int status;
    public final long created;
    public final long updated;
    public final String title;
    public final String progress;
    public final int percent;
    public final String error;
    /** The result's name and id on the tablet, once done. */
    public final String result;
    public final String tablet;
    /** The result's files in Delta's Files: its PDF and the other formats asked for, page images in order. */
    public final List<String> files;
    /** The formats the user asked for (Formats); none for a job from before there was a choice. */
    public final List<String> formats;

    Job(String id, String document, String name, int status, long created, long updated, String title,
        String progress, int percent, String error, String result, String tablet, List<String> files,
        List<String> formats) {
        this.id = id;
        this.document = document;
        this.name = name;
        this.status = status;
        this.created = created;
        this.updated = updated;
        this.title = title;
        this.progress = progress;
        this.percent = percent;
        this.error = error;
        this.result = result;
        this.tablet = tablet;
        this.files = files;
        this.formats = formats;
    }

    static Job parse(Map<String, Object> o) {
        String status = Json.text(o, "status");
        Double percent = Json.number(o, "percent");
        List<String> files = new ArrayList<>();
        for (Object f : Json.children(o, "files")) if (f instanceof String) files.add((String) f);
        List<String> formats = new ArrayList<>();
        for (Object f : Json.children(o, "formats")) if (f instanceof String && Formats.index((String) f) >= 0) formats.add((String) f);
        int state = "done".equals(status) ? DONE : "failed".equals(status) ? FAILED : RUNNING;
        return new Job(Json.string(o, "id"), orEmpty(Json.text(o, "document")), orEmpty(Json.text(o, "name")),
            state, Json.instant(o, "created"), Json.instant(o, "updated"),
            orEmpty(Json.text(o, "title")).trim(), orEmpty(Json.text(o, "progress")).trim(),
            percent == null ? 0 : Math.max(0, Math.min(100, percent.intValue())),
            orEmpty(Json.text(o, "error")).trim(), orEmpty(Json.text(o, "result")),
            orEmpty(Json.text(o, "tablet")), Collections.unmodifiableList(files), Collections.unmodifiableList(formats));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public boolean active() {
        return status == RUNNING;
    }

    /** What it's making, once the agent has said; until then, the document it's from. */
    public String heading() {
        return title.length() > 0 ? title : name;
    }

    /** The document it came from, when the heading is the agent's title rather than its name; else "". */
    public String from() {
        return title.length() > 0 ? name : "";
    }

    /** What the agent is doing now, in its words. */
    public String step() {
        return progress.length() > 0 ? progress : "Starting";
    }

    public String stateLabel() {
        switch (status) {
            case RUNNING: return "Running";
            case DONE: return "Done";
            default: return "Failed";
        }
    }

    /** Why it failed, or that it did. */
    public String failure() {
        return error.length() > 0 ? error : "No result";
    }

    /** When it finished: "10:42" today, "yesterday", "Oct 12". */
    public String finished(long now, TimeZone zone, boolean hours24) {
        long at = updated > 0 ? updated : created;
        return at > 0 ? When.day(at, now, zone, hours24) : "";
    }

    /** The result's files in `format` (Formats), in order: one, or a page image each. */
    public List<String> files(String format) {
        List<String> out = new ArrayList<>();
        for (String f : files) if (Formats.of(f).equals(format)) out.add(f);
        return out;
    }

    /** The formats it has files in, in Formats order. */
    public List<String> made() {
        List<String> out = new ArrayList<>();
        for (String format : Formats.ALL) if (!files(format).isEmpty()) out.add(format);
        return out;
    }

    /** The second line of a job in the list. A running job's percentage is its ring, not words. */
    public String summary(long now, TimeZone zone, boolean hours24) {
        switch (status) {
            case RUNNING: return step();
            case DONE:
                return finished(now, zone, hours24);
            default: return failure();
        }
    }
}
