# VITURE Android SDK integration

For the cross-brand feature matrix and user setup, see [smart-glasses support](../smart-glasses-support.md).

## Importing the SDK

Connect VITURE glasses and open **Setup → Smart glasses → Display** (or the
glasses settings in a running session). **Get SDK from VITURE** opens the
[VITURE developer portal](https://www.viture.com/developer) in a browser. Choose
**VITURE XR Glasses SDK for Android (2.4.x)**, download the ZIP, and leave it zipped.
Return to DroidDeck and choose **Import VITURE SDK** to open Android's file picker.
Select the downloaded ZIP, review the validated version, and confirm the import.
An enclosing release directory inside the ZIP is supported.

The importer requires the three Android ARM64 libraries (`libglasses.so`,
`libcarina_vio.so`, and `libcloud_protocol.so`), the SDK version header, and
Android LICENSE and NOTICE. It checks the archive paths, size limits, ELF
architecture, and required exported symbols before confirmation. These checks
establish format compatibility, not publisher authenticity: obtain the SDK
directly from VITURE. No SDK code runs during archive validation.

Confirmed libraries and notices are stored in the app's private, non-backed-up
storage. Failed validation or activation does not replace an existing install.
The app loads a successful import immediately and reconnects VITURE USB control;
grant USB permission if prompted. It restores the SDK on subsequent launches.
The import action is replaced by an SDK-ready status while the SDK is active.

The import unlocks supported device controls and the existing Luma Ultra
positional/head/hand/controller paths. Positional tracking and controller
options remain opt-in in **Tracking**; importing does not enable them or change
display preferences. Capabilities still depend on a successful device connection.

The **Advanced** tab exposes the connected device's supported controls. Device
getters and capability checks determine availability; unsupported or failed reads
do not create usable controls. New settings are manual and do not change exposure,
tracking rate, or native display processing simply because the SDK was imported.

| Function | Availability and app behavior |
| --- | --- |
| Brightness, speaker volume, lens tint | SDK 2.4.1+ reports device-specific ranges. Beast/Phantom tint exposes all nine levels; binary film remains a toggle on other supporting models. |
| Native display processing | Beast/Phantom: bypass/native selection, four documented bypass modes, eight documented native modes including ultrawide. Ultrawide is not interpreted as SBS. Firmware and Android output confirmation still apply. |
| Built-in tracking | Beast/Phantom: off, fixed-screen 3DoF, smooth follow, and recenter. Host tracking pauses while native tracking is active, and its saved preference is restored afterwards. SteamVR uses host tracking. |
| Native screen controls | Beast/Phantom: five screen sizes, distance steps 1–10, and side mode. |
| Power-on mode and physical 2D/3D button | Luma/Luma Pro capability: save the current mode in the glasses; enable/lock the physical switch until the next power cycle. |
| Panel illumination and wear detection | Duty-cycle adjustment and live wear status when the device accepts the relevant getters. |
| Tracking rate | Queried supported IMU frequencies; Luma Ultra polling offers 240/500/1000 Hz. Higher rates are opt-in. |
| Tracking camera exposure | Luma Ultra automatic or manual exposure (0.01–8 ms, gain 0–15). Automatic remains the connection default. |
| Tracking recovery | Luma Ultra engine restart or SDK-origin recenter invalidates host pose/controller history and waits for a fresh stable pose. |
| RGB camera | Live preview with Android camera/USB permission on SDK-confirmed camera devices: Luma Pro/Cyber/Ultra and Beast. Phantom Beast has no RGB camera. |

SDK reporting remains disabled. Raw sensor/debug APIs are not presented as user
features. Hand and Joy-Con tracking continue to use DroidDeck's existing
experimental stereo-camera integration; the SDK does not supply those algorithms.

## Building

Every build includes the small `droiddeck_viture` adapter with runtime symbol
loading and no link dependency on proprietary libraries. Vendor headers and
binaries are not stored in the repository or required for an ordinary build.
Without an imported SDK, legacy VITURE and the experimental Pro 2
USB transports remain available, alongside the other glasses integrations.

Builds do not bundle the proprietary SDK. Non-empty legacy SDK-directory build
options are rejected; users supply the official Android SDK ZIP after installing
the app. Release validation rejects vendor libraries anywhere in the APK.

## Supported app functions

| Device | Transport and tracking | App controls |
| --- | --- | --- |
| Pro 2 XR | SDK Gen2 pose callbacks, observed OpenGL axes converted to NWU; negotiated pose frequency | 1080p 2D through 120 Hz, SBS 60 Hz, brightness 0–8, volume 0–8 |
| Luma Ultra | SDK Carina USB transport; OpenGL poses converted to NWU | 1200p 2D through 120 Hz, SBS 60/90 Hz, brightness, volume, binary dimming |
| Beast / Phantom Beast | SDK Gen2; native/bypass display commands selected by capability and current mode | 1080p/1200p output, SBS, native ultrawide, brightness, volume 0–15, nine tint levels |

Pro 2 identification uses the SDK's PID validation and market name, not a guessed
USB ID. Pro 2 has no electrochromic film control. Its display profile uses the
[official 50° FOV and 1080p/SBS limits](https://www.viture.com/academy/product/viture-pro-2-xr-glasses).
Device firmware can reject individual modes; Android's physical display size
must confirm SBS before the app considers the switch successful.

The SDK's exact `Phantom Beast` market name selects the Beast app profile.
[VITURE's device documentation](https://www.viture.com/developer/glasses-sdk/glasses#native-dof-devices)
describes it as a Beast variant without a pass-through camera. On 2026-11-12,
SDK 2.4.2 identified the connected Phantom Beast at `35ca:1221`, with USB product
name `VITURE Phantom XR Glasses` and firmware `22.1.01.372_20261028`. That verified
PID and the observed name are recognized before import; control still requires
the imported SDK to validate the detected PID.
Before import, a VITURE-branded product name matching a documented glasses model
can expose the import action without advertising USB control or tracking.

All three feed the existing `Glasses.PoseSink`, head recentering, compositor and
SteamVR pipeline. Ultra selects 3DoF or the opt-in positional 6DoF mode before SDK
initialization. Its positional poses and stereo cameras feed the
[Luma spatial pipeline](luma-spatial-tracking.md). The Device tab provides live
RGB camera preview on SDK-confirmed camera models.
The SDK's optional statistics reporter is not enabled.

The SDK owns USB interface selection and I/O. `UsbDeviceConnection` remains open
until stop/shutdown/destroy finish. A generation token rejects callbacks from an
old connection. Device brightness, volume and dimming events update the app.
Beast native and standard display codes have separate meanings; they are mapped
explicitly. Host tracking suspends native DOF to prevent applying rotation twice
and restores the previous native DOF on stop. Older firmware rejecting a bypass
mode can fall back to the native table, with rollback on failure.

`Game on glasses` uses Android's existing Presentation output. USB control alone
does not create a video display: the phone, cable and adapter must expose a usable
external display. Enabling 3D also enables and saves `Game on glasses` before
requesting the stereo mode, so the picture uses the glasses' presentation instead
of the mirrored phone output. Returning to 2D keeps the game on the glasses.
Discovery checks both display name and product metadata, with
a single unnamed-display fallback when recognized glasses USB is present.

Screen width and distance apply to **Virtual screen** rendering. In **Automatic**,
ordinary 2D games stay flat. On an SBS display, the complete flat frame is fitted
into each eye without cropping or head tracking. A running SteamVR session uses
the direct stereo view. Games or videos that already contain separate eye images
use the explicit **Side-by-side game** picture setting. Switching SBS on does not
activate a virtual screen or head tracking for ordinary games.
The direct view uses SteamVR field of view instead of virtual-screen dimensions
while SteamVR is running. A plain side-by-side game fills each eye and does not
use the SteamVR field-of-view or recenter controls.

All supported glasses with an available head-tracking connection have a separate
**3DoF virtual screen** toggle, including VITURE, RayNeo and XREAL 1S. Availability
comes from the connected transport's IMU capability rather than a model whitelist.
The app renders this screen without enabling a built-in 3DoF mode or changing
brightness. Enabling it selects Virtual screen, head tracking and Game on glasses,
and saves those preferences without changing the glasses' 2D/SBS mode. It works
with flat games in either display mode. Disabling it returns the picture to Automatic; switching
SBS on or off preserves the 3DoF selection. Virtual screens center on the current
viewing direction when tracking starts or the user recenters, including the
up/down angle. Direct SteamVR views retain their gravity-aligned recentering.

The settings UI, menu presentation, and session renderer share this mode selection
through `GlassesRendering`. Setup → Smart glasses configures DroidDeck menus,
non-Steam launchers, desktop sessions, and games. Tracked menus use the app's own
glasses presentation while phone controls remain available; see
[tracked menus](glasses-menu-tracking.md). Regular refresh rate applies to 2D
output; SBS uses the model's separate 3D refresh rate.
Both the home menu and session drawer use **Display**, **VR**, **Tracking**, and
**Device** tabs. Display groups connection and output controls, 2D/3D resolution
and active refresh rate, then picture and virtual-screen geometry. VR contains
SteamVR controls, automatic 3D, VR resolution, and field of view. Tracking groups
head orientation, position and floor, and hands with camera setup. Device contains
brightness, volume, and sunblock. Unsupported hardware tabs are hidden. Recenter
is the first action on every session tab while head tracking is enabled for a
3DoF/6DoF virtual screen or SteamVR.

Display saves **2D resolution** and **3D resolution**; VR saves **VR resolution**. Values are per eye: 1920 x 1080 or, on tall VITURE
models, 1920 x 1200. Automatic keeps the existing model default; VR defaults to
following the active 2D/3D preference. Home settings expose all three profiles,
while a running session shows the active profile next to its refresh control.
Switching resolution retains the refresh preference and does not change brightness,
color processing, or the session's game-rendering resolution. Android selects an
advertised mode with matching dimensions and the closest available refresh rate,
without crossing the 2D/SBS layout. VITURE's USB command selects the matching
resolution family first. A saved resolution unsupported by another attached model
falls back to its automatic behavior. Resolution controls wait for a pending SBS
transition to finish before accepting another change.

Both lists offer 30 Hz only when Android advertises a matching native display mode at the current
resolution and **Game on glasses** is enabled. Android's presentation selects that mode without a
vendor command. A saved 30 Hz choice falls back to 60 Hz if the display or eye layout lacks it;
mirrored output never substitutes a 30 fps rendering limit.
Software picture controls remain available while a display is connected even if
USB control is lost; hardware controls and head tracking still require USB.
Presentation refresh preferences are revalidated on display changes even when
the requested Hz is unchanged, and keep both dimensions of the active mode.

## Validation

Run `:app:externalNativeBuildDebug` and
`:app:testDebugUnitTest --tests 'com.droiddeck.launcher.glasses.*' --tests 'com.droiddeck.launcher.ui.VitureSdkImportControlsTest'`
with `-PvitureSdkDir=` to verify SDK-free builds and imports. Run
`python3 -m unittest tools.tests.test_viture_sdk_loader` for native loader
failure, retry, and version checks. Importer tests exercise archive validation,
cancel cleanup, integrity checks, and failed-install rollback; UI tests cover
the file picker, confirmation, and detection gating.

With a local SDK, compile `app/src/test/cpp/viture_display_modes_test.cpp` with
the NDK C++17 compiler and its include directory to check native/standard
mappings against vendor constants. The other Kotlin tests cover model
capabilities, axis conversion, invalid poses, rejected tracking starts, and stale
callbacks.

Physical acceptance checks remain: connect each model, grant USB permission,
verify the Play SteamVR control, verify game output moves to the glasses,
test head rotation/recenter and SBS, then unplug/reconnect. Verify import from
an SDK-free install, relaunch persistence, and Luma Ultra positional tracking.
Building and unit
testing do not establish hardware compatibility for every firmware revision.

On 2026-11-12, the SDK-free APK with SHA-256
`1a7969610e5ced93b48937a8268336158981eed2a20d87e2705769ab5569540f`
was tested on the user's Fold7 with Luma Ultra `35ca:1104`, firmware
`12.0.02.002_20260901`, and imported SDK **2.4.2**. The glasses/UI suite passed
**573 tests with zero failures and zero errors**. The APK contains no proprietary
SDK libraries; the existing import remained available after updating the app.

| Hardware check | Observed result |
| --- | --- |
| Import and persistence | Android picker/confirmation, immediate USB reconnection, and persistence after force-stop/relaunch and APK update passed. |
| Runtime controls | Reported maximum brightness 8, volume 8, and binary tint maximum 1. Brightness write/readback passed and the original level was restored. |
| Head and positional tracking | Opt-in 6DoF produced fresh valid positions; the user confirmed movement looked correct while wearing the glasses. Default tracking delivered approximately 233 orientation samples/second. |
| RGB preview | Camera `0c45:636b` displayed a visually verified live image. Closing and reopening the preview succeeded while 6DoF remained valid, with an observed pose age of 2.6 ms. Pose age is not an end-to-end latency measurement. |
| Tracking recovery | SDK-origin recenter and tracking-engine restart both reported success and returned to fresh valid 6DoF. |
| Camera exposure | Manual 4 ms exposure with gain 0 was accepted; automatic exposure was then restored. |
| Higher tracking rate | Selecting 500 Hz produced 661 poses over 1.5 seconds, approximately 440 poses/second. The setting was restored to 240 Hz; the selected rate is not a measured throughput guarantee. |
| Stereo tracking cameras | Frames were 640×480 at approximately 25 Hz. |

Tracking correctly became unavailable when unworn glasses faced an unsuitable
scene. These checks cover this Luma Ultra, phone, SDK and firmware combination.
Regular Beast, other models, and the broader hand/controller accuracy, gameplay
and sustained-performance targets still require hardware acceptance.

### Phantom Beast acceptance, 2026-11-12

The same Fold7 and imported SDK 2.4.2 recognized Phantom Beast `35ca:1221`,
firmware `22.1.01.372_20261028`. Its USB name is `VITURE Phantom XR Glasses`;
both that name and the verified PID now expose import before the SDK is loaded.

| Hardware check | Observed result |
| --- | --- |
| Device ranges | Brightness maximum 8, volume maximum 15, tint maximum 8 (nine levels). Brightness 8→7→8 and tint 5→4→5 passed write/readback. |
| Built-in tracking | Smooth follow→fixed screen and native recenter succeeded. Switching to bypass restored DroidDeck head tracking. |
| Native screen controls | Large→Medium→Large and side mode off→on→off passed write/readback. |
| Native 120 Hz | The SDK accepted mode `0x33`; Android reported a physical 120 Hz display mode. Android paced the frontend at 60 fps, so the frontend's reported rendering rate is not the physical refresh rate. |
| Ultrawide | Mode `0x3d` produced a confirmed 3840×1080 at 60 Hz display and was classified as ultrawide rather than SBS. Output was restored to 1920×1080 at 60 Hz. |
| Unsupported features | The SDK reported no RGB camera; Ultra positional tracking remained unavailable. |
| Host orientation | Approximately 205 samples/second arrived in bypass mode. The generic Gen2 NWU mapping mixed up all axes. After the Phantom OpenGL correction, the wearer confirmed correct directions and horizon. |

The Phantom orientation correction selects USB PID `0x1221`. Tests exercise yaw,
pitch and roll through `HeadPose` and recentering. Generic SDK documentation
still describes Gen2 callbacks as NWU, so hardware validation remains necessary
for other variants; regular Beast validation is recorded below.

The final Phantom test APK has SHA-256
`915a951994401f1903c7e4bb8cd4cc70ba1271825ee8841c86a54048eeaea999`.
All **533 glasses and VITURE-settings tests** passed with zero failures/errors;
runtime, session-asset and native-library packaging checks also passed. The APK
contains no proprietary SDK libraries. After installation, the existing SDK
import reloaded and the Phantom reconnected with host tracking active and no
reported control error. The wearer subsequently confirmed the revised axes and
recentered horizon.

Refresh choices now follow the connected device's active native/bypass mode,
including 90 Hz bypass SBS and 60 Hz ultrawide. A successful explicit Advanced
display selection cancels any older SBS confirmation deadline, so that timeout
cannot later restore 2D over the new choice. Regression tests cover both changes.

RGB-only hand tracking for camera-equipped models is planned separately in the
[color-camera hand tracking plan](viture-rgb-hand-tracking-plan.md). It is not
enabled by these changes, and the Phantom has no camera for that path.

### Regular Beast and model-limit audit, 2026-11-12

The regular Beast at `35ca:1201`, firmware `20.0.01.028_20260915`, connected through
the imported SDK 2.4.2. DroidDeck received orientation with native display mode
enabled and built-in tracking set to Off. The wearer reported mixed-up axes with
the generic NWU mapping, then confirmed all three directions after the same
OpenGL conversion used for Phantom was selected for PID `0x1201`. A 1.5-second
capture delivered 305 poses, approximately 203/second. Variant `0x1211` remains
unverified and retains its previous mapping.

The APK with SHA-256
`e12f28c56b994f6c5e233edc6d154e97983d3e29c2cb1806d1de99b9cefef15b`
passed **598 glasses/settings tests**, runtime and session-asset checks, and
native-library packaging validation. It contains no proprietary SDK libraries.
The existing import persisted after installation, and the Beast reconnected
without control errors.

The [model-limit audit](../smart-glasses-support.md#display-and-device-controls)
corrects One/Lite refresh and control ranges, prevents Lite tint commands,
uses the identified Luma/GT Max optical profile, limits Rokid SBS rates by
resolution, and uses Android's advertised refresh rates on RayNeo. The menu
presentation now uses the same resolved rate as session output.

DroidDeck preserves the glasses' native screen size and distance on connection.
These values are independent of the app's saved virtual-screen geometry, whose
defaults are 2 m width and 3 m distance. On this Beast, the initial SDK readback
was Large (2) and distance 6, while the app's saved width was 1.8 m at 3 m.
Changing native size to Ultra large (4) was accepted with host tracking active;
the wearer confirmed it filled the available screen. Ultra large was left selected,
with native distance 6 and the app's saved virtual-screen geometry unchanged.
The selector is available in glasses settings under Advanced → Screen size.

### Review fixes, 2026-11-12

Failed SDK tracking pauses now publish the actual stopped state and attempt host
tracking recovery while retaining the control error. Refresh-only changes preserve
the current Advanced resolution and layout; explicit resolution changes still
apply the selected profile. Import recovery removes abandoned staging directories
while preserving active extraction and pending confirmation. Camera previews share
one capture executor so a replacement opens after the previous native and USB
cleanup finishes.

The updated APK has SHA-256
`40b02e34a7d8f19b68e5bdadfd69b74e2be93fb451d664ca6140704fd806bd3a`.
The glasses/settings suite reported **611 passed, 1 skipped**, with no failures or
errors. The skipped symlink test requires a privilege unavailable on this Windows
host. Runtime, session-asset, and native-library packaging checks passed; no
proprietary SDK libraries are bundled. On the phone, startup removed a staged
interrupted-import fixture, preserved the existing import manifest, and loaded
SDK 2.4.2 after the update. Glasses were disconnected during this verification,
so the fixes have not yet received another live camera/tracking check.

### Pro 2 pose-axis correction, 2026-10-14

The connected Pro 2 delivered approximately 200 normalized poses/sec. The wearer
reported yaw moving the view vertically, pitch rolling it, and roll turning it
horizontally. Directed captures showed a left turn primarily increasing quaternion
Y (approximately 0.004 to 0.253), while repeated upward looks varied quaternion X
(approximately -0.187 to +0.082) with Y/Z remaining near zero. This is the OpenGL
right/up/back basis, despite the SDK 2.4.0 generic Gen2 callback documentation
describing NWU.

The adapter therefore selects the existing OpenGL-to-shared-glasses conversion
for the SDK-identified Pro 2 family as well as Carina. Other Gen2 models retain
their previous mapping. Tests exercise model selection and yaw/pitch/roll through
the complete shared `HeadPose` conversion and recenter path. This fixes a model
coordinate mismatch; it does not claim to run a vendor sensor-calibration command
or save a RayNeo-style calibration for VITURE. All 188 glasses/shared-pose/
SteamVR-launch/UI regression checks passed, packaged runtime inputs verified,
and the corrected build was installed on the phone. Visual verification is pending.
