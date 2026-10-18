package com.canvas.xp3;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Detail;
import com.canvas.xp3.data.Link;
import com.xp3.common.ui.Markdown;

import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The row shapes the screens share: a work row with its course dot, a row
 * led by an icon tile, a file box, a section label, the back line at the top
 * of a page, an empty state and the grey shapes that stand in while loading.
 */
final class Parts {

    private final MainActivity app;
    private final Views v;
    private final Look look;
    /** Links numbered down the screen, so each keeps its key across a redraw. */
    private int links;

    Parts(MainActivity app) {
        this.app = app;
        this.v = app.views();
        this.look = v.look;
    }

    /** A page that scrolls with the d-pad, and the column it holds. */
    LinearLayout page(Screen screen, LinearLayout into) {
        ScrollView scroll = new ScrollView(app);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout column = v.column();
        v.pad(column, 0, 0, 0, 10);
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        into.addView(scroll, Views.tall());
        screen.scroll = scroll;
        return column;
    }

    /** "‹ Assigned" on the left, the class's dot and code on the right. */
    View nav(String back, Course course) {
        LinearLayout row = v.row();
        v.pad(row, 12, 4, 12, 4);
        TextView name = v.text("‹ " + back, 10, look.semibold, look.muted);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, Views.fill());
        if (course != null) {
            row.addView(v.dot(look.course(course.color), 7));
            TextView code = v.text(course.code, 10, look.semibold, look.ink);
            code.setSingleLine(true);
            row.addView(code, v.margins(Views.wrap(), 4, 0, 0, 0));
        }
        return row;
    }

    View nav(String back, String right) {
        LinearLayout row = v.row();
        v.pad(row, 12, 4, 12, 4);
        TextView name = v.text("‹ " + back, 10, look.semibold, look.muted);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, Views.fill());
        if (right != null) row.addView(v.text(right, 10, look.semibold, look.muted));
        return row;
    }

    /** A section label with an optional count or note on the right. */
    View section(String label, String note) {
        LinearLayout row = v.row();
        v.pad(row, 12, 10, 12, 5);
        row.addView(v.label(label), Views.fill());
        if (note != null) row.addView(v.text(note, 9, look.semibold, look.muted));
        return row;
    }

    /** The day header on the timeline: "TUESDAY OCT 20 ··· 2 DUE". */
    View dayHeader(String label, String note) {
        LinearLayout row = v.row();
        v.pad(row, 12, 7, 12, 3);
        row.addView(v.label(label), Views.fill());
        if (note != null) row.addView(v.label(note));
        return row;
    }

    /** A piece of work in a list: course dot, title, one line of detail, and a chip or note on the right. */
    Item work(String key, Assignment item, String meta, View right, Runnable open) {
        Item row = new Item(app, key, "Open", open);
        LinearLayout line = v.row();
        line.setGravity(Gravity.TOP);
        v.pad(line, 12, 6, 12, 6);
        Course course = app.course(item.courseId);
        View dot = v.dot(look.course(course == null ? 0 : course.color), 8);
        line.addView(dot, v.margins(new LinearLayout.LayoutParams(v.dp(8), v.dp(8)), 0, 3, 9, 0));
        LinearLayout text = v.column();
        MarkText title = v.itemTitle(item.title);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f * look.scale);
        text.addView(title, Views.wide());
        text.addView(v.meta(meta), v.margins(Views.wide(), 0, 2, 0, 0));
        line.addView(text, Views.fill());
        if (right != null) {
            LinearLayout.LayoutParams at = v.margins(Views.wrap(), 8, 1, 0, 0);
            at.gravity = Gravity.CENTER_VERTICAL;
            line.addView(right, at);
        }
        row.addView(line, Views.wide());
        row.marks(title, look);
        return row;
    }

    /** A row led by a soft tile: classwork, notifications, files, people. */
    Item tiled(String key, String ok, int icon, int tileFill, int tileTint, String title, String meta, View right,
               Runnable action) {
        Item row = new Item(app, key, ok, action);
        LinearLayout line = v.row();
        v.pad(line, 12, 6, 12, 6);
        line.addView(v.tile(icon, tileFill, tileTint, 26, 14, 8));
        LinearLayout text = v.column();
        MarkText name = v.mark(title, 11.5f, look.semibold, look.ink);
        name.setLineSpacing(0, 1.1f);
        text.addView(name, Views.wide());
        if (meta != null) text.addView(v.meta(meta), v.margins(Views.wide(), 0, 2, 0, 0));
        line.addView(text, v.margins(Views.fill(), 9, 0, 0, 0));
        if (right != null) line.addView(right, v.margins(Views.wrap(), 8, 0, 0, 0));
        row.addView(line, Views.wide());
        row.marks(name, look);
        return row;
    }

    /** The coloured tile for a file's kind, as the boxes under an assignment show it. */
    View fileTile(String kind) {
        int fill;
        int tint;
        int icon;
        String k = kind == null ? "" : kind.toLowerCase(java.util.Locale.US);
        if (k.equals("pdf")) {
            fill = look.dark ? 0xFF3A1A10 : 0xFFFFEDE6;
            tint = look.dark ? 0xFFFF8A65 : 0xFFC4320A;
            icon = R.drawable.ic_doc;
        } else if (k.matches("jpe?g|png|gif|heic|webp|photos")) {
            fill = look.dark ? 0xFF0E2C24 : 0xFFDDF4EC;
            tint = look.dark ? 0xFF5FD3A6 : 0xFF03795C;
            icon = R.drawable.ic_image;
        } else if (k.matches("m4a|mp3|wav|aac|ogg|3gp|amr|audio")) {
            fill = look.dark ? 0xFF241B44 : 0xFFEEEAFE;
            tint = look.dark ? 0xFFB9A3FF : 0xFF6941C6;
            icon = R.drawable.ic_mic;
        } else if (k.equals("link")) {
            fill = look.paper;
            tint = look.ink;
            icon = R.drawable.ic_link;
        } else if (k.equals("text")) {
            fill = look.dark ? 0xFF1B2350 : 0xFFE8ECFF;
            tint = look.dark ? 0xFF9AA8FF : 0xFF3D5AFE;
            icon = R.drawable.ic_edit;
        } else {
            fill = look.dark ? 0xFF1B2350 : 0xFFE8ECFF;
            tint = look.dark ? 0xFF9AA8FF : 0xFF3D5AFE;
            icon = R.drawable.ic_doc;
        }
        return v.tile(icon, fill, tint, 24, 13, 6);
    }

    /** A bordered box for a file or a piece of work; its border turns ink under the d-pad. */
    Item box(String key, String ok, View tile, String title, String meta, Runnable action) {
        Item row = new Item(app, key, ok, action);
        LinearLayout line = v.row();
        v.pad(line, 5, 5, 7, 5);
        line.addView(tile);
        LinearLayout text = v.column();
        MarkText name = v.mark(title, 10.5f, look.semibold, look.ink);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name, Views.wide());
        if (meta != null) text.addView(v.meta(meta), v.margins(Views.wide(), 0, 2, 0, 0));
        line.addView(text, v.margins(Views.fill(), 8, 0, 0, 0));
        row.addView(line, Views.wide());
        row.boxed(v.round(look.bg, 10, look.line, 1), v.round(look.bg, 10, look.ink, 1));
        row.also(has -> name.mark(has, look.markStroke));
        return row;
    }

    String fileMeta(Detail.FileRef file) {
        return file.size >= 0 ? file.kind() + " · " + size(file.size) : file.kind();
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** A small line drawing, one sentence, and the next key to press. */
    View empty(int art, String title, String hint) {
        LinearLayout box = v.column();
        box.setGravity(Gravity.CENTER);
        v.pad(box, 22, 0, 22, 0);
        if (art != 0) {
            ImageView picture = new ImageView(app);
            picture.setImageResource(art);
            picture.setColorFilter(null);
            if (look.dark) picture.setColorFilter(new android.graphics.LightingColorFilter(0, look.ink));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(v.dp(84), v.dp(60));
            at.bottomMargin = v.dp(10);
            box.addView(picture, at);
        }
        TextView heading = v.text(title, 14, look.heavy, look.ink);
        heading.setGravity(Gravity.CENTER);
        heading.setLetterSpacing(-0.01f);
        box.addView(heading, Views.wrap());
        if (hint != null) {
            TextView detail = v.text(hint, 10, look.regular, look.muted);
            detail.setGravity(Gravity.CENTER);
            detail.setLineSpacing(0, 1.3f);
            box.addView(detail, v.margins(Views.wrap(), 0, 4, 0, 0));
        }
        return box;
    }

    /**
     * The one loading state: a spinner in the middle of an otherwise empty
     * screen. A screen is either loading or whole. Half-drawn pages with a
     * "Loading" line or grey stand-in shapes teach people to wait for the
     * rest anyway, and then the rest jumps in under them.
     */
    View spinner() {
        android.widget.FrameLayout box = new android.widget.FrameLayout(app);
        box.setBackgroundColor(look.bg);
        android.widget.ProgressBar wheel = new android.widget.ProgressBar(app);
        wheel.setIndeterminate(true);
        wheel.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(look.ink));
        box.addView(wheel, new android.widget.FrameLayout.LayoutParams(v.dp(28), v.dp(28), Gravity.CENTER));
        box.setFocusable(true);
        box.setDefaultFocusHighlightEnabled(false);
        return box;
    }

    /** The thin paper banner for being offline: what still works, and that work is safe. */
    View offline(String strong, String rest) {
        LinearLayout box = v.row();
        box.setGravity(Gravity.TOP);
        box.setBackground(v.round(look.paper, 10, 0, 0));
        v.pad(box, 8, 6, 8, 6);
        box.addView(v.icon(R.drawable.ic_cloud_off, look.ink, 14));
        TextView text = v.text("", 9.5f, look.regular, look.body);
        android.text.SpannableStringBuilder said = new android.text.SpannableStringBuilder();
        said.append(strong);
        said.setSpan(new android.text.style.ForegroundColorSpan(look.ink), 0, strong.length(), 0);
        said.setSpan(new TypefaceSpan2(look.bold), 0, strong.length(), 0);
        said.append(" ").append(rest);
        text.setText(said);
        text.setLineSpacing(0, 1.25f);
        box.addView(text, v.margins(Views.fill(), 7, 0, 0, 0));
        LinearLayout holder = v.column();
        holder.addView(box, v.margins(Views.wide(), 10, 2, 10, 4));
        return holder;
    }

    /**
     * Instructions, posts and pages, rendered by the shared Markdown and then
     * restyled into this app's type and theme: the shared renderer draws in
     * the system font and the light palette, and is shared with Delta, so it
     * is adapted here rather than changed there.
     */
    void markdown(LinearLayout host, String source) {
        markdown(host, source, app.backend.config().canvasBase());
    }

    /** The same, for text that came from `base`, against which its relative links are read. */
    void markdown(LinearLayout host, String source, URI base) {
        int start = host.getChildCount();
        new Markdown(app, 12, 11).into(host, source == null ? "" : source);
        int end = host.getChildCount();
        for (int i = start; i < end; i++) restyle(host.getChildAt(i));
        for (int i = start; i < end; i++) links(host.getChildAt(i), base);
    }

    /** Puts every link the phone can follow in reach of the d-pad (see LinkText). */
    private void links(View view, URI base) {
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) links(group.getChildAt(i), base);
            return;
        }
        if (!(view instanceof TextView) || !(((TextView) view).getText() instanceof android.text.Spanned)) return;
        TextView text = (TextView) view;
        final android.text.Spanned spanned = (android.text.Spanned) text.getText();
        Markdown.Link[] found = spanned.getSpans(0, spanned.length(), Markdown.Link.class);
        if (found.length == 0) return;
        Arrays.sort(found, new Comparator<Markdown.Link>() {
            @Override
            public int compare(Markdown.Link a, Markdown.Link b) {
                return spanned.getSpanStart(a) - spanned.getSpanStart(b);
            }
        });
        URI canvas = app.backend.config().canvasBase();
        LinkText wrapped = null;
        for (Markdown.Link span : found) {
            final Link link = Link.of(span.target, base, canvas);
            if (link.kind == Link.NONE) continue;
            int from = spanned.getSpanStart(span);
            int to = spanned.getSpanEnd(span);
            if (to <= from) continue;
            if (wrapped == null) {
                android.view.ViewGroup parent = (android.view.ViewGroup) text.getParent();
                int at = parent.indexOfChild(text);
                android.view.ViewGroup.LayoutParams params = text.getLayoutParams();
                parent.removeViewAt(at);
                wrapped = new LinkText(app, text, look.markStroke);
                parent.addView(wrapped, at, params);
            }
            final String label = spanned.subSequence(from, to).toString().trim();
            Item stop = new Item(app, "link:" + links++, "Open",
                () -> LinkMenu.show(app, link, label));
            wrapped.add(from, to, stop);
        }
    }

    private void restyle(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.graphics.Typeface face = text.getTypeface();
            boolean strong = face != null && face.isBold();
            boolean mono = face != null && face.equals(android.graphics.Typeface.MONOSPACE);
            if (!mono) text.setTypeface(strong ? look.bold : look.regular);
            float size = text.getTextSize() / app.getResources().getDisplayMetrics().scaledDensity;
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size * 10.5f / 11f * look.scale);
            int color = text.getCurrentTextColor();
            if (color == app.getColor(R.color.text_muted)) text.setTextColor(look.muted);
            else text.setTextColor(strong ? look.ink : look.body);
            text.setLinkTextColor(look.ink);
            text.setLineSpacing(0, 1.3f);
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) restyle(group.getChildAt(i));
        } else if (look.dark) {
            // Rules and table lines: drawn in the light palette's colours.
            view.setBackgroundColor(look.line);
        }
    }

    /** Who posted, and when: an avatar, the name, the day. */
    View author(String name, long at, int color) {
        LinearLayout who = v.row();
        who.addView(avatar(name, color, 22));
        LinearLayout words = v.column();
        words.addView(v.text(Course.shortName(name), 10.5f, look.bold, look.ink));
        words.addView(v.text(com.canvas.xp3.data.Dates.shortDay(at), 8.5f, look.regular, look.muted),
            v.margins(Views.wrap(), 0, 1, 0, 0));
        who.addView(words, v.margins(Views.fill(), 7, 0, 0, 0));
        return who;
    }

    /** An avatar: initials in a circle of the course colour. */
    View avatar(String name, int color, int size) {
        TextView view = v.text(Course.initials(name), size <= 20 ? 7.5f : 8, look.heavy, 0xFFFFFFFF);
        view.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        view.setBackground(circle);
        view.setLayoutParams(new LinearLayout.LayoutParams(v.dp(size), v.dp(size)));
        return view;
    }

    /** A typeface as a span, for one bold phrase inside a line of body text. */
    static final class TypefaceSpan2 extends android.text.style.MetricAffectingSpan {
        private final android.graphics.Typeface face;

        TypefaceSpan2(android.graphics.Typeface face) {
            this.face = face;
        }

        @Override
        public void updateDrawState(android.text.TextPaint paint) {
            paint.setTypeface(face);
        }

        @Override
        public void updateMeasureState(android.text.TextPaint paint) {
            paint.setTypeface(face);
        }
    }
}
