package com.droiddeck.launcher.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StepOutTest {
    private val pill = Rect(400f, 100f, 500f, 140f)

    @Test fun leftOutlineRunsFromTheBoxLeftToThePillRight() {
        val path = Path()
        stepPath(path, pill, left = 240f, bottom = 300f, pullTop = 146f, box = 16f, fillet = 10f)
        val b = path.getBounds()
        assertEquals(240f, b.left, 0.5f)
        assertEquals(500f, b.right, 0.5f)
        assertEquals(100f, b.top, 0.5f)
        assertEquals(300f, b.bottom, 0.5f)
    }

    @Test fun rightOutlineMirrorsItAboutThePill() {
        val path = Path()
        stepPathRight(path, pill, right = 660f, bottom = 300f, pullTop = 146f, box = 16f, fillet = 10f)
        val b = path.getBounds()
        assertEquals(400f, b.left, 0.5f)
        assertEquals(660f, b.right, 0.5f)
        assertEquals(100f, b.top, 0.5f)
        assertEquals(300f, b.bottom, 0.5f)
    }

    @Test fun aClosedOutlineIsJustThePill() {
        val path = Path()
        stepPathRight(path, pill, right = pill.right, bottom = pill.bottom, pullTop = pill.bottom + 6f, box = 16f, fillet = 10f, pillCorner = 12f)
        val b = path.getBounds()
        assertEquals(pill.left, b.left, 0.5f)
        assertEquals(pill.right, b.right, 0.5f)
        assertEquals(pill.bottom, b.bottom, 0.5f)
    }

    @Test fun aCardMarkIsTakenOnceAndOnlyForItsGame() {
        StoresMotion.markCard(CardMark("gog:1", pill, 10f, null))
        assertNull(StoresMotion.takeCard("gog:2"))
        StoresMotion.markCard(CardMark("gog:1", pill, 10f, null))
        assertNotNull(StoresMotion.takeCard("gog:1"))
        assertNull(StoresMotion.takeCard("gog:1"))
    }
}
