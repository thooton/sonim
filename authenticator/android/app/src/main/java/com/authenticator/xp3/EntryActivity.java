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
 * Adding an account by hand: the service, the account and the secret key
 * the service shows beside its QR code ("Can't scan it?"). A whole
 * otpauth:// link typed or pasted as the key works too. The result goes back
 * to MainActivity, which adds it on the command center.
 */
public final class EntryActivity extends BaseActivity {

    static final String ISSUER = "issuer";
    static final String ACCOUNT = "account";
    static final String SECRET = "secret";
    /** Set instead of the three when an otpauth link was entered. */
    static final String LINK = "link";

    private EditText issuer;
    private EditText account;
    private EditText secret;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = vertical();
        int pad = dp(10);
        page.setPadding(pad, dp(12), pad, dp(12));

        TextView title = text("Add account", 15, color(R.color.ink));
        title.setTypeface(Look.medium(this));
        title.setPadding(dp(4), 0, 0, dp(10));
        page.addView(title);

        issuer = field(page, "Service", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        account = field(page, "Account", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        secret = field(page, "Secret key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        secret.setImeOptions(EditorInfo.IME_ACTION_DONE);
        secret.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });

        TextView add = new TextView(this);
        add.setText("Add");
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
            try {
                OtpLink.parse(key);
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
                return;
            }
            result.putExtra(LINK, key);
        } else {
            try {
                Account.of(issuer.getText().toString(), account.getText().toString(), key);
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
                View wrong = e.getMessage().contains("name") ? issuer : secret;
                wrong.requestFocus();
                return;
            }
            result.putExtra(ISSUER, issuer.getText().toString().trim());
            result.putExtra(ACCOUNT, account.getText().toString().trim());
            result.putExtra(SECRET, key);
        }
        setResult(Activity.RESULT_OK, result);
        finish();
    }
}
