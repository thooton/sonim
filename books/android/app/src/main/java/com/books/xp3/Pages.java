package com.books.xp3;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AlignmentSpan;
import android.text.style.CharacterStyle;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.UpdateAppearance;

import com.books.xp3.book.Paragraph;
import com.books.xp3.book.Run;
import com.books.xp3.book.Section;

import java.util.ArrayList;
import java.util.List;

import com.xp3.common.ui.FaceSpan;

/**
 * One section of a book laid out for the screen and cut into pages.
 *
 * The section is set as a single StaticLayout, justified and hyphenated, and
 * a page is a run of whole lines that fits the screen's height. Nothing is
 * reflowed per page, so turning a page is only a matter of drawing a
 * different slice of the same layout.
 *
 * Built off the main thread: nothing here touches a View. The paint is the
 * layout's own and is only recoloured afterwards, for Night Shift.
 */
final class Pages {

    /** Space above and below the text block, in pixels; the percentage sits in the bottom one. */
    static final int TOP = 9;
    static final int BOTTOM = 17;

    final Section section;
    final int left;
    private final TextPaint paint;
    private final StaticLayout layout;
    /** The first line of each page. */
    private final int[] starts;
    /** The colour scene breaks are drawn in; set with the text colour. */
    int dimColor;

    private Pages(Section section, TextPaint paint, StaticLayout layout, int[] starts, int left) {
        this.section = section;
        this.paint = paint;
        this.layout = layout;
        this.starts = starts;
        this.left = left;
    }

    int count() {
        return starts.length;
    }

    /** The page that shows `offset`, an offset into the section's text. */
    int pageOf(int offset) {
        int line = layout.getLineForOffset(Math.max(0, Math.min(offset, section.length())));
        int low = 0;
        int high = starts.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (starts[middle] <= line) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    /** The offset in the section's text that `page` starts at. */
    int startOf(int page) {
        return layout.getLineStart(starts[Math.max(0, Math.min(page, starts.length - 1))]);
    }

    void draw(Canvas canvas, int page, int color, int dim) {
        if (page < 0 || page >= starts.length) return;
        paint.setColor(color);
        dimColor = dim;
        int first = starts[page];
        int last = page + 1 < starts.length ? starts[page + 1] : layout.getLineCount();
        int from = layout.getLineTop(first);
        int to = layout.getLineTop(last);
        canvas.save();
        // The clip is the page's own lines, so the layout draws only those.
        canvas.clipRect(0, TOP, canvas.getWidth(), TOP + (to - from));
        canvas.translate(left, TOP - from);
        layout.draw(canvas);
        canvas.restore();
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    // Layout's break and justification constants are LineBreaker's under
    // other names, which lint (reading API 35's annotations) doesn't know.
    @SuppressLint("WrongConstant")
    static Pages build(AssetManager assets, Section section, int font, int size, boolean justify,
                       float spacing, int margin, int width, int height) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Fonts.face(assets, font, 0));
        paint.setTextSize(size);
        paint.setHinting(Paint.HINTING_ON);

        int textWidth = Math.max(40, width - 2 * margin);
        int textHeight = Math.max(40, height - TOP - BOTTOM);
        float line = size * spacing;
        // Line spacing as a fixed multiple of the size, whatever the font's
        // own line height: so "Normal" means the same in every font.
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        int natural = metrics.descent - metrics.ascent;
        int lineAdd = Math.max(-natural / 4, Math.round(line - natural));

        Pages[] self = new Pages[1];
        SpannableString text = styled(assets, section, section.text, font, size, line, paint, self);
        // Justification spreads every line but a paragraph's last, centred
        // ones too: so a centred paragraph's lines are each made a paragraph
        // of their own, the spaces they break at turned into newlines.
        if (justify) {
            String broken = breakCentred(text, section, paint, textWidth);
            if (broken != null) text = styled(assets, section, broken, font, size, line, paint, self);
        }
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(lineAdd, 1f)
            .setBreakStrategy(justify ? Layout.BREAK_STRATEGY_HIGH_QUALITY : Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        if (justify) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        StaticLayout layout = builder.build();

        Pages pages = new Pages(section, paint, layout, paginate(layout, section, textHeight), margin);
        self[0] = pages;
        return pages;
    }

    /** The first line of each page: as many whole lines as fit, headings kept with what follows. */
    static int[] paginate(StaticLayout layout, Section section, int height) {
        List<Integer> starts = new ArrayList<Integer>();
        int lines = layout.getLineCount();
        int start = 0;
        starts.add(Integer.valueOf(0));
        for (int line = 1; line < lines; line++) {
            if (layout.getLineBottom(line) - layout.getLineTop(start) <= height) continue;
            int cut = line;
            // A heading or scene break at the foot of a page goes over with
            // the text it introduces, if the page has anything else on it.
            Paragraph before = paragraphAt(section, layout.getLineStart(line - 1));
            if (before != null && (before.kind == Paragraph.HEADING || before.kind == Paragraph.BREAK)) {
                int headingLine = layout.getLineForOffset(before.start);
                // Headings in a row ("Chapter 1" over its title) move together.
                Paragraph previous = paragraphAt(section, before.start - 1);
                if (previous != null && previous.kind == Paragraph.HEADING && before.kind == Paragraph.HEADING) {
                    headingLine = layout.getLineForOffset(previous.start);
                }
                if (headingLine > start) cut = headingLine;
            }
            starts.add(Integer.valueOf(cut));
            start = cut;
            line = cut;
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i).intValue();
        return out;
    }

    private static Paragraph paragraphAt(Section section, int offset) {
        if (offset < 0) return null;
        List<Paragraph> paragraphs = section.paragraphs;
        int low = 0;
        int high = paragraphs.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (paragraphs.get(middle).start <= offset) low = middle;
            else high = middle - 1;
        }
        return paragraphs.isEmpty() ? null : paragraphs.get(low);
    }

