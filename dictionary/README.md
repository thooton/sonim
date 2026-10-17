# Dictionary, for the Sonim XP3

An offline English dictionary for the XP3's 240x320 screen and d-pad, laid out as the [Dictionary.com app](https://play.google.com/store/apps/details?id=com.dictionary) is: its search capsule, its Word of the Day card, its recent searches, and a word's page with Definitions and Thesaurus tabs. It has no permissions and no dependencies.

| Key | Home and a word's page | Searching |
| --- | --- | --- |
| Menu | Open the search | Close it |
| OK | Home: open the Word of the Day. A word: say it (when the phone has text-to-speech). On the search capsule: open the search | Open the row picked, or else the best match |
| Up / Down | Scroll; Up from the top reaches the search capsule | Move through Clear and the rows |
| Left / Right | Definitions / Thesaurus | Move the cursor |
| Back | Back a page; leave from home | Close it and go back to where you were |

The rows change as you type: the word's dictionary form first when that is what you typed (mice: mouse, went: go, biggest: big), then every word that begins with what you typed. Red text means nothing matches. The words you open from the search are kept as Recent Searches (Clear empties them). The Word of the Day is picked from the date, so it changes at midnight and is the same all day.

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

To rebuild the data (the generated assets are already in the tree), from this directory:

```bash
curl -LO https://wordnetcode.princeton.edu/wn3.1.dict.tar.gz
tar xzf wn3.1.dict.tar.gz && mv dict wordnet
python3 tools/build_dict.py wordnet app/src/main/assets
```

WordNet licence: WordNet 3.1 Copyright 2011 by Princeton University. All rights reserved. The full notice is in [WORDNET_LICENSE](WORDNET_LICENSE) and ships inside the APK.

The type is IBM Plex Sans and IBM Plex Serif, the faces Dictionary.com's screenshots are set in, cut down to Latin-1 (`app/src/main/res/font`). They are under the SIL Open Font License 1.1, in [PLEX_LICENSE](PLEX_LICENSE), which also ships inside the APK.
