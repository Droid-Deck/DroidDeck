# Luma Ultra hand tracking investigation

Date: 2026-10-16. Status: **experimental; gameplay usability has not passed**.

There is room to improve the current implementation, but the available evidence does not support calling another smoothing adjustment a gameplay fix. The strongest current blockers are rejected palm reconstruction, interrupted gesture continuity, and processing latency under SteamVR load. The next useful milestone is a repeatable open → pinch → release interaction with a stable aiming pose, followed by sustained in-game testing.

## What the existing evidence establishes

The latest recorded post-installation interaction is `build/spatial-live-hands-sticky-grab-updated.json`, summarized by `build/sticky-grab-updated.log`. It belongs to Android process 17867 and SteamVR session `2026-10-16-60-steam`, using the stability candidate before the fork migration. It is historical evidence, not a measurement of the changes made during this investigation.

| Finding | Evidence | Interpretation and limits |
|---|---|---|
| Head tracking remained available while hands failed | All 2,823 coherent channel samples had fresh head tracking; right hand was fresh in 1,446 samples (51.2%) | The observed interruption is in the hand path. This is sampled channel availability, not accepted-camera-observation rate; the user's exact hand visibility was not recorded. |
| Palm acceptance is a major failure point | Five of eight status snapshots reported matched/reconstructed hands but zero accepted palms | Detection alone is insufficient. The old message grouped missing palm anchors, implausible width, degenerate orientation, and temporal outliers together. It does not identify which gate caused each rejection. |
| Gesture continuity can prevent clicks entirely | The right-hand trace contained valid open and pressed pinch values, separated by loss transitions | Requiring a fresh observed release after loss is correct, but unreliable tracking can prevent a usable press sequence. See the replay below. |
| Processing already consumes most of the latency target | Eight reported processing times ranged from 63.3 to 114.0 ms; observation ages from 63.6 to 155.7 ms | These are internal status samples, not physical-motion-to-display latency. Some exceed the complete external p95 target of 100 ms before rendering is counted. |
| The run was thermally constrained | Android thermal status was 4; HAL skin temperature increased from 53.9 to 54.9 °C | This run cannot establish cold performance or isolate thermal throttling from game workload. |
| Latest stability changes did not establish usable interaction | The user reported similar stationary jitter and inability to start a dashboard drag | The previous candidate's automated tests and successful installation did not establish a hardware interaction pass. |

The trace used CPU, full model, concurrent stereo inference, automatic input rate, and scheduled tracking. All eight diagnostic snapshots selected the two-hand search bank and a 500-permille camera copy rate. Approximately 25 Hz camera delivery therefore supplied at most about 12.5 eligible stereo pairs per second before processing and rejection. The left-hand channel had only 14 fresh samples; this was not a controlled two-hand visibility test, so that number is not left-hand detection accuracy.

### Why a detected pinch may produce no click

The right-hand transition list spans 15.885 seconds and contains 142 rows. Applying the current trigger rules from `tools/vr/src/spatial_tracking.h` to those sampled rows gives:

| Trace quantity | Count |
|---|---:|
| Fresh, valid new observations below the 0.25 release threshold | 31 |
| Fresh, valid new observations above the 0.65 press threshold | 12 |
| Changes in the right trigger's loss-counter byte | 16 |
| Valid-palm transition rows with invalid pinch geometry | 58 |
| Above-threshold observations while the replayed trigger remained armed | 0 |

Replay starts disarmed because the first sampled hand is invalid. It compares the low byte of the right hand's `epochs`, releases on invalid/stale hand or pinch data, ignores duplicate observations for arming, checks the 200 ms gap against the previous accepted hand timestamp, and arms only on a fresh valid trigger value below 0.25. A valid palm with invalid pinch still advances the hand observation timestamp, as in the driver.

This is an inference from a sampled shared-memory trace. Sampling may omit intermediate observations, and this replay does not record what SteamVR or its dashboard consumed. It nevertheless gives a concrete explanation consistent with the reported inability to start a drag: the recognizer produces high pinch values, but continuity is lost before those values can form an armed press. Removing the release requirement would reintroduce presses on reacquisition and would not repair the observation stream.

