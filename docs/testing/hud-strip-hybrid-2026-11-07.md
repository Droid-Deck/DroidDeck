# Strip hybrid rendering versus PR2194

PR2194 avoids the observed Strip flicker by rendering the entire Strip in a
software View layer. This follow-up keeps the View hardware accelerated and
rasterizes only its graph and logo into small bitmaps. The graph bitmap is
reused and cleared before each trace; the logo bitmap updates when its size
or colours change. Text and the rest of the HUD use the normal hardware path.
The 66 ms graph invalidation cap from PR2194 is retained.

This reduces the amount of HUD drawing that needs a software layer while
retaining bitmap rendering for the vector content used by the previously
working hybrid approach. It preserves Strip's layout and settings.

## Device comparison

On 2026-11-07, six samples were captured on a HONOR BKQ-N49 with Adreno 840
and Android 17. Both renderers were selected in the same comparison APK,
based on upstream `e5434117f`, using the same cached published-preview runtime
components. The later native presentation and producer-wait experiments were
excluded. The local build reused those components with source-provenance
warnings; this was a matched HUD comparison, not validation of newly built
runtime components from upstream main.

The debug harness selected either `LAYER_TYPE_SOFTWARE` with direct graph/logo
Paths, matching PR2194, or `LAYER_TYPE_NONE` with the hybrid bitmap graph/logo.
The software baseline therefore did not pay for the hybrid bitmap rendering
as well as its software View layer.

Each sample had a five-second warm-up and a 25-second measurement. Real
DPAD Right/Left inputs alternated at a target interval of 0.125 seconds,
keeping Steam navigation active. Each run delivered 244 inputs over its
30.5-second navigation window, including warm-up. The order was software,
hybrid, hybrid, software, software, hybrid. The same full-width Strip layout
and settings were used throughout. Display settings were temporarily held at
60 Hz and the original adaptive settings were restored and read back afterward.
No screen recording ran during measurement.

Values below are means of the three per-run measurements. CPU percentages
use 100% for one occupied CPU core. Draw and frame values are means of the
individual runs' p95 values, not pooled percentiles.

| Metric | PR2194 software | Hybrid | Relative change |
| --- | ---: | ---: | ---: |
| UI-thread CPU | 6.957% | 6.110% | -12.2% |
| App-process CPU | 32.788% | 30.863% | -5.9% |
| App-window draw p95 | 2.055 ms | 1.357 ms | -34.0% |
| App-window frame p95 | 5.397 ms | 4.543 ms | -15.8% |
| Reported GPU p95 | 1.389 ms | 1.346 ms | -3.1% |

| Run | Renderer | UI-thread CPU | App-process CPU | Draw p95 | Frame p95 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | Software | 7.044% | 31.664% | 2.095 ms | 5.357 ms |
| 2 | Hybrid | 6.232% | 31.572% | 1.395 ms | 4.570 ms |
| 3 | Hybrid | 6.105% | 31.612% | 1.327 ms | 4.549 ms |
| 4 | Software | 6.873% | 33.228% | 2.025 ms | 5.430 ms |
| 5 | Software | 6.952% | 33.472% | 2.045 ms | 5.403 ms |
| 6 | Hybrid | 5.992% | 29.404% | 1.348 ms | 4.509 ms |

All three pairs favoured hybrid rendering for UI-thread CPU and draw p95.
All runs retained Steam focus, reported thermal status 0, and lost no frame
metric reports. Layer type and Canvas hardware acceleration were checked
for each mode. The per-run measurements are in
[the accompanying JSON](hud-strip-hybrid-2026-11-07.json).

## Validation and limits

The 12 HUD regression checks passed, including graph trace clearing, scaling,
and a new check that a cached Strip logo updates its colours after a theme
change without resizing. The comparison APK built successfully.

An earlier saved APK using this hybrid approach was physically confirmed
flicker-free by the tester. Physical confirmation of the rebuilt comparison
APK is separate from the automated measurements and remains pending.
Frame metrics cannot establish absence of visible corruption.

These short samples on one device show a reduction in measured HUD rendering
cost. App-process CPU excludes other guest processes and is not whole-device
CPU usage. Sustained game performance, battery life, and temperature savings
were not established. This change targets Strip; it does not address the
separately observed flicker while moving a docked FPS tab or shrinking other
HUD modes.

Comparison APK SHA256:
`8df2d75216c40f24353a22a7c830341cb62a5ce6423ad2207477a0d769e22ae2`.
Temporary benchmark controls were excluded from the proposed production patch.
