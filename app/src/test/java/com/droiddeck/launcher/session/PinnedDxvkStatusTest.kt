package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedDxvkStatusTest {
    private val pinned = "dxvk-gplasync-2.7.1-1-linux.wcp"
    private val base = "dxvk-2.7.1-linux.wcp"
    private fun choice(file: String, version: String, auto: Boolean = true) = mapOf("dxvk" to ComponentsManager.Choice(file, auto, version))

    @Test fun thePinIsHeldOnlyWhenEveryFollowingProtonRunsIt() {
        val followers = listOf("a" to "10.0-1", "b" to "10.0-2")
        assertTrue(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "10.0-1"), "b" to choice(pinned, "10.0-2")), base))
        assertFalse(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "10.0-1")), base))
        assertFalse(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "10.0-1"), "b" to choice("DD-dxvk-arm64ec-3.1.1.wcp", "10.0-2")), base))
        assertFalse(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "9.0-1"), "b" to choice(pinned, "10.0-2")), base))
    }

    @Test fun noFollowingProtonMeansNothingToReport() {
        assertTrue(ComponentUpdater.pinnedHeld(pinned, emptyList(), emptyMap(), base))
    }

    @Test fun aDxvkThePlayerPickedIsTheirChoiceNotAFailedInstall() {
        val followers = listOf("a" to "10.0-1", "b" to "10.0-2")
        val own = choice("DD-dxvk-arm64ec-3.1.1.wcp", "10.0-2", auto = false)
        assertTrue(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(pinned, "10.0-1"), "b" to own), base))
        assertTrue(ComponentUpdater.ownChoice(own.getValue("dxvk"), "10.0-2", pinned, base))
        assertFalse(ComponentUpdater.ownChoice(own.getValue("dxvk"), "10.0-3", pinned, base))
        assertFalse(ComponentUpdater.ownChoice(choice("DD-dxvk-arm64ec-3.1.1.wcp", "10.0-2").getValue("dxvk"), "10.0-2", pinned, base))
    }

    @Test fun theForcedDxvkThePinReplacesStillCountsAsNotInstalled() {
        val followers = listOf("a" to "10.0-1")
        assertFalse(ComponentUpdater.pinnedHeld(pinned, followers, mapOf("a" to choice(base, "10.0-1", auto = false)), base))
    }
}
