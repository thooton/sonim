#!/usr/bin/env python3
"""Turns Grascii's Anniversary Gregg shorthand dictionary into the app's asset.

    pip install grascii
    python3 tools/build_shorthand.py app/src/main/assets

Grascii (https://github.com/grascii/grascii, MIT) writes Gregg shorthand
outlines in ASCII. Of its dictionaries only the Anniversary edition's (1929)
is used.

shorthand  deflated text, one line per outline and translation, sorted by
           lowercased translation (then outline):
           <outline>\t<translation>\t<strokes>
           The translation is lowercased, but for "I" and its contractions and
           abbreviations ("Mr."). Strokes is the outline as Grascii's own
           parser reads it, one character per stroke, chr(0xC0 + i) for
           STROKES[i], and ^ for a disjoiner; annotations and aspirates are
           left out, since the search ignores them (as Grascii's does by
           default), as are boundaries (-) and intersections (\\).
           Shorthand.java holds the same STROKES.
"""
import os, re, sys, zlib

from grascii import grammar
from grascii.parser import GrasciiParser, InvalidGrascii

STROKES = ("A AU A&' A&E B CH D DD DF DM DN DT DV E EU F G I J JND JNT K L LD "
           "M MD MM MN MT N ND NG NK NT O OE P PND PNT R S SH SS T TD TH TM TN TV "
           "U V X XS Z").split()
assert set(STROKES) == grammar.STROKES


def lower(translation):
    words = []
    for w in translation.split(' '):
        keep = w == 'I' or w.startswith("I'") or '.' in w
        words.append(w if keep else w.lower())
    return ' '.join(words)

def strokes(parser, outline):
    out = []
    for token in next(parser.interpret(outline)):
        if isinstance(token, list):
            continue
        value = getattr(token, 'value', token)
        if value == grammar.DISJOINER:
            out.append('^')
        elif value in STROKES:
            out.append(chr(0xC0 + STROKES.index(value)))
    return ''.join(out)

def main(dst):
    root = os.path.join(os.path.dirname(grammar.__file__), 'dictionary')
    parser = GrasciiParser()
    entries = {}  # (translation, outline) -> strokes
    skipped = 0
    folder = os.path.join(root, 'anniversary')
    for letter in sorted(os.listdir(folder)):
        with open(os.path.join(folder, letter), encoding='utf-8') as f:
            for line in f:
                outline, _, translation = line.strip().partition(' ')
                translation = re.sub(r'\s+', ' ', translation.strip())
                key = (lower(translation), outline)
                if not outline or not translation or key in entries:
                    continue
                try:
                    entries[key] = strokes(parser, outline)
                except InvalidGrascii:
                    skipped += 1

    lines = ['%s\t%s\t%s' % (outline, translation, codes)
             for (translation, outline), codes
             in sorted(entries.items(), key=lambda e: (e[0][0].lower(), e[0][1]))]
    text = '\n'.join(lines)
    os.makedirs(dst, exist_ok=True)
    with open(os.path.join(dst, 'shorthand.dat'), 'wb') as f:
        f.write(zlib.compress(text.encode('latin-1'), 9))
    print('%d outlines (%d unreadable left out); %d KB'
          % (len(lines), skipped, os.path.getsize(os.path.join(dst, 'shorthand.dat')) // 1024))

if __name__ == '__main__':
    main(*sys.argv[1:])
