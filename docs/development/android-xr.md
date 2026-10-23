# Shared OpenXR bridge for Android XR and Quest

This experimental integration adds an Android XR entry point while retaining the Quest entry point. Both use one native runtime and the existing SteamVR driver. It does not add a separate dashboard, a camera-based gesture recognizer, or GPU driver overrides.

## Architecture

```text
SessionActivity
  ├─ AndroidXrActivity ─┐
  └─ QuestXrActivity ───┴─ GuestXrActivity
                           └─ xr-core native runtime
                                ├─ OpenXR lifecycle, stereo and tracking
                                ├─ runtime hand actions and joint tracking
                                └─ existing guest frame transport

SteamVR driver
  ├─ head pose and velocity
  ├─ hand actions through existing input components
  └─ OpenXR joints converted to SteamVR skeletal input
```

`xr-core` contains the shared native runtime and JVM entry checks. The existing native activity entry forwards into the shared runtime; it does not duplicate the render loop.

## Entry and lifecycle

Android XR is selected by system features; Quest retains its existing device detection. Feature declarations allow probing and do not prove that an OpenXR runtime is available. Real capability checks happen during native initialization.

The Android XR entry requests hands. Quest retains automatic hand/controller selection. The loader enumerates extensions, checks GLES requirements, creates spaces/actions/stereo swapchains, and starts the guest only after the XR session becomes visible or focused. The Android XR activity uses the full-space unmanaged start property and declares the optional system OpenXR library.

The shared activity associates XR ownership with the current Steam session. Replacing an activity cannot stop another activity's guest. Initialization failures return to the panel with a diagnostic; loss of the guest also ends immersive mode. Closing immersive mode stops SteamVR, so it can interrupt an active game.

Reference spaces prefer STAGE, LOCAL_FLOOR, then LOCAL. LOCAL does not receive a fabricated floor height. A reference-space change ends the guest XR session rather than mixing frames from different origins.

## Hand input

The runtime reads standard `XR_EXT_hand_interaction` actions when available. A capability-selected fallback uses `XR_EXT_hand_tracking` with `XR_FB_hand_tracking_aim`. Platform differences stay in acquisition; transport, SteamVR mappings and release behavior are shared.

Aim and grip remain distinct poses. Runtime pinch or aim activation drives the existing trigger components; runtime grasp drives grip only when that capability exists. The bridge does not invent missing sticks, face buttons or grasp signals. Hand mode keeps input neutral while tracking is unavailable and requires a release before rearming after a discontinuity.

`XR_EXT_hand_tracking` provides 26 joints per hand. The bridge converts their poses into SteamVR's 31-bone parent-space skeleton, including auxiliary bones. Hand render models are separate from ray aim. SteamVR hands and Touch controllers use separately activated device pairs with immutable profiles; replacing a pair disconnects and releases the previous one. A visible Dashboard hand model is not proof that an application's skeletal input works.

Android and Quest hand-tracking permissions are declared. Joint tracking is conditional on permission and actual tracker availability. Standard runtime actions can remain available when joint permission is denied. Loss of focus, expired input, invalid poses or a changed session/origin releases held inputs.

Two runtime pinch values held together request a system-button pulse after releasing trigger/grip, avoiding SteamVR's screenshot chord. This shortcut does not inspect the dashboard state. Its usability, ray orientation and drag behavior still require headset acceptance.

## Gamepads

The immersive activity forwards ordinary gamepad buttons and axes to the existing PadBridge. View/Select + Menu/Start requests a SteamVR system-button pulse. Requests are device-scoped, do not repeat while held, and are not replayed across focus or session changes. A gamepad does not become a tracked hand. Individual applications must support gamepad navigation.

## Transport and timing

Tracking protocol v2 adds head linear/angular velocity and validity flags; all consumers must be rebuilt together. Runtime velocity takes precedence. Continuous tracked pose samples provide a bounded difference fallback when velocity is unavailable, with rejection across invalid tracking, time discontinuities and origin changes.

Input and skeleton sidecars include a session generation, origin epoch and timestamps. Atomic snapshots and a single writer prevent torn packets; stale packets cannot restore old controller state. Header sizes and layouts have C/C++ contract tests.

Frames reuse the existing shared GPU-buffer and DMA-BUF transport. GPU completion must precede buffer acknowledgement. The shared receiver can wait for completion off the display thread, retaining connection ownership during shutdown/reconnect. Existing synchronous completion remains the capability fallback. No new CPU pixel-copy transport is introduced.

The guest display timing can follow fresh OpenXR display timing. Submission frequency is not a game frame-rate measurement. Exact association between SteamVR's final rendered image and its camera pose remains an open validation question; intermittent Dashboard jitter has not been resolved.

## Scope and validation

This change includes the shared runtime, Android entry, hand/skeleton and gamepad bridge, packaging inputs, host tests and main-app integration checks. Device-specific GPU drivers, per-chip render scales, scheduling-priority experiments and local game fixes are excluded.

Host contracts, JVM checks, main-app compilation and selected Android input tests have passed locally. Earlier hardware tests observed SteamVR stereo frames and gamepad operation, but the current upstream-based source has not completed a fresh end-to-end headset regression. Quest controller behavior, skeletal input in applications, hand orientation/drag/menu recall and sustained frame stability still need testing.

The main application is the only APK target in this change. Emulator installation or a successful build does not establish immersive OpenXR or hand-tracking support. See the [runbook](android-xr-runbook.md) for reproducible checks.
