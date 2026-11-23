package com.droiddeck.launcher.input

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "xhdpi")
class InputSurfaceToolsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val density get() = context.resources.displayMetrics.density
    private val w = 1920f
    private val h = 1080f
    private val margin get() = 8 * density

    private val sw = 400f
    private val sh = 150f
    private val gap = 16f

    private fun sliders(outline: SurfaceBounds, corners: List<SurfaceBounds> = emptyList()) =
        placeSelectionSliders(w, h, sw, sh, margin, gap, outline, corners)

    private fun Pair<Float, Float>.box() = SurfaceBounds(first, second, first + sw, second + sh)

    private fun SurfaceBounds.hits(o: SurfaceBounds) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    private fun assertOnScreen(spot: Pair<Float, Float>) {
        assertTrue("$spot", spot.first >= margin && spot.first + sw <= w - margin + .01f)
        assertTrue("$spot", spot.second >= margin && spot.second + sh <= h - margin + .01f)
    }

    @Test fun sizeSitsAboveAndOpacityBelowAMiddleSelection() {
        val outline = SurfaceBounds(860f, 440f, 1060f, 640f)
        val (size, opacity) = sliders(outline)
        assertEquals(outline.centerX - sw / 2, size.first, .01f)
        assertEquals(outline.centerX - sw / 2, opacity.first, .01f)
        assertEquals(outline.top - gap - sh, size.second, .01f)
        assertEquals(outline.bottom + gap, opacity.second, .01f)
    }

    @Test fun atTheTopEdgeBothGoBelowSizeFirst() {
        val outline = SurfaceBounds(860f, 20f, 1060f, 220f)
        val (size, opacity) = sliders(outline)
        assertEquals(outline.bottom + gap, size.second, .01f)
        assertEquals(size.second + sh + gap, opacity.second, .01f)
        assertOnScreen(size); assertOnScreen(opacity)
    }

    @Test fun atTheBottomEdgeBothGoAboveOpacityNearest() {
        val outline = SurfaceBounds(860f, 860f, 1060f, 1060f)
        val (size, opacity) = sliders(outline)
        assertEquals(outline.top - gap - sh, opacity.second, .01f)
        assertEquals(opacity.second - gap - sh, size.second, .01f)
        assertOnScreen(size); assertOnScreen(opacity)
    }

    @Test fun atTheSideEdgesTheSlidersStayOnScreen() {
        val (leftSize, leftOpacity) = sliders(SurfaceBounds(0f, 440f, 120f, 640f))
        assertEquals(margin, leftSize.first, .01f)
        assertEquals(margin, leftOpacity.first, .01f)
        val (rightSize, rightOpacity) = sliders(SurfaceBounds(w - 120f, 440f, w, 640f))
        assertEquals(w - sw - margin, rightSize.first, .01f)
        assertEquals(w - sw - margin, rightOpacity.first, .01f)
    }

    @Test fun aSelectionAsTallAsTheScreenGetsBothBesideItOnTheRoomierSide() {
        val outline = SurfaceBounds(300f, 0f, 700f, h)
        val (size, opacity) = sliders(outline)
        assertEquals(outline.right + gap, size.first, .01f)
        assertEquals(size.first, opacity.first, .01f)
        assertEquals(size.second + sh + gap, opacity.second, .01f)
        assertFalse(size.box().hits(outline)); assertFalse(opacity.box().hits(outline))
        assertOnScreen(size); assertOnScreen(opacity)
    }

    @Test fun aSelectionFillingTheScreenGetsThemOverItAsALastResort() {
        val outline = SurfaceBounds(0f, 0f, w, h)
        val (size, opacity) = sliders(outline)
        assertOnScreen(size); assertOnScreen(opacity)
        assertEquals(size.first, opacity.first, .01f)
        assertTrue(size.second < opacity.second)
        assertFalse(size.box().hits(opacity.box()))
    }

    @Test fun theSlidersKeepClearOfTheBadgeAndCornerHandles() {
        val reach = 40f
        for (outline in listOf(SurfaceBounds(900f, 440f, 1020f, 640f), SurfaceBounds(700f, 440f, 1220f, 640f), SurfaceBounds(0f, 20f, 160f, 200f))) {
            val corners = listOf(outline.left to outline.top, outline.right to outline.top, outline.left to outline.bottom, outline.right to outline.bottom)
                .map { (x, y) -> SurfaceBounds(x - reach, y - reach, x + reach, y + reach) }
            val (size, opacity) = sliders(outline, corners)
            for (spot in listOf(size, opacity)) {
                assertOnScreen(spot)
                assertFalse("$outline $spot", spot.box().hits(outline))
                for (c in corners) assertFalse("$outline $spot $c", spot.box().hits(c))
            }
        }
    }

    @Test fun aSideShiftThatClampsBackOntoTheCornersGoesOverTheSelectionInstead() {
        val outline = SurfaceBounds(400f, 100f, 1488f, 380f)
        val corners = listOf(outline.left to outline.top, outline.right to outline.top, outline.left to outline.bottom, outline.right to outline.bottom)
            .map { (x, y) -> SurfaceBounds(x - 36f, y - 36f, x + 36f, y + 36f) }
        val spots = placeSelectionSliders(1920f, 480f, sw, sh, 16f, 16f, outline, corners)
        for (spot in listOf(spots.size, spots.opacity)) for (c in corners) assertFalse("$spot $c", spot.box().hits(c))
        assertEquals(outline.centerX - sw / 2, spots.size.first, .01f)
    }

    @Test fun aSelectionUnderTheMenuHandleKeepsItsSlidersClearOfIt() {
        val handle = SurfaceBounds(w / 2 - 48f, margin, w / 2 + 48f, margin + 96f)
        val under = SurfaceBounds(860f, 200f, 1060f, 400f)
        val (size, opacity) = sliders(under, listOf(handle))
        for (spot in listOf(size, opacity)) { assertOnScreen(spot); assertFalse("$spot", spot.box().hits(handle)); assertFalse(spot.box().hits(under)) }
        assertTrue(size.second >= under.bottom && opacity.second > size.second)
        val beside = SurfaceBounds(1000f, 200f, 1200f, 400f)
        val shifted = sliders(beside, listOf(handle)).size
        assertFalse("$shifted", shifted.box().hits(handle))
        assertTrue(shifted.second + sh <= beside.top)
    }

    @Test fun aSliderPushedUpPastACornerStillKeepsClearOfTheMenu() {
        val outline = SurfaceBounds(0f, 372f, 400f, 620f)
        val corner = SurfaceBounds(364f, 336f, 436f, 408f)
        val menu = SurfaceBounds(180f, 16f, 620f, 200f)
        val (size, opacity) = placeSelectionSliders(800f, 1080f, sw, sh, 16f, 16f, outline, listOf(corner, menu))
        for (spot in listOf(size, opacity)) for (o in listOf(outline, corner, menu)) assertFalse("$spot $o", spot.box().hits(o))
        assertTrue(size.second >= outline.bottom && opacity.second > size.second)
    }

    @Test fun aNewControlLandsInTheFreeSpotInsideTheSafeArea() {
        val area = SurfaceBounds(.1f, 0f, 1f, 1f)
        val (x, y) = freeSurfaceSpot(listOf(SurfaceBounds(.1f, 0f, .91f, 1f)), .08f, .16f, area)
        assertTrue("$x", x >= .91f && x + .08f <= area.right + 1e-4f)
        assertTrue("$y", y >= 0f && y + .16f <= 1f + 1e-4f)
    }

    @Test fun aNewControlFitsTheNarrowGapBetweenTwoControls() {
        val (x, _) = freeSurfaceSpot(listOf(SurfaceBounds(.1f, 0f, .4f, 1f), SurfaceBounds(.5f, 0f, 1f, 1f)), .09f, .16f, SurfaceBounds(.1f, 0f, 1f, 1f))
        assertTrue("$x", x >= .4f - 1e-4f && x + .09f <= .5f + 1e-4f)
    }
}
