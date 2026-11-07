# Porting DroidDeck from Android to HarmonyOS 7

This is the working notes for the port: what each Android piece maps onto,
what has no equivalent, and what the phased plan is.

Target: **HarmonyOS 7.0 / API 26.0.0**, OpenHarmony 7.0 Release.
Toolchain: DevEco Studio 26.0.0, HarmonyOS SDK 26.0.0, OHOS NDK (musl).

## 1. Why this is a port and not a repackage

OpenHarmony 7.0 **removed the Android APK compatibility layer** on
phone-class devices. A HarmonyOS 7 phone cannot install DroidDeck's APK, so
the launcher, the native layer and the runtime host all have to be rebuilt
against the HarmonyOS APIs.

Two consequences shape everything below:

* **No AOSP.** No `android.*` framework, no bionic, no `libandroid.so`,
  no `ASurfaceControl`, no `AHardwareBuffer`, no `MediaCodec` NDK, no AAudio.
* **Sandboxed.** The app runs in a HarmonyOS sandbox. `ptrace`, `/proc`
  access, `/dev/uinput` and shared storage are all restricted or undocumented.

The OpenHarmony kernel is Linux-ABI compatible and the NDK is musl based, so
the *guest* side of the problem is more tractable than it first looks: the
guest is ordinary aarch64 Linux. It is the *host* side that has to be rebuilt.

## 2. Architecture

```
                  upstream (Android)                    this port (HarmonyOS 7)
                  ───────────────────                    ─────────────────────
 launcher UI      Jetpack Compose            ->        ArkUI (ArkTS), V1 state
 persistent       SharedPreferences          ->        @kit.ArkData preferences
 network          OkHttp                     ->        @kit.NetworkKit http
 graphics         SurfaceTexture + EGL       ->        XComponent + EGL (OHOS NDK)
 GPU id           /sys/class/kgsl            ->        EGL GL_RENDERER
 media decode     MediaCodec (NDK)           ->        @ohos.multimedia.media AVCodec
 audio out        AudioTrack / AAudio        ->        OH_AudioRenderer (NDK)
 sensors          SensorManager              ->        @kit.Sensors
 haptics          Vibrator                   ->        @kit.SensorService / vibrator
 web              WebView                    ->        @kit.ArkWeb
 clipboard         ClipboardManager           ->        @kit.BasicServicesKit pasteboard
 input            MotionEvent/KeyEvent       ->        @kit.MultimodalInput
 files            MANAGE_EXTERNAL_STORAGE    ->        app sandbox + file picker
 native glue      JNI                        ->        Node-API (napi)
 guest launch     proot (ptrace)             ->        proot, or direct exec fallback
 guest rootfs     .tar.zst                   ->        .tar.gz (zlib is in the NDK)
```

## 3. The native module

`entry/src/main/cpp` builds one Node-API module, `libdroiddeck_native.so`,
which owns everything ArkTS cannot express:

| File | Responsibility |
|---|---|
| `device/device.cpp` | EGL-based GPU identification; CPU cores; RAM; Vulkan ICD probe |
| `runtime/runtime.cpp` | Guest supervisor: mode probe, proot argv, bind list, process control |
| `runtime/install.cpp` | SHA-256 verification, the runtime overlay, the version marker |
| `runtime/unpack.cpp` | ustar reader over zstd and zlib; unpacks the runtime artifact |
| `input/gamepad.cpp` | uinput virtual gamepad |
| `surface/surface.cpp` | EGL context on an ArkUI XComponent; guest frame publishing |
| `audio/audio.cpp` | `OH_AudioRenderer` sink for the guest's PCM stream |
| `napi_init.cpp` | The ArkTS-facing bindings |

### The ArkTS ↔ native contract

`entry/src/main/cpp/types/libdroiddeck_native/index.d.ts` declares the
module's 27 functions, and the module's `oh-package.json5` points at it:

```json5
"dependencies": {
  "libdroiddeck_native.so": "file:./src/main/cpp/types/libdroiddeck_native"
}
```

Nothing in the build connects that file to the C++ source, so a function
renamed on one side and not the other would compile and then fail on the
device as *undefined is not a function*. `tools/check_native_api.py`
compares the declarations against the property descriptor tables in the
C++ sources and against the strings in the built library.

Two things about this are worth knowing, because both cost time:

* The ArkTS compiler resolves a Node-API module through the module's
  `oh-package.json5` and checks the calls against the declared
  signatures. Plain TypeScript does not know that mechanism and
  silently downgrades an unverified native module to `any`, so a proxy
  check that does not resolve the import explicitly sees no errors at
  all. `tools/typecheck_arkts.py` resolves the import to the
  declarations itself, which is what makes the native calls checkable
  outside DevEco Studio.
* The app resolves its libraries **by soname** out of
  `entry/libs/<abi>/`. A library whose file name does not match its
  soname is not found on the device, and neither is the C++ runtime the
  module links against: `libc++_shared.so` has to ship next to it.

### The audio chain

Upstream's audio runs the **server on the host** and the **client in the
guest**, which is the opposite of what the port's code currently assumes.
Reading upstream end to end:

* `audio/PulseAudioComponent.java` unpacks a `pulseaudio.tzst` bundle on
  the host — `pactl` plus `module-aaudio-sink.so` and
  `module-directaudio-native-sink.so` — and starts a PulseAudio server
  whose `module-native-protocol-unix` listens on a Unix socket.
* The host's sink module writes to **AAudio**, the Android audio HAL.
  `tools/aaudio-sink/module-aaudio-sink.c` is upstream's own code for
  that; it is built against **prebuilt** `libpulsecore-13.0.so`,
  `libpulsecommon-13.0.so` and `libpulse.so` from
  `app/src/main/jniLibs/arm64-v8a`.
