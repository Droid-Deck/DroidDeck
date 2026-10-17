# Steam QAM frame limit vs an in-game cap: pacing investigation (2026-10-15)

Status: **unfinished**. The cause on the reporter's device is narrowed down but not fixed. This branch
holds compositor changes that are *plausible but unproven*, plus the tracing and repro tooling, so the
next person doesn't start from zero.

## The report

Discord, bug-reports forum, thread "Unstable frame limit". The main reporter, siigonis_ru, is on a
Retroid Pocket G2 (Adreno 732 class GPU, 60 Hz panel, 1280x720 session shown at 1920x1080 with FSR 1).

- With Steam's QAM Frame Limit at 30, motion is choppy: "small stops and ladders" during camera pans.
  It is worst with LSFG 2x on top, which also feels laggy.
- The same game capped by its own 30 FPS lock is smooth. In the Serious Sam 2 clip that is perfect even
  with LSFG 2x ("the generated 60 feels like real 60").
- The DroidDeck cap (session settings, 30) was "sometimes as good as the in-game lock, sometimes worse
  than ever".
- Earlier PRs from the same thread: #731 (re-attached buffers counted as new frames), #753 (gamescope
  patch 0115, per-window time-paced limiter), #717 (presentation timestamps on the vsync tick).

## Timeline and findings

### 1. The reporter's own data (logs + clips from 2026-10-13..15)

Measured from his screen recordings (`tools/vdiff.py` + `tools/ana.py`, Serious Sam 2, LSFG 2x):

| | Content changes/s | Spacing σ | Holds |
|---|---|---|---|
| In-game 30 cap + LSFG 2x | 60 | 0 ms | none |
| QAM 30 + LSFG 2x | 40-57 | 15-50 ms | 3- and 4-refresh holds |

His compositor log (`DroidDeckWayland [perf]`) under QAM 30 with frame generation off shows `present` at
10-13 ms average (25 ms max) and `render_scene` around 16 ms. Uncapped, `present` is 0.3 ms. On the G2
the compositor spends most of a refresh blocked in `vkQueuePresentKHR`.

### 2. LSFG Adaptive runaway in the DroidDeck-cap log → fixed in #927

The "worse than ever" stretches of the DD-cap clip were LSFG Adaptive 60 locking itself at 2-3
generations with the game shown at 16-20 fps. The pacer measured the source rate from compositor
passes, not game frames, so a slowed loop justified more generations. #927 feeds it the real game
frame count; validated on the Thor (recovers in 3-5 s after a load spike instead of 25-40 s).

### 3. How gamescope's QAM limiter works here (code reading)

- Steam sets the cap through `gamescope_control` / `GAMESCOPE_LIMITER_FILE`
  (`STEAM_GAMESCOPE_DYNAMIC_FPSLIMITER=1`). The `GAMESCOPE_FPS_LIMIT` root atom is overwritten and is
  useless for testing. The debug patch below adds `dd_force_fps_limit` for that.
- With the limiter on, the WSI layer forces FIFO. FIFO commits from a limited window only become
  displayable on an allowed vblank (`handle_done_commits_xwayland`), and `frame_done` only goes out
  then. DXVK paces on present completion, so the game runs one frame at a time against that grid.
- The Wayland backend's vblank grid comes from our `wp_presentation` timestamps. gamescope wakes
  `redzone (1.65 ms) + draw time` before each target vblank. The redzone is hard-coded
  (`kDefaultVBlankRedZone`) and tuned for DRM scanout, not for a nested host compositor.
- `GetValidDynamicRefreshRates()` is empty in the Wayland backend. Even exposing rates would not move a
  30 cap off 60 Hz: Steam picks the highest rate that divides by the cap.

### 4. Reproducing on the AYN Thor (Saints Row IV, 60 Hz, Steam offline)

Unloaded, QAM 30 is clean on screen on the Thor (`tools/shift.py` motion CV 0.05-0.08, one held frame
in 10 s). The gamescope trace shows a perfect 2-refresh cadence (865/865 frames).

With 3 guest busy loops, QAM 30 + LSFG shows 50 ms holds like the reporter's. But `tools/slip.py` shows
**no game commit was waiting** at every skipped vblank: under that load Saints Row IV simply misses 33 ms.
Any limiter shows that, so this is not the reporter's case.

Saints Row IV is a poor stand-in for his games anyway. Its vsync is off by default, so a DXVK
`maxFrameRate` cap presents non-FIFO and was *less* even than QAM (motion CV ~0.4). His games' 30 FPS
locks are most likely vsync interval 2, which FIFO-queues each frame for exactly two refreshes.

### 5. The compositor side (compositor trace patch)

With `patches/compositor-frame-trace.patch` (vsync ticks, every new client buffer, every render):

- Our event loop blocks 8-25 ms per frame inside `vkQueuePresentKHR`. On Android, Turnip waits on the
  CPU for the frame's GPU work, which queues behind the game's. 96% of late vsync ticks happened while
  we were blocked there.
- gamescope paces its next composite on our presentation feedback. Feedback was sent only after the
  blocked present, so gamescope's commit often landed right *after* the following tick.
- gamescope commits arrive only ~2.3 ms (median, min 0.05 ms) before our tick. With the composite or
  transport a bit slower, they arrive after it and the frame shows one refresh late (3/1 "ladders").
