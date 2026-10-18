# Experimental system Vulkan backends

PowerVR, Xclipse and Mali use the bundled libhybris ICD to load the device's Android Vulkan
HAL from the Linux guest. The Android compositor uses the system Vulkan loader.
The guest Gamescope SDL backend presents through libhybris Wayland WSI and
DroidDeck's `android_wlegl` / Android hardware buffer receiver.

| Selection | PowerVR | Xclipse | Mali |
| --- | --- | --- | --- |
| HAL under `/vendor/lib64/hw/` | `vulkan.powervr.so` | `vulkan.samsung.so` | `vulkan.mali.so` |
| DRM | Accessible, identifiable PowerVR render node | Accessible, identifiable `sgpu` render node | No DRM-node requirement or handle emulation |
| Inner X11 presentation | Existing DRI3/Steam GL profile | Existing DRI3/Steam GL profile | Bundled X11 WSI with `WSI_X11_FORCE_SHM=1` |
| Steam UI rendering | Software CEF; game graphics remain hardware accelerated | Existing GPU CEF path | Existing GPU CEF path |
| Proton DXVK | Managed patched `dxvk` | Managed patched `dxvk-2.6.2` for 128-byte push constants | Managed experimental `dxvk-mali`, guarded to Mali-G715 driver 54.3.0 |
| Session / game compatibility layers | compat / WSI + compat (+ placed for i386) | compat / WSI + compat | compat / WSI + compat |
| Session / managed-game BC emulation | Existing policy / `dxvk` | `0` / `dxvk` | Existing policy / `dxvk` |

X11 layer settings are applied after the outer Gamescope launch, including direct
program and desktop sessions. `droiddeck-gpu` restores the Wayland bridge settings
before starting a nested Gamescope and passes X11 settings only to its client.
Shared-memory presentation adds a CPU copy; it does not imply software Vulkan
rendering. Actual rendering and performance still require device testing.

The app selects a profile and passes capabilities to the guest helpers. Presentation
and DXVK policy are independent: a future driver can use shared-memory presentation
with a managed DXVK payload, or native presentation with a user-selected payload.

| Environment key | Meaning |
| --- | --- |
| `DROIDDECK_SYSTEM_VULKAN_RUNTIME` | Selected bridge directory; takes precedence over the legacy alias |
| `DROIDDECK_SYSTEM_VULKAN_GPU` | Vendor profile identity; selects compatibility defaults, including PowerVR's 32-bit placed-memory path |
| `DROIDDECK_SYSTEM_VULKAN_PRESENTATION` | `native` preserves normal presentation; `x11-shm` enables the X11 client fallback |
| `DROIDDECK_SYSTEM_VULKAN_DXVK` | `managed` uses the runtime wrapper/payload; `user` preserves Proton/DXVK/WineD3D choices |
| `DROIDDECK_HYBRIS_SESSION_LAYERS` | Outer/session layer list, restored before launching a nested Gamescope |
| `DROIDDECK_HYBRIS_SESSION_BC_TEXTURES` | Optional outer/session BC policy, restored before nested compositors; Xclipse sets `0` |
| `BL_STEAM_CEF_SOFTWARE` | `1` selects `-cef-disable-gpu` for Steam's UI; selected by the PowerVR profile only |
| `DROIDDECK_EXPERIMENTAL_PLACED_MEMORY` | `1` enables the bundled low-address memory layer for confirmed 32-bit Windows games; `0` disables the PowerVR default |

`DROIDDECK_HYBRIS_LAYERS` separately specifies game compatibility layers;
`DROIDDECK_HYBRIS_LAYER_PATH` adds their directory, and `DROIDDECK_HYBRIS_DXVK`
names the managed DXVK payload. Mali Auto also sets `DXVK_MALI_COMPAT=1`; the
payload still enforces its exact GPU and driver identity. The X11 WSI layer is added
only for inner clients.
Xclipse uses the shared compatibility layer throughout. Its outer/session environment
sets `HYBRIS_BC_TEXTURES=0`; managed game launches override it to `dxvk` in their
own process environment, and nested compositors restore the session policy.
Absent or empty capability fields retain legacy helper defaults: Mali uses
`x11-shm`/`user`, and other system profiles use `native`/`managed`. The current app
passes explicit `x11-shm`/`managed` fields for Mali Auto; those legacy defaults do
not describe its current selection. The old
`DROIDDECK_POWERVR_RUNTIME` alias remains available for PowerVR and Xclipse helpers.
Manual driver selections retain their existing behavior.

