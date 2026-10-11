# Dictionary, for the Sonim XP3

An offline English dictionary laid out like a book, one headword per page, for the XP3's 240x320 screen and d-pad. It has no permissions and no dependencies.

| Key | Reading | Searching |
| --- | --- | --- |
| Left / Right | Previous / next word (hold to leaf through) | Move the cursor |
| Up / Down | Scroll the page | Scroll the page |
| Menu | Open the search box | Close it, staying on the word found |
| OK | | Close it, staying on the word found |
| Back | Exit | Close it and go back to where you were |

The page changes as you type. A search goes to the word itself, or else to its dictionary form (mice → mouse, went → go, dogs → dog, biggest → big), or else to the first word that begins with what you typed. Red text means nothing matches. The app reopens on the last word you read.

## Data

[WordNet 3.1](https://wordnet.princeton.edu/) from Princeton: 147,478 headwords, including about 64,000 phrases ("ice cream", "kick the bucket", "a priori"). Each headword has every meaning in its parts of speech, with examples and synonyms. WordNet covers nouns, verbs, adjectives and adverbs only, so words like "the" and "of" are not in it.

`tools/build_dict.py` turns the WordNet database into `app/src/main/assets` (6 MB, from 22 MB of text). Each meaning is stored once and shared by all its synonyms. Meanings are deflated in blocks of 128 so the app can open any page without unpacking the rest. Deflate is built into Android, so no decompression library is needed. The script's docstring documents the format.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with this machine's Android debug key, which is enough for sideloading; keep `~/.android/debug.keystore` to install updates over it. It also appears in `../launcher` (App Drawer).

To rebuild the data (the generated assets are already in the tree):

```bash
curl -LO https://wordnetcode.princeton.edu/wn3.1.dict.tar.gz
tar xzf wn3.1.dict.tar.gz && mv dict wordnet
python3 tools/build_dict.py wordnet app/src/main/assets
```

WordNet licence: WordNet 3.1 Copyright 2011 by Princeton University. All rights reserved. The full notice is in [WORDNET_LICENSE](WORDNET_LICENSE) and ships inside the APK.
