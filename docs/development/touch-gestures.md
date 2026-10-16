# Configurable touchscreen gestures

The session drawer's **Controller → Touch → Gestures** mode converts touchscreen
gestures into mouse and keyboard input. Direct touch and Touchpad remain available.
Open **Touch gesture settings → Edit** in the same drawer to change bindings.
The editor groups settings into One finger, Two fingers, Three fingers, and
Options tabs. Action selection supports text search and categories such as Mouse,
Navigation, Modifiers, and Numpad. Actions are alphabetical within each category;
function keys follow their numeric sequence. Default and game-specific drafts remain intact
when switching scopes; Save changes saves the edited drafts and Cancel discards them.
With Steam touch controls, choose Mouse or Controller + mouse in Steam's touch
menu so background touches reach the gesture recognizer.

Settings apply to all games by default. When a game is in front, the settings
dialog can save an override for that game. Choose **Defaults** to edit the shared
settings or **This game** to edit the game's settings. **Use defaults** removes its
override. Game focus changes reload the appropriate configuration and cancel any
active gesture before switching profiles.

## Default actions

| Gesture | Action |
| --- | --- |
| Tap | Left click |
| One-finger drag | Hold left button and drag |
| Double tap | Double left click |
| Two-finger tap | Right click |
| Two-finger drag | Arrow-key pan |
| Two-finger hold | Hold middle mouse button |
| Pinch | Mouse-wheel zoom |
| Three-finger tap | Open DroidDeck's PC keyboard |
| Three-finger hold | Hold Escape |

Long press and three-finger drag are available but disabled by default.
In Gestures mode, the configured three-finger binding takes precedence over Steam's
keyboard shortcut. Disabling it leaves three-finger taps unbound. Other touch modes
retain Steam's three-finger keyboard shortcut in its non-desktop UI.
Hold actions support a held button or a
single click. Pan bindings include WASD, arrow keys, middle-mouse movement,
left/right click-drag, and inverted variants. Pinch can emit the wheel, +/- keys,
or Page Up / Page Down. Mouse-button drags can use direct coordinates or relative
movement for games that recenter the pointer. Keyboard actions use Linux evdev
codes, including modifiers, function keys, navigation, punctuation and numpad keys.
Middle-mouse panning establishes its pointer origin at the center of the game image
before pressing middle, then emits relative motion, including inverted pan. Before
pointer lock, the compositor turns those deltas into absolute positions. A game that
recenters its own cursor can otherwise receive its first movement relative to the
old finger position and jump sideways. The center is set once per pan, and an already
locked pointer keeps its current origin. Motion and button release are immediate.

Button-only compositor inputs do not emit absolute motion. This avoids replaying
a stale cursor position when a game recenters during a middle click, hold, or pan
release. Absolute touch events still carry their coordinates, including on clicks.
This correction applies to button-only input across touch modes and physical mice.

Double taps must land within Android's density-scaled touch slop of the first tap.
The second touch is confirmed on lift, so it can become a drag, a configured hold,
or a multi-finger gesture without first injecting an unintended double click.
Nearby taps outside that small tolerance click their own positions, so neighboring
icons remain independently tappable even in quick succession. Matching double taps
reuse the first click's position and supply complete 50 ms presses
with at least 50 ms released between tap actions. Two- and three-finger mouse/key
taps use the same timed click path. A single tap remains immediate
when no previous click is finishing. Fast subsequent clicks queue rather than
shortening the first click or emitting a release and press in the same frame.
Cancellation clears queued clicks and releases active input.
The one-finger movement threshold applies only when starting a drag. Once active,
direct and relative drags follow every move, including through the starting point.

The multi-finger settling periods delay gesture recognition but still track finger
travel. Swiping or pinching beyond the gesture threshold cancels tap eligibility,
even if the fingers lift before the settling period ends. Travel beyond Android's
touch slop cancels pending holds, allowing slow movement to reach pan/zoom recognition.
Replacing a finger after a finished multi-finger gesture waits for all fingers to
lift before accepting another gesture.
Movement history in Android's batched touch events also counts toward tap and
hold cancellation. An out-and-back swipe cannot become a tap simply because the
latest sample returned to the starting point; jitter within the tolerance remains
eligible for a hold.

Direct two- and three-finger mouse drags press at the gesture's initial midpoint.
Replacing one of the tracked fingers during a three-finger drag resets its movement
baseline and preserves the cursor position. Subsequent movement continues from
there, without restarting the middle-mouse pan origin.

