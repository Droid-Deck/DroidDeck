# Steam's touch controller in a DroidDeck session

Research notes, started 2026-10-07. The question: instead of DroidDeck drawing its own on-screen
pad and keeping its own profiles, can the session use the touch controller Steam already has for
the Steam Link app, so that touch layouts are per game, synced to the Steam account and shared
through Steam's community configs?

This page records what the Steam client contains, the candidate approaches, and the experiments
run on the Thor to decide between them; details are in the [Experiment log](#experiment-log).

## Conclusion

**Yes, and without streaming anything.** Steam's touch controller is a Steam Input controller type
(Mobile Touch, `controller_mobile_touch`) that Steam builds for any HID device with the ids
0000:11fb. Steam Link only supplies that device remotely; libfakeinput can supply it locally, the
way it already supplies the Deck pad. Shown on the Thor with an experimental build:

- Steam opens a local `/dev/hidraw17` 0000:11fb, builds the touch controller (type 43, the same
  attributes as Steam Link's), loads the game's touch config for it (Valve's touch templates,
  official per-game touch configs, the user's own synced ones, community ones), and games get
  its input through Steam Input's virtual pad. A touch-controller stick report flew the ship in
  Geometry Wars.
- Steam tells the device which app and action set are active (output report 4) and when action
  set layers come and go (5, 6), live, plus rumble (1). That is what decides which layout to show.
- Big Picture treats it as a touch controller: the in-game menu shows the touch layout preview,
  bindings are edited in Steam's own configurator.
- It can be plugged and unplugged mid-session (injected udev events), so it can step aside when
  a physical pad is used, as Steam Link does.
- The layout is part of the config file (`touch_layout`, a `CVirtualControllerLayouts` protobuf),
  so it syncs and is shared with the config. Steam loads a touch config written to disk.

**Recommended: approach D below.** DroidDeck draws the controls (from Steam's layout) and sends
the device's 40-byte report; Steam does everything else. It needs no stream, no pairing, no video
and no patching of Steam, and the parts it depends on are the ones Valve has to keep stable for
the Steam Link apps already installed on phones and TVs: the device's ids and report format.

Not settled yet: saving an edited layout goes through a file write rather than a Steam call (Steam
loads the written file and queues it for Steam Cloud like its own autosaves); the overlay must
reproduce Steam Link's default layout and its hiding of unbound controls; touch menus and gyro
were not exercised.

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

| | How | Verdict |
|---|---|---|
| **A. Local Remote Play** | DroidDeck streams from its own Steam client the way the Steam Link app does (pairing, start stream, touch input negotiated). | **Works, not worth it.** The official Steam Link app on the Thor pairs with the session's Steam over loopback and its touch controls drive the session (experiment 2). But the host captures and software-encodes video (libx264, 72-85 % CPU, frames froze), the Steam Link app cannot turn video off, and an input-only client of our own means reimplementing the Remote Play transport and crypto. The protocol does have `enable_video_streaming` in the streaming request. Kept as a fallback only. |
| **B. In-process injection** | Feed touches into the path between the stream server (`steamui.so`) and `CMobileTouchControllerAbstraction` (`steamclient.so`). | **Not needed.** That path is a HID device; D supplies the device instead of patching Steam. |
| **C. Data only** | Read Steam's touch configs and layouts, draw them ourselves, keep feeding the Deck pad. | **Rejected.** Touch configs bind straight to keys, mouse and actions; honouring them would mean reimplementing Steam Input. (D still reads the layout from the config, but Steam does the bindings.) |
| **D. Local Mobile Touch device** | libfakeinput serves 0000:11fb as a local hidraw node; DroidDeck draws Steam's layout and writes the 40-byte report. | **Works (experiments 3, 4). Recommended.** |

## Experiments

1. **Host side alive?** Yes: discovery answers on loopback and Steam Link pairs.
2. **Touch controller over a stream?** Yes, end to end, with Steam Link on the same device.
3. **Loopback cost.** Too high: software video encode (part of 2).
4. **Input without video.** Not run: needs our own Remote Play client; superseded by D.
5. **Valve's renderer as an overlay.** Not run: `streaming_client` draws over decoded video and
   lives behind the stream; superseded by D.
6. **Injection points.** The touch controller enters Steam as a HID device (0000:11fb), which is
   approach D.
7. **Local 0000:11fb device.** Works: controller built, config loaded, games driven (section 3).
8. **Hotplug.** Works with injected udev events (section 4).
9. **Layout written to disk.** Steam loads it (section 5).

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

Report layout, all little-endian (Steam Link `CVirtualController`: `TouchControl_JoyButton`,
`TouchControl_JoyStick`, `SetTrackpad`, `UpdateControllerState`):

| Bytes | Field |
|---|---|
| 0-7 | u64 buttons and touch flags (table above; d-pad as the Deck's: up 8, right 9, left 10, down 11; left / right stick touched 46 / 47) |
| 8-15 | int16 left X, left Y, right X, right Y: `x * 32767`, Y stored inverted (`~(y * 32767)`) |
| 16-27 | three trackpads, int16 x (`x * 65535 ^ 0x8000`) and y (`0x7fff - y * 65535`) |
| 28-33 | accelerometer xyz, int16, ±2 g (19.61 m/s²) to ±32767 |
| 34-39 | gyro xyz, int16 |

**Games, through Steam Input, with no stream.** With the session started on Geometry Wars:
Retro Evolved (8400) and the game launched by pressing A twice on the local device, the client
loaded `controller_base/templates/controller_mobile_touch_gamepad_joystick.vdf` for app 8400 on the
touch controller (Valve's stock touch template), told the device `04 d0 20 00 00 ...` (app 8400),
and a left-stick report flew the ship across the screen. Steam's own path end to end:
DroidDeck → `/dev/hidraw17` → Mobile Touch controller → the game's touch config → Steam Input's
virtual Xbox pad → the game. D-pad bits navigated the Steam menu the same way.

What the stream gives that the local device does not:

- **The active config and its layout.** Steam Link asks for it (`GetTouchConfigData`) and the host
  answers with the config and the layout. Locally the device is told only the app id and action
  set (report 4), so the overlay has to resolve the config itself: `configset_controller_mobile_touch.vdf`
  per app (`autosave` = `<appid>/controller_mobile_touch.vdf`, otherwise a template or workshop
  config), and the console log line `Loaded Config for ... App ID <n>, Controller <i>: <path>`
  names the file Steam actually loaded. Stock templates carry no `touch_layout`; Steam Link draws
  its built-in default layout for them (`CVirtualController::ResetLayoutToDefault`), hiding
  elements the config leaves unbound (`UpdateElementAvailability`).
- **Saving an edited layout.** Steam Link sends `SaveTouchConfigLayout`; the host writes it into
  the app's autosave config, which is what syncs. The local device has no such message. Big
  Picture's `SteamClient.Input` API (seen in `steamui/sp.js`) has the config editing calls
  (`StartEditingControllerConfigurationForAppIDAndControllerIndex`,
  `SetEditingControllerConfigurationMiscSetting`, `SaveEditingControllerConfiguration`,
  `GetConfigForAppAndController`, `QueryControllerConfigsForApp`, `SetSelectedConfigForApp`,
  `GetTouchMenuIconsForApp`, `RegisterForTouchMenuMessages`) but no layout call; whether
  `touch_layout` goes through the misc-setting call is untested. The session's devtools port only
  admits guest processes (`BL_CDP_GUARD`), so this needs the agent bridge's guest commands rather
  than a probe from adb.
- **Custom icons** (`SetTouchIconData`).

Everything else (bindings, action sets, layers, touch menus, rumble, the configurator, community
configs, cloud sync) is Steam's own and works with the local device.

### 4. Two controllers, and hotplug

With the touch device present beside the Deck pad, Steam creates a virtual pad for each
(`Created virtual controller at slot 0 for controller 0` / `slot 1 for controller 1`): a game
sees two players. Steam Link avoids this by withdrawing its touch controls while a physical pad is
in use, and a local device has to do the same, which needs hotplug.

- **Unplug works as is.** Ending the device's report stream (`/tmp/touchctl.off` in the experiment)
  gives `Controller device closed after hid_read failure`, `Controller 0 disconnected`, and the
  virtual pad is destroyed. Steam re-enumerates at once, and an open refused then keeps it off.
- **Plugging back in needs an event.** The client watches only `/dev/input` with inotify (already
  redirected by libfakeinput) and never rescans hidraw on its own; an inotify event in
  `/dev/input` did not trigger a HID rescan. Its HID discovery is SDL's, driven by a udev monitor,
  and the session's monitor is `udevmon.c`'s silent stand-in (the sandbox refuses netlink).
- **Injected udev events work.** The experiment extends the stand-in: a file in `/tmp/udev-inject`
  (`ACTION`, `DEVPATH`, `SUBSYSTEM=hidraw`, `DEVNAME`, `MAJOR`, `MINOR`) is sent by every process
  with a stand-in as udevd's message format (`libudev` header, magic `0xfeedcafe`, properties plus
  `SEQNUM`), and the stand-in's `recvmsg` (through `ntsync.c`, which owns the symbol) reports the
  udev multicast group as the sender and root credentials, as libudev requires. An injected `add`
  for `hidraw17` made the client open the node again within a second and rebuild the touch
  controller (type 43) and its virtual pad, mid-session. (The sender address has to be written
  from the room `msg_namelen` had before the call; a unix socket sets it to 0.)

So DroidDeck can show and hide Steam's touch controller the way Steam Link does: plug the device
in when the on-screen controls are shown, unplug it when a physical pad takes over.

### 5. Configs and layouts written by DroidDeck

With the session stopped, a `config/8400/controller_mobile_touch.vdf` was written (Valve's gamepad
template plus a `touch_layout` taken from another config) and `"8400" { "autosave" "1" }` added to
`configset_controller_mobile_touch.vdf`. Launching the game:

```
Loaded Config for Local Selection Path for App ID 8400, Controller 0:
  .../Steam Controller Configs/392297941/config/8400/controller_mobile_touch.vdf
```

Steam used it as the game's touch config and left the file (and its `touch_layout`) as written.
**Steam Cloud picked it up too**: Steam Controller Configs sync as app 241100's cloud files, and
`userdata/<account>/241100/remotecache.vdf` gained an entry for
`392297941/config/8400/controller_mobile_touch.vdf` (`syncstate` 3, `remotetime` 0: queued for
upload), the same bookkeeping as the account's other touch configs. Both files were put back
afterwards.

Also seen: pressing A on the touch device logged `Seating controller 1 in slot 0`. Steam moves the
controller in use to the first player slot, which softens the two-controller problem even before
the device is unplugged.

### 6. Steam Link's default layout

Stock templates carry no `touch_layout`. Steam Link then places each control from a built-in table
(`CVirtualController::BInitializeDefaultElement`, table at `0x463d48`, positions on a 1280x720
reference):

| Element | Position (of 1280x720) |
|---|---|
| Thumb (menu) | 75, 75 |
| Select / Steam / Start | 516, 75 / 636, 75 / 756, 75 |
| Paste / Keyboard | 1115, 75 / 1205, 75 |
| Magnifying glass / record / playback | 1205, 165 / 255 / 345 |
| D-pad | 200, 525 |
| X / Y / B / A | 1003, 527 / 1083, 447 / 1163, 527 / 1083, 607 |

Sticks, triggers, bumpers and macros are not in the table; they appear when the config binds them
(`UpdateElementAvailability`), and the overlay has to reproduce that rule.

## What building D would take

- **libfakeinput:** the 0000:11fb node as in the experiment (Generic Desktop / Game Pad report
  descriptor, 40-byte input reports, output reports 1, 3-6 and feature report 2), fed from a
  ring the app writes, like the Deck pad's.
- **SteamDeckPad.kt:** the second device's sysfs, as in the experiment.
- **udevmon.c:** event injection for plug and unplug, with a proper channel from the app instead of
  the experiment's file drop.
- **The overlay (Kotlin):** read the active config (configset per app, else the template Steam
  logged loading), decode `touch_layout` (or the default table above), show the elements of the
  action set Steam names in report 4 and the layers in 5/6, hit-test touches into the report
  (buttons, sticks, trackpads; motion from PadMotion into the gyro fields), rumble from report 1.
  Hide the unbound controls as Steam Link does.
- **Editing:** move/scale in the overlay, saved by writing `touch_layout` into the app's autosave
  config, as Steam does when Steam Link saves a layout.
- **Policy:** plug the device in while the on-screen controls are up and no physical pad is in use;
  unplug it otherwise.

The experimental pieces on this branch (`FAKE_EVDEV_TOUCHCTL`, `/tmp/touchctl.*`,
`/tmp/udev-inject`) are research scaffolding to be replaced, not shipped.
