# Steam game environment

Open **Games → Game environment**, directly below **FEX preset**, in Steam
settings or the in-session drawer. Choose all Proton games, an installed game, or enter a Steam app ID. For a
non-Steam shortcut, use its numeric `compatdata` directory ID (unsigned 32-bit).
The drawer opens the editor on its selected game profile. That editing scope stays
fixed if Steam's selected game changes in the background; use the editor's own
profile picker to switch deliberately.
Changes apply on the next game launch, including when Steam is already running.
A running game must be restarted. Native Linux games and Steam itself are outside
this editor's scope.

Opening **Components** from a game's launch settings selects that game's profile.
Opening Components from the navigation rail selects **Default profile**. Use the
**Configuration** dropdown to switch profiles explicitly. **Default profile** owns the
default Proton and FEX preset. An installed game can inherit those defaults or
choose its own Proton and FEX preset. Proton choices are applied through Steam's
per-app compatibility mapping, including while Steam is running when its live
compatibility helper is attached. A newly installed Proton that the running
client has not discovered yet applies when Steam next starts.

Each game can also override the stored FEX, DXVK and VKD3D-Proton package used
by its selected Proton. The launch wrapper applies that game’s component files
immediately before Proton starts, removes managed files that are absent from the
selection, and restores Default component choices for the next game. **Use
Default** removes the game override. Component packages must first be downloaded
or imported on the Default Components page.

Before publishing game component choices, DroidDeck preserves each Proton build's
original components, including an explicitly empty bundle when a component is not
shipped. Those restore choices remain after the last override is cleared. A missing
or unreadable original is an error, not permission to delete the component's files.
If an original is missing after a build may already have used a game override,
DroidDeck refuses to capture those potentially modified files as originals.

Component selections and the launch table are saved with a recovery record.
Publishing the launch table commits the selection: if the app stops before its
settings file is updated, the next component read recovers the matching settings.
An interruption before publication leaves the previous selections in place.
Unused staged packages are removed only after both files have been saved.

**Reset game profile** removes all overrides owned by the selected game: Proton,
FEX preset, component packages, environment variables, texture filtering and
Windows components. The game then inherits the Default profile again.

The order is inherited process environment, selected FEX preset and built-in
settings, shared edits, then game-specific edits. Editor entries therefore win
over matching Steam launch-option variables. **Remove** explicitly unsets a
variable. **Restore inherited settings** removes the override. The environment
editor's **Clear environment overrides** action clears only the selected
environment entries and is intentionally narrower than **Reset game profile**.
Values are literal strings, with no shell expansion; quotes are only needed when
the consuming program expects them.

## Game debug logs

Under **Setup > Diagnostics**, enable **Session logs** and **Game debug logs**
before reproducing a Windows game problem. Game debug logs is off by default and
is available when Session logs is enabled. It applies to the next game launch through DroidDeck's
Valve or third-party Proton wrapper, including added games and store shortcuts.
A running game must be restarted. If the current session began with Session logs
disabled, restart that session first: its existing log folder is disposable, and
the UI shows a restart reminder instead of enabling logs that would be discarded.

| Generated setting | Effective value while enabled |
| --- | --- |
| `PROTON_LOG` | `1` |
| `PROTON_LOG_DIR`, `PROTON_CRASH_REPORT_DIR`, `DXVK_LOG_PATH` | The actual session log directory |
| `DXVK_LOG_LEVEL` | `debug` |
| `VKD3D_DEBUG` | `info` |
| `WINEDEBUG` | Valid custom channels plus Proton's standard diagnostic channels |

Diagnostics take precedence over conflicting inherited, shared, or per-game log
settings while the switch is on. Wine channel selectors are normalized and
deduplicated with the last selector winning; required exception, module, and
timestamp channels are applied last, including when a profile contains `-all`.
Custom channels from `PROTON_LOG` are retained when valid. Malformed channel tokens
are omitted; an oversized list falls back to the standard diagnostic channels.

Saved profiles and Steam launch options are unchanged. The launcher merges a
process-environment map, so every variable has one value and paths containing
spaces, quotes, or shell characters stay literal. Existing arguments and
`%command%` are not reconstructed. Disabling the switch restores the user's normal
settings on the next launch. It does not change Steam probes or native Linux
programs, whose ordinary output remains in the session log.

After reproducing once, stop the session and use **Latest session logs > Share
logs**. The archive includes the generated Wine/Proton and graphics logs. Debug
logging can reduce performance and consume storage; disable it after collecting
the report. If the session log directory is unavailable, the launcher reports
that diagnostics were skipped instead of writing uncollected files elsewhere.

A Unity game under FEX (`UnityPlayer.dll` for Windows builds under Proton,
`UnityPlayer.so` or `GameAssembly.so` for native Linux builds started by
**Linux x86 (FEX)**) keeps FEX's TSO on when the Default profile's FEX preset
turns it off: without TSO such games can deadlock or crash (seen with Megabonk
and Crab Game). A FEX preset chosen for the game itself still applies.

Games launched through Proton get `vblank_mode=0`, so OpenGL does not wait for
each screen refresh. Wine asks for a swap interval of 1 on every OpenGL window, and WineD3D's front-buffer
drawing (DirectDraw, Direct3D 1–7) never lowers it, so those games ran at the
refresh rate even with no frame limit (AIO Direct3D 7: 144 fps at 144 Hz, about
2,260 with the wait off). Steam's Frame Limit still holds a game: while it is on,
gamescope's WSI layer presents every swapchain in FIFO at the limit. A
`vblank_mode` from the launch options or the environment editor is kept, and
**Remove** in the editor leaves Mesa's own default.

