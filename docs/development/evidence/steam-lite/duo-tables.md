**duo: library idle per web helper variant** (one fresh session per run, median of its samples; cells are median (min-max) over runs, MiB)

| Variant | Runs | Client tree PSS | Renderer PSS | Browser PSS | KGSL GPU (not in PSS) |
| --- | --- | --- | --- | --- | --- |
| default | 5 | 941 (913-1104) | 359 (319-529) | 308 (303-344) | 202 (190-262) |
| gpu-budget | 2 | 1157 (1156-1158) | 568 (553-584) | 329 (323-335) | 246 (236-256) |
| v8-small | 5 | 1114 (1098-1134) | 519 (497-542) | 317 (300-340) | 202 (194-277) |
| cef-software | 3 | 954 (940-965) | 398 (382-406) | 291 (286-292) | 25 (25-25) |
| soft-compositing | 3 | 979 (945-985) | 415 (381-421) | 295 (290-295) | 26 (26-26) |

