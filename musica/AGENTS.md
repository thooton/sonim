# AGENTS.md

Música: a player for the Spanish songs in `Musica` on the SD card (or the
phone's own storage), each an `.mp3` with an `.srt` of the same name, whose
lyrics scroll along as the song plays. The look is Apple Music's, direction
B of `mockups/index.html` (renders in `mockups/screens/b-*.png`); the keys
are the table at the end of that page.

- `android/core` (plain Java, tested by `SelfTest`): `Lyrics` (SRT parsing,
  encodings, instrumental breaks of 6 s or more, the row at a time), `Scan`
  (finding songs and pairing their SRTs: same name ignoring case and
  accents, or a tagged `Song.es.srt`), `Names` (titles from file names,
  Spanish A to Z), `Song` (the catalogue and its tag cache), `Queue`
  (order, shuffle, repeat), `Pixels` (blur, veil, drawn-cover colours).
- `android/app`: `Player` is one process-wide MediaPlayer that outlives
  the activity (Back from the library leaves the music playing);
  `PlayerService` is only the foreground notification that keeps the
  process alive while it plays. `LibraryView`, `PlayerView` (Now Playing
  and the lyrics in one view, so the cover can shrink into the header) and
  `SheetView` (Menu's options) draw everything themselves on a Canvas, to
  the mockup's pixel sizes (which are the XP3's dp). They use the software
  layer, because Android 8.1 can't draw blurred text or shape shadows in
  hardware.
- `Files` reads songs and lyrics with a fallback to the card grant
  (`CardGrant`), since 8.1 can refuse plain reads of files another app put
  on the card.
- Fonts are static Inter instances made from `mockups/fonts/Inter.ttf`
  with fontTools (`varLib.instancer`, then `subset` to Latin); `OFL.txt` is
  their licence. Icons in `res/drawable/ic_*.xml` are the mockup's SVGs.