## Prioritized work

1. **Measure each rejection before changing acceptance.** Count processed observations, valid palm/gesture observations, and rejection reasons separately for each hand. Distinguish no matching pair, missing triangulated palm anchors, palm width, degenerate orientation, implausible motion, synchronization, and age. Keep cumulative counters separate from sparse UI snapshots. These counters need no camera image recording. A broad reduction in stereo-quality thresholds could trade disappearing hands for incorrect depth and jumps.

2. **Establish one reliable interaction sequence.** With a single visible hand at 30, 50, then 80 cm, repeat open → pinch → hold → release while collecting the observation counters and existing driver input-transition logs. Repeat for the other hand, then with both visible. Record whether failure happens before palm acceptance, at pinch validation, at arming, at OpenVR publication, or inside the dashboard. The dashboard uses trigger for click/drag; the installed Vive binding maps grip to Back. Keep the other fingers open during the pinch test so the two actions remain distinguishable.

3. **Reduce measured processing cost and discovery spikes.** Run temperature-matched comparisons of full/scheduled CPU, lite/scheduled CPU, and input-rate settings, retaining the same game scene and deliberate motions. Measure completed-observation rate and accepted-observation rate, not camera rate or repeated channel reads. Existing saved-open-palm benchmarks show potential: under SteamVR, scheduled full inference had median/p95 46.92/94.71 ms versus full-search 91.48/99.26 ms; scheduled lite gave 40.26/86.77 ms. Those static-image results cannot establish moving-hand, pinch, or crossed-hand accuracy. The full model should remain the baseline until those comparisons pass. GPU requires a separate stability check because earlier evidence records graphics recovery/device-loss concerns.

4. **Tune pose and aiming with motion evidence.** Record stationary raw and rendered pose distributions and a repeatable slow aiming path. Current driver stabilization already holds position within 3 mm and orientation within 1 degree around a stationary anchor, applies a 15 ms rendering correction, and bounds prediction to 2 cm/80 ms. Increasing smoothing without measuring raw noise and lag risks making aiming less responsive. Test motion-aware filtering or a more stable aim direction only against the same recorded motions and external latency measurement. Aiming direction and a game controller's grip pose may need separate treatment.

5. **Validate game compatibility after continuity.** Optical controllers currently expose Vive-compatible trigger/grip/trackpad inputs, with gamepad sticks and buttons supplying locomotion and menus; there is no skeletal hand output. `CardboardController::UpdateOptical` initializes `DriverPose_t` to zero and populates position and rotation, leaving linear and angular velocity zero. This is a source-confirmed omission to investigate for throwing and other velocity-dependent games, not an established cause of the dashboard problem. A velocity implementation needs capture-time derivatives, outlier/loss resets, a defined pose timestamp, and a game-level test before enabling it.

Startup should be measured separately from steady state. Earlier logs contained shader-pipeline creation well after the dashboard appeared, while both hand detectors were already initialized. Correlate observation latency and CPU/GPU workload through launch and the following two minutes; model warmup alone does not explain the reported minute-long startup delay.

The exported calibration also helps explain why a 3 mm stationary hold may have little visible effect. Its focal lengths are approximately 226–241 pixels and its stereo baseline is approximately 14 cm. The ideal rectified-stereo approximation `depth change ≈ depth² / (focal length × baseline) × disparity change` gives roughly 8 mm per disparity pixel at 50 cm and 20 mm at 80 cm. These are sensitivity estimates, not measured hand errors; the actual cameras have lens distortion and relative rotation. Independent landmark jitter can therefore exceed the driver's hold radius. If the new counters confirm palm reconstruction dominates, a robust fit across the available palm landmarks and temporal stereo correspondence are stronger candidates than simply enlarging that hold radius. Such a fit still needs explicit outlier rejection, side identity, capture-time freshness, and measured motion/gesture tests.

