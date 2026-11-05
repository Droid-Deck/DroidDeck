package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SeededMacTest {
    @Test
    fun theAndroidIdSeedsAStableLocalMac() {
        val mac = LinuxNetworkLinkComponent.seededMac { "0123456789abcdef" }
        assertEquals(mac, LinuxNetworkLinkComponent.seededMac { "0123456789abcdef" })
        assertNotEquals(mac, LinuxNetworkLinkComponent.seededMac { null })
        assertEquals(0x02, mac.substringBefore(':').toInt(16) and 0x03)
    }

    @Test
    fun aRomThatThrowsOnTheAndroidIdReadFallsBackToTheDefaultSeed() {
        val mac = LinuxNetworkLinkComponent.seededMac { throw IllegalArgumentException("Illegal char: - at offset 8") }
        assertEquals(LinuxNetworkLinkComponent.seededMac { null }, mac)
    }
}
