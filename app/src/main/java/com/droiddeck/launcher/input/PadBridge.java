package com.droiddeck.launcher.input;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.droiddeck.launcher.session.SessionState;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;

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
 *
 * <p>Each further controller is another player: slots 1 to 3, an Xbox 360 pad each, which in a
 * Steam session only the client reads (libfakeinput) and hands games through Steam Input.
 */
public final class PadBridge {
    private static final String TAG = "PadBridge";
    /** Player slots: the session prepares a ring for each (SessionService). */
    public static final int SLOTS = 4;
    private static final float DEAD_ZONE = 0.12f;
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

    private final File fakeInputDir;
    private final FakeInputWriter writer;
    private final PadState state = new PadState();
    /** The input device id holding each slot, or {@link #NO_CONTROLLER}. */
    private final int[] owners = new int[SLOTS];
    private final FakeInputWriter[] playerWriters = new FakeInputWriter[SLOTS];
    private final PadState[] playerStates = new PadState[SLOTS];
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
    /** device id, key code and scan code of keys already logged as unmapped / as a grip. */
    private final java.util.Set<String> loggedKeys = new java.util.HashSet<>();

    /** Razer's USB vendor id: the Kishi line. */
    static final int RAZER_VENDOR = 0x1532;

    /** Linux scan codes of BTN_C and BTN_Z: the Kishi V3 Pro's L4 and R4 in its HID mode. */
    static final int SCAN_BTN_C = 306, SCAN_BTN_Z = 309;

