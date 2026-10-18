# Dictionary, for the Sonim XP3

An offline English dictionary for the XP3's 240x320 screen and d-pad, with Anniversary Gregg shorthand: a home page with the Word of the Day under a search capsule, each word's page with Definitions, Thesaurus and Shorthand tabs, and a search with recent searches and suggestions as you type, which reads what you type both as English and as a shorthand outline. It has no permissions and no dependencies.

| Key | Home | A word's page | Searching |
| --- | --- | --- | --- |
| Menu | A menu: Word of the Day or Search | A menu: Pronounce (read the word aloud) or Search | Close it |
| OK | Open the Word of the Day | | Open the row picked, or else the best match |
| Up / Down | Scroll; Up from the top moves into the search box | Scroll; Up from the top moves into the search box | Move through Clear and the rows |
| Left / Right | | Definitions / Thesaurus / Shorthand | Move the cursor |
| Back | Leave | Back a page | Close it and go back to where you were |

The Word of the Day is picked from the date: a plain word of six to twelve letters with an example among its meanings, the same all day and new at midnight. The search's rows change as you type: the word's dictionary form first when that is what you typed (mice: mouse, went: go, biggest: big), then every word that begins with what you typed. Red text means nothing matches. The words you open from the search are kept as Recent Searches (Clear empties them). The Thesaurus tab is WordNet's synonyms for each meaning, and is left out for a word that has none. Pronounce uses the phone's text-to-speech, and is greyed out if the phone has none for English.

## Shorthand

Outlines are written in [Grascii](https://github.com/grascii/grascii), Gregg shorthand spelled in ASCII (`'ABT` is habit, `A~` a reversed circle, `^` a disjoiner), from its Anniversary (1929) dictionary.

- **English to shorthand.** A word's Shorthand tab lists its outlines, each with the strokes Grascii reads in it (`' · A · B · T`). The 1,549 words the shorthand dictionary has and WordNet doesn't (could, theirs, into) are in the search too, and open on a page with only that tab.
- **Shorthand to English.** Whatever is typed in the search is also read as an outline: under Shorthand are the outlines it is, then the longer ones it begins, then those with similar strokes (each at most one step away in Grascii's similarity graph, as its search's uncertainty 1), each with its English. Case doesn't matter, and annotations and aspirates are ignored, as in Grascii's default search. Typed in capitals or with Grascii's marks in it, the Shorthand rows come first, and OK opens the first of them; otherwise they follow the words.

## Data

[WordNet 3.1](https://wordnet.princeton.edu/) from Princeton: 147,478 headwords, including about 64,000 phrases ("ice cream", "kick the bucket", "a priori"). Each headword has every meaning in its parts of speech, with examples and synonyms. WordNet covers nouns, verbs, adjectives and adverbs only, so words like "the" and "of" are not in it.

`tools/build_dict.py` turns the WordNet database into `app/src/main/assets` (6 MB, from 22 MB of text). Each meaning is stored once and shared by all its synonyms. Meanings are deflated in blocks of 128 so the app can open any page without unpacking the rest. Deflate is built into Android, so no decompression library is needed. The script's docstring documents the format.

## Build

From the repository root:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :dictionary:app:assembleRelease
adb install -r dictionary/app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the repository's shared key (`../signing/signing.gradle`), which is enough for sideloading and lets a build from any checkout install over another. It also appears in `../launcher` (App Drawer).

To rebuild the WordNet data (the generated assets are already in the tree), from this directory:

```bash
curl -LO https://wordnetcode.princeton.edu/wn3.1.dict.tar.gz
tar xzf wn3.1.dict.tar.gz && mv dict wordnet
python3 tools/build_dict.py wordnet app/src/main/assets
```

The shorthand asset comes from Grascii's Anniversary dictionary, from this directory:

```bash
pip install grascii
python3 tools/build_shorthand.py app/src/main/assets
```

Grascii licence: MIT, Copyright (c) 2020-2026 chanicpanic. The notice is in [GRASCII_LICENSE](GRASCII_LICENSE) and ships inside the APK.

WordNet licence: WordNet 3.1 Copyright 2011 by Princeton University. All rights reserved. The full notice is in [WORDNET_LICENSE](WORDNET_LICENSE) and ships inside the APK.

The type is IBM Plex Sans and IBM Plex Serif, cut down to Latin-1 (`app/src/main/res/font`). They are under the SIL Open Font License 1.1, in [PLEX_LICENSE](PLEX_LICENSE), which also ships inside the APK.
