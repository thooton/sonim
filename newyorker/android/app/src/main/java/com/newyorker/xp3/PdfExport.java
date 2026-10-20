package com.newyorker.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;

import com.newyorker.xp3.news.Article;
import com.newyorker.xp3.news.Block;
import com.newyorker.xp3.news.Site;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An article as a PDF, set as a magazine page would be: US Letter, the
 * header centred over the lead picture, the text justified and hyphenated
 * in Caslon with an indent for each paragraph after the first, drop caps
 * where the sections open, pictures at the column's width with their
 * captions, and the page number at the foot.
 *
 * Drawn with the platform's PdfDocument and StaticLayout, in points. Runs
 * off the main thread: the pictures are fetched afresh at print size.
 */
final class PdfExport {

    private static final int WIDTH = 612;
    private static final int HEIGHT = 792;
    private static final int SIDE = 66;
    private static final int TOP = 64;
    private static final int BOTTOM = 70;
    private static final int COLUMN = WIDTH - 2 * SIDE;
    /** Pictures are asked for at about twice the column's width in points, for print. */
    private static final int PICTURE_PIXELS = 960;

    private static final float BODY = 10.5f;
    private static final float LEADING = 1.38f;

    private final Context context;
    private final Article article;
    private final PdfDocument document = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas canvas;
    private int number;
    private float y;

    private final int ink;
    private final int soft;
    private final int muted;
    private final int red;

    private PdfExport(Context context, Article article) {
        this.context = context;
        this.article = article;
        ink = context.getColor(R.color.ink);
        soft = context.getColor(R.color.ink_soft);
        muted = context.getColor(R.color.muted);
        red = context.getColor(R.color.rubric);
    }

