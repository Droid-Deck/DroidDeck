# Windows components by source

A game's Windows components (VC++ runtimes, DirectX's d3dx9, XNA, PhysX...) are picked for it by
itself, from the list its source keeps. The source badge (Steam, GOG, Epic Games, Amazon Games,
Custom) decides whose list is trusted (`WinCompSources`):

| Source | List | On by itself |
| --- | --- | --- |
| Steam | The Steamworks Shared redistributables Steam installs with the game: the appmanifest's `SharedDepots`, else the client's app info (`SteamAppInfo`) | yes |
| GOG | The build manifest's `dependencies`, kept in the sidecar at install (`extra.dependencies`) | yes |
| Epic Games | The manifest's prerequisite (`PrereqName`, `PrereqPath`), kept in the sidecar at install | yes, for the mapped parts |
| Amazon Games | fuel.json's `PostInstall` installers | yes, for the mapped parts |
| Custom, appid in its files | Steam's list for that app (`SteamMatch` certainty `FILES`: steam_appid.txt, also under steam_settings/ or a parent; an emulator ini; an appmanifest) | yes |
| Custom, matched by name | Steam's list for that app (certainty `NAME`, a store search with the same name) | no, Recommended |
| Custom, no match | the folder scan (`DependencyDetector`) | no, Recommended |

The folder scan is offered as Recommended for every game.

## Steam's data

Never scraped from a website. `SteamAppInfo` reads the Steam client's own app-info cache in the
runtime (`Steam/appcache/appinfo.vdf`, versions 27-29): the app's depots that point at app 228980
(Steamworks Shared) and its name. Each appid's answer is kept in `files/steam-appinfo.json`, so it
works offline; an app the client has not seen is looked for again after a week. A Steam game with
its appmanifest beside it uses the manifest first. `droiddeck-seed-redists` still marks Steam's
install scripts for those redistributables as already run, so the client never runs the real
installers.

## One Steam match per game

`SteamMatch` keeps, per added game (by its folder), `{appId, certainty FILES|NAME|NONE, source}`
in `files/steam-matches.json`. The art fetch (`AddedGameArt`) and the components read and write
the same record; an earlier build's art lookup (`added-art/<appid>/steam-appid`) is taken over as
a name match. A miss is tried again after a week.

## Mapping

All in `WinCompSources`:
- GOG ids: `MSVC<year>[_x64]` → `vcredist<year>` (2017 → the 2015 family, as the folder scan),
  `DirectX*`/`DX9`/`D3DX*` → `d3dx9`, `DOTNET<n>` → `dotnet35`...`dotnet48`, `PhysX` → `physx`,
  `OpenAL` → `oalinst`, `XNA3`/`XNA31` → `xna31`, other `XNA*` → `xna40`, `XLive*`/`GFWL*` →
  `XLiveRedist`, `XACT*` → `xact`.
- Epic: Unreal's `UE4PrereqSetup`/`UEPrereqSetup` (or a "UE4/UE5/Unreal Engine Prerequisites" name)
  → `vcredist2022` + `d3dx9`; any other installer by its path, as the folder scan names it.
- Amazon: each `PostInstall` command by its path, as the folder scan names it (Unreal's
  prerequisite as above).
- Steam: Steamworks Shared depots 228981-228990 (`SteamRedists.DEPOTS`).
Anything that maps to nothing is listed under "Also listed" with its own name. A mapped component
that needs a Windows installer here, or is not in the catalog, stays Recommended with its status.

## On by itself, and the user's switches

`AutoComponents.refresh` runs when a game is installed from a store, added or edited, when its
Windows components page opens, before a launch from DroidDeck, and once at app start for every
Steam game (Steam installs them without a word to DroidDeck). What is downloaded already turns on
at once; every other automatic pick is queued (`queueDownloads`, one game at a time, never a
component the user switched off), downloaded in the background with its progress on the page's
row, and turned on when it lands - for the next launch; an open page reads the picks again. `wincomponents.json` keeps, per game,
`auto` (each with its reason) and `user` (each switch the user pressed, on or off) beside `games`,
the effective list the launch reads (still version 1). A user switch always wins over an automatic
pick, either way, and a new automatic list never touches it. Picks saved before this build count
as the user's own.

The page shows "On for this game" with each reason ("From GOG's list for this game", "Steam
installs VC++ 2015 with <game> (app N)"), then Recommended, then "Also listed".
