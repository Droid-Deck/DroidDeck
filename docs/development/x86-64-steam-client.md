# x86-64 Steam client

Valve's x86-64 Linux Steam client, run unchanged under FEX beside the default arm64 client. It is
opt-in: Steam settings, **Steam client: x86-64** (`steamClientArch`, passed to the
session as `BL_STEAM_CLIENT_ARCH=x86_64`, applies from the next session), or the desktop menu entry
**Steam x86-64 (FEX, experimental)**. The Games tab lists the active client's games and tags its
header "x86-64" while that client's list is shown. This page records why each workaround exists and what was seen
on devices. The timeline is in `docs/progress-log.md`.

**Why.** The arm64 Linux client never runs VAC: Left 4 Dead 2 and Team Fortress 2 are refused by
VAC-secured servers ("Connection Blocked ... VAC secured server") and no client log mentions VAC. VAC
modules are x86 code. Under FEX the x86-64 client has a module it can run, as SteamLite does in Wine.

**Layout.** Everything lives in its own home, `$X64_HOME` (`BL_X64_HOME`, default `~/.droiddeck-x64`):
its own `~/.steam` links, config, libraries, x86 rootfs and log (`steam-x64.log`). Shared with the arm64
client: the sign-in, and its `config/uioverrides` (Steam's startup movie overrides), which the x86 client
links. One client runs at a time, since both use the same account; the standalone launcher shuts the
arm64 client down first.

| Command | Does |
| --- | --- |
| `droiddeck-steam-x64 [steam:// URL or client arguments]` | standalone run (the desktop menu entry) |
| `droiddeck-steam-x64 --prepare` | install and set up, then exit |
| `droiddeck-steam-x64 --exec ARGS...` | run the client binary in its environment (how the session starts it) |
| `droiddeck-steam-x64 --sync-login` | copy the newer sign-in to the other client |
| `droiddeck-steam-x64 --map-direct [--live]` | map Linux titles to DroidDeck direct |
| `droiddeck-steam-x64 --default-tool` | print the default ARM64 Proton wrapper's tool name |
| `droiddeck-steam-x64 --gpu` / `--webhelper-gpu` | exit status: client / x86 web helper on the GPU |

## Client install and update

- The first run downloads FEX (release `FEX_TAG` in Droid-Deck/DroidDeck-Components, sha256-pinned), FEX's Ubuntu 24.04 x86
  rootfs (about 525 MB, 1.5 GB unpacked, sha256-pinned; the same repository's copy of FEX-Emu's 2026-08-11 image) and the client (about 600 MB) from Valve's CDN,
  part by part with sha256 checks. A part-done rootfs download resumes; a finished one answers 416,
  which is not a failure. `unsquashfs -no-xattrs -ignore-errors`: ownership and xattrs mean nothing in
  the guest, and device nodes cannot be made and are not needed (FEX passes `/dev` through).
- Channel `steamdeck_publicbeta`, the `-clientbeta` the session starts it with. `package/beta` must
  match `-clientbeta` before every start, or the client loops on rc 42 (its restart request, which the
  standalone launcher honours up to five times, as `steam.sh` does).
- Before each start `x64_sync_client` brings the client to Valve's current build, downloading only the
  changed parts (9 of 28 on a device). If that fails, the client updates itself.
- The client is the `steamrt64` platform (`steamrt64/steam`, the manifest it checks for updates), so
  the install, the sync and the web-helper build check read `steam_client_<channel>_steamrt64`. The
  `ubuntu12` manifest lags it (device: 1792108788 against 1792192922): an install from it was always
  one build behind the arm64 client, so the arm64 web helper was never used, and the client updated
  itself in place at its first start (506 MB) after the helper had already been chosen.
- `.steam-enable-steamrt64-client` in the client folder opts into the SteamRT3 build
  (`steamrt64/steam`). Without it the client re-executes itself in the scout environment through
  `steam.sh`, whose start-up checks need what proot cannot give; the session stalled before the
  interface. The client is started directly, as the session starts `steamrtarm64/steam`:
  `STEAM_RUNTIME=1`, its own `steamrt64` libraries first, and the `~/.steam` links `steam.sh` makes.
- **User namespaces.** Unlike the arm64 client, this one carries pressure-vessel and refuses to start
  ("Steam now requires user namespaces") when `steam-runtime-check-requirements`' bwrap probe cannot
  create a user namespace, which no Android app can. `STEAM_RUNTIME_STEAMRT` points at
  `steamrt-shim`: Valve's runtime linked file by file with only the probe replaced (`exit 0`). proot
  is the container, as for the arm64 client.
- **Re-exec after an update.** The client re-executes itself inside the SteamRT container through
  `pressure-vessel-wrap` (bwrap). `STEAM_STEAMRT_RECURSION_GUARD=1` says it is already inside one; the
  FEX rootfs with the client's `steamrt64` libraries is that container.
- **Starting games.** Believing itself inside the container, the client starts games and system
  commands "alongside Steam" through `steam-runtime-launch-client`. Valve's asks a launcher service on
  the session bus (there is none: the game ended at once), and with none on `PATH` the launch fails
  (No such file). The shim's version applies `--directory`, `--env` and `--unset-env` and runs the
  command.
- **Libraries.** The rootfs lacks what the SteamRT container gives the client (GTK 2 for
  `steamui.so`, ...). Valve's steamrt3c platform libraries are linked into `steamrt-libs`; they ship
  without soname links (pressure-vessel makes those when it builds the container), so the rootfs's
  `ldconfig` adds them there. `LD_LIBRARY_PATH` beats the system folders whatever its order, so only
  libraries the rootfs lacks are kept: the platform's older glibc must not shadow the rootfs's.
- **System V IPC.** Android kernels have none, and the client's tier0 gave up on start ("Function not
  implemented" from `semget`; traced with a `-DDEBUG_STRACE` FEX). The session shim (`preload/*.c`) is
  also built for x86-64 and i386 and preloaded through the rootfs's own `/etc/ld.so.preload`, one copy
  per architecture under the multiarch folder named through glibc's `$LIB`. Without a list in the
  rootfs, FEX falls through to the guest's, which names the session's arm64 preloads.
- **Tracer.** proot is a tracer, so tier0 believes a debugger is attached and breaks into it with int3
  on the first failed assertion: the web helper died of SIGTRAP a second after every start.
  `preload/tracer.c` exempts web helpers on purpose (Chromium traps on a status fd that is not
  procfs); with `BL_WEBHELPER_HIDE_TRACER=1` only opens called from `libtier0_s.so` get the copy
  without the tracer.
- **FEXServer owns the rootfs.** A FEX process takes its rootfs from FEXServer, and a server started
  earlier without `FEX_ROOTFS` (any FEX run in the guest) serves none: every x86 library lookup fell
  through to the arm64 guest. It is stopped while no x86 client runs.
- **`/dev/dri`.** x86 programs enumerated `/dev/dri`, found the session's KGSL node and spun on it (x86
  Turnip "failed to query kernel driver version", KMS dumb-buffer ioctls in a loop); the web helper
  never got a browser up. FEX looks in the rootfs first, so an empty `rootfs/dev/dri` hides the node.