* The guest is told where that server is:
  `SessionService.kt:1021` adds `PULSE_SERVER=unix:<host socket>` to the
  guest environment, so Steam and Proton in the guest connect to the
  host's PulseAudio as clients.

The published runtime image contains none of the pieces the guest needs to
*produce* audio on its own: no `pipewire` daemon, no `wireplumber`, no
`pw-cat`, and no `pulseaudio` daemon — only the client libraries
(`libpulse.so.0`, `libpipewire-0.3.so`) and the client tools `pactl`,
`pacat` and `parec`. The PipeWire bundle upstream starts
(`/usr/local/lib/droiddeck-pipewire.tzst`) is built by
`tools/pipewire/assemble.sh` and staged into the rootfs from the APK's
assets at session time, so it is not in the published image either.

So the port's current audio path — the native layer connecting to a raw
PCM socket at `<runtime dir>/droiddeck-audio.sock` — cannot work: nothing
in the guest creates that socket, and the guest speaks the Pulse protocol
rather than raw PCM. The ArkTS side is ready (`audio.AudioRenderer` and
the native pump), and the socket is bound into the guest at
`/run/droiddeck`, so what is missing is a Pulse implementation on one side
or the other:

* **host as server** — upstream's design. Needs `libpulsecore` and
  `libpulsecommon` cross-compiled for aarch64-musl, plus a sink module
  that writes PCM where the native layer can read it. Upstream does not
  build these either; it uses a bionic prebuilt, which is the same
  problem the port has with `libwayland-server`.
* **host as client** — the guest runs PipeWire with the Pulse protocol
  exposed at `$XDG_RUNTIME_DIR/pulse/native`, and the host pulls the
  stream with a PulseAudio *client* library, which is much smaller than
  the server core. This still needs a Pulse library built for musl, and
  it needs the guest's PipeWire bundle, which is not published.

Both directions need a PulseAudio library for aarch64-musl. Neither is a
small change, so the port does not pretend audio works: the guest session
starts, the guest renders, and there is no sound until one of these is
built.

### The keymap the guest gets

The compositor publishes a keymap to the guest's clients, and it reads it
from `$XDG_RUNTIME_DIR/keymap.xkb`:

```c
snprintf(path, sizeof(path), "%s/keymap.xkb", rt ? rt : ".");
int fd = open(path, O_RDONLY | O_CLOEXEC);
if (fd >= 0) { ... wl_keyboard_send_keymap(k, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, size); }
else WLOGE("keymap open failed: %s", path);
```

Upstream keeps that file in its APK assets
(`app/src/main/assets/wayland/keymap.xkb`) and copies it into the runtime
directory when the session starts. The port ships the same file as a
rawfile and stages it the same way, because without it the compositor sends
**no keymap at all**: the guest's clients fall back to their own default,
the keys they produce do not match the ones the launcher sends, and the
keyboard is unusable.

The port reproduces upstream's mapping exactly, evdev codes and all —
`InputService`'s table is upstream's `input/EvdevKeys.java` value for
value, and upstream's compositor sends the same raw evdev codes to
`wl_keyboard_send_key`. Whatever a key does upstream, it does the same in
the port.

### The controller, and what reaches the guest

Upstream does **not** use `/dev/uinput`. Its host writes evdev events into
a shared ring buffer (`input/FakeInputWriter.java`), and a shim preloaded
into the Steam client (`app/src/main/cpp/fakeinput_steam.cpp`) reads that
ring and feeds the events to Steam's own input API. That works without a
privileged device node, which is why upstream never needs one.

The port's `input/gamepad.cpp` takes the other route: it opens
`/dev/uinput` and creates a virtual pad. HarmonyOS does not give a third
party app that device node, so the gamepad path will fail on a device.
What still works is the path through the compositor:

* **touch** reaches the guest as pointer events, through the Wayland
  compositor's own input pipe — no device node needed;
* **keys** reach the guest as keyboard events, the same way;
* the **controller mapping page** maps the on-screen buttons to evdev
  codes and pushes them into the virtual gamepad, so on a device those
  buttons have nowhere to go until the gamepad path is replaced.

So: pointer and keyboard work, the pad does not. Porting upstream's ring
and Steam shim is the way to close that, and it is a large piece of work
in its own right.

### The guest launch reproduces upstream

`Runtime::BuildArgv` mirrors `LinuxRuntime.prootPrefix` and
`LinuxRuntime.binds` from upstream, including `--kill-on-exit`,
`--kernel-release`, `-0` (fake root), `-r <root>`, `-w <cwd>` and the bind
list for `/dev`, `/proc`, `/sys`, the stdio descriptors and the Vulkan ICD.

The guest environment mirrors `GuestCommand.kt`: the variables travel as
arguments to `env -i`, and the set is upstream's — `HOME`, `USER`, `LANG`,
`PATH` (ending in `/usr/games`), `XDG_RUNTIME_DIR`, `XDG_DATA_HOME`,
`XDG_DATA_DIRS` — plus the `BL_*` variables upstream's session script
reads.

One detail upstream gets for free and the port has to reproduce: the fake
procfs files. Upstream ships copies of `/proc/stat`, `/proc/loadavg` and
friends inside the runtime and binds them over the real paths when the
host's copies cannot be read. The app sandbox hides most of `/proc/sys`,
so the port does the same, and seeds the three files the published image
does not carry (`file-max`, `overcommit_memory`, `pci_devices`).

The other is how the app's own directories reach the guest. Upstream
binds `context.getFilesDir()` and `context.getCacheDir()` at **the same
absolute path** inside the guest, which is what makes `BL_LOG` and
`BL_DEBUG_DIR` work: they are host paths, and the guest writes its
session log to the very file the launcher reads. The port binds them the
same way.

