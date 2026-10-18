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
     the lens). A new frustum mid-run (the glasses' SteamVR field of view) goes out with
     `SetDisplayProjectionRaw` and `VREvent_LensDistortionChanged`;
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
     locally from the host), so GPU waits work as written, a poll period late. An imported binary
     semaphore cannot be bridged that way, and its import is refused. With the semaphores dropped
     outright (the first version) the dashboard showed torn, half-drawn tiles;
   - Turnip on KGSL does not defer a submit that waits on a timeline value nobody has signalled yet:
     `vkQueueSubmit` blocks until it is. SteamVR's client waits on a compositor value that the
     compositor signals only after the client has submitted and said so, which deadlocked every app
     before its first frame (`helloxr`'s swapchain acquire, DXVK's submit thread in a Proton game).
     A submit's wait on an imported shared value that is neither reached nor queued in that process
     gets 3 ms for the other process to reach it, then is dropped. On a counter that has not moved for
     100 ms it is dropped at once: Angry Birds VR waits on two such values a frame, and the grace cost
     its submit thread 6 ms a frame and held it at 60 fps. Keeping these waits stops a Proton game
     before its first frame (Angry Birds VR stayed on SteamVR's loading card). While a Quest
     session's frame socket (`/run/droiddeck/droiddeck-xr-frames`) exists, every wait is kept, as the
     Quest path was built and tested with them;
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
   stand-in, because the dashboard's web helper checks the X server with it. Before every start it
   removes SteamVR's crash marker (`config/vrserver_crash_timestamp.txt`, written when vrserver
   crashes) and clears `driver_droiddeck.blocked_by_safe_mode`: after a crash early in a run SteamVR
   starts in safe mode and blocks every add-on driver, so a single crash left the glasses blank on
   every later launch (`HmdNotFound`). The driver also stops its pose, vsync and presenter threads
   if vrserver exits without deactivating it, since a running `std::thread` destroyed at exit aborts
   the process.

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
Once a headset (VITURE, RayNeo or XREAL glasses) is connected and under DroidDeck's control, the
Steam page shows **Play SteamVR** at the top left, with a **Settings** badge that drops that headset's
settings from the top; before that neither appears. Play SteamVR starts Steam and then SteamVR
(installing it first if needed) once the glasses show a picture, and SteamVR stops when the glasses
are unplugged. **Play Steam** never starts it: while SteamVR runs, Steam keeps its own window in front
of flat games. Through a VITURE Mobile Dock the glasses are only a display: the dock does not pass
their controls or head tracking to the phone, so the Steam page shows neither, and only the session
drawer's glasses page (output and **Game on glasses**) applies.

SteamVR defaults to 2D output: a single eye fills the glasses while head tracking remains active.
Enable **3D (side-by-side)** manually or opt into **3D for VR games** in the glasses controls for stereo. Automatic
switching waits until SteamVR reports that it is running; starting or installing it does not change
the display mode. A saved **3D for VR games** preference is retained.

While SteamVR runs, its side-by-side source takes priority over the glasses' saved picture mode,
including **3DoF virtual screen**. SteamVR already applies head tracking; projecting its combined
eye pair onto a virtual screen would show both images in each eye and apply head rotation again.
The glasses still supply poses to SteamVR. The saved picture mode resumes when SteamVR stops.

With **Game on glasses** off, SteamVR keeps the glasses' picture settings and IMU while Android
mirrors the phone output to the glasses. Turning off the dedicated display must not turn off the
renderer/HMD proximity flag: SteamVR otherwise reports the headset as taken off and enters standby,
freezing the view on both displays. Without a glasses display or an enabled phone VR viewer, SteamVR
uses a single-eye phone preview with the phone's tracking preference. These effective rendering
settings last only while SteamVR runs; the saved Cardboard setting is unchanged.
Verified on the VITURE Pro 2 with Game on glasses off: live glasses IMU, roughly 60 fps beyond
the previous standby point, and user-confirmed updating images and correct head tracking on both
screens. The rendering regression tests cover mirrored/dedicated parity and phone fallback.

