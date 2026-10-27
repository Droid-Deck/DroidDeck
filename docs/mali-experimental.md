# Experimental Mali support (phase 1)

DroidDeck was built for Adreno: Turnip drives the GPU over `/dev/kgsl-3d0` in
the Linux guest, adrenotools loads Turnip in the app's compositor, and the
compositor prefers Qualcomm's compressed (UBWC) buffer layout. None of that
exists on Arm Mali or Immortalis GPUs. Phase 1 makes the app start and run on
Mali by drawing the guest in software and putting frames on screen with the
phone's own Vulkan driver.

## Detection

A device counts as Mali only on positive evidence, and never when Adreno was
detected:

- `/dev/mali0` or `/sys/class/misc/mali0`
- `/vendor/lib64/egl/libGLES_mali.so` or `/vendor/lib64/hw/vulkan.mali.so`
- `ro.hardware.egl` or `ro.hardware.vulkan` set to `mali`
- a Vulkan device name containing `Mali` or `Immortalis`

Other non-Adreno GPUs (Xclipse, PowerVR) stay "Not an Adreno GPU" and
unsupported. A GPU that is neither detected as Adreno nor as Mali keeps the
Turnip fallback, because Adreno detection can miss on real devices.

## What changes on Mali

| Part | Adreno | Mali |
| --- | --- | --- |
| Display driver (compositor) | bundled or imported Turnip through adrenotools | the system `libvulkan.so` ("System Vulkan") |
| Compressed buffers | UBWC advertised | off, game buffers stay linear |
| Guest OpenGL | zink on Turnip | llvmpipe (`LIBGL_ALWAYS_SOFTWARE=1`, `GALLIUM_DRIVER=llvmpipe`) |
| Guest Vulkan | the runtime's Turnip (freedreno ICD) | the runtime's lavapipe ICD (`lvp_icd*.json`), when present |
| Imported Linux game driver | used | ignored |
| x86-64 Steam client | GL/Vulkan thunks | software path (`BL_X64_SOFTWARE=1`) |
| Desktop GPU launcher (`droiddeck-gpu`) | zink | llvmpipe |
| Driver auto picks | per Adreno family | none |

The guest gets `BL_SOFTWARE_GPU=1` so its scripts can tell the session draws
in software. KGSL-only features (GPU clock pin, KGSL HUD stats, the DRM node
stand-in, the freedreno ICD in bubblewrap sandboxes) already skip themselves
when `/dev/kgsl-3d0` is absent.

The setup check, the GPU info panel and the device report show the family as
"Arm Mali (experimental)". The first-run dialog warns that games are slow.

## What works and what does not

Expected to work, once confirmed on hardware:

- the app, the runtime install, and the compositor on the system driver
- the Steam client UI and the Desktop session, rendered on the CPU
- 2D and light programs

Not expected to work well:

- 3D games: llvmpipe and lavapipe run on the CPU, so most are far too slow and
  many DirectX 12 titles will not start
- HDR and the zero-copy display layer: both rely on Qualcomm gralloc details
  and should stay off
- performance overlays that read KGSL counters

### Known risk: gamescope on lavapipe

The Steam session runs gamescope with its Wayland backend, which hands frames
to the app's compositor as dma-bufs. Whether lavapipe in the runtime can export
dma-bufs on Android, and whether the Mali driver imports them, must be checked
on a device. If gamescope fails here, the Desktop session (KWin composites in
software over shared memory) is the fallback while this is solved.

## Testing on a Mali device

1. Install the build, open DroidDeck and check that the "Mali GPU: experimental"
   dialog appears before the runtime download.
2. In Setup, check that the GPU row says experimental Mali support, and that the
   GPU info panel shows "Arm Mali (experimental)" with the display driver on
   "System Vulkan".
3. Start the Desktop session, then the Steam session.
4. On failure, use Setup > Share logs. `device.txt` shows the detected family,
   the chosen display driver and the software Vulkan ICD; `session.log` shows
   the guest's Vulkan ICD; `app.log` shows the compositor's driver
   (`no adrenotools driver -> system libvulkan`) and `compressed (UBWC) game
   buffers off`.

## Planned follow-up: Vulkan forwarding

Mesa's PanVK is not an option: it drives Mali through the upstream
panfrost/panthor DRM drivers, and Android phones ship Arm's proprietary kbase
kernel driver instead. GPU acceleration for games will come from a guest
Vulkan ICD that serializes Vulkan calls to a host renderer running in the
Android process on the vendor driver, in the style of Venus/virtio-gpu or
gfxstream. That needs a transport between the proot guest and the app, a
renderer built against the system `libvulkan.so`, and shared buffers the
compositor can present without copies.