The one deliberate difference is the mode probe. Upstream can assume proot
works; the port cannot.

## 4. Blockers, hardest first

Ranked from a full sweep of the upstream tree (145K lines of Kotlin/Java,
plus C++ and Rust).

| # | Blocker | Upstream | What the port does |
|---|---|---|---|
| 1 | `ptrace` for proot | `runtime/LinuxRuntime.java:168-185` | Probe at runtime; proot when permitted, direct exec otherwise |
| 2 | `libandroid.so` `ASurfaceControl` / `AHardwareBuffer` | `cpp/waylandcomp/src/sc_layer.c:81-120` | No equivalent. The zero-copy layer path is deleted; the blit path is the only path |
| 3 | `VK_KHR_android_surface` | `cpp/waylandcomp/src/vk_present.c:800-803` | `VK_KHR_ohos_surface` in Phase 2, with the swapchain as the only route to the screen |
| 4 | Shared storage for the rootfs | `AndroidManifest.xml:29`, `runtime/GuestCommand.kt:40` | Rootfs lives in the app sandbox; the picker is used for anything the user must reach |
| 5 | Compose UI | `app/build.gradle:375-381` | The whole UI is rebuilt in ArkUI. This is the largest single-source rewrite |
| 6 | Android input event model | 1,039 uses across 58 files; `input/PadBridge.java` is 40K lines | The evdev target survives; the Android source is re-plumbed onto `@kit.MultimodalInput` |
| 7 | `MediaCodec` / AAudio | `cpp/venc/venc_server.cpp`, `tools/aaudio-sink` | `AVCodec` for decode, `OH_AudioRenderer` for playback |
| 8 | bionic prebuilt `.so` (8 of them) | `jniLibs/arm64-v8a` | All rebuilt against musl. `libwayland-server` is plain C and rebuilds cleanly |
| 9 | Turnip / KGSL GPU driver | `cpp/adrenotools`, `assets/graphics_driver/*.tzst` | Dead weight on HarmonyOS: there is no KGSL. Mali parts use the PanVK builds upstream already ships, or the phone's own driver |
| 10 | `android.os.SystemProperties` by reflection | `core/PhantomProcessLimit.kt:94` and others | Replaced with explicit probes |
| 11 | `LocalSocket` / `LocalServerSocket` | `runtime/BwrapSpawner.kt` | Raw AF_UNIX through the NDK |
| 12 | Widget host, shortcut pinning, accessibility, wallpaper services | `HomeWidgets.kt`, `HomeButtonService.kt`, `wallpaper/SteamWallService.kt` | Functionally droppable; no HarmonyOS equivalent |
| 13 | MediaPipe + CameraX hand tracking | `handtracking/HandCamera.kt` | Needs a rewrite onto `OH_ImageReceiver` |
| 14 | WebRTC (Remote Play / xCloud) | `org/webrtc/DroidDeckLowLatencyDecoderFactory.java` | The WebRTC build is Android-only |
| 15 | Multi-display (glasses, second screen) | `ui/DualScreenPanel.kt:143` | No HarmonyOS equivalent for virtual displays |

### Open risks

* **`ptrace` policy is undocumented.** This is the single biggest risk: if
  the sandbox denies it *and* the guest cannot be made relocatable, the
  Linux guest does not run at all. The probe makes the failure visible
  rather than silent, and the Settings page reports the mode.
* **Vulkan dma-buf import** on HarmonyOS is unverified. If
  `VK_EXT_external_memory_dma_buf` is missing, the guest-to-screen path has
  to be the socket bridge rather than a dma-buf import.
* **The Mali bridge source is absent** from this clone (`external/MaliBridge`
  is an empty submodule), so the guest-to-host GPU path could not be
  audited.
* **The EGL context was made current on two threads.** `SurfaceHost::CreateEgl`
  created the context on the ArkUI thread and left it current there, while
  the compositor thread draws the guest's frames into the same window. A
  context can be current to only one thread at a time, so the compositor's
  `eglMakeCurrent` failed with `EGL_BAD_ACCESS` and the guest's window would
  have stayed blank. The thread that creates the context now lets go of it
  once the texture is made, and the EGL work is serialised against the
  teardown with a lock, so the compositor and the ArkUI thread cannot destroy
  a context the other is using. What is still open is the compositor thread
  itself: it is never stopped, so it lives for the life of the process.

## 5. What carried over unchanged

* The **Rust store engines** (Epic, Amazon, GOG: ~15K lines) depend only on
  `libc`, `jni` and `reqwest`. They are the best return in the tree: a musl
  rebuild and a `napi` binding is all that is needed.
* `cpp/shaders/` — 14 GLSL shaders, pure strings.
* `cpp/thirdparty/dxbc` — DXBC to SPIR-V, portable C++.
* `cpp/termux` — PTY and fork/exec, plain POSIX.
* The **Wayland server core** (`cpp/waylandcomp`, 27 files, 14 protocols) is
  OS-agnostic C. Only its output layer was Android-bound.
* The **string catalogue**: 3,640 strings across 32 locales, ~97% already
  externalised in `strings.xml`, so the conversion to
  `resources/base/element/string.json` is mechanical.
* The **theme**: `tools/theme_palette.py` reproduces upstream's
  `ThemeColors.palette()` colour maths exactly, so the ArkUI palette is
  identical to the Compose one.

## 6. Phases

**Phase 1 — the launcher and the host bridge** (this directory)

The ArkTS launcher, the native module, the device facts, the runtime
supervisor and the surface/audio plumbing. Everything here compiles and is
wired up; what is *not* verified is the guest booting on real hardware.

