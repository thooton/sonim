package com.newyorker.xp3;

import com.newyorker.xp3.html.Html;
import com.newyorker.xp3.html.Node;
import com.newyorker.xp3.news.Article;
import com.newyorker.xp3.news.Block;
import com.newyorker.xp3.news.Feed;
import com.newyorker.xp3.news.Saved;
import com.newyorker.xp3.news.Site;
import com.newyorker.xp3.news.Story;
import com.xp3.common.Check;

import java.io.File;
import java.nio.file.Files;

final class NewsTests {

    private NewsTests() {}

    static void run(Check check) throws Exception {
        check.run("html", () -> html(check));
        check.run("site", () -> site(check));
        check.run("feed", () -> feed(check));
        check.run("article", () -> article(check));
        check.run("saved", () -> saved(check));
    }

    private static void html(Check check) {
        Node root = Html.parse("<!doctype html><html><head><title>x</title><script>if (a<b) {}</script></head>"
            + "<body><div class=\"a b\"><p>One &amp; <em>two</em>&#8212;three&nbsp;four<p>Five</div>"
            + "<svg><svg></svg><text>gone</text></svg><img src=x><br/>tail</span>&unknown; &#x2019;</body>");
        Node div = root.first(n -> n.hasClass("b"));
        check.ok("class", div != null && div.hasClass("a") && !div.hasClass("c"));
        check.same("paragraphs close each other", 2, div.all(n -> n.is("p")).size());
        check.same("entities", "One & two—three four", div.first(n -> n.is("p")).text());
        check.same("script, svg and head dropped", "One & two—three four Five tail&unknown; ’", root.text());
        check.ok("void element has no children", root.first(n -> n.is("img")).children().isEmpty());
        Node unclosed = Html.parse("<ul><li>a<li>b</ul><p>after");
        check.same("list items close each other", 2, unclosed.first(n -> n.is("ul")).children().size());
        check.same("attribute forms", "y", Html.parse("<a data-x=y href='z' hidden>").first(n -> n.is("a")).attr("data-x"));
        check.same("truncated page", "cut", Html.parse("<p>cut<a href=\"").text());
    }

    private static void site(Check check) {
        check.same("relative article", "https://www.newyorker.com/news/the-lede/a-story",
            Site.article("/news/the-lede/a-story/?utm=1#top"));
        check.same("absolute article", "https://www.newyorker.com/magazine/2026/10/26/a-piece",
            Site.article("https://www.newyorker.com/magazine/2026/10/26/a-piece"));
        check.same("section front", null, Site.article("/culture"));
        check.same("issue page", null, Site.article("/magazine/2026/10/26"));
        check.same("podcast", null, Site.article("/podcast/political-scene/an-episode"));
        check.same("puzzle", null, Site.article("/puzzles-and-games-dept/crossword/2026/10/20"));
        check.same("elsewhere", null, Site.article("https://example.com/news/a/b"));
        check.same("picture resized", "https://media.newyorker.com/photos/abc/4:3/w_320%2Cc_limit/x.jpg",
            Site.picture("https://media.newyorker.com/photos/abc/4:3/w_1600%2Cc_limit/x.jpg", 320));
        check.same("svg is no picture", null, Site.picture("/verso/static/logo.svg", 320));
        check.same("issue date", "October 5, 2026", Site.issueDate("https://www.newyorker.com/magazine/2026/10/05/x"));
    }