A separate freeze occurred after swapping Pro 2 for GT Max and restarting SteamVR in the same
DroidDeck process (2026-10-14, session 52): GT Max board `0x41` kept streaming poses and the HMD
stayed active, but `vrcompositor` reported `vkerror=-4` (`VK_ERROR_DEVICE_LOST`) from 17:18:53.
Restarting the Steam session alone reproduced it (sessions 53/54). Restarting DroidDeck itself
cleared it; session 55 continued rendering with live GT Max tracking for over a minute without
new device-loss errors. This is a recovery workaround, not a confirmed permanent fix; retained
graphics state across restarts is a hypothesis. Frame counters alone do not prove recovery:
the presenter can keep presenting an old SteamVR image while the compositor reports device loss.

### Joy-Con controllers

**Glasses → Tracking → Experimental → Luma Joy-Con tracking** is off by default. Enable
positional tracking first, then this toggle. It uses the Luma Ultra primary stereo camera pair
to locate each hand holding a Joy-Con, with the existing calibrated, capture-time head transform.
Joy-Con IMU orientation, buttons, sticks, battery reporting and haptics remain in use. This mode
also enables Joy-Con takeover for SteamVR even if the ordinary controller preference is off.
Android 12+ and controller IMU support are still required.

Stereo positions and the 6DoF head share the same recentered floor origin. If a hand is hidden,
invalid, or its observation is older than 200 ms, that side blends to the arm model anchored to
the current head position. Reacquisition also blends (180 ms time constant). No usable calibration
or visible palm means arm-model-only positioning. Loss of head tracking beyond 100 ms invalidates
the controllers and releases their inputs. Reconnects, origin changes and Joy-Con recentering discard
old positional corrections. Point the Joy-Cons forward when connecting or recentering; camera
positions do not correct IMU yaw drift. Position extrapolation is disabled in this mode.

The camera observes the gripping **hand**, not the Joy-Con shell. Occluded palm landmarks, crossed
hands, and handedness ambiguity can reduce tracking availability. This is an experimental hand
position proxy with estimated fallback, not marker-based controller tracking. Controller grip offset,
aiming, motion latency, and sustained performance still need physical validation with held Joy-Cons.

Luma Joy-Con tracking and stereo bare-hand controllers are mutually exclusive. Either selected
Luma controller mode pauses phone-camera inference. External VR controllers retain priority.
Disable both Luma controller toggles to return to the upstream Joy-Con/device-camera modes,
which retain their existing fixed head-position anchor. Positional tracking, stereo controllers,
and phone-camera controls are grouped at the bottom of the glasses settings under **Experimental**.

Nintendo Joy-Cons paired over Bluetooth become SteamVR hands while SteamVR runs, and stay an ordinary
game pad otherwise. It needs Android 12 or later (motion sensors on an `InputDevice`) and a kernel whose
`hid-nintendo` driver reports the IMU (Android common kernel `android14-6.1` or newer; older 5.x GKI
builds expose the buttons only). A Joy-Con without motion sensors stays a game pad.
**Controller settings > Joy-Cons as VR controllers** (on by default) turns it off.

`input/JoyConVr.kt` takes a Joy-Con once SteamVR reports that it is running. It reads the Joy-Con's
gyroscope and accelerometer on a thread of its own. A Mahony filter turns them into an orientation,
and the gyro bias is learned while the Joy-Con rests. Each IMU report carries three samples every
10 ms. On the tested kernel they arrive newest first and about four in ten reports repeat the
previous report's newest sample, while the kernel's timestamps run about twice as fast in an uneven
3.3/3.3/13.3 ms pattern. `JoyConReports` collects a report's samples, learns their order from the
repeats, drops the repeated one and times the rest from the report's arrival (report interval divided
by new samples). That cut the gyro's error from 13 to 48 degrees per movement to about 2 to 3.

