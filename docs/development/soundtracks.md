# Soundtracks

Steam soundtracks get their own tab under Library, a built-in music player, and an Android media session.

## Where soundtracks live

Steam installs a soundtrack to `<library>/steamapps/music/<installdir>`, next to a normal `appmanifest_<appid>.acf`. This holds for the internal Steam library and for a second library folder.

`Library.steamGames()` skips any manifest whose install directory exists only under `steamapps/music`. `Library.soundtracks()` returns exactly those entries, marked with `SteamGame.soundtrack`. While the Music tab is enabled, games and soundtracks therefore never mix in the Library tab, the launch drawer, game file sync or component preparation.

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

Steam's own library cache image first, then `AlbumArtwork`, `cover`, `folder` and similar files in the soundtrack folder. When none exists, the picture embedded in the first track that has one is used, so the grid shows it without opening the album. The player page shows the embedded picture of the track that is playing and falls back to the album cover for tracks without one. The media notification and the system media panel follow the same rule: while a track plays, its embedded picture replaces the item artwork in place, which does not restart playback, unless a cover was chosen in the art changer. The SteamGridDB artwork changer applies to Steam soundtracks like any other Steam entry.

## Player page

The layout follows Steam's soundtrack page and uses the DroidDeck theme colors and shapes, so every theme works. Cover, title, artist and total length sit on the left with seek, shuffle, previous, play or pause, next, repeat and volume. The track list with disc headers sits on the right. Portrait stacks the two. The action buttons (game files, shortcut, edit art, uninstall) use the same wide pill style as Steam's See More button.

Controller: d-pad moves between buttons and rows, A confirms, B goes back, the sliders respond to left and right.

Shortcuts use `droiddeck://soundtrack/<appid>` and open the soundtrack page.

## Playback and Android media controls

`SoundtrackService` is a media3 `MediaSessionService` with an `ExoPlayer`. It runs as a foreground service of type `mediaPlayback`, so playback keeps going when DroidDeck is closed and the notification and lock screen controls show the cover, title and artist. `SoundtrackController` connects a `MediaController` to it and exposes Compose state.

Launching a game pauses the music unless Enable ingame is on (see Playing during games). Audio focus and headphone unplug are handled by ExoPlayer.

Added dependencies: `androidx.media3:media3-exoplayer` and `androidx.media3:media3-session` 1.3.1.

## Playback options

The player page has a Playback options button that opens a panel with:

- Speed and pitch, from 0.5x to 2x in 0.05 steps, applied through ExoPlayer's playback parameters.
- Skip silence.
- A sleep timer for 15, 30 or 60 minutes or the end of the current track. The timer runs in `SoundtrackService`, so it still fires when DroidDeck is closed.
- An equalizer with the device presets, one slider per band, and bass boost where the device supports it. The effects are attached to the player's audio session in the service.

Settings are saved in `soundtrack_settings` and sent to the service whenever the controller connects. The last album, track and position are saved too, so the play button on an album resumes where it stopped. The position is saved on track changes, pauses and seeks.

## Playing during games

Setup › Launcher has an Enable ingame toggle, shown while soundtracks are on. When it is off, launching a game pauses the soundtrack. When it is on, the soundtrack keeps playing and the service stops requesting audio focus, so the game's own audio mixes with it. The setting is sent to the service with the `INGAME` session command and is read again when the service starts.

## Searching and sorting

Inside a soundtrack, a search field above the track list filters by title, artist and album, and a sort button orders the list by album order, title or length. The Music tab search also looks up tracks: soundtracks and collections whose names match come first, followed by a Tracks section.

## Favorites and collections

- The heart under the transport controls adds or removes the current track from Favorites. The bookmark button next to it opens the Saved panel to add the track to a collection or create one.
- The Music tab has Collections and Favorites chips. The bookmark in the Music header opens the same Saved panel to open or create a collection.
- Long-press a track to select. The selection bar has Select all, Deselect all, Add to collection, Add to favorites and, on a collection page, Remove from collection.
- A collection page has a Manage button to rename, add tracks, remove tracks or delete. Favorites cannot be renamed or deleted.
- Collections are stored in `soundtrack-collections.json` in the app files directory as snapshots of path, title, artist, album, length and cover. Tracks whose files are gone are skipped when a collection is played.
- Collection and Favorites playback does not overwrite the saved resume position of an album.

