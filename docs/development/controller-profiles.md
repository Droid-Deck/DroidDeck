# Controller profiles

How a physical controller's stick dead zone and key names are decided. A pad is matched by what
Android reports about it, so the same settings follow it onto any phone.

## Global dead zone

**Setup › Controller › Stick dead zone** (0, 5, 8, 12, 15, 20 or 25 %, default 12 as before).
It applies to every physical pad that has no profile. The dead zone is a circle round each stick's
centre: inside it the stick reads as centred, outside it the value passes through unchanged. (It used
to be a separate 12 % on each axis, which held back small movements on one axis while the stick was
pushed over on the other; 0 % now means no dead zone at all.)

## Profiles

A profile is a JSON entry in either file; the first match wins, Downloads before the app's folder:

1. `Download/droiddeck-controller-profiles.json`
2. `files/controller-profiles.json` in the app's data folder

```json
{"profiles": [
  {"name": "My pad", "vendor": "0x045e", "product": "0x028e", "nameContains": "pad",
   "deadZone": 0.06, "keys": {"BUTTON_MODE": "BUTTON_SELECT"}}
]}
```

| Field | Meaning |
| --- | --- |
| `name` | Label for the log. Required. |
| `vendor`, `product` | USB / Bluetooth ids: `"0x045e"`, `"1118"` or a number. |
| `nameContains` | Part of the device name, any case. |
| `deadZone` | 0 to 0.5 of the stick's travel. Left out: the global setting. |
| `keys` | Key the pad reports → key it is treated as (`KEYCODE_` optional, or a number). |

A profile needs `vendor`/`product` or `nameContains`; one with neither matches nothing. When several
match, the most specific wins (ids and name, then ids, then name), the earlier entry on a tie. The
file is read when a session opens, so a change shows after the next one.

## Writing one for a pad you have

When a controller connects, the app log (`logcat -s SessionActivity PadBridge`) has a line like

```
controller "MCON Gamepad" reports keys [BUTTON_MODE,BACK]; axes [X[-1.00..1.00 flat 0.060 fuzz 0.000]; ...];
profile entry { "profiles": [ { "name": "MCON Gamepad", "vendor": "0x....", "product": "0x....", "deadZone": 0.12 } ] }
```

Copy the entry, change what is wrong, and save it as one of the files above. `flat` is the dead zone the
pad itself asks Android for, a good starting point. `PadBridge` then logs `profile "..." applies`
the first time that pad sends input.

## Code

`input/ControllerProfiles.kt` (matching, files, parsing, the per-device cache), read per event by
`PadBridge.onKeyEvent` / `onMotionEvent`. Tests: `ControllerProfilesTest`.

## Not here yet

Per-controller settings beyond dead zone and key names (trigger curves, stick calibration, the touch
controls hiding, rumble), a profile editor in Setup, sharing profiles in the app, and phone-wide profiles
(refresh rate, FPS cap, performance mode keyed to the device). See the tracking issue.
