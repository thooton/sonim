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
        period.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });
        algorithm = choice(column(settings, 1.3f), "Algorithm");

        issuer.setText(text(given, ISSUER, ""));
        account.setText(text(given, ACCOUNT, ""));
        digits.setText(Integer.toString(given.getIntExtra(DIGITS, 6)));
        period.setText(Integer.toString(given.getIntExtra(PERIOD, 30)));
        algorithm.setText(text(given, ALGORITHM, "SHA1"));

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

        ScrollView scroll = new ScrollView(this);
        scroll.setSmoothScrollingEnabled(false);
        scroll.setFillViewport(true);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.addView(page);
        setContentView(scroll);
        issuer.requestFocus();
        issuer.setSelection(issuer.length());
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
        TextView name = text(label, 12, color(R.color.ink_muted));
        name.setTypeface(Look.medium(this));
        name.setPadding(dp(4), 0, 0, dp(4));
        page.addView(name);
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

    private static void step(TextView pill, int by) {
        int at = java.util.Arrays.asList(ALGORITHMS).indexOf(pill.getText().toString());
        pill.setText(ALGORITHMS[Math.floorMod(at + by, ALGORITHMS.length)]);
    }

    private EditText field(LinearLayout page, String label, int inputType) {
        TextView name = text(label, 12, color(R.color.ink_muted));
        name.setTypeface(Look.medium(this));
        name.setPadding(dp(4), 0, 0, dp(4));
        page.addView(name);
        EditText edit = new EditText(this);
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
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(color(R.color.card));
        focused.setCornerRadius(dp(17));
        focused.setStroke(dp(1.5f), color(R.color.ink));
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(color(R.color.card));
        rest.setCornerRadius(dp(17));
        rest.setStroke(dp(1), color(R.color.track));
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[0], rest);
        return states;
    }

    /** Authy's navy pill; ringed while the d-pad is on it. */
    private StateListDrawable button() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(color(R.color.ink));
        focused.setCornerRadius(dp(18));
        focused.setStroke(dp(3), 0xFF8891AA);
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(color(R.color.ink));
        rest.setCornerRadius(dp(18));
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
            digits.requestFocus();
            return;
        }
        try {
            seconds = Integer.parseInt(period.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("A code lasts 1 to 300 seconds");
            period.requestFocus();
            return;
        }
        String chosen = algorithm.getText().toString();
        boolean keeps = id != null && key.length() == 0;
        try {
            // A kept secret is checked with a stand-in; the one it has is fine.
            new Account(id, issuer.getText().toString(), account.getText().toString(), keeps ? "AAAAAAAAAAAAAAAA" : key,
                chosen, places, seconds);
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            String why = e.getMessage();
            View wrong = why.contains("name") ? issuer : why.contains("digits") ? digits : why.contains("seconds") ? period : secret;
            wrong.requestFocus();
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
