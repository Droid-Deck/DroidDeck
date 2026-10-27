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
- `0116-hold-overlay-repaint-for-next-frame.patch` - this app: on a fixed-refresh output every
  mangoapp commit asked for a repaint of its own, and mangoapp draws once per game frame just after
  that frame was shown, so a 60 fps game on a 120 Hz panel was composited twice per frame - 75 frames
  a second reached the screen, one in two 8 ms after the last with no new picture (Valheim, Stardew
  Valley, Among Us). The VRR path already leaves such repaints to the next game frame; on a fixed
  refresh output they now wait for it too, for at most `external_overlay_hold_cycles` refreshes (4,
  so 30 fps at 120 Hz still merges), and are painted on their own after that.

Sixteen more of Armada's patches are DRM/lease/HDR-on-KMS work for a native display, which this
app's Wayland-hosted gamescope never reaches, or need a newer gamescope than the runtime has.

- `0116-release-cursor-shm-buffers.patch` - this app: pointer cursor surfaces have no
  X11/xdg window association, so routing their commits through `g_PendingCommits`
  retained every old SHM buffer until the surface was destroyed. Ignore cursor-role
  commits before taking the buffer lock; X11 cursor images still follow the existing
  MouseCursor path. Window commits awaiting association are unchanged.

The live regression probe is `tools/tests/test_gamescope_cursor_shm.py`. Run it
inside an active Gamescope session with access to that process's `/proc` fd
directory. It submits 20 distinct SHM cursor buffers and requires at most the
current buffer to remain open, then none after surface destruction. The
`--unassigned-control` variant omits the cursor role and verifies that all 20
commits awaiting a window association remain queued until surface destruction.

Device validation (2026-10-20): the stock component retained 20/20 cursor buffers;
the patched component retained 1/20, then 0 after destruction. The unassigned
control retained 20/20 and then 0. In Backpack Battles, two batches of ten touch
drags leaked 188 and 197 SHM descriptors before the fix; the same batches added
zero after the fix (112 total descriptors before and after each batch).

## 0117 WSI: cache the present-time Xwayland bypass check

`QueuePresentKHR` in the WSI layer calls `canBypassXWayland` for every swapchain on
every present. Each call makes several synchronous X requests (window geometry,
`_WINE_ALLOW_FLIP`, parent window, a child-window scan, the toplevel window). At
high frame rates that is thousands of round trips a second: on an AYANEO Pocket FIT
running AIO Graphics Test uncapped, Xwayland used 24-32% of a core and the game's
present thread sat about half idle.

The patch can keep the result for `DROIDDECK_WSI_BYPASS_CHECK_MS` milliseconds. Unset
or 0 keeps the per-present check; the app sets 100. With 100, a window resize or a flip/obscure change is seen up to 100 ms
later than before; surface creation and capability queries still check every time.

## 0118 Wayland: preserve signed plane positions

Fill scaling and Steam magnification can place the image at negative x/y coordinates.
The Wayland backend converted these signed positions through an unsigned size helper:
at host scale 120, -120 became 35791275 and the image moved off-screen while overlays
could remain visible. A separate signed conversion uses a 64-bit intermediate and
retains the existing ceiling convention; viewport sizes keep their original helper.

The component build runs `wayland_scaling` for negative crop offsets, positive/zero
positions, fractional host scales and wide intermediates. This patch may be worth
upstreaming: the same unsigned position calls were present in ValveSoftware/gamescope
3.16.29 and master `d175688ad4ec490dc7b0695c5c62f8807684a1f1` when checked on
2026-10-26. Carry it here until an upstream release containing the fix is adopted.

Device validation on AYN Thor (1280x720 session, 1920x1080 Android output):
800x600 and 1600x600 source windows retained their image through Auto, Fit,
Stretch, Fill, Integer and 2x Steam magnification, including returning to Fit.
The old component lost the image in Fill and magnification. The tested executable
matches the pinned p14 archive and the locally assembled APK. Hardware checks used
native Gamescope properties; the physical Back+LB shortcut was not independently
confirmed. The original APK and runtime were restored after validation.

## 0119 WSI: default image count while the frame limiter runs

The app sets `GAMESCOPE_WSI_MIN_IMAGE_COUNT=8` so an uncapped game keeps drawing while
gamescope turns its buffers around. With Steam's Frame Limit on, gamescope forces FIFO,
and the layer always hands the driver MAILBOX, so a game with eight images could queue
up to seven frames inside gamescope before it blocked: each one adds a frame of input
latency at the limited rate.

While the limiter forces FIFO, the layer now reports and enforces its default minimum
of 3 images instead of the raised count; with the limiter off it uses the raised count
as before. DXVK and VKD3D-Proton already recreate their swapchain when the limiter
changes (the layer returns `VK_ERROR_OUT_OF_DATE_KHR`), so their image count follows
the Quick Access menu. Other apps keep their swapchain on a limiter change; they now
get `VK_SUBOPTIMAL_KHR` while their image count no longer matches, unless the layer
marks them as not taking it (`NoSuboptimal`).

## 0120 Wayland: nested refresh follows the host

`-r` fixes the refresh gamescope paces at, and the frame limiter and Steam's Frame Limit
slider use the same value. The app passes the panel refresh at session start, so a
panel switched to another mode during the session (for example in the device's own
quick settings) left gamescope pacing at the old rate until the next session; only the
reported output refresh changed.

With `GAMESCOPE_NESTED_REFRESH_FOLLOWS_HOST=1`, a refresh the host compositor reports
through presentation feedback replaces the `-r` value, so pacing and the limiter switch
with the panel. `-r` still sets the starting rate, which avoids gamescope assuming
60 Hz until the first frame is shown. When the refresh changes, gamescope also sends
its `gamescope_control` display information to Steam again; Steam otherwise reads it
only when it binds.

A host mode switch can report a passing rate for a few frames (72 Hz between 144 and
120 Hz on the AYANEO Pocket FIT), so with following on a new rate is taken once it has
been reported for 300 ms. Without the variable the reported rate is taken at once, as
upstream does.

The rate comes from the feedback of a frame gamescope presents, so with nothing moving
on screen no feedback arrived and the old rate stayed. With following on, gamescope
presents once when the host's `wl_output` mode changes (DroidDeck's compositor sends
the new mode) and keeps presenting while a new rate is pending (since p16).

## 0121 Xwayland: skip the X tree walk for a repeated window override

The WSI layer sends `override_window_content` on every present of an X11 swapchain.
gamescope first walked from that window to its top-level window with `XQueryTree`, one
blocking X round trip per level under the wlserver lock, and only then noticed that the
same swapchain already overrides that window. At thousands of frames per second that is
thousands of round trips to Xwayland every second.

The override now remembers the window it was asked for. When the same swapchain and
surface ask for the same window again within 100 ms, the request returns at once; after
100 ms the walk runs again, so a window that moved to another top level is still found.

## 0122 Wayland: wake steamcompmgr after reading host events

Backport of upstream commit 2962992 by Matthew Schwartz (ValveSoftware/gamescope).
The nested Wayland backend's input thread reads the host display, but steamcompmgr
does not watch that file descriptor, so buffer releases and presentation feedback
queued by that read could wait up to one refresh for steamcompmgr's next poll. The
input thread now wakes steamcompmgr after each read.
