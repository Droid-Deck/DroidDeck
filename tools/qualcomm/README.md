# Experimental Qualcomm backends

The Linux backend uses the installed Qualcomm Vulkan HAL through a pinned libhybris ICD. The Android compositor imports gralloc buffers through `android_wlegl`. Choosing **System Qualcomm driver** in the runtime driver list also selects the system display driver. Restart the app after changing an already loaded display driver.

## Imported AdrenoTools Qualcomm packages

Import a Qualcomm Android driver with **Change display driver → Import an AdrenoTools zip…**. In **Mix and match → Change runtime driver**, select its **Qualcomm (AdrenoTools, experimental)** entry. Previously imported packages appear without reimporting. This selects the package for Linux applications and the system driver for Android presentation. The runtime change applies to the next session; restart DroidDeck if its display driver was already loaded. Changing only the display selection does not change the Linux runtime driver.

Candidates must have `vendor: Qualcomm` metadata, an installed ARM64 library, and a `minApi` no newer than the phone. Mesa/Turnip packages are excluded. The runtime setting uses `qualcomm-adrenotools:<imported-id>`. Deleting the source package clears a matching runtime selection. A selected package that disappears or becomes incompatible outside that removal flow causes an explicit startup error rather than switching drivers silently.

PRoot privately overlays the package HAL at `/vendor/lib64/hw/vulkan.adreno.so` and its ARM64 companion libraries under `/vendor/lib64`. Packaged `libc++.so` is also overlaid at `/system/lib64/libc++.so`: Android's SP-HAL namespace resolves that dependency from system first. These mounts are repeated in nested application sandboxes and use the running session's selection. Android's instance loader and the bridge must resolve the same HAL; passing only an imported path to `HYBRIS_VULKAN_HAL` is insufficient. Android core libraries and its Vulkan loader are not replaced. No device system file is modified.

RedMagic 11 Pro hardware verification on 2026-10-24 used the existing `v891.7` import. Its HAL SHA-256 is `b2c6e92a05d4e6388250f5ec6f94408f2e693cb7fd26274382913ddcb6586108`. Vulkan reports **Qualcomm 0.891.7**, and the GPU fill/copy/readback probe passes. Steam Big Picture reached its library and the installed Windows Isaac rendered through ARM64 Proton. Both Gamescope and the game mapped `graphics_driver/v891.7/vulkan.ad891.so`, its compiler/dependencies and the bridge ICD. The system HAL SHA-256 remained `16acf005480a071af9452f13d8e9bb9930971488d889ea276f87d73616cfd6f5`.

The import's metadata advertises Vulkan **1.4.359**, but this device's Linux Vulkan probe actually reports physical-device API **1.3.351**, including with implicit layers disabled. The package label is not proof of its exposed API on a particular GPU. Driver identity and API support are separate results. Broader compatibility is untested. Evidence is in `build/research/qualcomm/packaged-*`, session `2026-10-24-02-steam`.

## Steam browser hardware-rendering investigation

Games, Gamescope and Android presentation already use Vulkan hardware acceleration. The software overrides apply to Steam's CEF browser and its SDL window surface. RedMagic 11 Pro/v891.7 trials on 2026-10-24 removed the CEF software override, tried ANGLE Vulkan and OpenGL/Zink, explicitly passed Chromium backend switches, removed the requested in-process GPU switch, and retried without the SDL software-surface override. None produced a stable usable Steam UI. The browser restarted or exited and the session subsequently shut down; logs include command-buffer creation and X11 connection errors. These observations do not isolate the root cause, and `READY` alone does not confirm the Steam window is usable.

