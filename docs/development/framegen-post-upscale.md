# Experimental spatial upscaling after frame generation

The compositor limits effects output to the logical scene size while frame
generation is active. This avoids running interpolation over the full phone
panel, but can make FSR/GSR/NIS see equal input and output sizes and skip their
spatial pass. Version 0.5.0 instead upscaled before interpolation, which made
the interpolation workload scale with panel resolution.

This experiment keeps the existing source-resolution selection and runs frame
generation at the effects input size. Each generated frame and the final real
frame then uses the selected spatial filter at the mapped display size.
It introduces no extra frame queue or CPU fence wait. Performance and latency
improvements are hypotheses, not measured results.

## Scope and activation

Use an SDR game with a real input buffer smaller than the mapped output.
Enable frame generation and select AMD FSR 1, FSR Fit, Snapdragon GSR/GSR HQ,
or NIS in the scaling filter setting. Keep additional effects (CAS, color,
FXAA, CRT, etc.) off for this experiment. Sharpen-only, linear, nearest,
frame-generation-off, HDR and cardboard paths retain their existing behavior.
Combining a spatial filter with additional effects also retains the old path;
this experiment does not claim to fix that combination.

Source selection remains conservative. A game already rendering a native-size
backbuffer cannot recover its lower internal rendering resolution here.
Overlays included in the low-resolution input are interpolated/upscaled with
that input; this is not a separate native-resolution HUD reconstruction.

GPU targets and source descriptors are separate for each presentation slot so
recording one generated frame cannot replace another frame's bindings.
Source views are released after the existing frame-completion fence; targets
are cached and destroyed with the effects device. This increases GPU memory
use with interpolation multiplier and panel resolution. Allocation failures
retain the input-sized image and the existing screen blit fallback.

## Applying and reverting

Install the test APK produced for this PR. Check its signing/package identity
before replacing an installed release: an Actions test-key APK is not a
release-signed update. An official signed test build requires the repository's
normal publication authorization. Back up session logs and record the exact
APK commit, GPU driver, engine and game settings for comparisons.

No setting migration is required. Disable frame generation or select Linear
to return to the previous presentation path. Do not mix this test with Vulkan
feature-level or game compatibility changes.

## Device validation required before merge

- Compare the same scene on baseline main and this branch: FG off/on, Linear,
  FSR, GSR, GSR HQ, NIS, FSR Fit, sharpen-only and nearest.
- Test each supported interpolation multiplier, both engines, warm-up/no
  generated frames, 720p-to-1080p and 1080p-to-panel scaling, and no upscale.
- Check real and generated frames for consistent sharpness, halos, ghosting,
  text/cursor artifacts, black bars, stretching and frame-order errors.
- Change resolution/filter/engine/multiplier live; open Steam/QAM, rotate,
  suspend/resume and stop/restart. Verify no stale image, GPU loss or crash.
- Confirm HDR, cardboard, FG-off and additional-effects fallback behavior.
- Compare sustained frame pacing, generated vs real FPS, GPU load, memory,
  temperature and battery use after warm-up; do not treat output FPS alone as
  lower input latency. Measure input-to-display latency if equipment permits.
- Test memory pressure and allocation failure fallback; inspect Vulkan
  validation for descriptor lifetime, image layouts and synchronization.

Host harnesses exercise resolution routing, generated/real frame counts,
filter gates, fallback and source descriptor lifetime. They do not validate
shader output, mobile-driver behavior, performance or physical input latency.