## Vendor runtime options

The public [VITURE Glasses C SDK documentation](https://www.viture.com/developer/glasses-sdk/glasses) describes USB management, head tracking, display control, and camera streaming, including Android support. It does not document a phone hand-tracking runtime that can replace this application's MediaPipe reconstruction directly. That is a statement about the published API reviewed on this date, not proof that VITURE has no private or forthcoming implementation.

The [VITURE Unity SDK documentation](https://www.viture.com/developer/unity-sdk/unity) does describe hand tracking, responsive/stable filter modes, and arm/wrist contributions to aiming. Its stated hardware requirement and deployment instructions target the Pro Neckband. Those controls provide useful design references, but the documentation does not establish that their runtime can be redistributed inside this phone app. Confirm phone runtime availability, platform support, and licensing with VITURE before planning that replacement.

## Calibration and completion criteria

The phone's current calibration can be shipped as the Luma Ultra starting profile. That reduces setup friction; it does not fix inference cost, hand occlusion, gesture continuity, or jitter by itself. Preserve each device's own calibration as the higher-priority choice, restrict the base profile to its matching model and camera geometry, and identify it as a shared starting calibration. A calibration validated on the source glasses has not been independently measured on every Luma Ultra unit.

Retain the existing acceptance gates in `luma-spatial-tracking.md`: at least 95% accepted slow unobstructed hand observations at 30–80 cm, no more than 5 cm position error and 2 cm stationary jitter, external p95 latency below 100 ms, reliable release and reacquisition, and no more than 10% regression in p95 game frame time over a 20-minute run. Dashboard selection and Angry Birds VR aim/grip/pull/release still require deliberate physical validation. Keep the feature visibly experimental until these tests pass.

Supporting records: `luma-spatial-evidence.md`, `build/spatial-live-hands-sticky-grab-updated.json`, `build/hand-reliability-benchmark-smoothness-steamvr.json`, and `build/hand-startup-review.json`. The build-directory records are local historical artifacts and may not accompany a fresh checkout.

## Joy-Con grip and arm extension investigation (2026-10-16)

The target is a closed fist holding a controller, with the wrist and forearm visible from the
wearer's viewpoint. Open-palm performance is not sufficient evidence for this use case. In a
requested 16-second extension exercise, 5 of 80 sampled left positions and 1 of 80 right positions
were valid while head tracking stayed valid. These are channel availability samples, not 80
independent detector results. The earlier driver also restarted its transition to the arm model
on each invalid hand frame, preventing intermittent camera positions from converging.

The initial grip estimator required the wrist, middle base knuckle, and one supporting base
knuckle, rather than all five palm landmarks and a hand-derived orientation. The later
wrist-only update below removes the remaining knuckle dependency. It never generates hand
gestures; Joy-Con IMU and buttons remain authoritative. Stereo optical flow can carry the
identified wrist through brief model misses, with forward/backward consistency, photometric,
stereo reprojection and motion checks. Tracks expire 750 ms after the last detector seed; frame
gaps over 200 ms discard them. The driver retains a camera observation only until 200 ms after
its original capture time. Neither mechanism turns stale coordinates into fresh measurements.

### Model assessment

- **MediaPipe Full (current baseline):** an existing Android pipeline with separate palm
  detection and landmark regression. Curling fingers does not intrinsically exclude a hand,
  but occlusion and the egocentric view must be tested with actual grips. Its published
  monocular world landmarks are not used as metric camera distance; calibrated stereo supplies
  distance. [Official task documentation](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker).
- **Bundled Lite:** the local bundle changes the landmark regressor while retaining the Full
  palm detector (see `tools/build_hand_lite.py`). It can reduce processing cost, but cannot be
  assumed to fix the initial fist-detection problem. Earlier saved open-palm replay under
  SteamVR measured scheduled Full median/p95 46.92/94.71 ms and Lite 40.26/86.77 ms. Those figures
  do not establish closed-fist accuracy.
- **RTMDet-nano hand detector plus RTMPose-m Hand5:** a concrete alternative detector and
  landmark pipeline worth evaluating against grip frames. The official demo uses separate
  hand boxes and landmarks. It is not a replacement `.task` asset: Android inference,
  preprocessing, handedness/identity association, and device timings need integration and
  verification. No claim of superior Joy-Con performance is established yet.
  [Official hand demo](https://github.com/open-mmlab/mmpose/blob/main/demo/docs/en/2d_hand_demo.md).
- **HaMeR:** useful as an offline reference for occluded and egocentric hands, with published
  evaluation procedures. Its reference implementation uses PyTorch, ViTPose, and MANO assets;
  it is not an established real-time Android replacement for this app. Do not substitute its
  inferred mesh depth for measured stereo depth.
  [Official implementation](https://github.com/geopavlakos/hamer).
- **Whole-body arm pose:** not the first candidate for cameras on the wearer's glasses,
  where the wearer's shoulders and torso are commonly outside view. An egocentric fist/wrist
  detector or forearm segmentation model is the more directly matched development target.
  This is an engineering assessment, not a measured comparison of those models on this device.

The third camera is a separately exposed UVC pass-through camera, not an unused member of the
stereo callback. It may provide better RGB recognition, but requires its own capture/decode and
camera-to-head calibration. It does not independently provide metric depth. The preferred use
would be recognition or association assistance while stereo supplies distance; size-based
monocular distance would need separate calibration and uncertainty handling.
[VITURE SDK camera support](https://www.viture.com/developer/glasses-sdk/glasses).

Saved-grip replay reports `joyCons` acceptance separately from bare-hand `hands`, for both Full
and Lite. Repeating one image measures model/geometry behavior on that image, not motion
continuity. Live tests must still check reach response, left/right identity, reacquisition,
camera loss, and head motion before considering the experiment reliable.

### Closed-fist model comparison

Three requested grip pairs were saved locally as captures `110935538464606`, `110939418512521`,
and `110943418461791`. In on-phone saved-frame replay of the middle and last pair, Full detected
one hand in the left image and none in the right image in all 20 measured repetitions per
configuration; Lite detected none in either image. Neither produced a valid grip position.
On the middle pair, scheduled Full median/p95 was 56.05/62.96 ms and Lite 46.00/47.99 ms. On
the last pair, it was 21.17/23.33 ms and 18.08/20.06 ms respectively. Timing differences between
frames are not accuracy improvements. Repeated static frames are not independent observations.

An isolated Windows evaluator tested the official RTMDet-nano hand / RTMPose-m Hand5 ONNX
exports using `rtmlib` 0.0.16 and ONNX Runtime 1.31.0. Across the six raw images, hand-box counts
were 0, 0, 1, 0, 2, 1. Lens-corrected images gave 0, 1, 1, 0, 2, 1. Neither variant found both
hands in both cameras for any pair. These are box counts, not validated stereo positions or
ground-truth accuracy. Desktop timings cannot predict Android cost. Model downloads and hashes
are in `build/joycon-alternative-models/provenance.json`; results and overlays are local.

MediaPipe desktop preprocessing comparisons used the same Full model asset with Python Tasks
1.1.1, versus Android Tasks 1.0.0. Overlapping 400-pixel crops recovered some missing detections;
lens correction and lower thresholds also changed detections and sometimes handedness. Only
isolated stereo matches passed the existing geometry checks. This does not justify enabling
either preprocessing change in the live pipeline yet, or replacing Full with Lite/RTMPose.

The post-install shared-memory sample arrays in `build/joycon-reach-after.json` and
`build/joycon-reach-grip.json` are not valid live-pose measurements: the SteamVR session had
not restarted, so its pose publisher was inactive. Their before/after provider diagnostics and
requested image captures remain useful. During the actual closed-fist interval, all 253
processed pairs reported invalid head tracking, so no live reach claim can be made. The local
measurement script now requires a running session, valid head tracking, and advancing head
timestamps to reject this situation. The image comparisons above do not depend on a live head
pose. A new live test is still required after head tracking and the session recover.

### Third-camera grip comparison

The separately exposed RGB camera delivered 1920 by 1080 JPEGs after Android Camera and USB
permissions were granted. SDK 2.4.0 crashed in `xr_camera_provider_start` when reopening the
provider in the main app process. RGB diagnostics now run in a dedicated process that skips
normal app initialization. Four initial successful captures preserved the main app PID.
Subsequent one-shot attempts intermittently returned zero callbacks despite a successful SDK
start. Keeping a stream open between requests did not resolve this: four successive startup
attempts returned zero callbacks. USB reconnection and a separate attempt with positional
tracking temporarily disabled also returned no frames. The user's decision was to stop the RGB
route and compare the models on stereo. The persistent-stream and RGB/stereo calibration-buffer
prototypes were removed; the isolated one-shot command remains for manual diagnostics only.

Three requested normal-grip RGB captures (`112581486574499`, `112588468819444`, and
`112595950634337`) were evaluated with the same local model assets and thresholds:

| Pipeline, full image | First | Second | Third |
|---|---:|---:|---:|
| MediaPipe Full | 0 hands | 0 hands | 2 hands |
| MediaPipe Lite | 0 hands | 0 hands | 1 hand |
| RTMDet-nano + RTMPose-m Hand5 | 2 hands | 2 hands | 2 hands |

Overlapping square crops recovered one grip in the first image with Full and Lite; neither
recovered a hand in the second. The no-hands control image produced zero detections for all
three pipelines. Visual inspection of the alternative model's overlays found plausible wrist
locations on these grips, including the closer, partly cropped second view. Three selected
images are not an accuracy dataset or evidence of continuous tracking, identity preservation,
or measured reach. RGB results and overlays are in `build/joycon-rgb-evaluation`.

The unchanged official ONNX models were also replayed on the Fold7 using Android ONNX Runtime
1.31.0 and OpenCV 4.13.0 preprocessing. Four warm-ups and ten timed iterations per image gave
two-thread median times of 119.90, 119.88, and 129.24 ms; the largest sample was 151.04 ms.
Four threads gave medians of 120.45, 120.79, and 121.05 ms. Timings include preprocessing,
detection, and two hand landmark passes, but exclude JPEG decoding, camera acquisition,
stereo depth, head fusion, rendering, and concurrent SteamVR load. Thus they already exceed
the 100 ms end-to-end target. The app's OpenCV DNN importer alone rejected the detector's TopK
operator; these measurements used a separate native benchmark, not a new production model
dependency. [ONNX Runtime mobile support](https://onnxruntime.ai/docs/get-started/with-mobile.html).

The RGB SDK timestamps were zero on this device. Diagnostics record host callback receipt
separately. A temporary prototype successfully collected one nearby stereo pair with surrounding
frames, but the subsequent board attempt received no RGB frame. No RGB calibration was fitted.
Existing stereo calibration does not supply RGB lens parameters or the RGB-to-stereo transform.
RGB has never been used to publish controller positions in this experiment.

### Stereo-only follow-up and wrist landmark 0

The alternative RTMDet/RTMPose pipeline was replayed on the Fold7 against the same three raw
stereo grip pairs, without RGB. With live Joy-Con inference temporarily paused, counts were
0/0, 1/0, and 2/1 hands in the left/right images. Per-image medians were 45.63/45.42,
129.35/45.03, and 199.50/123.13 ms. These are sequential single-image benchmark measurements,
not concurrent stereo latency or end-to-end tracking. Both candidate pairings in the last
capture failed the current geometry gates. The plausible wrist pair projected with 4.17 px
and 3.27 px error, exceeding the 3 px limit; the other pairing reconstructed behind the camera.
Neither a valid grip nor a valid isolated wrist was established. The log and geometry report
are `build/joycon-stereo-phone-ort-controlled.log` and
`build/joycon-stereo-phone-ort-geometry.json`.

“Full” in this project is the full MediaPipe Hands landmark model, not a full-body model.
At the time of the model comparison, the live Joy-Con estimator used the midpoint of wrist 0
and middle-finger base 9, validated with an additional base knuckle. The saved-pair diagnostic
added `wrists`, testing landmark 0 without those palm anatomy requirements. This cannot
recover a wrist when the upstream hand detector misses the grip in either camera.

The existing desktop Full outputs at the production 0.6 thresholds, using raw uncropped
images, gave confident left/right counts of 0/0, 1/0, and 0/0. Thus none offered a wrist
pair even before palm geometry checks. Desktop Tasks 1.1.1 static-image evaluation differs
from Android Tasks 1.0.0 VIDEO replay and must not be presented as an Android measurement.
The local report is `build/joycon-mediapipe-wrist-evaluation.json`. The updated Android
wrist-only diagnostic was built and installed, but its replay is pending the glasses USB
approval after installation. The last phone settings check showed positional tracking on,
Joy-Con tracking off, and hand controllers off; there was no active stereo engine.

Keep MediaPipe Full as the live baseline. The alternative's better RGB detections did not
transfer to dependable stereo grip positions on these captures. No new physical arm-extension
validation has been completed.

### Live wrist-only Joy-Con position

Following the user's correction, Joy-Con mode triangulates only landmark 0 and publishes its
position transformed through the camera-to-head and captured head poses. Knuckle availability,
palm proportions, and the wrist/knuckle midpoint no longer affect Joy-Con acceptance or position.
The estimator is `JoyConWristPosition`, and diagnostics report `joycon-wrist` with wrist-specific
missing-landmark and motion outcomes. Bare-hand gesture tracking still uses its palm geometry.

Optical flow now seeds only the wrist and accepts a single tracked point per camera; the
previous three-point minimum is removed. Forward/backward and photometric checks, stereo
depth and reprojection limits, motion rejection, head synchronization, freshness, and track
expiry remain in place. Controller rotation and buttons still come from the Joy-Con, and
the driver still falls back to the arm model when fresh camera positions are unavailable.
This removes the unnecessary knuckle dependency, but does not bypass MediaPipe's hand
detector or establish continuous wrist visibility in the saved grip images.

### RGB transport retry: hidden USB submission failure

After the wrist-only test did not meet the user's needs, the RGB investigation resumed.
Capture `116048611549114` added stderr collection with `LIBUSB_DEBUG=4`, separate from the
vendor log hook. The SDK returned start status 0 and streaming status 1, but libusb reported
`submit_iso_transfer: submiturb failed, errno=12` (`ENOMEM`) and subsequent isochronous packet
errors. No RGB callbacks arrived. GET_INTERFACE showed alternate setting 5; the negotiated
UVC payload was 2400 bytes per microframe. This establishes USB allocation/submission failure,
not its exact resource limit: Android denied reading the USB memory-limit sysfs attribute.

Binary inspection also traced the earlier native crash to `libusb_cancel_transfer(NULL)`
while cleaning up a failed start. Reopening alone was therefore too specific an explanation.
The embedded SDK's roughly 100-transfer queue is a plausible source of pressure. Its public
API cannot change the queue or the fixed 1080p30 mode.

An optional independent diagnostic transport now uses libusb 1.0.30 and libuvc 0.0.8 with eight
queued transfers. The pinned libuvc source was patched to preserve submission errors and safely
drain partial queues instead of reporting success. A hardware-free executable passed 60 starts
on the phone covering failure before any submission, failure after three submissions, and all
eight submitted. This validates failure cleanup, not camera capture. The reproducible recipe,
source hashes, and licenses are documented in `docs/third-party/uvc/README.md`.

Raw USB descriptors advertise MJPEG 640×480, 1280×720, and 1920×1080 among other sizes, with
30/25/20/15/10/5 fps intervals. The direct path can compare these modes while head/stereo tracking
continues. It saves a local JPEG plus transfer diagnostics and stream statistics; it is not yet
connected to controller tracking. RGB lens/extrinsic calibration and measured reach remain
separate unfinished requirements.