A game launch (`waitforexitandrun`) also gets
`GAMESCOPE_WSI_MIN_IMAGE_COUNT=8` and `GAMESCOPE_WSI_ENSURE_MIN_IMAGE_COUNT=1`,
so an uncapped game keeps drawing while gamescope turns buffers around. The
session itself no longer sets them: the Steam client's UI renders through the
same WSI layer, and with eight images and no frame limit it drew flat out
(about 240 frames a second at 120 Hz) and heated the device. Values set in the
launch options or the environment editor are kept.

## Defaults and available suggestions

The initial configuration enables Mesa shader caching with
`MESA_SHADER_CACHE_DISABLE=false`, sets `VKD3D_FEATURE_LEVEL=12_2` and
`VKD3D_SHADER_MODEL=6_9`, and preserves the selected FEX preset. Existing edits
and explicit removals take precedence over these defaults. FEX's own default
remains the default preset. Shader caching in VKD3D/DXVK, synchronization, ray
tracing and diagnostic logging otherwise retain the runtime's defaults.

On an Adreno 6xx, Turnip does not expose `storageBuffer8BitAccess`, which DXVK 3
requires. A Steam or desktop session installs DXVK 2.7.1 into any Proton whose
DXVK is still the copy that Proton shipped, including after a Proton update put
that copy back. A DXVK package chosen in Components stays for that Proton build.

The variable-name picker offers 24 predefined variables and a Custom entry. Like
WinNative, known variables use toggles, value dropdowns, multi-select lists, or
numeric/text fields. Feature-level and shader-model dropdowns also accept custom
values. Custom variables have editable names and literal values.

| Variable | Use |
| --- | --- |
| `VKD3D_FEATURE_LEVEL` | D3D12 capability override; defaults to `12_2`. |
| `VKD3D_SHADER_MODEL` | Shader-model override; defaults to `6_9`. |
| `VKD3D_CONFIG` | Per-game workarounds such as `nodxr`; not a universal performance preset. |
| `MESA_SHADER_CACHE_MAX_SIZE` | Storage budget for Mesa's shader cache, for example `1G`. |
| `mesa_glthread` | OpenGL threading; test with the affected game. |
| `DXVK_CONFIG` | DXVK configuration options; recent builds accept `dxvk.maxFrameRate = 60`. |
| `VKD3D_FRAME_RATE` | D3D12 frame-rate limit. |
| `DXVK_HUD`, `PROTON_LOG` | Optional diagnostics; leave unset for ordinary play. |
| `PROTON_USE_WINED3D` | D3D9–11 OpenGL fallback for compatibility testing. |
| `PROTON_USE_XALIA` | Proton's gamepad-navigation helper toggle. |

Forcing `12_2` or `6_9` changes reported capabilities; it cannot implement a missing
Vulkan feature. Remove either entry to use automatic detection for that capability.
Other picker suggestions are editable starting values and are enabled only when
added and saved. Older or
custom components can support a different set of options. Android Wine wrapper,
ALSA-server, Box64 and patched async-DXVK options are identified in the editor as
requiring a different/custom component; custom entries remain allowed. The old
`DXVK_FRAME_RATE` environment variable was removed from current DXVK, so it is
not offered as a suggestion. Prefer Steam's Quick Access menu frame limiter for ordinary play.

## Launch path and references

WinNative's Linux session filters Android-only options, merges user variables
over the FEX preset, and passes the result to `env -i` before starting Steam. Its
Windows/Wine graphics helper also maps the selected feature level to
`VKD3D_FEATURE_LEVEL`. Reviewed source: [WinNative Linux session](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/runtime/display/XServerDisplayActivity.java),
[variable editor](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/shared/ui/widget/EnvVarsView.java),
[graphics configuration](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/feature/settings/drivers/DXVKConfigUtils.java).

DroidDeck instead publishes an atomic JSON snapshot at
`/root/.config/droiddeck/game-environment.json`. Both its Valve ARM64 Proton
wrapper and adopted third-party Proton wrappers execute `droiddeck-game-env`,
which reads that snapshot for each real game launch and uses `execvpe` to start
Proton. Probe prefix `compatdata/0` and non-launch verbs are unchanged. Malformed
configuration falls back to the inherited environment without evaluating its
contents. Both component and environment selection normalize signed non-Steam
prefix IDs to unsigned IDs.

The published file also carries generated `dxvkConfig` and `dxvkConfigGames`
values the app's own file never has. The session menu's texture filtering
(`core/TextureFiltering`) becomes `d3d9/d3d11.samplerAnisotropy` and
`samplerLodBias` options. Default choices apply to games without overrides; each
game can save either choice independently or return it to **Use Default**. The
launcher appends the selected options to `DXVK_CONFIG` after the environment
profile, so a user's own `DXVK_CONFIG` entry keeps its options. "Auto" texture
sharpness is `-log2(panel / session)`, derived when the session is sized, and
applies from the next launch.

Upstream references: [VKD3D capability parsing](https://github.com/HansKristian-Work/vkd3d-proton/blob/master/libs/vkd3d/device.c),
[VKD3D options](https://github.com/HansKristian-Work/vkd3d-proton#environment-variables),
[Proton runtime options](https://github.com/ValveSoftware/Proton/tree/proton_11.0#runtime-config-options),
[Mesa variables](https://docs.mesa3d.org/envvars.html),
[DXVK variables](https://github.com/doitsujin/dxvk#environment-variables),
[DXVK frame-limiter changes](https://github.com/doitsujin/dxvk/releases).

Validation: `./gradlew testDebugUnitTest` and
`python3 -m unittest discover -s tools/tests -p test_game_environment.py`.
Actual game compatibility depends on the installed Proton, VKD3D and Vulkan driver;
these checks do not establish that every device supports feature level 12_2 or SM 6_9.
