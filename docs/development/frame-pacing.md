# Even frame pacing

A game capped below the panel's refresh rate, such as Steam's Frame Limit at 30 or 40 fps on a 120 Hz screen, has to stay the same number of refreshes on screen for every frame. Two things knocked frames off that rhythm. **Even frame pacing** (Display page of the session drawer, off by default, `presentCadence` in the `session` prefs) handles both.

## Why frames landed off cadence

Measured on an AYN Thor (Adreno 740, Android 13) with Skyrim SE.

1. **The frame is ready late.** The compositor copies each game frame onto the game's display layer (`sc_layer_present` and friends) and waits for the copy before it hands the frame to SurfaceFlinger. The copy queues on the GPU behind the game's next frame, so the hand-off lands 0–15 ms late. That is enough to move a frame to another 8.3 ms refresh. KGSL preemption is off on that device, and Turnip's KGSL backend has a single queue priority, so the copy can't jump the queue. A zero-copy frame (`sc_layer_present_ahb`) skips the copy, but it is committed before the game's GPU work on it is done, and SurfaceFlinger takes it only once its fence has signalled.
2. **SurfaceFlinger shows lone frames early.** At 120 Hz there, SurfaceFlinger's work duration (10.33 ms, `dumpsys SurfaceFlinger | grep duration`) is longer than a refresh, so it composes a frame 2 ms before the refresh ahead of the expected one. When nothing was presented on that earlier refresh and its commit beats it, the display shows the frame there, one refresh early. Whether it wins is a race decided by a fraction of a millisecond, so frames come out 3 and 5 refreshes apart instead of 4. The frame timeline marks them `Jank Type: SurfaceFlinger Scheduling` / `Present Metadata: Early Present`. At 60 Hz the work duration fits inside a refresh, so there is no race.

Without even frame pacing, each layer transaction carries a desired present time aimed at the next refresh, held to an even step when the game's rate is a steady whole fraction of the refresh rate (`apply_present_time` in `sc_layer.c`). That handles the first cause but not the second.

## How it works

- `present_cadence.c` plans each frame. It tracks frame arrivals against a reference on the refresh grid, which absorbs an eighth of each frame's offset.
  - A frame's target refresh keeps the stream's cadence. It is never earlier than the reference + the longest wait of the last 128 frames + SurfaceFlinger's deadline.
  - A copied frame's wait ends when its copy is done. A zero-copy frame's wait ends when its fence signalled, read from the fence when the next frame arrives (`zero_copy_ready`).
  - The grid and the deadline come from Choreographer's frame timeline (`VsyncCallback`, Android 13+, `sc_layer_frame_timeline`).
  - Pacing turns on once the last 8 gaps average 1.5 refreshes or more, and off below 1.25.
- The presenter thread `dd-present` (`sc_layer.c`) applies each held frame 1 ms before SurfaceFlinger's deadline for its refresh.
  - On every refresh in between, it swaps the buffer of a transparent 16×16 layer (`droiddeck_wayland_pacer`) under the game, so every refresh is presented and a frame can't land early.
  - Every other layer transaction goes through the same queue, so none overtakes a held frame.
  - Paced frames carry no desired present time. SurfaceFlinger applies one app's transactions in order, so a frame waiting there for a later refresh would hold back the fillers behind it.
- The compositor turns pacing off, and logs why, while something else paces every refresh: black frame insertion, frame generation, streaming, Cardboard VR, or a headset composing its own layers. It also waits while the game is drawn on any display other than the phone screen (glasses, a bottom screen, or a session on an external display), because the frame timeline describes the phone screen's refreshes (`cadence_blocked` in `compositor.c`).
- The toggle only shows on Android 13 and later, which have the frame timeline.

Cost: frames reach the screen later (about 34 ms from arrival at 40 fps on 120 Hz with Skyrim, against about 18 ms unpaced), and SurfaceFlinger composes on every refresh while a capped game is paced.

## Reading the logs

Every 10 seconds the compositor log has a `[pacing]` line such as:

```
400 game frames held to a steady cadence (every 3 refreshes at the end), 34.08/36.34 ms from arrival to screen,
0 ready too late for their refresh, 0 shown unpaced | presenter: 796 filler refreshes, 0 refreshes left empty,
0 frames past their refresh, 0 late wake-ups, ...
```

On a steady stream, filler refreshes plus game frames should equal the refresh count. Refreshes left empty should be 0.

## Measuring what reaches the screen

```sh
L=$(adb shell dumpsys SurfaceFlinger --list | grep droiddeck_wayland_game)
adb shell dumpsys SurfaceFlinger --latency "$L"          # desired, actual present and ready times per frame
adb shell dumpsys SurfaceFlinger --frametimeline -all    # Early / On Time / Late presents and their jank types
droiddeckctl pacing --seconds 10                         # present-to-present histogram from timestats
```

The `--latency` window holds the last 128 game frames, so once a second is enough for it. The frame timeline holds the last 64 display frames, and with fillers that is one display frame per refresh, about 0.53 s at 120 Hz, so sample it at least twice a second.
