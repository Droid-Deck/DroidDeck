package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HidMouseReportTest {
    private fun bytes(hex: String) = hex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun HidMouseReport.decode(hex: String) = bytes(hex).let { decode(it, it.size) }

    /** A MOSART 2.4G receiver (062a:4c01), as read on a REDMAGIC 10 Pro. */
    private val receiver = HidMouseReport.parse(bytes(
        "05 01 09 02 a1 01 85 01 09 01 a1 00 05 09 19 01 29 05 15 00 25 01 75 01 95 05 81 02 75 03 95 01 81 01 " +
        "05 01 09 30 09 31 15 81 25 7f 75 08 95 02 81 06 09 38 15 81 25 7f 75 08 95 01 81 06 c0 05 0c 15 81 25 7f " +
        "75 08 95 01 0a 38 02 81 06 c0 05 0c 09 01 a1 01 85 03 19 00 2a 80 03 15 00 26 80 03 75 10 95 01 81 00 c0 " +
        "05 01 09 80 a1 01 85 02 19 81 29 83 25 01 75 01 95 03 81 02 95 05 81 01 c0 06 f2 f1 0a f2 f1 a1 01 85 05 " +
        "09 00 95 06 75 08 15 00 26 ff 00 91 02 09 00 95 05 75 08 15 00 26 ff 00 81 02 c0"))!!

    @Test fun readsTheReceiversMouseReport() {
        assertEquals(1, receiver.reportId)
        assertEquals(HidMouseReport.State(0, 5, -7, 0), receiver.decode("01 00 05 f9 00 00"))
        assertEquals(HidMouseReport.State(0, -30, -2, 0), receiver.decode("01 00 e2 fe 00 00"))
    }

    @Test fun readsButtonsAndWheel() {
        assertEquals(HidMouseReport.State(0b10, 0, 0, 0), receiver.decode("01 02 00 00 00 00"))
        assertEquals(HidMouseReport.State(0b11111, 0, 0, 0), receiver.decode("01 1f 00 00 00 00"))
        assertEquals(HidMouseReport.State(0, 0, 0, -1), receiver.decode("01 00 00 00 ff 00"))
    }

    @Test fun ignoresTheReceiversOtherReports() {
        assertNull(receiver.decode("03 e9 00"))
        assertNull(receiver.decode("02 01"))
    }

    @Test fun readsSixteenBitMotionWithoutReportIds() {
        val mouse = HidMouseReport.parse(bytes(
            "05 01 09 02 a1 01 09 01 a1 00 05 09 19 01 29 05 15 00 25 01 95 05 75 01 81 02 95 01 75 03 81 01 " +
            "05 01 16 01 80 26 ff 7f 75 10 95 02 09 30 09 31 81 06 15 81 25 7f 75 08 95 01 09 38 81 06 c0 c0"))
        assertNotNull(mouse)
        assertEquals(0, mouse!!.reportId)
        assertEquals(HidMouseReport.State(1, 300, -100, 1), mouse.decode("01 2c 01 9c ff 01"))
    }

    @Test fun findsNoMouseInAKeyboard() {
        assertNull(HidMouseReport.parse(bytes("05 01 09 06 a1 01 05 07 19 e0 29 e7 15 00 25 01 75 01 95 08 81 02 c0")))
    }

    @Test fun rejectsOutOfRangeReportCountsAndSizes() {
        assertNull(HidMouseReport.parse(bytes(
            "05 01 09 02 a1 01 05 09 19 01 29 03 15 00 25 01 75 01 97 ff ff ff 7f 81 02 c0")))
        assertNull(HidMouseReport.parse(bytes(
            "05 01 09 02 a1 01 05 01 09 30 09 31 15 81 25 7f 77 ff ff ff 7f 95 02 81 06 c0")))
    }

    @Test fun readsTheBootReport() {
        assertEquals(HidMouseReport.State(0b11, -6, 2, 0), HidMouseReport.BOOT.decode("03 fa 02"))
    }
}
