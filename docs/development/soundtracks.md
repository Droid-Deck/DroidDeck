# Soundtracks

Steam soundtracks get their own tab under Library, a built-in music player, and an Android media session.

## Where soundtracks live

Steam installs a soundtrack to `<library>/steamapps/music/<installdir>`, next to a normal `appmanifest_<appid>.acf`. This holds for the internal Steam library and for a second library folder.

`Library.steamGames()` skips any manifest whose install directory exists only under `steamapps/music`. `Library.soundtracks()` returns exactly those entries, marked with `SteamGame.soundtrack`. Games and soundtracks therefore never mix in the Library tab, the launch drawer, game file sync or component preparation.

Folders added by hand (Soundtracks tab, plus button) are stored in `added-soundtracks.json` and carry a negative app id, so they cannot collide with a Steam app id.

## Turning it off

Setup > Launcher > Soundtracks. When off, the tab disappears and installed Steam soundtracks show in Library again, as they did before.

## Tracks, discs and duplicates

`Soundtrack.load()` walks the folder (four levels deep) for mp3, flac, ogg, opus, wav, m4a and aac. Tags come from `MediaMetadataRetriever` and are cached per folder in `files/soundtrack-index`, keyed by path, size and modification time.

`Soundtrack.group()` is pure and unit tested:

1. The same track in several formats collapses to one. Identity is disc and track number when both tags exist, otherwise the cleaned title. Lossless wins over lossy.
2. Disc comes from the disc tag, otherwise from the subfolder (natural order), labelled with the folder name when it is numeric (for example `3+4`). A folder with no subfolders is one disc and shows no disc headers. Tags missing on the kept copy are filled from its duplicates, since FLAC files often use tag names Android does not read.
3. Tracks sort by the number at the start of the file name, then by track tag, then by name. This matches Valve's soundtrack documentation, which says the Steam music player takes play order from file names and not from metadata. Numbers shown on screen are positions inside the disc.
4. Titles come from the tag, otherwise from the file name with the artist, album and track number removed.

## Cover art

Steam's own library cache image first, then `AlbumArtwork`, `cover`, `folder` and similar files in the soundtrack folder. The SteamGridDB artwork changer applies to Steam soundtracks like any other Steam entry.

## Player page

The layout follows Steam's soundtrack page and uses the DroidDeck theme colors and shapes, so every theme works. Cover, title, artist and total length sit on the left with seek, shuffle, previous, play or pause, next, repeat and volume. The track list with disc headers sits on the right. Portrait stacks the two. The action buttons (game files, shortcut, edit art, uninstall) use the same wide pill style as Steam's See More button.

Controller: d-pad moves between buttons and rows, A confirms, B goes back, the sliders respond to left and right.

Shortcuts use `droiddeck://soundtrack/<appid>` and open the soundtrack page.

## Playback and Android media controls

`SoundtrackService` is a media3 `MediaSessionService` with an `ExoPlayer`. It runs as a foreground service of type `mediaPlayback`, so playback keeps going when DroidDeck is closed and the notification and lock screen controls show the cover, title and artist. `SoundtrackController` connects a `MediaController` to it and exposes Compose state.

Launching a game pauses the music. Audio focus and headphone unplug are handled by ExoPlayer.

Added dependencies: `androidx.media3:media3-exoplayer` and `androidx.media3:media3-session` 1.3.1.
