# Steam game environment

Open **Games → Game environment**, directly below **FEX preset**, in Steam
settings or the in-session drawer. Choose all Proton games, an installed game, or enter a Steam app ID. For a
non-Steam shortcut, use its numeric `compatdata` directory ID (unsigned 32-bit).
Changes apply on the next game launch, including when Steam is already running.
A running game must be restarted. Native Linux games and Steam itself are outside
this editor's scope.

The order is inherited process environment, selected FEX preset and built-in
settings, shared edits, then game-specific edits. Editor entries therefore win
over matching Steam launch-option variables. **Remove** explicitly unsets a
variable. **Restore inherited settings** removes the override. **Reset this
profile** clears only that profile's overrides. Values are literal strings, with
no shell expansion; quotes are only needed when the consuming program expects them.

## Defaults and available suggestions

The initial configuration enables Mesa shader caching with
`MESA_SHADER_CACHE_DISABLE=false`, sets `VKD3D_FEATURE_LEVEL=12_2` and
`VKD3D_SHADER_MODEL=6_9`, and preserves the selected FEX preset. Existing edits
and explicit removals take precedence over these defaults. FEX's own default
remains the default preset. Shader caching in VKD3D/DXVK, synchronization, ray
tracing and diagnostic logging otherwise retain the runtime's defaults.

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
not offered as a suggestion. Prefer the session frame limiter for ordinary play.

## Launch path and references

WinNative's Linux session filters Android-only options, merges user variables
over the FEX preset, and passes the result to `env -i` before starting Steam. Its
Windows/Wine graphics helper also maps the selected feature level to
`VKD3D_FEATURE_LEVEL`. Reviewed source: [WinNative Linux session](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/runtime/display/XServerDisplayActivity.java),
[variable editor](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/shared/ui/widget/EnvVarsView.java),
[graphics configuration](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/feature/settings/drivers/DXVKConfigUtils.java).

DroidDeck instead publishes an atomic JSON snapshot at
`/root/.config/droiddeck/game-environment.json`. Both its Valve ARM64 Proton
wrapper and adopted third-party Proton wrappers execute `bannerlator-game-env`,
which reads that snapshot for each real game launch and uses `execvpe` to start
Proton. Probe prefix `compatdata/0` and non-launch verbs are unchanged. Malformed
configuration falls back to the inherited environment without evaluating its
contents. Signed non-Steam prefix IDs are normalized to unsigned IDs.

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
