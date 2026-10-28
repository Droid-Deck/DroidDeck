# itch.io install and version picker

Installing an itch.io game opens a version picker first. It lists the game's uploads from `/games/{id}/uploads`, with the platform, size and date of each.

- Windows builds install. A game with a single installable build skips the picker and installs at once.
- Linux builds install too. A build that lists both Windows and Linux counts as Windows, and Windows builds are listed before Linux ones.
- Other builds (macOS, Android, web) open the game's page on itch.io.

## Linux builds

The installer takes the platform from the chosen upload. A Linux-only upload is extracted like any other, and `StoreExe.pickLinux` picks the program: a file with an x86 ELF header (64-bit `0x3E` or 32-bit `0x03`, little-endian), skipping `.so` libraries, hidden folders and the same helper names `StoreExe.pick` skips, preferring a name that matches the title, then the shallowest, then the largest. An archive with no such file fails with "no exe".

The install record's `extra` gets `"platform": "linux"`. A record without it is a Windows install, so existing installs are unchanged. `AddedGames` turns the record into `Game.linux`, and the listing carries `"linux": true` for those games only.

The game is still a Steam shortcut and starts through Steam. Only its compatibility tool differs:

- Live, `SteamLiveShortcuts` passes `droiddeck-fex` to `SpecifyCompatTool` for a Linux game, or `droiddeck-direct` when the running client is the x86-64 one. Every other game keeps the Proton tool.
- At session start, `droiddeck-steam-shortcuts` passes the appids of listing entries with `"linux": true` to `register_default(linux=...)`, which maps just those to the Linux tool. A tool the user already chose for an appid is kept, as for any other mapping.

Starting a Linux game without Steam is not offered, because that path runs the program through Proton.

The Properties dropdown does not list `droiddeck-fex` for a non-Steam shortcut, because the tool is declared linux to linux and Steam treats shortcuts as Windows programs. The tool is set from the app instead. On a device, a shortcut for an x86-64 Linux Unity game set this way was mapped (`Mapping AppID ... to tool "droiddeck-fex"`), launched through `droiddeck-fex-tool waitforexitandrun`, and ran with no execute bit on the file.

## Free games

A free game does not need to be in the library. The game's number is read from the `itch:path` meta tag of its page (`games/<id>`), and both the upload list and the download work with the API key alone and no download key. This was checked against a live free game: the upload list answered 200 and a one-byte range request on the download answered 206.

A game that costs money and has no download key in the library is still refused.

## Pieces

`ItchGame` finds the game number and turns the upload list into builds. `ItchUploads.platforms` reads the platform from the upload's flags or `p_*` traits, and treats an upload with none as Windows. `ItchBuildPicker` is the pop-up. The chosen upload is carried in the item's `extra` as `uploadId`, and `ItchInstaller` downloads that upload.

If the upload the user chose is no longer in the game's list when the install starts, the install fails with "No Windows download for this game" and does not pick another build. Only an install with no chosen upload falls back to the newest Windows build, or to another build when the game has no Windows one. The saved queue keeps whether a game is free, so an interrupted free-game install resumes after a restart.

Before an install extracts, a leftover partial install of the same game (a folder whose marker said "installing" with this game's ID when the install started) is emptied, so a different build chosen after an interruption never mixes with files from the first. A finished or updating install, or another game's unfinished folder that happens to share the folder name, is never emptied.
