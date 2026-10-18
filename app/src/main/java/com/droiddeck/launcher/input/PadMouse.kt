package com.droiddeck.launcher.input

import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

class PadMouse(private val listener: Listener, private val clock: () -> Long = SystemClock::uptimeMillis) {
    interface Listener {
        fun active(): Boolean
        fun onScrollStart()
        fun onScroll(dx: Float, dy: Float)
        fun onKey(evdev: Int, pressed: Boolean)
        fun onKeyboard()
        fun onNavigate()
        fun scrollSpeed(): Float
    }

    private class Sticks {
        var leftX = 0f
        var leftY = 0f
        var rightX = 0f
        var rightY = 0f
        var leftTrigger = 0f
        var rightTrigger = 0f

        fun clear() {
            leftX = 0f
            leftY = 0f
            rightX = 0f
            rightY = 0f
            leftTrigger = 0f
            rightTrigger = 0f
        }
    }

    private val choreographer = Choreographer.getInstance()
    private val physical = HashMap<Int, Sticks>()
    private val touch = Sticks()
    private val total = Sticks()
    private val triggerAxes = HashMap<Int, Boolean>()
    private val wiredLayout = HashMap<Int, Boolean>()
    private val down = HashMap<Int, MutableSet<Int>>()
    private val sent = HashMap<Int, IntArray>()
    private val held = LinkedHashMap<Int, Int>()
    private var scrolling = false
    private var stickDirection = NONE
    private var stickRepeatAt = 0L
    private var frameAt = 0L
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (!listener.active()) {
                reset()
                return
            }
            val dt = if (frameAt == 0L) 1f / 60f else ((frameTimeNanos - frameAt) / 1e9f).coerceIn(0f, 0.05f)
            frameAt = frameTimeNanos
            step(dt)
            if (moving()) choreographer.postFrameCallback(this) else stop()
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.repeatCount == 0 && !hasTriggerAxes(event)) {
                val sticks = physical.getOrPut(event.deviceId) { Sticks() }
                val value = if (event.action == KeyEvent.ACTION_DOWN) 1f else 0f
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2) sticks.leftTrigger = value else sticks.rightTrigger = value
                wake()
            }
            return true
        }
        val button = BUTTONS[event.keyCode] ?: return false
        if (event.repeatCount > 0) return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> set(source(event.deviceId, SOURCE_KEYS), button, true)
            KeyEvent.ACTION_UP -> set(source(event.deviceId, SOURCE_KEYS), button, false)
        }
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return true
        val sticks = physical.getOrPut(event.deviceId) { Sticks() }
        val wired = wiredLayout.getOrPut(event.deviceId) { PadBridge.wiredXboxLayout(event) }
        sticks.leftX = event.getAxisValue(MotionEvent.AXIS_X)
        sticks.leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        sticks.rightX = PadBridge.rightX(event, wired)
        sticks.rightY = PadBridge.rightY(event, wired)
        if (hasTriggerAxes(event)) {
            sticks.leftTrigger = PadBridge.leftTrigger(event, wired)
            sticks.rightTrigger = PadBridge.rightTrigger(event, wired)
        }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hat = source(event.deviceId, SOURCE_HAT)
        set(hat, UP, hatY < -0.5f)
        set(hat, DOWN, hatY > 0.5f)
        set(hat, LEFT, hatX < -0.5f)
        set(hat, RIGHT, hatX > 0.5f)
        wake()
        return true
    }

    fun onTouchPad(state: PadState) {
        touch.leftX = state.leftX
        touch.leftY = state.leftY
        touch.rightX = state.rightX
        touch.rightY = state.rightY
        touch.leftTrigger = state.leftTrigger
        touch.rightTrigger = state.rightTrigger
        for (button in TOUCH_BUTTONS) {
            set(SOURCE_TOUCH, button, state.isDown(button))
        }
        set(SOURCE_TOUCH, UP, state.up)
        set(SOURCE_TOUCH, DOWN, state.down)
        set(SOURCE_TOUCH, LEFT, state.left)
        set(SOURCE_TOUCH, RIGHT, state.right)
        wake()
    }

    fun reset() {
        stop()
        physical.clear()
        triggerAxes.clear()
        wiredLayout.clear()
        touch.clear()
        down.clear()
        sent.clear()
        stickDirection = NONE
        scrolling = false
        held.keys.toList().reversed().forEach { code ->
            held.remove(code)
            send(code, false)
        }
    }

    internal fun step(dt: Float) {
        sum()
        val lx = total.leftX
        val ly = total.leftY
        val held = heldDirection()
        val direction = when {
            held != NONE -> held
            hypot(lx, ly) < STICK_NAVIGATE -> NONE
            abs(lx) > abs(ly) -> if (lx > 0) RIGHT else LEFT
            else -> if (ly > 0) DOWN else UP
        }
        val now = clock()
        if (direction != stickDirection) {
            stickDirection = direction
            if (direction != NONE) {
                tap(direction)
                stickRepeatAt = now + REPEAT_FIRST_MS
            }
        } else if (direction != NONE && now >= stickRepeatAt) {
            tap(direction)
            stickRepeatAt = now + REPEAT_MS
        }
        val gain = gain(total.rightX, total.rightY)
        val sx = total.rightX * gain
        val triggers = trigger(total.rightTrigger) - trigger(total.leftTrigger)
        val vertical = (total.rightY * gain + triggers).coerceIn(-1f, 1f)
        if (sx != 0f || vertical != 0f) {
            if (!scrolling) {
                scrolling = true
                listener.onScrollStart()
            }
            val speed = listener.scrollSpeed() * dt
            listener.onScroll(sx * speed, vertical * speed)
        } else if (scrolling) {
            scrolling = false
            press(KEY_SETTLE)
            release(KEY_SETTLE)
        }
    }

    private fun set(source: Int, button: Int, pressed: Boolean) {
        val sources = down.getOrPut(button) { HashSet() }
        val was = sources.isNotEmpty()
        if (pressed) sources.add(source) else sources.remove(source)
        val now = sources.isNotEmpty()
        if (was == now) return
        if (button in DIRECTIONS) {
            if (now) step(0f)
            wake()
            return
        }
        if (now) {
            if (button == PadState.Y) {
                listener.onKeyboard()
                return
            }
            val codes = codesFor(button)
            sent[button] = codes
            codes.forEach { press(it) }
        } else {
            sent.remove(button)?.reversed()?.forEach { release(it) }
        }
    }

    private fun hasTriggerAxes(event: android.view.InputEvent): Boolean = triggerAxes.getOrPut(event.deviceId) {
        val device = event.device ?: return@getOrPut true
        device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null || device.getMotionRange(MotionEvent.AXIS_BRAKE) != null ||
            (event is MotionEvent && wiredLayout.getOrPut(event.deviceId) { PadBridge.wiredXboxLayout(event) })
    }

    private fun heldDirection(): Int {
        for (direction in DIRECTIONS) if (down[direction]?.isNotEmpty() == true) return direction
        return NONE
    }

    private fun sum() {
        total.leftX = touch.leftX
        total.leftY = touch.leftY
        total.rightX = touch.rightX
        total.rightY = touch.rightY
        total.leftTrigger = touch.leftTrigger
        total.rightTrigger = touch.rightTrigger
        for (sticks in physical.values) {
            total.leftX += sticks.leftX
            total.leftY += sticks.leftY
            total.rightX += sticks.rightX
            total.rightY += sticks.rightY
            total.leftTrigger += sticks.leftTrigger
            total.rightTrigger += sticks.rightTrigger
        }
    }

    private fun tap(direction: Int) {
        listener.onNavigate()
        val codes = codesFor(direction)
        codes.forEach { press(it) }
        codes.reversed().forEach { release(it) }
    }

    private fun codesFor(button: Int): IntArray = when (button) {
        PadState.A -> intArrayOf(KEY_ENTER)
        PadState.X -> intArrayOf(KEY_MENU)
        PadState.B -> intArrayOf(KEY_ESC, KEY_BACK)
        PadState.START -> intArrayOf(KEY_ENTER)
        PadState.LB -> intArrayOf(KEY_LEFTCTRL, KEY_PAGEUP)
        PadState.RB -> intArrayOf(KEY_LEFTCTRL, KEY_PAGEDOWN)
        UP -> intArrayOf(KEY_UP)
        DOWN -> intArrayOf(KEY_DOWN)
        LEFT -> intArrayOf(KEY_LEFT)
        RIGHT -> intArrayOf(KEY_RIGHT)
        else -> IntArray(0)
    }

    private fun wake() {
        if (moving() && !running) {
            running = true
            frameAt = 0L
            choreographer.postFrameCallback(frame)
        }
    }

    private fun moving(): Boolean {
        if (heldDirection() != NONE || stickDirection != NONE || scrolling) return true
        sum()
        return hypot(total.leftX, total.leftY) >= STICK_NAVIGATE || gain(total.rightX, total.rightY) != 0f ||
            trigger(total.leftTrigger) != 0f || trigger(total.rightTrigger) != 0f
    }

    private fun stop() {
        running = false
        frameAt = 0L
        choreographer.removeFrameCallback(frame)
    }

    private fun press(code: Int) {
        val count = held[code] ?: 0
        held[code] = count + 1
        if (count == 0) send(code, true)
    }

    private fun release(code: Int) {
        val count = held[code] ?: return
        if (count > 1) {
            held[code] = count - 1
            return
        }
        held.remove(code)
        send(code, false)
    }

    private fun send(code: Int, pressed: Boolean) {
        listener.onKey(code, pressed)
    }

    companion object {
        private const val DEAD_ZONE = 0.15f
        private const val STICK_NAVIGATE = 0.5f
        private const val REPEAT_FIRST_MS = 300L
        private const val REPEAT_MS = 120L
        private const val SOURCE_KEYS = 0
        private const val SOURCE_HAT = 1
        private const val SOURCE_TOUCH = Int.MIN_VALUE
        private const val NONE = -1
        private const val UP = 100
        private const val DOWN = 101
        private const val LEFT = 102
        private const val RIGHT = 103
        private val DIRECTIONS = intArrayOf(UP, DOWN, LEFT, RIGHT)
        private val TOUCH_BUTTONS = intArrayOf(PadState.A, PadState.B, PadState.X, PadState.Y, PadState.LB, PadState.RB, PadState.START)
        private const val KEY_ESC = 1
        private const val KEY_ENTER = 28
        private const val KEY_UP = 103
        private const val KEY_LEFT = 105
        private const val KEY_RIGHT = 106
        private const val KEY_DOWN = 108
        private const val KEY_BACK = 158
        private const val KEY_LEFTCTRL = 29
        private const val KEY_PAGEUP = 104
        private const val KEY_PAGEDOWN = 109
        private const val KEY_MENU = 127
        private const val KEY_SETTLE = 183

        private val BUTTONS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to PadState.A,
            KeyEvent.KEYCODE_BUTTON_B to PadState.B,
            KeyEvent.KEYCODE_BUTTON_X to PadState.X,
            KeyEvent.KEYCODE_BUTTON_Y to PadState.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to PadState.LB,
            KeyEvent.KEYCODE_BUTTON_R1 to PadState.RB,
            KeyEvent.KEYCODE_BUTTON_START to PadState.START,
            KeyEvent.KEYCODE_DPAD_UP to UP,
            KeyEvent.KEYCODE_DPAD_DOWN to DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to RIGHT,
        )

        internal fun curve(x: Float, y: Float): Pair<Float, Float> {
            val gain = gain(x, y)
            return x * gain to y * gain
        }

        private fun gain(x: Float, y: Float): Float {
            val length = hypot(x, y)
            if (length <= DEAD_ZONE) return 0f
            val scaled = (length.coerceAtMost(1f) - DEAD_ZONE) / (1f - DEAD_ZONE)
            return scaled * scaled / length
        }

        private fun source(deviceId: Int, kind: Int): Int = deviceId * 2 + kind



        private fun trigger(value: Float): Float =
            if (value <= DEAD_ZONE) 0f else (value.coerceAtMost(1f) - DEAD_ZONE) / (1f - DEAD_ZONE)
    }
}
