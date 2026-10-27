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

## Validation

Run `python3 -m unittest discover -s tools/tests -p 'test_ddfg_flow_levels.py'`.
The host test needs a C++17 compiler and system Vulkan headers or an Android NDK
selected through the usual SDK/NDK environment variables. It compiles the actual
DD-FG chain against a mock Vulkan dispatch table and checks resource counts,
dispatch order and sizes, valid descriptor bindings, both models, every preset,
2x/3x/4x generation, small/odd extents, resize and live preset changes.
It does not execute shaders on a GPU or replace on-device validation.
