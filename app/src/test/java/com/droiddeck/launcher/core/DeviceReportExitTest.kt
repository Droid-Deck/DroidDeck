package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReportExitTest {
    @Test fun knownReasonCodesHaveTheirPlatformNames() {
        assertEquals("EXIT_SELF", DeviceReport.exitReasonName(1))
        assertEquals("LOW_MEMORY", DeviceReport.exitReasonName(3))
        assertEquals("CRASH_NATIVE", DeviceReport.exitReasonName(5))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", DeviceReport.exitReasonName(9))
        assertEquals("FREEZER", DeviceReport.exitReasonName(14))
    }

    @Test fun anUnknownCodeStillShowsItsNumber() {
        assertEquals("REASON_99", DeviceReport.exitReasonName(99))
        assertEquals("REASON_-1", DeviceReport.exitReasonName(-1))
    }

    @Test fun aSummaryNamesTheProcessReasonAndStatus() {
        val line = DeviceReport.exitSummary("com.droiddeck.launcher", 3, 0, "  low mem & is ok  ")
        assertTrue(line, line.startsWith("com.droiddeck.launcher  LOW_MEMORY  status=0"))
        assertTrue(line, line.endsWith("low mem & is ok"))
        assertEquals("?  UNKNOWN  status=0", DeviceReport.exitSummary(null, 0, 0, null))
        assertEquals("p  SIGNALED  status=9", DeviceReport.exitSummary("p", 2, 9, "   "))
    }
}