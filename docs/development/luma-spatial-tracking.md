# Luma Ultra spatial tracking

This is an experimental implementation of native Luma Ultra head tracking, positional virtual screens, and optical SteamVR controllers. Both settings default to off. Combined release acceptance remains pending until the calibration, accuracy, latency, gameplay, and sustained performance targets below have been measured on hardware.

## Build and setup

Use the existing optional `droiddeck.vitureSdk` local property with the external VITURE Android SDK 2.4.0. No vendor headers or libraries are checked into the repository. `-PvitureSdkDir=` explicitly builds without the SDK. The ordinary runtime freshness checks remain enabled. Rebuild the ARM64 VR components with `tools/vr/build_windows.py`; its manifest includes `spatialpose_shm.h`.

The hand pipeline pins MediaPipe Tasks Vision 1.0.0, OpenCV 4.13.0, and the model and SHA-256 recorded in `app/src/main/assets/models/hand_landmarker.provenance.json`. Both Gradle and the inference worker verify the model checksum. CPU inference is the default. GPU inference is a diagnostic option and needs separate game-performance acceptance.

1. Connect a Luma Ultra and grant its existing USB permission. Spatial controls appear only after the SDK identifies the device as Carina.
2. Enable **Positional tracking — experimental**. This restarts the provider in 6DoF mode. Switching picture modes does not restart it.
3. Enter the current seated or standing eye height (shown in centimeters and feet/inches) and choose **Set floor from current height**. Normal VR recentering then preserves that floor, including while crouched. After reconnecting or explicitly restarting tracking, accept the new origin through setup/recenter. A recenter request during lost/stale tracking or recovery remains pending until 250 ms of fresh, steady poses arrive; repeated taps are unnecessary. The pending request is canceled on disconnect.
4. Open **Stereo camera setup and diagnostics → Open setup**. The page uses the selected DroidDeck theme and app scale. Check head tracking and camera synchronization first; capture controls become available when both are ready. Expand **Show diagnostics** for frame rate, frame age, dropped frames, processing time, and recovery tools.
5. Print the 5 by 7 ChArUco board at actual size. Measure a square: it must be 30 mm (approximately 1.181 inches). Keep the board stationary and capture at least 24 sharp views by moving and rotating the glasses, covering the image center and edges. Include both yaw and pitch, roll, and varied distances. If the underlying tracking frame changes, start a new capture set; software recentering in an unchanged frame preserves the set.
6. Calculate calibration. The fitter compares pinhole and fisheye models on held-out views. It rejects poor stereo reprojection, an implausible baseline, insufficient rotational diversity, and inconsistent stationary-board alignment.
7. Select **20 in (50.8 cm)** or **50 cm (19.69 in)** under **Measured distance**. Show the complete board with its center that distance from the left camera, then choose **Validate distance**. The selected value is passed to validation without treating 20 inches as 50 cm. The physical-distance check must pass within 5 cm (about 2 inches) before the profile becomes usable. Profiles are keyed to device serial, dimensions, and format version. Without a serial they remain connection-local.
8. Enable **Hand controllers — experimental**. Present open hands first, then pinch for trigger and curl fingers for grip. Gamepad sticks supply locomotion and turning; buttons retain the Vive-compatible mapping. External VR controllers retain precedence.

The diagnostic capture button saves one stereo pair and metadata to the app-private `files/spatial/captures` directory. Merely opening diagnostics does not save images. Calibration profiles stay in `files/spatial`. A lost optical hand remains unavailable; it does not turn into a synthetic controller. Switch hand controllers off explicitly to return to gamepad-positioned controllers.

The vendor SDK working directory is `noBackupFilesDir/viture`. Android can evict `cacheDir` during a live session, so active tracking files must not depend on that location. The SDK initializes its own files in the new directory on the next provider start; existing printed-board calibration profiles remain in `files/spatial`.

## Coordinates and lifecycle

`SpatialPose` uses meters and right/up/back OpenGL coordinates. Quaternions are normalized xyzw internally; SDK arrays are position followed by wxyz. Legacy orientation conversion and the 128-byte head-pose channel remain intact. The session coordinator survives `HeadTracker` recreation and owns separate full-pose screen and yaw/floor VR origins. The camera worker uses a generation guard, a bounded latest-frame queue, and no SDK lifecycle operations in callbacks.

Head polling is approximately every 4 ms with prediction disabled; this is not a claim of a 250 Hz sensor. Camera-rate callbacks provide a liveness check. Stale or unstable poses retain the last rendered transform and produce an invalid SteamVR pose. Large pose discontinuities latch a setup requirement. Provider shutdown invalidates queued work before closing the vendor provider and Java USB connection.

The 176-byte versioned `droiddeck-spatialpose` snapshot uses sequence-counter publication. The driver validates file length, version, finite normalized poses, and monotonic age. Head and both hands use one snapshot and one VR origin. Each hand retains its own observation timestamp. Tracking loss releases trigger and grip and requires an observed released gesture before presses can resume.

The screen shader puts translation in `extra.yzw`, rotates the eye offset, and rejects intersections behind the eye. SBS and flat branches ignore translation. The push-constant allocation stays unchanged. A screen reference can include pitch and roll, preserving placement while reclining.

## Stereo reconstruction and limitations

The primary `left0/right0` images are copied only for diagnostics, calibration, or enabled hands. At most one pair is pending while the worker processes another. Automatic exposure is retained because the cameras also serve native VIO. Two video-mode Hand Landmarker instances receive matching timestamps. The pipeline undistorts images, matches unambiguous same-handed candidates across cameras, rejects excessive stereo reprojection or implausible depth, and derives palm pose and gestures from triangulated landmarks. It does not treat MediaPipe world landmarks as headset-relative positions.

The public header does not document the coordinate basis of the camera-rate pose callback. Its pose values are therefore not used as canonical head transforms. A bounded history of polled OpenGL poses supplies the capture-time transform. Source-to-host clock offsets must first stabilize over at least 16 observations; callback timestamp agreement and a nearby canonical head sample are required. Receipt-based offset estimation cannot establish absolute camera latency. External motion-to-visible-controller measurement is still required.

Conservative stereo association rejects ambiguous hands rather than inventing depth. Crossed hands and occlusion may therefore reduce availability. Palm orientation, lens model, image orientation, camera-to-head alignment, and grayscale hand-detection performance need hardware validation. No skeleton output, desktop gestures, fast-action guarantee, or hands-only locomotion is provided.

## Combined release gates

| Area | Required evidence |
|---|---|
| Translation | Measured right/up/back movement and approximately 1:1 meter scale; lean and crouch; no double application |
| Origins | Crouched recenter preserves floor; picture switching preserves independent screen and VR references |
| Screens | Anchoring while moving sideways, toward and away; stable output after crossing the screen plane |
| Hands | At least 95% valid slow unobstructed observations at 30–80 cm; position error at most 5 cm; stationary jitter at most 2 cm |
| Latency | External physical-motion-to-visible-controller p95 below 100 ms |
| Recovery | Release within 100 ms of observation loss; clear-hand reacquisition within 500 ms |
| Performance | At most 10% regression in p95 game frame time over 20 minutes, with baseline frame rate maintained |
| Gameplay | Dashboard selection and Angry Birds VR aim, grip, pull, release |
| Robustness | Crossed hands, partial occlusion, head motion, lighting, USB reconnect, thermal load |

Record results in `luma-spatial-evidence.md`. A build or head-tracking pass alone does not satisfy this combined gate.
