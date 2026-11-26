# Bottom screen: research and proposals

Researched 2026-11-26 against `main` at `d788afc12` (0.7.11). Implementation is not scheduled yet. This document records what other dual-screen Android projects put on the second screen, how they get their data, where DroidDeck stands, and the features we want to build.

The starting point was WULFF DEN's survey video [Dual screen Android apps are getting crazy](https://youtu.be/dAiuG2A-wkc) (2026-11-26). It covers the AYN Thor and Retroid Pocket Duo, and most of its apps come from the [GAFT list](https://andreyvelsk.github.io/GAFT) of Thor apps.

## DroidDeck in the video

DroidDeck appears from 17:47 to 18:30 on a Thor: the Steam gamepad UI on top, our bottom panel below.

- Shown: the mode picker (DroidDeck menu, Keyboard + trackpad, Terminal, Deck grips + trackpads, Profile controls, Dashboard), the controller page of the docked menu, and the Dashboard's In-game stats card with FPS, 1%/0.1%/0.01% lows and a frame-time graph while Ball x Pit runs.
- Verdict: it fills the bottom screen "with useful things like a keyboard and a trackpad" or game information, and makes the device feel "a lot more like having a dual screen Steam Deck".
- Problem: it "froze on install a handful of times" and needed three device restarts. The build was probably 0.7.1 or earlier. Two first-run hang fixes landed later: #3070 (FEX/AppImage extraction deadlock in the clock-pause preload) and #3212 (first-run setup crashing in the background). Whether they cover this report is unconfirmed; slow exFAT preallocation on the Thor's SD card ([thor-sd-storage-investigation.md](thor-sd-storage-investigation.md)) can also look like a freeze.
- Problem: with no game running, the stats card reads "59.6 fps · Steam Client ARM". It measures the Steam client itself, which tells the user nothing.

## What other projects do

### System companions and utilities

