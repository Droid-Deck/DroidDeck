# Stores: GOG, Epic Games and Amazon Games in the launcher

How the Stores section (`app/src/main/java/com/droiddeck/launcher/stores/`, `ui/Stores*.kt`) fits
the rest of the app, and the on-disk contract that makes a store game an ordinary added game.

## Library and Stores

The rail's Library (`ui/LibraryPage.kt`, rail key `games`) lists every owned game: installed ones
from the scanned library (`FrontEndState.steamGames`: Steam, added folders, store installs), then,
under Not installed, each signed-in store's `StoresState.library` products that are not. An
installed game claims its product by `"<source>:<storeId>" == CatalogItem.key`, so nothing shows
twice. Stores is only for getting games: a store opens on its storefront (search, then the shelves)
under a profile strip whose "Owned games in Library" opens the Library filtered to that store. The
Epic Games Launcher (open, settings, uninstall, install) lives in Epic's profile strip.

Every game opens one page (`ui/GameDetail.kt`) from either place: the hero and its actions by state
(installed, downloading, failed, unfinished, owned, not owned), then tabs LB/RB cycle through:
About, Achievements, Launch, Saves & cloud and Install, each hidden when it has nothing to show.
Library pages are `app:<appId>` for an installed game and `app:<store>:<id>` for a store product.

## Where a store game lives

```
<Games storage>/Games/<Store>/<title>/
    <game files>
    .droiddeck-store.json      the sidecar (below)
    .droiddeck-launch.bat      only when the game needs arguments or environment (Epic, Amazon)
    .droiddeck-epic-code       one-shot, written right before an Epic launch, read and deleted by the .bat
```

The default root is the app's internal storage - the runtime's tree, `/root/Games/Stores`, no bind
needed - whatever the session's Game storage setting says. **Setup > Storage > Default storage**
can send new installs straight to internal storage or the first external target; with **Ask every
time**, Install asks "Install to" when more than one target exists (internal / the card, free space
shown; the last pick is only the dialog's default). An existing install is always resumed in place,
and changing the default does not move it. A card's root is `<card app folder>/Games`; when the
card is the Steam library the library's bind (`/mnt/droiddeck-sd`) already covers it, otherwise
`SessionService` binds the root at `/mnt/droiddeck-stores/<volume uuid>`, so a shortcut's guest
path - and its appid - is the same from one session to the next. Every root is
scanned (`StoreInstallRoot.roots`): internal, every card, and the Steam library's `Games` folder for
installs an earlier build put there. `<Store>` is `GOG`, `Epic` or `Amazon`; `<title>` is the title
with unsafe characters dropped, at most 60 characters (`StoreInstallRoot.folderName`), chosen once;
a rerun (repair, update) lands on the folder whose sidecar carries the game's id, wherever it is.
An Epic install on a card keeps its in-flight chunks in `cacheDir/stores/epic/<id>/`;
`""` keeps the cache beside the game, as an internal install has it. The scratch folder is
removed on cancel-with-delete and on uninstall.

Preflight resolves an existing game's folder before selecting its target root, so a resume or
repair on a card checks and remembers that volume rather than internal storage. Fresh GOG and
Amazon installs can reject insufficient catalog-size space immediately; existing installs defer
to manifest checks of the files still needing work (GOG MD5, Amazon SHA-256 where supplied).
Free-space display probes the nearest existing ancestor without creating the target folder; if
the probe cannot determine space, it reports **Free space unknown** rather than blocking an install.
`StoreInstallRoot` uses `-1` for unknown and preserves a measured `StatFs` zero as **0 B free**.
Fresh GOG/Amazon preflight and the backends' own space checks (GOG in Kotlin, Epic and Amazon in
the Rust engine) reject known zero when bytes remain. The read-only probe walks existing ancestors without creating directories; a failed
`StatFs` probe can use a positive `File.usableSpace` fallback, but that API's ambiguous zero is
unknown, not a measurement of a full disk.
Preflight failures remain failed Downloads rows with Clear and Retry. Retry reruns preflight
outside the worker lifecycle and then enqueues the real backend; it never occupies a queue slot
with a placeholder job. Failures are kept in the Downloads UI, notification and logs rather than
duplicated as transient failure toasts. Active queue persistence and ordinary backend pause/retry
are unchanged.

