# Qualcomm system Vulkan driver in DroidDeck

Research date: 2026-10-23. Target: Adreno 840, Vulkan 1.4.295, as shown in the user's device screenshot. The existing `LOGS/my/2026-10-21-03-steam/device.txt` additionally describes a nubia NX809J, SM8850, Android 16/API 36, 4 KiB pages, KGSL, and `/vendor/lib64/hw/vulkan.adreno.so`. Those log details are a candidate baseline; the screenshot alone does not establish the phone model or Qualcomm driver build.

Implementation update, 2026-10-24: imported AdrenoTools Qualcomm packages are now available as Linux runtime choices. The existing v891.7 import passed a GPU transfer/readback probe and rendered Steam/Windows Isaac on the RedMagic 11 Pro; Gamescope and game process maps identify the imported HAL. Its metadata says Vulkan 1.4.359, while the device probe exposes 1.3.351 and driver 0.891.7. See `tools/qualcomm/README.md` for selection, namespace mounts, evidence and limitations.

## Decision

Implement an experimental **System Qualcomm driver** Linux backend using a glibc libhybris ICD that loads the installed Android Vulkan driver. Present through gralloc/native handles and an Android hardware buffer Wayland receiver. Keep the existing Turnip backend selectable. Add imported Qualcomm packages through an explicit driver-selection extension to this bridge after the system-driver path passes validation.

This has credible prior implementation evidence. It does not require recompiling Qualcomm's closed driver, changing the device kernel, or replacing the phone's system graphics stack. Subsequent testing on the connected RedMagic 11 Pro confirmed GPU execution and presentation; see the hardware implementation results below.

My earlier assessment understated both the existing libhybris integration work and the availability of Qualcomm Linux drivers using KGSL. A Linux Qualcomm package must be inspected for its actual kernel interface; assuming all such packages require mainline DRM is incorrect.

## Evidence and route comparison

| Route | Evidence | Remaining DroidDeck work | Priority |
| --- | --- | --- | --- |
| Installed Android Qualcomm driver through libhybris | An Adreno 840 report shows Qualcomm driver 0.842.19, Vulkan 1.4.295, vkcube/vkmark and Zink queries in unrooted Debian PRoot; resizing and GL swapchain failures were also reported | Runtime packaging, Android system-library visibility, Wayland buffer receiver, Gamescope and Proton validation | First |
| Imported Android Qualcomm driver through libhybris selection hooks | AdrenoTools already packages and loads proprietary Qualcomm drivers; bridge support for selecting the same driver throughout is additional work | Adapt namespace/loader selection, dependencies and driver identity checks | Second |
| Native glibc Qualcomm driver plus Mesa WSI wrapper | An existing wrapper reports vkcube/vkmark on Adreno 740 with driver r00026; also reports exit hangs, artifacts and GPU faults | Find an 840-compatible binary, verify KGSL ABI and allocation path, implement suitable WSI | Parallel fallback experiment |
| Android renderer process using Venus/vtest or another command transport | A DroidDeck fork reports Vulkan enumeration and Gamescope initialization on PowerVR | Transport buffer import, synchronization and compatibility, plus Android renderer service | Fallback if in-process bridge cannot load this firmware |

The Adreno report is third-party evidence, not a test on this user's firmware. Its 60 FPS vkmark result was FIFO limited and cannot establish performance relative to Turnip. See [the original device report](https://github.com/Droid-Deck/DroidDeck/issues/165#issuecomment-6086402696). The [native Qualcomm wrapper README](https://github.com/fish4terrisa-MSDSM/mesa-vulkan-wrapper/blob/9658357c332d25cd251d71a0bea440d9d7daf49b/README.md) documents its own limitations. The [Venus investigation](https://github.com/gogetwoo/DroidDeck/blob/pixel-venus/docs/pixel-venus.md) is a fallback reference, not an established Qualcomm solution.

## Recommended architecture

