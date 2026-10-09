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
`.droiddeck-epic-code` when present, deletes it, and appends
`-AUTH_LOGIN=unused -AUTH_PASSWORD=<code> -AUTH_TYPE=exchangecode`; `StoreLaunch.prepare` mints the
code right before a launch from the Stores page or the Games tab (the Steam client's own Play
button gets the offline identity arguments only).

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
