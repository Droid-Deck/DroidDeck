# No Man's Sky asynchronous frame readiness

No Man's Sky (Steam app 275850) on a Retroid Pocket 6 can present whole-view
backward jumps, particularly in space. In the investigated KGSL driver path,
polling the dma-buf reports ready even while rendering is unfinished. A game-side
CPU wait made the picture stable but significantly reduced performance.

`0936-nms-asynchronous-frame-readiness.patch` moves that completion wait off the
game's presenting thread and gates the matching Gamescope commit on a real
completion signal. It is an opt-in candidate for the driver below, not a generic
change to Vulkan presentation or an automatic default for other games/devices.

## Mechanism and driver constraints

The Gamescope WSI layer injects an owned, reusable per-image
`VkSwapchainPresentFenceInfoEXT` for one hooked Wayland/bypassed swapchain.
Original application wait semaphores and other present extensions are retained.
The layer sends an initially unreadable eventfd to the server before the native
present. A worker waits for the Vulkan fence, signals the eventfd on success,
and releases its descriptor. Gamescope consumes that descriptor with the same
surface commit and uses its existing image waiter before sampling the image.
It takes priority over implicit dma-buf readiness and cannot be bypassed by
preemptive upscaling.

This depends on the tested Mesa implementation, not just generic present-fence
semantics. DD-Turnip v0.4.0 pins Mesa commit
`b1a8996cb336f8398b06583c399fae821523152d`. At that revision:

- `src/vulkan/wsi/wsi_common.c` signals present fences after application
  semaphore waits and any WSI blits.
- `src/vulkan/wsi/wsi_common_wayland.c` marshals the buffer attach and surface
  commit synchronously on the same Wayland display used by the Gamescope layer.

A per-swapchain mutex protects the readiness-request/native-present/clear
sequence. Fence reuse waits for the worker to finish host access. Queue storage
is bounded by image count; the worker never calls a `VkQueue` operation.
Pending and committed descriptors close on replacement, discard or teardown.
Unsupported paths, including an application-provided present-fence structure,
use a CPU completion fallback. Native failure never marks unfinished work ready.
An error after a partial commit can leave that commit waiting until surface
recreation. On GPU hang, bounded worker shutdown leaves a pending Vulkan fence
for device teardown rather than destroying a fence still in use.

## Activation and component integration

The patch is disabled by default. With rebuilt Gamescope **and** its matching
WSI layer installed, add `DROIDDECK_ASYNC_PRESENT=1` to the No Man's Sky game
environment. The layer additionally checks `SteamAppId=275850`. Restrict testing
to the known KGSL Turnip path until other driver implementations are verified.
No app-wide SessionService override or GPU-load diagnostic hook is required.

Adding a source patch does not change the downloaded component. Normal local
and component builds fetch the archive pinned in `tools/gamescope/release.env`.
Before a release can expose this fix:

1. Build the complete current patch stack using
   `.github/workflows/build-gamescope.yml` or `tools/gamescope/build-in-arch.sh`.
   The latter must run in its dedicated Arch build container with `/work` as
   its build checkout; it recreates its `pkg` and `out` directories.
2. Run the included `present_ready` test and the existing Gamescope checks.
   The build script now includes `present_ready` in its required tests.
3. Publish a **new** Gamescope component tag and record the resulting archive's
   actual SHA-256 in `tools/gamescope/release.env`. Existing published tags must
   not be overwritten. This draft intentionally retains the existing p22 pin.
4. Rebuild the runtime component/APK so the server and WSI layer both update.
   Confirm their runtime library and symbol requirements remain compatible.
5. Enable the game environment variable, stop the old session, and start a new
   session. Verify asynchronous per-image readiness and explicit acquire-fence
   counts in the log. A CPU fallback marker means the async path is not in use.

Maintainer component publication and a new device test of this current-main
port are still required before treating the draft as release-ready. Do not
claim that rebuilding an APK with the unchanged p22 pin includes this fix.

## Device evidence

