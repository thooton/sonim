package com.canvas.xp3.data;

import com.xp3.common.Check;
import com.xp3.common.Json;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** The Week Strip model: courses, the week, drafts and turning in, reminders, notices and what is cached. */
public final class ClassroomTests {

    private ClassroomTests() {}

    public static void run(final Check check) {
        check.run("course names", () -> courses(check));
        check.run("week strip", () -> week(check));
        check.run("draft plans", () -> plans(check));
        check.run("formatted answers", () -> formatting(check));
        check.run("reminders", () -> reminders(check));
        check.run("notices", () -> notices(check));
        check.run("details", () -> details(check));
        check.run("classroom store", () -> store(check));
        check.run("this semester", () -> semester(check));
    }

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private static long at(int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(Locale.US);
        calendar.set(2026, month, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static void courses(Check check) {
        String[] thf = Course.split("THF 101: Acting Introduction (2026 Fall C)", "THF 101");
        check.same("the code comes off the front", "THF 101", thf[0]);
        check.same("the title is what is left", "Acting Introduction", thf[1]);
        check.same("the term comes out of the brackets", "2026 Fall C", thf[2]);
        String[] uni = Course.split("UNI 220: Mindset Connections TH 1:30", null);
        check.same("a colon later in the title stays", "Mindset Connections TH 1:30", uni[1]);
        String[] plain = Course.split("Pitchfork Pantry Fall 2026", "PANTRY-2026-ORGANIZATION");
        check.same("no code: the whole name is the title", "Pitchfork Pantry Fall 2026", plain[1]);
        check.ok("and the code is short", plain[0].length() <= 8);
        check.same("teacher names shorten", "M. Okafor", Course.shortName("Monica Okafor"));
        check.same("initials", "MO", Course.initials("Monica Okafor"));

        List<java.util.Map<String, Object>> records = new ArrayList<>();
        records.add(Json.object(Json.parse("{\"id\":3,\"name\":\"UNI 220: Mindset\"}")));
        records.add(Json.object(Json.parse("{\"id\":1,\"name\":\"THF 101: Acting\",\"enrollments\":"
            + "[{\"type\":\"student\",\"computed_current_score\":92.5}],\"teachers\":[{\"display_name\":\"M O\"}]}")));
        records.add(Json.object(Json.parse("{\"id\":2,\"name\":\"THF 160: Story\",\"hide_final_grades\":true,"
            + "\"enrollments\":[{\"type\":\"student\",\"computed_current_score\":80}]}")));
        List<Course> sorted = Sync.courses(records);
        check.same("colours are dealt in code order", "THF 101", sorted.get(0).code);
        check.same("first colour", 0, sorted.get(0).color);
        check.same("third colour to the third code", 2, sorted.get(2).color);
        check.same("a shared grade is kept", Double.valueOf(92.5), sorted.get(0).score);
        check.same("a hidden one is not", null, sorted.get(1).score);
        check.same("teachers come along", "M O", sorted.get(0).teachers.get(0));
    }

    private static Assignment work(String id, Long due, boolean done) {
        return new Assignment("1", id, "W" + id, "Bio", due, 10, false, done, done ? Long.valueOf(0) : null,
            null, false);
    }

    private static void week(Check check) {
        Dates.setWeekStart(Calendar.MONDAY);
        long saturday = at(Calendar.OCTOBER, 17, 12, 0);
        long tuesday = at(Calendar.OCTOBER, 20, 10, 30);
        Todos todos = new Todos();
        List<Assignment> all = new ArrayList<>();
        all.add(work("a", Long.valueOf(tuesday), false));
        all.add(work("b", Long.valueOf(tuesday + 10 * 60 * 60 * 1000L), false));
        all.add(work("c", Long.valueOf(at(Calendar.OCTOBER, 22, 9, 0)), false));
        all.add(work("d", null, false));
        all.add(work("e", Long.valueOf(at(Calendar.OCTOBER, 21, 9, 0)), true));
        todos.replace(all);
        check.same("nothing left this week: open on the next due day", Dates.dayOf(tuesday),
            todos.openingDay(saturday));
        check.same("with work this week, open on today", Dates.dayOf(tuesday),
            todos.openingDay(at(Calendar.OCTOBER, 20, 8, 0)));
        check.same("a day's work, earliest first", "a", todos.dueOn(Dates.dayOf(tuesday), saturday).get(0).assignmentId);
        check.same("both of Tuesday's", 2, todos.dueOn(Dates.dayOf(tuesday), saturday).size());
        check.same("done work is off the strip", 0,
            todos.dueOn(Dates.dayOf(at(Calendar.OCTOBER, 21, 9, 0)), saturday).size());
        check.same("Right from Tuesday goes to Thursday", Long.valueOf(Dates.dayOf(at(Calendar.OCTOBER, 22, 9, 0))),
            todos.dayWithWork(Dates.dayOf(tuesday), 1, saturday));
        check.same("Left from Tuesday finds nothing", null, todos.dayWithWork(Dates.dayOf(tuesday), -1, saturday));
        check.same("undated work is listed apart", 1, todos.undated(saturday).size());
        check.same("Tuesday is the second column", 1, Dates.column(tuesday));
        check.same("the calendar page", "TUE", Dates.weekdayTab(tuesday));
        check.same("the day heading", "TUESDAY OCT 20", Dates.dayHeading(tuesday));
        Dates.setWeekStart(Calendar.SUNDAY);
        check.same("a Sunday week starts on the 18th", "SUNDAY OCT 18", Dates.dayHeading(Dates.weekOf(tuesday)));
        check.same("and Tuesday is the third column", 2, Dates.column(tuesday));
        Dates.setWeekStart(Calendar.MONDAY);
    }

    private static void plans(Check check) {
        List<String> textOrFiles = Arrays.asList(Assignment.TEXT, Assignment.UPLOAD);
        Draft draft = Draft.empty("1:2");
        check.ok("an empty draft can't be turned in", !draft.plan(textOrFiles, null).ok());
        draft = draft.with(Draft.Piece.text("t", "Answers", "One\n\nTwo <b>"), 1);
        check.same("typed answers alone are a text entry", Assignment.TEXT, draft.plan(textOrFiles, null).kind);
        check.same("as paragraphs, escaped", "<p>One</p><p>Two &lt;b&gt;</p>", draft.html());
        Draft both = draft.with(Draft.Piece.file("f", Draft.AUDIO, "Take 1", "a.m4a", "audio/mp4", 10, 4000), 2);
        check.same("with a file it is an upload", Assignment.UPLOAD, both.plan(textOrFiles, null).kind);
        check.ok("unless the extension isn't allowed", !both.plan(textOrFiles, Arrays.asList("pdf")).ok());
        check.ok("or files aren't taken", !both.plan(Arrays.asList(Assignment.TEXT), null).ok());
        Draft link = Draft.empty("1:2").with(Draft.Piece.link("l", "Video", "https://youtu.be/x"), 1);
        check.same("a link alone is a url", Assignment.URL, link.plan(Arrays.asList(Assignment.URL), null).kind);
        check.ok("a link with anything else is refused",
            !link.with(Draft.Piece.text("t", "A", "b"), 2).plan(Arrays.asList(Assignment.URL, Assignment.TEXT), null).ok());
        Draft two = draft.with(Draft.Piece.text("u", "More", "Three"), 3);
        check.ok("two answers each get a heading", two.html().startsWith("<h3>Answers</h3>"));
        check.same("removing a piece", 1, two.without("u", 4).pieces.size());
        check.same("words are counted", 3, Draft.Piece.text("x", "x", " a b  c ").words());
    }

    private static void formatting(Check check) {
        List<Rich.Run> runs = Arrays.asList(new Rich.Run(0, 4, Rich.BOLD), new Rich.Run(2, 7, Rich.ITALIC),
            new Rich.Run(8, 10, Rich.UNDERLINE));
        String stored = Rich.write("bold it\n*u+", runs);
        check.same("styles become markers, literals are escaped", "**bo*ld** it*\n++\\*u++\\+", stored);
        Rich.Styled back = Rich.parse(stored);
        check.same("and read back to the same text", "bold it\n*u+", back.text);
        check.same("with the same styles", stored, Rich.write(back.text, back.runs));
        check.same("a marker left open ends with its line", "a\nb", Rich.plain("**a\nb"));
        check.same("the plain answer", "a b", Rich.plain("**a** *b*"));

        check.same("formats become Canvas's own tags", "<p><strong>Big</strong> <em>and</em> <u>lined</u></p>",
            Rich.html("**Big** *and* ++lined++"));
        check.same("bullets and numbers become lists",
            "<p>Intro</p><ul><li>one</li><li><strong>two</strong></li></ul><ol start=\"3\"><li>three</li></ol>",
            Rich.html("Intro\n\u2022 one\n\u2022 **two**\n3. three"));
        check.same("lines stay breaks, blank lines paragraphs", "<p>a<br>b</p><p>c &lt;d&gt;</p>",
            Rich.html("a\nb\n\n c <d> "));

        Draft.Piece old = Draft.Piece.text("t", "A", "5*3 = 15 \\o/");
        check.same("an older answer's stars are kept as typed", "<p>5*3 = 15 \\o/</p>",
            Draft.empty("1:2").with(old, 1).html());
        Draft.Piece rich = old.withRich("**Yes**\n\u2022 one");
        check.ok("a formatted answer says so", rich.rich() && !old.rich());
        check.same("its .txt has no markers", "Yes\n\u2022 one", rich.plain());
        check.same("and bullets aren't words", 2, rich.words());

        check.same("pasted Markdown keeps what Canvas can show",
            "**Title**\n\u2022 **bold** and *it* and ++u++\n\u2022 item\n2. next\n*mix **both***",
            Rich.fromMarkdown("# Title\n- __bold__ and _it_ and <u>u</u>\n* item\n2) next\n*mix **both***"));
        check.same("plain text passes through", "2 \\* 3 \\* 4 = snake_case\\+1",
            Rich.fromMarkdown("2 * 3 * 4 = snake_case+1"));
        check.same("code, quotes, links and fences become text", "see x\nquote\nGoogle (https://g.co)\ncode \\*",
            Rich.fromMarkdown("see `x`\n> quote\n[Google](https://g.co)\n```\ncode *\n```"));
        check.same("***both*** at once", "***both***", Rich.write(Rich.parse(Rich.fromMarkdown("***both***")).text,
            Rich.parse(Rich.fromMarkdown("***both***")).runs));
        check.same("pasted rich text", "**Hi** ++there++\n\n\u2022 one",
            Rich.fromHtml("<p><b>Hi</b> <u>there</u></p><ul><li>one</li></ul>"));
    }

    private static void reminders(Check check) {
        long monday = at(Calendar.OCTOBER, 19, 12, 0);
        long tuesdayDue = at(Calendar.OCTOBER, 20, 10, 30);
        List<Assignment> all = new ArrayList<>();
        all.add(work("a", Long.valueOf(tuesdayDue), false));
        all.add(work("b", Long.valueOf(at(Calendar.OCTOBER, 20, 9, 0)), true));
        HashSet<String> quiet = new HashSet<>();
        Reminders.Next evening = Reminders.next(monday, Prefs.EVENING, all, quiet);
        check.same("the evening before", at(Calendar.OCTOBER, 19, 18, 0), evening.at);
        check.same("naming only open work", 1, evening.work.size());
        Reminders.Next morning = Reminders.next(monday, Prefs.MORNING, all, quiet);
        check.same("or the morning of", at(Calendar.OCTOBER, 20, 8, 0), morning.at);
        Reminders.Next first = Reminders.next(at(Calendar.OCTOBER, 19, 19, 0), Prefs.BOTH, all, quiet);
        check.same("both: whichever is next", at(Calendar.OCTOBER, 20, 8, 0), first.at);
        check.same("off is off", null, Reminders.next(monday, Prefs.OFF, all, quiet));
        check.same("a 12-hour label", "Evening before, 6:00 PM", Prefs.reminderLabel(Prefs.EVENING, false));
        check.same("a 24-hour label", "Morning of, 08:00", Prefs.reminderLabel(Prefs.MORNING, true));
        quiet.add("1");
        check.same("a quiet class is left out", null, Reminders.next(monday, Prefs.EVENING, all, quiet));
        check.ok("the line states the date", Reminders.text(evening).contains("due Oct 20"));
        check.ok("and never hurries", !Reminders.text(evening).toLowerCase(Locale.US).contains("soon"));
    }

    private static void notices(Check check) {
        List<Course> courses = new ArrayList<>();
        courses.add(new Course("7", "Acting", "THF 101", null, new ArrayList<String>(), 0, null, null));
        List<Notice> notices = Notice.from(Json.array(Json.parse("["
            + "{\"type\":\"Submission\",\"updated_at\":\"2026-10-17T16:12:00Z\",\"course_id\":7,\"score\":10,"
            + "\"grade\":\"10\",\"assignment\":{\"id\":5,\"name\":\"Chapter 4\",\"points_possible\":10}},"
            + "{\"type\":\"Announcement\",\"updated_at\":\"2026-10-17T15:30:00Z\",\"course_id\":7,"
            + "\"title\":\"Workshops\",\"announcement_id\":9},"
            + "{\"type\":\"Submission\",\"updated_at\":\"2026-10-16T23:44:00Z\",\"course_id\":7,"
            + "\"assignment\":{\"id\":6,\"name\":\"Analysis\"},"
            + "\"submission_comments\":[{\"author_name\":\"Monica Okafor\",\"comment\":\"Yes\"}]},"
            + "{\"type\":\"Unknown\",\"updated_at\":\"2026-10-16T23:44:00Z\"}]")), courses);
        check.same("unknown events are left out", 3, notices.size());
        check.same("a grade says so", "Work returned: 10/10", notices.get(0).title);
        check.same("and names the work", "Chapter 4", notices.get(0).detail);
        check.same("announcements name the class", "New in THF 101", notices.get(1).title);
        check.same("comments name the teacher", "M. Okafor replied", notices.get(2).title);
        check.same("and point at the assignment", "6", notices.get(2).assignmentId);
    }

    private static void details(Check check) {
        List<Detail.FileRef> files = Detail.linkedFiles("<p>Read <a href=\"/courses/1/files/42/download\">"
            + "Scene <b>list</b>.pdf</a> and <a href=\"https://canvas.asu.edu/courses/1/files/42\">again</a>"
            + " and <a href='https://x.org/files/abc'>not a file</a> and <a href=\"/files/43?wrap=1\">two</a></p>");
        check.same("each linked file once", 2, files.size());
        check.same("named by its link", "Scene list.pdf", files.get(0).name);
        check.same("by its id", "43", files.get(1).id);
        check.same("a file's kind is its extension", "PDF", files.get(0).kind());

        Detail detail = Detail.from(Json.object(Json.parse("{\"description\":\"<p>Do it</p>\","
                + "\"allowed_extensions\":[\"PDF\"],\"rubric\":[{\"id\":\"r1\",\"description\":\"Depth\",\"points\":5,"
                + "\"ratings\":[{\"id\":\"x\",\"description\":\"Full\"}]}]}")),
            Json.object(Json.parse("{\"user_id\":3,\"submitted_at\":\"2026-10-17T21:05:00Z\","
                + "\"submission_type\":\"online_upload\",\"attachments\":[{\"id\":8,\"display_name\":\"a.pdf\","
                + "\"size\":2048}],\"rubric_assessment\":{\"r1\":{\"points\":4,\"rating_id\":\"x\"}},"
                + "\"submission_comments\":[{\"author_id\":3,\"author_name\":\"Me\",\"comment\":\"Hi\","
                + "\"created_at\":\"2026-10-16T22:00:00Z\"},{\"author_id\":9,\"author_name\":\"T\","
                + "\"comment\":\"Hello\",\"created_at\":\"2026-10-16T23:00:00Z\"}]}")), 5);
        check.same("instructions as Markdown", "Do it", detail.body.trim());
        check.same("extensions lower-cased", "pdf", detail.extensions.get(0));
        check.same("the rubric level", "Full", detail.rubric.get(0).level);
        check.same("and its points", Double.valueOf(4), detail.rubric.get(0).earned);
        check.same("what was turned in", "a.pdf", detail.submittedFiles.get(0).name);
        check.ok("the student's comment is theirs", detail.comments.get(0).mine);
        check.ok("the teacher's is not", !detail.comments.get(1).mine);
        Detail back = Detail.fromMap(detail.toMap());
        check.same("it survives being written down", "a.pdf", back.submittedFiles.get(0).name);
        check.same("comments too", "Hello", back.comments.get(1).text);
        check.same("an unreadable record is none", null, Detail.fromMap(Json.object(Json.parse("{}"))));
    }

    private static void store(Check check) throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir"), "canvas-classroom-" + System.nanoTime());
        directory.mkdirs();
        try {
            Store store = new Store(directory);
            check.same("default reminders", Prefs.EVENING, store.prefs().reminders);
            check.same("weeks follow the region by default", null, store.prefs().weekStart);
            store.savePrefs(store.prefs().withTheme(Prefs.DARK).withQuiet("7", true));
            check.same("a theme is kept", Prefs.DARK, store.prefs().theme);
            check.ok("so is a quiet class", store.prefs().quiet.contains("7"));
            check.same("a nonsense value reads as the default", Prefs.PHONE,
                new Prefs("purple", null, null, null, null, null, 0).theme);

            List<Assignment> work = new ArrayList<>();
            work.add(new Assignment("7", "91", "Lab", "Bio", null, 20, false, true, Long.valueOf(5), Double.valueOf(18),
                false, Arrays.asList(Assignment.TEXT), "4", null, true, Long.valueOf(9), "B", 3, 1, false));
            List<Course> courses = new ArrayList<>();
            courses.add(new Course("7", "Bio", "BIO 1", "Fall", Arrays.asList("T"), 2, Double.valueOf(90), "A"));
            java.util.Map<String, String> overrides = new java.util.HashMap<>();
            overrides.put("91", "300");
            store.saveCache(new Sync.Result(work, courses, new ArrayList<String>(), 10, overrides,
                new HashSet<String>(Arrays.asList("91"))));
            Sync.Result read = store.cache();
            Assignment item = read.work.get(0);
            check.same("submission types survive", Assignment.TEXT, item.kinds.get(0));
            check.same("the quiz survives", "4", item.quizId);
            check.ok("returned survives", item.returned);
            check.same("the grade survives", "B", item.grade);
            check.same("attempts survive", 3, item.attemptsAllowed);
            check.same("courses survive", "BIO 1", read.courses.get(0).code);
            check.same("their colour", 2, read.courses.get(0).color);
            check.same("check marks survive", "300", read.overrides.get("91"));
            check.ok("and whether they are ticked", read.checked.contains("91"));

            Draft draft = Draft.empty("7:91").with(Draft.Piece.text("t", "A", "words"), 1).queued(true, 2);
            store.saveDraft(draft);
            check.same("a draft comes back", "words", store.draft("7:91").pieces.get(0).text);
            check.same("a queued one is found", 1, store.queued().size());
            new File(store.draftFolder(draft), "a.m4a").createNewFile();
            store.dropDraft("7:91");
            check.ok("dropping it removes its files", !new File(new File(directory, "drafts"), "7-91").exists());
            check.same("and its record", 0, store.draft("7:91").pieces.size());

            ClassData data = new ClassData(new ArrayList<ClassData.Post>(), new ArrayList<ClassData.Topic>(),
                Arrays.asList(new ClassData.Person("Ann", "Teacher")), 3);
            store.saveClassData("7", data);
            check.same("a class's page is kept", "Ann", store.classData("7").people.get(0).name);
            store.clear();
            check.same("signing out forgets classes", null, store.classData("7"));
        } finally {
            delete(directory);
        }
    }

    private static java.util.Map<String, Object> course(String json) {
        return Json.object(Json.parse(json));
    }

    private static void semester(Check check) {
        List<java.util.Map<String, Object>> all = new ArrayList<>();
        all.add(course("{\"id\":1,\"name\":\"THF 101: Acting (2026 Fall C)\",\"term\":{\"name\":\"2026 Fall C\"}}"));
        all.add(course("{\"id\":2,\"name\":\"UNI 220: Mindset\",\"term\":{\"name\":\"2026 Fall DYN\"}}"));
        all.add(course("{\"id\":3,\"name\":\"ENG 105 (2026 Spring C)\",\"term\":{\"name\":\"2026 Spring C\"}}"));
        all.add(course("{\"id\":4,\"name\":\"CSE 205 (2024 Fall C)\",\"term\":{\"name\":\"2024 Fall C\"}}"));
        all.add(course("{\"id\":5,\"name\":\"Pitchfork Pantry Fall 2026\",\"term\":{\"name\":\"2025 ORG\"}}"));
        all.add(course("{\"id\":6,\"name\":\"ASU Ready\",\"term\":{\"name\":\"2025 TRN\"}}"));
        all.add(course("{\"id\":7,\"name\":\"THE 125 (2026 Fall A)\"}"));
        List<java.util.Map<String, Object>> fall = Terms.current(all, at(Calendar.OCTOBER, 17, 12, 0));
        check.same("this fall's classes only", 3, fall.size());
        check.same("in order", 1, ((Number) fall.get(0).get("id")).intValue());
        check.same("a term in the course's own name counts", 7, ((Number) fall.get(2).get("id")).intValue());
        List<java.util.Map<String, Object>> spring = Terms.current(all, at(Calendar.FEBRUARY, 3, 12, 0));
        check.same("in February, spring's", 1, spring.size());
        List<java.util.Map<String, Object>> other = new ArrayList<>();
        other.add(course("{\"id\":8,\"name\":\"Biology\",\"end_at\":\"2020-01-01T00:00:00Z\"}"));
        other.add(course("{\"id\":9,\"name\":\"Chemistry\"}"));
        List<java.util.Map<String, Object>> kept = Terms.current(other, at(Calendar.OCTOBER, 17, 12, 0));
        check.same("without season names, ended classes go", 1, kept.size());
        check.same("and undated ones stay", 9, ((Number) kept.get(0).get("id")).intValue());

        List<Course> saved = new ArrayList<>();
        saved.add(new Course("1", "Acting", "THF 101", "2026 Fall C", new ArrayList<String>(), 0, null, null));
        saved.add(new Course("4", "Java", "CSE 205", "2024 Fall C", new ArrayList<String>(), 1, null, null));
        List<Assignment> work = new ArrayList<>();
        work.add(new Assignment("1", "10", "Now", "Acting", null, 0, false, false, null, null, false));
        work.add(new Assignment("4", "11", "Old", "Java", null, 0, false, false, null, null, false));
        Sync.Result trimmed = Terms.current(new Sync.Result(work, saved, new ArrayList<String>(), 1),
            at(Calendar.OCTOBER, 17, 12, 0));
        check.same("a saved sync keeps this semester's classes", 1, trimmed.courses.size());
        check.same("and only their work", "Now", trimmed.work.get(0).title);
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