    /**
     * A Razer Kishi's back buttons as Android reports them. On a Kishi V3 Pro in its HID mode
     * (1532:0724) the gamepad sends L4 as BTN_C (KEYCODE_BUTTON_C) and R4 as BTN_Z
     * (KEYCODE_BUTTON_Z), seen with getevent. In a Steam session they hold the Deck's L4 and R4
     * grips. (M1/M2 send nothing of their own: Razer Cortex can only make them copy another
     * button, e.g. C/Z.) Returns the DeckControls bit, or 0.
     */
    static int kishiGrip(int vendorId, int keyCode) {
        if (vendorId != RAZER_VENDOR) return 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_C: return DeckControls.L4;   // Kishi L4 (BTN_C)
            case KeyEvent.KEYCODE_BUTTON_Z: return DeckControls.R4;   // Kishi R4 (BTN_Z)
            default: return 0;
        }
    }

    /**
     * Android's fallback for an unhandled BUTTON_C / BUTTON_Z is DPAD_CENTER, carrying the same
     * scan code; from a Razer pad that would act as A / select (and, not being a pad key, would
     * be forwarded to the desktop as a keyboard key). Such an event is swallowed. Only from a
     * controller device: a keyboard's Enter is KEYCODE_ENTER, and BTN_C / BTN_Z are gamepad
     * scan codes no keyboard sends.
     */
    static boolean isKishiGripFallback(boolean fromController, int vendorId, int keyCode, int scanCode) {
        return fromController && vendorId == RAZER_VENDOR && keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                && (scanCode == SCAN_BTN_C || scanCode == SCAN_BTN_Z);
    }

    private static String gripName(int bit) {
        return bit == DeckControls.L4 ? "L4" : bit == DeckControls.R4 ? "R4" : bit == DeckControls.L5 ? "L5" : bit == DeckControls.R5 ? "R5" : "?";
    }

    /** Logs a key from a pad (or a Razer device) once per device, key code and scan code. */
    private void logKeyOnce(KeyEvent event, String what) {
        InputDevice d = event.getDevice();
        String key = event.getDeviceId() + ":" + event.getKeyCode() + ":" + event.getScanCode() + ":" + what;
        if (!loggedKeys.add(key)) return;
        Log.i(TAG, String.format(java.util.Locale.ROOT, "key %s: %s (code %d, scan %d) from \"%s\" (%04x:%04x, id %d, sources 0x%x); deck pad %b",
                what, KeyEvent.keyCodeToString(event.getKeyCode()), event.getKeyCode(), event.getScanCode(),
                d != null ? d.getName() : "?", d != null ? d.getVendorId() : 0, d != null ? d.getProductId() : 0,
                event.getDeviceId(), d != null ? d.getSources() : 0, SessionState.getDeckPad()));
    }
    private int lastDeviceId = Integer.MIN_VALUE;

    /** No physical controller is driving the pad: nothing yet, or the on-screen controls were last. */
    public static final int NO_CONTROLLER = -1;
    /**
     * The Android input device id of the physical controller that last drove any slot, or
     * {@link #NO_CONTROLLER}: where rumble goes when it names no slot of ours (RumbleComponent).
     */
    private static volatile int activeControllerId = NO_CONTROLLER;
    /** Per slot, the controller its rumble belongs to; slot 0's is none while the on-screen controls lead. */
    private static final AtomicIntegerArray slotControllers = new AtomicIntegerArray(SLOTS);
    static {
        for (int slot = 0; slot < SLOTS; slot++) slotControllers.set(slot, NO_CONTROLLER);
    }

    public static int activeControllerId() { return activeControllerId; }

    /** The controller playing in this ring slot, or the last one used for any other slot. */
    public static int controllerForSlot(int slot) {
        return slot >= 0 && slot < SLOTS ? slotControllers.get(slot) : activeControllerId;
    }

    public PadBridge(File fakeInputDir) {
        this.fakeInputDir = fakeInputDir;
        writer = new FakeInputWriter(fakeInputDir.getAbsolutePath(), 0);
        Arrays.fill(owners, NO_CONTROLLER);
        for (int slot = 1; slot < SLOTS; slot++) playerStates[slot] = new PadState();
    }

    /** Opens the ring; safe to call more than once. */
    public synchronized boolean start() {
        if (!open) {
            open = writer.open();
            Log.i(TAG, "ring slot 0" + (open ? " open" : " NOT open"));
        }
        return open;
    }

    public synchronized void stop() {
        activeControllerId = NO_CONTROLLER;
        for (int slot = 0; slot < SLOTS; slot++) {
            owners[slot] = NO_CONTROLLER;
            slotControllers.set(slot, NO_CONTROLLER);
            if (playerWriters[slot] != null) {
                playerWriters[slot].destroy();
                playerWriters[slot] = null;
            }
            if (playerStates[slot] != null) playerStates[slot].clear();
        }
        systemGuidePressed = false;
        systemQamPressed = false;
        qamChordActive = false;
        qamSyntheticAPressed = false;
        qamTapPressed = false;
        qamChordGeneration++;
        DeckControls.INSTANCE.releasePadGrips();
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
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** A Bluetooth keyboard can share a device with a joystick. Classify the key, not just
     * the device, so its letters and modifiers still reach the desktop keyboard. */
    public static boolean isControllerKey(KeyEvent event) {
        return isFromController(event.getDevice())
                && isPadKey(event.getKeyCode(), event.getDevice().getKeyboardType());
    }

    public static boolean isPadKey(int keyCode, int keyboardType) {
        if ((keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE)
                || (keyCode >= KeyEvent.KEYCODE_BUTTON_1 && keyCode <= KeyEvent.KEYCODE_BUTTON_16)) return true;
        if (keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MENU: return true;
            default: return false;
        }
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
        InputDevice device = event.getDevice();
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
        if (device != null && isKishiGripFallback(isFromController(device), device.getVendorId(), event.getKeyCode(), event.getScanCode())) {
            if (pressed) logKeyOnce(event, "swallowed (fallback of a Kishi L4/R4)");
            return true;
        }
        // A Kishi's L4/R4 (pad keys from the pad itself): only for a Deck controller, which has
        // the grips to put them on.
        int grip = device != null && SessionState.getDeckPad() && isControllerKey(event) ? kishiGrip(device.getVendorId(), event.getKeyCode()) : 0;
        if (grip != 0) {
            if (pressed) logKeyOnce(event, "-> Deck " + gripName(grip));
            int slot = slotFor(device);
            noteDevice(device, slot);
            if (event.getRepeatCount() == 0 || !pressed) DeckControls.INSTANCE.setPadGrip(grip, pressed);
            if (pressed) notePlayerInput();
            statButtons++;
            scheduleStats();
            return true;
        }
        if (!isControllerKey(event) || device == null) {
            // A pad's key that is not a pad key (e.g. a Razer pad's extra buttons) is logged once;
            // a keyboard's keys never are.
            if (pressed && device != null && isFromController(device) && device.getVendorId() == RAZER_VENDOR) logKeyOnce(event, "unmapped (Razer pad, not a pad key)");
            return false;
        }
        int slot = slotFor(device);
        PadState pad = stateFor(slot);
        noteDevice(device, slot);
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: pad.press(0, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_B: pad.press(1, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_X: pad.press(2, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_Y: pad.press(3, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_L1: pad.press(4, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_R1: pad.press(5, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK: pad.press(6, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU: pad.press(7, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: pad.press(8, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: pad.press(9, pressed); break;
            // The client's own in-game menu is opened by this one; the interposer publishes it as
            // BTN_MODE, which SDL reports as the "guide" button.
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_HOME: pad.press(PadState.GUIDE, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_L2: pad.leftTrigger = pressed ? 1f : 0f; break;
            case KeyEvent.KEYCODE_BUTTON_R2: pad.rightTrigger = pressed ? 1f : 0f; break;
            case KeyEvent.KEYCODE_DPAD_UP: pad.up = pressed; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: pad.right = pressed; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: pad.down = pressed; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: pad.left = pressed; break;
            default:
                if (pressed) logKeyOnce(event, "unmapped");
                return false;
        }
        if (pressed) notePlayerInput();
        statButtons++;
        scheduleStats();
        publish(slot);
        return true;
    }

    /** @return true when the event was a pad's sticks/triggers and has been consumed. */
    public synchronized boolean onMotionEvent(MotionEvent event) {
        if (!isFromController(event.getDevice())) return false;
        if (event.getAction() != MotionEvent.ACTION_MOVE) return false;
        int slot = slotFor(event.getDevice());
        PadState pad = stateFor(slot);
        noteDevice(event.getDevice(), slot);
        statAxes++;
        scheduleStats();
        pad.leftX = axis(event, MotionEvent.AXIS_X);
        // Android's Y axis grows downwards and evdev's ABS_Y does too, so no flip here: what the
        // pad reports as "down" is what the client is told.
        pad.leftY = axis(event, MotionEvent.AXIS_Y);
        pad.rightX = axis(event, MotionEvent.AXIS_Z);
        pad.rightY = axis(event, MotionEvent.AXIS_RZ);
        float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        // Some pads only report the triggers on BRAKE/GAS.
        if (lt == 0f) lt = event.getAxisValue(MotionEvent.AXIS_BRAKE);
        if (rt == 0f) rt = event.getAxisValue(MotionEvent.AXIS_GAS);
        pad.leftTrigger = lt;
        pad.rightTrigger = rt;
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        pad.up = hatY < -0.5f;
        pad.right = hatX > 0.5f;
        pad.down = hatY > 0.5f;
        pad.left = hatX < -0.5f;
        if (Math.max(Math.max(Math.abs(pad.leftX), Math.abs(pad.leftY)), Math.max(Math.abs(pad.rightX), Math.abs(pad.rightY))) > 0.5f
                || lt > 0.5f || rt > 0.5f || pad.up || pad.right || pad.down || pad.left) {
            notePlayerInput();
        }
        publish(slot);
        return true;
    }

    /**
     * The on-screen controls' way in: they mutate the same state a physical pad writes, so the
     * client only ever sees one device and a user can use both at once without them fighting.
     */
    public synchronized void applyTouch(java.util.function.Consumer<PadState> mutation) {
        mutation.accept(state);
        activeControllerId = NO_CONTROLLER;
        slotControllers.set(0, NO_CONTROLLER);
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
        DeckControls.INSTANCE.releasePadGrips();
        state.clear();
        publish();
        for (int slot = 1; slot < SLOTS; slot++) {
            if (playerWriters[slot] == null) continue;
            playerStates[slot].clear();
            publish(slot);
        }
    }

    /** A controller went away: its player's buttons are let go, and a further player's pad is unplugged. */
    public synchronized void onDeviceRemoved(int deviceId) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (owners[slot] == deviceId) free(slot);
        }
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

    /** The slot this controller plays in: the one it has, else the first free one, else player 1's. */
    private int slotFor(InputDevice device) {
        int id = device.getId();
        if (owners[0] == id) return 0;
        for (int slot = 1; slot < SLOTS; slot++) {
            if (owners[slot] == id) return slot;
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            if (owners[slot] != NO_CONTROLLER && InputDevice.getDevice(owners[slot]) == null) free(slot);
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            if (owners[slot] != NO_CONTROLLER) continue;
            owners[slot] = id;
            Log.i(TAG, "\"" + device.getName() + "\" (id " + id + ") is player " + (slot + 1));
            return slot;
        }
        return 0;
    }

    private PadState stateFor(int slot) {
        return slot == 0 ? state : playerStates[slot];
    }

    private void free(int slot) {
        Log.i(TAG, "player " + (slot + 1) + " (id " + owners[slot] + ") left");
        owners[slot] = NO_CONTROLLER;
        slotControllers.set(slot, NO_CONTROLLER);
        if (slot == 0) {
            state.clear();
            publish();
            return;
        }
        playerStates[slot].clear();
        if (playerWriters[slot] != null) {
            playerWriters[slot].destroy();
            playerWriters[slot] = null;
        }
    }

    /** Per event, so the common case - the same pad as last time - is a single compare. */
    private void noteDevice(InputDevice device, int slot) {
        activeControllerId = device.getId();
        slotControllers.set(slot, device.getId());
        if (device.getId() == lastDeviceId) return;
        lastDeviceId = device.getId();
        statDevice = device.getName();
        if (seenDevices.add(device.getId())) {
            Log.i(TAG, String.format(java.util.Locale.ROOT, "first input from \"%s\" (%04x:%04x, id %d, sources 0x%x); slot %d, ring %s, deck pad %b",
                    device.getName(), device.getVendorId(), device.getProductId(), device.getId(), device.getSources(),
                    slot, open ? "open" : "not open yet", SessionState.getDeckPad()));
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

    private void publish(int slot) {
        if (slot == 0) {
            publish();
            return;
        }
        FakeInputWriter player = playerWriters[slot];
        if (player == null) {
            player = new FakeInputWriter(fakeInputDir.getAbsolutePath(), slot);
            if (!player.open()) {
                statDropped++;
                return;
            }
            playerWriters[slot] = player;
        }
        player.writePad(playerStates[slot]);
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
