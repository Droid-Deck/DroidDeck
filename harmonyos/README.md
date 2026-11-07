# DroidDeck for HarmonyOS 7

A native HarmonyOS 7 (API 26) port of [DroidDeck](https://github.com/Droid-Deck/DroidDeck),
the launcher that brings the SteamOS experience to a handheld.

Upstream DroidDeck is an Android app (Kotlin + Compose, with a C++/Rust native
layer) that hosts a Linux guest running Steam, Proton and a KDE desktop.
HarmonyOS 7 dropped the Android APK compatibility layer on phone-class
devices, so this is a **real port**: ArkTS for the launcher, and the OHOS NDK
for everything that has to touch the guest.

## Layout

```
harmonyos/
├── AppScope/                     app name, icon, bundle metadata
├── build-profile.json5           SDK 26 product + module wiring
├── hvigor/                       Hvigor configuration
├── runtime/
│   └── overlay/                  upstream's runtime overlay, applied on top
│                                 of the published image (see below)
├── entry/
│   ├── src/main/
│   │   ├── ets/
│   │   │   ├── components/       GameCard, BottomNav
│   │   │   ├── entryability/     EntryAbility
│   │   │   ├── model/            Game, DeviceInfo + GPU classification
│   │   │   ├── native/           NativeBridge (Node-API bindings)
│   │   │   ├── pages/            Index, Home, Library, Stores, Downloads,
│   │   │   │                     Settings, GameDetail, Session, ControllerMap
│   │   │   ├── service/          HttpService, SteamService, RuntimeService
│   │   │   ├── state/            AppStore (the shared observable state)
│   │   │   └── common/           Logger, Paths, Prefs
│   │   ├── cpp/                  the OHOS NDK module
│   │   │   ├── device/           EGL GPU identification
│   │   │   ├── runtime/          guest supervisor, install path, tar unpacker
│   │   │   ├── input/            uinput gamepad
│   │   │   ├── surface/          EGL surface on an ArkUI XComponent
│   │   │   ├── audio/            guest PCM reader
│   │   │   ├── render/           guest frames as GLES textures, and the
│   │   │   │                     upstream-compatible presentation layer
│   │   │   ├── compositor/       upstream's Wayland compositor, with the
│   │   │   │                     Android-only modules replaced
│   │   │   ├── compat/           AOSP headers the ported code expects
│   │   │   ├── types/            the Node-API declarations ArkTS sees
│   │   │   ├── tests/            host-side native tests
│   │   │   └── thirdparty/       musl libwayland-server headers, zstd
│   │   └── resources/            strings, colours, media, rawfile/overlay
│   ├── libs/arm64-v8a/             the libraries the HAP ships
│   └── build-profile.json5       native build options (arm64-v8a)
├── docs/PORTING.md               the full Android -> HarmonyOS mapping
└── tools/
    ├── build_hap.sh               builds the HAP with DevEco Studio's hvigor
    ├── build_wayland_server.sh   rebuilds libwayland-server for musl
    ├── build_overlay_tar.sh      packs the runtime overlay into the HAP
    ├── build_pulseaudio.sh       rebuilds PulseAudio for musl, and the sink
    ├── build_zstd.sh             rebuilds the vendored zstd
    ├── check_app_config.py       validates the manifests against the SDK
    ├── check_guest_paths.py      checks the guest is told guest paths
    ├── check_guest_tools.py      checks the image has the guest's programs
    ├── check_native_api.py       checks the declarations against the module
    ├── check_native_deps.sh      checks the shipped libraries can be loaded
    ├── fetch_upstream_runtime.sh downloads the published runtime
    ├── install_cxx_runtime.sh    installs libc++_shared.so into the HAP
    ├── package_port.sh           packs the port into a zip
    ├── patch_build_profile.py    patches the build profiles for the CLI build
    ├── pulseaudio/               the stubs musl needs for PulseAudio
    ├── test_native_host.sh       runs the native unit tests
    ├── verify_upstream_runtime.sh  installs the real published runtime
    ├── typecheck_arkts.py        type-checks the ArkTS against the SDK
    └── theme_palette.py          regenerates the ArkUI palette from upstream's
                                  Kotlin colour maths
```

## Building

### With DevEco Studio

1. Open the `harmonyos` directory as a project.
2. Let DevEco Studio 26.0.0 sync the `HarmonyOS SDK 26.0.0` (or point
   `local.properties` at an OpenHarmony 7.0 SDK).
3. **File > Project Structure > Signing Configs** and add your own signing
   profile — the repository deliberately ships no keys.
4. **Build > Build Hap(s)/APP(s) > Build Hap(s)**.

The HAP lands in `entry/build/default/outputs/default/entry-default-signed.hap`.

### From the command line

The public OpenHarmony SDK ships the NDK, the ArkTS compiler
(`ets/build-tools/ets-loader`), the resource compiler (`toolchains/restool`) and
`hdc`. **Hvigor, ohpm and Node are not in it** — they ship with DevEco Studio,
so the build has to borrow that installation:

```bash
cd harmonyos
tools/build_hap.sh
```

The script finds DevEco Studio (set `DEVECO_HOME` if it is not in
`/Applications`), uses the SDK that DevEco installed, derives the SDK version
string from that SDK and writes `local.properties`. The result is an
**unsigned** HAP under `entry/build/default/outputs/default/`.

Two switches change what it does:

- `--sdk <root>` builds against another SDK root, for example a plain
  OpenHarmony SDK. The root has to hold the `openharmony` and/or `hms`
  component directories, because Hvigor picks the API from them based on the
  `runtimeOS` in `build-profile.json5`.
- `--native` rebuilds the native module from source with the SDK's NDK
  instead of shipping the prebuilt libraries in `entry/libs/arm64-v8a`. The
  default is to ship them: Hvigor packages that directory as it stands, and
  rebuilding the native module in place would replace the prebuilt PulseAudio
  and Wayland libraries that the HAP needs.

A phone only installs a HAP that Huawei has signed, so the next step is
DevEco Studio's **File > Project Structure > Signing Configs > Automatically
generate signature**.

### What the HAP ships in `entry/libs/arm64-v8a/`

The app resolves its libraries **by soname** out of that directory, so
every library the native module needs has to be there, under the name its
soname says:

| File | Why |
|---|---|
| `libdroiddeck_native.so` | the native module itself, built by hvigor or by cmake |
| `libwayland-server.so` | the Wayland server core, rebuilt for musl |
| `libc++_shared.so` | the C++ runtime the native module links against |
| `libpulsecore-17.0.so` | the PulseAudio server core, rebuilt for musl |
| `libpulsecommon-17.0.so` | the code PulseAudio's two sides share |
| `libpulse.so.0` | PulseAudio's client library, which the guest's audio client links against |
| `libpulse-simple.so.0` | PulseAudio's simple client API |
| `libprotocol-native.so` | the native protocol's wire format, which the modules link against |
| `module-native-protocol-unix.so` | the socket the guest's clients connect to |
| `module-native-protocol-fd.so` | the same protocol over a file descriptor |
| `module-droiddeck-sink.so` | the sink module that writes the audio to the speaker |

```bash
OHOS_SDK_HOME=/path/to/openharmony-sdk tools/install_cxx_runtime.sh
```

A file whose name does not match its soname cannot be found on the device:
the module loads and then fails with *cannot open shared object file*.
`tools/check_native_deps.sh` checks every shipped library for that.

### Verifying without DevEco Studio

Hvigor is not published to a public registry, so the two halves of the
project can be checked against the OpenHarmony SDK on their own.

**The native module** — needs only the SDK's NDK:

```bash
export OHOS_SDK_HOME=/path/to/openharmony-sdk
export OHOS_SDK_NATIVE=$OHOS_SDK_HOME/native
cmake -S entry/src/main/cpp -B build/native \
  -DCMAKE_TOOLCHAIN_FILE="$OHOS_SDK_NATIVE/build/cmake/ohos.toolchain.cmake" \
  -DOHOS_ARCH=arm64-v8a -DOHOS_PLATFORM=OHOS -DOHOS_STL=c++_shared
cmake --build build/native
```

**The guest-launch logic and the install path** — plain C++, runs anywhere:

```bash
tools/test_native_host.sh
```

Two things are pinned by these tests:

* the **guest-launch argv** has to match upstream exactly, because upstream's
  proot prefix and bind list decide how the guest sees the filesystem. The
  tests assert the port produces the same command line, the same binds and
  the same guest environment, and that the direct-exec fallback does not
  pretend to have path rewriting;
* the **runtime install path** — a fixture that mimics the published image is
  unpacked by the ported unpacker, the overlay is applied on top of it, and
  the supervisor is then asked whether it considers the result an installed
  runtime.

**The published runtime** — the real thing, end to end:

```bash
tools/fetch_upstream_runtime.sh   # 291 MB, checksum verified
tools/verify_upstream_runtime.sh  # unpack, overlay, recognise as installed
```

The second script is the strongest check the port has without a phone: it
installs the exact artifact a phone downloads — the 26,022-entry upstream
`ubuntu-r1-rc4` image — and asserts that the result is a runtime the
supervisor accepts.

`tools/check_guest_tools.py` checks that same image against the guest
session: every program upstream's session script runs has to be in the
image, or the session fails half way through its start-up. The programs
upstream guards with `command -v` are reported separately, because their
absence only skips the feature that uses them.

**The app configuration** — validated against the SDK's own schemas:

```bash
python3 tools/check_app_config.py
```

The OpenHarmony SDK ships the schemas the build uses to check an app's
configuration, in `toolchains/modulecheck/` and `toolchains/configcheck/`.
This tool validates `module.json5`, `app.json5` and the pages profile
against them, and adds what the schemas cannot express: that every
`$string:`/`$media:`/`$color:`/`$profile:` reference resolves in the scope
it is used in, that the pages the manifest lists exist as ArkTS files, and
that the ability's source entry is there.

Two places where the SDK's own schemas are wrong are reported rather than
hidden, and both are explained in the tool:

* the `deviceTypes` enum omits `phone` — Huawei's store FAQ says
  `default` compiles but cannot be published, and recommends `phone`;
* the `apiReleaseType` pattern is an unanchored alternation, so it rejects
  the documented value `Release`.

**The ArkTS sources** — uses the SDK's own TypeScript compiler:

```bash
python3 tools/typecheck_arkts.py            # check
python3 tools/typecheck_arkts.py --strict   # warnings fail too
python3 tools/typecheck_arkts.py --selftest # prove the check really works
```

The checker splits the sources in two, because the ArkUI declarative DSL
(`Column() { ... }`) is not valid TypeScript and only the ArkTS compiler
understands it:

* the plain-TypeScript modules (`common`, `model`, `native`, `service`,
  `state`, `entryability`) get a full semantic check against the API 26
  SDK declarations;
* the unit tests under `entry/src/test` get the same check, so a test
  that cannot compile is not a test;
* the ArkUI files (`components`, `pages`) get structural checks instead:
  balanced delimiters, imports that resolve, and resource references that
  resolve against the string and colour tables.

**Errors fail the check; warnings do not**, unless `--strict` is given. The
exit code is the check's result, so `python3 tools/typecheck_arkts.py`
returning 0 means the port's TypeScript compiles cleanly.

The **native module's calls are type-checked too**. The ArkTS compiler
resolves a Node-API module through the module's `oh-package.json5` and
checks the calls against the declared signatures; plain TypeScript does
not know that mechanism and quietly downgrades an unverified native module
to `any`. The checker therefore resolves the import to
`entry/src/main/cpp/types/libdroiddeck_native/index.d.ts` directly, so a
call with the wrong arguments is caught here rather than on a phone.

That declaration file is the contract between the two sides, and
`tools/check_native_api.py` checks it against the module: every function
the native code registers has to be declared, every declared function has
to be registered, and every name has to be present in the built library.

`--selftest` injects a deliberate type error and fails if the checker does
not catch it, so a clean result can be trusted.

**The theme** can be regenerated from upstream's own colour maths:

```bash
python3 tools/theme_palette.py
```

**PulseAudio** is rebuilt for musl from source as well, because the host
runs the PulseAudio server the guest's audio client connects to:

```bash
OHOS_SDK_HOME=/path/to/openharmony-sdk tools/build_pulseaudio.sh
```

Upstream does not build it either — it links a bionic prebuilt — and musl
is missing `gettext`, `libsndfile` and `libltdl`. The port brings what it
can (`libltdl` from the GNU libtool release) and stubs the rest, because
the port never plays a sound file and never translates a string. The
script also builds the sink module that writes the guest's audio to the
socket the native side reads. `docs/PORTING.md` has the details.

**libwayland-server** is rebuilt for musl from source, because upstream
ships a bionic prebuilt that cannot be loaded on HarmonyOS:
```bash
OHOS_SDK_HOME=/path/to/openharmony-sdk tools/build_wayland_server.sh
```

It cross-compiles wayland 1.22.0 and libffi 3.4.6 with the OHOS NDK and
installs the result into `entry/libs/arm64-v8a/`. The script is verified
end to end on a clean work directory.

The script installs the **library** but never the headers:
`entry/src/main/cpp/thirdparty/wayland/include/` holds upstream's
wlroots-patched Wayland headers, which the ported compositor needs, and
replacing them with stock ones breaks the compositor build. The script
checks the vendored headers instead of overwriting them.

The rebuilt library's SONAME is `libwayland-server.so`, matching the file
name that ships in the HAP, so the loader can resolve it on the device.

**The shipped libraries** have to be loadable on the device:

```bash
OHOS_SDK_HOME=/path/to/openharmony-sdk tools/check_native_deps.sh
```

A shared library records its dependencies by name, and the loader looks
each name up in the app's own library directory. A library whose SONAME
does not match the file name that ships is therefore unresolvable: the
module loads and then fails with *cannot open shared object file* the
moment the compositor starts. The check reads the `DT_NEEDED` entries of
every library the HAP ships and asserts that each one is either a
platform library or a file that ships under exactly that name.

## Installing the Linux runtime

The launcher installs the runtime the same way upstream's
`LinuxRuntimeInstaller` does, with one extra step that upstream does not
need:

```
catalog  ->  url + sha256  ->  download  ->  verify  ->  unpack  ->  overlay
```

* **catalog** — `linuxfs-ubuntu.json` in upstream's `DroidDeck-Components`
  repository, the same document upstream reads. It names the release, the
  download URL and the SHA-256.
* **download** — through the platform download manager, so a 291 MB transfer
  survives the app being backgrounded.
* **verify** — the native layer computes the SHA-256 of the download and
  compares it with the catalog's digest *before* anything is written. A
  corrupted download never reaches the rootfs.
* **unpack** — the published image is a zstd-compressed tar with 26,022
  entries, 4,640 symlinks and 117 hard links. The port vendors zstd, detects
  the compression from the file's magic number, and streams the archive
  through a tar reader that restores all three entry kinds.
* **overlay** — see below.

### The overlay, and why the port needs it

Upstream keeps a directory of guest-side programs in
`tools/linuxfs/overlay/`, including `/usr/local/bin/droiddeck-session` — the
script that actually starts the Steam client, the desktop and the
compatibility layers. Upstream bakes that overlay into the runtime image at
**build** time.

The image upstream publishes therefore has no `droiddeck-session` in it.
Installing it as-is gives a rootfs with gamescope and a desktop environment,
and no way to start them. Upstream never notices, because its installer
installs an image that was built with the overlay already inside.

The port downloads that same published image, so it has to apply the overlay
itself. `runtime/overlay/` is a verbatim copy of upstream's overlay, packed
by `tools/build_overlay_tar.sh` into
`entry/src/main/resources/rawfile/overlay.tar.gz` and applied over the
unpacked rootfs by the native installer, with the file modes preserved so
the session script stays executable.

This is the difference between "the download finished" and "the runtime
works", and it is the reason the supervisor insists on finding
`/usr/local/bin/droiddeck-session` before it reports a runtime as installed.

### Guest paths, matched to upstream

The port follows upstream's layout rather than inventing its own:

| What | Upstream | The port |
|---|---|---|
| Guest runtime directory | `/run/droiddeck` | `/run/droiddeck` |
| Wayland display | `wayland-0` | `wayland-0` |
| Guest `PATH` | `…:/bin:/usr/games` | `…:/bin:/usr/games` |
| Session script | `/usr/local/bin/droiddeck-session` | same, plus the legacy `bannerlator-session` |
| Version marker | `/.version` | `/.version` |
| Guest data home | `/root/.local/share` | `/root/.local/share` |

The guest environment is rebuilt through `env -i`, exactly as upstream's
`GuestCommand` does, and the `BL_*` variables upstream's session script
reads — `BL_WIDTH`, `BL_HEIGHT`, `BL_LOG`, `BL_DEBUG_DIR` — are passed
through to it.

## Testing it on a phone

The SDK ships `hdc`, the HarmonyOS device connector, which is how the app
gets onto a phone and how the guest's logs come back:

```bash
HAP=$OHOS_SDK_HOME/toolchains/hdc

# The phone has to be connected and have USB debugging on.
"$HAP" list targets

# Install a HAP built with DevEco Studio.
"$HAP" install -r entry/build/default/outputs/default/entry-default-signed.hap

# Follow the app's log, including the guest session's.
"$HAP" shell hilog | grep -i droiddeck

# The guest's own session log, which the app writes into its files dir.
"$HAP" shell "cat /data/app/el2/100/base/com.droiddeck.launcher/files/logs/session.log"
```

The guest's session log is the place to look first when a session does
not come up: upstream's session script writes `== STEP …` lines into it,
and the port passes it the same way (`BL_LOG`).

The **execution mode** the sandbox granted is in the Settings page:
`proot` means the guest gets full path rewriting, `direct` means it is
exec'd as-is and only works for guests that do not depend on absolute
paths.

## Testing it on a phone before Steam exists

The full runtime is an Arch Linux ARM rootfs with Steam, Proton and a desktop —
far too big to build here. `tools/build_smoke_rootfs.sh` produces the smallest
artifact that exercises the whole chain the port owns:

**download → unpack → recognise as installed → launch → capture output**

```bash
OHOS_SDK_HOME=/path/to/openharmony-sdk tools/build_smoke_rootfs.sh
# -> build/droiddeck-runtime-smoke-arm64.tar.gz
```

It carries the layout the supervisor looks for, plus a statically linked
guest program that runs on any Linux kernel and prints a heartbeat the
supervisor captures into the session log. Installing it on a phone answers
the question that matters first: **does the HarmonyOS sandbox let the guest
run at all?**

The runtime source is a setting, so the artifact can come from anywhere —
**Settings → Runtime source**. Serve it locally and point the launcher at it:

```bash
cd build && python3 -m http.server 8000
# then set the runtime source to
# http://<your-computer>:8000/droiddeck-runtime-smoke-arm64.tar.gz
```

The Settings page also shows the **execution mode** the sandbox granted:
`proot` (full path rewriting) or `direct` (the guest is exec'd as-is).

## What is ported, and what is not

This is the honest status. Nothing here is claimed to work on a device
unless it was verified.

| Area | Status | Notes |
|---|---|---|
| Project scaffold, module config, permissions | **done** | Targets API 26, `compileSdkVersion` 26.0.0 |
| Launcher shell in ArkUI | **done** | Home, Library, Stores, Downloads, Settings, Game detail, Controller mapping |
| Theme | **done** | Palette reproduced exactly from upstream's `ThemeColors.palette()` |
| GPU identification | **done** | EGL `GL_RENDERER` instead of Qualcomm's `/sys/class/kgsl` |
| GPU classification | **done** | Upstream's family taxonomy, including the Mali/PanVK pairs Huawei needs; unit tested |
| Native module (Node-API) | **done, compiles** | Built with the OpenHarmony 7.0 NDK into an arm64-v8a `libdroiddeck_native.so` |
| Guest supervisor | **done, tested** | Reproduces upstream's proot argv and bind list; pinned by `tools/test_native_host.sh` |
| Runtime install path | **done, tested** | Catalog → download → checksum → unpack → overlay; the real published image installs correctly |
| Guest environment | **done, checked** | The guest is told guest paths, and the image holds every program the session runs |
| Runtime overlay | **done** | Upstream's `tools/linuxfs/overlay/` vendored and applied over the unpacked rootfs |
| `libwayland-server` for musl | **done** | Rebuilt from source for arm64-v8a; upstream's bionic prebuilt cannot load on HarmonyOS |
| Presentation layer | **done** | Upstream's `vk_present` API, reimplemented over GLES textures instead of Vulkan |
| Wayland compositor | **done, links** | Upstream's protocol layer and `compositor.c` compile unchanged; JNI replaced with Node-API |
| Guest input path | **done** | ArkUI touch and key events mapped to the guest's evdev codes; unit tested |
| Wayland keymap | **done** | The guest is published upstream's keymap, without which every key comes out wrong |
| Guest session launch | **done** | The session page starts `/usr/local/bin/droiddeck-session` with upstream's `BL_*` environment |
| Guest Vulkan driver | **done** | The guest is pinned to lavapipe, the only driver in the image that works without a GPU device node |
| App configuration | **done, checked** | The manifests are validated against the SDK's own schemas; see `tools/check_app_config.py` |
| Guest audio | **built, untested** | A PulseAudio server for aarch64-musl and the sink module that feeds the speaker; see `docs/PORTING.md` |
| Virtual gamepad | **not working** | `/dev/uinput` is not available to an app; touch and keyboard still reach the guest |
| Runtime smoke artifact | **done** | A minimal installable rootfs that proves the guest can start in the sandbox |
| ArkTS type check | **done, clean** | 0 errors and 0 warnings against the API 26 SDK; the native calls are checked too; see `tools/typecheck_arkts.py` |
| Native module declarations | **done** | `entry/src/main/cpp/types/libdroiddeck_native/index.d.ts`, checked against the module by `tools/check_native_api.py` |
| ArkUI sources | **structurally checked** | The ArkUI DSL needs the ArkTS compiler, so these are checked structurally |
| Guest bring-up on a real device | **not verified** | Needs a HarmonyOS 7 device and the runtime artifact |
| HAP packaging | **not verified** | Hvigor and the ArkTS compiler are not publicly obtainable, so the build itself needs DevEco Studio |
| Steam / Proton inside the guest | **not done** | Needs the guest runtime to boot first; see `docs/PORTING.md` |
| Wayland desktop compositor | **partial** | The XComponent + EGL surface is in place; the Wayland server that draws into it is Phase 2 |

### The runtime, honestly

The Linux guest is the hard part, and the port does not pretend otherwise:

* Upstream launches the guest with **proot**, which needs `ptrace`.
  HarmonyOS sandboxes app processes, and whether `ptrace` is granted is not
  documented for third-party apps.
* The native layer therefore **probes** at runtime: `Runtime::PtraceAvailable()`
  tries `PTRACE_TRACEME` in a throwaway child. If it succeeds the port uses
  proot and gets upstream parity. If it fails the port falls back to
  `RuntimeMode::Direct`, which execs the guest loader directly with the
  rootfs on its library path. Direct mode has **no path remapping**, so it
  only works for guests that do not depend on absolute paths.
* The rootfs lives in the app's private `filesDir`. HarmonyOS has no
  `MANAGE_EXTERNAL_STORAGE`, so upstream's "bind the shared storage into the
  guest" trick is not available.
* The published image is a `.tar.zst`. The port vendors zstd for it, because
  the OHOS NDK ships zlib but no zstd. The compression is detected from the
  file's magic number, so a gzip artifact works too.
* The **overlay** is applied by the port rather than baked into the image,
  because the image upstream publishes does not contain it. See above.
* The guest renders on the **CPU**. HarmonyOS does not give an app a device
  node for the phone's GPU, so the guest cannot use freedreno or panfrost;
  the port pins the image's software driver, lavapipe.
* The **audio chain is built but unproven**. The port builds a PulseAudio
  server for aarch64-musl and a sink module that feeds ArkTS's
  `audio.AudioRenderer`, and the guest is pointed at it with
  `PULSE_SERVER` — but it has never run on a device.
* The **virtual gamepad does not work**: `/dev/uinput` is not available to
  an app. Touch and keyboard still reach the guest, through the Wayland
  compositor.

`AppStore.runtime.mode` and the Settings page report which mode the device
actually selected.

## Licence

DroidDeck is GPL-3.0. This port is a derivative work and is GPL-3.0 too.
