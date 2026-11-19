# steam-present

Host-side Steam menu frame-rate measurement using only unprivileged
`adb shell dumpsys SurfaceFlinger` (no droiddeckctl provider, no access to
`/run/droiddeck-hud/frames`).

Run once the menu is up:

    python3 tools/steam-present/steam_present.py 10 -s <serial>

Output is JSON: frames, mean/p50/p95/p99 interval (ms), fps (from mean
interval), fpsWall (frames / window), histogram (6.7 / 8.33 / 11.1 / 16.7 /
33 ms buckets, 33 includes slower), and `verdict`: `120-paced`, `90-capped`,
`60-capped`, `dropped-frames` or `no-frames` (exit 1).

How it works: during the window it re-runs `--list` each poll (layer names with
`#id` change on session restart), picks the `SessionActivity` SurfaceView
(BLAST preferred; `--layer REGEX` overrides) and polls
`--latency <exact layer>`, de-duplicating actual-present timestamps across
polls (the buffer holds 127 rows, about 1 s at 120 Hz). In parallel it enables
`--timestats` and dumps it at the end. If `--latency` gives fewer than 10 frames
(BLAST surfaces often return only the refresh period), the per-layer
present2present histogram is used instead (1 ms bins, so 8.33 lands in the
8 ms bin; bucketed by bin midpoint). `source` in the output says which.
Gaps over 100 ms are counted as `idleGaps`, not drops, since BLAST layers
only present on change; a static menu can therefore show few frames.
`--latency-clear` is deliberately unused (it would discard samples).

Tests (synthetic 8.33 / 16.67 / 11.1 / mixed cadences, empty buffers, pending
rows, layer rename, timestats fallback): `python3 -I -m unittest discover -s tools/tests -p test_steam_present.py`
(also picked up by tools/ci/run-python-tests.sh).
