package com.droiddeck.launcher.input

import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.droiddeck.launcher.session.SessionPrefs

class PenStream(private val sink: Sink) {
    fun interface Sink {
        fun sample(flags: Int, x: Int, y: Int)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val exitLater = Runnable { if (exitPending) leave() }
    private val hoverWatchdog = Runnable { checkHover() }
    private var watching = false
    private var contact = false
    private var barrel = 0
    private var keyDown = false
    private var keyDevice = Int.MIN_VALUE
    private var keyCode = 0
    private var exitPending = false
    private var exitTime = 0L
    private var deviceId = Int.MIN_VALUE
    private var x = 0
    private var y = 0
    private var sampleTime = 0L

    var sideButton = SessionPrefs.PEN_BUTTON_RIGHT

    var inProximity = false
        private set

    fun onMotion(event: MotionEvent, pointer: Int, area: RectF, width: Int, height: Int): Boolean {
        val action = event.actionMasked
        val time = event.eventTime
        val own = isPenAction(event, pointer)
        expire(time)
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                continueAt(time)
                contact = false
                enter(event)
                samples(event, pointer, area, width, height)
            }
            MotionEvent.ACTION_HOVER_EXIT -> if (inProximity && !exitPending && !contact) pendExit(time)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                continueAt(time)
                if (!inProximity) return true
                barrel = event.buttonState
                samples(event, pointer, area, width, height)
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (own) {
                continueAt(time)
                enter(event)
                contact = true
                samples(event, pointer, area, width, height)
            } else if (contact) samples(event, pointer, area, width, height)
            MotionEvent.ACTION_MOVE -> if (contact) {
                barrel = event.buttonState
                samples(event, pointer, area, width, height)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (!own) {
                if (contact) samples(event, pointer, area, width, height)
            } else if (contact) {
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) cancel()
                else {
                    barrel = event.buttonState
                    samples(event, pointer, area, width, height, lift = true)
                    pendExit(time)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
            else -> return false
        }
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        expire(event.eventTime)
        val down = event.action == KeyEvent.ACTION_DOWN
        val repeat = event.repeatCount > 0
        if (exitPending && !repeat) leave()
        if (!inProximity || exitPending) {
            if (!repeat && event.deviceId == keyDevice && event.keyCode == keyCode) keyDown = down
            return true
        }
        if (down == keyDown) return true
        val before = state()
        keyDown = down
        if (down) {
            keyDevice = event.deviceId
            keyCode = event.keyCode
        }
        if (state() == before) return true
        emit(state())
        return true
    }

    fun cancel(releaseKey: Boolean = false) {
        if (releaseKey) keyDown = false
        leave()
    }

    fun holdsKey(event: KeyEvent): Boolean = keyDown && event.deviceId == keyDevice && event.keyCode == keyCode

    fun deviceRemoved(deviceId: Int) {
        if (deviceId == this.deviceId) cancel()
        if (!keyDown || deviceId != keyDevice) return
        val before = state()
        keyDown = false
        if (inProximity && state() != before) emit(state())
    }

    private fun enter(event: MotionEvent) {
        barrel = event.buttonState
        if (inProximity) return
        inProximity = true
        deviceId = event.deviceId
    }

    private fun continueAt(time: Long) {
        if (!exitPending) return
        if (time - exitTime > EXIT_GRACE_MS) { leave(); return }
        exitPending = false
        handler.removeCallbacks(exitLater)
    }

    private fun pendExit(time: Long) {
        exitPending = true
        exitTime = time
        handler.removeCallbacks(exitLater)
        handler.postDelayed(exitLater, EXIT_GRACE_MS)
    }

    private fun expire(time: Long) {
        if (inProximity && !contact && time - sampleTime >= HOVER_TIMEOUT_MS) leave()
    }

    private fun watchHover(delay: Long = HOVER_TIMEOUT_MS) {
        if (watching || contact) return
        watching = true
        handler.postDelayed(hoverWatchdog, delay)
    }

    private fun checkHover() {
        watching = false
        if (!inProximity || contact || exitPending) return
        val silent = SystemClock.uptimeMillis() - sampleTime
        if (silent >= HOVER_TIMEOUT_MS) leave() else watchHover(HOVER_TIMEOUT_MS - silent)
    }

    private fun leave() {
        if (!inProximity) return
        emit(0)
        handler.removeCallbacks(exitLater)
        handler.removeCallbacks(hoverWatchdog)
        watching = false
        inProximity = false
        contact = false
        exitPending = false
        barrel = 0
    }

    private fun samples(event: MotionEvent, pointer: Int, area: RectF, width: Int, height: Int, lift: Boolean = false) {
        for (h in 0 until event.historySize) sample(event, pointer, h, area, width, height)
        if (lift) contact = false
        sample(event, pointer, -1, area, width, height)
    }

    private fun sample(event: MotionEvent, pointer: Int, h: Int, area: RectF, width: Int, height: Int) {
        x = InputSpace.fixedX(event.axis(MotionEvent.AXIS_X, pointer, h).coerceIn(area.left, area.right), width)
        y = InputSpace.fixedY(event.axis(MotionEvent.AXIS_Y, pointer, h).coerceIn(area.top, area.bottom), height)
        sampleTime = if (h < 0) event.eventTime else event.getHistoricalEventTime(h)
        emit(state())
        watchHover()
    }

    private fun emit(flags: Int) = sink.sample(flags, x, y)

    private fun state(): Int =
        PEN_IN or (if (contact) PEN_TIP else 0) or buttonFlags(keyDown || barrel and MotionEvent.BUTTON_STYLUS_PRIMARY != 0,
            barrel and MotionEvent.BUTTON_STYLUS_SECONDARY != 0, sideButton)

    private fun MotionEvent.axis(axis: Int, pointer: Int, h: Int): Float =
        if (h < 0) getAxisValue(axis, pointer) else getHistoricalAxisValue(axis, pointer, h)

    companion object {
        const val PEN_IN = 1 shl 0
        const val PEN_TIP = 1 shl 1
        const val PEN_RIGHT = 1 shl 2
        const val PEN_MIDDLE = 1 shl 3
        const val HOVER_TIMEOUT_MS = 2_000L
        const val EXIT_GRACE_MS = 50L

        private val PEN_NAME = Regex("\\b(pen|stylus)", RegexOption.IGNORE_CASE)

        fun isPen(event: MotionEvent, index: Int): Boolean = when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
            MotionEvent.TOOL_TYPE_UNKNOWN -> event.isFromSource(InputDevice.SOURCE_STYLUS)
            else -> false
        }

        fun penIndex(event: MotionEvent): Int {
            for (index in 0 until event.pointerCount) if (isPen(event, index)) return index
            return -1
        }

        fun isPenAction(event: MotionEvent, index: Int): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> event.actionIndex == index
            else -> false
        }

        fun isPenKey(event: KeyEvent): Boolean {
            val device = event.device ?: return false
            return isPenKeyboard(device.name, device.sources, Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || device.isExternal,
                device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        }

        fun isPenKeyboard(name: String?, sources: Int, external: Boolean, alphabetic: Boolean): Boolean =
            external && !alphabetic && sources == InputDevice.SOURCE_KEYBOARD && name != null && PEN_NAME.containsMatchIn(name)

        fun buttonFlags(primary: Boolean, secondary: Boolean, sideButton: String): Int {
            val first = when (sideButton) {
                SessionPrefs.PEN_BUTTON_RIGHT -> PEN_RIGHT
                SessionPrefs.PEN_BUTTON_MIDDLE -> PEN_MIDDLE
                else -> 0
            }
            val second = if (sideButton == SessionPrefs.PEN_BUTTON_RIGHT) PEN_MIDDLE else PEN_RIGHT
            return (if (primary) first else 0) or (if (secondary) second else 0)
        }
    }
}