**Phase 2 — the guest**

* Ship the runtime artifact as `.tar.gz`.
* **Rebuild `libwayland-server` against musl.** Upstream ships a bionic
  prebuilt in `app/src/main/cpp/waylandcomp/prebuilt/lib/`; a bionic
  library cannot load on HarmonyOS, and the compositor needs the server
  core to accept the guest's Wayland clients. Wayland is plain C, so the
  recipe is:

  ```bash
  git clone --depth 1 --branch 1.22.0 https://github.com/wayland-mirror/wayland.git
  cd wayland
  # The protocol sources are generated by the scanner, which needs libxml2
  # on the host. See the version note below.
  # wayland-scanner is a C program: build it for the host first.
  # Then cross-compile src/wayland-server.c, src/wayland-shm.c,
  # src/event-loop.c, src/wayland-util.c, src/connection.c, src/wayland-os.c
  # and the generated wayland-protocol.c for arm64-v8a with the OHOS NDK,
  # and ship the result as libwayland-server.so.0.22.0.
  ```

  Two host dependencies bite here, and both cost time to find:

  * **libxml2 must be 2.9.x.** `wayland-scanner` calls `XML_GetBuffer`,
    which libxml2 2.10 and later no longer export (2.13 replaced it with
    the `xmlBuf` API), so the scanner fails to link against a modern
    libxml2 even when the headers are present.
  * **libffi has to be rebuilt for musl as well.** The OHOS NDK does not
    ship it, and wayland's `wl_closure` marshalling needs it. Upstream's
    `jniLibs/arm64-v8a/libffi.so` is a bionic build and cannot be
    reused.

  The generated protocol sources are what the compositor links against,
  so the scanner has to run on the host even though the library is
  cross-compiled.

  **This is done.** `tools/build_wayland_server.sh` performs the whole
  rebuild and installs the result into `entry/libs/arm64-v8a/` together
  with the headers the compositor needs, in
  `entry/src/main/cpp/thirdparty/wayland/include/`. The library links
  against musl only (`libc.so`) and carries libffi statically, so it can
  be loaded from the HarmonyOS app sandbox.

  Two details cost real time and are worth keeping:

  * **Use the scanner's expat backend, not libxml2.** The scanner has
    both; the libxml2 one calls the push-parser API (`XML_GetBuffer`,
    `XML_ParseBuffer`, `XML_GetCurrentLineNumber`), which libxml2 2.10
    and later no longer export, so it fails to link against any modern
    libxml2. The expat backend needs nothing but expat.
  * **`HAVE_SYS_UCRED_H` must be left undefined, not set to 0.** musl has
    no `<sys/ucred.h>`; it provides `struct ucred` through
    `<sys/socket.h>` with `SO_PEERCRED`, which is the branch
    `wayland-os.c` falls back to. `wayland-os.c` tests the define with
    `#ifdef`, so a `#define ... 0` would still pull in the missing header.

### The compositor seam

Upstream's compositor is `app/src/main/cpp/waylandcomp` — 15,739 lines of C
across `src/` plus 14 generated protocol files. The protocol layer is
OS-agnostic; the rendering layer is not, and it is woven into the core
rather than sitting behind a clean seam. `src/compositor.c` includes
`sc_layer.h`, `ahb_swapchain.h`, `vk_present.h`, `framegen_bridge.h`,
`effects_chain.h` and `cardboard.h` directly.

That gives the port its shape — replace these, keep the rest:

| Upstream file | Role on Android | What HarmonyOS needs | Ported |
|---|---|---|---|
| `ahb_swapchain.c` | AHardwareBuffer swap chain | Not available: AHardwareBuffer is an AOSP type | `compositor/ohos_modules.c` |
| `sc_layer.c` | SurfaceControl layers, zero-copy | **Delete.** `libandroid.so` has no OHOS counterpart | `compositor/ohos_modules.c` |
| `wl_android_buffer.c` | `wl_buffer` over AHardwareBuffer | **Not ported** — the guest uses shared memory instead | not ported |
| `vk_present.c` | `VK_KHR_android_surface` | `VK_KHR_ohos_surface`, or an EGL blit | **done** — `render/vk_present.cpp` |
| `effects_chain.c`, `cardboard.c`, `framegen_*` | Post-processing | Optional; drop for the first working session | `compositor/ohos_modules.c` |
| `waylandcomp_jni.c` | JNI entry points | Node-API, matching the rest of this port | **done** — `compositor/compositor_ohos.c` |
| `wl_clipboard.c`, `wl_color_mgmt.c`, `wl_dmabuf.c`, `wl_single_pixel.c`, `wl_text_input.c`, `wl_toplevel_icon.c`, `droiddeck_ext.c` | Protocol plumbing | Carry over unchanged | **ported as-is** |

The protocol layer and `compositor.c` are compiled **unchanged** from
upstream. What the port adds around them:

* `compat/android/log.h` — AOSP's liblog on top of hilog. The compositor
  logs through `__android_log_print`, which has no HarmonyOS counterpart.
* `compat/android/hardware_buffer.h` — an opaque `AHardwareBuffer`, because
  the ported compositor refers to the type without the `struct` tag.
* `compositor/sc_layer.h`, `ahb_swapchain.h`, `effects_chain.h`,
  `framegen_bridge.h`, `cardboard.h` — the same APIs as upstream, with the
  Android-only entry points reporting that the capability is missing. The
  compositor already handles that state: it only takes the zero-copy path
  when `sc_layer_available()` and `vkp_quest_layer_presentation()` are
  true, and otherwise presents through `vkp_render`, which is the port's
  GLES path.

