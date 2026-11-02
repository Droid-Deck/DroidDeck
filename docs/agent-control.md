# Agent control

`tools/droiddeckctl` drives DroidDeck on a real device over ADB, for agents and scripts that test
the app end to end: start and stop sessions, launch and quit Steam games, wait for a game to have
focus, send input, inspect the app's UI and gamescope's windows, run commands inside the session,
and collect logs, screenshots and recordings. `tools/droiddeck-scenario` repeats a scripted test
and reports every run.

## How it works

- **The provider** `content://com.droiddeck.launcher.agent` (`agent/AgentBridgeProvider.kt`)
  answers each command with JSON. It and the session-start Activity (`AgentStartActivity`) are in
  every build and require `android.permission.DUMP`, held by the ADB shell and privileged or
  explicitly granted callers. The provider checks the calling permission before decoding requests.
  A build shipped under another package (`tools/release/variants.txt`) answers at
  `content://<package>.agent`: set `DROIDDECK_PACKAGE` to that package for `droiddeckctl`.
- **The guest agent** `droiddeck-agent` runs inside every session (started by
  `droiddeck-session`). It runs commands with the session's own environment, publishes gamescope's
  focus, and talks to the Steam client's DevTools, which only accepts guest processes. The app and
  the agent exchange request and answer files under the session root (`files/session/agent`).
- **The UI dump** reads the app's Compose semantics from inside the app (`agent/AgentUi.kt`), so it
  works on every display and window. UI Automator, by contrast, dumps whichever window has focus,
  which on a dual-screen device is often another app's.

## Access

Player controls and observations work over authorized ADB on any build: `state`, `start`, `stop`,
`resume`, `wait`, `launch`, `quit`, `focus`, `input`, `ui`, `logs`, `screenshot`, `record`, `pacing`,
`displays`, `wake`, `glasses` (except `glasses raw`), and `access`.

**Setup > Diagnostics > Debugging tools** is off by default in every build, including debug builds.
It enables `run`, `guest`, `cdp`, all `env` operations, `override`, `prefs`, and `glasses raw`. The
toggle switches directly without a confirmation dialog. The bridge refuses to change it through `ui`
or preference writes. The existing preference key, `setting-agent-commands` test tag, JSON command
names, and `AGENT_COMMANDS_DISABLED` error code are unchanged. Refusals exit 7; `droiddeckctl access`
reports whether commands are enabled.

Disabling the toggle clears queued and persistent agent environment settings and installed binary
overrides. Disabled settings cannot apply at the next session start. Already running commands and
effects already applied to a session are not undone; stop and restart that session to discard them.

Authorized command execution runs inside the runtime containing Steam's saved login. CDP evaluates
JavaScript in the authenticated Steam client, and preferences can contain private values. These
capabilities remain unrestricted when enabled: the toggle is an explicit opt-in, not credential
isolation or a sandbox against the authorized caller. Main already supported arbitrary guest
programs through `run`; restricting credential access would require addressing that route too.
ADB access remains a trust boundary, and ADB input can operate the device's own toggle.

The helper exchanges private files with the app and adds no network listener. The guarded Steam
DevTools port is bound to loopback and accepts only peers with a fresh private registration for a
live socket descriptor, matching socket inode, destination port, and address family. The existing
x86_64 Decky loader exception remains: that mode does not enable the guest-only acceptance guard.

Requests are limited to 256 KiB of encoded JSON. Guest responses and DevTools messages are limited
to 1 MiB; provider replies above 384 Ki characters return `RESPONSE_TOO_LARGE` to avoid overflowing
Binder. Command output retains at most 256 KiB per stream while continuing to drain both streams.
Timeouts must be finite, positive, and no greater than 600 seconds, and terminate the command's
process group. Override installation refuses path traversal and symbolic links.

Automatic process logs record environment keys and argument counts rather than values or full
argument lists. UI nodes marked as passwords redact text, editable text, descriptions, and state
text. This does not redact arbitrary authorized command results, CDP data, preferences, or screen
captures. Scenario directories are owner-only and JSON reports are written with mode 0600. Review
artifacts before sharing; a live `logs` pull copies the current folder without a fresh export scrub.

