# droiddeck-esync packs

DroidDeck gives Proton three in-process sync backends, picked with the Wine sync tabs in the
Steam settings' Games group: ntsync, fsync (the default), esync and wineserver. The wineserver tab
sets `PROTON_NO_ESYNC=1`, `PROTON_NO_FSYNC=1` and `PROTON_NO_NTSYNC=1` (unless a game's own
environment sets them), so every sync object goes through the server.

- droiddeck-esync: eventfd and shared memory sync. Stock Wine 11 / Proton 11 builds
  have no eventfd-based sync, so the patch series under `patches/<series>` adds it to wineserver
  and the unix side of ntdll. A Proton build without a pack (a release newer than the published
  packs, or one whose pack failed to install) runs stock with droiddeck-fsync standing in, or
  droiddeck-ntsync where fsync is off for the game (`PROTON_NO_FSYNC=1`, or one of the games
  Proton itself keeps off fsync and esync), so it still gets in-process sync; `PROTON_NO_NTSYNC=1`
  as well leaves it on wineserver-only sync. A Proton with esync built in (GE, cachyos) runs its
  own esync, and a game that turns esync off moves to droiddeck-fsync or droiddeck-ntsync there
  too.
- droiddeck-fsync: Proton's own fsync on the stock binaries. fsync waits with `futex_waitv(2)`,
  which the app sandbox refuses, so `libblsession.so` (`BL_FSYNC=1`) answers that system call in
  userspace: every waiter registers the futexes it waits on in a shared table next to the fsync
  shared memory and sleeps on a futex of its own, and every `FUTEX_WAKE` on the fsync shared memory
  wakes the waiters registered on that word. No pack is involved, so it keeps working the moment
  Steam updates Proton. It is the default (the fsync tab, `BL_FSYNC_FIRST=1`, also per game),
  ahead of droiddeck-esync; on the esync tab it stands in where no pack fits, and it also takes
  over for a game whose environment turns esync off (`PROTON_NO_ESYNC=1`, `WINEESYNC=0`, or too
  few file descriptors); where fsync is off as well, such a game gets droiddeck-ntsync, with the
  pack's wineserver when a pack fits. A launch into a prefix whose wineserver is still running
  keeps the sync choice that server was started with (`prefixes.json` in the store), because a
  Wine client that disagrees with its server about fsync exits. It inherits stock fsync's behaviour, the same as on a Linux kernel:
  a wait-all on a mutex abandoned by a dead thread is not satisfied, a pending user APC is
  delivered ahead of an object that is already signaled, a mutex owned by a process killed with
  `TerminateProcess` is not abandoned, and `PulseEvent` can miss a waiter that has not run yet.
- droiddeck-ntsync: a userspace implementation of the `/dev/ntsync` interface in
  `libblsession.so` (`BL_SYNC=1`), which stock Proton 11 builds use as they would the kernel
  driver. It is the ntsync tab. A pack still supplies the binaries when one fits, and its
  wineserver then picks ntsync over esync; a Proton whose wineserver has no ntsync client (no
  `/dev/ntsync` in it) gets droiddeck-fsync instead.

wineserver picks the backend at startup: `PROTON_NO_NTSYNC` unset -> `/dev/ntsync`
(droiddeck-ntsync, or a kernel driver the device provides) -> fsync (droiddeck-fsync, or a kernel
and sandbox that allow `futex_waitv`) -> droiddeck-esync (needs `WINEESYNC` nonzero) ->
server-side sync. When a pack is in use and droiddeck-esync is on, the guest's `droiddeck-esync`
script also sets `PROTON_NO_FSYNC=1` unless the game's environment sets it, so droiddeck-esync
comes before fsync unless droiddeck-fsync was chosen. A wineserver from a pack reports the
backend on stderr as `droiddeck-ntsync: up and running.` or `droiddeck-esync: up and running.`;
with droiddeck-fsync the stock wineserver prints `fsync: up and running.` after
`droiddeck-fsync: up and running.`. A pack's wineserver also abandons, when a process dies, every
mutex still owned by one of its threads, and, once a thread killed by `TerminateThread` has really
exited, every mutex it still owns. A killed thread keeps running until the kill signal reaches it
and can take a mutex after the server abandoned its mutexes; without these passes that mutex would
stay locked for good (they cover droiddeck-ntsync and kernel ntsync as well). The ntdll side
reads, writes and polls its eventfds with raw system calls, so no `LD_PRELOAD` wrapper of
`read`/`write`/`poll` (the guest preloads several) sits in the sync path or in the unwind of a
thread Wine kills.