```text
Steam / Linux application / ARM64 Proton WineVulkan / FEX host Vulkan thunk
    -> runtime's glibc Khronos Vulkan loader
    -> libhybris Vulkan ICD + selected compatibility layers
    -> Android system loader for instance initialization
    -> selected Qualcomm Android Vulkan HAL for rendering
    -> /dev/kgsl-3d0

Linux Wayland presentation
    -> bridge-owned swapchain and gralloc/native buffers
    -> android_wlegl native handles and file descriptors
    -> DroidDeck Android hardware buffer receiver
    -> compositor Vulkan import/copy initially
    -> Android window / SurfaceFlinger
```

Vulkan rendering stays in the calling ARM64 Linux process. The buffer presentation exchange crosses processes; ordinary draw calls do not need an IPC round trip. TLS/ABI mediation still has overhead, and the existing PRoot overhead remains. There is no measured performance conclusion for this target.

The current [libhybris ICD implementation](https://github.com/taowen/libhybris/blob/2375acd0b5d278a0a2f745c645680545e8f80539/hybris/vulkan/icd/icd.c) loads the HAL in the SP-HAL namespace when appropriate and loads Android's Vulkan loader for instance initialization. Its [instance code](https://github.com/taowen/libhybris/blob/2375acd0b5d278a0a2f745c645680545e8f80539/hybris/vulkan/icd/instance.c) verifies that platform and HAL physical-device query functions agree. This prevents mixing objects from independently selected drivers. Preserve that invariant when adding imported drivers.

Use the normal glibc loader plus an ICD manifest. Avoid globally replacing `libvulkan.so.1` with libhybris's legacy replacement frontend: existing Linux layers, Steam runtime loaders, and FEX need the desktop loader contract. The [Khronos ICD interface](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderDriverInterface.md) defines negotiation and dispatch responsibilities.

## Existing DroidDeck fork to reuse

The inspected `wallentx/DroidDeck` branch `wallentx/powervr`, revision `c931c4de26339c50f92354b9b06f6fdf7043ca8f`, already contains:

- `tools/hybris/build_runtime.sh`: pinned cross-build, packaged bridge libraries, source/license bundle, manifests and hashes.
- `SystemVulkanDriver.java`: component preparation, ICD and per-process environment setup.
- `wl_android_buffer.c`: `android_wlegl` native-handle receiver using Android hardware buffer import.
- Gamescope/session integration and separate session/game compatibility policies.

Its shipped vendor profiles are PowerVR, Xclipse and Mali. Adding Adreno requires its own capability policy; copying a Mali or PowerVR profile wholesale would enable irrelevant workarounds. The fork's pinned libhybris is `bc8cb3586b79d208fd21b925fa0209b3e432e008`, whereas the independently inspected `arlinux` branch is `2375acd0b5d278a0a2f745c645680545e8f80539`. Audit the differences and choose one reproducible baseline before importing patches; the fork applies patches to its older pin.

Source references: [runtime builder](https://github.com/wallentx/DroidDeck/blob/c931c4de26339c50f92354b9b06f6fdf7043ca8f/tools/hybris/build_runtime.sh), [backend class](https://github.com/wallentx/DroidDeck/blob/c931c4de26339c50f92354b9b06f6fdf7043ca8f/app/src/main/java/com/droiddeck/launcher/gpu/SystemVulkanDriver.java), [buffer receiver](https://github.com/wallentx/DroidDeck/blob/c931c4de26339c50f92354b9b06f6fdf7043ca8f/app/src/main/cpp/waylandcomp/src/wl_android_buffer.c).

## Device detection and user selection

The UI should offer the following explicit choices, with probe status alongside the installed driver identity:

| Choice | Meaning |
| --- | --- |
| Auto | Existing device-tested recommendation |
| Turnip | Existing Linux and Android driver selections |
| System Qualcomm driver — experimental | Device's currently installed Qualcomm driver through the Linux bridge |
| Imported Qualcomm driver — experimental | A selected AdrenoTools Qualcomm package through the extended bridge |

Detect through the system Android Vulkan loader before loading a custom driver. Existing `deviceinfo/vkinfo.c` and `gpu/VulkanInfo.kt` already query device/vendor IDs, driver name/info, versions and selected features. Extend the structured result with `VkPhysicalDeviceDriverProperties.driverID`, device/driver UUIDs and raw driverVersion. Prefer `VK_DRIVER_ID_QUALCOMM_PROPRIETARY` where available; vendor ID `0x5143` and an Adreno name alone cannot distinguish Qualcomm's driver from Turnip.

Display the GPU, driver name, driver build, Vulkan version and test result. Do not label Vulkan 1.4.295 as the Qualcomm package version. Record the loaded HAL/library identity separately from the GPU name. Android's loader chooses the actual system driver through HAL/APEX rules; a hard-coded `/vendor` filename is only a candidate for the bridge. See [Android's driver discovery contract](https://source.android.com/docs/core/graphics/implement-vulkan).

Use a short-lived helper process for bridge probes so a native loader/compiler failure cannot kill the launcher. Distinguish **detected**, **bridge loads**, **renders**, **presents**, and **game tested**. Cache success against Android build fingerprint, driver identity, bridge build and selected package hash. An OTA or imported-driver change invalidates it. Probe under the DroidDeck app UID; `adb shell` success does not prove app access.

Persist the backend and package identity for each session. Stop/restart the Linux session to change its driver; the compositor may require its own process restart. Per-game overrides must preserve the backend's complete environment and bridge assets. Snapshot selected component paths for running sessions so updating or deleting a package cannot invalidate their loaded dependencies.

## What AdrenoTools contributes

The upstream [AdrenoTools project](https://github.com/bylaws/libadrenotools) is already vendored in DroidDeck. It loads alternate ARM64 Android drivers without root; its [package format](https://github.com/bylaws/libadrenotools/blob/8fae8ce254dfc1344527e05301e43f37dea2df80/tools/ADPKG.md) explicitly includes proprietary Qualcomm libraries and renamed dependencies. Existing `TurnipDriver.java` accepts an ARM64 Android library named by `meta.json`, rather than enforcing Mesa as its vendor.

Its [loader](https://github.com/bylaws/libadrenotools/blob/8fae8ce254dfc1344527e05301e43f37dea2df80/src/driver.cpp) creates Android namespaces and hooks the system Vulkan loader. That provides driver selection inside an Android process. It does not convert a Bionic binary into a glibc ICD or supply Linux Wayland swapchains.

For imported Qualcomm support, port or adapt its selection mechanism inside the libhybris-controlled Android loading environment. Both Android instance creation and HAL resolution must select the same imported library and dependency set. Merely setting `HYBRIS_VULKAN_HAL` to the imported package while leaving platform instance creation on the system driver violates the bridge's identity check. Removing that check is not a solution.

Keep this integration behind a separate milestone. Loading the existing Android AdrenoTools binary unchanged through libhybris is an experiment, not established compatibility: its linker hooks and namespace objects must belong to the same loader implementation used by the bridge. Alternatively, place AdrenoTools in a native Android renderer service, accepting a command-transport architecture.

DroidDeck's bundled BC enabler is not called by the compositor loader, and its driver-version gating is specific. Do not assume importing a Qualcomm package automatically supplies desktop BC texture support. Query native format support, then enable only a validated native or decoding path.

## Port map in this checkout

| Area | Required change |
| --- | --- |
| `gpu/VulkanInfo.kt`, `deviceinfo/vkinfo.c` | Driver ID, UUID/build identity, complete capability probe and persistent result |
| `gpu/LinuxVulkanDriver.java`, `LinuxVulkanDriverManager.java` | Introduce a backend descriptor distinct from a Turnip ZIP; current import expects `libvulkan_freedreno*.so` and a glibc string |
| `DriverMenus.kt`, `ui/DriverPage.kt`, `session/SessionPrefs.kt` | System/imported Qualcomm choices, status and session restart behavior |
| `runtime/LinuxRuntime.java`, `session/SessionService.kt` | Bridge component paths; Android library/config mounts; select ICD and loader environment |
| `runtime/BwrapSpawner.kt` | Propagate selected bridge and Android dependencies into Flatpak/nested runtime plans; avoid the default Turnip injection |
| `tools/linuxfs/overlay/usr/local/bin/droiddeck-session` | Backend-specific Gamescope/presentation environment and diagnostics |
| `waylandcomp` | Add native-handle receiver, AHB Vulkan import and correct ownership/release behavior |
| Component build/verification pipeline | Build, pin, hash and package bridge sources/libraries/protocols |

The Linux guest currently binds `/dev`, `/proc` and `/sys`; Android paths need an explicit audit. Expose readable host `/system`, `/vendor`, `/odm`, `/system_ext`, `/product`, `/apex`, `/linkerconfig` and property-service data as required by the selected firmware and bridge, including their resolved symlink destinations. PRoot path mappings do not grant extra Android permissions. Do not mix driver dependencies extracted from a different firmware with the installed system driver.

Keep the glibc library search path separate from Android's library search path. The bridge builder uses private RPATHs for its own dependencies; set `HYBRIS_LD_LIBRARY_PATH` for Bionic loading rather than putting Android `libc.so` into glibc `LD_LIBRARY_PATH`.

Audit preload behavior as part of the backend switch. `tools/linuxfs/preload/kgslid.c` currently rewrites reported chip ID `0x44050A21` to `0x44050A31`; a proprietary driver may need the original hardware ID. Disable that translation for the Qualcomm backend unless device evidence establishes a need. Check whether libhybris's ioctl hooks reach the glibc interposer before assuming the rewrite occurs. `LinuxRuntime.java` and `tools/linuxfs/preload/drm.c` also provide a DRM facade over KGSL. That facade is not a real DRM/MSM kernel driver and cannot satisfy arbitrary ioctls expected by a different UMD.

Use the maintained bridge's TLS and namespace fixes. Stock upstream libhybris documentation assumes Android adaptation changes; this fork contains support for unpatched firmware, Bionic static TLS and linker configuration. Validate thread creation, compiler initialization and process exit with the selected API 36 firmware. Late-loading the bridge into a process with a large existing TLS footprint is a separate acceptance case from loading it at startup.

## Buffer presentation and synchronization

The current Android compositor requests dma-buf external-memory and DRM modifier extensions. An Android Qualcomm driver may support AHB import without supporting that raw dma-buf path. A successful `dlopen` therefore does not make the current compositor path compatible.

Initially use the existing fork's `android_wlegl` receiver and AHB import. Preserve full native handles, all FDs, integer metadata, buffer dimensions/stride/format and lifetime. A raw dma-buf FD cannot reconstruct the missing gralloc metadata. DroidDeck's existing `banner_ahb_v1` protocol is another possible transport, but libhybris currently speaks `android_wlegl`; adapting one side is required.

The bridge [swapchain implementation](https://github.com/taowen/libhybris/blob/2375acd0b5d278a0a2f745c645680545e8f80539/hybris/vulkan/icd/swapchain.c) uses Android acquire/release operations. Audit producer completion and compositor release separately. The current Wayland native-handle protocol lacks a per-commit fence channel, so its implementation can wait on release FDs on the host. Begin with correct FIFO behavior. Optimize to explicit fence transport and SurfaceControl only after reuse, resize, surface destruction and GPU effects paths pass.

The screenshot's `VK_KHR_swapchain_mutable_format` is encouraging but does not prove bridge swapchains support the necessary flags and storage usage. In the inspected bridge, `hybris/vulkan/icd/swapchain.c` explicitly rejects any nonzero swapchain creation flags, including mutable format. Supporting that mode requires an actual bridge implementation, not adding an extension name. Query the bridge surface and gralloc-backed image capabilities. If Gamescope cannot write its composition output directly into those images, use an optimal composition image followed by a GPU copy into the presentation image. Validate color channels and sRGB conversion with pixel readbacks; a visible cube alone misses these failures.

## Native Linux Qualcomm route

Qualcomm publishes [Debian packaging for prebuilt Adreno libraries](https://github.com/qualcomm-linux/pkg-adreno). Its [8 Elite Gen 5 announcement](https://www.qualcomm.com/developer/blog/2025/10/same-day-snapdragon-8-elite-gen-5-upstream-linux-support) describes a Vulkan 1.4 UMD with a DRM/MSM stack. A separate [Qualcomm image recipe](https://github.com/qualcomm-linux/meta-qcom-distro/blob/main/recipes-products/images/qcom-multimedia-proprietary-image.bb) includes both qcom-adreno and KGSL. These are distinct integration contexts.

For each candidate package, inspect ELF architecture, DT_NEEDED, GLIBC symbol requirements, exported Vulkan entry points, kernel device/ioctl use, GPU IDs, gralloc/GBM dependencies and redistribution terms. Extract into a private experiment prefix. Test compute/readback before adding WSI. A matching ARM64/glibc ABI does not establish matching KGSL or 840 support.

The inspected Mesa native wrapper uses `WRAPPER_VULKAN_PATH`, defaults to `/opt/qti/usr/lib/libvulkan_adreno.so`, and adds desktop WSI. Its published 740 evidence makes this worth a controlled experiment. It is less suitable as the initial user-facing system-driver option because it substitutes a separate vendor binary rather than using the known installed Android driver.

## Execution gates

| Gate | Experiment and acceptance |
| --- | --- |
| 0. Native identity | Query installed driver in a fresh Android helper; save raw capabilities, build/UUID, feature chains and format support |
| 1. Bridge load | Same driver identity through glibc loader and libhybris; instance/device create/destroy and repeated process exits |
| 2. Rendering | Transfer and compute readbacks; sampled textures; BC formats; multithreaded shader compilation; compare actual pixel values |
| 3. Presentation | Wayland/AHB triangle with RGB and sRGB checks; resize, minimize, surface loss, buffer retirement and release-before-reuse |
| 4. Gamescope | Nested startup without a genuine DRM node; confirm buffer import, storage usage, modifiers and synchronization; copy fallback if required |
| 5. Steam | Zink context creation and Steam UI redraw/scroll/resize; check CEF/ANGLE separately; tolerate a UI-only software fallback if needed |
| 6. Games | Native ARM64 Vulkan, x86-64 through FEX, DXVK, VKD3D-Proton, then 32-bit WoW64 mapping and low-address behavior |
| 7. Product | Selection persistence, driver restart, failed probe recovery, OTA invalidation, nested runtime/Flatpak visibility and logs |
| 8. Performance | Matched resolution/settings, warm caches, repeated runs, frametime percentiles, thermal state, memory and presentation latency against Turnip |

Check DXVK/VKD3D's actual required features for the shipped versions: BC formats, robustness, transform feedback, descriptor indexing, dynamic rendering, timeline synchronization and any shader requirements. A Vulkan version string is insufficient. Apply compatibility layers only where a tested driver needs them; do not expose unimplemented features by name.

FEX's existing Vulkan thunk should reach the ARM64 host loader and bridge, but function coverage and process environment need tests. WoW64 additionally requires suitable low-address mapped memory. VR/effects and ray tracing are later acceptance cases; the screenshot's ray-tracing-maintenance extension alone does not establish complete ray tracing support.

The libhybris repository includes baseline rendering probes and a [WSI runner](https://github.com/taowen/libhybris/blob/2375acd0b5d278a0a2f745c645680545e8f80539/tests/wsi/README.md). Reuse their pixel checks and validation support after adapting the external compositor contract to DroidDeck. Their default endpoint belongs to Arlinux and their X11 path expects TAWC-DRI, so they are not turnkey DroidDeck tests.

## Reproducible research inputs

Inspected local DroidDeck revision: `2e0db5a2cd21c92fd93380478a305a20cce0c203`.

| Source | Inspected revision |
| --- | --- |
| taowen/libhybris, arlinux | `2375acd0b5d278a0a2f745c645680545e8f80539` |
| wallentx/DroidDeck, wallentx/powervr | `c931c4de26339c50f92354b9b06f6fdf7043ca8f` |
| bylaws/libadrenotools | `8fae8ce254dfc1344527e05301e43f37dea2df80` |
| fish4terrisa-MSDSM/mesa-vulkan-wrapper | `9658357c332d25cd251d71a0bea440d9d7daf49b` |
| taowen/arlinux-rootfs | `c092b069c407afa02b14706f650571cea4301f9e` |

Source checkouts and issue snapshots are retained under ignored `build/research/qualcomm/`. The donor APK was inspected as an archive to extract its checksum-pinned Gamescope executable; the donor APK itself was not installed. The modified local DroidDeck development APK was installed into `com.droiddeck.launcher.xr` for hardware tests.

## Hardware implementation results

Connected device: RedMagic 11 Pro, NX809J, Android 16, Adreno 840, installed Qualcomm 0.842.44, Vulkan 1.4.295. The regular DroidDeck application package was not replaced.

Verified on this phone:

- Android driver detection and persistent **System Qualcomm driver** selection in the runtime driver list; selecting it also selects the system display driver.
- Linux `vulkaninfo` identifies the same Qualcomm driver through the glibc libhybris ICD.
- A GPU fill/copy/readback probe verifies 4096 bytes.
- The Android compositor imports an AHardwareBuffer and verifies all 4096 pixels by GPU readback.
- Wayland `vkcube` completes 120 frames.
- An X11 `vkcube` renders visibly through Gamescope, the shared-memory X11 WSI fallback, the libhybris Wayland swapchain and the Android compositor.
- `glxinfo -B` reports accelerated Zink OpenGL 4.6 on the Qualcomm GPU, including with Steam's bundled Vulkan loader.
- Steam Big Picture reaches the app's `READY` state and displays its library. Its browser uses software compositing; Gamescope continues to render on Qualcomm Vulkan.
- The installed Windows version of The Binding of Isaac: Rebirth/Repentance launches through ARM64 Proton, receives focus as app 250900 and renders its intro and a game room. Its process maps contain `/vendor/lib64/hw/vulkan.adreno.so` and the selected libhybris ICD. This is a rendering smoke test, not a full playthrough or performance comparison.

Required integration fixes found by these tests:

1. Bind traversable Android APEX paths even when directory listing is denied. Filtering mounts with `canRead()` excluded Bionic's libc.
2. Advertise dma-buf only when the display driver supports it; use `android_wlegl`/AHB for Qualcomm instead.
3. Use Gamescope's SDL Wayland backend and an offscreen composition/copy fallback for the Android swapchain's supported usage flags.
4. Replace dynamic indexing of YUV sampler arrays with constant-index switches. Qualcomm's compiler otherwise raises SIGTRAP with `constant expression is expected for indexing YUV samplers`. A small Vulkan layer substitutes only the six known Gamescope shader fingerprints, rebuilt from pinned source and the tracked patch; it leaves other shaders alone.
5. Use the vendor-neutral WSI layer's X11 shared-memory path inside Gamescope; the bridge's native X11 backend expects TAWC-DRI, which this Xwayland does not provide.
6. Reserve additional glibc static TLS for late loading in Steam. The diagnostic log identified `libhybris-common.so.1: cannot allocate memory in static TLS block`.
7. Scope `SDL_FRAMEBUFFER_ACCELERATION=0` and the Kopper override to Steam's webhelper through the helper's `taskset` invocation. SDL's accelerated window-surface setup otherwise stalls on the X11 SHM path. This adaptation follows the inspected fork's `droiddeck-steam-taskset` integration.

Broader game coverage, FEX-specific cases, suspend/resume, HDR and performance require separate acceptance. A Gamescope shutdown SIGSEGV has also been observed after its primary child exits. The backend remains explicitly experimental. Source pins, build instructions and probe limitations are in `tools/qualcomm/README.md`; device logs and screenshots are retained under `build/research/qualcomm/`. Successful Steam/game evidence belongs to session `2026-10-23-10-steam`.
