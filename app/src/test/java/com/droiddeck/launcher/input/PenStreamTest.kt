package com.droiddeck.launcher.input

import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.droiddeck.launcher.input.PenStream.Companion.PEN_IN
import com.droiddeck.launcher.input.PenStream.Companion.PEN_MIDDLE
import com.droiddeck.launcher.input.PenStream.Companion.PEN_RIGHT
import com.droiddeck.launcher.input.PenStream.Companion.PEN_TIP
import com.droiddeck.launcher.session.SessionPrefs
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PenStreamTest {
    data class Record(val flags: Int, val x: Int, val y: Int)
    data class Sample(val time: Long, val x: Float, val y: Float = 300f)

    private val records = mutableListOf<Record>()
    private val stream = PenStream { flags, x, y -> records += Record(flags, x, y) }
    private val area = RectF(0f, 0f, 1000f, 600f)

    @Test fun hoverEntersMovesAndLeavesProximity() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 200f))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(30, 250f))
        assertEquals(listOf(PEN_IN, PEN_IN), flags())
        idle()
        assertEquals(listOf(PEN_IN, PEN_IN), flags())
        grace()
        assertEquals(listOf(PEN_IN, PEN_IN, 0), flags())
        assertEquals(listOf(100f, 200f).map(::x), records.take(2).map { it.x })
        assertFalse(stream.inProximity)
    }

    @Test fun hoverExitThenDownWithTheSameTimeStaysInProximity() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        send(MotionEvent.ACTION_DOWN, Sample(20, 100f))
        grace()
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_TIP), flags())
    }

    @Test fun hoverExitThenADownAfterTheGraceLeavesProximityFirst() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        send(MotionEvent.ACTION_DOWN, Sample(21 + PenStream.EXIT_GRACE_MS, 100f))
        assertEquals(listOf(PEN_IN, 0, PEN_IN or PEN_TIP), flags())
    }

    @Test fun aPenFlickeringAtTheEdgeOfHoverRangeStaysInProximity() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        for (i in 0 until 5) {
            send(MotionEvent.ACTION_HOVER_EXIT, Sample(20L + i * 22, 100f))
            send(MotionEvent.ACTION_HOVER_ENTER, Sample(34L + i * 22, 101f))
        }
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(130, 101f))
        send(MotionEvent.ACTION_DOWN, Sample(130 + PenStream.EXIT_GRACE_MS, 101f))
        grace()
        assertEquals(List(6) { PEN_IN } + (PEN_IN or PEN_TIP), flags())
        assertTrue(stream.inProximity)
    }

    @Test fun liftingIntoHoverKeepsProximityAndLiftingAwayLeavesIt() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f))
        send(MotionEvent.ACTION_UP, Sample(20, 110f))
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(20, 110f))
        grace()
        assertEquals(listOf(PEN_IN or PEN_TIP, PEN_IN, PEN_IN), flags())
        records.clear()
        send(MotionEvent.ACTION_DOWN, Sample(30, 100f))
        send(MotionEvent.ACTION_UP, Sample(40, 100f))
        grace()
        assertEquals(listOf(PEN_IN or PEN_TIP, PEN_IN, 0), flags())
    }

    @Test fun aPenKeyDuringTheExitGraceEndsProximityFirst() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 25)))
        grace()
        assertEquals(listOf(PEN_IN, 0), flags())
    }

    @Test fun historicalSamplesGoOutOldestFirst() {
        send(MotionEvent.ACTION_DOWN, Sample(100, 10f))
        send(MotionEvent.ACTION_MOVE, Sample(103, 20f), Sample(106, 30f), Sample(109, 40f))
        assertEquals(listOf(10f, 20f, 30f, 40f).map(::x), records.map { it.x })
        assertTrue(records.all { it.flags == PEN_IN or PEN_TIP })
        assertEquals(y(300f), records.last().y)
    }

    @Test fun cancelEndsTheStrokeAndIgnoresTheRest() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f))
        send(MotionEvent.ACTION_MOVE, Sample(20, 150f))
        send(MotionEvent.ACTION_CANCEL, Sample(30, 160f))
        send(MotionEvent.ACTION_MOVE, Sample(40, 170f))
        send(MotionEvent.ACTION_UP, Sample(50, 170f))
        assertEquals(listOf(PEN_IN or PEN_TIP, PEN_IN or PEN_TIP, 0), flags())
        assertFalse(stream.inProximity)
    }

    @Test fun anUpThatAndroidCanceledIsACancelNotALift() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f))
        send(MotionEvent.ACTION_UP, Sample(20, 100f), flags = MotionEvent.FLAG_CANCELED)
        assertEquals(listOf(PEN_IN or PEN_TIP, 0), flags())
    }

    @Test fun theSideButtonSettingMapsThePrimaryButtonAndTheSecondaryGetsTheOtherClick() {
        val cases = mapOf(SessionPrefs.PEN_BUTTON_RIGHT to (PEN_RIGHT to PEN_MIDDLE),
            SessionPrefs.PEN_BUTTON_MIDDLE to (PEN_MIDDLE to PEN_RIGHT), SessionPrefs.PEN_BUTTON_OFF to (0 to PEN_RIGHT))
        for ((setting, expected) in cases) {
            assertEquals(setting, 0, PenStream.buttonFlags(primary = false, secondary = false, sideButton = setting))
            assertEquals(setting, expected.first, PenStream.buttonFlags(primary = true, secondary = false, sideButton = setting))
            assertEquals(setting, expected.second, PenStream.buttonFlags(primary = false, secondary = true, sideButton = setting))
            assertEquals(setting, expected.first or expected.second, PenStream.buttonFlags(primary = true, secondary = true, sideButton = setting))
        }
        stream.sideButton = SessionPrefs.PEN_BUTTON_MIDDLE
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 100f), buttons = MotionEvent.BUTTON_STYLUS_SECONDARY)
        assertEquals(listOf(PEN_IN or PEN_MIDDLE, PEN_IN or PEN_RIGHT), flags())
    }

    @Test fun barrelButtonsFollowTheButtonState() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 100f), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_BUTTON_PRESS, Sample(21, 100f), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)
        send(MotionEvent.ACTION_DOWN, Sample(30, 100f), buttons = MotionEvent.BUTTON_STYLUS_SECONDARY)
        send(MotionEvent.ACTION_MOVE, Sample(40, 100f))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_RIGHT, PEN_IN or PEN_RIGHT or PEN_MIDDLE, PEN_IN or PEN_TIP or PEN_MIDDLE,
            PEN_IN or PEN_TIP), flags())
    }

    @Test fun aBluetoothPenKeyIsTheSideButtonWhileThePenIsNear() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 15)))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 16, repeat = 1)))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 130f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_UP, 25)))
        stream.cancel()
        stream.sideButton = SessionPrefs.PEN_BUTTON_MIDDLE
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(30, 100f))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 35))
        stream.onKey(key(KeyEvent.ACTION_UP, 36))
        stream.cancel()
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_RIGHT, PEN_IN or PEN_RIGHT, PEN_IN, 0,
            PEN_IN, PEN_IN or PEN_MIDDLE, PEN_IN, 0), flags())
        assertEquals(x(130f), records[2].x)
        records.clear()
        stream.sideButton = SessionPrefs.PEN_BUTTON_OFF
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(40, 100f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 45)))
        assertTrue(stream.holdsKey(key(KeyEvent.ACTION_UP, 46)))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_UP, 46)))
        assertEquals(listOf(PEN_IN), flags())
    }

    @Test fun aBluetoothPenKeyIsConsumedWhenThePenIsAway() {
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 10)))
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(20, 100f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_UP, 30)))
        assertEquals(listOf(PEN_IN), flags())
    }

    @Test fun aKeyHeldWhileThePenIsAwayIsReleasedAndPressedAgainOnReturn() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 15))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        grace()
        stream.onKey(key(KeyEvent.ACTION_DOWN, 500, repeat = 1))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 550, repeat = 2))
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(600, 100f))
        send(MotionEvent.ACTION_DOWN, Sample(610, 100f))
        send(MotionEvent.ACTION_UP, Sample(620, 100f))
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(620, 100f))
        stream.onKey(key(KeyEvent.ACTION_UP, 630))
        val right = PEN_IN or PEN_RIGHT
        assertEquals(listOf(PEN_IN, right, 0, right, right or PEN_TIP, right, right, PEN_IN), flags())
    }

    @Test fun aKeyReleasedWhileThePenIsAwayStaysReleased() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 15))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        grace()
        assertTrue(stream.holdsKey(key(KeyEvent.ACTION_UP, 40)))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_UP, 40)))
        assertFalse(stream.holdsKey(key(KeyEvent.ACTION_UP, 41)))
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(50, 100f))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_RIGHT, 0, PEN_IN), flags())
    }

    @Test fun onlyALifecycleCancelForgetsAHeldKeyAndARepeatBringsItBack() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 15))
        stream.cancel()
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(20, 100f))
        stream.cancel(releaseKey = true)
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(30, 100f))
        assertFalse(stream.holdsKey(key(KeyEvent.ACTION_UP, 35)))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 40, repeat = 1))
        stream.onKey(key(KeyEvent.ACTION_UP, 50))
        val right = PEN_IN or PEN_RIGHT
        assertEquals(listOf(PEN_IN, right, 0, right, 0, PEN_IN, right, PEN_IN), flags())
    }

    @Test fun removingTheBluetoothPenReleasesItsKeyAndForgetsIt() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 15))
        stream.deviceRemoved(10)
        assertFalse(stream.holdsKey(key(KeyEvent.ACTION_UP, 18)))
        assertTrue(stream.inProximity)
        stream.onKey(key(KeyEvent.ACTION_DOWN, 20))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(25, 100f))
        grace()
        stream.deviceRemoved(10)
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(30, 100f))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_RIGHT, PEN_IN, PEN_IN or PEN_RIGHT, 0, PEN_IN), flags())
    }

    @Test fun aHoverThatStopsReportingCountsAsGone() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(1_000, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(2_000, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(4_500, 110f))
        assertEquals(listOf(PEN_IN, PEN_IN, 0, PEN_IN), flags())
        records.clear()
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 7_000)))
        assertEquals(listOf(0), flags())
    }

    @Test fun aPenKeyDoesNotKeepASilentHoverNear() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(1_000, 100f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 2_500)))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_UP, 3_000)))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_RIGHT, 0), flags())
    }

    @Test fun aSilentHoverIsSentOutByTheWatchdogWithoutAnotherEvent() {
        val start = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(start, 100f), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(start + 1_500, 110f), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.HOVER_TIMEOUT_MS - 1))
        assertTrue(stream.inProximity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(stream.inProximity)
        assertEquals(listOf(PEN_IN or PEN_RIGHT, PEN_IN or PEN_RIGHT, 0), flags())
    }

    @Test fun theWatchdogNeverEndsAContact() {
        val start = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(start, 100f))
        send(MotionEvent.ACTION_DOWN, Sample(start + 10, 100f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(stream.inProximity)
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_TIP), flags())
    }

    @Test fun aStrokeIsNeverTimedOut() {
        send(MotionEvent.ACTION_DOWN, Sample(1_000, 100f))
        send(MotionEvent.ACTION_MOVE, Sample(9_000, 110f))
        assertEquals(listOf(PEN_IN or PEN_TIP, PEN_IN or PEN_TIP), flags())
    }

    @Test fun lifecycleCancelEndsEverythingOnce() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f))
        stream.cancel()
        stream.cancel()
        assertEquals(listOf(PEN_IN or PEN_TIP, 0), flags())
    }

    @Test fun removingThePenDeviceEndsItsStream() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f), device = 6)
        stream.deviceRemoved(8)
        assertTrue(stream.inProximity)
        stream.deviceRemoved(6)
        assertEquals(listOf(PEN_IN, 0), flags())
    }

    @Test fun aPenNextToAFingerKeepsItsOwnStroke() {
        val finger = MotionEvent.TOOL_TYPE_FINGER
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        mixed(MotionEvent.ACTION_DOWN, 10, finger to 50f)
        assertTrue(records.isEmpty())
        mixed(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20, finger to 50f, stylus to 400f)
        mixed(MotionEvent.ACTION_MOVE, 30, finger to 60f, stylus to 410f)
        mixed(MotionEvent.ACTION_POINTER_UP, 40, finger to 60f, stylus to 420f)
        mixed(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, finger to 60f, stylus to 430f)
        assertEquals(listOf(PEN_IN or PEN_TIP, PEN_IN or PEN_TIP, PEN_IN or PEN_TIP, PEN_IN), flags())
        assertEquals(listOf(400f, 410f, 420f, 430f).map(::x), records.map { it.x })
    }

    @Test fun positionsAreClampedToThePicture() {
        val event = event(MotionEvent.ACTION_HOVER_ENTER, listOf(Sample(10, 40f, y = 580f)), MotionEvent.TOOL_TYPE_STYLUS)
        try { stream.onMotion(event, 0, RectF(100f, 50f, 900f, 550f), 1000, 600) } finally { event.recycle() }
        assertEquals(Record(PEN_IN, x(100f), y(550f)), records.single())
    }

    @Test fun pensAreFoundByToolTypeFirstAndSourceSecond() {
        fun index(tool: Int, source: Int): Int {
            val event = event(MotionEvent.ACTION_DOWN, listOf(Sample(0, 1f)), tool, source = source)
            return try { PenStream.penIndex(event) } finally { event.recycle() }
        }
        assertEquals(0, index(MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_MOUSE))
        assertEquals(0, index(MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_TOUCHSCREEN))
        assertEquals(0, index(MotionEvent.TOOL_TYPE_UNKNOWN, InputDevice.SOURCE_STYLUS))
        assertEquals(-1, index(MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_STYLUS))
        assertEquals(-1, index(MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS))
        assertEquals(-1, index(MotionEvent.TOOL_TYPE_UNKNOWN, InputDevice.SOURCE_TOUCHSCREEN))
    }

    @Test fun penButtonKeyboardsAreRecognisedByNameAndShape() {
        val keyboard = InputDevice.SOURCE_KEYBOARD
        assertTrue(PenStream.isPenKeyboard("OPPO AI Pen", keyboard, external = true, alphabetic = false))
        assertTrue(PenStream.isPenKeyboard("Xiaomi Smart STYLUS", keyboard, external = true, alphabetic = false))
        assertTrue(PenStream.isPenKeyboard("HUAWEI M-Pencil", keyboard, external = true, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard("OPPO AI Pen", keyboard, external = false, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard("OPPO AI Pen", keyboard or InputDevice.SOURCE_MOUSE, external = true, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard("Pen Keyboard K380", keyboard, external = true, alphabetic = true))
        assertFalse(PenStream.isPenKeyboard("Open Remote", keyboard, external = true, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard("8BitDo Remote", keyboard, external = true, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard(null, keyboard, external = true, alphabetic = false))
    }

    private fun flags() = records.map { it.flags }
    private fun x(position: Float) = InputSpace.fixedX(position, 1000)
    private fun y(position: Float) = InputSpace.fixedY(position, 600)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun grace() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))

    private fun key(action: Int, time: Long, repeat: Int = 0) =
        KeyEvent(time, time, action, KeyEvent.KEYCODE_UNKNOWN, repeat, 0, 10, 191, 0, InputDevice.SOURCE_KEYBOARD)

    private fun send(action: Int, vararg samples: Sample, buttons: Int = 0, flags: Int = 0, device: Int = 6) {
        val event = event(action, samples.toList(), MotionEvent.TOOL_TYPE_STYLUS, buttons, flags, device = device)
        try { stream.onMotion(event, 0, area, 1000, 600) } finally { event.recycle() }
    }

    private fun mixed(action: Int, time: Long, vararg pointers: Pair<Int, Float>) {
        val properties = Array(pointers.size) { MotionEvent.PointerProperties().apply { id = it; toolType = pointers[it].first } }
        val coords = Array(pointers.size) { MotionEvent.PointerCoords().apply { x = pointers[it].second; y = 300f; pressure = 0.5f } }
        val event = MotionEvent.obtain(0, time, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 6, 0,
            InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS, 0)
        try {
            val pen = PenStream.penIndex(event)
            if (pen >= 0) stream.onMotion(event, pen, area, 1000, 600)
        } finally { event.recycle() }
    }

    private fun event(action: Int, samples: List<Sample>, tool: Int, buttons: Int = 0, flags: Int = 0,
        source: Int = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS, device: Int = 6): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        fun coords(sample: Sample) = arrayOf(MotionEvent.PointerCoords().apply {
            x = sample.x
            y = sample.y
        })
        val first = samples.first()
        val event = MotionEvent.obtain(0, first.time, action, 1, properties, coords(first), 0, buttons, 1f, 1f, device, 0, source, flags)
        for (sample in samples.drop(1)) event.addBatch(sample.time, coords(sample), 0)
        return event
    }
}