The accelerometer also measures movement. Linux removes the gyro's offset but not the
accelerometer's (one Joy-Con read 10.08 m/s² lying still, the other 9.51), so `JoyConAccelCalibration`
learns offset and scale by fitting a sphere to the readings of a Joy-Con resting in at least five
different positions; it happens on its own while you play and is saved per Joy-Con. `JoyConMotion`
takes gravity away in the world frame, integrates what is left into a velocity and a displacement,
sets the velocity to zero whenever the Joy-Con is still, and leaks it away quickly once a movement
lasts longer than a second, so the displacement holds for short lifts, pushes and punches and cannot
run away. It also keeps a second velocity that is never zeroed and only leaks over 30 s, the plain
integral of the acceleration, for camera tracking. The pose, displacement, both velocities, buttons,
stick and battery go to a 224-byte seqlocked file, `joycon_shm.h`, at
`/run/droiddeck/droiddeck-joycon`. The game pad no longer sees that Joy-Con;
it gets it back when SteamVR stops, the Joy-Con disconnects or the setting is turned off.

The driver adds a left and a right Index controller (`knuckles`, Valve's Index input profile and
models) when the first Joy-Con appears. The pad hand on that side switches off. A Joy-Con cannot
measure where it is, only how it turns and how it speeds up. Without the camera, Google VR's arm model
places the hand from its orientation and the head, and the measured displacement moves the hand on
top of that before it settles back over 0.6 s (1.5 s while the trigger or grip is held, at most
35 cm away), so a quick lift or punch shows but a slow sideways drift does not. With camera hand
tracking (below) the camera places the hand instead. Its pointing direction is the Index controller's
aim pose. Its heading is matched to the head when it connects. Holding Home (right) or Capture
(left) for 0.8 s matches it again, with a short buzz; point the Joy-Con where you look first. A short
press is the system button. A hand that sends nothing for 500 ms disconnects.

| Index        | Left Joy-Con     | Right Joy-Con  |
|--------------|------------------|----------------|
| Trigger      | ZL               | ZR             |
| Grip         | L, SL, SR        | R, SL, SR      |
| A            | down, right      | A, B           |
| B            | up, left, minus  | X, Y, plus     |
| Thumbstick   | stick, click     | stick, click   |
| System       | Capture (press)  | Home (press)   |

The fingers follow the buttons on a simulated hand: the index finger curls with the trigger, the
other three with the grip, the thumb when it is on the stick or a face button. SteamVR's haptic
events go to that hand's Joy-Con through `@droiddeck-rumble` (slots `0x100` and `0x101`) and respect
the rumble setting.

Outside SteamVR two Joy-Cons are combined into one player: left stick, D-pad, L, ZL, minus and Capture
from the left one, the face buttons, right stick, R, ZR, plus and Home from the right. Face buttons
keep their positions (Nintendo X is the top button, Y on Xbox). SL and SR are shoulder buttons on a lone
Joy-Con and are ignored while two are combined. A combined pair splits rumble the way a pad does,
strong to the left and weak to the right. A lone Joy-Con plays the stronger of the two.

#### Camera hand tracking

The device's camera can watch the player so each Joy-Con hand sits where the real hand is, including
lifting, lowering, reaching and moving sideways. With no controller connected it tracks bare hands
instead (below). It needs Android 12 or later. The first time a headset connects, DroidDeck asks whether to use the camera. On a device
with cameras on both sides it offers the back one (usually sharper and wider) or the front one; with
cameras on one side only it uses those without asking. Android's camera permission is requested
right after. **Headset sheet > Head tracking > Hand tracking** turns it on or off, switches the
camera, asks for the permission again and opens the placement check.