### Vendor TLS bounds and loader diagnostics

The bridge reserves 1040 bytes for promoted Bionic static TLS. This accommodates
the XRing O3 report's offset 984 and 41-byte segment while retaining bounds checks.
The reservation uses glibc initial-exec TLS; increasing it to 4096 bytes would
conflict with the upstream bridge's documented Firefox late-loading limit. The
new size is the smallest 16-byte-aligned increase that contains the reported range.

Component CI builds the patched production TLS implementation as a shared library
and loads it after process startup. Its probe checks the reported range, overflow
rejection, the last legal byte and replay into a new thread. This does not replace
testing on XRing or in Firefox. The built `probe-glibc tls-bounds` command exercises
the same cases against the packaged runtime.

The ICD still requires loader interface version 5. On negotiation rejection it
logs the offered version; a failed Android `vkCreateInstance` call logs its result
separately from the existing dispatch-mismatch diagnostic. A generic
`VK_ERROR_INCOMPATIBLE_DRIVER` does not identify which of these paths failed.
Capture `VK_LOADER_DEBUG=error,warn,driver` alongside the session log before
changing the interface contract. `probe-glibc version` checks rejected and
accepted interface versions without requiring a Vulkan instance.

### BC decoding and push descriptors

The BC compatibility layer preserves native `VK_KHR_push_descriptor` support.
It records owned copies of compute push descriptors and restores them after its
decoder uses the compute pipeline. Both direct and template commands are covered,
including the `maintenance6` entry points. Template and pipeline-layout shadows
remain alive while a command buffer references them; replay of a mixed shader-stage
mask restores compute state only.

The layer forwards graphics-only pushes and inactive BC paths without recording
compute state. It retains the native extension revision and descriptor limit;
it does not add push-descriptor support to drivers that lack it. The DXVK profile
still reports Vulkan 1.3 because other Vulkan 1.4 operations remain outside the
decoder's tracking.

The focused test compiles the patched production C files. Set `HYBRIS_PUSH_SOURCE`
to a clean checkout of the revision in `tools/hybris/source.env`, then run:

```sh
python3 -m unittest tools.tests.test_hybris_push_descriptors
```

Component CI also builds `probes/probe-glibc`, with its build manifest and checksums.
The opt-in `bc-images-push-control-validation` and `bc-images-push-validation`
modes use the same Direct3D BC formats and `maintenance6` settings. The latter
exercises direct, template, and both newer KHR routes, clears caller-owned data,
and destroys application templates and layouts before submission. Texture readback
after BC uploads verifies that descriptor restoration survives actual decoder work.
Run these with `HYBRIS_BC_TEXTURES=dxvk` and the selected runtime's ICD and layers.

### 32-bit Windows memory mappings

Wine's WoW64 Vulkan path requires mapped memory below 4 GiB. On the tested
PowerVR driver, native mappings land above that range; Wine unmaps them and returns
`VK_ERROR_OUT_OF_HOST_MEMORY` even when physical memory is available. The bundled
`VK_LAYER_DROIDDECK_placed_memory` supplies `VK_EXT_map_memory_placed` using
exported `OPAQUE_FD` memory and a shared mapping at Wine's reserved address.

`droiddeck-game-env` enables this layer by default for PowerVR system-driver
launches only after checking that the actual executable is PE32/i386. The layer
runs below `VK_LAYER_HYBRIS_compat`, so resources created by BC translation follow
the same export policy. Steam, native Linux programs and 64-bit Windows programs
retain their existing layer lists. Explicit driver overrides bypass automatic
activation. Set `DROIDDECK_EXPERIMENTAL_PLACED_MEMORY=0` to disable it, or `1` to
test another system-driver profile such as Mali.

