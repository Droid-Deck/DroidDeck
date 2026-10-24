# Experimental Qualcomm backends

The Linux backend uses the installed Qualcomm Vulkan HAL through a pinned libhybris ICD. The Android compositor imports gralloc buffers through `android_wlegl`. Choosing **System Qualcomm driver** in the runtime driver list also selects the system display driver. Restart the app after changing an already loaded display driver.

## Imported AdrenoTools Qualcomm packages

Import a Qualcomm Android driver with **Change display driver → Import an AdrenoTools zip…**. In **Mix and match → Change runtime driver**, select its **Qualcomm (AdrenoTools, experimental)** entry. Previously imported packages appear without reimporting. This selects the package for Linux applications and the system driver for Android presentation. The runtime change applies to the next session; restart DroidDeck if its display driver was already loaded. Changing only the display selection does not change the Linux runtime driver.

Candidates must have `vendor: Qualcomm` metadata, an installed ARM64 library, and a `minApi` no newer than the phone. Mesa/Turnip packages are excluded. The runtime setting uses `qualcomm-adrenotools:<imported-id>`. Deleting the source package clears a matching runtime selection. A selected package that disappears or becomes incompatible outside that removal flow causes an explicit startup error rather than switching drivers silently.

PRoot privately overlays the package HAL at `/vendor/lib64/hw/vulkan.adreno.so` and its ARM64 companion libraries under `/vendor/lib64`. Packaged `libc++.so` is also overlaid at `/system/lib64/libc++.so`: Android's SP-HAL namespace resolves that dependency from system first. These mounts are repeated in nested application sandboxes and use the running session's selection. Android's instance loader and the bridge must resolve the same HAL; passing only an imported path to `HYBRIS_VULKAN_HAL` is insufficient. Android core libraries and its Vulkan loader are not replaced. No device system file is modified.

RedMagic 11 Pro hardware verification on 2026-10-24 used the existing `v891.7` import. Its HAL SHA-256 is `b2c6e92a05d4e6388250f5ec6f94408f2e693cb7fd26274382913ddcb6586108`. Vulkan reports **Qualcomm 0.891.7**, and the GPU fill/copy/readback probe passes. Steam Big Picture reached its library and the installed Windows Isaac rendered through ARM64 Proton. Both Gamescope and the game mapped `graphics_driver/v891.7/vulkan.ad891.so`, its compiler/dependencies and the bridge ICD. The system HAL SHA-256 remained `16acf005480a071af9452f13d8e9bb9930971488d889ea276f87d73616cfd6f5`.

The import's metadata advertises Vulkan **1.4.359**, but this device's Linux Vulkan probe actually reports physical-device API **1.3.351**, including with implicit layers disabled. The package label is not proof of its exposed API on a particular GPU. Driver identity and API support are separate results. Broader compatibility is untested. Evidence is in `build/research/qualcomm/packaged-*`, session `2026-10-24-02-steam`.

## Steam browser hardware-rendering investigation

Games, Gamescope and Android presentation use Vulkan hardware acceleration. Experimental builds also use Steam's regular GPU-enabled browser launch path for both system and imported Qualcomm drivers. This is a shared backend default with no GPU-model allowlist. The helper's `SDL_FRAMEBUFFER_ACCELERATION=0` workaround remains scoped to its SDL window surface; it does not disable Chromium GPU compositing. The Adreno 840 browser verification and remaining limits are described below.

