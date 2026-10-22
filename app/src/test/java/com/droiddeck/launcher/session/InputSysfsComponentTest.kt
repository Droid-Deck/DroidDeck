package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.FakeInputWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSysfsComponentTest {
    @Test fun steamInputsVirtualPadKeepsItsIdentity() {
        val pad = InputSysfsComponent.virtualPad("PRODUCT=3/28de/11ff/1\nNAME=\"Microsoft X-Box 360 pad 0\"\nPHYS=\"\"\n")!!
        assertEquals(listOf(3, 0x28de, 0x11ff, 1), listOf(pad.bus, pad.vendor, pad.product, pad.version))
        assertEquals("Microsoft X-Box 360 pad 0", pad.name)
        assertEquals("", pad.phys)
        assertTrue(pad.uevent().startsWith("PRODUCT=3/28de/11ff/1\nNAME=\"Microsoft X-Box 360 pad 0\"\n"))
    }

    @Test fun anUnreadableDescriptionIsNotListed() {
        assertNull(InputSysfsComponent.virtualPad(""))
        assertNull(InputSysfsComponent.virtualPad("PRODUCT=3/28de\nNAME=\"pad\"\n"))
        assertNull(InputSysfsComponent.virtualPad("PRODUCT=3/28de/11ff/1\n"))
    }

    @Test fun ringPadsMatchWhatTheirIoctlsAnswer() {
        val xbox = InputSysfsComponent.ringPad(1, steamVirtual = false)
        assertEquals("Xbox 360 Controller (1)", xbox.name)
        assertEquals(listOf(0x045e, 0x028e, 0x110), listOf(xbox.vendor, xbox.product, xbox.version))
        val steam = InputSysfsComponent.ringPad(0, steamVirtual = true)
        assertEquals("Microsoft X-Box 360 pad 0", steam.name)
        assertEquals(listOf(0x28de, 0x11ff), listOf(steam.vendor, steam.product))
    }

    @Test fun ringPadsAreHiddenWhereGamesCannotOpenThem() {
        assertFalse(InputSysfsComponent.ringWithdrawn(deckPad = false, type = FakeInputWriter.PAD_TYPE_DECK))
        assertFalse(InputSysfsComponent.ringWithdrawn(deckPad = true, type = FakeInputWriter.PAD_TYPE_XBOX))
        assertTrue(InputSysfsComponent.ringWithdrawn(deckPad = true, type = FakeInputWriter.PAD_TYPE_DECK))
        assertTrue(InputSysfsComponent.ringWithdrawn(deckPad = true, type = -1))
    }

    @Test fun onlyThePadsOwnUdevRecordsAreCleared() {
        assertTrue(InputSysfsComponent.isPadRecord("c13:64"))
        assertTrue(InputSysfsComponent.isPadRecord("c13:80"))
        assertTrue(InputSysfsComponent.isPadRecord("+input:input16"))
        assertFalse(InputSysfsComponent.isPadRecord("c13:63"))
        assertFalse(InputSysfsComponent.isPadRecord("c13:128"))
        assertFalse(InputSysfsComponent.isPadRecord("c240:16"))
        assertFalse(InputSysfsComponent.isPadRecord("+input:input64"))
    }

    @Test fun theUdevRecordMarksAnInitializedJoystick() {
        val record = InputSysfsComponent.udevRecord(42).lines()
        assertEquals("I:42", record.first())
        assertTrue("E:ID_INPUT_JOYSTICK=1" in record)
        assertTrue("E:ID_INPUT=1" in record)
    }
}
