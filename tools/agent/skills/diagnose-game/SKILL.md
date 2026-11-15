---
name: diagnose-game
description: Find why a game crashed, hung or ran badly in DroidDeck, from session state and logs
---

1. `droiddeckctl state`: `session.phase`, `session.failure`, `session.focus.focusedApp` (769 is
   Steam's own UI) and `session.id`.
2. Read the session's live log folder directly; it is on your filesystem:
   `/data/user/0/com.droiddeck.launcher/files/logs/<session id>/`. `ls -t` there finds earlier
   sessions; the crash may be in the one before a restart. `logs latest` makes a redacted, partial
   copy with renamed files: use it only to hand logs to someone.
   - `games/<time>-<id>-<appid>/`, one folder per launch: `launch.txt` (exe, Proton, environment)
     and `steam-<appid>.log` (Proton and Wine).
   - `session.log` (the session script, `== ` lines), `wayland.log` (compositor), `app.log`,
     `events.jsonl`, `crash.log` (Android's crash buffer at session end), `memory-pressure.log`
     (low-memory kills), `graphics-report.txt`, `vulkan-summary.txt`, `steam/` (client logs).
   - The game's own log is in its prefix,
     `/root/.local/share/Steam/steamapps/compatdata/<appid>/pfx/drive_c/users/steamuser/`:
     Unity writes `AppData/LocalLow/<company>/<game>/Player.log`, Unreal
     `AppData/Local/<game>/Saved/Logs/`.
3. Find the fatal error, not the first `err:`. Every run logs harmless ones (`err:nsi` bind
   errno 13, `err:hid`, `winebth`, `tabtip`, `overriding CPU configuration`), and `trace:seh`
   first-chance exceptions are often handled. Look for the last exception before the process ends:
   `grep -nE "EXCEPTION_ACCESS_VIOLATION|Unhandled exception|virtual_setup_exception|DEVICE_LOST|VK_ERROR" steam-<appid>.log`.
   Same address in every launch means a deterministic fault; compare with an older launch that
   worked before blaming a line.
4. For performance, `droiddeckctl pacing --seconds 20` (`fpsOnScreen`, `offCadencePercent`) and
   `droiddeckctl thermal`: at `SEVERE` or worse the device is throttling, so say that first.
5. Explain the cause in plain words with the lines that show it (file and line number). Propose
   the smallest reversible change and ask before applying it:
   - Proton for one game: list `/root/.local/share/Steam/compatibilitytools.d` and
     `steamapps/common/Proton*`, then
     `droiddeckctl cdp eval 'SteamClient.Apps.SpecifyCompatTool(<appid>, "<tool dir name>")'`;
     check `CompatToolMapping` in `config/config.vdf`.
   - Launch options for one game: read them first,
     `droiddeckctl cdp eval 'appDetailsStore.GetAppDetails(<appid>)?.strLaunchOptions'`, keep
     them, then `SteamClient.Apps.SetAppLaunchOptions(<appid>, "VAR=1 %command%")`.
   - For every game: `droiddeckctl env set KEY=VALUE` applies to the next session only
     (`--persistent` keeps it); restart the session with `droiddeckctl stop` and `start steam`.
6. Relaunch and read the new launch folder before saying it is fixed. If Steam stays on
   "Stopping", `droiddeckctl quit <appid>`, then look for leftover Wine processes with
   `droiddeckctl guest -- sh -c 'ps -eo pid,args | grep -i wine'`; never kill the Steam client.
