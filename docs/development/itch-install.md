# itch.io install and version picker

Installing an itch.io game opens a version picker first. It lists the game's uploads from `/games/{id}/uploads`, with the platform, size and date of each.

- Windows builds install. A game with a single Windows build skips the picker and installs at once.
- Other builds (macOS, Android, web) open the game's page on itch.io.
- Linux builds are listed but cannot be installed yet.

## Free games

A free game does not need to be in the library. The game's number is read from the `itch:path` meta tag of its page (`games/<id>`), and both the upload list and the download work with the API key alone and no download key. This was checked against a live free game: the upload list answered 200 and a one-byte range request on the download answered 206.

A game that costs money and has no download key in the library is still refused.

## Pieces

`ItchGame` finds the game number and turns the upload list into builds. `ItchUploads.platforms` reads the platform from the upload's flags or `p_*` traits, and treats an upload with none as Windows. `ItchBuildPicker` is the pop-up. The chosen upload is carried in the item's `extra` as `uploadId`, and `ItchInstaller` downloads that upload.

If the upload the user chose is no longer in the game's list when the install starts, the install fails with "No Windows download for this game" and does not pick another build. Only an install with no chosen upload falls back to the newest Windows build. The saved queue keeps whether a game is free, so an interrupted free-game install resumes after a restart.

Before an install extracts, a leftover partial install of the same game (a folder whose marker said "installing" with this game's ID when the install started) is emptied, so a different build chosen after an interruption never mixes with files from the first. A finished or updating install, or another game's unfinished folder that happens to share the folder name, is never emptied.
