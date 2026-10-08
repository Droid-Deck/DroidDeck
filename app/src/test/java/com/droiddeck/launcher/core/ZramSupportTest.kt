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
    fun swapTotalComesFromMeminfo() {
        assertEquals(12582908L, ZramSupport.swapTotalKb(listOf("MemTotal:       15600380 kB", "SwapTotal:      12582908 kB")))
        assertEquals(0L, ZramSupport.swapTotalKb(listOf("SwapTotal:             0 kB")))
        assertEquals(0L, ZramSupport.swapTotalKb(emptyList()))
    }
}
