# Android XR bring-up

This implementation adds Android XR to the existing native OpenXR/SteamVR bridge.
It also provides an independent Jetpack XR diagnostics app and host tests.
See [the recorded validation results](android-xr-validation.md) before treating a
build or synthetic test as device validation. Full finger skeletons, eye tracking,
room meshing, and graphics-engine replacement are outside this milestone.

## Documentation and checkout audit

Official documentation was rechecked through the web connector on October 22,
2026. The emulator constraints supplied on October 21 are unchanged:

- [AVD setup](https://developer.android.com/develop/xr/jetpack-xr-sdk/run/create-avds/xr-headsets-glasses)
  explicitly excludes OpenXR and Unity applications. Supported XR AVD hosts are
  macOS on Apple Silicon and Windows; the current Linux workspace cannot run this
  documented XR emulator path.
- [Emulator controls](https://developer.android.com/develop/xr/jetpack-xr-sdk/run/emulator/xr-headsets-glasses)
  provide view rotation, pan, dolly, and right-hand pinch. Left-hand control,
  grabbing, and poking remain unsupported.
- [Studio setup](https://developer.android.com/develop/xr/jetpack-xr-sdk/get-studio)
  recommends the latest Canary Studio and current emulator/platform tools.
- [OpenXR configuration](https://developer.android.com/develop/xr/openxr/get-started)
  requires unmanaged full space and the Google runtime native-library declaration.
  [OpenXR support](https://developer.android.com/develop/xr/openxr) describes 1.1
  with selected extensions; extension enumeration is still required.
- [Permissions](https://developer.android.com/develop/xr/permissions) distinguishes
  raw hand joints from basic gesture actions. The production backend uses actions,
  queries system hand capability, and does not acquire raw joints. It therefore
  does not request `HAND_TRACKING` or indiscriminately require `HEAD_TRACKING`.
- [Jetpack device pose](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/device-pose)
  uses `ArDevice`, `DeviceTrackingMode.SPATIAL`, and the `HEAD_TRACKING` runtime
  permission on immersive devices. [Jetpack hands](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/hands)
  uses `HandTrackingMode.BOTH` and `HAND_TRACKING` for joint access.
- [ARCore testing](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/testing)
  documents `ArCoreTestRule`. The diagnostics test uses this to inject palm data.
- The [Runtime release notes](https://developer.android.com/jetpack/androidx/releases/xr-runtime)
  now list **1.0.0 stable, released October 21, 2026**. Diagnostics pins the 1.0.0
  Runtime, ARCore, SceneCore and testing family instead of copying the older
  `rc02` dependency from the testing guide. Artifact resolution and Kotlin/APK
  compilation remain blocked in the recorded environment; these pins need the
  local build check below.

The supplied checkout was clean at `65f44e2b807fdc6c05780460e66001ff7e87f893`
on `work`, with origin `informalTechCode/DroidDeckXR`. Work is on
`feat/android-xr`. Canonical upstream main was inspected through the GitHub
connector at `829a39a9ca8cd6181179e9afe59aa486f0c23325`; its native XR source
matched the checkout apart from the fetched file's trailing newline. The review
reference `0e7818049ab5ee0cf4409af0143c12ac98e6e1a8` was not a local Git object.
No reset, downgrade, or upstream merge was performed. Root `AGENTS.md` was the
applicable instruction file. The initial native path bound Oculus Touch; its
296-byte v1 packet carried controller poses/actions, not joint arrays.

## Architecture and conventions

`shared/xr/core.h` is the dependency-light implementation used by native OpenXR,
the diagnostics JNI adapter, and host tests. `tools/vr/src/driver_droiddeck.cpp`
also uses its quaternion conversion. Acquisition, rendering, Android lifecycle,
and Linux guest startup remain in their existing adapters.

- Positions are meters in a right-handed space, +X right, +Y up, -Z forward.
  OpenXR and Jetpack perception poses use this convention. Quaternions are
  explicitly XYZW; OpenVR receives WXYZ. No scale, mirroring, or invented head
  translation is applied.
- A pose retains position-valid, orientation-valid, position-tracked, and
  orientation-tracked bits separately. Valid but untracked poses remain visible
  to diagnostics but cannot drive an active controller or held gesture. The
  SteamVR bridge requires fully tracked 6DoF. Orientation-only data stays 3DoF.
- Quaternion squared norms outside 0.9–1.1, zero/non-finite quaternions, and
  non-finite positions lose the affected validity. Small norm errors are
  normalized. Invalid components are cleared before packet construction.
- Each sample identifies its provider, session and origin generation, clock,
  capabilities, and per-side input source. Core acceptance requires matching
  generations and provider, a positive timestamp, and age 0–250 ms. Future
  samples and incomparable clocks fail closed. Publication preserves acquisition
  time rather than making an older accepted sample fresh again. Missing prediction is zero.
- Native poses use `xrWaitFrame`'s predicted display time and the same world
  reference for head, eyes, grip and aim. `XR_KHR_convert_timespec_time` converts
  prediction into `CLOCK_MONOTONIC`; if unavailable or rejected, prediction is
  omitted. XrTime is never numerically reinterpreted as another clock.
- Jetpack reports device-monotonic callback/adapter receipt time. Its `CoreState`
  time mark is used only through `elapsedNow()` to reject stale runtime poses;
  that age is exported separately. SceneCore event timestamps are opaque. No
  OpenXR prediction or continuous gesture-strength stream is fabricated.
- Controller data has priority over simultaneous hand data on a side; an
  available controller with a lost pose does not fall through to a second device.
  OpenXR's selected interaction profile resolves the native source. EXT hand
  interaction may itself be mapped by a runtime from a controller; provenance
  means the **action profile**, not proof of optical tracking hardware.
- Hand gestures press at 0.70 and release at 0.45. After loss, focus changes,
  provider changes, permission denial, session restart, or origin changes, a
  neutral value is required before rearming. Physical trigger/grip analog values
  remain analog after rearming. The v1 hand mapping emits debounced 0/1 values so
  the existing SteamVR click threshold cannot undo hysteresis.

The packet remains 296 bytes, version 1, with unchanged offsets. The documented
optional `DROIDDECK_XR_HAND_SOURCE` bit (bit 31 of `hands[].buttons`) identifies
the restricted hand action profile; old readers ignore this bit. No joints are
hidden in existing fields. Full skeletal output needs a separately versioned
protocol. Provider/generation detail is retained in the core and diagnostics;
v1 does not serialize those generations.

The production writer and existing mmap/seqlock reader are exercised against
temporary app-private files in diagnostics, and temporary host files in tests.
Live Jetpack packets have no stereo view metadata and therefore are **not**
claimed to pass the guest's full stereo `Fresh()` predicate. The round trip
checks bytes and protocol acceptance. Synthetic bridge tests supply explicitly
synthetic stereo metadata and check the production freshness predicate too.

## Native runtime and SteamVR

The dedicated `QuestXrActivity` retains its name and guest lifecycle. It alone
has `XR_ACTIVITY_START_MODE_FULL_SPACE_UNMANAGED`. The ordinary launcher/panel
is unaffected. This is a capability-checked shared APK: the Google native library
and OpenXR feature are optional, and Android XR entry requires the package
manager's `android.software.xr.api.openxr` feature. Quest eligibility remains
supported. Quest GPU defaults and workarounds remain in `QuestExperimental` and
are not applied to arbitrary Android XR models.

Production stays compile SDK 34, target SDK 28, ARM64, AGP 8.8.0, Kotlin 2.1.0,
NDK 27.3.13750724, and OpenXR loader 1.1.49. There is no production target-SDK
change requiring a Linux-runtime execution migration. Local/CI cache keys and
Windows source attestation include `shared/xr`; Windows runtime packaging also
attests the new hand profile. CI invokes the shared host test command.

Touch bindings are retained. `XR_EXT_hand_interaction` adds grip/aim poses,
`pinch_ext/value`, `grasp_ext/value`, and their `ready_ext` gates. Unsupported or
permission-rejected optional profile suggestions leave head tracking usable.
System tracking properties and optional `supportsHandTracking` are logged;
raw-hand capability is not confused with action-profile availability. No raw
joint tracker is created in production, so no joint-tracker lifecycle or custom
grasp heuristic is needed. Hand action unavailability clears inputs.

Grip pose is the virtual controller pose, with an identity offset. Aim is
acquired separately and is optional; v1 continues to send grip, not a fabricated
aim/grip transform. Head and both hands share one origin. The custom SteamVR
`droiddeck_hand` input profile exposes trigger and grip only; it advertises no
thumbsticks, face buttons, skeleton, or haptic output. Physical Touch can resume
its profile on the same two virtual devices, clearing prior inputs. Hand-only
games may need manual SteamVR bindings and cannot use absent locomotion sticks,
menu/face buttons, or haptics. SteamVR's handling of dynamic profile changes is a
hardware validation item. Existing phone/Cardboard/gamepad and glasses paths are
retained.

STAGE supplies the runtime floor reference. If unavailable, the existing LOCAL
space with a 1.6 m standing-height estimate is retained and logged as estimated,
not calibrated. Reference-space changes take effect at the announced XrTime:
the core increments origin generation, releases input, discards retained guest
imagery, and rejects frames captured before that change. v1 guest frames do not
carry an origin generation, so exact in-flight guest pose/image association at
that boundary remains a hardware check. Native probe imagery is a head-relative
grid, not a world-locked translation-accuracy test or a floor calibration.

View configurations, blend modes, and swapchain formats are enumerated and
logged. The existing renderer requires primary stereo, GLES 3.0 or newer and an
sRGB RGBA8 swapchain. Context creation selects a GLES 3.x version within the
runtime requirements (up to 3.2) and verifies the actual context version. It selects an advertised blend mode, preferring opaque. The optional
GPU transfer needs GLES 3.1, external buffers, texture views and EGL native-client
buffers; otherwise the existing CPU transfer stays available. No guest rendering
performance or Android XR driver compatibility has yet been measured.

## Isolated diagnostics setup

Open `tools/androidxr-diagnostics` as its own Studio project. It does not include
the production Gradle settings or depend on any Linux rootfs, Steam, Wine/FEX,
proot, graphics driver pack, glasses SDK, or Rust engine.

Pins: Gradle 8.13, AGP 8.11.1, Kotlin 2.2.20, JDK 17, compile/target SDK 36,
minimum SDK 34, NDK 27.3.13750724, CMake 3.22.1, Jetpack XR 1.0.0. The Gradle
wrapper JAR is copied from this repository's wrapper. JNI uses static libc++.
Default JNI ABIs are ARM64 and x86_64; `--serial` selects the device's advertised
supported ABI before building. These ABI/toolchain settings are isolated from
production. SDK packages can be installed with your existing SDK manager:

```bash
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' \
  'ndk;27.3.13750724' 'cmake;3.22.1' 'platform-tools'
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/Android/sdk
python3 tools/test_xr_common.py
python3 tools/test_androidxr_diagnostics.py --build
python3 tools/test_androidxr_diagnostics.py --injected-tests
```

Create an XR headset or wired XR glasses AVD in Canary Studio's XR device
category, download its current supported image, and start it. Record the image
fingerprint/API and emulator version with every run. Do not substitute a normal
phone AVD or try to validate native OpenXR with this emulator.

```bash
adb devices -l
python3 tools/test_androidxr_diagnostics.py --serial emulator-5554 --build --install --launch live
# After granting head permission and interacting with the spatial target:
python3 tools/test_androidxr_diagnostics.py --serial emulator-5554 --collect --seconds 10
python3 tools/test_androidxr_diagnostics.py --serial emulator-5554 --launch fixture --collect --seconds 10
```

PowerShell equivalents (from the repository root):

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
py tools/test_xr_common.py  # requires a C++17 compiler; use WSL if needed
py tools/test_androidxr_diagnostics.py --build
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
py tools/test_androidxr_diagnostics.py --serial emulator-5554 --build --install --launch live
py tools/test_androidxr_diagnostics.py --serial emulator-5554 --collect --seconds 10
py tools/test_androidxr_diagnostics.py --serial emulator-5554 --launch fixture --collect --seconds 10
```

Live mode requests head access explicitly. Optional raw palm diagnostics request
hand permission separately. Denied permissions/configuration errors are shown.
Move the emulator view using rotate/pan/dolly and watch `ArDevice` observations.
Use the emulator's **hand** interaction mode to pinch the spatial target;
SceneCore `InputEvent.Source.HANDS` plus `Pointer.RIGHT` supplies that observation.
Mouse/gaze clicks are not counted as hand pinch. DOWN/UP/CANCEL feed the shared
core; a two-second event inactivity watchdog releases held input. This is a
discrete event check with a watchdog, not a continuous pinch-strength measurement.
Grip remains unavailable in live diagnostics. Palms are shown only when ARCore
actually supplies tracked palm data. The X/Z reference grid is labeled in meters.

Debug-only synthetic mode explicitly exercises two distinct hands, pinch/grasp,
loss while pressed, focus loss, revocation, recenter, malformed poses,
disconnect, and reconnect. It calls the same C++ core and runs its deterministic
checks through JNI. Live observations are recorded separately as
`liveJetpackObservations`, even while fixtures are selected. Release JNI excludes fixture implementation. Failed live
tracking never selects synthetic mode automatically.

The collector writes `build/androidxr-validation/report.json` and targeted
Logcat. The app-private report is also available using:

```bash
adb -s emulator-5554 shell run-as com.droiddeck.xrdiagnostics cat files/report.json
adb -s emulator-5554 shell getprop ro.build.fingerprint
adb -s emulator-5554 shell getprop ro.product.cpu.abilist
```

Reports identify the current fixture step/scenario, source, library/API/system image/ABI, advertised features,
observations, missing capabilities, permissions, validity, sample age, origin,
round-trip results and failures. Host collection does not compare host monotonic
time to device monotonic time. `ArCoreTestRule` tests validate injected Jetpack
adapter data only; the shared C++ suite validates production behavior separately.

## Native builds and later hardware checks

For quick compilation without the guest, use the small native build command.
Obtain the **real** loader AAR from the production-pinned Maven artifact
`org.khronos.openxr:openxr_loader_for_android:1.1.49`, and extract its Prefab
`include` directory and ARM64 loader. Do not replace the loader with a stub.

```bash
python3 tools/build_xr_native.py --sdk "$ANDROID_HOME"
python3 tools/build_xr_native.py --sdk "$ANDROID_HOME" \
  --openxr-include /path/to/prefab/modules/openxr_loader/include \
  --openxr-loader /path/to/prefab/modules/openxr_loader/libs/android.arm64-v8a/libopenxr_loader.so
python3 tools/test_xr_common.py --openxr-include /path/to/openxr/include
# Optional ASan/UBSan; on ptrace-restricted hosts only, disable unsupported LeakSanitizer:
ASAN_OPTIONS=detect_leaks=0 python3 tools/test_xr_common.py --sanitize
```

The compile command prints whether the production link was attempted. It does
not build or certify the shipping APK. Run production preparation using the
existing workflow (Docker image and pinned runtime/native packs are required):

```bash
DROIDDECK_BUILD_VARIANT=debug tools/build_local.sh
# Once the normal preparation workflow is complete:
./gradlew :app:externalNativeBuildDebug :app:processDebugMainManifest \
  :app:processReleaseMainManifest :app:testDebugUnitTest
python3 tools/test_androidxr_diagnostics.py --build
```

Inspect the merged debug/release production manifests under
`app/build/intermediates/merged_manifests/`: native Google library optional,
unmanaged start mode only on `QuestXrActivity`, ordinary launcher intact,
target SDK 28, no production HEAD_TRACKING requirement. Inspect diagnostics'
own merged manifests: independent ID, managed full space, head/hand permissions,
target SDK 36. Both APK/merged-manifest checks were blocked in the recorded run.

When hardware arrives, install a prepared **debug** production APK and run the
native probe before starting a Linux guest:

```bash
python3 tools/test_androidxr_native.py --serial HEADSET_SERIAL --seconds 20
# Then start a normal DroidDeck Steam session and use Enter SteamVR:
python3 tools/test_quest_xr.py --serial HEADSET_SERIAL
python3 tools/test_quest_xr.py --serial HEADSET_SERIAL --require-controllers
```

The probe uses `native_probe=true`, skips guest startup and frame receivers, and
renders a simple grid through the native swapchains. It exports native health
telemetry. It is hardware-only and has not been run on a compatible device.
Release builds ignore the probe extra. The existing Quest validator also works
for the normal shared frame bridge; its `controllersActive` field means the two
virtual action devices, not proof of physical controllers or individual joints.

Validate in order: native startup/grid and pose telemetry; guest presentation;
SteamVR dashboard; lightweight game. Check measured head translation and rotation,
left/right identity, pinch and supported grasp, occlusion and reacquisition,
neutral rearming, focus/pause/resume, permission/settings changes, recenter and
floor handling, controller/profile switching, all input releases, image/pose
alignment, and sustained frame rate/thermal behavior. Repeat ordinary Quest,
phone, Cardboard and display-glasses smoke tests. None of these hardware results
can be inferred from a successful Jetpack demo or synthetic two-hand fixture.
