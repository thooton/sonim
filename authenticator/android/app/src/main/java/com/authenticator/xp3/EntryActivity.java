package com.authenticator.xp3;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

/**
 * Adding an account by hand, or changing one: the service, the account, the
 * secret key the service shows beside its QR code ("Can't scan it?"), and
 * the code's digits, seconds and algorithm. A whole otpauth:// link typed or
 * pasted as the key works too. The result goes back to MainActivity, which
 * adds or changes it on the command center.
 *
 * Given an ID, it changes that account: its fields start as they are, but
 * for the secret, which shows as dots in an empty field and is kept unless
 * a new one is typed.
 */
public final class EntryActivity extends BaseActivity {

    /** The account being changed; absent when adding. */
    static final String ID = "id";
    static final String ISSUER = "issuer";
    static final String ACCOUNT = "account";
    /** "" when changing an account keeps its secret. */
    static final String SECRET = "secret";
    static final String ALGORITHM = "algorithm";
    static final String DIGITS = "digits";
    static final String PERIOD = "period";
    /** Set instead of the rest when an otpauth link was entered to add. */
    static final String LINK = "link";

    private static final String[] ALGORITHMS = {"SHA1", "SHA256", "SHA512"};

    private String id;
    private EditText issuer;
    private EditText account;
    private EditText secret;
    private EditText digits;
    private EditText period;
    private TextView algorithm;
    /** The fields and the button, in the order Up, Down and Enter go through them. */
    private View[] order;
    /** Enter went down in a field and moved on; its release is not for the next one. */
    private boolean swallowEnter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = vertical();
        int pad = dp(10);
        page.setPadding(pad, dp(12), pad, dp(12));

        Intent given = getIntent();
        id = given.getStringExtra(ID);
        TextView title = text(id == null ? "Add account" : "Edit account", 15, color(R.color.ink));
        title.setTypeface(Look.medium(this));
        title.setPadding(dp(4), 0, 0, dp(10));
        page.addView(title);

        issuer = field(page, "Service", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        account = field(page, "Account", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        secret = field(page, "Secret key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (id != null) secret.setHint("••••••••");

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(settings);
        digits = field(column(settings, 1), "Digits", InputType.TYPE_CLASS_NUMBER);
        period = field(column(settings, 1), "Seconds", InputType.TYPE_CLASS_NUMBER);
        period.setImeOptions(EditorInfo.IME_ACTION_DONE);
        algorithm = choice(column(settings, 1.3f), "Algorithm");

        issuer.setText(text(given, ISSUER, ""));
        account.setText(text(given, ACCOUNT, ""));
        digits.setText(Integer.toString(given.getIntExtra(DIGITS, 6)));
        period.setText(Integer.toString(given.getIntExtra(PERIOD, 30)));
        algorithm.setText(text(given, ALGORITHM, "SHA1"));
        // Given back when the command center couldn't take what was typed.
        String typed = given.getStringExtra(LINK);
        secret.setText(typed != null ? typed : text(given, SECRET, ""));

        TextView add = new TextView(this);
        add.setText(id == null ? "Add" : "Save");
        add.setTextSize(14);
        add.setTypeface(Look.medium(this));
        add.setTextColor(0xFFFFFFFF);
        add.setGravity(Gravity.CENTER);
        add.setFocusable(true);
        add.setBackground(button());
        add.setOnClickListener(view -> save());
        add.setOnKeyListener((view, keyCode, event) -> {
            if (!Ui.isOk(keyCode)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) save();
            return true;
        });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        at.topMargin = dp(8);
        page.addView(add, at);
        order = new View[] {issuer, account, secret, digits, period, algorithm, add};
        for (EditText edit : new EditText[] {issuer, account, secret, digits, period}) {
            edit.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_NEXT && action != EditorInfo.IME_ACTION_DONE) return false;
                next(view);
                return true;
            });
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setSmoothScrollingEnabled(false);
        scroll.setFillViewport(true);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.addView(page);
        setContentView(scroll);
        issuer.requestFocus();
        issuer.setSelection(issuer.length());
    }

    /**
     * Up and Down go through the fields, the row and the button in order, and
     * Enter in a field goes on to the next (in Seconds it saves): a single-line
     * field keeps Up and Down to itself, and Android's own next field skips
     * about the row.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        View focused = getCurrentFocus();
        int at = order == null ? -1 : java.util.Arrays.asList(order).indexOf(focused);
        if (at >= 0 && (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            int to = at + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            if (down && to >= 0 && to < order.length) focus(order[to]);
            return true;
        }
        boolean enter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (enter && !down && swallowEnter) {
            swallowEnter = false;
            return true;
        }
        if (enter && focused instanceof EditText && at >= 0) {
            if (down && event.getRepeatCount() == 0) {
                swallowEnter = true;
                next(focused);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** On from a field to the next, or from Seconds, saves. */
    private void next(View from) {
        if (from == period) {
            save();
            return;
        }
        int at = java.util.Arrays.asList(order).indexOf(from);
        if (at >= 0 && at + 1 < order.length) focus(order[at + 1]);
    }

    private static void focus(View view) {
        view.requestFocus();
        if (view instanceof EditText) ((EditText) view).setSelection(((EditText) view).length());
    }

    private static String text(Intent intent, String name, String otherwise) {
        String value = intent.getStringExtra(name);
        return value == null ? otherwise : value;
    }

    /** One of the settings' columns, side by side with a gap between. */
    private LinearLayout column(LinearLayout row, float weight) {
        LinearLayout column = vertical();
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        if (row.getChildCount() > 0) at.leftMargin = dp(6);
        row.addView(column, at);
        return column;
    }

