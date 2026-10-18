package com.tablet.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every transform job, as the command center lists them, and the
 * comparisons the app makes between one listing and the next.
 */
public final class Jobs {

    private final List<Job> all;

    private Jobs(List<Job> all) {
        this.all = all;
    }

    public static Jobs empty() {
        return new Jobs(new ArrayList<Job>());
    }

    /** Throws IllegalArgumentException when the listing isn't one. */
    public static Jobs parse(String json) {
        List<Job> all = new ArrayList<>();
        for (Object o : Json.array(Json.parse(json))) all.add(Job.parse(Json.object(o)));
        // Newest first, as the command center sends them, without counting on it.
        Collections.sort(all, (a, b) -> Long.compare(b.created, a.created));
        return new Jobs(all);
    }

    /** Running first, then finished ones; newest first within each. */
    public List<Job> ordered() {
        List<Job> out = new ArrayList<>();
        for (Job job : all) if (job.active()) out.add(job);
        for (Job job : all) if (!job.active()) out.add(job);
        return out;
    }

    public int active() {
        int n = 0;
        for (Job job : all) if (job.active()) n++;
        return n;
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    /** The newest running job, for the drawer's line under Transforms. */
    public Job running() {
        for (Job job : all) if (job.active()) return job;
        return null;
    }

    /** A job running on document `id`, or null. */
    public Job runningOn(String id) {
        for (Job job : all) if (job.active() && job.document.equals(id)) return job;
        return null;
    }

    public Job find(String id) {
        for (Job job : all) if (job.id.equals(id)) return job;
        return null;
    }

    /** The same list less one job, as it is once the command center has forgotten it. */
    public Jobs without(String id) {
        List<Job> out = new ArrayList<>(all);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).id.equals(id)) {
                out.remove(i);
                break;
            }
        }
        return new Jobs(out);
    }

    /** The same list with a job just started at the top. */
    public Jobs with(Job job) {
        List<Job> out = new ArrayList<>(all);
        out.add(0, job);
        return new Jobs(out);
    }

    /** Jobs running in `before` that have finished in this listing, for saying so. */
    public List<Job> finishedSince(Jobs before) {
        List<Job> out = new ArrayList<>();
        for (Job job : all) {
            Job was = before.find(job.id);
            if (!job.active() && was != null && was.active()) out.add(job);
        }
        return out;
    }

    /** A job from POST /transforms's answer. */
    public static Job job(String json) {
        return Job.parse(Json.object(Json.parse(json)));
    }
}