The layer keeps the native driver mapping for flush/invalidate operations and
preserves Wine's reserved address on unmap. Buffers and images use matching
external-memory creation and allocation flags. Image configurations that cannot
be exported retain native allocation with host-visible memory types excluded;
the layer does not force host-memory imports onto unsupported images. Native
placed-memory support takes precedence, and unsupported explicit external-handle
contracts fail rather than being silently rewritten.

The experimental Mali backend retains ordinary native allocations and their real
memory-type flags. On the tested G715, OPAQUE_FD export is unavailable and host
imports cannot supply coherent memory. Instead, the layer aliases the native
`/dev/mali` mapping at Wine's address, in descending chunks of at most 1 MiB.
It accepts Wine's anonymous read/write reservations as well as inaccessible
reservations, and preserves the native mapping for the driver's lifetime and
cache operations.

Mali placement requires the bundled libhybris mapping-lock API; older runtimes
do not expose this backend. The lock serializes placement with Android driver
`mmap`, `mmap64`, and `munmap` hooks, including driver worker threads, and survives
fork. Guards occupy only free address ranges and never replace unrelated mappings.
Target validation runs again under the lock before releasing Wine's reservation.
This remains an explicit experiment for the tested G715/hybris stack: arbitrary
raw syscalls, other mapping implementations, and other Mali generations have not
been validated. Source changes require rebuilding and repinning the libhybris
component before they are included in an APK.

Pixel 8 Pro validation covered two coherent 8 MiB allocations at exact low
addresses that are not 2 MiB aligned, GPU copies in both directions without
explicit cache flushing, and reserve/remap lifetime. An adversarial driver-mapping
test completed 100 placements with no reservation loss or data mismatches after
adding the shared lock. With the inline-shader normalization below, Portal also
reached a rendered main menu and loaded Test Chamber 00 on this Pixel 8 Pro
(Mali-G715, driver 54.3.0). This was a brief launch check, not sustained gameplay.

DXVK also supplies shaders inline through a stage's `VkShaderModuleCreateInfo`
chain. The compatibility layer materializes supported inline shaders before
feature conversion, so clip-distance emulation also covers this path. Without
that normalization, Portal reaches device creation but sends unsupported clip
outputs to the native G715 compiler. Temporary modules remain alive through
pipeline creation and are released afterward; unsupported inline-module chains
are rejected without being rewritten. Clip conversion preserves execution-mode ordering and
reuses direct clip-distance output arrays, avoiding undeclared output locations
when conservative cleanup leaves extension-bearing modules intact. All 84
captured Portal shaders passed SPIR-V validation after conversion with scalar
block layout enabled.

Clip-distance emulation is limited to complete vertex/fragment pipelines. For
graphics-library flags, geometry or tessellation stages, and unsupported stage
extension chains, the layer inspects pointer uses in the selected SPIR-V entry
point after entry extraction and rejects the pipeline only when it accesses
`BuiltIn ClipDistance`; the equivalent layout without that access passes through
unchanged. Inactive entry points, unused interface declarations, and unused
`ClipDistance` capabilities do not trigger the guard. Graphics-pipeline-library
and shader-object extensions remain
unavailable while clip emulation is active because those routes can bypass the
complete-pipeline rewrite; unrelated native geometry and tessellation features
remain exposed.

Device validation on Pixel 11 Pro XL covered coherent and noncoherent shared
mappings, reserve/remap lifetime, GPU buffer copies, normal and mutable image
clears, and Portal reaching a rendered menu. Portal was also confirmed working
by the user. Other Mali and PowerVR drivers, and sustained gameplay/performance need
separate device validation. The original launch crash additionally required
Steam to verify Proton Experimental: its installed Windows and Unix Vulkan
modules used incompatible dispatch tables. This layer does not repair corrupt
or mixed Proton installations.

### PowerVR Steam UI containment

The PowerVR profile selects software CEF rendering for Steam. A Pixel 11 Pro XL
capture of rapid **All Games** scrolling identified `steamwebhelper` as the
PowerVR `GEOM GUILTY_LOCKUP` client before a fatal GNOC reset. The precise failing
graphics operation and the reset's initiating transaction remain unresolved.
This policy removes Steam's forced GPU/ANGLE flags and selects `-cef-disable-gpu`.
The launch log records `CEF software rendering selected (-cef-disable-gpu)`.