Epic installs run in the Rust `epic` module of `libdroiddeckstores.so` (`epic/install/`), driven by
`EpicInstaller` through `EpicNative.install`. There is one engine; the old hidden `storesEpicMode`
switch is no longer read. Without the native library the Epic store cannot install (the facade
reports "the store engine is not available"). The design, and how it was written from a
functional spec only, is in [epic-cleanroom.md](epic-cleanroom.md).

A run, in order: CDN list from the build information (`EpicApiClient.getManifestApiJson`) →
manifest from the first CDN that serves it → parse (binary or JSON) → files selected by the device
language's install tags (`EpicInstallTags`) → staging recovery → the delta pass (a selected file is
up to date when it has the manifest size and SHA-1; a file without a manifest hash is always
rewritten) → space plan → chunk cache preparation → fetch schedule → download and write → the
installed record (`StoreInstalls.complete`). Resume, update and repair are the same rerun.

- **Schedule** (`epic/install/schedule.rs`). The pending files' parts form one timeline. A chunk is
  downloaded at its first use and feeds every later use in the same file directly. If it is used
  again in a later file it is kept, decompressed and verified, in the chunk cache, which has a byte
  budget; when keeping a chunk would exceed it, the cached chunk needed furthest in the future is
  dropped and downloaded again when needed. Chunks a previous run left in the cache are reused,
  soonest-needed first, as far as the budget allows.
- **Writing** (`epic/install/writer.rs`). Files are admitted strictly in order, a little ahead of
  the download, and a download is dispatched only once the file of its first use is admitted, so
  the number of half-written files stays small. Each admitted file is written into a unique
  `epic-<pid>-<seq>-<nanos>.stage` file in `<game>/.droiddeck-epic-staging/`; the file SHA-1 is
  computed in file order as parts land. A complete file is synced, size- and hash-checked, and
  renamed over its destination. Right before the first rename of the run `StoreInstalls.markUpdating`
  switches an installed record to `updating`; a refusal fails the run with nothing replaced.
- **Fetching** uses the shared `fetch_core` (adaptive window, CDN rotation, retries with back-off,
  stall and 429 cooldowns) with the speed tier's window and decode workers. Every chunk is checked
  (header, zlib stream end, exact size, SHA-1) before any byte reaches a file or the cache.
- **Space** (`epic/install/space.rs`). Free space is probed per volume (`statvfs`; unknown is never
  "no space"). The plan: net peak of the pending files in order (each old copy credited at its own
  swap), a cache budget of half the remaining space (at most 1 GiB, 256 MiB when unknown, 0 is
  allowed), an overlap budget that limits how many bytes of files replacing existing data are
  staged at once on an update (at least the largest replaced file), and a 64 MiB transient
  allowance. A card install keeps its cache in `cacheDir/stores/epic/<id>/` when internal storage
  can hold a useful cache and the game has no non-empty `.chunks` yet; otherwise the cache is
  `<game>/.chunks`. When the full plan does not fit, the run falls back to one file at a time with
  no cache (needs only the net peak); when even that does not fit the install is refused before any
  chunk request, naming the storage, the need, the free space and the shortfall.
- **Progress**: the bar counts pending-file bytes written (not compressed chunk bytes), at most every
  50 ms plus once per finished file. A resumed run starts the bar at what earlier runs kept; on a
  fresh install that includes the files already complete, so the bar carries on from the pause. The run logs `epic: plan …` and `epic: summary mode=adaptive|narrow
  files=… bytes_written=… avg_mbps=… fetches=… refetches=… peak_cache_mib=… hosts=…`.
