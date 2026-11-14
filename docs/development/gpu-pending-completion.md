# Compositor work whose completion is unknown

A timed-out fence wait does not cancel submitted GPU work. Continuing after
that result can reset a command buffer or fence still in use, or destroy an
image and its memory while the GPU may still read them. Previously the finite
wait paths returned without putting the compositor into a terminal state, and
swapchain teardown ignored the result of `vkDeviceWaitIdle`.

An unsuccessful completion check other than real `VK_ERROR_DEVICE_LOST` now
puts the compositor in a distinct completion-unknown state. It stops subsequent
presentation and retains potentially pending image/memory and swapchain
resources until the DroidDeck app process exits. Device loss continues through
the existing device-loss path. This is deliberate retention rather than an
automatic recovery; restarting a guest session cannot reset the in-process GPU
state, so the diagnostic asks for an app restart.

Initialization checks the black-image submit and fence result. An unsuccessful
empty completion-marker submit no longer causes a wait on an unsignalled fence.
Surface-rebuild and effect-map paths stop when teardown cannot establish idle.
Acquire retries also stop: retaining the old swapchain is not permission to
acquire from it again. A later frame-generation acquire that ends in a terminal
state returns before the completion marker, fence wait or any further recording.
Real device loss on present is handled before attempting that marker, too.
Mapped SHM bytes are retained unchanged, including same-size `wl_shm` and
single-pixel commits; keeping only the allocation would still race a GPU read.
Off-screen pass copy/abort and the internal fence wait reject terminal states
without further Vulkan calls. Release fds are still consumed on rejected copies.
The normal frame fence still has its existing infinite timeout; this change is
not a general GPU hang watchdog.

Externally owned DMA-BUF contents need the same protection. `vkp_completion_unknown()`
exposes the terminal state to compositor-thread lifecycle paths. Replacement,
surface/client destruction, cursor/idle-role releases, previously queued paced
releases and SurfaceFlinger callbacks no longer return a DMA-BUF to its client
after quarantine. The compositor retains DMA-BUF references/backing FDs and AHB
references until app process exit. A display fence alone does not establish that
the compositor's Vulkan reads completed. Queued releases detach destroyed surfaces
and remain held without repeatedly arming an expired timer. New terminal releases
require no allocation, so allocation failure cannot fall back to an unsafe release.
Copied SHM releases and healthy/genuine-device-loss cleanup keep their existing
behaviour. This intentionally prevents further client buffer reuse; restarting
the Android app process, not just the guest session, is required.

## Regression coverage

`python3 -m unittest discover -s tools/tests -p test_vk_pending_completion.py -v`
compiles the production wait/destructor and swapchain teardown functions with a
fake Vulkan dispatch table. The two cases cover success, timeout, host-memory
errors, genuine device loss, resource retention and suppressed repeated teardown.
No real GPU fault is injected. The harness matches main's Vulkan-image resources;
it does not assume the experimental PowerVR branch's additional AHB fields.

`python3 -m unittest discover -s tools/tests -p test_vk_terminal_callers.py -v`
additionally compiles the **entire production `vk_present.c`**, with fake Vulkan
dispatch callbacks and stub external modules. The compositor's `take_shm` and
`take_pixel` callers are inserted verbatim, not rewritten. It needs the Android
NDK's Vulkan-Headers (`ANDROID_HOME`/`ANDROID_SDK_ROOT`/`NDK`), a host Vulkan
development package, or `DROIDDECK_VULKAN_INCLUDE`; absent headers are explicitly
reported as a skip. No Android runtime or GPU is required.

The 12 scenarios include first/later OUT_OF_DATE and SURFACE_LOST followed by an
idle failure, a finite blit timeout followed by same-size SHM/single-pixel
uploads, normal presentation/frame generation, a healthy later acquire timeout
whose earlier work still needs draining, real acquire/present device loss, and
terminal off-screen pass entry. Every work-issuing fake callback asserts the
device is live. After quarantine, the tests verify retained handles and unchanged
mapped bytes, and probe the public render/blit/readback/import/pass entry points
for suppressed acquire/submit/wait/reset and destruction. Consumed fds are checked.
Against PR #560's `68dc1c45` the same harness compiles, but nine scenarios fail
(the three healthy controls pass); the corrected production unit passes all 12.

The baseline production fence/destructor compiles but fails the timeout-state
oracle; the corrected functions pass both cases with `-Wall -Wextra -Werror`.
The change was also exercised in an independently integrated Pixel PowerVR Lab,
but its short game observations do not establish the cause of the earlier GNOC
reboot or sustained device stability. Main's native CI and APK build remain
separate validation.

`python3 -m unittest discover -s tools/tests -p test_vk_dmabuf_quarantine.py -v`
compiles the production compositor/AHB release, reference and listener functions
verbatim, using the real Wayland list layouts/macros with fake IPC/timer/AHB
endpoints. It covers paced replacement, immediate surface cleanup, already queued
releases, a mixed DMA-BUF/SHM queue, client destruction, a display-fence callback,
healthy cleanup and genuine device loss. It checks withheld releases, retained
FDs/references/AHB ownership, removal of surface listeners and disarmed timers.
The same harness can test an old source directory via
`DROIDDECK_DMABUF_TEST_SOURCE`; it does not inject a real driver fault or prove
long-term device stability.
