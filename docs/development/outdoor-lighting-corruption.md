# Outdoor lighting corruption investigation

Status: root cause unknown. The evidence does not yet identify the first incorrect rendering resource, so no production rendering change is justified.

## Reported behavior and launch environment

A [public support report](https://discord.com/channels/1552707793247539220/1556195514322780160/1571201861208514720) shows black mountain faces and foliage outdoors while other geometry remains normally lit. The reporter says the corruption disappears indoors. A [later video](https://discord.com/channels/1552707793247539220/1556195514322780160/1571208309103530085) was captured with mods completely disabled. These observations establish the visible symptom but not the failing render pass.

The supplied session report identifies application 0.7.1 (19) on a Samsung SM-F971U with an Adreno840v2. Automatic driver selection chose DD-Turnip v0.7.0 for both display and Linux rendering. Snapdragon GSR quality was active with 70% sharpness. Driver changes reduced the reporter's observed frequency, but this was not a controlled comparison and GSR remains an uncontrolled variable.

The session log launches app 489830 through DroidDeck-Proton 11-12.3. Its Gamescope WSI block identifies `SkyrimSE.exe` and DXVK. The reported engine version is insufficient to establish the exact DXVK source revision. No driver error, DXVK resource trace, shader trace, or frame capture identifies a faulty operation.

## Code and upstream audit

Existing game environment composition is implemented in `tools/linuxfs/overlay/usr/local/bin/droiddeck-game-env`. Its executable-specific `render_path_defaults` workaround must not be extended to this unresolved symptom. No matching open or merged correction was found in DroidDeck pull requests.

Issue #2374 concerns an Adreno 650 D3D9 hang and per-game environment ordering. Issue #2167 describes fixed-region tiled corruption on Adreno 850. Neither establishes the same mechanism as this outdoor D3D11 lighting corruption, and neither issue is resolved by this record.

[Mesa merge request 45918](https://gitlab.freedesktop.org/mesa/mesa/-/merge_requests/45918) corrects partial LRZ fast-clear coverage on A8xx hardware. Its per-slice coverage and slice-pitch alignment changes are already present in the source released as [DD-Turnip v0.7.0](https://github.com/Droid-Deck/Drivers/releases/tag/DD-Turnip-v0.7.0). Re-porting that change would not alter the reported driver's behavior.

[DXVK issue 6023](https://github.com/doitsujin/dxvk/issues/6023) describes black lighting caused by UNORM flag rounding in a different engine. Without shader or resource evidence for this report, applying that workaround globally would be speculative. Searches across Wine, Proton, DXVK, vkd3d-proton, Mesa, FEX, Box64, Winlator, and public fork history did not locate a verified correction matching the supplied evidence.

## Device comparison

A Retroid Pocket Duo running 0.7.1 PR #2600 build `5cec6b889b9f9f91a7beee7109a8d0d3de4d4a73` and ubuntu-r1-rc5 was used for a bounded comparison. The affected game was not installed. An installed D3D11 comparison title reached a focused window, but its display remained black while the HUD reported 36–37 FPS. Input did not produce a usable scene, so this attempt neither reproduced the outdoor corruption nor exercised a comparable shadow pass. The session was stopped and the application closed afterward.

## Required next experiment

Obtain a runnable affected scene or save and a frame capture from the reported device and build. Record the effective DXVK revision and configuration, driver library hash, and game INI/plugin state.

At the same camera position, first compare GSR enabled and disabled, then LRZ enabled and disabled, changing one variable at a time and preserving explicit user settings. Inspect the shadow-depth attachment immediately after its clear and draw operations, then inspect the first lighting pass that samples it. If depth is correct, compare the lighting G-buffer inputs and output before presentation.

Only the first observed incorrect resource can justify selecting a DXVK format or bias correction, a Turnip LRZ or cache correction, a shader-translation correction, or a presentation correction. Until then, per-game overrides, blanket depth-format forcing, and global driver-feature disabling remain inappropriate.

## Existing validation

Run `python3 -m unittest tools.tests.test_game_environment tools.tests.test_game_gpu_launchers` through the prescribed test wrapper. All 192 existing tests pass in 4.627 seconds, with two subprocess ResourceWarnings and no failures. Those tests validate existing environment composition only; they are not regression coverage for this rendering symptom.
