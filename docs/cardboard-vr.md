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
     by side into a fullscreen X11 window on gamescope's Xwayland, marked `STEAM_OVERLAY` so gamescope
     shows it above Steam. (The external overlay slot, `GAMESCOPE_EXTERNAL_OVERLAY`, is taken by
     mangoapp.)
2. **`VK_LAYER_DROIDDECK_vr`**, a Vulkan layer for SteamVR's processes:
   - `vrcompositor` always asks for `VK_KHR_display` and keeps a direct-mode window even with a
     driver-direct HMD. Turnip on KGSL refuses to enumerate any GPU when `VK_KHR_display` is enabled
     ("I can't KHR_display"). The layer takes the display extensions off the instance and stands in
     for one display with one mode and one plane, whose surface is a headless one
     (`VK_EXT_headless_surface`, sized from the mode because headless reports none). The vblank side
     of `VK_EXT_display_control` is a 60 Hz clock;
   - SteamVR shares a timeline semaphore with every shared image, exported as an opaque fd, which
     Turnip/KGSL cannot export. The layer creates it unshared, hands out a placeholder fd, and drops
     GPU waits on an imported placeholder. Each process still orders its own work;
   - `DROIDDECK_VR_CRASHTRACE=1` prints a backtrace on a fatal signal. `vrcompositor`'s launcher drops
     `LD_PRELOAD`, but the layer is loaded there.
3. **`droiddeck-steamvr`**, the guest helper (`status | install | start | stop`), which the app drives
   through the guest agent's fixed `vr` request (no agent commands needed). It also supplies an `xset`
   stand-in, because the dashboard's web helper checks the X server with it.

Imported eye textures carry the same format list SteamVR's mutable sRGB images do. With it Turnip keeps
UBWC compression on, and an import that disagreed read compressed data as garbage.

Verified on the AYN Thor (SD 8 Gen 2, Turnip): SteamVR 2.17.10 starts with the DroidDeck HMD active,
the compositor runs driver-direct at about 60 fps, its default environment shows in both eyes through
Cardboard mode, and a pose written to the shared file turns SteamVR's view. Started from the drawer
button through the agent.

### Not done yet

- **Controllers**: the driver adds two Vive-profile hands driven by the session's game pad. It reads
  the absolute-state snapshot the app already publishes in fake input ring 0
  (`FAKE_EVDEV_MEMFD_PATHS`), so the app needs no new plumbing. They are a gaze pointer: low in front
  of each hand, pointing where the head looks. Mapping, right hand: right stick = trackpad, A/R3 =
  trackpad click, RT = trigger, RB = grip, Start = menu, guide = system. Left hand: left stick, X/L3,
  LT, LB, Select. The pad's state is verified in the ring and both hands activate in SteamVR, but the
  dashboard has not been seen yet: either the guide press is taken before it reaches the ring as a
  plain button (Deck mode), or the dashboard's web helper does not render here. Next to chase.
- **Layers**: only the first layer of each frame is drawn (the compositor's scene). Overlays submitted
  separately (some dashboard states) are not composited yet.
- **Timing**: no reprojection, and the presenter blits whatever was submitted last. The chain
  (SteamVR → X11 overlay → gamescope → DroidDeck compositor → lens pass) adds latency. The gamescope
  output is the session's resolution (often 1280x720), which then scales to the panel.
- **Cross-process GPU sync** is approximate (see the semaphore note above).
- **Apps and games**: SteamVR's own ARM64 OpenXR sample, `helloxr -g Vulkan2` (run with the layer
  environment `droiddeck-steamvr start` sets), connects: it sees the system as
  "SteamVR/OpenXR : droiddeck" and creates its eye swapchains. It then leaves cleanly before its first
  frame, right after SteamVR reports no input profile for it. That is the next thing to chase, along
  with controllers. `hellovr_sdl` (OpenGL) needs GLEW, which the runtime lacks. x86/Windows VR games
  would go through FEX/Proton and need vrclient for those architectures: untested.
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
