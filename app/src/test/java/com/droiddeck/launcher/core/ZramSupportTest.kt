package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZramSupportTest {
    @Test
    fun kernelVersionsFromAndroidReleases() {
        assertTrue(ZramSupport.kernelAtLeast("6.12.38-android16-5-g665eafb62659-ab14778838-4k", 5, 4))
        assertTrue(ZramSupport.kernelAtLeast("5.4.0", 5, 4))
        assertFalse(ZramSupport.kernelAtLeast("4.19.157-perf+", 5, 4))
        assertFalse(ZramSupport.kernelAtLeast("", 5, 4))
    }

    @Test
    fun underTwelveGbFromAdvertisedOrTotalMemory() {
        val gb = 1_000_000_000L
        val gib = 1L shl 30
        assertTrue(ZramSupport.belowTwelveGb(8 * gb, 0))
        assertTrue(ZramSupport.belowTwelveGb(8 * gib, 0))
        assertFalse(ZramSupport.belowTwelveGb(12 * gb, 0))
        assertFalse(ZramSupport.belowTwelveGb(12 * gib, 0))
        assertTrue(ZramSupport.belowTwelveGb(0, 7_400_000L * 1024))
        assertTrue(ZramSupport.belowTwelveGb(0, 9_300_000L * 1024))
        assertFalse(ZramSupport.belowTwelveGb(0, 11_100_000L * 1024))
        assertFalse(ZramSupport.belowTwelveGb(0, 15_600_380L * 1024))
    }

    @Test
    fun swapTotalComesFromMeminfo() {
        assertEquals(12582908L, ZramSupport.swapTotalKb(listOf("MemTotal:       15600380 kB", "SwapTotal:      12582908 kB")))
        assertEquals(0L, ZramSupport.swapTotalKb(listOf("SwapTotal:             0 kB")))
        assertEquals(0L, ZramSupport.swapTotalKb(emptyList()))
    }
}
