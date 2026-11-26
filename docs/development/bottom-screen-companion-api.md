# Bottom screen: companion API for PC games

Part of the [bottom screen research](bottom-screen.md). Researched 2026-11-26 against `main` at `d788afc12`. Not implemented.

Let games and mods running in the Linux and Proton session drive pages on DroidDeck's bottom screen: live state (health, money, map position, inventory, quests) and touch commands back into the game.

## Use cases

- **RPGs**: a Pip-Boy for Fallout 4, inventory and quests for Skyrim and Fallout: New Vegas, equipment swaps by touch.
- **Sims and management**: Stardew Valley's day, bag, bundles and calendar; RimWorld colonists and alerts; Project Zomboid's status.
- **Open world**: GTA's radio, weapons, wanted level and GPS-to-blip.
- **Racing and flight**: the telemetry dashboards DroidDeck already has.
- **Speedrunning**: LiveSplit splits for any game with an auto-splitter.

## Companions people already use

Several Thor companions already run against PC games in Wine, and two document DroidDeck as a supported launcher. Each is a separate Android app paired with a game-side mod that serves state on loopback.

| Companion | Game-side piece | Transport |
| --- | --- | --- |
| [Thor Pip-Boy](https://github.com/harmssi/thor-pipboy) (Fallout 4, **targets DroidDeck**) | F4SE plugin | WebSocket on `127.0.0.1:8767`, protocol copied from SkyrimWebSocket; commands `toggle`, `equip`, `use`, `radio`; raw access to the game's Pip-Boy data |
| [Grand Theft Companion](https://github.com/BrianJr03/GrandTheftCompanion) (GTA IV and V, **documents DroidDeck setup**) | `.asi` via Ultimate ASI Loader (IV) or ScriptHookV (V); needs `WINEDLLOVERRIDES=dinput8=n,b` in Steam launch options | JSON on `127.0.0.1:47801` at 20 Hz with a hello frame; radio, weapon, GPS and cheat commands. Its map page is a crop of the minimap |
| [AYN Thor dual-screen mods](https://github.com/JoeCorrell/AYN-Thor-Dualscreen-Mods) (Stardew Valley, for WEMU) | SMAPI mod | Hand-written RFC 6455 WebSocket on `127.0.0.1:7786` (".NET's WebSocket server wraps Windows components Wine does not implement"). No auth. Replays the latest message of each kind on connect, computes nothing without a client, hides the game HUD while one is attached. Sprites sent once as base64 PNG with nine-patch insets. Commands: `select_slot`, `move_item`, `eat_slot`, `sort_bag`, `shift_toolbar`, `craft_recipe` and others. Optional UDP beacon on 7787. The [Barry client](https://github.com/project-barry/stardew-dual-screen/blob/main/docs/protocol.md) documents the schema and ships a mock server |
| [SkyrimWebSocket](https://github.com/andreyvelsk/SkyrimWebSocket) + SkyrimWebMonitor | SKSE plugin | WebSocket on `127.0.0.1:8765`. Client-driven `subscribe` with frequency, send-on-change and aliased fields; `query`, `heartbeat`, `command` → `commandResult` ([protocol](https://github.com/andreyvelsk/SkyrimWebSocket/blob/main/PROTOCOL.md)). Client is a web app |
| [SkyrimSE-DualScreen](https://github.com/Plus-Cloud/SkyrimSE-DualScreen) | `SkyrimAYN.dll` | A 384 KiB memory-mapped file with a seqlock header ([layout](https://github.com/Plus-Cloud/SkyrimSE-DualScreen/blob/main/native/shared-memory.md)). Only works with its own bundled Wine |
| FNVDS (Fallout: New Vegas) | xNVSE plugin | TCP on `127.0.0.1:47888` |
| FFXII Companion | LUA Script Loader script reading memory, gated on the game build | JSON files in shared storage; commands as request files |
| [ZomboidDS](https://github.com/Space001000/ZomboidDS) (Project Zomboid on a Java port) | Lua and Java mod | WebSocket `ws://127.0.0.1:7786/ws` (**the same port as the Stardew mod**), envelope `{v,type,seq,data}`, hello first, full-state topics replayed on connect, `command_result`, `capabilities[]`, icons over HTTP ([protocol](https://github.com/Space001000/ZomboidDS/blob/main/protocol/PROTOCOL.md)) |

Lessons:

- Every project invents its own port and schema; two already collide on 7786.
- Each needs its own Android app on the bottom screen. Thor Pip-Boy warns that bringing it to the front "lowers DroidDeck's priority and could get the game killed" while Fallout 4 is loading. Pages drawn inside DroidDeck's own bottom-screen Presentation avoid that.
- The good protocols share a shape: hello with capabilities, complete state per topic replayed on connect, commands with ids and results, assets sent once and cached.
- Mods should do no work while nobody is watching (the Stardew mod and SkyrimWebSocket both do this).

## Game-side APIs a companion could consume

| Source | Protocol | Works in Proton on ARM? |
| --- | --- | --- |
| SKSE, F4SE, xNVSE plugins | Any socket from a Windows DLL | Yes, proven by the companions above |
| ScriptHookV / ASI plugins | Any socket | Yes, proven |
| [RIMAPI](https://github.com/IlyaChichkov/RIMAPI) (RimWorld) | REST and SSE on `localhost:8765`, 120+ endpoints, extensible by other mods | Likely (Unity, Mono) |
| [Hollow Knight tracker](https://github.com/kingkiller39/HollowKnightRandomizerTracker2.8) | WebSocket on `localhost:11420` | Likely |
| [Ficsit Remote Monitoring](https://docs.ficsit.app/ficsitremotemonitoring/latest/webserver.html) (Satisfactory) | HTTP and WebSocket on 8080 | The game is heavy for ARM |
| [Valheim WebMap](https://github.com/kylepaulsen/ValheimWebMap) | BepInEx, HTTP and WebSocket on 3000 | Server-side mod |
| Cyberpunk 2077 | CET plus RedSocket, used by [Crowd Control](https://crowdcontrol.live/guides/cyberpunk2077/) | Unknown; heavy |
| Fallout 4 Pip-Boy app protocol | UDP 28000 discovery, binary stream on TCP 27000 ([reverse-engineered](https://github.com/luckydonald/JavaPipBoyServer/blob/master/PROTOCOL.md)) | Broken in the current game build per Thor Pip-Boy |
| CS2 / Dota 2 Game State Integration | The game POSTs JSON to a URI declared in a cfg file | Protocol is a good model; the games are unrealistic on ARM |
| ETS2 / ATS telemetry SDK | Shared memory; needs an in-Wine relay such as [Funbit's server](https://github.com/Funbit/ets2-telemetry-server) | With a relay |
| [LiveSplit server](https://github.com/LiveSplit/LiveSplit.Server) | Text commands on TCP 16834 | Yes |
| Discord Rich Presence | Named pipe `\\.\pipe\discord-ipc-0` with JSON frames | A Wine-side pipe sink would give presence data for any game that integrates Discord |
| Racing UDP, War Thunder, Elite Dangerous | Already consumed by DroidDeck (below) | Yes |

Generic memory readers that publish JSON are another model: [tosu](https://github.com/KotRikD/tosu) serves osu! state on a WebSocket, and LiveSplit's ASL and [WASM auto-splitters](https://github.com/alexknauth/hollowknight-autosplit-wasm) are per-game pointer descriptions that a Rust runtime (livesplit-core) evaluates.

## What DroidDeck already has

Paths are under [`app/src/main/java/com/droiddeck/launcher/`](../../app/src/main/java/com/droiddeck/launcher/).

- **Shared loopback.** proot creates no network namespace and the bwrap shim ignores `--unshare-net`, so the guest's `127.0.0.1` is the app's `127.0.0.1` ([`core/NetworkReport.kt`](../../app/src/main/java/com/droiddeck/launcher/core/NetworkReport.kt), `tools/linuxfs/overlay/usr/local/bin/droiddeck-bwrap`). A Windows game in Proton can connect to a socket the app opens, and the app can connect to a socket a mod opens.
- **Telemetry template.** [`session/GameTelemetry.kt`](../../app/src/main/java/com/droiddeck/launcher/session/GameTelemetry.kt) parses Codemasters (UDP 20777), Forza (5300) and OutGauge (4444) packets, War Thunder's `http://127.0.0.1:8111/state` and Elite Dangerous's `Status.json` read straight from the Proton prefix. [`ui/BottomTelemetryPage.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/BottomTelemetryPage.kt) runs one listener per source, marks data stale after 3 s, reports busy ports and unknown formats, and draws dashboards. [`session/TelemetryOutput.kt`](../../app/src/main/java/com/droiddeck/launcher/session/TelemetryOutput.kt) edits a game's config in its prefix (with a backup) to turn telemetry on. This is the first version of the companion API: built-in adapters for known games.
- **Agent channel.** `droiddeck-agent` in the guest and [`agent/AgentGuest.kt`](../../app/src/main/java/com/droiddeck/launcher/agent/AgentGuest.kt) talk through request and response files in a shared directory, plus watched state files (`focus.json` and others). `steamEval` reaches Steam's UI over the DevTools port.
- **Installing mods.** [`session/WinComponents.kt`](../../app/src/main/java/com/droiddeck/launcher/session/WinComponents.kt) installs per-game components from the [DroidDeck-Components](https://github.com/Droid-Deck/DroidDeck-Components) catalog: download, extract, copy DLLs and files, DLL overrides, registration. That is exactly what SKSE, F4SE, ScriptHookV, ASI Loader and BepInEx need. Per-game environment overrides ([`session/GameEnvironmentStore.kt`](../../app/src/main/java/com/droiddeck/launcher/session/GameEnvironmentStore.kt)) reach the game through the `steam-compatibility` wrapper, including `WINEDLLOVERRIDES`. There is no SMAPI or BepInEx installer yet.
- **A second guest on the bottom screen.** The bottom KDE desktop is a second compositor in its own process with its own `/run/droiddeck`, so the main guest cannot see its Wayland socket. A debug-gated run queue in [`droiddeck-desktop`](../../tools/linuxfs/desktop/droiddeck-desktop) runs scripts there with `WAYLAND_DISPLAY=wayland-1 DISPLAY=:1`, which is a seed for a "run on bottom" command.

## Proposed design

### Phase 1: adapters for existing companions

DroidDeck connects as a client to the servers mods already open and draws their pages natively in the bottom-screen Presentation:

- Stardew (port 7786, WEMU protocol), Skyrim and Fallout 4 (SkyrimWebSocket protocol on 8765 and Thor Pip-Boy on 8767), GTA (47801), RIMAPI, the Hollow Knight tracker, LiveSplit.
- Probe only the ports known for the focused app id, so a busy port never confuses two games.
- Offer the mod itself through the components catalog, so setup is one toggle in the game's settings.

This gives working showcases without writing any mods, and a reason to define the shared state model first.

### Phase 2: DroidDeck's own protocol

For new mods, DroidDeck is the server and mods are clients:

- **Transport.** WebSocket at `ws://127.0.0.1:<port>/companion/v1`, and newline-delimited JSON over raw TCP on the same port (detected from the first bytes). Clients are trivial under Wine; servers inside Wine are not (the Stardew mod had to hand-write one). Several mods or games can attach at once.
- **Discovery.** DroidDeck sets `DROIDDECK_COMPANION_URL`, `DROIDDECK_COMPANION_TOKEN` (random per launch) and `DROIDDECK_APPID` in the game's environment through the existing game-environment path, and writes the same values to a known file in the prefix for processes that lose the environment.
- **Security.** Any Android app can reach loopback, so the token is required in `hello` within about 2 s or the connection closes. No remote bind. Commands that change the game need a per-mod user toggle. Limits on frame and asset sizes.
- **Envelope.** `{"v":1,"type":…,"seq":n,…}`.

| Direction | Message | Purpose |
| --- | --- | --- |
| game → DroidDeck | `hello{token, game{appid, build}, mod{id, version}, capabilities[]}` | Identify and authenticate |
| | `declare{states{key: {type, unit, min, max}}}` | Describe the state keys the mod publishes |
| | `state{topic, data}` | Complete value of a topic, never a diff; DroidDeck keeps the latest and replays it when a page opens |
| | `asset{id: sha256, mime, nine_patch, filter}` + binary frame | Art sent once and cached across sessions by content hash |
| | `event{toast \| haptic \| sound}` | Notifications |
| | `result{id, ok, error}` | Command outcome |
| DroidDeck → game | `welcome{session, screen{w, h}}` | Accept the connection |
| | `visibility{visible, topics[], hz}` | Tell the mod what is on screen so it computes nothing else |
| | `command{id, name, args}` | Touch actions |
| | `input{button}` | Pass L/R or similar when a page claims them |
| | `ping` | Liveness |

A small C header and a C# client file would let SKSE, F4SE, ASI and BepInEx/SMAPI authors adopt it in an afternoon.

### Phase 3: pages

- **Declarative pages** in a `.ddpage` zip (`manifest.json` plus assets), modelled on Eden Duo's [package format](https://github.com/igawa6/eden-duo-companions/blob/main/docs/PACKAGE_FORMAT.md) but drawn with DroidDeck's own components:
  - `pages[]` of widgets (`label`, `value`, `bar`, `grid`, `list`, `image`, `map`, `button`) bound to state keys, with `repeat` over arrays;
  - actions `command`, `page` and `input` on tap, hold and swipe;
  - a settings page backed by the mod's options;
  - host values (`@device.*`, `@perf.*`) so pages can mix game and device data.
- **Normalized core keys**, the lesson from SimHub, whose dashboards work across many games because readers map them onto one property tree: `player.health`, `player.money`, `location.name`, `map.{x,y,heading}`, `inventory[]`, `quests[]`, `game.time`. A generic RPG page then works for any mod that provides them.
- **WebView escape hatch** for richer pages, in the style of EmuLnk's [theme API](https://github.com/EmuLnk/emulnk-repo/wiki/Theme-API): `window.droiddeck.onState` and `.send`. This also hosts existing web clients like SkyrimWebMonitor unchanged.
- Pages ship inside the mod, or from a catalog repository like the components catalog.

### Phase 4: run on bottom

A `droiddeck-run-bottom -- <cmd>` command, like Armada's `armada-run-bottom`, that opens a guest program on the bottom screen with touch. Kanto Gear's separate-window mode and second-monitor mods would then work as they are. Unity games could render to `Display.displays[1]` (the Silksong Android approach) if the bottom panel is exposed as a second output to the guest. Builds on the bottom desktop's run queue, or on binding its runtime directory into the main guest.

### Phase 5: memory profiles (only if demand)

Per-game JSON pointer profiles, gated on the Steam build id, evaluated on the Android side against the game process, in the style of Eden Duo or LiveSplit's auto-splitters. Strict version gating with a "needs build X" page, as Eden Duo does. Highest maintenance cost; only for popular games without mod support.

## Open questions

- Whether a single port and token for every game is enough, or each session gets its own port.
- How pages and mods are versioned together, and what happens when a mod is newer than the page.
- Which component installs belong in the catalog (SKSE, F4SE, ScriptHookV, ASI Loader, SMAPI, BepInEx) and how they interact with Steam updates of the game.
- Asking the authors of Thor Pip-Boy, Grand Theft Companion and the Stardew mod whether they would also speak DroidDeck's protocol.
- Touch commands that change game state while the game is paused or loading.

## Milestones

1. Stardew adapter (port 7786) with a native page, and the SMAPI mod offered through components.
2. Skyrim and Fallout 4 adapters (SkyrimWebSocket protocol), GTA adapter.
3. DroidDeck protocol server, C and C# clients, `.ddpage` renderer, normalized keys.
4. Run on bottom.
5. Memory profiles.
