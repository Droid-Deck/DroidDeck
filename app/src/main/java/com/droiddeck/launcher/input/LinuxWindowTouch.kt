package com.droiddeck.launcher.input

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.input.InputManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.droiddeck.launcher.session.SessionPrefs
import kotlin.math.hypot

class LinuxWindowTouch(
    context: Context,
    private val send: (type: Int, a: Int, b: Int) -> Unit,
    private val onTap: () -> Unit,
    private val onKeyboard: () -> Unit,
    private val onCursor: (x: Float, y: Float, visible: Boolean) -> Unit = { _, _, _ -> },
    private val sendGesture: (type: Int, a: Float, b: Float, c: Float, time: Long) -> Unit = { _, _, _, _, _ -> },
    private val sendMotion: (x: Float, y: Float, time: Long) -> Unit = { x, y, _ -> send(2, x.toInt(), y.toInt()) },
) {
    private val context = context.applicationContext
    private val session = SessionPrefs.sessionPrefs(this.context)
    private val preferences = this.context.getSharedPreferences("touch_gestures", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val slop = PointerGestures.slop(context)
    private var started = false
    private var accepting = false
    private var tapCandidate = false
    private var downX = 0f
    private var downY = 0f
    private var directPointer = -1
    private var scrolling = false
    private var config = GesturePrefs.read(context)
    private var mode = SessionPrefs.touchMode(context)
    private var directCursor = SessionPrefs.directCursor(context)
    private var cursorPresent = false
    private var mouseCursor = false
    private val buttons = mutableSetOf<Int>()
    private val mouseButtons = mutableSetOf<Int>()
    private val touchpadButtons = mutableSetOf<Int>()
    private val keys = mutableSetOf<Int>()
    private val keyOwners = mutableMapOf<Int, MutableSet<String>>()
    private val mouseWheel = MouseWheelInput { v, h -> send(5, v, h) }
    private var physicalEventTime = 0L
    private val physicalTouchpad = AndroidTouchpadInput(object : AndroidTouchpadInput.Output {
        override fun eventTime(time: Long) { physicalEventTime = time }
        override fun contact(fingers: Int) { gesture(8, fingers.toFloat(), time = physicalEventTime) }
        override fun move(x: Float, y: Float, relative: Boolean) {
            mouseCursor = true
            if (relative) moveTo(touchpad.x + x, touchpad.y + y, physicalEventTime)
            else moveTo(x, y, physicalEventTime)
        }
        override fun button(code: Int, down: Boolean) {
            val changed = if (down) touchpadButtons.add(code) else touchpadButtons.remove(code)
            if (changed && code !in buttons && code !in mouseButtons) send(3, code, if (down) 1 else 0)
        }
        override fun scroll(dx: Float, dy: Float) = fingerScroll(dx, dy, physicalEventTime)
        override fun scrollEnd(cancelled: Boolean) = endScroll(cancelled, physicalEventTime)
        override fun pinchBegin(fingers: Int) { gesture(2, fingers.toFloat(), time = physicalEventTime) }
        override fun pinchUpdate(dx: Float, dy: Float, scale: Float) { gesture(3, dx, dy, scale, physicalEventTime) }
        override fun pinchEnd(cancelled: Boolean) { gesture(4, if (cancelled) 1f else 0f, time = physicalEventTime) }
        override fun key(code: Int, down: Boolean) = key(code, down, "physical")
        override fun keyboard() { handler.post { cancel(); onKeyboard() } }
        override fun swipeBegin(fingers: Int) { gesture(5, fingers.toFloat(), time = physicalEventTime) }
        override fun swipeUpdate(dx: Float, dy: Float) { gesture(6, dx, dy, time = physicalEventTime) }
        override fun swipeEnd(cancelled: Boolean) { gesture(7, if (cancelled) 1f else 0f, time = physicalEventTime) }
    }, slop, ViewConfiguration.get(context).scaledHorizontalScrollFactor,
        ViewConfiguration.get(context).scaledVerticalScrollFactor)
    private val inputManager = this.context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val devices = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) {}
        override fun onInputDeviceChanged(id: Int) {}
        override fun onInputDeviceRemoved(id: Int) { physicalTouchpad.deviceRemoved(id) }
    }

    private val listener = object : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) = moveTo(x, y)
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
            moveTo(x, y)
            button(button, pressed)
        }
        override fun onWheel(steps: Int) { send(5, steps * 120, 0) }
        override fun onScroll(dx: Float, dy: Float) = fingerScroll(dx * 0.25f, dy * 0.25f)
        override fun onScrollEnd() = endScroll()
        override fun onPinchBegin(fingers: Int) { gesture(2, fingers.toFloat()) }
        override fun onPinchUpdate(dx: Float, dy: Float, scale: Float) { gesture(3, dx, dy, scale) }
        override fun onPinchEnd(cancelled: Boolean) { gesture(4, if (cancelled) 1f else 0f) }
        override fun onSwipeBegin(fingers: Int) { gesture(5, fingers.toFloat()) }
        override fun onSwipeUpdate(dx: Float, dy: Float) { gesture(6, dx, dy) }
        override fun onSwipeEnd(cancelled: Boolean) { gesture(7, if (cancelled) 1f else 0f) }
        override fun onKey(code: Int, down: Boolean) = key(code, down, "pad")
        override fun onKeyboard() { handler.post { cancel(); this@LinuxWindowTouch.onKeyboard() } }
        override fun onLongPress() {}
    }
    private val touchpad = TouchpadGestures(slop, listener).apply { desktop = true }
    private val direct = DirectTouchHold(slop, listener, object : DirectTouchHold.Touch {
        override fun down(pointerId: Int, x: Float, y: Float) {
            if (directPointer != -1) return
            directPointer = pointerId
            moveTo(x, y)
            button(PointerGestures.BTN_LEFT, true)
        }
        override fun move(pointerId: Int, x: Float, y: Float) {
            if (directPointer == pointerId) moveTo(x, y)
        }
        override fun up(pointerId: Int, x: Float, y: Float) {
            if (directPointer != pointerId) return
            moveTo(x, y)
            button(PointerGestures.BTN_LEFT, false)
            directPointer = -1
        }
        override fun cancel() {
            directPointer = -1
            button(PointerGestures.BTN_LEFT, false)
        }
    })
    private val twoFinger = TwoFingerGestures(slop, object : TwoFingerGestures.Listener {
        override fun cancelTouch() = direct.cancel()
        override fun replay(event: MotionEvent) { direct.onTouch(event) }
        override fun rightClick(x: Float, y: Float) {
            listener.onButton(PointerGestures.BTN_RIGHT, true, x, y)
            handler.postDelayed({ button(PointerGestures.BTN_RIGHT, false) }, 50)
        }
        override fun scrollStart(x: Float, y: Float) = moveTo(x, y)
        override fun scroll(dx: Float, dy: Float) = listener.onScroll(dx, dy)
        override fun scrollEnd() = listener.onScrollEnd()
    })
    private val gestures = TouchscreenGestures(slop, object : TouchscreenGestures.Output {
        override fun moveTo(x: Float, y: Float) = this@LinuxWindowTouch.moveTo(x, y)
        override fun moveBy(dx: Float, dy: Float, relative: Boolean) {
            moveTo(touchpad.x + dx, touchpad.y + dy)
        }
        override fun button(code: Int, down: Boolean) = this@LinuxWindowTouch.button(code, down)
        override fun key(code: Int, down: Boolean) = this@LinuxWindowTouch.key(code, down)
        override fun wheel(steps: Int) = listener.onWheel(steps)
        override fun swipeBegin(fingers: Int) = listener.onSwipeBegin(fingers)
        override fun swipeUpdate(dx: Float, dy: Float) = listener.onSwipeUpdate(dx, dy)
        override fun swipeEnd(cancelled: Boolean) = listener.onSwipeEnd(cancelled)
        override fun prepareRelativePan() {
            moveTo(touchpad.bounds.centerX(), touchpad.bounds.centerY())
        }
        override fun showKeyboard() {
            handler.post { cancel(); onKeyboard() }
        }
    })
    private val multiActions = GestureActionExecutor(object : GestureActionExecutor.Output {
        override fun key(code: Int, down: Boolean) = key(code, down, "direct")
        override fun button(code: Int, down: Boolean) = this@LinuxWindowTouch.button(code, down)
        override fun keyboard() { handler.post { cancel(); onKeyboard() } }
    })
    private val directMulti = MultiFingerInput(slop, object : MultiFingerInput.Output {
        override fun cancelLower() { twoFinger.cancel(); direct.cancel() }
        override fun click(action: String) = multiActions.click(action)
        override fun hold(action: String, down: Boolean) = multiActions.hold(action, down)
        override fun swipeBegin(fingers: Int) = listener.onSwipeBegin(fingers)
        override fun swipeUpdate(dx: Float, dy: Float) = listener.onSwipeUpdate(dx, dy)
        override fun swipeEnd(cancelled: Boolean) = listener.onSwipeEnd(cancelled)
    })
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { source, name ->
        if (started && ((source === session && (name == null || name == "touch" || name == "directCursor")) ||
                (source === preferences && (name == null || name == "default")))) refresh()
    }

    fun start() {
        if (started) return
        started = true
        inputManager?.registerInputDeviceListener(devices, handler)
        session.registerOnSharedPreferenceChangeListener(changed)
        preferences.registerOnSharedPreferenceChangeListener(changed)
        refresh()
    }

    fun stop() {
        started = false
        inputManager?.unregisterInputDeviceListener(devices)
        session.unregisterOnSharedPreferenceChangeListener(changed)
        preferences.unregisterOnSharedPreferenceChangeListener(changed)
        cancel()
        cancelMouse()
        hideCursor()
    }

    private fun refresh() {
        val nextMode = SessionPrefs.touchMode(context)
        val nextConfig = GesturePrefs.read(context)
        val nextCursor = SessionPrefs.directCursor(context)
        if (mode == nextMode && config == nextConfig && directCursor == nextCursor) return
        val routingChanged = mode != nextMode || config != nextConfig
        if (routingChanged) cancel()
        mode = nextMode
        config = nextConfig
        directCursor = nextCursor
        if (routingChanged) {
            direct.configure(config)
            gestures.configure(config)
            touchpad.configure(config)
            physicalTouchpad.configure(config)
            directMulti.configure(config)
        }
        cursor()
    }

    init {
        direct.configure(config)
        gestures.configure(config)
        touchpad.configure(config)
        physicalTouchpad.configure(config)
        directMulti.configure(config)
    }

    fun resize(width: Int, height: Int) {
        cancel()
        cancelMouse()
        val fresh = touchpad.bounds.width() <= 1f
        touchpad.bounds = RectF(0f, 0f, (width - 1).coerceAtLeast(0).toFloat(),
            (height - 1).coerceAtLeast(0).toFloat())
        if (fresh) touchpad.place(touchpad.bounds.centerX(), touchpad.bounds.centerY())
        else touchpad.place(touchpad.x, touchpad.y)
        cursor()
    }

    fun place(x: Float, y: Float) = touchpad.place(x, y)

    private fun moveTo(x: Float, y: Float, time: Long = SystemClock.uptimeMillis()) {
        touchpad.place(x, y)
        sendMotion(touchpad.x, touchpad.y, time)
        cursorPresent = true
        cursor()
    }

    private fun cursor() {
        val touchVisible = when (mode) {
            SessionPrefs.TOUCH_DIRECT -> directCursor
            SessionPrefs.TOUCH_OFF, SessionPrefs.TOUCH_GESTURES -> false
            else -> true
        }
        onCursor(touchpad.x, touchpad.y, cursorPresent && (mouseCursor || mouseButtons.isNotEmpty() || touchVisible))
    }

    fun hideCursor() {
        gesture(9)
        cursorPresent = false
        cursor()
    }

    private fun button(code: Int, down: Boolean) {
        val changed = if (down) buttons.add(code) else buttons.remove(code)
        if (!changed) return
        if (code !in mouseButtons && code !in touchpadButtons) send(3, code, if (down) 1 else 0)
        if (!down && code == PointerGestures.BTN_LEFT && tapCandidate && code !in mouseButtons) {
            onTap()
        }
    }

    private fun mouseButton(code: Int, down: Boolean) {
        val changed = if (down) mouseButtons.add(code) else mouseButtons.remove(code)
        if (changed && code !in buttons && code !in touchpadButtons) send(3, code, if (down) 1 else 0)
    }

    fun cancelMouse() {
        mouseButtons.toList().forEach { mouseButton(it, false) }
        mouseWheel.reset()
    }

    fun showMouseCursor(x: Float, y: Float) {
        mouseCursor = true
        touchpad.place(x, y)
        cursorPresent = true
        cursor()
    }

    fun onMouse(event: MotionEvent): Boolean {
        if (AndroidPointerSource.kind(event) != AndroidPointerSource.Kind.MOUSE) return false
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            hideCursor()
            return true
        }
        mouseCursor = true
        moveTo(event.x, event.y)
        fun code(mask: Int) = when {
            mask and MotionEvent.BUTTON_PRIMARY != 0 -> PointerGestures.BTN_LEFT
            mask and MotionEvent.BUTTON_SECONDARY != 0 -> PointerGestures.BTN_RIGHT
            mask and MotionEvent.BUTTON_TERTIARY != 0 -> PointerGestures.BTN_MIDDLE
            else -> PointerGestures.BTN_LEFT
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> mouseButton(code(event.buttonState), true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelMouse()
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE ->
                mouseButton(code(event.actionButton), event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            MotionEvent.ACTION_SCROLL -> mouseWheel.onEvent(event)
        }
        return true
    }

    private fun key(code: Int, down: Boolean, owner: String = "screen") {
        val owners = keyOwners.getOrPut(code) { mutableSetOf() }
        val before = owners.isNotEmpty()
        if (down) owners.add(owner) else owners.remove(owner)
        val after = owners.isNotEmpty()
        if (!after) keyOwners.remove(code)
        if (before != after) {
            if (after) keys.add(code) else keys.remove(code)
            send(4, code, if (after) 1 else 0)
        }
    }

    fun keyboardKey(code: Int, down: Boolean) = key(code, down, "keyboard")

    fun cancel() {
        accepting = false
        tapCandidate = false
        handler.removeCallbacksAndMessages(null)
        twoFinger.cancel()
        direct.cancel()
        touchpad.cancel()
        gestures.cancel()
        physicalTouchpad.cancel()
        directMulti.cancel()
        multiActions.cancel()
        buttons.toList().forEach { button(it, false) }
        keyOwners.toMap().forEach { (code, owners) ->
            owners.filter { it != "keyboard" }.forEach { key(code, false, it) }
        }
        endScroll(true)
    }

    private fun gesture(type: Int, a: Float = 0f, b: Float = 0f, c: Float = 0f,
                        time: Long = SystemClock.uptimeMillis()) = sendGesture(type, a, b, c, time)

    private fun fingerScroll(dx: Float, dy: Float, time: Long = SystemClock.uptimeMillis()) {
        if (dx == 0f && dy == 0f) return
        scrolling = true
        gesture(0, dx, dy, time = time)
    }

    private fun endScroll(cancelled: Boolean = false, time: Long = SystemClock.uptimeMillis()) {
        if (scrolling) gesture(1, if (cancelled) 1f else 0f, time = time)
        scrolling = false
    }

    fun onPointer(event: MotionEvent): Boolean = when (AndroidPointerSource.kind(event)) {
        AndroidPointerSource.Kind.TOUCHPAD -> if (started) physicalTouchpad.onEvent(event) else true
        AndroidPointerSource.Kind.MOUSE -> onMouse(event)
        AndroidPointerSource.Kind.TOUCHSCREEN -> onTouch(event)
        AndroidPointerSource.Kind.OTHER -> false
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (!started) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            accepting = true
            tapCandidate = false
            mouseCursor = false
            downX = event.x
            downY = event.y
        }
        if (!accepting) return true
        if (event.pointerCount > 1 || hypot(event.x - downX, event.y - downY) > slop)
            tapCandidate = false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { cancel(); return true }
        val route = TouchRouter.decide(TouchRouter.Snapshot(
            uiBlocking = false, mode = mode, scrollLayer = false,
            steamSession = false, steamDesktopUi = true,
        ))
        when (route) {
            TouchRouter.Route.TOUCHPAD -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) moveTo(touchpad.x, touchpad.y)
                touchpad.onTouch(event)
            }
            TouchRouter.Route.GESTURES -> gestures.onTouch(event)
            TouchRouter.Route.DIRECT -> if (!directMulti.onTouch(event, twoFinger.committed)) {
                if (!twoFinger.onTouch(event)) direct.onTouch(event)
            }
            else -> tapCandidate = false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && route != TouchRouter.Route.OFF)
            tapCandidate = true
        cursor()
        if (event.actionMasked == MotionEvent.ACTION_UP) accepting = false
        return true
    }
}