- Caveat found late: gamescope also pushes a new game-plane buffer whenever it re-composites (MangoHud
  overlay update), so "new game-plane buffer" is **not** "new game frame". The compositor-side cadence
  numbers overstate the problem; judge everything by the screen recording.

### 6. Emulating the G2 on the Thor (the useful repro)

`dd_force_fps_limit 30` plus `dd_paint_delay_us 8000` (each gamescope composite delayed by a random
0-8 ms) produces the reporter's symptom on the Thor's screen with the **stock** compositor:

| stock compositor, QAM-style 30, FG off | off-cadence changes | motion CV |
|---|---|---|
| no delay | ~1% | 0.05-0.08 |
| 0-4 ms delay | 4 of ~150 | 0.09-0.14 |
| 0-8 ms delay | 16 of ~197 (8%) | 0.26-0.74 |

**This A/B was not finished**: the same 0-8 ms run on this branch's compositor still has to be done.

### 7. Side findings

- Requesting a HIGH global-priority queue for the compositor (Turnip on KGSL) did not help.
  `CP: AHB bus error` GPU faults then appeared and the Thor eventually froze and needed a reboot. The
  faults later showed up on stock builds too (~120/min while gaming), so the link is unproven, but the
  priority request was dropped.
- Wireless ADB changes port after a reboot (`adb mdns services`). Steam comes back online after every
  session restart; `SteamClient.User.GoOffline()` over CDP avoids "account playing elsewhere" kicks.

## What is on this branch

Compositor changes, **unvalidated on screen**:

1. **Present worker thread** (`vk_present.c`). A single-present frame (frame generation off) is handed to
   `dd-present` for `vkQueuePresentKHR` and the fence wait. Everything that touches the queue, fence or
   swapchain calls `present_drain()` first. Client buffer releases wait for an in-flight present.
   Measured: `render_scene` from ~12 ms to under 1 ms; late vsync ticks p95 from 9 ms to 1.5 ms.
2. **Early feedback** (`compositor.c`). Presentation feedback and frame callbacks are sent when the scene
   is ready (`vkp_scene_ready_hook`, before acquire/present) rather than after the present returns.
3. **Dedicated vsync thread** (`waylandcomp_jni.c`, `CompositorHost.kt`). Ticks come from an
   `AChoreographer` on its own looper thread (`dd-vsync`), not the main thread's Choreographer.
4. **Post-vsync latch** (`compositor.c`). The scene is composed `min(35% of a refresh, 6 ms)` after the
   vsync instead of on the tick, so a gamescope commit aimed at that vsync still makes it. Composing
   takes under 1 ms now, so it should still hit the same SurfaceFlinger vsync and add no display
   latency. That is **not yet measured**: check with SurfaceFlinger timestats
   (`tools/droiddeckctl pacing`) before shipping.

`patches/` (not built):

- `gamescope-limiter-debug.patch`, on top of `tools/gamescope/patches` (after 0115). Adds the
  `DD_FRAMETRACE=<file>` env trace (`V` vblank wake, `C` client commit, `D` commit displayable,
  `F` frame_done, `P` base-plane paint) and two convars for `gamescopectl`:
  `dd_force_fps_limit <fps>` and `dd_paint_delay_us <max>`.
- `compositor-frame-trace.patch`, on top of this branch. Writes `T`/`S`/`R` lines to
  `files/comptrace.log` when `files/comptrace.on` exists (create it as root, then chown/chcon it to
  the app).

`tools/`: capture and analysis scripts used above (headers say what each does).

## How to pick this up

1. Build gamescope with `patches/gamescope-limiter-debug.patch` (`tools/gamescope/build-in-arch.sh`, or
   a persistent `menci/archlinuxarm:base-devel` container and `ninja src/gamescope` in
   `pkg/arch/src/build` for 20 s rebuilds). Install it with `tools/droiddeckctl override gamescope` and
   `droiddeckctl env set DD_FRAMETRACE=/tmp/ddtrace.log`, then restart the session.
2. In game: Steam QAM "Disable Frame Limit" on, then
   `gamescopectl dd_force_fps_limit 30; gamescopectl dd_paint_delay_us 8000`.
3. `tools/capture-case.sh <name>`, then `tools/vdiff.py` + `tools/ana.py` and `tools/shift.py` on the
   clip. Compare this branch against main **on screen**, FG off and LSFG 2x.
4. If the branch wins and SurfaceFlinger timestats show no added latency, split it into PRs: the
   present worker + early feedback, the vsync thread, the latch delay. Then ask the reporter to try a
   `publish-test` build with Serious Sam 2 at QAM 30 + LSFG 2x.
5. If the latch doesn't help, the gamescope-side alternative is a larger redzone for the nested Wayland
   backend (`kDefaultVBlankRedZone`). That has the same latency cost and needs a gamescope component
   release.
6. Also worth testing on a weaker GPU: the reporter's DroidDeck-cap log (gamescope `-r 30` plus
   compositor release pacing) was perfectly steady once #927's runaway is out of the way. Mapping the
   QAM cap onto that path (nested refresh = cap when it divides the panel rate) is a candidate fix if
   the compositor changes fall short.