### The wlroots-patched Wayland headers

Upstream links a **bionic** `libwayland-server.so` built against
wlroots' patched Wayland headers, which add a `release` request to
`wl_data_device_manager`. Stock Wayland has no such request, so
`wl_clipboard.c` does not compile against it.

The port uses upstream's patched headers (they are portable C) with a
**musl** `libwayland-server.so` built from stock Wayland 1.22. The
compositor's extra `release` handler is simply never dispatched, because
the library's interface does not describe that request.

### The Wayland socket

The compositor creates its socket at `$XDG_RUNTIME_DIR/wayland-0`
(`compositor.c:3914`), and the guest has to be told where that is. Two
details matter:

* The app sets `XDG_RUNTIME_DIR` for the compositor thread to the
  directory the guest's `/runtime` is bound to, so both sides name the
  same place.
* The guest's environment is rebuilt from scratch by `env -i`, so the
  variables travel as **arguments** to `env -i` rather than being
  inherited — `WAYLAND_DISPLAY=wayland-0` and `XDG_RUNTIME_DIR=/runtime`
  are passed that way, exactly as upstream's `RootSpawner` does. Passing
  them through the process environment instead would silently wipe them
  and the guest would never find the socket.

The wiring is pinned by `TestProotPassesTheGuestEnvironment` in
`entry/src/main/cpp/tests/runtime_test.cpp`.

### The Wayland socket

The compositor creates its socket at `$XDG_RUNTIME_DIR/wayland-0`
(`compositor.c:3914`), and the guest has to be told where that is. Two
details matter:

* The app sets `XDG_RUNTIME_DIR` for the compositor thread to the
  directory the guest's `/runtime` is bound to, so both sides name the
  same place.
* The guest's environment is rebuilt from scratch by `env -i`, so the
  variables travel as **arguments** to `env -i` rather than being
  inherited — `WAYLAND_DISPLAY=wayland-0` and `XDG_RUNTIME_DIR=/runtime`
  are passed that way, exactly as upstream's `RootSpawner` does. Passing
  them through the process environment instead would silently wipe them
  and the guest would never find the socket.

The wiring is pinned by `TestProotPassesTheGuestEnvironment` in
`entry/src/main/cpp/tests/runtime_test.cpp`.

### The Vulkan driver the guest gets

Upstream pins the guest to a driver the phone can actually offer:

```java
// LinuxRuntime.vulkanIcd()
File[] manifests = icdDir.listFiles((dir, name) -> name.endsWith(".json"));
for (File manifest : manifests) {
    if (manifest.getName().contains("freedreno")) return manifest;   // Adreno
}
```

and `SessionService.kt` then adds `VK_ICD_FILENAMES=<that file>` to the
guest environment.

Two details matter, and the port had both of them wrong at first:

* **`VK_ICD_FILENAMES` names manifest *files*.** The port passed the
  *directory* that holds them, which the loader ignores, leaving the guest
  to pick a driver on its own.
