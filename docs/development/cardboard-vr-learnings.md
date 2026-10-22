# Cardboard VR and SteamVR on Turnip: what we learned

Written 2026-10-13, after the first stretch of Cardboard mode work on the AYN Thor (Snapdragon 8 Gen 2,
Adreno 740, Turnip on KGSL). `docs/cardboard-vr.md` describes how the feature works. This page records
what it took to get there: the traps, why each fix looks the way it does, how to debug a live session,
and what is still open. Read it before the next round of VR work.

## Where it stands

| What | Result |
| --- | --- |
| Cardboard mode (lens pass, 3DoF head tracking, recentre) | Works on any picture; picture follows SteamVR on and off |
| SteamVR 2.17.10 ARM64 (the Steam Frame's `deckard-steamvr`) | Starts from the drawer; driver-direct at 60 Hz, dashboard and Steam library in VR |
| Thumper (356400, D3D11, VR launch option) | Menus and levels at 60 Hz, 7 ms CPU / 13 ms GPU, 23 of 6485 frames dropped |
| The Thrill of the Fight (494150, Unity) | Gym loads, 60 Hz, 6.5 ms CPU / 8.5 ms GPU |
| The Lab (450390) | Loads, then Android's low-memory killer takes the whole app |
| SUPERHOT VR (617830) | Reaches its EULA in the dashboard (not accepted in testing) |
| SteamVR Home | Blocked: Proton's `vrclient_x64` lacks `IVROverlayView_002` |
| `helloxr -g Vulkan2` (OpenXR) | Session focused and submitting, picture shows as torn tiles |

Screenshots from the Thor: [Thumper gameplay](evidence/cardboard-thumper-vr-gameplay-2026-10-13.png),
[Thumper title](evidence/cardboard-thumper-vr-cardboard-2026-10-13.png),
[The Thrill of the Fight](evidence/cardboard-thrill-of-the-fight-cardboard-2026-10-13.png),
[the SteamVR dashboard with a game's EULA](evidence/cardboard-steamvr-dashboard-2026-10-13.png).

Games run as x86-64 Windows builds through Proton ARM64 (FEX, DXVK). Proton's `vrclient_x64` forwards
OpenVR to SteamVR's ARM64 `vrclient.so`, so no x86 SteamVR is involved.

## Turnip on KGSL: the four things SteamVR trips over

1. **`VK_KHR_display` makes the GPU disappear.** Turnip on KGSL enumerates no physical device when an
   instance enables it ("I can't KHR_display"). `vrcompositor` always asks for it. The layer takes the
   display extensions off the instance and fakes one display, mode and plane backed by a headless
   surface. Headless surfaces report a `UINT32_MAX` extent, so the swapchain is sized from the mode.
2. **No exportable semaphores.** SteamVR shares a timeline semaphore with every shared image as an
   opaque fd, and Turnip/KGSL cannot export one. The layer creates the semaphore unshared and hands out
   a memfd (`droiddeck-shared-sem`) holding a 64-bit counter. A poller thread in each process carries
   values both ways every 300 µs. Dropping the semaphores outright, the first attempt, gave torn
   dashboard tiles.
3. **Wait-before-signal blocks the submitting thread.** Turnip does not defer a submit that waits on
   a timeline value nobody has signalled yet; `vkQueueSubmit` sleeps until it is. SteamVR's client
   waits on a compositor value that the compositor signals only after the client has submitted and
   said so, so every client deadlocked before its first frame:
   - `helloxr` blocked in a wait-idle in `vrclient.so`;
   - DXVK's `dxvk-queue` thread blocked in Proton games.

   The layer now gives a submit's wait on an imported shared value 3 ms, then drops it, at once when
   the shared counter has not moved for 100 ms. It is kept when the value is already reached, when
   this process has queued the signal itself, or while a Quest session's frame socket exists (checked
   at most once a second). Three details mattered:
   - the waits are judged before the submit's own signals count, because an acquire waits on one
     value and signals the next on the same semaphore;
   - SteamVR's client interop device is Vulkan 1.0, so only the `KHR` entry points resolve there;
   - a Proton game waits on two values a frame that are never signalled (5,500 dropped, none reached
     within 200 ms afterwards). With the grace on each, Angry Birds VR's submit thread lost 6 ms a
     frame and the game ran at 60 fps; without it, 120.
4. **UBWC layouts must agree.** An imported image has to be created with the same
   `VkImageFormatListCreateInfo` (sRGB + UNORM) as its creator, or Turnip lays out the compression
   differently and the reader sees garbage. The presenter's eye-texture import does this.

## Other traps, in the order they cost time

- **A still headset goes to standby.** A phone on a table looks unworn, SteamVR enters standby after
  about 15 s, and the compositor stops submitting. The driver's proximity sensor reads "worn" while
  Cardboard is on.
- **Emulated SysV semaphores never set `sem_otime`.** SteamVR's web helper waits for it to become
  non-zero before using a semaphore set, so the dashboard never appeared. `sysv.c` now stamps
  `sem_otime` and `sem_ctime`.
- **Steam's web helper needs the layer too.** It shares Steam's in-VR overlays with SteamVR and crashed
  without the layer; after a few crashes Steam quits. Its GPU process outlives SteamVR runs, so
  `steamwebhelper` gets the semaphore bridge whenever SteamVR is installed, not only while SteamVR's
  `active` marker exists. It never gets the display stand-in.
- **Keep the layer out of ordinary sessions.** As an implicit layer it loads everywhere. Display
  extensions are only taken off while SteamVR runs, a new session clears a leftover `active` marker,
  and without SteamVR installed every process gets the driver's functions unchanged. Review feedback
  on the first version pointed out that gamescope asking for a display extension would have been
  rewritten in an ordinary session.
- **The `active` marker race.** Clearing a stale marker whenever `vrserver` is not running also
  cleared it during startup, before `vrserver` appeared, because the app polls status every 3 s. Steam
  quit as a result. Only a marker older than 2 minutes is cleared now.
- **Signal handlers in Proton games.** The layer's crash tracer replaced the `SIGSEGV` handler that FEX
  and Wine rely on, and The Lab crashed in FEX's JIT. It now installs only where nothing handles the
  signal.
- **mangoapp owns gamescope's single external overlay slot**, so an X11 presenter has to use
  `STEAM_OVERLAY`. The default presenter is now a Wayland window on DroidDeck's own compositor, kept
  on top by app id.
- **Memory.** Steam, SteamVR and one VR game fit in the Thor's 12 GB. A large game such as The Lab, or
  two games at once, push the device into critical pressure, and the low-memory killer takes
  DroidDeck itself (`ended-without-teardown.txt` in the session logs, `lowmemorykiller` in logcat).
- **Adreno 840 forced sysmem.** On the Fold with VITURE Luma Ultra, launching SteamVR from the library
  inherited `TU_DEBUG=sysmem` and produced `vkerror=-4`, triggering recovery. Adding `noubwc` still
  failed; replacing the flags with `noubwc` or an empty value worked. The shared launch helper removes
  only `sysmem` on `Adreno840*`, leaving other flags, the parent Steam session and other GPUs unchanged.
  The user confirmed improved landing-area brightness and colors with this configuration and asked
  to preserve them.
- **Adreno 840 with DD-Turnip v0.2.0.** SteamVR reproduced `vkerror=-4` with `sysmem` removed,
  including on the previous app build and with the presenter disabled. After a phone reboot,
  the unchanged `fcf8c588` baseline rendered for 109 seconds without observed device loss.
  `TU_DEBUG=flushall` initially appeared promising: a global run rendered for over a minute,
  and a SteamVR-only run rendered for about 32 seconds before background suspension. The
  packaged SteamVR-only change then reproduced device loss with `flushall` confirmed in
  `vrcompositor`, so the automatic flag was removed. These bounded runs do not establish the
  underlying cause or a reliable workaround.
- **Live files are not cache.** Android's `installd` evicted `cache/shm/u10528-*` while SteamVR was
  running, followed by Chromium shared-memory failures and lost IPC. Shared memory, emulated device
  files, sandbox trees and PRoot temporary files now use `no_backup/guest-runtime`, retaining their
  existing cleanup lifetimes. Session state must survive cache eviction.
- **One host signal owner.** The submit-wait path must not mirror a shared timeline value itself:
  the poller can signal it between the submit thread's counter read and signal, producing an invalid
  duplicate signal. The poller now owns mirroring; a forced-interleaving test covers this race.
- **Lost hand tracking is not a disconnect.** Publishing a disconnected pose and then a connected,
  untracked pose on every optical update floods SteamVR with device-change notifications. With
  uncalibrated hands this grew its web buffer to about 34 MB and triggered the Web thread watchdog.
  Release inputs and publish only the connected, untracked pose; reserve disconnection for an
  actual controller removal. The controller regression exercises repeated loss and reacquisition.
- **Failed realtime scheduling can still install a CPU limit.** On the Fold, SteamVR's SDL RTKit
  fallback set `RLIMIT_RTTIME` to 100/200 ms despite realtime priority being refused. The render
  thread later received kernel `SIGXCPU` during `IOCTL_KGSL_GPU_COMMAND`, with policy `SCHED_OTHER`
  and the soft limit advanced to 1100 ms, confirming the realtime watchdog fired. The VR scheduling
  preload rejects lowering this limit only in `vrcompositor` when `RLIMIT_RTPRIO` is zero and
  `CAP_SYS_NICE` is absent. It preserves inherited limits, other processes and other resources;
  it does not intercept signals. SDL's scheduling hint cannot cover SteamVR's direct
  `SDL_LinuxSetThreadPriorityAndPolicy` call. A native preload regression covers rejection and
  forwarding. The exact Android kernel boost/accounting path was not established.
- **Frames shown out of order.** Video from the glasses jumped back a few frames in bursts while the
  head turned. Two causes, each found by stamping a frame counter into the presenter's output:
  - the presenter copied SteamVR's eye texture a fixed 4 ms after `Present`, with no fence. Under
    GPU load `vrcompositor` had not drawn it yet, so the copy got that texture's previous frame. The
    layer now fences `vrcompositor`'s submits and the presenter waits for the frame's count;
  - DroidDeck's compositor copies a Wayland client's dma-buf without waiting for its implicit fence,
    and KGSL does not order the copy after the client's GPU work. The presenter's blit sat behind the
    game's frame, so the compositor showed the swapchain image's older picture. The presenter now
    waits for its blit before it presents.
- **Flat games under SteamVR.** With SteamVR running, Steam lists a flat game after its own window in
  gamescope's base layer, so Big Picture keeps the focus and the game never shows, theatre setting or
  not. SteamVR is therefore started only by Play SteamVR, never with the glasses alone.
- **A new projection needs an event.** `SetDisplayProjectionRaw` changed what `vrserver` reports, but
  `vrcompositor` kept the frustum it started with, so the glasses' field of view did nothing until
  SteamVR restarted. The driver now follows it with `VREvent_LensDistortionChanged`.
- **Head-tracking axes.** With the display at `ROTATION_90`, screen x is minus device y and screen y is
  device x. Getting this wrong made a turn at the hips tilt the view. Recentring now keeps the world
  vertical and resets the heading only.

## Library launch validation (2026-10-15)

Two hands-off launches through the Steam library's Launch button completed 308 seconds and
188 seconds, with an intentional stop between runs. Each retained its compositor process and
session, sustained 60 fps, showed dashboard/world transitions, and kept USB control and valid
6DoF head tracking. Neither run reported device loss, a watchdog abort, recovery, or a session
restart. Hand controllers remained enabled but uncalibrated. Brightness stayed at 8; no shader
color changes or temporary graphics overrides were used.

These timed runs used APK SHA-256
`4ce7a623ceb6b1a100c0ed1184530ece2ea8e13a1cb9ee6c0ab24666a8249004`. A newer calibration/recenter
APK was subsequently installed and verified; see `luma-spatial-evidence.md`. The timed results
do not establish sustained gameplay or calibrated-hand acceptance for that newer build.

## Debugging a live session

- **Logs:** `~/.local/share/Steam/logs/{vrserver,vrcompositor,xrclient_*}.txt` and
  `/run/droiddeck/droiddeck-steamvr/vrstartup.log`. Lines from the driver start with `droiddeck:`,
  lines from the layer with `[droiddeck-vr-layer]`. A game's layer messages land in the session log.
- **Markers beside `active`** in `/run/droiddeck/droiddeck-steamvr/`:
  - `crashtrace` prints backtraces on fatal signals;
  - `semtrace` logs each bridged semaphore's local and shared value every 2 s, named by the memfd
    inode so the same semaphore can be matched across processes, plus the first waits, signals and
    shared image create infos.

  Both are read when a process starts.
- **Frame stats:** in `/opt/steamvr/bin/linuxarm64`, `LD_LIBRARY_PATH=. ./vrcmd --stats` reports
  submits, drops and app CPU/GPU times. `./vrcmd --hidedashboard` closes the menu SteamVR opens over a
  new game.
- **Where a process is stuck:** a stack scan of `/proc/<tid>/mem` from the saved stack pointer (a small
  Python helper) gives module+offset frames. That is how the wait-idle in `vrclient.so` and the
  blocked `dxvk-queue` thread were found.
- **VR launch options:** `SteamClient.Apps.GetLaunchOptionsForApp(id)` lists them, and
  `SteamClient.Apps.RunGame("<id>", "", <index>, 100)` starts one, through `droiddeckctl cdp eval`.
- **Quitting:** `droiddeckctl quit <appid>`. Without the id the game keeps running.
- **Pitfalls when testing:**
  - never `pkill -f` or `pgrep -f` a pattern inside a guest command, because it matches the
    command's own shell; use `pgrep -x`;
  - replace a loaded `.so` by copying to a temporary name and `mv`, never `cp` over it;
  - a session start restages the driver and layer from the APK assets, so a hand-copied build is
    lost on the next start.

## Open items

- **`helloxr` picture.** Both sides create the swapchain images with identical parameters, so this is
  not the UBWC import issue. Reading an image mid-render (the dropped wait) or binning tiles is the
  next suspicion.
- **SteamVR Home** needs `IVROverlayView_002` in Proton's `vrclient`, or an ARM64 build of Home.
- **The Lab and other large games** need memory headroom. Candidates: stop Steam's own web helpers
  while a VR game runs, or lower SteamVR's footprint.
- **Wait drops are a heuristic.** A 3 ms grace keeps the common case in sync, and an idle counter
  skips it. A submit thread in the layer, deferring submits until their waits are reachable, would be
  exact.
- **Layers:** only the first layer of each frame is presented. No reprojection.
- **Controllers** are a gaze pointer driven by the pad. Pad presses did not close the SteamVR menu in
  testing. Untested: whether that came from gamescope focus being on Steam, or from the system button
  mapping.
