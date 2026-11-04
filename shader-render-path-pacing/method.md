# Render-path pacing investigation

Retroid Pocket Duo, Adreno 722, DD-Turnip 0.4.0, Proton 11-11.11 and Ubuntu runtime rc4. Spintires uses Direct3D 9 through DXVK; VKD3D is not in this rendering path.

## Method

Each controlled launch restored the same Flood checkpoint, configuration and camera. The output was 1280×720 at 60 Hz, highest shaders, high grass/LOD, with shadows, SSAO, FXAA and motion blur enabled. Samples began after screenshot matching and warm-up. SurfaceFlinger present intervals measured frames reaching the screen. Device CPU/GPU, clocks, memory and thermal state were sampled alongside pacing. Samples were sequential rather than randomized; they are short observations, not confidence intervals or a complete thermal study. Severe skin thermal status was present; sampled GPU clocks stayed at 1150 MHz.

## Findings

Stock controls stayed between 18.8 and 19.0 FPS. Turning off DXVK tiler heuristics produced 22.1 FPS twice without lowering graphics quality. The automatically selected helper subsequently reproduced 22.1 FPS twice. A release-signed APK based on main-f3650d3 measured 22.1, 22.1 and 22.5 FPS in the three fix runs, bracketed by explicit Auto controls of 18.9 and 19.0 FPS. The signed-APK gain is 17.3% over the mean of those controls, and intervals of at least 50 ms fell from 80.0% to 67.5%. This is a bounded throughput improvement; long frame holds remain.

Mesa overlay profiling measured 33.32 ms of GPU timestamp spans and 54.16 ms of wall time per stock frame. With tiler heuristics off, the corresponding values were 33.82 and 46.76 ms. Submission counts changed from 7 to 9 per frame. Acquire and present API time stayed below 1 ms. The timestamps can include queue stalls and profiling overhead, so the difference is not an exclusive measurement of shader execution or FEX overhead. The evidence supports improved render scheduling; it does not isolate secondary command buffers, cached allocation preference or flush policy individually.

Thread snapshots did not show saturation of the main or command-stream thread. That does not eliminate CPU scheduling, emulation or synchronization from the remaining cost. PRoot counters and global paging statistics do not identify an individual blocking call. Hardware and software perf-event access against the real game PID was denied.

Changing shader precision/wave size, driver versions, buffer mapping, present interval, maximum frame latency, game limiter, detailed logging, floating-point emulation, device-local constants and NTSYNC did not improve on the tiler change. Forced Turnip sysmem, LRZ/binning toggles also did not help; forced gmem was slower. The existing custom GPU-load layer was absent from the game's process.

## Scope and exclusions

The default is restricted to the tested executable, Adreno 722 and DD-Turnip 0.4.0. Explicit tiler options and custom configuration files retain control. Other games, GPUs and drivers keep their existing behavior.

A 640×360 request was not an advertised mode and the actual swapchain remained 1280×720. Those runs are excluded from resolution-scaling conclusions. An interrupted DXVK 2.7.1 run, interrupted captures, mismatched camera scenes and an accidental self-profile are excluded. No statement that every stall is fixed or that a different DXVK release is equivalent follows from these tests.

## Primary sources

- [DXVK performance hints](https://github.com/doitsujin/dxvk/blob/master/src/dxvk/dxvk_device.cpp)
- [DXVK render-pass command buffers](https://github.com/doitsujin/dxvk/blob/master/src/dxvk/dxvk_context.cpp)
- [DXVK configuration](https://github.com/doitsujin/dxvk/blob/master/dxvk.conf)
- [Mesa Adreno architecture](https://docs.mesa3d.org/drivers/freedreno.html)
- [Mesa environment controls](https://docs.mesa3d.org/envvars.html)

## Package verification

The repository fast-deploy tool rebuilt main-f3650d3 with exactly one changed non-signature payload: the environment helper. Release certificate/lineage, ZIP alignment and complete session-asset source checks passed. The APK installed successfully over the existing app, and its extracted helper matched the tested source hash. The original installed APK was retained locally for rollback. This is a helper-only package rebuild using the existing compiled main application, not a new compilation of unchanged native/Kotlin code.

The APK and source-helper digests are recorded in apk-proof.json. Raw process logs, device identifiers and account details are kept out of the shared evidence.
## Gameplay check and remaining work

After the controlled runs, the virtual controller opened/closed the map and drove the truck 14 m. Keyboard driving and camera changes responded; the odometer reached 19 m. A 60-second dynamic capture completed with the game still active. The inspected rear, orbit and hood views showed no obvious corruption. This is a Flood spot check, not validation of every map or physical-button latency. The dynamic capture is excluded from controlled comparisons.

The new default removes measured avoidable render scheduling cost. The remaining GPU-side spans and long present intervals still need investigation; a full stall resolution has not been demonstrated. Next work should profile expensive rendering passes and the remaining submission gaps, rather than assume that disabling synchronization or changing shader precision is a proven fix. Original environment/test settings were restored, and the game was closed after testing.

Steam was stopped, temporary overrides and diagnostic bridge files were removed, and DroidDeck was force-closed/reopened on its main Play Steam page. The installed package retains the tested helper. Temporary test progress is preserved privately; pre-existing save/config files were retained.
