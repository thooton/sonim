"""Makes the QR pictures SelfTest reads, into resources/qr/, with expected.txt
listing each picture and the text it holds (or NONE).

    pip install segno pillow && python3 fixtures.py

Run from this directory. Only the tests use these; the app reads codes itself.
"""
import io
import math
import os
import random

import segno
from PIL import Image, ImageDraw, ImageFilter, ImageOps

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resources", "qr")
random.seed(7)
expected = []


def save(name, image, text, quality=None):
    path = os.path.join(OUT, name)
    if quality:
        image.convert("RGB").save(path, "JPEG", quality=quality)
    else:
        image.save(path, optimize=True)
    expected.append(f"{name}\t{text if text is not None else 'NONE'}")


def symbol(text, version=None, error="m", scale=4, border=4, mode=None):
    qr = segno.make_qr(text, version=version, error=error, mode=mode, boost_error=False)
    buffer = io.BytesIO()
    qr.save(buffer, kind="png", scale=scale, border=border)
    buffer.seek(0)
    return Image.open(buffer).convert("L"), qr.version


def perspective(image, corners, size):
    """Maps the picture's corners onto `corners` (TL, TR, BR, BL) in a size canvas."""
    w, h = image.size
    src = [(0, 0), (w, 0), (w, h), (0, h)]
    matrix = []
    for (x, y), (u, v) in zip(corners, src):
        matrix.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        matrix.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    b = [c for p in src for c in p]
    # Solve 8x8 by Gaussian elimination.
    a = [row + [b[i]] for i, row in enumerate(matrix)]
    for c in range(8):
        p = max(range(c, 8), key=lambda r: abs(a[r][c]))
        a[c], a[p] = a[p], a[c]
        for r in range(8):
            if r != c:
                f = a[r][c] / a[c][c]
                a[r] = [x - f * y for x, y in zip(a[r], a[c])]
    coeffs = [a[i][8] / a[i][i] for i in range(8)]
    return image.transform(size, Image.PERSPECTIVE, coeffs, Image.BICUBIC, fillcolor=255)


GITHUB = "otpauth://totp/GitHub:sam%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"

# Clean codes of many versions and every level.
words = "otpauth://totp/Example:alice@example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&n="
for version, error in [(1, "l"), (2, "m"), (3, "q"), (4, "h"), (5, "l"), (6, "q"), (7, "m"), (8, "h"),
                       (10, "q"), (14, "l"), (17, "h"), (21, "m"), (25, "q"), (30, "l"), (35, "h"), (40, "m")]:
    # Fill the symbol with bytes so the whole data area is used.
    text = None
    for length in range(2000, 0, -1):
        candidate = (words * 60)[:length] if version > 3 else ("12345678901234567890" * 5)[:length]
        try:
            if segno.make_qr(candidate, version=version, error=error, boost_error=False).version == version:
                text = candidate
                break
        except Exception:
            continue
    image, _ = symbol(text, version=version, error=error, scale=3 if version > 20 else 4)
    save(f"v{version:02d}{error}.png", image, text)

# Alphanumeric and numeric segments.
image, _ = symbol("HELLO WORLD 123 $%*+-./:", error="q")
save("alphanumeric.png", image, "HELLO WORLD 123 $%*+-./:")
image, _ = symbol("0123456789012345678901234567890123", error="l")
save("numeric.png", image, "0123456789012345678901234567890123")
image, _ = symbol("Ünïcödé 2FA ✓", error="m")
save("utf8.png", image, "Ünïcödé 2FA ✓")

# As a phone would see a code on a screen: rotated, at an angle, soft, uneven, noisy, JPEG.
base, _ = symbol(GITHUB, error="m", scale=10, border=4)
w, h = base.size
canvas = (900, 700)
corners = [(210, 120), (690, 90), (720, 600), (170, 640)]
photo = perspective(base, corners, canvas)
photo = photo.filter(ImageFilter.GaussianBlur(1.6))
light = Image.linear_gradient("L").resize(canvas).rotate(35, expand=False, fillcolor=128)
photo = Image.blend(photo, ImageOps.invert(light), 0.35)
pixels = photo.load()
for y in range(canvas[1]):
    for x in range(canvas[0]):
        pixels[x, y] = max(0, min(255, int(pixels[x, y] * 0.8 + 25 + random.gauss(0, 10))))
save("photo.jpg", photo, GITHUB, quality=70)

rotated = base.rotate(23, expand=True, fillcolor=255, resample=Image.BICUBIC).resize((520, 520))
save("rotated.png", rotated, GITHUB)

upside = base.rotate(180).resize((300, 300), Image.BILINEAR)
save("upside_down.png", upside, GITHUB)

small = base.resize((120, 120), Image.BILINEAR)
save("small.png", small, GITHUB)

save("inverted.png", ImageOps.invert(base).resize((400, 400)), GITHUB)
save("mirrored.png", ImageOps.mirror(base).resize((400, 400)), GITHUB)

# A Google Authenticator export: two time-based accounts and a counter-based one.
def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def field(number, value):
    if isinstance(value, int):
        return varint(number << 3) + varint(value)
    return varint(number << 3 | 2) + varint(len(value)) + value


def parameters(secret, name, issuer, algorithm, digits, kind):
    return (field(1, secret) + field(2, name.encode()) + field(3, issuer.encode())
            + field(4, algorithm) + field(5, digits) + field(6, kind))


import base64
import urllib.parse
payload = (field(1, parameters(b"Hello!\xde\xad\xbe\xef", "GitHub:sam@example.com", "GitHub", 1, 1, 2))
           + field(1, parameters(bytes(range(20)), "root", "AWS", 2, 2, 2))
           + field(1, parameters(b"counter!", "old", "Bank", 1, 1, 1))
           + field(2, 1) + field(3, 1) + field(4, 0) + field(5, 12345))
MIGRATION = "otpauth-migration://offline?data=" + urllib.parse.quote(base64.b64encode(payload).decode(), safe="")
image, _ = symbol(MIGRATION, error="l", scale=4)
save("migration.png", image, MIGRATION)

# A screenshot-like picture with the code small among other things.
shot = Image.new("L", (480, 800), 245)
draw = ImageDraw.Draw(shot)
for i in range(14):
    draw.rectangle((30, 40 + i * 50, 450, 60 + i * 50), fill=200)
code, _ = symbol(GITHUB, error="l", scale=4, border=2)
shot.paste(code, (130, 300))
save("screenshot.png", shot, GITHUB)

# Nothing to find.
noise = Image.effect_noise((400, 300), 60).filter(ImageFilter.GaussianBlur(2))
save("none.png", noise, None)

with open(os.path.join(OUT, "expected.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(expected) + "\n")
print("\n".join(e.split("\t")[0] for e in expected))