- **Cancel / pause / failure**: the cancel flag is forwarded within 250 ms; the run stops its
  requests, joins its threads, removes every `.part` cache entry, and only then returns. On a cancel
  or a download error each unfinished stage file is kept in the staging folder as
  `<path id>-<sha1>-<size>.part`, with the written parts' offsets in `<same>.part.list`; the next run
  fetches only the other parts and hashes the whole file once it is complete (a partial that no
  longer matches the manifest, or whose file has no SHA-1 in it, is deleted). Any other failure removes the unfinished stage files. Swapped files stay; unswapped destinations keep their old content; the chunk cache
  stays for the next run. Success removes the cache and the staging folder (when only its marker is
  left).

The staging folder is recognised by its `owner` marker (`DroidDeck Epic staging v1` + LF). Before
the delta pass the run checks that the folder is a real directory at the expected canonical path,
that the marker matches and that every other entry is a plain `.stage`, `.part` or `.part.list` file
directly inside it; only then are the `.stage` orphans deleted. Anything unexpected fails the run without deleting. A manifest path
naming the staging folder, an absolute path or a `..` segment is refused. `.stage` / `.part` files
elsewhere in the game folder are never touched.

Amazon installs run in the Rust `amazon` module (`amazon/install/`), driven by `AmazonInstaller`
through `AmazonNative.install`; sign-in, the library sync and the size lookup call the same module
through `AmazonNative.call`. The design, and how it was written from a functional spec only, is in
[amazon-cleanroom.md](amazon-cleanroom.md).