The patch changes no wineserver request: `make_pack.py` refuses a build whose
`server_protocol.h`, `request_handlers.h` or `request_trace.h` differ from the unpatched
control build, or whose ntdll.so exports other symbols. Only `files/lib/wine/aarch64-unix/ntdll.so`
and `files/bin-arm64/wineserver` are replaced, so every Proton build needs its own pair, built
from that build's exact wine source. That pair plus `pack.json` is a pack (`<id>.tzst`). On the
device the guest runs Proton from a shadow tree that links the stock tool and holds the two
patched files (`droiddeck-esync`, store in `~/.local/share/droiddeck-esync`).

## Credits

droiddeck-esync follows the esync design and droiddeck-ntsync implements the ntsync interface;
both are the work of Elizabeth Figura for Wine, Proton and the Linux kernel. droiddeck-fsync runs
fsync, by Elizabeth Figura and Paul Gofman for Proton, over `futex_waitv(2)` by André Almeida.
DroidDeck builds on that work for Android, where neither the ntsync driver, `futex_waitv` nor a
Proton build with esync is available.

## Matching

- `ge`, `cachyos`: built against the release tarball's own binaries. `pack.json` `stock` holds
  their sha256; the guest uses the pack only when both files hash the same. `make_pack.py` also
  requires the control build to reproduce both files byte for byte, so the patched pair differs
  from the release only by the patch (`--allow-unreproduced` lifts this for local experiments).
- `valve`: the depots cannot be downloaded without a Steam login, so `stock` holds the control
  build's hashes and `source_match` is true: the guest also accepts a tool whose version token
  (second field of `<tool>/version`) and ntdll.so exports hash equal the pack's. With the optional
  `STEAM_DEPOT_USER` / `STEAM_DEPOT_TOKEN` secrets (and the `DEPOT_DOWNLOADER_SHA256` variable)
  the job downloads the depot instead and, when the depot is at the tag being built, makes an
  exact pack. The guest's launch line says which kind matched (`exact match` or `source match`).
- A stock wineserver that already has esync built in (it prints `esync: up and running`) never
  gets a pack.

## Building and publishing

`.github/workflows/watch-proton-releases.yml` runs every 20 minutes and decides when a build is
due. It asks Steam anonymously (`steamcmd +app_info_print`) which public build of each Valve ARM64
depot app in `flavors.json` is live, and `discover.py watch` compares that, the newest `keep` tags
and releases of every flavor, each flavor's `rev` and its patch series digest with the state of the
last run (`watch-state.json` in the `droiddeck-esync-index` release). A flavor whose builds, rev or
patch series changed, or whose Steam build moved, starts
`.github/workflows/build-droiddeck-esync-packs.yml` for that flavor; nothing else starts a build.
A run that finds no change takes about a minute and builds nothing. If steamcmd fails, the run
watches the tags only and keeps the last Steam state.

`.github/workflows/build-droiddeck-esync-packs.yml` also runs once a day as a safety net, on pushes
to `tools/droiddeck-esync/**` and by hand (flavor, one tag, publish yes/no). Only runs on `main` of
`Droid-Deck/DroidDeck` publish packs; the schedule and pushes do nothing in other repositories,
and manual runs elsewhere only build.

