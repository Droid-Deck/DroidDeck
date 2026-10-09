# Stores: GOG, Epic Games and Amazon Games in the launcher

How the Stores section (`app/src/main/java/com/droiddeck/launcher/stores/`, `ui/Stores*.kt`) fits
the rest of the app, and the on-disk contract that makes a store game an ordinary added game.

## Where a store game lives

```
<Games storage>/Games/<Store>/<title>/
    <game files>
    .droiddeck-store.json      the sidecar (below)
    .droiddeck-launch.bat      only when the game needs arguments or environment (Epic, Amazon)
    .droiddeck-epic-code       one-shot, written right before an Epic launch, read and deleted by the .bat
```

The default root is the app's internal storage - the runtime's tree, `/root/Games/Stores`, no bind
needed - whatever the session's Game storage setting says. When a card the app may write is in the
device (`GameStorage.options`), Install asks "Install to" (internal / the card, free space shown;
the last pick is only the dialog's default). A card's root is `<card app folder>/Games`; when the
card is the Steam library the library's bind (`/mnt/droiddeck-sd`) already covers it, otherwise
`SessionService` binds the root at `/mnt/droiddeck-stores/<volume uuid>`, so a shortcut's guest
path - and its appid - is the same from one session to the next. Every root is
scanned (`StoreInstallRoot.roots`): internal, every card, and the Steam library's `Games` folder for
installs an earlier build put there. `<Store>` is `GOG`, `Epic` or `Amazon`; `<title>` is the title
with unsafe characters dropped, at most 60 characters (`StoreInstallRoot.folderName`), chosen once;
a rerun (repair, update) lands on the folder whose sidecar carries the game's id, wherever it is.
An Epic install on a card keeps its in-flight chunks in `cacheDir/stores/epic/<id>/`: the
`chunkCacheDir` of both native calls (`EpicNative.run` fetches into it, `EpicNative.assemble`
writes the files from it and drops each chunk after its last use, then the folder), and of the
manager's own loops when the engine is not there; `""` keeps the cache beside the game, as an
internal install has it. The scratch folder is removed on cancel-with-delete and on uninstall.
The cache holds whole ~1 MiB chunk windows, shared with files this device does not install, so it
is often larger than the game (Metalstorm: 9.1 GB of chunks for 4.4 GB of files); the free-space
check, made after the delta pass, counts the missing chunks and the missing files on their own
volumes. A successful run removes the cache.

## The sidecar

```json
{
  "version": 1,
  "store": "epic",                    // gog | epic | amazon
  "id": "Samorost3",                  // the store's own id: GOG product id, Epic app name, Amazon product id
  "title": "Samorost 3",
  "exe": "Samorost3.exe",             // relative to the folder, forward slashes; what the icon is read from
  "launcher": ".droiddeck-launch.bat",// optional; what the shortcut runs instead of exe
  "args": ["-EpicPortal", "-epicusername=\"Name\"", "..."],
  "env": {"FUEL_DIR": "C:\\ProgramData\\Amazon Games Services\\Legacy"},
  "installVersion": "1.4.0",
  "installedAt": 1759900000000,
  "cover": "https://...", "hero": "https://...",
  "extra": {"namespace": "...", "catalogItemId": "..."}
}
```

An install writes the sidecar at its start with `"state": "installing"` (store, id, title, no exe)
and rewrites it finished at its end (exe, launcher, no state field = installed). A folder under a
store root whose sidecar says `installing`, or that has no sidecar at all, is an unfinished
install: `AddedGames.scan` leaves it out (it is neither a game nor a Custom folder), and the Stores
card and page offer **Resume install**, which reuses that folder - and an Epic `.chunks` beside it -
instead of starting a fresh one (`StoreInstallRoot.existingFolder`). A finished install being
repaired keeps its finished sidecar. The launcher and the finished sidecar are written before the
art and the Steam registration; a failure writing them fails the download with its message.

`StoreGameSidecar.parse` refuses anything whose `exe` or `launcher` would point outside the folder.
Every store install is a Steam shortcut; an `addToSteam` field in a sidecar from an earlier build is
read past and ignored.

## Registration with Steam

`AddedGames.scan` walks the store folders as it walks the user's added folders, and
`AddedGames.scanGame` reads the sidecar: the exe (the folder's guess only when that file is gone),
the title as the shortcut's name, the store as the game's `source`. From there the path is the one
an added folder takes: `session/added-games.json` → `droiddeck-steam-shortcuts` at the client's
next start. `StoreInstalls.register` rewrites the listing at install time, so a client that restarts
inside the running session already has it, and asks a running client to add the shortcut live over
its DevTools port (`SteamLiveShortcuts`: `SteamClient.Apps.AddShortcut`, our tag, the compat tool).
The appid Steam derives (CRC32 of the quoted exe plus the name, high bit set) is the one the
listing carries, so the live add and the writer never disagree. Uninstall removes the folder and the
listing drops the entry; the live client is asked to remove it too.

`Library.SteamGame.source` is `steam`, `gog`, `epic`, `amazon` or `added`; the Games tab shows it as
a chip on every row and in the hero.

## Launch

A Steam shortcut names an exe and nothing more, and the listing carries no launch options, so a
game that needs arguments or environment gets `.droiddeck-launch.bat` (`StoreLaunch.launcherText`):
`cd` into the exe's folder, `set` each variable, start the exe with the arguments and wait. Proton's
`steam.exe` shim hands a `.bat` to Wine's `cmd`. For Epic the script also reads
`.droiddeck-epic-code` when present, deletes it, and passes
`-AUTH_LOGIN=unused -AUTH_PASSWORD=<code> -AUTH_TYPE=exchangecode` on the game's command line only;
the variable it read the code into is cleared on that same line, so nothing of it is left in the
game's environment. A launcher from an earlier build is rewritten before the next Epic launch.

The code is minted for every launch, wherever it starts. The Games tab and Stores call
`StoreLaunch.prepare` before they launch; the compat tool (`droiddeck-proton`,
`droiddeck-proton-wrap`) runs `droiddeck-store-launch` before Proton on every real launch, the Steam
client's Play button included. It finds the store launcher among Steam's arguments, reads the
sidecar beside it, and for an Epic game asks the app through `<session>/stores/req` and `resp`
(`StoreLaunchRequests` → `StoreLaunch.epicCode`), waiting up to 5 s; the app writes the code into
the game's folder itself. Each attempt logs `epic launch id=… code=yes|no reason=…`, never the code.

An Epic game's launch choices are the Epic card in the Games tab's launch settings - "Epic sign-in
(EOS)" (on) and "Launch offline" (off), stored in the sidecar's `epic` block so both launch paths
read the same thing - and "Resolve Epic sign-in", which opens Epic's account page behind its
sign-in in Android's browser.

A game's web links reach Android: `droiddeck-store-launch` sets the prefix's
`HKCU\Software\Wine\WineBrowser` `Browsers` to `/usr/local/bin/droiddeck-open-url`, which is also
`xdg-open` first on the game's PATH, and hands http(s) addresses to the app, which opens them in
Android's browser. EOS uses this when a sign-in needs a one-time step ("corrective action"): it
falls back to a browser, the user approves on the phone, and the game signs in. `BL_DEBUG_BROWSER=1`
in droiddeck-env adds `WINEDEBUG=+shell,+winebrowser`. Epic's EOS overlay is not provisioned (with
it games exited while loading); an `OverlayPath` an earlier build wrote is removed at launch. The
parked overlay work is on the branch `park/epic-eos-overlay`.

## Cloud saves

GOG and Epic games sync their saves with the store's cloud (`CloudSaves`, after Bannerlator's
managers): before a launch the cloud copy comes down, after the game exits what changed goes up.
`CloudPlan` decides per file against a baseline - each file's local MD5 and cloud MD5/time as of
this device's last sync:

