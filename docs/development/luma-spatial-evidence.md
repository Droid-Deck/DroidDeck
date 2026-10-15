# Luma Ultra implementation evidence

Date: 2026-10-15. Status: experimental prototype, combined release gate not passed. Native 6DoF and synchronized stereo camera delivery were observed on hardware. Camera calibration and optical-hand acceptance were deferred at the user's request.

## Device and build

| Item | Observed value |
|---|---|
| Android host | Samsung Galaxy Z Fold7, SM-F976U |
| Android version | 17 |
| Glasses | SDK-confirmed VITURE Luma Ultra / Carina |
| Glasses firmware | `12.0.01.100_20260326` |
| Vendor SDK | External Android C SDK 2.4.0 |
| Test application | `com.droiddeck.launcher.xr`, debug experimental build |
| Camera pair | Primary left0/right0, both 640 by 480 |
| Hand runtime | MediaPipe Tasks Vision 1.0.0; OpenCV 4.13.0; pinned model SHA-256 in the model provenance asset |
| CPU or GPU inference benchmark | Not run; no accepted calibration |

Existing picture, refresh-rate, and graphics work was preserved. The test temporarily enabled positional tracking and in-memory camera diagnostics. Diagnostics were stopped and the positional preference restored to its previous off setting afterward. Hand controllers were never enabled. No camera image recordings were saved. Subsequent builds are local artifacts; the phone was left available for a separate active SteamVR test.

## Hardware observations

Initial VIO output was unstable. After the user wore/moved the glasses and the software origin was recentered, valid 6DoF output was observed. Changing position values confirms positional data is available, but does not establish correct physical axes or 1:1 scale without a measured reference.

Camera frames initially arrived before their matching VIO callback. A bounded wait of up to 35 ms in the latest-frame reader allowed matching timestamps without building a queue. The public callback's undocumented pose basis is not used; capture transforms come from canonical OpenGL pose history.

A subsequent diagnostic interval produced these results:

| Measurement | Result and scope |
|---|---|
| Sampled head validity | 30 of 30 status snapshots valid |
| Sampled camera/head synchronization | 30 of 30 status snapshots synchronized |
| Processed stereo pairs | Counter increased by 1,048 |
| Dropped stereo pairs | Counter increased by 9 |
| Median reported camera rate | 24.998 Hz |
| Median estimated frame age | 24.73 ms |
| Observed estimated frame-age range | 21.44–45.96 ms across the 30 snapshots |
| Median diagnostic processing time | 1.62 ms; no hand inference or calibrated undistortion |
| Host receipt age of latest head poll | 0.09–4.40 ms across sampled snapshots; not sensor latency |

These are sparse status samples in an uncontrolled workload, not a frame-by-frame latency benchmark. Receipt-based source-clock mapping does not measure absolute camera exposure delay. None of these values establishes physical-motion-to-visible-controller latency, hand availability, or game-frame-time performance.

Local raw metadata is in `build/spatial-hardware-observations.json`. The earlier startup/recenter observations are in `build/spatial-after-movement.json`, `build/spatial-recenter-state.json`, and `build/spatial-sync-state.json`. Those files contain pose/status metadata, not images.

## Automated and build validation

- SDK-enabled and explicitly SDK-free debug APK builds pass with runtime freshness checks enabled.
- All 305 affected Kotlin glasses, head-pose, Cardboard, and glasses UI tests pass with zero failures or skips. New cases exercise canonical coordinates, normalization, floor anchoring, crouched recenter, independent origins, stale/discontinuous samples, generation rejection, pose history, camera-clock warmup/reset, gesture geometry, and inference-buffer ownership.
- ARM64 VR driver and presentation-layer builds pass. Native tests cover ABI offsets and size, empty/truncated/version-mismatched or incoherent reads, independent hand timestamps, finite poses, stale tracking, trigger hysteresis, lost-input release and released-gesture rearming. Existing fence, timeline, and render-target tests also pass.
- Native Cardboard parameter tests cover all three translation values, zero-translation equivalence, and unchanged SBS/flat parameters. The GLSL compiles; the committed SPIR-V matches regenerated glslang output, and push constants remain within the guaranteed range.
- VR build manifests include the new shared header. Packaged runtime and APK payload checks reject mismatched source/output checksums. Both external vendor files and transient dependency-inspection files remain outside tracked source.
- Packaging regressions pass: 8 Windows VR build tests, 13 Windows preparation tests, and 11 runtime-input tests. Runtime-input tests ran in the Linux container because the Windows host has no standalone zstd executable.

