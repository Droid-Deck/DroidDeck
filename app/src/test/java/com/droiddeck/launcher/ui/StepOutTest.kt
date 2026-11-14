package com.droiddeck.launcher.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    @Test fun aLeftMenuWithRoomStaysLeft() {
        assertEquals(StepSide.Left, fitStepSide(StepSide.Left, Rect(1900f, 20f, 1980f, 80f), hostWidth = 2400f, wanted = 840f))
    }

    @Test fun aLeftMenuNearTheLeftEdgeOpensRight() {
        assertEquals(StepSide.Right, fitStepSide(StepSide.Left, Rect(300f, 20f, 420f, 80f), hostWidth = 1080f, wanted = 840f))
    }

    @Test fun aRightMenuNearTheRightEdgeOpensLeft() {
        assertEquals(StepSide.Left, fitStepSide(StepSide.Right, Rect(900f, 20f, 1000f, 80f), hostWidth = 1080f, wanted = 840f))
    }

    @Test fun aSqueezedMenuKeepsItsSideWhenTheOtherSideIsNarrower() {
        assertEquals(StepSide.Left, fitStepSide(StepSide.Left, Rect(600f, 20f, 700f, 80f), hostWidth = 1080f, wanted = 840f))
    }

    @Test fun theOutlineReachFollowsTheChosenSide() {
        val wide = Rect(600f, 20f, 700f, 80f)
        assertEquals(600f to 300f, stepReach(StepSide.Left, wide, hostWidth = 1080f, width = 400))
        assertEquals(700f to 1000f, stepReach(StepSide.Right, wide, hostWidth = 1080f, width = 400))
    }

    @Test fun theOutlineReachStaysInsideTheHost() {
        val near = Rect(100f, 20f, 200f, 80f)
        assertEquals(100f to 0f, stepReach(StepSide.Left, near, hostWidth = 1080f, width = 400))
        assertEquals(200f to 1080f, stepReach(StepSide.Right, near, hostWidth = 1080f, width = 1200))
    }

    @Test fun aClosedOutlineIsJustThePill() {
        val path = Path()
        stepPathRight(path, pill, right = pill.right, bottom = pill.bottom, pullTop = pill.bottom + 6f, box = 16f, fillet = 10f, pillCorner = 12f)
        val b = path.getBounds()
        assertEquals(pill.left, b.left, 0.5f)
        assertEquals(pill.right, b.right, 0.5f)
        assertEquals(pill.bottom, b.bottom, 0.5f)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aNearlyClosedOutlineDoesNotDoubleBackPastThePillCorner() {
        val path = Path()
        stepPath(path, pill, left = pill.left - 1f, bottom = pill.bottom + 2f, pullTop = pill.bottom + 6f, box = 16f, fillet = 10f, pillCorner = 12f)
        val length = PathMeasure().apply { setPath(path, false) }.length
        val arcs = (Math.PI / 2).toFloat() * (12f + 16f + 15f + 0.5f + 12f)
        assertEquals(175f + arcs, length, 2f)
    }

    @Test fun aCardMarkIsTakenOnceAndOnlyForItsGame() {
        StoresMotion.markCard(CardMark("gog:1", pill, 10f, null))
        assertNull(StoresMotion.takeCard("gog:2"))
        StoresMotion.markCard(CardMark("gog:1", pill, 10f, null))
        assertNotNull(StoresMotion.takeCard("gog:1"))
        assertNull(StoresMotion.takeCard("gog:1"))
    }
}
