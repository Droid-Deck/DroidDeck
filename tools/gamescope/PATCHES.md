# gamescope patches carried by the app

Built by `.github/workflows/build-gamescope.yml` on top of the exact gamescope the Linux runtime
ships (3.16.29, Arch Linux ARM's package, same build options), and staged from the apk over
`/usr/local/bin/gamescope` at each session start - the hosted runtime image is never touched.
The binary's shared-library needs are checked against `runtime-sonames.txt`, the runtime's own
library list, before anything is published.

- `0002-steamcompmgr-fallback-appid-focus.patch` - Armada (armada-os/armada), verbatim.
- `0009-fix-arm64-steam-night-mode.patch` - Armada, verbatim: the ARM64 client packs the
  night-mode property differently; the slider did nothing.
- `0019-steamcompmgr-arm64-virtual-white.patch` - Armada, verbatim: the colour-temperature
  slider's (x, y) arrives as one 64-bit element from the ARM64 client; y is recovered from x.
- `0020-color-p3-red-is-wide-gamut.patch` - Armada, verbatim.
- `0100-realtime-queue-and-gamepad-cursor.patch` - this app, two of Armada's ported by hand onto
  3.16.29: realtime-priority Vulkan queues on request (`GAMESCOPE_FORCE_VULKAN_REALTIME=1`)
  without CAP_SYS_NICE, which proot can never have (a no-op on KGSL Turnip, which has a single
  submit-queue priority); and the gamepad-driven cursor sprite following the X pointer that XTest
  moves (it sat frozen). The X pointer is asked for only while a cursor image is drawn - every
  vblank while shown, every 50 ms while hidden for inactivity - since each ask is a blocking round
  trip to Xwayland on the paint thread; with no image, wlserver's position is used as upstream does.
- `0101-nested-wayland-cursor-routing.patch` - this app: `gamescope_cursor_composite=2` lets the
  nested Wayland session paint its cursor in the output using the real X pointer position; client
  cursor surfaces are cleared to prevent a duplicate at the host pointer, while Gamescope's original
  default host-cursor fallback is preserved when its no-keyboard-focus policy selects it. Empty
  client cursor images and Gamescope's hidden flag still suppress the painted cursor.
- `0110-wayland-backend-touch.patch` - this app: the nested Wayland backend bound only the host's
  pointer and keyboard, so a finger on the phone's screen never reached Steam. It now binds
  `wl_touch` too and hands each finger to wlserver's touch path (`wlserver_touchdown` / `motion` /
  `up`) - the one a Steam Deck's touchscreen drives - so what a touch does follows the client's
  touch mode (Steam's Big Picture sets Passthrough: a real touch, rows scroll under a finger).
  Finger ids are offset by one, since the nested pointer already moves wlserver's touch 0.
- `0111-wayland-pointer-warps-in-passthrough.patch` - this app: the nested pointer's motion goes to
  wlserver as touch 0, and in Passthrough (Big Picture's touch mode) a motion for a touch that is not
  down moves nothing - so in the app's touchpad mode the Steam client saw no hover and a click landed
  wherever the pointer had last been. The motion now always warps the real pointer as well
  (`bAlwaysWarpCursor`), which the other touch modes did already.
- `0112-restore-iconified-game-on-resume.patch` - this app: the Steam menu is an overlay that
  takes input without changing the focus window, and a fullscreen wine game minimizes itself when
  it loses input. gamescope only takes a window out of iconic when the focus window changes, and
  wine will not activate a window it believes iconic, so after Resume the game stayed minimized: a
  black screen with its small caption in the top-left corner (Titanfall 2, GE-Proton 11). The
  iconify request is remembered and the window goes back to NormalState before input returns to it,
  then focus is handed over again. `GAMESCOPE_RESTORE_FOCUS_WINDOW` on the root window asks for the
  same restore from outside (the session script's resume watcher).
- `0114-take-override-redirect-from-mapnotify.patch` - upstream (ValveSoftware/gamescope 3829340),
  verbatim; drop it once the runtime's gamescope includes it. Wine creates a game's window
  override-redirect and makes it managed before mapping it, which sends no X event. When gamescope
  read the window's attributes first, it kept the window as override-redirect and left it out of
  `GAMESCOPE_FOCUSABLE_WINDOWS`, so the client kept its loading screen over the running game
  (Skyrim SE, A Plague Tale: Innocence, on the games' own Xwayland). MapNotify now refreshes the flag.
- `0115-limiter-paces-per-window.patch` - this app: on a fixed-refresh output the frame limiter
  (Steam's Frame Limit) let a capped window's frames out only on every Nth refresh of a global grid,
  so a frame finished just after its slot waited a whole extra slot. At 30 on 60 Hz one picture was
  held for three or four refreshes - "small stops and ladders" with frame generation - where a game's
  own limiter slips such a frame by one refresh. The fixed-refresh branch now paces each window by
  time, as the VRR branch already did: its next frame goes out on the first refresh a capped interval
  after its last one, with half a refresh of tolerance so an on-time window keeps every Nth refresh.

The system GPU paths use the existing SDL/Wayland swapchain backend. Their additional patches are:

- `0120-nested-swapchain-capability-fallback.patch`: negotiate the surface's usages, mutable-format
  support, image count, alpha and extent; use offscreen composition and GPU copies when required.
- `0121-powervr-single-pixel-rcas.patch`: use the existing single-pixel RCAS shader on PowerVR,
  where the quad variant fails compilation.
- `0122-powervr-linear-dmabuf-import.patch`: retain the explicit linear layout when PowerVR
  rejects the mutable-format query, and intersect imported-image and FD memory-type masks.
- `0123-powervr-rgba-copy-surface.patch`: require an advertised RGBA SDR copy surface matching
  the shader's `rgba8` storage view. The tested workaround leaves other vendors, HDR and direct
  composition unchanged; it does not restrict game-facing BGRA formats.
- `0124-sdl-preserve-explicit-refresh.patch` and `0125-sdl-wait-before-present.patch`: preserve
  configured refresh across SDL events and finish composition before presenting without a
  render-finished semaphore. The build runs the focused capability, format and refresh tests.
- `0126-shm-commit-ready.patch`: mark an SHM texture ready after its synchronous upload instead
  of exporting image memory and treating the returned DMA-BUF fd as a sync fence. Explicit
  acquire events and real DMA-BUF commits retain their existing wait paths. The build compiles
  and runs the extracted production dispatch against SHM, explicit-acquire and DMA-BUF mocks.
- `0127-refresh-shm-buffer-contents.patch`: upload a fresh texture for every SHM commit. The
  texture cache remains enabled for DMA-BUF imports, which reference the producer's allocation;
  SHM imports are snapshots and otherwise replay old pixels when a client reuses its buffers.
  The build tests the production import function with reused buffers and retained older commits.
- `0128-keep-swapchain-shm-textures-internal.patch`: keep SHM uploads internal on Vulkan-swapchain
  backends, which sample them locally rather than consuming exported input buffers. This avoids
  unsupported DMA-BUF allocations, optimal-image layout queries and foreign ownership transfers
  on the SDL path. Backends that forward or scan out input buffers retain their export behavior.
  The build tests the actual SHM texture flags for both backend types.
- `0129-keep-swapchain-cursors-internal.patch`: keep Xwayland SHM cursor textures internal on
  Vulkan-swapchain backends. The trace proves generic `CVulkanTexture` DMA-BUF-attribute cleanup
  closed fd 0 after Wayland assigned it to Xwayland SHM; it does not identify an individual cursor
  allocation or driver return as that source. SDL samples its cursor texture locally, so this removes
  the remaining cursor external-FD ownership path. Cursor textures remain exportable for
  hardware-plane and forwarding backends, and the existing linear requirement for a hardware cursor
  plane is unchanged. The build tests the extracted production flags; device validation is pending.

Sixteen more of Armada's patches are DRM/lease/HDR-on-KMS work for a native display, which this
app's Wayland-hosted gamescope never reaches, or need a newer gamescope than the runtime has.