## Device selection and output

The CLI resolves one authorized device using `ADB_SERIAL` or `ANDROID_SERIAL` when set. Otherwise it
de-duplicates transports that report the same device serial and asks for an explicit serial if
multiple devices remain. Pass `--serial` before the command to choose directly; set `ADB` (or pass
`--adb`) to choose an adb executable. `tools/deploy_local.sh` uses the same resolver.

Every command writes one JSON object to stdout and its resolved serial to stderr. Exit codes: 0
success, 2 invalid or rejected command, 3 ADB or device error, 4 session error, 5 timeout, 6
artifact or file error, 7 debugging tools disabled.

## Commands

```sh
tools/droiddeckctl state
tools/droiddeckctl start steam --wait
tools/droiddeckctl start steam --wait --reuse
tools/droiddeckctl start steam --steamvr --wait
tools/droiddeckctl start desktop
tools/droiddeckctl run /usr/bin/foo -- arg1 arg2
tools/droiddeckctl wait ready|idle|failed
tools/droiddeckctl stop
tools/droiddeckctl resume

tools/droiddeckctl launch 489830 --wait
tools/droiddeckctl wait focused 489830
tools/droiddeckctl wait game
tools/droiddeckctl quit 489830
tools/droiddeckctl focus

tools/droiddeckctl input key ENTER
tools/droiddeckctl input text 'hello world'
tools/droiddeckctl input tap 640 360
tools/droiddeckctl input swipe 100 600 100 100 --duration 400
tools/droiddeckctl input pad a
tools/droiddeckctl input stick left 0 -1 --hold 500
tools/droiddeckctl input trigger right 1.0

tools/droiddeckctl ui dump
tools/droiddeckctl ui click rail-steam
tools/droiddeckctl ui click --match "Play"
tools/droiddeckctl ui set-text some-field --text abc

tools/droiddeckctl guest -- xprop -root GAMESCOPE_FOCUSED_APP
tools/droiddeckctl cdp targets
tools/droiddeckctl cdp eval 'SteamClient.Apps.GetMyRunningApps?.()'
tools/droiddeckctl env set DXVK_HUD=fps
tools/droiddeckctl env list
tools/droiddeckctl env clear [next|persistent|all]
tools/droiddeckctl override gamescope ./gamescope
tools/droiddeckctl override libblfastpath.so ./libblfastpath.so
tools/droiddeckctl proot-profile enable --slow-us 1000
tools/droiddeckctl proot-profile disable
tools/droiddeckctl proot-profile report ./session.log --sort wall
tools/droiddeckctl prefs get session
tools/droiddeckctl prefs set session some_key true --type bool

tools/droiddeckctl logs latest ./session-artifacts
tools/droiddeckctl screenshot ./screen.png --display 4
tools/droiddeckctl xr-frame ./xr-capture
tools/droiddeckctl record ./clip.mp4 --seconds 8 --display 0
tools/droiddeckctl pacing --seconds 30
tools/droiddeckctl displays
tools/droiddeckctl wake

tools/droiddeckctl glasses status
tools/droiddeckctl glasses set refreshHz 120
tools/droiddeckctl glasses set 3d on
tools/droiddeckctl glasses simulate --seconds 6 --yaw 15
tools/droiddeckctl glasses stop-simulate
tools/droiddeckctl glasses mode 0x34
tools/droiddeckctl glasses raw 0x0007
```

Glasses status reports `deviceMode` from the MCU separately from `sbs`, which
requires a physical stereo display mode. `switching3d` marks the bounded
confirmation window. A successful mode-command reply alone does not confirm 3D.
`badFrames` counts reports and IMU packets dropped for a bad checksum.