* **The driver has to be one the guest can use.** The published image
  ships eleven manifests — `intel`, `lvp`, `nouveau`, `virtio`,
  `powervr_mesa`, `asahi`, `freedreno`, `panfrost`, `broadcom`,
  `gfxstream_vk` and `radeon` — and every hardware driver needs a device
  node the guest cannot have:
  HarmonyOS does not give an app a `/dev/kgsl` or a `/dev/mali0`. Only
  **lavapipe** (`lvp_icd.json`, Mesa's software Vulkan driver) renders
  without one, so the port pins that.

The port also sets `LIBGL_ALWAYS_SOFTWARE=1` and `GALLIUM_DRIVER=llvmpipe`
for the guest's OpenGL stack, which is the same fallback upstream's own
session script reaches for when its accelerated path is unavailable. The
guest therefore renders on the CPU — slowly, but it renders, and the
Wayland compositor path (guest → gamescope → socket → XComponent) is the
same one upstream uses.

The manifest path handed to the guest is the **guest's** path,
`/usr/share/vulkan/icd.d/lvp_icd.json`, because the rootfs is the guest's
/ and the guest's environment is rebuilt inside it. The port checks the
file on the host, where the rootfs lives, and hands over the path the
guest will see. In `RuntimeMode::Direct` there is no path rewriting at
all, so absolute paths inside the guest are not remapped — which is one
of the limits of that mode.

### The presentation layer is done

`render/vk_present.h` keeps upstream's API **exactly**, declaration for
declaration, so the ported `compositor.c` and `wl_dmabuf.c` compile against
it unchanged. The implementation is different: upstream keeps guest frames
in Vulkan images and presents them with `VK_KHR_android_surface`, while
HarmonyOS guarantees EGL and GLES but not Vulkan.

So the port:

* keeps the frames in **GLES textures** (`render/vkp_image.h`), created in
  the same EGL context as the XComponent's window, so they can be drawn
  straight into the screen;
* **presents** by drawing the texture into the XComponent's surface
  (`SurfaceHost::DrawTextureRect`), with the scene scaled onto the
  surface;
* uploads the guest's shared memory buffers, which are BGRA with a
  possibly wider stride, using `GL_UNPACK_ROW_LENGTH`, and falls back to a
  CPU channel swap on drivers without `GL_EXT_texture_format_BGRA8888`;
* reports the Vulkan-only capabilities as **unavailable**
  (`vkp_can_import_ahb()` is 0, dma-buf import returns null), which is the
  state upstream's compositor already handles for devices without an
  accelerated path — the guest then falls back to shared memory buffers.

The module exports the C symbols upstream's compositor links against
(`vkp_render`, `vkp_image_create_shm`, `vkp_image_upload_shm`,
`vkp_ready`, `vkp_output_size`, ...), so the ported compositor needs no
source changes on this side.

What is left is the compositor itself: copy the protocol layer, replace
the AOSP files listed above, and turn the JNI entry points into Node-API.
* Connect the compositor to the XComponent surface, replacing the
  AHardwareBuffer path with a blit into `SurfaceHost::PublishFrame()`.
* Rebuild the Wayland output layer on `VK_KHR_ohos_surface`.
* Rebuild the PulseAudio chain against musl and replace the AAudio sink
  with the ArkTS `audio.AudioRenderer` (already wired up in
  `entry/src/main/ets/service/AudioService.ets`). See
  **The audio chain** below for what that actually means — the direction
  has to be chosen first, and it is not the one the port's current code
  assumes.
* Rebuild the Rust store engines as a `napi` module.

### The compositor seam

Upstream's compositor is `app/src/main/cpp/waylandcomp` — 15,739 lines of C
across `src/` plus 14 generated protocol files. The protocol layer is
OS-agnostic; the rendering layer is not, and it is woven into the core
rather than sitting behind a clean seam. `src/compositor.c` includes
`sc_layer.h`, `ahb_swapchain.h`, `vk_present.h`, `framegen_bridge.h`,
`effects_chain.h` and `cardboard.h` directly.

That gives the port its shape — replace these, keep the rest:

| Upstream file | Role on Android | What HarmonyOS needs | Ported |
|---|---|---|---|
| `ahb_swapchain.c` | AHardwareBuffer swap chain | Not available: AHardwareBuffer is an AOSP type | `compositor/ohos_modules.c` |
| `sc_layer.c` | SurfaceControl layers, zero-copy | **Delete.** `libandroid.so` has no OHOS counterpart | `compositor/ohos_modules.c` |
| `wl_android_buffer.c` | `wl_buffer` over AHardwareBuffer | **Not ported** — the guest uses shared memory instead | not ported |
| `vk_present.c` | `VK_KHR_android_surface` | `VK_KHR_ohos_surface`, or an EGL blit | **done** — `render/vk_present.cpp` |
| `effects_chain.c`, `cardboard.c`, `framegen_*` | Post-processing | Optional; drop for the first working session | `compositor/ohos_modules.c` |
| `waylandcomp_jni.c` | JNI entry points | Node-API, matching the rest of this port | **done** — `compositor/compositor_ohos.c` |
| `wl_clipboard.c`, `wl_color_mgmt.c`, `wl_dmabuf.c`, `wl_single_pixel.c`, `wl_text_input.c`, `wl_toplevel_icon.c`, `droiddeck_ext.c` | Protocol plumbing | Carry over unchanged | **ported as-is** |

The protocol layer and `compositor.c` are compiled **unchanged** from
upstream. What the port adds around them:

* `compat/android/log.h` — AOSP's liblog on top of hilog. The compositor
  logs through `__android_log_print`, which has no HarmonyOS counterpart.
* `compat/android/hardware_buffer.h` — an opaque `AHardwareBuffer`, because
  the ported compositor refers to the type without the `struct` tag.
* `compositor/{sc_layer,ahb_swapchain,effects_chain,framegen_bridge,cardboard}.h`
  — the same APIs as upstream, with the Android-only entry points
  reporting that the capability is missing. The compositor already handles
  that state: it only takes the zero-copy path when `sc_layer_available()`
  and `vkp_quest_layer_presentation()` are true, and otherwise presents
  through `vkp_render`, which is the port's GLES path.

### The wlroots-patched Wayland headers

Upstream links a **bionic** `libwayland-server.so` built against
wlroots' patched Wayland headers, which add a `release` request to
`wl_data_device_manager`. Stock Wayland has no such request, so
`wl_clipboard.c` does not compile against it.

The port uses upstream's patched headers (they are portable C) with a
**musl** `libwayland-server.so` built from stock Wayland. The
compositor's extra `release` handler is simply never dispatched, because
the library's interface does not describe that request. The two layouts
agree where it matters: `release` is the third member, and the stock
server only ever dispatches the first two, which sit at the same offsets
in both.

This is why `tools/build_wayland_server.sh` installs the **library** but
never the headers. The generated headers in
`entry/src/main/cpp/thirdparty/wayland/include/` are upstream's patched
ones, and replacing them with stock ones silently breaks the compositor
build with *field designator 'release' does not refer to any field*. The
script now checks the vendored header instead of overwriting it.

### The presentation layer

Upstream keeps guest frames in Vulkan images and presents them with
`VK_KHR_android_surface`. HarmonyOS guarantees EGL and GLES but not
Vulkan, so the port keeps the API and swaps the implementation:
`render/vkp_image.h` holds the frames in **GLES textures** created in the
same EGL context as the XComponent's window, and `render/vk_present.cpp`
presents them by drawing into that window.

### The Wayland socket

The compositor creates its socket at `$XDG_RUNTIME_DIR/wayland-0`
(`compositor.c:3914`), and the guest has to be told where that is. Two
details matter:

* The app sets `XDG_RUNTIME_DIR` for the compositor thread to the
  directory the guest's runtime directory is bound to, so both sides name
  the same place.
* The guest's environment is rebuilt from scratch by `env -i`, so the
  variables travel as **arguments** to `env -i` rather than being
  inherited — `WAYLAND_DISPLAY=wayland-0` and
  `XDG_RUNTIME_DIR=/run/droiddeck` are passed that way, exactly as
  upstream's `RootSpawner` does. Passing them through the process
  environment instead would clear them, and the guest would never find
  the socket.

The guest path is `/run/droiddeck`, not `/runtime`. That is upstream's
`LinuxRuntime.GUEST_RUNTIME_DIR`, and the guest's own programs look for
the compositor's socket there; a port that picked a different path would
have the compositor and the guest disagree about where the socket is.
The host directory is bound into the guest twice, at its own path and at
`/run/droiddeck`, so either name resolves.

The wiring is pinned by `TestProotPassesTheGuestEnvironment` in
`entry/src/main/cpp/tests/runtime_test.cpp`.

### The overlay: an upstream step the port has to add

Upstream keeps a directory of guest-side programs in
`tools/linuxfs/overlay/`. It holds 84 files, and among them
`/usr/local/bin/droiddeck-session` — the script that starts the Steam
client, the desktop and the compatibility layers, and that reads the
`BL_*` environment variables.

That overlay is applied when upstream **builds** the runtime image, not
when it installs it. Upstream's own installer downloads an image that
already contains the overlay.

The published image does not. Downloading
`ubuntu-r1-rc4.tar.zst` and listing it shows 26,022 entries, of which
exactly 8 live in `usr/local/bin` — `apt`, `apt-get`, `aptitude`, `pkexec`,
`su`, `sudo`, `sudoedit` — and no `droiddeck-session`. The image ships
`usr/games/gamescope`, `etc/droiddeck/empty` and `etc/droiddeck/proc/`,
but nothing that can start a session.

So the port has to apply the overlay itself. `runtime/overlay/` is a
verbatim copy of upstream's directory, packed into the HAP as a rawfile
and extracted over the unpacked rootfs by the native installer, with the
file modes preserved so the session script stays executable. The
supervisor refuses to report a runtime as installed until
`/usr/local/bin/droiddeck-session` exists, which is what stops a
half-installed runtime from looking usable.

The overlay carries a second upstream directory as well:
`tools/linuxfs/desktop/`. Upstream bakes five of its files into the image
it builds (`tools/components/recipes/runtime.sh`) and stages all six from
its APK assets (`SessionFiles.DESKTOP_FILES`), and the published image has
none of them:

| Guest path | Upstream's file |
|---|---|
| `usr/local/bin/droiddeck-desktop` | `tools/linuxfs/desktop/droiddeck-desktop` |
| `usr/local/bin/droiddeck-gpu` | `tools/linuxfs/desktop/droiddeck-gpu` |
| `usr/local/bin/droiddeck-desktop-gpu` | `tools/linuxfs/desktop/droiddeck-desktop-gpu` |
| `usr/local/bin/kwin_wayland_wrapper` | `tools/linuxfs/desktop/kwin_wayland_wrapper` |
| `usr/lib/firefox/defaults/pref/droiddeck.js` | `tools/linuxfs/desktop/firefox-droiddeck.js` |
| `etc/xdg/autostart/droiddeck-clipboard.desktop` | `tools/linuxfs/desktop/droiddeck-clipboard.desktop` |

The first one is what the session script's desktop mode execs, so without
it the guest session starts and dies on the first `exec`. The port vendors
all six into the overlay, at the paths upstream uses, and
`tools/verify_upstream_runtime.sh` checks that they land in the unpacked
rootfs.

This is the kind of difference that only shows up when the real artifact
is installed rather than a fixture built to match expectations, which is
why `tools/verify_upstream_runtime.sh` exists.

### The session mode the port can actually use

Upstream's session script takes a mode: `desktop` execs `pcmanfm`, `lxqt`
starts the KDE desktop, `steam` starts the Steam client and `run` starts a
program. The port starts the guest in **`steam`**.

The other two need more than the published runtime carries. `pcmanfm` comes
with upstream's LXQt desktop pack (`tools/desktop-kde/lxqt-applications.txt`),
and `lxqt` needs the pack's `/usr/bin/kwin_wayland_wrapper` as well — the
one the port's `/usr/local/bin/kwin_wayland_wrapper` forwards to. The
published image has neither, and the guest cannot install them: upstream
installs packages through `droiddeck-root`, a helper that talks to the
**Android** app's root service over `/etc/droiddeck/root-socket`, and the
guest's own `apt` needs a real root that a HarmonyOS app does not have.

The `steam` mode needs neither. `droiddeck-steam-install` downloads the
client with `curl` and unpacks it into the guest's home, and both `curl`
and `unzip` are in the image. That is also the mode DroidDeck is for.

The guest's offline apt cache (`/var/lib/droiddeck/apt/`, with the kwin and
mesa packages) is therefore unusable here, which is worth knowing: it
means the desktop stays out of reach until the app can install packages
itself.

