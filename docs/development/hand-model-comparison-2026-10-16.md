# Full versus Lite hand models and image preprocessing

The saved-image comparison favors the Full palm detector and Full landmark model.
Neither contrast normalization nor lens correction improved usable bare-hand detections
on the two available bare-hand scenes. This is a small static corpus, not a live tracking pass.

## Method

The same saved 640 × 480 grayscale stereo pairs were evaluated with eight configurations:
Full or Lite palm/landmark bundles, each with raw input, calibrated lens correction,
1st/99th-percentile contrast normalization, or both corrections. Grayscale-to-RGB replication
and MediaPipe's model-required tensor scaling were enabled in every configuration.

The main comparison ran on the Samsung SM-F976U using the app's Android MediaPipe Tasks
1.0.0 and OpenCV 4.13.0. Each configuration created fresh CPU VIDEO detectors, with two
hands per camera, parallel camera inference, and all detection/presence/tracking thresholds
at 0.6. Adaptive input and scheduled one-hand inference were excluded. Each pair received
one cold inference, four warmups, and twelve measured repetitions at synthetic 80 ms
timestamp intervals. Bare-hand mode was verified; wrist-only mode was false.

This was an offline replay with the game idle and tracking cameras disconnected. It loaded
the phone's saved verified calibration and never initialized cameras or published replay
poses. Raw-image landmarks were undistorted during triangulation; corrected-image landmarks
were triangulated without applying distortion correction again. Both paths retained the
existing handedness, depth, reprojection, palm geometry, and motion checks.

The corpus contains two bare-hand scenes, one no-hand control, and six controller-grip
scenes. The central bare-hand scene was also repeated at the end of the phone run. Replaying
one pair twelve times does not create twelve independent accuracy samples. Desktop IMAGE
and VIDEO tests using MediaPipe 0.10.21/OpenCV 4.11.0 provide a separate cross-check.

## Bare-hand result

For capture `64390821246320`, two open hands are visible in both cameras:

| Image preprocessing | Full: usable stereo palms per replay | Lite: usable stereo palms per replay |
|---|---:|---:|
| Raw | 1 of 2 | 0 of 2 |
| Lens correction only | 1 of 2 | 0 of 2 |
| Contrast normalization only | 1 of 2 | 0 of 2 |
| Lens correction + contrast normalization | 1 of 2 | 0 of 2 |

The cold frame and all twelve measured frames agreed. Full found one hand in each eye;
Lite found no hands with raw, lens-only, or contrast-only input. With both corrections,
Lite found one hand in the left camera and none in the right, so it could not reconstruct
a stereo position.

Capture `72745789186883` contains one open hand, peripheral in the left image. Every
configuration missed it in both cameras throughout cold and measured replay. The desktop
IMAGE and VIDEO tests agreed with these bare-hand outcomes. The no-hand control
`59405518228482` returned zero hands across all configurations.

The final repeat of the central scene reproduced the same cold and measured counts in
every configuration. All six controller-grip pairs (`110854218431877`, `110858058677761`,
`110862058272915`, `110935538464606`, `110939418512521`, `110943418461791`) produced
zero accepted stereo palms and zero accepted wrists in all measured phone replays. Some
Full configurations produced individual camera detections, but these did not become valid
stereo positions. Grip scenes are reported separately from the bare-hand comparison.

## Phone timing

These are median milliseconds for a parallel stereo pair on the central scene. They include
RGB preparation, inference, and result extraction, but exclude decoding, precomputed lens
correction, model initialization, and stereo reconstruction. They are not end-to-end latency.

| Model | Preprocessing | First run | Final repeat |
|---|---|---:|---:|
| Full | Raw | 46.10 | 82.98 |
| Full | Lens only | 75.17 | 84.46 |
| Full | Contrast only | 85.64 | 82.99 |
| Full | Lens + contrast | 84.10 | 80.49 |
| Lite | Raw | 43.07 | 40.06 |
| Lite | Lens only | 37.29 | 39.70 |
| Lite | Contrast only | 38.73 | 42.54 |
| Lite | Lens + contrast | 78.78 | 75.34 |

Android reported thermal status 2 throughout the saved-pair runs. Configuration order was
fixed, and inference work differs when a hand is found. Full/raw's first-versus-last variation
also prevents attributing the timing differences to preprocessing alone. Lite's lower timings
mostly accompany zero returned hands; its two-camera usable-hand result remains zero.

## What normalization changes

The saved calibration uses pinhole radial distortion coefficients (`fisheye: false`). The
comparison tests that calibrated lens correction, rather than forcing a fisheye model onto
the coefficients. Keeping the original camera matrix and output dimensions narrows the
field of view: the left output samples approximately raw x=96–544 and y=56–437, and the
right approximately x=93–547 and y=57–440. Some peripheral fingers near raw x=600 disappear
from the corrected left image. This is a concrete limitation of the current mapping, although
the raw version also failed to detect that hand; the crop alone does not explain all failures.

Model tensor normalization was already provided by MediaPipe. The additional normalization
tested here changes image contrast. Replicating grayscale into RGB satisfies the input channel
contract but cannot recover missing color information.

## Recommendation and limits

Use Full with raw grayscale replicated into RGB as the next live baseline, retaining MediaPipe's
tensor scaling and calibrated point correction for stereo geometry. Full has the stronger
detection result here; extra contrast/lens preprocessing has no demonstrated acceptance gain
on these scenes. Broader motion testing is still needed before calling any configuration best
overall. Full itself still misses one of two central hands and the peripheral hand.

The comparison used a temporary diagnostic build with opt-in preprocessing and offline replay.
That comparison code has been removed after selecting the Full/raw baseline; the live path
keeps grayscale-to-RGB conversion and calibrated point correction. Build, twenty focused JVM
tests, packaged runtime verification, and the installed Android replay were checked for the experiment.

Local evidence is under `build/hand-comparison/`: `phone/` contains full per-frame results,
thermal snapshots and before/after status; `phone-summary.json` contains compact counts;
`desktop/` and `desktop-video/` contain the independent comparisons. The scripts are
`compare.py`, `compare_video.py`, `run_phone.py`, and `report_phone.py` in that directory.
`input-manifest.json` records all capture, calibration, and model hashes plus runtime versions.