    /** A pill like a field's that steps through the algorithms on OK. */
    private TextView choice(LinearLayout page, String label) {
        label(page, label);
        TextView pill = new TextView(this);
        pill.setTextSize(13.5f);
        pill.setTypeface(Look.regular(this));
        pill.setTextColor(color(R.color.ink));
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setSingleLine(true);
        pill.setFocusable(true);
        pill.setDefaultFocusHighlightEnabled(false);
        pill.setBackground(fieldBackground());
        pill.setPadding(dp(12), 0, dp(8), 0);
        pill.setOnClickListener(view -> step(pill, 1));
        pill.setOnKeyListener((view, keyCode, event) -> {
            int by = Ui.isOk(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (by == 0) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) step(pill, by);
            return true;
        });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        at.bottomMargin = dp(10);
        page.addView(pill, at);
        return pill;
    }

    private void label(LinearLayout page, String label) {
        TextView name = text(label, 12, color(R.color.ink_muted));
        name.setTypeface(Look.medium(this));
        name.setPadding(dp(4), 0, 0, dp(4));
        page.addView(name);
    }

    private static void step(TextView pill, int by) {
        int at = java.util.Arrays.asList(ALGORITHMS).indexOf(pill.getText().toString());
        pill.setText(ALGORITHMS[Math.floorMod(at + by, ALGORITHMS.length)]);
    }

    private EditText field(LinearLayout page, String label, int inputType) {
        label(page, label);
        EditText edit = new EditText(this) {
            // Back hides the keyboard and leaves in the same press; the XP3
            // never shows one, so a press spent on hiding it looks dead.
            @Override
            public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyPreIme(keyCode, event);
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (ime != null) ime.hideSoftInputFromWindow(getWindowToken(), 0);
                }
                return EntryActivity.this.dispatchKeyEvent(event);
            }
        };
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setTextSize(13.5f);
        edit.setTypeface(Look.regular(this));
        edit.setTextColor(color(R.color.ink));
        edit.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        edit.setBackground(fieldBackground());
        edit.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        at.bottomMargin = dp(10);
        page.addView(edit, at);
        return edit;
    }

    /** A white pill, outlined in the ink while it takes the keys. */
    private StateListDrawable fieldBackground() {
        return pill(color(R.color.card), 17, 1.5f, color(R.color.ink), 1, color(R.color.track));
    }

    /** Authy's navy pill; ringed while the d-pad is on it. */
    private StateListDrawable button() {
        return pill(color(R.color.ink), 18, 3, 0xFF8891AA, 0, 0);
    }

    /** A pill of `fill`, its edge `focusedStroke` dp of `focusedColor` while focused, else `restStroke` (0 for none). */
    private StateListDrawable pill(int fill, float radius, float focusedStroke, int focusedColor, float restStroke, int restColor) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(fill);
        focused.setCornerRadius(dp(radius));
        focused.setStroke(dp(focusedStroke), focusedColor);
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(fill);
        rest.setCornerRadius(dp(radius));
        if (restStroke > 0) rest.setStroke(dp(restStroke), restColor);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[0], rest);
        return states;
    }

    private void save() {
        String key = secret.getText().toString().trim();
        Intent result = new Intent();
        if (key.toLowerCase(java.util.Locale.US).startsWith("otpauth")) {
            java.util.List<Account> parsed;
            try {
                parsed = OtpLink.parse(key);
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
                return;
            }
            if (id == null) {
                result.putExtra(LINK, key);
                setResult(Activity.RESULT_OK, result);
                finish();
                return;
            }
            // Changing an account to a link's: its key and settings, and its
            // names where none are typed.
            Account from = parsed.get(0);
            if (issuer.length() == 0) issuer.setText(from.issuer);
            if (account.length() == 0) account.setText(from.account);
            digits.setText(Integer.toString(from.digits));
            period.setText(Integer.toString(from.period));
            algorithm.setText(from.algorithm);
            key = from.secret;
            secret.setText(key);
        }
        int places;
        int seconds;
        try {
            places = Integer.parseInt(digits.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("Codes have 6 to 8 digits");
            focus(digits);
            return;
        }
        try {
            seconds = Integer.parseInt(period.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("A code lasts 1 to 300 seconds");
            focus(period);
            return;
        }
        String chosen = algorithm.getText().toString();
        boolean keeps = id != null && key.length() == 0;
        // Each field is checked on its own, in the form's order, so the one at
        // fault is known without reading the message.
        View wrong = issuer;
        try {
            // A kept secret is checked with a stand-in; the one it has is fine.
            String stand = "AAAAAAAAAAAAAAAA";
            new Account(id, issuer.getText().toString(), account.getText().toString(), stand, chosen, 6, 30);
            wrong = digits;
            new Account(id, "-", "", stand, chosen, places, 30);
            wrong = period;
            new Account(id, "-", "", stand, chosen, places, seconds);
            wrong = secret;
            new Account(id, issuer.getText().toString(), account.getText().toString(), keeps ? stand : key,
                chosen, places, seconds);
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            focus(wrong);
            return;
        }
        if (id != null) result.putExtra(ID, id);
        result.putExtra(ISSUER, issuer.getText().toString().trim());
        result.putExtra(ACCOUNT, account.getText().toString().trim());
        result.putExtra(SECRET, key);
        result.putExtra(ALGORITHM, chosen);
        result.putExtra(DIGITS, places);
        result.putExtra(PERIOD, seconds);
        setResult(Activity.RESULT_OK, result);
        finish();
    }
}
