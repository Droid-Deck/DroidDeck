# Steam session display sizing: research and device tests

Started 2026-10-06. This is a working log. Findings are added as they are measured on a device, so
it reads in order.

## Question

Should the Steam session run gamescope at the panel's own size and let gamescope scale games, the
way SteamOS does, instead of one session resolution shared by the Steam client and every game
(1280x720 16:9 by default) that our compositor stretches onto the panel? What does that mean on
very high-resolution panels and on foldables?

## How the session is sized today (main @ c636eea3)

- `droiddeck-session` starts gamescope with `-W/-H` (output) = `-w/-h` (nested, what games and the
  client see) = `BL_WIDTH x BL_HEIGHT`, the per-mode "Resolution" setting. The default is
  `1280x720` (`SessionDisplay.DEFAULT_RESOLUTION`). One Xwayland server holds both the Steam
  client and the games.
- The Android compositor (`waylandcomp`) draws gamescope's output into a panel-sized swapchain
  (2414x1080 on a 20:9 phone) and fits it with bars where the shapes differ.
- "Force game windows fullscreen" (`--force-windows-fullscreen`, off by default since #276) makes
  every window the nested size. A game whose own resolution differs fights it, and the resize
  storm that follows piles up GPU buffers until the low-memory killer ends the session (seen
  2026-10-06 on the AYN Thor with Alan Wake's American Nightmare).

Why the current choices were made, from the history:

| Choice | Reason given | Source |
|---|---|---|
| 720p default | The Steam client's CEF is the heaviest thing in a session, and 720p keeps Big Picture responsive on a regular flagship | `SessionPrefs.resolutionCap` comment |
| Never narrower than 16:9 | Games handed a near-square display (Fold inner panel) crop their own menus | `d2ea9b89` |
| 16:9 stretch option | Games on a narrow panel letterbox themselves; stretch fills it | #63, #133 |
| Custom resolution | Parity with Bannerlator, 4:3 and 16:10 presets | `ad2f9f8a` |
| Force fullscreen | A game that shrinks its window on focus loss (FlatOut) comes back small in a corner | #276, `droiddeck-session` |

## How SteamOS-style sessions do it

- gamescope output at the panel's size, `--xwayland-count 2`, and `STEAM_MULTIPLE_XWAYLANDS=1`
  (ChimeraOS `gamescope-session-plus` defaults to two Xwayland servers). The client lives on
  one server and games on the other.
- Per-game "Game Resolution" (Deck mode game properties): Steam writes
  `GAMESCOPE_XWAYLAND_MODE_CONTROL = {server, width, height, allow_super_res}` on the root window,
  and gamescope resizes that Xwayland server (`steamcompmgr.cpp`, the `gamescopeXWaylandModeControl`
  handler). gamescope then scales the game to the output with the scaler and filter chosen in the QAM.
- `GAMESCOPE_STEAM_MAX_HEIGHT` on the root window caps only the Steam client's window height, and
  gamescope scales it up (`steamcompmgr.cpp`, the `g_nSteamMaxHeight` branch). So a native output
  need not make the client render natively.
- Our gamescope (3.16.29-p5) contains both atoms (checked in the installed binary on the Thor). We
  use neither, and we don't pass `--xwayland-count`.

## Android side

- Hardware scaler: a surface whose buffer is smaller than the view is scaled by the display
  hardware at no GPU cost (`SurfaceHolder.setFixedSize`; Android Developers blog 2013, NVIDIA).
  Our compositor always renders a panel-sized swapchain and scales on the GPU.
- Android 16 ignores orientation and aspect-ratio locks on large screens, except for games. The
  manifest's game category covers us.
- `game_mode_config.xml` already refuses OEM downscaling and FPS overrides.

## Device tests

All runs on the AYN Thor (SD 8 Gen 2, Adreno 740, 1920x1080 top panel), Deck mode on, Force
fullscreen off, with a spike build of `feat/steam-native-output`. Each run sets
`Download/droiddeck-env` and starts the session with
`am start -n com.droiddeck.launcher/.SessionActivity --es mode steam [--es steamUrl ...]`.

### Run 1: baseline (Resolution = Match screen, 1920x1080)

Steam UI idle: GPU 0%, gamescope 27 dma-bufs, Xwayland 39. Everything renders at 1920x1080.

### Run 2: client height cap (`BL_STEAM_MAX_HEIGHT=720`)

- Log: `== steam: the client's height capped at 720 (was unset)`. The client never wrote its own
  value during the run, so a one-time set would hold.
- On screen: the Steam UI lays out at 1280x720 and gamescope scales it to fill 1920x1080. The
  mangoapp overlay stays at native size and sharpness, so the cap affects only the client's window.

### Run 3: two Xwayland servers (`--xwayland-count 2`, `STEAM_MULTIPLE_XWAYLANDS=1`), Alan Wake's American Nightmare, Force fullscreen off

- gamescope starts Xwayland on `:0` and `:1`. The client stays on `:0`, and the game runs on `:1`:
  the second Xwayland's dma-buf count rises when the game starts.
- The game runs full screen at about 60 fps with one swapchain, no resize churn, and stable buffer
  counts. Nothing visibly broke in the client.

### Run 4: as run 3, Force fullscreen on

The storm still happens. The games' Xwayland went from 16 to 840 dma-bufs in about 13 s, and the
memory guard (kills the session under 2.5 GB free) stopped it. gamescope logged no mode change for
server `#1`, so it stayed at the output size (1920x1080) while the game draws at 1280x720.

### Run 5: as run 4, writing `GAMESCOPE_XWAYLAND_MODE_CONTROL = 1,1280,720,0` at session start

gamescope applied it (`Updating mode for xwayland server #1: 1280x720`), then the client set it
back to `1920x1080` when it launched the game, and the storm followed. **The client manages the games'
Xwayland size itself:** at each launch it writes the game's "Game Resolution", which defaults to
native.

### Run 6: as run 5, writing the mode 5 s after the game takes focus (what a chosen Game Resolution does)

**No storm.** Force fullscreen on, games' Xwayland at 1280x720: 10 dma-bufs on `:1` and 41 in
gamescope for the whole run, 9 frame-size changes in total (runs 4/5: 835 and 1591), memory
steady, 60 fps. gamescope scales the 1280x720 game to fill 1920x1080.

**Conclusion so far:** the resize storm comes from Force fullscreen resizing a game window to a
display size the game won't accept. With the games on their own Xwayland server at the game's own
resolution, Force fullscreen is harmless. Two Xwayland servers alone don't help, because the client
sizes the games' server to native unless the game's resolution is set.

### Run 7: two Xwayland servers, output 1920x1080, `-w 1280 -h 720` (games 720p), Alan Wake, Force fullscreen off

The client ignores gamescope's nested size. It set both servers to the output size
(`#0: 1920x1080`, `#1: 1920x1080`). **The client's "native" game resolution is gamescope's output
size.** A panel-sized output on a 3120x1440 phone would therefore make every game default to
3120x1440 until a per-game resolution is chosen.

Side finding: gamescope passed the game's 1280x720 buffers straight through to our compositor (its
frame size changed to 1280x720) instead of compositing at the output size. Our compositor does the
final scale either way.

### Default resolution history

Before #247 the default was `resolutionCap = 720` with shape `auto`: the panel's own shape at 720
lines, never narrower than 16:9 (`SessionDisplay.resolve`). On a 2412x1080 phone that is 1608x720,
which is exactly the "custom" size a tester had been using. #247 replaced it with fixed 16:9
presets (`1280x720` default, `1600x900`, `1920x1080`). Those draw the client with bars on any panel
wider than 16:9, which is the black border the tester reported after #247.

## Conclusions

1. **The output size sets the default game resolution** (run 7), so it must stay bounded on
   very high-resolution panels. A native output is only safe on panels about 1080 lines or shorter.
2. **The output should keep the panel's shape**, so the client's interface is edge to edge, with
   the 16:9 floor from `d2ea9b89` so a foldable's inner panel doesn't make games crop themselves.
3. **The resize storm is Force fullscreen against a display size the game won't accept** (runs
   4 to 6). The SteamOS model fixes it: two Xwayland servers, with the games' server at the game's
   resolution. That needs a per-game Game Resolution, so it is a follow-up, not a default.
