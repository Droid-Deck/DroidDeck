# Hybrid hand inference benchmark

Measured on the connected Samsung SM-F976U on 2026-10-17. This sparse-correspondence
prototype did not improve useful hand throughput. It was slower than scheduled stereo
on the central open-hand capture and accepted palms only on its dual-detector recovery
frames. The earlier estimated 20–60% throughput improvement is not supported by this test.

Turning image lens correction off did not improve accepted-palm counts. The saved
calibration uses the **pinhole** distortion model, not OpenCV's fisheye model. The benchmark did not change its diagnostic build's live algorithm or input-rate policy,
and restored the prior settings after each sweep. That build ended with Lite selected.
The shipping baseline was subsequently set to Full with raw grayscale-to-RGB input.
The failed hybrid prototype and its runners have been removed; these results are historical.

## Central open-hand result

The table pools the initial and final central-scene runs in the lens on/off sweep.
Each stereo entry combines the baselines before and after the hybrids. Times include
per-frame image preparation, inference, matching when applicable, reconstruction, and
palm/gesture estimation. Acceptance means at least one geometrically accepted palm in
a repeated saved image, not live detection accuracy. This scene contains two open hands;
Full consistently recovered only one.

| Full-model path | Image lens correction | Median / p95 processing | Frames with a palm | Sparse frames with a palm |
|---|---|---:|---:|---:|
| Scheduled stereo | On | 30.15 / 61.60 ms | 160 / 160 | — |
| Left-led hybrid | On | 62.59 / 84.06 ms | 48 / 80 | 0 / 32 |
| Right-led hybrid | On | 40.58 / 80.21 ms | 48 / 80 | 0 / 32 |
| Scheduled stereo | Off | 30.51 / 61.18 ms | 160 / 160 | — |
| Left-led hybrid | Off | 62.57 / 79.82 ms | 48 / 80 | 0 / 32 |
| Right-led hybrid | Off | 61.75 / 83.05 ms | 48 / 80 | 0 / 32 |

All successful hybrid frames came from ordinary stereo inference. Correspondence failed
to reconstruct the five required palm anchors; the original palm gates were retained.
The first corrected-image trial measured about 19–21 ms for rectification and matching
alone. Repeated failures also prevented the hybrid from sustaining the cheaper one-hand
tracker schedule. The live input limit should not be raised on the strength of this candidate.

Lite accepted no palms with either lens setting or either architecture on the lens-sweep
captures. The peripheral open hand and representative controller grip also yielded no
accepted palms. The no-hand control remained negative. The earlier corrected-image sweep
covered all nine saved pairs, including six controller grips, with no extra palms recovered
by the hybrid. These are a small set of static scenes, not independent accuracy trials.

## What was measured

`HybridHandBenchmark` was reachable only through the saved-capture diagnostic. Both models
use CPU VIDEO inference, the existing 0.6 thresholds, and the existing one-/two-hand
schedule. Stereo uses concurrent camera inference. The hybrid runs one detector, rectifies
both images, and searches for each landmark along its epipolar line using 11 × 11 normalized
correlation patches. It requires correlation at least 0.80, a 0.04 uniqueness margin outside
three pixels, patch standard deviation at least four, and forward/backward agreement within
1.5 pixels. Search depth spans 0.15–1.5 m. Existing triangulation and palm/gesture checks
remain in force. This is one concrete hybrid candidate, not a test of every stereo matcher.

Both detectors run every fifth frame and on the frame after failed sparse palm estimation.
The hybrid's recovery costs and failures are included in the results. Each configuration
has a separate cold frame, five warmups, and forty measured frames. Synthetic VIDEO
timestamps advance by 50 ms, but processing runs unpaced; the experiment did not measure
a live 20 Hz stream. Initialization, file decoding, camera transfer, head synchronization,
pose publication, and rendering are excluded. Replayed poses are never published.

Contrast normalization and grayscale-to-RGB conversion stay enabled in both lens cases.
With input lens correction off, detection runs on raw images; landmarks are still corrected
using calibration for triangulation. The hybrid always uses corrected, rectified images
for correspondence and transforms matched points back to the detector's image coordinates.
This comparison does not disable the geometric calibration required for metric depth.

The lens sweep tested the central scene, peripheral scene, no-hand control, one grip scene,
and then the central scene again. Full and Lite each ran stereo-before, left-led hybrid,
right-led hybrid, and stereo-after for both lens settings: 3,200 measured replays. The
separate initial corrected-image corpus sweep also recorded 3,200 replays across nine
unique pairs, repeating the central scene last. Repetition is for timing, not extra accuracy
samples. SteamVR was idle and live tracking was temporarily disabled during these runs.

## Validation and limits

The Android build and 33 focused JVM tests passed. On the phone, the matcher passed fifty
synthetic correspondences in both directions and rejected textureless synthetic images
before each capture benchmark. These checks establish basic coordinate/search behavior;
they do not establish matching accuracy on real hands.

Android thermal status was 0–1 during the corrected-image sweep and 1 during the lens
sweep. Timings varied substantially within the fixed configuration order, so the on/off
timing difference cannot be attributed solely to lens correction. Full stereo medians in
the first lens-sweep central trial ranged from 16.7 to 30.8 ms; the final trial was slower
with correction enabled. Baselines bracket the hybrid cases, but the run is not randomized
or temperature-controlled.

The initial corpus's final Full/stereo-before trial included a 2,611.74 ms inference stall.
It remains in the raw samples and means; p95 alone hides that isolated stall. Geometric
acceptance does not apply live capture-age gates, so that observation would be stale in
live tracking. The later central lens-sweep samples had no processing times above 200 ms.
No moving-hand, pinch/release, sustained SteamVR throughput, external latency, or independent
position-accuracy pass is claimed. The present matcher needs better correspondence and
lower cost before a live increased-input-rate trial is justified.

## Reproduction and evidence

The temporary runner and matcher are no longer shipped. Local evidence from those runs:

- Final lens comparison and raw samples: `build/hand-hybrid/lens/` and its `summary.json`.
- Initial full corpus: `build/hand-hybrid/corpus/` and its `summary.json`.
- First prototype trial: `build/hand-hybrid/first/`.
- Build and focused tests: `build/hand-hybrid-validation.log`.
- Lens comparison build: `build/hand-hybrid-lens-build.log`.
- Installed lens-benchmark APK SHA-256: `d976329b96e6aaceec62ef38085e203942cf763c51102adfeb50c4e5614891f1`.
- Calibration SHA-256: `3983438843877a321d3098f73496dfd9256f0088f095aa8d591602cf0b2cb213`.

Each result also includes both capture-image hashes and the model hashes. Build-directory
evidence is local and may not be present in a fresh checkout.
