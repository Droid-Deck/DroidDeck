# Covered intermediate images

The compositor already detects opaque coverage before clearing its main render
target. The shared coverage search retains that behavior.

The SDR SurfaceControl effect pass and HDR mixed image additionally omit their
initial clear only for one valid, non-blending draw whose computed blit destination
is exactly the whole scene. That draw uses `CmdBlitImage`, which replaces the target
texels rather than reading or blending with their previous contents.

Partial, transparent, multiple-layer and invalid draws retain their clear.
All image transitions, synchronization barriers, blits, shaders, HDR parameters,
black borders and resource lifetimes are unchanged. This does not bypass HDR
encoding or the effect chain.

The command/coverage/content-model regressions compile the renderer's actual C
functions. They compare non-clear commands and modeled image contents with the
baseline, including failure and resize cases. The model does not execute Vulkan
shaders or a real GPU's filtering, color conversion or compression.

One omitted clear command per eligible intermediate frame is not a measured
latency, FPS, physical-memory-bandwidth or VRAM improvement. A driver's fast-clear
implementation can make the benefit small. Device measurements are needed to
quantify it.
