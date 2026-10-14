# Astra 2 and VITURE stereo

## Tested connection

2026-10-14: REDMAGIC Astra 2, model `NP06J`, Android 16,
`RedMagicOS11.5.16_NP06J_GB`, Snapdragon `SM8850P`; VITURE Pro XR
(`35ca:101d`), firmware `07.0.03.013_20250422`. The tablet reports a locked
bootloader and green verified boot. Tests used the ordinary ADB shell, without root.

2D negotiates **1920×1080 at 120 Hz**. The glasses accept stereo command
`0x0008 [32]` with reply `00 32`, but Android then exposes **only 640×480 at
60 Hz**. An accepted USB command is not proof of a working stereo signal.
In this state the glasses reject a 2D command (`01 31` or `01 34`) and their
mode query returns `01 00`. A physical cable reconnect restored 1920×1080/120
with this update; Android then requested USB permission again and control resumed.

On the fresh attempt, `0x030D [03]` arrived 3.1 seconds after the accepted stereo
command. The vendor SDK maps that report to 2D/120 (`0x34`); the subsequent mode
query was rejected. The report does not encode a fallback reason or establish a
fixed timeout. The earlier command acknowledgement cannot prove stereo retention.

## What was checked

| Route | Result on this firmware |
| --- | --- |
| Pro full SBS command `0x32` | Accepted by MCU; physical output falls to 640×480 |
| Android preferred mode 3840×1080/60 | Preference saved; physical supported modes remain 640×480 only; preference cleared after test |
| USB source/host → source/device | Request issued; actual port stayed source/host |
| USB data disable/enable | Data status changed and was restored; physical display remained 640×480 |
| `IUsbManager.resetUsbPort` through ADB | HAL callback reported success (`0`); physical display mode did not change |
| External-display power off/reset | Did not recover the signal; glasses still rejected 2D |
| Physical cable reconnect | Restored 1920×1080/120 and USB controls after the permission prompt |
| OEM mirror/3D controls, source review | Change virtual-display routing, not physical stereo timings |
| OEM resolution selection, source review | Chooses cached resolution candidates and prefers stale 16:9 mode |
| Pro startup mode / half SBS, SDK review | No supported Pro startup-mode setting or half-SBS input |

