# Agent control

`tools/droiddeckctl` drives DroidDeck on a real device over ADB, for agents and scripts that test
the app end to end: start and stop sessions, launch and quit Steam games, wait for a game to have
focus, send input, inspect the app's UI and gamescope's windows, run commands inside the session,
and collect logs, screenshots and recordings. `tools/droiddeck-scenario` repeats a scripted test
and reports every run.

## How it works

- **The provider** `content://com.droiddeck.launcher.agent` (`agent/AgentBridgeProvider.kt`)
  answers each command with JSON. It and the session-start Activity (`AgentStartActivity`) are in
  every build and require `android.permission.DUMP`, which only the ADB shell and the system hold.
- **The guest agent** `droiddeck-agent` runs inside every session (started by
  `droiddeck-session`). It runs commands with the session's own environment, publishes gamescope's
  focus, and talks to the Steam client's DevTools, which only accepts guest processes. The app and
  the agent exchange request and answer files under the session root (`files/session/agent`).
- **The UI dump** reads the app's Compose semantics from inside the app (`agent/AgentUi.kt`), so it
  works on every display and window. UI Automator, by contrast, dumps whichever window has focus,
  which on a dual-screen device is often another app's.

## Access

Commands that only observe or act the way a player does work over ADB on any build: `state`,
`start`, `run`, `stop`, `resume`, `wait`, `launch`, `quit`, `focus`, `input`, `ui`, `logs`,
`screenshot`, `record`, `displays`, `wake`, `access`, and `env list`.

**Agent commands** reach into the app's sandbox, which holds the Steam login in the guest: `guest`,
`cdp`, `env set`, `override` and `prefs`. Debug builds allow them. A release build allows them only
after **Setup > Session > Agent commands** is turned on; the bridge refuses to flip it (`ui click`
on it answers `PROTECTED_CONTROL`, `prefs` on its file `PROTECTED_PREFS`). Refusals exit 7 with
`AGENT_COMMANDS_DISABLED`. `droiddeckctl access` reports the current setting.

The toggle keeps an agent from reaching into the sandbox by accident, and keeps a release build
from doing so until someone chose it. It is not a wall against whoever holds the ADB connection:
`adb shell input tap` can press any switch on the screen. ADB access itself is the trust boundary,
as it is for everything else on the device.

## Device selection and output

The CLI resolves one authorized device using `ADB_SERIAL` or `ANDROID_SERIAL` when set. Otherwise it
de-duplicates transports that report the same device serial and asks for an explicit serial if
multiple devices remain. Pass `--serial` before the command to choose directly; set `ADB` (or pass
`--adb`) to choose an adb executable. `tools/deploy_local.sh` uses the same resolver.

Every command writes one JSON object to stdout and its resolved serial to stderr. Exit codes: 0
success, 2 invalid or rejected command, 3 ADB or device error, 4 session error, 5 timeout, 6
artifact or file error, 7 agent commands disabled.

## Commands

```sh
tools/droiddeckctl state                         # schema 2, below
tools/droiddeckctl start steam --wait            # --ui desktop, --url steam://..., --timeout
tools/droiddeckctl start desktop
tools/droiddeckctl run /usr/bin/foo -- arg1 arg2
tools/droiddeckctl wait ready|idle|failed        # --timeout (default 90 s)
tools/droiddeckctl stop
tools/droiddeckctl resume

tools/droiddeckctl launch 489830 --wait          # Steam game by app id; --wait = until it has focus
tools/droiddeckctl wait focused 489830           # gamescope focuses it AND Steam lists it focusable
tools/droiddeckctl wait game                     # any app but the Steam client (769) has focus
tools/droiddeckctl quit 489830                   # Steam's TerminateApp, then SIGTERM/SIGKILL
tools/droiddeckctl focus                         # focus properties and every Xwayland server's windows

tools/droiddeckctl input key ENTER               # names (ESC, F1, UP, A...) or evdev codes; --mod CTRL
tools/droiddeckctl input text 'hello world'      # as US-layout keys; --commit for text-input
tools/droiddeckctl input tap 640 360             # session output pixels; --space fraction for 0..1
tools/droiddeckctl input swipe 100 600 100 100 --duration 400
tools/droiddeckctl input pad a                   # a b x y lb rb start select guide qam up down left right
tools/droiddeckctl input stick left 0 -1 --hold 500
tools/droiddeckctl input trigger right 1.0

tools/droiddeckctl ui dump                       # tagged, labelled and clickable nodes; --all for every node
tools/droiddeckctl ui click rail-steam           # by test tag
tools/droiddeckctl ui click --match "Play"       # by text or description; --index for the nth match
tools/droiddeckctl ui set-text some-field --text abc

tools/droiddeckctl guest -- xprop -root GAMESCOPE_FOCUSED_APP     # --timeout --env K=V --cwd --stdin --check
tools/droiddeckctl cdp targets
tools/droiddeckctl cdp eval 'SteamClient.Apps.GetMyRunningApps?.()'  # --target, --no-await
tools/droiddeckctl env set DXVK_HUD=fps          # next session only; --persistent until cleared
tools/droiddeckctl env list
tools/droiddeckctl env clear [next|persistent|all]
tools/droiddeckctl override gamescope ./gamescope   # first on the next session's PATH, removed after it
tools/droiddeckctl prefs get session
tools/droiddeckctl prefs set session some_key true --type bool   # returns the previous value

tools/droiddeckctl logs latest ./session-artifacts
tools/droiddeckctl screenshot ./screen.png --display 4
tools/droiddeckctl record ./clip.mp4 --seconds 8 --display 0
tools/droiddeckctl displays                      # logical ids, names, sizes, capture ids
tools/droiddeckctl wake                          # wake the screen, dismiss the keyguard
```