    /** The folder the PDFs go in: New Yorker, inside the phone's Download folder. */
    static File folder() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "New Yorker");
    }

    /** Writes the article into the folder, named for its headline, and returns the file. */
    static File write(Context context, Article article) throws IOException, InterruptedException {
        File folder = folder();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Couldn’t make the Download/New Yorker folder.");
        File file = free(folder, name(article.hed));
        PdfExport export = new PdfExport(context, article);
        try {
            export.draw();
            OutputStream out = new FileOutputStream(file);
            try {
                export.document.writeTo(out);
            } finally {
                out.close();
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            file.delete();
            throw e;
        } finally {
            export.document.close();
        }
        return file;
    }

    /** The headline as a file name: no characters a file system refuses, not too long. */
    static String name(String hed) {
        String clean = hed.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim();
        if (clean.length() > 80) clean = clean.substring(0, 80).trim();
        return clean.isEmpty() ? "Article" : clean;
    }

    /** `name`.pdf, or "name 2.pdf" and so on when that is taken. */
    private static File free(File folder, String name) {
        File file = new File(folder, name + ".pdf");
        for (int n = 2; file.exists(); n++) file = new File(folder, name + " " + n + ".pdf");
        return file;
    }

    private void draw() throws InterruptedException {
        newPage();
        header();
        body();
        if (page != null) document.finishPage(page);
    }

    private void newPage() {
        if (page != null) document.finishPage(page);
        number++;
        page = document.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, number).create());
        canvas = page.getCanvas();
        y = TOP;
        TextPaint foot = paint(Look.sans(context, false), 7.5f, muted);
        foot.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.valueOf(number), WIDTH / 2f, HEIGHT - 40, foot);
    }

    private void header() throws InterruptedException {
        if (!article.rubric.isEmpty()) {
            TextPaint paint = paint(Look.sans(context, false), 9, red);
            text(new SpannableStringBuilder(article.rubric), paint, Layout.Alignment.ALIGN_CENTER, 1.2f, 0, 0, 1);
            y += 8;
        }
        TextPaint hed = paint(Look.display(context), 27, ink);
        text(new SpannableStringBuilder(article.hed), hed, Layout.Alignment.ALIGN_CENTER, 1.08f, 20, 20, 1);
        y += 10;
        if (!article.dek.isEmpty()) {
            TextPaint dek = paint(Look.caslon(context, Look.REGULAR), 13, soft);
            text(new SpannableStringBuilder(article.dek), dek, Layout.Alignment.ALIGN_CENTER, 1.25f, 30, 30, 1);
            y += 10;
        }
        if (!article.byline.isEmpty()) {
            TextPaint byline = paint(Look.sans(context, false), 8.5f, ink);
            byline.setLetterSpacing(0.08f);
            String upper = article.byline.toUpperCase(java.util.Locale.US);
            text(new SpannableStringBuilder(upper), byline, Layout.Alignment.ALIGN_CENTER, 1.2f, 0, 0, 1);
            y += 3;
        }
        if (!article.date.isEmpty()) {
            text(new SpannableStringBuilder(article.date), paint(Look.sans(context, false), 8.5f, muted),
                Layout.Alignment.ALIGN_CENTER, 1.2f, 0, 0, 1);
        }
        y += 16;
        if (!article.image.isEmpty()) {
            picture(article.image, article.caption);
            y += 6;
        }
    }

    private void body() throws InterruptedException {
        int last = -1;
        for (int i = 0; i < article.blocks.size(); i++) {
            if (article.blocks.get(i).kind == Block.PARAGRAPH) last = i;
        }
        boolean first = true;
        int previous = -1;
        for (int i = 0; i < article.blocks.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Block block = article.blocks.get(i);
            switch (block.kind) {
                case Block.PICTURE:
                    y += 6;
                    picture(block.extra, block.caption);
                    break;
                case Block.BREAK:
                    y += 14;
                    break;
                case Block.HEADING:
                    y += 10;
                    // Kept with at least two lines of what follows: a heading never ends a page.
                    if (y + 60 > HEIGHT - BOTTOM) newPage();
                    text(ArticleActivity.styled(context, block, false), paint(Look.caslon(context, Look.BOLD), BODY + 1.5f, ink),
                        Layout.Alignment.ALIGN_NORMAL, 1.25f, 0, 0, 1);
                    y += 4;
                    break;
                case Block.QUOTE:
                    y += 4;
                    text(ArticleActivity.styled(context, block, false), paint(Look.caslon(context, Look.REGULAR), BODY - 0.5f, ink),
                        Layout.Alignment.ALIGN_NORMAL, LEADING, 24, 24, 2);
                    y += 4;
                    break;
                case Block.ITEM: {
                    TextPaint paint = paint(Look.caslon(context, Look.REGULAR), BODY, ink);
                    SpannableStringBuilder text = new SpannableStringBuilder(block.extra + " ");
                    int hang = (int) Math.ceil(paint.measureText(text.toString()));
                    text.append(ArticleActivity.styled(context, block, false));
                    text.setSpan(new LeadingMarginSpan.Standard(0, hang), 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                    text(text, paint, Layout.Alignment.ALIGN_NORMAL, LEADING, 14, 0, 2);
                    y += 2;
                    break;
                }
                default: {
                    TextPaint paint = paint(Look.caslon(context, Look.REGULAR), BODY, ink);
                    SpannableStringBuilder text = ArticleActivity.styled(context, block, i == last);
                    boolean drop = (block.dropCap || first) && dropCap(text, paint);
                    if (drop && !first) y += 10;
                    // As in print, a paragraph that follows another is indented rather than spaced.
                    if (!drop && previous == Block.PARAGRAPH) {
                        text.setSpan(new LeadingMarginSpan.Standard(16, 0), 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                    }
                    text(text, paint, Layout.Alignment.ALIGN_NORMAL, LEADING, 0, 0, drop ? DropCap.LINES : 2);
                    first = false;
                    break;
                }
            }
            previous = block.kind;
        }
        y += 18;
        text(new SpannableStringBuilder(article.url), paint(Look.sans(context, false), 7, muted),
            Layout.Alignment.ALIGN_NORMAL, 1.2f, 0, 0, 1);
    }

    /** Puts the drop cap span on `text`, as on screen; false when it is too short or opens oddly. */
    private boolean dropCap(SpannableStringBuilder text, TextPaint paint) {
        int at = 0;
        while (at < text.length() && "“‘\"'".indexOf(text.charAt(at)) >= 0) at++;
        if (at >= text.length() || !Character.isLetterOrDigit(text.charAt(at))) return false;
        if (text.length() < 160) return false;
        String letter = text.subSequence(0, at + 1).toString();
        text.delete(0, at + 1);
        Rect bounds = new Rect();
        paint.getTextBounds("H", 0, 1, bounds);
        int lineHeight = Math.round(paint.getFontSpacing() * LEADING);
        text.setSpan(new DropCap(letter, Look.caslon(context, Look.REGULAR), ink, lineHeight, bounds.height(), 4),
            0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        return true;
    }

    private TextPaint paint(Typeface face, float size, int color) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(face);
        paint.setTextSize(size);
        paint.setColor(color);
        return paint;
    }

    /**
     * Lays out `text` across the column (less `left` and `right`) and draws
     * it from `y`, carrying on over pages as it must: never fewer than
     * `keep` lines at the foot of a page, so a paragraph does not leave one
     * line behind and a drop cap is never cut.
     */
    private void text(CharSequence text, TextPaint paint, Layout.Alignment alignment, float leading,
                      int left, int right, int keep) {
        int width = COLUMN - left - right;
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
            .setAlignment(alignment)
            .setLineSpacing(0, leading)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        if (alignment == Layout.Alignment.ALIGN_NORMAL && keep > 1) {
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        }
        StaticLayout layout = builder.build();
        int count = layout.getLineCount();
        int line = 0;
        while (line < count) {
            int top = layout.getLineTop(line);
            float room = HEIGHT - BOTTOM - y;
            int end = line;
            while (end < count && layout.getLineBottom(end) - top <= room) end++;
            int needed = Math.min(keep, count - line);
            if (end - line < needed && y > TOP) {
                newPage();
                continue;
            }
            if (end == line) end = line + 1;
            int bottom = layout.getLineBottom(end - 1);
            canvas.save();
            canvas.translate(SIDE + left, y - top);
            canvas.clipRect(-SIDE, top, width + SIDE, bottom);
            layout.draw(canvas);
            canvas.restore();
            y += bottom - top;
            line = end;
            if (line < count) newPage();
        }
        y += paint.getTextSize() * 0.55f;
    }

    /** A picture at the column's width (narrower when it is tall), and its caption under it. */
    private void picture(String url, String caption) throws InterruptedException {
        String sized = Site.picture(url, PICTURE_PIXELS);
        Bitmap bitmap;
        try {
            bitmap = Net.picture(sized == null ? url : sized, PICTURE_PIXELS);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // A picture that will not come is left out rather than failing the whole PDF.
            return;
        }
        try {
            float maxHeight = (HEIGHT - TOP - BOTTOM) * 0.55f;
            float width = COLUMN;
            float height = width * bitmap.getHeight() / bitmap.getWidth();
            if (height > maxHeight) {
                width = width * maxHeight / height;
                height = maxHeight;
            }
            if (y + height + 24 > HEIGHT - BOTTOM) newPage();
            float left = SIDE + (COLUMN - width) / 2;
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(bitmap, null, new RectF(left, y, left + width, y + height), paint);
            y += height + 5;
        } finally {
            bitmap.recycle();
        }
        if (!caption.isEmpty()) {
            text(new SpannableStringBuilder(caption), paint(Look.sans(context, false), 7.5f, muted),
                Layout.Alignment.ALIGN_NORMAL, 1.25f, 0, 0, 1);
        }
        y += 6;
    }
}
