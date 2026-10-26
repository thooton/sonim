"""Finds each service's own icon: the apple-touch-icon or icon its home page
links, else /apple-touch-icon.png or /favicon.ico, verified to be a PNG, JPEG,
GIF, WebP or ICO (what Android 8.1 decodes) served directly with a 200."""
import json, glob, sys, re, ssl, struct, concurrent.futures as cf
import urllib.request, urllib.error, urllib.parse
from html.parser import HTMLParser

src, out = sys.argv[1], sys.argv[2]
UA = "Mozilla/5.0 (Linux; Android 8.1.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
ctx = ssl.create_default_context()

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None
plain = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), NoRedirect)
follow = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx))

def get(url, opener, limit):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with opener.open(req, timeout=10) as r:
        return r.status, r.geturl(), r.headers.get("Content-Type", ""), r.read(limit)

def kind(b):
    if b[:8] == b"\x89PNG\r\n\x1a\n": return "png", struct.unpack(">II", b[16:24]) if len(b) >= 24 else (0, 0)
    if b[:3] == b"\xff\xd8\xff": return "jpg", (0, 0)
    if b[:6] in (b"GIF87a", b"GIF89a"): return "gif", struct.unpack("<HH", b[6:10])
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP": return "webp", (0, 0)
    if b[:4] == b"\x00\x00\x01\x00":
        n = struct.unpack("<H", b[4:6])[0]; best = 0
        for i in range(n):
            w = b[6 + 16 * i] if 6 + 16 * i < len(b) else 0
            best = max(best, w or 256)
        return "ico", (best, best)
    return None, None

def verify(url):
    """The url's final address and its size if it serves a decodable image."""
    try:
        for _ in range(4):
            try:
                status, final, ctype, body = get(url, plain, 600000)
            except urllib.error.HTTPError as e:
                if e.code in (301, 302, 303, 307, 308) and e.headers.get("Location"):
                    url = urllib.parse.urljoin(url, e.headers["Location"])
                    if not url.startswith("https://"): return None
                    continue
                return None
            if status != 200: return None
            k, size = kind(body)
            if not k or len(body) >= 600000: return None
            return url, max(size) if size else 0
    except Exception:
        return None
    return None

class Links(HTMLParser):
    def __init__(self):
        super().__init__(); self.icons = []; self.base = None
    def handle_starttag(self, tag, attrs):
        a = dict((k.lower(), v or "") for k, v in attrs)
        if tag == "base" and a.get("href") and self.base is None: self.base = a["href"]
        if tag != "link" or not a.get("href"): return
        rel = a.get("rel", "").lower().split()
        if not ({"icon", "apple-touch-icon", "apple-touch-icon-precomposed"} & set(rel)): return
        href = a["href"].strip()
        if href.startswith("data:") or href.lower().split("?")[0].endswith(".svg") or "svg" in a.get("type", ""): return
        m = re.findall(r"(\d+)x\d+", a.get("sizes", ""))
        size = max(map(int, m)) if m else (180 if "apple-touch-icon" in " ".join(rel) else 32)
        self.icons.append((href, size, "apple" in " ".join(rel)))

def score(size):
    # Best near 64 to 192; a bigger one is fine, a smaller one less so.
    return -abs(min(size, 256) - 128) if size >= 48 else size - 1000

def find(domain):
    tried = set()
    cands = []
    origin = "https://" + domain + "/"
    try:
        status, final, ctype, body = get(origin, follow, 1500000)
        origin = final
        if "html" in ctype or b"<html" in body[:2000].lower():
            p = Links()
            try: p.feed(body.decode("utf-8", "replace"))
            except Exception: pass
            base = urllib.parse.urljoin(final, p.base) if p.base else final
            for href, size, apple in p.icons:
                cands.append((urllib.parse.urljoin(base, href), size))
    except Exception:
        pass
    root = urllib.parse.urljoin(origin, "/")
    cands.sort(key=lambda c: -score(c[1]))
    found = []
    for url, _ in cands[:4] + [(root + "apple-touch-icon.png", 180), (root + "favicon.ico", 32)]:
        if url in tried or not url.startswith("https://"): continue
        tried.add(url)
        v = verify(url)
        if v:
            found.append(v)
            if v[1] >= 64: break
    if not found: return None
    return max(found, key=lambda v: score(v[1]))[0]

entries = []
for f in sorted(glob.glob(src + "/entries/*/*.json")):
    for name, e in json.load(open(f)).items():
        if e.get("domain"): entries.append((name, e["domain"], e.get("additional-domains", [])))
done = {}
try:
    for line in open(out):
        r = json.loads(line); done[r["domain"]] = r
except FileNotFoundError:
    pass
todo = [e for e in entries if e[1] not in done]
print(len(entries), "services,", len(todo), "to crawl", flush=True)
with open(out, "a") as sink, cf.ThreadPoolExecutor(64) as pool:
    futures = {pool.submit(find, d): (n, d, extra) for n, d, extra in todo}
    for i, fut in enumerate(cf.as_completed(futures)):
        n, d, extra = futures[fut]
        try: url = fut.result()
        except Exception: url = None
        sink.write(json.dumps({"name": n, "domain": d, "more": extra, "logo": url}) + "\n"); sink.flush()
        if i % 200 == 0: print(i, flush=True)
