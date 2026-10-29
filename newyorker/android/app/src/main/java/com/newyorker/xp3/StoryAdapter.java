package com.newyorker.xp3;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.newyorker.xp3.news.Feed;
import com.newyorker.xp3.news.Story;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A list of stories as the New Yorker app lays them out, cut down to a
 * 240-pixel screen: a section's title over a rule, the section's first story
 * as a lead with its picture across the screen, and the rest as rows with
 * the words on the left and a square picture on the right.
 */
final class StoryAdapter extends BaseAdapter {

    static final int TITLE = 0;
    static final int SECTION = 1;
    static final int LEAD = 2;
    static final int ROW = 3;

    private static final class Item {
        final int type;
        final String text;
        final Story story;

        Item(int type, String text, Story story) {
            this.type = type;
            this.text = text;
            this.story = story;
        }
    }

    private final Context context;
    private final List<Item> items = new ArrayList<>();

    StoryAdapter(Context context) {
        this.context = context;
    }

    /**
     * The feed's sections, under a page title if `title` is not empty (an
     * issue's date). A section's first story leads it when `leads`.
     */
    void setFeed(String title, Feed feed, boolean leads) {
        items.clear();
        if (!title.isEmpty()) items.add(new Item(TITLE, title, null));
        for (Feed.Section section : feed.sections) {
            if (!section.title.isEmpty()) items.add(new Item(SECTION, section.title, null));
            for (int i = 0; i < section.stories.size(); i++) {
                Story story = section.stories.get(i);
                items.add(new Item(leads && i == 0 && !story.image.isEmpty() ? LEAD : ROW, null, story));
            }
        }
        notifyDataSetChanged();
    }

    void setStories(List<Story> stories) {
        items.clear();
        for (Story story : stories) items.add(new Item(ROW, null, story));
        notifyDataSetChanged();
    }

    Story story(int position) {
        return position >= 0 && position < items.size() ? items.get(position).story : null;
    }

    /** The next story after `position` (or before it, for a negative `direction`), or -1. */
    int nextStory(int position, int direction) {
        for (int i = position + direction; i >= 0 && i < items.size(); i += direction) {
            if (items.get(i).story != null) return i;
        }
        return -1;
    }

    /** Where the story at `url` is now, or the first story when it is gone. */
    int positionOf(String url) {
        int first = -1;
        for (int i = 0; i < items.size(); i++) {
            Story story = items.get(i).story;
            if (story == null) continue;
            if (first < 0) first = i;
            if (story.url.equals(url)) return i;
        }
        return first;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 4;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return items.get(position).story != null;
    }

    @Override
    public View getView(int position, View convert, ViewGroup parent) {
        Item item = items.get(position);
        switch (item.type) {
            case TITLE: return title(item.text, convert);
            case SECTION: return section(item.text, convert, position == 0);
            case LEAD: return lead(item.story, convert, position == 0);
            default: return row(item.story, convert, position);
        }
    }

    private int dp(float value) {
        return Ui.dp(context, value);
    }

    private LinearLayout vertical() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private View title(String text, View convert) {
        TextView view = convert instanceof TextView ? (TextView) convert
            : Look.text(context, Look.display(context), 21, R.color.ink);
        view.setText(text);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(dp(10), dp(10), dp(10), dp(4));
        return view;
    }

    /** A heavy rule across the page and the section's name under it, as the site heads its rows. */
    private View section(String text, View convert, boolean first) {
        LinearLayout box;
        if (convert instanceof LinearLayout) {
            box = (LinearLayout) convert;
        } else {
            box = vertical();
            View rule = new View(context);
            rule.setBackgroundColor(context.getColor(R.color.ink));
            LinearLayout.LayoutParams ruled = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
            ruled.setMargins(dp(10), 0, dp(10), 0);
            box.addView(rule, ruled);
            TextView name = Look.text(context, Look.display(context), 16, R.color.ink);
            name.setLineSpacing(0, 1.09f);
            name.setPadding(dp(10), dp(6), dp(10), dp(2));
            box.addView(name);
        }
        box.setPadding(0, first ? dp(8) : dp(18), 0, 0);
        ((TextView) box.getChildAt(1)).setText(text);
        return box;
    }

