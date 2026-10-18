# DroidDeck - Progress Log

Running engineering log for the DroidDeck app (`com.droiddeck.launcher`; called SteamDeck,
`com.steamdeck.launcher`, until 2026-09-23 - entries below that date keep the old name). Newest state first, then
the timeline, then lessons and backlog. Companion to the README (what the app *does*) and to
`docs/releases/` (what each version said) - this is *how it got here and where it stands*.

---

## 2026-10-16/17 - `feat/x86-64-steam-client`: web helper on the GPU, org main, review rounds

- **x86-64 web helper on the GPU** (`f603a085`): the client's own steamwebhelper runs ANGLE's GL backend
  through FEX's GL thunk to zink/Turnip (ANGLE-Vulkan was CPU-bound at 16 fps). No arm64 helper needed.
- **Quick Access Menu** (`26d80895`): the QAM opened blank until a d-pad press and the screen behind it went
  black. Steam's compositor draws each browser window from its X pixmap; gamescope's WSI layer took the
  helper's zink swapchains, so the pixmaps never got frames. The helper now runs with
  `DISABLE_GAMESCOPE_WSI=1` (games keep the layer). Not FEX: a texture-from-pixmap test behaved the same
  under FEX and native, so no fex-2610-r4.
- **Black flashes** (`1b52df77`): gamescope sets `vk_xwayland_wait_ready=false` for its children; without
  its layer, unfinished frames reached the compositor (3 flashes in 10 QAM opens, screen-recorded and
  counted frame by frame). The helper waits for frames and presents without kopper: 0 flashes in 60 opens.
  The x86-64 menus run at 60 fps on that path (Xwayland's per-window timing for child windows); the user
  chose no flashes over ~140 fps.
- **Org main**: rebased twice (522e755b, then e95a4226), linear, each final tree checked equal to a plain
  merge. Main's art picker, grid sync and boot movie follow the selected client (`32bbf79a`).
- **CodeRabbit** on personal PRs #68 and #69 (full review of 76 files on #69). Fixed: one Steam channel for
  the x86-64 client; VAC report reads only console logs this session wrote, and a stopped session keeps its
  own marker; segvlog reads stack words without faulting and opens files with raw syscalls; the x86-64
  shortcuts pass no longer writes the arm64 client's compat `state.json`; DroidDeck direct mapping in one
  pass (2000-title fixture: same output, 16.5 s -> 0.2 s); session stop/start handoff under one lock;
  desktop sessions prepare the x86-64 card library; Decky's x86-64 marker only for an installed client;
  library follows the running client; download monitor restarts on the other client's log; compat
  restart reads the x86-64 card folder; CPU pinning sees FEX processes; build-fex has a read-only build
  job, a separate publish job, never republishes a tag, requires thunks and tags the built commit
  (`8e2751f9`..`f9de849c`). Skipped with evidence: IPC home for the probes (device: ownership probe
  succeeds in x86-64 sessions); shortened `STEAM_COMPAT_DATA_PATH` (read before the override).
- **Device** (5004cf4e): TF2, Half-Life, Borderlands 2 and DiRT Showdown launch and play on the x86-64
  client; the arm64 client starts. BL2/Half-Life "downloads" were Steam shader pre-caching (6.1 GB Deck
  `steam_pipeline_cache.foz` for BL2), not game updates. Half-Life is the Windows build in the arm64 client
  and the Linux build in the x86-64 one, in separate folders.
- **Org readiness** (2026-10-17): org main's rules are a PR with the `Build APK` check (ruleset "Protect main")
  and `AGENTS.md`'s one line on comments. Comments added by the branch went from about 440 lines to under
  100, each a non-obvious external constraint; the device history and reasons moved to
  `docs/development/x86-64-steam-client.md` (code unchanged, checked by stripping comments from both sides).
  The Steam client setting now says the library is the client's own and the sign-in is shared. Rebased
  onto 83eda433 (VITURE XR); squashed as personal PR #70, `feat:` title. FEX tags r1-r3 point at personal
  main, not their build commits; build-fex now tags the built commit and needs thunks to publish.
- **PR #70 review**: added Linux-only titles in the x86-64 client went to `droiddeck-fex`, a tool that
  client lacks (now DroidDeck direct, and `droiddeck-fex` is retaken); pulseguard finds a libpulse loaded
  RTLD_LOCAL (Wine's winepulse.so); segvlog passes faults on to the program's own handlers after logging;
  Lossless's DLL search includes the x86-64 card folder; the log scrubber keeps account names when a
  `loginusers.vdf` cannot be read; build-diag runs with a read-only token.
- **Org main 97f976ef** (Epic Games Launcher for Linux, #928): rebased onto it. Its new session code
  launched, restarted and recovered games through `steamrtarm64/steam`, and its added-game shortcut check
  read the arm64 client's Steam folder; both now use the session's client. Added games marked native are
  not given a Proton by either client's shortcuts pass.
- **FEX hosted by the org**: `fex-2610-r3` is mirrored into Droid-Deck/DroidDeck-Components by its
  `mirror-release.yml`, checked against a new `fex.json` catalog there (Components PR #2), and
  `droiddeck-steam-x64` downloads it from there instead of The412Banner/DroidDeck.
- **FEX fex-2610-r4** (PR #71 review): without openat2 (proot) FEX opened rootfs paths with absolute
  symlinks resolved against the arm64 guest, and `libblsession.so` converted every openat2 the same way
  before FEX saw ENOSYS. `preload/robust.c` now resolves `RESOLVE_IN_ROOT` paths component by component
  inside the root (59 cases; 100,000-path differential fuzz; new `test_openat2_preload.py`), and patch
  0001 does the same in FEX. Patch 0003 marks only the host mappings that overlap a failed MAP_32BIT
  range (the low-2 GB case: about a million mmap tries before, one now). Built with thunks, tagged at the
  built commit, mirrored into DroidDeck-Components (PR #3). The Steam client setting names the FEX build.
  The bring-up crash logger (`tools/diag`) left the branch. Device: FEX r4 downloaded on first start,
  client UI in 23 s (19-33 s before), TF2 played. The first session on the new build drew no SVG fills
  in Steam's UI (icons blank, outlines and text fine); a restart drew them, cause not found.
- **Added games**: the scan treated every folder of the card library as a game folder except
  `steamapps`, so the x86-64 client's `x86-64/` library showed up as a custom game named "x86-64"
  with the exe of a Steam title installed there (FlatOut). `AddedGames.isSteamLibraryFolder` skips it
  like `steamapps` in the scan, the exe-to-folder lookup and the folder list (`AddedGamesScanTest`).
- Rebased onto org main 45513c90; squashed again for a full automated review (personal PR #72,
  replacing #71).
- **PR #72 review**: grid art, the content-log monitor, the repair marker, storage leftovers and the
  VAC diagnostics files are kept per client (the x86-64 client's first art sync was deleting chosen
  art as "removed in Steam"); the old games-folder migration skips the x86-64 library too.
- **Which client**: the Games header shows a plain "x86-64" chip while the x86-64 client's list is
  shown (on its own line in a narrow pane); nothing changes for arm64. The setting reads "Applies next
  session. FEX 2610 r4" with the choices ARM64 and x86-64 (`GamesClientTagTest`).
- **Stale list**: every refresh drops the Steam-client games when they belong to another client than
  the active one (a setting change, the end of a session, a resume), keeping added and store games;
  the cached list records its client in the same file (`library-cache.json`, saved under one lock). The next review of PR #72 had no
  findings. Device: the header chip and the removed "x86-64" entry seen on 04568806.
- **x86-64 rootfs in DroidDeck-Components**: FEX-Emu's Ubuntu 24.04 image (2026-08-11, 525,615,104
  bytes, unmodified) is now fetched from Droid-Deck/DroidDeck-Components release
  `fex-rootfs-ubuntu-24.04-2026-08-11` instead of rootfs.fex-emu.gg. Components PR #4 added
  `fex-rootfs.json` and `mirror-upstream.yml` (fetches a catalog's upstream link, refuses it unless
  sha256 and size match, checks the digest GitHub stored); its run 39084452789 brought the file in.
  The sha256 the app pins is unchanged, so an unpacked rootfs is kept.
- **One cache file**: the review of that change found the list and its client marker written as two
  files, so concurrent saves could pair one client's list with the other's marker. `library-cache.json`
  is now one object (`client`, `games`) written by one rename under a lock; a bare list from earlier
  builds reads as arm64's. Device (73e2a80b): the first start after install read the old list,
  rescanned and wrote `{"client":"x86_64","games":[...]}` with the same 23 games.
- A scan reads the client it tagged the cache with (`Library.launchableGames(arch = ...)`), so a switch
  during a scan cannot pair one client's games with the other's tag; a failed rename drops the cache
  instead of leaving the previous list behind (`LibraryArchTest`).
- The next review of PR #72 (a2552c58) had no findings; every review thread is resolved. Device: 43f9a8a8
  installed.
- Device checks on 43f9a8a8: the setting reads "Applies next session. FEX 2610 r4" with the choices
  ARM64 and x86-64; picking ARM64 drops the chip and the Games tab showed the arm64 list on first
  look, and back on x86-64 the chip and the x86-64 list return (the cache records each client). Two
  custom games' chosen background art is kept, with the same file in both clients' grid folders.
  Team Fortress 2 launched from the Games tab with the x86-64 client selected: the session started
  the client, handed it app 440 and the Linux build (`tf_linux64` under FEX) took focus about 60 s
  after the tap.
- **Translations**: the branch's 11 strings (the Steam client setting and its values, VAC diagnostics,
  the x86-64 loading title and the Storage labels) are in all 31 other locales, after
  `stores_registering` as in the English file, reusing each locale's wording for "Steam client",
  "Session logs" and "Uninstall" and its formal or informal address. Every file parses, every
  translation keeps its English placeholders, and no locale has a key the English file lacks.
- The translations went to PR #72 (a43217c4) for review; org main is still 45513c90, so the branch
  needs no rebase before the org PR. Device: 9afb9bd5 installed.
- **Org PR**: the draft #152 from 2026-10-02 (same branch name, 12 commits, conflicting with main) is
  refreshed as one commit on org main 45513c90 and marked ready, once the review of the translations
  is clean. Its old head is kept at `refs/backup/20261018/x86-64-steam-client-afa479ee`.
- Korean: the review of the translations found `을(를)` left in the two uninstall strings; both client
  names end in a vowel when read, so they take `를`. The next review was clean.
- **Submitted**: #152 is one commit (a53fd117) on org main 45513c90, ready for review; Build APK passed.
  Its `kwin` job (Build desktop package, KDE Plasma) failed for an unrelated reason: `build-kwin.sh`
  requires Arch Linux ARM's `kwin-6.7.5`, which `extra` replaced with 6.8.0 on 2026-10-15; the job ran
  only because refreshing the branch from its 2026-10-14 head spanned main's own `tools/desktop-kde`
  changes. This branch changes nothing there.
- **Org review** (CodeRabbit on #152, 7 points): the library cache is saved only by the newest scan; a
  session records the client it ran (`steam-client.txt`) so an abandoned session's Steam logs come
  from that client; two console logs with the same game and mod get distinct names in the VAC report;
  CPU pinning also unwraps `FEXInterpreter`; the VAC hint names the game in pl, ru, uk, cs and bg.
  Pinning `actions/download-artifact` to a SHA was left out: no workflow here pins one. Follow-up
  commit eb8634ea.
- Merged as ae94d9db.
- **FEX r5** (report: a Galaxy S25 without the KDE desktop): the x86-64 client exited 127 at its first
  FEX call, `error while loading shared libraries: libxxhash.so.0`. FEX, FEXServer, FEXRootFSFetcher
  and FEXOfflineCompiler link libxxhash; linuxfs-r9 does not ship it, and the test device had it only
  from the KDE desktop package. r5 bundles it in `usr/local/lib` with RUNPATH `$ORIGIN/../lib` on
  those programs (the runtime has no ld.so.cache), and `build-in-arch.sh` now fails when a program
  needs a library that is neither bundled nor listed in `tools/fex/runtime-libs.txt` (the r9 libraries
  FEX uses). With the device's libxxhash hidden, r4 failed with the reported error and r5 loaded.
  Mirrored in DroidDeck-Components (#5); the app moves to fex-2610-r5 and an installed r4 is replaced
  at the next x86-64 start.
- Merged as 7d21ba22.
- **FEX from the catalog**: FEX builds may need to follow user reports faster than app builds, so the
  app now reads Components' `fex.json` (directly, kept between starts) and passes it to the session; a
  build is installed only once its sha256 matches and its programs load, the installed one stays while it
  loads, and the build pinned in the script remains the first-start default. **Session logs** for the
  x86-64 client now name the FEX build and its source, the rootfs and the client's builds, report the
  rootfs download in tenths instead of a progress bar (one report's log ended in hundreds of bar lines),
  and, when the web helper's GPU process keeps exiting (a report from an Adreno 840: the menus never
  appeared), say so in one line with x86 `glxinfo`/`vulkaninfo` through FEX. Settings shows the FEX
  build installed. FEX is now built and published by DroidDeck-Components' `build-fex.yml` from this
  repository's `tools/fex`, replacing the archive on its single `fex` release and recording the version
  in `fex.json`; this repository's `build-fex.yml` only checks the build. Tests: `test_fex_select.py`,
  `FexCatalogTest`.
  Components' first build there published `2610-r6` (run 39104443888). Device (Pocket FIT, r4
  installed): the app read `fex.json`, and the next x86-64 start logged `downloading FEX (fex-2610-r6)`
  then `FEX ready (fex-2610-r6, catalog)` a second later, with the rootfs and client lines; the client
  came up on it.
- Review of #1053: an install keeps the files it replaces aside until the installed copy loads and puts
  them back otherwise (a failed copy could leave a partly replaced FEX marked as the new one;
  `test_fex_install.py`), and the catalog is read on its own thread, not before the library scan, and
  not in Offline Mode.

## 2026-10-15 - `feat/x86-64-steam-client`: SD card library, ARM parity, org main, Counter-Strike 2

- **SD card library** (`3fc74f3e`): the x86-64 client had internal storage as its only library, so Steam's
  install dialog never offered a place. It now has `x86-64/` inside the card library
  (`/mnt/droiddeck-sd/x86-64`; compatdata, shadercache and runtime tools private like the arm64 card
  library). Before building, FEX was shown to run x86 programs from the noexec exFAT card. Device: the
  dialog offers Internal Storage and SD card.
- **Card downloads** (`f77113fb`): `storage.c` (no zero-filled reservation where `fallocate` is refused)
  and `dircache.c` recognised only `steamrtarm64/steam`; the x86-64 client wrote each download in full as
  zeros first (Audiosurf: 415 MB written, 0 downloaded). Device: FlatOut, 1.19 GB, to the card in 51 s.
- **zRAM** (`877c27eb`): the watcher read argv[0], which under FEX is FEX; it now reads FEX's first argument.
- **ARM parity** (`b0923f05`): touch layouts read the active client's files; Offline Mode is written to
  both clients' `loginusers.vdf`; the Lossless Scaling ownership check runs for x86-64; Repair Steam repairs
  the x86-64 client too (and two unshown starts do, like arm64; the app clears its start count). Device: no
  repair on the stale 31-start count, count cleared once the client was on screen. Switch to Desktop needs
  no change (Deck mode: an x86 shell under FEX runs `steamos-session-select`).
- **DroidDeck-Proton default** (`2bd752aa`): newest DroidDeck-Proton before GE-Proton; `default-tool`
  records the default this script set, and on a change the titles still on it move along (device: 113 off
  GE-Proton11-7). A default the user chose is kept. FlatOut from the card: in game on DroidDeck-Proton
  11-9.1, exits on GE-Proton11-7 (no log).
- **Org main** merged (`714198bf`, 36 commits; strings and `steam_started` conflicts kept both sides).
  A stale line from the 10-07 sync ran the live compat helper in the foreground on the arm64 restart path:
  black screen after a Steam import restart (`02e7c503` matches main again).
- **VAC findings:** x86-64 client + Linux builds secure (TF2, HL, L4D2). x86-64 client + Windows build
  (TF2 on DroidDeck-Proton) insecure: Proton reaches Steam through the arm64 client's `steamclient.so`.
  arm64 client + L4D2 blocked on both Protons; arm64 + Half-Life (Windows) joined a vac=1 GoldSrc server.
  Why the arm64 client fails is not established. Sharing libraries between the clients stays parked.
- **arm64 `droiddeck-fex`** (`a53b49ef`): Valve's FEX-Emu keeps its server socket in
  `$STEAM_COMPAT_DATA_PATH/fex-emu/Server` whatever `FEX_APP_DATA_LOCATION`/`FEX_SERVERSOCKETPATH` say;
  under the app's folder that path passed 108 bytes (Half-Life: 136) and FEXServer never listened. A short
  path is used now. 32-bit Linux titles through it still fail (not pursued).
- **Counter-Strike 2** (Linux, newer runtime) hung at 100% of one core before loading its engine. With
  ptrace taken by proot, `perf_event_open` IP sampling put the loop in FEX's `MemAllocator32Bit`: CS2
  reserves the low 2 GB with `MAP_32BIT` in shrinking sizes, FEX met `EEXIST`, stepped down one page per
  retry and restarted from the top for ever. **`fex-2610-r3`** (patch `0003`, `b0d316dc`): such a range is
  skipped whole and a second exhausted scan returns `-ENOMEM`, as the kernel does. Next, its menu text
  library failed: CS2 ships an older `libfreetype.so.6` but no HarfBuzz, and the rootfs's needs a newer
  FreeType; the game flagged its files corrupt (a verify each start) and waited behind an error box that
  could not open. DroidDeck direct now preloads Valve's runtime HarfBuzz (2.7) for newer-runtime titles
  (`c9eb7d30`). Device with both done by hand: engine and modules loaded (5 GB) with no error, then Android
  killed DroidDeck for memory while it was in the background. Not yet seen at the menu.
- **CS2 on the r3 APK** (`c9eb7d30`, no launch options): no hang, the menu text library loads, the game
  client and modules load and the window resizes. Every run then ended in Android's low-memory killer:
  CS2 goes from 3.5 to 5.3 GB in about 15 s and `lmkd` killed DroidDeck (adj 200, in the background
  while the device was in Termux) with zRAM on and still filling (1.5 to 3.2 GB of 6 GB). This device's
  `lmkd` runs in minfree mode (`persist.device_config.lmkd_native.use_minfree_levels=true`; background
  apps go below about 120 MB free), which does not count swap. Proposed, not applied: switch it to the
  PSI mode (`use_minfree_levels=false`, `lmkd.reinit=1`; reverted with `true`). CS2 has not been seen
  at its menu yet.
- **CS2 reached its menu (14:47, DroidDeck in the foreground):** still loading, 11 fps, 11.6 GiB used.
  Five seconds later `lmkd` killed nearly every app, then DroidDeck itself at adj 0 (foreground), while
  swap was about 22 of 23 GB free (zRAM 6 GB with 0.9 GB used, `/data/swapfile` 16 GB almost unused).
  Steam and CS2 died with the app; neither crashed on its own.
- **Root test without changing Android (lmkd left as is):** DroidDeck's zRAM watcher only pages out
  `steam` and `steamwebhelper`, never the game. A root tool (`/data/local/tmp/pageout cs2 3000 <log>`)
  calls `process_madvise(MADV_PAGEOUT)` on CS2's private anonymous memory whenever MemAvailable drops
  below 3 GB, so free RAM stays above the killer's levels. One call per mapping in 1 GB pieces: a single
  call with many ranges is capped at about 2 GB in total, and FEX reserves huge ranges, so batched calls
  silently skipped most of a FEX process. With that fixed, the x86 Steam client moved 223 of 256 MB to
  swap. If CS2 survives to its menu, the app version is the zRAM watcher also paging out the running
  game during its load (no root).
- **Page-out result (14:59):** the tool fired at 2.9 GB available and moved 4.8 GB of CS2 to swap
  (resident 5.8 → 1.5 GB). CS2 drew its full menu (2–34 fps). 27 s later `lmkd` still killed down to
  DroidDeck at adj 0. Its kill record: MemFree 111 MB, Cached 6.3 GB, but **Shmem 3.4 GB and
  Unevictable 3.5 GB** (Mlocked only 120 MB), swap 17 GB free. In minfree mode the "file" figure is
  Cached minus Shmem, Unevictable and SwapCached, so about 0, and it kills to the foreground. After the
  session died Shmem fell to 0.3 GB, so the 3.4 GB of pinned shared memory belonged to the CS2 session.
  MemAvailable counted it as available, which is why the tool saw 3.7 GB of headroom 2 s before the
  kill. Page-out cannot move unevictable pages.
- **Next run (15:04):** a root logger (`/data/local/tmp/shmlog.sh` → `shm-cs2.log`, every 3 s) records
  MemFree / Cached / Shmem / Unevictable / Mlocked / SwapFree and every process holding more than 50 MB
  of shared (`RssShmem`) or locked (`VmLck`) memory, to find who pins the 3.4 GB (FEX, the X server,
  Vulkan buffers, a ramfs). No SysV shared memory on the device. Then the trigger moves from
  MemAvailable to MemFree plus droppable file pages.
- **Dust II (15:27):** CS2 loaded Dust II "Training Day" (bots) at 78 fps, then was killed at 15:28:05 the
  same way. From 15:25:30 Shmem and Unevictable rose from 0.25 to 3.9 / 4.0 GB, yet the largest process
  mapping shared memory (an `exe`) held only about 535 MB, so about 3.3 GB is not mapped by any process.
- **Open files ruled out (15:32-15:36):** a second logger (`fdlog.sh` → `fd-cs2.log`) listed every memfd,
  ashmem, deleted or tmpfs file of 20 MB or more held by the app. Only 64 MB PulseAudio memfds showed up
  while Shmem reached 2.7 GB. The run was killed at 15:36:28 (Shmem 3.4 GB, Unevictable 3.5 GB).
- **Lead: GPU memory.** `/sys/class/kgsl/kgsl/page_alloc_max` reads 6.07 GB (the peak since boot), and the
  driver has `max_reclaim_limit` / `page_reclaim_per_call`, i.e. a KGSL build with process reclaim, where
  GPU pages are shmem-backed and stay unevictable while the process is in the foreground. That fits
  memory that no process maps, that has no file, and that is freed when the session dies. Not proven.
- **Low-settings test (15:42):** `cs2_video.txt` in the x86 userdata: `gpu_mem_level` 3 → 0,
  `cpu_level` 3 → 0, resolution 1024x576 → 854x480 (everything else was already at its lowest; backup
  `cs2_video.txt.before-low`). A third logger (`gpulog.sh` → `gpu-cs2.log`, every 3 s) records KGSL
  `page_alloc`, MemFree, Shmem, Unevictable and each process with more than 200 MB of GPU memory. If
  Shmem rises with `page_alloc`, the GPU memory is what is pinned. Background apps were force-stopped
  and caches dropped first (about 11 GB free).
- **GPU memory confirmed (15:47):** with low settings CS2 reached its menu at 94 fps and was killed at
  15:47:59 the same way. KGSL `page_alloc` followed Shmem within 20-120 MB through the whole load
  (0.23 GB at 15:44:50, 0.93 GB at 15:46:47, then +2.2 GB in 3 s, about 3.2 GB at the kill), and
  Unevictable stayed about 100 MB above it. One process (`exe`, pid 23906) held nearly all of it. So the
  pinned 3.4 GB is the game's GPU memory, and the low settings did not make it smaller. (The negative
  values in that log were the device shell's 32-bit arithmetic; the logger now divides in awk.)
- **New page-out trigger (16:23):** the tool now acts on what lmkd's minfree mode looks at, MemFree plus
  `Cached + Buffers - Shmem - Unevictable`, instead of MemAvailable (which counted the GPU memory as
  free). Threshold 2500 MB, checked every 0.5 s, page-out at most once a second. The idea: push CS2's
  swappable memory out early so the 3.2 GB of GPU memory fits beside it. The open-file logger was stopped.
- **Still to test:** CS2 without the kill; touch layouts, Offline Mode, Switch to Desktop, a Linux game from
  the card. TF2 on the device is the Windows build after the VAC test.

## 2026-10-14 (evening) - `feat/x86-64-steam-client`: Team Fortress 2 runs (sniper check, FEX thunks)

- **Sniper check:** `tf.sh` reads `VERSION_CODENAME` from `/etc/os-release` and stops unless it is
  `sniper`. DroidDeck direct cannot use Valve's sniper container (no user namespaces under Android), so
  for titles in `direct-sniper` it runs such a script through `bash -c` with that line replaced by
  `VERSION_CODENAME=sniper` (`92d65e3d`). Device: TF2 gets past the check and loads on the rootfs
  libraries; sniper itself is not needed.
- **Then a crash at the first frames**, with both launch options (Vulkan and Legacy OpenGL). Strict FEX
  memory ordering and turning off the Gamescope WSI layer changed nothing. With FEX thunks off (lavapipe,
  software) TF2 reached its main menu and loaded a match at 17 fps, so the thunks were the cause; with
  only the Vulkan thunk on it still crashed.
- **Cause (from a SIGSEGV report taken inside the game):** the main thread was executing Turnip's
  `vk_common_CmdBindDescriptorSets` as x86 code, an address FEX never linked. FEX-2609's guest
  `vkGetDeviceProcAddr` passes a lookup of `vkGetDeviceProcAddr` itself to the host and hands back the
  raw host function; every pointer fetched through it is unlinked. TF2's native DXVK (2.3) makes that
  lookup; the Windows games through Proton do not. FEX-2610 answers self-lookups in the guest
  (FEX-Emu/FEX 3397eb9b).
- **FEX 2610** (`af49e545`): `tools/fex/build-in-arch.sh` builds FEX-2610 with the same openat2 patch and
  host thunks as `fex-2609-r3`. The container's newer clang has no `FileManager::clearStatCache()`,
  which the thunk generator called after each run; patch `0002` drops that call (`31d66e67`). Published
  as `fex-2610-r1` on the personal repo.
- **2610 alone did not fix it.** An ARM-side signal hook in FEX (wrapping its `syscall(rt_sigaction)`)
  logged no fault in Turnip, and the guest thunk's disassembly showed why: Arch's clang builds the x86
  guest thunks with stack protection, and the canary load in `CallHostFunction`'s prologue
  (`mov %fs:0x28, %r11`) overwrote r11, which carries the host function's address, before the thunk
  stored it. FEX called the canary. 9 of 521 Vulkan and 66 of 736 GL host calls (those with 7+
  arguments) were affected, `vkCmdBindDescriptorSets` among them. Windows games never reach these
  thunks (they run on the arm64 Proton) and 32-bit games pass the host address in mm0.
- **`fex-2610-r2`** (`56e5a712`): the toolchain files add `-fno-stack-protector` for the guest thunks;
  no guest thunk library has a canary load. The x86 client fetches it (`9cc0ebcb`). Device, stock
  settings: TF2 main menu at 358 fps on the GPU, Casual matchmaking onto a VAC-secured Valve server,
  in game at about 84 fps; Half-Life (32-bit, direct) runs on it. Windows games are not on this FEX:
  in x86 client mode they still run on the arm64 Proton (Borderlands 2: GE-Proton11-7-aarch64), whose
  own FEX translates the game.
- **Which FEX runs what in x86 client mode:** the client (`steamrt64/steam`) and every Linux game on
  DroidDeck direct run on the runtime's FEX (`fex-2610-r2`); Windows games go to the arm64 Proton and
  its own FEX. At each start every library game with a Linux build, installed or not, is mapped to
  DroidDeck direct, so Steam downloads the Linux depots; Windows-only games stay on the default
  Proton, and a game already installed as its Windows build stays there (Borderlands 2 here:
  `Binaries/Win32`, depot 49521). A game new to the library mid-session is mapped at the next start.
- APK run 38670180921 (`9cc0ebcb`), signed and staged as `DroidDeck-x64-fex2610r2-9cc0ebcb.apk`.

## 2026-10-14 - `feat/x86-64-steam-client`: brought up to main; added games run through the x86-64 client

Work lives on The412Banner/DroidDeck only until it runs correctly; the org branch and draft PR #152
stay as they were (`afa479ee`).

- **Merged main (94 commits).** Main dropped the Steam channel and Deck-mode settings (#546): only the
  client choice (`steamClientArch`), `activeSteamRoot` and VAC diagnostics remain. Main's web-helper
  arguments (#566, `bl_webhelper_argv`) are composed with the x86 client's arm64 web-helper redirect in
  `preload/net.c`. Main's single background loop (#575) now carries the x86 hooks: the client's own
  compat pass (`--x64 --live`), `steamrt64` in the pinning and stale-tmp patterns, Lossless probe off for
  x86, and the once-per-title restart after a new mapping. The text-strings merge had dropped three of
  main's strings; main's files were taken and the six x86 strings added back.
- **Builds on the personal repo** need the pinned Debian image in a release of their own:
  `ci-debian-bullseye-r1` was copied (same sha256) as a pre-release. The bullseye compilers come from
  `deb.debian.org` first; `archive.debian.org` no longer serves `libmpfr6_4.1.0-3`.
- **Client update before start** (`x64_sync_client`): Valve serves one build to both clients; the x86
  client is brought to it (changed parts only, 9 of 28 on device) so it can use the arm64 web helper on
  the GPU from the first frame (device: 144 fps menus).
- **Web helper loop:** with main's in-process GPU arguments the x86 web helper's GPU crash took the
  whole helper down (102 restarts). The x86 helper now starts with stock arguments.
- **Loading screen:** "Starting Steam x86-64", and the x86 setup and update steps reach it.
- **Added games:** written first, then pointed at the x86 client's Proton (they were mapped to the
  arm64 client's tool name, which this client ignores). Windows components install into the x86
  prefix (DiRT Showdown and DiRT 3 needed OpenAL).
- **Hidden game window, two causes.** The arm64 launcher loaded the saved arm session's `DISPLAY=:0`
  over the game display Steam chose; it now keeps Steam's. And the game window had no `STEAM_GAME`
  tag, so gamescope never listed it as focusable; the agent's focus watcher now tags untagged
  `steam_app_<id>` windows on the game display when the x86 client runs. Device: DiRT 3 (added game)
  at its title screen at 144 fps.
- **Parity with the arm64 client:** Steam UI scale and the Steam import watcher run for the x86 client
  too (the import is untested). An SD-card library stays arm64-only: FEX cannot run Linux games from it.
- **Later the same day:**
  - DiRT Showdown's ~7 s exit was the same `DISPLAY=:0` problem; it runs now.
  - **Tools adopted as arm64 Protons:** main's `droiddeck-steam-shortcuts` calls `adopt_extras` on the
    client's `compatibilitytools.d`; in the x86 client that rewrote DroidDeck direct as a Windows tool,
    so Steam installed Half-Life's Windows files. The x86 client no longer adopts its tools, and its
    preparation removes what an adoption left. The arm64 Proton is adopted on demand at an x86 launch.
  - **Scout runtime:** shipped as `steam-runtime.tar.xz`; `steam.sh` unpacks it, and the x86 client
    is started without it. Its preparation unpacks it now. Device: Half-Life's native Linux build at
    101 fps on the GPU under FEX (32-bit), and a VAC-secured multiplayer server.
  - **Install scripts:** the evaluator script is looked up in the x86 client's folder. A game's own
    installers without a `HasRunKey` (Borderlands 2: VC++ 2005/2008/2010, DirectX, .NET 4.0) are now
    marked under `Software\Valve\Steam\Apps\<appid>` (and the Wow6432Node view); Steam skips them.
    This applies to the arm64 client too. Device: Borderlands 2 started past its install script, and
    on the build that marks them itself it went straight into the game.
  - **Windows components** detection follows the chosen client (in-session install watch and
    `appinfo.vdf`).
- **Next:** Counter-Strike: Source, the first game on the newer (sniper) runtime path, where DroidDeck
  direct runs it on the rootfs instead of the scout runtime.
- **Open:** newer-runtime Linux games (sniper) untested; the arm64 client untested on this build; the
  Steam import watcher untested; an SD-card library for the x86 client deferred.

## 2026-10-14 - `gog-library-listing`: the GOG library is drawn from the account listing, details follow

- **The listing** (`GogLibrary`): the library came from `embed.gog.com/user/data/games`, which is the owned
  ids and nothing else - no title, no art - so the tab stayed empty until every product had been asked about
  one by one. It now comes from `account/getFilteredProducts` (`mediaType=1`, `hiddenFlag=0`, `sortBy=title`),
  a hundred to a page, which carries the title, the category and the release date. Its `image` is a bare
  hash the CDN serves only through Galaxy's own formatters, so a listing entry's card art is
  `<hash>_glx_vertical_cover.webp` until the per-product fetch brings the wide background.
- **The listing can fail** (`GogLibrary`): when it cannot be read the sync falls back to the older
  owned-ids endpoint and keeps the old behaviour - there are no titles to draw, so nothing is painted
  until the products come back.
- **The tab paints first** (`GogLibrary.sync`): the whole library goes to `GogBackend` the moment the listing
  is in, so the grid is on screen in seconds instead of after the last product.
- **Each card fills itself in** (`GogLibrary.sync`, `GogBackend`): every product that comes back is handed
  over on its own and only that entry is replaced, so a card swaps to its own art and title as soon as its
  product has been asked about while the cards around it keep their objects - no list-wide repaint, no reset
  of the scroll or the focus. A product this round finds uninstallable is dropped when the round ends, not in
  the middle of it. The progress bar is gone once the grid has games, so a sync in progress no longer reads
  as a list still loading.
- **The art swap has no gap** (`GogLibrary.sync`, `StoresPage`): a card that swaps from the listing's cover to
  the wide background keeps the cover while the new one decodes - the fetched record carries the cover the
  card already shows, and the card's request names it as the memory-cache placeholder - so no card goes dark
  in the middle of a sync.
- **The per-product fetch stays** (`fetchGame`): `api.gog.com` is what has the description, the developer,
  the publisher, the Galaxy portrait and the build generation, and the detail page reads all of those from
  the library entry, so the listing alone cannot replace it.
- **The cache keeps only what was fetched**: a listing entry that never got its details is asked about again
  next sync rather than kept as a bare title, and a product the last sync found uninstallable stays out of
  the list while it is re-checked. The cache's own entry stays on screen even while it is re-fetched, so a
  forced refresh does not drop back to box art.
- **A card patch adds what it brought** (`patchItems`, `GogBackend`): a product that comes back is patched into the
grid where its title belongs, whether or not the grid already had that card. Replacing only the entries already
there left a sync with nothing painted - the owned-ids fallback - showing an empty grid for the whole round. An
empty listing is no longer posted either: that dropped the cards already on screen.
- **A bar needs an empty tab** (`StoresPage`): the bar is drawn only for a sync line that carries a count ("25/55")
  and only where the tab has nothing else on it - the storefront's shelves and the Installed tab's own cards are
  content - so a library sync no longer creeps along above cards that are already there, and a line that only says
  what it is doing leaves the empty state to say it.
- **A page that fails keeps the pages that came** (`GogLibrary`): a listing page that cannot be read ends the
listing instead of discarding it, so a page lost to a flaky request no longer costs the whole early paint. The
cache is saved as the round goes, so a sync the user closes the app on still leaves a library to paint next launch.
- **The hero waits under the card's art** (`StoresDetail`): the library's hero asked for the wide art with no
  placeholder of its own, so a card that opened showed its own bitmap only for the length of the opening
  animation and then fell back to the title's colour until 300-540KB had been decoded. It now builds the same
  request a card does, naming the card's art as the memory-cache placeholder, so the cover stays under the hero
  until the wide one lands.
- **The listing says what it gave** (`GogLibrary`, `StoreNet`): one line per sync in the stores' log - how many
  games the listing carried, or the page that could not be read - and every failed store GET, which used to
  reach logcat only. A shared log can now show whether the account listing came back at all.
- **Tests** (`GogListingTest`, `StoreCatalogPatchTest`): the filters - movies, other platforms, Galaxy's own entry, empty titles - the
  fields a card needs, and a response that is not a listing.

## 2026-10-10 - `feat/wincomp-auto-sources`: Windows components picked by the game's source

- **Per source** (`WinCompSources`): Steam - Steamworks Shared depots from the appmanifest or the
  client's app info (`SteamAppInfo`, appinfo.vdf v27-29, cached per appid, weekly retry); GOG - the
  build's `dependencies` (now kept in the sidecar); Epic - the manifest's prerequisite (now parsed
  and kept); Amazon - fuel.json `PostInstall`; Custom - Steam's list when its files name the appid
  (on by itself), when only the name matches (Recommended); else the folder scan. Unmapped entries
  are listed with their own names; mapping tables in one place.
- **One Steam match per game** (`SteamMatch`, `files/steam-matches.json`), used by the art fetch
  and the components alike; the earlier art lookup is taken over.
- **On by itself** (`AutoComponents`): at store install, add/edit, page open and before a launch
  from DroidDeck; `wincomponents.json` keeps `auto` (with reasons) and `user` switches beside the
  effective `games` list (still version 1). A user switch always wins. The page gets "On for this
  game" with reasons and "Also listed".
- **Device fix:** on a Steam game (Need for Speed Payback) only the already-downloaded d3dx9 went
  on; vcredist2013/2019 stayed Recommended, never downloaded (Steam games have no install event
  here). Every refresh (page open, launch, an app-start sweep over Steam games, install/add/edit)
  now queues the missing automatic picks for a background download and turns each on when it
  lands; the open page shows the progress and reloads.
- **Gentle on the device:** one low-priority worker and one queue (games and components each once,
  what the user asked for first); nothing while a session runs; the start-up sweep and pre-launch
  looks only queue, wait ten seconds after start, and skip battery saver, a warm device or a
  metered network; runtime helpers under nice 19 / ionice idle; a game's list kept and worked out
  again only when its files change, on the worker. Tests: download lands, queue paused while a
  session runs, unfavourable device, dedupe.
- **Fluid:** after a session the Games tab reads Steam's manifests first (no wait for the added
  games' walk), a newly installed game eases into the list and its components' list is queued on
  the worker. The components page shows "Checking what this game needs…" (small spinner) until
  the list is worked out, never "nothing found" before; "On for this game" expands in, components
  on their way sit there with their progress and a "Turning on N components…" line, and rows ease
  in (~250 ms; instant with animations off).
- **Quieter:** "Checking…" only when the game's list was never worked out; a kept list shows at
  once and is checked again silently, its changes easing in. The lists wait for the catalog and
  fade in once; "Loading the components…" only after 300 ms.
- **Offline:** with no validated network, components not downloaded yet are greyed and cannot be
  switched ("Available when online"), automatic picks on their way included (no progress bar);
  downloaded ones stay usable. The page follows the network live, and downloads wait in the queue
  until it is back.
- **Every source, older installs:** the start/after-session pass covers all games (Custom, GOG,
  Epic, Amazon). GOG/Epic games installed before the sidecar kept the list get it once from the
  store's build manifest with the saved sign-in (`StoreListBackfill`; no game files; weekly retry
  on failure). Fresh installs keep the keys even when empty.
- **Apps the client never saw** (Dragon's Dogma 2, 2054970 not in appinfo.vdf): the client ask
  gave nothing (no `SteamClient` call returns depots), so Steam is asked anonymously instead
  (`SteamPics`: CM list, WebSocket CM, anonymous logon, PICS access tokens + product info, ≤25
  apps a request, one connection a pass, on the worker; Kotlin, no new library). Prototyped first
  against Steam from the build host: 2054970 → depot 228988 (VC++ 2019), 1190460 → one shared
  depot. Cached in steam-appinfo.json like the client's, weekly retry on a miss.
- **Installed during a session:** the Steam libraries' appmanifests are looked at every 5 s while
  a session runs; a game turning fully installed gets one in-session job (waits while a game runs)
  that writes its automatic picks and downloads only its missing components; the launch waits up
  to 10 s for components listed in `wincomponents-pending.json`, never when nothing is pending.
- **Checks:** a store list counts as read only when the manifest was read whole (`listRead`;
  GOG: a manifest with depots, Epic: the prerequisite fields read); older empty lists are fetched
  once more. Log: `backfill: <game> gog deps=[...]` / `epic prereq=<name|none>`, plus the
  manifest's depot/file count. Unity's `*_Data/Managed` is skipped by the folder scan (Mono
  assemblies, not .NET Framework).
- Docs: `docs/development/windows-components-sources.md`. Tests: `WinCompSourcesTest` (each
  source's fixture, certainty rules, appinfo reading, auto picks, user overrides, shared match),
  `AddedGameArtLookupTest` updated. Not yet on a device.

## 2026-10-10 - `fix/games-folder-migration`: Games folders become entries, not Steam libraries

- **Seen on a device:** Steam's Install dialog listed "Games (Internal shared storage)" and "Games
  (android)", the user's two Games folders saved before #492, still registered as libraries
  while Second library is Automatic; with the settings section gone they could not be removed.
- **Migration** (`GamesFolderMigration`, once at app start and before any scan, idempotent):
  every game a Games folder listed becomes an entry (`AddedExes`), keeping its chosen exe, stored
  appid, name / Start in / launch-option overrides, art choices and removal (all keyed by its
  folder). Store installs and `steamapps` are skipped. The folders stay bound at their old guest
  paths (`SessionPrefs.gameFolderBinds`), so the listing and every Steam shortcut are unchanged;
  `addedGamesDirs` is cleared. Log: `games: migrated <root> → N games`.
- **Libraries:** `GameStorage.gamesFolderLibraries` returns only a Games folder Steam installed
  into (an appmanifest in its `steamapps/`); the others drop out of `BL_STEAM_LIBRARIES` and the
  runtime's `droiddeck-steam-library` forgets the `/root/Games/...` entries it had registered.
  The user's two folders have `steamapps/` without manifests (checked on the device), so both go.
- **Tests:** `GamesFolderMigrationTest` (exe / appid / name / removal kept, guest paths unchanged,
  idempotent, store and steamapps skipped, libraries empty, same-name folders), `GameStorageTest`
  updated. Not yet on a device.

## 2026-10-02 - `feat/x86-64-steam-client`: Valve's x86-64 Linux client under FEX (experiment)

Why: the arm64 Linux client never runs VAC. L4D2 (and TF2 in Bannerlator) are refused by VAC
servers ("Connection Blocked ... VAC secured server") and no client log mentions VAC; VAC modules
are x86 code. The question is whether Valve's x86-64 client under FEX gets a VAC module it can run.
Branched from `feat/vac-diagnostics`. Opt-in: `bannerlator-steam-x64` (desktop menu "Steam x86-64
(FEX, experimental)") runs the client in its own home (`~/.droiddeck-x64`) with its own rootfs,
login and library. The arm64 client stays the default and is not touched.

Device-proven on the FIT (Genshin repack): **the x86-64 client runs, self-updates, renders its full
UI (software GL), logs in, shows friends, and downloads games.** VAC itself is not tested yet.

What it took, in order (each step was the next failure on device):
- **FEX for the guest** - `tools/fex/build-in-arch.sh` + `build-fex.yml` (FEX-2609, Arch Linux ARM
  container, no thunks), released as `fex-2609-r2` (not Latest). Rootfs: FEX's Ubuntu 24.04 image.
- **openat2** - our proot answers it with ENOSYS; FEX opened every rootfs path with
  `openat2(RESOLVE_IN_ROOT)`, fell through to the arm64 guest on anything but EXDEV, and x86 bash
  could not find libtinfo. `tools/fex/patches/0001-openat2-enosys-fallback.patch`.
- **FEXServer owns the rootfs** - a server started before `FEX_ROOTFS` (any earlier FEX run) answers
  every client with no rootfs. The launcher stops it first.
- **32-bit x86 died with SIGILL** - libblfastpath (the proot fast path, preloaded into every guest
  process, FEX itself included) breaks FEX's 32-bit mode. `PROOT_FP_OFF=1` for FEX.
- **"Steam now requires user namespaces"** - steam-runtime-check-requirements' bwrap probe; the
  arm64 client has no such probe. The SteamRT3 client (`steamrt64/steam`) is started directly, the
  way the session starts `steamrtarm64/steam`, with `STEAM_RUNTIME_STEAMRT` pointing at Valve's
  runtime linked file by file and only the probe replaced.
- **tier0 assertion "Function not implemented"** - semget: Android has no System V IPC. Traced with
  a `-DDEBUG_STRACE` FEX (build-fex.yml `cxxflags` input, artifact only). The session shim
  (`preload/*.c`) is now also built natively as `libblsession-x86_64.so`, staged, and preloaded
  through the rootfs's own `/etc/ld.so.preload`.
- **re-exec into pressure-vessel after self-update** - `STEAM_STEAMRT_RECURSION_GUARD=1`.
- **libgtk-x11-2.0 missing / soname links / old glibc shadowing** - Valve's steamrt3c platform
  libraries linked into `~/.droiddeck-x64/steamrt-libs`, soname links from the rootfs `ldconfig`,
  and only libraries the rootfs lacks (LD_LIBRARY_PATH beats system folders whatever its order).
- **GLX failed** - the desktop exports zink over the arm64 Turnip ICD; cleared, llvmpipe used.
- **web helper died of SIGTRAP a second after start** - found with pid-tagged traces (FEX AppConfig
  `OutputLog=server` + a foreground FEXServer, `BL_FEXSERVER_LOG`; file logs overwrite each other):
  every web helper read `/proc/self/status` from libtier0_s.so, saw proot as a tracer, and tier0 broke
  into the "debugger" with int3. `preload/tracer.c` exempts the web helper on purpose (Chromium traps
  on a status fd that is not procfs); with `BL_WEBHELPER_HIDE_TRACER=1` only opens called from
  libtier0_s.so get the copy.
- `SessionFiles` stages `bannerlator-steam-x64` and the x86-64 shim (they were missing from the list).

Dead ends worth remembering: headless VAC testing is impossible (current Steam starts the saved
login from its UI); `-cef-*` switches are not forwarded to the x86 web helper; the KMS
`CREATE_DUMB` errors are a red herring (40 fast ENOTTY per process); not an address-space problem
(0 ENOMEM in 618k traced syscalls, although the device is 39-bit VA).

Later the same day (device-proven unless noted):
- **Controllers**: libfakeinput is now also built for x86-64 and preloaded in the FEX rootfs; the
  client registers the pad, and **Big Picture works with it** (Xbox prompts, D-pad moves focus).
- **Online**: the launcher starts the system bus and the NetworkManager stand-in like the session.
- From an audit of `bannerlator-session`: stale lock cleanup, branch sync before every start, core
  re-pinning, path cache and `perms.c` for `steamrt64`, the app's FEX preset (PERFORMANCE_TSO).
- **Play button (built, not yet device-tested)**: Steam settings > Client > "Steam client: ARM64 /
  x86-64 (FEX, experimental)" (`BL_STEAM_CLIENT_ARCH`). The session then starts the x86 client via
  `bannerlator-steam-x64 --prepare/--exec` and gives it everything it gives the arm64 one; steps
  only for the arm64 client (ARM64 Proton, mapping, seeding, Decky, steamclient.so probes) are
  skipped.
- L4D2 hard-link reuse does not work: switching platform makes Steam redownload depot 551 anyway.

Evening (device-proven unless noted):
- **Play button with the x86-64 client works** (Steam settings > Client > Steam client: x86-64).
  Deck mode is skipped for it: with -steamos3 its SteamOS audio manager aborts on the runtime's
  PulseAudio 13 (pa_operation_get_state assertion).
- **Left 4 Dead 2 runs in the x86-64 client and joined a live online game** (Quick Match, Swamp
  Fever, other players, 138 ms) - the server type that refused the arm64 client with "Connection
  Blocked ... VAC secured server". Not yet hard proof of VAC (no client log line; -condebug added
  for the server's secure flag). Software rendering: ~11 fps.
- What the launch needed: "DroidDeck direct", a compatibility tool running native Linux games with
  Valve's scout library runtime instead of the Steam Linux Runtime container; a stand-in
  steam-runtime-launch-client (the client, believing itself inside the SteamRT container, starts
  every game "alongside Steam" through it, and Valve's needs a launcher service on a session bus);
  i386 builds of the session shim (the i386 controller reader crashes the game and is left out for
  now). A steam:// URL written to the launch dir now reaches the running client.
- **FEX with host thunks** (fex-2609-r3): FEX's Vulkan/GL/EGL/DRM/Wayland/ALSA thunks, 64- and
  32-bit guests, guest halves cross-compiled against an Arch x86_64/multilib sysroot. The x86 client
  uses them when installed (GPU mode, the arm64 client's ANGLE-on-Vulkan flags) - built, not yet
  device-tested.
- The proot fast path now stands down in FEX only for 32-bit programs (it broke FEX's 32-bit mode).
- **On the GPU, VAC proven (19:40):** games get FEX's host thunks and the session's Turnip/Zink
  settings (the client and its browser stay in software: with the thunks the browser's GPU process
  dies and the client never opens a window). Left 4 Dead 2 in The Passing on an online server:
  51 fps (was ~11 in software). The server answered the A2S info query as "Valve Left4Dead 2 US
  East Server", VAC=1 - an official VAC-secured server, which the arm64 client is refused.
- The crash that held this up was not the GPU: the 32-bit controller reader was still preloaded
  (FEX falls through to the session's own i386 copy), and the game's SDL2 strdup()s a NULL udev
  attribute of its fake devices. Found with a SIGSEGV logger (tools/diag/segvlog.c).
- The Linux FEX as a component file: /sdcard/Download/FEX-2609-r2-x86-64-guest.wcp (type
  FEX-Guest); the Components section does not know that type yet.

Open:
- VAC test: L4D2's native Linux build in the x86 client's own library (hard-linked from the arm64
  copy, same filesystem, so only the Linux depots download). Native Linux games start inside the
  Steam Linux Runtime container (pressure-vessel) - expected to need the same treatment.
- No x86 GPU driver: games render with llvmpipe. Playable needs FEX host thunks.
- Windows games in the x86 client need x86 Proton = pressure-vessel.
- One client per account: integration would be a per-title hand-off, not side by side.
## 2026-10-09 - `feat/games-add-edit`: add games from the Games tab, edit them like Steam's Properties

- **Steam ⚙ settings:** the Games section is gone (it was a feature, not a setting). Games
  folders added before keep listing their games.
- **+ beside "Games N":** a compact in-app picker (storage step, then the storage root; every
  folder A-Z, unreadable ones greyed; `.exe` only; width fixed at opening; red X; B goes up).
  Picking an exe adds the game (name from `GameIdentifier`, art, Custom chip, Steam shortcut via
  the listing and the running client, Start in = exe folder) and selects it; an exe in a listed
  game switches its target; the same exe again selects it; a path the session cannot see is
  refused. Internal storage anywhere now maps to `/root/Storage`.
- **"Add all games in this folder":** each subfolder with its best exe (`GameExePicker`, depth
  5, "?" when unsure), listed ones skipped, then a summary (added, already there, rows that open
  the editor with back to the summary).
- **✎ editor** on a Custom game: Name, Target (ranked, "?", Other exe…), Start in, Launch
  options, Artwork (Cover, Background, Logo, Icon with thumbnails and sources; chooser with the
  game folder, Steam and SteamGridDB; Pick image…; Reset to automatic), Remove (two presses, files
  kept). Every edit goes through the same shortcut on its stored appid (listing + live DevTools);
  the writer keeps Steam-side edits and records them, and the app takes them over once.
- **Setup:** Artwork switch and SteamGridDB API key (user key sealed with the Keystore, wins over
  `BuildConfig.SGDB_API_KEY` from the `SGDB_API_KEY` secret).
- Brought over from `feat/added-games-remove` (not merged): `GameExePicker`, `GameIdentifier` +
  `PeVersionInfo`, the art and SteamGridDB work, the build secret, the + and the picker.
- Docs: `docs/development/added-games.md`. Tests: `AddedExesTest`, `AddedGameEditsTest`,
  `AddedGamesCandidatesTest`, `AddedGameArtLookupTest`, `GameIdentifierTest`, `PickListingTest`,
  `AddedGamesRemoveTest`, `test_steam_shortcuts.py`. Not yet on a device.

## 2026-10-09 - `feat/store-credentials-keystore`: store sign-ins sealed with the Android Keystore

- **What:** `filesDir/stores/<store>/credentials.json` (GOG, Epic, Amazon: tokens, ids, names,
  expiry) is now an AES-256-GCM envelope (`{"v":1,"alg":"AES/GCM","iv","ct"}`) under a
  non-exportable AndroidKeyStore key (StrongBox if present, else TEE; no user authentication).
  `StoreAccounts` is the one layer; `CredentialCipher` holds the envelope and the key.
- **Upgrade:** a plain file is sealed at the first start (verified, then replaced); users stay
  signed in. Log: `stores: credentials encrypted <store>`.
- **Fallbacks:** no Keystore - stays plain, logged once, retried next start. A file that can never
  open (bad tag, key invalidated or missing, not an envelope) - deleted, signed out (sign-in card).
  Any other Keystore or I/O failure - file kept, store stays signed in but unavailable (actions show
  `<Store> sign-in could not be read: try again.`), logged once, retried on the next access. Never
  a crash.
- **Session:** the guest sees the envelope only; it never read the files (the Epic code comes over
  the request channel, written beside the launcher and deleted by it).
- **Tests:** `StoreAccountsCipherTest` (round trip, sealed on disk, migration of all three stores,
  tampered file, lost key, key missing, garbage, no-Keystore fallback then sealed next start,
  transient Keystore / I/O failures keep the file and a later read succeeds, sign-out). Not yet
  on a device.

## 2026-10-08 - `feat/private-logs`: every log in app-private storage, shared as one scrubbed zip

- **Where logs live:** `files/logs/` (`LinuxRuntime.logDir`), nothing under `Download/DroidDeck`
  any more. Session folders (`<day>-NN-<what>/`), `tools/` (GuestCommand, Flatpak, Windows
  component installers) and `stores/` (the stores' engine log, one file a day) all sit there. The
  files directory is bound into every session at the same path, so the session script, gamescope
  and the guest tools write there as before. Thirty sessions kept; the Logs switch off still means
  a cache folder deleted at session end. Game save backups stay public in `Download/DroidDeck/Saves`.
- **Moving the old ones:** on the first start of this build, session folders, `tools/` and loose
  `*.log` files move from `Download/DroidDeck/` into `files/logs/` (rename, else copy and delete;
  an item that fails stays). A marker records the pass; `Saves/` and anything else there is never
  touched, and the app does not read `Download/DroidDeck/` again.
- **Share logs:** one zip in `cache/share/`, made on demand: the newest session folder (or the one
  the session screen shares), the last seven days of `stores/` and `tools/`. Every text file goes
  through `LogRedactor.redactForShare` on the way in - the existing rules (Steam tokens, JWTs,
  SteamIDs, account names, emails, own addresses, MACs, serials) plus URLs without query, fragment
  or userinfo and with token-bearing path segments blanked (GOG secure links), `__token__`,
  `f_token`, `hdnts`, OAuth `code=`, and Authorization / Cookie headers - so a file written before a
  rule existed is clean in the zip. The zip is deleted at the next share or app start.
  `SessionLogShareTest` covers it. Nothing was relaxed: the per-session scrub, `scrubOlder` and
  every existing rule stay as they were.
- **File manager:** the Session logs shortcut opens `files/logs` (the app reads its own storage).
- **On main after Stores (#476) and the KDE Plasma desktop (#473):** the stores' log is already
  `files/logs/stores/stores-<date>.log` and the zip carries its last seven days under `stores/`. One
  set of rules scrubs both the stores' log and the zip (`SecretScrub`): `StoreLog.redactLine` keeps
  URLs to their host and first plain segment, the zip pass keeps paths with token segments blanked,
  both blank token values and Authorization / Cookie headers; the two test suites pass on it. The
  Plasma desktop writes nothing outside the session folder (its output is the session's
  `desktop.log`), so there was nothing new to move.
- **Privacy scan fixes:** the Steam login account (an email, 374 times) was still in older
  folders' copied `steam/webhelper_js.txt` (`SteamUI: INFO: Login: OnLoginStateChange <account>`):
  those folders carried a scrub marker from before the account rules, and the marker never
  expired. The marker is now named after the redactor's rules version (`.scrubbed-r3`), so every
  folder scrubbed under older rules goes through again at app start, steam/ and every subfolder
  included; folders moved out of `Download/DroidDeck` lose every old marker
  (`SessionArtifacts.unmarkMoved`), so the same pass takes them whatever those markers said. New rules: `OnLoginStateChange <anything>`, and
  `AccountName` / `account_name` / `username` / `login` fields (`login` only as `login=`, a quoted
  key or a VDF pair, so the "Login:" label stays). The `code=` rule no longer touches Proton's
  exception codes (`code=c0000005`, `406d1388`, `80000003`): a bare `code=` is blanked only right
  after `?`/`&` or on a line with OAuth keys (`client_id`, `redirect_uri`, `state`, tokens), and
  never when it is 8 hex digits; exchange / authorization codes are always blanked. Tests:
  `LogRedactorTest` (the login line with an email and with a plain name), `SecretScrubTest`,
  `SessionLogShareZipTest` (older and moved folders scrubbed on disk).
- **Device check (31 migrated folders):** only 4 carried `.scrubbed-r3` a minute in. Nothing was
  skipped - the pass was still running: one folder holds ~30 MB of Steam logs, ~30 s through the
  redactor, one folder after another (about 15 minutes for 31). The start pass now scrubs a few
  folders at a time (half the cores, at most 4, background priority), old markers go whether or
  not a folder's pass succeeds, a folder the process does not finish is taken up at the next start,
  and it logs one line: `logs: scrubbed N folders under r3`. Test: nine migrated folders carrying
  `.scrubbed-2`, `.scrubbed-1` or a stale `.scrubbed-r3` all end up scrubbed under r3 after one
  migration + start pass.
- **LAN addresses in the zip:** private IPv4 addresses (10/8, 172.16/12, 192.168/16, 169.254/16;
  e.g. the router in `LinuxNetworkLink: resolver: ...`) become `<lan-address>` in the zip pass;
  public server addresses and dotted version numbers stay (`LogRedactorTest`).
## 2026-10-08 - `feat/stores`: GOG, Epic Games and Amazon Games in the launcher (in progress)

A new **Stores** section - the three storefronts' libraries and public catalogs, one download
queue for them, and every install registered in the Steam client exactly like an added folder.
Ported from Bannerlator's store clients (auth, API, library sync, download managers); its
Activities and XML are not ported, the UI is Compose, after the approved preview. The native
download engine is the Rust side's work (`libdroiddeckstores.so`, loaded lazily; the app runs
without it and Setup says "Stores engine not built").

- **Where it lives.** Rail item `stores` under Games, gated by Setup › Stores › "Show Stores in
  the rail" (`SessionPrefs.gameStoresEnabled`, off by default); a count badge while anything is
  downloading. The page is full width: four chips (GOG · Epic · Amazon · Downloads, a signed-in
  dot per store) and a cog at the row's end whose popup holds the section's own settings - which
  tab a store opens on (`storesOpenTab`, Library by default; applied on chip tap, never while the
  user is switching tabs by hand) and the speed tier (`gameStoresSpeedTier`, Balanced / Fast /
  Max = 16 / 32 / 96 requests in flight). Setup repeats the rows under the gate. A signed-out store
  shows its sign-in card first. Every install is added to Steam - the user's call: "Add to Steam
  shouldn't be optional, automatic" - so the switch an earlier build had (`gameStoresAddToSteam`,
  the sidecar's `addToSteam`, the page's "Add to Steam" button) is gone; an older sidecar's field
  is read past and ignored, and the game page shows a passive "In Steam" chip. The Games hero's
  eyebrow for a never-played store game reads "Games storage" (the chip names the store), and
  "Custom game" for a hand-added folder; Steam titles keep their library label.
- **A store game is an added game.** The install lands at `<Games storage>/Games/<Store>/<title>`
  (the SD library's folder when one is chosen, else the runtime's own `/root/Games/Stores`) with a
  `.droiddeck-store.json` sidecar (store, id, title, exe, launcher, args, env, version,
  addToSteam, art). `AddedGames.scan` now walks those folders too and `scanGame` reads the sidecar
  for the exe and the name, so the game goes down the existing path: `session/added-games.json`
  → `droiddeck-steam-shortcuts` at the client's next start. The listing is rewritten at install
  time, and with a client running the shortcut is added live over the client's DevTools port
  (`SteamClient.Apps.AddShortcut` + our tag + the compat tool; `SteamLiveShortcuts`), best-effort.
  Uninstall removes the folder, the sidecar and the shortcut.
- **Launch.** A shortcut can only name an exe and the listing carries no launch options, so a game
  that needs arguments or environment gets a `.droiddeck-launch.bat` beside it (Proton's steam.exe
  shim hands it to Wine's cmd): Epic's identity arguments, Amazon's FuelPump variables. Epic's
  per-launch exchange code is minted by the app right before a launch into `.droiddeck-epic-code`,
  which the script reads once and deletes; with no code the game starts in its offline identity
  mode. The EOS overlay is never provisioned.
- **Games tab badges.** `Library.SteamGame.source` (steam / gog / epic / amazon / added, from the
  sidecar) with a coloured chip on every row and in the hero eyebrow; `LibraryCache` keeps it.
- **Downloads.** `DownloadQueue` runs 1-3 at a time, stages Manifest → Download → Verify →
  Install, pause = stop and keep the files (every engine skips verified files on the rerun),
  cancel deletes; `StoreDownloadService` keeps the process alive meanwhile; the page shows speed,
  ETA, the tier, downloads-at-a-time and the engine log.
- Tests: `StoreGameSidecarTest`, `AddedGamesStoreScanTest` (JVM), `tools/tests/test_store_shortcuts.py`
  (the writer with a launcher .bat as Exe, the appid the app computes, removal).
- Files: `stores/*` (Store, StoreGameSidecar, StoreInstallRoot, StoreInstalls, StoreLaunch,
  StoreAccounts, StoresState, StoresNative, SteamLiveShortcuts, download/*), `ui/StoresPage.kt`,
  `ui/StoresDetail.kt`, `ui/StoresDownloads.kt`, `ui/StoreChips.kt`, `ui/StoresWidgets.kt`;
  `frontend/AddedGames.kt`, `frontend/Library.kt`, `frontend/LibraryCache.kt`,
  `ui/FrontEndRail.kt`, `ui/FrontEndContent.kt`, `ui/FrontEndSetup.kt`, `ui/FrontEndGames.kt`,
  `ui/FrontEndScreen.kt`, `MainActivity.kt`, `session/SessionPrefs.kt`, `AndroidManifest.xml`
  (the download service), `res/values/strings.xml`.
- **The three stores** (`stores/gog`, `stores/epic`, `stores/amazon`), ported from Bannerlator's
  clients with their storage replaced: GOG's OAuth page (Galaxy identity, a browser identity for
  the social-login hop), library from `embed.gog.com` + `api.gog.com`, shelves from
  `catalog.gog.com`, gen2 chunks / gen1 ranges / plain installer; Epic's web login (the code off
  the redirect's JSON page), library service + catalog, the store's GraphQL and the free-games
  feed, ChunksV4 manifests with install tags and a delta pass, the manifest's launch exe; Amazon's
  PKCE device sign-in, entitlements, `manifest.proto` (the app's xz library), SHA-256 per file,
  `fuel.json` / exe scoring, FuelPump variables; Amazon has no public catalog, so its Store tab is
  cut from the account's own games. Each manager hands the byte-fetch loop to the native engine
  when `libdroiddeckstores.so` is there (`GogNative` / `EpicNative` / `AmazonNative`, JNI.md's
  names and signatures) and runs its Java pool otherwise.
- Left out on purpose: GOG DLC installs and GOG redistributable installs (Steam seeds the shared
  redistributables in the prefix as for any shortcut), Epic's Denuvo ownership token and overlay,
  Amazon's SDK DLL deployment into the prefix (the prefix does not exist before the first launch),
  cloud saves, and the stores' social tabs - none of them in the preview.
- **Card art (device feedback on `61a9a48`, GOG › Library washed out to white):** the GOG library
  had drawn the product's `images.background` - the store page's fade-out backdrop, white once its
  alpha is gone. The library now takes `api.gog.com/v2/games/<id>` `_links.galaxyBackgroundImage`
  (the Galaxy client's dark 16:9 library art) and `boxArtImage` (the vertical cover), cached with
  the sync (cache key bumped so old caches re-fetch); the catalog's `productId` filter was tried
  and ignored by the service, so it is one call per owned game. Epic cards prefer `DieselGameBox` /
  `DieselGameBoxTall` over the offer images; Amazon prefers `backgroundUrl2` and never the logo.
  Cards draw over a dark surface with a fade into the card at the foot. On install `StoreArt`
  places `cover.jpg`, `hero.jpg` and `header.jpg` in the game folder, re-encoded as opaque JPEGs
  over a dark ground, under the names `AddedGameArt` looks for - so the Games tab and the client's
  grid (through the listing) get real art for store games.
- **Device feedback on `9f5a2f3` (GOG › DOOM I Enhanced page):** (1) the pad lost focus when a
  card opened its page (the card left composition; the next press landed on the rail). The page
  now follows the Flathub store's pattern: a focus move is recorded on each change and
  `focusWithinFrames` lands it - the game page's main action (or its back link), the card the page
  was opened from on the way back, the first card after a tab or chip change (`firstTile`); the
  page is a `focusGroup`, so Left is the way to the rail. (2) GOG's account endpoints return
  template keys (`product_description_2015545325<br>product_feature_…`) for some products; the
  v2 record has the same keys for them. `cleanStoreText` drops those keys, strips tags, decodes
  entities; the library tries the product's lead, then v2 `overview`, then v2 `description`, and
  shows nothing when all are templates. Epic's and Amazon's text goes through the same cleaner.
- **Speed shown = install rate.** Device evidence (Epic › Metalstorm, 1.68 GB on Max to the SD
  card): the engine's windows burst at 0.4–20 MB/s with 0–3 of 20 requests in flight while the card
  writes at 26 MB/s - the fetchers wait on the write side, so the engine's `speedBps` jumped while
  the bar advanced at the write rate. `DownloadQueue` now measures the shown speed itself from the
  byte deltas (an exponential average over 3 s, sampled at most every 250 ms) and derives the ETA
  from it, for all three stores; the engine's own figure stays in its log lines, and the detail
  line under the bar has it stripped. The tier and downloads-at-a-time controls sit on one compact
  row, on the Downloads page and in the cog's popup.
- **Install location (user decision):** the default is the app's internal storage for all three
  stores, whatever the session's Game storage says. With a card in the device, Install asks
  "Install to" first (internal / the card, free space per target, pad-focusable, last pick as the
  default only). A card that is not the Steam library gets a bind of its own in the session
  (`/mnt/droiddeck-stores/<volume uuid>`; the library's own bind covers a library card, so installs
  already registered there keep their appid); every root is scanned and uninstall works for
  either. The Downloads
  row shows where a game is going; the game page names the
  place. Epic installs on a card keep their in-flight chunks in the app's cache: the engine's
  `chunkCacheDir` (fetch and the new `nativeAssemble`, which drops each chunk after its last use)
  and the manager's own loops read the same path; internal installs pass `""` as before. Cleaned on
  cancel-with-delete and uninstall.
- **Half-installed Epic game shown as Custom (device, Metalstorm on the SD card).** The folder had
  2.85 of 4.4 GB of files, a 0-byte file where the assembly stopped (19:15:10, four minutes after it
  began), 9.1 GB of `.chunks`, and no sidecar. The run stopped inside the assembly loop - before the
  `.chunks` removal and the sidecar, both of which only ran at the very end; the events log does
  not reach back far enough to say whether the process died or the loop threw. Either way three
  things made it silent: the sidecar was only written last, the queue lives in memory (a process
  death drops the row), and `AddedGames.scan` listed any sidecar-less folder under a store root as
  a Custom game. Now: the sidecar is written at the start in state `installing` and finished at the
  end; unfinished folders (installing, or no sidecar) are never scanned as games; the Stores card
  reads **Resume install**, which reuses the folder and its `.chunks`; launcher/sidecar write
  failures fail the download, art and Steam registration are best-effort. The 9.1 GB cache is
  whole 1 MiB chunk windows shared with files outside this device's install tags - the free-space
  check now counts cache and files; a successful run removes the cache (Kotlin loop and
  `nativeAssemble` both).
- **The cog popup is capped at 440 dp**, like the Install dialog.
- **Store cards are art + one line** (maintainer feedback): no source chip (the tab says the
  store), no "In library" chip, no action button. The title sits left; right-aligned is a check
  when installed, Resume, the download percentage, the install size, or Free / price / discount.
  A thin bar over the art while downloading. Install, Resume, Uninstall, Get and Buy live on the
  game page; A on a card opens it.
- **No log panel:** the Downloads page's engine log is gone. Store lines go to logcat and to
  app-private daily files (`filesDir/logs/stores/stores-<date>.log`, seven days, ~2 MB rolling to
  `.1`), pruned as the app starts; the session's Share logs zip includes them under `stores/`.
- **Store log lines are redacted at the one choke point** (`StoresState.logLine`, which the engines'
  lines reach too): every URL is cut to scheme, host and a plain first path segment (a GOG secure
  link's token sits in the path, so it goes), no query, fragment or userinfo; token-like values
  (`token`, `__token__`, `f_token`, `hdnts`, access/refresh/id tokens, `code=`) and Authorization /
  Cookie headers are blanked outside URLs too. `StoreLogTest` covers a GOG secure link and an Epic
  Akamai URL. The engine's own logcat lines (tag `EpicNative` etc.) are written on the native side.
- **GOG downloads use every CDN host:** the secure link lists several hosts; only the first was
  taken (`used=1/1 servers`). All are parsed now and kept as one set, refreshed together on
  expiry; the Java pool takes them in turn. The progress bars glide to each new value over 250 ms. With the engine's `0849a7c` the
  native start takes the whole set (`cdnBases`; the engine spreads its window across the hosts) and
  its `onBytes` - every 250 ms, including files still in flight - drives the bar and the speed, so a
  large file no longer moves the bar in one jump.
- **Cloud saves device-proven (AYANEO Pocket FIT, `6a175f1`, ELDERBORN on GOG):** the launch logged
  `cloud gog 1732383191 down result=ok files=5 bytes=237499 reason=downloaded` (template found) and
  the in-game slot shows the cloud save (Catacombs: Tutorial part 1, 00:08:40).
- **Store cards a quarter smaller:** 112 dp least width (was 150) for shelf cards and grid cells; the
  grid fits as many columns as the width allows and shares the rest, so a density setting changes
  the column count; titles stay at 11 sp, art 16:9. Downloads rows unchanged.
- **Cache first, refreshed in the background:** a store's library shows from its saved copy at once
  and is refreshed at most every six hours (or from the account row's Refresh), the result merged
  so unchanged cards keep their objects - only changed cards recompose, the grid's scroll and the
  pad's focus stay. Shelves saved on an earlier run count as fresh for three hours. Sizes are kept
  per game and version in `filesDir/stores/<store>/sizes.json` (format-versioned) and looked up
  again only for a new version. An installed card shows its size on disk with a small green check
  after it.
- **Every owned card shows its size** at the right of its title strip, in the title's type: GOG's from
  its catalog; Epic's (build manifest, the files this device installs) and Amazon's (download
  manifest) looked up once a card is on screen or close, three at a time in the background, kept in
  the store's prefs (`size_<id>`). Nothing shows until it is known; a marker (installed, Resume, a
  download's percent) keeps the slot.
- **Two tabs, Store and Library** (the All count mixed owned games with whatever shelves had loaded:
  GOG Library 33 / All 120): Library's dropdown filters All (every owned game, the default, the
  tab's count) or Installed, and the tab reads the filter ("Library (33)", "Installed (1)"); the old
  All view is gone. Amazon has Library alone. Search follows the filter; the shoulders cycle Store
  and Library. The dropdown opens under the Library tab, left edges aligned, as wide as its
  entries (at least the tab, at most 260 dp) - `AnchoredMenu(compact = true)`.
- **The Stores chip row paints nothing** (maintainer: a black box behind the chips): it sits over the
  page's own background; the content scrolling under it is clipped at its edge and faded there by
  the content's alpha once scrolled, never by a colour.
- **Pre-PR device checks pass (AYANEO Pocket FIT):** GOG install, launch and cloud saves down and up
  (the session-end upload included); Epic with EOS sign-in; Amazon (Dread Templar); an SD-card install
  (DOOM I Enhanced) launched from `/mnt/droiddeck-sd`.
- **Downloads page, icons and one Clear** (maintainer): the row actions are round icon buttons -
  pause, resume, play, retry, and a cancel that asks with a red delete and an undo - their labels as
  descriptions. Downloads under way sit on top; finished ones below a line whose X clears them all
  (rows only; a failed download's kept files stay for its game page's Clear), installed ones as a
  compact row with Play.
- **Saves menu without explanations:** Import / Export rows (Steam, Custom, GOG and Epic alike) lose
  their descriptive subtitles; only live values remain (last sync, "…", conflict count, the save
  folder and its size). The three strings are gone from every locale.
- **One place for a store game's saves:** a GOG or Epic game's hero button reads "Cloud saves" and
  opens its saves view, which now holds the Cloud saves switch, the sync status, Upload / Download
  and the conflict actions ("No cloud saves" when the store keeps none). The switch is gone from the
  cards; GOG has no card left, Epic's keeps sign-in, offline and Resolve Epic sign-in. Steam and
  Custom games keep "Manage saves". The view is laid out in full from the first frame - the switch (focused),
  Upload and Download with "…" until the cloud check is back - and only subtitles change after; a
  conflict turns the two actions into Keep local / Keep cloud in place.
- **Background downloads:** while a download runs the service holds a Wi-Fi lock and a partial wake
  lock (timed, renewed with the progress, released when nothing runs). The notification shows a
  real progress bar and "<game> · 42% · 1.2 GB/2.5 GB · 12.3 MB/s · 3 min" (the stage while
  installing), with Pause / Resume and a two-step Cancel (Delete / Keep, ten seconds); a paused
  download leaves a notice to resume from; "<game> installed" opens it in Games, "<game> download
  failed" opens Downloads. Notifications are asked for once on Android 13+. The Android 15 dataSync
  time limit (and its `onTimeout`) applies only to apps targeting 35+: this app targets 28 and
  compiles against 34, so there is nothing to handle yet.
- **Cloud uploads do not depend on the exit hook:** a dirty mark per game launch; uploaded on the
  first of exit request, session end, or the next app start (`trigger=exit|session-end|recovery`).
- **Cloud saves, device round 1 (ELDERBORN, `b323a0b`): nothing ran** - a GOG game without arguments
  gets no launcher .bat, Steam runs its exe, and `droiddeck-store-launch` only recognised the .bat.
  It now recognises the exe too (searching the folders above it for the sidecar), and every path
  logs a result line. A first launch has no prefix yet: the download is deferred until Proton makes
  it, without holding the launch. Uploads are now safe: none before a download has set this
  device's baseline, a file changed on both sides is a conflict left for Keep cloud / Keep local,
  and the cloud copy is backed up before an upload replaces it (`CloudPlan`, tests for each rule).
- **GOG and Epic cloud saves** (ported from Bannerlator's managers, device-proven there in August):
  down before every launch where the cloud copy is newer (the compat tool waits up to 15 s), up
  after the game exits (the compat tool waits for Proton for such a game, then asks without
  waiting), newest wins per file with an MD5 check, a backup of what a download overwrites (last
  three). The save folder comes from GOG's remote-config template or Epic's CloudSaveFolder,
  expanded inside the game's prefix (`CloudSavePaths`, Bannerlator's cases as tests). A "Cloud
  saves" switch on the GOG / Epic card, and cloud rows in Manage saves (last sync, Upload,
  Download, "No cloud saves"). Not yet device-tested.
- **Epic sign-in device-proven without the overlay (AYANEO Pocket FIT, `bf81104`, Metalstorm):** EOS's
  corrective-action browser flow → `droiddeck xdg-open: https://www.epicgames.com` → Chrome
  `epicgames.com/id/authorize?user_code=…` → approved → `[eos] got logged in`, product user id
  created, the game in its hangar at 92 fps. The EOS overlay crashed the game on every overlay-on
  launch, so it is removed (user decision): no overlay switch, no download, no `OverlayPath`; a
  launch removes a pointer an earlier build wrote, and the app deletes an earlier 656 MB download
  once. The work is parked on `park/epic-eos-overlay` (at `bf81104`). Kept: the browser hand-off,
  the Epic card (sign-in, offline, Resolve Epic sign-in) and `BL_DEBUG_BROWSER`.
- **A game launched from the Games tab with no session running sat at "Launching"** (device,
  2026-10-09-04-steam, and 2026-10-08-05 before it). The session put `steam://rungameid/<id>` on
  the client's command line; the client took it seconds before its interface was up (focus went to
  769 at 04:29:07, the launch at 04:29:09) and its launch stopped at `LaunchApp waiting for user
  response to ShowInterstitials` with nothing to answer - the next launch from inside Steam went
  straight through. Not store-specific: the 2026-10-08 cold launch of another shortcut stopped the
  same way. `droiddeck-session` now holds such a URL back and hands it over through the same
  `steam-game` request an in-session launch uses, once gamescope's focused app is the interface
  (769) and four seconds have passed, or after two minutes regardless. The `rc=139` at the end of
  both sessions is the client crashing in its own shutdown, after "Shutdown", unrelated to the
  launch. The shortcuts writer keeps a compat tool the user chose (`plan_mapping` skips an entry
  that is not one it set itself), and the live add sets one only for a new shortcut.
- **Why EOS's browser flow never reached Android:** the hand-off script was never in the APK. The
  build copies only `usr/local/bin/droiddeck-*`, `steam-compatibility` and `usr/bin/**` of the overlay
  into the assets, so `usr/local/lib/droiddeck/browser/xdg-open` was skipped, the session staged
  nothing, the wrapper did not put it on the PATH, and Wine's winebrowser ran the runtime's own
  `/usr/bin/xdg-open`, which has no browser to open in a Steam session. GE-Proton 11-7's winebrowser
  (Metalstorm's tool) is Wine's own - registry `Browsers` list, then `xdg-open` by name - with no
  steam://openurl route. The script is now `usr/local/bin/droiddeck-open-url`: named as Wine's
  browser in the prefix (`"Browsers"="/usr/local/bin/droiddeck-open-url,xdg-open"`, an absolute path
  that does not depend on Wine's PATH), linked as `xdg-open` first on the game's PATH, and reading
  the session channel from `/tmp/droiddeck-browser/launch-dir` when Wine does not pass
  `BL_LAUNCH_DIR` on. Whether EOS calls ShellExecute at all in its browser flow (rather than handing
  a verification URI and code to the game to show) is what the next run's `droiddeck xdg-open:` line,
  or `BL_DEBUG_BROWSER=1` in droiddeck-env (`WINEDEBUG=+shell,+winebrowser`), will show.
- **Resolve Epic sign-in** (the Epic card): opens Epic's account page behind its sign-in in Android's
  browser, where a pending corrective action (privacy policy, EULA) is presented.
- **Epic overlay off by default** (device A/B, Metalstorm: overlay pointer set → exits while
  loading, twice; off → signs in to its Starform account and runs at 118 fps). `EpicOptions.overlay`
  defaults to false; the sidecar's `epic` block carries `"v": 2`, and an older block (the old default
  wrote `"overlay": true` unasked) reads as off - in the app and in `droiddeck-store-launch` alike.
  The 656 MB overlay is fetched only for a game that turns it on (the card's switch, or a launch of
  such a game), no longer after every library sync.
- **The card's switches are saved where both launch paths read them:** each toggle reads the
  sidecar fresh from disk, writes it and reads it back (`StoreGameSidecar.updateEpic`), and the card
  shows what was read back; the app logs `epic options <id> eos= offline= overlay=`.
- **Registry lines that a running wineserver would drop:** `droiddeck-store-launch` waits up to 8 s
  for a wineserver still holding the prefix to exit before appending to `user.reg` (it rewrites the
  file from memory when it exits, which is how `OverlayPath` vanished), reads the value back, and
  logs `overlay-set`, `overlay-off`, `overlay-not-written` or `overlay-still-set`, plus
  `server-running` when one never left. The helper's docstring is raw (no SyntaxWarning).
- **Browser hand-off diagnostics:** the game-PATH `xdg-open` logs every call as `scheme://host`
  only and accepts `steam://openurl/<address>`. On the device it was never called while EOS waited
  on its browser flow, so how EOS opens the browser under this Proton is still to be seen in the
  next log.
- **The exchange code no longer reaches the game's environment** (device: Metalstorm's Player.log
  dumped `DD_AUTH: … -AUTH_PASSWORD=<code>`). The launcher read the code into a variable the game
  inherited. It now clears the variable on the same line that starts the game - cmd has expanded it
  there already - so the code is on the command line only; the variable has a plain name. An old
  launcher is rewritten before the next Epic launch (`StoreLaunch.refreshLauncher`, from both the
  app and the Steam-launch request).
- **A store game page shows no description that is only its title again** (Metalstorm's).
- **Epic launch card** (Games tab › Launch settings, Epic games only): "Epic sign-in (EOS)" on,
  "Launch offline" off, "Epic overlay" on, stored in the game's sidecar (`"epic": {"eos", "offline",
  "overlay"}`, kept across updates) so both launch paths honour them alike - the app's
  `StoreLaunch.epicCode` and `droiddeck-store-launch` for a launch from the Steam client: no code
  when sign-in is off or offline, the overlay pointer written or removed per the switch. The card
  reads e.g. "EOS · Overlay". Bannerlator's forced ownership token (`-epicovt`) is not ported, so
  there is no switch for it.
- **EOS overlay** (device: Metalstorm's EOS grant answered `corrective_action_required`, the overlay
  was not configured, and EOS fell back to a browser flow that never finished). Epic's own overlay
  component ("EpicOnlineServicesOverlay", the mechanism from Legendary's `lfs/eos.py`, credits in
  `EpicOverlay.kt`) is downloaded once, with the Epic account's token, to
  `/root/.local/share/droiddeck/epic-overlay` in the runtime - after an Epic library sync, or at the
  first launch that finds it missing. `droiddeck-store-launch` then writes the one pointer,
  `HKCU\Software\Epic Games\EOS` `OverlayPath` = that folder through Z:, into the game's prefix
  (`STEAM_COMPAT_DATA_PATH/pfx/user.reg`, appended as DirectAudio's key is; a prefix Proton has not
  created yet gets it on the next launch). Logged as `epic overlay installed=<build> prefix=<appid>`.
  A game's web links now open on Android: Wine's browser is set to xdg-open, and on a game's PATH
  `droiddeck-open-url` hands http(s) addresses to the app, which opens them
  with Android's browser - so a device-code sign-in can be finished on the phone.
- **Epic sign-in from Steam's own Play button (device, Metalstorm "Guest Account"):** the game was
  started from the Steam client, so the app never minted its exchange code - the code was only
  written when a launch began in the app. Now the compat tool (`droiddeck-proton` and
  `droiddeck-proton-wrap`, `steam-compatibility`) runs `droiddeck-store-launch` on every real launch:
  it recognises the store launcher among Steam's arguments, and for an Epic game asks the app
  through `<session>/stores/req|resp` and waits up to 5 s; the app (`StoreLaunchRequests` →
  `StoreLaunch.epicCode`) refreshes the token, mints the code and writes it into the game's own
  folder - the code never crosses the channel or the log. No answer: the game starts with its
  offline identity. A code file older than five minutes is dropped first. Every attempt logs
  `epic launch id=<app> code=yes|no reason=<ok|signed-out|sign-in-expired|exchange-failed|timeout|…>`;
  a sign-in that ran out dims the Epic chip and shows the sign-in card. The Games tab and Stores
  still prepare a code before they launch; the compat tool's request is the one every path shares.
- **Amazon has Installed and Library only:** with no public catalog its Store tab repeated the
  library (Trending = Your library) and All equalled Library. The open-on Store choice falls back to
  Library for Amazon. Amazon's library API carries no install size, so a size shows only once a
  game has been installed (the manifest gives it then); no placeholder otherwise.
- **Shelves are titles only** (no "Just landed" / "On sale this week" lines); the chip row sits on
  the page's own ground with room under it, and what scrolls passes beneath it clipped, with a short
  fade where it meets the row.
- **Show mature content** (Setup › Stores and the cog; off by default): the storefront's shelves
  and search leave out titles the store itself rates or tags as adult; Library, Installed and owned
  games always show, and a title without rating data stays. GOG: `ratings[].ageRating` 17+ (ESRB M
  17, PEGI / USK / GOG 18) or the tags `mature`, `nsfw`, `sexual-content`, `nudity` - the catalog
  API has no server-side filter for either, so it is client-side. Epic: its public store GraphQL
  carries no age rating on an offer (no age-gating or rating field on CatalogOffer or StoreConfig;
  introspection is off), so only an adult content tag would mark one, and none of the probed
  titles have one - Epic's storefront is effectively unfiltered. Amazon's Store tab is the user's
  own games.
- **UI slow while downloading (device):** engines report per chunk or file, and each report
  published a new queue that the whole front end read (the rail badge was computed from it in the
  root composition). Now progress-only changes reach the UI at most four times a second (stage and
  state changes at once), each row and card reads only its own entry, the list replaces itself only
  when rows come, go or change state, the badge count changes only when it changes, and the
  notification redraws at most once a second. Download threads run at background priority (the
  queue's runner and every Java pool), the tier leaves two cores free (`processWorkers` capped at
  cores - 2, at least 2), and the stores log is written in half-second batches. The native engine's
  own threads need the same priority on its side.
- **Installed tab empty under "Installed (2)" (device):** the count came from the installs on disk,
  the grid from the library filtered by id. Both now come from `installedCards`: each install joined
  to its library item by id (or title), else a card from the sidecar's title and art.
- **GOG rows (device, DOOM + DOOM II):** bytes read "0 B" instead of blank; GOG and Amazon report
  their size on disk from the manifest; their Install stage counts its finishing steps (record and
  launcher, art, Steam) instead of sitting empty - GOG writes files in place while downloading, so
  there is no file move to count. A stage a store skips reads complete once a later one starts.
  The tier's window does reach GOG's engine (Max = 96 as its ceiling); the plan line now says so
  (`gog: plan … window=96 … tier=max`). The per-host spread is the engine's and was not changed.
- **Stages named for what they do; both sizes; Cancel deletes.** Epic's check of the files already
  there is part of Manifest ("Checking 120/515"), never a 100% Verify before anything is fetched.
  Install has its own write rate and ETA from the bytes the assembler writes, and files n/N. The
  row shows the download (compressed) as "1.6 GB / 2.5 GB" and the size on disk beside the
  location; Epic remembers the disk size for the card and page. Cancel asks twice ("Delete
  download?") and removes everything the install wrote - folder, sidecar, `.chunks`, scratch,
  `.gog_chunks` - except a finished install being repaired, which keeps itself; the row says
  Cancelled for three seconds, then goes, and the game reads Install. Pause keeps everything. A
  failed row reads "Failed · 2.1 GB kept" (the reason is in the log) with Resume and Clear (Clear
  deletes like Cancel); the game page offers Resume install and Clear for an unfinished install.
  Library sync is a thin bar and "25/55", no sentence.
- **Per-stage progress** (device: Epic DOOMBLADE, 13 GB, ~3 min of assembly with a still bar). The
  entry carries the active stage's own count (amount + files) and which stages are passed; the
  Downloads row draws one segment per stage (Manifest, Download, Verify, Install), and its line
  reads e.g. "Installing 42% · 120/515 files" (speed and ETA only during Download). Fed by Epic's
  verify pass, its native and fallback assembly, and GOG's chunk-cache removal; Amazon writes while
  it downloads and has no long stage after it. Leaving Download clears the measured speed. Cards
  and the game page follow the active stage's percent.
- **The art is the card** (maintainer feedback): a store card is its art edge to edge, sharp above
  and, under the one line, a blurred and darkened copy of the same picture (blur on API 31+, a
  stronger scrim below it). A Downloads row is the game's wide art cropped to the row with a dark
  wash from the left, the copy in white there and the actions on the right; no thumbnail.
- **Install dialog:** one plain line "Install <game>:", then one row per place (icon, name, free
  space), Cancel small in the title line; 440 dp wide.
- **No flavor text** (standing rule from the maintainer): Setup › Stores and the cog popup keep
  only control labels; the download-controls caption, the Downloads footnote, the sign-in blurbs
  and footnote, the engine note and the Install-to caption are gone; empty states read "No games",
  "Nothing installed", "No downloads", "No catalog". A Custom game's hero has no eyebrow (its chip
  says it).
- **Logos reverted** (user decision, no third-party marks): back to the text source chips and the
  coloured dots.
- **Device-proven on the AYANEO Pocket FIT, 2026-10-08 (GOG):** sign-in, the library (33 owned),
  DOOM I Enhanced installed through the GOG engine to the SD Games root, shown in Games with the
  GOG chip and real art, registered as a non-Steam game in the Steam client (grid art present), and
  launched from the Games tab through the shortcut and droiddeck-proton to the main menu at 143 fps.
- Status: CI green (build, JVM and python tests). Epic and Amazon are still unproven on device -
  their sign-in pages, an install, and for Epic the launcher .bat with the exchange code are the
  next things to prove on hardware.

## 2026-10-08 - `feat/directaudio-from-release`: DirectAudio from its own release, picked by interface, and the client gets the real engine

Three things were wrong with audio at once, and one tidy-up @xXJSONDeruloXx asked for.

- **Proton-CachyOS games never got DirectAudio.** Both CachyOS Protons we offer carry Wine's newer
  *system-thread* audio interface (`dlls/mmdevapi/unixlib.h` differs from Valve's); the one driver
  we shipped was built for the classic table, and the wrapper only asked "is this Wine 11?", which
  CachyOS passes. Now two driver sets ship (`linux-wine11` for Valve Proton 11 / Experimental /
  GE-Proton, `linux-wine11-systhread` for Proton-CachyOS) and `steam-compatibility` picks by what
  the Proton's own `winepulse.so` imports (`PsCreateSystemThread` only on the system-thread table) -
  never by the version string. No set for the interface, or no `winepulse.so`: DirectAudio stays
  off and Proton's audio runs. Confirmed on the FIT: Experimental 20260924 and GE-Proton11-7
  classic, cachyos-11.0-20261005-slr system-thread.
- **CachyOS also overrode the registry** (device log: `init_driver Loading driver list
  L"pulse,alsa"`): its launcher sets `WINE_AUDIO_DRIVER=pulse,alsa` (setdefault) and its mmdevapi
  reads that variable before the registry; Valve's and GE's mmdevapi ignore it. The wrapper now
  exports `WINE_AUDIO_DRIVER=directaudio,pulse` as well as writing the registry. On CachyOS that
  also means DirectAudio engages on a game's first launch, no registry value needed.
- **Some games ran on PulseAudio while the log said DirectAudio.** Two prefixes on the FIT had the
  driver linked into `system32` but a pristine `user.reg` with no `Audio` value: Steam runs the
  compat tool several times per launch, and a wineserver from an earlier verb still holding the
  registry saves its copy over the line we appended. The wrapper now says so when a server is
  live. Two stronger fixes were tried on the device and withdrawn the same day: writing the value
  through the live server with a bare `wine reg add`, and having Proton create a missing prefix
  first (`proton run wineboot`) - the second Proton run inside Steam's launch sequence got the
  launch aborted (`KeyboardInterrupt` in Proton, games bounced back to their page, a half-made
  prefix). On Valve/GE a first launch therefore still takes DirectAudio from the second run.
- **The Steam client's own sound was "DirectAudio" in name only.** PulseAudio → sink → relay →
  AAudio is one hop more than a plain AAudio sink; the only gain was sharing the games' stream.
  The daemon runs on the Android side, so DirectAudio's engine can run *in* it:
  `module-directaudio-native-sink` (from the DirectAudio release) - the game driver's adaptive
  buffer, two-burst floor, primed ring with fades, route-change reopen, watchdog and limiter, one
  step from Android. It is now the only route: the *Steam client audio* choice is gone from the
  menu (the relay route was one hop more for no gain, and the old "Classic" sink is the automatic
  fallback). If the native sink cannot open its stream the daemon keeps running (`.nofail`) and
  `PulseAudioComponent.ensureClientSink` loads the plain AAudio sink instead, saying so in
  `audio.log`. The relay helper now starts only for games and the microphone.
- **Binaries out of tree** (@xXJSONDeruloXx): the driver sets, the relay helper and both sinks come
  from the pinned release in `tools/directaudio/release.env` (`directaudio-linux-v1.1.0`, four
  zips, sha256-checked) through `tools/directaudio/fetch.sh`, in CI and `build_local.sh`, like
  gamescope. Deleted: `app/src/main/assets/directaudio/` (now generated, ignored),
  `tools/directaudio-relay/`, `tools/aaudio-sink/module-directaudio-sink.c` + `da_relay_proto.h`.
  `tools/aaudio-sink` keeps only `module-aaudio-sink` (the fallback). The release carries
  MaxsTechReview's #338 fixes, credited there.
- Audio bundle stamp bumped so an installed app re-unpacks the modules.
- **Device-proven on the FIT, four cases:** the client's sink opened at 12 ms / 4 ms bursts with
  0 underruns after 10 s (`client sink: DirectAudio (module-directaudio-native-sink)`); FEZ on
  `cachyos-11.0-20261005-slr` took the system-thread set - Proton log `Loading driver list
  L"directaudio,pulse"` → `Selecting driver L"directaudio"`, relay `hello from FEZ.exe`, 12 ms;
  FEZ on Proton Experimental 20260924 on `linux-wine11`, relay hello, 12 ms (no regression);
  Curse of the Dead Gods on CachyOS from a cold prefix - DirectAudio on its first launch, one
  early xrun and the buffer grew 12 → 16 ms. Open: a Thor (20 ms bursts). An added game (Noita,
  a Winlator-era folder never launched here before) bounced on launch with and without this
  branch's wrapper steps, before Proton wrote a log - its own issue.

## 2026-09-29 - `feat/controller-input`: the pad the way SteamOS has it

Device-tested on the AYN Thor (Katamari under Proton Experimental ARM64).

- **Why**: the client read the pad as an Xbox 360 controller, and games read the *same* node
  wearing Steam Input's virtual identity (28de:11ff), because the client's own virtual pad needs
  `/dev/uinput` and never appeared. Steam Input's layouts therefore never reached a game, and the
  QAM was a timed Guide+A chord. InputPlumber itself cannot run in the sandbox (root, uinput, uhid,
  udev, D-Bus), so what it provides is emulated in the interposer instead.
- **`/dev/uinput` stand-in** (`FAKE_EVDEV_UINPUT=1`, libfakeinput): a gamepad the client makes
  becomes `/dev/input/event16+`, backed by a ring of the app's own format, with the client's name,
  ids and bits; its writes are that ring's events. Other devices (virtual keyboard and mouse) are
  accepted and dropped. `inputudev.c` describes these nodes to Wine's HID bus from a `.uevent` file
  beside each one, and while one exists describes the app's pads as the Xbox 360 pad they are, so
  Wine reads only Steam Input's output. Verified: the client makes "Microsoft X-Box 360 pad 0",
  re-makes it at game launch, and the game binds it (`Controller 0 uses xinput : true`).
- **Steam Deck controller** (`FAKE_EVDEV_DECK=1`, Steam sessions): `/dev/hidraw16`, found through a
  sysfs tree `SteamDeckPad.kt` binds in (usb_device → interface 2 → hid 28de:1205 → hidraw), streams
  the Deck's 64-byte state report every 4 ms from ring 0 and answers feature reports as InputPlumber
  does. systemd 261's libudev refuses a syspath not on sysfs, so `fstatfs`/`statfs` report sysfs
  for that tree. The app's evdev nodes are withdrawn while it is on, and only the `steam` process
  sees the Deck. QAM is a real button (snapshot bit 11). Verified: the client lists a Steam Deck
  Controller (V1 HID protocol) and runs its handshake (0x83, 0xAE, 0x81/0x87 lizard off, 0x8F).
- **Setting**: Steam page → Touch & controls → **Controller**: *Steam Deck controller* (default) or
  *Xbox 360 controller* (the pad of earlier versions, QAM by Guide+A; games still get Steam Input's
  virtual pad). Debug switches: `droiddeck-no-uinput` (back to the 28de:11ff disguise),
  `droiddeck-no-deck-pad` (forces the Xbox 360 pad).
- **Gyro**: `PadMotion.kt` feeds the handheld's own gyro and accelerometer (4 ms sampling, while
  the session is on screen) into an IMU block after ring 0's events, turned to the screen and then
  to the Deck's axes and units; the Deck report carries them. Verified: Steam's Gyro Calibration
  page moves with the Thor (sh5001 IMU); at rest the accelerometer reads 1 g.
- **Deck grips + trackpads on the second screen** (`DeckControlsPanel.kt`, offered while the pad
  is a Deck controller): tabs for the four grips (2x2), either trackpad with a click bar, and both
  trackpads over the grips in a row; a second finger on a trackpad clicks it. True black for OLED.
  Grips, pad positions, touch, click and pressure go through the same block after ring 0's events
  as the gyro (`DeckControls.kt`) into the Deck report.
- **Not yet**: a Bluetooth pad's own IMU (DualSense); the
  game's rumble on the virtual pad goes straight to the vibrator rather than back through the client.
- **Prior art checked**: WinNative and Bannerlator never emulated uinput or hidraw; Bannerlator's
  `-steamdeck` mode stopped at "the virtual pad never arrives", which is this missing uinput.

## 2026-09-25 - main `8cdedbe`: melonDS out of the box, touch in Big Picture, log privacy, Decky

State: **main = `8cdedbe`** (PR #22 merge). Device-tested on the AYANEO Pocket FIT (repacked test builds)
unless marked. gamescope now staged from release **`gamescope-3.16.29-p3`**.

- **PR #18 → `9369dc3`** (`feat/melonds-preset`) - melonDS and the session:
  - **melonDS preset** (`bannerlator-pad-defaults`): the top screen as big as the panel allows with the bottom
    screen alone beside it, centred (Horizontal + Emphasize top; Hybrid repeats the top screen), sharp pixels;
    full screen from the front end (`-f`) at the panel's own resolution; the guide button toggles full
    screen, leaving to a 1x-wide 16:9 window whose menus gamescope stretches to a usable size; DS/DSi BIOS,
    firmware and NAND found in `ROMs/nds/{bios,firmware}` (BIOS by checksum). Existing installs moved once.
  - **Desktop**: `QT_QPA_PLATFORM="wayland;xcb"` (the melonDS AppImage ships only xcb and exited at once);
    a labwc window rule opens melonDS at 800x600, centred.
  - **HUD** follows the game window, not gamescope's 1x1 cursor or Qt menus (read 0 fps at 60).
  - **Cursor**: hides on pad / on-screen-control input (Bannerlator's 1.2 s rule), and shows the program's own
    shape - labwc's resize arrows, I-beams, a hide (Bannerlator's `wl_pointer.set_cursor` port; new here:
    labwc's GPU cursor is a UBWC dma-buf, read back through `vkp_image_readback`).
  - **Steam desktop switching**: "Steam Desktop UI" opens the desktop with Steam's desktop client in it (under
    gamescope the client picks Big Picture regardless); the drawer's **DESKTOP** button does it from Big
    Picture; relaunches restart the singleTop activity in place (`startActivity` from it was swallowed).
    Big Picture's own Power → Switch to Desktop still hangs: it waits on SteamOS Manager before its
    `steamos-session-select` fallback (stub included; a session bus did not help - tried and reverted).
- **PR #19 → `ce0da01`** (`fix/log-privacy`) - no credentials or identities in session logs: the device's
  public addresses (the client's IPv6 check logs "external address" ~20x a session), `network.txt` addresses
  as their kind and the MAC masked, every Steam AccountName/PersonaName (plain account names leaked in the
  UI log's login lines), and every text file scrubbed at session end and in the share-logs zip. Verified:
  0 hits across 83 session folders.
- **PR #16 → `5270574`** (Decky Loader, Kurt) - merged with the Steam CEF debugging marker tied to the
  supervisor toggle (it was created at install; on Android any app can reach 127.0.0.1:8080 - proven from
  another uid). Decky's own updater still points at the official project (no ARM64 build there).
- **PR #20 → `036317f`** (Kurt) - local builds carry the CI payload (NDK proot, patched gamescope).
- **PR #22 → `8cdedbe`** (`feat/steam-touch`) - touch in Big Picture: gamescope `0110` binds `wl_touch` in the
  nested backend and feeds wlserver's touch path (Big Picture sets Passthrough: tap and swipe rows as on a
  Deck); `0111` makes the nested pointer warp in Passthrough (touchpad mode: hover and tap-to-click work);
  the compositor's pointer fallback no longer jams on a lost finger-up.
- **In design, not built**: the drawer's page dots as QAM-style icons (Display / Controls / Settings), the
  selected one "stepping forward" in a line; mock `droiddeck-drawer-tabs-preview.html`.

## 2026-09-24 (afternoon) - main `94f9935`: PS1, controller focus, own signing key, CI hardening

State: **main = `94f9935`** (PR #41 merge), main build run 36036219740 - the first one signed with
DroidDeck's own key (`droiddeck-signed-standard`). No tag, no release since 0.1.6. Device-tested on
the AYANEO Pocket FIT unless marked. **main is now protected** (below).

- **PR #34 → `63d7051`, PR #38 → `c7998a8`** (`feat/duckstation`, `feat/duckstation-play`) - PS1 and the front end:
  - **DuckStation like ARMSX2**: Player 1 on the controller, BIOS from `ROMs/psx/bios`, `psx` as the game
    list, Vulkan, guide → pause menu, `-batch -bigpicture -fullscreen` from the rail. Crash Bandicoot and
    Tekken 3 at 60 fps from the desktop and from the front end.
  - **One tap to play**: DuckStation's and ARMSX2's first-time setup is answered for the user
    (`SetupWizardIncomplete = false`); ARMSX2 names its BIOS dump itself (`[Filenames] BIOS`, a US dump
    first, re-checked before every launch) - PCSX2 needs a named file, DuckStation finds one in its folder.
    DuckStation no longer warns it cannot inhibit a screensaver (no `org.freedesktop.ScreenSaver` here).
  - **One tile per disc**: a `.cue`/`.gdi`/`.m3u` hides the files it names (Tekken 3 showed 4×, one per
    `(Track N).bin`); `bios`/`firmware` folders are skipped (the BIOS showed as a game).
  - **Controller focus**: the rail item is outlined on open (an app starts in touch mode, where clickables
    refuse focus - the app now asks for keyboard mode first); Right enters a page on its main button,
    Left from the page's edge returns to the rail, Right again returns to the tile or button last used
    (explicit tracking - `FocusRequester.saveFocusedChild` restored into the grid, not the tile); a page
    opened from a tile takes focus on its main button (focus used to vanish with the tile and the next
    press landed on Steam); Down from a page's buttons goes to the first tile.
- **PR #41 → `94f9935`** (`feat/release-signing`) - signing, file access, hardening before going public:
  - **Own signing key** (RSA 4096, made on device by `tools/release/make-release-key.sh`, digest
    `b241ea7d…5d3f0` in `keystore/release-signer.sha256`). Every build before it - 0.1.6 included - was
    signed with the public AOSP testkey, so anyone could sign an "update" that installs over DroidDeck.
  - **Hand-over** (APK Signature Scheme v3 rotation): v1/v2 by the testkey, v3 by our key with a lineage
    testkey → ours, testkey rollback off, `--rotation-min-sdk-version 28`. Proven on the FIT with a throwaway
    key and package: a 0.1.6-style install updates and keeps its data; an apk signed with the testkey alone
    is refused; so is one with an attacker's own lineage from the testkey; an apk signed with our key alone
    (what MT Manager / APK Tool M make) installs over a hand-over install and fresh, not over a testkey one.
  - **CI**: `sign.yml` (reusable, a matrix job per package) runs after every non-pull-request build
    (environment `signing-main` on main, `signing-branch` elsewhere; the key is an environment secret).
    `release.yml` (dispatch, version) signs the exact Build APK artifact of a main commit as four packages:
    `com.droiddeck.launcher`, `com.tencent.ig`, `com.antutu.benchmark.full`, `com.ludashi.benchmark`
    (`tools/release/variants.txt`; the manifest rename also covers `.documents` and the `.home` task affinity).
  - **Documents provider** (`files/AppFilesProvider.kt`): the app's data folder in Android's file picker
    ("Open from" → DroidDeck, per-package title), confined to the data folder (a `../` id is refused, links
    out of it are hidden), and deleting never follows a link.
  - **Hardening**: pull requests build with `pull_request`, not `pull_request_target` (a pull request could
    write the caches main's signed builds restore); a build fails if a private key file is committed;
    `.gitignore` covers key files; **main is protected** - pull request + passing `build` + one approval,
    no force-push or deletion, the owner can override for his own pull requests. History scanned before
    going public: no secrets.
  - Max and Kurt get the key (jks, p12, pk8 + x509.pem, lineage) with a password and three plain guides
    (MT Manager / APK Tool M signing, the hand-over, how pull requests / test builds / releases work).
- **Also merged today** (Kurt): #20 Back shortcuts (session menu, Steam QAM), #24 build identity on the
  launcher, #33 per-launch Android display choice, #35 CI build-cleanliness snapshot, #37 the split-D logo,
  #40 faster held-stick navigation in the session drawer; (The412Banner) #32 stretch games to fill the
  screen, Quake-engine titles windowed at the session's size.

## 2026-09-24 - main `08a6769`: GPU desktop, emulators like PS3, ARMSX2, game art

State: **main = `08a6769`** (PR #29 merge), main build run 35983098806, staged as `Download/DroidDeck-main-08a6769(-Genshin).apk`. No tag, no release since 0.1.6. **Device-tested on the AYANEO Pocket FIT** (Adreno 750) unless marked.

- **PR #25 → `f6f7470`** (`feat/desktop-gpu`) - the desktop on the GPU:
  - **Patched wlroots** (`tools/wlroots`, release `wlroots-0.20.2-p1`, staged at `/usr/local/lib/droiddeck-wlroots`): a Vulkan DMA-BUF allocator for the KGSL stand-in node, which is not a DRM device - stock wlroots failed at `drmModeCreateLease` → "unable to create allocator"; linux-dmabuf skips its GEM import check on such a node. labwc's **vulkan** and **gles2** renderers start; **vulkan is the default**.
  - Compositor: a toplevel's **initial commit is answered with a configure** (labwc on vulkan waited for ever - wlroots moves its toplevel to a private queue after that commit); **`xdg_positioner` / `xdg_popup` / `wl_output` requests no longer abort** the app (NULL implementations; a gamescope beside labwc took the app down); without a Wine desktop a new window takes the keys.
  - Fallback: labwc that exits or does not answer its socket within 20 s → pixman, remembered per renderer + wlroots md5 + driver (`~/.droiddeck-renderer-failed`, cleared by choosing a renderer). On pixman, Game/Emulator menu entries run through `droiddeck-gpu` (their own gamescope on wayland-0).
  - Emulators: the pad is a plain Xbox 360 controller outside Steam (SDL ignores Steam's virtual gamepad outside Steam); `bannerlator-pad-defaults` seeds Player 1 per emulator (source-verified formats) and never overwrites a user's profile; big-core pinning (`program_cores`, game cores when chosen); rail programs use the desktop's Linux driver (one shader cache); RPCS3 moved once to `Async Recompiler (multi-threaded)` (no 6650-variant interpreter precompile each boot).
  - **PC keyboard** (drawer: Keyboard [Hardware] [Android]): Esc, F1-F12, editing block, modifiers (sticky), arrows; real evdev key presses; 47% of the screen.
  - Proven: Vulkan and gles2 desktops, Firefox on Vulkan, RPCS3 God of War II HD in a window and from the rail (pad bound, no precompile, cores 2-7), pixman fallback path, Steam session.
- **PR #26 → `6980428`** (`feat/armsx2`) - **PS2 is ARMSX2** (PCSX2 fork with ARM64 recompilers, GPL-3.0). Upstream PCSX2 only has `pcsx2/arm64/RecStubs.cpp`: NFS Underground 2 ran at 17 fps with one core at 100%; ARMSX2 runs it at **59.9 fps / 100%**, all recompilers + fastmem on under proot. PCSX2 left the front end and the catalog.
- **PR #29 → `08a6769`** (`feat/armsx2-polish`):
  - ARMSX2 from the rail: `-batch -bigpicture -fullscreen`; guide → pause menu → Close Game returns to the app. First run: PCSX2 settings + memory cards copied once; a new player gets ARMSX2's setup with the BIOS folder in `ROMs/ps2/bios` (or `ps2/firmware`), `ps2` as game list, Vulkan, controller on Player 1. Users bring their own BIOS and games.
  - Catalog `pages` - a file per memory page size; ARMSX2 4K + 16K.
  - On-screen controls: Auto for rail games and Steam when no controller, off on the desktop; setting in the drawer everywhere; drawer headed by the emulator for rail games.
  - Front end: Back/B steps back a level (emulator → Desktop, game → emulator) instead of leaving the app; games found up to 3 folders deep (`ps2/games/<game>/`); **box art** (libretro-thumbnails by name exact/loose, xlenore/ps2-covers by PS2 serial, GameTDB by GC/Wii ID, PS3 discs' own `ICON0.PNG`), kept in `files/covers`; wide art shown whole over a blurred copy; titles without dump tags.
- **winlator-contents** (the catalog): ARMSX2 `nightly-20260923-92aa2174da` mirrored (`armsx2.AppImage` 4K, `armsx2-16k.AppImage`), PCSX2 dropped from `desktop.json` (PRs #12, #13); the mirror workflow lists only the file being added (a re-run re-fetches and clobbers every listed file, and DuckStation's upstream link moves).
- **Not yet device-played**: DuckStation, Dolphin, Cemu, melonDS, PPSSPP, RetroArch (profiles + covers written from their sources). Known gap: a keyboard's Esc does not open ARMSX2's pause menu in a rail game (guide does).

## 2026-09-23 (evening) - renamed SteamDeck → DroidDeck

- **App name, app ID and code package**: DroidDeck, `com.droiddeck.launcher` (was `com.steamdeck.launcher`), sources under `app/src/main/java/com/droiddeck/launcher`, the 68 JNI functions renamed with them. A new app ID means DroidDeck installs **beside** SteamDeck, not over it: the runtime, the Steam sign-in and the settings start fresh.
- **Downloads**: session logs go to `Download/DroidDeck/`; the switch files are `droiddeck-env`, `-osc`, `-driver`, `-tu-debug`, `-no-pad`, `-pad-log`, `-no-hud`, `-wlr-renderer`. The old `steamdeck-*` names are not read.
- **Inside the runtime**: `droiddeck-desktop`, Firefox's `droiddeck.js`, `~/.droiddeck-desktop-debug`, `~/.cache/droiddeck`, the shortcuts tag `droiddeck-app`, `X-DroidDeck-AppId`, the `droiddeck-rumble` socket. "Desktop installed" now tests for `usr/bin/labwc` (it tested for the launcher, which the app stages itself).
- **Kept on purpose** - Valve's Steam Deck: the `-steamdeck` flag, `steamdeck_publicbeta`/`steamdeck_stable`, "Steam Deck mode", `steamdeck-packages.steamos.cloud`. And names inside the hosted desktop package: `steamdeck.png`, `steamdeck-steam(.desktop)`, `steamdeck-bigpicture.desktop` (renaming them means re-hosting it).
- CI artifact `droiddeck-apk`; a stray committed `__pycache__/*.pyc` removed.

## 2026-09-23 (afternoon) - main `90c7574`: first-run onboarding, Desktop & apps page, 4:3 display

State: **main = `90c7574`** (PR #14 merge), main build run 35895701784. No tag, no release; next version is still open. **Nothing below is device-tested** except the audio default (Kurt, AYN Thor).

- **PR #11 → `f79dc2d`** (`fix/directaudio-default`): the Steam client's audio is back on the 0.1.5 classic sink by default (`module-aaudio-classic-sink.so`, byte-identical to 0.1.5's arm64 module); DirectAudio is a choice in the Steam cog ("Steam client audio", pref `clientDirectAudio`, default off). Games keep DirectAudio on by default; mic on by default, RECORD_AUDIO asked once. Old armhf sink out of the bundle; the bundle re-unpacks when its contents change (stamp = BUNDLE_STAMP + CRC32 of pulseaudio.tzst). Relay logs a heartbeat line; Setup › Session logs › Share latest logs. Kurt on the Thor: classic "works perfectly".
- **PR #12 → `0ddb0be`** (`feat/play-installs-runtime`):
  - `586c9c2` Play and Desktop UI are enabled with no runtime; the session's loading screen downloads, checks and unpacks it ("downloading the Linux runtime · 332 of 791 MB"), then starts the session. A failure ends on the loading screen with the reason.
  - `f437957` Desktop does the same: the runtime if missing, then the `desktop` package from `desktop.json` (426 MB), then LXQt. Play, Desktop UI and Desktop all give the non-Adreno warning before any download (`MainActivity.startSession`).
  - `cbd3bc9` Desktop & apps is a page in the pane (pageKey `apps`), not a dialog: a rail-style dropdown per tier with an installed/total pill (Native ARM64 open), per-package Install/Remove, and the installing package's step line and bar in its own row. Leaving the page does not stop an install.
- **PR #14 → `90c7574`** (`fix/exact-panel-shape`), from a user report on a 4:3 handheld (RP Nova) - "the panel's shape" still gave 16:9:
  - Cause: `SessionActivity` sized the display at `maxOf(panel aspect, 16:9)` (the foldable guard), so on a 4:3 panel both choices were 16:9 with bars.
  - `9c47095` Shape › **Exactly this panel (4:3, 3:2…)** - the panel's own aspect, no floor. The other two unchanged; foldables still default to 16:9. In the cog and the in-session menu (`SessionPrefs.shapeChoices`).
  - `ad2f9f8` Resolution › **Custom…** per mode (`customRes.<mode>`, 320×240-3840×2160, evened) with 4:3 / 16:10 / 16:9 presets. It replaces the cap and the shape (Shape greys out); picking a cap clears it; the device report lists it. Not in the in-session menu.
  - Note: the branch was cut from `cbd3bc9`, not `0ddb0be` - same tree.
- Staged: `SteamDeck-play-installs-runtime-586c9c2.apk` (`21460acd…`), `…-f437957.apk` (`174e74a6…`), `SteamDeck-apps-page-cbd3bc9.apk` (`c2cfbebe…`), `SteamDeck-custom-res-ad2f9f8.apk` (`d02ee5ce…`, = main's content). HTML preview of the onboarding flow: `/sdcard/Download/SteamDeck-onboarding-preview.html`.
- CI note: a branch push does not build (`build.yml` = main + PRs); dispatch it with `gh workflow run build.yml --ref <branch>`. A newer dispatch on the same branch cancels the older run.
- Open: Kurt's PR #13 "Add Steam second-screen controls" (`steamdeck-second-screen`) - not reviewed; now needs to merge onto `90c7574`.
- **Next:** (1) fresh-install pass: uninstall, install, press Desktop first (runtime + desktop, ~1.2 GB), then Play with no download; (2) the Desktop & apps page on device (dropdowns, in-row bar, Back mid-install); (3) the Nova reporter tests Exactly this panel / Custom 960×720; (4) PR #13.

## 2026-09-23 - branch `feat/armada-and-lineage`

- Steam Deck mode: `-steamdeck` only; SteamOS helper stubs staged from the apk under `/usr/bin` and `/usr/bin/steamos-polkit-helpers` (the missing `steamos-update` there was the "Update Error" dialog; `jupiter-dock-updater --check` answers 7 so no dock firmware row); Deck mode defaults the `steamdeck_publicbeta` channel (on `publicbeta` every start reinstalled the client and lost the launch URL); client branch row in the Steam cog.
- QAM battery: `session/BatteryComponent.kt` writes BAT0/BAT1 from BatteryManager, bound over `/sys/class/power_supply`. Cloud saves checked on device: in sync, nothing was broken.
- Added games: any number of Games folders, each bound under `/root/Games`; one shortcut per subfolder in the client's `shortcuts.vdf` under the ARM64 Proton; exe choice per game; art from the folder, else Steam's store (capsule, header, hero, logo) copied into the client's grid.
- gamescope: the runtime's 3.16.29 rebuilt in CI (`tools/gamescope`, release `gamescope-3.16.29-p1`) with Armada's ARM64 client fixes, a realtime-queue switch and the gamepad cursor fix; staged over `/usr/local/bin`. Game windows forced fullscreen in Steam mode (a game came back in the top-left corner after the Steam menu).
- Audio: own PulseAudio sinks in `tools/aaudio-sink`, built into the bundle by CI. With DirectAudio on, the client's sound goes through the relay's shared ring (`module-directaudio-sink`, sink named DirectAudio); off, `module-aaudio-sink`. Inside proot AAudio only gave 20 ms bursts, the relay gets 4 ms.
- Winlator code rewritten or removed (SessionPart, HostEnvironment, HostProcess, PadState, cpp/framegen); rumble now served by `session/RumbleComponent.kt`.
- Front end shelf laid out whole so the d-pad reaches every tile.
- Hosted runtime and desktop packages untouched.

##  0.1.5 RELEASED 2026-09-23 03:17 - known-good point

- **Tag `0.1.5` = `d2f4a84`** (bump commit, versionCode 6), notes `docs/releases/0.1.5.md` on main after it. Private release [0.1.5](https://github.com/The412Banner/SteamDeck/releases/tag/0.1.5) and public [`SteamDeck-0.1.5`](https://github.com/The412Banner/winlator-contents/releases/tag/SteamDeck-0.1.5) on winlator-contents, both Latest, asset `SteamDeck-0.1.5.apk` 21,093,959 bytes, sha256 `85b42954…`, run 35813465422. Staged as `/sdcard/Download/SteamDeck-0.1.5.apk`.
- What went in since 0.1.4 (58 commits): the motion front end, settings as pages with anchored menus, the session drawer, three themes (Paper default) and the new icon, run-as-a-game + libpci fix (117 fps Big Picture on a Fold), client-interface switches, FEX presets, 720p default, TZ, non-Adreno gate, 16 KB app libs, two on-screen sticks + bar-aware layout, folded categories at launch, Kurt's #3 (Back key) / #4 (icon) / #5 (local build helpers), pull-request CI + contributions ledger.
- Untested at release: sticks/bar layout, FEX presets, Deck mode, desktop → Steam hand-off, the `perf:` line. Rollback: `git reset --hard 8e58e8e` (0.1.4) + `SteamDeck-0.1.4.apk`.
- After the cut: the public release on winlator-contents got the private title, and that repo's README a SteamDeck section (latest release, `linuxfs.json`, `desktop.json`) - refresh both at every cut. Verified on the FIT's client binary: there is no `-steampal` flag; the Deck flags are `-steamdeck` / `-steamos3` (Deck mode on the Performance page), `-gamepadui`, `-steamos`.
- **Next, in order:** (1) device pass of 0.1.5 - sticks in a game and the bar layout on the Fold, the `perf:` line and 720p in a session log, a FEX preset, the desktop → Steam hand-off; (2) decide the stick click gesture (double tap or long press); (3) Kurt's draft PR #1 (Decky installer) builds when he marks it ready - it compiles against main, minus two stray `.kotlin` files; (4) rebuild the six PulseAudio prebuilts 16 KB-aligned; (5) optional FIFO/mailbox switch on the compositor's Vulkan present path; (6) the rename away from Valve marks is still open (icon is now the "A").

## 2026-09-22 (late) - motion front end + run-as-a-game, branch `feat/frontend-motion` (unmerged)

- Front end rebuilt around a motion system (`ui/FrontEndScreen.kt`, same state/actions API): the rail's selection is one pill that springs between rows; sub-lists unfold and stagger their children; a page change sinks out and cascades in over a blurred wash of the selection's art; tiles lift/ring/shine on focus and pop a play badge; the launch button sweeps a sheen and squashes on press; the session activity rises over the front end (`res/anim/session_*`). Durations follow the system animator scale.
- The session now runs as a game: manifest `appCategory=game` + `game_mode_config` (Performance mode welcome; the OS FPS cap and downscaling refused), sustained performance mode, the panel's fastest mode at its size, GameManager game state, and an ADPF hint session over the compositor thread fed with each presented frame's interval (`session/PerfMode.kt`, `session/PerfHints.kt`, `nativeCompositorTid`). One `perf:` line in the session log says what took on the device.
- App libraries link 16 KB-aligned. Still 4 KB-only (prebuilt, need a rebuild): libpulse, libpulseaudio, libpulsecommon-13.0, libpulsecore-13.0, libsndfile, libltdl.
- The session's display caps at 720p by default now, client and desktop alike (`SessionPrefs.defaultResolutionCap`); a cap the user chose still wins. The cog's list marks the default per mode.
- Builds: r1 `7ddfb57` (motion only), r2 `546c063` (+ run-as-a-game), r3 `d1ced87` (+ client 720p), r4 `fb2a3d4` (+ desktop 720p) - all CI-green, none device-tested. Staged as `SteamDeck-motion-r1..r4.apk`; r4 has everything.
- r5 `1ba803b` + TZ in the guest; r6 `39272cf` Setup folds, "Linux desktop environment", help link above the grid (r4 seen running on the FIT); r7 `af3f377` settings without a pop-up: the cog and Performance are pages in the pane with anchored menus under each value, frame generation / logs / offline open in place on the rail. **r7 merged fast-forward to `main` (`af3f377`) and main's auto-build staged as `SteamDeck-r52.apk`; no tag, no release.**
- r8 `90ff23a` (branch only, unmerged): the session drawer in the front end's dress with the same rows and menus (Now / Next session / leave), FEXCore presets carried over from Bannerlator (`core/FexPreset`, Steam settings page + drawer, FEX_* into the Steam session's environment; default = FEX defaults as before), file manager and picker locked to landscape.
- Collaborators: `maxjivi05` (push) and `xXJSONDeruloXx` (push, invited 2026-09-22) on the private repo.
- CI for pull requests (2026-09-23 early): `Build APK` runs on `pull_request_target` against main (a conflicting pull request never fires `pull_request`), checks the pull request out at its head with full history, merges main itself, and leaves one comment kept current: mergeable + built (apk on the run), or the conflicting files and hunks, or the compiler's errors from the kept Gradle log. Drafts wait. Fork pull requests may run without approval (repo setting). Proven with a throwaway pull request (#2, closed) both ways. `Contributions ledger` rewrites the README's contributors table on open/merge/close via `.github/scripts/contributions.sh`, committed to main by the Actions bot.

##  Checkpoint 2026-09-22 22:xx - known-good point for the front end

- **`main` @ `36cde7d`**, APK `SteamDeck-r51.apk` staged (sha `adb5acf5…`, run 35761437514) =
  frontend-r6 + docs. Same versionCode 5 / 0.1.4 inside; **0.1.5 not cut yet**.
- **Proven on the Pocket FIT this evening:** the front end renders and navigates (rail, art,
  square emulator icons, focus outline); RPCS3 lists Tomb Raider (ISO in a subfolder) and God of
  War II HD (its HDD);  God of War II HD boots the game under gamescope at ~40 fps
  (`session-20260922-133044`: `run: rpcs3.AppImage --no-gui …NPUA80491/USRDIR/EBOOT.BIN`, 399
  frames on screen in 10 s).
- **Not yet proven:** desktop→Steam hand-off after the three fixes (r47–r50);  FlatOut from the
  rail (Steam session with rungameid); Running tile; volume keys; automatic SD game storage
  (Install drive drop-down); Steam desktop-UI session; Tomb Raider ISO boot.
- **Open decision:** rename the app away from Valve's marks before it spreads (shortlist offered:
  Linuxlator / Pocketscope / Portascope); rename = label, icon text, `applicationId` (fresh
  install + runtime re-download), `Download/SteamDeck/` log folder, repo, release-tag pattern.
- **Rollback:** `git checkout 36cde7d` (or 0.1.4 tag `8e58e8e` for the last release), reinstall
  `SteamDeck-r51.apk` / `SteamDeck-0.1.4.apk` from Downloads.
- Loose ends: `ui/MainScreen.kt` keeps ConfirmDialog/CreditsDialog/EmulatorHelpDialog but its
  `MainScreen`/tiles are dead; worktree `~/steamdeck-frontend` still exists (branch merged).

## Current state (2026-09-22, evening)

- **`main` @ the front end merge** (`feat/frontend` fast-forwarded): the launcher main screen -
  Steam ▸ games, Desktop ▸ emulators ▸ games, Running tile, focus outline.  First emulator game
  proven from the app: God of War II HD in RPCS3 under gamescope at ~40 fps on the Pocket FIT.
- Since 0.1.4 on main, unreleased: volume keys to Android;  emulators under gamescope; ROMs chip
  + ? explainer; sysmem copy + ENOSYS hint; automatic game storage (a card = a Steam library);
  the client's games on the desktop with a `steam` shim; desktop→Steam hand-off (three causes
  fixed from FIT logs: Surface detach race, the replaced session's exit ending the new one,
  shared log folder); the front end. Next release = 0.1.5 once the hand-off is seen working.

## State at 0.1.4 (2026-09-22)

- **Latest release: 0.1.4** - private: https://github.com/The412Banner/SteamDeck/releases/tag/0.1.4
  · public: https://github.com/The412Banner/winlator-contents/releases/tag/SteamDeck-0.1.4
  `main` @ `8e58e8e`, CI run 35682844106, versionCode 5, APK sha256 `4b53ce6a…` (20,957,823 B).
- **Runtime:** `linuxfs-r9` on winlator-contents (790 MB), the only runtime release left; desktop
  packages from `steamdeck-desktop-r1`. The app rewrites its own scripts (`bannerlator-*`), the
  driver, `bannerlator-netmanager` and the DirectAudio pieces into the installed runtime at every
  launch, so a fix in those reaches an installed runtime without a re-download.
- **Shipped feature set:** the native ARM64 Steam client under gamescope on the in-app Wayland
  compositor; a LXQt-on-labwc desktop with Firefox and a shelf of emulators; Proton tools
  (GE / CachyOS) as downloads; graphics drivers importable per mode; DirectAudio + microphone;
  client/game core masks + four session switches; NetworkManager stand-in (Max's); overlay
  restored; ROMs folder + Storage bound into the session's home; Bannerlator's File Manager;
  a cog per launch mode (resolution, shape, HDR10, drivers, touch, OSC/audio, renderer); per-session
  log folders that survive a crash; leftover-process sweep; frame generation (Win-FG, LSFG).
- **Verification level:** every release is CI-green and staged to the maintainer's device. Proven
  on hardware (Pocket FIT / Fold): sign-in, store, install + launch (FlatOut 144 Hz), frame
  generation, controllers + OSC, sound, desktop + Firefox, folding mid-session, driver import, the
  log folder, the ANR fix, the network page. **Not yet proven:** everything 0.1.4 added (ROMs
  folder inside an emulator, File Manager, resolution cap, HDR10, crash sweep, orphan sweep, the
  drawer's Steam menu), DirectAudio *voice*, the core masks' effect, offline start, the soft
  keyboard, any emulator with a game, Adreno 710.
- **Open field reports (Thor Pro, 8 Gen 2):** trackpad-mode tap does not click (code sends the
  click; cause not found by reading); crash after 1–2 min in Ballionaire / Geometry Wars (no log
  survived - 0.1.4's sweep will leave `crash.log`); a Fold's 10–20 fps client menus (Chromium →
  ANGLE → Zink on an experimental A8xx Turnip); Fold crash loop (xalia ENOSYS storm - switches
  shipped, untested); FlatOut shrinking after Resume (gamescope forcing the swapchain extent).

## Release pipeline (how every version is cut)
1. Work on `main`, pushed → `Build APK` runs `assembleRelease` (AOSP test key, v1+v2+v3 re-signed
   by CI with zipalign + apksigner) → artifact `steamdeck-apk`. Dev builds keep the last release's
   versionCode/versionName; a release bumps both in `app/build.gradle` in its own commit.
2. Verify: `gh run view <id> --json conclusion` (never trust a run listing's first row - a
   `workflow_dispatch` run gets cancelled by concurrency in favour of the push-triggered one),
   download the artifact, sha256 it.
3. Stage: `cp` to `/sdcard/Download/SteamDeck-rN.apk` (dev) or `SteamDeck-X.Y.Z.apk` (release).
   Never `pm install` - the maintainer installs.
4. Private release `X.Y.Z` targeting the **full 40-char sha** (a short sha is refused), notes from
   the draft in the session scratchpad, `--latest`. Public release `SteamDeck-X.Y.Z` on
   winlator-contents with the README-style notes; one public release per version.
5. README ledger, this log, memory.

## Timeline
- **2026-09-19 - 0.1 groundwork.** Lifted out of Bannerlator's gamescope runtime on the user's
  ask: one screen, one button. r2 reached Big Picture sign-in on the Pocket FIT. Package renamed to
  `com.steamdeck.launcher` (r4), foreground service, test key, on-screen controls.
- **2026-09-20 - 0.1.** The Linux Steam client and a desktop; runtime r6→r9 in a day (Proton
  self-selection, proot shipped, refresh rate, video, first run reaches a game). Public explainer
  page for non-Linux users.
- **2026-09-21 - 0.1.1.** Catch-up with Bannerlator: driver selection (two lists, per mode),
  overlay restored + GameHub force-stop, DirectAudio + microphone, core masks, four session
  switches as toggles, per-session log folders with credentials scrubbed, Adreno 710 path via
  Banners-Turnip r3.
- **2026-09-22 - 0.1.2.** The teardown ANR (log collection on the main thread) fixed; two
  compositor log lines (frame-size change, window rename); README ledger.
- **2026-09-22 - 0.1.3.** Max's NetworkManager stand-in ported (the client's network page); shim
  audit vs WinNative = zero functional drift.
- **2026-09-22 (later) - after 0.1.4, on main.** Volume keys;  emulators under gamescope (the
  desktop cannot: labwc on pixman offers no dma-buf); ROMs chip; sysmem warning + ENOSYS hint;
  automatic game storage; the client's games on the desktop (`steam` shim hands off to a
  gamescope session); the hand-off's three faults found in FIT logs and fixed; the front end
  merged (branch `feat/frontend`, r1–r6); God of War II HD booted in RPCS3 from the rail.
- **2026-09-22 - 0.1.4.** ROMs folder + Storage in the session's home; Bannerlator's File Manager
  ported whole; a cog per mode (railed settings window) with resolution cap, shape, HDR10 gated on
  the panel, drivers, touch, OSC/audio/renderer; two-column main menu; crash-safe log folders +
  Session logs toggle; OrphanReaper; drawer Steam menu. Public releases reorganised: one per
  version, old `Steamdeck` release and runtimes r1–r8 deleted.

## Architecture (where things live)
- `MainActivity` / `ui/MainScreen.kt` - the main screen; `ui/ModeSettingsDialog.kt` the cogs;
  `ui/*Dialog.kt` the rest.
- `SessionActivity` - the Surface, input (touch, touchpad, pads, keyboard), the drawer, the HDR
  decision, the compositor start (`wayland/CompositorHost`, `wayland/WaylandCompositor`).
- `session/SessionService` - proot + gamescope/labwc + PulseAudio + DirectAudio relay + network
  link; binds (`/root/Storage`, `/root/ROMs`); env for the guest; teardown; `OrphanReaper`;
  `SessionArtifacts` + `CrashHandler` + `SessionPaths` for the log folders; `SessionPrefs`.
- `files/` - Bannerlator's File Manager (`FileManagerScreen.kt` and its helpers), the picker
  activity and `InAppFilePicker` intent API.
- `gpu/` - Turnip (bionic, for the compositor) and Linux Turnip (glibc, for the runtime) managers.
- `tools/linuxfs/overlay/usr/local/bin/bannerlator-*` - the guest scripts, staged into the
  runtime by `SessionFiles` at every launch (CI packages only `bannerlator-*` names).
- `app/src/main/cpp/waylandcomp/` - the compositor (shared lineage with Bannerlator).

## Lessons learned (don't repeat these)
- Nothing in teardown may do bulk filesystem work on the main thread (0.1.1's ANR).
- A CI closure check on a multi-line variable must flatten it first (`libaaudio.so` refusal).
- A runtime script must be named `bannerlator-*` or CI never packages it.
- `gh release create --target` needs the full sha.
- A proot bind is invisible to a program's "Computer" list; put what users need under home.
- The compositor decides its driver and its HDR gate once per app process - anything that changes
  them applies after the app is fully closed, and the UI must say so.
- Bannerlator's File Manager ports mechanically (`port_fm.py` anchors) once the container hooks
  are cut; do not hand-edit 2,500 lines.
- Deleting a release asset on GitHub can drop a sibling asset - re-list and restore.
- A resource without an implementation makes libwayland abort on its first request - every
  interface the compositor hands out needs one, even as no-ops (positioner, popup, output).
- The KGSL stand-in answers no DRM ioctl: `drmIsMaster()` reads that as "master"; check
  `drmGetVersion()` before treating a GPU fd as DRM.
- Verify an emulator's config values from its source: RPCS3's GUI says "Async Shader Recompiler",
  the config value is "Async Recompiler (multi-threaded)".
- Upstream PCSX2 has no ARM64 recompiler; a correct ARM64 build of it is still interpreter-slow.
- An Android app starts in touch mode, where Compose clickables refuse focus: `requestFocus()` on open
  does nothing until the app asks for keyboard input mode.
- `grep -q` (or `head`) at the end of a pipe exits early; under `pipefail` that fails the whole line at
  random. Capture the output first, then search it.
- A reusable workflow gets no more permission than its caller gives, and sees environment secrets only
  with `secrets: inherit`. A workflow naming an environment that does not exist creates it unprotected.
- `pull_request_target` shares main's caches with the pull request's code - never with signed builds.
- apksigner prints signers as "Signer #1", "Signer (minSdkVersion=…)" or "V3.0 Signer:" by version.

## Backlog / next
- Play-test the other emulators one by one on the FIT: Dolphin, Cemu, melonDS, PPSSPP, RetroArch
  (pad profiles and covers exist; nothing played yet). DuckStation done 2026-09-24.
- **Kurt's PR #39 (Non-Launcher flavor) breaks signing as written**: it replaces the `droiddeck-apk`
  artifact with `droiddeck-home-apk` / `droiddeck-non-launcher-apk`, which `sign.yml` and `release.yml`
  read, and it conflicts with today's `build.yml`. Adapt before merging: sign both editions (a release
  would then be editions × packages).
- After the repo is public: add a required reviewer to the `release` environment (not allowed on a
  private repo on the Pro plan).
- First release with the new key (0.1.7): run `release.yml`, publish; 0.1.6 users move over by the
  hand-over.
- Keyboard Esc in a rail game does not reach ARMSX2's pause menu (guide does).
- 0.1.7 pre-release when ready (everything above is on main only).
- Pin a newer ARMSX2 nightly only after testing it (it changes daily).
- Device-prove 0.1.4 on the FIT (list above).
- Thor Pro: trackpad tap; the 1–2 min in-game crash once a `crash.log` arrives.
- Xfce as a second desktop shell (Max's branch runs XFCE 4.20 on labwc) - a catalog package + a
  shell choice in the Desktop cog; comfort, not performance.
- Quick Access Menu for non-Deck pads - Back double-press (500 ms) now routes to the existing Guide+A
  chord; the in-session menu, Steam settings, and Setup › Session can swap the single- and double-Back
  actions. Device confirmation is still needed.
- FlatOut shrink: try `vk_wsi_force_swapchain_to_current_extent=false` via `steamdeck-env`.
- Max's stricter `winnative-directaudio` guards; `winnative-epic-launch` (a feature).
- Rename before anything truly public: "SteamDeck" is Valve's mark. (Done 2026-09-23: DroidDeck.)
