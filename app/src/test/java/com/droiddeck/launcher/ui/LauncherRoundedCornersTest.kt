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

        assertEquals(FullscreenSafeInsets(left = 56, top = 64, right = 72, bottom = 72), result)
    }

    @Test fun aCornerOutsideTheCurrentWindowDoesNotCreatePaddingAfterAWindowMove() {
        val result = roundedCornerSafeInsets(1_080, 2_400, listOf(
            FullscreenRoundedCorner(FullscreenCorner.TOP_LEFT, 48, 48, -300),
            FullscreenRoundedCorner(FullscreenCorner.BOTTOM_RIGHT, 64, 1_016, 2_336),
        ))

        assertEquals(FullscreenSafeInsets(right = 64, bottom = 64), result)
    }

    @Test fun cutoutAndRoundedCornerInsetsUseTheLargerSafeDistance() {
        val result = FullscreenSafeInsets(left = 28, top = 96, right = 28)
            .maxWith(FullscreenSafeInsets(left = 48, top = 40, right = 64, bottom = 48))

        assertEquals(FullscreenSafeInsets(left = 48, top = 96, right = 64, bottom = 48), result)
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
