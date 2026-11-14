# DD-FG flow resources

DD-FG keeps seven luminance pyramid levels for both input frames. Its performance
preset chooses the finest level at which optical flow is solved:

| Preset | Finest solved level | Allocated flow levels |
|---|---:|---|
| 0 | 1 | 1 through 6 |
| 1 | 2 | 2 through 6 |
| 2 (the app default) | 3 | 3 through 6 |

Only those solved levels need RGBA16F flow images, flow UBOs and flow descriptor
sets. The vectors keep their original level indices; unused entries are null.
The full luminance pyramids, full-resolution expanded flow images, regularization
and synthesis are unchanged.

At the default preset this avoids three flow images, three uniform buffers and
three descriptor sets. The removed image payload is
`8 * (width * height + (width >> 1) * (height >> 1) + (width >> 2) * (height >> 2))`
bytes for dimensions at least four pixels:

| Input size | Removed image payload |
|---|---:|
| 1280 x 720 | 9,676,800 bytes |
| 1920 x 1080 | 21,772,800 bytes |
| 2560 x 1440 | 38,707,200 bytes |

These are pixel payloads, not driver allocation sizes or measured FPS gains.

## Scratch ownership and rebuilds

Each `FrameGen` instance owns its scratch buffers, mappings and descriptor sets.
A size or live-preset rebuild first confirms GPU completion and resets that
instance's descriptor pool, then releases the old scratch and image resources.
Motion/history validity is reset with the rebuilt images. Repeated preparation
at the same size and ordinary frame recording do not add device-idle waits.

Healthy destruction releases the descriptor pool, mapped scratch buffers and
images. The native engine releases its input ring only after the chain confirms
completion, so an input image is not destroyed before the wait.

If `vkDeviceWaitIdle` fails, the affected instance becomes unusable: further
recording, rebuilding and destruction issue no GPU work or repeated waits, and
its Vulkan resources and input ring are intentionally retained. This is local
DD-FG containment, not compositor-wide GPU recovery. It does not claim that a
session or engine restart can recover a faulted device; an app process restart
may be required. A descriptor-pool reset failure also stops that resize without
releasing the resources, but healthy teardown can still release them.

## Validation

Run `python3 -m unittest discover -s tools/tests -p 'test_ddfg_flow_levels.py'`.
The host test needs a C++17 compiler and system Vulkan headers or an Android NDK
selected through the usual SDK/NDK environment variables. It compiles the actual
DD-FG chain against a mock Vulkan dispatch table and checks resource counts,
dispatch order and sizes, valid descriptor bindings, both models, every preset,
2x/3x/4x generation, small/odd extents, resize and live preset changes.
The same harness enforces descriptor-pool capacity, accounts for all live
resources, and models pending GPU work to reject early cleanup. Lifecycle cases
cover repeated rebuilds, same-size restarts, two simultaneous instances,
automatic/idempotent destruction, partial resize allocation, failed GPU waits,
and failed descriptor-pool resets. The actual native `Engine` is included to
check the input-ring destruction order.
It does not execute shaders on a GPU or replace on-device validation.