- **Scout runtime.** Valve ships it as `ubuntu12_32/steam-runtime.tar.xz` and `steam.sh` unpacks it.
  Since `steam.sh` is not used, `--prepare` unpacks it when its checksum changes.
- **Repair.** Repair Steam (`BL_X64_REPAIR=1`), or two starts in a row that never showed the client
  (`package/droiddeck-unconfirmed`; the app clears the count once the client is on screen), removes
  the install stamp so the client is reinstalled. Counts from before this was tracked are dropped
  rather than taken as failed starts. `droiddeck-steam-install` removes the repair request when it is
  done, so the session reads it first and both clients are repaired.
- **Stale state.** A killed client leaves `.crash`, which opens the update UI on its spinner, and
  `htmlcache/Singleton*` locks, which can leave the UI blank. Both are removed while no client runs.
- **Network.** The client's connection header and Settings > Internet come from NetworkManager on the
  system bus; without it the client shows no connection while downloads run. The standalone launcher
  starts the session's system bus configuration and the `droiddeck-netmanager` stand-in when no
  system bus is up (a desktop has none).
- **FEX settings.** The app's PERFORMANCE_TSO preset (`core/FexPreset.kt`, WinNative's default). FEX's
  own defaults keep half-barrier TSO and full-precision x87, which cost every x86 program time. Values
  already in the environment win.
- **Fast path.** libblfastpath, preloaded into every guest process and so into FEX, broke FEX's 32-bit
  mode (SIGILL at start). It stands down when FEX is about to run a 32-bit program; 64-bit programs
  keep it. `BL_X64_FP_OFF=1` turns it off for everything.
- **Diagnostics.** `BL_FEX` runs another FEX build (a `DEBUG_STRACE` one, say). `BL_FEXSERVER_LOG`
  starts a FEXServer in the foreground logging there; every FEX process that logs to "server"
  (`FEX_OUTPUTLOG` or an AppConfig) then has its lines tagged with its pid (file logs overwrite each
  other).

## Web helper on the GPU, QAM/WSI and flicker

The client's web helper draws its whole UI. There are two:

