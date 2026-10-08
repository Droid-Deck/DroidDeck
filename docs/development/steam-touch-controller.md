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

### 1. Host side alive: yes (2026-10-07, Thor, Steam 1791249696)

- With a Steam session READY, the client listens on TCP 27036 and UDP 27036 (`/proc/net`, app
  uid). A discovery probe built from `steammessages_remoteclient_discovery.proto` sent to
  `127.0.0.1:27036` from the device gets a `CMsgRemoteClientBroadcastStatus` back: hostname
  `DroidDeck`, `enabled_services` 98 (game streaming on), the signed-in user, `steam_deck`
  unset, `gaming_device_type` 541.
- The official Steam Link app (Play Store, on the Thor's bottom screen while the session runs on
  the top one) finds "DroidDeck" by itself, shows a PIN, and the session's Big Picture shows
  **Authorize Device**. After the PIN the app runs its network test and lists DroidDeck as
  paired. Pairing to the session's own client over loopback works with no changes.
- Launching Steam Link on display 0 instead puts DroidDeck's session activity into PiP; use
  `am start --display 4`. On first start Steam Link sits on its splash until BLUETOOTH_CONNECT
  is answered, and the permission dialog does not render on the second screen (granted with
  `pm grant`).

### 2. Touch controller over the stream: works end to end

Starting a stream (`streaming_log.txt`, `console_log.txt` in the session's Steam `logs/`):

```
CLIENT: Sending HID device 2020/0112/-1  Xbox Wireless Controller at sdl://3
CLIENT: Sending HID device 0000/11fb/-1  Mobile Touch Control at touch://0
...
Remote Device Found  type: 0000 11fb  path: touch://0  Product: Mobile Touch Control
!! Steam controller device opened for index 1.
Controller device closed after hid_read failure
Controller 1 disconnected
```

- **The touch controller reaches the host as a remote HID device, 0000:11fb**, opened by the
  same controller code that opens the Deck pad, and turned into a virtual controller. The
  streaming client is what makes the reports; Steam on the host only sees a HID device.
- Video fails on the host: `PipeWire: Could not connect PipeWire context` (the session runs no
  PipeWire), so the app gets audio but a black screen and keeps its loading spinner.
- The app then re-sends only the Thor's built-in pad and drops the touch device; it seems to
  withdraw touch controls when a physical controller is present, or until video arrives.

With video fixed (below), **Steam's touch controller runs against the session's own client**:

- Steam Link withholds its touch controls while a physical pad is attached (the Thor's own pad
  shows up as "Xbox Wireless Controller"). A four-finger tap opens its stream menu, which has
  **Enable Touch Controls** (injected with `sendevent` on `/dev/input/event5`, the bottom panel).
- The client then re-sends `touch://0`; the host opens it as controller type 43, product 0x11fb,
  serial `MT-<Steam Link device id>`, capabilities `0000007f83045bff`, creates a virtual controller
  for it, and sends the client `TouchConfigActive`, `TouchActionSetActive` and
  `SetTouchConfigData`. The app draws Big Picture's touch layout (d-pad, ABXY, Steam button,
  keyboard, `...`).
- Touching the controls drives the session: the touch Steam button opens Big Picture's main
  menu on the Thor's top screen, touch B closes it, and the session's glyphs switch to the touch
  controller's. (D-pad taps from `input tap` did not register; not chased.)
- Cost: the host encodes with libx264 in software (`/dev/video-enc0` missing), 4 threads,
  1240x698. CPU sat at 72-85 % in the overlay and `k_EStreamControlVideoOverflow` flooded the log;
  the app's picture froze within a minute while input kept working. **Video loopback is not a
  shippable path**; only the input half of the stream is useful.

Video fix used for this test only: PipeWire and WirePlumber are in the rootfs and gamescope is
built with PipeWire, so a `gamescope` wrapper on `PATH` (via `Download/droiddeck-env`) that starts
`pipewire` and `wireplumber` first gives gamescope a capture node (`stream available on node ID`)
and the client a picture. (Editing `droiddeck-session` on the device does not stick: the app
re-syncs the overlay each session.)

### Where Steam keeps touch configs

In the session's Steam, `steamapps/common/Steam Controller Configs/<account id>/config/`:

- `<appid>/controller_mobile_touch.vdf` for each game with a saved touch config (dozens already
  there for this account, synced down from Steam Cloud), plus named copies like
  `<appid>/default touch_0.vdf`;
- `configset_controller_mobile_touch.vdf`: which config each app uses (`autosave` = the per-app
  file above, otherwise a template or workshop id);
- `configset_MT-<serial>.vdf`, `preferences_MT-<serial>.vdf` and `config/MT-<serial>_gyro.vdf`:
  per touch device.

