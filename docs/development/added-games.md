# Added games

Windows games the user adds to DroidDeck's Games tab, each a non-Steam shortcut in the
Steam client that runs under `droiddeck-proton-arm64`. They show with the Custom chip.

## Adding

- **+ beside "Games N"** opens the in-app picker (`ExePickerDialog`, never Android's
  system picker): the storage rows (Internal storage, each card, free space), then the
  chosen storage from its root: every folder A-Z (dot-folders hidden, unreadable ones
  greyed), `.exe` files only. The dialog keeps the width it opened with; long names are cut
  short; it grows to 70% of the screen and scrolls. Red X closes; B / Back goes up a
  folder, then closes.
- **Picking an exe** (`AddedGames.addExe`): the game it already is gets selected; an exe in
  a listed game's folder becomes that game's target; otherwise the exe's folder becomes a
  game (`files/added-exes.json`), registered at once (the listing the shortcuts writer
  reads, and a running client over DevTools) and selected. A path the session cannot see
  (internal storage maps to `/root/Storage`; cards only through Games folders, the Steam
  library and store roots) is refused with one line.
- **"Add all games in this folder"** (top row of a folder with subfolders,
  `AddedGames.addFolder`): each subfolder with an exe becomes a game with
  `GameExePicker`'s best exe, kept as an entry of its own (not a Games folder); subfolders
  already listed are counted, not added. A summary follows: games added, how many were
  there already, one row per game (cover, name, exe, "?" when unsure); a row opens that
  game's editor, straight on Target when unsure, with back to the summary.
- Games folders added before this (Steam settings had a Games section) are migrated once
  (`GamesFolderMigration`, at app start and before any scan): each game in one becomes an entry
  as "Add all games in this folder" would make it, keeping everything keyed by its folder (the
  chosen exe, the shortcut appid, name, Start in, launch options, art, a removal). The folders
  stay bound into the session at the guest paths they had (`/root/Games/<name>`,
  `SessionPrefs.gameFolderBinds`), so no exe path or shortcut changes. They are no longer Steam
  libraries - the session's library list leaves them out and `droiddeck-steam-library` forgets
  them - unless Steam installed a game into one (a manifest in its `steamapps/`), which then
  stays a library. Log: `games: migrated <root> → N games`.

## Exe and name

`GameExePicker` walks up to five folders down (nearest first, 400 entries, no symlinks,
past installer / redistributable / extras folders), drops junk exe names and scores the
rest (named after the folder, Unreal `-Shipping` and `Binaries/Win64`, `bin/`, launchers
and junk lower, deeper lower, bigger higher). Best under 60, or a runner-up within 15:
uncertain, shown as "?". `GameIdentifier` / `PeVersionInfo` give the title (Steam
manifest by installdir, GOG info, the exe's version resource), else the folder name. The
shortcut appid is stored per folder at the first scan and never changes after.

## Editor

The ✎ on a Custom game's page (`AddedGameEditor`) mirrors Steam's Properties for a
non-Steam game: Name, Target (ranked exes, "?", Other exe… opening the picker in the
game's folder), Start in (automatic = the exe's folder), Launch options, Artwork (Cover,
Background, Logo, Icon, each with its thumbnail and source), and Remove (two presses; the
files stay; adding it again brings it back). `AddedGameEdits` writes each change through
the same shortcut: the listing (`name`, `exe`, `dir`, `launch`, `art`, the stored `appid`)
and, with a client running, `SetShortcutName` / `Exe` / `StartDir` / `LaunchOptions` and
`SetCustomArtworkForApp` / `SetShortcutIcon` on that appid. A new target updates the
existing shortcut; there is never a second one.

The shortcuts writer (`droiddeck-steam-shortcuts`) keeps what the user set in Steam: a
field follows the app only while Steam still has what the app last asked for. It records
Steam's AppName, Exe, StartDir and LaunchOptions in `.droiddeck-shortcuts.json`; a scan
takes a Steam-side edit over as the app's own once (`AddedGames.adoptSteamEdits`), so one
value holds on both sides.

## Art

Automatic: the game folder's own images, then Steam (the appid the files name, else a
store search whose result has the same name), then SteamGridDB, then the exe's icon. The
art chooser (an Artwork row) shows the game folder's images, Steam's official piece and
SteamGridDB's best few (fetched when it opens); a tap applies one, "Pick image…" takes a
file through the same picker (png, jpg, jpeg, webp, ico), "Reset to automatic" drops the
choice. A choice is a copy in `files/added-art/<appid>/chosen-<slot>.<ext>`, its source in
the preferences.

Setup › Launcher › Games holds the Artwork switch (automatic fetching) and the
SteamGridDB API key: the user's own, sealed with `CredentialCipher`, wins over the build's
(`BuildConfig.SGDB_API_KEY` from the `SGDB_API_KEY` secret); with neither, SteamGridDB is
skipped.

## Tests

`AddedExesTest`, `AddedGameEditsTest` (bulk add and skip, edits to the listing on a stable
appid, Steam-side read-back, art choice and reset), `AddedGamesCandidatesTest`,
`AddedGameArtLookupTest` (key precedence), `GameIdentifierTest`, `PickListingTest`, and
`tools/tests/test_steam_shortcuts.py` for the writer.