- **The arm64 client's helper** (`droiddeck-x64-arm-webhelper`), used when both clients are the same
  Steam build (unless `BL_X64_X86_WEBHELPER=1`). FEX hands the arm64 program to the CPU, so it runs
  natively with the session's preloads (KGSL poll fix, fast path) and the arm64 session's GPU
  settings. It talks to the client over IPC, and a helper of another build never comes up: the client
  restarted it every 10 s (device: 1790721607 against 1790904859). The session therefore updates the
  arm64 client's parts on the same channel first (`droiddeck-steam-install --sync
  steamdeck_publicbeta`). With this helper the client itself gets the GPU: it shows every Big Picture
  frame the helper draws, and in software (llvmpipe under FEX) that held the UI at about 20 fps
  (`BL_X64_CLIENT_SOFTWARE=1` keeps it in software). Device: 144 fps menus.
- **The client's own x86 helper on the GPU** (`droiddeck-x64-webhelper`), otherwise: ANGLE's GL
  backend through FEX's GL thunk to zink and Turnip. On ANGLE's Vulkan backend the x86 compositor
  thread was the bottleneck (16 fps against 60). Never `--in-process-gpu`, the preload's default: a
  GPU crash there took the whole helper down (102 restarts). `BL_X64_CEF_GPU=0` keeps it in software.
  Its GPU process loads libpci, which exits when `/sys/bus/pci/devices` lists a device whose files
  the app may not read (a RedMagic's PCIe root ports): every GPU process exited with status 1 in about
  50 ms, software ones too, until Chromium gave up ("GPU process isn't usable") and the menus stayed
  blank. `LinuxRuntime` binds an empty folder over `/sys/bus/pci/devices` on such devices; device:
  0 GPU process exits, ANGLE on zink and Turnip.

The session shim (`preload/net.c`) turns `steamrt64/steamwebhelper.sh`'s exec of `./steamwebhelper`
into `BL_X64_ARM_WEBHELPER`. Nothing in the client's folder is edited: the client checksums its files
as it starts and reinstalled itself over an edited launcher (2-3 minutes) on every start.

Quick Access Menu and flicker, x86 helper:

- Steam's compositor in the helper draws each browser window (main UI, QAM) from that window's X
  pixmap. gamescope's WSI layer took the helper's zink swapchains and sent the frames to gamescope
  instead: the QAM opened blank until a d-pad press and the screen behind it went black. The helper
  runs with `DISABLE_GAMESCOPE_WSI=1`; games keep the layer. Not FEX: a texture-from-pixmap test
  behaved the same under FEX and natively.
- gamescope sets `vk_xwayland_wait_ready=false` for its children because its layer does the waiting.
  Without the layer, unfinished frames reached the compositor as black flashes (3 in 10 QAM opens,
  screen-recorded and counted frame by frame). The helper sets `vk_xwayland_wait_ready=true`.
- zink's kopper swapchain still flashed on about 1 in 20 opens with the wait. With
  `LIBGL_KOPPER_DISABLE=true` none did in 60 opens, at 60 fps instead of about 140 (Xwayland's
  per-window timing for child windows). No flashes was chosen over frame rate.

Software path (no host thunks, or `BL_X64_SOFTWARE=1`): x86 Mesa has no driver for this GPU, so
llvmpipe for GL (the client's own window needs GLX) and the rootfs's own Vulkan ICDs (lavapipe). GLX
first failed because the desktop exports zink over the arm64 Turnip ICD; those variables are cleared.
`LIBGL_DRI3_DISABLE=1`: over DRI3 the X server hands out the GPU node and Mesa's software path then
tries KMS dumb buffers on it in a loop.

Decky's loader talks to the client's CEF debugging port. Its marker
(`.cef-enable-remote-debugging`) is kept in both clients' roots, because the session reads it from the
client it runs. The app creates it in the x86-64 root only once that client is installed: the folder
alone would then read as an installed client.

## FEX build and patches

- `tools/fex/build-in-arch.sh` runs in an Arch Linux ARM container (`menci/archlinuxarm:base-devel`)
  on an arm64 runner: Arch Linux ARM has no FEX package and FEX publishes no Linux binaries. It packs
  FEX and the runtime's own `unsquashfs` as `fex.tzst` (`usr/local/...`), with `fex-needed.txt` (every
  needed soname must resolve in a stock guest) and `fex-files.txt`. `TUNE_CPU=none`: the default
  (native) would tune for the runner's Neoverse cores, not the device.
- **Host thunks** (`FEX_THUNKS=1`): an x86 program's libvulkan, libGL, libX11, ... calls go to the
  runtime's arm64 libraries (Turnip, Mesa). The host half needs the graphics development packages. The
  guest half is cross-compiled with clang against an x86 sysroot of Arch x86_64 and multilib i686
  packages, unpacked by pacman into their own root and never run (no scriptlets, no signature checks).
  FEX's x86 toolchain files assume an x86 build machine, so on arm64 the sysroot also supplies headers,
  C runtime objects and libgcc.
- **Stack protector.** Arch's clang builds the guest thunks with stack protection. The canary load in
  `CallHostFunction`'s prologue (`mov %fs:0x28, %r11`) overwrote r11, which carries the host function's
  address, and FEX called the canary. 9 of 521 Vulkan and 66 of 736 GL host calls (those with 7 or
  more arguments) were affected, `vkCmdBindDescriptorSets` from TF2's DXVK among them; 32-bit games pass
  the host address in mm0. The toolchain files add `-fno-stack-protector` (`fex-2610-r2`).
- **`vkGetDeviceProcAddr`.** FEX-2609's guest version passed a lookup of `vkGetDeviceProcAddr` itself
  to the host and handed back the raw host function, so every pointer fetched through it was unlinked.
  TF2's native DXVK (2.3) makes that lookup. FEX-2610 answers self-lookups in the guest (FEX-Emu/FEX
  3397eb9b).
- Patches in `tools/fex/patches`:
  - `0001-openat2-enosys-fallback`: our proot answers `openat2` with ENOSYS (`tools/proot/PATCHES.md`,
    0008). FEX opened every rootfs path with `openat2(RESOLVE_IN_ROOT)` and fell through to the arm64
    guest on anything but EXDEV; x86 bash could not find libtinfo. Since r4 the fallback resolves the
    path inside the rootfs itself (absolute symlinks restart at the rootfs, as `RESOLVE_IN_ROOT` does);
    before, they resolved against the arm64 guest. `libblsession.so` (`preload/robust.c`) answers
    `openat2` first in practice and does the same walk for `RESOLVE_IN_ROOT` callers, so Valve's FEX-Emu
    on the arm64 client gets it too. Each path component is then its own system call.
  - `0002-thunkgen-newer-clang`: the container's newer clang has no `FileManager::clearStatCache()`,
    which the thunk generator called after each run.
  - `0003-allocator32-map32bit-exhaustion`: Counter-Strike 2 reserves the low 2 GB with `MAP_32BIT`
    in shrinking sizes. FEX's `MemAllocator32Bit` met EEXIST, stepped down one page per retry and
    restarted from the top forever (found with `perf_event_open` IP sampling, since proot holds
    ptrace). Such a range is now skipped whole and a second exhausted scan returns `-ENOMEM`, as the
    kernel does (`fex-2610-r3`).
- **Building and publishing.** Droid-Deck/DroidDeck-Components' `build-fex.yml` builds `tools/fex` from
  this repository at a chosen ref (default `main`) with host thunks, in the same Arch Linux ARM container
  with a read-only token, then a separate job replaces `fex.tzst` on Components' single `fex` release,
  checks the digest GitHub stored, and records the build's version, sha256 and size in its `fex.json`
  (in that order: a device that reads the old `fex.json` meanwhile gets a mismatching archive and keeps
  its FEX). This repository's `build-fex.yml` only builds `tools/fex` when it changes (and on dispatch,
  for diagnostic builds such as `-DDEBUG_STRACE`); nothing is published from it.
- Releases so far: `fex-2609-r2` (no thunks), `fex-2609-r3` (thunks), `fex-2610-r1`, `fex-2610-r2`,
  `fex-2610-r3`, `fex-2610-r4` (in-root resolution when openat2 is missing, exact MAP_32BIT
  marking), `fex-2610-r5` (current: bundles libxxhash, which the Linux runtime does not ship; the
  build fails when a program in the package needs a library that is neither bundled nor listed in
  `tools/fex/runtime-libs.txt`). They were built and published in The412Banner/DroidDeck; up to r3 their tags point at that
  repository's `main`, from r4 at the commit each was built from. Later builds are versions on the single
  `fex` release; `fex-2610-r5` stays published as the build `droiddeck-steam-x64` pins.
- **Where the app gets it.** The app downloads FEX from Droid-Deck/DroidDeck-Components, where nothing is
  uploaded by hand. The app follows its `fex.json`: while the x86-64 client is selected it reads it from
  Components' `main` (directly, at most every ten minutes) and keeps the last copy, and a session passes
  that build to `droiddeck-steam-x64` (`BL_FEX_TAG`, `BL_FEX_SHA256`, `BL_FEX_URLS` with the download
  source's mirrors). The script installs a build only after its archive matches the sha256 and every
  program in it loads (`ld-linux --list`); the files it replaces are kept aside until the installed copy
  loads too and put back otherwise, and the tag is written last. Otherwise it keeps the installed one while that still loads,
  then falls back to `FEX_TAG`/`FEX_SHA256` built in, which stay the first-start default when no copy
  of `fex.json` has been read. Publishing a FEX build therefore takes a Components run only; a
  replaced archive cannot be pointed back to, so undoing a build means building an earlier ref. The rootfs is
  FEX-Emu's own image, unmodified: `fex-rootfs.json` there names its rootfs.fex-emu.gg link, sha256 and
  size, and `mirror-upstream.yml` fetches it into the catalog's release only when both match. A newer
  image is a new catalog release; `ROOTFS_URL` and `ROOTFS_SHA256` then move to it, and a device that
  already unpacked one keeps it.

- **What a session log shows.** `FEX ready (<tag>, <source>)` names the build and where it came from
  (`catalog`, `built-in`, or `kept` when a newer one did not install, with the reason a line above);
  `x86-64 rootfs:` names the image; `x86-64 client:` the client's steamrt64 build at start
  (it may update itself after). The rootfs download reports every tenth of its size as a step. When the
  web helper's GPU process keeps exiting until Chromium gives up on the GPU (the menus then never appear),
  one line says so with the exit codes, followed by x86 `glxinfo -B` and `vulkaninfo --summary` through
  FEX's thunks.

## DroidDeck direct for Linux games and runtimes

- Steam runs native Linux games in the Steam Linux Runtime container (pressure-vessel), which needs
  bwrap and user namespaces. **DroidDeck direct** (`compatibilitytools.d/droiddeck-direct`, linux to
  linux) runs them under FEX with no container. Scout titles run through Valve's scout library runtime
  (`ubuntu12_32/steam-runtime/run.sh`). Titles Valve gives a newer runtime (sniper, soldier; listed in
  `direct-sniper`) are built for newer libraries than scout's and run on the rootfs (Ubuntu 24.04) as
  it is.
- **Mapping** (`x64_map_direct`): every game the client's appinfo cache shows with a Linux build (type
  game, oslist with linux; runtime from `config/app_mappings`: SteamLinuxRuntime_sniper or _soldier,
  none means scout), installed or not, so Steam downloads the Linux depots. A title on a tool chosen in
  Properties keeps it, except `BL_X64_DIRECT_APPS` (Left 4 Dead 2, 550, by default), which is always
  taken back. `direct-auto.json` lists what was mapped. One pass over the mapping: a 2000-title fixture
  went from 16.5 s to 0.2 s with the same output.
- A game whose Windows build is installed (it ran on a Proton before) stays a Windows game on the
  default Proton. Steam does not swap an installed build when the tool changes: DroidDeck direct was
  handed `Launcher.exe` and the game ended at once (Borderlands 2), and getting the Linux build back
  means downloading the game again.
- The client rewrites `config.vdf` from memory as it exits, so the mapping is written only while no
  x86 client runs: by `--prepare`, and by the session after each exit, when the appinfo cache also
  knows titles bought during the run. `--live` (the session's pass while the client runs) writes
  nothing; installed Linux titles it would map go to the session's pending list.
- A new user's client has no `config.vdf` before its first start, so Steam Play's default and the
  Linux titles would wait a session. `--prepare` writes the `CompatToolMapping` block alone, which the
  client keeps.
- **Sniper check.** `tf.sh` reads `VERSION_CODENAME` from `/etc/os-release` and stops unless it is
  `sniper`. For `direct-sniper` titles the run script runs such a script through `bash -c` with that
  line replaced by `VERSION_CODENAME=sniper`.
- **HarfBuzz.** The rootfs's HarfBuzz needs a newer FreeType than a game may bring. Counter-Strike 2
  ships an older `libfreetype.so.6` and no HarfBuzz: its menu text library failed to load, the game
  flagged its files corrupt (a verify each start) and waited behind an error box that could not open.
  Newer-runtime titles get Valve's runtime HarfBuzz (2.7) preloaded from `sniper-compat`, as they would
  in its container.
- **GPU.** Games get FEX's host thunks and the session's GPU settings (Turnip, Zink), saved by the
  launcher as `game-gpu.env` and rewritten whenever it runs; `game-gpu.local` is the user's, never
  rewritten, and is read after it. `BL_X64_GAME_SOFTWARE=1` keeps a game in software, with
  `mesa_glthread=false` (GL threading is no help with software GL under FEX).
- Source games (`hl2.sh`, `hl2_linux`, `srcds`) get `-condebug` for the VAC diagnostics.
- **Controllers.** `libfakeinput` is built for x86-64 and preloaded in the rootfs; Big Picture works
  with it. The 32-bit copy crashed Left 4 Dead 2: its fake devices reach the game's own SDL2 through
  udev with a missing attribute and SDL `strdup()`s the NULL (fault backtrace: strlen in libc <- `__strdup`
  <- libSDL2 <- `udev_device_get_sysattr_value`). 32-bit programs get only the session shim. The
  preload line names the x86_64 copy by full path, since with `$LIB` FEX falls through to the
  session's own `/usr/local/lib/i386-linux-gnu` copy.
- Device: TF2 main menu at 358 fps on the GPU and about 84 fps in a match; Half-Life (32-bit) at
  101 fps; Left 4 Dead 2 at 51 fps (about 11 in software).
- The arm64 client's route to x86 Linux titles, `droiddeck-fex`, is not wrapped for this client. Its
  wrapper ran when the client probed its tools after logon; the arm64 client took that for an x86
  program wanting FEX and installed Valve's FEX.
- `droiddeck-fex` itself (arm64 client): Valve's FEX-Emu keeps its server socket in
  `$STEAM_COMPAT_DATA_PATH/fex-emu/Server` whatever `FEX_APP_DATA_LOCATION` or `FEX_SERVERSOCKETPATH`
  say. Under the app's data folder that path passed the 108 bytes a socket path may have (Half-Life:
  136), FEXServer never listened and the game ended at once. FEX's data folder in the runtime directory
  is short enough; the one under HOME is not, so `/tmp/fex` stands in for it.

## Libraries, SD card and sharing between clients

- **Separate libraries.** With folders shared, each client could update, verify or switch the
  other's games. A title with a Linux build runs as that in the x86 client and as the Windows build in
  the arm64 one, so opening it in the other client switched the files and the first client downloaded
  its version again (Left 4 Dead 2, 8.6 GB). Hard links do not help: switching platform makes Steam
  download depot 551 anyway. A game wanted in both clients is installed in both. Folders an earlier
  version shared are taken out of the x86 client's lists (written only while no x86 client runs); the
  games stay in the arm64 library.
- **SD card.** The x86 client's card library is a folder inside the arm64 one (`/mnt/droiddeck-sd/x86-64`,
  `GameStorage.X64_CARD_DIR`, passed as `BL_X64_STEAM_LIBRARIES`), so the install dialog offers Internal
  Storage and SD card. compatdata, shadercache and runtime tools stay private, as in the arm64 card
  library. FEX runs x86 programs from the noexec exFAT card. The folder appears with the client's first
  session on the card and its `steamapps` with the first library write. Desktop sessions prepare it as
  well: desktop mode offers the x86 client whichever is selected, and `droiddeck-steam-x64` drops
  library folders it is not given. The arm64 card library may hold the arm64 copy of the same title,
  so the session's installed check looks only in the x86 card folder.
- **Card downloads.** `storage.c` (no zero-filled reservation where `fallocate` is refused) and
  `dircache.c` recognised only `steamrtarm64/steam`, and the x86 client wrote each download in full as
  zeros first (Audiosurf: 415 MB written, 0 downloaded). After the fix: FlatOut, 1.19 GB, to the card
  in 51 s.
- **Sign-in.** Before either client starts, the newer sign-in (`config/loginusers.vdf`, the login token
  in `local.vdf`, `AutoLoginUser` and `RememberPassword` in `registry.vdf`) is copied to the other, so
  signing in or switching accounts in one counts for both; never while either client runs, since each
  rewrites these files. A client that refused its token rewrites `local.vdf` with an empty
  `ConnectCache`, so it is never the source. The copy keeps the time stamp, so both match until one
  client signs in again. The rest of `registry.vdf` (language, skin) stays each client's.
- **Account IDs.** A client logs on with the token only once it knows the account's SteamID, which it
  takes from `Accounts` in `config/config.vdf`. The x86 client's `config.vdf` is made by this script
  without one, so it logged on as `[U:1:0]` with the arm64 token and Steam answered "Invalid Password"
  (and the client then dropped the token and `AutoLoginUser`): every switch asked for the password.
  Each start adds the accounts either client knows to the other's `Accounts`, and an `AutoLoginUser` or
  `RememberPassword` one client has and the other lacks. Device: the x86 client signed in with the
  arm64 token (`RecvMsgClientLogOnResponse ... 'OK'`).
- **App side.** The library, art picker, grid sync and boot movie follow the running client, else the
  selected one; a changed client applies from the next session. A game is listed once per client: an added game
  whose folder a Steam install already covers (its `DroidDeck-<appid>` import) is shown as that Steam
  game only, and a client switch drops the old client's imported entries with its Steam games. In
  Steam, an import stays one when its ownership cannot be confirmed that session instead of gaining a
  shortcut beside it, and only the client's own library folders count as an install (the arm64 card
  library made the x86 client skip a game's import and its shortcut). Offline Mode is written to both
  clients' `loginusers.vdf`; until the x86 client's first start its sign-in is the arm64 one. The log
  redactor learns account names from both clients. The startup movie is staged into the x86 client's
  config only once its first start has created it; `--prepare` links the arm64 `config/uioverrides`
  and copies the `StartupMovie` choice.
- **Storage page.** It lists both clients' games, the x86 ones labelled; a game can be installed in
  both and an id left over in several libraries. `steam://uninstall` reaches only the session's client,
  so the other client's copy is uninstalled after switching clients. Tools in the x86 client's
  libraries are shown as required (it runs its Linux games with Valve's runtimes). Proton tool files
  live only in the arm64 root; the x86 client has wrappers. An x86 client without `config.vdf` has
  never mapped a game.

## Proton for Windows titles in the x86 client

- Windows games run on the arm64 client's Protons; x86 Proton would need pressure-vessel. For each
  arm64 client tool (droiddeck-proton-arm64, DroidDeck-Proton, GE-Proton, CachyOS) `--prepare` makes a
  wrapper in the x86 client's `compatibilitytools.d` with the same folder and display name: Components
  swaps and per-title mappings are keyed by them. In the tool name `arm64` and `aarch64` become `a64`,
  because the x86 client ignores a tool named for arm64 ("for a different target platform linux
  arm64"). The session runs `--prepare` again after it installs a new Proton.
- The wrapper is an x86 script under FEX. It starts `droiddeck-x64-arm-proton` through an arm64 binary
  (a copy of `/usr/bin/env`), which FEX hands straight to the CPU, so Wine and the game run as from the
  arm64 client: same Proton, launcher, Components swaps, per-game environment and Steam client library
  (`~/.steam/sdk64`).
- `droiddeck-x64-arm-proton` drops FEX's settings and the x86 library paths and preloads, then restores
  the arm64 session's environment that `droiddeck-steam-x64` saved before it built the x86 one
  (`arm-session.env`: HOME, PATH, GPU, audio, input, fast path). Steam's per-game variables are not in
  it. It keeps the game display Steam chose: the saved `DISPLAY=:0` hid the game window and made
  DiRT Showdown exit after about 7 s. Proton takes its Steam client parts from the client's install
  folder, and the x86 client's are x86, so `STEAM_COMPAT_CLIENT_INSTALL_PATH` points at the arm64 root
  and `BL_X64_STEAM_ROOT` keeps the x86 one for install-script lookups.
- **Game overlay.** The x86 client preloads its own x86 overlay; the arm64 one takes its place. The
  overlay marks the game's windows with the Steam app ID, and without it gamescope never shows them
  (FlatOut ran and played sound behind Steam's launch screen, "steam app id: 0"). The agent also tags
  untagged `steam_app_<id>` windows with `STEAM_GAME` on the game display when the x86 client runs
  (DiRT 3, an added game, then reached its title screen at 144 fps).
- The x86 client's tools are not adopted as arm64 Protons: `adopt_extras` on its
  `compatibilitytools.d` rewrote DroidDeck direct as a Windows tool, and Steam installed Half-Life's
  Windows files. The arm64 Proton is adopted on demand at an x86 launch.
- **Default.** The newest DroidDeck-Proton, then the newest GE-Proton (aarch64), else
  `droiddeck-proton-a64`. FlatOut from the card ran on DroidDeck-Proton 11-9.1 and exited on
  GE-Proton11-7 (no log). With droiddeck-proton-arm64 (Proton Experimental) Xwayland never got its
  window buffers back and FlatOut took the device's RAM with it (10.5 GB of dma-buf in 35 s);
  GE-Proton, same game and client, stayed flat. The session asks for GE-Proton when none is installed.
  `default-tool` records the default the script set; when it changes, the titles still on the old one
  move along (device: 113 titles off GE-Proton11-7). A default or title the user set to another tool
  keeps it. Before `default-tool` existed, the default the script set was an `-a64` wrapper at
  priority 75.
- `steam-compatibility --x64` repoints entries the client aimed at a Proton it cannot start (Valve's
  `proton_*` names, and `droiddeck-proton-arm64` left by an arm64 compat pass) to the default wrapper.
  Tools in its own `compatibilitytools.d` are kept: the wrappers include `proton-cachyos-*-a64`, which
  starts with "proton" like Valve's.
- **Added games** are written by the client the session runs and pointed at its default wrapper. The
  shortcuts pass keeps the x86 client's own default and state (`droiddeck-compat` beside its Steam
  folder), because the arm64 client's `state.json` lists which of that client's titles follow its
  default.
- **Install scripts.** A game's own installers without a `HasRunKey` (Borderlands 2: VC++ 2005, 2008
  and 2010, DirectX, .NET 4.0) are marked under `Software\Valve\Steam\Apps\<appid>` (and the
  Wow6432Node view), so Steam skips them; this applies to both clients. Windows components install into
  the x86 client's prefix (DiRT Showdown and DiRT 3 needed OpenAL).

## VAC findings

- x86-64 client with Linux builds: secure. Left 4 Dead 2 joined a live Quick Match game, the server
  type that refuses the arm64 client; on the GPU an online server answered the A2S info query as
  "Valve Left4Dead 2 US East Server", VAC=1. TF2 Casual matchmaking put it on a VAC-secured Valve
  server. Half-Life's native build joined a VAC-secured server.
- x86-64 client with a Windows build (TF2 on DroidDeck-Proton): insecure. Proton reaches Steam through
  the arm64 client's `steamclient.so`.
- arm64 client: Left 4 Dead 2 was blocked on both Protons; Half-Life (Windows build) joined a vac=1
  GoldSrc server. Why the arm64 client fails is not established; it has no VAC module of its own to run.
- Headless VAC testing is not possible: current Steam starts the saved login from its UI.
- **VAC diagnostics** (Setup page). A Source game decides secure or insecure once, as it starts, and
  says so only in its own console ("You are in insecure mode. You must restart before you can connect
  to secure servers."). That console reaches a file only with `-condebug`: `<game>/<mod>/console.log`,
  or `<game>/game/<mod>/console.log` for Source 2. At session start the app writes
  `~/.droiddeck-vac-diagnostics` (start time, client, each console.log's length, since `-condebug`
  appends). The launch wrapper (`bl_vacdiag`) then adds `-condebug` to Source titles (found by
  `gameinfo.txt` or `gameinfo.gi`), flags `-insecure` in launch options and notes each launch. At the
  end the session copies what each console.log gained into `vac/`, scrubbed, and writes `vac.txt`: the
  game's secure/insecure lines, VAC lines in the client's logs, and which client files are present. A
  stopped session keeps its own copy of the marker and launch list; a new session waits for earlier
  collections, because it replaces the marker and Steam's logs.
- "Connecting to" is only an attempt. L4D2 logs it, retries, and then refuses a VAC server with a
  dialog ("Connection Blocked: Please remove plug-ins before attempting to connect to a VAC secured
  server") that never reaches the console. Only "Connected to" means the game got in.
- SteamLite's "INSECURE - VAC may kick" warning was our own parser reading the wrong log while the
  client was secure all along. `vac.txt` therefore quotes the lines it found and says "inconclusive"
  when the game never reached a server.

## Session integration, restarts and mapping

- With `BL_STEAM_CLIENT_ARCH=x86_64`, `droiddeck-session` updates the arm64 client's parts
  (`droiddeck-steam-install`, then `--sync steamdeck_publicbeta`), runs `droiddeck-steam-x64 --prepare`
  and starts the client with `droiddeck-steam-x64 --exec`. Steps only for the arm64 client (library
  registration, the ARM64 Proton fetch, the live compat helper) are skipped; added games,
  `steam-compatibility --x64`, UI scale and redistributables run for the x86 client.
- **Deck mode.** With `-steamos3` the client's SteamOS audio manager passed libpulse the NULL
  operation of a failed request, and libpulse aborted the client right after start ("Assertion 'o'
  failed at ../src/pulse/operation.c:136 pa_operation_get_state()", the runtime's PulseAudio 13).
  `preload/pulseguard.c` (x86 builds only; the arm64 client does not hit it) answers a missing operation
  as cancelled and ignores its unref and cancel. The session passes `-steamdeck -steamos3` unless
  `BL_X64_DECK=0`. Switch to Desktop is expected to need no change (an x86 shell under FEX runs
  `steamos-session-select`); the full switch has not been tested on a device.
- **Process names.** Under FEX a process's argv[0] is FEX and the program is its first argument. The
  session's game detection, CPU pinning and the zRAM watcher read that argument. Steam pins itself and
  its web helper to a few cores, so the session's CPU set is applied again every 5 s.
- **Live mapping and restarts.** While the client runs, the session's background loop runs
  `steam-compatibility --x64 --live` and `droiddeck-steam-x64 --map-direct --live`, every 15 s and
  only in the session's first hour (720 beats of 5 s). A title installed after that is mapped when the
  client exits and takes effect from the next session. Steam reads a
  mapping changed while it runs only at its next start, so changed titles go to `.bl-compat-pending`.
  Once they have installed (`StateFlags` 4 in the client's root or its card folder) and no game runs,
  the client is restarted once per title, at most 3 times a session.
- **After exit.** Added games, `steam-compatibility --x64` and `--map-direct` run again: the client
  wrote its mapping from memory as it exited, over what the live passes set.
- The Lossless Scaling ownership probe and the Steam import watcher reach the x86 client through the
  arm64 client's `steamclient.so` (`BL_STEAMCLIENT`); the watcher restarts the client with
  `droiddeck-steam-x64 --exec -shutdown`.
- Steam logs and the Steam language come from the x86 client's home. On the desktop, `steam` and
  `steamdeck-steam` start the selected client, and the desktop game entries list its libraries.
- The standalone launcher (desktop menu) shuts the arm64 client down first, starts the system bus and
  the NetworkManager stand-in when missing, clears stale locks, pins CPUs and restarts the client on
  rc 42.

## Known limits

- Experimental and opt-in; one client runs at a time.
- Windows titles in the x86 client are not VAC-secure: Proton reaches Steam through the arm64
  `steamclient.so`.
- 32-bit games get no controller reader (the i386 `libfakeinput` is left out).
- 32-bit Linux titles through the arm64 client's `droiddeck-fex` still fail (not pursued).
- The x86 web helper's menus run at 60 fps without kopper.
- Counter-Strike 2 reaches its menu (and loaded Dust II at 78 fps), then Android's `lmkd` kills
  DroidDeck. The game's GPU memory is pinned: KGSL `page_alloc` followed Shmem through the load, to
  about 3.2 GB at the kill, with Unevictable just above it. This device's `lmkd` runs in minfree mode,
  which does not count swap, while MemAvailable counted that memory as available. Paging the game out
  with a root tool (`process_madvise(MADV_PAGEOUT)`) let it draw its menu, but `lmkd` still killed the
  session. Not solved.
- Sharing libraries between the clients is parked.