    /**
     * The text with each centred paragraph (headings, scene breaks) broken
     * into lines where an unjustified layout breaks it, or null if none
     * wraps. Space becomes newline, so every offset stays where it was.
     */
    private static String breakCentred(SpannableString text, Section section, TextPaint paint, int width) {
        char[] chars = null;
        for (Paragraph p : section.paragraphs) {
            if (!p.centered && p.kind != Paragraph.HEADING && p.kind != Paragraph.BREAK) continue;
            if (p.end <= p.start) continue;
            CharSequence one = text.subSequence(p.start, p.end);
            StaticLayout measured = StaticLayout.Builder.obtain(one, 0, one.length(), paint, width)
                .setIncludePad(false)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
            for (int l = 0; l < measured.getLineCount() - 1; l++) {
                int end = measured.getLineEnd(l);
                if (end < 1 || one.charAt(end - 1) != ' ') continue;
                if (chars == null) chars = section.text.toCharArray();
                chars[p.start + end - 1] = '\n';
            }
        }
        return chars == null ? null : new String(chars);
    }

    private static SpannableString styled(AssetManager assets, Section section, String raw, int font, int size,
                                          float line, TextPaint paint, Pages[] owner) {
        SpannableString text = new SpannableString(raw);
        int length = raw.length();
        int indent = Math.round(size * 1.25f);
        List<Paragraph> paragraphs = section.paragraphs;

        for (int i = 0; i < paragraphs.size(); i++) {
            Paragraph p = paragraphs.get(i);
            // Paragraph styles run to just past the paragraph's newline.
            int end = Math.min(length, p.end + 1);
            if (p.start >= end) continue;
            int above = p.spaced ? Math.round(line * 0.8f) : 0;
            int below = 0;
            float scale = 1f;
            switch (p.kind) {
                case Paragraph.HEADING: {
                    scale = p.level <= 1 ? 1.4f : p.level == 2 ? 1.28f : p.level == 3 ? 1.15f : 1.05f;
                    text.setSpan(new RelativeSizeSpan(scale), p.start, p.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.setSpan(new FaceSpan(Fonts.face(assets, font, Run.BOLD)), p.start, p.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    boolean follows = i > 0 && paragraphs.get(i - 1).kind == Paragraph.HEADING;
                    above = Math.max(above, follows ? Math.round(line * 0.15f) : Math.round(line * 0.9f));
                    // A chapter opens a little way down its first page, as in print.
                    if (i == 0 && section.opensChapter()) above = Math.round(line * 2.2f);
                    boolean nextHeading = i + 1 < paragraphs.size() && paragraphs.get(i + 1).kind == Paragraph.HEADING;
                    below = nextHeading ? 0 : Math.round(line * 0.7f);
                    break;
                }
                case Paragraph.BREAK:
                    text.setSpan(new DimSpan(owner), p.start, p.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    above = Math.round(line * 0.45f);
                    below = Math.round(line * 0.45f);
                    break;
                case Paragraph.QUOTE:
                    text.setSpan(new LeadingMarginSpan.Standard(indent, indent), p.start, end, Spanned.SPAN_PARAGRAPH);
                    break;
                case Paragraph.ITEM: {
                    int space = raw.indexOf('\u00A0', p.start);
                    int hang = space > p.start && space - p.start < 8
                        ? Math.round(paint.measureText(raw, p.start, space + 1)) : indent;
                    text.setSpan(new LeadingMarginSpan.Standard(indent / 2, indent / 2 + hang), p.start, end, Spanned.SPAN_PARAGRAPH);
                    break;
                }
                case Paragraph.PRE:
                    text.setSpan(new RelativeSizeSpan(0.92f), p.start, p.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default:
                    if (!p.opening && !p.centered) {
                        text.setSpan(new LeadingMarginSpan.Standard(indent, 0), p.start, end, Spanned.SPAN_PARAGRAPH);
                    }
                    break;
            }
            if (p.centered || p.kind == Paragraph.BREAK) {
                text.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), p.start, end, Spanned.SPAN_PARAGRAPH);
            }
            if (above > 0 || below > 0) {
                Paint.FontMetricsInt base = paint.getFontMetricsInt();
                text.setSpan(new SpaceSpan(above, below, Math.round(base.ascent * scale), Math.round(base.descent * scale)),
                    p.start, end, Spanned.SPAN_PARAGRAPH);
            }
        }

        // Italic and bold, set after the headings' bold so that they win; in
        // a heading they keep its weight.
        int at = 0;
        for (Run run : section.runs) {
            while (at + 1 < paragraphs.size() && paragraphs.get(at + 1).start <= run.start) at++;
            int style = run.style;
            if (!paragraphs.isEmpty() && paragraphs.get(at).kind == Paragraph.HEADING) style |= Run.BOLD;
            text.setSpan(new FaceSpan(Fonts.face(assets, font, style)), run.start, Math.min(run.end, length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    // ------------------------------------------------------------------
    // Spans
    // ------------------------------------------------------------------

    /**
     * Space above a paragraph's first line and below its last.
     *
     * It sets each line's height outright, from the paragraph's own type
     * size, rather than adding to the metrics it is handed: StaticLayout can
     * hand a later line the metrics an earlier line's call left behind, and
     * adding would then pile the space onto every line of the paragraph.
     */
    static final class SpaceSpan implements LineHeightSpan {
        private final int above;
        private final int below;
        private final int ascent;
        private final int descent;

        SpaceSpan(int above, int below, int ascent, int descent) {
            this.above = above;
            this.below = below;
            this.ascent = ascent;
            this.descent = descent;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanStartV, int lineHeight,
                                 Paint.FontMetricsInt fm) {
            Spanned spanned = (Spanned) text;
            fm.ascent = ascent - (start == spanned.getSpanStart(this) ? above : 0);
            fm.descent = descent + (end >= spanned.getSpanEnd(this) ? below : 0);
            fm.top = fm.ascent;
            fm.bottom = fm.descent;
        }
    }

    /** The muted colour of a scene break, which follows Night Shift. */
    static final class DimSpan extends CharacterStyle implements UpdateAppearance {
        private final Pages[] owner;

        DimSpan(Pages[] owner) {
            this.owner = owner;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            if (owner[0] != null) paint.setColor(owner[0].dimColor);
        }
    }
}
