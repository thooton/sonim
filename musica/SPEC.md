# Música

## Songs
- Songs are read from a folder named `Musica` (any case, with or without the accent) at the top of the SD card and of the phone's storage, and its subfolders up to four deep. MP3 is the format meant; M4A, AAC, OGG, Opus, FLAC and WAV play too.
- A song's lyrics are the `.srt` beside it with the same name (case and accents ignored), or failing that a tagged one such as `Song.es.srt` (Spanish preferred).
- Title, artist, album and cover come from the file's tags. With no title tag, `Artist - Title.mp3` is split and a leading track number (`01 - `, `3. `) dropped. With no cover, one is drawn from the title in colours taken from it.
- SRTs in UTF-8 (with or without BOM), UTF-16 with BOM and Windows-1252 read correctly. HTML-style and `{\an8}` tags are dropped; a cue with two lines stays one block.
- The list reloads each time the app comes back to the front.

## Library
- White, titled "Songs", with Play and Shuffle capsules and the songs A to Z (Spanish order: accents, case, leading ¡ ¿ and El/La/Los/Las/The ignored).
- Each row: cover, title, artist (or "Unknown artist"), and "· No lyrics" when there's no SRT. The song loaded has a red title and moving bars on its cover (still when paused).
- Up/Down move a grey row one song; Up from the first song reaches the capsules, Left/Right switch between them. OK on a song plays it from the start (or, if it's the one loaded, carries on) and opens Now Playing. Play plays all in order from the first; Shuffle shuffles all.
- The mini player floats at the foot whenever a song is loaded: cover, title, artist, play/pause and next icons.
- No folder or no songs: "No songs yet" with where songs go. Storage refused: "No access to storage".
- Back leaves the app; the music keeps playing, with a notification (previous, play/pause, next) that reopens Now Playing.

## Now Playing
- The cover blurred to colour fills the screen; the cover, title, artist, a progress bar with elapsed and "-remaining" times, and back/play-pause/ahead icons.
- OK plays/pauses. Left goes back to the start of the song, or to the previous song in its first 3 s; Right goes to the next song. Held, Left/Right seek through the song and settle on release. 4/6 go back/ahead 5 s. Down opens the lyrics. Back returns to the library.

## Lyrics
- The cover shrinks into a header; the lines below are large and bold. Only the line being sung is bright; the rest are dim, and blurred two or more lines away. The page scrolls to keep the sung line near the top. A gap of 6 s or more (also before the first line) shows Apple's three dots, which fill in as the break passes.
- Up/Down pick a line: it's highlighted with its start time, and the page follows the pick instead of the song until OK or 5 s pass. OK on a picked line plays from its start; otherwise OK plays/pauses.
- Left goes back to the start of the line being sung (to the previous line if it began under 0.8 s ago); Right goes to the next line. 4/6 go back/ahead 5 s. Back returns to Now Playing.
- A song without an SRT says "No lyrics for this song" and "<name>.srt isn't in Musica".

## Options (Menu)
- A sheet over the screen (light over the library, dark over the player) with the song at its head: Lyrics (on/off: opens or closes the lyrics), Playback speed (1×, 0.9×, 0.75×, 0.5×, pitch kept), Repeat (Off, All, This song), Shuffle (Off, On), Sleep timer (Off, 15, 30, 60 min, End of song; shows minutes left).
- Up/Down move; OK and Right step a setting on, Left back. Back or Menu close it. Speed, repeat and shuffle are kept between runs.

## Playback
- The last song and its place are kept: reopening the app shows it paused in the mini player.
- Playback pauses when headphones are unplugged and for a call or another app's audio (resuming after a brief interruption), and answers headset buttons.
- A file that can't be played says "Can't play <name>". If Android refuses to read the card, the app asks for access to it once.