| Project | Bottom screen | Data source |
| --- | --- | --- |
| [ArmadaLive](https://github.com/FabianZettl/armadalive) (Armada OS, SteamOS-like Linux on the Thor) | Follows the top screen: logo, background, info, PDF manual, cheats and play time of the current game. Launcher with user and smart folders (recent, favourites, per system), random pick, Add to Steam. System page with FPS and a 60/120 Hz switch, rearrangeable quick-setting tiles (power profile, fan curve, stick LEDs, per-screen brightness, screen off), gauges. Rules (power profile, FPS limit, emulator resolution per game, per system, idle, or base). Tuning (governor, GPU limits, fan curve editor) that reverts after 15 s unless kept. Graphs, task manager, play-time history, trackpads, back buttons as a second controller. Dims after 10 s; hold Start+Select to close a game; AYN button shows or hides it, hold turns the screen off. 55 themes, first-run permission walkthrough, health check, verified self-update | ES-DE custom event scripts writing a state file, emulator process scan matched to ROMs, gamescope focus and stats pipe, the Big Picture selection read over Steam's CEF debug port, ES-DE scraped media and Steam's library cache |
| [Armada OS](https://github.com/armada-os/armada) | Little in game mode: the bottom screen sleeps with the top. An experimental Plasma Mobile session, and `armada-run-bottom -- <cmd>` to run single apps on a second gamescope on the bottom panel | n/a |
| [Thor Command Panel](https://github.com/BrianJr03/command-panel) | Replaces the stock dual-screen assistant: screen modes with real blanking, per-screen brightness and volume, 60/120 Hz with FPS, tiles, telemetry, themes, drag-to-arrange cards, replay buffer of either or both screens, full gamepad navigation | System APIs |
| [Better Dual Screen Assistant](https://github.com/stormpanda/better-dual-screen-assistant) (Thor and Duo) | One-tap swap of the foreground apps between screens, linked brightness and volume, FPS from real frame delivery, paged tiles switched with L1/R1, recording, touch lock, mouse mode, key mapper, works before unlock | System APIs |
| [Wayfinder](https://github.com/Thor-Wayfinder/thor-wayfinder) | Move or swap live apps between screens, button combos with a cheat sheet, per-app profiles (screen, performance, fan, refresh rate, lights, controls), keyboard and trackpad on the other screen | System APIs |
| [Megingiard](https://github.com/stormpanda/megingiard) | Mirrors up to 10 cropped regions of the top screen (minimap, HUD) with pinch zoom; per-app macro pads; split keyboard on text focus; trackpad | Screen capture |
| [WalkThor](https://github.com/nerf-herder-29/WalkThor) | Now Playing art, the guide at your last page, up to 4 mirrored top-screen regions remembered per game, console-style face-button labels, FPS graph, learned per-game battery runtime, offline captions of game audio. Hands focus back to the game | Cocoon and ES-DE log files, screen capture |
| [Heimdall](https://github.com/mastercook777/Heimdall-AYN-Thor-Assistant) | Per-game module grid, macros, precision aim, virtual mouse and stick, "portals" (live top-screen regions), OCR translation | Screen capture |
| [ThorSport](https://github.com/CHINW3/ThorSport) | Live scoreboards for 15 leagues, bottom-only mode so a game can run on top, spoiler delay synced to a stream | Sports APIs |
| AYANEO Flip DS (Windows, AYASpace) | Performance stats, quick settings, app shortcuts, moving any window between screens | Windows |

### Guides

| Project | Bottom screen | Data source |
| --- | --- | --- |
| [Game Twin](https://github.com/alpha-minus/game-twin) | Per-game vault of guides (GameFAQs, StrategyWiki, IGN) saved offline, VGMaps maps with markers, RetroAchievements and Steam achievements including missables, checklists. Switches workspace with the game and reopens where you left off. Optional Gemini help with a screenshot of the top screen | Polls Cocoon `launch_debug.log`, ES-DE `es_log.txt`, RetroArch `content_history.lpl`, and the Steam Web API for recently played |
| [Pixel Guide](https://github.com/rexmont/Pixel-Guide-Android) | Offline guides, maps, manuals, RetroAchievements, AI help | Games are added by hand |

### Game companions

| Project | Bottom screen | How it gets live state |
| --- | --- | --- |
| [Eden Duo](https://github.com/igawa6/eden-duo) (Switch) | Interactive per-game pages (Stats, Map, Tasks); can use items and change equipment | Per-game `.dsmod.zip` packages from a [catalog repo](https://github.com/igawa6/eden-duo-companions): `package.json` with build IDs and per-ABI native modules, a declarative UI manifest (pages, widgets, actions, `poll_hz`, art loaded from the game's own romfs), and pointer chains per build. A small C ABI gives modules `read_memory`/`write_memory`/`publish_*`. Unity IL2CPP members are resolved by name. Unknown builds show a "needs version X" page |
| [GBA DS](https://github.com/kyannon/gba-ds) | Map or HUD for 77 GBA games. Per-game screen sizing, placement, generated overlays and per-screen brightness | A second silent emulator instance receives the live state, opens the map and captures it, confirmed by RAM flags and pixel probes. Some games render twice, with and without the HUD |
| [WiiDS](https://github.com/Maddex712/WiiDS) | Course maps, standings, a rotating Galaxy planet with a touch cursor | Native recompilations, so state is in-process |
| [Silksong Android](https://github.com/jakobkhansen/SilksongAndroid) | Interactive map, inventory and pause menu that look like part of the game | C# patches compiled on-device against the game's assemblies, drawn by a Unity camera on `Display.displays[1]`. Downloads the Linux depot with JavaSteam and syncs Steam Cloud saves |
| [AYN Thor dual-screen mods](https://github.com/JoeCorrell/AYN-Thor-Dualscreen-Mods) (Stardew Valley) | Today, Map, Farm, Journal, Bundles, Calendar; touch actions | A SMAPI mod inside a Steam container serves a WebSocket on `127.0.0.1:7786`; a separate APK draws the pages. A [second client](https://github.com/project-barry/stardew-dual-screen) speaks the same protocol |
| [ShulkDS](https://github.com/NaiSenshin/ShulkDS) (Minecraft Java) | Minimap, touch inventory, options | Its own client reimplementation |
| [TotK Native](https://github.com/AurelioB/totk-android) | Touch equip and eat actions | Guarded reads of native memory into a JSON snapshot polled by a Java UI |
| [Kanto Gear](https://github.com/AverageConsumer/kanto-gear) ([Gen 1 recomp](https://github.com/bryanthaboi/gen1recomp)) | Companion panel, as a combined window, a separate process, or a phone/browser over Wi-Fi | In-process; [KantoGearBottom](https://github.com/venatrix-ritz/KantoGearBottom) puts the separate window on Armada's bottom gamescope |
| [EmuLnk](https://github.com/EmuLnk/emulnk) | HTML/JS dashboards per game that can also write memory or run macros | Emulator forks send memory over UDP; profiles are JSON memory points from a [content repo](https://github.com/EmuLnk/emulnk-repo) |

Other GAFT companions: Thor Pip-Boy, FNVDS, Grand Theft Companion, FFXII, Terraria, ZomboidDS, Skyrim, Delphis (live Dolphin button guide), PokeMMO Companion, Thor-SpeedrunSplits, DrillPod.

### Patterns that recur

- The bottom screen follows the game on top without the user telling it which game.
- Everything is remembered per game: panel, guide page, mirrored regions, power and FPS rules, control profiles.
- One hardware button shows or hides the panel; holding it switches the bottom screen fully off, not just black.
- Per-screen brightness, a 60/120 Hz switch and an honest FPS readout.
- Live crops of the minimap or HUD.
- Auto-dim of an idle OLED panel.
- The panel never takes focus or priority from the game.

## DroidDeck today

Paths are under [`app/src/main/java/com/droiddeck/launcher/`](../../app/src/main/java/com/droiddeck/launcher/).

**Panels.** [`input/SecondScreenMode.kt`](../../app/src/main/java/com/droiddeck/launcher/input/SecondScreenMode.kt) lists the modes: menu, keyboard + trackpad, terminal, Deck grips + trackpads, profile controls, dashboard and the KDE desktop. [`ui/SecondScreenPresentation.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/SecondScreenPresentation.kt) hosts them in a `Presentation`, with the bar in [`ui/BottomScreenBar.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/BottomScreenBar.kt). The last mode is remembered once for game sessions and once for desktop sessions (`SessionPrefs.secondScreenPanel`), not per game. Outside a session the launcher shows [`ui/DualScreenPanel.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/DualScreenPanel.kt).

**Dashboard.** [`ui/BottomDashboard.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/BottomDashboard.kt) has In-game stats, Downloads, Screenshots and Chat & voice. The tab is remembered per session and jumps to stats when a game starts. A strip of metrics ([`ui/BottomStrip.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/BottomStrip.kt)) can sit on the other tabs.

**Displays.** [`input/DualScreen.kt`](../../app/src/main/java/com/droiddeck/launcher/input/DualScreen.kt) finds built-in presentation displays with their own touch panel. Sessions support Auto, On, Swapped (game on the bottom, [`ui/BottomGameScreen.kt`](../../app/src/main/java/com/droiddeck/launcher/ui/BottomGameScreen.kt)) and Off. The game is rendered by our compositor into a `SurfaceView` we own, not by a separate activity. [`input/BottomScreenGuests.kt`](../../app/src/main/java/com/droiddeck/launcher/input/BottomScreenGuests.kt) yields the screen when another app takes it.

**Data already available.**

- Frame times from gamescope's ring buffer ([`hud/GameFrames.kt`](../../app/src/main/java/com/droiddeck/launcher/hud/GameFrames.kt)), with lows, graph, focused app and refresh rate.
- Device metrics ([`hud/HudMetrics.java`](../../app/src/main/java/com/droiddeck/launcher/hud/HudMetrics.java)): CPU/GPU load, clocks and temperatures, RAM, VRAM, network, battery with a runtime estimate, fan.
- Game telemetry ([`session/GameTelemetry.kt`](../../app/src/main/java/com/droiddeck/launcher/session/GameTelemetry.kt)): racing UDP, War Thunder over local HTTP, Elite Dangerous status file.
- Steam downloads from app manifests and `content_log.txt`, screenshots from DroidDeck and Steam, Steam chat in a WebView with the web-login cookies.
- `AgentGuest.steamEval` runs `SteamClient.*` in Steam's UI. Only offline mode and live shortcuts use it today.
- Per-game profiles ([`session/SelectedGameProfile.kt`](../../app/src/main/java/com/droiddeck/launcher/session/SelectedGameProfile.kt)) that follow the game Steam has focused (`GameProfileFollowState`).

**Gaps.**

- The panel does not follow the game: no per-game mode, tab or control layout.
- With no game running, the stats card measures the Steam client.
- No Steam achievements, friends or notifications on the bottom screen. Achievements exist only for Epic, GOG and EA. The 0.7.0 notes mention a notifications page that no longer exists.
- No way to switch the bottom panel fully off or dim it when idle.
- [`settings-map.md`](../settings-map.md) lists only "Dual screen launcher". Dual screen sessions, Show the game on, Bottom screen controls in games, Bottom screen HUD, Strip on every tab and Physical controls can use bottom screen are missing.

DroidDeck already has more on the bottom screen than any other project that runs PC games. What it lacks is context: it does not react to what is on top.

## Proposals

Ordered by value for effort. Each is independent unless noted.

### 1. Idle home page

When no game is focused, replace the Steam-client FPS card with a page about the game highlighted in Steam: art, play time, last played, achievement progress, and Play / Stream buttons. Add downloads in progress and friends online.

- Source: the Big Picture selection and app details through `steamEval`, as ArmadaLive does over the CEF port. Art from the existing `SteamGameArt`.
- Open question: which `SteamClient` call or store gives the focused library item most reliably across client updates. ArmadaLive lists this as fragile; we need a fallback to the most recent game.

### 2. Per-game panel memory

Remember the bottom mode, dashboard tab and control layout per Steam app id, and restore them when that game gains focus. Fall back to today's global choice.

- Source: the focus already tracked for `GameProfileFollowState`. Store next to the game profile so Reset clears it.
- Applies to swapped mode too, where the panel is on the top screen.

### 3. Steam achievements and notifications

An achievements page for the running game (progress, recent unlocks, locked list with hidden descriptions behind a tap) and a notifications page (friends, invites, downloads finished).

- Source: `steamEval` against Steam's UI stores; the Steam Web API as a fallback for achievements when the client is offline.
- Matches the existing Epic, GOG and EA achievement readers so all stores look the same.

### 4. Quick tiles and per-game rules

Rearrangeable tiles on the dashboard: FPS limit, 60/120 Hz, per-screen brightness, frame generation, screen effects, screenshot, swap screens, bottom screen off. A "Keep for this game" action saves the value into the game profile; settings that can make the device unusable revert after 15 s unless confirmed.

- Most of these are already per-game somewhere (frame generation, screen effects, FPS limit for non-Steam games). This exposes them where the user is looking during play.

### 5. Bottom screen off and auto-dim

- A real off state for the bottom panel during a game (the backlight off, not a black Presentation), toggled from the bar or a button hold.
- Dim after a period without touch, wake on touch. Off by default for the trackpad and keyboard modes.
- Investigate which hardware buttons we can claim on the Thor and Duo without fighting the stock assistant.

### 6. Region mirror from our compositor

Crop rectangles of the game image (minimap, HUD, inventory) and show them scaled on the bottom panel, saved per game.

- We own the game's output surface, so we can sample it on the GPU without screen capture, a permission prompt, or capture latency. Megingiard, WalkThor and Heimdall all need MediaProjection for this.
- Editing: drag a rectangle on a frozen frame on the bottom screen, pinch to zoom. Optionally hide the region on the top screen.
- Needs a design for how the crop reaches the bottom `Presentation`: a second output of the compositor, or a shared texture.

### 7. Guides tab

A per-game guides tab: Steam Community guides for the running app id, pinned pages, and the last page reopened when the game returns. Save pages for offline use.

- The chat WebView and its cookies are the starting point.
- Later: maps, and ProtonDB or DroidDeck compatibility notes for the game.

### 8. Companion API for PC games

Let games and mods drive the bottom screen. In order of effort:

1. **Loopback channel.** Mods inside the container (SMAPI, BepInEx, MelonLoader) publish JSON or WebSocket state that a bottom-screen page renders. First target: compatibility with the existing Stardew mod on `127.0.0.1:7786`, which gives a full showcase without writing a mod. Needs a check that loopback in the container reaches the app, or a relay through `droiddeck-agent`.
2. **Run on bottom.** A command like Armada's `armada-run-bottom` that opens a guest window on the bottom panel with touch, building on the KDE desktop mode. This lets a game's second window or a companion process (Kanto Gear's separate mode, second-monitor mods) appear there. Unity games could use `Display.displays[1]` if the bottom panel is exposed as a second X output.
3. **Declarative packages.** Eden Duo-style per-game packages: a UI manifest plus a data source (loopback, file tail, or memory reads locked to the Steam build ID). Distributed from a catalog repo and fetched in-app. Only after 1 and 2 show demand.

The existing racing, War Thunder and Elite Dangerous telemetry follows the same idea and becomes the first set of built-in companions.

## Related fixes

- Add the missing dual-screen settings to `SettingsIndex` and regenerate [`settings-map.md`](../settings-map.md).
- Reproduce a first install on a Thor from a clean state, including with Steam on the SD card, against 0.7.11 to confirm the reported freeze is gone.
