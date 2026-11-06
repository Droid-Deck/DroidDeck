package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OverlayShownTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun config(name: String, text: String, modified: Long) =
        File(tmp.root, "tmp").apply { mkdirs() }.resolve(name).apply { writeText(text); setLastModified(modified) }

    @Test fun noConfigIsHidden() {
        assertFalse(OverlayShown.check(tmp.root))
    }

    @Test fun mangohudConfigFollowsNoDisplay() {
        val f = config("mangohud.abcdefgh", "no_display\n", 1_000_000L)
        assertFalse(OverlayShown.check(tmp.root))
        f.writeText("preset=3\n")
        assertTrue(OverlayShown.check(tmp.root))
    }

    @Test fun steamSessionConfigFollowsNoDisplay() {
        val f = config("droiddeck-mangohud.conf", "no_display\n", 1_000_000L)
        assertFalse(OverlayShown.check(tmp.root))
        f.writeText("preset=3\n")
        assertTrue(OverlayShown.check(tmp.root))
    }

    @Test fun newestConfigWins() {
        config("mangohud.abcdefgh", "no_display\n", 1_000_000L)
        config("droiddeck-mangohud.conf", "preset=3\n", 2_000_000L)
        assertTrue(OverlayShown.check(tmp.root))
        config("mangohud.abcdefgh", "no_display\n", 3_000_000L)
        assertFalse(OverlayShown.check(tmp.root))
    }
}
