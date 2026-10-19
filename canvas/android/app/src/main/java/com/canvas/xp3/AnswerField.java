package com.canvas.xp3;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.MetricAffectingSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.widget.EditText;

import com.canvas.xp3.data.Rich;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An answer box that keeps formatting, as Canvas's own editor does: bold,
 * italic and underline as spans over the text, bulleted and numbered lists
 * as lines starting "• " or "1. ". The draft holds it in `Rich`'s form.
 *
 * A keypad phone can hardly select text, so a style also works with nothing
 * selected: it turns on (or off) for what is typed next, as a word
 * processor's toolbar does. That is a zero-length span at the cursor which
 * grows as letters arrive, flagged to grow at both ends, because multi-tap
 * replaces the letter it is cycling through rather than adding one.
 *
 * Paste, from Options or the platform's own menu, reads Markdown and rich
 * text into the same formatting; copy carries it along as HTML.
 */
final class AnswerField extends EditText {

    /** Bold in the app's own heavier Bricolage, keeping any italic under it. */
    static final class Bold extends MetricAffectingSpan {
        private final Typeface face;

        Bold(Typeface face) {
            this.face = face;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            apply(paint);
        }

        @Override
        public void updateMeasureState(TextPaint paint) {
            apply(paint);
        }

        private void apply(TextPaint paint) {
            Typeface old = paint.getTypeface();
            boolean italic = old != null && (old.getStyle() & Typeface.ITALIC) != 0;
            paint.setTypeface(italic ? Typeface.create(face, Typeface.ITALIC) : face);
            if (italic && (paint.getTypeface().getStyle() & Typeface.ITALIC) == 0) paint.setTextSkewX(-0.25f);
        }
    }

    static final class Italic extends StyleSpan {
        Italic() {
            super(Typeface.ITALIC);
        }
    }

    /** Its own class, so the keyboard's underline under a word being typed is never taken for formatting. */
    static final class Underline extends UnderlineSpan {}

    private static final Pattern NUMBER = Pattern.compile("^(\\d{1,9})\\. ");

    private final Typeface bold;
    /** Told when a format changes without the text changing, so it is saved like a keystroke. */
    Runnable formatted;
    private boolean changing;
    private int newline = -1;

    AnswerField(Context context, Typeface bold) {
        super(context);
        this.bold = bold;
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                newline = !changing && before == 0 && count == 1 && s.charAt(start) == '\n' ? start : -1;
            }