`xr-frame` captures both app eye textures while SteamVR is open, saving `left.png`,
`right.png` and `capture.json` with the matching source frame, projection poses,
FOV and dimensions. It works over wired or wireless authorized ADB.
Newer builds also include `source.png`, the incoming stereo texture before the
Android eye draw, and sRGB attachment/write state in the metadata for color debugging.
Source PNGs use the incoming frame's color space and eye PNGs use sRGB. Transfers use Android's
gzip utility to avoid sending full uncompressed images over wireless ADB.
A unique request ID prevents stale images from passing as a fresh capture; no valid XR
layer produces an explicit error. Captures exclude Meta system overlays and
the headset compositor's final reprojection. GPU readback runs only on request
and can briefly affect frame timing. Remote files are overwritten each time.

A sleeping device suspends the session and hides the app's windows from `ui dump`; `wake` first.

`pacing` reads SurfaceFlinger's timestats for the game's layer (the session's SurfaceView when the
compositor has no layer of its own) and reports the frames that reached the panel, `fpsOnScreen`,
the present-to-present histogram and the share of intervals off the usual cadence. A game's own fps
counter counts frames handed over, not frames shown, so it can read 60 while one frame in four
stays on screen for two refreshes.

`input` needs a session that has drawn its first frame. Pad input goes through the session screen's
virtual pad, the same one the on-screen controls drive, so games that ignore touch still respond.
`launch`, `quit` and `cdp` need a Steam session; `focus` and `wait focused` need gamescope (every
mode except the desktop).

## State (schema 2)

`state` returns `schema`, `build`, `appVersion`, `runtime {installed, version}`,
`agent {guest, guestFocus, commands}` and `session`:

- `id`, `phase`, `running`, `mode`, `program`, `steamUi`, `steamUrl`, `steamVr`, `suspended`, `pip`,
  `firstFrame`, `output` [w, h], `refreshHz`, `lastTransitionAt`, `guestPid`, `installing`
- `logDir`, `eventsFile`, `artifactsAvailable`, `artifactsComplete`, `failure {code, message, status}`
- `focus`: gamescope's focus as the guest agent last published it, or null:
  `focusedApp`, `focusedWindow`, `focusableApps`, `baselayerAppIds`, `seq`, `t`.

Phases: `IDLE`, `PREPARING`, `INSTALLING_RUNTIME`, `STARTING_COMPOSITOR`, `STARTING_GUEST`,
`STARTING_STEAM`, `READY`, `SUSPENDED`, `STOPPING`, `FAILED`. Schema 2 only added `agent` and
`session.focus`; every schema 1 field is unchanged.

A game the player can see is `focusedApp == appId` **and** `appId in focusableApps`.
`GAMESCOPE_FOCUSABLE_WINDOWS` is what the Steam client orders the base layer from: a game missing
there stays behind the client's loading screen however healthy its window looks
(docs/development/steam-focus-debugging.md). `focus` returns the full snapshot, including each
server's windows with map state, override-redirect, app id, `WM_STATE`, pid, class, opacity and
`_NET_WM_STATE`.

## Hand tracking diagnostics

With debugging tools enabled, the glasses provider accepts `spatial-hand-options` with optional
boolean `lite`, `halfRate`, `parallel`, `adaptive`, and `scheduled` fields. These are temporary
tracking-session controls and do not change the CPU/GPU delegate. `parallel` selects concurrent
camera inference on CPU; GPU remains serial on its owning thread. `scheduled` enables persistent
one-hand and two-hand detector banks on CPU, using the one-hand bank after stable agreement
and periodically searching for another hand. GPU retains ordinary two-hand inference.
`adaptive` lets CPU input rise from 500 permille to 600 or 800 only after sustained processing
and observation-age headroom. Slow processing lowers the rate, and tracking loss resets it.
Explicit `halfRate` selects manual input and disables adaptation; if both fields are supplied,
`adaptive` is applied afterward. This allows restoring the manual preference and automatic mode
in one request. CPU, full model, parallel inference, scheduled discovery, and automatic input
starting at 500 permille are the defaults.

