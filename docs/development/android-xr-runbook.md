# Shared OpenXR bridge: build and validation

Run commands from the repository root. Hardware results must identify the tested source revision and distinguish local runtime output from SteamVR guest output. Do not commit account data, device identifiers, screenshots or raw session logs.

## Toolchain

- JDK 17, Gradle wrapper 8.10.2, AGP 8.8.0, Kotlin 2.1.0.
- Android platform 34, build-tools 35.0.0, NDK 27.3.13750724, CMake 3.22.1.
- OpenXR loader 1.1.49.

Configure JAVA_HOME, ANDROID_HOME and optional GRADLE_USER_HOME for your machine. Install the Android inputs:

```sh
sdkmanager 'platforms;android-34' 'build-tools;35.0.0' \
  'ndk;27.3.13750724' 'cmake;3.22.1'
```

## Host tests

```sh
./gradlew -PxrCoreOnly=true :xr-core:test --no-daemon
cmake -S xr-core/src/main/cpp -B build/xr-contracts -DCMAKE_BUILD_TYPE=Debug
cmake --build build/xr-contracts
ctest --test-dir build/xr-contracts --output-on-failure
python3 -m unittest tools.tests.test_xr_validation \
  tools.tests.test_quest_xr_validation tools.tests.test_runtime_inputs \
  tools.tests.test_windows_vr_build tools.tests.test_prepare_windows_runtime
```

Tests cover protocol layouts, atomic snapshots, writer ownership, invalid/stale input release, coordinate conversions, skeleton reconstruction and head velocity. GPU completion tests require Linux or Android; macOS host tests do not establish Linux GPU synchronization.

## Standalone smoke APK

```sh
./gradlew -PxrSmokeOnly=true :xr-smoke:assembleDebug :xr-smoke:lintDebug --no-daemon
```

This build does not need the full Linux guest. Install it on a compatible headset and use its runtime probe/local stereo scene. A 2D launcher or two created swapchains alone does not prove tracked stereo or hand input.

## Main application

Use the existing full build workflow:

```sh
tools/build_local.sh
```

Docker prepares the existing Linux guest helpers, SteamVR driver and other assets. The VR cache key includes shared native sources. Runtime-input checks require the matching rebuilt guest libraries and hand-model outputs. An old prebuilt guest is not sufficient after protocol changes.

For source integration checks without rebuilding store engines:

```sh
./gradlew -PskipRust=true :app:compileDebugKotlin :app:testDebugUnitTest \
  --tests com.droiddeck.launcher.input.XrGamepadInputTest \
  --tests com.droiddeck.launcher.input.PadBridgeRoutingTest --no-daemon
```

Skipping store engines is a development check, not a full distributable build.

## Linux guest

```sh
VK_INCLUDE=/usr/include tools/vr/build.sh --native
python3 -m unittest tools.tests.test_vr_wsi
```

The native route needs ARM64 cross compilers and Vulkan headers. Its build script runs the existing driver/input regression suite. Use the Docker route in the main build on hosts without these prerequisites.

## Headset acceptance

1. Verify the normal Steam session still launches; sign in without recording credentials.
2. Install SteamVR and enter immersive mode through the existing session menu.
3. Verify fresh tracked head poses, nonzero eye separation and advancing guest stereo images.
4. Grant hand-tracking permission where required. Verify both hands, orientation, ray aim, pinch press/release, sustained drag, tracking loss and recovery.
5. Verify the system-button shortcut reopens the Dashboard without taking a screenshot.
6. With a gamepad, verify axes, confirm/back, menu combination, release/repress, disconnect/reconnect and focus loss.
7. Recheck Quest Touch controllers and hand/controller switching.
8. Test an actual VR application. Report its scene rendering, input and sustained performance separately from SteamVR startup.
9. Compare Dashboard open/closed while turning the head. Record remaining jitter rather than treating high submission counts as smoothness.
10. Verify normal exit, guest stop, failed startup and reentry. Disable temporary debugging access and remove test overrides after diagnostics.

`tools/test_xr.py --help` documents the shared validator. It rejects simulated input and requires live runtime evidence; Android-side action counters alone do not prove that SteamVR consumed the input. `tools/test_quest_xr.py` retains the existing Quest checks.

## CI

The Shared OpenXR bridge workflow runs host contracts, Linux guest tests and smoke APK build/lint. Android SDK setup explicitly installs platform-tools, avoiding the obsolete tools package. Passing these checks does not replace hardware regression.