A sleeping device suspends the session and hides the app's windows from `ui dump`; `wake` first.

`input` needs a session that has drawn its first frame. Pad input goes through the session screen's
virtual pad, the same one the on-screen controls drive, so games that ignore touch still respond.
`launch`, `quit` and `cdp` need a Steam session; `focus` and `wait focused` need gamescope (every
mode except the desktop).

## State (schema 2)

`state` returns `schema`, `build`, `appVersion`, `runtime {installed, version}`,
`agent {guest, guestFocus, commands}` and `session`:

- `id`, `phase`, `running`, `mode`, `program`, `steamUi`, `steamUrl`, `suspended`, `pip`,
  `firstFrame`, `output` [w, h], `refreshHz`, `lastTransitionAt`, `guestPid`, `installing`
- `logDir`, `eventsFile`, `artifactsAvailable`, `artifactsComplete`, `failure {code, message, status}`
- `focus`: gamescope's focus as the guest agent last published it, or null:
  `focusedApp`, `focusedWindow`, `focusableApps`, `baselayerAppIds`, `seq`, `t`.

Phases: `IDLE`, `PREPARING`, `INSTALLING_RUNTIME`, `STARTING_COMPOSITOR`, `STARTING_GUEST`,
`STARTING_STEAM`, `READY`, `SUSPENDED`, `STOPPING`, `FAILED`. Schema 2 only added `agent` and
`session.focus`; every schema 1 field is unchanged.

A game the player can see is `focusedApp == appId` **and** `appId in focusableApps`.
`GAMESCOPE_FOCUSABLE_WINDOWS` is what the Steam client orders the base layer from: a game missing
there stays behind the client's loading screen however healthy its window looks
(docs/development/steam-focus-debugging.md). `focus` returns the full snapshot, including each
server's windows with map state, override-redirect, app id, `WM_STATE`, pid, class, opacity and
`_NET_WM_STATE`.

## Events

Each session writes `events.jsonl` beside its other artifacts: the lifecycle (`session.created`,
`compositor.started`, `guest.exited`, ...) plus `agent.ready` (the guest agent is up),
`focus.changed` (the focused app, focusable apps or base-layer order changed) and
`agent.launch_requested`.

## UI test tags

Compose test tags are exposed as resource ids (`ui/TestTags.kt`), so both `ui dump` and UI
Automator show them:

| Tag | Where |
|---|---|
| `rail-steam`, `rail-games`, `rail-desktop`, `rail-store`, `rail-components`, `rail-android-apps`, `rail-setup`, `rail-updates` | the front end's rail |
| `play-steam` | Play on the Steam and Games pages |
| `setting-<key>` | every toggle and choice row, by its key (`setting-logs`, `setting-agent-commands`, ...) |
| `tab-<n>` | a page's tab strip (Setup's Overview, Controller, Session, Launcher...), by position |
| `drawer-tab-controller`, `-second_screen`, `-display`, `-effects`, `-games`, `-session` | the session drawer's pages |
| `drawer-stop`, `drawer-stop-confirm`, `drawer-stop-cancel` | stopping a session from the drawer |

## Scenarios

`tools/droiddeck-scenario` runs a JSON scenario: `setup` once, `steps` `repeat` times, `teardown`
once. A list step is a droiddeckctl command line; `{"assert": "dotted.path", "equals"|"in"|
"contains"|"not"|"exists": ...}` checks the last answer; `{"sleep"}`, `{"screenshot"}` and
`{"record"}` do what they say. A failing run keeps a screenshot, the focus snapshot and the
session's logs in its folder, and `report.json` sums up the runs.

```sh
tools/droiddeck-scenario tools/scenarios/steam-game-focus.json --var appId=489830 --repeat 10 --keep-going
```

`tools/scenarios/steam-game-focus.json` launches a game, checks it has focus the way a player
sees it, and quits it: the test that catches a game left behind Steam's loading screen, which only
shows up in some fraction of launches. A game with a launcher of its own needs the launcher pressed
through first, since the launcher window carries the game's app id too:
`tools/scenarios/skyrim-launcher-focus.json` taps Skyrim's Play before checking.
