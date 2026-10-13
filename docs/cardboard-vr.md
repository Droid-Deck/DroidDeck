# Cardboard VR (experimental)

Cardboard mode turns the session screen into a phone VR viewer (Google Cardboard and similar): the
panel is split into a left and a right eye, each drawn through a barrel distortion that the viewer's
lenses undo. Two sources can feed it:

- **Virtual screen** (any game, Steam, the desktop): the session hangs as a flat screen in front of
  you, with real stereo depth from the eye distance. With head tracking on, the phone's rotation
  sensor keeps the screen fixed in the room while your head turns (3DoF).
- **Side-by-side 3D**: the frame already holds a left and a right image. This is what SteamVR draws
  here (below), and what SBS 3D video or a stereo shader like SuperDepth3D produces. Only the lens is
  added.

The settings live in the session drawer, **Display > Cardboard VR**: mode, picture, viewer (lens
profile), eye distance, screen size and distance, head tracking and recenter, and SteamVR. Everything
applies live. `tools/droiddeckctl prefs set session cardboard '{"enabled":true,...}'` drives the same
settings (`gpu/Cardboard.kt` has the keys).

## How the picture is made

```
game / Steam -> gamescope -> DroidDeck compositor -> effects -> frame generation
             -> Cardboard pass (cardboard.frag) -> 1:1 blit -> panel
```

`app/src/main/cpp/waylandcomp/src/cardboard.c` is the last stage of the compositor pass. It forces the
copy path (no zero-copy layer: the pass draws the whole panel) and renders `cardboard.frag` into a
panel-sized target:

- each output pixel picks its eye, finds its offset from that eye's lens centre in tangent space, and
  applies the viewer's distortion polynomial `r(1 + k1 r² + k2 r⁴)` (sampling outwards draws the image
  barrel-shaped; the lens pincushions it back), optionally per colour channel for chromatic
  aberration;
- **virtual screen**: the ray from the eye (offset by half the IPD), rotated by the head pose,
  meets a plane `screenDistance` away; outside it is a dark backdrop;
- **side-by-side**: the eye samples its own half of the frame over the same field of view.

