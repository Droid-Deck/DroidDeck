package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceReportCpuTest {
    @Test fun onlyFeatureListsAreReportedAndDifferentCoreCapabilitiesAreKept() {
        val cpuInfo = """
            processor : 0
            Features : fp asimd aes
            Serial : private-device-id
            processor : 1
            Features : fp asimd aes atomics
            processor : 2
            Features : fp asimd aes
        """.trimIndent()
        assertEquals("fp asimd aes | fp asimd aes atomics", DeviceReport.cpuFeaturesSummary(cpuInfo))
        assertEquals("not reported", DeviceReport.cpuFeaturesSummary("processor : 0\n"))
    }
}