`handModel`, `handCpuParallel`, `handAdaptiveInput`, and `handScheduledTracking` identify the live
selection. `handInputRatePermille` reports the current requested frame fraction, while
`cameraCopyPermille` records the fraction used for the latest reported camera processing sample.
At a 25 Hz source, 500/600/800 correspond to input limits of about 12.5/15/20 Hz, not completed
tracking throughput. Calibration and explicit capture use 1000. `cameraFrameDivisor` remains
2 for 500, 1 for 1000, and 0 for intermediate fractions; use `cameraCopyPermille` for rate calculations.
`cameraHz` still describes the source cameras; `rateLimitedFrames` counts deliberate skips and
`dropped` counts eligible pairs replaced while processing is busy. These controls do not change
the glasses' sensor rate or native head tracking.

`spatial-hand-capture`, with debugging tools enabled, requests one local stereo pair in
`files/spatial/captures`. Use only for an explicitly requested diagnostic capture; it includes
the wearer's surroundings. It does not upload images.

`spatial-rgb-capture` requests one local JPEG from the Luma Ultra's separate UVC RGB camera.
It requires Android camera permission, USB permission for that camera, and debugging tools.
If USB permission is requested, approve it and explicitly repeat the capture request; permission
approval alone does not save an image. Poll `spatial-rgb-status` for completion and the local
`files/spatial/rgb-captures` path. The isolated worker stops the stream after the requested
diagnostic window or a timeout, then exits. The default `transport: "sdk"` uses the vendor's
fixed 1920×1080/30-fps stream. With the optional UVC build present, `transport: "uvc"` selects
the independent eight-buffer transport; `width`, `height`, `fps`, and `durationMs` default to
640, 480, 30, and 5000. Duration is bounded to 1000–30000 ms and camera modes must be supported
by the device. This remains a manual debugging command, not a source of live controller positions.
Both paths save `usb.log` and `usb-descriptors.bin`, including when acquisition fails.
Direct UVC reports `snapshotAcquired` separately from `streamHealthy`. Health requires the
full requested observation window, at least 80% of requested FPS, and no frame gap exceeding
the larger of 250 ms or three frame periods. `observedFps`, `maxFrameGapMs`, and
`tailFrameAgeMs` include silence at the end of the window. Status remains busy with
`phase: "finishing"` until the capture worker exits, so a new request cannot race its teardown.
`receiptNs` is the host callback time, not camera exposure time; the SDK timestamp can be zero.
This diagnostic does not supply controller positions or replace the stereo tracking stream.

`spatial-hand-benchmark` takes a numeric `capture` directory name from the app's existing
`files/spatial/captures` folder. It replays that saved stereo pair on CPU with full-model two-hand
inference, scheduled full, scheduled lite, then full-model two-hand inference again, all with
concurrent cameras. It clears live hand input during the comparison and never captures new images.
Poll `glasses.spatial.handBenchmark` for completion, timing, and geometric results. Every replay
row reports `parallel` and `scheduled`. `wristOnly` identifies the selected controller mode: bare-hand
replay estimates `hands`, while Joy-Con replay estimates `joyCons` from wrist 0. Both report
`wrists` after stereo geometry checks. Image preprocessing is raw grayscale replicated into RGB;
lens correction applies once to landmark coordinates before triangulation. `wrists` reports landmark 0 after the existing stereo depth
and reprojection checks, with the same unambiguous handedness pairing. Live Joy-Con tracking
also uses wrist 0 directly, with motion and freshness checks; its `positionEstimator` is
`joycon-wrist`. Samples identify the selected bank as `inferenceHandLimit`
(1 or 2). Timings exclude model construction. Saved-pair results are not live accuracy,
second-hand discovery delay, game performance, or motion-to-display latency measurements.

The live baseline is Full palm + Full landmarks. `handImagePreprocessing` is `raw-grayscale-rgb`;
`handLensCorrection` reports `pinhole-points-only`, `fisheye-points-only`, or `none`.
`steadyHandAim` is true only when bare hands and the optional shoulder-based aim setting are active.

