"""Writes the app's list of services and their logos,
android/app/src/main/assets/services.tsv: one service a line, its name, its
domains (comma separated, the main one first) and its logo's address, or
nothing when none was found (the app then asks Google's favicon service for
the main domain's).

    python3 crawl.py <twofactorauth checkout> crawl.jsonl
    python3 build.py crawl.jsonl

The services are 2fa.directory's (github.com/2factorauth/twofactorauth, MIT),
with EXTRA's added and OVERRIDE's logos in place of the crawled ones."""
import json, os, sys

# Services 2fa.directory lacks (it leaves out Google's and Microsoft's own
# accounts, keeping only their products).
EXTRA = [
    ("Google", ["google.com", "accounts.google.com", "gmail.com"], "https://www.google.com/images/branding/product/1x/gsa_android_144dp.png"),
    ("Microsoft", ["microsoft.com", "account.microsoft.com", "live.com"], "https://account.microsoft.com/favicon.ico"),
    ("Anthropic", ["anthropic.com"], "https://www.anthropic.com/images/icons/apple-touch-icon.png"),
    ("Claude", ["claude.ai"], "https://claude.ai/apple-touch-icon.png"),
    ("Tailscale", ["tailscale.com"], "https://tailscale.com/favicon.png"),
]
# Logos the crawl couldn't reach from where it ran.
OVERRIDE = {
    "github.com": "https://github.githubassets.com/apple-touch-icon-180x180.png",
}

rows = {}
for line in open(sys.argv[1]):
    r = json.loads(line)
    domains = [r["domain"]] + [d for d in r["more"] if d != r["domain"]]
    rows[r["name"]] = (domains, OVERRIDE.get(r["domain"], r["logo"] or ""))
for name, domains, logo in EXTRA:
    rows.setdefault(name, (domains, logo))
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "app", "src", "main", "assets", "services.tsv")
with open(out, "w") as f:
    for name in sorted(rows, key=str.lower):
        domains, logo = rows[name]
        clean = lambda s: " ".join(s.replace("\t", " ").split())
        f.write("%s\t%s\t%s\n" % (clean(name), ",".join(clean(d) for d in domains), clean(logo)))
print(len(rows), "services")