An Amazon run, in order: download lookup for the entitlement (`GetGameDownload`: a CDN base URL with
a signed query, and the version id) → `manifest.proto` from that base (a 4-byte big-endian header
length, an opaque header, then an xz or LZMA-alone compressed protobuf) → parse (every file of every
package; paths refused when absolute, with a drive letter, `..`, empty segments, or naming
DroidDeck's own root files) → leftover `<file>.tmp` siblings removed → the check of files on disk (a
file is complete when it has the manifest size and SHA-256; the same test drives the space check and
the download plan) → space check (`Not enough free space: need …, only … free`; unknown free space
never blocks) → empty files created locally → every other pending file fetched from
`files/<sha256 hex>` under the base, one streamed GET each through the shared fetch core (the speed
tier's window; at least half the CPU cores verifying and writing), hashed as it arrives, written to
`<file>.tmp` and renamed over the destination after the size and hash checks → the installed record.
Before the first file is put in place the record is switched to `updating`, so an interrupted repair
is offered as "Resume install". There is no partial-file resume: a file is complete or fetched again
from its first byte. Files the manifest does not list are left alone.

## The sidecar

```json
{
  "version": 1,
  "store": "epic",
  "id": "Samorost3",
  "title": "Samorost 3",
  "exe": "Samorost3.exe",
  "launcher": ".droiddeck-launch.bat",
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
repaired keeps its finished sidecar until Epic reaches the first verified file swap. Immediately
before that swap Epic atomically changes the record to `"state": "updating"`, preserves launch
choices and identifiers, and clears `installVersion`. Network/hash failures or cancellation before
that point leave the old installed record and files intact. A later failure can leave a mixture of
verified new files and untouched old files: replacement is atomic **per file**, not a transactional
whole-game update or rollback. The updating folder is excluded from installed scans and offered
for resume. Cancellation with deletion clears caches but retains that folder and its verified
swaps; retry preserves the updating record's metadata. If marking or swapping fails, no destructive
fallback is attempted (the conservative updating state can remain even when a swap fails).
Completion writes the installed record with the new version only after all selected files succeed.
An already registered live Steam shortcut is not revoked at each file swap; do not launch an
in-progress update. The launcher and the finished sidecar are written before the
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

`Library.SteamGame.source` is `steam`, `gog`, `epic`, `amazon` or `added`; the Library shows it as
a chip on every tile and in the hero, and filters by it.

## Launch

A Steam shortcut names an exe and nothing more, and the listing carries no launch options, so a
game that needs arguments or environment gets `.droiddeck-launch.bat` (`StoreLaunch.launcherText`):
`cd` into the exe's folder, `set` each variable, start the exe with the arguments and wait. Proton's
`steam.exe` shim hands a `.bat` to Wine's `cmd`. For Epic the script also reads
`.droiddeck-epic-code` when present, deletes it, and passes
`-AUTH_LOGIN=unused -AUTH_PASSWORD=<code> -AUTH_TYPE=exchangecode` on the game's command line only;
the variable it read the code into is cleared on that same line, so nothing of it is left in the
game's environment. A launcher from an earlier build is rewritten before the next Epic launch.

The code is minted for every launch, wherever it starts. The game page calls
`StoreLaunch.prepare` before they launch; the compat tool (`droiddeck-proton`,
`droiddeck-proton-wrap`) runs `droiddeck-store-launch` before Proton on every real launch, the Steam
client's Play button included. It finds the store launcher among Steam's arguments, reads the
sidecar beside it, and for an Epic game asks the app through `<session>/stores/req` and `resp`
(`StoreLaunchRequests` → `StoreLaunch.epicCode`), waiting up to 5 s; the app writes the code into
the game's folder itself. Each attempt logs `epic launch id=… code=yes|no reason=…`, never the code.

An Epic game's launch choices are the Epic card in its page's Launch tab - "Epic sign-in
(EOS)" (on) and "Launch offline" (off), stored in the sidecar's `epic` block so both launch paths
read the same thing - and "Resolve Epic sign-in", which opens Epic's account page behind its
sign-in in Android's browser.

An Amazon game's exe and arguments come from its `fuel.json` (`Main.Command`, used when it names
an existing file; `Main.Args`, used even when it does not), else from a score over every `.exe`
below the folder (Unreal shipping binaries and `Binaries/Win64` first, crash handlers, setups,
launchers and tiny stubs last; the rules see paths relative to the install folder), else the shared
picker. Its launcher sets five FuelPump variables frozen at install time: `FUEL_DIR`,
`AMAZON_GAMES_SDK_PATH`, `AMAZON_GAMES_FUEL_ENTITLEMENT_ID`, `AMAZON_GAMES_FUEL_PRODUCT_SKU` and
`AMAZON_GAMES_FUEL_DISPLAY_NAME` (the account name, `Player` without one). No Amazon Games SDK files
are deployed into the prefix and `fuel.json` `PostInstall` steps are not run.

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
  has no cloud saves; a cached none is retried after a day.
- **Galaxy SDK saves:** `cloudStorage.enabled` with an empty `locations` list is Galaxy SDK
  IStorage, whose objects live under a location named `__default` in
  `%LocalAppData%/GOG.com/Galaxy/Applications/<clientId>/Storage/Shared/Files`. It goes through the
  same location-qualified handling below. GET peels gzip when `Content-Encoding` is gzip, or when
  the object round-trips as GOG's PUT gzip (deflate 6, mtime 0); a gzip-format save is left intact.
- **GOG names are location-qualified:** the cloud API lists `saves/user1.dat`, where `saves` is the
  `name` of the selected `cloudStorage.locations` entry and the folder is that entry's `location`
  (`.../Hollow Knight Silksong/default`). `GogCloud` strips the location segment for the local path
  and puts it back for `HEAD`/`GET`/`PUT`; files from other locations are ignored. Builds before
  this wrote `<location>/...` one folder too deep (`default/saves/user1.dat`); `migrateGogNested`
  moves those copies up, deletes byte-identical leftovers and rewrites their baseline keys. The
  migration runs once per game (`cloud_layout_<id>`), so a game that legitimately keeps a folder
  named like the location is not relocated again; a non-prefixed listing is left unchanged.
- **The services:** GOG `cloudstorage.gog.com/v1/<user>/<client>` with a token issued to the game's
  own client (the client secret is kept at install; the Galaxy token is the fallback); Epic's
  savesync data storage with signed read and write links (`EpicCloud` → `EpicSaveSync` → the Rust
  `epic/savesync.rs`; the template's root token and `{EpicId}` / `{AppName}` are read by
  `EpicSaveTemplate`).
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
- **Per game:** a GOG or Epic game's hero button reads "Cloud saves" and opens its saves view: the
  Cloud saves switch (on; the sidecar's `"cloud"`, read by both launch paths), the last sync,
  Upload / Download and the conflict actions, or "No cloud saves".

## Downloads

`DownloadQueue` runs 1–3 jobs at a time (Setup / the Downloads page), each a store's whole install
(`DownloadJob.run`), with stages Manifest → Download → Verify → Install. Pause stops the job and
keeps the files; every store's install skips complete, verified files on the rerun, so resume is a
rerun. Cancel deletes the folder. `StoreDownloadService` holds a foreground notification while
anything runs. The native engine (`libdroiddeckstores.so`, `StoresNative`, `GogNative`, `EpicNative`,
`AmazonNative`; contract in `app/src/main/rust/stores/JNI.md`) is loaded on first use; without it
the GOG manager runs its Java fetch loop, and the Epic and Amazon stores (all of whose service calls
and installs run in the Rust `epic` and `amazon` modules) are unavailable: sign-in, sync and install
report "the store engine is not available".

## Settings

| Setting | Pref | Where |
|---|---|---|
| Show Stores in the rail | `SessionPrefs.gameStoresEnabled` (off) | Setup › Stores |
| Download speed tier | `SessionPrefs.gameStoresSpeedTier` (fast) | the cog, Setup › Stores, Downloads |
| Downloads at a time | `SessionPrefs.gameStoresParallel` (1) | Downloads |
| Install to (last pick, dialog default only) | `SessionPrefs.storesInstallTarget` | the Install dialog |

Store log lines (the engines' included) go through `StoresState.logLine`, which redacts them
(`StoreLog.redactLine`) and writes them to logcat and to `filesDir/logs/stores/stores-<date>.log`
(`StoreLogFiles`: one file a day, seven days kept, ~2 MB each before it rolls to `.1`). Nothing
shows them in the app; the session's Share logs zip carries them under `stores/`.

Store URLs are logged through `StoreLog.redactUrl`, which drops the query string where the signed
tokens live.

## Security

- **Sign-ins:** `filesDir/stores/<store>/credentials.json` only, read and written through
  `StoreAccounts` alone (`GogAuth`, `EpicCredentialStore`, `AmazonCredentialStore`, cloud saves
  all call it). The file is an envelope `{"v":1,"alg":"AES/GCM","iv":...,"ct":...}` (base64):
  AES-256-GCM (`CredentialCipher`) under a key generated in the AndroidKeyStore (alias
  `droiddeck-store-credentials`; encrypt/decrypt, GCM, no padding, not exportable, no user
  authentication so background downloads and launch-time exchange codes work with the screen off;
  StrongBox where present, else the TEE). Written through a temp file and a rename, mode 600.
- **Earlier plain files:** sealed at app start (`StoreAccounts.encryptAll`, and on any read),
  verified by opening the result, the plain file replaced; the log says
  `stores: credentials encrypted <store>`. A plain `.tmp` left by an older build is deleted.
- **No Keystore** (some ROMs): the file stays plain, the log says
  `stores: keystore unavailable, credentials stay plain (<exception class>)` once, and the next
  start tries again.
- **A file that can never open** - it does not authenticate (`AEADBadTagException`: tampered, or
  another key after data was cleared or a backup restored on another device), the key is
  permanently invalidated or gone from the Keystore, or it is not an envelope: deleted, and the
  store reads as signed out, so its sign-in card shows.
- **A failure that may pass** (`KeyStoreException`, `ProviderException`, any other
  `InvalidKeyException`, an I/O error, StrongBox busy): the file is kept, the store stays signed in
  (no sign-in card) and is marked unavailable (`StoreAccounts.isUnavailable`), a store action shows
  `<Store> sign-in could not be read: try again.`, the log says
  `stores: credentials unreadable this start (<exception class>)` once, and the next access tries
  again.
- **The Linux session:** the app's files directory is bound into every session, so the guest can
  see `credentials.json` - as the envelope only; the key never leaves the Keystore. Nothing in the
  guest reads the files: `droiddeck-store-launch` asks the app over `<session>/stores/req|resp`
  for an Epic exchange code, which the app writes beside the game's launcher; the launcher reads
  it into a variable and deletes it before the game starts (single use, expires in minutes).
  Steam's own client files are outside this.
