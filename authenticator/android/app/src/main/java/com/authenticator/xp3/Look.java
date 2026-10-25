package com.authenticator.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

import com.xp3.common.ui.Ui;

import java.util.Locale;

/**
 * Authy's list view in code: Inter (bundled, OFL, as Authy bundles it), the
 * white pill cards with their soft shadow, and the colour each service's
 * logo would have, which tints its monogram and its countdown ring.
 */
final class Look {

    private Look() {}

    private static Typeface regular;
    private static Typeface medium;
    private static Typeface semiBold;

    static Typeface regular(Context context) {
        if (regular == null) regular = context.getResources().getFont(R.font.inter_regular);
        return regular;
    }

    static Typeface medium(Context context) {
        if (medium == null) medium = context.getResources().getFont(R.font.inter_medium);
        return medium;
    }

    static Typeface semiBold(Context context) {
        if (semiBold == null) semiBold = context.getResources().getFont(R.font.inter_semi_bold);
        return semiBold;
    }

    /**
     * A card: white, as round at the ends as it is tall, over a shadow 1dp
     * below it. The one under the d-pad is outlined in the ink.
     */
    static Drawable card(Context context, boolean focused) {
        float round = Ui.dp(context, 40);
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(context.getColor(R.color.shadow));
        shadow.setCornerRadius(round);
        GradientDrawable face = new GradientDrawable();
        face.setColor(context.getColor(R.color.card));
        face.setCornerRadius(round);
        if (focused) face.setStroke(Ui.dp(context, 1.5f), context.getColor(R.color.ink));
        LayerDrawable layers = new LayerDrawable(new Drawable[] {shadow, face});
        layers.setLayerInset(0, 0, Ui.dp(context, 1), 0, 0);
        layers.setLayerInset(1, 0, 0, 0, Ui.dp(context, 1));
        return layers;
    }

    /** Authy's round navy add button. */
    static Drawable fab(Context context, boolean focused) {
        GradientDrawable face = new GradientDrawable();
        face.setShape(GradientDrawable.OVAL);
        face.setColor(context.getColor(R.color.ink));
        if (focused) face.setStroke(Ui.dp(context, 3), 0xFF8891AA);
        return face;
    }

    /** Services' own colours, from their brand guidelines, by a word in the name. */
    private static final String[][] BRANDS = {
        {"google", "#4285F4"}, {"gmail", "#EA4335"}, {"youtube", "#FF0000"},
        {"github", "#181717"}, {"gitlab", "#FC6D26"}, {"bitbucket", "#0052CC"},
        {"microsoft", "#00A4EF"}, {"outlook", "#0078D4"}, {"azure", "#0078D4"},
        {"aws", "#FF9900"}, {"amazon", "#FF9900"},
        {"discord", "#5865F2"}, {"facebook", "#1877F2"}, {"meta", "#0467DF"},
        {"instagram", "#E4405F"}, {"whatsapp", "#25D366"},
        {"twitter", "#000000"}, {"x.com", "#000000"},
        {"twitch", "#9146FF"}, {"twilio", "#F22F46"}, {"authy", "#EC1C24"},
        {"dropbox", "#0061FF"}, {"slack", "#4A154B"}, {"apple", "#000000"},
        {"reddit", "#FF4500"}, {"paypal", "#003087"}, {"venmo", "#008CFF"},
        {"coinbase", "#0052FF"}, {"binance", "#F0B90B"}, {"kraken", "#5741D9"},
        {"linkedin", "#0A66C2"}, {"proton", "#6D4AFF"}, {"bitwarden", "#175DDC"},
        {"1password", "#0572EC"}, {"lastpass", "#D32D27"},
        {"cloudflare", "#F38020"}, {"digitalocean", "#0080FF"}, {"heroku", "#430098"},
        {"vercel", "#000000"}, {"netlify", "#00C7B7"}, {"npm", "#CB3837"},
        {"docker", "#2496ED"}, {"atlassian", "#0052CC"}, {"jira", "#0052CC"},
        {"notion", "#000000"}, {"figma", "#F24E1E"}, {"zoom", "#0B5CFF"},
        {"epic", "#313131"}, {"steam", "#171A21"}, {"nintendo", "#E60012"},
        {"playstation", "#003791"}, {"sony", "#003791"}, {"xbox", "#107C10"},
        {"okta", "#007DC1"}, {"stripe", "#635BFF"}, {"shopify", "#7AB55C"},
        {"openai", "#000000"}, {"anthropic", "#D97757"}, {"claude", "#D97757"},
        {"tailscale", "#242424"}, {"namecheap", "#DE3723"}, {"godaddy", "#1BDBDB"},
        {"robinhood", "#00C805"}, {"fidelity", "#368727"}, {"schwab", "#00A0DF"},
        {"chase", "#117ACA"}, {"wells", "#D71E28"}, {"asu", "#8C1D40"},
        {"canvas", "#E72429"}, {"snapchat", "#FFFC00"}, {"tiktok", "#000000"},
        {"yahoo", "#6001D2"}, {"adobe", "#FF0000"}, {"spotify", "#1DB954"},
    };

    /** For names without a brand: tones in the manner of Authy's own logos, chosen by the name. */
    private static final int[] TONES = {
        0xFF0263E0, 0xFFEC1C24, 0xFF14B053, 0xFF6D2ED1, 0xFFF47C22, 0xFF008CFF, 0xFFD61F69, 0xFF394762,
    };

    static int brand(Account account) {
        String issuer = account.issuer.toLowerCase(Locale.US).trim();
        if (issuer.equals("x")) return 0xFF000000;
        // Whole words of the service's name (or the account's, when there is
        // no service), so "ASU" doesn't colour "Treasury" and "Bank
        // (sam@gmail.com)" isn't Gmail's; a long brand name may lead a word
        // ("GitHubEnterprise", "googlemail").
        String[] words = account.title().toLowerCase(Locale.US).split("[^a-z0-9.]+");
        for (String[] brand : BRANDS) {
            for (String word : words) {
                if (word.equals(brand[0]) || (brand[0].length() >= 5 && word.startsWith(brand[0]))
                    || word.endsWith("." + brand[0]) || word.startsWith(brand[0] + ".")) {
                    return android.graphics.Color.parseColor(brand[1]);
                }
            }
        }
        return TONES[Math.abs(account.title().toLowerCase(Locale.US).hashCode() % TONES.length)];
    }

    /** The monogram on a logo: the name's first letter or digit. */
    static String initial(Account account) {
        String title = account.title();
        for (int i = 0; i < title.length(); ) {
            int point = title.codePointAt(i);
            if (Character.isLetterOrDigit(point)) return new String(Character.toChars(point)).toUpperCase(Locale.getDefault());
            i += Character.charCount(point);
        }
        return "?";
    }

    /** Whether white reads on `color`, or it needs the ink. */
    static boolean light(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b > 186;
    }
}