A touch config is an ordinary `controller_mappings` VDF (`controller_type` `controller_mobile_touch`,
bindings per action set and layer) with one extra key, **`touch_layout`: the on-screen layout as
a hex-encoded `CVirtualControllerLayouts` protobuf**: one layout per action set, each a list of
elements (`EControllerElementType`, visible, x/y position normalised to the screen, x/y scale)
and a colour, plus input mode, mouse mode and pinch-zoom settings. Bindings and layout travel
together in the one file that syncs and is shared as a community config.

### The touch device's HID protocol

From Steam Link for Android (`libmain.so`, exported symbols; `CVirtualController` is both the
renderer and the HID device behind `touch://0`):

- **Input report: 40 bytes** (`CVirtualController::Read`), kept at `this+200` and marked dirty
  at `this+240` by every setter. Bytes 0-7: button/touch bits (e.g. bit 27, 19, 20: trackpads
  0, 1, 2 touched). Bytes 16, 20, 24: trackpads 0-2 as int16 x (centred, `x*65535 ^ 0x8000`) and
  y (inverted). Remaining fields (sticks, triggers, gyro) not yet mapped.
  `CHIDDeviceReportGenerator::BInjectGamepadStateMobileTouch` is a stub; the report is the
  controller's own format, not the generic SDL gamepad state.
- **Output reports from Steam** (`Write` → `QueueFeatureReport` → `HandleFeatureReports`),
  at least 17 bytes, first byte the type:
  - 1: rumble (u16 low, u16 high, u32 duration);
  - 3: setting `0x30` with a u16 value (a flag at `this+1488`, likely gyro);
  - **4: action set change** (u32 appid, u32 action set id): switches the layout shown;
  - **5 / 6: action set layer added / removed** (u32 appid, u32 layer id).
- **Feature report 2**: battery (level, from `SDL_GetPowerInfo`), 17 bytes.

So Steam drives action sets, layers and rumble over HID. The only things that come solely over
the stream are the config and layout blobs (`SetTouchConfigData`), the active config's
appid/revision/creator (`TouchConfigActive`) and custom icons (`SetTouchIconData`); the first two
are on disk in the VDF above.

### 3. Without a stream: Steam takes a local 0000:11fb device (works)

Experimental build on this branch: `FAKE_EVDEV_TOUCHCTL=1` (in `Download/droiddeck-env`) makes
libfakeinput serve `/dev/hidraw17` as 0000:11fb "Mobile Touch Control" next to the Deck pad, with
its sysfs written by `SteamDeckPad.prepare` (a second USB device, `usb2`). The 40-byte input report
is read from `/tmp/touchctl.report` in the session; every output and feature report the client
sends is logged to `pad.log` and appended to `/tmp/touchctl.out`.

- With a vendor-page report descriptor (`06 ff ff 09 01`, as the Deck's) the client walks the
  whole sysfs tree, reads the descriptor and never opens the node. **With Generic Desktop / Game
  Pad (`05 01 09 05`) it opens it**: for a non-Valve vendor id the client wants a gamepad usage.
- Steam then builds the touch controller exactly as it does for Steam Link:

  ```
  Local Device Found  type: 0000 11fb  path: /dev/hidraw17  serial_number: MT-DROIDDECK0001
  !! Controller 0 attributes:  Type: 43  ProductID: 4603  Serial: MT-MT-DROIDDECK0001
     Capabilities: 0000007f83045bff
  ```

  and speaks the touch protocol to it: `get-feature 02` (battery), output `03 30 ..` (the setting
  report), and **output `04 01 03 00 00 01 00 00 00`: action set 1 of app 769** (Big Picture).
- Writing button bits into the report drives the client: Steam (`0x2000`) opens Big Picture's
  main menu, B (`0x20`) closes it. Opening the menu made Steam send `04` with app 443510 (the
  Steam menu's own touch config; it is one of the app folders in the config directory) and
  closing it `04` with 769 / 1 again: **the active app and action set arrive over HID, live.**

Button bits (Steam Link's `CVirtualController` table at `0x463e70`, indexed by
`EControllerElementType - 1`), the same layout as the Deck report's low word:

| Element | Bit | | Element | Bit |
|---|---|---|---|---|
| A | 7 | | Select | 12 |
| B | 5 | | Steam | 13 |
| X | 6 | | Start | 14 |
| Y | 4 | | Left stick click | 22 |
| Left bumper | 3 | | Right stick click | 26 |
| Right bumper | 2 | | Macro 0-7 | 32-39 |
| Left trigger | 1 | | 1-finger / 2-finger macro | 48, 49 |
| Right trigger | 0 | | Trackpad 0 / 1 / 2 touched | 27 / 19 / 20 |
