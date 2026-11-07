package com.droiddeck.launcher.input

class InputSurfaceSource(private val pad: PadBridge, private val role: InputRole, val element: InputSurfaceElement) {
    private val router = pad.inputRouter
    private val route = router?.channel(role, element.control, element = element)
    private val deck = DeckControls.source(role, routed = true)
    private val clickDeck = DeckControls.source(role, routed = true)
    private val clickOwner = Any()
    private val clickRoute = if (element.hasClick) router?.channel(role, InputControl.A, element = element, elementClick = true) else null
    private val gestureWheel = if (element.mouseGestures) router?.channel(role, InputControl.RIGHT_PAD, fixedAction = InputAction.Scroll()) else null
    private val gestureRight = if (element.mouseGestures) router?.channel(role, InputControl.A, fixedAction = InputAction.MouseButton(273)) else null
    private val gestureMiddle = if (element.mouseGestures) router?.channel(role, InputControl.A, fixedAction = InputAction.MouseButton(274)) else null
    val mouseGestures get() = element.mouseGestures && route?.action is InputAction.Pointer

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
    private fun resetLatch() { latched = false; latchBlocked = fingerDown; onStateChanged() }
    private val snapshot: () -> Unit = { releaseNative(); resetLatch(); if (element.toggle) route?.release() }

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

    fun cancel() {
        if (closed) return
        route?.takeIf { it.action is InputAction.Command }?.release()
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
        router?.removeSnapshot(snapshot)
        releaseNative(); resetLatch()
        route?.close(); clickRoute?.close(); gestureWheel?.close(); gestureRight?.close(); gestureMiddle?.close(); deck.close(); clickDeck.close()
    }

    fun release() {
        if (closed) return
        releaseNative(); resetLatch()
        route?.release(); clickRoute?.release(); gestureWheel?.release(); gestureRight?.release(); gestureMiddle?.release()
    }

}
