# Pen input

A pen on the phone's screen, or an external drawing tablet, drives the session's pointer like a mouse: hover moves
the pointer, the tip holds the left button and the side button right-clicks. Fingers keep their touch mode.

The settings are a "Pen" group after the touch settings, in the Steam and Desktop sheets' **Controls** tab and on
the session drawer's **Controller** page. The group is shown once the phone has a stylus device or a pen event was
seen; the first of either sets `penSeen`, which a settings reset keeps. Before Android 14 every touchscreen whose
driver reports a tool type has the stylus source, pen or not, so there a touchscreen counts only with its first pen
event.

## Modes

`pen`: Mouse (default) or Touch.

| | Mouse | Touch |
|---|---|---|
| Hover | moves the pointer and the cursor | ignored |
| Tip | holds the left button | a finger in the current touch mode |
| Side button | as the Side button setting says | ignored; a Bluetooth pen key is an ordinary key |

- With the picture on the glasses, or while a program in gamescope holds a pointer lock, Mouse drives the touchpad
  as a finger does: a tap clicks and a drag moves the pointer relatively.
- App UI on top (the drawer, achievements, input settings, picture in picture) takes the pen as Android input, as
  it takes a mouse.
- The Touch column is for a pen on the phone's screen. In Touch an external tablet stays on the mouse path, so its
  hover moves the pointer. The stylus rules under Android capture apply in both modes.
- A touch mode change ends the pen whatever its route, so it cannot leave a pen click held
  ([#318](https://github.com/Droid-Deck/DroidDeck/issues/318)).

## Side button

`penButton`: Right click (default), Middle click or Off. It applies to the pen's first barrel button. A second
barrel button, where a pen has one, sends the other click: middle when the setting is Right click, right otherwise.
Off sends nothing. The row is hidden in Touch.

Some pens send their button as a key from a separate Bluetooth keyboard; the OPPO AI Pen sends `KEYCODE_F21`. Such a
key is the side button when its device is external, keyboard-only and not alphabetic, and has a word starting with
"pen" or "stylus" in its name. In Mouse the pen always takes it, so neither the program nor the key bindings see
it, and it acts only while the pen is near: a key held when the pen comes back presses the button. Pause, focus
loss, pointer-capture loss and removing its device forget it. In Touch it is an ordinary key.

## Palm rejection

`penPalm`: on (default) or off, in both modes. While a pen on the phone's screen hovers or touches, and for 400 ms
after it leaves, a finger that goes down is ignored until it lifts, and so is every finger that joins its stream.
Fingers already down when the pen comes near get one `ACTION_CANCEL`, and a pen that lands in a finger stream
starts a stream of its own. An external drawing tablet does not count; a built-in pen that reports a mouse source
does.

`PalmFilter` sits in front of `dispatchTouchEvent` of the session window and of the lower-screen game, so it covers
every finger route (touchpad, direct touch, gestures, scroll, the on-screen and Steam touch controls) and the app UI
in those windows, such as the drawer. Pen and mouse pointers are never filtered, and without a pen near events pass
unchanged. `PalmGate` keeps the pen's state for both windows. A hover that stops reporting counts as gone after 2 s,
and everything that cancels touches counts as the pen leaving, as does removing its device. The log gets
`palm: pen near, cancelled <n> finger(s)` and, once per stream, `palm: finger ignored`.

Whatever the setting, a finger `POINTER_UP` with `FLAG_CANCELED`, which is how Android marks one of several touches
it rejected, ends the finger stream without a click, and the rest of that stream is dropped: `wl_touch` cannot
cancel a single touch point.

## Android capture

- Pens are found by tool type (stylus or eraser), or by `SOURCE_STYLUS` when the tool type is unknown. External
  tablets report `SOURCE_MOUSE | SOURCE_STYLUS`, so the source alone is not enough. Events that mix a finger and a
  pen are split, and the finger keeps its route.
- Contact is taken at the top of `SessionActivity.onTouchEvent`, after the Compose UI and the on-screen controls
  declined it, so the pen presses on-screen buttons like a finger. Hover is taken in `dispatchGenericMotionEvent`
  before the mouse branch. The lower-screen game takes both.
- With Low latency mode on (the default) the window asks for unbuffered pointer dispatch, so pen samples are not
  batched per frame. Every historical sample is forwarded in order, and `pointerSubset` keeps the history when it
  splits an event.
- `PenStream` keeps proximity, tip and buttons. A `HOVER_EXIT` followed within 50 ms by a hover or a `DOWN` does not
  leave proximity: Android splits a landing into an exit and a down with the same time, and a digitizer can flicker
  out and in at the top of its hover range. A hover that sends no sample for 2 s leaves proximity.
- Stylus devices are not hardware mice, so a drawing tablet does not turn on pointer capture.
- On the desktop a hardware mouse makes KWin grab the pointer. A pen that comes near releases the grab, so it
  moves KWin's pointer, and the mouse takes the grab back when it moves after the pen has left.
- Where the session hides the mouse pointer it also hides Android's stylus hover icon.

## What ends the pen

Everything that cancels touches: touch mode, touch route and pen setting changes, app UI opening, configuration
changes, surface resize, the game moving between screens, picture in picture, pause, focus loss, pointer-capture
loss and the activity's end. So do a change of the pen's own route and the removal of its device. `UP` with
`FLAG_CANCELED` and `ACTION_CANCEL` end it at once instead of lifting into hover. Ending the pen releases every
button and leaves proximity; `wl_pointer` has no cancel, so on the pointer a cancelled tap still clicks.

## Compositor

Each sample is one input record, type 13: the flags (in proximity, tip, right, middle) and the position in input
space as 24.8 fixed point. A record is the pen's whole state, so the next record makes good any lost one.

`deliver_pen` emulates the pointer, as the touch `pointer_fallback` does: hover is motion, the tip is `BTN_LEFT`,
the side button `BTN_RIGHT` or `BTN_MIDDLE`, and a record without the in-proximity bit releases every button. A
button pressed or released mid-stroke keeps the implicit grab on the window the stroke started in. The
relative-motion origin is reset when the pen comes and goes, so its jump never reaches a program as relative
motion. Under a program's pointer lock only the buttons pass, which also covers the time before the app sees the
lock.

The app logs `pen route <old> -> <new> (mode=<mode>)` and the compositor `[pen] <client> gets the pen as the
pointer`.

## Limits

- A hardware eraser end clicks like the tip.
- The pen and a mouse share the pointer's buttons, so a release from one ends a press of the other, as with the
  touch fallback.
- Some systems keep the pen's button for themselves, such as Samsung's Air actions. ColorOS opens its stylus
  palette for the OPPO AI Pen's key unless that action is turned off in the system's stylus settings.
