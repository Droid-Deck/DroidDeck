/*
 * SPDX-License-Identifier: GPL-3.0
 * Modified for DroidDeck on 2026-10-15.
 */
package com.droiddeck.launcher.input;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TouchscreenGestures {
    public enum Gesture {
        TAP, DRAG, LONG_PRESS, DOUBLE_TAP,
        TWO_FINGER_TAP, TWO_FINGER_DRAG, TWO_FINGER_HOLD, PINCH,
        THREE_FINGER_TAP, THREE_FINGER_DRAG, THREE_FINGER_HOLD
    }

    public interface Output {
        void moveTo(float x, float y);
        void moveBy(float dx, float dy, boolean relative);
        void button(int code, boolean down);
        void key(int code, boolean down);
        void wheel(int steps);
        void showKeyboard();
        /** Establish the pointer origin before a relative middle-mouse pan begins. */
        default void prepareRelativePan() {}
        default void gesture(Gesture gesture) {}
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Output output;
    private final float doubleTapSlop;
    private TouchGestureConfig gestureConfig = new TouchGestureConfig();
    private static final int CLICK_DELAYED_TIME = 50;
    private Runnable delayedPress, pendingHoldClickRelease;
    private Runnable pendingTapPress;
    private final ArrayDeque<TapClick> pendingTapClicks = new ArrayDeque<>();
    private long lastInputReleaseTime;
    private String delayedPressAction, pendingHoldClickReleaseAction;
    private final Map<Integer, Integer> keysDown = new LinkedHashMap<>();
    private final Map<Integer, Integer> buttonsDown = new LinkedHashMap<>();
    private boolean leftClickDragButtonDown, rightClickDragButtonDown;

    private static final class TapClick {
        final String action;
        final int x, y;
        TapClick(String action, float x, float y) {
            this.action = action; this.x = (int) x; this.y = (int) y;
        }
    }

    public TouchscreenGestures(float doubleTapSlop, Output output) {
        this.output = output;
        this.doubleTapSlop = Math.max(1f, doubleTapSlop);
    }
    public void configure(TouchGestureConfig config) {
        cancel();
        gestureConfig = config;
    }
    public boolean onTouch(MotionEvent event) { return handleGestureTouchscreenEvent(event); }
    public void cancel() {
        handleTsCancel();
        handler.removeCallbacksAndMessages(null);
        for (int code : keysDown.keySet()) output.key(code, false);
        for (int code : buttonsDown.keySet()) output.button(code, false);
        keysDown.clear(); buttonsDown.clear(); lastTapTime = 0;
    }
    private void postDelayed(Runnable action, long delay) { handler.postDelayed(action, delay); }
    private void removeCallbacks(Runnable action) { handler.removeCallbacks(action); }
    private void notifyGesture(Gesture gesture) {
        if (gestureConfig.getShowGestureDebugOverlay()) output.gesture(gesture);
    }
    private void pressKey(int code) { press(keysDown, code, true); }
    private void releaseKey(int code) { release(keysDown, code, true); }
    private void pressButton(MouseButton button) { press(buttonsDown, button.code, false); }
    private void releaseButton(MouseButton button) { release(buttonsDown, button.code, false); }
    private void press(Map<Integer,Integer> held, int code, boolean key) {
        int count = held.getOrDefault(code, 0);
        held.put(code, count + 1);
        if (count == 0) { if (key) output.key(code, true); else output.button(code, true); }
    }
    private void release(Map<Integer,Integer> held, int code, boolean key) {
        int count = held.getOrDefault(code, 0);
        if (count == 0) return;
        if (count > 1) { held.put(code, count - 1); return; }
        held.remove(code);
        if (key) output.key(code, false); else output.button(code, false);
        lastInputReleaseTime = SystemClock.uptimeMillis();
    }
    private enum MouseButton {
        BUTTON_LEFT(0x110), BUTTON_RIGHT(0x111), BUTTON_MIDDLE(0x112);
        final int code;
        MouseButton(int code) { this.code = code; }
    }
    // Long-press detection
    private Runnable longPressRunnable;
    private boolean longPressTriggered;
    private boolean longPressActionHeld, twoFingerHoldActionHeld, threeFingerHoldActionHeld;

    // Double-tap detection
    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;
    private boolean secondTapCandidate;
    private float doubleTapAnchorX, doubleTapAnchorY;
    private long singleFingerDownTime;

    // Drag tracking (box selection)
    private boolean isDragging;
    private boolean dragButtonPressed;

    // Two-finger tracking
    private boolean twoFingerDragging;
    private float twoFingerLastX0, twoFingerLastY0;
    private float twoFingerLastX1, twoFingerLastY1;
    private boolean twoFingerMiddleButtonDown;
    // Pinch tracking
    private float pinchLastDistance;
    private float pinchZoomRemainder;
    private float keyPanRemainderX, keyPanRemainderY;
    private static final float PINCH_STEP_PX = 10f;
    // Two-finger tap detection
    private boolean twoFingerTapPossible;
    private boolean twoFingerTapFired;
    // Track which WASD/arrow keys are currently held
    private boolean panKeyUp, panKeyDown, panKeyLeft, panKeyRight;
    // True when finger has moved beyond tap tolerance (prevents spurious tap on lift)
    private boolean movedBeyondTapThreshold;
    // True after a multi-finger gesture has been active — prevents the remaining
    // finger from starting a spurious single-finger drag on lift sequence.
    private boolean multiFingerGestureUsed;
    // Remember which keycodes were actually pressed so releasePanKeys releases only those
    private Integer panLeftKey, panRightKey, panUpKey, panDownKey;
    // Long-press movement tolerance (pixels of raw finger movement before cancelling long-press)
    private static final float LONG_PRESS_MOVE_TOLERANCE = 50f;
    // Raw touch-down coordinates for measuring finger travel
    private float touchDownRawX, touchDownRawY;
    // Last raw coordinates for single-finger drag delta
    private float singleFingerLastRawX, singleFingerLastRawY;
    // Two-finger gesture mutual exclusion (only pan OR pinch, not both)
    private static final int TWO_FINGER_GESTURE_NONE = 0;
    private static final int TWO_FINGER_GESTURE_PAN = 1;
    private static final int TWO_FINGER_GESTURE_ZOOM = 2;
    private int twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
    // Accumulated deltas for deciding which two-finger gesture to lock into
    private float accumulatedPinchDelta, accumulatedPanDelta;

    // Settle window: ignore movement for first N ms after second finger lands (#1)
    // Also serves as a grace period for a third finger to arrive before 2F locks in
    private static final long TWO_FINGER_SETTLE_MS = 120;
    private static final long THREE_FINGER_SETTLE_MS = 50;
    private long twoFingerDownTime;
    private long threeFingerDownTime;

    // Velocity gating for two-finger hold vs drag (#2)
    private long twoFingerLastMoveTime;
    private static final float MIN_DRAG_VELOCITY = 0.3f; // px/ms to commit to drag

    // Pinch vs drag ratio: pinch delta must exceed pan by this factor to classify as pinch (#4)
    private static final float PINCH_RATIO_THRESHOLD = 1.5f;

    // Cancel-and-reclassify: allow reclassification within this window after lock-in (#5)
    private static final long RECLASSIFY_WINDOW_MS = 100;
    private long twoFingerLockTime;
    private float reclassifyPinchDelta, reclassifyPanDelta;
    // Gesture pointer ownership tracking (prevents d-pad/button pointer conflicts)
    private int gestureFingerCount = 0;
    private final ArrayList<Integer> gestureOwnedPointerIds = new ArrayList<>();
    private final int[] multiFingerStartIds = new int[3];
    private final float[] multiFingerStartX = new float[3], multiFingerStartY = new float[3];
    private int multiFingerStartCount;

    // Two-finger hold detection
    private Runnable twoFingerHoldRunnable;
    private boolean twoFingerHoldTriggered;

    // Three-finger tracking
    private boolean threeFingerTapPossible;
    private boolean threeFingerTapFired;
    private boolean threeFingerDragging;
    private float threeFingerLastMidX, threeFingerLastMidY;
    private float threeFingerDragOffsetX, threeFingerDragOffsetY;
    private Runnable threeFingerHoldRunnable;
    private boolean threeFingerHoldTriggered;
    private static final int THREE_FINGER_GESTURE_NONE = 0;
    private static final int THREE_FINGER_GESTURE_PAN = 1;
    private int threeFingerGestureMode = THREE_FINGER_GESTURE_NONE;
    private float accumulatedThreeFingerDelta;

    private boolean handleGestureTouchscreenEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // A new touch must not shorten or discard an already recognized click.
                handleTsCancel(true);
                // First pointer overall — gesture system claims it
                gestureOwnedPointerIds.clear();
                gestureOwnedPointerIds.add(pointerId);
                gestureFingerCount = 1;
                handleTsDown(event, actionIndex);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                // New pointer arrived — gesture system claims it
                if (!gestureOwnedPointerIds.contains(pointerId)) {
                    gestureOwnedPointerIds.add(pointerId);
                    gestureFingerCount++;
                }
                if (gestureFingerCount == 1) {
                    // First gesture finger (controls own earlier pointers)
                    handleTsDown(event, actionIndex);
                } else if (gestureFingerCount == 2) {
                    handleTsPointerDown(event);
                } else if (gestureFingerCount == 3) {
                    handleTsThreeFingerDown(event);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (gestureFingerCount >= 3) {
                    handleTsThreeFingerMove(event);
                } else if (gestureFingerCount >= 2) {
                    handleTsTwoFingerMove(event);
                } else if (gestureFingerCount == 1 && !gestureOwnedPointerIds.isEmpty()) {
                    int idx = event.findPointerIndex(gestureOwnedPointerIds.get(0));
                    if (idx >= 0) handleTsMove(event, idx);
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (gestureOwnedPointerIds.contains(pointerId)) {
                    int prevCount = gestureFingerCount;
                    int removedIndex = gestureOwnedPointerIds.indexOf(pointerId);
                    gestureOwnedPointerIds.remove(Integer.valueOf(pointerId));
                    gestureFingerCount = Math.max(0, gestureFingerCount - 1);
                    if (prevCount == 3 && gestureFingerCount == 2) {
                        // Three-finger gesture ending
                        handleTsThreeFingerUp(event);
                    } else if (prevCount == 2 && gestureFingerCount == 1) {
                        // Two-finger gesture ending (2→1)
                        handleTsPointerUp(event);
                    } else if (prevCount >= 4) {
                        if (removedIndex < 3) rebaseThreeFingerMovement(event);
                    } else if (prevCount >= 1 && gestureFingerCount == 0) {
                        // Last gesture finger lifted while controls still hold pointers
                        handleTsUp(event, actionIndex);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (gestureOwnedPointerIds.contains(pointerId)) {
                    int prevCount = gestureFingerCount;
                    gestureOwnedPointerIds.remove(Integer.valueOf(pointerId));
                    gestureFingerCount = Math.max(0, gestureFingerCount - 1);
                    // Clean up multi-finger state if needed, routing by previous
                    // gesture mode so a 3-finger gesture isn't ended via the
                    // two-finger cleanup path.
                    if (prevCount >= 3) {
                        handleTsThreeFingerUp(event);
                    } else if (prevCount == 2) {
                        handleTsPointerUp(event);
                    }
                    handleTsUp(event, actionIndex);
                }
                // ACTION_UP = last pointer overall — full cleanup
                gestureOwnedPointerIds.clear();
                gestureFingerCount = 0;
                break;

            case MotionEvent.ACTION_CANCEL:
                gestureOwnedPointerIds.clear();
                gestureFingerCount = 0;
                cancel();
                break;
        }
        return true;
    }

    private void handleTsDown(MotionEvent event, int actionIndex) {
        flushPendingHoldClickRelease();

        int x = (int) event.getX(actionIndex);
        int y = (int) event.getY(actionIndex);

        longPressTriggered = longPressActionHeld = false;
        secondTapCandidate = false;
        isDragging = false;
        dragButtonPressed = false;
        movedBeyondTapThreshold = false;
        multiFingerGestureUsed = false;
        twoFingerDragging = false;
        twoFingerTapPossible = false;
        twoFingerTapFired = false;
        twoFingerMiddleButtonDown = false;
        twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
        threeFingerTapPossible = false;
        threeFingerDragging = false;
        threeFingerTapFired = false;
        threeFingerGestureMode = THREE_FINGER_GESTURE_NONE;
        threeFingerHoldTriggered = false;
        accumulatedThreeFingerDelta = 0;

        // Store raw touch coordinates for long-press movement tolerance
        touchDownRawX = event.getX(actionIndex);
        touchDownRawY = event.getY(actionIndex);
        singleFingerDownTime = SystemClock.uptimeMillis();
        singleFingerLastRawX = touchDownRawX;
        singleFingerLastRawY = touchDownRawY;

        // Keep a nearby re-tap undecided until UP: it can still become a drag,
        // long press, or multi-finger gesture instead of a double click.
        if (isDoubleTap(event, actionIndex)) {
            secondTapCandidate = true;
            doubleTapAnchorX = lastTapX;
            doubleTapAnchorY = lastTapY;
            lastTapTime = 0;
        }

        if (!secondTapCandidate && delayedPress == null && pendingTapPress == null) moveCursorTo(x, y);

        // Schedule long-press
        if (gestureConfig.getLongPressEnabled()) {
            longPressRunnable = () -> {
                secondTapCandidate = false;
                lastTapTime = 0;
                longPressTriggered = true;
                moveCursorTo((int) touchDownRawX, (int) touchDownRawY);
                longPressActionHeld = injectHoldAction(
                        gestureConfig.getLongPressAction(),
                        gestureConfig.getLongPressMouseBehavior());
                notifyGesture(Gesture.LONG_PRESS);
            };
            postDelayed(longPressRunnable, gestureConfig.getLongPressDelay());
        }

        // Tap: immediate cursor move (press is deferred to UP for click, or MOVE for drag)
    }

    private void handleTsPointerDown(MotionEvent event) {
        if (multiFingerGestureUsed) return;
        secondTapCandidate = false;
        flushPendingTapRelease();
        lastTapTime = 0;
        flushPendingHoldClickRelease();
        // Cancel any single-finger long-press.  cancelLongPressTimer only
        // clears the pending runnable; if the long-press has already fired
        // we must also release the held action before we transition into a
        // multi-finger gesture, otherwise the long-press button/key stays
        // down for the rest of the touch sequence.
        if (longPressTriggered) {
            longPressActionHeld = releaseHoldAction(longPressActionHeld, gestureConfig.getLongPressAction());
            longPressTriggered = false;
        }
        cancelLongPressTimer();
        cancelDrag();

        if (gestureFingerCount == 2 && gestureOwnedPointerIds.size() >= 2) {
            twoFingerTapPossible = true;
            twoFingerDragging = false;
            twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
            twoFingerHoldTriggered = twoFingerHoldActionHeld = false;
            accumulatedPinchDelta = 0;
            accumulatedPanDelta = 0;
            resetSmallMovement();
            twoFingerDownTime = SystemClock.uptimeMillis();
            twoFingerLastMoveTime = twoFingerDownTime;
            twoFingerLockTime = 0;
            reclassifyPinchDelta = 0;
            reclassifyPanDelta = 0;
            int idx0 = event.findPointerIndex(gestureOwnedPointerIds.get(0));
            int idx1 = event.findPointerIndex(gestureOwnedPointerIds.get(1));
            if (idx0 >= 0 && idx1 >= 0) {
                twoFingerLastX0 = event.getX(idx0);
                twoFingerLastY0 = event.getY(idx0);
                twoFingerLastX1 = event.getX(idx1);
                twoFingerLastY1 = event.getY(idx1);
                pinchLastDistance = (float) Math.hypot(
                        event.getX(idx0) - event.getX(idx1),
                        event.getY(idx0) - event.getY(idx1));
                recordMultiFingerOrigins(event, 2);
            }
            // Schedule two-finger hold
            if (gestureConfig.getTwoFingerHoldEnabled()) {
                twoFingerHoldRunnable = () -> {
                    twoFingerHoldTriggered = true;
                    twoFingerTapPossible = false;
                    twoFingerHoldActionHeld = injectHoldAction(
                            gestureConfig.getTwoFingerHoldAction(),
                            gestureConfig.getTwoFingerHoldMouseBehavior());
                    notifyGesture(Gesture.TWO_FINGER_HOLD);
                };
                postDelayed(twoFingerHoldRunnable, gestureConfig.getTwoFingerHoldDelay());
            }
        }
    }

    private void handleTsMove(MotionEvent event, int pointerIndex) {
        // After a multi-finger gesture, ignore single-finger movement until all fingers lift
        if (multiFingerGestureUsed) return;

        int x = (int) event.getX(pointerIndex);
        int y = (int) event.getY(pointerIndex);

        // If long press already triggered, ignore movement (right-click already sent)
        if (longPressTriggered) return;

        // Measure raw finger travel from initial touch point
        float rawTravel = maxFingerTravel(event, pointerIndex, touchDownRawX, touchDownRawY);
        if (secondTapCandidate && (rawTravel > doubleTapSlop
                || maxFingerTravel(event, pointerIndex, doubleTapAnchorX, doubleTapAnchorY) >= doubleTapSlop)) {
            secondTapCandidate = false;
        }

        // The threshold decides when a drag starts. An active drag must keep
        // following the finger even when it returns through the touch origin.
        if (!isDragging && rawTravel <= LONG_PRESS_MOVE_TOLERANCE) {
            return;
        }

        // Beyond tolerance — cancel long press
        cancelLongPressTimer();
        movedBeyondTapThreshold = true;

        // Detect drag start
        if (!isDragging && gestureConfig.getDragEnabled()) {
            flushPendingTapRelease();
            String dragAction = gestureConfig.getDragAction();
            isDragging = true;
            dragButtonPressed = true;
            singleFingerLastRawX = event.getX(pointerIndex);
            singleFingerLastRawY = event.getY(pointerIndex);
            // Send the configured drag press immediately so that a short
            // move followed straight by UP still produces a press+release
            // pair.  A zero-delta performPanAction just presses the button
            // (or is a no-op for the key-pan variants, which press per
            // direction on their next move frame).
            if (isClickDragAction(dragAction)) {
                // The candidate's cursor stayed at the previous click. Start
                // a drag at this touch's own origin before pressing its button.
                moveCursorTo((int) touchDownRawX, (int) touchDownRawY);
                if (useRelativeMouseDragMovement()) {
                    pressClickDragAction(dragAction);
                } else {
                    performClickDragAt(event.getX(pointerIndex), event.getY(pointerIndex), dragAction);
                }
            } else {
                performPanAction(0f, 0f, dragAction);
            }
            notifyGesture(Gesture.DRAG);
            // Non-click pan actions continue with real deltas on the next frame.
            return;
        }

        if (isDragging) {
            String dragAction = gestureConfig.getDragAction();
            if (isClickDragAction(dragAction) && !useRelativeMouseDragMovement()) {
                moveCursorTo(x, y);
                return;
            }

            float rawX = event.getX(pointerIndex);
            float rawY = event.getY(pointerIndex);
            float dx = rawX - singleFingerLastRawX;
            float dy = rawY - singleFingerLastRawY;
            singleFingerLastRawX = rawX;
            singleFingerLastRawY = rawY;
            if (isClickDragAction(dragAction)) {
                performClickDragWithRelativeMovement(dx, dy, dragAction);
            } else {
                performPanAction(dx, dy, dragAction);
            }
            return;
        }

        // Move cursor
        moveCursorTo(x, y);
    }

    private void handleTsTwoFingerMove(MotionEvent event) {
        if (multiFingerGestureUsed) return;
        // If a two-finger hold has already fired, ignore subsequent jitter so
        // it can't lock into a pan/zoom gesture mid-hold.
        if (twoFingerHoldTriggered) return;
        if (gestureFingerCount < 2 || gestureOwnedPointerIds.size() < 2) return;

        int idx0 = event.findPointerIndex(gestureOwnedPointerIds.get(0));
        int idx1 = event.findPointerIndex(gestureOwnedPointerIds.get(1));
        if (idx0 < 0 || idx1 < 0) return;

        // Settling delays recognition, not movement tracking. A swipe or pinch
        // must stop being a tap/hold even when it finishes inside that delay.
        if (multiFingerMovementExceeded(event)) {
            twoFingerTapPossible = false;
            cancelTwoFingerHoldTimer();
        }
        // Slow deliberate travel can cross touch slop before reaching the
        // pan/zoom threshold. It must not turn into a stationary hold first.
        if (multiFingerMovementExceeded(event, true)) cancelTwoFingerHoldTimer();

        float x0 = event.getX(idx0), y0 = event.getY(idx0);
        float x1 = event.getX(idx1), y1 = event.getY(idx1);

        // Compute per-frame pan and pinch deltas
        float currDist = (float) Math.hypot(x0 - x1, y0 - y1);
        float framePinchDelta = Math.abs(currDist - pinchLastDistance);

        float midX = (x0 + x1) / 2f;
        float midY = (y0 + y1) / 2f;
        float lastMidX = (twoFingerLastX0 + twoFingerLastX1) / 2f;
        float lastMidY = (twoFingerLastY0 + twoFingerLastY1) / 2f;
        float dx = midX - lastMidX;
        float dy = midY - lastMidY;
        float framePanDelta = (float) Math.hypot(dx, dy);

        long now = SystemClock.uptimeMillis();
        if (now - twoFingerDownTime < TWO_FINGER_SETTLE_MS) {
            // Still settling — update stored positions but don't accumulate deltas
            twoFingerLastX0 = x0; twoFingerLastY0 = y0;
            twoFingerLastX1 = x1; twoFingerLastY1 = y1;
            pinchLastDistance = currDist;
            return;
        }
        long frameTime = now - twoFingerLastMoveTime;
        twoFingerLastMoveTime = now;
        float velocity = (frameTime > 0) ? framePanDelta / frameTime : 0; // px/ms

        // Accumulate deltas until we have enough movement to decide gesture type
        if (twoFingerGestureMode == TWO_FINGER_GESTURE_NONE) {
            accumulatedPinchDelta += framePinchDelta;
            accumulatedPanDelta += framePanDelta;
            if (accumulatedPinchDelta + accumulatedPanDelta > (float) gestureConfig.getGestureThreshold()) {
                // Enough movement to lock into a gesture — no longer a tap or hold
                twoFingerTapPossible = false;
                cancelTwoFingerHoldTimer();
                if (accumulatedPinchDelta > accumulatedPanDelta * PINCH_RATIO_THRESHOLD
                        && gestureConfig.getPinchEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_ZOOM;
                    twoFingerLockTime = now;
                    notifyGesture(Gesture.PINCH);
                } else if (velocity >= MIN_DRAG_VELOCITY && gestureConfig.getTwoFingerDragEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_PAN;
                    twoFingerLockTime = now;
                    notifyGesture(Gesture.TWO_FINGER_DRAG);
                } else if (accumulatedPanDelta >= accumulatedPinchDelta && gestureConfig.getTwoFingerDragEnabled()) {
                    // Fallback: pan is dominant but velocity was too low — still drag
                    twoFingerGestureMode = TWO_FINGER_GESTURE_PAN;
                    twoFingerLockTime = now;
                    notifyGesture(Gesture.TWO_FINGER_DRAG);
                } else if (gestureConfig.getPinchEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_ZOOM;
                    twoFingerLockTime = now;
                    notifyGesture(Gesture.PINCH);
                } else if (gestureConfig.getTwoFingerDragEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_PAN;
                    twoFingerLockTime = now;
                    notifyGesture(Gesture.TWO_FINGER_DRAG);
                }
            }
        }
        if (twoFingerGestureMode != TWO_FINGER_GESTURE_NONE && twoFingerLockTime > 0
                && (now - twoFingerLockTime) < RECLASSIFY_WINDOW_MS) {
            reclassifyPinchDelta += framePinchDelta;
            reclassifyPanDelta += framePanDelta;
            float reclassifyTotal = reclassifyPinchDelta + reclassifyPanDelta;
            if (reclassifyTotal > 20f) { // enough post-lock data to reclassify
                boolean shouldBeZoom = reclassifyPinchDelta > reclassifyPanDelta * PINCH_RATIO_THRESHOLD;
                if (shouldBeZoom && twoFingerGestureMode == TWO_FINGER_GESTURE_PAN
                        && gestureConfig.getPinchEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_ZOOM;
                    releaseDragInput();
                    resetSmallMovement();
                    notifyGesture(Gesture.PINCH);
                } else if (!shouldBeZoom && twoFingerGestureMode == TWO_FINGER_GESTURE_ZOOM
                        && gestureConfig.getTwoFingerDragEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_PAN;
                    resetSmallMovement();
                    notifyGesture(Gesture.TWO_FINGER_DRAG);
                }
                twoFingerLockTime = 0; // done reclassifying
                reclassifyPinchDelta = 0;
                reclassifyPanDelta = 0;
            }
        }
        if (twoFingerGestureMode == TWO_FINGER_GESTURE_ZOOM) {
            float delta = currDist - pinchLastDistance;
            pinchZoomRemainder = accumulateSignedMovement(pinchZoomRemainder, delta);
            if (Math.abs(pinchZoomRemainder) >= PINCH_STEP_PX) {
                performZoomAction(pinchZoomRemainder > 0);
                // Keep the fractional travel, without queuing a burst of old
                // zoom actions after a large move or a direction reversal.
                pinchZoomRemainder %= PINCH_STEP_PX;
            }
        }
        pinchLastDistance = currDist; // Always update baseline to avoid stale deltas
        if (twoFingerGestureMode == TWO_FINGER_GESTURE_PAN) {
            String twoFingerDragAction = gestureConfig.getTwoFingerDragAction();
            if (isClickDragAction(twoFingerDragAction)) {
                if (useRelativeMouseDragMovement()) {
                    twoFingerDragging = true;
                    performClickDragWithRelativeMovement(dx, dy, twoFingerDragAction);
                } else {
                    if (!twoFingerDragging) moveCursorToMultiFingerOrigin();
                    twoFingerDragging = true;
                    performClickDragAt(midX, midY, twoFingerDragAction);
                }
            } else if (dx != 0f || dy != 0f) {
                twoFingerDragging = true;
                performPanAction(dx, dy, twoFingerDragAction);
            }
        }

        twoFingerLastX0 = x0;
        twoFingerLastY0 = y0;
        twoFingerLastX1 = x1;
        twoFingerLastY1 = y1;
    }

    private void handleTsPointerUp(MotionEvent event) {
        cancelTwoFingerHoldTimer();
        if (multiFingerMovementExceeded(event)) twoFingerTapPossible = false;
        // Two-finger hold release
        if (twoFingerHoldTriggered) {
            twoFingerHoldActionHeld = releaseHoldAction(twoFingerHoldActionHeld, gestureConfig.getTwoFingerHoldAction());
            twoFingerHoldTriggered = false;
        }
        // Two-finger tap detection
        else if (twoFingerTapPossible && !twoFingerDragging && gestureConfig.getTwoFingerTapEnabled()) {
            // Move cursor to midpoint between the two fingers
            float midX = (twoFingerLastX0 + twoFingerLastX1) / 2f;
            float midY = (twoFingerLastY0 + twoFingerLastY1) / 2f;
            queueTapClick(gestureConfig.getTwoFingerTapAction(), midX, midY);
            notifyGesture(Gesture.TWO_FINGER_TAP);
            twoFingerTapFired = true;
        }
        releaseDragInput();
        twoFingerDragging = false;
        twoFingerTapPossible = false;
        multiFingerStartCount = 0;
        resetSmallMovement();
        twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
        accumulatedPinchDelta = 0;
        accumulatedPanDelta = 0;
        reclassifyPinchDelta = 0;
        reclassifyPanDelta = 0;
        twoFingerLockTime = 0;
        // Suppress single-finger tap/drag when last finger lifts after any 2F gesture
        movedBeyondTapThreshold = true;
        multiFingerGestureUsed = true;
    }

    private void handleTsUp(MotionEvent event, int actionIndex) {
        cancelLongPressTimer();

        if (longPressTriggered) {
            // Release the long-press action
            longPressActionHeld = releaseHoldAction(longPressActionHeld, gestureConfig.getLongPressAction());
            longPressTriggered = false;
            return;
        }

        float x = event.getX(actionIndex);
        float y = event.getY(actionIndex);

        if (isDragging && dragButtonPressed) {
            // End drag (release any held drag buttons/keys)
            releaseDragInput();
            dragButtonPressed = false;
            isDragging = false;
            lastTapTime = 0;
            return;
        }

        if (secondTapCandidate) {
            secondTapCandidate = false;
            long tapLimit = gestureConfig.getLongPressEnabled()
                    ? gestureConfig.getLongPressDelay() : ViewConfiguration.getLongPressTimeout();
            if (SystemClock.uptimeMillis() - singleFingerDownTime < tapLimit
                    && Math.hypot(x - doubleTapAnchorX, y - doubleTapAnchorY) < doubleTapSlop) {
                // A normal left tap already supplied the first click. Other
                // single-tap mappings need a complete synthesized double click.
                boolean firstTapClicked = gestureConfig.getTapEnabled()
                        && TouchGestureConfig.ACTION_LEFT_CLICK.equals(gestureConfig.getTapAction());
                int clicks = firstTapClicked ? 1 : 2;
                for (int i = 0; i < clicks; i++) queueTapClick(TouchGestureConfig.ACTION_LEFT_CLICK, doubleTapAnchorX, doubleTapAnchorY);
                notifyGesture(Gesture.DOUBLE_TAP);
                lastTapTime = 0;
                return;
            }
        }

        // Two-finger tap already sent its click on POINTER_UP — skip the tap on UP
        if (twoFingerTapFired) {
            twoFingerTapFired = false;
            return;
        }

        // Three-finger tap already sent its click — skip the tap on UP
        if (threeFingerTapFired) {
            threeFingerTapFired = false;
            return;
        }

        // Simple tap — only if finger stayed within tap tolerance
        if (gestureConfig.getTapEnabled() && !movedBeyondTapThreshold) {
            queueTapClick(gestureConfig.getTapAction(), x, y);
            notifyGesture(Gesture.TAP);
        }

        // Record for double-tap detection (even if tap itself is disabled,
        // so double-tap can still prime from single touch-ups)
        if (!movedBeyondTapThreshold && (gestureConfig.getTapEnabled() || gestureConfig.getDoubleTapEnabled())) {
            lastTapTime = SystemClock.uptimeMillis();
            lastTapX = x;
            lastTapY = y;
        }
        movedBeyondTapThreshold = false;
    }

    private void handleTsCancel() {
        handleTsCancel(false);
    }

    private void handleTsCancel(boolean preserveTapRelease) {
        cancelLongPressTimer();
        cancelTwoFingerHoldTimer();
        cancelThreeFingerHoldTimer();
        flushPendingHoldClickRelease();
        if (!preserveTapRelease) flushPendingTapRelease();
        if (dragButtonPressed) {
            releaseDragInput();
            dragButtonPressed = false;
        }
        if (longPressTriggered) {
            longPressActionHeld = releaseHoldAction(longPressActionHeld, gestureConfig.getLongPressAction());
            longPressTriggered = false;
        }
        if (twoFingerHoldTriggered) {
            twoFingerHoldActionHeld = releaseHoldAction(twoFingerHoldActionHeld, gestureConfig.getTwoFingerHoldAction());
            twoFingerHoldTriggered = false;
        }
        if (threeFingerHoldTriggered) {
            threeFingerHoldActionHeld = releaseHoldAction(threeFingerHoldActionHeld, gestureConfig.getThreeFingerHoldAction());
            threeFingerHoldTriggered = false;
        }
        releaseDragInput();
        isDragging = false;
        twoFingerDragging = false;
        twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
        threeFingerDragging = false;
        threeFingerTapPossible = false;
        threeFingerTapFired = false;
        threeFingerGestureMode = THREE_FINGER_GESTURE_NONE;
        longPressTriggered = false;
        longPressActionHeld = twoFingerHoldActionHeld = threeFingerHoldActionHeld = false;
        multiFingerGestureUsed = false;
        movedBeyondTapThreshold = false;
        secondTapCandidate = false;
        gestureOwnedPointerIds.clear();
        gestureFingerCount = 0;
        multiFingerStartCount = 0;
        resetSmallMovement();
    }

    private void handleTsThreeFingerDown(MotionEvent event) {
        if (multiFingerGestureUsed) return;
        flushPendingHoldClickRelease();
        cancelLongPressTimer();
        cancelDrag();
        cancelTwoFingerHoldTimer();
        // If a two-finger hold already injected its action, release it before
        // transitioning so the held button/key doesn't stay down.
        if (twoFingerHoldTriggered) {
            twoFingerHoldActionHeld = releaseHoldAction(twoFingerHoldActionHeld, gestureConfig.getTwoFingerHoldAction());
            twoFingerHoldTriggered = false;
        }
        // Clean up two-finger state
        releaseDragInput();
        resetSmallMovement();
        twoFingerDragging = false;
        twoFingerTapPossible = false;
        twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;

        threeFingerTapPossible = true;
        threeFingerDragging = false;
        threeFingerHoldTriggered = threeFingerHoldActionHeld = false;
        threeFingerGestureMode = THREE_FINGER_GESTURE_NONE;
        accumulatedThreeFingerDelta = 0;
        threeFingerDragOffsetX = threeFingerDragOffsetY = 0f;
        threeFingerDownTime = SystemClock.uptimeMillis();

        // Compute midpoint of three gesture fingers
        if (gestureOwnedPointerIds.size() >= 3) {
            float sumX = 0, sumY = 0;
            int validCount = 0;
            for (int i = 0; i < 3; i++) {
                int idx = event.findPointerIndex(gestureOwnedPointerIds.get(i));
                if (idx >= 0) {
                    sumX += event.getX(idx);
                    sumY += event.getY(idx);
                    validCount++;
                }
            }
            if (validCount < 3) return; // Can't proceed without all 3 pointers
            threeFingerLastMidX = sumX / 3f;
            threeFingerLastMidY = sumY / 3f;
            recordMultiFingerOrigins(event, 3);
        }

        // Schedule three-finger hold
        if (gestureConfig.getThreeFingerHoldEnabled()) {
            threeFingerHoldRunnable = () -> {
                threeFingerHoldTriggered = true;
                threeFingerTapPossible = false;
                threeFingerHoldActionHeld = injectHoldAction(
                        gestureConfig.getThreeFingerHoldAction(),
                        gestureConfig.getThreeFingerHoldMouseBehavior());
                notifyGesture(Gesture.THREE_FINGER_HOLD);
            };
            postDelayed(threeFingerHoldRunnable, gestureConfig.getThreeFingerHoldDelay());
        }
    }

    private void handleTsThreeFingerMove(MotionEvent event) {
        if (gestureFingerCount < 3 || gestureOwnedPointerIds.size() < 3) return;
        // If a three-finger hold has already fired, ignore subsequent jitter so
        // it can't lock into a pan gesture mid-hold.
        if (threeFingerHoldTriggered) return;
        if (multiFingerMovementExceeded(event)) {
            threeFingerTapPossible = false;
            cancelThreeFingerHoldTimer();
        }
        if (multiFingerMovementExceeded(event, true)) cancelThreeFingerHoldTimer();

        float sumX = 0, sumY = 0;
        int validCount = 0;
        for (int i = 0; i < 3; i++) {
            int idx = event.findPointerIndex(gestureOwnedPointerIds.get(i));
            if (idx >= 0) {
                sumX += event.getX(idx);
                sumY += event.getY(idx);
                validCount++;
            }
        }
        if (validCount < 3) return;

        float midX = sumX / 3f;
        float midY = sumY / 3f;
        float dx = midX - threeFingerLastMidX;
        float dy = midY - threeFingerLastMidY;
        float frameDelta = (float) Math.hypot(dx, dy);
        long now = SystemClock.uptimeMillis();
        if (now - threeFingerDownTime < THREE_FINGER_SETTLE_MS) {
            threeFingerLastMidX = midX;
            threeFingerLastMidY = midY;
            return;
        }

        if (threeFingerGestureMode == THREE_FINGER_GESTURE_NONE) {
            accumulatedThreeFingerDelta += frameDelta;
            if (accumulatedThreeFingerDelta > (float) gestureConfig.getGestureThreshold()) {
                threeFingerTapPossible = false;
                cancelThreeFingerHoldTimer();
                if (gestureConfig.getThreeFingerDragEnabled()) {
                    threeFingerGestureMode = THREE_FINGER_GESTURE_PAN;
                    notifyGesture(Gesture.THREE_FINGER_DRAG);
                }
            }
        }

        if (threeFingerGestureMode == THREE_FINGER_GESTURE_PAN) {
            // Once recognized, retain small mouse movements. Keyboard panning
            // accumulates its own direction threshold in performKeyPan.
            String threeFingerDragAction = gestureConfig.getThreeFingerDragAction();
            if (isClickDragAction(threeFingerDragAction)) {
                if (useRelativeMouseDragMovement()) {
                    threeFingerDragging = true;
                    performClickDragWithRelativeMovement(dx, dy, threeFingerDragAction);
                } else {
                    if (!threeFingerDragging) moveCursorToMultiFingerOrigin();
                    threeFingerDragging = true;
                    performClickDragAt(midX + threeFingerDragOffsetX, midY + threeFingerDragOffsetY, threeFingerDragAction);
                }
            } else if (dx != 0f || dy != 0f) {
                threeFingerDragging = true;
                performPanAction(dx, dy, threeFingerDragAction);
            }
        }

        threeFingerLastMidX = midX;
        threeFingerLastMidY = midY;
    }

    private void handleTsThreeFingerUp(MotionEvent event) {
        cancelThreeFingerHoldTimer();
        if (multiFingerMovementExceeded(event)) threeFingerTapPossible = false;

        if (threeFingerHoldTriggered) {
            threeFingerHoldActionHeld = releaseHoldAction(threeFingerHoldActionHeld, gestureConfig.getThreeFingerHoldAction());
            threeFingerHoldTriggered = false;
        } else if (threeFingerTapPossible && !threeFingerDragging
                && gestureConfig.getThreeFingerTapEnabled()) {
            queueTapClick(gestureConfig.getThreeFingerTapAction(), threeFingerLastMidX, threeFingerLastMidY);
            notifyGesture(Gesture.THREE_FINGER_TAP);
            threeFingerTapFired = true;
        }

        releaseDragInput();
        threeFingerDragging = false;
        threeFingerTapPossible = false;
        multiFingerStartCount = 0;
        resetSmallMovement();
        threeFingerGestureMode = THREE_FINGER_GESTURE_NONE;
        // Suppress single-finger tap/drag when last finger lifts after any 3F gesture
        movedBeyondTapThreshold = true;
        multiFingerGestureUsed = true;
    }

    private void resetSmallMovement() {
        pinchZoomRemainder = keyPanRemainderX = keyPanRemainderY = 0f;
    }

    private static float accumulateSignedMovement(float remainder, float delta) {
        if (delta != 0f && Math.signum(delta) != Math.signum(remainder)) remainder = 0f;
        return remainder + delta;
    }

    private void recordMultiFingerOrigins(MotionEvent event, int count) {
        multiFingerStartCount = 0;
        for (int i = 0; i < count; i++) {
            int id = gestureOwnedPointerIds.get(i);
            int index = event.findPointerIndex(id);
            if (index < 0) return;
            multiFingerStartIds[i] = id;
            multiFingerStartX[i] = event.getX(index);
            multiFingerStartY[i] = event.getY(index);
        }
        multiFingerStartCount = count;
    }

    private void moveCursorToMultiFingerOrigin() {
        if (multiFingerStartCount == 0) return;
        float x = 0, y = 0;
        for (int i = 0; i < multiFingerStartCount; i++) {
            x += multiFingerStartX[i];
            y += multiFingerStartY[i];
        }
        moveCursorTo((int) (x / multiFingerStartCount), (int) (y / multiFingerStartCount));
    }

    private void rebaseThreeFingerMovement(MotionEvent event) {
        float x = 0, y = 0;
        for (int i = 0; i < 3; i++) {
            int index = event.findPointerIndex(gestureOwnedPointerIds.get(i));
            if (index < 0) return;
            x += event.getX(index);
            y += event.getY(index);
        }
        x /= 3f;
        y /= 3f;
        // Replacing a tracked finger changes the centroid without moving the cursor.
        if (threeFingerDragging) {
            threeFingerDragOffsetX += threeFingerLastMidX - x;
            threeFingerDragOffsetY += threeFingerLastMidY - y;
        }
        threeFingerLastMidX = x;
        threeFingerLastMidY = y;
        accumulatedThreeFingerDelta = 0;
        threeFingerTapPossible = false;
        cancelThreeFingerHoldTimer();
        recordMultiFingerOrigins(event, 3);
        resetSmallMovement();
    }

    private float maxFingerTravel(MotionEvent event, int index, float originX, float originY) {
        float distance = (float) Math.hypot(event.getX(index) - originX, event.getY(index) - originY);
        for (int h = 0; h < event.getHistorySize(); h++) {
            distance = Math.max(distance, (float) Math.hypot(
                    event.getHistoricalX(index, h) - originX, event.getHistoricalY(index, h) - originY));
        }
        return distance;
    }

    private boolean multiFingerMovementExceeded(MotionEvent event) {
        return multiFingerMovementExceeded(event, false);
    }

    private boolean multiFingerMovementExceeded(MotionEvent event, boolean forHold) {
        float tolerance = forHold ? doubleTapSlop : gestureConfig.getGestureThreshold();
        for (int i = 0; i < multiFingerStartCount; i++) {
            int index = event.findPointerIndex(multiFingerStartIds[i]);
            if (index < 0) continue;
            if (maxFingerTravel(event, index, multiFingerStartX[i], multiFingerStartY[i]) > tolerance) return true;
            for (int j = 0; !forHold && j < i; j++) {
                int other = event.findPointerIndex(multiFingerStartIds[j]);
                if (other < 0) continue;
                double initialDistance = Math.hypot(multiFingerStartX[i] - multiFingerStartX[j], multiFingerStartY[i] - multiFingerStartY[j]);
                double distance = Math.hypot(event.getX(index) - event.getX(other), event.getY(index) - event.getY(other));
                if (Math.abs(distance - initialDistance) > tolerance) return true;
                for (int h = 0; h < event.getHistorySize(); h++) {
                    distance = Math.hypot(event.getHistoricalX(index, h) - event.getHistoricalX(other, h),
                            event.getHistoricalY(index, h) - event.getHistoricalY(other, h));
                    if (Math.abs(distance - initialDistance) > tolerance) return true;
                }
            }
        }
        return false;
    }

    private boolean isDoubleTap(MotionEvent event, int index) {
        return gestureConfig.getDoubleTapEnabled() && lastTapTime > 0
                && SystemClock.uptimeMillis() - lastTapTime < gestureConfig.getDoubleTapDelay()
                && Math.hypot(event.getX(index) - lastTapX, event.getY(index) - lastTapY)
                    < doubleTapSlop;
    }

    private void queueTapClick(String action, float x, float y) {
        pendingTapClicks.addLast(new TapClick(action, x, y));
        startPendingTapClick();
    }

    private void startPendingTapClick() {
        if (delayedPress != null || pendingTapPress != null || pendingTapClicks.isEmpty()) return;
        TapClick click = pendingTapClicks.peekFirst();
        // Games that poll button state need an observable UP interval too.
        // Reuse the first tap's location: normal finger drift must not turn
        // this into two clicks on different targets or a tiny drag.
        long delay = Math.max(0, CLICK_DELAYED_TIME - (SystemClock.uptimeMillis() - lastInputReleaseTime));
        pendingTapPress = () -> {
            pendingTapPress = null;
            pendingTapClicks.removeFirst();
            moveCursorTo(click.x, click.y);
            injectClick(click.action);
            delayedPressAction = click.action;
            delayedPress = () -> {
                injectRelease(click.action);
                delayedPress = null;
                delayedPressAction = null;
                startPendingTapClick();
            };
            postDelayed(delayedPress, CLICK_DELAYED_TIME);
        };
        if (delay > 0) postDelayed(pendingTapPress, delay);
        else pendingTapPress.run();
    }

    private void flushPendingTapRelease() {
        if (pendingTapPress != null) removeCallbacks(pendingTapPress);
        pendingTapPress = null;
        pendingTapClicks.clear();
        if (delayedPress != null) {
            removeCallbacks(delayedPress);
            injectRelease(delayedPressAction);
            delayedPressAction = null;
            delayedPress = null;
        }
    }

    private void flushPendingHoldClickRelease() {
        if (pendingHoldClickRelease != null) {
            removeCallbacks(pendingHoldClickRelease);
            String action = pendingHoldClickReleaseAction;
            pendingHoldClickRelease = null;
            pendingHoldClickReleaseAction = null;
            injectRelease(action);
        }
    }

    private void cancelLongPressTimer() {
        if (longPressRunnable != null) {
            removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void cancelDrag() {
        if (isDragging && dragButtonPressed) {
            releaseDragInput();
            dragButtonPressed = false;
        }
        isDragging = false;
    }

    private void cancelTwoFingerHoldTimer() {
        if (twoFingerHoldRunnable != null) {
            removeCallbacks(twoFingerHoldRunnable);
            twoFingerHoldRunnable = null;
        }
    }

    private void cancelThreeFingerHoldTimer() {
        if (threeFingerHoldRunnable != null) {
            removeCallbacks(threeFingerHoldRunnable);
            threeFingerHoldRunnable = null;
        }
    }

    private void moveCursorTo(int x, int y) { output.moveTo(x, y); }

    private boolean injectClick(String action) {
        if (action == null) return false;
        if (TouchGestureConfig.ACTION_SHOW_KEYBOARD.equals(action)) {
            output.showKeyboard(); return false;
        }
        if (TouchGestureConfig.ACTION_KEY_TILDE.equals(action)) {
            pressKey(42); pressKey(41); return true;
        }
        Integer code = actionToKeycode(action);
        if (code != null) { pressKey(code); return true; }
        MouseButton button = actionToButton(action);
        if (button != null) { pressButton(button); return true; }
        return false;
    }

    private void injectRelease(String action) {
        if (action == null) return;
        if (TouchGestureConfig.ACTION_KEY_TILDE.equals(action)) { releaseKey(41); releaseKey(42); return; }
        Integer code = actionToKeycode(action);
        if (code != null) { releaseKey(code); return; }
        MouseButton button = actionToButton(action);
        if (button != null) releaseButton(button);
    }

    private boolean injectHoldAction(String action, String mouseBehavior) {
        if (TouchGestureConfig.isMouseButtonAction(action) && TouchGestureConfig.MOUSE_BEHAVIOR_CLICK.equals(mouseBehavior)) {
            flushPendingHoldClickRelease();
            if (injectClick(action)) {
                pendingHoldClickReleaseAction = action;
                pendingHoldClickRelease = () -> {
                    String pendingAction = pendingHoldClickReleaseAction;
                    pendingHoldClickRelease = null;
                    pendingHoldClickReleaseAction = null;
                    injectRelease(pendingAction);
                };
                postDelayed(pendingHoldClickRelease, CLICK_DELAYED_TIME);
            }
            return false;
        }
        return injectClick(action);
    }

    private boolean releaseHoldAction(boolean held, String action) {
        if (held) injectRelease(action);
        return false;
    }

    private Integer actionToKeycode(String action) {
        return action != null && action.startsWith("key_") ? GestureKeys.code(action.substring(4)) : null;
    }

    private MouseButton actionToButton(String action) {
        if (TouchGestureConfig.ACTION_LEFT_CLICK.equals(action)) return MouseButton.BUTTON_LEFT;
        if (TouchGestureConfig.ACTION_RIGHT_CLICK.equals(action)) return MouseButton.BUTTON_RIGHT;
        if (TouchGestureConfig.ACTION_MIDDLE_CLICK.equals(action)) return MouseButton.BUTTON_MIDDLE;
        return null;
    }
    private void performZoomAction(boolean zoomIn) {
        String action = gestureConfig.getPinchAction();
        if (TouchGestureConfig.ZOOM_SCROLL_WHEEL.equals(action)) { output.wheel(zoomIn ? -1 : 1); return; }
        int code = TouchGestureConfig.ZOOM_PLUS_MINUS.equals(action) ? (zoomIn ? 13 : 12) : (zoomIn ? 104 : 109);
        pressKey(code);
        postDelayed(() -> releaseKey(code), CLICK_DELAYED_TIME);
    }

    private void performPanAction(float dx, float dy, String action) {
        switch (action) {
            case TouchGestureConfig.PAN_MIDDLE_MOUSE:
                performMiddleMousePan(dx, dy);
                break;
            case TouchGestureConfig.PAN_INVERTED_MIDDLE_MOUSE:
                performMiddleMousePan(-dx, -dy);
                break;
            case TouchGestureConfig.PAN_WASD:
                performKeyPan(dx, dy, GestureKeys.code("A"), GestureKeys.code("D"), GestureKeys.code("W"), GestureKeys.code("S"));
                break;
            case TouchGestureConfig.PAN_INVERTED_WASD:
                performKeyPan(dx, dy, GestureKeys.code("D"), GestureKeys.code("A"), GestureKeys.code("S"), GestureKeys.code("W"));
                break;
            case TouchGestureConfig.PAN_ARROW_KEYS:
                performKeyPan(dx, dy, GestureKeys.code("LEFT"), GestureKeys.code("RIGHT"), GestureKeys.code("UP"), GestureKeys.code("DOWN"));
                break;
            case TouchGestureConfig.PAN_INVERTED_ARROW_KEYS:
                performKeyPan(dx, dy, GestureKeys.code("RIGHT"), GestureKeys.code("LEFT"), GestureKeys.code("DOWN"), GestureKeys.code("UP"));
                break;
            case TouchGestureConfig.PAN_LEFT_CLICK_DRAG:
                performClickDrag(dx, dy, MouseButton.BUTTON_LEFT);
                break;
            case TouchGestureConfig.PAN_RIGHT_CLICK_DRAG:
                performClickDrag(dx, dy, MouseButton.BUTTON_RIGHT);
                break;
        }
    }

    private boolean isClickDragAction(String action) {
        return TouchGestureConfig.PAN_LEFT_CLICK_DRAG.equals(action)
                || TouchGestureConfig.PAN_RIGHT_CLICK_DRAG.equals(action);
    }

    private boolean useRelativeMouseDragMovement() {
        return TouchGestureConfig.MOUSE_DRAG_MOVEMENT_RELATIVE.equals(gestureConfig.getMouseDragMovementMode());
    }

    private MouseButton buttonForClickDragAction(String action) {
        return TouchGestureConfig.PAN_RIGHT_CLICK_DRAG.equals(action)
                ? MouseButton.BUTTON_RIGHT : MouseButton.BUTTON_LEFT;
    }

    private void performClickDragAt(float rawX, float rawY, String action) {
        pressClickDragAction(action);
        moveCursorTo((int) rawX, (int) rawY);
    }

    private void performMiddleMousePan(float dx, float dy) {
        if (!twoFingerMiddleButtonDown) {
            output.prepareRelativePan();
            pressButton(MouseButton.BUTTON_MIDDLE);
            twoFingerMiddleButtonDown = true;
        }
        // Pan from the client's current pointer. Games can recenter the mouse
        // when the middle button goes down; replaying our old absolute touch
        // anchor after that warp causes a large, unintended camera movement.
        moveCursorByRelativeDelta(dx, dy);
    }

    private void performClickDrag(float dx, float dy, MouseButton button) {
        pressClickDragButton(button);
        moveCursorByDelta(dx, dy);
    }

    private void performClickDragWithRelativeMovement(float dx, float dy, String action) {
        pressClickDragAction(action);
        moveCursorByRelativeDelta(dx, dy);
    }

    private void pressClickDragAction(String action) {
        pressClickDragButton(buttonForClickDragAction(action));
    }

    private void pressClickDragButton(MouseButton button) {
        if (button == MouseButton.BUTTON_LEFT && !leftClickDragButtonDown) {
            pressButton(MouseButton.BUTTON_LEFT);
            leftClickDragButtonDown = true;
        } else if (button == MouseButton.BUTTON_RIGHT && !rightClickDragButtonDown) {
            pressButton(MouseButton.BUTTON_RIGHT);
            rightClickDragButtonDown = true;
        }
    }

    private void moveCursorByRelativeDelta(float dx, float dy) { output.moveBy(dx, dy, true); }

    private void moveCursorByDelta(float dx, float dy) { output.moveBy(dx, dy, false); }

    private void releaseTwoFingerMiddleButton() {
        if (twoFingerMiddleButtonDown) {
            releaseButton(MouseButton.BUTTON_MIDDLE);
            twoFingerMiddleButtonDown = false;
        }
    }

    private void releaseClickDragButtons() {
        if (leftClickDragButtonDown) {
            releaseButton(MouseButton.BUTTON_LEFT);
            leftClickDragButtonDown = false;
        }
        if (rightClickDragButtonDown) {
            releaseButton(MouseButton.BUTTON_RIGHT);
            rightClickDragButtonDown = false;
        }
    }

    private void releaseDragInput() {
        releasePanKeys();
        releaseAllDragButtons();
    }

    private void releaseAllDragButtons() {
        releaseTwoFingerMiddleButton();
        releaseClickDragButtons();
    }

    private void performKeyPan(float dx, float dy, Integer leftKey, Integer rightKey, Integer upKey, Integer downKey) {
        // When fingers are actively moving, update key states to match the
        // swipe direction.  When fingers stop moving but stay on screen,
        // keep the current keys held — movement continues until finger-up
        // calls releasePanKeys().

        keyPanRemainderX = accumulateSignedMovement(keyPanRemainderX, dx);
        keyPanRemainderY = accumulateSignedMovement(keyPanRemainderY, dy);
        float totalDelta = Math.abs(keyPanRemainderX) + Math.abs(keyPanRemainderY);
        if (totalDelta < 5f || (Math.abs(keyPanRemainderX) <= 3f && Math.abs(keyPanRemainderY) <= 3f)) return;
        dx = keyPanRemainderX;
        dy = keyPanRemainderY;
        keyPanRemainderX = keyPanRemainderY = 0f;

        // --- Significant movement detected: update each axis ---

        // Horizontal: determine desired state from this frame's delta
        boolean wantLeft  = dx < -3f;
        boolean wantRight = dx > 3f;

        if (panKeyLeft && !wantLeft)   { releaseKey(leftKey);  panKeyLeft = false; }
        if (panKeyRight && !wantRight) { releaseKey(rightKey); panKeyRight = false; }
        if (wantLeft && !panKeyLeft)   { pressKey(leftKey);    panKeyLeft = true; panLeftKey = leftKey; }
        if (wantRight && !panKeyRight) { pressKey(rightKey);   panKeyRight = true; panRightKey = rightKey; }

        // Vertical: determine desired state from this frame's delta
        boolean wantUp   = dy < -3f;
        boolean wantDown = dy > 3f;

        if (panKeyUp && !wantUp)     { releaseKey(upKey);   panKeyUp = false; }
        if (panKeyDown && !wantDown) { releaseKey(downKey); panKeyDown = false; }
        if (wantUp && !panKeyUp)     { pressKey(upKey);     panKeyUp = true; panUpKey = upKey; }
        if (wantDown && !panKeyDown) { pressKey(downKey);   panKeyDown = true; panDownKey = downKey; }
    }

    private void releasePanKeys() {
        keyPanRemainderX = keyPanRemainderY = 0f;
        if (panKeyLeft  && panLeftKey  != null) { releaseKey(panLeftKey);  panKeyLeft = false; }
        if (panKeyRight && panRightKey != null) { releaseKey(panRightKey); panKeyRight = false; }
        if (panKeyUp    && panUpKey    != null) { releaseKey(panUpKey);    panKeyUp = false; }
        if (panKeyDown  && panDownKey  != null) { releaseKey(panDownKey);  panKeyDown = false; }
    }

}
