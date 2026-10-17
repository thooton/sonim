package com.canvas.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One class's Stream, Classwork and People, as the class page shows them.
 *
 * Fetched when a class is opened rather than with every sync: a term's worth
 * of modules and announcements across five courses is most of a minute on
 * this phone's network, for screens most launches never open. Like
 * everything else it is kept once fetched, so a class read once reads again
 * with no signal.
 */
public final class ClassData {

    /** An announcement. */
    public static final class Post {
        public final String id;
        public final String title;
        public final String author;
        public final long at;
        /** The message, as Markdown. */
        public final String body;
        public final int replies;
        /** False when the teacher has turned replies off. */
        public final boolean open;

        public Post(String id, String title, String author, long at, String body, int replies, boolean open) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.at = at;
            this.body = body;
            this.replies = replies;
            this.open = open;
        }
    }

    /** A module: one of the teacher's topics, and what is in it. */
    public static final class Topic {
        public final String name;
        public final List<Item> items;

        public Topic(String name, List<Item> items) {
            this.name = name;
            this.items = items;
        }
    }

    /** One thing in a module. */
    public static final class Item {
        public static final String ASSIGNMENT = "Assignment";
        public static final String QUIZ = "Quiz";
        public static final String DISCUSSION = "Discussion";
        public static final String PAGE = "Page";
        public static final String FILE = "File";
        public static final String LINK = "ExternalUrl";
        public static final String TOOL = "ExternalTool";
        public static final String HEADER = "SubHeader";

        public final String type;
        public final String title;
        /** The assignment, quiz, page, file or topic id the item stands for. */
        public final String contentId;
        public final String url;
        public final Long due;

        public Item(String type, String title, String contentId, String url, Long due) {
            this.type = type;
            this.title = title;
            this.contentId = contentId;
            this.url = url;
            this.due = due;
        }
    }

    /** A teacher, assistant or classmate. */
    public static final class Person {
        public final String name;
        /** "Teacher", "TA" or "Student". */
        public final String role;

        public Person(String name, String role) {
            this.name = name;
            this.role = role;
        }
    }

    public final List<Post> posts;
    public final List<Topic> topics;
    public final List<Person> people;
    public final long fetchedAt;

    public ClassData(List<Post> posts, List<Topic> topics, List<Person> people, long fetchedAt) {
        this.posts = posts;
        this.topics = topics;
        this.people = people;
        this.fetchedAt = fetchedAt;
    }

    public static ClassData empty() {
        return new ClassData(new ArrayList<Post>(), new ArrayList<Topic>(), new ArrayList<Person>(), 0);
    }

    // ------------------------------------------------------------------
    // From Canvas
    // ------------------------------------------------------------------

    public static List<Post> posts(List<Object> records) {
        List<Post> posts = new ArrayList<>();
        for (Object entry : records) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> record = Json.object(entry);
            String id = id(record, "id");
            Long at = Sync.time(Json.text(record, "posted_at"));
            if (id == null || at == null) continue;
            String title = Json.text(record, "title");
            Double replies = Json.number(record, "discussion_subentry_count");
            posts.add(new Post(id, title == null ? "Announcement" : title.trim(),
                String.valueOf(Json.text(record, "user_name")), at.longValue(),
                Html.toMarkdown(Json.text(record, "message")), replies == null ? 0 : (int) replies.doubleValue(),
                !Json.flag(record, "locked")));
        }
        return posts;
    }

    public static List<Topic> topics(List<Object> records) {
        List<Topic> topics = new ArrayList<>();
        for (Object entry : records) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> module = Json.object(entry);
            List<Item> items = new ArrayList<>();
            for (Object raw : Json.children(module, "items")) {
                if (!(raw instanceof Map)) continue;
                Map<String, Object> item = Json.object(raw);
                if (item.containsKey("published") && !Json.flag(item, "published")) continue;
                String type = Json.text(item, "type");
                String title = Json.text(item, "title");
                if (type == null || title == null) continue;
                String content = id(item, "content_id");
                if (Item.PAGE.equals(type) && content == null) content = Json.text(item, "page_url");
                Long due = Sync.time(Json.text(Json.child(item, "content_details"), "due_at"));
                items.add(new Item(type, title.trim(), content, Json.text(item, "external_url"), due));
            }
            String name = Json.text(module, "name");
            topics.add(new Topic(name == null ? "Module" : name.trim(), items));
        }
        return topics;
    }

    /** Teachers first, then assistants, then classmates, each by name. */
    public static List<Person> people(List<Object> records) {
        List<Person> teachers = new ArrayList<>();
        List<Person> assistants = new ArrayList<>();
        List<Person> students = new ArrayList<>();
        for (Object entry : records) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> record = Json.object(entry);
            String name = Json.text(record, "name");
            if (name == null) continue;
            String role = "Student";
            for (Object e : Json.children(record, "enrollments")) {
                if (!(e instanceof Map)) continue;
                String type = Json.text(Json.object(e), "type");
                if ("TeacherEnrollment".equals(type)) role = "Teacher";
                else if ("TaEnrollment".equals(type) && !"Teacher".equals(role)) role = "TA";
            }
            Person person = new Person(name.trim(), role);
            if ("Teacher".equals(role)) teachers.add(person);
            else if ("TA".equals(role)) assistants.add(person);
            else students.add(person);
        }
        List<Person> all = new ArrayList<>(teachers);
        all.addAll(assistants);
        all.addAll(students);
        return all;
    }

    /** The replies under a post, oldest first, as plain lines of text. */
    public static List<Detail.Comment> replies(List<Object> entries) {
        List<Detail.Comment> out = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> record = Json.object(entry);
            if (Json.flag(record, "deleted")) continue;
            Long at = Sync.time(Json.text(record, "created_at"));
            if (at == null) continue;
            String name = Json.text(record, "user_name");
            String text = Html.toMarkdown(Json.text(record, "message")).trim();
            out.add(new Detail.Comment(name == null ? "Classmate" : name, false, text, at.longValue()));
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    private static String id(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0) return null;
        return Long.toString(((Number) value).longValue());
    }

    // ------------------------------------------------------------------
    // Written down
    // ------------------------------------------------------------------

    Map<String, Object> toMap() {
        List<Object> p = new ArrayList<>();
        for (Post post : posts) {
            p.add(Json.map("id", post.id, "title", post.title, "author", post.author, "at", Long.valueOf(post.at),
                "body", post.body, "replies", Integer.valueOf(post.replies), "open", Boolean.valueOf(post.open)));
        }
        List<Object> t = new ArrayList<>();
        for (Topic topic : topics) {
            List<Object> items = new ArrayList<>();
            for (Item item : topic.items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", item.type);
                m.put("title", item.title);
                m.put("contentId", item.contentId);
                m.put("url", item.url);
                m.put("due", item.due);
                items.add(m);
            }
            t.add(Json.map("name", topic.name, "items", items));
        }
        List<Object> people = new ArrayList<>();
        for (Person person : this.people) people.add(Json.map("name", person.name, "role", person.role));
        return Json.map("posts", p, "topics", t, "people", people, "fetchedAt", Long.valueOf(fetchedAt));
    }

    static ClassData fromMap(Map<String, Object> map) {
        List<Post> posts = new ArrayList<>();
        for (Object entry : Json.children(map, "posts")) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> m = Json.object(entry);
            Double at = Json.number(m, "at");
            Double replies = Json.number(m, "replies");
            String id = Json.text(m, "id");
            if (at == null || id == null) continue;
            posts.add(new Post(id, String.valueOf(Json.text(m, "title")), String.valueOf(Json.text(m, "author")),
                (long) at.doubleValue(), String.valueOf(Json.text(m, "body")),
                replies == null ? 0 : (int) replies.doubleValue(), Json.flag(m, "open")));
        }
        List<Topic> topics = new ArrayList<>();
        for (Object entry : Json.children(map, "topics")) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> m = Json.object(entry);
            List<Item> items = new ArrayList<>();
            for (Object raw : Json.children(m, "items")) {
                if (!(raw instanceof Map)) continue;
                Map<String, Object> i = Json.object(raw);
                Double due = Json.number(i, "due");
                String type = Json.text(i, "type");
                String title = Json.text(i, "title");
                if (type == null || title == null) continue;
                items.add(new Item(type, title, Json.text(i, "contentId"), Json.text(i, "url"),
                    due == null ? null : Long.valueOf((long) due.doubleValue())));
            }
            topics.add(new Topic(String.valueOf(Json.text(m, "name")), items));
        }
        List<Person> people = new ArrayList<>();
        for (Object entry : Json.children(map, "people")) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> m = Json.object(entry);
            String name = Json.text(m, "name");
            if (name != null) people.add(new Person(name, String.valueOf(Json.text(m, "role"))));
        }
        Double at = Json.number(map, "fetchedAt");
        return new ClassData(posts, topics, people, at == null ? 0 : (long) at.doubleValue());
    }
}
