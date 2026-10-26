package com.authenticator.xp3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The services the app knows the logos of: the app's `services.tsv`
 * (2fa.directory's services, their domains and the logo each one's site
 * links, made by `services/build.py`), and which of them an account is.
 */
public final class Services {

    /** One service: its name, main domain, and where its logo is fetched from. */
    public static final class Service {
        public final String name;
        public final String domain;
        /** The logo the service's own site links, or "" when none was found. */
        public final String logo;

        Service(String name, String domain, String logo) {
            this.name = name;
            this.domain = domain;
            this.logo = logo;
        }

        /** Where to fetch the logo, in order: the site's own, then Google's copy of its icon. */
        public List<String> logos() {
            List<String> urls = new ArrayList<>();
            if (logo.length() > 0) urls.add(logo);
            urls.add(favicon(domain));
            return urls;
        }
    }

    /** Google's favicon service, which answers 404 for a site it has no icon for. */
    static String favicon(String domain) {
        return "https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://"
            + domain + "&size=128";
    }

    private static final Pattern DOMAIN = Pattern.compile("^(?:www\\.)?([a-z0-9-]+(?:\\.[a-z0-9-]+)+)$");
    private static final Pattern ASIDE = Pattern.compile("^(.*?)\\s*\\((.*)\\)\\s*$");

    private final int size;
    /** By the whole name, squashed ("Epic Games" as "epicgames"), and its parts in brackets. */
    private final Map<String, Service> names = new HashMap<>();
    /** By every domain, whole. */
    private final Map<String, Service> domains = new HashMap<>();
    /** By a domain's first label ("aws" for aws.amazon.com, "github", "tutanota"). */
    private final Map<String, Service> labels = new HashMap<>();

    private Services(List<String[]> rows) {
        size = rows.size();
        List<Service> all = new ArrayList<>();
        List<String[]> theirs = new ArrayList<>();
        for (String[] row : rows) {
            String[] its = row[1].split(",");
            Service service = new Service(row[0], its[0], row.length > 2 ? row[2] : "");
            all.add(service);
            theirs.add(its);
            putName(row[0], service);
            for (String domain : its) domains.putIfAbsent(domain.toLowerCase(Locale.US), service);
        }
        // Labels come second, so a service named "Amazon" wins "amazon"
        // over one that only has a domain there; and main domains before the
        // rest, so "Tuta" is "tuta" and, by its old domain, "tutanota".
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < all.size(); i++) {
                String[] its = theirs.get(i);
                for (int d = pass == 0 ? 0 : 1; d < (pass == 0 ? 1 : its.length); d++) {
                    String label = its[d].toLowerCase(Locale.US).replaceFirst("^www\\.", "");
                    label = label.substring(0, label.indexOf('.') < 0 ? label.length() : label.indexOf('.'));
                    if (!names.containsKey(label)) labels.putIfAbsent(label, all.get(i));
                }
            }
        }
    }

    private void putName(String name, Service service) {
        String whole = squash(name);
        if (whole.length() > 0) names.putIfAbsent(whole, service);
        Matcher aside = ASIDE.matcher(name);
        if (aside.matches()) {
            // "X (Twitter)" is "X" and "Twitter"; "OpenAI (ChatGPT)" both too.
            for (String part : new String[] {aside.group(1), aside.group(2)}) {
                String key = squash(part);
                if (key.length() > 0) names.putIfAbsent(key, service);
            }
        }
    }

    /** The list in services.tsv's form: name, domains (comma separated), logo; a tab between. */
    public static Services parse(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] row = line.split("\t", -1);
            if (row.length >= 2 && row[0].length() > 0 && row[1].length() > 0) rows.add(row);
        }
        return new Services(rows);
    }

    public int size() {
        return size;
    }

    /**
     * Which service an account is, by its service's name (or the account's,
     * when there is none): the whole name, a domain, the name without what
     * follows a bracket or colon, and its first word. A name that is a domain
     * the list lacks is that domain's own service. Null when none fits.
     */
    public Service find(Account account) {
        String title = account.issuer.length() > 0 ? account.issuer : account.account;
        // An account with no service is usually an email address, whose
        // domain is the mail provider's, not the service's.
        if (account.issuer.length() == 0 && title.contains("@")) return null;
        String lower = title.toLowerCase(Locale.US).trim();
        Service found = lookup(lower);
        if (found != null) return found;
        String shorter = lower.replaceFirst("\\s*[(:\\[|].*$", "").replaceFirst("\\s+-\\s.*$", "").trim();
        if (shorter.length() > 0 && !shorter.equals(lower) && (found = lookup(shorter)) != null) return found;
        String[] words = shorter.split("[^\\p{L}\\p{N}.]+");
        if (words.length > 1 && words[0].length() >= 3 && (found = lookup(words[0])) != null) return found;
        Matcher domain = DOMAIN.matcher(lower);
        if (domain.matches()) return new Service(title, domain.group(1), "");
        return null;
    }

    private Service lookup(String lower) {
        Matcher domain = DOMAIN.matcher(lower);
        if (domain.matches()) {
            Service found = domains.get(domain.group(1));
            if (found != null) return found;
        }
        String key = squash(lower);
        if (key.length() == 0) return null;
        Service found = names.get(key);
        return found != null ? found : labels.get(key);
    }

    /** Lower case letters and digits only. */
    private static String squash(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toLowerCase(Locale.US).toCharArray()) {
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }
}
