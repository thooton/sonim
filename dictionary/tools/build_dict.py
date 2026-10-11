#!/usr/bin/env python3
"""Turns a WordNet 3.1 database into the app's three assets.

    python3 tools/build_dict.py wordnet app/src/main/assets

Each meaning (synset) is stored once, however many headwords share it, which
roughly halves the data compared with writing every entry out in full.

All three are deflated with zlib, which Android can inflate without any
library, and are stored uncompressed in the APK (noCompress) so the app can
seek straight to a block.

words     deflated text, one line per headword in sorted order:
          <hex POS mask><headword>\t<synset> <synset> ...
          The mask is 1 noun, 2 verb, 4 adjective, 8 adverb. Synsets are listed
          noun senses first, then verb, adjective, adverb, each most common first.
synsets   int32 block count, int32 block offsets (count + 1, from the start of
          the file), then the blocks. Block k is the deflated text of synsets
          k*BLOCK .. k*BLOCK+BLOCK-1, separated by '\n', each one
          <POS letter n/v/a/r><gloss>\t<word>|<word>|...
          Synsets are numbered in the order the sorted headwords first use
          them, so paging through the book reads the blocks in sequence.
morph     deflated text, WordNet's exception lists (irregular forms such as
          mice, went, better): <POS letter n/v/a/r>\t<form>\t<base>[\t<base>...]
"""
import os, re, struct, sys, zlib

BLOCK = 128
POS = ['noun', 'verb', 'adj', 'adv']
LETTER = {'noun': 'n', 'verb': 'v', 'adj': 'a', 'adv': 'r'}

def name(lemma):
    return lemma.replace('_', ' ')

def lines(path):
    with open(path, encoding='latin-1') as f:
        for line in f:
            if not line.startswith('  '):  # the licence header
                yield line.rstrip('\n')

def deflate(text):
    return zlib.compress(text.encode('latin-1'), 9)

def main(src, dst):
    synsets = {}  # (pos, offset) -> record text
    for pos in POS:
        for line in lines(os.path.join(src, 'data.' + pos)):
            head, _, gloss = line.partition(' | ')
            f = head.split()
            letter = 'a' if f[2] == 's' else f[2]  # satellites are adjectives
            words = [name(re.sub(r'\(.*\)$', '', f[4 + 2 * i])) for i in range(int(f[3], 16))]
            synsets[pos, f[0]] = letter + gloss.strip() + '\t' + '|'.join(words)

    entries = {}  # headword -> [mask, [(pos, offset), ...]]
    for bit, pos in enumerate(POS):
        for line in lines(os.path.join(src, 'index.' + pos)):
            f = line.split()
            e = entries.setdefault(name(f[0]), [0, []])
            e[0] |= 1 << bit
            e[1] += [(pos, o) for o in f[-int(f[2]):]]

    order = sorted(entries)
    number = {}  # (pos, offset) -> id, in order of first use
    lines_out = []
    for word in order:
        mask, refs = entries[word]
        ids = [number.setdefault(r, len(number)) for r in refs]
        lines_out.append('%x%s\t%s' % (mask, word, ' '.join(map(str, ids))))
    records = [None] * len(number)
    for r, i in number.items():
        records[i] = synsets[r]

    os.makedirs(dst, exist_ok=True)
    with open(os.path.join(dst, 'words.dat'), 'wb') as f:
        f.write(deflate('\n'.join(lines_out)))

    blocks = [deflate('\n'.join(records[i:i + BLOCK])) for i in range(0, len(records), BLOCK)]
    offsets = [4 + 4 * (len(blocks) + 1)]
    for b in blocks:
        offsets.append(offsets[-1] + len(b))
    with open(os.path.join(dst, 'synsets.dat'), 'wb') as f:
        f.write(struct.pack('>%di' % (len(offsets) + 1), len(blocks), *offsets))
        f.writelines(blocks)

    morph = []
    for pos in POS:
        for line in lines(os.path.join(src, pos + '.exc')):
            morph.append('\t'.join([LETTER[pos]] + [name(w) for w in line.split()]))
    with open(os.path.join(dst, 'morph.dat'), 'wb') as f:
        f.write(deflate('\n'.join(morph)))

    sizes = {n: os.path.getsize(os.path.join(dst, n + '.dat')) // 1024 for n in ('words', 'synsets', 'morph')}
    print('%d headwords, %d synsets, %d exceptions; %s KB'
          % (len(order), len(records), len(morph), sizes))

if __name__ == '__main__':
    main(*sys.argv[1:])