### The compositor seam

Upstream's compositor is `app/src/main/cpp/waylandcomp` — 15,739 lines of C
across `src/` plus 14 generated protocol files. The protocol layer is
OS-agnostic; the rendering layer is not, and it is woven into the core
rather than sitting behind a clean seam. `src/compositor.c` includes
`sc_layer.h`, `ahb_swapchain.h`, `vk_present.h`, `framegen_bridge.h`,
`effects_chain.h` and `cardboard.h` directly.

That gives the port its shape — replace these, keep the rest:

| Upstream file | Role on Android | What HarmonyOS needs | Ported |
|---|---|---|---|
| `ahb_swapchain.c` | AHardwareBuffer swap chain | Not available: AHardwareBuffer is an AOSP type | `compositor/ohos_modules.c` |
| `sc_layer.c` | SurfaceControl layers, zero-copy | **Delete.** `libandroid.so` has no OHOS counterpart | `compositor/ohos_modules.c` |
| `wl_android_buffer.c` | `wl_buffer` over AHardwareBuffer | **Not ported** — the guest uses shared memory instead | not ported |
| `vk_present.c` | `VK_KHR_android_surface` | `VK_KHR_ohos_surface`, or an EGL blit | **done** — `render/vk_present.cpp` |
| `effects_chain.c`, `cardboard.c`, `framegen_*` | Post-processing | Optional; drop for the first working session | `compositor/ohos_modules.c` |
| `waylandcomp_jni.c` | JNI entry points | Node-API, matching the rest of this port | **done** — `compositor/compositor_ohos.c` |
| `wl_clipboard.c`, `wl_color_mgmt.c`, `wl_dmabuf.c`, `wl_single_pixel.c`, `wl_text_input.c`, `wl_toplevel_icon.c`, `droiddeck_ext.c` | Protocol plumbing | Carry over unchanged | **ported as-is** |

