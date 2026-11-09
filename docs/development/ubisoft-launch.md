# Ubisoft launch without Ubisoft Connect

A Ubisoft game never holds its own ticket. Its DRM loader opens Ubisoft's API DLL from the Connect
folder, and that DLL asks the running launcher for the session over local named pipes. DroidDeck
answers those pipes with `droiddeck-upc.exe`, a small Windows program that runs in the game's prefix.
The game and every Ubisoft DLL stay unmodified, and there is no Connect client, CEF or background
service. The session the game receives is real: its ticket comes from Ubisoft's ownership service for
the signed-in account.

This file is the specification `tools/ubisoft-launcher` was written from. The facts come from the
protobuf descriptors embedded in Ubisoft's own `uplay_r1.dll` (`proto_uplaydll/UplayDll.proto`) and
from the public notes in [Foxtrot47/Ubisoft-Connect-RE](https://github.com/Foxtrot47/Ubisoft-Connect-RE)
(`docs/06-launch.md`, `docs/10-denuvo.md`). No code was taken from either.

## Pieces

| Piece | Where | Job |
|---|---|---|
| Ubisoft runtime | `C:\Program Files (x86)\Ubisoft\Ubisoft Game Launcher\` in the shared prefix | Ubisoft's own `uplay_r1(64).dll`, `uplay_aux_r1(64).dll`, `uplay_r2(64).dll`, `ubiorbitapi_r2(64).dll`, `UbisoftGameLauncher(64).exe` and the starter's imports (`discord-rpc.x86/x64.dll`, `steam_api(64).dll`). `droiddeck-store-launch` extracts exactly these 14 files with `7z` from the official `UbisoftConnectInstaller.exe`, then deletes the installer |
| Registry | `HKLM\Software\[Wow6432Node\]Ubisoft\Launcher` | `InstallDir` points at the runtime folder in both views; `Installs\<productId>\InstallDir` points at the game, with forward slashes and a trailing slash, as Connect writes it |
| Shared prefix | `compatdata/2929927072` | Every Ubisoft game runs here (`UbisoftConnect.launchOptions` sets `STEAM_COMPAT_DATA_PATH`), so the runtime is extracted once |
| Launch file | `<game>/.droiddeck-ubisoft.json` | Written by `droiddeck-store-launch` before every launch from the app's `ubisoft-launch` answer: the account, the owned product ids, a fresh PC ticket, the entitlement's application id and the save folder. Removed first, so a failed refresh falls back to starting the game directly |
| Launcher | `<game>/.droiddeck-launch.bat` | Runs `Z:\usr\local\lib\droiddeck-ubisoft\droiddeck-upc.exe <launch file>` when both exist |
| Tickets | app, Rust `ubisoft/service.rs` | Demux `ownership_service`: initialize (owned products), then `GetUplayPCTicketReq` (field 14) answered in field 13 `{1: success, 2: ticket}` |

## Pipes

All pipes are `\\.\pipe\terminal_<session>_<name>`, where `<session>` is
`ProcessIdToSessionId(GetCurrentProcessId())` (1 under Wine). They are byte-mode duplex pipes with
unlimited instances, created before the game starts. Every message is a 4-byte big-endian length
followed by a proto2 message, except on `uplay_service_ipc_pipe_`.

| Pipe | Client | Exchange |
|---|---|---|
| `game_start_ipc_pipe_` | `UbisoftGameLauncher.exe` | `game_starter.Upstream{1:{1:{1: build, 2: product, 10: exe, 11: arguments, ...}}}` → `Downstream{2:{1:{1:1, 2:0, 3: <root>/ + "/" + <exe under root>, 4: <root>/, 8:3, 9:{1:{1,113,0,1,0} 1:{3,123,0,0,0}}}}}` |
| `uplay_api_process_ipc_pipe_` | game | `Req.initProcessReq{1: product, 2: game pid, 5: env set}` → `Rsp.initProcessRsp(13){1:1, 3:1, 4:3, 6:0}`. Kept open. Answering it is what releases the starter (below) |
| `uplay_ipc_pipe_` | game (`uplay_r1.dll`) | `Req.initReq(1)` → `Rsp.initRsp(1)`, answered every time it is asked. `Req.installerReq(13)` with `initInstallerReq` → `Rsp.installerRsp(10){1:{1:1, 2:"", 3: language}}` |
| `uplay_aux_ipc_pipe_` | `uplay_aux_r1(64).dll` (Denuvo) | `{1: rid, 2: init}` → `{1: rid, 2:{1:1, 2: pid, 3:3367, 3:3368, 3:3962}}`; `{1: rid, 3:{2:{2: challenge}}}` → `{1: rid, 3:{2:{2:1, 3:0, 4: game token, 5:""}}}` |
| `uplay_service_ipc_pipe_` | Ubisoft helpers | Server sends `{3:{1:1, 3:{1: <hwscore path>}}}` once, then only reads, unframed |
| `upc_api_process_ipc_pipe_standard`, `uplay_protocol_ipc_pipe_`, `uplay_overlay_ipc_pipe_`, `orbit_ipc_pipe_`, `uplay_crash_reporter_ipc_pipe_` | — | Held open, never answered |

### The session (`InitRsp`)

| Field | Value |
|---|---|
| 1 result | 1 (`InitResult_Success`) |
| 2 account | `{1: userId, 2: username, 3: nameOnPlatform, 4: email, 5: "" (password), 6: country}` |
| 3 connected | 1 |
| 4 gameUplayId | the product |
| 5 uplayIds | every owned product whose ownership entry is owned, of type 0 or 1, in state 0 or 3 |
| 6 gameCdKeys | `{1: product, 2: ""}` |
| 7 isInOfflineMode | 0 |
| 8 upcTicket | the PC ticket from `GetUplayPCTicket`, never the Denuvo token |
| 11, 12, 15, 16, 18, 19, 21, 23 | the values a real launch carries (`{1:0}`, 0, overlay `{1:1, 2:3}`, hardware score `{6211, 22395, 1.0f}`, empty, `{1:1, 2:1}`, `{2:1}`, empty) |
| 17 storage | `{1: "C:/users/steamuser/Saved Games/Ubisoft/<userId>/<product>/"}`; the game appends file names directly, so the trailing slash matters |
| 22 ubiServices | `{1: applicationId}` from the account's entitlements, left out when unknown |

## Start sequence

1. `droiddeck-upc.exe` creates the pipes and an auto-reset event marked inheritable, then starts the
   game's own exe with handle inheritance, in the install folder, with no extra arguments.
2. The exe sees it was not started by Connect and starts `UbisoftGameLauncher.exe
   -gamelauncher_wait_handle <event> -upc_uplay_id <product> ...` (it exits with code 3; that is normal).
3. The starter asks `game_start` and starts the real game from the reply.
4. The game announces itself on `uplay_api_process`; the answer sets the event, which lets the starter
   exit. Without it the starter times out and its job object ends the game after about 15 seconds.
5. `uplay_r1.dll` asks `uplay_ipc` for the session.
6. `droiddeck-upc.exe` waits for the announced process, allows 15 s for a restart, then exits with the
   game's code so Steam sees the game end.

A log of every exchange is written to `~/.cache/droiddeck/ubisoft/upc-<product>.log` in the guest.

## Denuvo

For protected titles the aux pipe carries the activation challenge. `droiddeck-upc.exe` writes it to
`~/.cache/droiddeck/ubisoft/bridge-<product>/denuvo-<rid>.req` and waits 90 s for `.rsp`. Answering it
needs `denuvo_service` with an ownership token (`OwnershipTokenReq`, field 28) and is the next step;
titles without Denuvo never open the aux pipe.

## Not covered yet

- R2 (`UPC_*`) titles use `uplay_r2.dll`; their pipe traffic has not been captured, so whether the
  same server answers them is unverified.
- Orbit (`ubiorbitapi_r2`) titles.
- Achievements, friends, overlay, cloud saves.