After recognition, mouse pans retain small deltas. Key pans accumulate small moves
until they establish a direction, including diagonal motion. Pinch zoom accumulates
fractional travel while keeping one output step per large movement event. Reversing
direction drops the previous direction's remainder; release, cancellation, and
gesture-type changes clear the accumulators so old motion is not replayed later.

Optional recognized-gesture feedback helps check bindings and movement thresholds.
The former immediate touch-hold, click-highlight, and gesture-cursor options have
been removed. Their old JSON fields are ignored when loading existing settings.

## Device checks

1. First verify Direct touch and Touchpad still work in Steam Big Picture.
2. Select Gestures, optionally enable recognized-gesture feedback, then
   check taps, two-finger right clicks and double clicks in a game with a pointer.
   Double tap quickly and at a normal pace, with slight finger drift. Check a
   third rapid tap, and repeat with single tap disabled or mapped to a keyboard key.
   Tap neighboring icons quickly; each must receive one click at its own position.
   Tap, then immediately drag or use an enabled long press at the same position.
   Watch for extra left clicks when the last finger lifts after a multi-finger gesture.
3. Drag a selection box and sliders. Check a quick drag followed immediately by
   lift; the button must release. Repeat with right-click drag configured.
   Shrink a selection box back through its starting point and drag back and forth
   on a minimap. Motion should continue smoothly near and across the origin.
4. In an RTS or strategy game, check two-finger arrow/WASD panning and pinch zoom.
   Panning should stop when a finger lifts, without leaving a direction held.
   For middle-mouse pan, start near both sides of the screen and repeat short and
   long drags in each direction. Check the first movement after placing the fingers,
   normal and inverted pan, and a quick lift immediately after movement starts.
   Also try gentle mouse/key panning, diagonals, slow pinching, and direction reversals.
5. Check two-finger hold, three-finger tap, and three-finger hold. Add or lift
   fingers in different orders, including a fourth finger during a three-finger
   hold. Each held input must release when the gesture ends.
   During a three-finger pan, add a fourth finger and lift one of the original
   three. The view should stay still until the remaining fingers move.
6. Bind a hold gesture to Middle click. Check both Hold button and Click once,
   quick releases, and repeated holds near both sides of the screen. The view
   should stay still without drag movement. Also check ordinary left/right clicks,
   box selection, and direct/relative drag in the other touch modes.
7. Hold an on-screen controller button and use a screen gesture, then reverse
   the order. Controller fingers must not count toward the screen gesture.
8. Open the drawer or keyboard, background the app, change touch mode, rotate or
   fold the display during a hold. Resume and confirm no button or key is stuck.
9. Save a game override, switch games, and restart the session. Confirm the
   override returns for its game, other games inherit defaults, and removing the
   override restores the defaults.

## Implementation

The recognizer emits outputs through an interface. SessionActivity maps those
outputs to DroidDeck's existing compositor mouse, wheel and keyboard functions;
relative movement uses the current output-to-view scale. The native compositor
suppresses synthetic motion for button-only inputs; Steam Input device code is
unchanged. A touch listener on the game surface lets
FrameLayout split pointers between that surface and controller overlays.

Input cleanup runs on gesture cancellation, gesture-configuration updates, input-mode
changes, focus loss, display-configuration changes, surface resizing or destruction,
and activity pause. Gesture keyboard
ownership is tracked separately from hardware and on-screen keyboard ownership.

The focused regression tests can run without native preparation:

```sh
./gradlew -PskipRust=true :app:testDebugUnitTest \
  --tests 'com.droiddeck.launcher.input.TouchscreenGesturesTest' \
  --tests 'com.droiddeck.launcher.input.GesturePrefsTest' \
  --tests 'com.droiddeck.launcher.input.GestureRoutingTest' \
  --tests 'com.droiddeck.launcher.input.SessionGestureRoutingTest' \
  --tests 'com.droiddeck.launcher.input.TouchpadGesturesTest' \
  --tests 'com.droiddeck.launcher.input.InputSpaceTest' \
  --tests 'com.droiddeck.launcher.input.ThreeFingerTapTest' \
  --tests 'com.droiddeck.launcher.ui.TouchGestureDialogTest' \
  --tests 'com.droiddeck.launcher.ui.GestureActionsTest'
```

The tap regressions check press and release timing, stable click coordinates,
rapid third taps, and cancellation. The pan regressions model the compositor's absolute position before the client takes
lock, verify normal and inverted motion, and check that the pan origin is prepared
before the button press, once per drag. Left-click drag keeps its own touch origin.
Dialog tests cover searching and selecting keys, saving and cancelling, keeping
drafts across tabs and scopes, keeping an open editor attached to its original
game, and reaching navigation and Save on a narrow window at 150% text size.