The parent presentation environment is retained for native games, and Proton
launches keep their managed WSI/DXVK path. No global Mesa software-rendering
variable is set; Gamescope and the Android compositor still use the GPU. Mali
and Xclipse keep their current Steam UI behavior. Software CEF may reduce UI
performance; build/test success is not proof that the reboot is fixed. Device
validation must repeat **All Games -> hold Down -> rapid scrolling**, including
newly encountered artwork rather than only a small, already loaded page.

```text
Android compositor
  +-- Gamescope: SDL/Wayland, session layers
        +-- session client: X11 presentation selected by profile
        +-- MangoApp: independently applies the same X11 profile
```

## Runtime compatibility ownership

The app selects the shared `VK_LAYER_HYBRIS_compat` layer for all three profiles;
it no longer stages or enables a standalone Xclipse layer.
`tools/powervr/release.json` records the verified archive and tree checksums, recipe
inputs, producer commit, and upstream source revision.

The pinned libhybris source already supplies the Android linker, Bionic TLS,
hardware-buffer/gralloc bridge, Vulkan forwarding, and Wayland WSI. DroidDeck's
downstream patch adds independently selected workarounds inside that foundation.
This is an in-tree extension contract, not an external plugin ABI.

```text
App GPU profile
  +-- HAL / DRM / presentation / layer / DXVK choices
  `-- Common runtime and launch helpers
        +-- libhybris ICD: Android loader and HAL preparation
        +-- shared compatibility layer: physical/device policy
        `-- WSI and DXVK: presentation and Direct3D requirements
```

| Behavior | Owner and selection | Native behavior preserved |
| --- | --- | --- |
| Bionic environment preparation | Common/ICD path, before loading any explicitly selected HAL | Preserve an existing environment; initialize once with a process-lifetime copy and retry allocation failures |
| Xclipse per-format BC handling | Shared layer; Samsung HAL, Xclipse device name, missing native BC, and active decoder policy | Keep the complete `textureCompressionBC` feature false when the decoder cannot fulfill its full contract |
| Immutable YCbCr sampler handling | Shared layer; Samsung HAL, Xclipse device name, and native sampler-YCbCr capability | Restrict handling to applicable combined-image-sampler bindings; maintain per-device sampler lifetime |
| Calibrated timestamp aliases | Shared layer; both KHR and EXT advertised, with a missing KHR command and an available EXT command | Prefer each native KHR command independently; device fallback also requires enabled KHR and EXT extensions |
| Feature request rewriting | Shared layer; only an implemented, selected emulation | Preserve the caller's input and unknown suffixes; reject an unknown prefix that cannot safely be copied |

Timestamp fallback enables the advertised EXT dependency through an owned copy of
the device extension list. Its state is attached to the created device and removed
on destruction. HAL selection alone does not enable an unrelated physical-device
workaround. Xclipse-specific selectors retain the tested family boundary because
a narrower supported driver-version range has not been established.

PowerVR and Mali retain their existing BC policy. Xclipse disables BC emulation
in the outer/session path and enables its DXVK profile only for managed games.
Its 128-byte push-constant limit still selects DXVK 2.6.2. The patched 2.6.2 payload
checks the 14 BC formats required by Direct3D, including sampled-image, linear
filtering and transfer support, before accepting D3D8/9/10/11 device creation.
It requests the complete Vulkan BC feature only when the driver reports it.
The layer's partial decoder therefore does not need to claim full Vulkan BC support.

BCn decoding and several SPIR-V transformations already exist in the pinned layer.
Descriptor and dynamic-state compatibility is partial: filtering an unsupported
path is not equivalent to implementing its emulation. Mali's wireframe,
cull-distance, multiple-viewport and exact-driver guards remain in force. Component
tests establish the dispatch and packaging contracts; full APK CI and installed
device regressions remain separate validation gates.

The component build is available through the existing APK workflow. Replace
`<reviewed-branch-or-tag>` with the reviewed branch or tag containing the component
recipe and workflow:

