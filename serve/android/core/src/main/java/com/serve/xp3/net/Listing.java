package com.serve.xp3.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The folder listing and the error page, in the look of `npx serve`
 * (serve-handler's directory and error templates): "Index of" with a
 * breadcrumb, then a plain list with ".." first, folders before files, a
 * line icon before each, and the columns that template wraps into on a wide
 * screen.
 */
public final class Listing {

    /** One name in a folder. */
    public static final class Entry {
        final String name;
        final boolean folder;

        public Entry(String name, boolean folder) {
            this.name = name;
            this.folder = folder;
        }
    }

    private static final String FONT = "-apple-system, BlinkMacSystemFont, \"Segoe UI\", \"Roboto\", \"Oxygen\", "
        + "\"Ubuntu\", \"Cantarell\", \"Fira Sans\", \"Droid Sans\", \"Helvetica Neue\", sans-serif";

    private static final String FILE_ICON = "data:image/svg+xml;utf8,<svg width='15' height='19' fill='none' "
        + "xmlns='http://www.w3.org/2000/svg'><path d='M1.5 3A1.5 1.5 0 0 1 3 1.5h5.6l4.9 4.9V16a1.5 1.5 0 0 1-1.5 "
        + "1.5H3A1.5 1.5 0 0 1 1.5 16zM8.5 1.5V5a1.5 1.5 0 0 0 1.5 1.5h3.5' stroke='black'/></svg>";

    private static final String FOLDER_ICON = "data:image/svg+xml;utf8,<svg width='20' height='16' fill='none' "
        + "xmlns='http://www.w3.org/2000/svg'><path d='M1.5 4V2a1 1 0 0 1 1-1h4.7l1.4 1.3h8.4a1 1 0 0 1 1 1V4' "
        + "stroke='black'/><path d='M1.4 4.5h17.2a.5.5 0 0 1 .5.5l-.6 9.6a1 1 0 0 1-1 .9H2.5a1 1 0 0 1-1-.9L.9 5a.5.5 "
        + "0 0 1 .5-.5z' stroke='black'/></svg>";

    private static final String IMAGE_ICON = "data:image/svg+xml;utf8,<svg width='16' height='16' fill='none' "
        + "xmlns='http://www.w3.org/2000/svg'><rect x='.5' y='.5' width='15' height='15' rx='2' stroke='black'/>"
        + "<circle cx='5' cy='5' r='1.5' stroke='black'/><path d='M.5 12.5l4.5-4.5 3 3 2.5-2.5 5 5' "
        + "stroke='black'/></svg>";

    private static final String STYLE = ""
        + "body{margin:0;padding:30px;background:#fff;font-family:" + FONT + ";-webkit-font-smoothing:antialiased}"
        + "main{max-width:920px}"
        + "header{display:flex;justify-content:space-between;flex-wrap:wrap}"
        + "h1{font-size:18px;font-weight:500;margin-top:0;color:#000}"
        + "header h1 a{font-size:18px;font-weight:500;margin-top:0;color:#000}"
        + "h1 i{font-style:normal}"
        + "ul{margin:0 0 0 -2px;padding:20px 0 0 0}"
        + "ul li{list-style:none;font-size:14px;display:flex;justify-content:space-between}"
        + "a{text-decoration:none}"
        + "ul a{color:#000;padding:10px 5px;margin:0 -5px;white-space:nowrap;overflow:hidden;display:block;"
        + "width:100%;text-overflow:ellipsis}"
        + "ul a::before{display:inline-block;vertical-align:middle;margin-right:10px;width:24px;"
        + "text-align:center;line-height:12px}"
        + "ul a.file::before{content:url(\"" + FILE_ICON + "\")}"
        + "ul a:hover{text-decoration:underline}"
        + "ul a.folder::before{content:url(\"" + FOLDER_ICON + "\")}"
        + "ul a.file.gif::before,ul a.file.jpg::before,ul a.file.jpeg::before,ul a.file.png::before,"
        + "ul a.file.webp::before,ul a.file.svg::before{content:url(\"" + IMAGE_ICON + "\")}"
        + "::selection{background-color:#79FFE1;color:#000}"
        + "::-moz-selection{background-color:#79FFE1;color:#000}"
        + "@media (min-width:768px){ul{display:flex;flex-wrap:wrap}ul li{width:230px;padding-right:20px}}"
        + "@media (min-width:992px){body{padding:45px}h1,header h1 a{font-size:15px}"
        + "ul li{font-size:13px;box-sizing:border-box;justify-content:flex-start}}";

    private static final String ERROR_STYLE = ""
        + "html,body{height:100%}"
        + "body{margin:0;background:#fff;font-family:" + FONT + ";-webkit-font-smoothing:antialiased}"
        + "main,section{display:flex;justify-content:center;align-items:center;flex-direction:column}"
        + "main{height:100%}"
        + "section span{font-size:24px;font-weight:500;display:block;border-bottom:1px solid #EAEAEA;"
        + "text-align:center;padding-bottom:20px;width:100px}"
        + "section p{font-size:14px;font-weight:400}"
        + "section span+p{margin:20px 0 0 0}"
        + "@media (min-width:768px){section{height:40px;flex-direction:row}"
        + "section span,section p{height:100%;line-height:40px}"
        + "section span{border-bottom:0;border-right:1px solid #EAEAEA;padding:0 20px 0 0;width:auto}"
        + "section span+p{margin:0;padding-left:20px}}";

    private Listing() {}

    /** The listing of the folder at this decoded path ("/a/b/"). */
    public static String render(String path, List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, (a, b) -> {
            if (a.folder != b.folder) return a.folder ? -1 : 1;
            int folded = a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
            return folded != 0 ? folded : a.name.compareTo(b.name);
        });

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("<title>Files within ").append(escape(path)).append("</title>\n")
            .append("<style>").append(STYLE).append("</style>\n")
            .append("</head>\n<body>\n<main>\n<header>\n<h1><i>Index of&nbsp;</i>");

        // The breadcrumb: "/" and then each folder on the way down, each a link.
        html.append("<a href=\"/\">/</a>");
        StringBuilder walked = new StringBuilder("/");
        for (String name : path.split("/")) {
            if (name.length() == 0) continue;
            walked.append(name).append('/');
            html.append("<a href=\"").append(escape(Paths.href(walked.toString()))).append("\">")
                .append(escape(name)).append("/</a>");
        }
        html.append("</h1>\n</header>\n<ul id=\"files\">\n");

        if (!"/".equals(path)) {
            String parent = path.substring(0, path.lastIndexOf('/', path.length() - 2) + 1);
            item(html, parent, "..", "..", "folder");
        }
        for (Entry entry : sorted) {
            if (entry.folder) {
                item(html, path + entry.name + "/", entry.name, entry.name + "/", "folder");
            } else {
                int dot = entry.name.lastIndexOf('.');
                String ext = dot <= 0 ? "txt" : entry.name.substring(dot + 1).toLowerCase(Locale.ROOT);
                item(html, path + entry.name, entry.name, entry.name, "file " + ext);
            }
        }
        html.append("</ul>\n</main>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void item(StringBuilder html, String target, String title, String shown, String type) {
        html.append("<li><a href=\"").append(escape(Paths.href(target)))
            .append("\" title=\"").append(escape(title))
            .append("\" class=\"").append(escape(type)).append("\">")
            .append(escape(shown)).append("</a></li>\n");
    }

    /** serve's error page: the code and a sentence, side by side on a wide screen. */
    public static String error(int status, String reason) {
        String sentence = status == 404 ? "The requested path could not be found" : reason;
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "<title>" + escape(reason) + "</title>\n"
            + "<style>" + ERROR_STYLE + "</style>\n"
            + "</head>\n<body>\n<main>\n<section>\n<span>" + status + "</span>\n<p>" + escape(sentence)
            + "</p>\n</section>\n</main>\n</body>\n</html>\n";
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