When camera tracking is on, **Play SteamVR** first shows the placement check: the live picture with
the detected body drawn on it, what to fix, and **Skip**. It reads the device's gravity sensor and
asks to stand a device up when it lies flat face up or face down, or to stand it more upright when
the camera points more than 45 degrees up or down. Then it asks to step into view, to step back or
come closer (the shoulders should take 12 to 26 percent of the picture's width, about 0.75 to 1.6 m
from a 90 degree camera), to step back when an elbow or wrist is near the edge of the picture, and
to hold both hands in view. Once
everything has been fine for 1.2 s SteamVR starts on its own. With the back camera the screen faces
away from the player, so the advice is also spoken aloud.

`handtracking/HandCamera.kt` runs while SteamVR runs and either a Joy-Con is taken or no game
controller is connected at all. It picks the widest
camera on the chosen side (up to 100 degrees, zooming a logical camera out when it can), holds it at
its fastest fixed frame rate up to 30 per second so exposures stay short and moving hands do not
blur, takes 960x720 frames from CameraX, turns them upright by gravity, and runs MediaPipe's Pose
Landmarker (Lite) on the CPU in video mode, which follows the body from frame to frame and smooths it,
15 times a second (10 and then 6 when the device reports it is getting hot). The pose model's depth
cannot tell a hand at the chest from one reaching out (it read about 25 cm in front of the shoulders
for both), so each hand is measured on its own: `HandModel` runs MediaPipe's Hand Landmarker on a
square around the hand. To find a hand it looks for up to two hands in a square around the pose's
wrist and keeps the one whose wrist is nearest, taking turns between a square 4 shoulder widths across
centred on the wrist and one 2.5 shoulder widths across moved toward the fingers. The palm detector
misses many hands in a tight square: with the player about 0.65 m away the 2.5-wide square found 73
percent of the hands in view (a 1.4-wide one 49, the whole picture 68), and with the player lying close
to the camera, where hands look large, the wide square found 77 percent of the hands the tracker had
seen and the two in turn 82 (the 2.5-wide one alone 54). After that it follows the hand in a square 2.8
hands across, and finds it again in the same frame when it is lost. A hand whose wrist is nearer the other arm's wrist than its own is dropped.
The hand's 21 landmarks come in metres and in pixels, so a least-squares fit gives the hand's
distance from the size it appears (`BodyPose.handFix`); the middle of the palm is the point used.
The head is fitted the same way from the pose model's face landmarks. Each hand is given relative to
the eyes in a frame levelled by gravity, along with the face's heading from the nose and ears. When
the hand model misses a hand the pose still sees, the hand keeps its last distance for half a second
and is marked as estimated. The frame's exposure time, moved onto the monotonic clock, the hands, each hand-model hand's 21
landmarks relative to the middle of its palm (in metres, in the same levelled frame), the face heading
and whether bare hands are wanted go to a 568-byte seqlocked file, `handcam_shm.h`, at
`/run/droiddeck/droiddeck-handcam`.
No picture is kept or sent.

The driver turns the camera's frame to SteamVR's by comparing the face's heading with the headset's
heading at the same moment, averaged over about 3 s and restarted when they disagree for half a
second (after a recenter). Each hand then runs a Kalman filter (`HandKalman` in `hand_motion.h`) with
position and velocity on each axis. The Joy-Con's plain velocity integral moves it forward between
camera frames; its reports are timed 45 ms earlier than they arrive, which lines their movement up
with the camera's. The Joy-Con is trusted less while it moves hard, since a small misalignment then
turns into a large error. The filter's speed fades with a 0.3 s time constant (0.1 s once the camera
has not seen the hand for 0.2 s, since a small tilt error then pushes the hand steadily), and while the Joy-Con
has been still for 0.1 s (turning under 0.3 rad/s and accelerating under 0.6 m/s²) it is told the hand
is not moving, so a hand the camera loses settles where it was instead of sliding away, and a resting
hand does not shake (on a recording, 3.6 to 4.3 cm of shake fell to 1.0 to 1.5 cm). A camera sighting
is applied at the moment the picture was taken, 60 to 140 ms in the past, and the Joy-Con movement
since then is replayed on top, so the hand is where it is now, not where it was. A hand-model sighting
counts as about 3 cm of camera noise, an estimated one as 6 cm.
One sighting far from the filter (the model swapping left and right) is ignored unless three in a row
agree. When the camera has not seen a hand for 0.75 s, the arm model takes over from where the hand
was, and changes between the two blend over a quarter of a second.

