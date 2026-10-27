package com.droiddeck.launcher.session

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ComponentsSelectionTest {
    private val proton = ComponentsManager.Proton("DroidDeck-Proton_11-11.1", "DroidDeck-Proton 11-11.1", File("/p"), "/p", "DroidDeck-Proton-11-11.1", false)
    private val entry = JSONObject().put("file", "dxvk-low-latency-arm64ec-3.1.1-2-linux.wcp").put("protonVersion", "DroidDeck-Proton-11-11.1")

    @Test
    fun `a package swapped into an installed Proton is in use`() {
        assertTrue(ComponentsManager.liveSelection("active", entry, proton))
        assertTrue(ComponentsManager.liveSelection("queued", JSONObject().put("file", "x.wcp"), proton))
    }

    @Test
    fun `a package left on a removed Proton is not in use`() {
        assertFalse(ComponentsManager.liveSelection("active", entry, null))
        assertFalse(ComponentsManager.liveSelection("queued", entry, null))
    }

    @Test
    fun `a package from before a Proton update is not in use`() {
        assertFalse(ComponentsManager.liveSelection("active", JSONObject(entry.toString()).put("protonVersion", "DroidDeck-Proton-11-9.1"), proton))
    }
}
