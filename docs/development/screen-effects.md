# Screen-effect pipeline creation

The Vulkan screen-effect chain creates its shared render pass, sampler and
descriptor resources on first use. Graphics pipelines are created only for
passes needed by the current frame and cached until output-format reset or
explicit chain teardown. Switching settings adds missing pipelines without
rebuilding those already available. Source-format and size changes do not
discard the pipeline cache.

CAS alone needs one pipeline; FSR and FSR Fit need EASU and RCAS. Spatial
upscalers are prepared only when the existing dispatch path actually uses them,
so selecting FSR at native size does not compile unused upscaling pipelines.
Sharpen-only mode keeps its existing native-size RCAS pass. Zero-strength CAS
and disabled effects do not require a pipeline.

All required pipelines are prepared before any effect draw is recorded. If a
required vertex shader, fragment shader or pipeline fails to build, the existing
whole-chain failure path logs the error, releases chain resources and returns
the plain scene. It does not silently omit one effect or leave a partial chain.
Failed initialization is not retried on every frame; the existing format/device
reset behavior is retained.

One effect-chain invocation uses an entry snapshot of both its settings and
blit filter. Settings changed during recording are applied to the next
invocation, rather than introducing a new effect after pipeline preparation.
No global lock is held during pipeline compilation or drawing.

The change reduces first-use compilation work, not the shader work performed
each frame. First use of a newly selected effect can still require compilation.
No effect is precompiled in the background. Shader code, pass order, saved
settings and default quality are unchanged. Pipeline-count reductions are not
measurements of startup milliseconds or FPS.

## Validation

Run `python3 -m unittest discover -s tools/tests -p 'test_effects_source_cache.py'`.
The tests compile the actual effect chain using a C compiler and Vulkan headers
from the system or Android NDK. Mock Vulkan endpoints check pipeline budgets,
cache reuse, settings/size/format/teardown transitions, mid-frame updates, valid
draws, full cleanup and failure before any partial drawing. They also retain
the existing source-image cache lifetime tests.

The host harness records normalized drawing commands for baseline comparisons.
It does not execute shaders on a GPU or replace on-device visual or latency
measurements.