```sh
gh workflow run build.yml --repo Droid-Deck/DroidDeck \
  --ref '<reviewed-branch-or-tag>' -f component=hybris
```

This selects an artifact-only libhybris job. It produces `runtime.tzst`, its
checksum, and `hybris-component.json`, a candidate release pin with the producer
commit, build URL, archive/tree hashes, and exact recipe inputs. It does not publish
the archive or modify `release.json`. The archive also records the build recipe
and ships the original compiler manifest and patched source, including new files.

For subsequent native changes, review and build the component, publish that exact
archive, verify its download, and update the `hybris` entry in
`tools/powervr/release.json`. APK assembly rejects a recipe or patch stack that no
longer matches its pinned component. Preserve the separation between the Xclipse
outer/session BC policy and the managed-game override when changing launch code.

The paired Xclipse producer uses the same workflow with `-f component=dxvk-xclipse`.
It emits `dxvk-2.6.2.tar.gz`, its checksum, and `dxvk-xclipse-component.json`.
Component validation covers the archive, PE imports/exports, source and provenance.
The candidate pin inventories all ten i386/x86-64 DLLs and records source, compiler,
container, recipe and resolved build-tool provenance. Publish and verify that exact
archive before replacing its entry in `release.json`; retain the other DXVK
payload pins. A layer change that removes a previously advertised emulated feature
must ship together with any consumer change needed to use its actual capabilities.

The companion Gamescope component includes the downstream GPU presentation
patches, upstream's per-window limiter fix through
`4163b31b`, and nested cursor routing. The cursor, presentation, DMA-BUF, and SHM
regressions passed in the combined source build. Failed fence duplication or waiter
registration drops the affected commit while retaining frame-callback progress;
the regression compiles the actual production queueing block. Its public archive
hash, AArch64 executable, declared dependencies and
symbol requirements were checked before updating `tools/gamescope/release.env`.
That older component path validates the archive hash, but does not enforce a
recipe-input gate. Rebuild and verify its archive whenever its patch set changes;
the attached source-input record documents this build without claiming a hermetic
Arch compiler environment.

## Comparing native, bare, and compatibility capabilities

`tools/vulkan/capability_report.py` compares saved captures without accessing a
device or changing its configuration. It accepts Android `vkjson` and Vulkan
Profiles JSON from `vulkaninfo --json`. Its checked-in, typed Vulkan Registry data
normalizes format IDs, flag masks, enum names and promoted feature/property
structures. The comparison runs offline and has no third-party Python dependencies.

Collect all three views on the same device and driver before interpreting a delta:

1. Save the native Android view with `adb shell cmd gpu vkjson > native.json`.
   Record the Android build fingerprint and the collector/version used.
2. In the selected DroidDeck Linux guest, use the commands below to list GPUs and
   capture its libhybris ICD with all layers disabled. Select the matching GPU
   number explicitly; save the generated Profiles file as `bare.json`.
3. Capture the same ICD in a fresh process with the managed-game compatibility/WSI
   layer list and `HYBRIS_BC_TEXTURES=dxvk`, saving `compat.json`.
   A session/compositor capture is a different policy and needs its own report.
4. Retain each command, environment, exit status, stderr and loader trace alongside
   the JSON. Record the APK commit, runtime archive/hash, HAL path, GPU identity,
   driver version, layers and DXVK component. Confirm the loader trace matches the
   intended mode; filenames alone do not establish which stack ran.
5. Copy the guest JSON files to the checkout alongside `native.json`, then compare
   them with the report command below.