- **No upload before a download has completed once on this device** (no baseline): a first launch
  creates new profile files that must never replace the real saves in the cloud.
- **The first download lets the cloud win** where the two differ.
- **A file changed on both sides since the baseline is a conflict:** neither side is overwritten;
  Manage saves shows "Cloud conflict" with Keep cloud / Keep local.
- Otherwise the side that changed wins; bytes that match move nothing.

Both sides are backed up before they are replaced: the local folder before a download writes into
it, the cloud copies before an upload replaces them, under
`files/stores/cloud-backups/<store>-<id>/local-*` and `cloud-*` (the last three of each).

A game launched before Proton has made its prefix (a first launch) gets its download deferred: the
request is answered at once (`result=deferred reason=no-prefix`) and the download runs as soon as
the prefix's user folder appears, within a minute. Every path logs
`cloud <store> <id> down|up result=ok|skipped|deferred|failed files= bytes= reason=`.

- **The folder:** GOG's public remote-config gives a location template per game (by its Galaxy client
  id), e.g. `<?APPLICATION_DATA_LOCAL_LOW?>/Hyperstrange/ELDERBORN`; Epic's catalog gives
  `CloudSaveFolder`, e.g. `{AppData}/Game/Saved/SaveGames`. `CloudSavePaths` expands it inside the
  game's prefix (`compatdata/<shortcut appid>/pfx/drive_c/users/steamuser`) or its install folder,
  case-insensitively, and refuses a path that leaves its boundary. A game whose store gives none
  has no cloud saves.
