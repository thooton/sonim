package com.tree.xp3.qa;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The command center's nightly QA as `GET /qa` describes it: the schedule,
 * every app it goes through, the run going now (if any) and the latest runs.
 * Read leniently: a field that is missing or of the wrong type takes its
 * empty value rather than hiding the rest.
 */
public final class Qa {

    public final Settings settings;
    public final List<App> apps;
    /** The run going now, or null. */
    public final Run running;
    /** Newest first; no apps or jobs in these, only the run's own fields. */
    public final List<Run> runs;
    /** Why the command center couldn't list the apps, or "". */
    public final String error;
    public final boolean devices;

    Qa(Settings settings, List<App> apps, Run running, List<Run> runs, String error, boolean devices) {
        this.settings = settings;
        this.apps = Collections.unmodifiableList(apps);
        this.running = running;
        this.runs = Collections.unmodifiableList(runs);
        this.error = error;
        this.devices = devices;
    }

    public static Qa parse(String json) {
        Map<String, Object> root = Json.object(Json.parse(json));
        List<App> apps = new ArrayList<>();
        for (Object item : Json.children(root, "apps")) {
            if (item instanceof Map) {
                App app = App.from(Json.object(item));
                if (app.id.length() > 0) apps.add(app);
            }
        }
        List<Run> runs = new ArrayList<>();
        for (Object item : Json.children(root, "runs")) {
            if (item instanceof Map) runs.add(Run.from(Json.object(item)));
        }
        Object running = root.get("running");
        return new Qa(Settings.from(Json.child(root, "settings")), apps,
            running instanceof Map ? Run.from(Json.object(running)) : null,
            runs, text(root, "error"), Json.flag(root, "devices"));
    }

    /** The app with this id ("sonim/list"), or null. */
    public App app(String id) {
        for (App app : apps) if (app.id.equals(id)) return app;
        return null;
    }

    /** The run going now, else the newest one, else null. */
    public Run latest() {
        if (running != null) return running;
        return runs.isEmpty() ? null : runs.get(0);
    }

    /** The newest run that wrote a summary, or null. */
    public Run lastSummary() {
        for (Run run : runs) if (run.summary.trim().length() > 0) return run;
        return null;
    }

    /** This app's place in the run going now, or null when none is going or it isn't in it. */
    public Progress progress(String id) {
        return running == null ? null : running.progress(id);
    }

