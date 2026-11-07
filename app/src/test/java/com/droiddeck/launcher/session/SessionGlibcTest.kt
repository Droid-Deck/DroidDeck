package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionGlibcTest {
    @Test fun systemBridgeReservesTlsWithoutReplacingExistingTunables() {
        val entries = sessionGlibcTunables(systemVulkan = true).split(':')
        val tunables = entries.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("tunables must not contain duplicate keys", entries.size, tunables.size)
        assertEquals("32768", tunables["glibc.rtld.optional_static_tls"])
        assertEquals("0", tunables["glibc.pthread.rseq"])
        assertEquals("16777216", tunables["glibc.malloc.top_pad"])
        assertEquals(sessionGlibcTunables(systemVulkan = false),
            entries.filterNot { it.startsWith("glibc.rtld.optional_static_tls=") }.joinToString(":"))
    }

    @Test fun nonBridgeDriversKeepTheOriginalTlsAllocation() {
        val tunables = sessionGlibcTunables(systemVulkan = false)
        assertFalse(tunables.contains("glibc.rtld.optional_static_tls"))
        assertEquals("glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216", tunables)
    }
}