The working software UI fallback was restored. No hardware CEF mode is shipped or claimed as working. Reproduction logs and trial scripts are retained in ignored `build/research/qualcomm/cef-gpu-*` and `cef-trial-*`; device sessions are `2026-10-24-04-steam` through `2026-10-24-08-steam`. Chromium/ANGLE backend switches are documented in [ANGLE debugging tips](https://github.com/google/angle/blob/main/doc/DebuggingTips.md).

`runtime.json` pins the downloaded bridge and WSI archives, the locally built private Gamescope binary and corresponding source archive, and every private Mesa runtime file. `fetch_runtime.py` checks these pins before Gradle stages them. Missing local build outputs fail the build. Qualcomm's proprietary device libraries are loaded from the phone and are not included in these archives. The bridge archive includes its licenses, source snapshot and patches.

The same lock pins a vendor-neutral X11 WSI layer and upstream Gamescope shader source. `build_shaders.py` applies `constant-yuv-samplers.patch` and builds six replacement SPIR-V modules with the configured NDK's `glslc`. It builds a small ARM64 glibc Vulkan layer using the NDK compiler without Android runtime linkage. The layer matches the six original Gamescope module fingerprints before substituting a shader; ordinary applications use the WSI/compatibility layers instead. Source archives, patches and licenses are packaged with the runtime. The patch can also be applied during a full Gamescope source build.

Qualcomm 0.842.44 traps in its LLVM shader compiler when Gamescope dynamically indexes YUV sampler arrays. The constant-index switch preserves the eight-layer selection. The inner X11 WSI layer uses shared-memory copies; this is a compatibility path with additional CPU and memory-bandwidth cost. Steam additionally needs a larger `glibc.rtld.optional_static_tls` reserve because it loads the Bionic TLS bridge late.

The private Gamescope is built from Valve revision `8f212644c46460549035971ab914421180f61a7c` with the regular DroidDeck patches, the additional SHM/cursor patches in `gamescope-patches`, the constant YUV sampler patch, and `gamescope-no-dmabuf.patch`. The additional integration patches were taken from `wallentx/DroidDeck` revision `c931c4de26339c50f92354b9b06f6fdf7043ca8f`. The corresponding source is packaged in the APK. Upstream integration references:

- [DroidDeck fork at the producer revision](https://github.com/wallentx/DroidDeck/tree/af320dd86c7de5738ff16cfeeefbcf42581c199e/tools/gamescope)
- [libhybris runtime builder and patches](https://github.com/wallentx/DroidDeck/tree/4ed93fb9ee6e2a0b24ff087196f2e93d247fd985/tools/hybris)

The Wayland receiver and external-image ownership handling were adapted from `wallentx/DroidDeck` revision `c931c4de26339c50f92354b9b06f6fdf7043ca8f`. The protocol retains its Collabora/libhybris license in `app/src/main/cpp/waylandcomp/protocols/wayland-android.xml`.

## Older Qualcomm capabilities

The connected vivo V2243A/SM8550 reports Adreno 740, proprietary Qualcomm 0.676.1, and physical Vulkan API 1.3.128. It lacks `VK_EXT_external_memory_dma_buf` and `VK_KHR_maintenance5`. The original private Gamescope refused to create a device. The new Gamescope permits missing DMA-BUF only for Vulkan swapchain backends, disables DMA-BUF format advertisement and imports/exports, and uses synchronous SHM uploads. Non-swapchain backends retain their requirement. GPU composition and Android hardware-buffer presentation remain Vulkan operations.

Mesa 26.2.3 made maintenance5 mandatory for Zink. `zink-optional-maintenance5.patch` restores the earlier optional extension check, preserves point-size shader stores without that feature, and omits the maintenance5 buffer-view usage structure when unsupported. The private build uses the same Mesa release as the installed runtime, exposes a distinct GLVND vendor, and includes only Zink as its Gallium driver. `qualcomm-gl.sh` selects its libraries for applications and Mangoapp in Qualcomm sessions. It does not change the Vulkan device's reported features or enable software rendering. This does not establish full OpenGL conformance or game compatibility.

Hardware verification on 2026-10-24 installed the full-runtime development APK and reached the visible Steam Big Picture library on this Adreno 740. Steam and Gamescope mapped `/vendor/lib64/hw/vulkan.adreno.so` through the bridge; Steam also mapped the private GLX vendor and `libgallium-26.2.3.so`. `egl_probe.c` reported **OpenGL 3.2 Compatibility Profile**, rendered a triangle through `zink Vulkan 1.3(Adreno (TM) 740 (QUALCOMM_PROPRIETARY))`, and verified all 256 readback pixels. Evidence is under ignored `build/research/qualcomm/adreno740`, device session `2026-10-24-05-steam`. Steam's CEF software fallback remains enabled. Games have not been verified on this phone, and the newly rebuilt components have not yet been retested on Adreno 840.

Build the private components on ARM64 Linux with their normal development dependencies (GCC/C++, Meson, Ninja, pkg-config, Wayland/X11/SDL2 headers, Vulkan headers, glslang, and Mesa's Python Mako/packaging/PyYAML, Flex and Bison requirements):

```sh
python3 tools/qualcomm/build_gamescope.py
python3 tools/qualcomm/build_zink.py
```

The recipes pin upstream source revisions and retain local outputs under `build/qualcomm-gamescope` and `build/qualcomm-zink/runtime`. Update `runtime.json` from the produced `build.json` hashes after deliberately rebuilding a component. The APK check verifies the pinned Gamescope, its source archive, WSI files, and private Mesa files alongside the full Linux runtime and all add-on packs. Native build outputs are prerequisites, just like the other prepared runtime components.

## APK packaging

Prepare the normal Linux runtime inputs and all five published add-on packs before compiling. Every APK must support a fresh installation; installed-device runtime reuse is prohibited by `AGENTS.md` and rejected by Gradle.

```powershell
./gradlew.bat -PskipRust=true :app:assembleDebug
```

The build verifies prepared inputs before packaging, then verifies the APK and the add-on pack contents after assembly. Published packs are downloaded on first use using their pinned checksums, as in the normal DroidDeck build. The base Linux root filesystem is downloaded during setup. The package follows `droiddeck.debugApplicationIdSuffix` in `local.properties`.

## Device probes

`probe.py` runs inside the Linux runtime with the bridge ICD selected. It submits a GPU fill and copy, then checks 4 KiB of host-visible readback. It requires Python 3 and the runtime's normal glibc Vulkan loader.

`ahb_probe.c` is an Android NDK program. Pass the newly built `libdroiddeckwayland.so` as its argument, with that library's dependencies on `LD_LIBRARY_PATH`. It allocates and writes an Android hardware buffer, imports it through the compositor's system Qualcomm Vulkan path, and verifies all 4096 converted BGRA pixels by GPU readback.

The two standalone probes do not establish presentation or game compatibility. Additional RedMagic 11 Pro tests confirmed Wayland vkcube presentation, a visible X11 cube through Gamescope, accelerated Zink OpenGL 4.6, Steam Big Picture startup and rendering in the installed Windows version of The Binding of Isaac through ARM64 Proton. Qualcomm 0.842.44, Adreno 840 and Vulkan 1.4.295 were identified under the development app's UID; the game's process maps also contain the Qualcomm HAL and the selected bridge ICD. Steam's browser uses software compositing and its SDL window-surface workaround is scoped to the webhelper. Broader game compatibility and performance remain untested. A Gamescope shutdown SIGSEGV after child exit is still under investigation. Device evidence is retained in `build/research/qualcomm/`, including `isaac-driver-maps.txt`, `isaac-qualcomm.png` and `isaac-menu-qualcomm.png`.
