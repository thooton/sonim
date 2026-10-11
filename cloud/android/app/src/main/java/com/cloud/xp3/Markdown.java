package com.cloud.xp3;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Enough Markdown for the assistant's replies, rendered into real views.
 *
 * This is deliberately small rather than complete. A reply on a 240px screen
 * needs lists, emphasis, the odd heading, quote or block of code, and every
 * one of those has to earn its vertical space. Tables shrink
 * their columns to the available width; nested lists, images and reference
 * links are left out rather than rendered inconsistently on this screen.
 *
 * Blocks become separate views rather than one long spanned TextView because
 * a bullet needs a hanging indent and a quote needs a rule down its left
 * edge, neither of which a single TextView will give you without leaning on
 * spans that behave differently across Android versions.
 */
final class Markdown {

    private final Context context;
    private final int textColor;
    private final int mutedColor;
    private final int ruleColor;
    private final int codeFill;
    private final int horizontalInset;
    private final int bodyTextSize;

    Markdown(Context context) {
        this(context, 10, 13);
    }

    /** Allows an embedding surface to supply its own padding and type scale. */
    Markdown(Context context, int horizontalInset, int bodyTextSize) {
        this.context = context;
        this.textColor = color(R.color.text);
        this.mutedColor = color(R.color.text_muted);
        this.ruleColor = color(R.color.primary);
        this.codeFill = color(R.color.code_fill);
        this.horizontalInset = horizontalInset;
        this.bodyTextSize = bodyTextSize;
    }

    /** Appends `source` to `host`, one view per block. */
    void into(LinearLayout host, String source) {
        String[] lines = source.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                i++;
                continue;
            }

            if (isTableStart(lines, i)) {
                List<String> headings = tableCells(lines[i]);
                List<String> delimiters = tableCells(lines[i + 1]);
                int[] alignments = new int[headings.size()];
                for (int column = 0; column < alignments.length; column++) {
                    alignments[column] = tableAlignment(delimiters.get(column));
                }
                List<List<String>> rows = new ArrayList<List<String>>();
                i += 2;
                while (
                    i < lines.length &&
                    lines[i].trim().length() > 0 &&
                    hasTablePipe(lines[i])
                ) {
                    rows.add(tableCells(lines[i]));
                    i++;
                }
                host.addView(table(headings, alignments, rows));
                continue;
            }

            if (line.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                // A fence left unclosed at the end of the source still renders;
                // running off the end is not a reason to drop the block.
                if (i < lines.length) i++;
                host.addView(codeBlock(code.toString()));
                continue;
            }

