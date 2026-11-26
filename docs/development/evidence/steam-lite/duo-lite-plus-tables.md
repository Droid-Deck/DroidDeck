**duo-lite-plus: save load in app 1388870** (fresh session per trial; a trial counts once the load started; a kill is an Android `LOW_MEMORY` exit)

| Arm | Trials loaded | Survived 90 s | Killed (lmkd) | Stopped otherwise | Seconds from Continue to kill |
| --- | --- | --- | --- | --- | --- |
| l1 | 2 of 2 | 0 | 2 | 0 | 33 (32-33) |
| softcomp+l1 | 3 of 3 | 0 | 3 | 0 | 30 (17-30) |
| softcomp | 2 of 2 | 0 | 2 | 0 | 24 (17-31) |
| stock | 2 of 3 | 0 | 2 | 0 | 30 (29-32) |

**duo-lite-plus bully-browser-target: one intervention at a time** (change from the sample just before it, MiB; negative = freed)

| Action | After | Client tree PSS | Renderer PSS | KGSL GPU |
| --- | --- | --- | --- | --- |
| browser-moderate | 3s | +0.1 | +0.1 | +0.0 |
| browser-moderate | 20s | +0.0 | +0.0 | +0.0 |
| browser-moderate | 40s | +0.0 | +0.0 | +0.0 |
| browser-critical | 3s | +0.1 | +0.1 | +0.0 |
| browser-critical | 20s | +0.0 | +0.0 | +0.0 |
| browser-critical | 40s | +0.0 | +0.0 | +0.0 |

**duo-lite-plus bully-hide: one intervention at a time** (change from the sample just before it, MiB; negative = freed)

| Action | After | Client tree PSS | Renderer PSS | KGSL GPU |
| --- | --- | --- | --- | --- |
| hide | 3s | +0.0 | +0.0 | +0.0 |
| hide | 20s | -38.9 | -39.6 | +0.0 |
| hide | 40s | -38.8 | -39.5 | +0.0 |
| show | 3s | +0.0 | +0.0 | +0.0 |
| show | 20s | +0.1 | +0.1 | +0.0 |
| show | 40s | +1.0 | +0.5 | +0.0 |

**duo-lite-plus bully-ingame: one intervention at a time** (change from the sample just before it, MiB; negative = freed)

| Action | After | Client tree PSS | Renderer PSS | KGSL GPU |
| --- | --- | --- | --- | --- |
| gc | 3s | -32.1 | -32.1 | +0.0 |
| gc | 20s | -40.3 | -40.4 | +0.0 |
| gc | 40s | -39.5 | -39.6 | +0.0 |
| moderate | 3s | +0.2 | +0.2 | +0.0 |
| moderate | 20s | +0.8 | +0.8 | +0.0 |
| moderate | 40s | +1.6 | +1.6 | +0.0 |
| critical | 3s | -1.2 | -1.2 | +0.0 |
| critical | 20s | -0.6 | -0.6 | +0.0 |
| critical | 40s | +0.2 | +0.2 | +0.0 |
| pause | 3s | -1.6 | -1.6 | +0.0 |
| pause | 20s | -1.0 | -1.0 | +0.0 |
| pause | 40s | -0.2 | -0.2 | +0.0 |
| resume | 3s | +0.2 | +0.2 | +0.0 |
| resume | 20s | +0.8 | +0.8 | +0.0 |
| resume | 40s | +1.4 | +1.4 | +0.0 |

**duo-lite-plus library-ui: one intervention at a time** (change from the sample just before it, MiB; negative = freed)

| Action | After | Client tree PSS | Renderer PSS | KGSL GPU |
| --- | --- | --- | --- | --- |
| gc | 3s | -0.3 | -0.3 | +0.0 |
| gc | 20s | -28.6 | -27.3 | -1.0 |
| moderate | 3s | +0.0 | +0.0 | +0.0 |
| moderate | 20s | +0.1 | +0.1 | +0.0 |
| critical | 3s | -2.0 | -2.0 | +0.0 |
| critical | 20s | +23.3 | +23.1 | +0.0 |

**duo-lite-plus: Bully in front, steady state** (one fresh session per run; game +80 s; MiB unless noted)

| Run | Resident PSS | SwapPss | Renderer | KGSL | MemAvailable | Web helper CPU % | Overlay open s (median, tool-bound) | Supervisor pass |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| l1-1 | 883 | 0 | 310 | 186 | 3198 | 2.2 | 4.11 | gc, moderate |
| l1-2 | 875 | 0 | 297 | 157 | 3111 | 2.6 | 4.06 | gc, moderate |
| l1-3 | 889 | 0 | 305 | 164 | 3229 | 2.6 | 4.07 | gc, moderate |
| l2-1 | 785 | 172 | 283 | 691 | 2841 | 2.4 | 4.09 | skipped-reclaim; pause |
| l2-2 | 796 | 216 | 283 | 676 | 2863 | 2.7 | 4.07 | skipped-reclaim; pause |
| l2-3 | 674 | 226 | 235 | 166 | 3360 | 2.8 | 4.04 | gc, moderate, pageout, pause |
| stock-1 | 849 | 139 | 297 | 664 | 2935 | 2.8 | 4.1 | none (stock) |
| stock-2 | 723 | 231 | 270 | 166 | 3286 | 2.9 | 4.06 | none (stock) |
| stock-3 | 902 | 0 | 305 | 187 | 3224 | 2.1 | 4.04 | none (stock) |

**duo-lite-plus: settled library (+90 to +110 s after start)**

| Run | Compositing | Web helper CPU % | Client tree PSS (MiB) | KGSL (MiB) |
| --- | --- | --- | --- | --- |
| default-1 | GPU compositing | 1.7 | 905 | 239 |
| default-2 | GPU compositing | 1.7 | 913 | 244 |
| softcomp-1 | soft compositing | 2.6 | 937 | 26 |
| softcomp-2 | soft compositing | 2.6 | 936 | 26 |

