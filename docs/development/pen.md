# Pen input

A pen on the phone's screen, or an external drawing tablet, reaches desktop programs that take tablet input as a
graphics tablet, with pressure, tilt and an eraser. Everywhere else it drives the session's pointer like a mouse:
hover moves the pointer, the tip holds the left button and the side button right-clicks. Fingers keep their touch
mode.

The settings are a "Pen" group after the touch settings, in the Steam and Desktop sheets' **Controls** tab and on
the session drawer's **Controller** page. The group is shown once the phone has a stylus device or a pen event was
seen; the first of either sets `penSeen`, which a settings reset keeps. Before Android 14 every touchscreen whose
driver reports a tool type has the stylus source, pen or not, so there a touchscreen counts only with its first pen
event.

## Modes

`pen`: Auto (default), Mouse or Touch.

| | Auto | Mouse | Touch |
|---|---|---|---|
| Hover | as Mouse, or tablet proximity for a tablet program on the desktop | moves the pointer and the cursor | ignored |
| Tip | as Mouse, or tablet contact with pressure and tilt | holds the left button | a finger in the current touch mode |
| Side button | as the Side button setting says | as the Side button setting says | ignored; a Bluetooth pen key is an ordinary key |

- Auto asks for the tablet only in the Desktop session. The compositor gives it to a program that binds tablet-v2
  and sends the pointer otherwise (see Tablet route). In Steam, and for programs in gamescope, Auto is Mouse. The
  drawer shows what Auto gives where the pen is: "Auto (tablet)" or "Auto (mouse)".
- With the picture on the glasses, or while a program in gamescope holds a pointer lock, Auto and Mouse drive the
  touchpad as a finger does: a tap clicks and a drag moves the pointer relatively.
- App UI on top (the drawer, achievements, input settings, picture in picture) takes the pen as Android input, as
  it takes a mouse.
- The Touch column is for a pen on the phone's screen. In Touch an external tablet stays on the mouse path, so its
  hover moves the pointer. The stylus rules under Android capture apply in every mode.