Run this inside the guest after the selected profile's environment has been
applied. Preserve its `HYBRIS_*` loader and HAL settings. These commands require a
loader supporting [layer filtering](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderLayerInterface.md#layer-filtering);
the trace must confirm that the requested layer isolation took effect.

```sh
set -eu
probe_runtime=${DROIDDECK_SYSTEM_VULKAN_RUNTIME:?selected system runtime required}
export VK_DRIVER_FILES="$probe_runtime/icd.json" VK_ICD_FILENAMES="$probe_runtime/icd.json"
export VK_LAYER_PATH="$probe_runtime/wsi:$probe_runtime/lib"
export VK_LOADER_DEBUG=layer VK_LOADER_LAYERS_DISABLE='*'
unset VK_LOADER_LAYERS_ENABLE VK_LOADER_LAYERS_ALLOW VK_INSTANCE_LAYERS
vulkaninfo --summary
```

Set `probe_gpu` to the matching GPU number printed above. Use a fresh capture
directory so an older auto-named file cannot be mistaken for the new output:

```sh
probe_gpu=0 # Replace with the matching GPU number from --summary.
probe_capture=$(mktemp -d "${TMPDIR:-.}/vulkan-capture.XXXXXX")
mkdir "$probe_capture/bare" "$probe_capture/compat"
(
  cd "$probe_capture/bare"
  HYBRIS_BC_TEXTURES=0 vulkaninfo --json="$probe_gpu" >stdout.txt 2>loader.txt
  set -- VP_VULKANINFO_*.json
  test "$#" -eq 1
  test -f "$1"
  mv "$1" ../bare.json
)
(
  cd "$probe_capture/compat"
  unset DISABLE_WSI_LAYER
  export VK_INSTANCE_LAYERS=VK_LAYER_window_system_integration:VK_LAYER_HYBRIS_compat
  export VK_LOADER_LAYERS_ENABLE='VK_LAYER_window_system_integration,VK_LAYER_HYBRIS_compat'
  export HYBRIS_BC_TEXTURES=dxvk
  if [ "${DROIDDECK_SYSTEM_VULKAN_PRESENTATION:-native}" = x11-shm ]; then
    export WSI_X11_FORCE_SHM=1
  fi
  vulkaninfo --json="$probe_gpu" >stdout.txt 2>loader.txt
  set -- VP_VULKANINFO_*.json
  test "$#" -eq 1
  test -f "$1"
  mv "$1" ../compat.json
)
if grep -Eq 'Insert (instance|device) layer' "$probe_capture/bare/loader.txt"; then
  printf '%s\n' 'bare capture inserted a layer' >&2
  exit 1
else
  probe_status=$?
  test "$probe_status" -eq 1 || exit "$probe_status"
fi
for probe_layer in VK_LAYER_window_system_integration VK_LAYER_HYBRIS_compat; do
  if grep -Eq "Insert (instance|device) layer.*$probe_layer" "$probe_capture/compat/loader.txt"; then
    :
  else
    probe_status=$?
    test "$probe_status" -eq 1 || exit "$probe_status"
    printf 'compat capture did not insert %s\n' "$probe_layer" >&2
    exit 1
  fi
done
printf 'Captures: %s\n' "$probe_capture"
```

The tested runtime filtered out the requested layers when only
`VK_INSTANCE_LAYERS` was set. The compat subprocess therefore explicitly permits
both names with `VK_LOADER_LAYERS_ENABLE`, while retaining the requested
colon-separated list in `VK_INSTANCE_LAYERS`. The trace checks
fail closed: bare must insert no instance or device layer, while compat must insert
both named layers. [Vulkaninfo JSON output](https://github.com/KhronosGroup/Vulkan-Tools/blob/main/vulkaninfo/vulkaninfo.md)
otherwise defaults to GPU 0 and an auto-generated filename; the explicit selection
and rename above avoid both ambiguities. Run the following from the checkout after
copying out the captures:

```sh
python tools/vulkan/capability_report.py \
  --native native.json --bare bare.json --compat compat.json \
  --output capability-report.json
```

The report contains native-to-bare, bare-to-compatibility, and native-to-compatibility
comparisons. API version is a capability; vendor/device/name/driver values establish
device identity. A multi-device capture is rejected rather than silently selecting
one. Choose and save one device explicitly before comparison.

| Result | Interpretation |
| --- | --- |
| `differences_ok` | Device identities match and every observed change matches an exact policy entry; unused policy entries fail |
| `coverage_complete` | Both producers cover the compared fields, with no unsupported schema or conflicting source values |
| `ok` | Both checks above pass |
| Exit `0` / `1` / `2` | Complete expected comparison / differences or incomplete evidence / invalid input |

Missing collector fields are reported as unknown coverage, not invented capability
gains or losses. Conflicting duplicate core/extension values retain their source
paths and values and fail coverage closed; they do not establish a driver defect.
Queue, memory and collector metadata are retained as capture information rather
than presented as compared capabilities. Unknown schema also prevents a complete
result. The native Android and guest collectors need not query identical structures,
so an incomplete result can still contain useful, unambiguous comparisons.

Use `--policy expected.json` only after investigating a difference. Each entry in
its `expected_differences` array requires `mode`, exact capability `path`,
`transition` (`lost`, `gained` or `changed`), exact `before` and `after` values,
`owner` (`native`, `compat` or `unavailable`), and a nonempty evidence-based `reason`.
Absent values use JSON `null`. Wildcards, duplicate entries, stale entries and
unbounded value changes are rejected. Direction alone does not establish ownership;
an unclassified difference remains `unavailable`. Policies cannot override missing
coverage or inconsistent input. Do not add exceptions merely to make a report pass.

The generator `tools/vulkan/generate_registry_data.py` verifies the pinned `vk.xml`
checksum before regenerating `registry_data.json`; the source URL, revision and
license accompany the data. Focused normalization and policy tests run with:

```sh
python -m unittest discover -s tools/tests -p test_vulkan_capability_report.py
```

The tests include representative fixtures and [sanitized full captures from a
Pixel 8 Pro](../../tools/tests/fixtures/vulkan-capabilities/README.md). The corpus
preserves unsupported-format placeholders, promoted aliases and conflicting
collector values, with source hashes and redactions documented. These historical
captures predate this new component. Equivalent fresh captures and rendering
regressions on PowerVR, Xclipse, Mali and an unchanged Adreno path remain hardware
validation work; successful normalization or component CI is not that validation.

## Pixel 8 Pro / Tensor G3 evidence

The supplied 2026-10-11 Steam report selected `turnip-sdk36` and the rootfs
`freedreno_icd.json`; both the Android compositor and Gamescope failed to find a
physical device. The device has `ro.hardware.vulkan=mali` and `vulkan.mali.so`.

A direct system-loader probe from Termux (UID 10514, `untrusted_app_27`) identified
Mali-G715, Vulkan 1.4.343, 256-byte push constants, and maintenance6. It exposed
DMA-BUF, DRM format modifier, external semaphore FD, Android hardware buffer,
foreign queue and robustness2 extensions, but no `VK_EXT_physical_device_drm`.

That probe also found `fillModeNonSolid`, `multiViewport`, `shaderClipDistance`,
`shaderCullDistance`, `vertexPipelineStoresAndAtomics`, `textureCompressionBC`
and `robustBufferAccess2` disabled. Several are mandatory in the pinned DXVK
build; the PowerVR BC patch does not implement the other missing features.
Consequently, Mali must not be locked to the PowerVR DXVK payload. Compatible
DXVK builds/layers or game-specific choices still need separate validation.

Historical installed-APK evidence goes beyond the Termux probe. On the Pixel 8
Pro, the installed 0.3.2 APK's native ARM64 Steam client
reached `READY` using shared-memory presentation, and Old School Rally downloaded
successfully.

The stock `DXVK 3.1.1-27-g25ca` payload still cannot create an adapter because
Mali lacks required `fillModeNonSolid` support, alongside the other capability
gates above. The tested Old School Rally build produced white or black output
with the WineD3D fallbacks and rejected `-force-vulkan` because Vulkan support was
not built into the game. Managed DXVK and WineD3D choices remain user-controlled.

The experimental Mali component used for this historical test passed component CI.
Artifact checksums and provenance passed review, along with all 30 PE headers
and the 10 ARM64X hybrids. All 15 baseline DLLs are byte-identical to the tested
`b3e3322b` component. Wine truncates Mali ID `0xb8a20000` to `0`, so the guard
accepts `0` only for the exact `Mali-G715` name, vendor `0x13b5`, driver
`0x0d803000`, and `DXVK_MALI_COMPAT=1`.

On a Pixel 8 Pro with Mali-G715 54.3.0, the installed `b35888b` 0.3.2 APK was
manually staged with the `15dc` component. A stock control with four exact DLL
hashes and fresh logs still rejected `fillModeNonSolid`. The experimental run
had four prefix DLL hashes matching CI. Its logs confirmed the enabled profile,
a D3D11 feature-level 11_0 device, a 1602x720 swapchain, and the same unsupported
Vulkan feature bits at `0`. It reached the animated intro, interactive menus,
and the Time Trial Arizona SS1 Taurus RS race scene, including brief acceleration.
This is a bounded test;
it does not establish a full race, long-run stability, broad compatibility,
robustness2 conformance, or coverage of every rejection guard.

The APK bundles the verified `15dc` experimental component through the
checksum-pinned PowerVR assembler. **GPU drivers > Auto** selects the matching
system Vulkan driver and bundled DXVK for PowerVR, Xclipse and Mali. Mali selects
`dxvk-mali` with `DXVK_MALI_COMPAT=1`, managed game DXVK and its existing X11 SHM
presentation automatically; there is no separate Mali opt-in switch or preference.
The existing Manual driver controls remain available. The strict DXVK driver
identity and unsupported-feature checks remain active.
The published component contains the same fifteen i386, x86_64 and ARM64X DLLs
verified in the device test above, plus source, license, patches and producer
provenance. Assembly and launch both reject plain ARM64 DLLs in this payload.

On 2026-10-14 UTC, installed `20c93b76` was tested on the Pixel 8 Pro with
Mali-G715 54.3.0 using **GPU drivers > Auto**. Steam reached `READY` at 1602x720,
and Old School Rally reached its intro, responsive menus, and the Time Trial
Arizona SS1 race scene with the Taurus RS, including brief acceleration. This was
a bounded smoke test, not a completed race or performance benchmark. All 18 installed
libraries and Mali DLL hashes matched the APK. Native, bare, and corrected compat
captures were each semantically identical to their respective historical captures;
the 13 native-collector conflicts persist, so the capability report remains
incomplete and fails closed rather than establishing conformance. The compatibility
`vulkaninfo` Wayland present-mode query still returned `ERROR_UNKNOWN` despite a
successful capability export; this test does not establish present-mode conformance.


The earlier opt-in `29a3a4e8` 0.4.0 APK was installed as an update on the same
Pixel 8 Pro. The visible switch started Off and was enabled through the GPU
page; Steam reached READY and Old School Rally launched normally in two sessions,
without manual component staging or per-game overrides. All fifteen installed
runtime DLLs and the four game-prefix DLLs matched the APK/component hashes.
Fresh logs identified the enabled Mali profile and D3D11 feature level 11_0,
with the actual game mapping the bundled compatibility layer and Mali HAL.
Intro, interactive menus, race rendering and user-controlled driving worked.
A 19.97-second driving capture sampled HUD readings of 61 and 55 FPS; the user
reported 60 FPS. This is a bounded observation, not a performance benchmark or
long-run stability result. Both environment-file hashes, the COMPATIBILITY FEX
preset, global DXVK logging and device stay-awake settings remained unchanged.
The earlier switch was left enabled at the user's request; the current Mali Auto
profile always selects that working path and no longer consults the retired
preference.

## Related implementation discussion

All 21 comments available on [DroidDeck issue #165](https://github.com/Droid-Deck/DroidDeck/issues/165)
were reviewed while preparing this extension. Particularly relevant:

- [Mali libhybris rendering report](https://github.com/Droid-Deck/DroidDeck/issues/165#issuecomment-6014766410).
- [Android handle transport, SDL and PowerVR DRM rationale](https://github.com/Droid-Deck/DroidDeck/issues/165#issuecomment-6102946636).
- [Mali shader, feature and DXVK compatibility gaps](https://github.com/Droid-Deck/DroidDeck/issues/165#issuecomment-6108722387).
- [Mali device findings and follow-up](https://github.com/Droid-Deck/DroidDeck/issues/165#issuecomment-6123415216), which reinforce the need to validate exact DXVK and wrapper artifacts on device.
- [Xclipse port findings](https://github.com/Droid-Deck/DroidDeck/issues/165#issuecomment-6114421494), which informed capability checks but are not enabled as Mali workarounds.
