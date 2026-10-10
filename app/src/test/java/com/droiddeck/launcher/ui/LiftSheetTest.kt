package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiftSheetTest {
    private val fps: (Int) -> String = { "$it fps" }

    @Test fun theLipSaysEverythingThatIsNotDefault() {
        val parts = lipParts(1280 to 720, 60, fps, "FSR 1", "Touchpad")
        assertEquals("1280×720 · 60 fps · FSR 1 · Touchpad", parts.joinToString(" · ") { it.second })
    }

    @Test fun defaultsAreLeftOut() {
        assertEquals(listOf("size" to "1920×1080"), lipParts(1920 to 1080, 0, fps, null, null))
        assertEquals(listOf("size", "touch"), lipParts(1920 to 1080, 0, fps, null, "Touchpad").map { it.first })
    }

    @Test fun nothingIsLitTheFirstTime() {
        assertEquals(emptySet<String>(), lipChanged(null, lipParts(1280 to 720, 60, fps, null, null)))
    }

    @Test fun aChangedOrNewPartIsLit() {
        val seen = lipParts(1280 to 720, 0, fps, null, "Touchpad").toMap()
        assertEquals(setOf("size", "fps"), lipChanged(seen, lipParts(1920 to 1080, 60, fps, null, "Touchpad")))
    }

    @Test fun theSheetTopIs82dpDownA360dpPane() {
        assertEquals(82f, sheetTop(360f, 56f, 16f, 3f), 0.01f)
        assertEquals(164f, sheetTop(720f, 56f, 16f, 3f), 0.01f)
    }

    @Test fun theButtonKeeps16dpAboveIt() {
        // A short pane: the edge comes down so the button riding it still clears the top by 16.
        val top = sheetTop(150f, 56f, 16f, 3f)
        assertEquals(41f, top, 0.01f)
        assertEquals(16f, top + 3f - 56f / 2f, 0.01f)
    }
}
