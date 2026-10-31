package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomDashboardTest {
    @Test fun theRememberedPageOpensAgain() {
        assertEquals("shots", pickBottomPage("shots", listOf("downloads", "shots")))
    }

    @Test fun aGoneOrMissingPageFallsBackToTheFirst() {
        assertEquals("downloads", pickBottomPage("gone", listOf("downloads", "shots")))
        assertEquals("downloads", pickBottomPage(null, listOf("downloads", "shots")))
        assertNull(pickBottomPage("shots", emptyList()))
    }

    @Test fun tabNamesShowOnlyWhileEveryTabFits() {
        // Retroid Pocket Duo bottom panel: 1280x960 at 320 dpi = 640 dp wide.
        assertTrue(bottomTabsWithLabels(640f, 4))
        assertFalse(bottomTabsWithLabels(640f, 5))
        assertFalse(bottomTabsWithLabels(360f, 3))
    }
}

class BottomDownloadsRateTest {
    @Test fun rateIsBytesPerSecondBetweenReads() {
        assertEquals(1_000_000L, downloadRate(0L, 0L, 2_000_000L, 2_000L))
    }

    @Test fun noMovementOrARestartIsZero() {
        assertEquals(0L, downloadRate(500L, 0L, 500L, 2_000L))
        assertEquals(0L, downloadRate(500L, 0L, 100L, 2_000L))
        assertEquals(0L, downloadRate(-1L, 0L, 100L, 2_000L))
    }
}