- **The services:** GOG `cloudstorage.gog.com/v1/<user>/<client>` with a token issued to the game's
  own client (the client secret is kept at install; the Galaxy token is the fallback); Epic's
  savesync data storage with signed read and write links.
- **Which launches:** `droiddeck-store-launch` recognises a store game by the launcher .bat Steam runs
  or, for a game without one (most GOG games), by its exe - the folders above it are searched for
  the sidecar.
- **When:** every launch runs `droiddeck-store-launch`, which asks the app (`cloud-down`) and waits up
  to 15 s; for a store game with cloud saves it returns 10, and the compat tool then waits for
  Proton instead of exec'ing it and runs `droiddeck-store-launch --exited` after, which asks for
  `cloud-up` without waiting. Each sync logs `cloud <store> <id> down|up files= bytes= result=`.
- **Uploads that do not depend on one hook:** the pre-launch step marks the game dirty in the app's
  storage; the upload runs on the first of the compat tool's exit request (`trigger=exit`), the
  session's end (`trigger=session-end`, after teardown, reading the prefix directly) or the app's
  next start for a mark a killed app left (`trigger=recovery`, not while a session runs). The mark
  goes only after an upload that went through or a final skip (off, no cloud saves, no baseline,
  not installed, nothing local).
- **Per game:** the store card in the Games tab's launch settings has "Cloud saves" (on), kept in the
  sidecar (`"cloud"`) and read by both sides; Manage saves shows the last sync with Upload and
  Download, or "No cloud saves".

## Downloads

`DownloadQueue` runs 1–3 jobs at a time (Setup / the Downloads page), each a store's whole install
(`DownloadJob.run`), with stages Manifest → Download → Verify → Install. Pause stops the job and
keeps the files; every store's install skips complete, verified files on the rerun, so resume is a
rerun. Cancel deletes the folder. `StoreDownloadService` holds a foreground notification while
anything runs. The native engine (`libdroiddeckstores.so`, `StoresNative`, `GogNative`, `EpicNative`,
`AmazonNative`; contract in `app/src/main/rust/stores/JNI.md`) is loaded on first use; without it
each manager runs its Java fetch loop.

## Settings

| Setting | Pref | Where |
|---|---|---|
| Show Stores in the rail | `SessionPrefs.gameStoresEnabled` (off) | Setup › Stores |
| Open a store on: Library · Store | `SessionPrefs.storesOpenTab` (library) | the chip row's cog, Setup › Stores |
| Download speed tier | `SessionPrefs.gameStoresSpeedTier` (fast) | the cog, Setup › Stores, Downloads |
| Downloads at a time | `SessionPrefs.gameStoresParallel` (1) | Downloads |
| Install to (last pick, dialog default only) | `SessionPrefs.storesInstallTarget` | the Install dialog |

Store log lines (the engines' included) go through `StoresState.logLine`, which redacts them
(`StoreLog.redactLine`) and writes them to logcat and to `filesDir/logs/stores/stores-<date>.log`
(`StoreLogFiles`: one file a day, seven days kept, ~2 MB each before it rolls to `.1`). Nothing
shows them in the app; the session's Share logs zip carries them under `stores/`.

Credentials live in `filesDir/stores/<store>/credentials.json` only (`StoreAccounts`); store URLs
are logged through `StoreLog.redactUrl`, which drops the query string where the signed tokens live.
