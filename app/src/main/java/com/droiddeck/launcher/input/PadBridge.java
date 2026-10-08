package com.droiddeck.launcher.input;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.droiddeck.launcher.session.SessionState;

import java.io.File;

/**
 * A physical controller, republished as a synthetic evdev device the Steam client can see.
 *
 * <p>Android hands a gamepad to the foreground activity as key and motion events; the Steam client
 * inside the runtime never sees that. What it does scan is {@code /dev/input}, which the session's
 * interposer (libfakeinput.so) serves out of the shared-memory rings written here. So the path is:
 * Android event → {@link PadState} → {@link FakeInputWriter} ring → interposer → evdev node →
 * SDL → Big Picture.
 *
 * <p>The identity the interposer reports is an Xbox 360 pad, which is what makes SDL apply a known
 * mapping without the user configuring anything. In a Steam session it is a Steam Deck controller
 * to the client instead ({@link com.droiddeck.launcher.session.SteamDeckPad}), which has a Quick
 * Access button of its own; an Xbox pad has none, so there the client's menu is reached with the
 * Guide-then-A chord the client itself answers.
 */
public final class PadBridge {
    private static final String TAG = "PadBridge";
    /** Slot 0 is the one the session exports; extra slots would each show as another pad. */
    private static final int SLOT = 0;
    private static final float DEAD_ZONE = 0.12f;
    private static final int[] JOYSTICK_AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS
    };
    private static final int[] CONTROLLER_BUTTONS = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2
    };
    // The client takes A as part of the chord only once it has had Guide held for a while, and a
    // client starved of CPU needs longer. Too short, and it acts on A as well, selecting whatever it
    // had focused before opening QAM: with 80 ms of lead that was 3 times in 20 at rest, and 250 ms
    // none in 20; with the session down to 1 fps a fixed 400 ms still let 3 in 15 through, where
    // 1000 ms let none. The lead keeps 80 ms when frames are quick, for a QAM that feels immediate,
    // and stretches to eight frames when they are slow.
    private static final long QAM_GUIDE_LEAD_MIN_MS = 80;
    private static final long QAM_GUIDE_LEAD_MAX_MS = 1500;
    private static final int QAM_GUIDE_LEAD_FRAMES = 8;
    private static final long QAM_A_HOLD_MS = 200;
    private static final long QAM_GUIDE_TAIL_MS = 200;
    // Off the main thread, so a busy UI cannot shorten or stretch the steps.
    private static final Handler chordHandler;
    static {
        android.os.HandlerThread thread = new android.os.HandlerThread("qam-chord", android.os.Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        chordHandler = new Handler(thread.getLooper());
    }

    private final FakeInputWriter writer;
    private final PadState state = new PadState();
    private final PadState effectiveState = new PadState();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean open;
    private boolean systemGuidePressed;
    private boolean systemQamPressed;
    private boolean qamChordActive;
    private boolean qamSyntheticAPressed;
    /** The Deck controller's Quick Access button, held for one tap. */
    private boolean qamTapPressed;
    private int qamChordGeneration;
    /** Told on the main thread when a player uses the pad or the on-screen controls; see [setOnPlayerInput]. */
    private volatile Runnable onPlayerInput;
    private final java.util.concurrent.atomic.AtomicBoolean playerInputPosted = new java.util.concurrent.atomic.AtomicBoolean();

    // What reached the pad over the last stats window, so a report of "the pad does nothing" or
    // "touch and the pad don't work together" shows in the app log whether input arrived, from
    // which device, and whether it got to the ring. Logged only for a window that had input.
    private static final long STATS_WINDOW_MS = 10_000;
    private int statButtons, statAxes, statOnScreen, statDropped;
    private String statDevice;
    private boolean statsScheduled;
    private final java.util.Set<Integer> seenDevices = new java.util.HashSet<>();
    private int lastDeviceId = Integer.MIN_VALUE;

    /** No physical controller is driving the pad: nothing yet, or the on-screen controls were last. */
    public static final int NO_CONTROLLER = -1;
    /**
     * The Android input device id of the physical controller that last drove the pad, or
     * {@link #NO_CONTROLLER}. Every pad feeds the one exported slot, so this is how a game's rumble
     * on that slot finds the motors in the player's hands (RumbleComponent).
     */
    private static volatile int activeControllerId = NO_CONTROLLER;

    public static int activeControllerId() { return activeControllerId; }

    public PadBridge(File fakeInputDir) {
        writer = new FakeInputWriter(fakeInputDir.getAbsolutePath(), SLOT);
    }

    /** Opens the ring; safe to call more than once. */
    public synchronized boolean start() {
        if (!open) {
            open = writer.open();
            Log.i(TAG, "ring slot " + SLOT + (open ? " open" : " NOT open"));
        }
        return open;
    }

    public synchronized void stop() {
        activeControllerId = NO_CONTROLLER;
        systemGuidePressed = false;
        systemQamPressed = false;
        qamChordActive = false;
        qamSyntheticAPressed = false;
        qamTapPressed = false;
        qamChordGeneration++;
        if (open) {
            state.clear();
            writer.writePad(state);
            writer.close();
            open = false;
        }
    }

    /** True when the device this event came from is a gamepad or joystick, not the touchscreen. */
    public static boolean isFromController(InputDevice device) {
        if (device == null) return false;
        // A virtual device is the system's own synthetic input, not a pad somebody is holding -
        // counting it would hide the on-screen controls with nothing to replace them.
        if (device.isVirtual()) return false;
        int sources = device.getSources();
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        if ((sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false;
        if (device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;
        // Some composite keyboards advertise joystick input without any sticks or pad buttons.
        // A keyboard's mouse ranges do not establish a joystick; a real hybrid's joystick ranges
        // or physical controller buttons do. GAMEPAD above remains authoritative even with keys.
        for (int axis : JOYSTICK_AXES) {
            InputDevice.MotionRange range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
            if (range != null && range.getMax() > range.getMin()) return true;
        }
        for (boolean present : device.hasKeys(CONTROLLER_BUTTONS)) {
            if (present) return true;
        }
        return false;
    }

    /** Whether this key belongs to the pad, rather than a keyboard on the same input device. */
    public static boolean isControllerKeyEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null || device.isVirtual()) return false;
        // An explicit pad button is unambiguous even when a hybrid omits its capabilities.
        if (KeyEvent.isGamepadButton(event.getKeyCode())) {
            return device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                    || device.supportsSource(InputDevice.SOURCE_JOYSTICK);
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HOME:
                if (!isFromController(device)) return false;
                // Without GAMEPAD, an alphabetic hybrid's keyboard arrows remain keyboard keys.
                // Its ambiguous navigation keys need explicit pad/joystick event provenance;
                // DPAD alone also describes ordinary keyboard arrows.
                return device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                        || device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC
                        || event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                        || event.isFromSource(InputDevice.SOURCE_JOYSTICK);
            default: return false;
        }
    }

    /** Mouse motion on a composite controller still belongs to the pointer, not its sticks. */
    public static boolean isControllerMotionEvent(MotionEvent event) {
        InputDevice device = event.getDevice();
        // An actual joystick event, like a pad button, survives a hybrid's missing capabilities.
        return event.isFromSource(InputDevice.SOURCE_JOYSTICK) && device != null && !device.isVirtual()
                && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                || device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }

    /** Whether any real controller is attached right now. */
    public static boolean anyControllerConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            if (isFromController(InputDevice.getDevice(id))) return true;
        }
        return false;
    }

    /**
     * Called (main thread) when a player presses a button, pushes a stick, trigger or d-pad past
     * halfway, or uses the on-screen controls - the session hides its mouse cursor then. Resting
     * sticks and trigger noise do not count.
     */
    public void setOnPlayerInput(Runnable listener) {
        onPlayerInput = listener;
    }

    private void notePlayerInput() {
        Runnable listener = onPlayerInput;
        if (listener == null || !playerInputPosted.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            playerInputPosted.set(false);
            listener.run();
        });
    }

    /** @return true when the event was a pad button and has been consumed. */
    public synchronized boolean onKeyEvent(KeyEvent event) {
        if (!isControllerKeyEvent(event)) return false;
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: state.press(0, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_B: state.press(1, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_X: state.press(2, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_Y: state.press(3, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_L1: state.press(4, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_R1: state.press(5, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK: state.press(6, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU: state.press(7, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: state.press(8, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: state.press(9, pressed); break;
            // The client's own in-game menu is opened by this one; the interposer publishes it as
            // BTN_MODE, which SDL reports as the "guide" button.
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_HOME: state.press(PadState.GUIDE, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_L2: state.leftTrigger = pressed ? 1f : 0f; break;
            case KeyEvent.KEYCODE_BUTTON_R2: state.rightTrigger = pressed ? 1f : 0f; break;
            case KeyEvent.KEYCODE_DPAD_UP: state.up = pressed; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: state.right = pressed; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: state.down = pressed; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: state.left = pressed; break;
            default: return false;
        }
        noteDevice(event.getDevice());
        if (pressed) notePlayerInput();
        statButtons++;
        scheduleStats();
        publish();
        return true;
    }

    /** @return true when the event was a pad's sticks/triggers and has been consumed. */
    public synchronized boolean onMotionEvent(MotionEvent event) {
        if (!isControllerMotionEvent(event)) return false;
        if (event.getAction() != MotionEvent.ACTION_MOVE) return false;
        noteDevice(event.getDevice());
        statAxes++;
        scheduleStats();
        state.leftX = axis(event, MotionEvent.AXIS_X);
        // Android's Y axis grows downwards and evdev's ABS_Y does too, so no flip here: what the
        // pad reports as "down" is what the client is told.
        state.leftY = axis(event, MotionEvent.AXIS_Y);
        state.rightX = axis(event, MotionEvent.AXIS_Z);
        state.rightY = axis(event, MotionEvent.AXIS_RZ);
        float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        // Some pads only report the triggers on BRAKE/GAS.
        if (lt == 0f) lt = event.getAxisValue(MotionEvent.AXIS_BRAKE);
        if (rt == 0f) rt = event.getAxisValue(MotionEvent.AXIS_GAS);
        state.leftTrigger = lt;
        state.rightTrigger = rt;
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        state.up = hatY < -0.5f;
        state.right = hatX > 0.5f;
        state.down = hatY > 0.5f;
        state.left = hatX < -0.5f;
        if (Math.max(Math.max(Math.abs(state.leftX), Math.abs(state.leftY)), Math.max(Math.abs(state.rightX), Math.abs(state.rightY))) > 0.5f
                || lt > 0.5f || rt > 0.5f || state.up || state.right || state.down || state.left) {
            notePlayerInput();
        }
        publish();
        return true;
    }

    /**
     * The on-screen controls' way in: they mutate the same state a physical pad writes, so the
     * client only ever sees one device and a user can use both at once without them fighting.
     */
    public synchronized void applyTouch(java.util.function.Consumer<PadState> mutation) {
        mutation.accept(state);
        activeControllerId = NO_CONTROLLER;
        notePlayerInput();
        statOnScreen++;
        scheduleStats();
        publish();
    }

    /** Touch-only Steam and QAM buttons, merged with physical input without changing its state. */
    public synchronized void setSystemButtons(boolean guidePressed, boolean qamPressed) {
        if (systemGuidePressed == guidePressed && systemQamPressed == qamPressed) return;
        boolean qamStarted = qamPressed && !systemQamPressed;
        systemGuidePressed = guidePressed;
        systemQamPressed = qamPressed;
        // A Deck controller's QAM button is held for as long as the touch one is.
        if (qamStarted && !SessionState.getDeckPad()) startQamChord();
        else publish();
    }

    /**
     * Everything released and centred - for when the controller stops feeding the game (the
     * session drawer opened), so a button or stick held at that moment is not left down in it.
     */
    public synchronized void releaseAll() {
        state.clear();
        publish();
    }

    /** Opens the client's Quick Access menu: a tap of the Deck's button, or the Guide-then-A chord. */
    public synchronized void triggerQam() {
        if (!SessionState.getDeckPad()) {
            startQamChord();
            return;
        }
        if (qamTapPressed) return;
        qamTapPressed = true;
        int generation = qamChordGeneration;
        publish();
        chordHandler.postDelayed(() -> releaseQamTap(generation), QAM_A_HOLD_MS);
    }

    private synchronized void releaseQamTap(int generation) {
        if (generation != qamChordGeneration || !qamTapPressed) return;
        qamTapPressed = false;
        publish();
    }

    private void startQamChord() {
        if (qamChordActive) return;
        qamChordActive = true;
        int generation = ++qamChordGeneration;
        publish();
        chordHandler.postDelayed(() -> pressQamA(generation), qamGuideLeadMs());
    }

    static long qamGuideLeadMs() {
        long frame = com.droiddeck.launcher.wayland.WaylandCompositor.recentFrameIntervalMs();
        if (frame <= 0) return QAM_GUIDE_LEAD_MIN_MS;
        return Math.max(QAM_GUIDE_LEAD_MIN_MS, Math.min(QAM_GUIDE_LEAD_MAX_MS, frame * QAM_GUIDE_LEAD_FRAMES));
    }

    private synchronized void pressQamA(int generation) {
        if (generation != qamChordGeneration || !qamChordActive) return;
        qamSyntheticAPressed = true;
        publish();
        chordHandler.postDelayed(() -> releaseQamA(generation), QAM_A_HOLD_MS);
    }

    private synchronized void releaseQamA(int generation) {
        if (generation != qamChordGeneration || !qamChordActive) return;
        qamSyntheticAPressed = false;
        publish();
        chordHandler.postDelayed(() -> releaseQamGuide(generation), QAM_GUIDE_TAIL_MS);
    }

    private synchronized void releaseQamGuide(int generation) {
        if (generation != qamChordGeneration || !qamChordActive) return;
        qamChordActive = false;
        publish();
    }

    /** Per event, so the common case - the same pad as last time - is a single compare. */
    private void noteDevice(InputDevice device) {
        activeControllerId = device.getId();
        if (device.getId() == lastDeviceId) return;
        lastDeviceId = device.getId();
        statDevice = device.getName();
        if (seenDevices.add(device.getId())) {
            Log.i(TAG, String.format(java.util.Locale.ROOT, "first input from \"%s\" (%04x:%04x, id %d, sources 0x%x); ring %s, deck pad %b",
                    device.getName(), device.getVendorId(), device.getProductId(), device.getId(), device.getSources(),
                    open ? "open" : "not open yet", SessionState.getDeckPad()));
        }
    }

    private void scheduleStats() {
        if (statsScheduled) return;
        statsScheduled = true;
        mainHandler.postDelayed(this::logStats, STATS_WINDOW_MS);
    }

    private synchronized void logStats() {
        statsScheduled = false;
        Log.i(TAG, "[input] last 10 s: pad " + statButtons + " buttons, " + statAxes + " axis events"
                + (statDevice != null ? " (\"" + statDevice + "\")" : "")
                + ", on-screen " + statOnScreen
                + (statDropped > 0 ? ", " + statDropped + " NOT delivered (ring closed)" : "")
                + "; ring " + (open ? "open" : "closed") + ", deck pad " + SessionState.getDeckPad());
        statButtons = statAxes = statOnScreen = statDropped = 0;
    }

    private void publish() {
        if (!open && !start()) {
            statDropped++;
            return;
        }
        boolean guide = systemGuidePressed || qamChordActive;
        boolean qam = SessionState.getDeckPad() && (systemQamPressed || qamTapPressed);
        if (!guide && !qam) {
            writer.writePad(state);
            return;
        }
        effectiveState.copyFrom(state);
        if (guide) effectiveState.press(PadState.GUIDE, true);
        if (qam) effectiveState.press(PadState.QAM, true);
        if (qamSyntheticAPressed) effectiveState.press(PadState.A, true);
        writer.writePad(effectiveState);
    }

    private static float axis(MotionEvent event, int axis) {
        float value = event.getAxisValue(axis);
        return Math.abs(value) < DEAD_ZONE ? 0f : value;
    }
}