1. `discover.py matrix` lists the newest `keep` builds of each family in `flavors.json` (Valve
   tags, GE and CachyOS releases with an arm64 tarball) and drops the ones the published index,
   or the `droiddeck-esync-<flavor>` release, already has at the flavor's `rev`, the ones
   `revoked.txt` names, and the ones that failed in the last day at the same `rev` and patch
   series (a `<key>-r<rev>-<series digest>.failed` asset in the flavor's release). Naming the tag
   in a manual run builds it anyway.
2. Each build runs on an arm64 runner with a read-only token, since it runs upstream code: the
   wine source is checked out at the tag (GE also runs its `protonprep-valve-staging.sh`),
   `build-pack.sh` builds a control and a patched pair inside the SDK image named by the
   source's own `Makefile.in` (arm64-llvm), at the flavor's `build_dir` in `flavors.json` (the
   path the release itself was built at, which ends up in the binaries), and `make_pack.py` makes
   the pack. Patches apply with `--fuzz=0`: a hunk whose context moved fails the build instead of
   landing somewhere else. For GE and CachyOS, whose release tarball holds the whole Proton,
   `smoke-test.sh` then boots a prefix with that stock Proton and runs `tests/esstress.c` (built
   with the SDK's llvm-mingw) under server-side sync as a baseline, and again with the pack under
   droiddeck-esync. A pack that fails while stock passes is not published; when stock itself cannot
   run on the runner the step only warns.
3. The publish job checks each build's artifact against the matrix (`discover.py packs`) and
   uploads it to the `droiddeck-esync-valve`, `droiddeck-esync-ge` or `droiddeck-esync-cachyos`
   release as `<id>.tzst` with its index entry `<id>.json`. A build that made no pack gets its
   `.failed` marker there (`discover.py failures`), so a patch that does not apply or a stock
   wineserver with esync built in is not rebuilt on every run.
4. The index job (environment `droiddeck-esync-signing`) runs only when the run built a pack or
   a published pack is not in the index yet. It collects every `<id>.json`, keeps the
   highest `rev` per stock pair and version, marks revoked ids, signs `index.json` (ECDSA P-256,
   `index-key.pub`) and publishes `bundle-<YYYYMMDD>-<HHMMSS>.tzst` (named after `generated`, so
   every signed index has its own), `index.json` and `index.json.sig` to `droiddeck-esync-index`.
   `generated` only grows. Packs stay in the index until they are revoked, also when their
   entry asset disappears; the previous index counts only when its signature verifies.

A pack is rebuilt only for a new `rev`: bump `rev` of the flavor in `flavors.json` whenever the
patch series or the build changes. Local builds: `BUILD_DIR=<the flavor's build_dir> SYNC_PROFILE=<the
flavor's profile> build-local.sh <sdk-rootfs> <wine-source> <work-dir> <out-dir> [patch-dir]`,
then `make_pack.py`.

## Setup

- Signing key: create the repository environment `droiddeck-esync-signing` (Settings ->
  Environments, limit it to `main`) and store the PEM private key as its secret
  `SYNC_INDEX_KEY`: `gh secret set SYNC_INDEX_KEY --env droiddeck-esync-signing < index-key.pem`.
  The key never enters the repository; the app trusts only `index-key.pub` (also compiled into
  `EsyncPacks.kt`), so a new key needs an app release.
- Optional: `STEAM_DEPOT_USER` and `STEAM_DEPOT_TOKEN` repository secrets for exact Valve packs,
  plus the repository variable `DEPOT_DOWNLOADER_SHA256`: the sha256 of
  `DepotDownloader-linux-arm64.zip` from the DepotDownloader release the workflow pins
  (`DEPOT_DOWNLOADER_TAG`). The depot step is skipped while it is unset.
- APK bundle: after an index run, the job summary prints three lines. Put them in
  `tools/droiddeck-esync/release.env`:

  ```
  SYNC_BUNDLE_TAG=droiddeck-esync-index
  SYNC_BUNDLE_ASSET=bundle-20261002-061733.tzst
  SYNC_BUNDLE_SHA256=<sha256>
  ```

  `build.yml` then unpacks that bundle into `app/src/main/assets/droiddeck-esync/` (gitignored). A
  published bundle is never overwritten, so the pinned sha256 stays valid; bump `release.env`
  to ship newer packs inside the apk. The app fetches newer packs on its own. A pack the bundle
  carries that `revoked.txt` names is left out of the apk, with a warning to bump `release.env`.

## Revocation

Add the pack id as the first word of a line in `revoked.txt` and push to `main`. The workflow
re-signs the index with `"revoked": true` for that id; the app deletes installed copies and
never installs it again, and `discover.py` does not rebuild it at the same `rev`. The apk build
also leaves it out of the bundled packs. To ship a fixed pack for the same Proton build, bump the
flavor's `rev`. Runs that only build (other refs, or manual runs without publish) never queue in
front of a publishing run, so a revocation is signed by the next run on `main`.