            if (isRule(line)) {
                host.addView(horizontalRule());
                i++;
                continue;
            }

            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                host.addView(heading(line.substring(level).trim(), level));
                i++;
                continue;
            }

            if (line.startsWith(">")) {
                StringBuilder quoted = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    join(quoted, lines[i].trim().substring(1).trim());
                    i++;
                }
                host.addView(quote(quoted.toString()));
                continue;
            }

            if (isBullet(line)) {
                while (i < lines.length && isBullet(lines[i].trim())) {
                    host.addView(
                        listItem("•", lines[i].trim().substring(1).trim())
                    );
                    i++;
                }
                continue;
            }

            if (orderedMarkerLength(line) > 0) {
                while (
                    i < lines.length &&
                    orderedMarkerLength(lines[i].trim()) > 0
                ) {
                    String item = lines[i].trim();
                    int marker = orderedMarkerLength(item);
                    host.addView(
                        listItem(
                            item.substring(0, marker).trim(),
                            item.substring(marker).trim()
                        )
                    );
                    i++;
                }
                continue;
            }

            // A paragraph runs until a blank line or the start of another
            // block, and its lines are joined with spaces: source wrapped at
            // some other column has no business wrapping at that column here.
            StringBuilder paragraph = new StringBuilder();
            while (
                i < lines.length &&
                lines[i].trim().length() > 0 &&
                !startsBlock(lines[i].trim()) &&
                !isTableStart(lines, i)
            ) {
                join(paragraph, lines[i].trim());
                i++;
            }
            host.addView(paragraph(paragraph.toString()));
        }
    }

    /**
     * Inline markers, applied to the text of one block.
     *
     * Emphasis is parsed by recursion so that bold containing italic keeps
     * both, and an unmatched marker is left as the character it is: source
     * that says "5 * 3" is arithmetic, not the start of an italic run that
     * never ends.
     */
    CharSequence inline(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(i + 1));
                i += 2;
                continue;
            }

            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(text.substring(i + 1, end));
                    out.setSpan(
                        new TypefaceSpan("monospace"),
                        start,
                        out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    out.setSpan(
                        new BackgroundColorSpan(codeFill),
                        start,
                        out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    i = end + 1;
                    continue;
                }
            }

            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > i + 1) {
                    span(
                        out,
                        inline(text.substring(i + 2, end)),
                        new StyleSpan(Typeface.BOLD)
                    );
                    i = end + 2;
                    continue;
                }
            }

            if (c == '*' || c == '_') {
                int end = text.indexOf(c, i + 1);
                if (end > i + 1) {
                    span(
                        out,
                        inline(text.substring(i + 1, end)),
                        new StyleSpan(Typeface.ITALIC)
                    );
                    i = end + 1;
                    continue;
                }
            }

            // Links keep their text and lose their target: there is no browser
            // worth opening on this phone, so the underline is there to say
            // "this was a link" and nothing more.
            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                if (
                    close > i &&
                    close + 1 < text.length() &&
                    text.charAt(close + 1) == '('
                ) {
                    int target = text.indexOf(')', close + 2);
                    if (target > close) {
                        span(
                            out,
                            inline(text.substring(i + 1, close)),
                            new UnderlineSpan()
                        );
                        i = target + 1;
                        continue;
                    }
                }
            }

            out.append(c);
            i++;
        }
        return out;
    }

    private void span(SpannableStringBuilder out, CharSequence inner, Object mark) {
        int start = out.length();
        out.append(inner);
        out.setSpan(mark, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void join(StringBuilder into, String line) {
        if (into.length() > 0) into.append(' ');
        into.append(line);
    }

    private static boolean startsBlock(String line) {
        return (
            line.startsWith("#") ||
            line.startsWith(">") ||
            line.startsWith("```") ||
            isBullet(line) ||
            isRule(line) ||
            orderedMarkerLength(line) > 0
        );
    }

    private static boolean isTableStart(String[] lines, int at) {
        if (at + 1 >= lines.length || !hasTablePipe(lines[at])) return false;
        List<String> headings = tableCells(lines[at]);
        List<String> delimiters = tableCells(lines[at + 1]);
        if (headings.size() == 0 || headings.size() != delimiters.size()) {
            return false;
        }
        for (int i = 0; i < delimiters.size(); i++) {
            if (tableAlignment(delimiters.get(i)) == 0) return false;
        }
        return true;
    }

    /** Splits pipes except when escaped or enclosed by an inline code span. */
    private static List<String> tableCells(String line) {
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean code = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value == '\\' && i + 1 < line.length()) {
                cell.append(value);
                cell.append(line.charAt(++i));
            } else if (value == '`') {
                code = !code;
                cell.append(value);
            } else if (value == '|' && !code) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(value);
            }
        }
        cells.add(cell.toString().trim());
        if (!cells.isEmpty() && cells.get(0).length() == 0) cells.remove(0);
        if (!cells.isEmpty() && cells.get(cells.size() - 1).length() == 0) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    private static boolean hasTablePipe(String line) {
        boolean code = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value == '\\' && i + 1 < line.length()) {
                i++;
            } else if (value == '`') {
                code = !code;
            } else if (value == '|' && !code) {
                return true;
            }
        }
        return false;
    }

    /** Gravity for a delimiter such as --- or :---:, or zero if invalid. */
    private static int tableAlignment(String delimiter) {
        String value = delimiter.trim();
        boolean left = value.startsWith(":");
        boolean right = value.endsWith(":");
        int start = left ? 1 : 0;
        int end = right ? value.length() - 1 : value.length();
        if (end - start < 3) return 0;
        for (int i = start; i < end; i++) {
            if (value.charAt(i) != '-') return 0;
        }
        if (left && right) return Gravity.CENTER_HORIZONTAL;
        return right ? Gravity.RIGHT : Gravity.LEFT;
    }

    private static boolean isBullet(String line) {
        return (
            line.length() > 1 &&
            (line.startsWith("- ") ||
                line.startsWith("* ") ||
                line.startsWith("+ "))
        );
    }

    /** Length of a "1." or "2)" marker including its trailing space, or 0. */
    private static int orderedMarkerLength(String line) {
        int digits = 0;
        while (digits < line.length() && Character.isDigit(line.charAt(digits))) {
            digits++;
        }
        if (digits == 0 || digits + 1 >= line.length()) return 0;
        char after = line.charAt(digits);
        if (after != '.' && after != ')') return 0;
        return line.charAt(digits + 1) == ' ' ? digits + 2 : 0;
    }

    private static boolean isRule(String line) {
        if (line.length() < 3) return false;
        char first = line.charAt(0);
        if (first != '-' && first != '*' && first != '_') return false;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != first) return false;
        }
        return true;
    }

    /**
     * Headings step down in weight rather than in size. A 240px column has
     * room for one level of "bigger" before a heading starts taking a line of
     * body text away from every screen it appears on, so the second and third
     * levels are body size and earn their rank from bold and from colour.
     */
    private View heading(String text, int level) {
        TextView view = text(
            inline(text),
            level == 1 ? bodyTextSize + 2 : bodyTextSize,
            level >= 3 ? mutedColor : textColor
        );
        view.setTypeface(null, Typeface.BOLD);
        view.setPadding(
            dp(horizontalInset),
            dp(level == 1 ? 8 : 7),
            dp(horizontalInset),
            dp(2)
        );
        return view;
    }

    private View paragraph(String source) {
        TextView view = text(inline(source), bodyTextSize, textColor);
        view.setPadding(
            dp(horizontalInset),
            dp(3),
            dp(horizontalInset),
            dp(4)
        );
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private View listItem(String marker, String source) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(
            dp(horizontalInset),
            dp(2),
            dp(horizontalInset),
            dp(2)
        );

        TextView bullet = text(marker, bodyTextSize, mutedColor);
        bullet.setLayoutParams(
            new LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT)
        );
        row.addView(bullet);

        // The body sits in its own column so a wrapped second line lands under
        // the first word and not under the bullet.
        TextView body = text(inline(source), bodyTextSize, textColor);
        body.setLineSpacing(dp(2), 1f);
        row.addView(
            body,
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
        );
        return row;
    }

    private View quote(String source) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(
            dp(horizontalInset),
            dp(4),
            dp(horizontalInset),
            dp(4)
        );

        View rule = new View(context);
        rule.setBackgroundColor(ruleColor);
        rule.setLayoutParams(
            new LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
        );
        row.addView(rule);

        TextView body = text(inline(source), bodyTextSize - 1, mutedColor);
        body.setPadding(dp(8), 0, 0, 0);
        body.setLineSpacing(dp(2), 1f);
        row.addView(
            body,
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
        );
        return row;
    }

    private View codeBlock(String source) {
        TextView view = text(source, bodyTextSize - 2, textColor);
        view.setTypeface(Typeface.MONOSPACE);
        view.setBackgroundResource(R.drawable.code_block);
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(
            dp(horizontalInset),
            dp(4),
            dp(horizontalInset),
            dp(4)
        );
        view.setLayoutParams(params);
        return view;
    }

    private View table(
        List<String> headings,
        int[] alignments,
        List<List<String>> rows
    ) {
        LinearLayout table = new LinearLayout(context);
        table.setOrientation(LinearLayout.VERTICAL);
        float[] widths = tableWidths(headings, rows);
        table.addView(tableRow(headings, alignments, widths, true));
        for (int i = 0; i < rows.size(); i++) {
            table.addView(tableRow(rows.get(i), alignments, widths, false));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(
            dp(horizontalInset),
            dp(4),
            dp(horizontalInset),
            dp(4)
        );
        table.setLayoutParams(params);
        return table;
    }

    /**
     * A compact version of intrinsic table layout: the longest unbreakable
     * value establishes a column's minimum, then body rows supply its preferred
     * width. Headers contribute only a minimum so long labels wrap around the
     * useful data instead of squeezing that data into slivers.
     */
    private float[] tableWidths(List<String> headings, List<List<String>> rows) {
        int columns = headings.size();
        float[] widths = new float[columns];
        Paint headerPaint = tablePaint(Math.max(9, bodyTextSize - 3), true);
        Paint bodyPaint = tablePaint(bodyTextSize - 1, false);
        for (int column = 0; column < columns; column++) {
            float minimum = Math.max(
                dp(40),
                longestWordWidth(headings.get(column), headerPaint) + dp(8)
            );
            float preferred = minimum;
            for (int row = 0; row < rows.size(); row++) {
                List<String> values = rows.get(row);
                if (column >= values.size()) continue;
                String value = values.get(column);
                minimum = Math.max(
                    minimum,
                    longestWordWidth(value, bodyPaint) + dp(10)
                );
                preferred = Math.max(
                    preferred,
                    bodyPaint.measureText(value) + dp(10)
                );
            }
            // A long prose cell may ask for the whole table. It can have more
            // room, but still needs to wrap before starving its neighbours.
            widths[column] = Math.max(minimum, Math.min(preferred, minimum * 2.5f));
        }
        return widths;
    }

    private Paint tablePaint(int sp, boolean bold) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(
            sp * context.getResources().getDisplayMetrics().scaledDensity
        );
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return paint;
    }

    private static float longestWordWidth(String value, Paint paint) {
        float widest = 0f;
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || Character.isWhitespace(value.charAt(i))) {
                if (i > start) {
                    widest = Math.max(widest, paint.measureText(value, start, i));
                }
                start = i + 1;
            }
        }
        return widest;
    }

    private View tableRow(
        List<String> values,
        int[] alignments,
        float[] widths,
        boolean heading
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        for (int column = 0; column < alignments.length; column++) {
            String value = column < values.size() ? values.get(column) : "";
            TextView cell = text(
                inline(value),
                heading ? Math.max(9, bodyTextSize - 3) : bodyTextSize - 1,
                heading ? mutedColor : textColor
            );
            cell.setGravity(alignments[column] | Gravity.TOP);
            cell.setPadding(
                dp(heading ? 4 : 5),
                dp(heading ? 3 : 5),
                dp(heading ? 4 : 5),
                dp(heading ? 3 : 5)
            );
            cell.setBackgroundResource(
                heading ? R.drawable.table_header_cell : R.drawable.table_cell
            );
            if (heading) cell.setTypeface(null, Typeface.BOLD);
            row.addView(
                cell,
                new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    widths[column]
                )
            );
        }
        return row;
    }

    private View horizontalRule() {
        View line = new View(context);
        line.setBackgroundColor(color(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        );
        params.setMargins(
            dp(horizontalInset),
            dp(7),
            dp(horizontalInset),
            dp(7)
        );
        line.setLayoutParams(params);
        return line;
    }

    private TextView text(CharSequence value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        // Markdown links are styled with UnderlineSpan rather than URLSpan.
        // Keep every generated block inert so a d-pad press always belongs to
        // the containing ListView row, never to text inside that row.
        view.setLinksClickable(false);
        view.setClickable(false);
        view.setFocusable(false);
        return view;
    }

    private int color(int resId) {
        return context.getResources().getColor(resId);
    }

    private int dp(int value) {
        return Math.round(
            value * context.getResources().getDisplayMetrics().density
        );
    }
}
