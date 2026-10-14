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

public final class PadBridge {
    private static final String TAG = "PadBridge";
    public static final int SLOTS = 4;
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
    private static final long QAM_GUIDE_LEAD_MIN_MS = 80;
    private static final long QAM_GUIDE_LEAD_MAX_MS = 1500;
    private static final int QAM_GUIDE_LEAD_FRAMES = 8;
    private static final long QAM_A_HOLD_MS = 200;
    private static final long QAM_GUIDE_TAIL_MS = 200;
    private static final Handler chordHandler;
    static {
        android.os.HandlerThread thread = new android.os.HandlerThread("qam-chord", android.os.Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        chordHandler = new Handler(thread.getLooper());
    }

    private final File fakeInputDir;
    private final FakeInputWriter writer;
    private final PadState state = new PadState();
    private final int[] owners = new int[SLOTS];
    private final FakeInputWriter[] playerWriters = new FakeInputWriter[SLOTS];
    private final PadState[] playerStates = new PadState[SLOTS];
    private final PadState effectiveState = new PadState();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean open;
    private volatile boolean selectSteam;
    private boolean systemGuidePressed;
    private boolean systemQamPressed;
    private boolean qamChordActive;
    private boolean qamSyntheticAPressed;
    private boolean qamTapPressed;
    private int qamChordGeneration;
    private volatile Runnable onPlayerInput;
    private final java.util.concurrent.atomic.AtomicBoolean playerInputPosted = new java.util.concurrent.atomic.AtomicBoolean();

    private static final long STATS_WINDOW_MS = 10_000;
    private int statButtons, statAxes, statOnScreen, statDropped;
    private String statDevice;
    private boolean statsScheduled;
    private final java.util.Set<Integer> seenDevices = new java.util.HashSet<>();
    private int lastDeviceId = Integer.MIN_VALUE;

    public static final int NO_CONTROLLER = -1;
    private static volatile int activeControllerId = NO_CONTROLLER;
    private static final AtomicIntegerArray slotControllers = new AtomicIntegerArray(SLOTS);
    static {
        for (int slot = 0; slot < SLOTS; slot++) slotControllers.set(slot, NO_CONTROLLER);
    }

    public static int activeControllerId() { return activeControllerId; }

    public static int controllerForSlot(int slot) {
        return slot >= 0 && slot < SLOTS ? slotControllers.get(slot) : activeControllerId;
    }

    public PadBridge(File fakeInputDir) {
        this.fakeInputDir = fakeInputDir;
        writer = new FakeInputWriter(fakeInputDir.getAbsolutePath(), 0);
        Arrays.fill(owners, NO_CONTROLLER);
        for (int slot = 1; slot < SLOTS; slot++) playerStates[slot] = new PadState();
    }

    public void setSelectSteam(boolean on) {
        selectSteam = on;
    }

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
        if (open) {
            state.clear();
            writer.writePad(state);
            writer.close();
            open = false;
        }
    }

    public static boolean isFromController(InputDevice device) {
        if (device == null) return false;
        if (device.isVirtual()) return false;
        int sources = device.getSources();
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        if ((sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false;
        if (device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;
        for (int axis : JOYSTICK_AXES) {
            InputDevice.MotionRange range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
            if (range != null && range.getMax() > range.getMin()) return true;
        }
        for (boolean present : device.hasKeys(CONTROLLER_BUTTONS)) {
            if (present) return true;
        }
        return false;
    }

    public static boolean isControllerKeyEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null || device.isVirtual()) return false;
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
                return device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                        || device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC
                        || event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                        || event.isFromSource(InputDevice.SOURCE_JOYSTICK);
            default: return false;
        }
    }

    public static boolean isControllerMotionEvent(MotionEvent event) {
        InputDevice device = event.getDevice();
        return event.isFromSource(InputDevice.SOURCE_JOYSTICK) && device != null && !device.isVirtual()
                && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                || device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }

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

