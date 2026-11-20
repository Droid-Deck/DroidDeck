package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.HeldInput
import com.droiddeck.launcher.input.InputSpace
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.ABS_X
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.ABS_Y
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.BTN_LEFT
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.BTN_TOUCH
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.EV_ABS
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.EV_KEY
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.EV_REL
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.EV_SYN
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.REL_HWHEEL
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.REL_WHEEL
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.REL_WHEEL_HI_RES
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.REL_X
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.REL_Y
import com.droiddeck.launcher.session.DesktopUinputDevice.Companion.SYN_REPORT
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UinputDesktopComponentTest {
    private class Recorder : DesktopUinputDevice.Sink {
        val calls = ArrayList<String>()
        override fun key(code: Int, down: Boolean) { calls.add("key $code $down") }
        override fun button(code: Int, down: Boolean) { calls.add("button $code $down") }
        override fun motion(dx: Int, dy: Int) { calls.add("motion $dx $dy") }
        override fun position(x: Int, y: Int) { calls.add("position $x $y") }
        override fun wheel(vertical120: Int, horizontal120: Int) { calls.add("wheel $vertical120 $horizontal120") }
    }

    private fun stream(xMin: Int, xMax: Int, yMin: Int, yMax: Int, vararg events: IntArray, magic: Int = UinputDesktopComponent.MAGIC): ByteArrayInputStream {
        val b = ByteBuffer.allocate(UinputDesktopComponent.HEADER_BYTES + events.size * UinputDesktopComponent.EVENT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(magic).putInt(xMin).putInt(xMax).putInt(yMin).putInt(yMax)
        events.forEach { b.putShort(it[0].toShort()).putShort(it[1].toShort()).putInt(it[2]) }
        return ByteArrayInputStream(b.array())
    }

    private fun read(stream: ByteArrayInputStream, r: Recorder) =
        UinputDesktopComponent.read(stream, r, HeldInput(r::key), HeldInput(r::button), 1)

    private fun ev(type: Int, code: Int, value: Int) = intArrayOf(type, code, value)
    private val syn = ev(EV_SYN, SYN_REPORT, 0)

    @Test fun keyboardKeysReachTheCompositorAndRepeatsAreLeftToIt() {
        val r = Recorder()
        read(stream(0, 0, 0, 0, ev(EV_KEY, 30, 1), syn, ev(EV_KEY, 30, 2), syn, ev(EV_KEY, 30, 0), syn), r)
        assertEquals(listOf("key 30 true", "key 30 false"), r.calls)
    }

    @Test fun relativeMouseMovesBeforeItClicksAndScrollsInWaylandDirection() {
        val r = Recorder()
        read(stream(0, 0, 0, 0,
            ev(EV_REL, REL_X, 5), ev(EV_REL, REL_Y, -3), ev(EV_REL, REL_X, 2), ev(EV_KEY, BTN_LEFT, 1), syn,
            ev(EV_KEY, BTN_LEFT, 0), syn,
            ev(EV_REL, REL_WHEEL, 1), ev(EV_REL, REL_HWHEEL, -1), syn), r)
        assertEquals(listOf("motion 7 -3", "button $BTN_LEFT true", "button $BTN_LEFT false", "wheel -120 -120"), r.calls)
    }

    @Test fun highResolutionWheelIsNotCountedTwice() {
        val r = Recorder()
        read(stream(0, 0, 0, 0,
            ev(EV_REL, REL_WHEEL_HI_RES, 60), syn,
            ev(EV_REL, REL_WHEEL_HI_RES, 60), ev(EV_REL, REL_WHEEL, 1), syn), r)
        assertEquals(listOf("wheel -60 0", "wheel -60 0"), r.calls)
    }

    @Test fun absoluteMouseScalesItsRangeToTheInputSpace() {
        val r = Recorder()
        read(stream(0, 19200, 0, 12000,
            ev(EV_ABS, ABS_X, 0), ev(EV_ABS, ABS_Y, 0), syn,
            ev(EV_ABS, ABS_X, 19200), ev(EV_ABS, ABS_Y, 12000), ev(EV_KEY, BTN_TOUCH, 1), syn,
            ev(EV_ABS, ABS_X, 40000), syn), r)
        assertEquals(listOf("position 0 0", "position ${InputSpace.WIDTH - 1} ${InputSpace.HEIGHT - 1}",
            "button $BTN_LEFT true", "position ${InputSpace.WIDTH - 1} ${InputSpace.HEIGHT - 1}",
            "button $BTN_LEFT false"), r.calls)
    }

    @Test fun absoluteAxesAndTouchAreIgnoredWithoutAPointerRange() {
        val r = Recorder()
        read(stream(0, 0, 0, 0, ev(EV_ABS, ABS_X, 100), ev(EV_ABS, ABS_Y, 100), ev(EV_KEY, BTN_TOUCH, 1), syn), r)
        assertEquals(emptyList<String>(), r.calls)
    }

    @Test fun gamepadButtonsAreNotTurnedIntoKeys() {
        val r = Recorder()
        read(stream(0, 0, 0, 0, ev(EV_KEY, 0x130, 1), ev(EV_KEY, 0x13c, 1), syn), r)
        assertEquals(emptyList<String>(), r.calls)
    }

    @Test fun aClosedDeviceReleasesWhatItHeld() {
        val r = Recorder()
        read(stream(0, 0, 0, 0, ev(EV_KEY, 42, 1), ev(EV_KEY, 0x111, 1), syn), r)
        assertEquals(listOf("key 42 true", "button 273 true", "key 42 false", "button 273 false"), r.calls)
    }

    @Test fun aKeyOrButtonHeldByTwoDevicesIsReleasedOnlyWhenBothLetGo() {
        val r = Recorder()
        val keys = HeldInput(r::key)
        val buttons = HeldInput(r::button)
        val keyboard = DesktopUinputDevice(null, null, r, keys, buttons, 1)
        val mouse = DesktopUinputDevice(null, null, r, keys, buttons, 2)
        keyboard.event(EV_KEY, 42, 1)
        mouse.event(EV_KEY, 42, 1)
        keyboard.event(EV_KEY, BTN_LEFT, 1)
        mouse.event(EV_KEY, BTN_LEFT, 1)
        keyboard.event(EV_KEY, 42, 0)
        keyboard.release()
        assertEquals(listOf("key 42 true", "button $BTN_LEFT true"), r.calls)
        mouse.release()
        assertEquals(listOf("key 42 true", "button $BTN_LEFT true", "key 42 false", "button $BTN_LEFT false"), r.calls)
    }

    @Test fun sharedHoldsPassASingleDeviceThroughUnchanged() {
        val direct = Recorder()
        val shared = Recorder()
        val events = arrayOf(ev(EV_KEY, 30, 1), syn, ev(EV_KEY, 30, 0), ev(EV_REL, REL_X, 4), ev(EV_KEY, BTN_LEFT, 1), syn,
            ev(EV_KEY, BTN_LEFT, 0), ev(EV_REL, REL_WHEEL, 1), syn, ev(EV_KEY, 42, 1), syn)
        read(stream(0, 0, 0, 0, *events), direct)
        val keys = HeldInput(shared::key)
        val buttons = HeldInput(shared::button)
        DesktopUinputDevice(null, null, Recorder(), keys, buttons, 1).event(EV_KEY, 50, 1)
        UinputDesktopComponent.read(stream(0, 0, 0, 0, *events), shared, keys, buttons, 2)
        assertEquals(listOf("key 50 true") + direct.calls, shared.calls)
    }

    @Test fun sharedHoldsDropReleasesNothingHeld() {
        val r = Recorder()
        val keys = HeldInput(r::key)
        val buttons = HeldInput(r::button)
        val keyboard = DesktopUinputDevice(null, null, r, keys, buttons, 1)
        val mouse = DesktopUinputDevice(null, null, r, keys, buttons, 2)
        keyboard.event(EV_KEY, 30, 0)
        mouse.event(EV_KEY, BTN_LEFT, 0)
        keyboard.event(EV_KEY, 30, 1)
        mouse.event(EV_KEY, 30, 0)
        mouse.release()
        keyboard.event(EV_KEY, 30, 0)
        keyboard.event(EV_KEY, 30, 0)
        assertEquals(listOf("key 30 true", "key 30 false"), r.calls)
    }

    @Test fun aStreamWithoutTheHeaderIsIgnored() {
        val r = Recorder()
        read(stream(0, 0, 0, 0, ev(EV_KEY, 30, 1), syn, magic = 0x1234), r)
        assertEquals(emptyList<String>(), r.calls)
    }
}
