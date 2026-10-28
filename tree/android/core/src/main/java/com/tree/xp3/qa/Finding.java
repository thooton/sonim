package com.tree.xp3.qa;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One thing an explore job found in an app, and what the fix job made of it.
 * Kept with the JSON it came from, so a screen can be handed it whole.
 */
public final class Finding {

    public final String id;
    public final String app;
    public final String run;
    /** bug, problem, inconsistency or feature. */
    public final String kind;
    public final String title;
    public final String detail;
    /** Screenshot names, for GET /qa/shots/{name}. */
    public final List<String> shots;
    /** open, fixed, passed, declined, grown or reverted. */
    public final String status;
    /** The fix job's one line on why. */
    public final String reason;
    public final String commit;
    /** up, down or "" (none given). */
    public final String verdict;
    public final long found;
    public final long updated;
    private final Map<String, Object> json;

    private Finding(Map<String, Object> json) {
        this.json = json;
        id = Qa.text(json, "id");
        app = Qa.text(json, "app");
        run = Qa.text(json, "run");
        kind = Qa.text(json, "kind");
        title = Qa.text(json, "title");
        detail = Qa.text(json, "detail");
        List<String> names = new ArrayList<>();
        for (Object item : Json.children(json, "shots")) {
            if (item instanceof String && Words.safeName((String) item)) names.add((String) item);
        }
        shots = Collections.unmodifiableList(names);
        status = Qa.text(json, "status");
        reason = Qa.text(json, "reason");
        commit = Qa.text(json, "commit");
        String given = Qa.text(json, "verdict");
        verdict = "up".equals(given) || "down".equals(given) ? given : "";
        found = Json.instant(json, "found");
        updated = Json.instant(json, "updated");
    }

    public static Finding from(Map<String, Object> json) {
        return new Finding(json);
    }

    public static Finding parse(String json) {
        return from(Json.object(Json.parse(json)));
    }

    /** A list as GET /qa/findings sends it; anything without an id is left out. */
    public static List<Finding> list(String json) {
        List<Finding> out = new ArrayList<>();
        for (Object item : Json.array(Json.parse(json))) {
            if (!(item instanceof Map)) continue;
            Finding finding = from(Json.object(item));
            if (finding.id.length() > 0) out.add(finding);
        }
        return out;
    }

    /** The JSON it came from, for handing to another screen. */
    public String toJson() {
        return Json.write(json);
    }

    /** A grown feature: the user may give it a verdict. */
    public boolean grown() {
        return "grown".equals(status);
    }

    /** A grown feature still waiting for the user's verdict. */
    public boolean awaiting() {
        return grown() && verdict.length() == 0;
    }
}
