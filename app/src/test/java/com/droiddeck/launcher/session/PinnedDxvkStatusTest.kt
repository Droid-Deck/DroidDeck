package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedDxvkStatusTest {
    private val pinned = "dxvk-gplasync-2.7.1-1-linux.wcp"
    private fun choice(file: String, version: String) = mapOf("dxvk" to ComponentsManager.Choice(file, true, version))

    @Test fun thePinIsHeldOnlyWhenEveryFollowingProtonRunsIt() {
        val followers = listOf("a" to "10.0-1", "b" to "10.0-2")
        assertTrue(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "10.0-1"), "b" to choice(pinned, "10.0-2"))))
        assertFalse(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "10.0-1"))))
        assertFalse(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "10.0-1"), "b" to choice("DD-dxvk-arm64ec-3.1.1.wcp", "10.0-2"))))
        assertFalse(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "9.0-1"), "b" to choice(pinned, "10.0-2"))))
    }

    @Test fun noFollowingProtonMeansNothingToReport() {
        assertTrue(ComponentUpdater.pinnedHeld(pinned, emptyList(), emptyMap()))
    }
}