4. `GAMESCOPE_STEAM_MAX_HEIGHT` caps only the client and works on our gamescope (run 2). It
   becomes useful when the output is larger than the client should render, so it belongs with
   any future native-output option.

## Change made (this branch)

The resolution presets are heights: **720p (default), 900p, 1080p**, each in the panel's shape,
never narrower than 16:9 and never taller than the panel. Then Match screen (exact panel), then
Custom. Saved fixed sizes, custom sizes and Match screen are unchanged. Only the default and the
preset list change. Resolved sizes:

| Panel | 720p | 900p | 1080p |
|---|---|---|---|
| 1920x1080 (Thor, 16:9) | 1280x720 | 1600x900 | Match screen |
| 2412x1080 (20:9 phone) | 1608x720 | 2010x900 | Match screen |
| 3120x1440 (1440p phone) | 1560x720 | 1950x900 | 2340x1080 |
| 2208x1840 (foldable inner) | 1280x720 | 1600x900 | 1920x1080 |

### Run 9: default on the Thor

With no saved resolution (the saved choice, legacy shape and cap removed from `session.xml`, then
restored): the session starts at `size=1280x720` and the device report shows `720p (1280x720)`.

## Follow-ups

- **Two Xwayland servers and per-game Game Resolution** (done, see below). Was to verify: whether the client shows
  Game Resolution under game Properties in our session, the Android clipboard bridge for games on
  `:1`, and the drawer's live Force fullscreen toggle (it writes `GAMESCOPE_FORCE_WINDOWS_FULLSCREEN`
  only on `:0`'s root, and gamescope reads it per server). The client's devtools (port 8080 when
  `.cef-enable-remote-debugging` exists) accept guest processes only, so test it from inside the
  session.
