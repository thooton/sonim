package com.canvas.xp3;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;

/**
 * Text fields: a paper box with a rule under it that turns into the yellow
 * highlighter while the field takes the keys.
 *
 * Typing is the phone's own keypad input (T9, multi-tap or numbers), so
 * students keep the habits they have. Back deletes a character in a field,
 * as it does everywhere else on the phone.
 */
final class Fields {

    private Fields() {}

    static EditText box(MainActivity app, final Views v, String text, String hint, int type) {
        EditText field = new EditText(app);
        field.setText(text);
        return styled(app, v, field, hint, type);
    }

    /** A box for an answer, its formatting shown; `stored` is in `Rich`'s form. */
    static AnswerField answer(MainActivity app, final Views v, String stored, String hint, int type) {
        AnswerField field = new AnswerField(app, v.look.bold);
        field.show(stored);
        return styled(app, v, field, hint, type);
    }

    private static <T extends EditText> T styled(MainActivity app, final Views v, T field, String hint, int type) {
        final Look look = v.look;
        field.setHint(hint);
        field.setHintTextColor(look.faint);
        field.setTextColor(look.ink);
        field.setTypeface(look.regular);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * look.scale);
        field.setLineSpacing(0, 1.3f);
        field.setInputType(type);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setIncludeFontPadding(false);
        field.setDefaultFocusHighlightEnabled(false);
        field.setHighlightColor(look.markStroke);
        final Drawable idle = background(v, look.line, 2);
        final Drawable focused = background(v, look.mark, 3);
        field.setBackground(idle);
        field.setPadding(v.dp(8), v.dp(6), v.dp(8), v.dp(8));
        field.setOnFocusChangeListener((view, has) -> {
            view.setBackground(has ? focused : idle);
            view.setPadding(v.dp(8), v.dp(6), v.dp(8), v.dp(8));
            app.refreshKeys();
        });
        field.setSelection(field.getText().length());
        return field;
    }

    /** Paper with rounded top corners, and a rule along the bottom. */
    static Drawable background(Views v, int rule, int height) {
        GradientDrawable paper = new GradientDrawable();
        paper.setColor(v.look.paper);
        float r = v.dp(8);
        paper.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        GradientDrawable line = new GradientDrawable();
        line.setColor(rule);
        LayerDrawable layers = new LayerDrawable(new Drawable[] { paper, line });
        layers.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        layers.setLayerHeight(1, v.dp(height));
        return layers;
    }
}
