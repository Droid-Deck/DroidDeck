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
Fresh GOG/Amazon preflight and all three Java backend space checks reject known zero when bytes
remain. The read-only probe walks existing ancestors without creating directories; a failed
`StatFs` probe can use a positive `File.usableSpace` fallback, but that API's ambiguous zero is
unknown, not a measurement of a full disk.
Preflight failures remain failed Downloads rows with Clear and Retry. Retry reruns preflight
outside the worker lifecycle and then enqueues the real backend; it never occupies a queue slot
with a placeholder job. Failures are kept in the Downloads UI, notification and logs rather than
duplicated as transient failure toasts. Active queue persistence and ordinary backend pause/retry
are unchanged.

Epic installs have four writers, all with the same per-file safety rules (below). The hidden
`storesEpicMode` string in the `session` preferences picks one: `adaptive` (default), `lookahead`,
`batch` or `java`. There is no UI for it; set it on a test device to compare the modes on the same
game. Every mode logs an `epic: summary mode=<mode>` line (files, bytes written, elapsed, average
MB/s). When `libdroiddeckstores.so` does not load, every mode runs the Java writer. Once it is
loaded, an engine failure (refused plan, ABI mismatch, network, hash or I/O failure, unsupported
atomic swap) fails the install; it never silently reruns as Java downloads.

**Adaptive (default)** - `EpicNative.stream`, `epic/stream.rs`. Measured on device: about
83 MB/s on a 71 GB install. The chunks go through the shared fetch core in the order their files
need them, with its full adaptivity (window ramp, per-host caps, in-flight byte budget, retries,
host rotation, stall handoff). A chunk only one file part uses is verified and written straight
into that file's staging file at the part's offset, in whatever order it arrives; it is never
cached, so each byte is written once. A chunk several parts use is written into every part whose
file the download has reached and kept, decompressed, in the cache until its last part is written.
The cache has a byte budget; the plan simulates the install in file order and, when the budget
would be exceeded, drops the cached chunk needed furthest ahead and downloads it again later.
Files are opened only once the download reaches them, so the set of half-written files stays
within the fetch window. All writes happen in the fetch core's process pool, whose completion
frees the in-flight byte budget: a slow card throttles the download instead of growing memory.
Each file's SHA-1 is computed as its parts are written (parts that arrived out of order are read
back once the bytes before them are in). Stage files go into the owned staging namespace below.
The run logs `stream-plan` (direct vs shared chunks, planned refetches, budget) and
`stream-summary mode=adaptive` (peak cache, peak staging, refetches, bytes written from the
download vs from the cache).

On an update, a staged file that replaces an existing one sits beside its old copy until its swap,
so the engine paces replacements with an overlap budget: files are admitted in order, a replaced
file only while the planned sizes of admitted, not-yet-swapped replaced files fit the budget (one
is always admitted, so the run progresses), and a chunk is fetched only once its file is admitted.
Fresh files are never held back, and admission only delays files, never reorders them, so the
cache plan stays exact. The run logs `overlap_budget=` in `stream-plan` and
`peak_replace_overlap_bytes=` in `stream-summary`. With room to spare the budget is large and
updates run as wide as fresh installs; on a nearly full volume, replaced files are staged one
after another and the fetch pauses at each replaced-file boundary until the previous one is
swapped in.

Its space plan (`EpicDownloadManager.planAdaptiveSpace`, unit-tested in `EpicAdaptivePlanTest`)
checks each volume for what lands on it, with the lookahead planner's rule (the need must not
exceed the free reading). The game volume: the net growth at its worst point (`settledNetPeak`,
the running Σ(new − old) with each old copy released at its swap), plus the overlap budget, plus
the cache when it is beside the game. The overlap budget is whatever the game volume has left
after the net growth and the cache, never less than the largest replaced file; when even that
does not fit, the plan hands over to the lookahead engine. The cache volume: the budget plus 64 MiB of
chunks in transit. The cache budget is half the cache volume's free space after those needs, at most
1 GiB (256 MiB when free space is unknown; 0 is allowed and means every reuse is a refetch). On a
card install the cache stays on internal scratch unless internal storage cannot hold at least
64 MiB of it; then the cache goes beside the game (still released chunk by chunk) instead of
failing. A plan that does not fit logs the short storage and the shortfall and hands over to the
lookahead engine, which narrows its window to the space there is (or fails its own preflight with
an explicit error). Running out of space in the middle of an adaptive run is not destructive: a
failed write fails the run, unfinished staging files are removed, the old files stay.

**Lookahead** - `EpicNative.installAdaptive`, `epic/adaptive.rs`; also the adaptive engine's
fallback when its space plan does not fit. The Java delta pass sends a versioned snapshot of
pending files, chunk metadata, verified cache lengths, old output lengths and both measured
free-space probes to Rust. The native planner and execution share one ordered moving-window
policy. Before any chunk request, Rust chooses the largest safe lookahead (128, 64, 32, 16, 8, 4,
2 or 1 part references) and reusable-cache budget (64 MiB, 16 MiB or zero) whose simulated
staged-file and cache peaks fit. Native device IDs also detect an explicitly named cache on the
game volume, so two separate paths cannot incorrectly turn one filesystem's requirement into two
independent checks.