Build logs are `build/spatial-sdkfree.log`, `build/spatial-final-build.log`, `build/spatial-vr-build.log`, `build/spatial-shader.log`, and `build/spatial-runtime-evidence.json`. Test reports are under `app/build/reports/tests/testDebugUnitTest`.

The preserved SDK-enabled snapshot is `build/DroidDeck-luma-spatial-experimental.apk`; the SDK-free counterpart is `build/DroidDeck-spatial-sdkfree.apk`. SHA-256 hashes and final payload checks are recorded in `build/spatial-final-validation.json`. The final local snapshot includes additional buffer-ownership and conservative tracking-loss fixes after the hardware smoke test. It was not installed over the phone being used for the separate SteamVR tests. Integrate these sources into that chat's next build before later calibration; do not replace its newer runtime-storage fixes with this older snapshot.

A subsequent report from the parallel SteamVR test identified Android eviction of the live `cache/viture` directory. The SDK working-directory source was changed to `noBackupFilesDir/viture` afterward. The preserved APK snapshot and the hardware observations above predate this storage change. No additional phone restart or installation was performed here; the next shared-workspace build should include it.

## Calibration usability follow-up (2026-10-15)

The calibration screen now uses DroidDeck's saved palette, app scale, settings groups, and buttons, with separate tracking, capture, validation, and diagnostic sections. Eye height, virtual-screen dimensions, board instructions, and validation messages include imperial measurements. The distance check accepts either 20 inches (0.508 m) or 50 cm (0.5 m), using the selected value in reconstruction validation.

Recenter requests made while the newest pose is stale or invalid were previously ignored. Requests now remain pending and are acknowledged immediately in the UI; recovery waits for 250 ms of fresh, steady tracking. Normal recentering preserves floor height, new connections cancel old requests, and captures cannot mix different VIO coordinate frames. Looking straight upward also retains a stable heading instead of deriving yaw from an almost-zero forward projection. Camera diagnostics resume when the provider restarts while setup remains open.

Validation: 247 glasses and calibration/settings UI tests pass with zero failures or skips. The SDK-enabled debug APK builds, runtime input verification passes, and native Robolectric renders of the recovery and distance screens were visually checked. The APK's DEX files were checked for the new measurement controls. Logs are in `build/spatial-setup-build.log`; previews are in `app/build/reports/spatial-setup/`. The phone was not reinstalled or reconfigured during the other chat's SteamVR stability run. The user's reported repeated-recenter behavior still needs confirmation on the glasses after installing this build.

After those stability tests finished, the combined calibration/recenter APK was installed at 17:09 MDT on October 15. Its SHA-256 is `80bf99374a31676b675856c782298aecc1e1c649ad2fc5c1ce3b18b981979e3e`; hashing the installed `base.apk` confirmed an exact match. The app cold launch and agent status checks passed. The helper, VR driver, and scheduling preload match the preceding freeze-tested build. This install confirms delivery of the latest calibration changes, not hardware acceptance of recentering or calibrated hands.

## Outstanding acceptance

| Gate | Current evidence or blocker |
|---|---|
| Measured head axes and meter scale | Pending measured translation, leaning and crouching |
| Floor and separate origins on hardware | Automated math passes; physical crouched recenter and picture switching remain pending |
| Screen anchoring and plane crossing | Shader and parameter tests pass; visual movement and plane-crossing checks remain pending |
| Camera image orientation | Dimensions and pair delivery verified; visual orientation check remains pending |
| Calibration quality | No printed-board capture set, held-out reprojection result, baseline, alignment residual, or physical-distance validation yet |
| Hand pose and aiming | Reconstruction and mapping implemented; no calibrated hardware observations or tested palm-to-controller aiming offset |
| At least 95% valid hands at 30–80 cm | Not measured |
| At most 5 cm hand error and 2 cm jitter | Not measured |
| External p95 controller latency below 100 ms | Not measured |
| Hardware input release and reacquisition | State-machine tests pass; physical 100 ms release / 500 ms reacquisition targets not measured |
| Twenty-minute game performance | No controlled baseline or CPU/GPU comparison |
| Dashboard and Angry Birds VR interactions | Not validated with optical controllers |
| Occlusion, crossed hands, lighting and thermal load | Pending |
| Physical USB reconnect during inference | Generation/lifecycle guards implemented; no calibrated-inference unplug test |

The immediate next step is the printed-board workflow in `luma-spatial-tracking.md`. The combined feature must remain experimental until calibration and the remaining acceptance measurements pass. Native 6DoF availability alone is insufficient for release.