Public driver decompilations from the related REDMAGIC 11 Pro (`NX809J`, SM8850)
[retain an active mode override](https://github.com/Coding-BR/android_kernel_nubia_sm8850_qwjujube/blob/efee6f411184886b0fbdaf63fc9db36b3ed96036/decompiled/msm_drm/dp_display_get_modes.c#L37-L44)
across a glasses hotplug. Their [USB-PD disconnect path](https://github.com/Coding-BR/android_kernel_nubia_sm8850_qwjujube/blob/efee6f411184886b0fbdaf63fc9db36b3ed96036/decompiled/msm_drm/dp_display_usbpd_disconnect_cb.c#L127-L147)
clears that override; their simulated hotplug path does not. This is consistent
with the Astra's measured behavior, but does not prove its exact kernel
implementation. That driver also [validates saved user modes against EDID](https://github.com/Coding-BR/android_kernel_nubia_sm8850_qwjujube/blob/efee6f411184886b0fbdaf63fc9db36b3ed96036/decompiled/msm_drm/nubia_edid_modes.c#L226-L250),
so active overrides and saved preferences must not be treated as the same
mechanism. The independent [MagicDesk investigation](https://github.com/mekhontsev/magicdesk/blob/main/docs/xr-resolution-fix.md)
documents a related failure on its separately identified REDMAGIC firmware.

The [VITURE SDK matrix](https://www.viture.com/en-SG/developer/glasses-sdk/glasses)
specifies full SBS at 3840×1080/60 for Pro. Persistent startup mode is restricted
to Luma/Luma Pro. Among the Luma families supported here, 90 Hz stereo is an
Ultra capability. The connected Pro firmware matches both the N6PL entry in the
[public updater catalog](https://static.viture.com/dfu-util/js/vers.js) and the newest
N6P entry in the [public release-note service](https://cloud.viture.dev/api/v1/system/releasenote)
checked on 2026-10-14. This does not rule out unpublished, device-specific updates.

## No-root hardware design to test

A powered pass-through connection may let the tablet see the stereo EDID on a
fresh USB-PD connection while the glasses retain their stereo mode:

1. Power the adapter and glasses independently of the tablet connection.
2. Enable stereo on the glasses.
3. Disconnect only the tablet's upstream USB-C connection promptly after the
   stereo acknowledgement, keeping the glasses powered. Compare with a delayed
   disconnect to test whether the observed mode report affects retention.
4. Reconnect the tablet and check its physical supported modes before starting a game.

This requires an adapter that preserves glasses power and mode during the
upstream disconnect, passes 3840×1080/60 video, and forwards USB HID for controls
and head tracking. [VITURE's Adapter Ultra documentation](https://www.viture.com/academy/adapters/usb-c-xr-charging-adapter-ultra)
explicitly excludes wired glasses data on Android, so it does not satisfy
DroidDeck's current USB control/tracking path. Its power and video specifications
also do not promise mode retention across upstream disconnect. This design
remains **unverified**, not a purchasing recommendation. First verifying stereo
retention through the adapter on a compatible host would provide a useful control.
An intermediary presenting the stereo EDID before first host
connection is another possible design; it must pass the actual stereo timing
and preserve HID access, not merely advertise a wider desktop.

VITURE advertises full Android functionality for its separate
[USB-C to Glasses and Charging Adapter](https://www.viture.com/en-MY/product/usb-c-to-glasses-and-charging-adapter).
That makes it a candidate for the USB-data experiment, not a confirmed fix.
First check USB enumeration, brightness controls and live IMU data through the
adapter; then test power/mode retention and the stereo timing.

Success requires Android to expose and select 3840×1080/60, different left/right
eye images to reach the lenses, live head tracking, and repeatable return to 2D.
A wide screenshot or `wm size` override alone does not meet those conditions.

## App safeguards

DroidDeck waits up to 20 seconds for physical stereo confirmation. A temporary
low-resolution mode does not permanently blacklist the device. Failed attempts
offer manual retry once the 2D signal returns; automatic retries do not loop.
USB mode reports are tracked separately from the physical display. When a session
enabled 3D, stopping it requests a return to 2D, including during negotiation;
this firmware can still require a cable reconnect. Output timing follows
the active glasses display rather than the tablet's requested refresh rate.

Keep hardware experiments separate from game loads. End the session, force-stop
DroidDeck, and reopen its home screen after each game test. Read current HAL
temperatures rather than cached thermal-service samples.

## Validation of this update

- Full debug APK build, including the Rust store engine; native-library, session
  asset and runtime-input packaging checks passed.
- 840 Android unit tests across 140 suites passed, including mode deadlines,
  reconnect ownership, stale USB callbacks and model-specific protocol cases.
- Four host tests passed, including native refresh notification, event coalescing,
  full-pipe handling and Cardboard output checks.
- On the Astra: brightness changed and was restored; IMU delivered 400 packets
  in two seconds; Steam reached its first frame in 4.9 seconds and exited cleanly.
  Wayland reported the actual 120 Hz tablet mode. Four complete steady-state
  ten-second windows recorded 116.8–119.5 screen frames/s in Steam's UI.
- After reconnect: a fresh stereo attempt remained pending during the 640×480
  fallback, failed at the 20-second deadline and issued one 2D request. The MCU
  refused that request; another 28 seconds of observation showed no retry loop.
- DroidDeck was force-stopped and relaunched after testing. Debugging tools and
  temporary logging/display overrides were restored. Current HAL temperatures
  after the session were 25°C battery and 28.3°C skin.

These checks do not establish stereo-game performance or a sustained thermal
baseline. Direct-cable stereo and the powered-adapter design remain unverified
as working solutions on this Astra firmware.
