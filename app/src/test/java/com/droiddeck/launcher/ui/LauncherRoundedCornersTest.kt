package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherRoundedCornersTest {
    @Test fun noCornerInformationLeavesTheLauncherEdgeToEdge() {
        assertEquals(LauncherSafeInsets(), roundedCornerSafeInsets(1_080, 2_400, emptyList()))
    }

    @Test fun asymmetricCornersReserveEachAffectedEdge() {
        val result = roundedCornerSafeInsets(1_080, 2_400, listOf(
            LauncherRoundedCorner(LauncherCorner.TOP_LEFT, 48, 48, 48),
            LauncherRoundedCorner(LauncherCorner.TOP_RIGHT, 64, 1_016, 64),
            LauncherRoundedCorner(LauncherCorner.BOTTOM_RIGHT, 72, 1_008, 2_328),
            LauncherRoundedCorner(LauncherCorner.BOTTOM_LEFT, 56, 56, 2_344),
        ))

        assertEquals(LauncherSafeInsets(left = 56, top = 64, right = 72, bottom = 72), result)
    }

    @Test fun aCornerOutsideTheCurrentWindowDoesNotCreatePaddingAfterAWindowMove() {
        val result = roundedCornerSafeInsets(1_080, 2_400, listOf(
            LauncherRoundedCorner(LauncherCorner.TOP_LEFT, 48, 48, -300),
            LauncherRoundedCorner(LauncherCorner.BOTTOM_RIGHT, 64, 1_016, 2_336),
        ))

        assertEquals(LauncherSafeInsets(right = 64, bottom = 64), result)
    }

    @Test fun cutoutAndRoundedCornerInsetsUseTheLargerSafeDistance() {
        val result = LauncherSafeInsets(left = 28, top = 96, right = 28)
            .maxWith(LauncherSafeInsets(left = 48, top = 40, right = 64, bottom = 48))

        assertEquals(LauncherSafeInsets(left = 48, top = 96, right = 64, bottom = 48), result)
    }
}
