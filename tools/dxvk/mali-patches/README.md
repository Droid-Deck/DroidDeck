# Experimental Mali DXVK profile

Apply these patches after `tools/dxvk/patches/*.patch` to the revision in
`tools/dxvk/source.env`. The profile is enabled only when
`DXVK_MALI_COMPAT=1` and the Vulkan device reports all of:

| Property | Required value |
| --- | --- |
| vendorID | `0x13b5` |
| deviceID | Native `0xb8a20000`, or Wine-visible `0x00000000` with exact device name `Mali-G715` |
| driverVersion | `0x0d803000` |

The Wine-visible zero is specific to pinned Wine commit
`e60892cc2398b0a83c332a2078c7b85e47bdb42b`: `dlls/win32u/vulkan.c`
passes Vulkan IDs through `struct pci_id`, whose `vendor` and `device` fields
are `UINT16` in `include/wine/gdi_driver.h`. The Mali device ID's low 16 bits
are zero. The zero-ID path therefore also requires the exact `Mali-G715` name;
the opt-in, vendor, and driver-version checks remain mandatory.

Only `fillModeNonSolid`, `multiViewport`, `shaderCullDistance`, and
`robustBufferAccess2` become optional. Enabled Vulkan feature bits remain equal
to the driver's supported bits. Core `robustBufferAccess`, all other required
features, and all 14 Direct3D BC formats remain mandatory.

This is incomplete Direct3D support. Core Vulkan robust buffer access does not
guarantee Direct3D's out-of-bounds behavior. The warning is intentional even
when focused native probes pass. No Vulkan feature bit is invented. Advertised Direct3D feature levels describe
this experimental subset, and game compatibility requires runtime validation.

## Rejection behavior

- All three D3D11 rasterizer creation APIs return `E_INVALIDARG` for wireframe
  when native non-solid fill is unavailable, including validation-only calls.
- D3D11 shader creation validates optimized IR declarations before shader
  registration. Unavailable CullDistance or ViewportIndex causes `E_INVALIDARG`;
  cached IR and every geometry stream receive the same check.
- More than one viewport permanently marks the Direct3D device unsupported.
  Subsequent graphics draws and compute dispatches are suppressed. D3D11
  `GetDeviceRemovedReason` and `Present` return `DXGI_ERROR_DEVICE_REMOVED`.
- Generic context guards reject non-solid rasterization and multiple viewports
  before storing invalid native state, covering the D3D9 path too. D3D9 reports
  device loss through cooperative-level, `CheckDeviceState`, present and reset checks.

The unsupported-operation latch is separate from native Vulkan device loss.
Submitted commands retain normal completion waits and resource lifetime;
pretending the native queue was lost could free resources still used by the GPU.
The latch is permanent for that device and is not cleared by D3D9 Reset.

## Validation

Static checks performed: patch applies cleanly to the existing patch baseline,
reverse application matches the edited source, and `git diff --check` passes.
No local DXVK build or runtime validation is implied by those checks.

Before publishing as a tested backend, build against the pinned submodules and
exercise: a normal single-viewport draw; all three wireframe creation APIs;
CullDistance and ViewportIndex shaders in each applicable stage, including
non-rasterized geometry streams and cache hits; multi-viewport rejection with
later draws and Present; D3D9 wireframe rejection; device teardown with queued
work; and profile rejection for unset/other env values or a different identity.
Use Vulkan validation to verify rejected operations reach no invalid native
pipeline or command, then test each requested game separately.