The protocol layer and `compositor.c` are compiled **unchanged** from
upstream. What the port adds around them:

* `compat/android/log.h` — AOSP's liblog on top of hilog. The compositor
  logs through `__android_log_print`, which has no HarmonyOS counterpart.
* `compat/android/hardware_buffer.h` — an opaque `AHardwareBuffer`, because
  the ported compositor refers to the type without the `struct` tag.
* `compositor/{sc_layer,ahb_swapchain,effects_chain,framegen_bridge,cardboard}.h`
  — the same APIs as upstream, with the Android-only entry points
  reporting that the capability is missing. The compositor already handles
  that state: it only takes the zero-copy path when `sc_layer_available()`
  and `vkp_quest_layer_presentation()` are true, and otherwise presents
  through `vkp_render`, which is the port's GLES path.

### The wlroots-patched Wayland headers

Upstream links a **bionic** `libwayland-server.so` built against
wlroots' patched Wayland headers, which add a `release` request to
`wl_data_device_manager`. Stock Wayland has no such request, so
`wl_clipboard.c` does not compile against it.

The port uses upstream's patched headers (they are portable C) with a
**musl** `libwayland-server.so` built from stock Wayland. The
compositor's extra `release` handler is simply never dispatched, because
the library's interface does not describe that request.

### The presentation layer

Upstream keeps guest frames in Vulkan images and presents them with
`VK_KHR_android_surface`. HarmonyOS guarantees EGL and GLES but not
Vulkan, so the port keeps the API and swaps the implementation:
`render/vkp_image.h` holds the frames in **GLES textures** created in the
same EGL context as the XComponent's window, and `render/vk_present.cpp`
presents them by drawing into that window.

### The Wayland socket

The compositor creates its socket at `$XDG_RUNTIME_DIR/wayland-0`
(`compositor.c:3914`), and the guest has to be told where that is. Two
details matter:

* The app sets `XDG_RUNTIME_DIR` for the compositor thread to the
  directory the guest's `/runtime` is bound to, so both sides name the
  same place.
* The guest's environment is rebuilt from scratch by `env -i`, so the
  variables travel as **arguments** to `env -i` rather than being
  inherited — `WAYLAND_DISPLAY=wayland-0` and `XDG_RUNTIME_DIR=/runtime`
  are passed that way, exactly as upstream's `RootSpawner` does. Passing
  them through the process environment instead would clear them, and the
  guest would never find the socket.

The wiring is pinned by `TestProotPassesTheGuestEnvironment` in
`entry/src/main/cpp/tests/runtime_test.cpp`.

**Phase 3 — the input and the rest of the surface**

* Re-plumb the gamepad and touch normalisation onto `@kit.MultimodalInput`.
* Replace `MediaCodec` with `AVCodec` for Remote Play and xCloud.
* Decide what to do about the features with no HarmonyOS equivalent:
  widget host, shortcut pinning, accessibility service, live wallpaper,
  virtual displays.

## 7. Building and verifying

See `README.md` for the full build steps. The native module can be compiled on
its own against the OpenHarmony 7.0 NDK, which is how it was verified:

```bash
export OHOS_SDK_HOME=/path/to/openharmony-sdk          # the SDK root
export OHOS_SDK_NATIVE=$OHOS_SDK_HOME/native           # the NDK
export PATH="$PATH:$HOME/Library/Python/3.14/bin"      # cmake

cmake -S entry/src/main/cpp -B build/native \
  -DCMAKE_TOOLCHAIN_FILE="$OHOS_SDK_NATIVE/build/cmake/ohos.toolchain.cmake" \
  -DOHOS_ARCH=arm64-v8a -DOHOS_PLATFORM=OHOS -DOHOS_STL=c++_shared
cmake --build build/native
```

This produces `build/native/libdroiddeck_native.so` for `arm64-v8a`, built
against musl with the OpenHarmony 7.0 headers (`napi/native_api.h`,
`ace/xcomponent/native_interface_xcomponent.h`, `native_window/external_window.h`,
`EGL/egl.h`, `GLES3/gl3.h`, `zlib.h`).

### What the NDK changed

Three upstream assumptions did not survive contact with the API 26 headers,
and the port was adjusted rather than papered over:

| Assumed | Reality in API 26 | Adjustment |
|---|---|---|
| `OH_AudioRenderer_Create` / `OH_AudioRenderer_Write` | The C audio API is builder-based (`OH_AudioStreamBuilder_*`) and playback is callback-driven | The native layer only pumps PCM out of the guest socket; ArkTS feeds it to `audio.AudioRenderer` |
| `OH_NativeXComponent_GetNativeXComponentById` | Only `OH_NativeXComponent_GetNativeXComponent(node)` and `OH_NativeXComponent_RegisterCallback` exist | The module exports the `OnSurfaceCreated` / `OnSurfaceChanged` / `OnSurfaceDestroyed` plugin entry points, which is how the framework hands over the window |
| `linux/uinput.h` in the NDK | Not shipped | The uinput ABI is declared locally in `input/gamepad.cpp` |
