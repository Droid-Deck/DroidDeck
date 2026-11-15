# External display (Desktop)

A Desktop session can put the desktop on a connected monitor and use the phone as a native panel. This is scoped to Desktop sessions. Steam sessions keep their existing behaviour, and a tablet's built-in second screen keeps the original **Second screen** page.

## Enabling it

1. Connect the monitor (USB-C DisplayPort Alt Mode / HDMI).
2. Open the quick shelf and select **Screens**.
3. Set **Desktop on** to **External monitor**. The default is **This device**.
4. Choose what the other screen shows under **Other screen**. The default is **None**.

The Screens page appears while an external monitor is connected and disappears when it is removed. Both choices are saved in the session preferences (`externalGameOutput` and `secondScreenPanel.desktop`) and are restored on the next monitor connection or session start. Connecting or removing the monitor resizes the Desktop output live; no session restart is needed.

## What it does

- Picks the first valid `FLAG_PRESENTATION` display that is not the phone, not the activity's display, not the smart glasses and not a tablet's built-in second screen (`session/ExternalOutput.kt`). Built-in second screens stay on the original second-screen path.
- Opens `ui/ExternalOutputScreen.kt`, a `Presentation` holding a `SurfaceView`, on that display. It uses the mode the monitor connected with and does **not** ask the system for another one. The session's resolution and refresh rate follow that mode, so the panel and the session agree even on vendor builds that ignore `preferredDisplayModeId` (OxygenOS/ColorOS do).
- `SessionActivity` hands that surface to the compositor the same way it does for glasses. The phone's game surface is hidden, but its layout stays for input.
- The phone shows the selected native panel: nothing, keyboard + trackpad, terminal, profile controls or the stats dashboard. The old on-screen game controls and touch fallback are disabled while the phone is a panel.
- The terminal panel keeps the original floating `TerminalView` keyboard. Its key events are routed to the terminal instead of the desktop on the monitor.
- Glasses always take precedence. Unplugging the monitor, picture-in-picture, stopping the activity or turning Desktop on back to This device returns the output to the phone.

## Phone panel and input

With **Other screen** set to a panel, the phone hosts the native panel. **None** leaves it blank but the quick shelf still opens with Back or the panel's menu button. Only the keyboard + trackpad and profile-controls panels move the pointer; other panels consume their own touches. The remote second-screen panel shown when Desktop on = This device is unchanged.

## Presentation

The compositor's swapchain uses `VK_PRESENT_MODE_FIFO_KHR`. The low-latency MAILBOX path exists in `vk_present.c` but this feature keeps it disabled with `nativeSetLowLatencyPresent(false)`; FIFO matches the tested Desktop/terminal path and avoids the earlier pointer-move flicker. Frame generation also requires FIFO because it queues several presents per frame.

## Platform notes

- **A newly connected display can be disabled.** On Android 15, connected-display management can add an external display with `mIsEnabled=false` (`dumpsys display`). Apps cannot see a disabled display, so the Screens page stays hidden until the display is enabled; `adb shell cmd display enable-display <id>` does it.
- **The connected mode is used as-is.** Some vendor builds of SurfaceFlinger only apply `preferredDisplayModeId` to the built-in panel, so requesting another mode leaves the monitor where it connected. This path does not request one; it adopts the monitor's current mode. If that is 4K at 30 Hz (common on 2-lane USB-C DisplayPort links), the output runs at 4K/30. Change the monitor or the USB-C link if you need 60 Hz.
- **System colour filters force GPU composition.** Night light or an "eye comfort" filter applies a colour matrix to the external display too, which makes SurfaceFlinger composite the game layer on the GPU (`Comp Type CLIENT` in `dumpsys SurfaceFlinger`) and tints the game.
- **Built-in second screens are not external monitors.** A tablet or dual-screen device keeps the original **Second screen** page, its labels and its translations.

## Diagnostics

In the session's `app.log` (included in **Share logs**):
- `external output: requested game output on display <id> "<name>" current <mode>, supported [...]` - the session uses `current`.
- `compositor output attached: external display <id> <WxH@Hz>`
- `external output: phone output, <reason>`, logged when the option is on but not in use: no external display, not a desktop session, glasses output active, picture-in-picture, activity stopped, session ended.

In `events.jsonl`: an `output.attached` event with `path` (`phone`, `glasses` or `external`), `display`, `mode`, `phoneHz` and `outputHz`.

## Known limits

- **Frame timing:** compositor frames are paced by the phone panel's vsync (`CompositorHost` uses the main-thread Choreographer), not the monitor's. Compare `phoneHz` and `outputHz` when measuring.
- **Touch:** the phone panel owns its touches while the desktop is on the monitor. A remote panel shown on the monitor keeps its own touchscreen handling.
- **Strings:** the external-monitor labels are translated in every locale; the original second-screen labels are unchanged.