    /** A copy with the run going now replaced (a run just started, or cancelled: null). */
    public Qa withRunning(Run run) {
        List<Run> list = new ArrayList<>(runs);
        if (run != null && !run.isRunning()) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(run.id)) list.set(i, run);
            }
        }
        return new Qa(settings, apps, run != null && run.isRunning() ? run : null, list, error, devices);
    }

    public Qa withSettings(Settings changed) {
        return new Qa(changed, apps, running, runs, error, devices);
    }

    /** A copy with one app's grow switched. */
    public Qa withGrow(String id, boolean grow) {
        List<App> list = new ArrayList<>();
        for (App app : apps) list.add(app.id.equals(id) ? app.withGrow(grow) : app);
        return new Qa(settings, list, running, runs, error, devices);
    }

    static String text(Map<String, Object> map, String key) {
        String value = Json.text(map, key);
        return value == null ? "" : value;
    }

    static int number(Map<String, Object> map, String key) {
        Double value = Json.number(map, key);
        return value == null ? 0 : (int) Math.round(value.doubleValue());
    }

    /** When the nightly run starts. */
    public static final class Settings {
        public final boolean enabled;
        /** 0 to 23, in timeZone. */
        public final int hour;
        public final String timeZone;

        public Settings(boolean enabled, int hour, String timeZone) {
            this.enabled = enabled;
            this.hour = hour;
            this.timeZone = timeZone;
        }

        public static Settings from(Map<String, Object> map) {
            return new Settings(Json.flag(map, "enabled"), Math.max(0, Math.min(23, number(map, "hour"))), text(map, "time_zone"));
        }
    }

    /** One app the QA goes through. */
    public static final class App {
        /** "sonim/list". */
        public final String id;
        /** "sonim" or "tablet". */
        public final String repo;
        /** "list". */
        public final String dir;
        public final boolean phone;
        /** Whether it has its SPEC.md yet. */
        public final boolean spec;
        public final boolean grow;
        /** When it was last explored, epoch ms, or 0. */
        public final long explored;
        public final int open;
        /** Findings passed to the user. */
        public final int passed;
        /** Grown features waiting for the user's verdict. */
        public final int grown;
        /** Its place in the latest run, or null. */
        public final Progress last;

        App(String id, String repo, String dir, boolean phone, boolean spec, boolean grow, long explored,
            int open, int passed, int grown, Progress last) {
            this.id = id;
            this.repo = repo;
            this.dir = dir;
            this.phone = phone;
            this.spec = spec;
            this.grow = grow;
            this.explored = explored;
            this.open = open;
            this.passed = passed;
            this.grown = grown;
            this.last = last;
        }

        static App from(Map<String, Object> map) {
            String id = text(map, "id");
            String repo = text(map, "repo");
            String dir = text(map, "dir");
            int slash = id.indexOf('/');
            if (repo.length() == 0 && slash > 0) repo = id.substring(0, slash);
            if (dir.length() == 0 && slash > 0) dir = id.substring(slash + 1);
            Object last = map.get("last");
            return new App(id, repo, dir, Json.flag(map, "phone"), Json.flag(map, "spec"), Json.flag(map, "grow"),
                Json.instant(map, "explored"), number(map, "open"), number(map, "passed"), number(map, "grown"),
                last instanceof Map ? Progress.from(Json.object(last)) : null);
        }

        App withGrow(boolean value) {
            return new App(id, repo, dir, phone, spec, value, explored, open, passed, grown, last);
        }

        /** "List", from its directory. */
        public String name() {
            return Words.name(dir);
        }
    }

    /** An app's place in a run. */
    public static final class Progress {
        public final String app;
        public final String repo;
        public final String dir;
        public final boolean phone;
        public final boolean spec;
        /** waiting, running, done or skipped. */
        public final String status;
        public final String why;

        Progress(String app, String repo, String dir, boolean phone, boolean spec, String status, String why) {
            this.app = app;
            this.repo = repo;
            this.dir = dir;
            this.phone = phone;
            this.spec = spec;
            this.status = status;
            this.why = why;
        }

        static Progress from(Map<String, Object> map) {
            String app = text(map, "app");
            String dir = text(map, "dir");
            int slash = app.indexOf('/');
            if (dir.length() == 0 && slash > 0) dir = app.substring(slash + 1);
            return new Progress(app, text(map, "repo"), dir, Json.flag(map, "phone"), Json.flag(map, "spec"),
                text(map, "status"), text(map, "why"));
        }

        public boolean finished() {
            return "done".equals(status) || "skipped".equals(status);
        }

        public String name() {
            return Words.name(dir);
        }
    }

    /** One run of the QA. */
    public static final class Run {
        public final String id;
        /** schedule or manual. */
        public final String trigger;
        /** running, done or cancelled. */
        public final String status;
        /** apps, refactor or done. */
        public final String stage;
        public final long created;
        public final long ended;
        public final String summary;
        public final String error;
        /** Only in a run fetched whole (GET /qa/runs/{id}, or the one running). */
        public final List<Progress> apps;
        public final List<Job> jobs;

        Run(String id, String trigger, String status, String stage, long created, long ended, String summary,
            String error, List<Progress> apps, List<Job> jobs) {
            this.id = id;
            this.trigger = trigger;
            this.status = status;
            this.stage = stage;
            this.created = created;
            this.ended = ended;
            this.summary = summary;
            this.error = error;
            this.apps = Collections.unmodifiableList(apps);
            this.jobs = Collections.unmodifiableList(jobs);
        }

        public static Run from(Map<String, Object> map) {
            List<Progress> apps = new ArrayList<>();
            for (Object item : Json.children(map, "apps")) {
                if (item instanceof Map) apps.add(Progress.from(Json.object(item)));
            }
            List<Job> jobs = new ArrayList<>();
            for (Object item : Json.children(map, "jobs")) {
                if (item instanceof Map) jobs.add(Job.from(Json.object(item)));
            }
            return new Run(text(map, "id"), text(map, "trigger"), text(map, "status"), text(map, "stage"),
                Json.instant(map, "created"), Json.instant(map, "ended"), text(map, "summary"), text(map, "error"),
                apps, jobs);
        }

        public static Run parse(String json) {
            return from(Json.object(Json.parse(json)));
        }

        public boolean isRunning() {
            return "running".equals(status);
        }

        public Progress progress(String id) {
            for (Progress progress : apps) if (progress.app.equals(id)) return progress;
            return null;
        }

        /** How many of its apps are done or skipped. */
        public int finished() {
            int count = 0;
            for (Progress progress : apps) if (progress.finished()) count++;
            return count;
        }

        /** The app being worked on now, or null. */
        public Progress current() {
            for (Progress progress : apps) if ("running".equals(progress.status)) return progress;
            return null;
        }
    }

    /** One cloud job in a run. */
    public static final class Job {
        public final String id;
        public final String app;
        public final String repo;
        /** spec, explore, fix or refactor. */
        public final String role;
        /** running, done, failed or cancelled. */
        public final String status;
        public final long created;
        public final long ended;
        public final String summary;

        Job(String id, String app, String repo, String role, String status, long created, long ended, String summary) {
            this.id = id;
            this.app = app;
            this.repo = repo;
            this.role = role;
            this.status = status;
            this.created = created;
            this.ended = ended;
            this.summary = summary;
        }

        static Job from(Map<String, Object> map) {
            return new Job(text(map, "id"), text(map, "app"), text(map, "repo"), text(map, "role"), text(map, "status"),
                Json.instant(map, "created"), Json.instant(map, "ended"), text(map, "summary"));
        }
    }
}