- **Hardware scaler.** The compositor draws a panel-sized swapchain and scales on the GPU.
  `setFixedSize` to the session size, when no compositor effect is on, would hand that scale to
  the display hardware.
- **Force fullscreen per game**, the way Quake-engine titles are handled (`cbcd3f80`), instead of a
  global switch.

### How runs 2 to 6 were driven

Two temporary session-script hooks, not kept on this branch: `BL_STEAM_MAX_HEIGHT=N` wrote
`GAMESCOPE_STEAM_MAX_HEIGHT` on the root window, and `BL_GAME_XWAYLAND_MODE=WxH` wrote
`GAMESCOPE_XWAYLAND_MODE_CONTROL = 1,W,H,0` five seconds after `GAMESCOPE_FOCUSED_APP` showed a
game. `--xwayland-count 2` and `STEAM_MULTIPLE_XWAYLANDS=1` went through `Download/droiddeck-env`.

## Two Xwayland servers by default (`feat/steam-dual-xwayland`)

Steam sessions now start gamescope with `--xwayland-count 2` and `STEAM_MULTIPLE_XWAYLANDS=1`.
`BL_XWAYLAND_COUNT=1` in `Download/droiddeck-env` goes back to one. The session script's
root-window requests that are meant for the game (the drawer's live Force fullscreen,
`GAMESCOPE_FORCE_WINDOWS_FULLSCREEN`, and the resume watcher's `GAMESCOPE_RESTORE_FOCUS_WINDOW`)
go to both displays, `$DISPLAY` and `STEAM_GAME_DISPLAY_0`. gamescope reads both per server, so
writing only to `:0` would have reached the Steam client and not the game.

Clipboard: gamescope copies a selection made on any of its Xwayland servers to all of them
(`gamescope_set_selection`), so the Android text `droiddeck-clipboard` puts on `:0` reaches games
on `:1` without changes here.

Device runs (Thor, no `droiddeck-env`):

| Run | Game | Game Resolution | Force fullscreen | Result |
|---|---|---|---|---|
| d1 | Alan Wake's American Nightmare | 1280x720 (set in Steam) | on | The client set `#1` to 1280x720 itself. 2 frame-size changes, `:1` steady at 10 dma-bufs, memory steady. No storm |
| d2 | Alan Wake's American Nightmare | 1280x720 | on → off → on, live | `stretch games to fill the screen: 0 (live)` then `1`, written to both displays. Game stable |
| d5 | 198X | default | off | The client set `#1` to native 1920x1080. Full screen, 60 fps |

Steam saves the per-game choice as `"ResolutionOverride2" "1280x720"` under the app in
`userdata/<id>/config/localconfig.vdf`.

Checked by hand (2026-10-06, Thor, Force fullscreen off):
- **FlatOut 2**, the game Force fullscreen was added for, does not shrink when the QAM opens over
  it and closes again. With the client's overlay on another X server, the game keeps its own X
  keyboard focus and never sees the deactivation that made it shrink.
- Resume after the Steam menu: the resume watcher's restore request reaches both servers
  (`restore requested for focus window` logged once for the client's window and once for the
  game's).

Still to check by hand: pasting Android text into a game.

Force fullscreen is still unsafe without a matching Game Resolution: with the default (native),
a game drawing at another size fights it as in run 4. It stays off by default.
