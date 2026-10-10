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

All of it runs on one background thread (`AutoComponents`), low priority (the runtime helpers an
install starts run under `nice -n 19` and `ionice -c3`), one job at a time from one queue: a game's
list (worked out again only when the files it is read from change, by modification time, and kept
in `files/wincomp-findings.json`, so a page opens on it at once) and a component's download (each
component once, for every game that wants it). What the user asked for goes first: the page of a
game (on a metered network too), a store install or an add. A game's list is queued when it is
installed, added or edited, when its page opens, when it is launched from DroidDeck (the launch
itself only takes what is on already), and for every Steam game once per app start, ten seconds
after the launcher is up (Steam installs them without a word to DroidDeck). The queue waits while
a session runs, and a download also waits for a validated network (the page then greys what is not
downloaded yet, "Available when online", and follows the network live); what nobody asked for also waits on battery saver, thermal status moderate or
worse, or a metered network. A component turns on for its games when its download lands, for the
next launch, and an open page shows the progress and reads the picks again. One log line per
queued and per finished download.

`wincomponents.json` keeps, per game,
`auto` (each with its reason) and `user` (each switch the user pressed, on or off) beside `games`,
the effective list the launch reads (still version 1). A user switch always wins over an automatic
pick, either way, and a new automatic list never touches it. Picks saved before this build count
as the user's own.

The page shows "On for this game" with each reason ("From GOG's list for this game", "Steam
installs VC++ 2015 with <game> (app N)"), then Recommended, then "Also listed".

## Older store installs

A GOG or Epic game installed before its sidecar kept the store's list gets it once, on the worker
(`StoreListBackfill`): GOG's build manifest for the installed build (`dependencies`), Epic's
manifest (prerequisite name and path), fetched with the saved sign-in - the manifest alone, never a
game file - and kept in the sidecar as an install keeps it. Fresh installs now keep the keys even
when empty. Without a sign-in or when the store refuses: skipped quietly, tried again a week later;
offline is no try. Like any work nobody asked for it waits for an unmetered network, unless the
game's page is open.

## Apps the client never saw

A Custom game whose files name an appid the account never owned or viewed is missing from the
client's `appinfo.vdf`. While a Steam session runs, once the client is up and has settled (two
minutes), `SessionService` asks the client for those apps quietly, at most ten, one every five
seconds, at background priority, stopping when the session ends: `SteamClient.Apps
.RegisterForAppDetails(appid, callback)` through the DevTools channel `SteamLiveShortcuts` uses
(what the library's game page calls; for an app not in its cache the client fetches the app's info
from Steam). The client writes its cache out to `appinfo.vdf`; a miss is read again as soon as that
file changed (else weekly), so the next look after the session finds the app's Steamworks Shared
depots. An app asked about is not asked again for a week. Name-only matches stay Recommended.