Earlier RedMagic 11 Pro/v891.7 trials on 2026-10-24 failed to produce a stable hardware-rendered Steam UI. They tried ANGLE Vulkan and OpenGL/Zink, explicit Chromium backend switches, removing the in-process GPU switch, and removing the SDL workaround. The browser restarted or exited; logs include command-buffer creation and X11 connection errors. Those trials preceded the private Mesa integration. Evidence remains in ignored `build/research/qualcomm/cef-gpu-*` and `cef-trial-*`, device sessions `2026-10-24-04-steam` through `2026-10-24-08-steam`. Chromium/ANGLE switches are documented in [ANGLE debugging tips](https://github.com/google/angle/blob/main/doc/DebuggingTips.md).

On Adreno 740/Qualcomm 0.676.1, session `2026-10-24-13-steam` enabled the regular browser GPU path with the private Mesa integration and retained the SDL workaround. Steam reached its visible library and the user confirmed faster interaction, then reported UI corruption. Captures show persistent black rectangles obscuring portions of the library. The browser command line lacked `--disable-gpu` and `--disable-gpu-compositing`; its process mapped the Qualcomm HAL, libhybris ICD, private Zink library and Steam's ANGLE libraries, held an open `/dev/kgsl-3d0` descriptor, and did not map SwiftShader. Disabling partial swap in session `2026-10-24-14-steam` did not remove the visible corruption; that flag is not packaged. Investigation was paused at the user's request to prepare an Adreno 840 test APK. This path is experimental and has not passed rendering acceptance. An explicit `BL_STEAM_CEF_SOFTWARE=1` session environment override remains available for diagnosis or fallback.

Qualcomm sessions also leave the Steam client CPU affinity unrestricted by default, preserving explicitly saved overrides. The previous SM8550 default pinned Steam, its browser and Gamescope to one core. The affinity loop now applies client masks only to Steam and its browser. A startup recording after that fix showed the complete six-second DroidDeck animation followed by the library; a brief initial white window remained before playback.

### Pixmap binding without DRI3

The subsequent Adreno 840/v891.7 test APK failed during browser startup. Tracing isolated `xcb_dri3_buffer_from_pixmap` from Mesa's Kopper texture-from-pixmap path: the GPU supports DMA-BUF, but the nested X11 server does not advertise DRI3. XCB closed that connection with `XCB_CONN_CLOSED_EXT_NOTSUPPORTED`, causing the later `Fatal IO error 11` in `XGetWindowAttributes`. Gamescope faulted after Steam exited. Disabling GL threading did not help. Forcing ANGLE Vulkan instead failed with `EGL_BAD_CONFIG`.

`zink-pixmap-without-dri3.patch` checks both GPU DMA-BUF capability and the server's DRI3 extension before pixmap import. When import is unavailable, it allocates a normal GPU texture and uses Mesa's existing pixmap pixel-copy update path. Other GPU rendering remains accelerated. No X11 extension or Vulkan capability is fabricated. The check is shared across GPU models and both Qualcomm driver sources.

Verification on RedMagic 11 Pro/Adreno 840 with the imported v891.7 driver:

- `pixmap_probe.c` binds a 17×9 X11 pixmap to a GPU texture, checks two different image contents and verifies all 306 GPU readback pixels on a server without DRI3. The original libraries returned black pixels; the patched libraries pass. Build it with `cc -O2 pixmap_probe.c -o pixmap-probe -lGL -lX11`, then run inside the selected Qualcomm session.
- Steam reaches its visible library, opens its main menu and survives a session restart. Device sessions are `2026-10-24-29-pixmap-probe`, `2026-10-24-30-steam` and `2026-10-24-31-steam`.
- Chromium `SystemInfo.getInfo` reports GPU compositing and rasterization enabled. Its renderer is `ANGLE (Mesa, zink Vulkan 1.3(Adreno (TM) 840 (QUALCOMM_PROPRIETARY)), OpenGL 4.6 (Core Profile) Mesa 26.2.3)`; WebGL independently reports the same hardware path. No software-browser override or tracing preload is used.

Evidence is retained under ignored `build/research/qualcomm/adreno840`. The earlier Adreno 740 corruption requires retesting with this patch; this result does not validate every Adreno model, driver version or game.

### Browser descriptor selection

Opening a game's details and gear menu exposed another Adreno 840/v891.7 failure after startup was fixed: controls were displaced and the menu's backdrop covered only part of the window. Chromium's offscreen screenshot was correct, but the X11 pixmap and displayed frame were corrupt. Apitrace captured both correct and corrupt frames. Disabling GL threading, partial swaps, pipeline optimization or renderpass tracking did not fix it.

The controlled descriptor-mode test reproduced corruption with `ZINK_DESCRIPTORS=lazy` and rendered the same page and menu correctly with `ZINK_DESCRIPTORS=auto` alone (session `2026-10-24-57-steam`). The Qualcomm webhelper branch in `droiddeck-steam-taskset` now selects `auto`. Zink chooses descriptor buffers when its capability checks permit them and otherwise retains its supported fallback. This applies to system and imported Qualcomm sessions without a GPU-model list. It does not establish that drivers which fall back to lazy descriptors are fixed.

The packaged APK was installed and checked without agent environment overrides or tracing preloads in session `2026-10-24-59-steam`. The game page, gear menu and Properties page rendered correctly. The browser's actual process environment contained `ZINK_DESCRIPTORS=auto`; its maps contained the imported v891.7 HAL and private Zink. Chromium reported GPU compositing and rasterization enabled on Adreno 840. Evidence is under `build/research/qualcomm/adreno840`, including `descriptors-auto*`, `nogeneral*` (a failing control), and `packaged-*`. Adreno 740 requires retesting; the underlying lazy-descriptor failure has not been reduced to a standalone Mesa test.

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

Hardware verification on 2026-10-24 installed the full-runtime development APK and reached the visible Steam Big Picture library on this Adreno 740. Steam and Gamescope mapped `/vendor/lib64/hw/vulkan.adreno.so` through the bridge; Steam also mapped the private GLX vendor and `libgallium-26.2.3.so`. `egl_probe.c` reported **OpenGL 3.2 Compatibility Profile**, rendered a triangle through `zink Vulkan 1.3(Adreno (TM) 740 (QUALCOMM_PROPRIETARY))`, and verified all 256 readback pixels. Evidence is under ignored `build/research/qualcomm/adreno740`, initially device session `2026-10-24-05-steam`, followed by the browser GPU trial described above. Games have not been verified on this phone. The later DRI3 pixmap fix has been verified on Adreno 840 as described above, but not yet on this Adreno 740.

Build the private components on ARM64 Linux with their normal development dependencies (GCC/C++, Meson, Ninja, pkg-config, Wayland/X11/SDL2 headers, Vulkan headers, glslang, and Mesa's Python Mako/packaging/PyYAML, Flex and Bison requirements):

```sh
python3 tools/qualcomm/build_gamescope.py
python3 tools/qualcomm/build_zink.py
python3 tools/qualcomm/build_wsi.py
```

The recipes pin upstream source revisions and retain local outputs under `build/qualcomm-gamescope`, `build/qualcomm-zink/runtime` and `build/qualcomm-wsi`. `build_wsi.py` takes the WSI source from the pinned WSI archive in `build/qualcomm-cache`, so stage it once with `fetch_runtime.py` first. Update `runtime.json` from the produced `build.json` hashes after deliberately rebuilding a component. The APK check verifies the pinned Gamescope, its source archive, WSI files, and private Mesa files alongside the full Linux runtime and all add-on packs. Native build outputs are prerequisites, just like the other prepared runtime components.

## Zero-copy game presentation

Proton games in Qualcomm sessions present through Gamescope's WSI layer instead of X11 shared memory. The Steam client, its browser and other session programs keep `WSI_X11_FORCE_SHM=1` and `DISABLE_GAMESCOPE_WSI=1`; the Proton wrapper's `bl_present` drops both for the game. Set `BL_QUALCOMM_SHM=1` in the session environment to keep games on the shared-memory path.

The Qualcomm HAL has DMA-BUF export but no `VK_EXT_image_drm_format_modifier`, and Android has no DRM device. The WSI layer is therefore rebuilt with its Wayland backend and `wsi-patches`:

- `0001` allocates swapchain images in the driver as LINEAR images with exportable dedicated memory, exports them with `vkGetMemoryFdKHR`, takes offset and stride from `vkGetImageSubresourceLayout`, and sends them as LINEAR `zwp_linux_dmabuf_v1` buffers. The modifier extension is no longer required at `vkCreateDevice`. X11 DRI3 keeps its previous behavior.
- `0002` removes the `__STDC_VERSION__` workaround that GCC 16 rejects.
- `0003` opens `/dev/dma_heap/system` read-only, the only access Android grants apps.
- `0004` exposes `vkAcquireNextImage2KHR` on Vulkan 1.0 devices; Gamescope's WSI layer acquires every image through it.

`gamescope-linear-dmabuf.patch` makes the private Gamescope create a version 3 `linux-dmabuf` global without a DRM device (wlroots otherwise creates none), advertise LINEAR beside the implicit layout, and import LINEAR buffers as LINEAR images. Without modifiers the stride cannot be imposed, so an import is refused when the buffer's offset or stride differs from the driver's own LINEAR layout.

Verification on 2026-10-24 used the RedMagic 11 Pro, Adreno 840 and the imported v891.7 driver at 1590x720. `vkcube` (600 frames, uncapped mailbox) went from 131.5 to 198.0 fps; its own CPU time fell from 1.15 s to 0.23 s, Xwayland from 1.64 s to 0.69 s and Gamescope from 6.88 s to 4.00 s. DRAGON BALL: Sparking! ZERO at 60 fps was launched from the same installed APK after a 60-second Steam settle, with only `BL_QUALCOMM_SHM` toggled, and sampled over the same 12-second startup window: Gamescope used 0.35 cores against 0.43 and 0.44 over two shared-memory runs, Xwayland 0.14 against 0.20, and the game 1.28 against 1.21 and 1.22. Average GPU load ranged from 5% to 13% across these runs and does not separate the paths at this phase. The startup logos rendered correctly and the performance overlay hooked DXVK directly. Gamescope's remaining extra cost comes from its SDL backend, the nested-swapchain copy and bridged Vulkan calls. `vkcube` in FIFO mode presented 169.5 fps against a 144 Hz output, so FIFO pacing through this path still needs checking. Native Linux games are not covered by the Proton wrapper.

## Compat-layer image-bounds rewrite

Qualcomm sessions set `HYBRIS_VULKAN_COMPAT_IMAGE_BOUNDS=0`. The bridge's `VK_LAYER_HYBRIS_compat` enables this pass by default, although it ports a Mali+DXVK workaround (Vortek's `removeImageBoundCheck`). After any `OpSampledImage` or `OpImageFetch`, it replaces the zero false operand of the next `OpSelect` with its true operand without checking that the select uses the image result. DXVK writes HLSL comparisons and `step()` as `select(cond, 1.0, 0.0)`, so those collapse to the constant `1.0`.

On RedMagic 11 Pro/Adreno 840 with v891.7, DRAGON BALL: Sparking! ZERO rendered its main-menu scene with a red cast. Replaying the pass over the 479 shaders DXVK compiled up to that menu rewrote 112 of them at 205 selects; 148 of those selects do not depend on any image read. The same scene rendered correctly with only this switch set to `0`, and with every compat shader rewrite disabled. Other compat passes keep their defaults. Evidence is under ignored `build/research/sparking-zero/red2-*`.

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
