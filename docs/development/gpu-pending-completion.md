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
The normal frame fence still has its existing infinite timeout; this change is
not a general GPU hang watchdog.

## Regression coverage

`python3 -m unittest discover -s tools/tests -p test_vk_pending_completion.py -v`
compiles the production wait/destructor and swapchain teardown functions with a
fake Vulkan dispatch table. The two cases cover success, timeout, host-memory
errors, genuine device loss, resource retention and suppressed repeated teardown.
No real GPU fault is injected. The harness matches main's Vulkan-image resources;
it does not assume the experimental PowerVR branch's additional AHB fields.

The baseline production fence/destructor compiles but fails the timeout-state
oracle; the corrected functions pass both cases with `-Wall -Wextra -Werror`.
The change was also exercised in an independently integrated Pixel PowerVR Lab,
but its short game observations do not establish the cause of the earlier GNOC
reboot or sustained device stability. Main's native CI and APK build remain
separate validation.