| Storage situation | Lookahead policy |
| --- | --- |
| Plenty of room | Up to 128 references ahead, up to 32 network requests, 64 MiB reusable data |
| Tight | Smaller lookahead and/or reusable budget; parallel fetching remains where space permits |
| Barely fits | One-reference lookahead, one request, no reusable retention if needed |
| Does not fit even the minimal schedule | Explicit preflight error before requests or replacement |
| Free space unknown | Conservative bounded lookahead (at most eight), not an invented zero |

The lookahead fetch coordinator reuses `fetch_core`'s host scheduling, retries, adaptive network
window and decode pool. Each chunk is cached, then its parts are copied into staged files by a
writer that runs concurrently, waiting only for its next required chunk, through the native
assembler's streaming copy primitive. GUIDs in the lookahead are reserved once before submission,
at their full decoded size. Chunks are released at last use on **both** cache layouts; distant
reusable data is oldest-first bounded and may be refetched. At most the selected reusable budget
plus distinct lookahead decoded sizes is resident/reserved. New request admission re-probes both
volumes and can shrink unsent lookahead or retention if headroom disappears; already in-flight
reservations remain protected. If even existing reservations cannot fit, the run fails and joins
its workers rather than waiting indefinitely or deleting old game files. An install to SD retains
its internal scratch cache, so assembled output is the only game data written to the card.

**Java** - without the native library, or with `storesEpicMode=java`. Eight Java workers prefetch
the next eight chunk-part references, across file boundaries, while one writer assembles and
verifies files in manifest order. Shared GUIDs within that window are fetched only once. Chunks
are deleted after their last use; other reusable chunks are retained up to 64 MiB. At most eight
additional decoded chunks (complete or in flight) coexist with that retained cache. The size
bound is 64 MiB plus the decoded sizes of the window's distinct GUIDs, not a fixed eight-MiB
allowance: manifests can supply larger chunk windows. Evicted distant shared chunks may be
downloaded again. No worker downloads outside the moving window; cancellation and failure
disconnect and join all workers before returning or removing the cache. Its summary line is
`mode=java-bounded`.

**Batch** - the earlier native design, kept for A/B benchmarks: every chunk fetched into the
cache in parallel, then the files assembled from it (`EpicNative.run` + `EpicNative.assemble`).
It needs the cache and the files at once (about twice the game) and checks exactly that before
starting; it writes files in place, so an existing install is marked `updating` before assembly.
`epic/assemble.rs::build_assemble_plan` enables `scratch_cache` only for a nonempty cache path:
`run_assemble` then removes chunks at their last use; the default beside-game cache is removed at
the end (`default_cache_assembles_like_the_kotlin_loop_and_removes_the_cache_at_the_end` and the
scratch-cache assembly tests cover that distinction).

The Epic download bar, measured rate and ETA (adaptive, lookahead and Java) count pending file bytes written, not unique
compressed chunks fetched. Shared chunks therefore cannot complete the bar before later files
are written, and an evicted chunk's refetch is reflected in the time spent doing that work.
Streaming stays in the Download stage until all files are written and verified, then enters
Install for registration; it does not bounce between stages for each file.

For the lookahead and Java writers, the space check follows the manifest delta pass and uses the
same pure moving-window policy as execution, reserving all incoming decoded chunk sizes before writing parts and simulating
last-use releases and unpinned oldest-first evictions. Existing verified cache bytes count against
current free space and are credited only at their scheduled release. This avoids adding the entire
cache to the entire game without undercounting concurrent downloads. Each file is written to a
unique file in an owned staging namespace on its output volume (never the literal `<filename>.part`, which can
be a legitimate manifest file), size/hash verified, synced and atomically renamed over its output.
The old file remains until that swap succeeds. The planner credits its bytes only after that
file's complete staging write, not by subtracting all old outputs up front. Both volume checks
must accept this safe staging peak; insufficient space is rejected explicitly, with no destructive
delete-first fallback. An unsupported/failed atomic rename fails the install and retains the old
destination. Completed files and
verified cached chunks are accounted for on resume. On a card, game and cache requirements
are checked separately. File SHA-1 hashes are checked where supplied by the manifest; failed
or cancelled staging files are removed without deleting old outputs. A successful run removes the
cache. A resume within a large incomplete file restarts that file, not its individual parts.
All three staged writers (adaptive, lookahead, Java) use `<installDir>/.droiddeck-epic-staging/`, whose `owner` marker identifies this
namespace. A successful install removes it when it holds nothing but the marker (same no-follow
and canonical-path checks); the next run recreates it. Before the delta pass and free-space probes, the manager removes orphan `.stage`
entries only from this owned namespace. A manifest naming that directory or any descendant
fails without deletion; an unowned directory or unexpected entry also fails conservatively.
The namespace must be a real directory at the expected canonical child of the install root.
Ownership markers and staging entries must be no-follow regular files; symlink directories,
markers and entries are rejected before recovery deletes anything.
Ordinary game files with `.stage` or `.part` suffixes elsewhere are never recovery targets.
Abrupt process termination is therefore recoverable on the next run without inferring ownership
from a loose filename prefix. Cancellation/failure waits for all native/Java fetch writers before
returning; completed verified cache chunks remain usable on resume.

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
