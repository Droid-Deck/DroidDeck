# Steam black screen with FSR 1

Branch `fix/fsr-steam-black-screen` starts at `origin/main` `e4c375ad` after PR #596 merged.

## Diagnosis

On AYN Thor (Adreno 740, WN-Turnip-1.19-p_Axxx), Steam reached READY and retained focus while the entire screen was black with FSR 1 at 720p / 85% sharpness. Switching the same running session to Linear immediately restored the picture. Production SGSR also went black. At native 1080p, FSR stayed selected but its upscale pass was unnecessary and Steam remained visible.

A temporary SGSR shader drew a constant red strip beside a normal texture sample. The strip and sampled picture were corrupted in a tiled pattern. Allowing unrestricted mutable formats on the post-processing targets made both render correctly. Restoring the original shaders then restored the first production FSR launch. A second launch exposed a separate source-view lifetime bug: the scene owner destroyed its image while the effect chain retained a view and descriptor; a recycled image handle at the same dimensions could hit that stale cache.

## Change

Create the effect chain's intermediate images with `VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT`, without a restricted view-format list. Turnip consequently avoids UBWC compression on these render-then-sample/blit targets. The input scene and guest images retain their existing allocation policy. Release the cached source view and descriptor before an SDR, HDR or frame-generation scene image is destroyed. This makes recycled Vulkan handles create fresh sampling resources. Scaling remains enabled and the FSR EASU/RCAS shaders and sharpness settings are unchanged.

Driver behavior is documented in Mesa's [image allocation implementation](https://chromium.googlesource.com/external/gitlab.freedesktop.org/mesa/mesa/+/3fcec4762f3fec551c00da52f349c83443152159/src/freedreno/vulkan/tu_image.cc), including the mutable-format check and `format_list_ubwc_possible`.

## Validation

- Four native cache regressions passed: recycled handles, unrelated/null images, repeated invalidation, and dimension changes. The fixture executes the actual cache implementation with Vulkan lifetime callbacks.
- Release build and 14 focused Android tests passed: SessionScalingPrefsTest and SessionDisplayTest.
- A separate Vulkan/MoltenVK smoke test ran the actual effect chain: a 16x16 source scaled by FSR to 32x32 retained RGBA 64/128/191/255.
- Signed APK installed over Thor's existing app, with its device-side transfer hash verified.
- Production FSR at 720p / 85% rendered visible Steam, including two consecutive stop/start cycles with the app/compositor retained (sessions 42 and 43). Live SGSR, Linear and return-to-FSR switching also rendered correctly.
- Session preferences match the pre-test snapshot; FSR 1 / 85%, 720p and the existing driver selections are retained.

Local screenshots, state snapshots and renderer logs are under `captures/fsr-black-screen/` (ignored). Temporary probe shaders are excluded from the final source change. The signed APK hash is `2b8b7ea18c49bf53f1f3cb00eea8f73d122258a900c18ffe660c85c5654bedae`. The old cache behavior fails the recycled-handle regression; the corrected implementation passes all four cases.
