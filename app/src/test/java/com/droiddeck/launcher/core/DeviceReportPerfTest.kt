package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceReportPerfTest {
    @Test fun thermalStatusesHaveTheirPlatformNames() {
        assertEquals("NONE", DeviceReport.thermalStatusName(0))
        assertEquals("MODERATE", DeviceReport.thermalStatusName(2))
        assertEquals("CRITICAL", DeviceReport.thermalStatusName(4))
        assertEquals("SHUTDOWN", DeviceReport.thermalStatusName(6))
        assertEquals("STATUS_9", DeviceReport.thermalStatusName(9))
    }

    @Test fun gameModesHaveTheirPlatformNames() {
        assertEquals("UNSUPPORTED", DeviceReport.gameModeName(0))
        assertEquals("STANDARD", DeviceReport.gameModeName(1))
        assertEquals("PERFORMANCE", DeviceReport.gameModeName(2))
        assertEquals("BATTERY", DeviceReport.gameModeName(3))
        assertEquals("MODE_7", DeviceReport.gameModeName(7))
    }

    @Test fun refreshModesAreRoundedDeduplicatedAndListedFastestFirst() {
        assertEquals("120 Hz, 60 Hz", DeviceReport.refreshModesSummary(listOf(60.0f, 120.00001f, 60.00001f)))
        assertEquals("60 Hz", DeviceReport.refreshModesSummary(listOf(59.94f)))
        assertEquals("", DeviceReport.refreshModesSummary(emptyList()))
    }
}
