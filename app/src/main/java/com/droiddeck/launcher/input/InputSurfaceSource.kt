package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class InputSurfaceSource(private val pad: PadBridge, private val role: InputRole, val element: InputSurfaceElement) {
    private val router = pad.inputRouter
    private val route = router?.channel(role, element.control, element = element)
    private val deck = DeckControls.source(role, routed = true)
    private val clickDeck = DeckControls.source(role, routed = true)
    private val clickOwner = Any()
    private val clickRoute = if (element.hasClick) router?.channel(role, InputControl.A, element = element, elementSource = ElementSource.CLICK) else null
    private val hold = Aux(ElementSource.HOLD)
    private val double = Aux(ElementSource.DOUBLE)
    private val gestureWheel = if (element.mouseGestures) router?.channel(role, InputControl.RIGHT_PAD, fixedAction = InputAction.Scroll()) else null
    private val gestureRight = if (element.mouseGestures) router?.channel(role, InputControl.A, fixedAction = InputAction.MouseButton(273)) else null
    private val gestureMiddle = if (element.mouseGestures) router?.channel(role, InputControl.A, fixedAction = InputAction.MouseButton(274)) else null
    val mouseGestures get() = element.mouseGestures && route?.action is InputAction.Pointer
    val directions get() = route?.action as? InputAction.Directions

    private val handler = Handler(Looper.getMainLooper())
    private var pressedAt = 0L
    private var pressed = false
    private var pendingSingle = false
    private var holding = false
    private var awaitingDouble = false
    private var doubleFiring = false
    private var repeating = false

    fun gestureContact(touching: Boolean) { if (!closed) route?.update(0f, 0f, touching) }
    fun pointerDelta(dx: Float, dy: Float) {
        if (closed) return
        val timing = InputTiming.begin()
        route?.pointerDelta(dx, dy)
        InputTiming.end(timing)
    }
    fun gestureWheel(steps: Int) { if (!closed && route?.acceptsPointerGesture() == true) gestureWheel?.wheelSteps(steps) }
    fun gestureButton(code: Int, down: Boolean) {
        if (closed || down && route?.acceptsPointerGesture() != true) return
        when (code) {
            272 -> click(down)
            273 -> gestureRight?.update(if (down) 1f else 0f)
            274 -> gestureMiddle?.update(if (down) 1f else 0f)
        }
    }

    private var nativePad = false
    private var nativeDeck = false
    private var clickPad = false
    private var clickDeckActive = false
    private var closed = false
    private var fingerDown = false
    private var latchBlocked = false
    var latched = false
        private set
    var onStateChanged: () -> Unit = {}
    var onHoldStarted: () -> Unit = {}
    private fun resetLatch() { latched = false; latchBlocked = fingerDown; onStateChanged() }
    private val snapshot: () -> Unit = { releaseNative(); resetLatch(); clearTimers(); if (element.toggle) route?.release() }

    init { router?.beforeProfileChange(snapshot) }

    fun update(value: Float, y: Float = 0f, touching: Boolean = false) {
        if (closed) return
        val timing = InputTiming.begin()
        val x = if (element.toggle && route?.action !is InputAction.Command && route?.action !is InputAction.Disabled) {
            val down = value > .5f
            if (!down) latchBlocked = false
            if (down && !fingerDown && !latchBlocked) latched = !latched
            fingerDown = down
            if (latched) 1f else 0f
        } else value
        val target = if (router == null) element.control else route?.update(x, y, touching)
        val toPad = target?.isPad == true
        val toDeck = target != null && !target.isPad
        if (toPad || nativePad) writePad(this, target) { state ->
            state.clear()
            if (toPad) {
                if (element.kind == InputElementKind.DPAD) {
                    state.up = y < -.5f; state.down = y > .5f; state.left = x < -.5f; state.right = x > .5f
                } else state.add(target, x, y)
            }
        }
        if (toDeck || nativeDeck) deck.native(if (toDeck) target else null, x, y, touching)
        nativePad = toPad; nativeDeck = toDeck
        if (element.toggle) onStateChanged()
        InputTiming.end(timing)
    }

    fun press() {
        if (closed || pressed) return
        pressed = true
        pressedAt = SystemClock.uptimeMillis()
        if (!element.tapElement()) { update(1f); return }
        if (awaitingDouble) {
            awaitingDouble = false
            handler.removeCallbacks(singleTap)
            handler.removeCallbacks(startHeld)
            handler.removeCallbacks(startHold)
            doubleFiring = true
            double.set(true)
            return
        }
        if (double.configured) {
            pendingSingle = true
            if (!hold.configured && !element.repeat) handler.postDelayed(startHeld, ScreenKeyTiming.doubleMs)
        } else update(1f)
        if (hold.configured || element.repeat) handler.postDelayed(startHold, ScreenKeyTiming.holdMs)
    }

    fun releasePress() {
        if (closed || !pressed) return
        pressed = false
        handler.removeCallbacks(startHeld)
        handler.removeCallbacks(startHold)
        if (doubleFiring) {
            doubleFiring = false
            double.set(false)
            return
        }
        if (holding) { endHold(); return }
        if (pendingSingle) {
            pendingSingle = false
            if (double.configured && SystemClock.uptimeMillis() - pressedAt < ScreenKeyTiming.holdMs) {
                awaitingDouble = true
                handler.postDelayed(singleTap, ScreenKeyTiming.doubleMs)
            } else tap()
            return
        }
        update(0f)
    }

    fun cancel() {
        if (closed) return
        pressed = false
        clearTimers()
        if (hold.configured) hold.set(false)
        if (double.configured) double.set(false)
        route?.takeIf { it.action is InputAction.Command }?.release()
        if (holding) { endHold(); return }
        update(0f)
    }

    private val singleTap = Runnable {
        awaitingDouble = false
        if (!pressed && !closed) tap()
    }

    private val startHeld = Runnable {
        if (!pressed || closed || !pendingSingle) return@Runnable
        pendingSingle = false
        update(1f)
    }

    private val startHold = Runnable {
        if (!pressed || closed || holding) return@Runnable
        holding = true
        pendingSingle = false
        handler.removeCallbacks(startHeld)
        if (hold.configured) {
            update(0f)
            hold.set(true)
            onHoldStarted()
            if (element.repeat) startRepeat()
        } else if (element.repeat) startRepeat()
    }

    private val repeatTick = object : Runnable {
        override fun run() {
            if (!pressed || closed || !repeating) return
            if (hold.configured) hold.set(true) else update(1f)
            handler.postDelayed({
                if (pressed && !closed && repeating) {
                    if (hold.configured) hold.set(false) else update(0f)
                }
            }, ScreenKeyTiming.repeatMs / 2)
            handler.postDelayed(this, ScreenKeyTiming.repeatMs)
        }
    }

    private fun startRepeat() {
        repeating = true
        handler.postDelayed(repeatTick, ScreenKeyTiming.repeatMs)
    }

    private fun endHold() {
        repeating = false
        handler.removeCallbacks(repeatTick)
        if (hold.configured) hold.set(false)
        update(0f)
        holding = false
    }

    private fun clearTimers() {
        handler.removeCallbacks(singleTap)
        handler.removeCallbacks(startHeld)
        handler.removeCallbacks(startHold)
        handler.removeCallbacks(repeatTick)
        awaitingDouble = false
        pendingSingle = false
        repeating = false
        doubleFiring = false
    }

    private fun tap() {
        update(1f)
        update(0f)
    }

    fun click(down: Boolean) {
        if (closed) return
        val timing = InputTiming.begin()
        val target = if (router == null) when (element.control) { InputControl.LS -> InputControl.L3; InputControl.RS -> InputControl.R3; else -> null } else clickRoute?.update(if (down) 1f else 0f)
        val toPad = target?.isPad == true
        val toDeck = target != null && !target.isPad
        if (toPad || clickPad) writePad(clickOwner, target) { state ->
            state.clear()
            if (toPad && down) state.add(target, 1f)
        }
        if (toDeck || clickDeckActive) clickDeck.native(if (toDeck) target else null, if (down) 1f else 0f, 0f, false)
        clickPad = toPad; clickDeckActive = toDeck
        InputTiming.end(timing)
    }

    private inner class Aux(source: ElementSource) {
        val channel = if (element.tapElement()) router?.channel(role, element.control, element = element, elementSource = source) else null
        private val deckSource = if (element.tapElement()) DeckControls.source(role, routed = true) else null
        private val owner = Any()
        private var onPad = false
        private var onDeck = false
        val configured get() = channel?.action.let { it != null && it != InputAction.Disabled }

        fun set(down: Boolean) {
            val channel = channel ?: return
            if (closed) return
            val timing = InputTiming.begin()
            val target = channel.update(if (down) 1f else 0f)
            if (down) {
                val toPad = target?.isPad == true
                val toDeck = target != null && !target.isPad
                if (toPad) writePad(owner, target) { state -> state.clear(); state.add(target, 1f) }
                if (toDeck) deckSource?.native(target, 1f, 0f, false)
                onPad = toPad; onDeck = toDeck
            } else {
                if (onPad) writePad(owner, null) { state -> state.clear() }
                if (onDeck) deckSource?.native(null, 0f, 0f, false)
                onPad = false; onDeck = false
            }
            InputTiming.end(timing)
        }

        private fun clear() {
            if (onPad) writePad(owner, null) { state -> state.clear() }
            if (onDeck) deckSource?.native(null, 0f, 0f, false)
            onPad = false; onDeck = false
        }
        fun close() { clear(); channel?.close(); deckSource?.close() }
        fun release() { clear(); channel?.release(); deckSource?.release() }
    }

    private fun writePad(owner: Any, target: InputControl?, mutation: (PadState) -> Unit) {
        if (router != null) pad.applyRoutedTouch(owner, role, mutation)
        else pad.applyTouch(owner, role, when (target) { InputControl.LS -> PadBridge.LEFT_STICK; InputControl.RS -> PadBridge.RIGHT_STICK; else -> 0 }, mutation)
    }

    private fun releaseNative() {
        if (nativePad) pad.releaseTouch(this)
        if (nativeDeck) deck.release()
        if (clickPad) pad.releaseTouch(clickOwner)
        if (clickDeckActive) clickDeck.release()
        clickPad = false; clickDeckActive = false
        nativePad = false; nativeDeck = false
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        router?.removeSnapshot(snapshot)
        releaseNative(); resetLatch()
        route?.close(); clickRoute?.close(); gestureWheel?.close(); gestureRight?.close(); gestureMiddle?.close(); deck.close(); clickDeck.close()
        hold.close(); double.close()
    }

    fun release() {
        if (closed) return
        clearTimers()
        releaseNative(); resetLatch()
        route?.release(); clickRoute?.release(); gestureWheel?.release(); gestureRight?.release(); gestureMiddle?.release()
        hold.release(); double.release()
        holding = false
    }
}
