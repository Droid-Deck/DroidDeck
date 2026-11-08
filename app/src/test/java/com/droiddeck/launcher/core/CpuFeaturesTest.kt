package com.droiddeck.launcher.core

import com.droiddeck.launcher.session.SessionFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuFeaturesTest {
    @Test fun anArmv80CpuLacksAtomics() {
        val cpuInfo = """
            processor : 0
            Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid
            processor : 1
            Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid
        """.trimIndent()
        assertTrue(CpuFeatures.lacksAtomics(cpuInfo))
    }

    @Test fun oneCoreWithoutAtomicsIsEnough() {
        val cpuInfo = """
            processor : 0
            Features : fp asimd atomics
            processor : 4
            Features : fp asimd
        """.trimIndent()
        assertTrue(CpuFeatures.lacksAtomics(cpuInfo))
    }

    @Test fun anArmv82CpuHasAtomics() {
        val cpuInfo = """
            processor : 0
            Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        """.trimIndent()
        assertFalse(CpuFeatures.lacksAtomics(cpuInfo))
    }

    @Test fun noFeatureLinesMeansNoEmulation() {
        assertFalse(CpuFeatures.lacksAtomics("processor : 0\n"))
        assertFalse(CpuFeatures.lacksAtomics(""))
    }

    @Test fun theEmulatorIsPreloadedFirstOnlyWhenNeededAndStaged() {
        assertEquals("/usr/local/lib/droiddeck-lse/liblse_emu.so\n", SessionFiles.lsePreload(lacksAtomics = true, staged = true))
        assertEquals("", SessionFiles.lsePreload(lacksAtomics = true, staged = false))
        assertEquals("", SessionFiles.lsePreload(lacksAtomics = false, staged = true))
    }
}