`handTracking` includes `leftRawHands`/`rightRawHands` before handedness-confidence filtering,
`previousLeftRawHands`/`previousRightRawHands` from the previous inference on those detectors
(-1 after detector creation), and `leftInferenceMs`/`rightInferenceMs`. Existing
`leftCameraHands`/`rightCameraHands` count confident handedness results. Compare camera timing
against its previous raw count: the stock two-hand graph may repeat palm search when fewer
than two hand regions were tracked. `inferenceHandLimit` identifies the bank that produced these
counts; previous counts belong to that same persistent bank, not necessarily the preceding camera
frame. These counts approximate graph conditions; they do not directly expose internal graph
execution. Replay samples also include raw counts and camera timings. Accepted palms still
require stereo depth, while pinch and grip validity now use agreement between image-space
finger estimates from both cameras. Palm acceptance alone does not establish valid gesture input.

`handTracking.experimental` is always true. `observationTimeNs` identifies the processed camera
observation; `observationAgeMs` is its age when diagnostics were published. Per-side `left` and
`right` records expose `outcome`, `reconstructedPalmLandmarks`, raw trigger/grip estimates,
geometry validity, and final input validity. `observations` contains connection-lifetime
`processedPairs`, `inferencePairs`, and independent left/right acceptance, rejection-cause,
tracking-loss, and gesture-loss counters. Each processed camera sequence is counted once;
repeated status polling does not inflate these counters. Setup/off/clock/head failures count
as processed pairs but not completed inference pairs. Skipped callbacks are not fabricated as
observations. Sample deltas over a controlled hand-visible interval and inspect rejection
causes before changing thresholds; these counters do not prove external latency or accuracy.

`spatial.calibrationSource` is `device`, `luma-ultra-base`, or `none`.
`calibrationVerified` indicates an individual distance-validated profile; `calibrated` indicates
geometry is available for experimental tracking, including the bundled model base. A shared base
does not claim validation on every Luma Ultra. Matching local profiles take precedence.

`glasses set joyConTracking true` enables experimental Luma stereo-assisted Joy-Cons after
positional tracking is enabled. It defaults off, excludes bare-hand controllers, and appears in
both saved settings and spatial diagnostics. Driver logs identify each hand's transition between
`stereo hand` and `6DoF arm estimate`; camera positions are hand proxies, while Joy-Con sensors and
buttons supply orientation and input.

The shared-memory channel is protocol **4**, still 176 bytes. Snapshot flag bit 2 selects Luma
Joy-Con tracking; bit 1 selects bare-hand controllers. Its word at byte offset 36 is
`input_epochs`: four unsigned bytes in left-trigger, left-grip, right-trigger, right-grip order.
Each byte increments modulo 256 when that gesture becomes ineligible. Inspect these counters
when a loss may have happened between two reader samples: a later valid pose does not erase
the intervening loss. Versions 1 through 3 are rejected, so a decoder, app, and driver must agree
on version 4. Hand observation age remains limited to 200 ms, head age to 100 ms. For bare-hand
controllers, a gap beyond
200 ms from the driver's last accepted observation releases input state even when the next
read contains a fresh replacement. Duplicate observations cannot rearm an input; recovery
requires a newer valid released gesture before a subsequent press.

Driver lines containing `left optical input` or `right optical input` report state changes,
changed connection/origin/loss counters, and changed input API errors, rather than every frame.
`pose` is the displayed pose's validity and can remain true during its short held-pose window;
`input` is the current observation's eligibility for input processing. Read `flags` for independent
gesture validity. `raw` contains trigger strength/grip state; `out` contains submitted trigger
strength/click/grip; `armed` and `epochs` are trigger/grip pairs. `error` is ordered **grip, trigger
click, trigger scalar**, using OpenVR's `EVRInputError` codes (0 means `VRInputError_None`).
The line also includes `ageMs`, `connection`, and `origin`. `controller inputs cleared: error=...`
records neutralization on mode changes, disconnect, or deactivation; a nonzero value is the last
failed component update in that clearing pass.

The driver publishes these input updates before the controller pose, including invalid poses,
and clears components before deactivation. A zero API error establishes successful submission,
not delivery of a dashboard mouse-up event. The installed Vive compositor binding maps trigger
to dashboard `LeftClick` (0.65 activation, 0.60 release) and grip to `Back`. Its grab-handle
JavaScript attaches to the initiating hand on mouse-down and clears that attachment on mouse-up;
that component has no tracking-invalid cleanup. A stuck dashboard drag therefore needs the
driver submission and actual dashboard behavior checked separately. Do not synthesize a press
or a valid pose as a recovery shortcut.

