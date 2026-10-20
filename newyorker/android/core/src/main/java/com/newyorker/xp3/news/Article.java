package com.newyorker.xp3.news;

import com.newyorker.xp3.html.Html;
import com.newyorker.xp3.html.Node;
import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An article as it is read: its header, then the body as a list of blocks.
 *
 * The page renders all of it into HTML, so no script is needed. The header's
 * parts carry data-testid attributes (ContentHeaderHed, ContentHeaderRubric,
 * BylinesWrapper, ContentHeaderPublishDate, ContentHeaderLeadAsset), and the
 * body is every "body__inner-container" in order, the page having cut it into
 * several around its advertisements. Inside, paragraphs, headings, quotes,
 * lists and pictures become blocks; promotions, newsletter sign-ups and
 * whatever else the body holds that is not the article are dropped.
 */
public final class Article {

    public final String url;
    public final String rubric;
    public final String hed;
    public final String dek;
    public final String byline;
    public final String date;
    /** At the phone's size, or "". */
    public final String image;
    public final String caption;
    public final List<Block> blocks;

    public Article(String url, String rubric, String hed, String dek, String byline, String date,
                   String image, String caption, List<Block> blocks) {
        this.url = url;
        this.rubric = Story.orEmpty(rubric);
        this.hed = Story.orEmpty(hed);
        this.dek = Story.orEmpty(dek);
        this.byline = Story.orEmpty(byline);
        this.date = Story.orEmpty(date);
        this.image = Story.orEmpty(image);
        this.caption = Story.orEmpty(caption);
        this.blocks = blocks;
    }

    /** The card a list shows for it, as in Saved. */
    public Story story() {
        return new Story(url, rubric, hed, dek, byline, image);
    }

    /** Whether the page held an article at all; a page that changed shape holds none. */
    public boolean hasBody() {
        for (Block block : blocks) if (block.kind == Block.PARAGRAPH) return true;
        return false;
    }

    public static Article parse(String url, String html, int imageWidth) {
        Node root = Html.parse(html);
        Node hed = root.first(node -> node.attr("data-testid").equals("ContentHeaderHed"));
        if (hed == null) hed = root.first(node -> node.is("h1"));
        Node header = hed == null ? null : enclosing(hed, "header");
        Node scope = header != null ? header : root;

        Node rubricBox = scope.first(node -> node.attr("data-testid").equals("ContentHeaderRubric"));
        Node rubric = (rubricBox != null ? rubricBox : scope).first(node -> node.hasClass("rubric__name"));
        Node dek = scope.first(node -> node.classContains("ContentHeaderDek"));
        Node bylines = scope.first(node -> node.attr("data-testid").equals("BylinesWrapper"));
        Node date = scope.first(node -> node.attr("data-testid").equals("ContentHeaderPublishDate"));
        Node lead = scope.first(node -> node.attr("data-testid").equals("ContentHeaderLeadAsset"));
        Node leadImage = lead == null ? null : picture(lead, imageWidth);
        String caption = header == null || lead == null ? "" : caption(header);

        List<Block> blocks = new ArrayList<Block>();
        for (Node body : root.all(node -> node.hasClass("body__inner-container"))) {
            Blocks.read(body, blocks, imageWidth);
        }
        return new Article(url, rubric == null ? "" : rubric.text(), hed == null ? "" : hed.text(),
            dek == null ? "" : dek.text(), bylines == null ? "" : Feed.byline(bylines),
            date == null ? "" : date.text(),
            leadImage == null ? "" : Site.picture(leadImage.attr("src"), imageWidth), caption, blocks);
    }

    private static Node enclosing(Node node, String tag) {
        for (Node at = node.parent(); at != null; at = at.parent()) if (at.is(tag)) return at;
        return null;
    }

    static Node picture(Node within, int imageWidth) {
        return within.first(node -> node.is("img") && Site.picture(node.attr("src"), imageWidth) != null);
    }

    /** A picture's caption and credit, as one line: "Caption. Photograph by X". */
    static String caption(Node within) {
        Node text = within.first(node -> node.hasClass("caption__text") || node.hasClass("responsive-cartoon__caption"));
        Node credit = within.first(node -> node.hasClass("caption__credit") || node.hasClass("responsive-cartoon__credit"));
        String a = text == null ? "" : text.text();
        String b = credit == null ? "" : credit.text();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " " + b;
    }

    public String toJson() {
        List<Object> list = new ArrayList<Object>();
        for (Block block : blocks) list.add(block.toJson());
        return Json.write(Json.map("url", url, "rubric", rubric, "hed", hed, "dek", dek, "byline", byline,
            "date", date, "image", image, "caption", caption, "blocks", list));
    }

    public static Article fromJson(String text) {
        Map<String, Object> json = Json.object(Json.parse(text));
        List<Block> blocks = new ArrayList<Block>();
        for (Object each : Json.children(json, "blocks")) blocks.add(Block.fromJson(Json.object(each)));
        return new Article(Json.string(json, "url"), Json.text(json, "rubric"), Json.text(json, "hed"),
            Json.text(json, "dek"), Json.text(json, "byline"), Json.text(json, "date"),
            Json.text(json, "image"), Json.text(json, "caption"), blocks);
    }
}
