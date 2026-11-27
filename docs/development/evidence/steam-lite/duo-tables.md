**duo: library idle per web helper variant** (one fresh session per run, median of its samples; cells are median (min-max) over runs, MiB)

| Variant | Runs | Client tree PSS | Renderer PSS | Browser PSS | KGSL GPU (not in PSS) |
| --- | --- | --- | --- | --- | --- |
| default | 5 | 941 (913-1104) | 359 (319-529) | 308 (303-344) | 202 (190-262) |
| gpu-budget | 2 | 1157 (1156-1158) | 568 (553-584) | 329 (323-335) | 246 (236-256) |
| v8-small | 5 | 1114 (1098-1134) | 519 (497-542) | 317 (300-340) | 202 (194-277) |
| cef-software | 3 | 954 (940-965) | 398 (382-406) | 291 (286-292) | 25 (25-25) |
| soft-compositing | 3 | 979 (945-985) | 415 (381-421) | 295 (290-295) | 26 (26-26) |

**duo: app 584400 in front, steady state** (one fresh session per run; game +80 s; MiB unless noted)

| Run | Resident PSS | SwapPss | Renderer | KGSL | MemAvailable | Web helper CPU % | FPS | p99 ms | Off cadence % | Overlay open s (median, tool-bound) | Supervisor pass |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| l1-1 | 693 | 162 | 248 | 225 | 3176 | 2.1 | 59.1 | 25 | 1.6 | 4.05 | skipped-reclaim |
| l1-2 | 771 | 90 | 273 | 224 | 3288 | 1.8 | 58.7 | 25 | 3.5 | 4.04 | skipped-reclaim |
| l2-1 | 742 | 124 | 258 | 936 | 2587 | 2.0 | 59.2 | 25 | 1.7 | 4.03 | skipped-reclaim |
| l2-2 | 781 | 113 | 313 | 239 | 3169 | 3.6 | 58.5 | 25 | 5.2 | 4.09 | skipped-reclaim |
| softcomp-1 | 790 | 118 | 341 | 26 | 3435 | 2.9 | 59.3 | 25 | 1.8 | 4.07 | none |
| softcomp-2 | 852 | 65 | 429 | 26 | 3373 | 11.4 | 59.2 | 25 | 2.5 | 4.04 | none |
| softcomp-3 | 736 | 139 | 376 | 26 | 3367 | 1.7 | 59.2 | 25 | 3.0 | 4.24 | none |
| stock-1 | 874 | 0 | 300 | 221 | 3058 | 2.5 | 59.4 | 16 | 1.2 | 3.98 | none |
| stock-2 | 719 | 121 | 242 | 193 | 3241 | 1.8 | 59.4 | 25 | 1.6 | 4.24 | none |
| stock-3 | 770 | 90 | 275 | 233 | 3197 | 2.2 | 59.2 | 25 | 1.4 | 4.02 | none |

