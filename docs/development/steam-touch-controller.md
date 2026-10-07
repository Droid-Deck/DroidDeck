# Steam's touch controller in a DroidDeck session

Research notes, started 2026-10-07. The question: instead of DroidDeck drawing its own on-screen
pad and keeping its own profiles, can the session use the touch controller Steam already has for
the Steam Link app, so that touch layouts are per game, synced to the Steam account and shared
through Steam's community configs?

This page records what the Steam client contains, the candidate approaches, and the experiments
run on the Thor to decide between them. Findings are appended as they come in; see
[Experiment log](#experiment-log).

## How Steam Link's touch controller works

Sources: the arm64 client DroidDeck installs (`steam_client_steamdeck_publicbeta_linuxarm64`,
manifest version 1791249696), the protobuf definitions in
[SteamDatabase/Protobufs](https://github.com/SteamDatabase/Protobufs)
(`steam/steammessages_remoteplay.proto`, `steam/steammessages_virtualcontroller.proto`) and Valve's
[touch controller guide](https://steamcommunity.com/app/353380/discussions/4/1735462352489233412).

The work is split between the streaming client (what runs on the phone) and the Steam client being
streamed from (the host):

- **Client: drawing and editing.** `streaming_client` holds `CVirtualController`, built from
  `streaming/client/virtualcontroller.cpp`: it draws the controls over the video, runs the layout
  editor (tray, colour picker, move/scale), and sends raw touches to the host as
  `CInputTouchFingerDownMsg` / `Motion` / `Up` (finger id, x and y normalised to the video).
- **Host: the controller.** `steamui.so` holds the stream server (`CStreamServer`,
  `IStreamServerInputDelegate`). `steamclient.so` holds `CMobileTouchControllerAbstraction`, a
  Steam Input controller backend next to `CSteamOSHandheldAbstraction` (the Deck),
  `CSteamControllerAbstraction` and `CGenericGamepadControllerAbstraction`. Its display name is
  "Steam Link Touch Controller" and its config type is `controller_mobile_touch`.
- **Host: the config.** The host owns the touch config and pushes it to the client:
  `k_EStreamControlTouchConfigActive` (appid, revision, creator),
  `GetTouchConfigData`/`SetTouchConfigData` (config data and layout blobs),
  `SaveTouchConfigLayout` (the client sends back an edited layout), `Get`/`SetTouchIconData`,
  `TouchActionSetActive` and `TouchActionSetLayerAdded`/`Removed`. Touch configs are Steam Input
  configs like any other: per game, saved to the account, publishable. SteamInputDB counted
  10,540 community configs for Mobile Touch in March 2026.

Every piece of this is in the arm64 package DroidDeck already installs: the host side in
`steamrtarm64/steamclient.so` and `steamui.so`, and the full renderer and editor in
`steamrtarm64/streaming_client`. The package also carries an `androidarm64/libsteamclient.so`
with the same touch strings.

## Where DroidDeck is today

The session's pad is presented to the Steam client as a Steam Deck controller (28de:1205 through a
served `hidraw` node and a written sysfs, see `SteamDeckPad.kt` and `fakeinput_steam.cpp`), so Deck
configs, per-game bindings and Deck community configs already apply. What Steam's touch controller
would add: control placement, icons, touch menus and on-device layout editing defined by Steam, and
the touch-specific community configs.

## Candidate approaches

| | How | For | Against |
|---|---|---|---|
| **A. Local Remote Play** | DroidDeck streams from its own Steam client the way the Steam Link app does (pairing, start stream, touch input negotiated), using Valve's `streaming_client` or a minimal client of our own. | Valve's controller, editor, sync and sharing unchanged. Leans on a protocol Valve keeps compatible with shipped Steam Link apps. | Host captures and encodes video nobody needs; the client may refuse to stream to itself. |
| **B. In-process injection** | Feed touches straight into the path between the stream server (`steamui.so`) and `CMobileTouchControllerAbstraction` (`steamclient.so`), with no stream. | No video cost. | Reverse engineering stripped arm64 binaries; breaks when Steam updates; layout drawing is still ours. |
| **C. Data only** | Read Steam's touch configs and layouts, draw them ourselves, keep feeding the Deck pad. | Simple. | Touch configs bind straight to keys, mouse and actions; honouring them means reimplementing Steam Input. |

## Experiments

Run in order; each one either rules an approach out or narrows the next.

1. **Host side alive?** Does the arm64 client in a DroidDeck session run the stream server, answer
   discovery on the network and accept a Steam Link pairing?
2. **Touch controller instantiated?** Stream to the official Steam Link app (same phone or another
   device) and check that the touch controller appears, takes a game config and drives a game.
3. **Loopback.** Can a client on the same device (127.0.0.1 / the device's own address) stream from
   the session's Steam, and what does it cost (capture, encode)?
4. **Input without video.** Does input keep flowing with the stream paused or captured tiny
   (`k_EStreamControlPause`, `SetCaptureSize`), or with a client that never decodes video?
5. **Valve's renderer as an overlay.** Can `streaming_client` itself run inside the session as an
   overlay over the game, rather than the phone app?
6. **Injection points.** How the stream server hands touches to `steamclient.so`, to size approach B.

## Experiment log
