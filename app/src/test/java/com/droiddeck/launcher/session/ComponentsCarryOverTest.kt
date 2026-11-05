package com.droiddeck.launcher.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComponentsCarryOverTest {
    @Test
    fun swappedPackagesFollowTheBuildThatReplacesTheirProton() {
        val state = JSONObject("""
            {"active": {"DroidDeck-Proton_11-11.2": {
                "dxvk": {"file": "dxvk-2.7.wcp", "label": "2.7", "protonVersion": "11-11.2"},
                "vkd3d": {"file": "original 11-11.1", "label": "Original from 11-11.1"},
                "fex": {"file": "gone.wcp", "label": "old"}}},
             "queued": {"DroidDeck-Proton_11-11.2": {
                "vkd3d": {"kind": "package", "file": "vkd3d-3.0.wcp", "label": "3.0"},
                "fex": {"kind": "original", "protonVersion": "11-11.1", "label": "Original 11-11.1"}}}}
        """.trimIndent())
        val carried = ComponentsManager.carried(state, "DroidDeck-Proton_11-11.2") { it != "gone.wcp" }
        assertEquals(setOf("dxvk", "vkd3d"), carried.keys)
        assertEquals("package", carried.getValue("dxvk").getString("kind"))
        assertEquals("dxvk-2.7.wcp", carried.getValue("dxvk").getString("file"))
        assertEquals("2.7", carried.getValue("dxvk").getString("label"))
        assertEquals("vkd3d-3.0.wcp", carried.getValue("vkd3d").getString("file"))
        assertEquals(emptyMap<String, JSONObject>(), ComponentsManager.carried(state, "GE-Proton11-7") { true })
    }
}
