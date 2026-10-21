# RayNeo Air / GT XR integration

Built on the current `feat/viture-xr` PR #732 at bdf876d47cc9bb6fa810d014370da5bbb3c28039.
The supplied historical hashes a127e66, 7f9dd75, e8e3e6b and f0b3297 were not
resolvable in the fetched branch; no native projection or synchronization files are changed.

## Discovery and transport

`Glasses.kt` owns USB permission, attachment/detachment and presentation-display
selection. There is no manifest USB VID filter: discovery uses `UsbManager.deviceList`.
VITURE discovery is retained. `RayNeoModel` accepts the scaffold's confirmed
`1bbb:af50` and verncat's confirmed GT identity `3941:af50`; other products under `1bbb` or bridge vendor `22d9` must identify
as RayNeo/TCL Air or GT in their USB product name. This covers named Air 2, 2s,
3, 4 Pro and GT devices without claiming arbitrary TCL phones or bridge chips.
Additional unnamed VID/PID pairs require captures before being allowlisted.
Other products under `3941` are rejected. Without a meaningful USB product name,
`3941:af50` is labeled "RayNeo GT series"; a supplied GT MAX name is preserved.
GT Max was observed on the Samsung SM-F976U with this USB identity on 2026-10-14;
USB permission and the command handshake succeeded.
EDID/product display names containing RayNeo, Ray Neo, or TCL Air/GT use the existing
external-screen path. Android's supported modes, not a synthetic refresh value,
determine whether 120 Hz can be selected. Stereo modes may have a lower maximum rate.
GT Max's observed display identity is `SmartGlasses`, EDID manufacturer `TCL`,
product `980` (1920x1080, 60/120 Hz in 2D). This exact metadata combination is
recognized even though neither display name contains RayNeo. Other generic
SmartGlasses/TCL products are not accepted by this rule.

Game output uses the same `GlassesScreen` presentation as VITURE, with the phone
retaining the session controls; no screen-capture permission is needed. Discovery
checks both Android's display name and its product name. On Android 12 and newer,
a missing product-info object is treated like an unnamed panel: a recognized
glasses USB device can identify a single presentation display. Unrelated named
monitors and multiple unnamed displays are not automatically selected by that
fallback. `Game on glasses` remains enabled by default.
The presentation holder is selected before `show()`, and the phone SurfaceView
is hidden only after the compositor attaches to the glasses holder. Late phone
surface callbacks cannot reset the external output.

`RayNeoLink` claims a paired IN/OUT interface and translates the controller's
internal commands to RayNeo packets. For `3941:af50`, selection prefers interface
number 5 (not array index 5), as tested in verncat's GT contribution. Only endpoints
that can carry 64-byte packets qualify. Firmware with a different interface number
can use a unique qualifying IN/OUT pair; ambiguous alternatives are rejected.
It requests device info (0x66/0), enables IMU
(0x66/1) and waits for acknowledgments, warms up for two seconds, and stops IMU
(0x66/2) when no pose consumer remains or the link closes. SBS uses commands 6/7.
Unsupported VITURE brightness, volume and electrochromic commands are never sent;
the VITURE hardware-control UI is hidden for RayNeo.
GT spatial-button notification 0x08 and volume/display ACKs remain non-device-info
responses and cannot satisfy unrelated startup commands. The spatial button is
left to the glasses' firmware; no undocumented spatial-mode command is sent.