    private static final class Holder {
        ImageView picture;
        TextView rubric;
        TextView hed;
        TextView dek;
        TextView byline;
        View rule;
    }

    /** The page's top story has the taller picture; a section's first, a shorter one. */
    private View lead(Story story, View convert, boolean top) {
        Holder holder;
        LinearLayout box;
        if (convert != null && convert.getTag() instanceof Holder) {
            box = (LinearLayout) convert;
            holder = (Holder) convert.getTag();
        } else {
            holder = new Holder();
            box = vertical();
            box.setPadding(0, dp(6), 0, dp(10));
            holder.picture = new ImageView(context);
            holder.picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.picture.setBackgroundColor(context.getColor(R.color.cream));
            LinearLayout.LayoutParams pictured = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
            pictured.setMargins(dp(10), 0, dp(10), dp(8));
            box.addView(holder.picture, pictured);
            words(holder, box, 18, true);
            box.setTag(holder);
        }
        holder.picture.getLayoutParams().height = dp(top ? 150 : 118);
        // A picture that can't be had leaves the lead its words, not an empty box.
        Pictures.showOrHide(holder.picture, Net.sized(story.image, 320), 320);
        fill(holder, story, 4);
        return box;
    }

    private View row(Story story, View convert, int position) {
        Holder holder;
        LinearLayout outer;
        if (convert != null && convert.getTag() instanceof Holder) {
            outer = (LinearLayout) convert;
            holder = (Holder) convert.getTag();
        } else {
            holder = new Holder();
            outer = vertical();
            holder.rule = new View(context);
            holder.rule.setBackgroundColor(context.getColor(R.color.rule));
            LinearLayout.LayoutParams ruled = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            ruled.setMargins(dp(10), 0, dp(10), 0);
            outer.addView(holder.rule, ruled);
            LinearLayout line = new LinearLayout(context);
            line.setPadding(0, dp(9), dp(10), dp(9));
            LinearLayout words = vertical();
            words(holder, words, 14.5f, false);
            line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            holder.picture = new ImageView(context);
            holder.picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams pictured = new LinearLayout.LayoutParams(dp(62), dp(62));
            pictured.setMargins(dp(8), dp(2), 0, 0);
            line.addView(holder.picture, pictured);
            outer.addView(line);
            outer.setTag(holder);
        }
        // No rule over the first story under a title, which has its own.
        Item above = position > 0 ? items.get(position - 1) : null;
        holder.rule.setVisibility(above == null || above.story == null ? View.INVISIBLE : View.VISIBLE);
        Pictures.showOrHide(holder.picture, Net.sized(story.image, 124), 124);
        fill(holder, story, 2);
        return outer;
    }

    private void words(Holder holder, LinearLayout box, float hedSize, boolean lead) {
        holder.rubric = Look.rubric(context, lead ? 10 : 9.5f);
        holder.rubric.setPadding(dp(10), 0, dp(10), dp(3));
        box.addView(holder.rubric);
        // Card headlines are the Caslon at hed-core's leading; a lead is the Irvin of hed-feature.
        holder.hed = Look.text(context, Look.caslon(context, Look.REGULAR), hedSize, R.color.ink);
        holder.hed.setLineSpacing(0, lead ? 1.14f : 1.27f);
        holder.hed.setPadding(dp(10), 0, dp(10), 0);
        box.addView(holder.hed);
        // The dek is the Caslon's italic, as the site's description-core sets it.
        holder.dek = Look.text(context, Look.caslon(context, Look.ITALIC), lead ? 12.5f : 11.5f, R.color.ink);
        holder.dek.setLineSpacing(0, 1.41f);
        holder.dek.setEllipsize(TextUtils.TruncateAt.END);
        holder.dek.setPadding(dp(10), dp(4), dp(10), 0);
        box.addView(holder.dek);
        holder.byline = Look.byline(context, lead ? 9.5f : 9f);
        holder.byline.setPadding(dp(10), dp(5), dp(10), 0);
        box.addView(holder.byline);
    }

    private static void fill(Holder holder, Story story, int dekLines) {
        show(holder.rubric, story.rubric);
        holder.hed.setText(story.hed);
        holder.dek.setMaxLines(dekLines);
        show(holder.dek, story.dek);
        show(holder.byline, story.byline);
    }

    private static void show(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
