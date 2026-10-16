package com.canvas.xp3.net;

import com.xp3.common.Json;
import java.util.Map;

/**
 * What a Canvas submission record means, in the two senses this app needs.
 *
 * They are not the same question, which is why both are written down here
 * rather than left as a condition inlined twice and drifting apart.
 *
 * `completed` is the agent's sense: has this stopped needing my attention. It
 * counts a graded record as complete, because for "what is still due this week"
 * a teacher having put a number on something means it is off the list.
 *
 * `turnedIn` is the Done tab's sense: did I hand this in. It is stricter,
 * because a zero entered against work nobody submitted is a graded record too,
 * and filing that under Done would be the app quietly telling somebody they had
 * done a thing they had not. That case belongs on the Missing tab, which is
 * where Canvas's own `missing` flag puts it.
 */
public final class Submissions {

    private Submissions() {}

    /** The agent's sense: submitted, pending review, graded or excused. */
    public static boolean completed(Map<String, Object> submission) {
        if (Json.flag(submission, "excused")) return true;
        if (submission.get("submitted_at") instanceof String) return true;
        String state = Json.text(submission, "workflow_state");
        return "submitted".equals(state) || "pending_review".equals(state) || "graded".equals(state);
    }

    /**
     * The Done tab's sense: excused, actually submitted, or graded with a score
     * on it. The last case is for work marked off outside Canvas, like an exam
     * sat on paper, which never gets a submitted_at but is certainly done.
     */
    public static boolean turnedIn(Map<String, Object> submission) {
        if (Json.flag(submission, "excused")) return true;
        if (submission.get("submitted_at") instanceof String) return true;
        return "graded".equals(Json.text(submission, "workflow_state"))
            && Json.number(submission, "score") != null;
    }

    /** Whether Canvas itself says this is missing, teacher overrides included. */
    public static boolean missing(Map<String, Object> submission) {
        return Json.flag(submission, "missing");
    }
}