The working test 9 APK was based on DroidDeck `430af5e2`, Gamescope 3.16.29
with the preceding 23 patches, and DD-Turnip v0.4.0 on both sides. Hardware was
Retroid Pocket 6 / Adreno 740, Android 13, approximately 11 GB reported RAM,
1280x720 output at 60 Hz. The tester reports that rollback is substantially
improved and the game is playable with better performance.

The successful test build also had `renderOnCommit=false`, compressed buffer
advertisement enabled, and imported-image ownership handoff changes. Current
main already has its own external-image synchronization implementation; those
earlier experiments are not duplicated by this patch. The contribution keeps
the successful readiness mechanism isolated for review. Latest-main behavior
with its default render scheduling has not been device-validated.

The supplied post-fix report gives:

| Observation | Test 9 |
| --- | ---: |
| Explicit acquire fences already ready | 143 |
| Explicit acquire fences waited for | 11,143 |
| Reported main-thread fence-reuse waits | 0 |
| CPU fallback markers / helper failures | 0 / 0 |
| Compositor pool drops | 0 |
| Current NMS swapchain images | 4 |
| Mixed-scene on-screen FPS median | 28.0 |

The preceding CPU-wait build's mixed-scene median was 21.9 FPS. This is **not**
a controlled speed comparison: scenes differ and graphics settings changed.
The first new video already shows approximately 30 FPS before those settings
changes. Later, the tester changes FSR 2 Quality to FXAA and render scaling from
100% to 70%, while keeping the in-game Max FPS at 30 and V-Sync off. The second
video shows approximately 30 FPS on foot/in space and 22-25 FPS in a station
cockpit. Its settings menu is not shown, so persisted values are not confirmed.
A sampled 48 seconds of flight footage has no severe older-frame recurrence
candidates; this is supporting evidence, not exhaustive proof of absence.

The worker's weighted wait average is about 20.9 ms across 11,010 reported
frames. That is background waiting, not a game-thread stall or isolated GPU
render cost. In a slower station interval, compositor GPU-fence waiting averages
about 30.1 ms; acquisition/release/presentation CPU work is small. This supports
further GPU-load investigation rather than another game-thread wait change.

## Memory pressure and validation limits

The prior app exit is recorded by Android as `LOW_MEMORY`, immediately before
the logged restart. The first video ends while applying display changes; its
session log is unavailable, so the triggering allocation cannot be identified.
The new session reaches 733 MB available RAM, about 2.96 GB swap used and
4.24 GB GPU allocation. Similar pressure existed in the CPU-wait build; this
does not establish a leak introduced by the asynchronous path.

Raw videos and session archives are retained locally. They are not part of
this contribution; the videos include personal notifications. Public evidence
here is limited to the relevant aggregate measurements and settings.

The device-tested APK SHA-256 is
`697891d06bf53fe6ed3d45ebb7936b286c68a96ddbe603391e9232204d358e2a`.
Its rebuilt server SHA-256 is
`6e2a18702ba10fa485a7bdb2a6b6db0642d039adc2d56acd897d1fb9c0119f14`;
its WSI layer SHA-256 is
`c9b6e4ee4e21797dd4f2c9113de0ccb7502a6a615d85f0c79e7197450c7f25fb`.
These identify the earlier tested build, not a device test of the current-main
source port.


## Current-main port validation

The source port is based on DroidDeck
`5b2f7a9a6d9fd5cc966719c11e9c20e9dc6f34fa` and Gamescope tag 3.16.29
(`8f212644c46460549035971ab914421180f61a7c`). It applies after the entire
current patch stack as 0936. The new patch applies with GNU patch fuzz zero
and reproduces all ten modified source/test files byte for byte. Existing
upstream patches 0924 and 0925 require their existing fuzz allowances; they
are unchanged by this contribution.

The current-main server and WSI layer compile as native AArch64 in the local
Ubuntu 26.04 build environment. All 12 Gamescope project tests pass, including
`present_ready`; the descriptor ownership regression test and build-script
shell syntax check pass. The worker test also passes with exceptions disabled
and warnings treated as errors. Native tests cannot replace the pending device
validation of this port. No latest-main APK is claimed tested on hardware.
