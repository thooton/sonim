package com.newyorker.xp3.news;

import com.newyorker.xp3.html.Html;
import com.newyorker.xp3.html.Node;
import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A page of stories in sections: the front page ("Today's Mix", "The Lede",
 * "The Critics"...) or an issue ("Reporting & Essays", "Fiction"...).
 *
 * Both are read the same way. Walking the page in order, a section's title
 * starts a section and every story card after it belongs to it, until the
 * next title. A card is an element whose class is "smart-item" (the front
 * page) or "summary-item" (the issue and the section fronts); inside it the
 * headline, dek, rubric and byline each carry a class of their own. Cards
 * that lead to something other than an article, and a story already shown
 * higher up, are left out, and so is a section left with no stories.
 */
public final class Feed {

    public static final class Section {
        /** "" for the stories above the first title, the front page's lead. */
        public final String title;
        public final List<Story> stories;

        public Section(String title, List<Story> stories) {
            this.title = title;
            this.stories = stories;
        }
    }

    /** What the page says it is, for an issue its date; may be "". */
    public final String title;
    public final List<Section> sections;

    public Feed(String title, List<Section> sections) {
        this.title = Story.orEmpty(title);
        this.sections = sections;
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /** Pictures are asked for at `imageWidth` pixels. */
    public static Feed parse(String html, int imageWidth) {
        Node root = Html.parse(html);
        final List<Section> sections = new ArrayList<Section>();
        final Set<String> seen = new HashSet<String>();
        final String[] current = {""};
        final List<Story>[] stories = newList();
        final String[] issue = {null};
        final int width = imageWidth;
        root.all(node -> {
            if (isSectionTitle(node)) {
                end(sections, current[0], stories[0]);
                current[0] = node.text();
                stories[0] = new ArrayList<Story>();
                return true;
            }
            if (node.hasClass("smart-item") || node.hasClass("summary-item")) {
                Story story = story(node, width);
                if (story != null && seen.add(story.url)) {
                    stories[0].add(story);
                    if (issue[0] == null) issue[0] = Site.issueDate(story.url);
                }
                return true;
            }
            return false;
        });
        end(sections, current[0], stories[0]);
        return new Feed(issue[0], sections);
    }

    @SuppressWarnings("unchecked")
    private static List<Story>[] newList() {
        return new List[] {new ArrayList<Story>()};
    }

    private static void end(List<Section> sections, String title, List<Story> stories) {
        if (!stories.isEmpty()) sections.add(new Section(title, stories));
    }

    private static boolean isSectionTitle(Node node) {
        return node.is("h2")
            && (node.hasClass("smart-container__header__hed") || node.classStartsWith("SectionTitleHed"));
    }

    static Story story(Node card, int imageWidth) {
        Node link = card.first(node -> node.is("a") && node.classEndsWith("__hed-link"));
        if (link == null) link = card.first(node -> node.is("a") && Site.article(node.attr("href")) != null);
        if (link == null) return null;
        String url = Site.article(link.attr("href"));
        if (url == null) return null;
        Node hed = card.first(node -> node.classEndsWith("__hed") || node.attr("data-testid").equals("SummaryItemHed"));
        String headline = hed != null ? hed.text() : link.text();
        if (headline.isEmpty()) return null;
        Node dek = card.first(node -> node.classEndsWith("__dek"));
        Node rubric = card.first(node -> node.hasClass("rubric__name"));
        Node image = card.first(node -> node.is("img") && Site.picture(node.attr("src"), imageWidth) != null);
        return new Story(url, rubric == null ? "" : rubric.text(), headline, dek == null ? "" : dek.text(),
            byline(card), image == null ? "" : Site.picture(image.attr("src"), imageWidth));
    }

    /**
     * "By A", "By A and B", "By A, B, and C", from the names under a byline
     * (each of which may carry its own "By ").
     */
    static String byline(Node within) {
        List<Node> names = within.all(node -> node.hasClass("byline__name"));
        List<String> people = new ArrayList<String>();
        for (Node name : names) {
            // Names may carry the site's own joining: "By A,", "B,", "and C".
            String text = name.text();
            if (text.startsWith("By ")) text = text.substring(3).trim();
            if (text.startsWith("and ")) text = text.substring(4).trim();
            while (text.endsWith(",")) text = text.substring(0, text.length() - 1).trim();
            if (!text.isEmpty() && !people.contains(text)) people.add(text);
        }
        if (people.isEmpty()) return "";
        StringBuilder out = new StringBuilder("By ");
        for (int i = 0; i < people.size(); i++) {
            if (i > 0) out.append(people.size() == 2 ? " and " : i == people.size() - 1 ? ", and " : ", ");
            out.append(people.get(i));
        }
        return out.toString();
    }

    public String toJson() {
        List<Object> list = new ArrayList<Object>();
        for (Section section : sections) {
            List<Object> stories = new ArrayList<Object>();
            for (Story story : section.stories) stories.add(story.toJson());
            list.add(Json.map("title", section.title, "stories", stories));
        }
        return Json.write(Json.map("title", title, "sections", list));
    }

    public static Feed fromJson(String text) {
        Map<String, Object> json = Json.object(Json.parse(text));
        List<Section> sections = new ArrayList<Section>();
        for (Object each : Json.children(json, "sections")) {
            Map<String, Object> section = Json.object(each);
            List<Story> stories = new ArrayList<Story>();
            for (Object story : Json.children(section, "stories")) stories.add(Story.fromJson(Json.object(story)));
            sections.add(new Section(Story.orEmpty(Json.text(section, "title")), stories));
        }
        return new Feed(Json.text(json, "title"), sections);
    }
}