#### Bare hands

With camera tracking on and no game controller or Joy-Con connected (checked again whenever an input
device comes or goes), the app sets a flag in the camera file and the driver gives SteamVR two hands
from the camera alone, the way a Quest's hand tracking does; the pad hands switch off. Each hand is
still an Index controller to SteamVR, so games' Index bindings and the dashboard work, but its render
model becomes a white glove with a light grey band at the wrist, like the plain hands a Quest draws
(`{droiddeck}droiddeck_hand_left` and `_right`, built at build time by `tools/vr/make_hand_models.cpp`
from the same skeleton). The glove is a smooth surface: tapered capsules along each finger bone, a
domed palm and the band are blended into one distance field and meshed with surface nets (a 2.6 mm
grid, up to about 10,000 vertices a part). SteamVR draws render models almost unlit, so the shading
is baked in: ambient occlusion sampled from the distance field, and a soft light on the back of the
hand, pick a column of a 64x64 texture whose rows run from the glove's white to the band's grey. It
has two parts with two shapes each: the palm with the middle, ring and little fingers (open, or
curled into a fist while `/input/grip/touch` is on, at half closed), and the thumb with the index
finger (open, or pinching while `/input/trigger/click` is on). The thumb part carries a copy of the
palm 0.3 mm inside the other part's, so the two blend without a seam. SteamVR only routes a part to
its input when the part also names a legacy button (`controller_button`, as the Index model's buttons
do; vrserver then logs `inputRouting=1` for it), and a hand whose laser points at the dashboard sends
its trigger to the dashboard, so that hand clicks without drawing the pinch. Its pointer and grip poses are the Index
controller's, so the laser leaves along the fingers. Games that draw hands from the
skeleton get the camera's finger curls. Joy-Cons connected later take over; their hands go back to the
Index model.

