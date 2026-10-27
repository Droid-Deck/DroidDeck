# External display prototype

Experimental and off by default.

## Enabling it

1. Connect the monitor (USB-C DisplayPort Alt Mode / HDMI) **before** starting the session, so the game starts at the monitor's resolution.
2. Open the session drawer, go to **Display**, and turn on **Game on external display (experimental)**. The row is shown only while a usable display is connected, or while the option is on.

The choice is kept in the `session` preferences (`externalGameOutput`). Turning it off returns the game to the phone without a restart.

## What it does

- Picks the first valid `FLAG_PRESENTATION` display that is not the phone, not the activity's display and not the smart glasses (`session/ExternalOutput.kt`).
- Opens `ui/ExternalOutputScreen.kt`, a `Presentation` holding a `SurfaceView`, on that display. It asks for the monitor's fastest refresh rate, then the largest resolution at that rate. On a 4K/30 link that offers 2560×1440 at 60 Hz, it picks 1440p60. The session's resolution and refresh rate come from that preferred mode, not the mode the monitor happens to be in when the session starts.
- `SessionActivity` hands that surface to the compositor the same way it does for glasses. The phone's game surface is hidden, but its layout stays for touch input.
- Glasses always take precedence. Unplugging the monitor, picture-in-picture, stopping the activity or turning the option off all return the output to the phone.

## Low-latency presentation

While the game is on the external display, the compositor's swapchain uses `VK_PRESENT_MODE_MAILBOX_KHR` when the driver offers it, so SurfaceFlinger shows the newest frame at each of the monitor's refreshes instead of draining a queue. If MAILBOX is not offered, FIFO is used with the minimum number of images. Frame generation keeps FIFO, because it queues several presents per frame. The phone and glasses outputs keep their previous setup.

## Platform notes

- **A newly connected display can be disabled.** On Android 15, connected-display management can add an external display with `mIsEnabled=false` (`dumpsys display`). Apps cannot see a disabled display, so the switch stays hidden until the display is enabled; `adb shell cmd display enable-display <id>` does it.
- **The display mode request can be ignored.** The `Presentation` asks for its mode through `preferredDisplayModeId`. Some vendor builds of SurfaceFlinger only apply mode requests to the built-in panel, and then the monitor keeps the mode it connected with. If that is 4K at 30 Hz (common on 2-lane USB-C DisplayPort links), the output runs at 30 Hz.
- **System colour filters force GPU composition.** Night light or an "eye comfort" filter applies a colour matrix to the external display too, which makes SurfaceFlinger composite the game layer on the GPU (`Comp Type CLIENT` in `dumpsys SurfaceFlinger`) and tints the game.

## Diagnostics

In the session's `app.log` (included in **Share logs**):
- `external output: requested game output on display <id> "<name>" current <mode>, preferred <mode>, supported [...]`
- `compositor output attached: external display <id> <WxH@Hz>`
- `external output: phone output, <reason>`, logged when the option is on but not in use: no external display, glasses output active, picture-in-picture, activity stopped, session ended.

In `events.jsonl`: an `output.attached` event with `path` (`phone`, `glasses` or `external`), `display`, `mode`, `phoneHz` and `outputHz`.

## Known limits

- **Frame timing:** compositor frames are paced by the phone panel's vsync (`CompositorHost` uses the main-thread Choreographer), not the monitor's. Compare `phoneHz` and `outputHz` when measuring.
- **Resolution:** the session resolution is fixed when the session starts. A monitor attached mid-session shows the existing resolution letterboxed.
- **Touch:** touch input still comes from the phone and is mapped proportionally, so the edges are off when the aspect ratios differ. Use a controller for tests.
- **Strings:** the new strings are English only.

## Measurement template

The same phone, adapter, cable, monitor, port, monitor mode (Game Mode on), test scene and controller for every row. Record at least 20 trials per row with a high-speed camera filming the button and the screen.

| Configuration | Output mode (from `dumpsys display`) | phoneHz / outputHz | Median ms | Range ms | n | Notes |
|---|---|---|---|---|---|---|
| Phone panel only | | | | | | |
| HDMI, option off (existing behaviour) | | | | | | |
| HDMI, option on | | | | | | |

A 240 fps camera resolves about ±4.2 ms per frame, so report differences smaller than that as within measurement uncertainty.
