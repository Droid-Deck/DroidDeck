package com.droiddeck.launcher.input;

import android.os.Build;
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
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
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
    private static final int[] ROUTED_PASS_THROUGH = {PadState.GUIDE, PadState.QAM, PadState.SELECT};
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
    private final int[] partners = new int[SLOTS];
    private volatile java.util.function.IntPredicate excluded = id -> false;
    private final FakeInputWriter[] playerWriters = new FakeInputWriter[SLOTS];
    private final PadState[] playerStates = new PadState[SLOTS];
    private final PadState effectiveState = new PadState();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean open;
    private volatile boolean selectSteam;
    private volatile boolean builtinPad = true;
    private TouchRoute touchRoute;
    private final PadState routedTouch = new PadState();
    private boolean systemGuidePressed;
    private boolean systemQamPressed;
    private boolean backQamPressed;
    private boolean homeGuidePressed;
    private final PadState lastBackQamReport = new PadState();
    private final PadState lastWrittenReport = new PadState();
    private boolean backQamAxesBlocked;
    private final java.util.Set<Runnable> backQamWrites = new java.util.HashSet<>();
    private long backQamDelayMs, backQamWriteAt;
    private boolean qamChordActive;
    private int chordButton = -1;
    private boolean chordButtonPressed;
    private boolean qamTapPressed;
    private int qamChordGeneration;
    private volatile Runnable onPlayerInput;
    private Runnable onChordInput;
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
    private static final AtomicIntegerArray slotJoyCons = new AtomicIntegerArray(SLOTS * 2);
    static {
        for (int slot = 0; slot < SLOTS; slot++) slotControllers.set(slot, NO_CONTROLLER);
        for (int i = 0; i < SLOTS * 2; i++) slotJoyCons.set(i, NO_CONTROLLER);
    }

    public static int activeControllerId() { return activeControllerId; }

    public static int controllerForSlot(int slot) {
        return slot >= 0 && slot < SLOTS ? slotControllers.get(slot) : activeControllerId;
    }

    public static int joyConForSlot(int slot, int side) {
        return slot >= 0 && slot < SLOTS && (side == JoyCon.LEFT || side == JoyCon.RIGHT)
                ? slotJoyCons.get(slot * 2 + side) : NO_CONTROLLER;
    }

    public PadBridge(File fakeInputDir) {
        this.fakeInputDir = fakeInputDir;
        writer = new FakeInputWriter(fakeInputDir.getAbsolutePath(), 0);
        Arrays.fill(owners, NO_CONTROLLER);
        Arrays.fill(partners, NO_CONTROLLER);
        for (int slot = 1; slot < SLOTS; slot++) playerStates[slot] = new PadState();
    }

    public void setSelectSteam(boolean on) {
        selectSteam = on;
    }

    public void setExcluded(java.util.function.IntPredicate devices) {
        excluded = devices;
    }

    public synchronized void setBuiltinPad(boolean on) {
        boolean changed = builtinPad != on;
        builtinPad = on;
        if (changed && !on) resetBuiltin();
    }

    private static volatile int firstSlot;

    public static void setFirstSlot(int slot) {
        firstSlot = slot;
    }

    private int firstPlayerSlot() {
        return firstSlot;
    }

    private void resetBuiltin() {
        homeGuidePressed = false;
        clearBackQamWrites();
        routedTouch.clear();
        systemGuidePressed = false;
        systemQamPressed = false;
        backQamPressed = false;
        qamTapPressed = false;
        qamChordActive = false;
        chordButtonPressed = false;
        qamChordGeneration++;
        chordHandler.removeCallbacksAndMessages(null);
        state.clear();
        publish();
    }

    public synchronized boolean start() {
        if (!open) {
            open = writer.open();
            Log.i(TAG, "ring slot 0" + (open ? " open" : " NOT open"));
        }
        return open;
    }

    public synchronized void stop() {
        homeGuidePressed = false;
        clearBackQamWrites();
        releaseBackPaddles();
        activeControllerId = NO_CONTROLLER;
        for (int slot = 0; slot < SLOTS; slot++) {
            owners[slot] = NO_CONTROLLER;
            partners[slot] = NO_CONTROLLER;
            slotControllers.set(slot, NO_CONTROLLER);
            clearJoyCons(slot);
            if (playerWriters[slot] != null) {
                playerWriters[slot].destroy();
                playerWriters[slot] = null;
            }
            if (playerStates[slot] != null) playerStates[slot].clear();
        }
        systemGuidePressed = false;
        systemQamPressed = false;
        backQamPressed = false;
        qamChordActive = false;
        chordButtonPressed = false;
        qamTapPressed = false;
        qamChordGeneration++;
        if (open) {
            state.clear();
            writer.writePad(state);
            writer.close();
            open = false;
        }
    }

    static boolean isSystemVirtualInputName(String name) {
        if (name == null) return false;
        String lower = name.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("uinput-") || lower.startsWith("uinput_");
    }

    public static boolean isFromController(InputDevice device) {
        if (device == null) return false;
        if (device.isVirtual() || isSystemVirtualInputName(device.getName())) return false;
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
        if (device == null || device.isVirtual() || isSystemVirtualInputName(device.getName())) return false;
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
                && !isSystemVirtualInputName(device.getName())
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
            if (!isFromController(device) || excluded.test(deviceId)) continue;
            int slot = slotFor(device);
            if (slot >= 0) publish(slot);
        }
    }

    public void setOnPlayerInput(Runnable listener) {
        onPlayerInput = listener;
    }

    public synchronized void setOnChordInput(Runnable listener) {
        onChordInput = listener;
    }

    private void noteChordInput(int slot) {
        if (slot == 0 && onChordInput != null) onChordInput.run();
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
        if (!isControllerKeyEvent(event) || excluded.test(event.getDeviceId())) return false;
        int slot = slotFor(event.getDevice());
        if (slot < 0) return false;
        int side = JoyCon.side(event.getDevice());
        if (side >= 0 && partners[slot] != NO_CONTROLLER && JoyCon.isRail(side, event.getScanCode())) return true;
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
            case KeyEvent.KEYCODE_BUTTON_Z:
                if (isPrimary(event)) DeckControls.INSTANCE.setGrip(isNovaController(event.getDevice()) ? DeckControls.R4 : DeckControls.L4, pressed);
                break;
            case KeyEvent.KEYCODE_BUTTON_C:
                if (isPrimary(event)) DeckControls.INSTANCE.setGrip(isNovaController(event.getDevice()) ? DeckControls.L4 : DeckControls.R4, pressed);
                break;
            case KeyEvent.KEYCODE_BUTTON_L2: pad.leftTrigger = pressed ? 1f : 0f; break;
            case KeyEvent.KEYCODE_BUTTON_R2: pad.rightTrigger = pressed ? 1f : 0f; break;
            case KeyEvent.KEYCODE_DPAD_UP: pad.up = pressed; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: pad.right = pressed; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: pad.down = pressed; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: pad.left = pressed; break;
            default: return false;
        }
        noteDevice(event.getDevice(), slot);
        if (pressed && event.getRepeatCount() == 0 && !event.isCanceled()) noteChordInput(slot);
        if (pressed) notePlayerInput();
        statButtons++;
        scheduleStats();
        publish(slot);
        return true;
    }

    public synchronized boolean onMotionEvent(MotionEvent event) {
        if (!isControllerMotionEvent(event) || excluded.test(event.getDeviceId())) return false;
        if (event.getAction() != MotionEvent.ACTION_MOVE) return false;
        int slot = slotFor(event.getDevice());
        if (slot < 0) return false;
        PadState pad = stateFor(slot);
        noteDevice(event.getDevice(), slot);
        statAxes++;
        scheduleStats();
        InputDevice device = event.getDevice();
        int side = JoyCon.side(device);
        if (side >= 0) {
            int[] stick = JoyCon.stickAxes(side, device, event.getSource());
            float x = axis(event, stick[0]);
            float y = axis(event, stick[1]);
            if (side == JoyCon.LEFT) {
                pad.leftX = x;
                pad.leftY = y;
            } else {
                pad.rightX = x;
                pad.rightY = y;
            }
            if (x != 0f || y != 0f) noteChordInput(slot);
            if (Math.max(Math.abs(x), Math.abs(y)) > 0.5f) notePlayerInput();
            publish(slot);
            return true;
        }
        pad.leftX = axis(event, MotionEvent.AXIS_X);
        pad.leftY = axis(event, MotionEvent.AXIS_Y);
        boolean wired = wiredXboxLayout(event);
        pad.rightX = rightX(event, wired);
        pad.rightY = rightY(event, wired);
        int source = event.getSource();
        float lt = 0f;
        float rt = 0f;
        if (wired || hasAxis(device, source, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)) {
            lt = leftTrigger(event, wired);
            pad.leftTrigger = lt;
        }
        if (wired || hasAxis(device, source, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)) {
            rt = rightTrigger(event, wired);
            pad.rightTrigger = rt;
        }
        if (hasAxis(device, source, MotionEvent.AXIS_HAT_X)) {
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            pad.right = hatX > 0.5f;
            pad.left = hatX < -0.5f;
        }
        if (hasAxis(device, source, MotionEvent.AXIS_HAT_Y)) {
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            pad.up = hatY < -0.5f;
            pad.down = hatY > 0.5f;
        }
        if (pad.leftX != 0f || pad.leftY != 0f || pad.rightX != 0f || pad.rightY != 0f
                || lt > 0f || rt > 0f || pad.up || pad.right || pad.down || pad.left) noteChordInput(slot);
        if (Math.max(Math.max(Math.abs(pad.leftX), Math.abs(pad.leftY)), Math.max(Math.abs(pad.rightX), Math.abs(pad.rightY))) > 0.5f
                || lt > 0.5f || rt > 0.5f || pad.up || pad.right || pad.down || pad.left) {
            notePlayerInput();
        }
        publish(slot);
        return true;
    }

    static boolean hasAxis(InputDevice device, int source, int... axes) {
        if (device == null) return false;
        for (int axis : axes) if (device.getMotionRange(axis, source) != null) return true;
        return false;
    }

    public interface TouchRoute {
        boolean active();

        void accept(PadState state);
    }

    public synchronized void setTouchRoute(TouchRoute route) {
        touchRoute = route;
        routedTouch.clear();
    }

    public synchronized void applyTouch(java.util.function.Consumer<PadState> mutation) {
        if (!builtinPad) return;
        if (touchRoute != null && touchRoute.active()) {
            mutation.accept(routedTouch);
            touchRoute.accept(routedTouch);
            boolean changed = false;
            for (int button : ROUTED_PASS_THROUGH) {
                boolean down = routedTouch.isDown(button);
                if (state.isDown(button) == down) continue;
                state.press(button, down);
                changed = true;
            }
            if (changed) {
                activeControllerId = NO_CONTROLLER;
                slotControllers.set(0, NO_CONTROLLER);
                publish();
            }
            return;
        }
        routedTouch.clear();
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
        if (!builtinPad) return;
        if (systemGuidePressed == guidePressed && systemQamPressed == qamPressed) return;
        boolean qamStarted = qamPressed && !systemQamPressed;
        systemGuidePressed = guidePressed;
        systemQamPressed = qamPressed;
        if (qamStarted && !nativeQam()) startChord(PadState.A);
        else publish();
    }

    public synchronized void releaseAll() {
        homeGuidePressed = false;
        clearBackQamWrites();
        routedTouch.clear();
        backQamPressed = false;
        releaseBackPaddles();
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
            if (owners[slot] == deviceId || partners[slot] == deviceId) leave(slot, deviceId);
        }
    }

    private static boolean nativeQam() {
        return SessionState.getDeckPad() && FakeInputWriter.padHasQam();
    }

    public boolean canHoldQam() {
        return builtinPad && nativeQam();
    }

    public boolean canHoldGuide() {
        return builtinPad;
    }

    public synchronized void setHomeGuidePressed(boolean pressed) {
        if (pressed && !canHoldGuide()) return;
        if (homeGuidePressed == pressed) return;
        homeGuidePressed = pressed;
        updateHeldModifier(PadState.GUIDE, pressed);
    }

    public synchronized void cancelHomeGuide() {
        if (!homeGuidePressed) return;
        clearBackQamWrites();
        homeGuidePressed = false;
        state.clear();
        publish();
    }

    public synchronized void setBackQamPressed(boolean pressed) {
        if (pressed && !canHoldQam()) return;
        if (backQamPressed == pressed) return;
        backQamPressed = pressed;
        updateHeldModifier(PadState.QAM, pressed);
    }

    private void updateHeldModifier(int button, boolean pressed) {
        if (pressed && (open || start())) {
            backQamAxesBlocked = false;
            long now = android.os.SystemClock.uptimeMillis();
            long modifierAt = Math.max(now, backQamWriteAt);
            backQamDelayMs = modifierAt - now + QAM_GUIDE_LEAD_MIN_MS;
            PadState modifier = new PadState();
            modifier.copyFrom(lastBackQamReport);
            modifier.press(button, true);
            writeBackQamReport(modifier, modifierAt);
        }
        if (!pressed && !backQamPressed && !homeGuidePressed) {
            backQamAxesBlocked = hasAxes(state);
            if (!backQamWrites.isEmpty()) {
                PadState neutral = new PadState();
                neutral.copyFrom(lastWrittenReport);
                clearAxes(neutral);
                writer.writePad(neutral);
                lastWrittenReport.copyFrom(neutral);
            }
        }
        publish();
    }

    public synchronized void cancelBackQam() {
        if (!backQamPressed && backQamWrites.isEmpty()) return;
        clearBackQamWrites();
        backQamPressed = false;
        state.clear();
        publish();
    }

    private void clearBackQamWrites() {
        for (Runnable pending : backQamWrites) mainHandler.removeCallbacks(pending);
        backQamWrites.clear();
        backQamDelayMs = backQamWriteAt = 0;
        lastBackQamReport.clear();
        lastWrittenReport.clear();
        backQamAxesBlocked = false;
    }

    private void writeBackQamReport(PadState report, long at) {
        lastBackQamReport.copyFrom(report);
        if (backQamWrites.isEmpty() && at <= android.os.SystemClock.uptimeMillis()) {
            writeReport(report, false);
            return;
        }
        PadState queued = new PadState();
        queued.copyFrom(report);
        Runnable pending = new Runnable() {
            @Override public void run() {
                synchronized (PadBridge.this) {
                    if (!backQamWrites.remove(this)) return;
                    writeReport(queued, true);
                    if (backQamWrites.isEmpty() && !hasButtons(lastBackQamReport)) backQamDelayMs = 0;
                }
            }
        };
        backQamWrites.add(pending);
        backQamWriteAt = at;
        mainHandler.postAtTime(pending, at);
    }

    private void writePad(PadState report) {
        if (backQamAxesBlocked && !hasAxes(state)) backQamAxesBlocked = false;
        long at = android.os.SystemClock.uptimeMillis();
        // Steam must see the modifier before a partner; preserve quick press/release pairs in order.
        if (backQamPressed || homeGuidePressed || !backQamWrites.isEmpty()) at += backQamDelayMs;
        writeBackQamReport(report, at);
    }

    private static boolean hasAxes(PadState pad) {
        return pad.leftX != 0f || pad.leftY != 0f || pad.rightX != 0f || pad.rightY != 0f
                || pad.leftTrigger != 0f || pad.rightTrigger != 0f;
    }

    private static void clearAxes(PadState pad) {
        pad.leftX = pad.leftY = pad.rightX = pad.rightY = pad.leftTrigger = pad.rightTrigger = 0f;
    }

    private static boolean hasButtons(PadState pad) {
        for (int i = 0; i < 10; i++) if (pad.isDown(i)) return true;
        return pad.isDown(PadState.GUIDE) || pad.up || pad.right || pad.down || pad.left;
    }

    private void writeReport(PadState report, boolean queued) {
        if (!queued && !backQamAxesBlocked) {
            lastWrittenReport.copyFrom(report);
            writer.writePad(report);
            return;
        }
        PadState live = new PadState();
        live.copyFrom(report);
        if (queued) {
            live.leftX = state.leftX; live.leftY = state.leftY;
            live.rightX = state.rightX; live.rightY = state.rightY;
            live.leftTrigger = state.leftTrigger; live.rightTrigger = state.rightTrigger;
        }
        boolean held = (backQamPressed && report.isDown(PadState.QAM)) || (homeGuidePressed && report.isDown(PadState.GUIDE));
        if ((queued && !held) || backQamAxesBlocked) clearAxes(live);
        lastWrittenReport.copyFrom(live);
        writer.writePad(live);
    }

    public synchronized void triggerQam() {
        if (!builtinPad) return;
        if (!nativeQam()) {
            startChord(PadState.A);
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

    public synchronized void triggerSteamKeyboard() {
        if (!builtinPad) return;
        startChord(PadState.X);
    }

    private void startChord(int button) {
        if (qamChordActive) return;
        qamChordActive = true;
        chordButton = button;
        int generation = ++qamChordGeneration;
        publish();
        chordHandler.postDelayed(() -> pressChordButton(generation), qamGuideLeadMs());
    }

    static long qamGuideLeadMs() {
        long frame = com.droiddeck.launcher.wayland.WaylandCompositor.recentFrameIntervalMs();
        if (frame <= 0) return QAM_GUIDE_LEAD_MIN_MS;
        return Math.max(QAM_GUIDE_LEAD_MIN_MS, Math.min(QAM_GUIDE_LEAD_MAX_MS, frame * QAM_GUIDE_LEAD_FRAMES));
    }

    private synchronized void pressChordButton(int generation) {
        if (generation != qamChordGeneration || !qamChordActive) return;
        chordButtonPressed = true;
        publish();
        chordHandler.postDelayed(() -> releaseChordButton(generation), QAM_A_HOLD_MS);
    }

    private synchronized void releaseChordButton(int generation) {
        if (generation != qamChordGeneration || !qamChordActive) return;
        chordButtonPressed = false;
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
        if (owners[0] == id || partners[0] == id) return 0;
        for (int slot = 1; slot < SLOTS; slot++) {
            if (owners[slot] == id || partners[slot] == id) return slot;
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            if (partners[slot] != NO_CONTROLLER && InputDevice.getDevice(partners[slot]) == null) leave(slot, partners[slot]);
            if (owners[slot] != NO_CONTROLLER && InputDevice.getDevice(owners[slot]) == null) leave(slot, owners[slot]);
        }
        int side = JoyCon.side(device);
        for (int slot = firstPlayerSlot(); side >= 0 && slot < SLOTS; slot++) {
            if (owners[slot] == NO_CONTROLLER || partners[slot] != NO_CONTROLLER) continue;
            InputDevice owner = InputDevice.getDevice(owners[slot]);
            if (JoyCon.side(owner) != 1 - side) continue;
            partners[slot] = id;
            slotJoyCons.set(slot * 2 + side, id);
            slotJoyCons.set(slot * 2 + 1 - side, owner.getId());
            if (slot == firstPlayerSlot()) releaseBackPaddles();
            if (slot == 0) clearBackQamWrites();
            stateFor(slot).clear();
            if (slot == 0) publish();
            Log.i(TAG, "\"" + device.getName() + "\" (id " + id + ") joins \"" + owner.getName() + "\" as player " + (slot + 1));
            return slot;
        }
        for (int slot = firstPlayerSlot(); slot < SLOTS; slot++) {
            if (owners[slot] != NO_CONTROLLER) continue;
            owners[slot] = id;
            Log.i(TAG, "\"" + device.getName() + "\" (id " + id + ") is player " + (slot + 1));
            return slot;
        }
        return -1;
    }

    private PadState stateFor(int slot) {
        return slot == 0 ? state : playerStates[slot];
    }

    private static boolean isNovaController(InputDevice device) {
        return "Retroid Pocket Nova".equalsIgnoreCase(Build.MODEL)
                && device.getVendorId() == 0x2022
                && (device.getProductId() == 0x3001 || device.getProductId() == 0x3002);
    }

    private boolean isPrimary(KeyEvent event) {
        int id = event.getDevice().getId();
        int first = firstPlayerSlot();
        return owners[first] == id || partners[first] == id;
    }

    private void releaseBackPaddles() {
        DeckControls.INSTANCE.setGrip(DeckControls.L4, false);
        DeckControls.INSTANCE.setGrip(DeckControls.R4, false);
    }

    private void leave(int slot, int id) {
        if (slot == 0) clearBackQamWrites();
        if (partners[slot] == NO_CONTROLLER) {
            free(slot);
            return;
        }
        Log.i(TAG, "player " + (slot + 1) + " Joy-Con (id " + id + ") left");
        if (owners[slot] == id) owners[slot] = partners[slot];
        partners[slot] = NO_CONTROLLER;
        clearJoyCons(slot);
        if (slotControllers.get(slot) == id) slotControllers.set(slot, owners[slot]);
        if (activeControllerId == id) activeControllerId = owners[slot];
        if (slot == firstPlayerSlot()) releaseBackPaddles();
        stateFor(slot).clear();
        publish(slot);
    }

    private static void clearJoyCons(int slot) {
        slotJoyCons.set(slot * 2, NO_CONTROLLER);
        slotJoyCons.set(slot * 2 + 1, NO_CONTROLLER);
    }

    private void free(int slot) {
        Log.i(TAG, "player " + (slot + 1) + " (id " + owners[slot] + ") left");
        owners[slot] = NO_CONTROLLER;
        partners[slot] = NO_CONTROLLER;
        clearJoyCons(slot);
        slotControllers.set(slot, NO_CONTROLLER);
        if (slot == firstPlayerSlot()) releaseBackPaddles();
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
        if (!builtinPad && !open && owners[0] == NO_CONTROLLER && partners[0] == NO_CONTROLLER) return;
        if (!open && !start()) {
            statDropped++;
            return;
        }
        boolean guide = systemGuidePressed || qamChordActive || homeGuidePressed;
        boolean qam = nativeQam() && (systemQamPressed || qamTapPressed || backQamPressed);
        if (!guide && !qam) {
            writePad(state);
            return;
        }
        effectiveState.copyFrom(state);
        if (guide) effectiveState.press(PadState.GUIDE, true);
        if (qam) effectiveState.press(PadState.QAM, true);
        if (chordButtonPressed) effectiveState.press(chordButton, true);
        writePad(effectiveState);
    }

    public static boolean wiredXboxLayout(MotionEvent event) {
        InputDevice device = event.getDevice();
        return device != null
                && ((device.getVendorId() == 0x20d6 && device.getProductId() == 0x2063)
                || (device.getVendorId() == 0x0e6f && device.getProductId() == 0x02f0))
                && device.getMotionRange(MotionEvent.AXIS_RX, event.getSource()) != null
                && device.getMotionRange(MotionEvent.AXIS_RY, event.getSource()) != null
                && device.getMotionRange(MotionEvent.AXIS_Z, event.getSource()) != null
                && device.getMotionRange(MotionEvent.AXIS_RZ, event.getSource()) != null;
    }

    public static float rightX(MotionEvent event, boolean wired) {
        return axis(event, wired ? MotionEvent.AXIS_RX : MotionEvent.AXIS_Z);
    }

    public static float rightY(MotionEvent event, boolean wired) {
        return axis(event, wired ? MotionEvent.AXIS_RY : MotionEvent.AXIS_RZ);
    }

    public static float leftTrigger(MotionEvent event, boolean wired) {
        if (wired) return Math.max(0f, Math.min(1f, (event.getAxisValue(MotionEvent.AXIS_Z) + 1f) / 2f));
        float value = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        return value != 0f ? value : event.getAxisValue(MotionEvent.AXIS_BRAKE);
    }

    public static float rightTrigger(MotionEvent event, boolean wired) {
        if (wired) return Math.max(0f, Math.min(1f, (event.getAxisValue(MotionEvent.AXIS_RZ) + 1f) / 2f));
        float value = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        return value != 0f ? value : event.getAxisValue(MotionEvent.AXIS_GAS);
    }

    private static float axis(MotionEvent event, int axis) {
        float value = event.getAxisValue(axis);
        return Math.abs(value) < DEAD_ZONE ? 0f : value;
    }
}