| Gesture | SteamVR input |
| --- | --- |
| Thumb and index finger together | Trigger: it starts pulling 6 cm apart, clicks under 3.5 cm and lets go over 4.5 cm |
| Middle, ring and little fingers closed | Grip, clicking when fully closed |
| Thumb to middle finger | B |
| Thumb to ring finger | A |
| Right palm toward the face, then pinch | System (the SteamVR dashboard) |
| Left palm toward the face, then pinch | B (most games' menu) |

A gesture has to hold for 50 ms to press and be gone for 100 ms to release, and a palm has to face the
headset for 0.3 s before its pinch becomes the menu. A and B only count with the hand open, take the
fingertips within 2.5 cm (released over 3.5 cm), and while the palm faces the headset the trigger stays
released. The distances were checked on 504 photos from the public HaGRID gesture set (50 each of
eleven gestures) through the same hand model: MediaPipe puts touching thumb and index tips a median
3.4 cm apart, so a 2.5 cm pinch caught only 27 percent of the "ok" signs; at 3.5 cm it catches 51
percent while open hands, pointing, "stop", "four", "three" and thumbs-up misfire in 4 percent or
fewer. A fist pinches in a quarter of them, which games read as grabbing with the trigger held. The
grip read closed for 92 percent of fists and 96 percent of pointing hands and for none of the open
hands.

`CameraHand` in `hand_pose.h` does the work. Each camera frame's landmarks are turned by the camera's
yaw. The finger curls come from each finger's joint angles, looked up in a table of the skeleton's own
angles. The skeleton's palm at those curls (wrist and four knuckles) is fitted to the camera's with
Horn's closed-form quaternion method, and the root is placed so the skeleton's palm lands on the palm
position. Only sightings with hand-model landmarks count; the pose-only estimates are left out. The
position goes through a 1€ filter (Casiez, Roussel and Vogel, 2012): a low-pass at 1 Hz while the
hand is still that opens up the further and faster the hand gets ahead of it. On a recording of bare hands it cut
the wobble of a still hand from the 9 to 11 mm the Joy-Con Kalman filter left to 2.5 to 3 mm, and the
largest jump between frames from 10 to 17 cm to about 1.3 cm. The turn goes through the same kind of
filter (1 Hz when still, 0.2 Hz more per radian per second), and the curls follow within 80 ms. A fit
more than 2 cm off is skipped, and a fit that turns the hand more than 45 degrees at once is held back
until the next one agrees within 25 degrees (the hand model now and then flips a hand around; the
largest turn between frames fell from 14 to 7.5 degrees). When the camera has not seen a hand's landmarks for half a second,
the hand reports itself out of range and lets go of every input; it reappears wherever it is seen
next. There is no stick or trackpad, so moving around needs a game's own hand tracking or pointing
to teleport.

On MediaPipe's own example pictures (a raised index finger, a thumbs-up) the right-hand skeleton fits
the palm within 7 mm, the curls read as the pose looks, and the palm direction points at the camera
when the palm faces it.

Depth from one camera is approximate: the model expects one person, up to about 4 m away, with the
head in view.

Joy-Con orientation and buttons were tested on a REDMAGIC tablet, and the movement code was tuned on
recordings from its Joy-Cons. Camera tracking was recorded on the same tablet with the front camera:
the camera holding 30 frames per second and video mode took the hands' frame-to-frame jitter from
about 5 cm to 1 to 2 cm; the hand model and the filter were then tuned on that recording. For a
diagnosis, an empty file named `handcam-record` in the app's files folder makes the next sessions save
up to 6 minutes of small camera pictures with every landmark and timing to the app's cache
(`handcam-rec/`), and the driver writes its camera, Joy-Con and filter values to
`/run/droiddeck/droiddeck-handtrace.csv` while they run. `tools/vr/build.sh` runs `test_joycon` (arm
model, aim pose, hand skeleton, movement, the filter's latency, noise and outlier handling, yaw,
both file layouts, a lost hand settling, the palm fit, curls, gestures, bare camera hands, the turn
filter and the hand render model). `JoyConTest`,
`JoyConOrientationTest`, `JoyConMotionTest`, `JoyConShmTest`, `JoyConPadTest`, `RumbleComponentTest`,
`BodyPoseTest`, `TrackingCamerasTest`, `PlacementAdviceTest`, `HandCameraShmTest`, `HandTrackingTest`
and `HandCameraPromptTest` cover the app side.

### Device-loss recovery

During a SteamVR session, DroidDeck reads new `vrcompositor.txt` output every two seconds and detects
`VK_ERROR_DEVICE_LOST` or `vkerror=-4`. Existing errors at session start, ordinary acquire timeouts,
and unrelated failures do not trigger recovery. Reads are bounded to the newest 128 KiB and handle
log replacement, truncation, and partial lines.

A detected fault shows an eight-second cancellable countdown while the session is foreground.
Touches outside the prompt do not dismiss it; the user can explicitly choose Keep open.
Recovery stops the guest, gives teardown and diagnostic collection up to 15 seconds, then uses a
private `:vr_recovery` activity to kill only DroidDeck's main process and reopen SteamVR. That process
does not initialize the glasses SDK or guest runtime. It recreates the native graphics context; an
Activity recreation or SteamVR-only restart does not. Running games close and unsaved progress cannot
be restored. A persisted ten-minute cooldown disables another automatic attempt, while still
offering a manual restart. Session events record detection and recovery requests. This mitigates
the freeze; it does not fix the underlying Vulkan device loss.

Validated on the phone with a clearly marked `SIMULATED_RECOVERY_TEST: VK_ERROR_DEVICE_LOST`
log entry in session 58. Detection and the eight-second countdown led to a new main-process PID
and session 59 automatically reached READY and requested SteamVR. The
cooldown timestamp survived the process restart. This was a recovery-flow test, not a new GPU
failure. Subsequent user feedback and `vrserver.txt` showed that SteamVR had blocked `driver_droiddeck`
after a previous crash: the server ran, but reported `HmdNotFound` and never produced VR output.
Recovery now clears only `driver_droiddeck.blocked_by_safe_mode`, atomically after stopping the old
process and before launching the new session. It preserves global safe-mode preferences and other
drivers' blocks. A running server or resumed sensor stream alone is not proof of successful recovery.
The follow-up marked test in session 63 exercised the manual retry during the cooldown. Recovery
logged the driver unblock, restarted into session 64, and SteamVR recognized the DroidDeck HMD.
Fresh compositor output and a 3840x1080 `DroidDeck Cardboard VR` presenter appeared, with subsequent
display intervals at 56–59 fps. The user confirmed the GT Max VR view and tracking work after this
recovery. The 19 SteamVR unit tests and APK runtime-input verification passed.
The glasses/SteamVR suite includes stale/rotated/split log handling, countdown,
cancellation, foreground-only behavior, and cooldown checks.

Stereo also requires a physical side-by-side display mode. On the tested Astra 2
firmware the Pro XR command succeeds but DisplayPort falls back to 640×480;
direct-connection stereo remains unresolved. DroidDeck remembers such a fallback for that glasses
model and stops asking for 3D on its own; **Retry** in the glasses settings tries again. See the
[Astra/VITURE investigation](development/astra-viture-stereo.md) for measured
results, app recovery behavior, and the unverified powered-adapter design.

### Not done yet

- **Controllers**: the driver adds two Vive-profile hands driven by the session's game pad. It reads
  the absolute-state snapshot the app already publishes in fake input ring 0
  (`FAKE_EVDEV_MEMFD_PATHS`), so the app needs no new plumbing. They rest low in front of each side and
  point where the head looks. Each stick moves its hand sideways (40 cm), up (50 cm) and down (30 cm)
  and turns it as if from an elbow 45 cm behind, so pointers and aim follow it too; the hand eases
  back when the stick is let go. The d-pad's up and down move both hands in and out; while a hand's
  trigger or grip is held they move only that hand (a slingshot or bow pulled back), and it eases back
  on release. Clicking the left stick hands the sticks to the game as trackpads (walking, turning,
  teleport) and back. Mapping, right hand: A/R3 = trackpad click, RT = trigger, RB = grip, Start = menu, guide =
  system. Left hand: X, LT, LB, Select. Both hands activate in SteamVR and their laser shows on the
  dashboard; guide, Start and A presses show up in the ring the driver reads. They are drawn as Index
  controllers and switch off while another driver's controller is connected. A Joy-Con hand replaces
  the pad hand on its side, and bare camera hands replace both while no controller is connected
  (above).
- **Layers**: only the first layer of each frame is drawn. SteamVR's compositor composites the
  dashboard into it, so this has not mattered yet.
- **Timing**: no reprojection. The presenter blits the frame submitted last once `vrcompositor`'s GPU
  work for it is done: the layer fences that process's submits and counts issued and finished ones in
  `present-sync` beside the `active` marker, and the presenter sleeps on the count (a futex). Without
  the file it falls back to a guard delay after the frame arrived (`DROIDDECK_VR_PRESENT_GUARD_MS`,
  4 ms). It hands its own picture to Wayland only once its blit is done, because KGSL does not order
  the DroidDeck compositor's copy after it. The chain is SteamVR → presenter → DroidDeck compositor →
  lens pass.
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