The parser/filter are adapted from the user's scaffold at
581dbf712ed601ece4cf5e4a7c1f52e780299c88. Sensor packets are framed with 0x99/0x65,
with little-endian acceleration at byte 4, gyro (degrees/sec) at 16 and a uint32
100us device tick at 40. Fragmented and coalesced reports are assembled before decoding.
Board 0x3a (Air 4 Pro) receives the scaffold's -20-degree X IMU mounting correction.
GT Max (`3941:af50`, board `0x41`) uses the mapping measured on 2026-10-14:
gyro and acceleration `(-y, z, -x)`, then +35 degrees around X.
At the user's level pose the raw acceleration was approximately
`(5.70, 0.00, 7.95)` m/s². A directed left-turn-and-hold capture produced positive
X/Z gyro (e.g. `(20.5, 2.0, 28.2)` deg/s), and upward looks produced negative Y
gyro. The 35-degree mounting estimate comes from that level capture,
not manufacturer calibration; frame fit and other GT variants remain unverified.
Gravity initializes orientation before the first published pose. Subsequent
body-to-world fusion uses `measuredUp × predictedUp`, correcting tilt toward gravity
instead of amplifying the error. These fusion corrections also apply to Air;
the GT Max sensor remapping does not apply to Air or VITURE.

The non-Max GT shares USB `3941:af50` but reports board `0x40`, confirmed by
switching between both physical pairs on the same phone. Its sensor axes use
the ordinary XYZ basis with a measured -25-degree X mounting correction, rather
than the Max permutation. Its confirmed upside-down resting acceleration was
approximately `(0.020, -8.819, -4.106)` m/s²; the worn level capture was near
`(0.09, 9.26, 3.11)`. Left turns produced positive Y/Z gyro. The mounting estimate
uses the resting surface; per-device calibration refines its tilt and gyro bias.
Both the pose filter and calibration collector select the same board profile.
Non-Max calibration keys have a `:gt-standard-v1` suffix so they cannot replace
existing Max profiles when USB identity fields coincide. Calibration loading
waits for the device-info response; detached-device callbacks cannot change the
replacement device's calibration key. Existing Max keys remain compatible.
After installing this correction, the non-Max completed the upside-down
calibration on the phone with `saved=true`, phase `SAVED`, progress 100.
All 186 glasses/shared-pose/SteamVR-launch/UI regression checks passed and the
installed APK's packaged runtime inputs verified. After calibration and the
recenter check, the wearer confirmed on 2026-10-14 that the non-Max GT view
looks and feels good.

## Existing pose and SteamVR pipeline

The scaffold fuses gyro/accel into wxyz. The Android adapter converts its Y-up
coordinate basis and component order into VITURE's Z-up xyzw convention before
calling the same `Glasses.PoseSink` used by VITURE. `HeadTracker` and `HeadPose`
retain their existing recenter/seated-reference behavior. The path continues through
`WaylandCompositor.nativeSetCardboardPose`, the compositor's `headpose_shm.h`
shared-memory record and `tools/vr/src/driver_droiddeck.cpp`'s pose-file reader.
The OpenVR projection, presenter, Vulkan wait logic and vblank counters are untouched.
An explicit Play SteamVR request polls the guest runtime independently of display
attachment and USB-control readiness. Both RayNeo and VITURE call the same
`droiddeck-steamvr start` helper. Stopping cancels the pending launch request;
automatic hardware 3D switching still respects the shared Auto 3D setting.

## Optional stationary calibration

Glasses → Tracking → Tracking calibration guides the user to rest the connected
RayNeo upside down on a level surface. It allows three seconds to settle, then
requires five continuous seconds of stationary samples (at least 200 reports).
Invalid gravity, the wrong orientation, motion/variance, or gaps longer than 250 ms
restart the window. A 30-second deadline fails without replacing a saved profile;
USB stream loss also terminates the operation. Cancellation and link replacement
invalidate queued callbacks by operation revision and connection generation.

The profile contains a raw gyro bias and a small mounting tilt correction, applied
to both sensor vectors before fusion. It does not estimate heading, full accelerometer
scale/bias, or translation. Profiles persist by vendor/product/serial; when a serial
is unavailable the product and model name share a fallback profile. Reset deletes
only the current profile and restores the model mapping. VITURE's fused SDK poses
do not expose the raw inputs this measurement needs, so this control is RayNeo-only.