The lens centres sit inset towards the nose so they line up with the viewer's lenses on the panel
actually used (the panel's physical size from DisplayMetrics). The field of view is the panel's height
seen from the lens's focal distance (`Cardboard.fovDeg`). Viewer profiles are in `Cardboard.Viewer`.

Head tracking (`gpu/HeadTracker.kt`) reads the game rotation vector (gyro and accelerometer, no compass)
on a thread of its own and hands a row-major head-to-world matrix straight to native code. Recenter
resets the heading only, so the horizon stays level. When the view is more than 60° from level (lying
back, or the device flat on a table) it takes the whole orientation as straight ahead. A new pose
redraws even a static scene (`vkp_cardboard_pose_changed` on the vsync tick).

The pose is also published to the guest: a 128-byte seqlocked file, `headpose_shm.h`, at
`/run/droiddeck/droiddeck-headpose`. It carries the rotation, the IPD, and the frustum the side-by-side
pass expects of a source (`tan_half_fov`, `eye_aspect`, eye size), so a renderer in the guest can match
the lens exactly.

## SteamVR

Valve's ARM64 SteamVR, the build the Steam Frame runs, comes from Valve's public SteamOS package
server (`deckard-steamvr`, pinned by version and SHA-256 in `droiddeck-steamvr`; the same hash
[steam-frame-nixos](https://github.com/y0usaf/steam-frame-nixos) pins). `droiddeck-steamvr install`
unpacks it to `/opt/steamvr`. The drawer's **Install** button runs that (571 MB). **Start** then
switches the picture to side-by-side and runs SteamVR with three DroidDeck pieces (`tools/vr`):

1. **`driver_droiddeck.so`**, an OpenVR driver with one HMD:
   - pose: the shared head pose file (3DoF, standing height);
   - projection: exactly the frustum the Cardboard pass samples, with identity distortion (the app does
     the lens);
   - a proximity sensor that reads "worn" while Cardboard mode is on. Without it SteamVR takes a still
     headset (a phone on a table) for an unused one, enters standby after a few seconds, and the
     compositor stops submitting frames;
   - frames: `IVRDriverDirectModeComponent` with a free-running 60 Hz vsync announcer, as ALVR does on
     Linux. SteamVR renders each eye into textures the driver allocates through its IPC resource
     manager (dmabufs);
   - `presenter.cpp` imports those eye textures into a Vulkan device of its own and blits them side
     by side into a fullscreen window on DroidDeck's own compositor (`wayland-0`, the app_id
     `droiddeck-cardboard-vr` keeps it above gamescope's window), sized to the Cardboard panel so
     nothing scales it down. SteamVR hands the textures over with no fence the driver can wait on, so
     the presenter gives the compositor's GPU work a few milliseconds first, as ALVR does.
     `DROIDDECK_VR_PRESENT=x11` uses the older path instead: an X11 window on gamescope's Xwayland marked
     `STEAM_OVERLAY` (the external overlay slot is mangoapp's), at the session's resolution;
2. **`VK_LAYER_DROIDDECK_vr`**, a Vulkan layer, enabled explicitly in SteamVR's processes and implicit
   for the rest of the session (Steam's own web helper shares its in-VR overlay with SteamVR too, and
   crashes without it until Steam quits). Implicitly it does nothing in an ordinary session:
   - the display stand-in below happens only while SteamVR runs (`droiddeck-steamvr`'s `active`
     marker, which a new session clears) or where the layer is enabled by name. Gamescope and games
     asking for display extensions outside SteamVR get them untouched;
   - the semaphore bridge runs in those same processes, and in Steam's web helper once SteamVR is
     installed;
   - with SteamVR not installed, every process gets the driver's functions unchanged.

   What it does in SteamVR's processes:
   - `vrcompositor` always asks for `VK_KHR_display` and keeps a direct-mode window even with a
     driver-direct HMD. Turnip on KGSL refuses to enumerate any GPU when `VK_KHR_display` is enabled
     ("I can't KHR_display"). The layer takes the display extensions off the instance and stands in
     for one display with one mode and one plane, whose surface is a headless one
     (`VK_EXT_headless_surface`, sized from the mode because headless reports none). The vblank side
     of `VK_EXT_display_control` is a 60 Hz clock;
   - SteamVR shares a timeline semaphore with every shared image, exported as an opaque fd, which
     Turnip/KGSL cannot export. The layer creates it unshared and hands out a memfd holding a 64-bit
     counter instead. Every process with the semaphore keeps a real local one, and a poller thread
     carries values both ways (reached locally: up into the counter; seen in the counter: signalled
     locally from the host), so GPU waits work as written, a poll period late. Waits on an imported
     binary semaphore, which cannot be bridged that way, are dropped. With the semaphores dropped
     outright (the first version) the dashboard showed torn, half-drawn tiles;
   - Turnip on KGSL does not defer a submit that waits on a timeline value nobody has signalled yet:
     `vkQueueSubmit` blocks until it is. SteamVR's client waits on a compositor value that the
     compositor signals only after the client has submitted and said so, which deadlocked every app
     before its first frame (`helloxr`'s swapchain acquire, DXVK's submit thread in a Proton game).
     A submit's wait on an imported shared value that is neither reached nor queued in that process
     gets 3 ms for the other process to reach it, then is dropped;
   - Steam's web helper has the semaphore bridge whenever SteamVR is installed: its GPU process
     outlives SteamVR runs, and one started without the layer crashed when SteamVR showed Steam's
     overlays, until Steam quit;
   - `DROIDDECK_VR_CRASHTRACE=1` (or a `crashtrace` file beside the `active` marker, for processes like
     Steam whose environment is not ours) prints a backtrace on a fatal signal. `vrcompositor`'s
     launcher drops `LD_PRELOAD`, but the layer is loaded there. It only takes signals nobody handles
     yet: Wine and FEX handle `SIGSEGV` as part of running a Proton game, and a replaced handler
     crashed The Lab;
   - a `semtrace` file beside the marker logs, every 2 s, each bridged semaphore's local and shared
     value and the memfd inode that names it in every process, plus the first waits and signals
     (values up to 3) submitted on them.
3. **`droiddeck-steamvr`**, the guest helper (`status | install | start | stop`), which the app drives
   through the guest agent's fixed `vr` request (no agent commands needed). It also supplies an `xset`
   stand-in, because the dashboard's web helper checks the X server with it.

Imported eye textures carry the same format list SteamVR's mutable sRGB images do. With it Turnip keeps
UBWC compression on, and an import that disagreed read compressed data as garbage.

Two more fixes outside `tools/vr` were needed for the dashboard (Steam's library, in VR):

- **SysV semaphores** (`tools/linuxfs/preload/sysv.c`): the session emulates them, and `IPC_STAT`
  reported `sem_otime` 0 forever. SteamVR's web helper does the usual race-free init (wait until
  `sem_otime` is non-zero), so its zygotes slept in a `semctl`/`usleep` loop and the dashboard never
  appeared. `semop` now stamps `sem_otime`, `SETVAL`/`SETALL`/`IPC_SET` stamp `sem_ctime`;
  `test_sysv_preload.py` covers it.
- `vrwebhelper` gets the same tracer treatment as Steam's `steamwebhelper` (`tracer.c`).

Verified on the AYN Thor (SD 8 Gen 2, Turnip): SteamVR 2.17.10 starts with the DroidDeck HMD active,
the compositor runs driver-direct at about 60 fps, the dashboard (Steam's library in VR, with the
controller's laser) shows cleanly in both eyes at the panel's resolution while Steam keeps running, and
a pose written to the shared file turns SteamVR's view. Started from the drawer button through the agent.

### Not done yet

- **Controllers**: the driver adds two Vive-profile hands driven by the session's game pad. It reads
  the absolute-state snapshot the app already publishes in fake input ring 0
  (`FAKE_EVDEV_MEMFD_PATHS`), so the app needs no new plumbing. They are a gaze pointer: low in front
  of each hand, pointing where the head looks. Mapping, right hand: right stick = trackpad, A/R3 =
  trackpad click, RT = trigger, RB = grip, Start = menu, guide = system. Left hand: left stick, X/L3,
  LT, LB, Select. Both hands activate in SteamVR and their laser shows on the dashboard; guide, Start and
  A presses show up in the ring the driver reads.
- **Layers**: only the first layer of each frame is drawn. SteamVR's compositor composites the
  dashboard into it, so this has not mattered yet.
- **Timing**: no reprojection, and the presenter blits whatever was submitted last, a guard delay
  after it arrived. The chain is SteamVR → presenter → DroidDeck compositor → lens pass.
- **Cross-process GPU sync** is a poll away from exact (see the semaphore note above); binary
  semaphores are not bridged.
- **Apps and games**: Windows VR games run through Proton ARM64 (FEX for the x86-64 game, DXVK, and
  Proton's `vrclient_x64` bridging to SteamVR's ARM64 `vrclient.so`). Tested on the Thor:
  - **Thumper** (356400, its own D3D11 engine), started with its VR launch option: menus and levels
    at 60 Hz, about 7 ms CPU and 13 ms GPU a frame, 23 dropped frames in 6485;
  - **The Thrill of the Fight** (494150, Unity): loads into its gym, 60 Hz with 6.5 ms CPU and 8.5 ms
    GPU a frame;
  - **The Lab** (6.6 GB) loads, but Android's low-memory killer took the whole app while it did, next
    to Steam and SteamVR. Two VR games at once do the same, so quit one (`droiddeckctl quit <appid>`)
    before starting the next;
  - **SUPERHOT VR** stops at its EULA, which SteamVR's dashboard shows in VR;
  - **SteamVR Home** is not in the ARM64 package. Steam's own Windows SteamVR (250820) has it
    (`tools/steamvr_environments`, `steamtours.exe`), and it starts under Proton, but Proton's
    `vrclient_x64` implements only `IVROverlayView_003` and Home asks for `_002`, so it stops at
    "SteamVR initialization failed!";
  - SteamVR's ARM64 OpenXR sample, `helloxr -g Vulkan2`, reaches a focused session and submits frames
    (it was stuck before the first one until the wait fix above), but its picture shows up as torn
    tiles: next to chase. `hellovr_sdl` (OpenGL) needs GLEW, which the runtime lacks.

  SteamVR opens its game menu over a newly started game; `vrcmd --hidedashboard` (in
  `/opt/steamvr/bin/linuxarm64`) closes it.
- **Quest 3**: unrelated to this; on the Quest DroidDeck stays a 2D panel (`docs/quest.md`).

## Building and testing

- `tools/vr/build.sh [OUT]` builds the driver and the layer in the local cross image, or
  `--native OUT` with aarch64 compilers on PATH (CI). `tools/build_local.sh` and CI put them in the
  APK under `assets/linuxfs/usr/local/lib/droiddeck-vr`; `SessionFiles` stages them, and
  `droiddeck-steamvr`, into the rootfs.
- Shader: `cardboard_frag.h` is generated from `cardboard.frag`
  (`glslangValidator -V --vn cardboard_frag_code -o cardboard_frag.h cardboard.frag`).
  `tools/tests/test_cardboard.py` checks it is current, and checks the push-constant packing on the
  host.
- `CardboardTest` covers the settings, lens inset and field of view, the head-pose maths (quaternions,
  display-rotation remaps, recentring) and the SteamVR status. `test_droiddeck_agent.py` covers the
  `vr` request.
- Logs: the compositor's `[cardboard]` lines; SteamVR's in `~/.local/share/Steam/logs`
  (`vrserver.txt`, `vrcompositor.txt`) and `/run/droiddeck/droiddeck-steamvr/vrstartup.log`. The
  driver's lines start with `droiddeck:`.

Lessons from bringing this up on the Thor, and how to debug a live session: `docs/development/cardboard-vr-learnings.md`.
