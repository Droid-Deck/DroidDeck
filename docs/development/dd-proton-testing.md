# DroidDeck Proton (Auto) on the Thor

Results of running games through "DroidDeck Proton (Auto)" on the AYN Thor (Snapdragon 8 Gen 2,
Adreno 740: `A7XX`). The base is Valve's Proton Experimental ARM64 `1790245028
experimental-11.0-20260924-arm64`. The FEX preset was the Stability-style full-TSO one. See
[game-environment.md](game-environment.md#droiddeck-proton-auto) for how recipes work and
[dd-proton-library.md](dd-proton-library.md) for the library.

Each game was mapped to the Auto tool in Steam's Compatibility setting. FPS readings come from
`DXVK_HUD`, set through the game's own environment profile so it applies with and without a
recipe.

## Plumbing (hand-written recipes, 2026-10-08)

| Check | Result |
| --- | --- |
| Tool listed in Steam's per-game Compatibility; choice survives restarts | Pass |
| Bug Fables (1082710), recipe DXVK 2.6.2 + `DXVK_HUD` | Pass: HUD shows `DXVK v2.6.2`, main menu at 60 FPS, prefix `d3d11.dll` matches the package |
| Same game, recipe without packages | Pass: Valve's `DXVK v3.1.1` back in the prefix (sha256 match), 60 FPS |
| Valve's depot after both | Pass: DXVK files byte-identical to before |
| 8-Bit Bayonetta (567090), Auto tool, no recipe | Pass: stock launch, no tree built, esync on the depot |
| esync on top of a recipe tree | Pass after the fix below: exact pack match, game runs |
| Proton update | Covered by unit tests (version line changes the key); not exercised on the device |

The first device run found that a guest `opendir()` of a directory symlink fails under proot's
fast path (`ENOTDIR`). esync's tree on top of the recipe tree then had no Wine unix libraries, and
`wineboot` died (`ws2_32.dll failed to initialize`). Recipe trees now keep esync's directories
real (`ESYNC_DIRS`). The fast path bug itself is tracked separately.

## Community library (bundled `default.json`, 2026-10-08)

With no override file, the app published the bundled library for `A7XX`: 209 recipes, 3
packages fetched. Three installed games have a library entry. Each received exactly its FEX
variables, and the Auto launch path ran.

| Game | Recipe | Result |
| --- | --- | --- |
| The Elder Scrolls V: Skyrim Special Edition (489830) | `FEX_HALFBARRIERTSOENABLED=0`, `FEX_X87REDUCEDPRECISION=1` (65 sources) | Recipe applied; Skyrim's launcher (`SkyrimSELauncher.exe`) and its first-run video detection dialog came up. Gameplay not yet checked. |
| Portal 2 (620) | `FEX_X87REDUCEDPRECISION=1` (15 sources) | Recipe applied; `portal2.exe` created its Vulkan device. Not yet checked on screen. |
| OCTOPATH TRAVELER II (1971650) | `FEX_HALFBARRIERTSOENABLED=0`, `FEX_X87REDUCEDPRECISION=1` (7 sources) | Recipe applied; the game stopped before creating a Vulkan device. Not yet compared with a stock launch, so the cause is open. |

Still to do on the device: a stock run of each of these for comparison (same tool with an
empty override), and FPS and stability in gameplay. Record the outcome in
`tools/recipes/overrides.json`: keep the entry, replace it with a tested one, or block it. Then
regenerate the library.