    private static final String FRONT =
        "<main><div class=\"smart-item\"><a class=\"Hed smart-item__hed-link\" href=\"/news/the-lede/lead-story\">"
        + "<h3 class=\"smart-item__hed\">Lead <em>Story</em></h3></a><p class=\"smart-item__dek\">A dek.</p>"
        + "<img src=\"https://media.newyorker.com/photos/1/master/w_1600%2Cc_limit/a.jpg\"></div>"
        + "<h2 class=\"X smart-container__header__hed\">Today’s Mix</h2>"
        + "<div class=\"smart-item story-unit\"><div class=\"RubricWrapper smart-item__rubric\"><span class=\"rubric__name\"><span>The Lede</span></span></div>"
        + "<a class=\"smart-item__hed-link\" href=\"/news/the-lede/second\"><h3 class=\"smart-item__hed\">Second</h3></a>"
        + "<div class=\"bylines smart-item__contributor\"><span class=\"byline__name\"><span class=\"byline__preamble\">By </span>Ann Writer</span>"
        + "<span class=\"byline__name\">Bo Writer</span></div></div>"
        + "<div class=\"smart-item\"><a class=\"smart-item__hed-link\" href=\"/news/the-lede/third\"><h3 class=\"smart-item__hed\">Third</h3></a>"
        + "<div class=\"bylines\"><span class=\"byline__name\">By A One,</span><span class=\"byline__name\">B Two,</span>"
        + "<span class=\"byline__name\">and C Three</span></div></div>"
        + "<div class=\"smart-item\"><a class=\"smart-item__hed-link\" href=\"/news/the-lede/lead-story\"><h3 class=\"smart-item__hed\">Lead again</h3></a></div>"
        + "<div class=\"smart-item\"><a class=\"smart-item__hed-link\" href=\"/podcast/x/y\"><h3 class=\"smart-item__hed\">A podcast</h3></a></div>"
        + "<h2 class=\"smart-container__header__hed\">Puzzles &amp; Games</h2>"
        + "<div class=\"smart-item\"><a class=\"smart-item__hed-link\" href=\"/puzzles-and-games-dept/crossword\"><h3 class=\"smart-item__hed\">Crossword</h3></a></div>"
        + "<h2 class=\"SectionTitleHed-abc\">Fiction</h2>"
        + "<div class=\"summary-item summary-item--article\"><a class=\"summary-item__hed-link\" href=\"/magazine/2026/10/26/a-story\">"
        + "<h3 data-testid=\"SummaryItemHed\" class=\"summary-item__hed\">A Story</h3></a></div></main>";

    private static void feed(Check check) {
        Feed feed = Feed.parse(FRONT, 320);
        check.same("sections, the empty one dropped", 3, feed.sections.size());
        check.same("the lead has no title", "", feed.sections.get(0).title);
        Story lead = feed.sections.get(0).stories.get(0);
        check.same("hed", "Lead Story", lead.hed);
        check.same("dek", "A dek.", lead.dek);
        check.same("image", "https://media.newyorker.com/photos/1/master/w_320%2Cc_limit/a.jpg", lead.image);
        check.same("section title", "Today’s Mix", feed.sections.get(1).title);
        check.same("duplicates and podcasts left out", 2, feed.sections.get(1).stories.size());
        check.same("the site's own commas", "By A One, B Two, and C Three", feed.sections.get(1).stories.get(1).byline);
        Story second = feed.sections.get(1).stories.get(0);
        check.same("rubric", "The Lede", second.rubric);
        check.same("byline", "By Ann Writer and Bo Writer", second.byline);
        check.same("issue section", "Fiction", feed.sections.get(2).title);
        check.same("issue date", "October 26, 2026", feed.title);
        Feed back = Feed.fromJson(feed.toJson());
        check.same("round trip", second.byline, back.sections.get(1).stories.get(0).byline);
        check.same("round trip title", feed.title, back.title);
    }

