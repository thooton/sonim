package com.flashcards.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.flashcards.xp3.algo.Stats;
import com.flashcards.xp3.data.Collection;
import com.flashcards.xp3.data.Deck;
import com.xp3.common.ui.Ui;

import java.time.ZoneId;

/**
 * Anki's Statistics window, cut to what the cards' state can tell
 * ({@link Stats}): the Deck / Collection choice at the top, then Today,
 * Future Due, Card Counts (Anki's pie and its table), Review Intervals and
 * Card Retrievability, each on a white card under its name. Up and Down
 * scroll; Left and Right switch between the deck and the whole collection.
 */
final class StatsPane extends ScrollView {

    private static final int[] SLICES = {
        R.color.count_new, R.color.count_learn, R.color.count_relearn, R.color.count_young, R.color.count_mature};

    private final Deck deck;
    private final Collection collection;
    private final ZoneId zone;
    private boolean whole;
    private final LinearLayout page;

    StatsPane(Context context, Deck deck, Collection collection, ZoneId zone) {
        super(context);
        this.deck = deck;
        this.collection = collection;
        this.zone = zone;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setVerticalScrollBarEnabled(false);
        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(6), dp(4), dp(6), dp(6));
        addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        show();
    }

    private void show() {
        page.removeAllViews();
        long now = System.currentTimeMillis() / 1000L;
        Stats s = whole ? Stats.ofDecks(collection.decks(), now, zone) : Stats.of(deck.cards(), now, zone);

        // Anki's search bar: the deck or the whole collection.
        LinearLayout scope = new LinearLayout(getContext());
        scope.setGravity(Gravity.CENTER);
        scope.setPadding(0, dp(2), 0, dp(6));
        scope.addView(radio(deck.name, !whole), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        scope.addView(radio("Collection", whole), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(scope);

        LinearLayout today = card("Today");
        today.addView(line(s.studied == 0 ? "No cards have been studied today."
            : "Studied " + cards(s.studied) + " today."));
        if (s.studied > 0) {
            today.addView(line("New: " + s.introduced + ", Review: " + (s.studied - s.introduced), R.color.text_subtle));
        }

        LinearLayout due = card("Future Due");
        due.addView(new Bars(getContext(), s.due, R.color.bar_due, dueLabels()), chart());
        int total = s.dueTotal();
        due.addView(line("Due today: " + reviews(s.due[0])));
        due.addView(line("Due tomorrow: " + reviews(s.due[1])));
        due.addView(line("Average: " + Stats.decimal(total / (double) Stats.DUE_DAYS) + " reviews/day"));
        due.addView(line("Total: " + reviews(total)));

        LinearLayout counts = card("Card Counts");
        Pie pie = new Pie(getContext(), s.counts);
        LinearLayout.LayoutParams pieAt = new LinearLayout.LayoutParams(dp(96), dp(96));
        pieAt.gravity = Gravity.CENTER_HORIZONTAL;
        pieAt.bottomMargin = dp(6);
        counts.addView(pie, pieAt);
        for (int i = 0; i < Stats.COUNTS.length; i++) {
            counts.addView(countRow(SLICES[i], Stats.COUNTS[i], s.counts[i], s.percent(s.counts[i]), false));
        }
        counts.addView(countRow(0, "Total", s.total, "", true));

        LinearLayout intervals = card("Review Intervals");
        intervals.addView(new Bars(getContext(), s.intervals, R.color.bar_interval, Stats.INTERVAL_LABELS), chart());
        intervals.addView(line("Average interval: " + Stats.span(s.averageInterval)));
        intervals.addView(line("Longest interval: " + Stats.span(s.longestInterval)));

        LinearLayout recall = card("Card Retrievability");
        String[] ticks = new String[Stats.RECALL_BINS + 1];
        ticks[0] = "0%";
        ticks[Stats.RECALL_BINS / 2] = "50%";
        ticks[Stats.RECALL_BINS] = "100%";
        recall.addView(new Bars(getContext(), s.recall, R.color.bar_recall, ticks), chart());
        recall.addView(line("Average retrievability: " + Stats.decimal(100 * s.averageRecall) + "%"));
        recall.addView(line("Estimated total knowledge: " + cards((int) Math.round(s.knowledge))));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean next = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (next != whole) {
                whole = next;
                show();
                scrollTo(0, 0);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static String[] dueLabels() {
        String[] labels = new String[Stats.DUE_DAYS];
        labels[0] = "0";
        labels[10] = "10";
        labels[20] = "20";
        return labels;
    }

    private static String cards(int n) {
        return n + (n == 1 ? " card" : " cards");
    }

    private static String reviews(int n) {
        return n + (n == 1 ? " review" : " reviews");
    }

    /** Anki's radio button: a ring, filled when it is the one chosen. */
    private TextView radio(String label, boolean on) {
        TextView text = text((on ? "◉ " : "○ ") + label, 12, on ? R.color.text : R.color.text_subtle);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setGravity(Gravity.CENTER);
        if (on) text.setTypeface(Anki.MEDIUM);
        text.setPadding(dp(4), 0, dp(4), 0);
        return text;
    }

    /** A white card with its name centred over it, as Anki's graphs sit. */
    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(6), dp(8), dp(8));
        GradientDrawable face = new GradientDrawable();
        face.setColor(getContext().getColor(R.color.elevated));
        face.setCornerRadius(dp(6));
        face.setStroke(1, getContext().getColor(R.color.border_subtle));
        card.setBackground(face);
        TextView name = text(title, 14, R.color.text);
        name.setTypeface(Anki.MEDIUM);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, 0, 0, dp(4));
        card.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        at.bottomMargin = dp(6);
        page.addView(card, at);
        return card;
    }

    private LinearLayout.LayoutParams chart() {
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84));
        at.bottomMargin = dp(4);
        return at;
    }

    private TextView line(String value) {
        return line(value, R.color.text);
    }

    private TextView line(String value, int colorRes) {
        TextView text = text(value, 12, colorRes);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    /** A row of Card Counts' table: its colour, name, count and share. */
    private View countRow(int colorRes, String label, int count, String share, boolean total) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(1), dp(4), dp(1));
        View swatch = new View(getContext());
        if (colorRes != 0) swatch.setBackgroundColor(getContext().getColor(colorRes));
        LinearLayout.LayoutParams swatchAt = new LinearLayout.LayoutParams(dp(10), dp(10));
        swatchAt.rightMargin = dp(6);
        row.addView(swatch, swatchAt);
        TextView name = text(label, 12, R.color.text);
        if (total) name.setTypeface(Anki.MEDIUM);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView n = text(Integer.toString(count), 12, R.color.text);
        if (total) n.setTypeface(Anki.MEDIUM);
        n.setGravity(Gravity.END);
        row.addView(n, new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView pct = text(share, 12, R.color.text_subtle);
        pct.setGravity(Gravity.END);
        row.addView(pct, new LinearLayout.LayoutParams(dp(50), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView text(String value, float sp, int colorRes) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(getContext().getColor(colorRes));
        return text;
    }

    private int dp(float n) {
        return Ui.dp(getContext(), n);
    }

    /** Card Counts' pie: a slice per kind of card, parted by thin white lines. */
    private static final class Pie extends View {
        private final int[] counts;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Pie(Context context, int[] counts) {
            super(context);
            this.counts = counts;
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(context.getColor(R.color.elevated));
            edge.setStrokeWidth(Ui.dp(context, 1));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = Ui.dp(getContext(), 1);
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            int total = 0;
            for (int n : counts) total += n;
            if (total == 0) {
                fill.setColor(getContext().getColor(R.color.border_subtle));
                canvas.drawOval(box, fill);
                return;
            }
            float start = -90;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] == 0) continue;
                float sweep = 360f * counts[i] / total;
                fill.setColor(getContext().getColor(SLICES[i]));
                canvas.drawArc(box, start, sweep, true, fill);
                if (sweep < 360) canvas.drawArc(box, start, sweep, true, edge);
                start += sweep;
            }
        }
    }

    /**
     * One of Anki's histograms: a bar per bin, the tallest's count at the
     * top, labels under some bins, or, given one more label than bins,
     * under the edges between them.
     */
    private static final class Bars extends View {
        private final int[] values;
        private final String[] labels;
        private final Paint bar = new Paint();
        private final Paint axis = new Paint();
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bars(Context context, int[] values, int colorRes, String[] labels) {
            super(context);
            this.values = values;
            this.labels = labels;
            bar.setColor(context.getColor(colorRes));
            axis.setColor(context.getColor(R.color.border));
            label.setColor(context.getColor(R.color.text_subtle));
            label.setTextSize(Ui.dp(context, 9));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int max = 0;
            for (int v : values) max = Math.max(max, v);
            float text = label.getTextSize();
            String top = Integer.toString(max);
            float left = label.measureText(top) + Ui.dp(getContext(), 4);
            float base = getHeight() - text - Ui.dp(getContext(), 3);
            float height = base - text / 2;
            float width = (getWidth() - left) / values.length;
            float gap = Math.max(1, width * 0.15f);

            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(top, left - Ui.dp(getContext(), 3), text * 0.85f, label);
            canvas.drawText("0", left - Ui.dp(getContext(), 3), base, label);
            canvas.drawRect(left, base, getWidth(), base + 1, axis);
            canvas.drawRect(left, text / 2, left + 1, base, axis);
            boolean edges = labels.length == values.length + 1;
            float under = getHeight() - Ui.dp(getContext(), 1);
            for (int i = 0; i < values.length; i++) {
                float x = left + i * width;
                if (max > 0 && values[i] > 0) {
                    float h = Math.max(1, height * values[i] / max);
                    canvas.drawRect(x + gap / 2, base - h, x + width - gap / 2, base, bar);
                }
            }
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] == null) continue;
                float x = left + i * width + (edges ? 0 : width / 2);
                label.setTextAlign(!edges ? Paint.Align.CENTER
                    : i == 0 ? Paint.Align.LEFT : i == values.length ? Paint.Align.RIGHT : Paint.Align.CENTER);
                canvas.drawText(labels[i], x, under, label);
            }
        }
    }
}