Controller display smoothing is independent of these gesture logs: there is no stationary
dead zone. Poses converge with one 15 ms correction and bounded receipt-relative translation
prediction (80 ms / 2 cm). A missed observation holds the last pose for up to 350 ms from capture;
input freshness remains limited to 200 ms, and a detected miss releases inputs immediately.
Neither stabilization nor prediction refreshes camera timestamps or creates gesture input.

## Events

Each session writes `events.jsonl` beside its other artifacts: the lifecycle (`session.created`,
`compositor.started`, `guest.exited`, ...) plus `agent.ready` (the guest agent is up),
`focus.changed` (the focused app, focusable apps or base-layer order changed),
`keyboard.changed` (Steam's on-screen keyboard opened or closed) and `agent.launch_requested`.

## UI test tags

Compose test tags are exposed as resource ids (`ui/TestTags.kt`), so both `ui dump` and UI
Automator show them:

| Tag | Where |
|---|---|
| `rail-steam`, `rail-games` (the Library), `rail-desktop`, `rail-store`, `rail-components`, `rail-android-apps`, `rail-setup`, `rail-updates` | the front end's rail |
| `play-steam` | Play on the Steam page, and on the Library when it has no games |
| `library-source-<source>` | the Library's source filter chips (`all`, `steam`, `gog`, ...) |
| `game-tab-<about\|achievements\|launch\|saves\|install>` | the open tab of a game page |
| `stores-profile`, `stores-epic-launcher` | a store's profile strip, and the Epic Games Launcher row in Epic's |
| `setting-<key>` | every toggle and choice row, by its key (`setting-logs`, `setting-agent-commands`, ...) |
| `tab-<n>` | a page's tab strip (Setup's Overview, Controller, Session, Launcher...), by position |
| `drawer-tab-controller`, `-second_screen`, `-display`, `-effects`, `-vr`, `-games`, `-session` | the session drawer's pages |
| `drawer-stop`, `drawer-stop-confirm`, `drawer-stop-cancel` | stopping a session from the drawer |

## Scenarios

`tools/droiddeck-scenario` runs a JSON scenario: `setup` once, `steps` `repeat` times, `teardown`
once. Each run starts with `before` (typically `start steam --wait --reuse`, so a session that died
in one run, to a crash or the low-memory killer, is started again for the next) and ends with
`after`, whether it passed or not, so a failed run cannot leave a game running into the next. A list step is a droiddeckctl command line; `{"assert": "dotted.path", "equals"|"in"|
"contains"|"not"|"exists": ...}` checks the last answer; `{"sleep"}`, `{"screenshot"}` and
`{"record"}` do what they say. A failing run keeps a screenshot, the focus snapshot and the
session's logs in its folder, and `report.json` sums up the runs.

```sh
tools/droiddeck-scenario tools/scenarios/steam-game-focus.json --var appId=489830 --repeat 10 --keep-going
```

`tools/scenarios/steam-game-focus.json` launches a game, checks it has focus the way a player
sees it, and quits it: the test that catches a game left behind Steam's loading screen, which only
shows up in some fraction of launches. A game with a launcher of its own needs the launcher pressed
through first, since the launcher window carries the game's app id too:
`tools/scenarios/skyrim-launcher-focus.json` taps Skyrim's Play before checking.

## PRoot performance experiments

`proot-profile enable` queues handler profiling for the next session using the existing debugging
consent gate. Counters and bounded slow samples appear in `session.log`; `proot-profile report`
ranks a saved local log without ADB. This measures PRoot handler work, not full syscall latency.
`override libblfastpath.so FILE` selects a glibc ARM64 shared library in the next session's preload
list, reports its SHA-256, and refuses installation until the current session stops. It lasts for
one session; the next session stages the bundled library again. See
[the profiling workflow](development/proot-performance.md#finding-the-next-fast-path) for timing
boundaries, build commands, path diagnostics, and A/B validation.
