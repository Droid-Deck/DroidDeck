# Keep-top guard A/B on dual screens

`KeepTopActivity` (#2763) covers every other display while a session is on top, so that
display's home is not its top activity. On the Thor, lmkd kills the bottom home under memory
pressure, Android relaunches it to the front (`wm_task_to_front`), the session loses the top
spot, drops to adj 50 and is killed in lmkd's next pass.

Android gives a translucent activity that is alone in its task an `ActivityRecordInputSink`
that consumes every touch inside the task bounds, whatever the window's flags say
(`ENABLE_TOUCH_OPAQUE_ACTIVITIES`, on for DroidDeck's target SDK; see
`services/core/java/com/android/server/wm/ActivityRecordInputSink.java`). The guard's
`FLAG_NOT_TOUCHABLE` therefore did nothing: with the dual-screen panel closed, the bottom
screen took no touch until the session ended, and the guard also stayed over it after Home.

The guard now takes touches itself. A touch removes it from that display. It comes back when
the session is top-resumed again, or after 5 s if the session kept the top spot (one touch on
the bottom screen, then back to the game). Stopping or entering PiP removes the guards.

## Tool

`tools/memory_guard_ab.py` drives a rooted dual-screen device over adb (`su` required) with a
build that has the debugging tools:

```sh
python3 tools/memory_guard_ab.py --label main --apk main.apk states
python3 tools/memory_guard_ab.py --label fix --apk fix.apk states
python3 tools/memory_guard_ab.py --label fix pressure --stop-mb 650 --hold 30 --runs 3
python3 tools/memory_guard_ab.py --label fix summarize ~/.cache/droiddeck-memory-guard-ab/fix-stop650-1-events.txt
```

Results are JSON lines in `~/.cache/droiddeck-memory-guard-ab/<label>.jsonl`, next to the
event log of every probe.

- `states` starts a fresh Steam session and walks the reachable states. In each one it records
  the focused display, the guards, the panel and DroidDeck's `oom_score_adj`, then kills the
  bottom home with `kill -9` (what lmkd does) and samples the adj for 5 s. It also counts guard
  launches during 3 idle seconds and `SessionActivity` instances, which catch a guard loop.
- `pressure` fills a private tmpfs with `/dev/urandom` until `MemAvailable` reaches
  `--stop-mb`, holds it, and releases it. zram cannot compress that data and no app owns it,
  so lmkd keeps killing by adj. It records lmkd's `killinfo`, process deaths, focus loss and
  whether DroidDeck survived.
- `--bottom-tap` must point at an inert spot of the bottom home; `--panel-close` at the
  panel's close button. Both are fractions of the bottom display.
- `am start` of `SessionActivity` needs `--display 0`; without it, it creates a second
  session on the focused bottom display.

## Results: Retroid Pocket Duo, Android 16, Cocoon as the bottom home

Builds: `main` (45c31a4), `fix` (this branch), and `no-guard` (`main` with
`guardOtherDisplays` returning at once, the behaviour before #2763).

### Bottom home killed in each state

| State | main | fix | no-guard |
| --- | --- | --- | --- |
| Panel open | guard, adj 0 | guard, adj 0 | adj 0 |
| Panel closed, bottom untouched | guard, adj 0 | guard, adj 0 | adj 0 |
| One bottom touch, under 5 s ago | guard, adj 0 (touch eaten) | no guard, adj 0 | focus on bottom, adj 50 |
| One bottom touch, then 8 s in the game | guard, adj 0 | guard, adj 0 | focus on bottom, adj 50 |
| Used the bottom screen, then tapped the game | guard, adj 0 | guard, adj 0 | adj 0 |
| Home (session in PiP) | guard, adj 50 | no guard, adj 50 | adj 50 |
| Using the bottom screen now | not reachable (touches eaten) | focus on bottom, adj 50 | focus on bottom, adj 50 |

No probe on any build lost the session's top spot or raised its adj when the bottom home was
killed. The fix has the guard on top wherever `main` has it, except in the 5 s after a bottom
touch and in PiP, where the session is at adj 50 with or without it. There were no guard
launches while idle and one session in every state.

### Real memory pressure, panel closed, game focused

| Run | lmkd kills (down to adj 0) | Bottom home deaths | Session lost the top spot | DroidDeck |
| --- | --- | --- | --- | --- |
| no-guard, stop at 650 MB | 42 (0) | 14 | no | survived |
| main, stop at 650 MB | 10 (0) | 0 | no | survived |
| fix, stop at 650 MB | 42 (0) | 18 | no | survived |
| no-guard, stop at 500 MB | 36 (2) | 15 | no | killed at adj 0 |
| main, stop at 500 MB | 73 (4) | 28 | no | killed at adj 0 |
| fix, stop at 500 MB | 74 (1) | 31 | no | survived |

Around 650 MB available, lmkd kills down to adj 50: the bottom home dies over and over and the
session survives. At 500 MB lmkd reaches its adj 0 pass and kills the foreground session
whatever the guard does; which build survives that is luck.

### What the Duo cannot show

Android 16 on the Duo restarts a killed bottom home in place (`wm_restart_activity`) and never
moves it to the front, even with no guard. The Thor's focus steal does not happen here, so the
Duo shows that the guard covers the same states as before, not that it saves the session. That
needs a Thor: run `states` and `pressure --stop-mb` near its adj-50 level on `main` and on the
fix, and look for `task_to_front` and `session_top_lost` before `droiddeck_died_at_adj` 50.
