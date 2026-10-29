# itch.io updates

An installed itch game is checked against the uploads itch lists for it. A game with an update shows an
Update label on its Library tile and an Update button on its game page. A Check for updates button sits
in the top right of an installed itch game's page, next to the delete button, and checks that game only.

## Detection

The install record (`.droiddeck-store.json`) keeps three things from the install: the upload's
`updated_at` as `installVersion`, and `gameId`, `downloadKeyId` and `uploadId` in `extra`.
`ItchUpdates.evaluate` compares them with `GET /games/<id>/uploads`:

- the installed upload is still listed and its `updated_at` is later: an update to the same upload;
- the installed upload is gone: the newest Windows upload is offered as a replaced build;
- an install without an `uploadId` compares the newest Windows upload's stamp;
- an unknown installed version or no answer from itch never reports an update.

The automatic check walks the installed itch games one at a time, one second apart, and logs
`itch.io: checked <n> installed games, <m> with updates`. It runs when the itch tab opens, at most every
six hours. The button on a game page checks only that game, at any time, answers with a toast ("Update
available", "Up to date" or "Sync failed") and logs `itch.io: checked <title>, ...`. A game itch did not answer for keeps its last
result, and the six-hour clock only advances after a fully answered pass. Results are kept in the itch
preferences until the update is installed or the account signs out.

## Installing

The Update button reruns the normal install over the game's own folder, keeping its storage location.
For a replaced build the game page asks first and names the new file.

An update never extracts into the live game. The download goes to the scratch folder, as for any
install, and the archive is unpacked into `.droiddeck-update` inside the game folder. The new build is
checked there (an executable must be found) before a single game file is touched. A failed or cancelled
download or extraction removes the staging folder and leaves the game as it was.

Only then does `ItchStaging.merge` move the staged files into place, which are renames inside one
folder: a staged file replaces the file with the same path, directories are merged, and anything the new
build does not contain (saves, settings, the install record, launcher and art) stays. The install record
is marked updating just before the move, so an interruption in that short step is resumable, and the
finished record carries the new `installVersion` and `uploadId`. Free space for one extra copy of the
build is needed while the update runs.

A cancel that arrives before the files are moved in stops the update and removes the staging folder. Once
the move has started the update is past the point of no return: it finishes and writes the new record, so
a cancel during that short step cannot leave new files under an old record.