    private static final String PAGE =
        "<header><div data-testid=\"ContentHeaderRubric\"><a class=\"rubric__link\"><span class=\"rubric__name\">Annals of Tests</span></a></div>"
        + "<h1 data-testid=\"ContentHeaderHed\" class=\"ContentHeaderHed-x\">The Title</h1>"
        + "<div class=\"BaseText SplitScreenContentHeaderDek-juw\">The dek.</div>"
        + "<div data-testid=\"BylinesWrapper\"><span class=\"byline__name\"><span>By </span><a>Pat Author</a></span></div>"
        + "<time data-testid=\"ContentHeaderPublishDate\">October 16, 2026</time>"
        + "<div data-testid=\"ContentHeaderLeadAsset\"><picture><img src=\"https://media.newyorker.com/photos/2/master/w_1600,c_limit/b.jpg\"></picture></div>"
        + "<div class=\"caption\"><span class=\"caption__credit\">Illustration by Someone</span></div></header>"
        + "<div class=\"body__inner-container\"><div class=\"ExternalLinkEmbedWrapper\"><a>You’re reading a column</a></div>"
        + "<p class=\"has-dropcap has-dropcap__lead-standard-heading\">First <em>words</em> here,\n  and <a href=\"/x\">a link</a>.</p>"
        + "<div class=\"consumer-marketing-unit\"><p>Subscribe now</p></div>"
        + "<h2>A Heading</h2></div>"
        + "<div class=\"body__inner-container\"><figure class=\"asset-embed\"><img src=\"https://media.newyorker.com/photos/3/master/w_1600%2Cc_limit/c.jpg\">"
        + "<span class=\"caption__text\">A caption.</span> <span class=\"caption__credit\">Photo by X</span></figure>"
        + "<blockquote><p>Quoted.</p></blockquote><ul><li>One</li><li>Two<br>lines</li></ul><hr>"
        + "<p>Last <strong>bold</strong> line.</p></div>";

    private static void article(Check check) {
        Article article = Article.parse("https://www.newyorker.com/a/b", PAGE, 320);
        check.same("rubric", "Annals of Tests", article.rubric);
        check.same("hed", "The Title", article.hed);
        check.same("dek", "The dek.", article.dek);
        check.same("byline", "By Pat Author", article.byline);
        check.same("date", "October 16, 2026", article.date);
        check.same("lead image", "https://media.newyorker.com/photos/2/master/w_320,c_limit/b.jpg", article.image);
        check.same("lead credit", "Illustration by Someone", article.caption);
        String[] kinds = new String[article.blocks.size()];
        for (int i = 0; i < kinds.length; i++) kinds[i] = String.valueOf(article.blocks.get(i).kind);
        check.same("blocks, promotions dropped", "0,1,4,2,3,3,5,0", String.join(",", kinds));
        Block first = article.blocks.get(0);
        check.ok("drop cap", first.dropCap);
        check.same("white space collapsed across runs", "First words here, and a link.", first.text());
        check.same("italic run, the space after it its own", "words ", first.runs.get(1).text);
        check.same("italic style", Block.Run.ITALIC, first.runs.get(1).style);
        check.same("picture", "https://media.newyorker.com/photos/3/master/w_320%2Cc_limit/c.jpg", article.blocks.get(2).extra);
        check.same("picture caption", "A caption. Photo by X", article.blocks.get(2).caption);
        check.same("list marker", "•", article.blocks.get(4).extra);
        check.same("line break kept", "Two\nlines", article.blocks.get(5).text());
        check.ok("has a body", article.hasBody());
        Article back = Article.fromJson(article.toJson());
        check.same("round trip", article.blocks.get(7).text(), back.blocks.get(7).text());
        check.same("round trip style", Block.Run.BOLD, back.blocks.get(7).runs.get(1).style);
        check.ok("round trip drop cap", back.blocks.get(0).dropCap);
        check.ok("a page of something else has no body", !Article.parse("u", "<p>hi</p>", 320).hasBody());
    }

    private static void saved(Check check) throws Exception {
        File folder = Files.createTempDirectory("saved").toFile();
        Saved saved = new Saved(folder);
        Article one = Article.parse("https://www.newyorker.com/a/one", PAGE, 320);
        Article two = Article.parse("https://www.newyorker.com/a/two", PAGE, 320);
        saved.add(one);
        saved.add(two);
        check.same("newest first", "https://www.newyorker.com/a/two", saved.stories().get(0).url);
        Saved again = new Saved(folder);
        check.same("kept on disk", 2, again.stories().size());
        check.same("article kept whole", one.blocks.size(), again.article(one.url).blocks.size());
        again.remove(one.url);
        check.ok("removed", !new Saved(folder).contains(one.url));
        check.ok("its file removed", again.article(one.url) == null);
    }
}