    public static boolean anyControllerConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            if (isFromController(InputDevice.getDevice(id))) return true;
        }
        return false;
    }

    public synchronized void syncConnectedControllers() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (isFromController(device)) publish(slotFor(device));
        }
    }

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

    public synchronized boolean onKeyEvent(KeyEvent event) {
        if (!isControllerKeyEvent(event)) return false;
        int slot = slotFor(event.getDevice());
        PadState pad = stateFor(slot);
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: pad.press(0, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_B: pad.press(1, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_X: pad.press(2, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_Y: pad.press(3, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_L1: pad.press(4, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_R1: pad.press(5, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (selectSteam) pad.press(PadState.GUIDE, pressed);
                else pad.press(6, pressed);
                break;
            case KeyEvent.KEYCODE_BACK: pad.press(6, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU: pad.press(7, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: pad.press(8, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: pad.press(9, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_HOME: pad.press(PadState.GUIDE, pressed); break;
            case KeyEvent.KEYCODE_BUTTON_L2: pad.leftTrigger = pressed ? 1f : 0f; break;
            case KeyEvent.KEYCODE_BUTTON_R2: pad.rightTrigger = pressed ? 1f : 0f; break;
            case KeyEvent.KEYCODE_DPAD_UP: pad.up = pressed; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: pad.right = pressed; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: pad.down = pressed; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: pad.left = pressed; break;
            default: return false;
        }
        noteDevice(event.getDevice(), slot);
        if (pressed) notePlayerInput();
        statButtons++;
        scheduleStats();
        publish(slot);
        return true;
    }

    public synchronized boolean onMotionEvent(MotionEvent event) {
        if (!isControllerMotionEvent(event)) return false;
        if (event.getAction() != MotionEvent.ACTION_MOVE) return false;
        int slot = slotFor(event.getDevice());
        PadState pad = stateFor(slot);
        noteDevice(event.getDevice(), slot);
        statAxes++;
        scheduleStats();
        pad.leftX = axis(event, MotionEvent.AXIS_X);
        pad.leftY = axis(event, MotionEvent.AXIS_Y);
        pad.rightX = axis(event, MotionEvent.AXIS_Z);
        pad.rightY = axis(event, MotionEvent.AXIS_RZ);
        float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
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

    public synchronized void applyTouch(java.util.function.Consumer<PadState> mutation) {
        mutation.accept(state);
        activeControllerId = NO_CONTROLLER;
        slotControllers.set(0, NO_CONTROLLER);
        notePlayerInput();
        statOnScreen++;
        scheduleStats();
        publish();
    }

    public synchronized void applyExternal(java.util.function.Consumer<PadState> mutation, boolean changed) {
        mutation.accept(state);
        if (changed) {
            activeControllerId = NO_CONTROLLER;
            notePlayerInput();
            statButtons++;
            scheduleStats();
        }
        publish();
    }

    public synchronized void setSystemButtons(boolean guidePressed, boolean qamPressed) {
        if (systemGuidePressed == guidePressed && systemQamPressed == qamPressed) return;
        boolean qamStarted = qamPressed && !systemQamPressed;
        systemGuidePressed = guidePressed;
        systemQamPressed = qamPressed;
        if (qamStarted && !nativeQam()) startQamChord();
        else publish();
    }

    public synchronized void releaseAll() {
        state.clear();
        publish();
        for (int slot = 1; slot < SLOTS; slot++) {
            if (playerWriters[slot] == null) continue;
            playerStates[slot].clear();
            publish(slot);
        }
    }

    public synchronized void onDeviceRemoved(int deviceId) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (owners[slot] == deviceId) free(slot);
        }
    }

    private static boolean nativeQam() {
        return SessionState.getDeckPad() && FakeInputWriter.padHasQam();
    }

    public synchronized void triggerQam() {
        if (!nativeQam()) {
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
        boolean qam = nativeQam() && (systemQamPressed || qamTapPressed);
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
