package com.newyorker.xp3.news;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One piece of an article's body: a paragraph, a heading, a quote, a list item, a picture or a break. */
public final class Block {

    public static final int PARAGRAPH = 0;
    public static final int HEADING = 1;
    public static final int QUOTE = 2;
    public static final int ITEM = 3;
    public static final int PICTURE = 4;
    /** The space between sections of a long piece. */
    public static final int BREAK = 5;

    public final int kind;
    public final List<Run> runs;
    /** A paragraph that opens a section, which the magazine sets with a large initial. */
    public final boolean dropCap;
    /** A picture's address at the phone's size, for PICTURE; or a list item's marker ("•", "3."). */
    public final String extra;
    /** A picture's caption and credit. */
    public final String caption;

    public Block(int kind, List<Run> runs, boolean dropCap, String extra, String caption) {
        this.kind = kind;
        this.runs = runs;
        this.dropCap = dropCap;
        this.extra = Story.orEmpty(extra);
        this.caption = Story.orEmpty(caption);
    }

    public String text() {
        StringBuilder out = new StringBuilder();
        for (Run run : runs) out.append(run.text);
        return out.toString();
    }

    Map<String, Object> toJson() {
        List<Object> list = new ArrayList<Object>();
        for (Run run : runs) list.add(Json.list(run.text, (long) run.style));
        return Json.map("kind", (long) kind, "runs", list, "dropCap", dropCap, "extra", extra, "caption", caption);
    }

    static Block fromJson(Map<String, Object> json) {
        List<Run> runs = new ArrayList<Run>();
        for (Object each : Json.children(json, "runs")) {
            List<Object> pair = Json.array(each);
            runs.add(new Run((String) pair.get(0), ((Number) pair.get(1)).intValue()));
        }
        Double kind = Json.number(json, "kind");
        return new Block(kind == null ? PARAGRAPH : kind.intValue(), runs, Json.flag(json, "dropCap"),
            Json.text(json, "extra"), Json.text(json, "caption"));
    }

    /** Text in one style. */
    public static final class Run {
        public static final int ITALIC = 1;
        public static final int BOLD = 2;

        public final String text;
        public final int style;

        public Run(String text, int style) {
            this.text = text;
            this.style = style;
        }
    }
}
