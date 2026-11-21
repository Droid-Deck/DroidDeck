package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FexHostTest {
    @Test fun aKernelWithoutIdRegisterEmulationHidesCpuFeatures() {
        val cpuInfo = """
            processor : 0
            Features : fp asimd evtstrm aes pmull sha1 sha2 crc32
            processor : 4
            Features : fp asimd evtstrm aes pmull sha1 sha2 crc32
        """.trimIndent()
        assertTrue(FexHost.kernelHidesCpuFeatures(cpuInfo))
    }

    @Test fun anArmv80CpuOnANewerKernelStillExposesThem() {
        val cpuInfo = "processor : 0\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid\n"
        assertFalse(FexHost.kernelHidesCpuFeatures(cpuInfo))
    }

    @Test fun everyCoreMustReportCpuid() {
        val cpuInfo = "processor : 0\nFeatures : fp asimd atomics cpuid\nprocessor : 1\nFeatures : fp asimd atomics\n"
        assertTrue(FexHost.kernelHidesCpuFeatures(cpuInfo))
    }

    @Test fun onlyTheExactFlagCounts() {
        assertTrue(FexHost.kernelHidesCpuFeatures("Features : fp nocpuid cpuid_x\n"))
    }

    @Test fun missingFeatureListsKeepFexAvailable() {
        assertFalse(FexHost.kernelHidesCpuFeatures("processor : 0\n"))
        assertFalse(FexHost.kernelHidesCpuFeatures(""))
    }
}