            @Override
            public void afterTextChanged(Editable s) {
                int at = newline;
                newline = -1;
                if (at >= 0 && at < s.length()) continueList(s, at);
            }
        });
    }

    // ------------------------------------------------------------------
    // The draft's form
    // ------------------------------------------------------------------

    /** Shows an answer kept in `Rich`'s form, the cursor at its end. */
    void show(String stored) {
        setText(spanned(stored));
        setSelection(getText().length());
    }

    /** The answer in `Rich`'s form. */
    String stored() {
        return stored(getText(), 0, getText().length());
    }

    private SpannableStringBuilder spanned(String stored) {
        Rich.Styled styled = Rich.parse(stored);
        SpannableStringBuilder out = new SpannableStringBuilder(styled.text);
        for (Rich.Run run : styled.runs) {
            out.setSpan(span(run.style), run.start, run.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
        return out;
    }

    private static String stored(Spanned text, int start, int end) {
        List<Rich.Run> runs = new ArrayList<>();
        for (int style : new int[] { Rich.BOLD, Rich.ITALIC, Rich.UNDERLINE }) {
            for (Object span : text.getSpans(start, end, type(style))) {
                int s = Math.max(start, text.getSpanStart(span));
                int e = Math.min(end, text.getSpanEnd(span));
                if (s < e) runs.add(new Rich.Run(s - start, e - start, style));
            }
        }
        return Rich.write(text.subSequence(start, end).toString(), runs);
    }

    private Object span(int style) {
        if (style == Rich.BOLD) return new Bold(bold);
        if (style == Rich.ITALIC) return new Italic();
        return new Underline();
    }

    private static Class<?> type(int style) {
        return style == Rich.BOLD ? Bold.class : style == Rich.ITALIC ? Italic.class : Underline.class;
    }

    // ------------------------------------------------------------------
    // Bold, italic, underline
    // ------------------------------------------------------------------

    /** Whether the selection has the style, or, with none, whether what is typed next will. */
    boolean active(int style) {
        Editable text = getText();
        int a = start();
        int b = end();
        if (a < b) return covered(text, style, a, b);
        for (Object span : text.getSpans(a, a, type(style))) {
            if (grows(text, span, a)) return true;
        }
        return false;
    }

    void toggle(int style) {
        Editable text = getText();
        int a = start();
        int b = end();
        if (a == b) {
            boolean on = false;
            for (Object span : text.getSpans(a, a, type(style))) {
                if (!grows(text, span, a)) continue;
                on = true;
                int s = text.getSpanStart(span);
                int e = text.getSpanEnd(span);
                text.removeSpan(span);
                if (s < a) text.setSpan(span(style), s, a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (a < e) text.setSpan(span(style), a, e, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            }
            if (!on) text.setSpan(span(style), a, a, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return;
        }
        if (covered(text, style, a, b)) {
            for (Object span : text.getSpans(a, b, type(style))) {
                int s = text.getSpanStart(span);
                int e = text.getSpanEnd(span);
                if (e <= a || s >= b) continue;
                text.removeSpan(span);
                if (s < a) text.setSpan(span(style), s, a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (b < e) text.setSpan(span(style), b, e, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            }
        } else {
            int s = a;
            int e = b;
            for (Object span : text.getSpans(a, b, type(style))) {
                s = Math.min(s, text.getSpanStart(span));
                e = Math.max(e, text.getSpanEnd(span));
                text.removeSpan(span);
            }
            text.setSpan(span(style), s, e, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
    }

    /** Whether a letter typed at `at` would land inside the span. */
    private static boolean grows(Spanned text, Object span, int at) {
        int s = text.getSpanStart(span);
        int e = text.getSpanEnd(span);
        int flags = text.getSpanFlags(span) & Spanned.SPAN_POINT_MARK_MASK;
        boolean startIncluded = flags == Spanned.SPAN_INCLUSIVE_EXCLUSIVE || flags == Spanned.SPAN_INCLUSIVE_INCLUSIVE;
        boolean endIncluded = flags == Spanned.SPAN_INCLUSIVE_INCLUSIVE || flags == Spanned.SPAN_EXCLUSIVE_INCLUSIVE;
        if (s == e) return s == at && startIncluded && endIncluded;
        if (s < at && at < e) return true;
        if (e == at) return endIncluded;
        return s == at && startIncluded;
    }

    private static boolean covered(Spanned text, int style, int a, int b) {
        boolean[] on = new boolean[b - a];
        for (Object span : text.getSpans(a, b, type(style))) {
            for (int k = Math.max(a, text.getSpanStart(span)); k < Math.min(b, text.getSpanEnd(span)); k++) {
                on[k - a] = true;
            }
        }
        for (boolean one : on) if (!one) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    /** Whether every line the cursor or selection is on is a bullet (or, for numbered, a number). */
    boolean listed(boolean numbered) {
        String s = getText().toString();
        for (int line : lines()) {
            if (!numbered && !s.startsWith(Rich.BULLET, line)) return false;
            if (numbered && marker(s, line) == null) return false;
        }
        return true;
    }

    /** Bullets the lines in reach, or takes their bullets off when they all have one. */
    void bullets() {
        Editable text = getText();
        boolean off = listed(false);
        List<Integer> lines = lines();
        changing = true;
        for (int i = lines.size() - 1; i >= 0; i--) {
            int line = lines.get(i);
            String marker = marker(text.toString(), line);
            if (off) text.delete(line, line + Rich.BULLET.length());
            else if (marker == null) text.insert(line, Rich.BULLET);
            else if (!marker.equals(Rich.BULLET)) text.replace(line, line + marker.length(), Rich.BULLET);
        }
        changing = false;
    }

    /**
     * Numbers the lines in reach, carrying on from a numbered line just above
     * them, or takes their numbers off when they are all numbered already.
     */
    void numbered() {
        Editable text = getText();
        boolean off = listed(true);
        List<Integer> lines = lines();
        int next = 1;
        if (!off && lines.get(0) > 0) {
            String s = text.toString();
            int above = s.lastIndexOf('\n', lines.get(0) - 2) + 1;
            Matcher m = NUMBER.matcher(s.substring(above, lines.get(0) - 1));
            if (m.find()) next = Integer.parseInt(m.group(1)) + 1;
        }
        changing = true;
        for (int i = lines.size() - 1; i >= 0; i--) {
            int line = lines.get(i);
            String marker = marker(text.toString(), line);
            int length = marker == null ? 0 : marker.length();
            text.replace(line, line + length, off ? "" : (next + i) + ". ");
        }
        changing = false;
    }

    /** A new line after a list line carries the list on; one after an empty item ends the list. */
    private void continueList(Editable text, int at) {
        String s = text.toString();
        int line = s.lastIndexOf('\n', at - 1) + 1;
        String marker = marker(s, line);
        if (marker == null) return;
        changing = true;
        if (s.substring(line, at).trim().equals(marker.trim())) {
            text.delete(line, at + 1);
        } else {
            Matcher m = NUMBER.matcher(marker);
            text.insert(at + 1, m.find() ? (Long.parseLong(m.group(1)) + 1) + ". " : Rich.BULLET);
        }
        changing = false;
    }

    /** The list marker a line starts with, or null. */
    private static String marker(String s, int line) {
        if (s.startsWith(Rich.BULLET, line)) return Rich.BULLET;
        int end = s.indexOf('\n', line);
        Matcher m = NUMBER.matcher(s.substring(line, end < 0 ? s.length() : end));
        return m.find() ? m.group() : null;
    }

    /** Where each line the cursor or selection touches starts. */
    private List<Integer> lines() {
        String s = getText().toString();
        int a = start();
        int b = end();
        List<Integer> out = new ArrayList<>();
        int line = s.lastIndexOf('\n', a - 1) + 1;
        out.add(line);
        int next = s.indexOf('\n', line);
        while (next >= 0 && next + 1 < b) {
            out.add(next + 1);
            next = s.indexOf('\n', next + 1);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The clipboard
    // ------------------------------------------------------------------

    boolean canPaste() {
        ClipboardManager clipboard = clipboard();
        return clipboard != null && clipboard.hasPrimaryClip();
    }

    /** Pastes over the selection: rich text and Markdown arrive formatted. */
    void paste() {
        ClipboardManager clipboard = clipboard();
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        ClipData.Item item = clip.getItemAt(0);
        String stored;
        CharSequence styled = item.getText();
        if (item.getHtmlText() != null) {
            stored = Rich.fromHtml(item.getHtmlText());
        } else if (styled instanceof Spanned && formatted((Spanned) styled)) {
            stored = fromSpans((Spanned) styled);
        } else {
            CharSequence text = item.coerceToText(getContext());
            if (text == null) return;
            stored = Rich.fromMarkdown(text.toString());
        }
        SpannableStringBuilder pasted = spanned(stored);
        int a = start();
        getText().replace(a, end(), pasted);
        setSelection(Math.min(getText().length(), a + pasted.length()));
    }

    /**
     * Copies the selection, or the whole answer when nothing is selected, as
     * HTML with plain text beside it for apps that take no formatting.
     */
    boolean copy() {
        ClipboardManager clipboard = clipboard();
        int a = start();
        int b = end();
        if (a == b) {
            a = 0;
            b = getText().length();
        }
        String stored = stored(getText(), a, b);
        if (clipboard == null || Rich.plain(stored).trim().length() == 0) return false;
        clipboard.setPrimaryClip(ClipData.newHtmlText("Answer", Rich.plain(stored), Rich.html(stored)));
        return true;
    }

    boolean selected() {
        return start() < end();
    }

    /**
     * The Options rows for an answer box (the right-click menu of a phone
     * with no pointer): the formats first, so their digits stay put, each
     * saying "On" when it is.
     */
    void choices(MainActivity app, List<Sheet.Choice> out) {
        out.add(format("Bold", R.drawable.ic_bold, active(Rich.BOLD), () -> toggle(Rich.BOLD)));
        out.add(format("Italic", R.drawable.ic_italic, active(Rich.ITALIC), () -> toggle(Rich.ITALIC)));
        out.add(format("Underline", R.drawable.ic_underline, active(Rich.UNDERLINE), () -> toggle(Rich.UNDERLINE)));
        out.add(format("Bulleted list", R.drawable.ic_bullets, listed(false), this::bullets));
        out.add(format("Numbered list", R.drawable.ic_numbers, listed(true), this::numbered));
        if (canPaste()) out.add(new Sheet.Choice("Paste", R.drawable.ic_paste, null, this::paste));
        if (selected()) {
            out.add(new Sheet.Choice("Cut", R.drawable.ic_cut, null, () -> {
                int a = start();
                int b = end();
                if (copy()) getText().delete(a, b);
            }));
        }
        out.add(new Sheet.Choice("Copy", R.drawable.ic_copy, null, () -> {
            if (copy()) app.say(selected() ? "Copied." : "Answer copied.");
        }));
    }

    private Sheet.Choice format(String label, int icon, boolean on, Runnable action) {
        return new Sheet.Choice(label, icon, on ? "On" : null, () -> {
            action.run();
            requestFocus();
            if (formatted != null) formatted.run();
        });
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.paste) {
            paste();
            return true;
        }
        if ((id == android.R.id.copy || id == android.R.id.cut) && start() < end()) {
            int a = start();
            int b = end();
            if (!copy()) return false;
            if (id == android.R.id.cut) getText().delete(a, b);
            return true;
        }
        return super.onTextContextMenuItem(id);
    }

    private static boolean formatted(Spanned text) {
        for (StyleSpan span : text.getSpans(0, text.length(), StyleSpan.class)) {
            if (span.getStyle() != Typeface.NORMAL) return true;
        }
        return text.getSpans(0, text.length(), UnderlineSpan.class).length > 0;
    }

    /** Another app's styled text, its bold, italic and underline kept. */
    private static String fromSpans(Spanned text) {
        List<Rich.Run> runs = new ArrayList<>();
        for (StyleSpan span : text.getSpans(0, text.length(), StyleSpan.class)) {
            int s = text.getSpanStart(span);
            int e = text.getSpanEnd(span);
            if ((span.getStyle() & Typeface.BOLD) != 0) runs.add(new Rich.Run(s, e, Rich.BOLD));
            if ((span.getStyle() & Typeface.ITALIC) != 0) runs.add(new Rich.Run(s, e, Rich.ITALIC));
        }
        for (UnderlineSpan span : text.getSpans(0, text.length(), UnderlineSpan.class)) {
            runs.add(new Rich.Run(text.getSpanStart(span), text.getSpanEnd(span), Rich.UNDERLINE));
        }
        return Rich.write(text.toString(), runs);
    }

    private ClipboardManager clipboard() {
        return (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private int start() {
        int a = getSelectionStart();
        int b = getSelectionEnd();
        if (a < 0 || b < 0) return getText().length();
        return Math.min(a, b);
    }

    private int end() {
        int a = getSelectionStart();
        int b = getSelectionEnd();
        if (a < 0 || b < 0) return getText().length();
        return Math.max(a, b);
    }
}
