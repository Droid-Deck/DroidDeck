package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.droiddeck.launcher.input.PalmGate.Pen.HOVER
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PalmFilterTest {
    data class Out(val action: Int, val ids: List<Int>, val downTime: Long, val time: Long, val history: Int, val flags: Int, val source: Int)

    private val logs = mutableListOf<String>()
    private val gate = PalmGate { logs += it }
    private val delivered = mutableListOf<Out>()
    private val instances = mutableListOf<MotionEvent>()
    private val filter = PalmFilter(gate) { e ->
        instances += e
        delivered += out(e)
        true
    }
    private val finger = MotionEvent.TOOL_TYPE_FINGER
    private val pen = MotionEvent.TOOL_TYPE_STYLUS
    private val screen = InputDevice.SOURCE_TOUCHSCREEN
    private val stylusScreen = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS
    private val tablet = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS
    private val sent = mutableListOf<MotionEvent>()
    private var streamDown = 0L

    @After fun tearDown() = sent.forEach { it.recycle() }

    @Test fun eventsPassUntouchedWithoutAPen() {
        val down = send(MotionEvent.ACTION_DOWN, 1_000, 0 to finger)
        val second = send(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 1_010, 0 to finger, 1 to finger)
        val up = send(pointerAction(MotionEvent.ACTION_POINTER_UP, 0), 1_020, 0 to finger, 1 to finger, flags = MotionEvent.FLAG_CANCELED)
        val mouse = send(MotionEvent.ACTION_DOWN, 1_030, 0 to MotionEvent.TOOL_TYPE_MOUSE, source = InputDevice.SOURCE_MOUSE)
        assertEquals(listOf(down, second, up, mouse), instances)
    }

    @Test fun aPenThatJoinsAnIgnoredFingerGetsItsOwnStream() {
        gate.onPen(1_000, HOVER, DEVICE)
        send(MotionEvent.ACTION_DOWN, 1_010, 0 to finger)
        assertTrue(delivered.isEmpty())
        send(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 1_020, 0 to finger, 1 to pen)
        send(MotionEvent.ACTION_MOVE, 1_030, 0 to finger, 1 to pen, history = 2)
        send(pointerAction(MotionEvent.ACTION_POINTER_UP, 0), 1_040, 0 to finger, 1 to pen)
        send(MotionEvent.ACTION_UP, 1_050, 1 to pen)
        assertEquals(listOf(
            Out(MotionEvent.ACTION_DOWN, listOf(1), 1_020, 1_020, 0, 0, stylusScreen),
            Out(MotionEvent.ACTION_MOVE, listOf(1), 1_020, 1_030, 2, 0, stylusScreen),
            Out(MotionEvent.ACTION_MOVE, listOf(1), 1_020, 1_040, 0, 0, stylusScreen),
            Out(MotionEvent.ACTION_UP, listOf(1), 1_020, 1_050, 0, 0, stylusScreen),
        ), delivered)
        assertEquals(listOf("palm: finger ignored"), logs)
    }

    @Test fun aPenThatJoinsAFingerStreamCancelsTheFingersFirst() {
        send(MotionEvent.ACTION_DOWN, 1_000, 0 to finger)
        send(MotionEvent.ACTION_MOVE, 1_010, 0 to finger)
        delivered.clear()
        send(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 1_020, 0 to finger, 1 to pen)
        send(MotionEvent.ACTION_MOVE, 1_030, 0 to finger, 1 to pen)
        assertEquals(listOf(
            Out(MotionEvent.ACTION_CANCEL, listOf(0), 1_000, 1_020, 0, MotionEvent.FLAG_CANCELED, screen),
            Out(MotionEvent.ACTION_DOWN, listOf(1), 1_020, 1_020, 0, 0, stylusScreen),
            Out(MotionEvent.ACTION_MOVE, listOf(1), 1_020, 1_030, 0, 0, stylusScreen),
        ), delivered)
        assertEquals(listOf("palm: pen near, cancelled 1 finger(s)"), logs)
    }

    @Test fun aHoveringPenCancelsTheFingersOfEveryWindow() {
        val other = mutableListOf<Out>()
        val bottom = PalmFilter(gate) { e -> other += out(e); true }
        send(MotionEvent.ACTION_DOWN, 1_000, 3 to finger)
        send(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 1_005, 3 to finger, 5 to finger)
        bottom.dispatch(event(MotionEvent.ACTION_DOWN, 1_010, 0 to finger))
        delivered.clear()
        other.clear()
        gate.onPen(1_100, HOVER, DEVICE)
        assertEquals(listOf(Out(MotionEvent.ACTION_CANCEL, listOf(3, 5), 1_000, 1_100, 0, MotionEvent.FLAG_CANCELED, screen)), delivered)
        assertEquals(listOf(Out(MotionEvent.ACTION_CANCEL, listOf(0), 1_010, 1_100, 0, MotionEvent.FLAG_CANCELED, screen)), other)
        assertEquals(listOf("palm: pen near, cancelled 3 finger(s)"), logs)
        send(MotionEvent.ACTION_MOVE, 5_000, 3 to finger, 5 to finger)
        send(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), 5_010, 3 to finger, 5 to finger)
        send(MotionEvent.ACTION_UP, 5_020, 3 to finger)
        assertEquals(1, delivered.size)
        send(MotionEvent.ACTION_DOWN, 6_000, 0 to finger)
        assertEquals(MotionEvent.ACTION_DOWN, delivered.last().action)
    }

    @Test fun anIgnoredStreamStaysIgnoredAfterThePenLeaves() {
        gate.onPen(1_000, HOVER, DEVICE)
        gate.leave(1_100)
        send(MotionEvent.ACTION_DOWN, 1_200, 0 to finger)
        send(MotionEvent.ACTION_MOVE, 5_000, 0 to finger)
        send(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 5_010, 0 to finger, 1 to finger)
        send(pointerAction(MotionEvent.ACTION_POINTER_UP, 0), 5_020, 0 to finger, 1 to finger)
        send(MotionEvent.ACTION_UP, 5_040, 1 to finger)
        assertTrue(delivered.isEmpty())
        val fresh = send(MotionEvent.ACTION_DOWN, 6_000, 0 to finger)
        assertEquals(listOf(fresh), instances)
        assertEquals(listOf("palm: finger ignored"), logs)
    }

    @Test fun androidsCancelReachesOnlyThePenAndStartsTheGrace() {
        gate.onPen(1_000, HOVER, DEVICE)
        send(MotionEvent.ACTION_DOWN, 1_010, 0 to pen)
        send(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 1_020, 0 to pen, 1 to finger)
        send(MotionEvent.ACTION_CANCEL, 1_030, 0 to pen, 1 to finger)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), delivered.map { it.action })
        assertEquals(listOf(0), delivered.last().ids)
        assertTrue(gate.armed(1_030 + PalmGate.GRACE_MS - 1))
        assertFalse(gate.armed(1_030 + PalmGate.GRACE_MS))
    }

    @Test fun aPenStreamPassesUntouchedAndItsLiftStartsTheGrace() {
        val down = send(MotionEvent.ACTION_DOWN, 1_000, 0 to pen)
        val up = send(MotionEvent.ACTION_UP, 1_100, 0 to pen)
        assertEquals(listOf(down, up), instances)
        assertTrue(gate.armed(1_100 + PalmGate.GRACE_MS - 1))
        assertFalse(gate.armed(1_100 + PalmGate.GRACE_MS))
    }

    @Test fun aDisabledGateNeverFilters() {
        gate.enabled = false
        gate.onPen(1_000, HOVER, DEVICE)
        val down = send(MotionEvent.ACTION_DOWN, 1_010, 0 to finger)
        val mixed = send(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 1_020, 0 to finger, 1 to pen)
        assertEquals(listOf(down, mixed), instances)
    }

    @Test fun aPlainStylusSourceArmsTheGateButAnExternalTabletDoesNot() {
        assertTrue(PalmFilter.armsGate(event(MotionEvent.ACTION_DOWN, 1_000, 0 to pen, source = InputDevice.SOURCE_STYLUS), 0))
        val down = send(MotionEvent.ACTION_DOWN, 1_000, 0 to pen, source = tablet)
        assertFalse(PalmFilter.armsGate(down, 0))
        val up = send(MotionEvent.ACTION_UP, 1_010, 0 to pen, source = tablet)
        assertFalse(gate.armed(1_020))
        assertEquals(listOf(down, up), instances)
    }

    @Config(sdk = [34])
    @Test fun aBuiltInPenThatReportsAMouseSourceArmsTheGate() {
        val input = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(InputManager::class.java)
        shadowOf(input).addInputDevice(InputDeviceBuilder.newBuilder().setId(21).setName("pen").setExternal(false).setSources(tablet).build())
        shadowOf(input).addInputDevice(InputDeviceBuilder.newBuilder().setId(22).setName("tablet").setExternal(true).setSources(tablet).build())
        assertTrue(PalmFilter.armsGate(event(MotionEvent.ACTION_DOWN, 1_000, 0 to pen, source = tablet, device = 21), 0))
        assertFalse(PalmFilter.armsGate(event(MotionEvent.ACTION_DOWN, 1_000, 0 to pen, source = tablet, device = 22), 0))
    }

    private fun out(e: MotionEvent) =
        Out(e.action, (0 until e.pointerCount).map { e.getPointerId(it) }, e.downTime, e.eventTime, e.historySize, e.flags, e.source)

    private fun pointerAction(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun send(action: Int, time: Long, vararg pointers: Pair<Int, Int>, flags: Int = 0, history: Int = 0,
        source: Int? = null): MotionEvent {
        val e = event(action, time, *pointers, flags = flags, history = history, source = source)
        assertTrue(filter.dispatch(e))
        return e
    }

    private fun event(action: Int, time: Long, vararg pointers: Pair<Int, Int>, flags: Int = 0, history: Int = 0,
        source: Int? = null, device: Int = DEVICE): MotionEvent {
        if (action == MotionEvent.ACTION_DOWN) streamDown = time
        val properties = Array(pointers.size) { MotionEvent.PointerProperties().apply { id = pointers[it].first; toolType = pointers[it].second } }
        fun coords(step: Int) = Array(pointers.size) { MotionEvent.PointerCoords().apply { x = 100f * it + step; y = 300f } }
        val from = source ?: if (pointers.any { it.second == pen }) stylusScreen else screen
        val first = time - history
        val e = MotionEvent.obtain(streamDown, first, action, pointers.size, properties, coords(0), 0, 0, 1f, 1f,
            device, 0, from, flags)
        for (step in 1..history) e.addBatch(first + step, coords(step), 0)
        sent += e
        return e
    }

    private companion object {
        const val DEVICE = 4
    }
}
