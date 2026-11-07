package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherRoundedCornersTest {
    @Test fun noCornerInformationLeavesTheLauncherEdgeToEdge() {
        assertEquals(FullscreenSafeInsets(), roundedCornerSafeInsets(1_080, 2_400, emptyList()))
    }

    @Test fun asymmetricCornersReserveEachAffectedEdge() {
        val result = roundedCornerSafeInsets(1_080, 2_400, listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 48, 48, 48),
            FullscreenRoundedCorner(FullscreenCorner.TOP_RIGHT, 64, 1_016, 64),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_RIGHT, 72, 1_008, 2_328),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_LEFT, 56, 56, 2_344),
        ))

        assertEquals(FullscreenSafeInsets(left = 17, top = 19, right = 22, bottom = 22), result)
    }

    @Test fun aCornerOutsideTheCurrentWindowDoesNotCreatePaddingAfterAWindowMove() {
        val result = roundedCornerSafeInsets(1_080, 2_400, listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 48, 48, -300),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_RIGHT, 64, 1_016, 2_336),
        ))

        assertEquals(FullscreenSafeInsets(right = 19, bottom = 19), result)
    }

    @Test fun pixelRoundedCornersUseTheArcBoundaryInsteadOfTheFullRadius() {
        val result = roundedCornerSafeInsets(2_992, 1_344, listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 153, 153, 153),
            FullscreenRoundedCorner(FullscreenCorner.TOP_RIGHT, 153, 2_839, 153),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_RIGHT, 153, 2_839, 1_191),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_LEFT, 153, 153, 1_191),
        ))

        assertEquals(FullscreenSafeInsets(left = 45, top = 45, right = 45, bottom = 45), result)
    }

    @Test fun pixelRoundedCornersRotateWithTheWindow() {
        val result = roundedCornerSafeInsets(1_344, 2_992, listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 153, 153, 153),
            FullscreenRoundedCorner(FullscreenCorner.TOP_RIGHT, 153, 1_191, 153),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_RIGHT, 153, 1_191, 2_839),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_LEFT, 153, 153, 2_839),
        ))

        assertEquals(FullscreenSafeInsets(left = 45, top = 45, right = 45, bottom = 45), result)
    }

    @Test fun landscapeSystemBarsLeaveOnlyTheExposedRoundedEdgesInset() {
        val result = roundedCornerSafeInsets(2_912, 1_264, listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 153, 153, 73),
            FullscreenRoundedCorner(FullscreenCorner.TOP_RIGHT, 153, 2_839, 73),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_RIGHT, 153, 2_839, 1_111),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_LEFT, 153, 153, 1_111),
        ))

        // The 80 px status and navigation bars already clear the top and right arcs. The left and
        // bottom edges still touch the physical corners, so they use the 45-degree boundary.
        assertEquals(FullscreenSafeInsets(left = 45, bottom = 45), result)
    }

    @Test fun aWindowOutsideTheRoundedQuadrantDoesNotReserveAnEdge() {
        val result = roundedCornerSafeInsets(800, 600, listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 100, -20, 100),
            FullscreenRoundedCorner(FullscreenCorner.TOP_RIGHT, 100, 820, 100),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_RIGHT, 100, 820, 500),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_LEFT, 100, -20, 500),
        ))

        assertEquals(FullscreenSafeInsets(), result)
    }

    @Test fun aCornerInsideTheWindowDoesNotReserveAnUnrelatedOuterEdge() {
        val result = roundedCornerSafeInsets(800, 600, listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 40, 100, 100),
        ))

        assertEquals(FullscreenSafeInsets(), result)
    }

    @Test fun invalidWindowAndCornerGeometryDoesNotCreatePadding() {
        val corners = listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 100, 100, 100),
            FullscreenRoundedCorner(FullscreenCorner.TOP_RIGHT, 0, 800, 0),
        )

        assertEquals(FullscreenSafeInsets(), roundedCornerSafeInsets(0, 600, corners))
        assertEquals(FullscreenSafeInsets(), roundedCornerSafeInsets(800, -1, corners))
        assertEquals(FullscreenSafeInsets(), roundedCornerSafeInsets(800, 600, corners.drop(1)))
    }

    @Test fun safeContentSizeSubtractsAllInsetsWithoutGoingNegative() {
        assertEquals(900 to 2_280, safeContentSize(
            1_080, 2_400, FullscreenSafeInsets(left = 80, top = 40, right = 100, bottom = 80),
        ))
        assertEquals(0 to 0, safeContentSize(
            100, 100, FullscreenSafeInsets(left = 80, top = 60, right = 80, bottom = 60),
        ))
    }

    @Test fun pipAndOpticalViewportsStayFullFrame() {
        assertEquals(true, fullscreenSafeInsetsEnabled(inPictureInPicture = false, opticalViewport = false))
        assertEquals(false, fullscreenSafeInsetsEnabled(inPictureInPicture = true, opticalViewport = false))
        assertEquals(false, fullscreenSafeInsetsEnabled(inPictureInPicture = false, opticalViewport = true))
    }

    @Test fun windowTouchesMapOnlyInsideAllFourSurfaceEdges() {
        assertEquals(0f to 0f, surfaceLocalPoint(40f, 60f, 40, 60, 900, 500))
        assertEquals(899.5f to 499.5f, surfaceLocalPoint(939.5f, 559.5f, 40, 60, 900, 500))
        assertEquals(null, surfaceLocalPoint(39.9f, 60f, 40, 60, 900, 500))
        assertEquals(null, surfaceLocalPoint(940f, 60f, 40, 60, 900, 500))
        assertEquals(null, surfaceLocalPoint(40f, 59.9f, 40, 60, 900, 500))
        assertEquals(null, surfaceLocalPoint(40f, 560f, 40, 60, 900, 500))
    }

}