While measuring, live pose publication pauses. On completion the user wears the
glasses, looks ahead and taps Done to recenter the active session. Dismissing the
dialog or leaving the tracking panel cancels an in-progress measurement. Agent
diagnostics expose the calibration state through `glasses`; operations are
`calibrate`, `cancel-calibration`, and `reset-calibration`.
The calibration build passed 157 targeted sensor, lifecycle, pose, launch and
Compose UI tests. Installing this feature and validating a real upside-down capture
on the phone remain pending a debugging connection.

## Regression and hardware validation

### SteamVR library launch routing (2026-10-14)

DroidDeck's SteamVR library entry (appid 250820), including its game shortcut,
now sets the same `EXTRA_STEAMVR` request as Play SteamVR instead of sending a
normal Steam game URL. Existing sessions resume with that request as well.
Other games retain their normal launch path and arguments.

Inside Steam's library, the existing SharedJSContext bridge wraps
`SteamClient.Apps.RunGame` for exactly 250820 and dispatches it to
`droiddeck-steamvr launch`. This calls the same install/start helper used by the
button, including OpenVR registration, the ARM64 runtime and Vulkan layer setup.
It does not change Steam's saved launch options. The library bridge is installed
for both desktop and Big Picture. It relies on the live Steam UI API and needs
verification against the actual phone's Steam version after installation.

The helper serializes installation/startup and avoids restarting an active
DroidDeck SteamVR session. Library launch diagnostics are in
`${XDG_RUNTIME_DIR:-/tmp}/droiddeck-steamvr/library-launch.log`; normal runtime
logs remain in `vrstartup.log` and Steam's logs directory. It does not intercept
arbitrary external `steam://` URLs or change VR game launch arguments.

Only VR-only games (`vr_only`) wait for SteamVR and start it. Games where VR is
optional, such as No Man's Sky, launch normally unless SteamVR is already
running; before this they started (and if needed downloaded) SteamVR on every
Play, so they opened in VR without the user asking for it.

Validation: seven bridge/dispatch tests passed under Node/Python, five Android
SteamVR launch tests passed, shell syntax passed and the debug APK built with
runtime input verification. The broader Linux compatibility suite cannot run
unmodified on Windows because it requires `fcntl`; no Linux runtime was available
for the helper's lock/process integration tests. Installation and live library
Play verification are pending while the phone charges.

Run `./gradlew -PskipRust=true :app:testDebugUnitTest --tests 'com.droiddeck.launcher.glasses.*'`.
Protocol tests cover malformed/fragmented packets, unsigned timestamps, model gating,
quaternion normalization, yaw basis and tick rollover. USB integration tests cover
permissions, GT interface preference/fallback, interleaved GT notifications and
ACKs during startup, 2D/3D writes, and stale pose callbacks. Hardware validation is still required per model:
USB enumeration, acknowledgment/stream startup, axes and recenter, hot unplug/replug,
SBS negotiation, Android's actual 120Hz mode, and SteamVR pose/frame pacing.
GT Max was verified on SM-F976U with USB `3941:af50` and the TCL/980 display:
the compositor attached to its separate 1920x1080 display, the Play SteamVR
request started native `vrserver`/`vrcompositor` through the shared helper, and
the presenter logged roughly 60 frames/s while the IMU packet count increased.
The test retained the user's disabled Auto 3D setting. After installing the corrected
GT sensor mapping, the user confirmed an upright starting view and correct movement
in all directions. SteamVR and the glasses IMU remained running. Stereo negotiation,
long-term drift, and alternate bridge firmware variants still need their own
hardware validation. The targeted glasses, Cardboard pose, and SteamVR launch
suite passed 134 tests after the tracking correction. Tracking regression coverage
checks initial gravity alignment, tilt convergence, the recorded GT Max level pose,
and yaw/pitch/roll through the shared VITURE basis conversion and recenter path.

Attribution and the scaffold's Apache-2.0 license are in `docs/third-party/rayneo/`.
