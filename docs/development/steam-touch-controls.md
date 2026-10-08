# Steam's touch controls

Drawer → Controller → **Touch controls: Steam (beta)** replaces DroidDeck's own on-screen pad, in a
Steam session, with Steam's touch controller: the one the Steam Link apps use. Bindings, action
sets, per-game configs, Valve's touch templates, official and community touch configs and Steam
Cloud sync are all Steam's; DroidDeck draws the controls and turns touches into the controller's
input. The research behind this (how Steam Link's touch controller works, the experiments on the
Thor) is in `steam-touch-controller.md` on the `research/steam-touch-controller` branch.

## How it fits together

```
SteamTouchControls (overlay)  →  SteamTouchDevice (shared file)  →  libfakeinput /dev/hidraw17
        ↑ layout                          ↑ app / action set                 ↓ 0000:11fb
SteamTouchConfig (Steam's configs)  ←───  output reports  ←──  Steam client: Mobile Touch controller
                                                                      ↓ Steam Input
                                                                  virtual pad → game
```

- **The device.** Steam builds its touch controller (Steam Input's Mobile Touch type) for any HID
  device with the ids 0000:11fb. libfakeinput offers one to the client as `/dev/hidraw17` beside
  the Deck pad, with its sysfs written by `SteamDeckPad.prepare(touch = true)`. It needs a Generic
  Desktop / Game Pad report descriptor: for a non-Valve vendor id the client ignores a vendor-page
  device. Offered only with the Deck pad (Steam controller set to Deck);
  `Download/droiddeck-no-steam-touch` turns it off.
- **The shared file** (`touchctl` in the session's pad directory, `FAKE_TOUCHCTL_RING`;
  `SteamTouchDevice.kt` / `TouchRingFile` in `fakeinput_steam.cpp`). The app writes the 40-byte input
  report under a seqlock, whether the device is plugged in, and the battery; libfakeinput writes
  back what the client tells the device.
- **The report** (Steam Link's `CVirtualController`): bytes 0-7 button bits (the Deck's low word for
  face buttons, bumpers, triggers, d-pad, Steam, menu buttons; 22/26 stick clicks; 46/47 sticks
  touched; 27/19/20 trackpads touched; 32-39 macros; 48/49 one- and two-finger macros), 8-15
  sticks (int16, Y up), 16-27 three trackpads, 28-33 accelerometer, 34-39 gyro (from PadMotion,
  in the Deck's units).
- **Output reports from the client:** 4 = app id and action set (decides which layout is shown),
  5/6 = action set layer added/removed, 1 = rumble (also sent to the app's vibration like any
  pad's), 3 = a setting. Feature report 2 is the battery.
- **Plugging.** The device is plugged in only while Steam's controls are shown: with Auto it steps
  aside when a controller is connected, as Steam Link withdraws its touch controls. Unplugging
  ends the device's report stream (the client lets the controller go); either way libfakeinput
  sends a udev event through `udevmon.c`'s stand-in monitor (`bl_udevmon_inject`), since the
  sandbox gives the client no kernel uevents and its HID discovery would never look again.

## Layouts

`SteamTouchConfig.load` finds the game's touch config: the app's own saved config when the
configset (`configset_controller_mobile_touch.vdf`) points at one, otherwise the file the client's
console log says it loaded for the touch controller (a template, an official or a workshop config).
`touch_layout` in it is a hex `CVirtualControllerLayouts` protobuf: per action set, each control's
type, visibility, centre (fractions of the screen) and scale. Controls with no layout go where
Steam Link puts them by default; only controls the config's active sources bind are shown.

**Editing** (drawer → Steam touch layout → Edit): drag, pinch or −/+ to resize, Hide, Reset,
Cancel, Save. Save writes `touch_layout` into the game's autosave config
(`<account>/config/<appid>/controller_mobile_touch.vdf`, made from the config in use) and points the
configset at it, as the client does when Steam Link saves a layout; the device is then replugged so
the client reads it again. Steam Cloud uploads the file like any autosaved config (seen in
`userdata/<account>/241100/remotecache.vdf`).

## Verified on the Thor

- Big Picture: Steam shows "Controller Connected - Remote Play Touch Controller"; the Steam button
  opens the menu, the d-pad moves focus.
- A game (Geometry Wars: Retro Evolved): Valve's Gamepad touch template loaded, the stick flies
  the ship.
- A saved layout survives a session restart and was uploaded to Steam Cloud.
- Touch menus: a touch menu bound to a trackpad is drawn by Steam's own overlay when the trackpad
  is held.
- Unplug and replug mid-session; two touch devices at once (research branch).

## Open

- Gyro: motion reaches the device's report, but a `gyro_to_joystick` binding did not move the stick
  in the one test made (injected rates, sensors paused). Needs a look at what the client wants
  (calibration, an enable setting).
- Custom touch icons (`SetTouchIconData` on Steam Link) are not drawn; controls use text labels.
- Not tried on a retail phone with SELinux enforcing (the Deck pad's sysfs works there).

## Icons, labels and colours

Each control shows what its binding carries, as Steam Link does: a binding is
`<action>, <label>, <icon>, <#foreground #background>` (e.g.
`xinput_button A, Attack, ghost_010_wpn_0070.png, #FFFFFF #AD0000`). Icons are looked up as Steam does:
the game's own `TouchMenuIcons` folder, then `tenfoot/resource/images/library/controller/binding_icons`
(459 icons). They are drawn as Steam draws binding icons: the icon multiplied by the foreground colour
on a disc of the background colour. A binding with no icon shows its label, else a glyph for what it
presses (LB, Esc, ⏎…), else the button's own name.

In the layout editor, **Icon** opens a picker for the selected control (a D-pad direction: the one
tapped): label, an icon from the game's own and Steam's library, foreground and background from Steam's
binding-icon palette. **Colour** sets the layout's colour and opacity. Save rewrites only those binding
strings in the game's touch config (byte for byte otherwise) along with `touch_layout`. Verified with
Castle Crashers (its config ships icons on the shoulders and stick click): icons drawn; A set to a knife,
white on red, "Attack" and B to another icon; both written in Steam's format and loaded back after a
session restart.

The pickers are panels in the session's window, not dialogs.

## Steam Link's menu and editor

- **Menu button (…)**: touch input Controller / Mouse / Controller + mouse; mouse as direct touch or
  trackpad; trackpad speed. Like Steam Link these are per game, saved in the game's `touch_layout`
  (`CVirtualControllerLayouts` fields 2-4), so they sync with the config. In Controller mode touches
  outside the controls do nothing; in Mouse mode only the menu button is drawn and the screen is the
  mouse. Also: vibrate on touch, fade when not touched, gyroscope (Steam Link's global options, kept
  per device), the layout editor and DroidDeck's own menu.
- **Editor**: ◀ ▶ steps through every action set of the game's config (each saved to its own layout),
  Add (the tray: bound controls not on screen, and the optional Paste button), More (colour and
  opacity, copy a layout, paste it into another game, reset), W−/W+/H−/H+ and axis-wise pinch for a
  control's width and height (ovals).
- **Paste** control: Ctrl+V into the session (the Android clipboard is shared with it).

Not done: Steam Link's magnifying glass / pinch zoom, record and playback of input, shake to fade, and
its custom tray drag-in animation. Gyro: the report now carries motion only while the client asks
(setting report 0x30) at Steam Link's scale (1000 °/s full scale); a `gyro_to_joystick` binding still
did not move a game's stick with injected rates, so gyro remains unconfirmed.
