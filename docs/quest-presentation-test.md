# Quest presentation candidate Test 2 — 2026-10-13

Base: `main` at `a394dfd31b2102e20dd99c6f51b4873bbdedab05`.
Reference APK: `DroidDeck-PR669-A6xx-Test2(1).apk`, SHA-256
`95ffb5ea6ae61d6a92c4ce9ad46f329d77a0f113ebbaabc6da30d6c8bd951154`.
Its embedded source revision is `d3f3439bbc45ca67de86d0093356a449c2c74600`.

## Evidence and scope

Reported symptoms: Desktop shows vertical corruption; Steam has green marks in
its black borders; starting Desktop first also corrupts subsequent Steam, and
closing Steam can expose corruption when returning to the menu. The supplied
modified PR669 APK reportedly avoids the transition problem.

Source inspection confirms that the compositor outlives sessions. The old
Quest workaround enables only `hdr.steam`, and the initial HDR-gated layer
selection happens only at compositor startup. Whole desktops and black borders
can still use the Android Vulkan swapchain even when a fullscreen game uses a
SurfaceControl layer. This is consistent with the reported symptoms, but is not
an on-device reproduction or proof of the exact driver defect.

The supplied APK has the same Steam-only preference initialization and the
same bundled Quest 3 Turnip binary as main (SHA-256
`af0d6bb38756eace17b43311c5c61a7ef3dc6e83d5cb8a63577d6a1d10c0aed0`).
It additionally supports a Quest 2 driver. Therefore the APK comparison does
not establish a new Quest 3 driver regression. Device, installed driver,
persistent preferences and session logs still matter.

## Test 1 startup regression and Test 2 correction

The Test 1 user confirmed a Quest 3 and a black window after runtime setup.
The supplied state report identifies `0.4.0-questdisplay-test1`, runtime `r9`
installed, an active guest, `installing=null`, no recorded failure, and saved
session artifacts. The session was suspended at capture. Suspension alone does
not establish the cause of the original black window. The retained main log
starts after the test window was closed, so the startup diagnostics are absent.

Source review found a Test 1 bug: `vkp_render` unconditionally signaled the first
frame after successfully showing the black backdrop, even with no client draws.
The Android listener then hid the loading UI and marked Steam's window as shown.
This also happened when the content-layer operation returned a successful no-op
(no visible geometry or no available pool slot). Consequently `firstFrame=true`
in Test 1 is not reliable evidence that Steam had displayed a frame.

Test 2 removes the backdrop notification. The existing content-layer presenter
owns the notification after submitting client content. A host regression test
compiles the production `vkp_render` entrypoint with controlled layer outcomes;
it failed on Test 1's empty scene and passes with this change. It also covers
successful no-ops, content presentation, errors and the existing non-Quest,
frame-generation and Cardboard routes. All seven focused host tests pass.

On 2026-10-13, the user tested Test 2 on Quest 3 and confirmed that it was
functioning as intended. This is a user-reported hardware result; a separate
pass/fail record for each checklist item below was not supplied. Broader headset,
game, colour and performance coverage remains outstanding. The user's separate
Quest 2 success report concerns the Adreno 6xx compatibility PR, not this build.

## Candidate change

- Recognize Quest 2/3/3S for presentation independently of HDR and session order.
  This does not install an A6xx driver or expand main's Quest 2 driver support.
- Enable the layer protocol before the first client connects.
- Compose complete SDR scenes into the existing GPU-rendered gralloc layer,
  including Desktop's multiple windows. Keep the existing path for a fullscreen
  AHB that cannot be imported into the compositor.
- Present the black backdrop as an opaque SurfaceControl layer, initialized
  once through a CPU-mapped 16-by-16 buffer. Game/desktop rendering stays on
  the GPU. The backdrop does not use a Turnip Android swapchain.
- Use actual native-window dimensions for layer geometry without creating a
  Vulkan swapchain solely for sizing.
- Retire the backdrop on detach/rebind and hide it before a real swapchain
  frame. Reject a black buffer whose CPU lock/unlock failed.

Other devices retain their normal presentation paths. Frame generation and
Cardboard still use the original swapchain path and are outside this test's
coverage; leave them off for the first test. HDR negotiation itself is unchanged.

## Validation

The full ARM64 native compositor, including the existing frame-generation
engines, is compiled from this checkout with Android NDK r27d, API 26 and 16 KB
ELF alignment. Host tests exercise the actual backdrop implementation with
mock Android APIs: initialized pixels and padded rows, immutable reuse,
resize, hide/show, retirement/recreation, absent windows, allocation/lock/
unlock/transaction failure recovery, and the Quest device gate. These checks
cannot validate Horizon's compositor, colours, latency or graphics output.

Run the focused host tests (LeakSanitizer may need `ASAN_OPTIONS=detect_leaks=0`
when the test runner itself operates under ptrace):

```sh
python3 -m unittest tools.tests.test_quest_presentation tools.tests.test_quest_kgsl -v
```

The test APK uses the verified official CI APK for this exact base commit,
with only `lib/arm64-v8a/libdroiddeckwayland.so` rebuilt and replaced, an added
`assets/quest-presentation-test.json` provenance record, and a new APK signature.
DEX, other native libraries and runtime assets stay unchanged. The manifest is
repackaged with the repository's variant helper as `com.droiddeck.questdisplaytest`
so it can install beside the normal app and the supplied APK, whose signing key
is different. Provider authorities and the signature permission are renamed too.
It uses separate app data and needs its own initial setup. It is not a complete
Gradle rebuild. The displayed version is `0.4.0-questdisplay-test2`. It is signed
using the repository's public AOSP test key. The launcher label remains DroidDeck;
check the version in app info to distinguish the test installation.

Test 2 retains Test 1's package, version code and signing certificate. Install it
over Test 1 without uninstalling or clearing data; its installed runtime and
session logs are retained. The original main application remains separate.

## Headset test

Fully close DroidDeck before starting. Keep the same driver and resolution
settings that produced the problem; turn off frame generation and Cardboard.

1. Cold launch Desktop. Check the desktop, windows and black borders.
2. Switch Desktop to Steam without restarting the app.
3. Close Steam and return to the DroidDeck menu.
4. Fully close the app, then launch Steam first; inspect the top/bottom borders.
5. Switch to Desktop and repeat both transitions several times.
6. Resize the Quest panel and suspend/resume the app once.

Record the headset model and whether each step is fixed. If it fails, export
that session's logs, including `wayland.log` and device information. Look for
`Quest presentation: display layers for Steam and desktop` to confirm this
renderer was loaded. Test 2's reported Quest 3 result is recorded above.