- A touch mode change ends the pen whatever its route, so it cannot leave a pen click held
  ([#318](https://github.com/Droid-Deck/DroidDeck/issues/318)).

## Side button

`penButton`: Right click (default), Middle click, Eraser while held or Off. It applies to the pen's first barrel
button. A second barrel button, where a pen has one, sends the other click: middle when the setting is Right click,
right otherwise. Off sends nothing. The row is hidden in Touch.

- On the tablet route Right click is `BTN_STYLUS2` and Middle click `BTN_STYLUS`, which GTK, Qt and Xwayland read
  as the right and middle buttons.
- Eraser while held makes the pen the eraser tool while the button is held. While hovering the switch is
  immediate and does not count as the pen leaving. During contact it waits until the tip lifts, and a press that
  comes with the tip's `DOWN` starts the stroke as the eraser. On the pointer route the button then sends nothing.
  It is offered only for Auto in the Desktop session; elsewhere a stored Eraser while held is Right click.

Some pens send their button as a key from a separate Bluetooth keyboard; the OPPO AI Pen sends `KEYCODE_F21`. Such a
key is the side button when its device is external, keyboard-only and not alphabetic, and has a word starting with
"pen" or "stylus" in its name. In Auto and Mouse the pen always takes it, so neither the program nor the key
bindings see it, and it acts only while the pen is near: a key held when the pen comes back presses the button.
Pause, focus loss, pointer-capture loss and removing its device forget it. In Touch it is an ordinary key.

## Pressure

`penPressure`: Soft, Normal (default) or Firm, shown only for Auto in the Desktop session, the one place pressure
reaches a program. Soft and Firm are cubic Bézier curves from (0,0) to (1,1), read as y of x like KWin's pressure
curve, with control points `0,0.25;0.75,1` and `0.25,0;1,0.75`: half pressure becomes 0.68 with Soft and 0.32 with
Firm. The app applies the curve before the value reaches the compositor. A program's own curve, such as Krita's,
applies on top.

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
- `PenStream` keeps proximity, tip, tool and buttons. A `HOVER_EXIT` followed within 50 ms by a hover or a `DOWN`
  does not leave proximity: Android splits a landing into an exit and a down with the same time, and a digitizer can
  flicker out and in at the top of its hover range. A switch between the pen and the eraser end keeps proximity
  too. A hover that sends no sample for 2 s leaves proximity.
- Each sample carries pressure, distance and tilt. Android turns the raw tilt into `AXIS_TILT` and
  `AXIS_ORIENTATION`; `PenAxes` inverts that exactly to tilt in x and y. A sample with no tilt at all, which
  some pens send at the edges of hover range and just before the tip lifts, carries no tilt, so the previous
  angle stays. Android turns the orientation of such a sample with the window, so only the tilt is checked.
- The pen's capabilities come from its device's motion ranges: pressure always, tilt when `AXIS_TILT` exists, and
  distance when `AXIS_DISTANCE` has more than two values. They are announced at session start, so the tablet exists
  before programs start (Wine reads tablets once per process), and again at the first pen event, because a device
  can report no ranges while the screen is off.
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

Each sample is one input record, type 13: the flags (in proximity, tip, right, middle, eraser, wants a tablet, has
tilt), the position in input space as 24.8 fixed point, pressure and distance as 0..65535, and tilt in hundredths
of a degree. A record is the pen's whole state, so the next record makes good any lost one. Type 14 tells the
compositor that the pen's capabilities changed.

On the pointer route `deliver_pen` emulates the pointer, as the touch `pointer_fallback` does: hover is motion, the
tip is `BTN_LEFT`, the side button `BTN_RIGHT` or `BTN_MIDDLE`, and a record without the in-proximity bit releases
every button. A button pressed or released mid-stroke keeps the implicit grab on the window the stroke started in.
The relative-motion origin is reset when the pen comes and goes, so its jump never reaches a program as relative
motion. Under a program's pointer lock only the buttons pass, which also covers the time before the app sees the
lock.

## Tablet route

- A record that asks for a tablet picks its target once per stroke, in the pointer's order: the constraint
  surface, the desktop surface, the implicit grab, then the window under the pen. If the target's client bound
  `zwp_tablet_manager_v2`, the stroke goes there as tablet-v2; otherwise it takes the pointer route. Target and route
  stay until the tip and every button are up, so a stroke is never split between the two.
- `wl_tablet.c` offers one tablet, "DroidDeck Pen" on the virtual bus, once the pen's capabilities are known, and
  keeps it while the compositor runs. Its pen and eraser tools are created on first use. A sample that changes
  nothing sends no frame, and every `proximity_in` frame carries the tool's whole state.
- Before the first tablet frame the compositor moves the pointer to the pen, so the window under the pen gets a
  pointer enter: Qt learns that the cursor entered a widget only from pointer crossings, and Krita starts a stroke
  only on a canvas the cursor entered. When the pen leaves, the pointer moves to where it left, as with a desktop
  graphics tablet. Neither move happens under a pointer lock. If the lock ends while the pen is near, as when the
  pen releases a hardware mouse's grab, the pointer moves to the pen before the next frame, or once the tip and
  every button are up. A tool switch moves nothing.
- The desktop draws the pen's cursor, so the Android cursor overlay hides while the pen is near on the tablet route.
  When the pen leaves, the overlay comes back as it was, or as the mouse or touchpad left it in the meantime, at the
  pen's last position. DroidDeck accepts a tool's `set_cursor` but draws only the pointer's cursor.
- A client named `gamescope` always gets the pointer. SDL2 in gamescope's SDL backend binds tablet-v2 but turns it
  into mouse input, and under a game's pointer lock gamescope would hand the pen's absolute motion to the game as
  camera motion.
- Tablet frames and proximity-out carry the compositor's clock domain time at delivery, like pointer, keyboard and
  touch events, not Android's event time. After a session pause they keep the same time base as the rest of the
  guest's input.

The app logs `pen route <old> -> <new> (mode=<mode>)`. The compositor logs `[pen] <client> takes the pen as a
tablet` when a program binds tablet-v2, and `[pen] <client> gets the pen as a tablet` or `as the pointer` when the
pen's route or client changes.

## Limits

- KWin's nested backend binds no tablet-v2 unless the runtime patches it to. Without that the desktop gets every
  stroke as the pointer: Auto behaves like Mouse and Pressure has no effect.
- A tablet tool keeps the capabilities known when it was first used. A pen with more axes that comes later in the
  same session, such as `droiddeckctl input pen` after a pen without tilt, gets no extra axes until the next session.
- On the pointer route a hardware eraser end clicks like the tip.
- The pen and a mouse share the pointer's buttons, so a release from one ends a press of the other, as with the
  touch fallback.
- Krita under X11: after a stroke that leaves the canvas and comes back, later strokes are lost until the pen
  visits another widget. Qt's xcb backend does not record the window of an XI2 tablet press, so the canvas loses the
  cursor during the stroke and never gets an enter back (see Tablet route). This is a Qt xcb and Krita bug; Krita on
  Wayland is not affected.
- Xournal++'s default pen cursor is a dot as wide as the stroke, which hides under the nib on a phone. Its View
  settings offer a pen icon or a highlighted cursor position instead.
- Some systems keep the pen's button for themselves, such as Samsung's Air actions. ColorOS opens its stylus
  palette for the OPPO AI Pen's key unless that action is turned off in the system's stylus settings.
