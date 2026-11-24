# Keep-top guard A/B on dual screens

`KeepTopActivity` (#2763, removed in the change that added this section) covered every other
display while a session was on top, so that display's home was not its top activity. The
reasoning: on the Thor, lmkd kills the bottom home under memory pressure, Android relaunches it
to the front, the session loses the top spot, drops to adj 50 and is killed next.

It had three side effects:

- Android gives a translucent activity alone in its task an `ActivityRecordInputSink` that
  consumes every touch in the task bounds whatever the window's flags say
  (`ENABLE_TOUCH_OPAQUE_ACTIVITIES`), so the bottom screen took no touch (#2910 worked around it).
- Cocoon tracks the top activity of each display; with a DroidDeck activity on the bottom it
  could not suspend or resume DroidDeck properly.
- The Thor's `com.odin.gameassistant` gives every app with an activity on top of the bottom
  display that display's volume (`secondary_screen_volume_level`). With the guard up, all of
  DroidDeck's audio played at the bottom screen's volume (AudioFlinger track gain -37, -13 and
  -2.6 dB at levels 3, 8 and 13; -7.4 dB, the top level, once the guard was removed).

The A/B below found no case on either device where the guard kept the session alive, so it was
removed.

## Tool

`tools/memory_guard_ab.py` drives a rooted dual-screen device over adb (`su` required) with a
build that has the debugging tools:

```sh
python3 tools/memory_guard_ab.py --label main --apk main.apk states
python3 tools/memory_guard_ab.py --label fix --apk fix.apk states
python3 tools/memory_guard_ab.py --label fix pressure --stop-mb 650 --hold 30 --runs 3
python3 tools/memory_guard_ab.py --label fix pressure --burst --stop-mb 1500 --hold 20 --runs 3
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
- `pressure --burst` takes everything above `--stop-mb` at once instead: it copies a 256 MiB
  random file into the tmpfs with all copies running in parallel (about 5 GiB in 2 to 3 s on a
  Thor), the way a game's GPU allocation burst lands on lmkd.
- `oom_score_adj` is read without `su`. Under pressure lmkd keeps killing the root manager app,
  and a `su` call can then open its request screen on top of the game.
- If the bottom display has no home (a killed home is not always restarted), the visible app on
  top of it stands in.
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

## Results: AYN Thor (12 GB), Android 13

Builds: `main` (56986b5, guard with the #2910 touch yield) and `no-guard` (`main` with
`guardOtherDisplays` returning at once). The game was focused on the top screen in every run.

| Pressure | Bottom app | no-guard | main |
| --- | --- | --- | --- |
| Fill to 650 MB free, hold 30 s | stock launcher | survived 1/1 usable run (launcher not reached) | survived 3/3 (launcher killed 80, 25, 0 times) |
| Fill to 550 MB free, hold 30 s | Cocoon as home | survived 3/3, Cocoon killed 387, 400, 84 times | survived 3/3, Cocoon killed 384, 127, 338 times |
| Burst to 400 MB free (about 5 GB in 2.5 to 3.4 s) | stock launcher | killed at adj 0 in 3/3 | killed at adj 0 in 3/3 |
| Burst to 1500 MB free (2.0 to 2.4 s) | stock launcher | survived 1/2, killed at adj 0 in 1/2 | killed at adj 0 in 2/2 |

No run on either build lost the top spot (`wm_on_top_resumed_lost_called` for
`SessionActivity`), moved a task to the front, or killed DroidDeck at adj 50. A killed bottom
home is either restarted in place or its task disappears and the next app shows on the bottom
screen, without taking focus. A burst big enough to matter makes lmkd kill the foreground
session at adj 0, which no activity on another display can prevent.

`kill -9` of the bottom app (`states`) is noisy on a Thor with many recent apps: each kill
surfaces the next app on the bottom screen, and every tap the tool sends there moves focus.
