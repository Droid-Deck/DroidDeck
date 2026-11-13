# VITURE color-camera hand tracking plan

Status: proposed on 2026-11-12. This plan does not enable a new tracking path or change the APK.

Follow-up work is tracked in [DroidDeckXR issue #3](https://github.com/informalTechCode/DroidDeckXR/issues/3).

The first target is useful hand gestures and pointing from the regular VITURE Beast's single color camera. The same provider can serve Luma Pro, Luma Cyber and Luma Ultra when their imported SDK reports a supported camera. Phantom Beast is excluded because it has no camera.

Color may improve detection, but that remains a hypothesis. A better-looking preview does not establish better hand detection, depth accuracy or latency. We will measure color's contribution separately from differences in camera resolution, field of view and image processing.

## Constraints and existing work

The VITURE 2.4.2 camera API specifies a fixed 1920×1080, 30 fps MJPEG stream. Each callback includes dimensions, a sequence number and a nanosecond timestamp; the image bytes are valid only during that callback. Camera IDs must come from the SDK's query for the connected glasses PID, followed by validation of the attached camera. The documented camera is `0c45:6368` for Beast and `0c45:636b` for the supported Luma models. These IDs alone do not identify a particular glasses connection. The timestamp's clock domain and relationship to exposure are not documented, so receipt timing and clock mapping need measurement. The camera API does not supply RGB intrinsics or exposure controls; the existing Carina exposure controls apply to Ultra tracking cameras. [VITURE camera API](https://www.viture.com/developer/glasses-sdk/glasses#camera-streaming)

MediaPipe supplies 21 image landmarks, handedness and hand-centered 3D landmarks. Its world landmarks use meters with the hand's geometric center as the origin; they are not a measured camera-to-hand translation. A single camera can support gestures and estimated depth, but it cannot provide the stereo depth measurement used by the current Ultra pipeline. [Google Hand Landmarker](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker)

Relevant code already in the repository:

| Component | Reuse and required change |
| --- | --- |
| `glasses/VitureCamera.kt`, `glasses/VitureCameraWorker.kt`, `cpp/viture/sdk_camera_bridge.cpp` | USB permission, SDK loading, bounded latest-frame storage, lifecycle and detach handling are reusable. The shared worker serializes camera capture across preview instances; retain the bridge's `mjpeg_payload.h` normalization when extracting the frame source. The preview currently discards timestamps and sequence numbers, decodes half-size RGB565 images and polls around 15 fps. Tracking needs a frame source independent of the preview dialog. |
| `handtracking/HandCamera.kt`, `handtracking/HandModel.kt` | Reuse model loading, CPU inference, crop tracking and thermal limits. Do not reuse the phone pipeline unchanged: it uses body pose, wrists and shoulders to find hands, whereas a glasses camera may see hands without the wearer's body. |
| `handtracking/BodyPose.kt` | Its hand-centered landmark translation solver is a starting point for approximate camera-relative depth. Validate its axis and scale assumptions before reuse; it is not a stereo reconstruction. |
| `glasses/StereoHandEngine.kt`, `HandGestures.kt` | Reuse gesture hysteresis, loss/rearm behavior and diagnostic conventions. Keep the existing stereo reconstruction and calibration available on Ultra. |
| `glasses/SpatialTracking.kt`, `SpatialPose.kt` | Existing spatial hands are coupled to a valid positional head session. Beast needs an explicit hand-input source independent of Ultra 6DoF; do not fake a valid spatial head pose to satisfy this gate. |
| `handtracking/HandCameraShm.kt`, `cpp/waylandcomp/src/spatialpose_shm.h` | Existing consumers make different coordinate and validity assumptions. Decide the publication contract before enabling estimated RGB controller positions in SteamVR. Any shared-memory change requires matching app, driver and runtime builds. |

## Phase 1: a shared frame source and an evidence-producing prototype

Extract a `VitureRgbFrameSource` from the preview implementation. A single owner manages camera permission, USB connection and native start/stop. Preview and hand inference receive separate subscriptions to that owner; opening a preview must not create a second provider, and closing it must not stop explicitly enabled hand tracking. Stop the provider when the last consumer releases it. Suspend or close it on session shutdown, detach, permission loss and app lifecycle changes appropriate to the active session.

Pass one immutable frame packet across JNI containing JPEG bytes, dimensions, SDK timestamp, sequence, host receipt time and connection generation. Keep one pending frame and at most one inference in flight. Count replaced, intentionally skipped and rejected frames separately. Copy callback bytes promptly; perform decoding and inference outside SDK callbacks. Verify reconnects and timestamp discontinuities invalidate pending observations.

Measure the camera clock against the host monotonic clock before combining observations with head poses. Until that mapping is validated, label timing as receipt-to-result age rather than capture latency and do not claim synchronized world-space output.

Decode tracking input to RGB/ARGB8888. Start with a 960×540 analysis image and the existing verified full hand model on CPU. Keep full-resolution source coordinates and the resize/crop transform; benchmark resolution and crops before changing defaults. Preview can use a cheaper independently throttled image. The model should scan for up to two hands without requiring a body detector, then track individual hand regions between discovery passes. Verify left/right identity, image rotation and mirroring using real left and right hands; phone screen rotation must not silently rotate the glasses-camera coordinate system.

Use a serialized video-mode detector with increasing observation timestamps as the initial implementation. An asynchronous live-stream implementation is an alternative, not an extra queue: it also needs bounded work and must retain the original observation time when results return. Android's live-stream API may drop input while busy. [Google Android guide](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker/android)

First expose a diagnostic view with landmarks, handedness, pinch/grip state, capture/receipt/inference times and confidence. Do not publish controllers yet. Images remain transient by default; an explicit diagnostic capture can save a bounded recording for replay.

## Phase 2: gestures and pointing on Beast

Add a source selector under Tracking: Off, Ultra stereo where available, and Glasses color camera where the SDK and connected camera agree. Keep the existing source as the default on Ultra until comparison results justify a change. Camera permission and the tracking-model download remain explicit; the VITURE SDK remains user-imported and absent from the APK.

Enable pinch/release, grip/release and a pointing ray first. These can use image landmarks and normalized hand geometry without presenting guessed depth as measured position. Calibrated camera direction can drive a head-relative ray; a screen-space interaction mode can precede world-oriented pointing. Require an observed open hand before arming and after tracking loss. Missing, stale or ambiguous hands release buttons immediately and require rearming; a visual pose hold must never refresh an observation timestamp or keep a button pressed.

Keep detector confidence, handedness confidence, gesture validity and position validity separate. A weak depth estimate must not suppress an otherwise reliable release, and a plausible palm pose must not imply a valid pinch during fingertip occlusion. Reuse the existing 200 ms maximum input-age guard, with a lower normal latency target. Test crossed hands, partial hands at image edges and identity swaps explicitly.

Beast remains a 3DoF headset. Its orientation can align a hand ray with the view, but the camera does not add positional head tracking. When built-in Beast tracking is active, retain the existing host/native tracking coordination and avoid applying head rotation twice.

A world-oriented ray requires a fresh host orientation sample. If built-in tracking suppresses the host pose stream, retain screen-space gestures or offer an explicit switch to DroidDeck tracking; do not silently invent a transform or change the glasses' tracking mode.

## Phase 3: optional estimated 3D hand/controller positions

Only start this phase after gestures work reliably. Calibrate the RGB camera's intrinsics, distortion and camera-to-head transform at the stream geometry in use. Do not reuse Ultra stereo calibration or assume equal optics from a shared USB camera ID. Store profiles by glasses identity when available, model, camera identity, dimensions and calibration version; distinguish a validated device profile from a model approximation.

Combine image landmarks, hand-centered model geometry, calibrated projection and a hand-size prior to estimate camera-relative translation. Evaluate the existing translation solver against a robust pose fit with reprojection checks. Reject inconsistent bone lengths, negative or implausible depth, edge crops and abrupt depth changes. Optional measured palm width can constrain scale; never imply that a model's nominal metric landmarks remove monocular scale ambiguity.

Use timestamp-aligned head orientation and camera-to-head geometry to transform the estimate. On Ultra, a valid synchronized 6DoF head pose can anchor it in the spatial session. On Beast, publish explicitly estimated head-relative controller positions under its 3DoF origin. Head translation remains unobserved. Do not turn on the existing positional-head validity flag just to publish hands.

Define source, coordinate frame, estimated-position status, confidence, timestamp and generation in the controller interface. If existing shared-memory consumers cannot represent this honestly, version the contract and update the native driver and validation tests together. Keep stable gesture/ray input available when estimated position is rejected. RGB/stereo fusion and RGB-assisted Joy-Con tracking are later work, after the independent sources have measured performance.

## Validation and release gates

Use two separate comparisons:

1. **Color contribution:** replay the same RGB frames as full color and as grayscale replicated into RGB channels. Keep frame size, crop, model, thresholds and timestamps identical. Label whether hands and gestures were actually visible; processed-frame counts alone are not an accuracy metric.
2. **Usable tracking systems:** compare RGB tracking with the existing Ultra stereo path over the same interactions. Account for each camera's field of view. Repeat RGB-only acceptance on a physical regular Beast; Ultra RGB success cannot substitute for that hardware check.

Cover bright, dim, backlit and cluttered scenes; open and closed hands; fingertips facing the camera; fast motion; two hands crossing; partial occlusion; and several hand sizes and skin/background contrasts where test participants are available. Measure precision/recall, missed and false presses, loss duration, reacquisition time, handedness swaps, stationary jitter, depth error where applicable, p50/p95 observation age and end-to-end input delay. Report decode, inference, USB load, battery/thermal behavior and game-frame pacing separately. Do not change the color camera's exposure through the Ultra tracking-camera controls.

Initial engineering targets, to be reviewed against the measured baseline rather than reported as achieved:

| Gate | Target |
| --- | --- |
| Frame transport | No unbounded queue or duplicate provider; timestamps stay attributable across reconnects; all consumers release cleanly. |
| Gesture input | At least 95 of 100 deliberate pinch/release trials recognized per supported test scene; no stuck presses and no false activation in a 30-minute ordinary-use run. |
| Freshness | Normal p95 observation age at most 150 ms; never accept input older than 200 ms; visible loss always releases input. Initially measure receipt-to-result age until the SDK capture clock is validated. |
| Color hypothesis | A reproducible reduction in missed hands or loss duration at matched false-press rate on the same-frame test. If absent, describe the feature as camera compatibility rather than a demonstrated color improvement. |
| Estimated depth | Report error against measured distances and motion; withhold general controller-position support until suitable interaction-specific tolerances and confidence rejection are demonstrated. |
| Sustained use | A 20-minute test with gameplay shows stable queue age and memory, acceptable thermal behavior and no material game-frame pacing regression against the existing tracking baseline. |
| Hardware | Regular Beast RGB, an Ultra stereo regression run, unsupported-model/Phantom gating, USB detach/reattach, permission denial, foreground/background, preview plus inference, and session teardown all pass. |

Unit tests should cover timestamp mapping and resets, bounded frame delivery, crop and orientation transforms, handedness continuity, calibration mismatch, geometric rejection, loss/rearming and source switching. Use deterministic saved-frame tests for model behavior and separately measure real camera-to-input latency on the phone. Run SDK-free packaging checks for every prototype build.

The first implementation deliverable is the shared frame source plus a two-hand diagnostic overlay and the color/grayscale replay experiment. Its results determine whether to proceed to gesture input, whether color improves reliability, and whether estimated 3D positions are worth the additional calibration and latency cost.
